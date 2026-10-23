package me.mycellium.skymyce.features.instances.dungeons.leap

import com.google.gson.JsonObject
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

import java.util.UUID

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
