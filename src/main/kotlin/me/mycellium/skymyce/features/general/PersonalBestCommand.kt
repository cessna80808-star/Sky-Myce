package me.mycellium.skymyce.features.general

import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStats
import me.mycellium.skymyce.features.instances.dungeons.friends.StatsState
import me.mycellium.skymyce.features.instances.dungeons.friends.FRIEND_FLOORS
import me.mycellium.skymyce.features.instances.dungeons.friends.formatDungeonTime
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendModMessage
import me.mycellium.skymyce.utils.Utils.displayModMessage
import me.mycellium.skymyce.loader.LoaderClient
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import java.util.UUID

internal fun m7PbMessage(name: String, stats: DungeonFriendStats?): String = when (stats?.state) {
    StatsState.AVAILABLE -> stats.sPlusTimes[DungeonFloor.M7]?.let {
        "$name — M7 S+ PB: ${formatDungeonTime(it)} (selected profile)"
    } ?: "$name — No published M7 S+ PB on the selected profile."
    StatsState.HIDDEN -> "$name — Dungeon stats are hidden by their API settings."
    StatsState.NO_PROFILE -> "$name — No SkyBlock profile found."
    else -> "$name — Could not load M7 PB. Check the Minecraft name and try again shortly."
}

/** One local lookup with bounded waiting and no output after its connection/account goes away. */
internal class M7PbLookup(val name: String, private val owner: UUID, private val connection: Any, private val deadline: Long) {
    var finished = false
        private set

    fun poll(owner: UUID, connection: Any?, allowed: Boolean, now: Long, stats: DungeonFriendStats?, fetching: Boolean): String? {
        if (finished) return null
        if (!allowed || this.owner != owner || this.connection !== connection) { finished = true; return null }
        if (stats == null && fetching && now < deadline) return null
        finished = true
        return if (stats == null && fetching) "$name — M7 PB lookup timed out. Try again shortly."
            else m7PbMessage(name, stats)
    }
}

/** The original !pb call site had no implementation. Share the assistant's cache instead of another API client. */
object PersonalBestCommand {
    private var pending: M7PbLookup? = null

    fun lookupM7(name: String = MC.instance.user.name) {
        val connection = MC.connection ?: return
        if (!LoaderClient.localAllowed || MC.instance.player == null) return
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            displayModMessage("Use /m7pb [Minecraft name] (1–16 letters, numbers or underscores).")
            return
        }
        tick(true)
        pending?.let { displayModMessage("Still looking up ${it.name}'s M7 PB. Please wait for that result."); return }
        val owner = MC.instance.user.profileId
        val uuid = if (name.equals(MC.instance.user.name, true)) owner else
            PartyAPI.members.firstOrNull { it.name.equals(name, true) }?.uuid
                ?: FriendsAPI.getFriend(name)?.uuid ?: connection.getPlayerInfo(name)?.profile?.id
        // Request first so a known name/UUID change cannot return another player's cached PB.
        // Explicit lookups refresh expired low-level/hidden profiles too, but retain failed-lookup cooldowns.
        DungeonFriendStatsCache.request(name, uuid, priority = true, refreshExpired = true)
        DungeonFriendStatsCache.verified(name)?.let { displayModMessage(m7PbMessage(name, it)); return }
        if (!DungeonFriendStatsCache.canFetch) {
            displayModMessage("M7 PB lookup is unavailable. Connect to the Sky-Myce relay, enable SkyBlockPv/SkyBlocker, or set your Hypixel API key in Party Commands.")
            return
        }
        pending = M7PbLookup(name, owner, connection, System.nanoTime() + 60_000_000_000L)
        displayModMessage("Looking up $name's M7 S+ PB…")
    }

    fun tick(allowed: Boolean) {
        val lookup = pending ?: return
        lookup.poll(MC.instance.user.profileId, MC.connection, allowed && MC.instance.player != null, System.nanoTime(),
            DungeonFriendStatsCache.verified(lookup.name), DungeonFriendStatsCache.isPending(lookup.name))?.let(::displayModMessage)
        if (lookup.finished) pending = null
    }

    fun handlePbCommand(arguments: String) {
        val floor = FRIEND_FLOORS.firstOrNull { it.name.equals(arguments.trim(), true) }
            ?: DungeonAPI.dungeonFloor?.takeIf { it in FRIEND_FLOORS }
        if (floor == null) {
            sendModMessage("Usage: !pb F1-F7 or M1-M7", ChatChannel.PARTY)
            return
        }
        val name = MC.player.name.string
        DungeonFriendStatsCache.request(name, MC.player.uuid)
        val stats = DungeonFriendStatsCache.get(name)
        val pb = stats?.sPlusTimes?.get(floor)
        sendModMessage("${floor.name} S+ PB: ${formatDungeonTime(pb)}${if (stats == null) " (stats loading; retry shortly)" else ""}", ChatChannel.PARTY)
    }
}
