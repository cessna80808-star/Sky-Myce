package me.mycellium.skymyce.features.instances.dungeons.splits

enum class MarkResult { ACCEPTED, DUPLICATE, OUT_OF_ORDER, NOT_APPLICABLE }

/** Milliseconds per server tick; server time is ticks × 50 ms, so lag never adds to it. */
const val TICK_MILLIS = 50L

/**
 * The server's tick count. The server sends its game time about once a second (every 20 ticks), as tick timers in
 * NoammAddons and Devonian read it; between those updates, each per-tick ping advances it, at most to the next update.
 * Hypixel sends more pings than ticks (about 4% more in M7), so pings are never added up on their own.
 */
/**
 * Lag lost while a run is live, read once per server time update. A late packet can only make lag look larger, and a
 * server never runs extra ticks to catch up, so the lowest of the last few readings is the honest one and the shown
 * value only ever rises. This keeps the HUD steady instead of swinging with packet jitter.
 */
class LagMeter(private val window: Int = 3) {
    private val readings = ArrayDeque<Long>()
    /** Null until a first reading. */
    var shown: Long? = null
        private set

    fun read(lagMillis: Long) {
        readings.addLast(lagMillis)
        while (readings.size > window) readings.removeFirst()
        val floor = readings.min().coerceAtLeast(0)
        shown = maxOf(shown ?: 0L, floor)
    }
}

class ServerTickClock {
    private var gameTime: Long? = null
    private var pings = 0
    // Never reported lower than before, even when an update lands a tick behind the pings.
    private var floor = 0L

    /** Null until the first game time arrives. */
    val now: Long? get() = gameTime?.let { maxOf(it + pings, floor) }

    /** False when the clock jumped (another world, or a gap of minutes), so timing from before it is not comparable. */
    fun time(value: Long): Boolean {
        val previous = now
        val continuous = previous == null || value >= previous - UPDATE_TICKS && value - previous <= MAX_GAP_TICKS
        gameTime = value; pings = 0; floor = if (continuous) previous ?: value else value
        return continuous
    }

    fun ping() { if (gameTime != null && pings < UPDATE_TICKS) pings++ }

    fun reset() { gameTime = null; pings = 0; floor = 0L }

    private companion object {
        const val UPDATE_TICKS = 20
        const val MAX_GAP_TICKS = 20L * 60 * 5
    }
}

/**
 * Live, single-run split state. Times are monotonic nanoseconds from the caller; they are stored as milliseconds from
 * the run's origin. Each milestone is accepted once, chain milestones only in chain order, and nothing is back-filled.
 *
 * Alongside real time, each mark keeps the server tick count since the origin (NoammAddons `DualTime`): real time is
 * what the clock measured, server time is how many ticks the server ran, and their difference is time lost to lag.
 */
class SplitTimeline(val definition: SplitDefinition, val startObserved: Boolean, private val originNanos: Long,
                    originTicks: Long? = null) {
    private val marked = LinkedHashMap<SplitMilestone, Long>()
    private val tickMarks = LinkedHashMap<SplitMilestone, Long>()
    private var tickOrigin = originTicks
    private var lastChainIndex = -1
    var conflicts = 0
        private set

    init {
        if (startObserved) {
            marked[SplitMilestone.START] = 0L; lastChainIndex = 0
            if (tickOrigin != null) tickMarks[SplitMilestone.START] = 0L
        }
    }

    val marks: Map<SplitMilestone, Long> get() = marked
    val ticks: Map<SplitMilestone, Long> get() = tickMarks
    val finished get() = SplitMilestone.END in marked
    /** False once ticks were missed (a reconnect) or never arrived; earlier tick marks stay valid. */
    val ticksAvailable get() = tickOrigin != null

    fun elapsed(nowNanos: Long): Long = ((nowNanos - originNanos) / 1_000_000L).coerceAtLeast(0)
    fun elapsedTicks(nowTicks: Long?): Long? = tickOrigin?.let { origin -> nowTicks?.let { (it - origin).coerceAtLeast(0) } }

    /** Stop server timing for the rest of the run, e.g. after ticks were missed while disconnected. */
    fun stopTicks() { tickOrigin = null }

    fun mark(milestone: SplitMilestone, atNanos: Long, atTicks: Long? = null): MarkResult {
        if (milestone == SplitMilestone.START) return if (startObserved) MarkResult.DUPLICATE else MarkResult.NOT_APPLICABLE
        if (milestone in marked) return MarkResult.DUPLICATE
        if (finished) return MarkResult.OUT_OF_ORDER
        val at = elapsed(atNanos)
        val result = if (milestone in definition.markers) MarkResult.ACCEPTED else {
            val index = definition.chain.indexOf(milestone)
            when {
                index < 0 -> MarkResult.NOT_APPLICABLE
                // A later phase has already been seen: this event is late or contradictory, never a reason to rewrite history.
                index <= lastChainIndex -> { conflicts++; MarkResult.OUT_OF_ORDER }
                at < (marked.values.maxOrNull() ?: 0L) -> { conflicts++; MarkResult.OUT_OF_ORDER }
                else -> { lastChainIndex = index; MarkResult.ACCEPTED }
            }
        }
        if (result != MarkResult.ACCEPTED) return result
        marked[milestone] = at
        markTicks(milestone, at, atTicks)
        return result
    }

    private fun markTicks(milestone: SplitMilestone, at: Long, atTicks: Long?) {
        val ticks = elapsedTicks(atTicks) ?: return
        // No game time for a while, or more server time than real time: the tick source is not trustworthy for this run.
        if (at > 10_000 && ticks == 0L || ticks * TICK_MILLIS > at + at / 20 + 1_000) { stopTicks(); return }
        tickMarks[milestone] = ticks
    }

    /** The section currently running: the next chain milestone after the latest one observed. */
    fun current(): SplitMilestone? = if (finished) null else definition.chain.drop(lastChainIndex + 1).firstOrNull()
        ?.takeIf { lastChainIndex >= 0 }

    fun record(id: String, recordedAt: Long, outcome: SplitOutcome) =
        SplitRunRecord(id, definition.floor, definition.version, recordedAt, startObserved, LinkedHashMap(marked), outcome, conflicts,
            LinkedHashMap(tickMarks))
}
