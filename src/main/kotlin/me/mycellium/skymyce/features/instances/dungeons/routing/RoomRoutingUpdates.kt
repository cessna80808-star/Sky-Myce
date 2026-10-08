package me.mycellium.skymyce.features.instances.dungeons.routing

data class RoomRoutingContext(val worldEpoch: Long, val runEpoch: Long, val roomKey: String) {
    init { require(roomKey.isNotBlank()) }
}

enum class RoomReplanReason { CONTEXT_CHANGED, TARGETS_CHANGED, GEOMETRY_CHANGED, MOVED, TELEPORTED, RETRY }

@ConsistentCopyVisibility
data class RoomReplanRequest internal constructor(
    val revision: Long,
    val context: RoomRoutingContext,
    val start: GridCell,
    val position: RoomPoint,
    val reason: RoomReplanReason
)

/** Small game-thread gate; workers return their original immutable request to complete(). */
class RoomRoutingUpdates {
    var revision: Long = 0
        private set
    val reason: RoomReplanReason? get() = pending

    private var context: RoomRoutingContext? = null
    private var pending: RoomReplanReason? = null
    private var dueAt = Long.MAX_VALUE
    private var start: GridCell? = null
    private var position: RoomPoint? = null
    private var observed: RoomPoint? = null
    private var planned: RoomPoint? = null
    private var plannedHasPath = false
    private var inFlight: RoomReplanRequest? = null
    private var stableRetries = 0
    private var lastNow = Long.MIN_VALUE

    companion object {
        const val MOVEMENT_BLOCKS = 2.0
        const val TELEPORT_BLOCKS = 8.0
        const val MOVEMENT_DEBOUNCE_MILLIS = 500L
        const val STABLE_RETRY_MILLIS = 5_000L
        const val MAX_STABLE_RETRIES = 1
    }

    fun updateContext(context: RoomRoutingContext?, nowMillis: Long) {
        val now = time(nowMillis)
        if (this.context == context) return
        clear()
        this.context = context
        if (context != null) { pending = RoomReplanReason.CONTEXT_CHANGED; dueAt = now }
    }

    /** Always advances the revision, including death/reconnect into the same room and epochs. */
    fun clear() {
        revision++
        context = null
        pending = null
        dueAt = Long.MAX_VALUE
        start = null
        position = null
        observed = null
        planned = null
        plannedHasPath = false
        inFlight = null
        stableRetries = 0
    }

    fun invalidate(reason: RoomReplanReason, nowMillis: Long) {
        val now = time(nowMillis)
        if (context == null) return
        request(reason, if (reason == RoomReplanReason.MOVED) after(now, MOVEMENT_DEBOUNCE_MILLIS) else now)
    }

    fun observe(start: GridCell, position: RoomPoint, nowMillis: Long, automaticMovement: Boolean = true) {
        val now = time(nowMillis)
        if (context == null) return
        val previous = observed
        this.start = start
        this.position = position
        observed = position
        if (previous != null && distanceSquared(previous, position) >= TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
            request(RoomReplanReason.TELEPORTED, now)
            return
        }
        val anchor = planned ?: return
        val moved = automaticMovement && distanceSquared(anchor, position) >= MOVEMENT_BLOCKS * MOVEMENT_BLOCKS
        if (!moved && pending == RoomReplanReason.MOVED && plannedHasPath) {
            // Coalesce small threshold oscillations without planning after the player returns.
            pending = null
            dueAt = Long.MAX_VALUE
        } else if (moved && (pending == null || pending == RoomReplanReason.RETRY)) {
            request(RoomReplanReason.MOVED, after(now, MOVEMENT_DEBOUNCE_MILLIS))
        }
    }

    fun due(nowMillis: Long): Boolean {
        val now = time(nowMillis)
        return context != null && pending != null && start != null && position != null && inFlight == null && now >= dueAt
    }

    fun take(nowMillis: Long): RoomReplanRequest? {
        if (!due(nowMillis)) return null
        revision++
        val request = RoomReplanRequest(revision, context!!, start!!, position!!, pending!!)
        pending = null
        dueAt = Long.MAX_VALUE
        planned = request.position
        plannedHasPath = false
        inFlight = request
        return request
    }

    /** False results are stale/duplicate; they neither block a new request nor schedule retries. */
    fun complete(request: RoomReplanRequest, foundPath: Boolean, nowMillis: Long): Boolean {
        val now = time(nowMillis)
        if (inFlight !== request || request.revision != revision || request.context != context) return false
        inFlight = null
        plannedHasPath = foundPath
        if (!foundPath && stableRetries < MAX_STABLE_RETRIES) {
            stableRetries++
            pending = RoomReplanReason.RETRY
            dueAt = after(now, STABLE_RETRY_MILLIS)
        } else {
            pending = null
            dueAt = Long.MAX_VALUE
            if (foundPath) stableRetries = 0
        }
        return true
    }

    private fun request(reason: RoomReplanReason, due: Long) {
        revision++
        inFlight = null
        if (pending == null || priority(reason) >= priority(pending!!)) pending = reason
        dueAt = minOf(dueAt, due)
        if (reason != RoomReplanReason.RETRY) stableRetries = 0
    }

    private fun time(now: Long): Long = maxOf(now, lastNow).also { lastNow = it }

    private fun after(now: Long, delay: Long): Long = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay

    private fun priority(reason: RoomReplanReason): Int = when (reason) {
        RoomReplanReason.CONTEXT_CHANGED -> 4
        RoomReplanReason.TELEPORTED -> 3
        RoomReplanReason.TARGETS_CHANGED, RoomReplanReason.GEOMETRY_CHANGED -> 2
        RoomReplanReason.MOVED -> 1
        RoomReplanReason.RETRY -> 0
    }

    private fun distanceSquared(from: RoomPoint, to: RoomPoint): Double {
        val x = from.x - to.x; val y = from.y - to.y; val z = from.z - to.z
        return x * x + y * y + z * z
    }
}
