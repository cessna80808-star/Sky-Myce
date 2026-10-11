package me.mycellium.skymyce.features.instances.dungeons.friends

import java.security.MessageDigest
import java.util.UUID
import com.google.gson.JsonObject

internal const val PARTY_INFO_REQUEST_MILLIS = 60_000L
internal fun partyInfoRequestDue(now: Long, requestedAt: Long, allowedAt: Long) = now >= requestedAt && now >= allowedAt
internal fun ircPartyRefreshMillis(grantMillis: Long) = (grantMillis / 2).coerceIn(5_000L, 30_000L)
internal fun ircPartyRosterError(onHypixel: Boolean, inParty: Boolean, memberCount: Int, rosterAgeMillis: Long?): String = when {
    !onHypixel -> "Join Hypixel to use Party IRC."
    !inParty || memberCount !in 2..20 -> "Party IRC needs a Hypixel party with 2–20 players."
    rosterAgeMillis == null -> "Waiting for Hypixel's complete party roster."
    rosterAgeMillis !in 0..65_000L -> "Refreshing Hypixel's expired party roster."
    else -> "Hypixel's party roster is incomplete or changed; waiting for a fresh update."
}

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
    // Subtract the clock-skew allowance so even a short server lease keeps a conservative local deadline.
    require(remaining in 5_001..65_000)
    (remaining - 5_000).coerceAtMost(55_000)
}.getOrNull()
