package me.mycellium.skymyce.features.instances.dungeons.leap

import com.google.gson.JsonObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.config.instances.dungeons.LeapMenuConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.RelayMessages
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI

// Compiled once: LeapPovSharing checks these every tick in the Catacombs.
internal val leapServerId = Regex("[A-Za-z0-9_-]{1,64}")
internal val leapFloorId = Regex("E|ENTRANCE|[FM][1-7]")

data class LeapPovContext(val epoch: String, val server: String, val floor: String, val alpha: Boolean,
    val party: String, val allowed: Map<UUID, String>)

/** Four consenting peers, held only in memory and bounded by the relay's clock rather than the computer's wall clock. */
class LeapPovCache {
    private data class Clock(val upperMillis: Long, val receivedNanos: Long, val leaseExpiresNanos: Long)
    private data class TimedPose(val pose: LeapCameraPose, val timestamp: Long, val receivedNanos: Long, val expiresNanos: Long)
    private data class Peer(val sourceEpoch: String, val sequence: Long, val timestamp: Long, val expiresNanos: Long,
        val previous: TimedPose?, val current: TimedPose?, val retired: Map<String, Long>, val revoked: Boolean = false)
    private data class Frame(val uuid: UUID, val sourceEpoch: String, val sequence: Long,
        val timestamp: Long, val expiresAt: Long, val pose: LeapCameraPose)

    private var context: LeapPovContext? = null
    private var clock: Clock? = null
    private val peers = mutableMapOf<UUID, Peer>()

    fun acknowledge(next: LeapPovContext, serverTime: Long, leaseExpiresAt: Long, sentNanos: Long, receivedNanos: Long): Boolean {
        val elapsed = receivedNanos - sentNanos
        if (!validContext(next) || serverTime !in 1..MAX_INTEGER - LEASE_MILLIS ||
            leaseExpiresAt - serverTime !in 1..LEASE_MILLIS || elapsed !in 0..LEASE_MILLIS * NANOS_PER_MILLI) {
            clear(); return false
        }
        val upper = serverTime + ceilMillis(elapsed)
        val remaining = leaseExpiresAt - upper
        if (remaining <= 0) { clear(); return false }
        if (context != next) peers.clear()
        context = next.copy(allowed = next.allowed.toMap())
        clock = Clock(upper, receivedNanos, receivedNanos + remaining * NANOS_PER_MILLI)
        return true
    }

    fun ready(nowNanos: Long): Boolean {
        val valid = clock?.let { nowNanos - it.receivedNanos >= 0 && it.leaseExpiresNanos - nowNanos > 0 } == true
        peers.entries.forEach { entry ->
            val peer = entry.value
            val current = peer.current?.takeIf { valid && it.expiresNanos - nowNanos > 0 }
            val previous = peer.previous?.takeIf { valid && it.expiresNanos - nowNanos > 0 }
            if (current !== peer.current || previous !== peer.previous) entry.setValue(peer.copy(current = current, previous = previous))
        }
        return valid
    }

    fun receive(json: JsonObject, nowNanos: Long): Boolean {
        val currentContext = context ?: return false
        val upperNow = upperServerNow(nowNanos) ?: return false
        val frame = decode(json, currentContext) ?: return false
        val remaining = frame.expiresAt - upperNow
        if (remaining !in 1..POSE_MILLIS) return false
        val prior = peers[frame.uuid]
        var retired = prior?.retired?.filterValues { it - nowNanos > 0 }.orEmpty()
        if (frame.sourceEpoch in retired) return false
        if (prior != null && (frame.timestamp < prior.timestamp ||
                frame.sourceEpoch == prior.sourceEpoch && (frame.sequence <= prior.sequence || prior.revoked) ||
                frame.sourceEpoch != prior.sourceEpoch && frame.timestamp <= prior.timestamp)) return false
        if (prior != null && frame.sourceEpoch != prior.sourceEpoch && prior.expiresNanos - nowNanos > 0) {
            if (retired.size >= 8) return false
            retired = retired + (prior.sourceEpoch to prior.expiresNanos)
        }
        val previous = prior?.takeIf { it.sourceEpoch == frame.sourceEpoch }?.current?.takeIf {
            it.expiresNanos - nowNanos > 0 && distanceSquared(it.pose, frame.pose) < 16.0 * 16.0
        }
        val pose = TimedPose(frame.pose, frame.timestamp, nowNanos, nowNanos + remaining * NANOS_PER_MILLI)
        peers[frame.uuid] = Peer(frame.sourceEpoch, frame.sequence, frame.timestamp, pose.expiresNanos, previous, pose, retired)
        return true
    }

    fun remove(json: JsonObject): Boolean {
        val currentContext = context ?: return false
        if (string(json, "type") != "leap_pov_remove" || string(json, "epoch") != currentContext.epoch) return false
        val uuid = uuid(string(json, "uuid")) ?: return false
        val sourceEpoch = string(json, "sourceEpoch") ?: return false
        val peer = peers[uuid]?.takeIf { it.sourceEpoch == sourceEpoch } ?: return false
        peers[uuid] = peer.copy(previous = null, current = null, revoked = true)
        return true
    }

    fun pose(identity: LeapIdentity, nowNanos: Long): LeapCameraPose? {
        if (!ready(nowNanos)) return null
        val uuid = identity.uuid ?: return null
        val expected = context?.allowed?.get(uuid) ?: return null
        if (normalizedLeapName(expected) != identity.key) return null
        val peer = peers[uuid] ?: return null
        val current = peer.current?.takeIf { it.expiresNanos - nowNanos > 0 && nowNanos - it.receivedNanos >= 0 } ?: return null
        val previous = peer.previous?.takeIf { it.expiresNanos - nowNanos > 0 } ?: return current.pose
        val duration = (current.timestamp - previous.timestamp).coerceAtMost(500)
        if (duration <= 0) return current.pose
        val progress = ((nowNanos - current.receivedNanos).toDouble() / (duration * NANOS_PER_MILLI)).coerceIn(0.0, 1.0)
        val yawDelta = ((current.pose.yaw.toDouble() - previous.pose.yaw) % 360 + 540) % 360 - 180
        return LeapCameraPose(previous.pose.x + (current.pose.x - previous.pose.x) * progress,
            previous.pose.y + (current.pose.y - previous.pose.y) * progress,
            previous.pose.z + (current.pose.z - previous.pose.z) * progress,
            (((previous.pose.yaw + yawDelta * progress) % 360 + 540) % 360 - 180).toFloat(),
            (previous.pose.pitch + (current.pose.pitch - previous.pose.pitch) * progress).toFloat())
    }

    fun clear() { context = null; clock = null; peers.clear() }

    private fun upperServerNow(nowNanos: Long): Long? {
        if (!ready(nowNanos)) return null
        val current = clock ?: return null
        return current.upperMillis + ceilMillis(nowNanos - current.receivedNanos)
    }

    private fun decode(json: JsonObject, current: LeapPovContext): Frame? {
        if (string(json, "type") != "leap_pov_pose" || string(json, "epoch") != current.epoch ||
            string(json, "server") != current.server || string(json, "floor") != current.floor ||
            boolean(json, "alpha") != current.alpha || string(json, "party") != current.party) return null
        val uuid = uuid(string(json, "uuid")) ?: return null
        val expected = current.allowed[uuid] ?: return null
        val name = string(json, "name")?.takeIf { NAME.matches(it) } ?: return null
        if (!name.equals(expected, ignoreCase = true)) return null
        val sourceEpoch = string(json, "sourceEpoch")?.takeIf { HEX.matches(it) } ?: return null
        val sequence = integer(json, "seq")?.takeIf { it in 0..MAX_INTEGER } ?: return null
        val timestamp = integer(json, "timestamp")?.takeIf { it in 1..MAX_INTEGER - POSE_MILLIS } ?: return null
        val expiresAt = integer(json, "expiresAt")?.takeIf { it == timestamp + POSE_MILLIS } ?: return null
        val x = number(json, "x")?.takeIf { abs(it) <= 30_000_000 } ?: return null
        val y = number(json, "y")?.takeIf { abs(it) <= 4096 } ?: return null
        val z = number(json, "z")?.takeIf { abs(it) <= 30_000_000 } ?: return null
        val yaw = number(json, "yaw")?.takeIf { it in -180.0..180.0 } ?: return null
        val pitch = number(json, "pitch")?.takeIf { it in -90.0..90.0 } ?: return null
        return Frame(uuid, sourceEpoch, sequence, timestamp, expiresAt, LeapCameraPose(x, y, z, yaw.toFloat(), pitch.toFloat()))
    }

    private companion object {
        const val POSE_MILLIS = 1500L
        const val LEASE_MILLIS = 15_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_INTEGER = 9_007_199_254_740_991L
        val HEX = Regex("[a-f0-9]{32}")
        val NAME = Regex("[A-Za-z0-9_]{1,16}")

        fun validContext(value: LeapPovContext) = HEX.matches(value.epoch) && HEX.matches(value.party) &&
            leapServerId.matches(value.server) && leapFloorId.matches(value.floor) && value.allowed.size <= 4 &&
            value.allowed.all { (uuid, name) -> uuid != UUID(0, 0) && NAME.matches(name) } &&
            value.allowed.values.map { it.lowercase(Locale.ROOT) }.distinct().size == value.allowed.size

        fun ceilMillis(nanos: Long) = nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI == 0L) 0 else 1
        fun distanceSquared(first: LeapCameraPose, second: LeapCameraPose) =
            (first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y) + (first.z - second.z) * (first.z - second.z)

        fun string(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun boolean(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        fun number(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asDouble.takeIf(Double::isFinite) }.getOrNull() }
        fun integer(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() }
        fun uuid(value: String?): UUID? = value?.takeIf { HEX.matches(it) }?.let {
            UUID.fromString("${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}")
        }
    }
}

/** Explicit party consent shares a short-lived camera pose, never images or stored history. */
object LeapPovSharing : SkyMyceModule() {
    private data class Scope(
        val account: UUID, val endpoint: String, val authorization: String, val communityGeneration: Long,
        val relayGeneration: Long, val connection: ClientPacketListener, val level: ClientLevel,
        val profileId: UUID?, val profileName: String?,
        val server: String, val floor: String, val alpha: Boolean, val party: String,
        val members: Map<UUID, String>, val menu: Screen?, val watching: Map<UUID, String>,
    )
    private data class Request(val id: String, val sentNanos: Long)
    private class Lease(val scope: Scope) {
        val epoch = UUID.randomUUID().toString().replace("-", "")
        val cache = LeapPovCache()
        @Volatile var request: Request? = null
        var nextControl = 0L
        var nextPose = 0L
        var sequence = 0L
        val context get() = LeapPovContext(epoch, scope.server, scope.floor, scope.alpha, scope.party, scope.watching)
    }
    @Volatile private var active: Lease? = null

    override fun init() {
        DungeonFriendRelay.onLeapPovEvent = ::receive
        DungeonFriendRelay.onLeapPovClosed = { stop(revoke = false) }
        ClientTickEvents.END_CLIENT_TICK.register { tickSharing() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { stop() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { stop() }
    }

    override fun onDisable() { stop() }

    fun pose(identity: LeapIdentity): LeapCameraPose? {
        val lease = active ?: return null
        if (!Settings.povEnabled) return null
        if (!current(lease) || scope() != lease.scope) { stop(); return null }
        return lease.cache.pose(identity, System.nanoTime())
    }

    private fun tickSharing() {
        val desired = scope()
        if (desired != active?.scope) {
            stop()
            if (desired != null) active = Lease(desired)
        }
        val lease = active ?: return
        val now = System.nanoTime()
        if (now >= lease.nextControl) subscribe(lease, now)
        if (lease.cache.ready(now) && now >= lease.nextPose) {
            lease.nextPose = now + 500_000_000L
            publish(lease)
        }
    }

    private fun scope(): Scope? {
        val client = MC.instance
        if (!isEnabled() || !Settings.enabled || !Settings.partyPovSharing || !CommunityClient.connected ||
            !DungeonFriendRelay.leapPovAvailable || !DungeonFriendRelay.currentGlobalSession() ||
            !LocationAPI.onHypixel || !LocationAPI.isOnSkyBlock || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return null
        val player = client.player?.takeIf { it.isAlive && !it.isRemoved } ?: return null
        if (DungeonAPI.ownPlayer?.dead == true) return null
        val level = client.level ?: return null
        val connection = client.connection ?: return null
        val authorization = CommunityClient.sessionToken ?: return null
        val roster = RelayMessages.partyRoster ?: return null
        val server = LocationAPI.serverId?.takeIf { leapServerId.matches(it) } ?: return null
        val floor = DungeonAPI.dungeonFloor?.name?.takeIf { leapFloorId.matches(it) } ?: return null
        val names = DungeonAPI.teammates.map { it.name }
        val keys = names.map { normalizedLeapName(it) ?: return null }
        val members = leapPovMembers(names, connection.onlinePlayers
            .filter { info -> keys.any { it.equals(info.profile.name, ignoreCase = true) } }
            .map { LeapIdentity(it.profile.name, it.profile.id) }, roster.members.toSet(),
            LeapIdentity(player.gameProfile.name, client.user.profileId)) ?: return null
        val menu = client.screen?.takeIf { LeapMenu.active(it) }
        val watching = if (Settings.povEnabled && menu != null) LeapMenu.sharedTargets() else emptyMap()
        if (watching.size > 4 || watching.keys.any { it == client.user.profileId || it !in members } ||
            watching.any { (uuid, name) -> normalizedLeapName(name) != normalizedLeapName(members.getValue(uuid)) }) return null
        return Scope(client.user.profileId, DungeonFriendsSettings.relayUrl, authorization, CommunityClient.sessionGeneration,
            DungeonFriendRelay.sessionGeneration, connection, level, ProfileAPI.profileId, ProfileAPI.profileName,
            server, floor, LocationAPI.onAlpha, roster.id, members, menu, watching)
    }

    /** The volatile lease also invalidates writes already waiting behind the shared socket's outgoing write. */
    private fun current(lease: Lease): Boolean = active === lease && Settings.enabled && Settings.partyPovSharing &&
        networkCurrent(lease.scope) && MC.instance.connection === lease.scope.connection && MC.instance.level === lease.scope.level &&
        ProfileAPI.profileId == lease.scope.profileId && ProfileAPI.profileName == lease.scope.profileName &&
        LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && LocationAPI.serverId == lease.scope.server &&
        DungeonAPI.dungeonFloor?.name == lease.scope.floor && LocationAPI.onAlpha == lease.scope.alpha &&
        (lease.scope.menu == null || MC.instance.screen === lease.scope.menu)

    private fun networkCurrent(scope: Scope): Boolean = CommunityClient.connected &&
        CommunityClient.sessionToken == scope.authorization && CommunityClient.sessionGeneration == scope.communityGeneration &&
        DungeonFriendRelay.sessionGeneration == scope.relayGeneration && DungeonFriendRelay.currentGlobalSession() &&
        MC.instance.user.profileId == scope.account && DungeonFriendsSettings.relayUrl == scope.endpoint

    private fun subscribe(lease: Lease, now: Long) {
        lease.nextControl = now + 5_000_000_000L
        val request = Request(UUID.randomUUID().toString().replace("-", ""), now)
        lease.request = request
        val fields = mapOf<String, Any>("id" to request.id, "epoch" to lease.epoch, "enabled" to true,
            "server" to lease.scope.server, "floor" to lease.scope.floor, "alpha" to lease.scope.alpha,
            "party" to lease.scope.party, "members" to lease.scope.members.keys.map { it.toString().replace("-", "") },
            "watching" to lease.scope.watching.keys.sortedBy(UUID::toString).map { it.toString().replace("-", "") })
        if (!DungeonFriendRelay.leapPovPacket("leap_pov_subscribe", fields, { current(lease) && lease.request === request })) lease.request = null
    }

    private fun publish(lease: Lease) {
        if (!current(lease)) return
        val player = MC.instance.player ?: return
        val body = LeapCameraPose(player.x, player.y, player.z, player.yHeadRot, player.xRot)
        val pose = interpolatedLeapPose(body, body, player.eyeHeight, 1f) ?: return
        if (lease.sequence >= 9_007_199_254_740_991L) { stop(); return }
        val fields = mapOf<String, Any>("epoch" to lease.epoch, "seq" to lease.sequence, "x" to pose.x, "y" to pose.y,
            "z" to pose.z, "yaw" to pose.yaw, "pitch" to pose.pitch)
        if (DungeonFriendRelay.leapPovPacket("leap_pov_pose", fields, { current(lease) }, dropIfBusy = true)) lease.sequence++
    }

    private fun receive(json: JsonObject) {
        val lease = active ?: return
        if (!current(lease) || scope() != lease.scope) { stop(); return }
        val now = System.nanoTime()
        when (json.get("type")?.asString) {
            "leap_pov_result" -> {
                val request = lease.request ?: return
                if (json.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != request.id ||
                    json.get("epoch")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != lease.epoch) return
                lease.request = null
                val times = leapPovAcknowledgement(json)
                val acknowledged = times != null && lease.cache.acknowledge(lease.context, times.first, times.second, request.sentNanos, now)
                if (!acknowledged) lease.cache.clear()
            }
            "leap_pov_pose" -> if (lease.scope.menu != null && lease.scope.watching.isNotEmpty()) lease.cache.receive(json, now)
            "leap_pov_remove" -> lease.cache.remove(json)
        }
    }

    private fun stop(revoke: Boolean = true) {
        val previous = active ?: return
        active = null
        previous.request = null
        previous.cache.clear()
        if (revoke) DungeonFriendRelay.leapPovPacket("leap_pov_subscribe",
            mapOf("id" to UUID.randomUUID().toString().replace("-", ""), "epoch" to previous.epoch, "enabled" to false),
            { networkCurrent(previous.scope) })
    }
}

internal fun leapPovMembers(names: List<String>, profiles: List<LeapIdentity>, party: Set<String>, self: LeapIdentity): Map<UUID, String>? {
    val keys = names.map { normalizedLeapName(it) ?: return null }
    if (keys.size !in 2..5 || keys.distinct().size != keys.size || self.key !in keys || self.uuid == null || self.uuid == UUID(0, 0)) return null
    val players = profiles.groupBy { it.key }
    val members = keys.map { name ->
        val profile = players[name]?.singleOrNull() ?: return null
        val uuid = profile.uuid?.takeUnless { it == UUID(0, 0) } ?: return null
        if (uuid.toString().replace("-", "") !in party) return null
        uuid to profile.name
    }.sortedBy { it.first.toString() }.toMap()
    if (members.size != keys.size || members[self.uuid]?.let(::normalizedLeapName) != self.key) return null
    return members
}

internal fun leapPovAcknowledgement(json: JsonObject): Pair<Long, Long>? = runCatching {
    require(!json.has("error") && json.get("enabled")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true)
    fun number(key: String): Long {
        val value = json.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]{1,16}")))
        return value.asLong.also { require(it <= 9_007_199_254_740_991L) }
    }
    number("serverTime") to number("expiresAt")
}.getOrNull()
