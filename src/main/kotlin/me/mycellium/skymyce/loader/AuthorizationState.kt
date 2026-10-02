package me.mycellium.skymyce.loader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.Properties

internal fun activationRequestCurrent(expectedGeneration: Long, generation: Long, account: String, current: String, proofUuid: Any?) =
    expectedGeneration == generation && account == current && account == proofUuid

/** Monotonic elapsed time is the only clock after a live, signed server response. No offline restart. */
class AuthorizationState(private val clock: () -> Long = System::nanoTime) {
    @Volatile var generation = 0L
        private set
    private var accepted = 0L
    private var expires = 0L
    private var offline = 0L
    private var denied = true
    private var unreachable = false
    var lease: String? = null
        private set
    val elapsed get() = ((clock() - accepted) / 1_000_000).coerceAtLeast(0)
    val onlineAllowed get() = !denied && !unreachable && lease != null && elapsed < expires
    val localAllowed get() = !denied && lease != null && elapsed < if (unreachable) offline else expires
    fun accept(token: String, claims: JsonObject, serverTime: Long, expectedGeneration: Long = generation) {
        require(expectedGeneration == generation) { "Authorization request invalidated" }
        require(serverTime in claims.get("iat").asLong..claims.get("exp").asLong)
        expires = claims.get("exp").asLong - serverTime
        offline = claims.get("offlineUntil").asLong - serverTime
        require(expires in 1..900000 && offline in expires..4500000)
        accepted = clock(); denied = false; unreachable = false; lease = token
    }
    fun unavailable() { unreachable = true }
    fun invalidatePending() { generation++ }
    fun deny() { invalidatePending(); denied = true; lease = null }
}

fun verifyLease(token: String, config: Properties, installation: Installation, uuid: String, version: String, challengeId: String, clientNonce: String): JsonObject {
    require(token.length <= 8192)
    val parts = token.split('.').also { require(it.size == 3) }
    val decode = Base64.getUrlDecoder()
    val header = JsonParser.parseString(String(decode.decode(parts[0]), Charsets.UTF_8)).asJsonObject
    require(header.get("alg").asString == "EdDSA" && header.get("typ").asString == "JWT")
    SignedFiles.verify(SignedFiles.key(config, header.get("kid").asString), "${parts[0]}.${parts[1]}".toByteArray(), parts[2])
    return JsonParser.parseString(String(decode.decode(parts[1]), Charsets.UTF_8)).asJsonObject.also {
        require(it.get("product").asString == "sky-myce" && it.get("protocol").asInt == 3)
        require(it.get("uuid").asString == uuid && it.get("installationId").asString == installation.id)
        require(it.get("deviceHash").asString == installation.deviceHash)
        require(it.get("hardwareHash")?.asString == installation.hardwareHash)
        require(it.get("keyFingerprint").asString == SignedFiles.sha256(Base64.getDecoder().decode(installation.publicKey)))
        require(it.get("minecraft").asString == config.getProperty("minecraft") && it.get("version").asString == version)
        require(it.get("accountId").asString.isNotBlank() && it.get("discordId").asString.matches(Regex("[0-9]{17,20}")))
        require(it.get("jti").asString.isNotBlank() && it.get("generation").asLong >= 0)
        require(it.get("challengeId").asString == challengeId)
        require(it.get("clientNonce").asString == clientNonce)
        require(it.get("platform")?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString == LaunchSession.platform } == true)
        require(it.get("launchSessionId")?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString == LaunchSession.id } == true)
        require(it.getAsJsonArray("features").all { feature -> feature.isJsonPrimitive && feature.asJsonPrimitive.isString })
    }
}
