package me.mycellium.skymyce.community

import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

internal fun communityRestartChecks(root: Path, helper: Path) {
    val classes = Files.createDirectories(root.resolve("classes"))
    val parentSource = root.resolve("Parent.java")
    Files.writeString(parentSource, """
        import me.mycellium.skymyce.loader.LoaderRestart;
        import me.mycellium.skymyce.community.update.UpdateInstaller;
        import java.io.*;
        import java.nio.file.*;
        import java.util.*;
        public class Parent {
            public static void main(String[] args) throws Exception {
                Path game = Path.of(args[0]);
                String token = new DataInputStream(System.in).readUTF();
                var self = ProcessHandle.current();
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", args[1], UpdateInstaller.class.getName()));
                if (args[3].equals("update") || args[3].equals("tampered")) {
                    Path mods = Files.createDirectory(game.resolve("mods"));
                    Path staging = Files.createDirectory(game.resolve(".sky-myce-updates"));
                    Path target = mods.resolve("mod.jar"); Path next = staging.resolve("next.jar");
                    Files.writeString(target, "old"); Files.writeString(next, "new");
                    var plan = new Properties();
                    plan.setProperty("parent", Long.toString(self.pid()));
                    plan.setProperty("parentStarted", Long.toString(self.info().startInstant().orElseThrow().toEpochMilli()));
                    plan.setProperty("mods", mods.toString()); plan.setProperty("staging", staging.toString());
                    plan.setProperty("target", target.toString()); plan.setProperty("next", next.toString());
                    plan.setProperty("backup", staging.resolve("old.backup").toString());
                    plan.setProperty("oldHash", UpdateInstaller.sha256(target)); plan.setProperty("newHash", UpdateInstaller.sha256(next));
                    plan.setProperty("size", "3");
                    Path file = staging.resolve("plan.properties");
                    try (var output = Files.newOutputStream(file)) { plan.store(output, "fixture"); }
                    command.add(file.toString());
                    if (args[3].equals("tampered")) Files.writeString(next, "tampered");
                } else command.addAll(List.of("--restart", Long.toString(self.pid()), Long.toString(self.info().startInstant().orElseThrow().toEpochMilli())));
                Path natives = Files.createDirectory(game.resolve("launcher-natives"));
                boolean invalidNative = args[3].equals("invalid-native");
                Files.write(natives.resolve("fixture.dll"), invalidNative ? new byte[]{0,0,0,0,42} : new byte[]{77,90,0,0,42});
                Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(game.resolve("helper.pid"), Long.toString(child.pid()));
                boolean failed = false;
                try {
                    LoaderRestart.request(child, game, List.of("-Xmx96m"), List.of(Path.of(args[2])),
                        Map.of("org.lwjgl.librarypath", natives.toString(), "java.library.path", natives.toString()),
                        new String[]{game.toString(), token});
                } catch (IOException failure) {
                    if (!invalidNative || !LoaderRestart.failureStage(failure).equals("NATIVE_LIBRARIES")) throw failure;
                    failed = true;
                    Files.writeString(game.resolve("failure-stage"), LoaderRestart.failureStage(failure));
                }
                if (failed != invalidNative) throw new IllegalStateException("Unexpected preparation result");
                if (failed && !child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Failed helper remained alive");
                Files.writeString(game.resolve("prepared"), failed ? "failed" : "ready");
                int action = System.in.read();
                if (!failed) {
                    if (action == 'C') LoaderRestart.commit(child); else child.getOutputStream().close();
                }
                Files.writeString(game.resolve("armed"), "ready");
                System.in.read();
                // Original launchers may discard their native directory as soon as Minecraft exits.
                Files.delete(natives.resolve("fixture.dll")); Files.delete(natives);
            }
        }
    """.trimIndent())
    val knotSource = root.resolve("KnotClient.java")
    Files.writeString(knotSource, """
        package net.fabricmc.loader.impl.launch.knot;
        import java.nio.file.*;
        public class KnotClient {
            public static void main(String[] args) throws Exception {
                Path game = Path.of(args[0]);
                Files.writeString(game.resolve("launch-attempt"), Long.toString(ProcessHandle.current().pid()));
                if (args.length != 2 || !args[1].startsWith("synthetic-")) throw new IllegalStateException();
                if (Files.exists(game.resolve("mods")) && !Files.readString(game.resolve("mods/mod.jar")).equals("new")) throw new IllegalStateException();
                Path natives = Path.of(System.getProperty("org.lwjgl.librarypath"));
                if (!natives.startsWith(game.resolve(".sky-myce/restart-natives"))
                    || !natives.toString().equals(System.getProperty("java.library.path"))
                    || !java.util.Arrays.equals(Files.readAllBytes(natives.resolve("fixture.dll")), new byte[]{77,90,0,0,42})
                    || Files.exists(game.resolve("launcher-natives"))) throw new IllegalStateException("Native preservation failed");
                Files.writeString(game.resolve("restarted"), Long.toString(ProcessHandle.current().pid()));
            }
        }
    """.trimIndent())
    check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", helper.toString(), "-d", classes.toString(), parentSource.toString(), knotSource.toString()) == 0)
    val fixture = root.resolve("fixture.jar")
    JarOutputStream(Files.newOutputStream(fixture)).use { jar ->
        Files.walk(classes).use { files -> files.filter(Files::isRegularFile).forEach { file ->
            jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/'))); Files.copy(file, jar); jar.closeEntry()
        } }
    }
    val executable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    for (mode in listOf("restart", "update", "cancel", "tampered", "invalid-native")) {
        val game = Files.createDirectory(root.resolve(mode))
        val token = "synthetic-${UUID.randomUUID()}"
        val parent = ProcessBuilder(executable, "-cp", "$helper${java.io.File.pathSeparator}$fixture", "Parent", game.toString(), helper.toString(), fixture.toString(), mode)
            .directory(game.toFile()).redirectErrorStream(true).redirectOutput(game.resolve("parent.log").toFile()).start()
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition()) { check(System.nanoTime() < deadline) { "Community restart $mode timed out" }; Thread.sleep(20) }
        }
        var child: ProcessHandle? = null
        try {
            val input = DataOutputStream(parent.outputStream); input.writeUTF(token); input.flush()
            await { Files.exists(game.resolve("prepared")) || !parent.isAlive }
            check(parent.isAlive && Files.exists(game.resolve("prepared")))
            child = ProcessHandle.of(Files.readString(game.resolve("helper.pid")).toLong()).orElse(null)
            if (mode == "invalid-native") {
                check(Files.readString(game.resolve("prepared")) == "failed")
                check(Files.readString(game.resolve("failure-stage")) == "NATIVE_LIBRARIES")
                check(child?.isAlive != true)
            } else check(child?.isAlive == true && Files.readString(game.resolve("prepared")) == "ready")
            input.write(if (mode == "cancel") 'X'.code else 'C'.code); input.flush()
            await { Files.exists(game.resolve("armed")) }
            Thread.sleep(100)
            check(parent.isAlive && !Files.exists(game.resolve("launch-attempt")))
            if (mode in listOf("update", "tampered")) check(Files.readString(game.resolve("mods/mod.jar")) == "old")
            input.write('Q'.code); input.close()
            check(parent.waitFor(10, TimeUnit.SECONDS) && parent.exitValue() == 0)
            await { child?.isAlive != true }
            if (mode in listOf("restart", "update")) {
                await { Files.exists(game.resolve("restarted")) }
                if (mode == "update") check(Files.readString(game.resolve(".sky-myce-updates/old.backup")) == "old")
            } else check(!Files.exists(game.resolve("launch-attempt")) && !Files.exists(game.resolve("restarted")))
            if (mode == "tampered") check(Files.readString(game.resolve("mods/mod.jar")) == "old")
            Files.walk(game).use { files -> files.filter(Files::isRegularFile).forEach { file ->
                check(!String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(token))
            } }
        } finally {
            if (parent.isAlive) parent.destroyForcibly()
            child?.takeIf { it.isAlive }?.destroyForcibly()
            val restarted = game.resolve("restarted")
            if (Files.exists(restarted)) ProcessHandle.of(Files.readString(restarted).toLong()).orElse(null)?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }
    println("Community restart checks passed: isolated helper with native preservation, safe preparation failure, update-before-relaunch, parent-exit ordering, cancellation, tamper refusal and private session pipe")
}
