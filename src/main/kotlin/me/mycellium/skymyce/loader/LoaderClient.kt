package me.mycellium.skymyce.loader

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.lang.management.ManagementFactory

class LoaderEntrypoint : ClientModInitializer { override fun onInitializeClient() = LoaderClient.initialize() }

object LoaderClient {
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val config by lazy { SignedFiles.configuration() }
    private lateinit var installation: Installation
    val authorization = AuthorizationState()
    private val stateDirectory get() = FabricLoader.getInstance().gameDir.resolve(".sky-myce")
    @Volatile private var targetVersion: String? = null
    private val version get() = targetVersion ?: config.getProperty("modVersion")
    private val loadedVersion get() = FabricLoader.getInstance().getModContainer("skymyce").map { it.metadata.version.friendlyString }.orElse("")
    private var release: JsonObject? = null
    private var downloadedVersion: String? = null
    private var uuid = ""
    private var nextAttempt = 0L
    private var failures = 0
    private var busy = false
    private var restartPreparing = false
    val isBusy get() = busy || restartPreparing
    var restartRequired = false
        private set
    private var updateInstaller: Process? = null
    var canRestart = false
        private set
    private var initialized = false
    private val generation get() = authorization.generation
    private var showAuthorization = true
    private var checkedPayload = false
    private var lastAllowed = false
    private val observers = mutableListOf<(Boolean) -> Unit>()
    var status = "Checking your Sky-Myce installation…"
        private set
    val localAllowed get() = checkedPayload && authorization.localAllowed
    val onlineAllowed get() = checkedPayload && authorization.onlineAllowed
    val hasPayload get() = FabricLoader.getInstance().isModLoaded("skymyce")
    fun observe(handler: (Boolean) -> Unit) { observers += handler; handler(localAllowed) }
    fun initialize() {
        if (initialized) return
        initialized = true
        showAuthorization = !hasPayload
        registerLoaderMenu()
        uuid = Minecraft.getInstance().user.profileId.toString().replace("-", "")
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommands.literal("skymyce-auth").executes { Minecraft.getInstance().setScreen(AuthorizationScreen()); 1 })
        }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        work({ installation = Installation() }) { canRestart = restartRuntimeAvailable(); nextAttempt = 0 }
    }
    private fun tick() {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.user.profileId.toString().replace("-", "")
        if (uuid != current) { busy = false; uuid = current; authorization.deny(); checkedPayload = false; nextAttempt = 0; showAuthorization = true }
        val allowed = localAllowed
        if (lastAllowed != allowed) { lastAllowed = allowed; observers.toList().forEach { it(allowed) } }
        if (showAuthorization && minecraft.screen is TitleScreen && !busy && !localAllowed) { showAuthorization = false; minecraft.setScreen(AuthorizationScreen()) }
        if (localAllowed) showAuthorization = false
        if (!busy && ::installation.isInitialized && System.nanoTime() >= nextAttempt) renew()
    }
    private fun endpoint(path: String): URI {
        val base = URI(config.getProperty("backend"))
        require(base.scheme == "https" && base.userInfo == null && base.rawQuery == null && base.fragment == null && base.path in setOf("", "/"))
        return base.resolve(path)
    }
    private fun request(path: String, body: Any): HttpRequest = HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(if (path == "/loader/artifact") 90 else 20))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build()
    private class Denied(val code: String) : Exception()
    private fun discoverRelease() {
        val response = http.send(HttpRequest.newBuilder(endpoint("/loader/release")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 16384))
        val bytes = response.body()
        if (response.statusCode() != 200) throw java.io.IOException("Release unavailable")
        require(bytes.size <= 16384)
        val envelope = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        val manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis())
        val published = manifest.get("version").asString
        require(SignedFiles.compareVersion(published, if (loadedVersion.isNotBlank()) loadedVersion else config.getProperty("modVersion")) >= 0)
        targetVersion = published; release = envelope
    }
    private fun post(path: String, body: Any): JsonObject {
        val response = http.send(request(path, body), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 32768))
        val bytes = response.body()
        require(bytes.size <= 32768)
        if (response.statusCode() in setOf(408, 429) || response.statusCode() >= 500) throw java.io.IOException("Backend temporarily unavailable")
        if (response.statusCode() in 400..499) {
            val code = runCatching { JsonParser.parseString(String(bytes)).asJsonObject.get("error").asString }.getOrDefault("unauthorized")
            throw Denied(code)
        }
        if (response.statusCode() != 200) throw java.io.IOException("Backend unavailable")
        return JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
    }
    private fun challenge(purpose: String): JsonObject {
        val clientNonce = UUID.randomUUID().toString()
        return post("/auth/challenge", mapOf("purpose" to purpose, "clientNonce" to clientNonce,
            "installationId" to installation.id, "publicKey" to installation.publicKey, "deviceHash" to installation.deviceHash,
            "minecraftVersion" to config.getProperty("minecraft"), "loaderVersion" to config.getProperty("loaderVersion"), "modVersion" to version))
            .also { it.addProperty("clientNonce", clientNonce) } // Retain our nonce, never trust an echoed value.
    }
    private fun signature(purpose: String, challenge: JsonObject) = installation.sign("Sky-Myce installation v1\n$purpose\n${challenge.get("nonce").asString}\n${installation.id}\n${installation.deviceHash}\n${config.getProperty("minecraft")}\n${config.getProperty("loaderVersion")}\n$version\n${challenge.get("clientNonce").asString}")
    fun confirmActivation(key: String) {
        if (busy || !::installation.isInitialized) return
        if (!key.matches(Regex("[A-Za-z0-9_-]{43}"))) { status = "Paste the complete activation code from your Sky-Myce DM, then click Confirm."; return }
        status = "Confirming your code and signed-in Minecraft account…"
        val account = uuid
        val epoch = generation
        work({
            discoverRelease()
            val pending = challenge("activate")
            val proof = MinecraftProof.create(pending.get("nonce").asString).get(30, TimeUnit.SECONDS)
            check(activationRequestCurrent(epoch, generation, account,
                Minecraft.getInstance().user.profileId.toString().replace("-", ""), proof["uuid"])) { "Minecraft account changed" }
            post("/auth/activate", mapOf("challengeId" to pending.get("challengeId").asString, "minecraftProof" to proof,
                "signature" to signature("activate", pending), "activationKey" to key)) to pending
        }) { if (account == uuid) accept(it.first, it.second.get("challengeId").asString, it.second.get("clientNonce").asString) }
    }
    private fun renew() {
        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val account = uuid
        work({
            discoverRelease()
            val nonce = challenge("renew")
            val proof = MinecraftProof.create(nonce.get("nonce").asString).get(30, TimeUnit.SECONDS)
            post("/auth/renew", mapOf("challengeId" to nonce.get("challengeId").asString,
                "minecraftProof" to proof, "signature" to signature("renew", nonce))) to nonce
        }) { if (account == uuid) accept(it.first, it.second.get("challengeId").asString, it.second.get("clientNonce").asString) }
    }
    private fun accept(response: JsonObject, challengeId: String, clientNonce: String) {
        val token = response.get("lease").asString
        val claims = verifyLease(token, config, installation, uuid, version, challengeId, clientNonce)
        val account = uuid
        val epoch = generation
        work({
            var matches = hasPayload && loadedVersion == version
            if (matches) {
                val envelope = requireNotNull(release)
                val manifest = SignedFiles.manifest(envelope, config, response.get("serverTime").asLong)
                require(manifest.get("version").asString == version)
                val origin = FabricLoader.getInstance().getModContainer("skymyce").orElseThrow().origin.paths.single()
                // A valid current lease can acquire a repair; mismatched loaded bytes never gain access.
                try { SignedFiles.verifyArtifact(origin, manifest) }
                catch (_: SecurityException) { matches = false }
                if (matches) SignedFiles.atomicWrite(stateDirectory.resolve("manifest.json"), gson.toJson(envelope).toByteArray())
            }
            installation.persistBoundary(token)
            matches
        }) { matches ->
            if (uuid != account) return@work
            checkedPayload = matches
            authorization.accept(token, claims, response.get("serverTime").asLong, epoch)
            failures = 0
            nextAttempt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(((claims.get("exp").asLong - response.get("serverTime").asLong) / 2).coerceAtMost(120000))
            status = if (restartRequired) "Sky-Myce is ready. Click Restart Minecraft to finish installation." else "Sky-Myce is ready. You can continue to Minecraft."
            if (!checkedPayload && downloadedVersion != version) downloadUpdate()
        }
    }
    fun relayAuthorization(nonce: String): Map<String, String> {
        check(onlineAllowed)
        val lease = authorization.lease ?: error("Authorization required")
        return mapOf("lease" to lease, "installationSignature" to installation.sign("Sky-Myce relay v3\n$nonce\n$lease"))
    }
    fun deny(revoked: Boolean = false) {
        busy = false
        authorization.deny()
        nextAttempt = Long.MAX_VALUE
        status = (if (revoked) "Your Sky-Myce license has been revoked. " else "") + "Sky-Myce has been disabled. Please contact the Sky-Myce owners if you believe this was a mistake."
        observers.toList().forEach { it(false) }; lastAllowed = false
        val minecraft = Minecraft.getInstance()
        minecraft.setScreen(AuthorizationScreen())
    }
    fun retry() {
        if (busy) return
        authorization.invalidatePending(); nextAttempt = 0
        status = "Checking your Sky-Myce account again…"
        if (!::installation.isInitialized) work({ installation = Installation() }) { canRestart = restartRuntimeAvailable(); nextAttempt = 0 }
    }
    private fun restartRuntimeAvailable() = runCatching { FabricLauncherBase.getLauncher().classPath.isNotEmpty() }.getOrDefault(false)
    fun requestRestart() {
        if (busy || restartPreparing) return
        if (!canRestart) { status = "This instance's restart settings are unavailable. Minecraft has been left open."; return }
        val minecraft = Minecraft.getInstance()
        val account = minecraft.user.profileId
        val epoch = generation
        // Launchers may pass their original arguments through stdin, so capture Fabric's parsed
        // game arguments instead of replaying the launcher-specific JVM entry point.
        val user = minecraft.user
        val current = linkedMapOf("username" to user.name, "uuid" to user.profileId.toString(),
            "accessToken" to user.accessToken, "clientId" to user.clientId.orElse(""), "xuid" to user.xuid.orElse(""),
            "gameDir" to FabricLoader.getInstance().gameDir.toAbsolutePath().toString())
        val original = FabricLoader.getInstance().getLaunchArguments(false)
        val arguments = mutableListOf<String>()
        var index = 0
        while (index < original.size) {
            val argument = original[index++]
            val key = argument.removePrefix("--").substringBefore('=')
            if (argument.startsWith("--") && key in current) {
                if ('=' !in argument && index < original.size) index++
            } else arguments += argument
        }
        current.forEach { (key, value) -> arguments += "--$key"; arguments += value }
        val properties = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("fabric.") || it in setOf("java.library.path", "org.lwjgl.librarypath", "java.io.tmpdir") }
            .associateWith { System.getProperty(it) }
        val classPath = FabricLauncherBase.getLauncher().classPath.toList()
        val vmArguments = ManagementFactory.getRuntimeMXBean().inputArguments.toList()
        status = "Preparing to restart this Minecraft instance…"
        restartPreparing = true
        busy = true
        var preparingInstaller: Process? = null
        CompletableFuture.supplyAsync {
            val helper = ensureInstaller().also { preparingInstaller = it }
            LoaderRestart.request(helper, Path.of(System.getProperty("user.dir")), vmArguments, classPath, properties, arguments.toTypedArray())
            helper
        }.whenComplete { _, failure -> minecraft.execute {
            val helper = preparingInstaller
            restartPreparing = false
            if (epoch != generation || account != minecraft.user.profileId) {
                helper?.destroyForcibly() // Never restart later with a now-stale account.
                if (updateInstaller === helper) updateInstaller = null
                CompletableFuture.runAsync { runCatching { ensureInstaller() } }
                return@execute
            }
            busy = false
            if (failure == null && helper != null && runCatching { LoaderRestart.commit(helper) }.isSuccess) minecraft.stop()
            else {
                helper?.destroyForcibly()
                if (updateInstaller === helper) updateInstaller = null
                val step = when (LoaderRestart.failureStage(failure)) {
                    "RUNTIME_LIBRARIES" -> "runtime libraries"
                    "NATIVE_LIBRARIES" -> "native libraries"
                    "WRAPPER" -> "restart helper"
                    "JVM_OPTIONS" -> "Java settings"
                    "TIMEOUT" -> "preparation timeout"
                    else -> "helper connection"
                }
                status = "Restart preparation failed ($step). Minecraft is still open; your verified update is safe. Details are in .sky-myce/update.log."
                // Preserve normal quit-to-update behavior if a restart attempt could not be prepared.
                CompletableFuture.runAsync { runCatching { ensureInstaller() } }
            }
        } }
    }
    @Synchronized private fun ensureInstaller(): Process {
        if (updateInstaller?.isAlive != true) {
            val loader = FabricLoader.getInstance().getModContainer("skymyce-loader").orElseThrow().origin.paths.single()
            updateInstaller = LoaderInstaller.applyAfterExit(FabricLoader.getInstance().gameDir, loader)
            if (updateInstaller!!.waitFor(250, TimeUnit.MILLISECONDS)) throw java.io.IOException("Installer did not start")
        }
        return updateInstaller!!
    }
    private class InstallFailure(cause: Exception) : java.io.IOException(cause)
    fun downloadUpdate() {
        if (busy || !authorization.onlineAllowed) return
        val epoch = generation
        status = "Verifying the current Sky-Myce release…"
        work({
            val manifestChallenge = challenge("manifest")
            val envelope = post("/loader/manifest", mapOf("challengeId" to manifestChallenge.get("challengeId").asString,
                "lease" to authorization.lease, "signature" to signature("manifest", manifestChallenge)))
            val manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis())
            require(SignedFiles.compareVersion(manifest.get("version").asString, version) >= 0)
            val nonce = challenge("artifact")
            Files.createDirectories(stateDirectory)
            val temporary = Files.createTempFile(stateDirectory, "release-", ".tmp")
            try {
                val response = http.send(request("/loader/artifact", mapOf("challengeId" to nonce.get("challengeId").asString,
                    "lease" to authorization.lease, "signature" to signature("artifact", nonce))),
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(temporary), 128L * 1024 * 1024))
                if (response.statusCode() in setOf(408, 429) || response.statusCode() >= 500) throw java.io.IOException("Artifact temporarily unavailable")
                if (response.statusCode() != 200) throw SecurityException("Artifact unavailable")
                SignedFiles.verifyArtifact(temporary, manifest)
                check(generation == epoch && authorization.onlineAllowed) { "Authorization changed" }
                val pending = stateDirectory.resolve("Sky-Myce.pending.jar")
                Files.move(temporary, pending, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                SignedFiles.atomicWrite(stateDirectory.resolve("pending-manifest.json"), gson.toJson(envelope).toByteArray())
                try {
                    if (!hasPayload) LoaderInstaller.apply(FabricLoader.getInstance().gameDir)
                    else ensureInstaller()
                } catch (failure: Exception) { throw InstallFailure(failure) }
            } finally { Files.deleteIfExists(temporary) }
        }) {
            downloadedVersion = version; restartRequired = true; showAuthorization = true
            status = if (canRestart) "Download verified. Click Restart Minecraft to finish; this instance will reopen automatically."
                else "Download verified. Quit and start Minecraft once to finish setup. You do not need to delete any mod files."
        }
    }
    private fun <T> work(action: () -> T, success: (T) -> Unit) {
        val epoch = generation
        busy = true
        CompletableFuture.supplyAsync(action).whenComplete { value, failure -> Minecraft.getInstance().execute {
            if (epoch != generation) return@execute
            busy = false
            if (failure == null) runCatching { success(value) }.onFailure { deny() }
            else {
                val cause = generateSequence(failure) { it.cause }.last()
                if (generateSequence(failure) { it.cause }.any { it is InstallFailure }) {
                    nextAttempt = Long.MAX_VALUE
                    status = "The download is verified, but installation could not finish. Close Minecraft and run the loader JAR again for this instance. Your download has been kept."
                    return@execute
                }
                when (cause) {
                    is Denied -> {
                        if (cause.code == "approval_required") {
                            status = "This loader needs a fresh activation code. Ask an admin to use /authorise with reissue:true."
                            nextAttempt = Long.MAX_VALUE
                            return@execute
                        }
                        if (cause.code in setOf("license_revoked", "account_suspended", "installation_revoked", "authorization_revoked")) {
                            deny(cause.code == "license_revoked"); return@execute
                        }
                        authorization.deny(); nextAttempt = Long.MAX_VALUE
                        showAuthorization = true
                        status = when (cause.code) {
                            "challenge_expired", "invalid_challenge" -> "The sign-in request expired. Paste the same code again and click Confirm."
                            "activation_invalid", "invalid_activation", "invalid_activation_key" -> "The activation code was not accepted. Use the code from your latest Sky-Myce DM, or ask an admin for a replacement."
                            "approval_pending" -> "Enter the activation code from your Sky-Myce DM and click Confirm."
                            else -> "Enter the code from your Sky-Myce DM and click Confirm. If you need a new code, ask an admin to use /authorise."
                        }
                    }
                    is SecurityException, is IllegalArgumentException -> deny()
                    else -> {
                        failures++
                        if (failures >= 3) authorization.unavailable()
                        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (failures < 3) 1L shl failures else 30L)
                        status = if (localAllowed) "Sky-Myce backend unavailable. Limited local mode; IRC is disabled." else "Sky-Myce authorization unavailable. Retry when the backend is reachable."
                    }
                }
            }
        } }
    }
}
