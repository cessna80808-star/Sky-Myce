package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

internal fun dungeonCanInvite(inParty: Boolean, allInvite: Boolean, leader: Boolean, moderator: Boolean) =
    !inParty || allInvite || leader || moderator

internal fun lfgAcceptanceUnavailable(solo: Boolean, busy: Boolean, coolingDown: Boolean): String? = when {
    !solo -> "Leave your current party before joining"
    busy -> "A join request is already pending"
    coolingDown -> "Wait a moment, then reply Yes again"
    else -> null
}

data class DungeonAvailability(
    val floor: DungeonFloor = DungeonFloor.F7,
    val classes: Set<DungeonClass> = emptySet(),
    val maxPbMillis: Long? = null,
) {
    val enabled get() = classes.isNotEmpty()
    fun accepts(stats: DungeonFriendStats?, requestedFloor: DungeonFloor): Boolean =
        requestedFloor == floor && maxPbMillis != null && stats?.state == StatsState.AVAILABLE &&
            stats.eligible(floor) && stats.sPlusTimes[floor]?.let { it > 0 && it <= maxPbMillis } == true
}

data class DungeonJoinPolicy(val floor: DungeonFloor, val maxPbMillis: Long?, val open: Boolean = true) {
    fun accepts(stats: DungeonFriendStats?, requestedFloor: DungeonFloor): Boolean = open && requestedFloor == floor &&
        (maxPbMillis == null || DungeonAvailability(floor, maxPbMillis = maxPbMillis).accepts(stats, floor))

    // The host verifies its own fresh stats before inviting. Missing client-side data may request that check.
    fun canRequest(stats: DungeonFriendStats?, requestedFloor: DungeonFloor): Boolean = open && requestedFloor == floor &&
        (stats == null || accepts(stats, requestedFloor))
}

fun parsePbLimit(text: String): Long? {
    val match = Regex("^(\\d{1,3}):([0-5]\\d)(?:\\.(\\d{1,3}))?$").matchEntire(text.trim()) ?: return null
    val (minutes, seconds, fraction) = match.destructured
    return (minutes.toLong() * 60000 + seconds.toLong() * 1000 + fraction.padEnd(3, '0').toLong())
        .takeIf { it > 0 }
}

private const val NAME = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
// Compiled once: these run for every received chat line.
private val PARTY_INVITER = Regex("^$NAME has invited you to join (?:their|his|her) party![^\\n]*$")
private val JOINED_PARTY_LEADER = Regex("^You have joined $NAME's? party!$")
private val PARTY_BORDER = Regex("[-▬─]{5,}")
private val PARTY_JOINED = Regex("^(?:\\[Party] )?$NAME joined the party\\.$")
private val OWN_INVITE = Regex("^You (?:have )?invited $NAME to (?:your|the) party!(?: They have 60 seconds to accept\\.)?$")
private val NAMED_INVITE = Regex("^$NAME invited $NAME to the party!(?: They have 60 seconds to accept\\.)?$")
fun partyInviter(message: String): String? = PARTY_INVITER
    .matchEntire(message.replace(LEGACY_FORMAT, "").trim())?.groupValues?.get(1)
fun joinedPartyLeader(message: String): String? = JOINED_PARTY_LEADER
    .matchEntire(message.replace(LEGACY_FORMAT, "").trim())?.groupValues?.get(1)

fun serverPartyInviter(component: Component): String? {
    val name = component.string.lines().firstNotNullOfOrNull(::partyInviter) ?: return null
    fun hasAccept(part: Component): Boolean {
        val command = (part.style.clickEvent as? ClickEvent.RunCommand)?.command?.trim()?.removePrefix("/")
        return command?.matches(Regex("(?i)(?:p|party) accept ${Regex.escape(name)}")) == true || part.siblings.any(::hasAccept)
    }
    return name.takeIf { hasAccept(component) }
}

data class SavedDungeonParty(
    val leader: String,
    val members: Set<String>,
    val classes: Map<String, DungeonClass>,
    val floor: DungeonFloor?,
    val savedAt: Long,
) {
    fun matches(leader: String?, roster: Set<String>, now: Long): Boolean =
        this.leader.equals(leader, true) && members == roster.map { it.lowercase() }.toSet() &&
            now >= savedAt && now - savedAt <= 86400000
}

data class DungeonJoinRequest(val floor: DungeonFloor, val classes: Set<DungeonClass>, val token: String) {
    fun message() = "Invite me for ${floor.name} as ${classes.sortedBy { it.ordinal }.joinToString("/") { it.displayName }} [SkyMyce Join $token]"
    companion object {
        fun parse(text: String): DungeonJoinRequest? {
            val match = Regex("^Invite me for ([FM][1-7]) as ([A-Za-z/]+) \\[SkyMyce Join ([a-f0-9]{16})]$")
                .matchEntire(text) ?: return null
            val classes = match.groupValues[2].split('/').map { parseDungeonClass(it) ?: return null }.toSet()
            if (classes.isEmpty() || classes.size > 5) return null
            return DungeonJoinRequest(DungeonFloor.valueOf(match.groupValues[1]), classes, match.groupValues[3])
        }
    }
}

/** The request token binds Yes to the invitation that was actually shown. */
data class DungeonLfgOffer(val request: DungeonJoinRequest, val text: String, val expires: Long) {
    fun message(): String = "[SkyMyce LFG ${request.floor.name} ${request.classes.joinToString("/") { it.displayName }} ${request.token}] $text".take(256)
    fun chatMessage(name: String): Component {
        val classes = request.classes.joinToString("/") { it.displayName }
        return Component.literal("§b$name §7wants you to join §b${request.floor.name} §7as §f$classes")
            .append("  ").append(Component.literal("§a[Yes]").withStyle {
                it.withClickEvent(ClickEvent.RunCommand("/skymyce lfgreply $name yes ${request.token}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to join $name's party")))
            })
            .append("  ").append(Component.literal("§c[No]").withStyle {
                it.withClickEvent(ClickEvent.RunCommand("/skymyce lfgreply $name no ${request.token}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to decline the invitation")))
            })
    }
    companion object {
        fun parse(text: String, now: Long): DungeonLfgOffer? {
            val match = Regex("^\\[SkyMyce LFG ([FM][1-7]) ([A-Za-z/]+) ([a-f0-9]{16})] (.+)$").matchEntire(text) ?: return null
            val request = DungeonJoinRequest.parse("Invite me for ${match.groupValues[1]} as ${match.groupValues[2]} [SkyMyce Join ${match.groupValues[3]}]") ?: return null
            return DungeonLfgOffer(request, match.groupValues[4], now + 60000)
        }
    }
}

/** Compact only confirmations attributable to a command this mod actually sent. */
class DungeonPartyNotices {
    var player: String = ""
        private set
    private data class Invite(val expires: Long, val confirmed: Boolean = false)
    private val invited = mutableMapOf<String, Invite>()
    private var accepted: Pair<String, Long>? = null

    fun command(command: String, automatic: Boolean, now: Long) {
        prune(now)
        val match = Regex("(?i)^(?:p|party) (?:(invite|accept|join) )?([A-Za-z0-9_]{1,16})$").matchEntire(command.trim()) ?: return
        val name = match.groupValues[2].lowercase()
        if (match.groupValues[1].lowercase() in setOf("accept", "join")) {
            accepted = if (automatic) name to now + 10000 else null
        } else if (automatic) invited[name] = Invite(now + 10000)
        else invited.remove(name) // A manual re-invite supersedes the mod's earlier attempt.
    }

    fun compact(message: String, self: String, now: Long): String? {
        player = ""
        prune(now)
        val lines = message.replace(LEGACY_FORMAT, "").lines().map(String::trim)
            .filter { it.isNotEmpty() && !it.matches(PARTY_BORDER) }
        if (lines.size != 1) return null // Never hide unrelated lines in a mixed chat packet.
        val line = lines.single()
        joinedPartyLeader(line)?.let { leader ->
            if (accepted?.first.equals(leader, true)) { accepted = null; player = leader; return "You joined $leader's party" }
            return null
        }
        PARTY_JOINED.matchEntire(line)?.let {
            val name = it.groupValues[1]
            return if (invited.remove(name.lowercase())?.confirmed == true) { player = name; "$name joined" } else null
        }
        val own = OWN_INVITE.matchEntire(line)
        val named = NAMED_INVITE.matchEntire(line)
            ?.takeIf { it.groupValues[1].equals(self, true) }
        val name = own?.groupValues?.get(1) ?: named?.groupValues?.get(2) ?: return null
        val pending = invited[name.lowercase()] ?: return null
        if (pending.confirmed) return null
        invited[name.lowercase()] = Invite(now + 60000, true)
        player = name
        return "$name has been invited"
    }

    private fun prune(now: Long) {
        invited.entries.removeIf { it.value.expires <= now }
        if (accepted?.second?.let { it <= now } == true) accepted = null
    }

    fun clear() { invited.clear(); accepted = null }

    fun pending(now: Long): Boolean {
        prune(now)
        return invited.isNotEmpty() || accepted != null
    }
}

data class JoinPartyContext(
    val availability: DungeonAvailability,
    val solo: Boolean,
    val canInvite: Boolean,
    val partySize: Int,
    val floor: DungeonFloor,
    val missing: Set<DungeonClass>,
    val members: Set<String>,
) {
    val policy get() = DungeonJoinPolicy(floor, availability.maxPbMillis.takeIf { availability.floor == floor }, canInvite && partySize < 5)
}

/** Hold a possible opening border until the following packet identifies whose notice it surrounds. */
class DungeonPartyBorders {
    private var held: Pair<Component, Long>? = null
    private var trailingUntil = 0L
    private fun separator(text: String) = text.replace(LEGACY_FORMAT, "").trim().matches(PARTY_BORDER)

    fun filter(component: Component, pending: Boolean, compacted: Boolean, now: Long, restore: (Component) -> Unit): Boolean {
        val previous = held
        held = null
        previous?.let { if (!compacted || now >= it.second) restore(it.first) }
        if (separator(component.string)) {
            if (now < trailingUntil) { trailingUntil = 0; return true }
            trailingUntil = 0
            if (pending) { held = component to now + 250; return true }
            return false
        }
        trailingUntil = if (compacted && !separator(component.string.lineSequence().last())) now + 250 else 0
        return false
    }

    fun tick(now: Long, restore: (Component) -> Unit) {
        held?.takeIf { now >= it.second }?.let { held = null; restore(it.first) }
    }

    fun clear() { held = null; trailingUntil = 0 }
}

/** Short-lived exchanges only: an offer in private chat is never treated as a server invitation. */
class DungeonFriendJoining {
    private data class Request(val data: DungeonJoinRequest, val expires: Long, var offered: DungeonClass? = null,
        var received: Boolean = false, var invited: Boolean = false, val manual: Boolean = false)
    private val incoming = linkedMapOf<String, Request>()
    private val invitations = linkedMapOf<String, Long>()
    private val recent = mutableMapOf<String, Request>()
    private var outgoing: Pair<String, Request>? = null
    private var acceptingUntil = 0L
    var accepted: Pair<String, Pair<DungeonFloor, DungeonClass>>? = null
        private set
    var status = ""
        private set
    var statusPlayer = ""
        private set
    val hasCommandWork get() = incoming.isNotEmpty() || invitations.isNotEmpty()
    val pendingCount get() = incoming.size + invitations.size + if (outgoing != null) 1 else 0
    fun busy(now: Long): Boolean = outgoing != null || now < acceptingUntil

    fun expectsInvite(name: String, now: Long): Boolean = outgoing?.let {
        it.first.equals(name, true) && it.second.expires > now && it.second.offered != null
    } == true

    fun inviteDetails(name: String, now: Long): Pair<DungeonFloor, DungeonClass>? =
        outgoing?.takeIf { expectsInvite(name, now) }?.second?.let { it.data.floor to it.offered!! }

    fun request(name: String, data: DungeonJoinRequest, now: Long, manual: Boolean = false): String {
        outgoing = name.lowercase() to Request(data, now + 60000, manual = manual)
        statusPlayer = name
        status = "Join requested from $name"
        return "msg $name ${data.message()}"
    }

    fun receiveRequest(name: String, data: DungeonJoinRequest, now: Long, manual: Boolean = false): Boolean {
        prune(now)
        val key = name.lowercase()
        val previous = recent[key]
        // A fresh, explicitly sent invitation supersedes the previous exchange, but never its own duplicate.
        if (previous != null && (!manual || previous.data.token == data.token)) return false
        if (key !in incoming && incoming.size >= 5) return false
        val request = Request(data, now + 60000, manual = manual)
        incoming[key] = request
        recent[key] = request
        return true
    }

    fun receiveAcceptedReply(name: String, data: DungeonJoinRequest, now: Long) {
        if (receiveRequest(name, data, now, manual = true)) incoming.getValue(name.lowercase()).received = true
    }

    fun receiveOffer(name: String, message: String, now: Long): Boolean {
        prune(now)
        val (target, request) = outgoing ?: return false
        if (!target.equals(name, true)) return false
        val match = Regex("^Inviting you for ([FM][1-7]) as ([A-Za-z]+) \\[SkyMyce Ready ([a-f0-9]{16})]$")
            .matchEntire(message) ?: return false
        val clazz = parseDungeonClass(match.groupValues[2]) ?: return false
        if (match.groupValues[1] == request.data.floor.name && match.groupValues[3] == request.data.token && clazz in request.data.classes) {
            request.offered = clazz
            return true
        }
        return false
    }

    fun invited(name: String, now: Long): Boolean {
        prune(now)
        // Available classes alone never authorize an unsolicited or manual party invitation.
        if (!expectsInvite(name, now)) return false
        invitations.putIfAbsent(name.lowercase(), now + 55000)
        return true
    }

    fun acknowledged(name: String, token: String) {
        incoming[name.lowercase()]?.takeIf { it.offered != null && it.data.token == token }?.received = true
    }

    fun failed(name: String, token: String): Boolean {
        val removed = incoming[name.lowercase()]?.takeIf { it.data.token == token }?.let { incoming.remove(name.lowercase()); true } == true
        if (removed) recent.remove(name.lowercase())
        val requested = outgoing?.first.equals(name, true) && outgoing?.second?.data?.token == token
        if (requested) { outgoing = null; invitations.remove(name.lowercase()) }
        if (removed || requested) { statusPlayer = name; status = "Could not reach $name" }
        return removed || requested
    }

    fun reconnect() {
        outgoing?.let { failed(it.first, it.second.data.token) }
        incoming.toMap().filterValues { !it.invited }.forEach { (name, request) -> failed(name, request.data.token) }
    }

    fun nextCommand(context: JoinPartyContext, now: Long, stats: (String) -> DungeonFriendStats?): String? {
        prune(now)
        if (now < acceptingUntil) return null
        if (context.solo && (context.availability.enabled || outgoing?.second?.manual == true)) {
            for (name in invitations.keys.toList()) {
                val request = outgoing?.takeIf { it.first == name }?.second ?: continue
                val clazz = request.offered?.takeIf { request.manual || it in context.availability.classes } ?: continue
                accepted = name to (request.data.floor to clazz)
                acceptingUntil = now + 10000
                invitations.clear()
                outgoing = null
                statusPlayer = name
                status = "Joining $name as ${clazz.displayName}"
                return "party accept $name"
            }
        }
        if (outgoing != null || !context.canInvite || context.partySize >= 5) return null
        incoming.keys.removeAll(context.members)
        for ((name, request) in incoming) {
            val floor = request.data.floor
            if (request.invited || (!request.manual && floor != context.floor)) continue
            val playerStats = stats(name)
            if (!request.manual && !context.policy.accepts(playerStats, floor)) {
                statusPlayer = name
                status = if (playerStats == null) "Checking $name's S+ PB" else "$name does not meet the ${floor.name} S+ PB limit"
                continue
            }
            val otherReservations = incoming.filter { it.key != name && it.value.offered != null }
            if (context.partySize + otherReservations.size >= 5) continue
            val reservedClasses = otherReservations.values.mapNotNull { it.offered }.toSet()
            val availableClasses = request.data.classes.filter { it in context.missing && it !in reservedClasses }
            val clazz = request.offered ?: playerStats?.selectedClass?.takeIf { it in availableClasses }
                ?: availableClasses.firstOrNull() ?: continue
            if (clazz !in availableClasses) continue
            if (request.offered == null) {
                request.offered = clazz
                statusPlayer = name
                status = "Offering ${clazz.displayName} to $name"
                if (!request.received) return "msg $name Inviting you for ${floor.name} as ${clazz.displayName} [SkyMyce Ready ${request.data.token}]"
            }
            if (!request.received) continue
            request.invited = true
            statusPlayer = name
            status = "Invited $name as ${clazz.displayName}"
            return "party invite $name"
        }
        return null
    }

    fun classFor(name: String): DungeonClass? = incoming[name.lowercase()]?.takeIf { it.invited }?.offered

    fun prune(now: Long) {
        incoming.entries.removeIf { it.value.expires <= now }
        invitations.entries.removeIf { it.value <= now }
        recent.entries.removeIf { it.value.expires <= now }
        if (outgoing?.second?.expires?.let { it <= now } == true) { statusPlayer = outgoing!!.first; outgoing = null; status = "Join request expired" }
        if (now >= acceptingUntil) accepted = null
    }

    fun clear() {
        incoming.clear(); invitations.clear(); recent.clear(); outgoing = null; accepted = null; acceptingUntil = 0; status = ""; statusPlayer = ""
    }
}
