package me.mycellium.skymyce.loader

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Real updater/relaunch processes, synthetic session data, and no Minecraft or launcher installation. */
fun checkRestart(root: Path, helperJar: Path) {
    Files.createDirectories(root)
    check(LoaderRestart.failureStage(IOException("synthetic-secret")) == "REQUEST")
    check(LoaderRestart.failureStage(IOException("restart:synthetic-secret")) == "REQUEST")
    check(LoaderRestart.failureStage(java.util.concurrent.CompletionException(IOException("restart:RUNTIME_LIBRARIES"))) == "RUNTIME_LIBRARIES")
    val privatePlan = LoaderRestart.Plan(root, listOf("-Xmx96m"), listOf(helperJar),
        linkedMapOf("fixture" to "Ω private property"), listOf("--accessToken", "synthetic-frame-secret"))
    val frame = ByteArrayOutputStream().also { LoaderRestart.write(it, privatePlan) }.toByteArray()
    check(LoaderRestart.read(ByteArrayInputStream(frame)) == privatePlan)
    check(!privatePlan.toString().contains("synthetic-frame-secret"))
    check(LoaderRestart.read(ByteArrayInputStream(byteArrayOf())) == null)
    fun rejectFrame(bytes: ByteArray) {
        check(runCatching { LoaderRestart.read(ByteArrayInputStream(bytes)) }.exceptionOrNull() is IOException)
    }
    for (length in listOf(-1, 0, 1_048_577)) rejectFrame(ByteArrayOutputStream().also {
        DataOutputStream(it).writeInt(length)
    }.toByteArray())
    rejectFrame(frame.copyOf(frame.size - 1))
    val excessiveCount = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeUTF(root.toString()); writeInt(8193) }
    }.toByteArray()
    rejectFrame(ByteArrayOutputStream().also {
        DataOutputStream(it).writeInt(excessiveCount.size); it.write(excessiveCount)
    }.toByteArray())
    val oversized = LoaderRestart.Plan(root, emptyList(), listOf(helperJar), linkedMapOf(), List(18) { "x".repeat(60_000) })
    check(runCatching { LoaderRestart.write(ByteArrayOutputStream(), oversized) }.exceptionOrNull() is IOException)
    val wrapperDirectory = Files.createDirectories(root.resolve("wrapper integrity Ω"))
    val wrapper = LoaderRestart.wrapper(wrapperDirectory, listOf(helperJar))
    val verifiedWrapper = Files.readAllBytes(wrapper)
    Files.write(wrapper, verifiedWrapper.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
    try {
        check(runCatching { LoaderRestart.wrapper(wrapperDirectory, listOf(helperJar)) }.exceptionOrNull() is IOException)
    } finally { Files.write(wrapper, verifiedWrapper) }
    check(LoaderRestart.wrapper(wrapperDirectory, listOf(helperJar)) == wrapper)
    val source = Files.createDirectories(root.resolve("sources with spaces Ω"))
    val classes = Files.createDirectories(root.resolve("classes Ω"))
    val parentSource = source.resolve("RestartParent.java")
    Files.writeString(parentSource, """
        package restart.fixture;
        import me.mycellium.skymyce.loader.*;
        import java.io.*;
        import java.nio.file.*;
        import java.security.MessageDigest;
        import java.util.*;
        public class RestartParent {
            public static void main(String[] args) throws Exception {
                Path game = Path.of(java.net.URI.create(args[0]));
                Path fixture = Path.of(java.net.URI.create(args[2]));
                Path extra = Path.of(java.net.URI.create(args[3]));
                String token = new DataInputStream(System.in).readUTF();
                Process helper = LoaderInstaller.applyAfterExit(game, Path.of(java.net.URI.create(args[1])));
                Files.writeString(game.resolve("helper.pid"), Long.toString(helper.pid()));
                var properties = new LinkedHashMap<String,String>();
                properties.put("fabric.gameJarPath", game.resolve("game marker.jar").toString());
                properties.put("restart.check.game", game.toString());
                properties.put("restart.check.java", System.getProperty("java.home"));
                properties.put("restart.check.classpathHashes", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture)))
                    + "," + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(extra))));
                properties.put("restart.check.hash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                properties.put("fixture.override", "fresh value Ω");
                properties.put("fixture.vm", "original VM Ω");
                properties.put("java.io.tmpdir", game.resolve("temporary runtime Ω").toString());
                properties.put("java.library.path", game.resolve("missing-launcher-natives").toString());
                properties.put("org.lwjgl.librarypath", game.resolve("temporary natives Ω").toString());
                var paths = List.of(fixture, extra);
                if (args[4].equals("invalid")) paths = List.of(game.resolve("missing library.jar"));
                boolean failed = false;
                try {
                    LoaderRestart.request(helper, game,
                        List.of("-Xmx96m", "-Dfixture.vm=original VM Ω", "-Dfixture.override=stale", "-Djava.class.path=stale", "-Dsun.java.command=stale"),
                        paths, properties, new String[]{"--accessToken", token, "--gameDir", game.toString(), "--fixture", "spaces Ω preserved"});
                } catch (IOException expected) {
                    failed = true;
                    if (!"restart:RUNTIME_LIBRARIES".equals(expected.getMessage())) throw new IllegalStateException("Unexpected restart diagnostic");
                    Files.writeString(game.resolve("failure-stage"), LoaderRestart.failureStage(expected));
                }
                if (failed != args[4].equals("invalid")) throw new IllegalStateException("Unexpected preparation result");
                if (failed) {
                    if (!helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Failed helper did not exit");
                    helper = LoaderInstaller.applyAfterExit(game, Path.of(java.net.URI.create(args[1])));
                    Files.writeString(game.resolve("recovery.pid"), Long.toString(helper.pid()));
                }
                Files.writeString(game.resolve("prepared"), failed ? "FAILED" : "READY");
                int action = System.in.read();
                if (!failed) {
                    if (action == 'C') LoaderRestart.commit(helper);
                    else helper.getOutputStream().close(); // Prepared request cancelled without commit.
                }
                Files.writeString(game.resolve("armed"), "READY");
                System.in.read(); // Keep the original game alive after commit to catch premature relaunch.
                Files.delete(game.resolve("temporary natives Ω/fixture.dll"));
                Files.delete(game.resolve("temporary natives Ω"));
                Files.delete(fixture);
                Files.delete(extra);
            }
        }
    """.trimIndent())
    val knotSource = source.resolve("KnotClient.java")
    Files.writeString(knotSource, """
        package net.fabricmc.loader.impl.launch.knot;
        import java.nio.file.*;
        import java.security.MessageDigest;
        import java.util.*;
        public class KnotClient {
            private static int step;
            private static void require(boolean value) throws Exception {
                step++;
                if (!value) {
                    Files.writeString(Path.of(System.getProperty("restart.check.game")).resolve("failed"), Integer.toString(step));
                    throw new IllegalStateException("Restart fixture mismatch");
                }
            }
            public static void main(String[] args) throws Exception {
                Path game = Path.of(System.getProperty("restart.check.game"));
                Files.writeString(game.resolve("restarted.pid"), Long.toString(ProcessHandle.current().pid()));
                require(Path.of("").toAbsolutePath().normalize().equals(game));
                require(args.length == 6 && args[0].equals("--accessToken") && args[2].equals("--gameDir") && args[3].equals(game.toString())
                    && args[4].equals("--fixture") && args[5].equals("spaces Ω preserved"));
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(args[1].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                require(hash.equals(System.getProperty("restart.check.hash")));
                require(System.getProperty("fixture.vm").equals("original VM Ω"));
                require(System.getProperty("fixture.override").equals("fresh value Ω"));
                require(System.getProperty("java.home").equals(System.getProperty("restart.check.java")));
                require(Runtime.getRuntime().maxMemory() <= 100L * 1024 * 1024);
                var hashes = new ArrayList<String>();
                for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    Path library = Path.of(entry);
                    require(library.startsWith(game.resolve(".sky-myce/restart-libraries")) && Files.isRegularFile(library));
                    hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library))));
                }
                require(String.join(",", hashes).equals(System.getProperty("restart.check.classpathHashes")));
                require(!Files.exists(game.resolve("temporary runtime Ω/fake Fabric Ω with spaces.jar")));
                require(System.getProperty("fabric.gameJarPath").equals(game.resolve("game marker.jar").toString()));
                Path natives = Path.of(System.getProperty("org.lwjgl.librarypath"));
                require(natives.startsWith(game.resolve(".sky-myce/restart-natives")));
                require(System.getProperty("java.library.path").equals(game.resolve("missing-launcher-natives").toString()));
                require(!Files.exists(Path.of(System.getProperty("java.library.path"))));
                require(Arrays.equals(Files.readAllBytes(natives.resolve("fixture.dll")), new byte[]{77,90,0,0,42}));
                require(!Files.exists(game.resolve("temporary natives Ω")));
                Files.writeString(game.resolve("restarted"), "PASS");
            }
        }
    """.trimIndent())
    val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
    val diagnostics = ByteArrayOutputStream()
    check(compiler.run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", helperJar.toString(),
        "-d", classes.toString(), parentSource.toString(), knotSource.toString()) == 0) { "Restart fixture compilation failed: $diagnostics" }
    val fixture = root.resolve("parent-fixture.jar")
    JarOutputStream(Files.newOutputStream(fixture)).use { jar ->
        Files.walk(classes).use { files -> files.filter(Files::isRegularFile).forEach { file ->
            jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')))
            Files.copy(file, jar); jar.closeEntry()
        } }
    }
    val extra = root.resolve("extra library Ω.jar")
    JarFile(fixture.toFile()).use { check(it.getJarEntry("restart/fixture/RestartParent.class") != null) { "Parent fixture class missing" } }
    JarOutputStream(Files.newOutputStream(extra)).use { }
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    for (mode in listOf("commit", "cancel", "invalid")) {
        val game = Files.createDirectories(root.resolve("Any launcher Ω $mode"))
        val natives = Files.createDirectories(game.resolve("temporary natives Ω"))
        Files.write(natives.resolve("fixture.dll"), byteArrayOf(77, 90, 0, 0, 42))
        Files.copy(extra, game.resolve("game marker.jar"))
        val runtime = Files.createDirectories(game.resolve("temporary runtime Ω"))
        val runtimeFixture = Files.copy(fixture, runtime.resolve("fake Fabric Ω with spaces.jar"))
        val runtimeExtra = Files.copy(extra, runtime.resolve(extra.fileName))
        val previousLog = "Previous safe diagnostics\n" + "x".repeat(65_536)
        if (mode == "invalid") {
            Files.createDirectories(game.resolve(".sky-myce"))
            Files.writeString(game.resolve(".sky-myce/update.log"), previousLog)
        }
        val token = "synthetic-session-" + UUID.randomUUID()
        val parentClasspath = listOf(helperJar, fixture).joinToString(File.pathSeparator) { game.relativize(it).toString() }
        val parent = ProcessBuilder(java.toString(), "-cp", parentClasspath, "restart.fixture.RestartParent",
            game.toUri().toASCIIString(), helperJar.toUri().toASCIIString(), runtimeFixture.toUri().toASCIIString(), runtimeExtra.toUri().toASCIIString(), mode)
            .directory(game.toFile()).redirectErrorStream(true).redirectOutput(game.resolve("parent.log").toFile()).start()
        var helper: ProcessHandle? = null
        var recovery: ProcessHandle? = null
        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (!condition()) { check(System.nanoTime() < deadline) { "Restart $mode fixture timed out" }; Thread.sleep(20) }
        }
        try {
            val input = DataOutputStream(parent.outputStream)
            input.writeUTF(token); input.flush()
            waitUntil { Files.exists(game.resolve("prepared")) || !parent.isAlive }
            val helperPid = game.resolve("helper.pid")
            if (Files.exists(helperPid)) helper = ProcessHandle.of(Files.readString(helperPid).toLong()).orElse(null)
            val recoveryPid = game.resolve("recovery.pid")
            if (Files.exists(recoveryPid)) recovery = ProcessHandle.of(Files.readString(recoveryPid).toLong()).orElse(null)
            check(parent.isAlive && Files.exists(game.resolve("prepared"))) {
                val logs = listOf(game.resolve("parent.log"), game.resolve(".sky-myce/update.log"))
                    .filter(Files::exists).joinToString("\n") { Files.readString(it).replace(token, "[redacted]").take(4096) }
                "Restart $mode preparation failed: $logs"
            }
            check(Files.readString(game.resolve("prepared")) == if (mode == "invalid") "FAILED" else "READY")
            check(!Files.exists(game.resolve("restarted")))
            if (mode == "invalid") {
                waitUntil { helper?.isAlive != true }
                check(recovery?.isAlive == true)
                check(Files.readString(game.resolve("failure-stage")) == "RUNTIME_LIBRARIES")
            }
            input.write(if (mode == "commit") 'C'.code else 'X'.code); input.flush()
            waitUntil { Files.exists(game.resolve("armed")) || !parent.isAlive }
            Thread.sleep(250)
            check(parent.isAlive && !Files.exists(game.resolve("restarted"))) // Even committed restart waits for parent exit.
            input.write('Q'.code); input.close()
            check(parent.waitFor(15, TimeUnit.SECONDS) && parent.exitValue() == 0)
            waitUntil { helper?.isAlive != true }
            if (mode == "invalid") {
                waitUntil { recovery?.isAlive != true }
                val log = Files.readString(game.resolve(".sky-myce/update.log"))
                check(log.contains("Sky-Myce restart failed at RUNTIME_LIBRARIES ("))
                check(log.indexOf("RUNTIME_LIBRARIES") < log.indexOf("Sky-Myce updater finished."))
                check(!log.contains("missing library.jar")) // Raw exception messages/pathnames never become diagnostics.
                check(Files.readString(game.resolve(".sky-myce/update.previous.log")) == previousLog)
                check(Files.size(game.resolve(".sky-myce/update.log")) < 65_536)
            }
            if (mode == "commit") {
                waitUntil { Files.exists(game.resolve("restarted")) || Files.exists(game.resolve("failed")) }
                check(!Files.exists(game.resolve("failed"))) { "Restarted fixture failed check ${Files.readString(game.resolve("failed"))}" }
                check(Files.readString(game.resolve("restarted")) == "PASS")
                val restarted = ProcessHandle.of(Files.readString(game.resolve("restarted.pid")).toLong()).orElse(null)
                waitUntil { restarted?.isAlive != true }
            } else check(!Files.exists(game.resolve("restarted.pid")))
            // Includes the wrapper JAR, native cache, source fixtures and operational logs.
            Files.walk(root).use { files -> files.filter(Files::isRegularFile).forEach { file ->
                check(!String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(token)) { "Synthetic session data persisted" }
                if (file.fileName.toString().endsWith(".jar")) JarFile(file.toFile()).use { jar ->
                    for (entry in jar.entries()) if (!entry.isDirectory) jar.getInputStream(entry).use { input ->
                        check(!String(input.readAllBytes(), Charsets.ISO_8859_1).contains(token)) { "Synthetic session data persisted in JAR" }
                    }
                }
            } }
        } finally {
            if (parent.isAlive) { parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS) }
            helper?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
            val recoveryPid = game.resolve("recovery.pid")
            if (Files.exists(recoveryPid)) ProcessHandle.of(Files.readString(recoveryPid).toLong()).orElse(null)?.takeIf { it.isAlive }
                ?.let { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
            val pid = game.resolve("restarted.pid")
            if (Files.exists(pid)) ProcessHandle.of(Files.readString(pid).toLong()).orElse(null)?.takeIf { it.isAlive }
                ?.let { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
        }
    }
    println("Restart checks passed: bounded frames, cache tampering, real child JVM, parent-exit ordering, private session pipe, VM/classpath/native preservation, cancellation, safe diagnostics and recovery log retention")
}
