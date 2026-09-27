package me.mycellium.skymyce.loader

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

fun checkLoaderUpdate(root: Path, config: Properties, envelope: (JsonObject) -> JsonObject) {
    fun mod(id: String, version: String) = ByteArrayOutputStream().also { bytes ->
        JarOutputStream(bytes).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write("""{"schemaVersion":1,"id":"$id","version":"$version"}""".toByteArray()); jar.closeEntry()
        }
    }.toByteArray()
    val old = mod("skymyce-loader", "1.0.0")
    val updated = mod("skymyce-loader", "1.0.1")
    val now = System.currentTimeMillis()
    val manifest = JsonObject().apply {
        addProperty("product", "sky-myce-loader"); addProperty("protocol", 1); addProperty("minecraft", "26.1.2")
        addProperty("version", "1.0.1"); addProperty("artifact", "loaders/Sky-Myce-Loader-1.0.1.jar")
        addProperty("size", updated.size); addProperty("sha256", SignedFiles.sha256(updated))
        addProperty("issuedAt", now); addProperty("expiresAt", now + 600000)
    }
    val signed = envelope(manifest)
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    SignedFiles.loaderManifest(signed, config, now)
    reject { SignedFiles.loaderManifest(signed, config, now + 600001) }
    for ((key, value) in listOf("product" to "sky-myce", "minecraft" to "26.3", "artifact" to "../unrelated.jar", "sha256" to "invalid"))
        reject { SignedFiles.loaderManifest(envelope(manifest.deepCopy().apply { addProperty(key, value) }), config, now) }
    reject { SignedFiles.loaderManifest(envelope(manifest.deepCopy().apply { addProperty("size", 64L * 1024 * 1024 + 1) }), config, now) }
    reject { SignedFiles.loaderManifest(signed.deepCopy().apply { addProperty("kid", "untrusted") }, config, now) }
    reject { SignedFiles.loaderManifest(signed.deepCopy().apply { addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))) }, config, now) }
    fun instance(name: String) = root.resolve(name).also { Files.createDirectories(it.resolve("mods")); Files.createDirectories(it.resolve(".sky-myce")) }
    fun stage(game: Path, bytes: ByteArray = updated, metadata: JsonObject = signed) {
        Files.write(game.resolve(".sky-myce/Loader.pending.jar"), bytes)
        Files.writeString(game.resolve(".sky-myce/loader-pending-manifest.json"), metadata.toString())
    }
    val game = instance("update")
    val target = game.resolve("mods/Sky-Myce-Loader.jar")
    Files.write(target, old)
    val unrelated = mod("unrelated", "1.0.0")
    Files.write(game.resolve("mods/unrelated.jar"), unrelated)
    for ((bytes, metadata) in listOf(
        "tampered".toByteArray() to signed,
        updated to signed.deepCopy().apply { addProperty("signature", "invalid") },
        updated to envelope(manifest.deepCopy().apply { addProperty("version", "1.0.0"); addProperty("artifact", "loaders/Sky-Myce-Loader-1.0.0.jar") }),
        unrelated to envelope(manifest.deepCopy().apply { addProperty("sha256", SignedFiles.sha256(unrelated)); addProperty("size", unrelated.size) })
    )) {
        stage(game, bytes, metadata); reject { LoaderInstaller.applyLoader(game, config) }
        check(Files.readAllBytes(target).contentEquals(old))
    }
    stage(game)
    if (System.getProperty("os.name").startsWith("Windows")) {
        val current = game.resolve(".sky-myce/loader-manifest.json")
        Files.writeString(current, "old metadata"); Files.setAttribute(current, "dos:readonly", true)
        try {
            reject { LoaderInstaller.applyLoader(game, config) }
            check(Files.readAllBytes(target).contentEquals(old) && Files.readString(current) == "old metadata")
            check(Files.exists(game.resolve(".sky-myce/Loader.pending.jar")))
        } finally { Files.setAttribute(current, "dos:readonly", false) }
    }
    Files.write(game.resolve("mods/renamed-loader.jar"), old)
    LoaderInstaller.applyLoader(game, config)
    check(Files.readAllBytes(target).contentEquals(updated) && !Files.exists(game.resolve("mods/renamed-loader.jar")))
    check(Files.readAllBytes(game.resolve("mods/unrelated.jar")).contentEquals(unrelated))
    val updatedConfig = Properties().apply { putAll(config); setProperty("loaderVersion", "1.0.1") }
    stage(game); LoaderInstaller.applyLoader(game, updatedConfig)
    check(Files.readAllBytes(target).contentEquals(updated) && !Files.exists(game.resolve(".sky-myce/Loader.pending.jar")))
    val changed = mod("skymyce-loader", "1.0.1") + byteArrayOf(42)
    stage(game, changed, envelope(manifest.deepCopy().apply { addProperty("sha256", SignedFiles.sha256(changed)); addProperty("size", changed.size) }))
    reject { LoaderInstaller.applyLoader(game, updatedConfig) }
    check(Files.readAllBytes(target).contentEquals(updated))
    check(Files.walk(game.resolve(".sky-myce/replaced")).use { files -> files.filter(Files::isRegularFile).anyMatch { Files.readAllBytes(it).contentEquals(old) } })
    val newer = mod("skymyce-loader", "1.0.2")
    Files.write(target, newer); stage(game); reject { LoaderInstaller.applyLoader(game, config) }
    check(Files.readAllBytes(target).contentEquals(newer))
    val childGame = instance("post-exit")
    val helper = installerHelperJar(childGame.resolve("mods/Sky-Myce-Loader.jar"), config)
    stage(childGame)
    checkPostExit(childGame, helper, updated, loaderUpdate = true)
    val restartHelper = installerHelperJar(root.resolve("restart-helper.jar"), config)
    checkRestart(root.resolve("update-then-restart"), restartHelper, updated to signed.toString())
    val recoveryGame = instance("recover-then-update-mod")
    val recoveryHelper = installerHelperJar(recoveryGame.resolve("mods/Sky-Myce-Loader.jar"), updatedConfig)
    val recoveryBytes = Files.readAllBytes(recoveryHelper)
    stage(recoveryGame, recoveryBytes, envelope(manifest.deepCopy().apply {
        addProperty("sha256", SignedFiles.sha256(recoveryBytes)); addProperty("size", recoveryBytes.size)
    }))
    Files.write(recoveryGame.resolve(".sky-myce/Sky-Myce.pending.jar"), unrelated)
    val payloadManifest = manifest.deepCopy().apply {
        addProperty("product", "sky-myce"); addProperty("protocol", 3); addProperty("version", "2.4.5")
        addProperty("minimumLoaderVersion", "1.0.0"); addProperty("artifact", "releases/Sky-Myce-2.4.5.jar")
        addProperty("sha256", SignedFiles.sha256(unrelated))
    }
    Files.writeString(recoveryGame.resolve(".sky-myce/pending-manifest.json"), envelope(payloadManifest).toString())
    checkPostExit(recoveryGame, recoveryHelper, unrelated)
    check(!Files.exists(recoveryGame.resolve(".sky-myce/Loader.pending.jar")))
    check(Files.readAllBytes(recoveryHelper).contentEquals(recoveryBytes))
    println("Loader self-update checks passed: signed metadata, tamper/downgrade rejection, identity, backup, rollback and Windows-safe post-exit replacement")
}
