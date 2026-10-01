package me.mycellium.skymyce.loader

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.system.exitProcess

internal fun startupActivationAllowed(code: String?) = code in setOf("installation_required", "approval_required", "approval_pending",
    "account_required", "minecraft_link_required", "invalid_activation_key", "invalid_activation", "activation_invalid", "challenge_expired", "invalid_challenge")

internal class StartupHandoffLimit : Exception()
internal fun requireStartupHandoffAllowed(attempted: String?) { if (attempted != null) throw StartupHandoffLimit() }

internal fun startupError(failure: Throwable): String {
    val cause = generateSequence(failure) { it.cause }.last()
    return when (cause) {
        is StartupHandoffLimit -> "The release changed again during startup. Close this window and launch your instance once more. Automatic startup has stopped to prevent a relaunch loop."
        is AuthorizationDenied -> accountAccessError(cause.code) ?: when (cause.code) {
            "invalid_activation_key", "invalid_activation", "activation_invalid" -> "That activation code is invalid, expired or already used. Use your newest Discord DM code, or ask an admin to reissue it."
            "license_revoked", "account_suspended", "installation_revoked", "authorization_revoked", "license_expired" -> "Sky-Myce access is restricted. Contact a Sky-Myce admin to review the account."
            else -> if (startupActivationAllowed(cause.code)) "Paste the activation code from your Discord DM. Sky-Myce will install before Minecraft starts."
                else "Sky-Myce could not authorize this account. Retry, or ask a Sky-Myce admin to review its access."
        }
        is SecurityException -> "Sky-Myce could not verify this release. Existing files are preserved. Retry or request the current loader from an admin."
        is IllegalArgumentException -> "Startup sign-in is unavailable for these launch settings. Use an online Minecraft account, or start without Sky-Myce and retry from its in-game Loader menu."
        else -> "Sky-Myce setup could not finish. Check your connection and retry. Existing files and verified downloads are kept."
    }
}

/** Runs after Fabric discovery but before Minecraft's main method, renderer and mod initialization. */
object StartupLoader {
    internal val authorization = AuthorizationState()
    internal class Ready(val installation: Installation, val uuid: String, val version: String, val release: JsonObject, val renewAt: Long)
    internal var ready: Ready? = null
        private set
    internal var skipped = false
        private set
    private const val RELAUNCH = "skymyce.startup.relaunch"
    private const val LOADER_RELAUNCH = "skymyce.startup.loaderRelaunch"

    @JvmStatic fun run() {
        // No desktop session: retain the existing in-game sign-in path, with no authorization granted.
        if (GraphicsEnvironment.isHeadless()) return
        val loader = FabricLoader.getInstance()
        StartupWindow { exitProcess(0) }.use { window ->
            var activation: String? = null
            while (true) {
                try {
                    window.status(if (activation == null) "Checking your Sky-Myce account before Minecraft starts…"
                        else "Confirming your code and signed-in Minecraft account…")
                    val config = SignedFiles.configuration()
                    val account = StartupAccount.parse(loader.getLaunchArguments(false))
                    val installation = Installation()
                    val loaderRelease = LoaderApi.loaderRelease(config, loader.gameDir)
                    if (loaderRelease != null && SignedFiles.compareVersion(
                            SignedFiles.loaderManifest(loaderRelease, config, System.currentTimeMillis()).get("version").asString,
                            config.getProperty("loaderVersion")) > 0) {
                        requireStartupHandoffAllowed(System.getProperty(LOADER_RELAUNCH))
                        window.status("Updating the Sky-Myce Loader…\nSetup will restart automatically, then continue into Minecraft.")
                        val updateApi = LoaderApi(config, installation, config.getProperty("modVersion"))
                        val purpose = if (activation == null) "bootstrap-renew" else "bootstrap-activate"
                        val challenge = updateApi.challenge(purpose)
                        val body = mutableMapOf<String, Any>("challengeId" to challenge.get("challengeId").asString,
                            "minecraftProof" to account.proof(challenge.get("nonce").asString), "signature" to updateApi.signature(purpose, challenge))
                        activation?.let { body["activationKey"] = it }
                        try { updateApi.downloadLoader(purpose, body, loaderRelease, loader.gameDir.resolve(".sky-myce")) }
                        finally { activation = null; body.remove("activationKey") }
                        handoff(LOADER_RELAUNCH)
                        return
                    }
                    val existing = loader.getModContainer("skymyce").orElse(null)
                    val envelope = LoaderApi.release(config, existing?.metadata?.version?.friendlyString ?: config.getProperty("modVersion"))
                    val manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis())
                    val version = manifest.get("version").asString
                    val api = LoaderApi(config, installation, version)
                    val purpose = if (activation == null) "renew" else "activate"
                    val challenge = api.challenge(purpose, startSession = !LaunchSession.authorized)
                    val proof = account.proof(challenge.get("nonce").asString)
                    val body = mutableMapOf<String, Any>("challengeId" to challenge.get("challengeId").asString,
                        "minecraftProof" to proof, "signature" to api.signature(purpose, challenge))
                    activation?.let { body["activationKey"] = it }
                    val response = try { api.post("/auth/$purpose", body) } finally { activation = null; body.remove("activationKey") }
                    val uuid = account.uuid.toString().replace("-", "")
                    val token = response.get("lease").asString
                    val claims = verifyLease(token, config, installation, uuid, version,
                        challenge.get("challengeId").asString, challenge.get("clientNonce").asString)
                    authorization.accept(token, claims, response.get("serverTime").asLong)
                    LaunchSession.accept()
                    val renewAt = System.nanoTime() + ((claims.get("exp").asLong - response.get("serverTime").asLong) / 2).coerceAtMost(120000) * 1_000_000
                    installation.persistBoundary(token)
                    val matches = existing?.metadata?.version?.friendlyString == version && runCatching {
                        SignedFiles.verifyArtifact(existing.origin.paths.single(), manifest)
                    }.isSuccess
                    if (!matches) {
                        requireStartupHandoffAllowed(System.getProperty(RELAUNCH))
                        window.status("Downloading and verifying Sky-Myce $version…\nMinecraft will start automatically when setup finishes.")
                        api.download(token, loader.gameDir.resolve(".sky-myce")) { authorization.onlineAllowed }
                        window.status("Applying the verified update and continuing startup…")
                        handoff(RELAUNCH)
                        return
                    }
                    SignedFiles.atomicWrite(loader.gameDir.resolve(".sky-myce/manifest.json"), Gson().toJson(envelope).toByteArray())
                    ready = Ready(installation, uuid, version, envelope, renewAt)
                    System.clearProperty(RELAUNCH)
                    System.clearProperty(LOADER_RELAUNCH)
                    window.status("Sky-Myce is ready. Starting Minecraft…")
                    return
                } catch (failure: Exception) {
                    authorization.deny(); ready = null; activation = null
                    val cause = generateSequence<Throwable>(failure) { it.cause }.last()
                    val answer = window.prompt(startupError(failure), cause is AuthorizationDenied && startupActivationAllowed(cause.code))
                    when (answer) {
                        StartupWindow.CONTINUE -> { skipped = true; return }
                        StartupWindow.RETRY -> Unit
                        else -> activation = answer
                    }
                }
            }
        }
    }

    private fun handoff(marker: String) {
        val loader = FabricLoader.getInstance()
        val jar = loader.getModContainer("skymyce-loader").orElseThrow().origin.paths.single()
        val helper = LoaderInstaller.applyAfterExit(loader.gameDir, jar)
        try {
            val properties = System.getProperties().stringPropertyNames()
                .filter { it.startsWith("fabric.") || it in setOf("java.library.path", "org.lwjgl.librarypath", "java.io.tmpdir", RELAUNCH, LOADER_RELAUNCH) }
                .associateWith { System.getProperty(it) }.toMutableMap()
            properties[marker] = "1"
            LoaderRestart.request(helper, Path.of(System.getProperty("user.dir")), ManagementFactory.getRuntimeMXBean().inputArguments,
                FabricLauncherBase.getLauncher().classPath.toList(), properties, loader.getLaunchArguments(false))
            LoaderRestart.commit(helper)
        } catch (failure: Exception) { helper.destroyForcibly(); throw failure }
        // Only the small bootstrap JVM has run. The fresh JVM discovers the new JAR before loading Minecraft.
        exitProcess(0)
    }
}
