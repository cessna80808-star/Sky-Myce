package me.mycellium.skymyce.features.instances.dungeons.splits

enum class MarkResult { ACCEPTED, DUPLICATE, OUT_OF_ORDER, NOT_APPLICABLE }

/**
 * Live, single-run split state. Times are monotonic nanoseconds from the caller; they are stored as milliseconds from
 * the run's origin. Each milestone is accepted once, chain milestones only in chain order, and nothing is back-filled.
 */
class SplitTimeline(val definition: SplitDefinition, val startObserved: Boolean, private val originNanos: Long) {
    private val marked = LinkedHashMap<SplitMilestone, Long>()
    private var lastChainIndex = -1
    var conflicts = 0
        private set

    init { if (startObserved) { marked[SplitMilestone.START] = 0L; lastChainIndex = 0 } }

    val marks: Map<SplitMilestone, Long> get() = marked
    val finished get() = SplitMilestone.END in marked

    fun elapsed(nowNanos: Long): Long = ((nowNanos - originNanos) / 1_000_000L).coerceAtLeast(0)

    fun mark(milestone: SplitMilestone, atNanos: Long): MarkResult {
        if (milestone == SplitMilestone.START) return if (startObserved) MarkResult.DUPLICATE else MarkResult.NOT_APPLICABLE
        if (milestone in marked) return MarkResult.DUPLICATE
        if (finished) return MarkResult.OUT_OF_ORDER
        val at = elapsed(atNanos)
        if (milestone in definition.markers) { marked[milestone] = at; return MarkResult.ACCEPTED }
        val index = definition.chain.indexOf(milestone)
        if (index < 0) return MarkResult.NOT_APPLICABLE
        // A later phase has already been seen: this event is late or contradictory, never a reason to rewrite history.
        if (index <= lastChainIndex) { conflicts++; return MarkResult.OUT_OF_ORDER }
        val previous = marked.values.maxOrNull() ?: 0L
        if (at < previous) { conflicts++; return MarkResult.OUT_OF_ORDER }
        marked[milestone] = at; lastChainIndex = index
        return MarkResult.ACCEPTED
    }

    /** The section currently running: the next chain milestone after the latest one observed. */
    fun current(): SplitMilestone? = if (finished) null else definition.chain.drop(lastChainIndex + 1).firstOrNull()
        ?.takeIf { lastChainIndex >= 0 }

    fun lastChainMark(): Long? = definition.chain.getOrNull(lastChainIndex)?.let { marked[it] }

    fun record(id: String, recordedAt: Long, outcome: SplitOutcome) =
        SplitRunRecord(id, definition.floor, definition.version, recordedAt, startObserved, LinkedHashMap(marked), outcome, conflicts)
}
