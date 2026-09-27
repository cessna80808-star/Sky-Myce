package me.mycellium.skymyce.loader;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/** Relaunches Fabric itself, without depending on the launcher that originally started it. */
public final class LoaderRestart {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int READY = 83;
    private LoaderRestart() {}

    // This object contains session credentials. Never include its fields in diagnostic output.
    record Plan(Path directory, List<String> vmArguments, List<Path> classPath,
                Map<String, String> properties, List<String> arguments) {
        @Override public String toString() { return "Private Minecraft restart plan"; }
    }

    public static void request(Process installer, Path directory, List<String> vmArguments, List<Path> classPath,
                               Map<String, String> properties, String[] arguments) throws Exception {
        var plan = new Plan(directory.toAbsolutePath().normalize(), List.copyOf(vmArguments), List.copyOf(classPath),
            new LinkedHashMap<>(properties), List.of(arguments.clone()));
        var prepared = CompletableFuture.runAsync(() -> {
            try {
                write(installer.getOutputStream(), plan);
                if (installer.getInputStream().read() != READY) throw new IOException("Restart was not prepared");
            } catch (IOException failure) { throw new CompletionException(failure); }
        });
        try { prepared.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) { installer.destroyForcibly(); throw new IOException("Restart preparation failed"); }
    }

    public static void commit(Process installer) throws IOException {
        try (var output = installer.getOutputStream()) { output.write(READY); output.flush(); }
    }

    static boolean committed() throws IOException { return System.in.read() == READY; }

    static Plan read(InputStream stream) throws IOException {
        int first = stream.read();
        if (first == -1) return null; // Normal game exit: install the update without restarting.
        var input = new DataInputStream(stream);
        int length = (first << 24) | (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
        if (length < 1 || length > MAX_BYTES) throw new IOException("Invalid restart request size");
        var bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete restart request");
        var data = new DataInputStream(new ByteArrayInputStream(bytes));
        var directory = Path.of(data.readUTF());
        var vm = strings(data);
        var paths = strings(data).stream().map(Path::of).toList();
        var pairs = strings(data);
        if (pairs.size() % 2 != 0) throw new IOException("Invalid restart properties");
        var properties = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.size(); i += 2) properties.put(pairs.get(i), pairs.get(i + 1));
        var arguments = strings(data);
        if (data.available() != 0 || paths.isEmpty() || arguments.isEmpty()) throw new IOException("Invalid restart request");
        return new Plan(directory, vm, paths, properties, arguments);
    }

    static void write(OutputStream stream, Plan plan) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        data.writeUTF(plan.directory().toString());
        strings(data, plan.vmArguments());
        strings(data, plan.classPath().stream().map(Path::toString).toList());
        var properties = new ArrayList<String>();
        plan.properties().forEach((key, value) -> { properties.add(key); properties.add(value); });
        strings(data, properties);
        strings(data, plan.arguments());
        if (bytes.size() > MAX_BYTES) throw new IOException("Restart request too large");
        var output = new DataOutputStream(stream);
        output.writeInt(bytes.size()); bytes.writeTo(output); output.flush();
    }

    private static List<String> strings(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 8192) throw new IOException("Invalid restart argument count");
        var values = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) values.add(input.readUTF());
        return values;
    }
    private static void strings(DataOutputStream output, List<String> values) throws IOException {
        if (values.size() > 8192) throw new IOException("Too many restart arguments");
        output.writeInt(values.size());
        for (var value : values) output.writeUTF(value);
    }

    /** Called in the small installer JVM before acknowledging that Minecraft may close. */
    static Path prepare(Plan plan) throws Exception {
        if (!Files.isDirectory(plan.directory())) throw new IOException("Instance directory unavailable");
        for (var entry : plan.classPath()) if (!Files.exists(entry)) throw new IOException("Runtime library unavailable");
        for (var argument : plan.vmArguments()) {
            int separator = argument.indexOf('=');
            if (argument.startsWith("-D") && separator > 2)
                plan.properties().putIfAbsent(argument.substring(2, separator), argument.substring(separator + 1));
        }
        plan.properties().remove("java.class.path"); plan.properties().remove("sun.java.command");
        plan.properties().putAll(RestartNatives.preserve(plan.directory(), plan.properties()));
        var wrapper = wrapper(plan.directory(), plan.classPath());
        command(wrapper, plan); // Reject an unrepresentable JVM option before the game closes.
        return wrapper;
    }

    static Path wrapper(Path directory, List<Path> classPath) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
            String.join(" ", classPath.stream().map(path -> path.toAbsolutePath().toUri().toASCIIString()).toList()));
        var bytes = new ByteArrayOutputStream();
        // A metadata-free wrapper keeps the updater/mod JAR off Fabric's launch classpath.
        // The manifest holds library paths only; arguments and credentials stay in anonymous pipes.
        try (var jar = new JarOutputStream(bytes)) {
            var metadata = new JarEntry("META-INF/MANIFEST.MF"); metadata.setTime(0);
            jar.putNextEntry(metadata); manifest.write(jar); jar.closeEntry();
            for (var type : List.of(LoaderRestart.class, Plan.class)) {
                var name = type.getName().replace('.', '/') + ".class";
                var entry = new JarEntry(name); entry.setTime(0); jar.putNextEntry(entry);
                try (var input = type.getResourceAsStream("/" + name)) {
                    if (input == null) throw new IOException("Restart helper unavailable");
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
        return cacheJar(directory.resolve(".sky-myce/restart"), bytes.toByteArray());
    }

    /** Temporary launch-agent JARs can disappear when their original launcher observes game exit. */
    static Plan preserveRuntime(Plan original) throws Exception {
        var paths = new ArrayList<Path>();
        var replacements = new LinkedHashMap<String, String>();
        var temporary = Path.of(original.properties().getOrDefault("java.io.tmpdir", System.getProperty("java.io.tmpdir"))).toRealPath();
        for (var path : original.classPath()) {
            var actual = path.toRealPath();
            if (actual.startsWith(temporary) && Files.isRegularFile(actual)) {
                var retained = retainJar(original.directory(), actual);
                replacements.put(path.toString(), retained.toString()); paths.add(retained);
            } else paths.add(path);
        }
        var vm = new ArrayList<String>();
        for (var argument : original.vmArguments()) {
            if (argument.startsWith("-javaagent:")) {
                int separator = argument.indexOf('=', 11);
                var path = argument.substring(11, separator == -1 ? argument.length() : separator);
                var actual = Path.of(path).toRealPath();
                var retained = retainJar(original.directory(), actual);
                replacements.put(path, retained.toString());
                replacements.put(actual.toString(), retained.toString());
                argument = "-javaagent:" + original.directory().relativize(retained) + (separator == -1 ? "" : argument.substring(separator));
            }
            vm.add(argument);
        }
        var properties = new LinkedHashMap<>(original.properties());
        properties.replaceAll((key, value) -> {
            if (!key.startsWith("fabric.")) return value;
            var entries = value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
            for (int i = 0; i < entries.length; i++) entries[i] = replacements.getOrDefault(entries[i], entries[i]);
            return String.join(File.pathSeparator, entries);
        });
        // An agent can also be a regular classpath entry. Keep both references on the same URL.
        var rewritten = paths.stream().map(path -> Path.of(replacements.getOrDefault(path.toString(), path.toString()))).toList();
        return new Plan(original.directory(), vm, rewritten, properties, original.arguments());
    }

    private static Path retainJar(Path game, Path source) throws Exception {
        // Bounded, immutable cache; only executable library JARs are retained, never launcher account files.
        if (!Files.isRegularFile(source) || Files.size(source) > 128L * 1024 * 1024) throw new IOException("Runtime JAR unavailable");
        try (var jar = new JarFile(source.toFile())) {
            var manifest = jar.getManifest();
            var dependencies = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (dependencies != null && !dependencies.isBlank())
                for (var entry : dependencies.trim().split("\\s+"))
                    if (!java.net.URI.create(entry).isAbsolute()) throw new IOException("Temporary runtime JAR has relative dependencies");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(source)) { bytes = input.readNBytes(128 * 1024 * 1024 + 1); }
        if (bytes.length > 128 * 1024 * 1024) throw new IOException("Runtime JAR too large");
        return cacheJar(game.resolve(".sky-myce/restart-libraries"), bytes);
    }

    private static Path cacheJar(Path directory, byte[] bytes) throws Exception {
        Files.createDirectories(directory);
        var target = directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".jar");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            var temporary = Files.createTempFile(directory, ".preparing-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (FileAlreadyExistsException race) { /* Verify the concurrently published copy below. */ }
            } finally { Files.deleteIfExists(temporary); }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != bytes.length
                || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)), MessageDigest.getInstance("SHA-256").digest(bytes)))
            throw new IOException("Restart cache failed verification");
        return target;
    }

    static void acknowledge() throws IOException { System.out.write(READY); System.out.flush(); }

    private static List<String> command(Path wrapper, Plan plan) throws Exception {
        var command = new ArrayList<String>();
        command.add(javaExecutable().toString());
        for (var argument : plan.vmArguments()) {
            // Explicit captured properties below include runtime changes made by the original launcher.
            if (!argument.startsWith("-Djava.class.path=") && !argument.startsWith("-Dsun.java.command=")
                    && plan.properties().keySet().stream().noneMatch(key -> argument.startsWith("-D" + key + "="))) command.add(argument);
        }
        var encoding = java.nio.charset.Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        for (var entry : plan.properties().entrySet()) {
            var value = entry.getValue();
            if (entry.getKey().equals("java.library.path")) {
                var paths = value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
                for (int i = 0; i < paths.length; i++)
                    if (!encoding.newEncoder().canEncode(paths[i])) paths[i] = plan.directory().relativize(Path.of(paths[i])).toString();
                value = String.join(File.pathSeparator, paths);
                if (!encoding.newEncoder().canEncode(value)) throw new IOException("Native search path unavailable");
            }
            // Properties with arbitrary Unicode arrive losslessly through the private pipe instead
            // of Windows' legacy Java command-line decoding. Set them before initializing Fabric.
            if (encoding.newEncoder().canEncode(value)) command.add("-D" + entry.getKey() + "=" + value);
        }
        command.addAll(List.of("-cp", plan.directory().relativize(wrapper).toString(), LoaderRestart.class.getName()));
        for (var argument : command.subList(1, command.size())) if (!encoding.newEncoder().canEncode(argument)) throw new IOException("JVM option unavailable for restart");
        if (System.getProperty("os.name").startsWith("Windows") && command.stream().mapToInt(String::length).sum() > 30000)
            throw new IOException("JVM options too long for restart");
        return command;
    }

    static Process launch(Path wrapper, Plan plan) throws Exception {
        var command = command(wrapper, plan);
        // Minecraft maintains its normal logs. Never log a reconstructed command or its arguments.
        var builder = new ProcessBuilder(command).directory(plan.directory().toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        clearInjectedOptions(builder);
        var child = builder.start();
        try (var output = child.getOutputStream()) { write(output, plan); }
        catch (Exception failure) { child.destroyForcibly(); throw failure; }
        return child;
    }

    static void clearInjectedOptions(ProcessBuilder builder) {
        // RuntimeMXBean already captured these options; applying the environment a second time
        // would duplicate agents, and would inject game options into the small updater JVM.
        for (var key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
    }

    static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
    }

    /** Fresh JVM, with the original Java settings and no launcher-specific bootstrap. */
    public static void main(String[] ignored) {
        try {
            var plan = Objects.requireNonNull(read(System.in));
            plan.properties().forEach(System::setProperty);
            // Knot reads this property rather than the manifest-expanded system classloader URLs.
            System.setProperty("java.class.path", String.join(File.pathSeparator, plan.classPath().stream().map(Path::toString).toList()));
            Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClient")
                .getMethod("main", String[].class).invoke(null, (Object) plan.arguments().toArray(String[]::new));
        } catch (Throwable failure) {
            // Never print exception text: third-party launch failures can include credentials.
            System.err.println("Sky-Myce could not restart Minecraft. Start this instance from your launcher.");
            System.exit(1);
        }
    }
}
