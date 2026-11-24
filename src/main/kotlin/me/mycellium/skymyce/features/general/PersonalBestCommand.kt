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
import net.minecraft.client.multiplayer.ClientPacketListener
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import java.util.UUID

internal fun m7PbMessage(name: String, stats: DungeonFriendStats?): String = when (stats?.state) {
    StatsState.AVAILABLE -> stats.sPlusTimes[DungeonFloor.M7]?.let {
        "$name — M7 S+ PB: ${formatDungeonTime(it).replace('.', ':')}"
    } ?: "$name — No M7 S+ PB."
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
    private val pending = mutableListOf<M7PbLookup>()

    fun lookupM7(name: String = MC.instance.user.name) {
        val connection = MC.connection ?: return
        if (MC.instance.player == null) return
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            displayModMessage("Use /m7pb [Minecraft name] (1–16 letters, numbers or underscores).")
            return
        }
        lookup(listOf(name), connection)
    }

    /** Every party member's M7 S+ PB, one line each as they arrive. */
    fun lookupPartyM7() {
        val connection = MC.connection ?: return
        if (MC.instance.player == null) return
        val names = PartyAPI.members.mapNotNull { it.name }
        if (!PartyAPI.inParty || names.isEmpty()) { displayModMessage("You're not in a party."); return }
        lookup((names + MC.instance.user.name).distinctBy { it.lowercase() }, connection)
    }

    private fun lookup(names: List<String>, connection: ClientPacketListener) {
        tick(true)
        // Priority requests go to the front of the queue, so the last one is fetched first.
        val waiting = names.asReversed().filter { name ->
            pending.none { it.name.equals(name, true) } && !request(name, connection)
        }
        if (waiting.isEmpty()) return
        if (!DungeonFriendStatsCache.canFetch) {
            displayModMessage("M7 PB lookup is unavailable. Connect to the Sky-Myce relay, enable SkyBlockPv/SkyBlocker, or set your Hypixel API key in Party Commands.")
            return
        }
        val deadline = System.nanoTime() + (60 + 5L * waiting.size) * 1_000_000_000L
        waiting.mapTo(pending) { M7PbLookup(it, MC.instance.user.profileId, connection, deadline) }
    }

    /** Shows a fresh cached PB and returns true, or queues a lookup. */
    private fun request(name: String, connection: ClientPacketListener): Boolean {
        val owner = MC.instance.user.profileId
        val uuid = if (name.equals(MC.instance.user.name, true)) owner else
            PartyAPI.members.firstOrNull { it.name.equals(name, true) }?.uuid
                ?: FriendsAPI.getFriend(name)?.uuid ?: connection.getPlayerInfo(name)?.profile?.id
        // Request first so a known name/UUID change cannot return another player's cached PB.
        // Explicit lookups refresh expired low-level/hidden profiles too, but retain failed-lookup cooldowns.
        DungeonFriendStatsCache.request(name, uuid, priority = true, refreshExpired = true)
        DungeonFriendStatsCache.verified(name)?.let { displayModMessage(m7PbMessage(name, it)); return true }
        return false
    }

    fun tick(allowed: Boolean) {
        pending.removeAll { lookup ->
            lookup.poll(MC.instance.user.profileId, MC.connection, allowed && MC.instance.player != null, System.nanoTime(),
                DungeonFriendStatsCache.verified(lookup.name), DungeonFriendStatsCache.isPending(lookup.name))?.let(::displayModMessage)
            lookup.finished
        }
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
