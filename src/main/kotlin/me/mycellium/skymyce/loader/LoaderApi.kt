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
    }
    private fun request(path: String, body: Any): HttpRequest = HttpRequest.newBuilder(endpoint(config, path))
        .timeout(Duration.ofSeconds(if (path == "/loader/artifact") 90 else 20))
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
    fun challenge(purpose: String): JsonObject {
        val nonce = UUID.randomUUID().toString()
        return post("/auth/challenge", mapOf("purpose" to purpose, "clientNonce" to nonce,
            "installationId" to installation.id, "publicKey" to installation.publicKey, "deviceHash" to installation.deviceHash,
            "minecraftVersion" to config.getProperty("minecraft"), "loaderVersion" to config.getProperty("loaderVersion"), "modVersion" to version))
            .also { it.addProperty("clientNonce", nonce) }
    }
    fun signature(purpose: String, challenge: JsonObject) = installation.sign("Sky-Myce installation v1\n$purpose\n${challenge.get("nonce").asString}\n${installation.id}\n${installation.deviceHash}\n${config.getProperty("minecraft")}\n${config.getProperty("loaderVersion")}\n$version\n${challenge.get("clientNonce").asString}")

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
