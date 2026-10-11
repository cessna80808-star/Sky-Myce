package me.mycellium.skymyce.features.instances.dungeons.friends

import java.security.MessageDigest
import java.util.UUID
import com.google.gson.JsonObject

/** Shared identifier describes mutual roster consent. It is not a server attestation of Hypixel membership. */
internal data class IrcPartyRoster(val members: List<String>, val leader: String) {
    val id: String = MessageDigest.getInstance("SHA-256")
        .digest(("sky-myce-party-v1\n$leader\n${members.joinToString("\n")}").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
}
internal fun validatedIrcPartyRoster(inParty: Boolean, members: List<UUID?>, leader: UUID?, self: UUID): IrcPartyRoster? {
    if (!inParty || members.size !in 2..20 || leader == null || members.any { it == null || it == UUID(0, 0) } ||
        members.distinct().size != members.size || self !in members || leader !in members) return null
    return IrcPartyRoster(members.map { it.toString().replace("-", "") }.sorted(), leader.toString().replace("-", ""))
}

internal fun ircPartyGrantMillis(response: JsonObject, partyId: String?, now: Long): Long? = runCatching {
    require(partyId != null && response.get("type")?.asString == "irc_result" && response.get("scope")?.asString == "party" &&
        response.get("room")?.asString == partyId && !response.has("error"))
    val expires = response.get("expiresAt")
    require(expires != null && expires.isJsonPrimitive && expires.asJsonPrimitive.isNumber && expires.asString.matches(Regex("[0-9]{1,16}")))
    val remaining = expires.asLong - now
    require(remaining in 1..60_000)
    remaining.coerceAtMost(55_000)
}.getOrNull()
