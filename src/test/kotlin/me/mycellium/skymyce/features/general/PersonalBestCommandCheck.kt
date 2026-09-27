package me.mycellium.skymyce.features.general

import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStats
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.StatsState
import me.mycellium.skymyce.features.instances.dungeons.friends.formatDungeonTime
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.M7
import java.util.UUID

fun checkPersonalBestCommand() {
    val owner = UUID(0, 1)
    val connection = Any()
    val stats = DungeonFriendStats(StatsState.AVAILABLE, catacombs = 50,
        completionTimes = mapOf(M7 to 100000), sPlusTimes = mapOf(M7 to 305678))
    val result = m7PbMessage("Alice", stats)
    check("M7 S+ PB: ${formatDungeonTime(305678)}" in result && "selected profile" in result)
    check(formatDungeonTime(100000) !in result) // A faster non-S+ completion is not the S+ PB.
    check("No published M7 S+ PB" in m7PbMessage("Alice", stats.copy(sPlusTimes = emptyMap())))
    check("hidden" in m7PbMessage("Alice", DungeonFriendStats(StatsState.HIDDEN)))
    check("No SkyBlock profile" in m7PbMessage("Alice", DungeonFriendStats(StatsState.NO_PROFILE)))
    check("Could not load" in m7PbMessage("Alice", DungeonFriendStats(StatsState.UNAVAILABLE)))
    val pending = M7PbLookup("Alice", owner, connection, 1000)
    check(pending.poll(owner, connection, true, 999, null, true) == null && !pending.finished)
    check(pending.poll(owner, connection, true, 999, stats, true) == result && pending.finished)
    check(pending.poll(owner, connection, true, 1000, stats, false) == null) // Output once.
    check(M7PbLookup("Alice", owner, connection, 1000).poll(owner, connection, true, 1000, null, true)?.contains("timed out") == true)
    check(M7PbLookup("Alice", owner, connection, 1000).poll(owner, connection, true, 1, null, false)?.contains("Could not load") == true)
    for ((account, session, allowed) in listOf(Triple(UUID(0, 2), connection, true),
        Triple(owner, Any(), true), Triple(owner, null, true), Triple(owner, connection, false))) {
        val cancelled = M7PbLookup("Alice", owner, connection, 1000)
        check(cancelled.poll(account, session, allowed, 1, stats, false) == null && cancelled.finished)
        check(cancelled.poll(owner, connection, true, 2, stats, false) == null)
    }
    val now = System.currentTimeMillis()
    val uuid = owner.toString().replace("-", "")
    try {
        for (old in listOf(stats.copy(catacombs = 30), DungeonFriendStats(StatsState.HIDDEN), DungeonFriendStats(StatsState.NO_PROFILE))) {
            DungeonFriendStatsCache.clear()
            check(DungeonFriendStatsCache.storeFetched("alice", 0, old, uuid, now - 700000, now - 700000))
            DungeonFriendStatsCache.request("alice", owner)
            check(!DungeonFriendStatsCache.isPending("alice")) // Background scans retain these profiles.
            DungeonFriendStatsCache.request("alice", owner, priority = true, refreshExpired = true)
            check(DungeonFriendStatsCache.isPending("alice")) // An explicit lookup refreshes them.
            DungeonFriendStatsCache.request("alice", owner) // A background scan must not lose the explicit refresh.
            check(DungeonFriendStatsCache.queuedNeedsFetch("alice", now)) // The provider dequeue must honor it too.
            check(DungeonFriendStatsCache.storeFetched("alice", 0, old, uuid, now, now))
            check(!DungeonFriendStatsCache.queuedNeedsFetch("alice", now)) // A result arriving before dequeue avoids a duplicate.
        }
        DungeonFriendStatsCache.clear()
        check(DungeonFriendStatsCache.storeFetched("alice", 0, stats, uuid, now - 700000, now - 700000))
        check(DungeonFriendStatsCache.storeFetched("alice", 0, DungeonFriendStats(StatsState.UNAVAILABLE), uuid, now, now))
        check(DungeonFriendStatsCache.get("alice") == stats && DungeonFriendStatsCache.verified("alice") == null)
        DungeonFriendStatsCache.request("alice", owner, priority = true, refreshExpired = true)
        check(!DungeonFriendStatsCache.isPending("alice")) // Failed refresh retains both old data and the retry cooldown.
        DungeonFriendStatsCache.clear()
        check(DungeonFriendStatsCache.storeFetched("alice", 0, stats, uuid, now, now))
        DungeonFriendStatsCache.request("Alice", owner, priority = true, refreshExpired = true)
        check(!DungeonFriendStatsCache.isPending("alice") && DungeonFriendStatsCache.verified("Alice") == stats)
        DungeonFriendStatsCache.request("Alice", UUID(0, 2), priority = true, refreshExpired = true)
        check(DungeonFriendStatsCache.verified("Alice") == null && DungeonFriendStatsCache.isPending("alice"))
    } finally { DungeonFriendStatsCache.clear() }
    println("M7 PB lookup checks passed: local results, status, timeout, cancellation, freshness, identity and retry cooldown")
}
