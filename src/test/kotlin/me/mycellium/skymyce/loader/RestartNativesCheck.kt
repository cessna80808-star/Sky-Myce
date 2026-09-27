package me.mycellium.skymyce.loader

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

fun checkRestartNatives(root: Path) {
    Files.createDirectories(root)
    val defaults = System.getProperty("java.library.path")
    val platform = Files.createDirectories(root.resolve("platform"))
    val source = Files.createDirectories(root.resolve("original-natives"))
    val game = Files.createDirectories(root.resolve("game"))
    fun native(file: Path, marker: String) {
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + marker.toByteArray())
    }
    fun reject(action: () -> Unit) = check(runCatching(action).isFailure)
    System.setProperty("java.library.path", platform.toString())
    try {
        native(source.resolve("liblwjgl.so"), "original")
        native(source.resolve("nested/libsupport.so.1"), "dependency")
        Files.writeString(source.resolve("account.json"), "not-a-native-do-not-copy")
        val properties = linkedMapOf(
            "java.library.path" to "$platform${File.pathSeparator}$source",
            "org.lwjgl.librarypath" to source.toString(),
            "unrelated" to "unchanged"
        )
        val first = RestartNatives.preserve(game, properties)
        val cached = Path.of(first.getValue("org.lwjgl.librarypath"))
        check(first.getValue("java.library.path") == "$platform${File.pathSeparator}$cached")
        check(first["unrelated"] == "unchanged" && properties["org.lwjgl.librarypath"] == source.toString())
        check(Files.readAllBytes(cached.resolve("liblwjgl.so")).contentEquals(Files.readAllBytes(source.resolve("liblwjgl.so"))))
        check(Files.exists(cached.resolve("nested/libsupport.so.1")))
        check(!Files.exists(cached.resolve("account.json")))
        check(RestartNatives.preserve(game, properties) == first)
        check(RestartNatives.preserve(game, first) == first)
        native(source.resolve("liblwjgl.so"), "updated")
        val updated = Path.of(RestartNatives.preserve(game, properties).getValue("org.lwjgl.librarypath"))
        check(updated != cached && Files.readString(cached.resolve("liblwjgl.so")).endsWith("original"))
        Files.delete(source.resolve("liblwjgl.so"))
        Files.delete(source.resolve("nested/libsupport.so.1"))
        Files.delete(source.resolve("nested"))
        Files.delete(source.resolve("account.json"))
        Files.delete(source)
        check(Files.exists(cached.resolve("liblwjgl.so")) && Files.exists(updated.resolve("liblwjgl.so")))
        check(RestartNatives.preserve(game, first) == first)
        check(RestartNatives.preserve(game, properties) == properties)
        val unusedSearch = root.resolve("missing-launcher-natives")
        val availableLwjgl = mapOf(
            "java.library.path" to "$unusedSearch${File.pathSeparator}$platform",
            "org.lwjgl.librarypath" to cached.toString()
        )
        check(RestartNatives.preserve(game, availableLwjgl) == availableLwjgl)
        check(Files.exists(cached.resolve("liblwjgl.so")))
        native(cached.resolve("liblwjgl.so"), "corrupted")
        reject { RestartNatives.preserve(game, first) }

        val gameNative = Files.createDirectories(game.resolve("inherited-natives"))
        val temporaryRoot = Files.createDirectories(root.resolve("launcher-temporary"))
        val temporaryNative = Files.createDirectories(temporaryRoot.resolve("natives"))
        val explicitNative = Files.createDirectories(root.resolve("explicit-lwjgl"))
        listOf(gameNative, temporaryNative, explicitNative).forEach { native(it.resolve("inherited.so"), "inherited") }
        System.setProperty("java.library.path", listOf(platform, gameNative, temporaryNative, explicitNative).joinToString(File.pathSeparator))
        val inheritedProperties = mapOf(
            "java.library.path" to listOf(platform, gameNative, temporaryNative, explicitNative).joinToString(File.pathSeparator),
            "org.lwjgl.librarypath" to explicitNative.toString(),
            "java.io.tmpdir" to temporaryRoot.toString()
        )
        val inherited = RestartNatives.preserve(game, inheritedProperties)
        val inheritedSearch = inherited.getValue("java.library.path").split(File.pathSeparator)
        check(inheritedSearch[0] == platform.toString())
        check(inheritedSearch[1] != gameNative.toString() && inheritedSearch[2] != temporaryNative.toString())
        check(inherited.getValue("org.lwjgl.librarypath") != explicitNative.toString())
        check(inheritedSearch[3] == inherited.getValue("org.lwjgl.librarypath"))
        check(Files.exists(Path.of(inheritedSearch[1]).resolve("inherited.so")))
        check(Files.exists(Path.of(inheritedSearch[2]).resolve("inherited.so")))
        System.setProperty("java.library.path", platform.toString())

        val empty = Files.createDirectories(root.resolve("empty"))
        // The default current-directory entry must not scan the entire instance as native libraries.
        System.setProperty("java.library.path", game.toString())
        Files.writeString(game.resolve("unrelated.dll"), "not a game native")
        check(RestartNatives.preserve(game, mapOf("java.library.path" to game.toString()))["java.library.path"] == game.toString())
        Files.delete(game.resolve("unrelated.dll"))
        System.setProperty("java.library.path", platform.toString())
        val emptyProperties = mapOf("java.library.path" to empty.toString())
        check(RestartNatives.preserve(game, emptyProperties)["java.library.path"] == empty.toAbsolutePath().normalize().toString())
        val nested = Files.createDirectories(root.resolve("too-deep"))
        native((1..18).fold(nested) { path, index -> path.resolve("d$index") }.resolve("deep.so"), "deep")
        reject { RestartNatives.preserve(game, mapOf("java.library.path" to nested.toString())) }
        val many = Files.createDirectories(root.resolve("too-many"))
        repeat(4097) { native(many.resolve("lib$it.so"), "small") }
        reject { RestartNatives.preserve(game, mapOf("org.lwjgl.librarypath" to many.toString())) }
        val oversized = Files.createDirectories(root.resolve("too-large"))
        java.io.RandomAccessFile(oversized.resolve("big.so").toFile(), "rw").use { it.writeInt(0x7f454c46); it.setLength(256L * 1024 * 1024 + 1) }
        reject { RestartNatives.preserve(game, mapOf("org.lwjgl.librarypath" to oversized.toString())) }
        val link = root.resolve("linked-natives")
        // Windows requires Developer Mode or privilege to create this fixture; check when available.
        if (runCatching { Files.createSymbolicLink(link, updated) }.isSuccess) {
            check(RestartNatives.preserve(game, mapOf("org.lwjgl.librarypath" to link.toString()))["org.lwjgl.librarypath"] == updated.toString())
            Files.delete(link)
            val leafDirectory = Files.createDirectories(root.resolve("linked-file"))
            Files.createSymbolicLink(leafDirectory.resolve("unsafe.so"), updated.resolve("liblwjgl.so"))
            reject { RestartNatives.preserve(game, mapOf("org.lwjgl.librarypath" to leafDirectory.toString())) }
            Files.delete(leafDirectory.resolve("unsafe.so"))
        }
    } finally { System.setProperty("java.library.path", defaults) }
}
