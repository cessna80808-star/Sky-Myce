package me.mycellium.skymyce.loader

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import java.util.UUID

internal class AuthorizationDenied(val code: String) : Exception()

/** Fixed, safe guidance shared by the desktop window and in-game menu. Never echo server text. */
internal fun accountAccessError(code: String): String? = when (code) {
    "device_reset_required" -> "This account is registered to another installation. Ask an admin to use /hwid reset for your Discord account, then retry your newest activation code. A new code alone cannot switch devices. (device_reset_required)"
    "device_owned" -> "This installation belongs to another Sky-Myce account. Ask an admin to review its device registration. (device_owned)"
    "device_revoked" -> "Device registration is blocked for this account. Ask an admin to review it before retrying. (device_revoked)"
    "minecraft_account_owned" -> "This Minecraft account is linked to another Sky-Myce account. Ask an admin to review the link. (minecraft_account_owned)"
    "minecraft_proof_invalid", "minecraft_proof_required" -> "Minecraft sign-in could not be verified. Close the game, sign in again through your launcher, then retry. (minecraft_proof_invalid)"
    "version_denied" -> "These Minecraft or Loader versions are not supported by the current release. Ask an admin for the current loader and required Minecraft version. (version_denied)"
    "loader_update_required" -> "This backend requires the current Sky-Myce Loader to verify launch sessions. Update the loader, then close and relaunch Minecraft. (loader_update_required)"
    "launch_authorization_required" -> "This launch needs fresh authorization. Close Minecraft and relaunch this instance. Your registered device stays the same; a new activation code is not needed for launch reauthorization. (launch_authorization_required)"
    "platform_changed" -> "This installation's operating system differs from its registered platform. Keep its device identity and ask a Sky-Myce admin to review an intended operating-system transfer. (platform_changed)"
    "authorization_revoked" -> "This Sky-Myce session was replaced or access was revoked. Close Minecraft and relaunch. If access remains restricted, contact a Sky-Myce admin. (authorization_revoked)"
    else -> null
}

/** Same signed protocol for the pre-game window and the in-game owo menu. No Minecraft classes. */
internal class LoaderApi(private val config: Properties, private val installation: Installation, private val version: String) {
    companion object {
        private val gson = Gson()
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
        private fun endpoint(config: Properties, path: String): URI {
            val base = URI(config.getProperty("backend"))
            require(base.scheme == "https" && base.userInfo == null && base.rawQuery == null && base.fragment == null && base.path in setOf("", "/"))
            return base.resolve(path)
        }
        fun release(config: Properties, baseline: String): JsonObject {
            val response = http.send(HttpRequest.newBuilder(endpoint(config, "/loader/release")).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 16384))
            if (response.statusCode() != 200) throw java.io.IOException("Release unavailable")
            val envelope = JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
            val manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis())
            require(SignedFiles.compareVersion(manifest.get("version").asString, baseline) >= 0)
            return envelope
        }
        fun loaderRelease(config: Properties, game: Path): JsonObject? {
            if (!LoaderPreferences.autoUpdateLoader(game)) return null
            // A temporary update-service outage must not disable an otherwise supported loader.
            val response = try {
                http.send(HttpRequest.newBuilder(endpoint(config, "/loader/bootstrap-release")).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 16384))
            } catch (_: java.io.IOException) { return null }
            if (response.statusCode() in setOf(404, 408, 429) || response.statusCode() >= 500) return null
            check(response.statusCode() == 200) { "Loader release unavailable" }
            val envelope = JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
            SignedFiles.loaderManifest(envelope, config, System.currentTimeMillis())
            return envelope
        }
    }
    private fun request(path: String, body: Any): HttpRequest = HttpRequest.newBuilder(endpoint(config, path))
        .timeout(Duration.ofSeconds(if (path == "/loader/artifact" || path.startsWith("/loader/bootstrap/")) 90 else 20))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build()

    fun post(path: String, body: Any): JsonObject {
        val response = http.send(request(path, body), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 32768))
        if (response.statusCode() in setOf(408, 429) || response.statusCode() >= 500) throw java.io.IOException("Backend temporarily unavailable")
        if (response.statusCode() in 400..499) {
            val code = runCatching { JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject.get("error").asString }.getOrDefault("unauthorized")
            throw AuthorizationDenied(code)
        }
        if (response.statusCode() != 200) throw java.io.IOException("Backend unavailable")
        return JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
    }
    internal fun challengeInput(purpose: String, startSession: Boolean, clientNonce: String): Map<String, Any> =
        mapOf("purpose" to purpose, "clientNonce" to clientNonce,
            "installationId" to installation.id, "publicKey" to installation.publicKey, "deviceHash" to installation.deviceHash,
            "minecraftVersion" to config.getProperty("minecraft"), "loaderVersion" to config.getProperty("loaderVersion"), "modVersion" to version,
            "platform" to LaunchSession.platform, "launchSessionId" to LaunchSession.id, "startSession" to startSession)

    fun challenge(purpose: String, startSession: Boolean = false): JsonObject {
        val nonce = UUID.randomUUID().toString()
        return post("/auth/challenge", challengeInput(purpose, startSession, nonce)).also {
            // Bind our outgoing request, never a server-echoed nonce or session intent.
            it.addProperty("clientNonce", nonce); it.addProperty("startSession", startSession)
        }
    }
    fun signature(purpose: String, challenge: JsonObject) = installation.sign("Sky-Myce installation v2\n$purpose\n${challenge.get("nonce").asString}\n${installation.id}\n${installation.deviceHash}\n${config.getProperty("minecraft")}\n${config.getProperty("loaderVersion")}\n$version\n${challenge.get("clientNonce").asString}\n${LaunchSession.platform}\n${LaunchSession.id}\n${challenge.get("startSession").asBoolean}")

    fun downloadLoader(purpose: String, body: Map<String, Any>, envelope: JsonObject, directory: Path) {
        require(purpose in setOf("bootstrap-renew", "bootstrap-activate"))
        val manifest = SignedFiles.loaderManifest(envelope, config, System.currentTimeMillis())
        check(SignedFiles.compareVersion(manifest.get("version").asString, config.getProperty("loaderVersion")) > 0)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "loader-", ".tmp")
        try {
            val response = http.send(request("/loader/bootstrap/${purpose.removePrefix("bootstrap-")}", body + ("version" to manifest.get("version").asString)),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(temporary), 64L * 1024 * 1024))
            if (response.statusCode() in setOf(408, 429) || response.statusCode() >= 500) throw java.io.IOException("Loader unavailable")
            if (response.statusCode() in 400..499) {
                val code = if (Files.size(temporary) <= 32768) runCatching {
                    JsonParser.parseString(Files.readString(temporary)).asJsonObject.get("error").asString
                }.getOrDefault("unauthorized") else "unauthorized"
                throw AuthorizationDenied(code)
            }
            check(response.statusCode() == 200)
            LoaderInstaller.verifyLoader(temporary, manifest)
            Files.move(temporary, directory.resolve("Loader.pending.jar"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            SignedFiles.atomicWrite(directory.resolve("loader-pending-manifest.json"), gson.toJson(envelope).toByteArray())
        } finally { Files.deleteIfExists(temporary) }
    }

    fun download(lease: String, directory: Path, stillAuthorized: () -> Boolean) {
        val challenge = challenge("manifest")
        val envelope = post("/loader/manifest", mapOf("challengeId" to challenge.get("challengeId").asString,
            "lease" to lease, "signature" to signature("manifest", challenge)))
        val manifest = SignedFiles.manifest(envelope, config, System.currentTimeMillis())
        require(manifest.get("version").asString == version)
        val nonce = challenge("artifact")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "release-", ".tmp")
        try {
            val response = http.send(request("/loader/artifact", mapOf("challengeId" to nonce.get("challengeId").asString,
                "lease" to lease, "signature" to signature("artifact", nonce))),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(temporary), 128L * 1024 * 1024))
            if (response.statusCode() in setOf(408, 429) || response.statusCode() >= 500) throw java.io.IOException("Artifact temporarily unavailable")
            if (response.statusCode() != 200) throw SecurityException("Artifact unavailable")
            SignedFiles.verifyArtifact(temporary, manifest)
            check(stillAuthorized()) { "Authorization changed" }
            Files.move(temporary, directory.resolve("Sky-Myce.pending.jar"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            SignedFiles.atomicWrite(directory.resolve("pending-manifest.json"), gson.toJson(envelope).toByteArray())
        } finally { Files.deleteIfExists(temporary) }
    }
}
