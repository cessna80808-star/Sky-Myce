package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

internal fun checkFriendWork() {
    val gate = WealthRosterGate()
    check(gate.ready(0))
    gate.idle(100)
    check(!gate.ready(1099) && gate.ready(1100))
    gate.idle(2000, 2050)
    check(!gate.ready(2049) && gate.ready(2050)) // Exact freshness deadline takes precedence over idle polling.
    gate.idle(3000, 999999)
    check(gate.ready(4000)) // Changes without a native event are still discovered within the existing one-second bound.
    gate.idle(4000)
    gate.wake()
    check(gate.ready(4001)) // User action/identity/roster/provider changes wake on the next native tick.

    val cases = listOf(
        FriendWealth(fetchedAt = 1000, hasProfile = true),
        FriendWealth(fetchedAt = 1000, hasProfile = true, expires = 0),
        FriendWealth(fetchedAt = 1000, hasProfile = true, expires = 1000 + FRIEND_WEALTH_TTL + 5000),
        FriendWealth(fetchedAt = 1000, hasProfile = false),
        FriendWealth(fetchedAt = 1000, hasProfile = null),
    )
    for (entry in cases) for (online in listOf(false, true)) for (skyblock in listOf(false, true)) {
        for (now in listOf(1000L, 60999L, 61000L, 1000L + FRIEND_WEALTH_TTL - 1, 1000L + FRIEND_WEALTH_TTL, 1000L + FRIEND_WEALTH_TTL + 5000)) {
            val deadline = entry.nextRefresh(now, online, skyblock)
            check((deadline <= now) == entry.shouldRefresh(now, online, skyblock))
            if (deadline != Long.MAX_VALUE && deadline > now) {
                check(!entry.shouldRefresh(deadline - 1, online, skyblock))
                check(entry.shouldRefresh(deadline, online, skyblock))
            }
        }
    }

    val requests = linkedMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    val expired = CompletableFuture<JsonObject?>()
    val live = CompletableFuture<JsonObject?>()
    val inserted = CompletableFuture<JsonObject?>()
    requests["expired"] = 100L to expired
    requests["live"] = 200L to live
    expired.whenComplete { _, _ -> requests["inserted"] = 50L to inserted }
    expireRelayRequests(requests, 100)
    check(expired.isDone && !live.isDone && !inserted.isDone)
    check(requests.keys == setOf("live", "inserted")) // Callbacks can safely enqueue a new request.
    expireRelayRequests(requests, 100)
    check(inserted.isDone && requests.keys == setOf("live")) // Snapshot excludes work added during completion.
    val cancelled = CompletableFuture<JsonObject?>().apply { cancel(false) }
    requests["cancelled"] = 300L to cancelled
    expireRelayRequests(requests, 150, cancelled = false)
    check(requests.keys == setOf("live", "cancelled")) // Existing shared-stats cancellation policy retained.
    expireRelayRequests(requests, 150)
    check(requests.keys == setOf("live"))
    expireRelayRequests(requests, 200)
    check(live.isDone && requests.isEmpty())

    // Disconnect drops queued ownership and wakes scheduling without changing provider capability.
    DungeonFriendStatsCache.clear()
    DungeonFriendStatsCache.request("QueuedFriend")
    check(DungeonFriendStatsCache.pendingCount == 1)
    check(DungeonFriendStatsCache.nextAutomaticRefresh("QueuedFriend", 100) == Long.MAX_VALUE)
    val queuedVersion = DungeonFriendStatsCache.version
    DungeonFriendStatsCache.disconnect()
    check(DungeonFriendStatsCache.pendingCount == 0 && DungeonFriendStatsCache.version > queuedVersion)
    check(DungeonFriendStatsCache.nextAutomaticRefresh("QueuedFriend", 200) == 200L)
    DungeonFriendStatsCache.request("QueuedFriend")
    check(DungeonFriendStatsCache.pendingCount == 1) // The new generation owns a fresh request.
    DungeonFriendStatsCache.clear()
}
