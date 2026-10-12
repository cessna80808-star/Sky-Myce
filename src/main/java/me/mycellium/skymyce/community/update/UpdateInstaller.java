package me.mycellium.skymyce.community.update;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Optional JDK-only helper. It runs from its own tiny JAR, never from the JAR being replaced. */
public final class UpdateInstaller {
    private UpdateInstaller() {}
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
        if (args.length != 1) return;
        Path planPath = Path.of(args[0]); Properties plan = new Properties();
        try {
            try (InputStream input = Files.newInputStream(planPath)) { plan.load(input); }
            long parent = Long.parseLong(plan.getProperty("parent"));
            long deadline = System.nanoTime() + 600_000_000_000L;
            while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(250);
            install(plan);
            Files.writeString(planPath.resolveSibling("result.txt"), "Update installed. The previous JAR is retained in .sky-myce-updates.\n");
        } catch (Exception failure) {
            try { Files.writeString(planPath.resolveSibling("result.txt"), "Update was not installed. Inspect mods and .sky-myce-updates; the previous JAR was retained or restored.\n"); } catch (Exception ignored) {}
        }
    }
}
