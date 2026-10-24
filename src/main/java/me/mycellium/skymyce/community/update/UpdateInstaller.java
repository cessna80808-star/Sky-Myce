package me.mycellium.skymyce.community.update;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Optional JDK-only helper. It runs from its own tiny JAR, never from the JAR being replaced. */
public final class UpdateInstaller {
    private UpdateInstaller() {}
    public static void writeHelper(Path destination) throws Exception {
        var types = new ArrayList<>(List.of(UpdateInstaller.class, GameRestart.class, RestartNatives.class));
        types.addAll(List.of(GameRestart.class.getDeclaredClasses()));
        var resources = new ArrayList<>(types.stream().map(type -> type.getName().replace('.', '/') + ".class").toList());
        // Anonymous file-tree visitors are executable helper code, but getDeclaredClasses() omits them.
        for (var suffix : List.of("1", "2"))
            resources.add(RestartNatives.class.getName().replace('.', '/') + "$" + suffix + ".class");
        try (var jar = new JarOutputStream(Files.newOutputStream(destination))) {
            for (var resource : resources) {
                jar.putNextEntry(new JarEntry(resource));
                try (var input = UpdateInstaller.class.getResourceAsStream("/" + resource)) {
                    if (input == null) throw new java.io.IOException("Restart helper unavailable");
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
    }
    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void install(Properties plan) throws Exception {
        long parent = Long.parseLong(plan.getProperty("parent"));
        if (parent <= 0 || ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) throw new IllegalStateException("Minecraft is still running");
        Path mods = Path.of(plan.getProperty("mods")).toRealPath();
        Path staging = Path.of(plan.getProperty("staging")).toRealPath();
        if (!mods.getFileName().toString().equals("mods") || !staging.getParent().equals(mods.getParent()) || !staging.getFileName().toString().equals(".sky-myce-updates")) throw new IllegalArgumentException("Invalid update scope");
        Path target = Path.of(plan.getProperty("target")).toRealPath();
        Path next = Path.of(plan.getProperty("next")).toRealPath();
        Path backup = Path.of(plan.getProperty("backup")).toAbsolutePath().normalize();
        if (!target.getParent().equals(mods) || !target.getFileName().toString().endsWith(".jar") || !next.getParent().equals(staging) || !backup.getParent().equals(staging) || Files.exists(backup)) throw new IllegalArgumentException("Invalid update paths");
        if (!sha256(target).equals(plan.getProperty("oldHash")) || !sha256(next).equals(plan.getProperty("newHash")) || Files.size(next) != Long.parseLong(plan.getProperty("size"))) throw new IllegalStateException("Files changed after verification");
        // Same game directory/filesystem. Refuse non-atomic replacement instead of weakening this guarantee.
        Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(next, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            if (!Files.exists(target)) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
            throw failure;
        }
    }
    public static void main(String[] args) {
        if (args.length != 1 && !(args.length == 3 && args[0].equals("--restart"))) return;
        Path planPath = args.length == 1 ? Path.of(args[0]) : null;
        try {
            var plan = new Properties();
            if (planPath != null) try (InputStream input = Files.newInputStream(planPath)) { plan.load(input); }
            long parent = Long.parseLong(planPath == null ? args[1] : plan.getProperty("parent"));
            long started = Long.parseLong(planPath == null ? args[2] : plan.getProperty("parentStarted", "0"));
            GameRestart.afterExit(parent, started, () -> {
                if (planPath != null) {
                    // Windows may briefly retain the mod's file handle after the process exits.
                    for (int attempt = 0; ; attempt++) {
                        try { install(plan); break; }
                        catch (FileSystemException locked) { if (attempt == 19) throw locked; Thread.sleep(250); }
                    }
                    Files.writeString(planPath.resolveSibling("result.txt"), "Update installed. The previous JAR is retained in .sky-myce-updates.\n");
                }
                return null;
            });
        } catch (Throwable failure) {
            GameRestart.reportFailure(failure);
            if (planPath != null) try { Files.writeString(planPath.resolveSibling("result.txt"), "Update/restart could not finish. Start Minecraft from your launcher; inspect mods and .sky-myce-updates.\n"); } catch (Exception ignored) {}
            System.exit(1);
        }
    }
}
