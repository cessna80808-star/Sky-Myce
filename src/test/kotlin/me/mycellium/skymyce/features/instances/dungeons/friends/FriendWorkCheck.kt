package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

internal fun checkFriendWork() {
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
