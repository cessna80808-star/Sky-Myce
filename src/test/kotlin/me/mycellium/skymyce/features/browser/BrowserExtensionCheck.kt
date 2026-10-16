package me.mycellium.skymyce.features.browser

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {
    fun archive(vararg names: String, oversized: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                if (oversized) repeat(129) { zip.write(ByteArray(65536)) } else zip.write("test".toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
    val root = Files.createTempDirectory("sky-myce-browser-extension-check")
    try {
        val valid = Files.createDirectory(root.resolve("valid"))
        extractBrowserExtension(archive("manifest.json", "js/background.js").inputStream(), valid)
        check(Files.readString(valid.resolve("js/background.js")) == "test")
        listOf("../escape.js", "/escape.js", "C:/escape.js", "js\\escape.js", "js/../../escape.js", "./escape.js").forEachIndexed { index, name ->
            val destination = Files.createDirectory(root.resolve("invalid-$index"))
            check(runCatching { extractBrowserExtension(archive(name).inputStream(), destination) }.isFailure) { "Accepted archive path: $name" }
        }
        check(!Files.exists(root.resolve("escape.js")))
        val duplicate = Files.createDirectory(root.resolve("duplicate"))
        check(runCatching { extractBrowserExtension(archive("A.js", "a.js").inputStream(), duplicate) }.isFailure)
        val oversized = Files.createDirectory(root.resolve("oversized"))
        check(runCatching { extractBrowserExtension(archive("large.js", oversized = true).inputStream(), oversized) }.isFailure)
        val packaged = BrowserExtension.prepare(root.resolve("packaged"))
        check(Files.isRegularFile(packaged.resolve("manifest.json")))
        check(Files.readString(packaged.resolve(".complete")) == BrowserExtension.SHA256)
        check(BrowserExtension.prepare(root.resolve("packaged")) == packaged)
        println("Browser extension checks passed")
    } finally {
        check(root.toAbsolutePath().normalize().parent == java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
