package me.mycellium.skymyce.features.instances.dungeons.routing

data class RoomRoutingContext(val worldEpoch: Long, val runEpoch: Long, val roomKey: String) {
    init { require(roomKey.isNotBlank()) }
}

enum class RoomReplanReason { CONTEXT_CHANGED, TARGETS_CHANGED, GEOMETRY_CHANGED, MOVED, TELEPORTED, RETRY }

/** Cache the known Blood doorway for the run, even when routing stops after opening it. */
internal class BloodDoorReminder {
    private var door: GridCell? = null
    private var blood = emptySet<RoomSegment>()

    fun clear() { door = null; blood = emptySet() }

    fun observe(exit: DungeonExit?) {
        val gate = exit?.doors?.lastOrNull { it.kind == "Blood door" }
        if (gate != null) {
            door = gate.door
            blood = gate.to.ifEmpty { setOf(dungeonGridSegment(gate.departure.x + .5, gate.departure.z + .5)) }
        } else if (exit?.kind == "Blood door") {
            val dx = exit.door.x.compareTo(exit.approach.x)
            val dz = exit.door.z.compareTo(exit.approach.z)
            if ((dx == 0) == (dz == 0)) return
            door = exit.door
            blood = setOf(dungeonGridSegment(exit.door.x + dx * 3 + .5, exit.door.z + dz * 3 + .5))
        }
    }

    fun highlight(mage: Boolean, position: RoomPoint): GridCell? = door.takeIf {
        mage && position.x.isFinite() && position.z.isFinite() && blood.none { room ->
            position.x >= room.x - .5 && position.x < room.x + 31.5 &&
                position.z >= room.z - .5 && position.z < room.z + 31.5
        }
    }
}

/** Keep checked guidance after obstacle removal only while its floor and every movement edge remain valid. */
internal fun checkedRoomPathSurvivesChange(points: List<RoomPoint>, canConnect: (RoomPoint, RoomPoint) -> Boolean): Boolean {
    if (points.isEmpty() || points.size > 65) return false
    return try {
        canConnect(points.last(), points.last()) && points.zipWithNext().all { (a, b) -> canConnect(a, b) }
    } catch (_: RuntimeException) { false }
}

/** An unfinished approach probe cannot discard a previously routed target that still needs a stop. */
internal fun roomApproachesPending(previous: Set<String>, selected: Set<String>, required: Set<String>, pending: Boolean) =
    pending && previous.any { it in required && it !in selected }

/** Partial geometry must not replace a required stop with an empty or exit-only guide. */
internal fun roomLocalGuideRelevant(route: RoomRoute?, required: Set<String>, exit: GridCell?) =
    if (required.isEmpty()) exit != null && route?.path?.lastOrNull() == exit
    else route?.targetOrder?.firstOrNull()?.let(required::contains) == true

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
    val observedPosition: RoomPoint? get() = observed

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
        const val MOVEMENT_DEBOUNCE_MILLIS = 100L
        const val STABLE_RETRY_MILLIS = 500L
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

    fun observe(start: GridCell, position: RoomPoint, nowMillis: Long, automaticMovement: Boolean = true, followingPath: Boolean = false) {
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
        val moved = automaticMovement && !followingPath && distanceSquared(anchor, position) >= MOVEMENT_BLOCKS * MOVEMENT_BLOCKS
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
