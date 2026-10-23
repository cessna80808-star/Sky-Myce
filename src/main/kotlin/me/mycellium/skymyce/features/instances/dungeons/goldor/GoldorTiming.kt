package me.mycellium.skymyce.features.instances.dungeons.goldor

import kotlin.math.max

/** An estimated duration. [confidence] LOW means only the order of magnitude is meaningful. */
data class Estimate(val millis: Long, val confidence: Confidence) {
    operator fun plus(other: Estimate) = Estimate(millis + other.millis, weaker(confidence, other.confidence))
    companion object { val ZERO = Estimate(0, Confidence.HIGH) }
}

/** The weaker of two confidences. */
fun weaker(a: Confidence, b: Confidence) = if (a.ordinal >= b.ordinal) a else b

/**
 * What is known about one terminal while someone works on it: its puzzle when the worker's SM client shared it, the
 * worker's progress in quarters, and when work began on it.
 */
data class TerminalWork(val puzzle: PuzzleType?, val quarters: Int = 0, val startedAt: Long? = null, val complete: Boolean = false)

/**
 * Remaining work on one terminal. A known puzzle uses its profile time; progress removes its share. Time already
 * spent counts too, but never brings a started terminal below a quarter of its time until it is complete, since a
 * slow attempt is still unfinished. An unknown puzzle uses the profile's unknown time at low confidence.
 */
fun remainingWork(work: TerminalWork, timing: PuzzleTiming, now: Long): Estimate {
    if (work.complete) return Estimate.ZERO
    val full = timing.millis(work.puzzle)
    val byProgress = full * (4 - work.quarters.coerceIn(0, 3)) / 4
    val spent = work.startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L
    val left = max(byProgress - spent, full / 4).coerceAtMost(byProgress)
    return Estimate(left, if (work.puzzle == null) Confidence.LOW else if (work.startedAt == null) Confidence.MEDIUM else Confidence.HIGH)
}

/**
 * Goldor's damage tick. [originMillis] is a tick that was observed (or derived from an observed phase line) and
 * [periodMillis] the configured interval; both come from the profile. Null origin means the tick is not known.
 */
data class GoldorTick(val originMillis: Long?, val periodMillis: Long, val observed: Boolean) {
    /** The first tick strictly after [time], or null when the tick is unknown. */
    fun nextAfter(time: Long): Long? {
        val origin = originMillis ?: return null
        if (time < origin) return origin
        return origin + ((time - origin) / periodMillis + 1) * periodMillis
    }
    /** The last tick at or before [time]. */
    fun lastAtOrBefore(time: Long): Long? {
        val origin = originMillis ?: return null
        if (time < origin) return null
        return origin + (time - origin) / periodMillis * periodMillis
    }
}

enum class LeapPhase { WAITING_TICK, PREPARE, LEAP, TRAVELLING, ARRIVED, MISSED, UNCERTAIN }

/**
 * One pre-leap sequence: the intended Goldor tick kill, the leap after it, the travel to the next terminal and arrival
 * before the next section opens. Times are absolute milliseconds on the same clock as [GoldorTick].
 */
data class LeapPlan(
    val phase: LeapPhase,
    val reason: String,
    val confidence: Confidence,
    /** The tick the leap waits for. */
    val tickAt: Long? = null,
    /** Leap no earlier than this… */
    val leapFrom: Long? = null,
    /** …and no later than this, or the travel no longer fits before [opensAt]. */
    val leapUntil: Long? = null,
    /** When the next section is expected to open. */
    val opensAt: Long? = null,
)

/**
 * Plans the pre-leap. The rule is the strategy's: wait for the Goldor tick kill, leap just after it, then travel and
 * reach the next terminal before the next section opens. The intended tick is the last one that still leaves time to
 * leap and travel before the predicted opening. Everything is recomputed whenever progress or the tick changes, so an
 * earlier or later tick, or faster work, moves the window.
 *
 * - No estimate of the remaining work, or no known tick: [LeapPhase.UNCERTAIN], with no times shown as exact.
 * - Low-confidence work estimate: the plan is computed but marked LOW so the HUD shows it as approximate.
 * - Not enough time left to leap and travel before the opening: [LeapPhase.MISSED].
 * - The fitting tick has passed: leap now only if no further tick lands before the opening, else [LeapPhase.MISSED].
 *
 * [reactionMillis] is how long the player takes to see the call and leap: every call ([LeapPlan.leapFrom], the prepare
 * warning and the latest moment) comes that much earlier, so the leap itself lands on time.
 */
fun planPreLeap(
    now: Long,
    remaining: Estimate?,
    tick: GoldorTick,
    timing: LeapTiming,
    travelMillis: Long?,
    leapt: Boolean = false,
    arrived: Boolean = false,
    reactionMillis: Long = 0,
): LeapPlan {
    if (arrived) return LeapPlan(LeapPhase.ARRIVED, "at target", Confidence.HIGH)
    if (leapt) return LeapPlan(LeapPhase.TRAVELLING, "go to target", Confidence.HIGH)
    if (remaining == null) return LeapPlan(LeapPhase.UNCERTAIN, "work unknown", Confidence.LOW)
    val travel = travelMillis ?: return LeapPlan(LeapPhase.UNCERTAIN, "target position unknown", Confidence.LOW)
    val opensAt = now + remaining.millis
    // The latest moment the leap still gets the player to the terminal with the safety margin to spare.
    val latestLeap = opensAt - timing.leapCastMillis - travel - timing.safetyMarginMillis - reactionMillis
    val confidence = weaker(remaining.confidence, if (tick.observed) Confidence.HIGH else Confidence.MEDIUM)
    if (latestLeap < now) return LeapPlan(LeapPhase.MISSED, "not enough time", weaker(confidence, Confidence.MEDIUM), opensAt = opensAt)
    if (tick.originMillis == null) return LeapPlan(LeapPhase.UNCERTAIN, "Goldor tick unknown", Confidence.LOW, leapUntil = latestLeap, opensAt = opensAt)
    // The intended tick kill: the last tick whose leap (tick + delay) still fits.
    val intended = tick.lastAtOrBefore(latestLeap + reactionMillis - timing.leapDelayAfterTickMillis)
    val leapFrom = intended?.plus(timing.leapDelayAfterTickMillis - reactionMillis)
    if (intended == null || leapFrom == null || leapFrom < now - timing.leapWindowMillis) {
        // No tick-timed leap remains. Leaping now is only safe if no tick lands before the section opens.
        val next = tick.nextAfter(now)
        if (next != null && next < opensAt) return LeapPlan(LeapPhase.MISSED, "no safe tick before opening", weaker(confidence, Confidence.MEDIUM), next, opensAt = opensAt)
        return LeapPlan(LeapPhase.LEAP, "leap now — no tick before opening", weaker(confidence, Confidence.MEDIUM), intended, now, latestLeap, opensAt)
    }
    val until = minOf(latestLeap, leapFrom + timing.leapWindowMillis)
    val phase = when {
        now >= leapFrom -> LeapPhase.LEAP
        now >= intended - timing.prepareLeadMillis - reactionMillis -> LeapPhase.PREPARE
        else -> LeapPhase.WAITING_TICK
    }
    val reason = when (phase) {
        LeapPhase.LEAP -> "leap after tick"
        LeapPhase.PREPARE -> "tick in ${seconds(intended - now)}"
        else -> "waiting for Goldor tick"
    }
    return LeapPlan(phase, reason, confidence, intended, leapFrom, until, opensAt)
}

/** Seconds to one decimal, for reasons and HUD text. */
fun seconds(millis: Long) = String.format(java.util.Locale.ROOT, "%.1fs", millis.coerceAtLeast(0) / 1000.0)

/** Walking time between two points at the profile's movement speed, plus a fixed overhead for turning and clicking. */
fun travelMillis(distanceBlocks: Double, timing: LeapTiming): Long =
    timing.travelOverheadMillis + (distanceBlocks / timing.blocksPerSecond * 1000).toLong()
