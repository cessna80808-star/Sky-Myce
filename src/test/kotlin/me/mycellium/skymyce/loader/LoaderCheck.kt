package me.mycellium.skymyce.loader

import com.google.gson.Gson
import com.google.gson.JsonObject
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

fun main() {
    checkStartup()
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val config = Properties().apply {
        setProperty("key.test", Base64.getEncoder().encodeToString(pair.public.encoded))
        setProperty("minecraft", "26.1.2"); setProperty("loaderVersion", "1.0.0")
    }
    val encode = Base64.getUrlEncoder().withoutPadding()
    fun sign(bytes: ByteArray) = Signature.getInstance("Ed25519").run { initSign(pair.private); update(bytes); encode.encodeToString(sign()) }
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    val now = System.currentTimeMillis()
    val temporary = Files.createTempDirectory("sky-myce-loader-check")
    try {
        val installation = Installation(temporary.resolve("identity"))
        val reload = Installation(temporary.resolve("identity"))
        check(installation.id == reload.id && installation.publicKey == reload.publicKey && installation.deviceHash == reload.deviceHash)
        val copiedGame = Installation(temporary.resolve("other-device"))
        check(copiedGame.id != installation.id && copiedGame.publicKey != installation.publicKey)
        val manifest = JsonObject().apply {
            addProperty("product", "sky-myce"); addProperty("protocol", 3); addProperty("minecraft", "26.1.2")
            addProperty("version", "2.4.2"); addProperty("minimumLoaderVersion", "1.0.0"); addProperty("artifact", "release.jar")
            addProperty("sha256", SignedFiles.sha256("verified payload".toByteArray())); addProperty("issuedAt", now); addProperty("expiresAt", now + 600000)
        }
        fun envelope(value: JsonObject): JsonObject {
            val bytes = Gson().toJson(value).toByteArray()
            return JsonObject().apply { addProperty("payload", encode.encodeToString(bytes)); addProperty("signature", sign(bytes)); addProperty("kid", "test") }
        }
        val signed = envelope(manifest)
        check(SignedFiles.manifest(signed, config, now).get("version").asString == "2.4.2")
        val artifact = temporary.resolve("payload.jar")
        Files.writeString(artifact, "verified payload"); SignedFiles.verifyArtifact(artifact, manifest)
        Files.writeString(artifact, "modified payload"); reject { SignedFiles.verifyArtifact(artifact, manifest) }
        reject { SignedFiles.manifest(signed.deepCopy().apply { addProperty("signature", encode.encodeToString(ByteArray(64))) }, config, now) }
        reject { SignedFiles.manifest(signed.deepCopy().apply { addProperty("kid", "untrusted") }, config, now) }
        reject { SignedFiles.manifest(signed, config, now + 600001) }
        reject { SignedFiles.manifest(envelope(manifest.deepCopy().apply { addProperty("minecraft", "26.3") }), config, now) }
        reject { SignedFiles.manifest(envelope(manifest.deepCopy().apply { addProperty("minimumLoaderVersion", "2.0.0") }), config, now) }
        check(SignedFiles.compareVersion("2.10.0", "2.9.9") > 0)
        val game = temporary.resolve("instance")
        val stateDirectory = game.resolve(".sky-myce").also(Files::createDirectories)
        val pending = stateDirectory.resolve("Sky-Myce.pending.jar")
        val pendingManifest = stateDirectory.resolve("pending-manifest.json")
        Files.writeString(pending, "verified payload"); Files.writeString(pendingManifest, Gson().toJson(signed))
        LoaderInstaller.apply(game, config)
        check(Files.readString(game.resolve("mods/Sky-Myce.jar")) == "verified payload")
        check(!Files.exists(pending) && !Files.exists(pendingManifest))
        Files.writeString(pending, "tampered"); Files.writeString(pendingManifest, Gson().toJson(signed))
        reject { LoaderInstaller.apply(game, config) }
        check(Files.readString(game.resolve("mods/Sky-Myce.jar")) == "verified payload")
        Files.writeString(pending, "verified payload")
        Files.writeString(pendingManifest, Gson().toJson(envelope(manifest.deepCopy().apply { addProperty("version", "2.4.1") })))
        reject { LoaderInstaller.apply(game, config) }
        check(Files.readString(game.resolve("mods/Sky-Myce.jar")) == "verified payload")
        checkInstaller(temporary.resolve("installer"), config, manifest, ::envelope)
        checkRestartNatives(temporary.resolve("native-restart"))
        val uuid = "a".repeat(32)
        check(activationRequestCurrent(7, 7, uuid, uuid, uuid))
        check(!activationRequestCurrent(7, 8, uuid, uuid, uuid)) // A retry/revocation invalidates an in-flight confirmation.
        check(!activationRequestCurrent(8, 7, uuid, uuid, uuid))
        check(!activationRequestCurrent(7, 7, uuid, "b".repeat(32), uuid))
        check(!activationRequestCurrent(7, 7, uuid, uuid, "b".repeat(32)))
        check(!activationRequestCurrent(7, 7, uuid, "b".repeat(32), "b".repeat(32))) // A switched profile cannot consume the code.
        check(!activationRequestCurrent(7, 7, "b".repeat(32), uuid, uuid))
        check(!activationRequestCurrent(7, 7, uuid, uuid, null))
        check(!activationRequestCurrent(7, 7, uuid, uuid, mapOf("uuid" to uuid)))
        val claims = JsonObject().apply {
            addProperty("product", "sky-myce"); addProperty("protocol", 3); addProperty("accountId", "account")
            addProperty("discordId", "1250627753191931914"); addProperty("uuid", uuid)
            addProperty("installationId", installation.id); addProperty("deviceHash", installation.deviceHash)
            addProperty("keyFingerprint", SignedFiles.sha256(Base64.getDecoder().decode(installation.publicKey)))
            addProperty("version", "2.4.2"); addProperty("minecraft", "26.1.2"); addProperty("iat", now)
            addProperty("exp", now + 300000); addProperty("offlineUntil", now + 900000); addProperty("generation", 0); addProperty("jti", "unique")
            addProperty("challengeId", "request-1"); add("features", com.google.gson.JsonArray().apply { add("relay"); add("irc") })
            addProperty("clientNonce", "fresh-client-nonce")
        }
        fun token(value: JsonObject): String {
            val header = encode.encodeToString("{\"alg\":\"EdDSA\",\"kid\":\"test\",\"typ\":\"JWT\"}".toByteArray())
            val payload = encode.encodeToString(Gson().toJson(value).toByteArray())
            val unsigned = "$header.$payload"
            return "$unsigned.${sign(unsigned.toByteArray())}"
        }
        val lease = token(claims)
        verifyLease(lease, config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce")
        reject { verifyLease(lease.dropLast(10) + "AAAAAAAAAA", config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, copiedGame, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, "b".repeat(32), "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, uuid, "2.4.1", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, uuid, "2.4.2", "different-request", "fresh-client-nonce") }
        // Even replaying both the old challenge response and its matching lease cannot match a new local nonce.
        reject { verifyLease(lease, config, installation, uuid, "2.4.2", "request-1", "another-fresh-client-nonce") }
        listOf("product" to "other", "deviceHash" to "b".repeat(64), "keyFingerprint" to "b".repeat(64), "minecraft" to "26.3").forEach { (key, value) ->
            reject { verifyLease(token(claims.deepCopy().apply { addProperty(key, value) }), config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        }
        var nanos = 0L
        val state = AuthorizationState { nanos }
        check(!state.onlineAllowed && !state.localAllowed)
        state.accept(lease, claims, now)
        check(state.onlineAllowed && state.localAllowed)
        nanos += 300001L * 1_000_000
        check(!state.onlineAllowed && !state.localAllowed)
        state.unavailable(); check(state.localAllowed && !state.onlineAllowed)
        // No wall-clock input can roll the deadline back; a new JVM starts denied despite disk cache.
        check(!AuthorizationState { 0 }.localAllowed)
        nanos = 900001L * 1_000_000; check(!state.localAllowed)
        state.accept(lease, claims, now); check(state.onlineAllowed)
        val beforeRevocation = state.generation
        state.deny(); state.unavailable(); check(!state.localAllowed && !state.onlineAllowed)
        reject { state.accept(lease, claims, now, beforeRevocation) }
        check(!state.localAllowed) // An in-flight pre-revoke renewal cannot re-enable the mod.
        val irc = IrcMode(); val sent = mutableListOf<String>()
        check(irc.chat("public", sent::add)); check(sent.isEmpty())
        check(!irc.command("chat irc")); check(irc.selected)
        check(!irc.chat("private", sent::add)); check(sent == listOf("private"))
        check(!irc.chat("offline") { }); check(irc.selected) // Never falls through to the server.
        check(irc.command("sm msg Alice hello") && irc.selected)
        check(irc.command("chat p") && !irc.selected && irc.chat("party", sent::add))
        check(!irc.command(" CHAT   IRC ")); check(irc.command("chat a") && !irc.selected)
        println("Sky-Myce loader checks passed: signatures, artifact tamper, duplicate repair, install rollback, post-exit helper, pinned keys, bindings, privacy identity persistence, monotonic grace, revoke and sticky IRC")
    } finally { Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
}

private fun checkInstaller(root: Path, config: Properties, template: JsonObject, envelope: (JsonObject) -> JsonObject) {
    fun mod(id: String, version: String) = ByteArrayOutputStream().also { bytes ->
        JarOutputStream(bytes).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json").apply { time = 0 })
            jar.write("""{"schemaVersion":1,"id":"$id","version":"$version"}""".toByteArray())
            jar.closeEntry()
        }
    }.toByteArray()
    fun instance(name: String) = root.resolve(name).also {
        Files.createDirectories(it.resolve("mods")); Files.createDirectories(it.resolve(".sky-myce"))
    }
    fun metadata(bytes: ByteArray, version: String = "2.4.3") = envelope(template.deepCopy().apply {
        addProperty("sha256", SignedFiles.sha256(bytes)); addProperty("version", version)
    })
    val old = mod("skymyce", "2.4.2")
    val updated = mod("skymyce", "2.4.3")
    val unrelated = mod("unrelated", "1.0.0")
    fun stage(game: Path, bytes: ByteArray = updated, signed: JsonObject = metadata(bytes)) {
        Files.write(game.resolve(".sky-myce/Sky-Myce.pending.jar"), bytes)
        Files.writeString(game.resolve(".sky-myce/pending-manifest.json"), Gson().toJson(signed))
    }
    fun installOld(game: Path) {
        Files.write(game.resolve("mods/Sky-Myce.jar"), old)
        Files.writeString(game.resolve(".sky-myce/manifest.json"), Gson().toJson(metadata(old, "2.4.2")))
    }
    fun backedUp(game: Path, bytes: ByteArray) = Files.walk(game.resolve(".sky-myce/replaced")).use { paths ->
        paths.filter(Files::isRegularFile).anyMatch { Files.readAllBytes(it).contentEquals(bytes) }
    }
    val duplicates = instance("renamed-payloads")
    Files.write(duplicates.resolve("mods/renamed-old.jar"), old)
    Files.write(duplicates.resolve("mods/another-old.jar"), old)
    Files.write(duplicates.resolve("mods/Sky-Myce-unrelated.jar"), unrelated)
    stage(duplicates)
    LoaderInstaller.apply(duplicates, config)
    check(LoaderInstaller.payloads(duplicates) == listOf(duplicates.resolve("mods/Sky-Myce.jar")))
    check(Files.readAllBytes(duplicates.resolve("mods/Sky-Myce.jar")).contentEquals(updated))
    check(Files.readAllBytes(duplicates.resolve("mods/Sky-Myce-unrelated.jar")).contentEquals(unrelated))
    check(backedUp(duplicates, old))
    check(!Files.exists(duplicates.resolve(".sky-myce/Sky-Myce.pending.jar")))

    val invalid = instance("invalid-update")
    installOld(invalid)
    val invalidUpdates = listOf(
        updated to metadata(updated).apply { addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))) },
        "tampered".toByteArray() to metadata(updated),
        updated to metadata(updated, "2.4.1"),
    )
    for ((bytes, signed) in invalidUpdates) {
        stage(invalid, bytes, signed)
        check(runCatching { LoaderInstaller.apply(invalid, config) }.isFailure)
        check(Files.readAllBytes(invalid.resolve("mods/Sky-Myce.jar")).contentEquals(old))
        check(Files.readAllBytes(invalid.resolve(".sky-myce/Sky-Myce.pending.jar")).contentEquals(bytes))
    }
    val occupied = instance("unrelated-target")
    Files.write(occupied.resolve("mods/Sky-Myce.jar"), unrelated)
    Files.write(occupied.resolve("mods/renamed-old.jar"), old)
    stage(occupied)
    check(runCatching { LoaderInstaller.apply(occupied, config) }.isFailure)
    check(Files.readAllBytes(occupied.resolve("mods/Sky-Myce.jar")).contentEquals(unrelated))
    check(Files.readAllBytes(occupied.resolve("mods/renamed-old.jar")).contentEquals(old))

    val legacy = instance("bootstrap-repair")
    Files.write(legacy.resolve("mods/old-manual-install.jar"), old)
    Files.write(legacy.resolve("mods/Sky-Myce-unrelated.jar"), unrelated)
    Files.writeString(legacy.resolve(".sky-myce/manifest.json"), "corrupted manifest")
    LoaderInstaller.prepareBootstrap(legacy, config)
    check(LoaderInstaller.payloads(legacy).isEmpty() && backedUp(legacy, old))
    check(Files.readAllBytes(legacy.resolve("mods/Sky-Myce-unrelated.jar")).contentEquals(unrelated))
    check(!Files.exists(legacy.resolve(".sky-myce/manifest.json")))
    val valid = instance("bootstrap-verified")
    installOld(valid)
    Files.write(valid.resolve("mods/duplicate.jar"), old)
    LoaderInstaller.prepareBootstrap(valid, config)
    check(LoaderInstaller.payloads(valid).size == 1 && backedUp(valid, old))

    val loaderGame = instance("duplicate-loaders")
    val loader = mod("skymyce-loader", "1.0.1")
    val oldLoader = mod("skymyce-loader", "1.0.0")
    val source = root.resolve("downloaded-loader.jar").also { Files.write(it, loader) }
    Files.write(loaderGame.resolve("mods/renamed-loader.jar"), oldLoader)
    Files.write(loaderGame.resolve("mods/Sky-Myce-Loader.jar"), oldLoader)
    LoaderInstaller.installBootstrap(loaderGame, source)
    check(Files.readAllBytes(loaderGame.resolve("mods/Sky-Myce-Loader.jar")).contentEquals(loader))
    check(!Files.exists(loaderGame.resolve("mods/renamed-loader.jar")) && backedUp(loaderGame, oldLoader))
    val installedSource = loaderGame.resolve("mods/renamed-current-loader.jar")
    Files.move(loaderGame.resolve("mods/Sky-Myce-Loader.jar"), installedSource)
    Files.write(loaderGame.resolve("mods/duplicate-loader.jar"), oldLoader)
    LoaderInstaller.installBootstrap(loaderGame, installedSource)
    check(Files.readAllBytes(installedSource).contentEquals(loader))
    check(!Files.exists(loaderGame.resolve("mods/duplicate-loader.jar")))
    check(!Files.exists(loaderGame.resolve("mods/Sky-Myce-Loader.jar")))

    // A read-only destination makes the manifest commit fail after the payload has been replaced.
    if (System.getProperty("os.name").startsWith("Windows")) {
        val rollback = instance("rollback")
        installOld(rollback); stage(rollback)
        val current = rollback.resolve(".sky-myce/manifest.json")
        val previous = Files.readAllBytes(current)
        Files.setAttribute(current, "dos:readonly", true)
        try {
            check(runCatching { LoaderInstaller.apply(rollback, config) }.isFailure)
            check(Files.readAllBytes(rollback.resolve("mods/Sky-Myce.jar")).contentEquals(old))
            check(Files.readAllBytes(current).contentEquals(previous))
            check(Files.readAllBytes(rollback.resolve(".sky-myce/Sky-Myce.pending.jar")).contentEquals(updated))
        } finally { Files.setAttribute(current, "dos:readonly", false) }
    }
    val waiting = instance("post-exit")
    stage(waiting)
    val helper = installerHelperJar(root.resolve("helper.jar"), config)
    checkPostExit(waiting, helper, updated)
    checkRestart(root.resolve("launcher-independent-restart"), helper)
}

/** Standalone child whose lifetime represents Minecraft, with no game or production credentials. */
object InstallerCheckChild {
    @JvmStatic fun main(arguments: Array<String>) {
        val helper = LoaderInstaller.applyAfterExit(Path.of(arguments[0]), Path.of(arguments[1]))
        SignedFiles.atomicWrite(Path.of(arguments[2]), helper.pid().toString().toByteArray())
        System.`in`.read()
    }
}

private fun installerHelperJar(file: Path, config: Properties): Path {
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = LoaderInstaller::class.java.name
    }
    JarOutputStream(Files.newOutputStream(file), manifest).use { jar ->
        val types = listOf(LoaderInstaller::class.java, LoaderRestart::class.java, RestartNatives::class.java,
            SignedFiles::class.java, InstallerCheckChild::class.java)
        for (type in types + types.flatMap { it.declaredClasses.toList() }) {
            val name = type.name.replace('.', '/') + ".class"
            jar.putNextEntry(JarEntry(name))
            requireNotNull(type.getResourceAsStream("/$name")).use { it.copyTo(jar) }
            jar.closeEntry()
        }
        // File-tree visitor anonymous classes are part of the standalone native-preservation helper.
        for (suffix in listOf("1", "2")) {
            val name = RestartNatives::class.java.name.replace('.', '/') + "$$suffix.class"
            jar.putNextEntry(JarEntry(name))
            requireNotNull(RestartNatives::class.java.getResourceAsStream("/$name")).use { it.copyTo(jar) }
            jar.closeEntry()
        }
        JarFile(Path.of(Gson::class.java.protectionDomain.codeSource.location.toURI()).toFile()).use { gson ->
            for (entry in gson.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/") || entry.name == "module-info.class") continue
                jar.putNextEntry(JarEntry(entry.name))
                gson.getInputStream(entry).use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        jar.putNextEntry(JarEntry("sky-myce-loader.properties"))
        config.store(jar, "Ephemeral installer test trust")
        jar.closeEntry()
    }
    return file
}

private fun checkPostExit(game: Path, helperJar: Path, expected: ByteArray) {
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val kotlinRuntime = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
    val ready = game.resolve("helper-ready")
    val child = ProcessBuilder(java.toString(), "-cp", "$helperJar${File.pathSeparator}$kotlinRuntime",
        InstallerCheckChild::class.java.name, game.toString(), helperJar.toString(), ready.toString())
        .directory(game.toFile())
        .redirectErrorStream(true).redirectOutput(game.resolve("child.log").toFile()).start()
    var helper: ProcessHandle? = null
    fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) { check(System.nanoTime() < deadline) { "Installer child timed out" }; Thread.sleep(20) }
    }
    try {
        waitUntil { Files.exists(ready) || !child.isAlive }
        check(child.isAlive && Files.exists(ready)) { "Installer child did not start" }
        val waitingHelper = ProcessHandle.of(Files.readString(ready).toLong()).orElseThrow()
        helper = waitingHelper
        Thread.sleep(350)
        check(child.isAlive && waitingHelper.isAlive)
        check(Files.exists(game.resolve(".sky-myce/Sky-Myce.pending.jar")))
        check(!Files.exists(game.resolve("mods/Sky-Myce.jar")))
        child.outputStream.use { it.write('\n'.code); it.flush() }
        check(child.waitFor(15, TimeUnit.SECONDS) && child.exitValue() == 0)
        waitUntil { !waitingHelper.isAlive }
        check(Files.readAllBytes(game.resolve("mods/Sky-Myce.jar")).contentEquals(expected))
        check(!Files.exists(game.resolve(".sky-myce/Sky-Myce.pending.jar")))
        check(!Files.exists(game.resolve(".sky-myce/pending-manifest.json")))
    } finally {
        if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
        helper?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
    }
}
