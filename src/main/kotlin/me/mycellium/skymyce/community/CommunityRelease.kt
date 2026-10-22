package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties

internal const val MAX_COMMUNITY_JAR = 128L * 1024 * 1024
internal data class CommunityRelease(val version: String, val size: Long, val sha256: String, val notes: String)
/** X.Y.Z or X.Y.Z-beta-N; a release sorts after every beta of the same X.Y.Z. */
internal fun communityVersion(value: String): List<Int> {
    val match = requireNotNull(Regex("([0-9]{1,5})\\.([0-9]{1,5})\\.([0-9]{1,5})(?:-beta-([0-9]{1,5}))?").matchEntire(value))
    return match.groupValues.subList(1, 4).map(String::toInt) + (match.groupValues[4].toIntOrNull() ?: Int.MAX_VALUE)
}
internal fun newerCommunityVersion(next: String, current: String): Boolean {
    val left = communityVersion(next); val right = communityVersion(current)
    return left.zip(right).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}
/** Authenticate the exact payload bytes before trusting any download metadata. */
internal fun verifyCommunityRelease(envelope: JsonObject, configuration: Properties, now: Long = System.currentTimeMillis()): CommunityRelease {
    val kid = envelope["kid"].asString
    require(kid.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    val encoded = envelope["payload"].asString; require(encoded.length <= 32768)
    val payload = Base64.getUrlDecoder().decode(encoded)
    val signature = Base64.getUrlDecoder().decode(envelope["signature"].asString); require(signature.size == 64)
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(configuration.getProperty("key.$kid"))))
    require(Signature.getInstance("Ed25519").run { initVerify(key); update(payload); verify(signature) })
    val data = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
    require(data["product"].asString == "sky-myce" && data["protocol"].asInt == 4 && data["installation"].asString == "fabric")
    require(data["minecraft"].asString == configuration.getProperty("minecraft"))
    val version = data["version"].asString; communityVersion(version)
    require(data["artifact"].asString == "releases/Sky-Myce-$version.jar" && !data.has("minimumLoaderVersion"))
    val size = data["size"].asLong; require(size in 4..MAX_COMMUNITY_JAR)
    val hash = data["sha256"].asString; require(hash.matches(Regex("[a-f0-9]{64}")))
    val issued = data["issuedAt"].asLong; val expires = data["expiresAt"].asLong
    require(issued <= now + 300_000 && expires > now && expires > issued && expires - issued <= 14L * 86400000)
    val notes = data["notes"]?.asString ?: ""; require(notes.length <= 8000)
    return CommunityRelease(version, size, hash, notes)
}
