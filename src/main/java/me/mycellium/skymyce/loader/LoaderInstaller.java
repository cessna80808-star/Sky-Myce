package me.mycellium.skymyce.loader;

import com.google.gson.JsonParser;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.swing.*;

/** Applies a verified download while Minecraft is stopped, so Windows never replaces an open JAR. */
public final class LoaderInstaller {
    private LoaderInstaller() {}
    public static void main(String[] arguments) {
        try {
            if (arguments.length == 4 && arguments[0].equals("--apply-after")) {
                var game = Path.of(java.net.URI.create(arguments[1])).toAbsolutePath().normalize();
                var restart = LoaderRestart.read(System.in);
                Path wrapper = null;
                if (restart != null) {
                    restart = LoaderRestart.preserveRuntime(restart);
                    wrapper = LoaderRestart.prepare(restart);
                    LoaderRestart.acknowledge();
                    // A stale/account-switched request is never armed by the client.
                    if (!LoaderRestart.committed()) restart = null;
                }
                var parent = ProcessHandle.of(Long.parseLong(arguments[2]));
                if (parent.isPresent() && parent.get().info().startInstant().orElseThrow().toEpochMilli() == Long.parseLong(arguments[3]))
                    parent.get().onExit().get();
                // Windows may briefly retain a file handle after the game process exits.
                for (int attempt = 0; ; attempt++) {
                    try {
                        boolean loaderChanged = Files.exists(game.resolve(".sky-myce/Loader.pending.jar")) && applyLoader(game, SignedFiles.configuration());
                        if (!loaderChanged && Files.exists(game.resolve(".sky-myce/Sky-Myce.pending.jar"))) apply(game);
                        break;
                    }
                    catch (FileSystemException locked) { if (attempt == 19) throw locked; Thread.sleep(250); }
                }
                System.err.println("Sky-Myce updater finished.");
                if (restart != null) LoaderRestart.launch(wrapper, restart);
                return;
            }
            Path game;
            if (arguments.length == 2 && arguments[0].equals("--apply")) game = Path.of(arguments[1]);
            else {
                JOptionPane.showMessageDialog(null, "1. Close Minecraft.\n2. Choose the instance folder that contains mods and saves (not the mods folder itself).\n3. Start Minecraft with Fabric after installation.\n\nMinecraft 26.1.2, Java 25, Fabric API and Fabric Language Kotlin are required.\nOld Sky-Myce copies are backed up automatically; other mods are left alone.", "Install Sky-Myce", JOptionPane.INFORMATION_MESSAGE);
                var chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
                game = chooser.getSelectedFile().toPath();
            }
            game = game.toAbsolutePath().normalize();
            if (!Files.isDirectory(game)) throw new IllegalArgumentException("Choose an existing instance");
            if (game.getFileName().toString().equalsIgnoreCase("mods")) throw new IllegalArgumentException("Choose the instance folder above mods");
            Files.createDirectories(game.resolve("mods"));
            var source = Path.of(LoaderInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            installBootstrap(game, source);
            if (Files.exists(game.resolve(".sky-myce/Sky-Myce.pending.jar"))) apply(game);
            else prepareBootstrap(game, SignedFiles.configuration());
            if (arguments.length == 0) JOptionPane.showMessageDialog(null, "Loader installed. Start this instance normally with Fabric.\n\nSky-Myce opens a setup window before Minecraft loads.\nEnter the code from your Discord DM and click Confirm and start.\nMinecraft starts automatically after the verified installation.\nFuture updates are checked before startup. The in-game Loader menu remains available.");
        } catch (Exception failure) {
            if (arguments.length > 0 && arguments[0].equals("--apply-after")) LoaderRestart.reportFailure(failure);
            String message = "Sky-Myce could not finish installation. Close Minecraft and run the loader again.\nChoose the instance folder, not mods. Existing files and verified downloads are retained.\nIf the problem continues, request the current loader from a Sky-Myce admin.";
            System.err.println(message);
            if (arguments.length == 0) JOptionPane.showMessageDialog(null, message, "Sky-Myce installation", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
    /** One helper per Minecraft process; waits outside the game so open JARs can be replaced safely. */
    public static Process applyAfterExit(Path game, Path loaderJar) throws Exception {
        var current = ProcessHandle.current();
        long started = current.info().startInstant().orElseThrow().toEpochMilli();
        var java = LoaderRestart.javaExecutable();
        var state = game.toAbsolutePath().normalize().resolve(".sky-myce");
        Files.createDirectories(state);
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        // The helper must not hold the installed loader open while replacing it on Windows.
        var wrapper = LoaderRestart.wrapper(directory, List.of(LoaderRestart.retainJar(game, loaderJar)));
        var log = state.resolve("update.log");
        if (Files.exists(log) && Files.size(log) > 65536)
            Files.move(log, state.resolve("update.previous.log"), StandardCopyOption.REPLACE_EXISTING);
        var builder = new ProcessBuilder(java.toString(), "-cp", directory.relativize(wrapper).toString(), LoaderInstaller.class.getName(), "--apply-after",
            game.toAbsolutePath().normalize().toUri().toASCIIString(), Long.toString(current.pid()), Long.toString(started))
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        LoaderRestart.clearInjectedOptions(builder);
        return builder.start();
    }
    /** Identify our mod by metadata, never by a filename substring that could match someone else's mod. */
    static List<Path> payloads(Path game) throws Exception {
        return mods(game, "skymyce");
    }
    private static List<Path> mods(Path game, String id) throws Exception {
        var result = new ArrayList<Path>();
        try (var jars = Files.newDirectoryStream(game.resolve("mods"), "*.jar")) {
            for (var jar : jars) {
                if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var archive = new JarFile(jar.toFile())) {
                    var entry = archive.getJarEntry("fabric.mod.json");
                    if (entry == null) continue;
                    try (var input = archive.getInputStream(entry)) {
                        var bytes = input.readNBytes(16385);
                        if (bytes.length <= 16384 && id.equals(JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString())) result.add(jar);
                    }
                } catch (java.io.IOException | RuntimeException ignored) { /* Unrelated/unreadable mods are never removed. */ }
            }
        }
        return result;
    }
    static void installBootstrap(Path game, Path source) throws Exception { installBootstrap(game, source, null); }
    private static void installBootstrap(Path game, Path source, byte[] metadata) throws Exception {
        game = game.toAbsolutePath().normalize(); source = source.toAbsolutePath().normalize();
        var existing = mods(game, "skymyce-loader");
        // A loader run from mods is already installed, even under a different filename.
        var target = source.getParent().equals(game.resolve("mods")) ? source : game.resolve("mods/Sky-Myce-Loader.jar");
        if (Files.isSymbolicLink(target) || (Files.exists(target) && !existing.contains(target))) throw new SecurityException("Loader target is occupied");
        var moved = new LinkedHashMap<Path, Path>();
        Path temporary = null;
        boolean installed = false;
        try {
            if (!source.equals(target)) {
                temporary = Files.createTempFile(game.resolve("mods"), ".sky-myce-loader-", ".tmp");
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            for (var file : existing) {
                if (file.equals(source)) continue;
                var destination = backup(game).resolve(file.getFileName());
                Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE); moved.put(file, destination);
            }
            if (temporary != null) { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); installed = true; }
            if (metadata != null) SignedFiles.atomicWrite(game.resolve(".sky-myce/loader-manifest.json"), metadata);
        } catch (Exception failure) {
            if (installed) Files.deleteIfExists(target);
            for (var entry : moved.entrySet()) Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE);
            throw failure;
        } finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }
    /** Only the already trusted helper verifies and installs a downloaded loader; never execute it first. */
    static boolean applyLoader(Path game, java.util.Properties config) throws Exception {
        game = game.toAbsolutePath().normalize();
        var state = game.resolve(".sky-myce");
        try (var channel = FileChannel.open(state.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new java.io.IOException("Another installer is running");
            var metadata = state.resolve("loader-pending-manifest.json");
            if (Files.size(metadata) > 16384) throw new SecurityException("Manifest too large");
            var manifest = SignedFiles.loaderManifest(JsonParser.parseString(Files.readString(metadata)).getAsJsonObject(), config, System.currentTimeMillis());
            var version = manifest.get("version").getAsString();
            int comparison = SignedFiles.compareVersion(version, config.getProperty("loaderVersion"));
            if (comparison < 0) throw new SecurityException("Loader downgrade rejected");
            var pending = state.resolve("Loader.pending.jar");
            verifyLoader(pending, manifest);
            var loaders = mods(game, "skymyce-loader");
            // Recover a crash after replacement but before pending-file cleanup. Never reinstall different bytes at the same version.
            if (comparison == 0) {
                if (loaders.size() != 1) throw new SecurityException("Ambiguous installed loader");
                verifyLoader(loaders.getFirst(), manifest);
                Files.delete(pending); Files.delete(metadata);
                return false;
            }
            // Another process may already have installed a newer loader. Never roll it back.
            for (var existing : loaders) {
                try (var jar = new JarFile(existing.toFile()); var input = jar.getInputStream(jar.getJarEntry("fabric.mod.json"))) {
                    var installedVersion = JsonParser.parseString(new String(input.readNBytes(16384), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("version").getAsString();
                    if (SignedFiles.compareVersion(version, installedVersion) < 0) throw new SecurityException("Loader downgrade rejected");
                }
            }
            installBootstrap(game, pending, Files.readAllBytes(metadata));
            Files.delete(pending); Files.delete(metadata);
            return true;
        }
    }
    public static void verifyLoader(Path file, com.google.gson.JsonObject manifest) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != manifest.get("size").getAsLong()) throw new SecurityException("Loader size mismatch");
        SignedFiles.verifyArtifact(file, manifest);
        try (var jar = new JarFile(file.toFile()); var input = jar.getInputStream(jar.getJarEntry("fabric.mod.json"))) {
            var bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw new SecurityException("Loader metadata too large");
            var mod = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!mod.get("id").getAsString().equals("skymyce-loader") || !mod.get("version").getAsString().equals(manifest.get("version").getAsString()))
                throw new SecurityException("Loader identity mismatch");
        }
    }
    private static Path backup(Path game) throws Exception {
        var directory = game.resolve(".sky-myce/replaced").resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        return directory;
    }
    static void prepareBootstrap(Path game, java.util.Properties config) throws Exception {
        var files = payloads(game);
        com.google.gson.JsonObject manifest = null;
        try { manifest = previousManifest(game.resolve(".sky-myce/manifest.json"), config); }
        catch (Exception invalid) { /* Unverified payloads must not prevent the standalone loader from starting. */ }
        Path retained = null;
        var moved = new LinkedHashMap<Path, Path>();
        try {
            for (var file : files) {
                if (manifest != null && retained == null) {
                    try { SignedFiles.verifyArtifact(file, manifest); retained = file; continue; }
                    catch (Exception invalid) { /* Back up this stale/altered copy for recovery. */ }
                }
                var destination = backup(game).resolve(file.getFileName());
                Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE);
                moved.put(file, destination);
            }
            var metadata = game.resolve(".sky-myce/manifest.json");
            if (manifest == null && Files.exists(metadata)) {
                var destination = backup(game).resolve(metadata.getFileName());
                Files.move(metadata, destination, StandardCopyOption.ATOMIC_MOVE);
                moved.put(metadata, destination);
            }
        } catch (Exception failure) {
            for (var entry : moved.entrySet()) Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE);
            throw failure;
        }
    }
    private static com.google.gson.JsonObject previousManifest(Path file, java.util.Properties config) throws Exception {
        if (Files.size(file) > 16384) throw new SecurityException("Manifest too large");
        var envelope = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return SignedFiles.manifest(envelope, config,
            JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(envelope.get("payload").getAsString()), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("issuedAt").getAsLong());
    }
    public static void apply(Path game) throws Exception {
        apply(game, SignedFiles.configuration());
    }
    static void apply(Path game, java.util.Properties config) throws Exception {
        game = game.toAbsolutePath().normalize();
        Files.createDirectories(game.resolve(".sky-myce"));
        try (var channel = FileChannel.open(game.resolve(".sky-myce/install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new java.io.IOException("Another installer is running");
            applyLocked(game, config);
        }
    }
    private static void applyLocked(Path game, java.util.Properties config) throws Exception {
        var state = game.resolve(".sky-myce");
        var metadata = state.resolve("pending-manifest.json");
        if (Files.size(metadata) > 16384) throw new SecurityException("Manifest too large");
        var envelope = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        var manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis());
        var current = state.resolve("manifest.json");
        if (Files.exists(current)) {
            // Verify old signature without granting access to its expired lease or release.
            var previous = previousManifest(current, config);
            if (SignedFiles.compareVersion(manifest.get("version").getAsString(), previous.get("version").getAsString()) < 0) throw new SecurityException("Downgrade rejected");
        }
        var pending = state.resolve("Sky-Myce.pending.jar");
        SignedFiles.verifyArtifact(pending, manifest);
        Files.createDirectories(game.resolve("mods"));
        var target = game.resolve("mods/Sky-Myce.jar");
        if (Files.isSymbolicLink(target)) throw new SecurityException("Unexpected installation target");
        var existing = payloads(game);
        if (Files.exists(target) && !existing.contains(target)) {
            // The fixed path may only be replaced if the previous signed installation owns it.
            if (!Files.exists(current)) throw new SecurityException("Installation target is occupied");
            SignedFiles.verifyArtifact(target, previousManifest(current, config));
            existing.add(target);
        }
        var temporary = Files.createTempFile(game.resolve("mods"), ".sky-myce-", ".tmp");
        var moved = new LinkedHashMap<Path, Path>();
        boolean installed = false;
        try {
            Files.copy(pending, temporary, StandardCopyOption.REPLACE_EXISTING);
            for (var file : existing) {
                var destination = backup(game).resolve(file.getFileName());
                Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE);
                moved.put(file, destination);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            installed = true;
            SignedFiles.atomicWrite(current, Files.readAllBytes(metadata));
        } catch (Exception failure) {
            if (installed) Files.deleteIfExists(target);
            for (var entry : moved.entrySet()) Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE);
            throw failure;
        } finally { Files.deleteIfExists(temporary); }
        // Retain the verified pending pair until both replacements finish, so interruption is recoverable.
        Files.delete(pending);
        Files.delete(metadata);
    }
}
