package me.mycellium.skymyce.loader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Keeps game natives alive when the original launcher cleans up after Minecraft exits. */
public final class RestartNatives {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private RestartNatives() {}

    /** Call from the plain helper JVM, whose library path still contains the platform defaults. */
    public static Map<String, String> preserve(Path game, Map<String, String> properties) throws Exception {
        var defaults = new HashSet<Path>();
        for (var value : System.getProperty("java.library.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator), -1))
            defaults.add(path(value));
        var result = new LinkedHashMap<>(properties);
        var rewritten = new HashMap<Path, Path>();
        var budget = new long[3];
        var gameRoot = game.toRealPath();
        var temporaryRoot = path(properties.getOrDefault("java.io.tmpdir", System.getProperty("java.io.tmpdir")));
        var cache = gameRoot.resolve(".sky-myce/restart-natives");
        for (var key : List.of("org.lwjgl.librarypath", "java.library.path")) {
            var original = properties.get(key);
            if (original == null) continue;
            var entries = original.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
            for (int i = 0; i < entries.length; i++) {
                var source = path(entries[i]);
                var replacement = rewritten.get(source);
                // The helper inherits PATH/LD_LIBRARY_PATH, which may include disposable game natives.
                if (replacement == null && key.equals("java.library.path") && defaults.contains(source)
                        && (source.equals(gameRoot) || source.equals(temporaryRoot)
                            || (!source.startsWith(gameRoot) && !source.startsWith(temporaryRoot)))) continue;
                if (replacement == null) {
                    // JVM/LWJGL search paths may include absent directories when natives came from JARs.
                    // There is nothing to retain from an absent entry; keep its original search position.
                    if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Native directory is unavailable");
                    if (cache.equals(source.getParent()) && source.getFileName().toString().matches("[0-9a-f]{64}"))
                        verifyCache(source, source.getFileName().toString());
                    var files = libraries(source, budget);
                    // An empty native directory is normal when LWJGL extracts its libraries from JARs.
                    replacement = source;
                    if (!files.isEmpty()) {
                        var digest = digest(source, files);
                        replacement = cache.resolve(digest);
                        safePath(cache);
                        Files.createDirectories(cache);
                        if (Files.exists(replacement, LinkOption.NOFOLLOW_LINKS)) {
                            verifyCache(replacement, digest);
                        } else {
                            var temporary = Files.createTempDirectory(cache, ".preparing-");
                            try {
                                long copied = 0;
                                for (var file : files) {
                                    var target = temporary.resolve(source.relativize(file));
                                    Files.createDirectories(target.getParent());
                                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                                         var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                                        var buffer = new byte[32768];
                                        for (int count; (count = input.read(buffer)) != -1;) {
                                            copied += count;
                                            if (copied > MAX_BYTES) throw new IOException("Native libraries exceed restart limit");
                                            output.write(buffer, 0, count);
                                        }
                                    }
                                }
                                verifyCache(temporary, digest);
                                try { Files.move(temporary, replacement, StandardCopyOption.ATOMIC_MOVE); }
                                catch (FileAlreadyExistsException race) { verifyCache(replacement, digest); }
                            } finally { removeTemporary(cache, temporary); }
                        }
                    }
                    rewritten.put(source, replacement);
                }
                entries[i] = replacement.toString();
            }
            result.put(key, String.join(File.pathSeparator, entries));
        }
        return result;
    }

    private static Path path(String value) throws IOException {
        var path = Path.of(value).toAbsolutePath().normalize();
        return Files.exists(path) ? path.toRealPath() : path;
    }

    private static void safePath(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe native path");
        }
    }

    private static List<Path> libraries(Path root, long[] budget) throws IOException {
        var result = new ArrayList<Path>();
        Files.walkFileTree(root, Set.of(), 17, new SimpleFileVisitor<>() {
            private void visit() throws IOException {
                if (++budget[2] > 8192) throw new IOException("Native directories exceed restart limit");
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                visit();
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe native directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                visit();
                if (!attributes.isRegularFile()) throw new IOException("Unsafe or excessively nested native directory");
                var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".jnilib") || name.matches(".+\\.so(?:\\.[a-z0-9._-]+)?")))
                    return FileVisitResult.CONTINUE;
                if (++budget[0] > MAX_FILES || (budget[1] += attributes.size()) > MAX_BYTES)
                    throw new IOException("Native libraries exceed restart limit");
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    var header = input.readNBytes(4);
                    if (header.length < 4) throw new IOException("Invalid native library");
                    int magic = ByteBuffer.wrap(header).getInt();
                    if (!((header[0] == 'M' && header[1] == 'Z') || magic == 0x7f454c46
                            || magic == 0xfeedface || magic == 0xcefaedfe || magic == 0xfeedfacf || magic == 0xcffaedfe
                            || magic == 0xcafebabe || magic == 0xbebafeca || magic == 0xcafebabf || magic == 0xbfbafeca))
                        throw new IOException("Invalid native library");
                }
                result.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(file -> root.relativize(file).toString()));
        return result;
    }

    private static String digest(Path root, List<Path> files) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        long read = 0;
        for (var file : files) {
            var name = root.relativize(file).toString().replace(File.separatorChar, '/').getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(name.length).array());
            hash.update(name);
            hash.update(ByteBuffer.allocate(8).putLong(Files.size(file)).array());
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = new byte[32768];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if ((read += count) > MAX_BYTES) throw new IOException("Native libraries exceed restart limit");
                    hash.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void verifyCache(Path directory, String expected) throws Exception {
        safePath(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !expected.equals(digest(directory, libraries(directory, new long[3]))))
            throw new IOException("Native restart cache failed verification");
    }

    private static void removeTemporary(Path cache, Path directory) throws IOException {
        if (!directory.getParent().equals(cache) || !directory.getFileName().toString().startsWith(".preparing-"))
            throw new IOException("Unsafe temporary native path");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
