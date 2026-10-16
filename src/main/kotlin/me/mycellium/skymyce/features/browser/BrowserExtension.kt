package me.mycellium.skymyce.features.browser

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.zip.ZipInputStream

internal object BrowserExtension {
    const val VERSION = "2026.1012.1545"
    const val SHA256 = "73909c8f26d608b00a99f446192630868bea4f947ba293c29bbd18ae948d903b"

    fun prepare(cache: Path): Path {
        Files.createDirectories(cache)
        val root = cache.toRealPath()
        val target = root.resolve("ubol-$VERSION")
        if (complete(target)) return target
        if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Ad blocker cache is incomplete: $target")
        val bytes = javaClass.getResourceAsStream("/browser/ubol-$VERSION.zip")?.use { it.readNBytes(12 * 1024 * 1024 + 1) }
            ?: throw IOException("Bundled ad blocker is missing")
        if (bytes.size > 12 * 1024 * 1024 || HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) != SHA256)
            throw IOException("Bundled ad blocker checksum does not match")
        val staging = Files.createTempDirectory(root, ".ubol-")
        try {
            extractBrowserExtension(bytes.inputStream(), staging)
            if (!Files.isRegularFile(staging.resolve("manifest.json"), NOFOLLOW_LINKS)) throw IOException("Ad blocker manifest is missing")
            Files.writeString(staging.resolve(".complete"), SHA256, CREATE_NEW)
            try {
                try { Files.move(staging, target, ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(staging, target) }
            }
            catch (failure: IOException) { if (!complete(target)) throw failure }
            return target
        } finally {
            // Staging is a new, direct child of this cache; never traverse links during cleanup.
            check(staging.parent == root && staging.fileName.toString().startsWith(".ubol-"))
            if (Files.exists(staging, NOFOLLOW_LINKS)) runCatching {
                Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun complete(directory: Path): Boolean = Files.isDirectory(directory, NOFOLLOW_LINKS) &&
        Files.isRegularFile(directory.resolve("manifest.json"), NOFOLLOW_LINKS) &&
        Files.isRegularFile(directory.resolve(".complete"), NOFOLLOW_LINKS) &&
        runCatching { Files.size(directory.resolve(".complete")) == SHA256.length.toLong() && Files.readString(directory.resolve(".complete")) == SHA256 }.getOrDefault(false)
}

internal fun extractBrowserExtension(archive: InputStream, destination: Path) {
    val root = destination.toAbsolutePath().normalize()
    val seen = HashSet<String>()
    var total = 0L
    ZipInputStream(archive).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (name.isBlank() || name.length > 256 || '\\' in name || ':' in name || name.startsWith('/') ||
                name.trimEnd('/').split('/').any { it == "." || it == ".." || it.isEmpty() } ||
                !seen.add(name.trimEnd('/').lowercase(Locale.ROOT)) || seen.size > 2048)
                throw IOException("Unsafe or duplicate ad blocker archive entry")
            val output = root.resolve(name).normalize()
            if (output == root || !output.startsWith(root)) throw IOException("Ad blocker archive path escapes its cache")
            if (entry.isDirectory) {
                if (zip.read() != -1) throw IOException("Ad blocker archive directory contains data")
                Files.createDirectories(output)
            } else {
                Files.createDirectories(output.parent)
                var size = 0L
                Files.newOutputStream(output, CREATE_NEW).use { file ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        size += count; total += count
                        if (size > 8L * 1024 * 1024 || total > 64L * 1024 * 1024) throw IOException("Ad blocker archive exceeds its size limit")
                        file.write(buffer, 0, count)
                    }
                }
            }
            zip.closeEntry()
        }
    }
}
