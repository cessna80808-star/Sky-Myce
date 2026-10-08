package me.mycellium.skymyce.loader

import com.google.gson.Gson
import com.google.gson.JsonObject
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.UUID
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
        checkInstallation(temporary.resolve("hardware"))
        checkInstallationDirectory(temporary.resolve("home-directory"))
        val installation = fixtureInstallation(temporary.resolve("identity"))
        val reload = fixtureInstallation(temporary.resolve("identity"))
        check(installation.id == reload.id && installation.publicKey == reload.publicKey && installation.deviceHash == reload.deviceHash && installation.hardwareHash == reload.hardwareHash)
        checkLaunchProof(installation, config)
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            val identity = temporary.resolve("identity")
            check(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(identity)) == "rwx------")
            check(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(identity.resolve("identity.properties"))) == "rw-------")
            val proof = "test Linux installation persistence"
            check(installation.sign(proof) == reload.sign(proof))
        }
        val copiedGame = fixtureInstallation(temporary.resolve("other-device"))
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
        checkLoaderUpdate(temporary.resolve("loader-update"), config, ::envelope)
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
            addProperty("hardwareHash", installation.hardwareHash)
            addProperty("keyFingerprint", SignedFiles.sha256(Base64.getDecoder().decode(installation.publicKey)))
            addProperty("version", "2.4.2"); addProperty("minecraft", "26.1.2"); addProperty("iat", now)
            addProperty("exp", now + 300000); addProperty("offlineUntil", now + 900000); addProperty("generation", 0); addProperty("jti", "unique")
            addProperty("challengeId", "request-1"); add("features", com.google.gson.JsonArray().apply { add("relay"); add("irc") })
            addProperty("clientNonce", "fresh-client-nonce")
            addProperty("platform", LaunchSession.platform); addProperty("launchSessionId", LaunchSession.id)
        }
        fun token(value: JsonObject): String {
            val header = encode.encodeToString("{\"alg\":\"EdDSA\",\"kid\":\"test\",\"typ\":\"JWT\"}".toByteArray())
            val payload = encode.encodeToString(Gson().toJson(value).toByteArray())
            val unsigned = "$header.$payload"
            return "$unsigned.${sign(unsigned.toByteArray())}"
        }
        val lease = token(claims)
        fun reference(value: String, trust: Properties = config) = signedHardwareReference(value, trust, installation.id, installation.deviceHash, installation.publicKey)
        check(reference(lease) == installation.hardwareHash)
        val expiredClaims = claims.deepCopy().apply {
            addProperty("iat", now - 600000); addProperty("exp", now - 300000); addProperty("offlineUntil", now - 1)
            remove("platform"); remove("launchSessionId")
        }
        val expired = token(expiredClaims)
        check(reference(expired) == installation.hardwareHash) // Signature still proves a prior encoding, never authorization.
        val neverAuthorized = AuthorizationState()
        reject { neverAuthorized.accept(expired, expiredClaims, now) }
        check(!neverAuthorized.localAllowed && !neverAuthorized.onlineAllowed && !LaunchSession.authorized)
        check(reference(lease.dropLast(10) + "AAAAAAAAAA") == null)
        check(reference("not-a-signed-lease") == null)
        val otherTrust = Properties().apply {
            putAll(config)
            setProperty("key.test", Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded))
        }
        check(reference(lease, otherTrust) == null)
        for ((key, value) in listOf("product" to "other", "installationId" to UUID.randomUUID().toString(),
            "deviceHash" to "b".repeat(64), "keyFingerprint" to "b".repeat(64), "hardwareHash" to "not-a-hardware-hash")) {
            check(reference(token(claims.deepCopy().apply { addProperty(key, value) })) == null)
        }
        check(reference(token(claims.deepCopy().apply { addProperty("protocol", 2) })) == null)
        check(reference(token(claims.deepCopy().apply { remove("hardwareHash") })) == null)
        verifyLease(lease, config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce")
        reject { verifyLease(token(claims.deepCopy().apply { remove("hardwareHash") }), config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease.dropLast(10) + "AAAAAAAAAA", config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, copiedGame, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, "b".repeat(32), "2.4.2", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, uuid, "2.4.1", "request-1", "fresh-client-nonce") }
        reject { verifyLease(lease, config, installation, uuid, "2.4.2", "different-request", "fresh-client-nonce") }
        // Even replaying both the old challenge response and its matching lease cannot match a new local nonce.
        reject { verifyLease(lease, config, installation, uuid, "2.4.2", "request-1", "another-fresh-client-nonce") }
        listOf("product" to "other", "deviceHash" to "b".repeat(64), "hardwareHash" to "b".repeat(64), "keyFingerprint" to "b".repeat(64), "minecraft" to "26.3",
            "platform" to "invalid", "launchSessionId" to UUID.randomUUID().toString()).forEach { (key, value) ->
            reject { verifyLease(token(claims.deepCopy().apply { addProperty(key, value) }), config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        }
        for (key in listOf("platform", "launchSessionId")) {
            reject { verifyLease(token(claims.deepCopy().apply { remove(key) }), config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
            reject { verifyLease(token(claims.deepCopy().apply { addProperty(key, 42) }), config, installation, uuid, "2.4.2", "request-1", "fresh-client-nonce") }
        }
        installation.persistBoundary(lease)
        checkLaunchProcesses(temporary.resolve("identity"), installation)
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
        check(fixtureInstallation(temporary.resolve("identity")).hardwareHash == installation.hardwareHash) // Session denial/expiry never resets hardware.
        reject { state.accept(lease, claims, now, beforeRevocation) }
        check(!state.localAllowed) // An in-flight pre-revoke renewal cannot re-enable the mod.
        LaunchSession.accept()
        state.deny(); state.unavailable()
        check(LaunchSession.authorized) // Retry/denial never turns this JVM into a fresh launch.
        val irc = IrcMode(); val sent = mutableListOf<String>()
        check(irc.chat("public", sent::add)); check(sent.isEmpty())
        check(!irc.command("chat irc")); check(irc.selected)
        check(!irc.chat("private", sent::add)); check(sent == listOf("private"))
        check(!irc.chat("offline") { }); check(irc.selected) // Never falls through to the server.
        check(irc.command("sm msg Alice hello") && irc.selected)
        check(irc.command("chat p") && !irc.selected && irc.chat("party", sent::add))
        check(!irc.command(" CHAT   IRC ")); check(irc.command("chat a") && !irc.selected)
        println("Sky-Myce loader checks passed: signatures, artifact tamper, duplicate repair, install rollback, post-exit helper, pinned keys, launch/session bindings, stable device across fresh JVMs, monotonic grace, revoke and sticky IRC")
    } finally { Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
}

private fun checkLaunchProof(installation: Installation, config: Properties) {
    val nonce = UUID.randomUUID().toString()
    val api = LoaderApi(config, installation, "2.4.2")
    val first = api.challengeInput("renew", true, nonce)
    val renewal = LoaderApi(config, installation, "2.4.2").challengeInput("renew", false, nonce)
    check(first["launchSessionId"] == LaunchSession.id && first["platform"] == LaunchSession.platform && first["startSession"] == true)
    check(renewal["launchSessionId"] == first["launchSessionId"] && renewal["startSession"] == false)
    check(first["hardwareHash"] == installation.hardwareHash && renewal["hardwareHash"] == first["hardwareHash"])
    check(UUID.fromString(LaunchSession.id).version() == 4)
    check(!LaunchSession.authorized)
    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(installation.publicKey)))
    for (purpose in listOf("renew", "activate", "manifest", "artifact", "bootstrap-renew", "bootstrap-activate")) {
        for (start in listOf(true, false)) {
            val challenge = JsonObject().apply {
                addProperty("nonce", "a".repeat(43)); addProperty("clientNonce", nonce); addProperty("startSession", start)
                // Server-echoed platform/ID cannot replace this process's signed metadata.
                addProperty("platform", "invalid"); addProperty("launchSessionId", UUID.randomUUID().toString()); addProperty("hardwareHash", "b".repeat(64))
            }
            val message = "Sky-Myce installation v3\n$purpose\n${challenge.get("nonce").asString}\n${installation.id}\n${installation.deviceHash}\n26.1.2\n1.0.0\n2.4.2\n$nonce\n${installation.hardwareHash}\n${LaunchSession.platform}\n${LaunchSession.id}\n$start"
            val signature = Base64.getDecoder().decode(api.signature(purpose, challenge))
            fun verifies(text: String) = Signature.getInstance("Ed25519").run {
                initVerify(publicKey); update(text.toByteArray()); verify(signature)
            }
            check(verifies(message))
            check(!verifies(message.substringBeforeLast('\n') + "\n${!start}"))
            check(!verifies(message.replace(LaunchSession.id, UUID.randomUUID().toString())))
            check(!verifies(message.replace("\n${LaunchSession.platform}\n", "\ninvalid\n")))
            check(!verifies(message.replace(installation.hardwareHash, "b".repeat(64))))
        }
    }
    check(launchPlatform("Linux") == "linux" && launchPlatform("LINUX") == "linux")
    check(launchPlatform("Windows 11") == "windows" && launchPlatform("Mac OS X") == "macos")
    check(launchPlatform("Darwin") == "macos" && launchPlatform("FreeBSD") == "other")
}

private fun checkLaunchProcesses(identity: Path, expected: Installation) {
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val classpath = listOf(LaunchSessionCheckChild::class.java, Installation::class.java, SignedFiles::class.java, Unit::class.java, Gson::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
    val ids = mutableSetOf(LaunchSession.id)
    for (identifier in listOf(FIXTURE_HARDWARE_UUID.replace("-", ""), "{${FIXTURE_HARDWARE_UUID.uppercase()}}", "\uFEFF$FIXTURE_HARDWARE_UUID")) {
        // Windows argv can replace a Unicode BOM with '?'; keep the synthetic provider output byte-identical.
        val encodedIdentifier = Base64.getEncoder().encodeToString(identifier.toByteArray(Charsets.UTF_8))
        val child = ProcessBuilder(java.toString(), "-cp", classpath, LaunchSessionCheckChild::class.java.name, identity.toString(), encodedIdentifier)
            .redirectErrorStream(true).start()
        try {
            check(child.waitFor(30, TimeUnit.SECONDS)) { "Launch session child timed out" }
            val output = child.inputStream.bufferedReader().readText()
            check(child.exitValue() == 0) { "Launch session child failed: $output" }
            val result = com.google.gson.JsonParser.parseString(output).asJsonObject
            check(result.get("id").asString == expected.id && result.get("deviceHash").asString == expected.deviceHash && result.get("publicKey").asString == expected.publicKey)
            check(result.get("hardwareHash").asString == expected.hardwareHash)
            check(result.get("platform").asString == LaunchSession.platform)
            check(ids.add(result.get("launchSessionId").asString))
            check(!result.get("authorized").asBoolean && !result.get("localAllowed").asBoolean)
        } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) } }
    }
}

/** Independent game/loader JVMs, synthetic identity only; output never contains a private key. */
object LaunchSessionCheckChild {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 2)
        val directory = Path.of(arguments[0])
        val identifier = String(Base64.getDecoder().decode(arguments[1]), Charsets.UTF_8)
        val installation = fixtureInstallation(directory, identifier)
        val again = fixtureInstallation(directory, FIXTURE_HARDWARE_UUID)
        check(installation.id == again.id && installation.deviceHash == again.deviceHash && installation.publicKey == again.publicKey && installation.hardwareHash == again.hardwareHash)
        check(Files.exists(directory.resolve("last-signed-lease")))
        println(Gson().toJson(mapOf("id" to installation.id, "deviceHash" to installation.deviceHash, "hardwareHash" to installation.hardwareHash, "publicKey" to installation.publicKey,
            "launchSessionId" to LaunchSession.id, "platform" to LaunchSession.platform, "authorized" to LaunchSession.authorized,
            "localAllowed" to AuthorizationState().localAllowed)))
    }
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

internal fun installerHelperJar(file: Path, config: Properties): Path {
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = LoaderInstaller::class.java.name
    }
    JarOutputStream(Files.newOutputStream(file), manifest).use { jar ->
        val types = listOf(LoaderInstaller::class.java, LoaderPreferences::class.java, LoaderRestart::class.java, RestartNatives::class.java,
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
        jar.putNextEntry(JarEntry("fabric.mod.json"))
        jar.write("""{"schemaVersion":1,"id":"skymyce-loader","version":"${config.getProperty("loaderVersion")}"}""".toByteArray())
        jar.closeEntry()
    }
    return file
}

internal fun checkPostExit(game: Path, helperJar: Path, expected: ByteArray, loaderUpdate: Boolean = false) {
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val kotlinRuntime = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
    val ready = game.resolve("helper-ready")
    val pending = game.resolve(if (loaderUpdate) ".sky-myce/Loader.pending.jar" else ".sky-myce/Sky-Myce.pending.jar")
    val metadata = game.resolve(if (loaderUpdate) ".sky-myce/loader-pending-manifest.json" else ".sky-myce/pending-manifest.json")
    val target = game.resolve(if (loaderUpdate) "mods/Sky-Myce-Loader.jar" else "mods/Sky-Myce.jar")
    val before = if (Files.exists(target)) Files.readAllBytes(target) else null
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
        check(Files.exists(pending))
        check(if (before == null) !Files.exists(target) else Files.readAllBytes(target).contentEquals(before))
        child.outputStream.use { it.write('\n'.code); it.flush() }
        check(child.waitFor(15, TimeUnit.SECONDS) && child.exitValue() == 0)
        waitUntil { !waitingHelper.isAlive }
        check(Files.readAllBytes(target).contentEquals(expected))
        check(!Files.exists(pending) && !Files.exists(metadata))
    } finally {
        if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
        helper?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
    }
}
