package me.mycellium.skymyce.features.performance

import me.mycellium.skymyce.ui.AutoResponse
import me.mycellium.skymyce.ui.GuiQuality
import java.util.Locale

/** Exact pre-optimization AutoGraphicsController (2.7.29), kept only as the differential-check and benchmark reference. */
internal class PreviousAutoGraphicsController(private val tiers: List<GuiQuality> = AUTO_TIERS) {
    var tier = tiers.indexOf(GuiQuality.MEDIUM).coerceAtLeast(0)
        private set
    var reason = "Starting at ${tiers[tier]}"
        private set
    var lastChange = Long.MIN_VALUE / 2
        private set

    private val fps = ArrayDeque<Pair<Long, Int>>()
    private val frames = ArrayDeque<Pair<Long, Double>>()
    private var badSince: Long? = null
    private var goodSince: Long? = null
    private var pausedUntil = Long.MIN_VALUE / 2

    val quality get() = tiers[tier]

    /** A loading screen or world change: forget the measurements and wait. */
    fun pause(now: Long) { pausedUntil = now + SETTLE_MILLIS; fps.clear(); frames.clear(); badSince = null; goodSince = null }

    /**
     * One measurement. [currentFps] is the last second's frame count; [frameMillis] the last frame's duration.
     * [targetFps] is the user's target already limited by the frame cap; [lowest] the lowest tier Auto may use.
     */
    fun sample(now: Long, currentFps: Int, frameMillis: Double, targetFps: Int, response: AutoResponse, lowest: GuiQuality = tiers.first()): GuiQuality {
        if (now < pausedUntil) return quality
        if (currentFps > 0) fps.addLast(now to currentFps)
        if (frameMillis.isFinite() && frameMillis > 0 && frameMillis < 2_000) frames.addLast(now to frameMillis)
        val window = maxOf(response.degradeMillis, response.restoreMillis)
        while (fps.isNotEmpty() && fps.first().first < now - window) fps.removeFirst()
        while (frames.isNotEmpty() && frames.first().first < now - window) frames.removeFirst()
        if (targetFps <= 0 || fps.size < 2 || frames.size < 10) return quality

        val floor = tiers.indexOf(lowest).coerceAtLeast(0)
        val targetMillis = 1000.0 / targetFps
        val recentFps = fps.filter { it.first >= now - response.degradeMillis }.map { it.second }.average()
        val recentFrames = frames.filter { it.first >= now - response.degradeMillis }.map { it.second }
        val p95 = percentile(recentFrames, 0.95)
        val bad = recentFps < targetFps * 0.9 || p95 > targetMillis * 1.6
        val good = recentFps >= targetFps * 1.15 && p95 <= targetMillis * 1.25
        badSince = if (bad) badSince ?: now else null
        goodSince = if (good) goodSince ?: now else null
        val coolingDown = now - lastChange < COOLDOWN_MILLIS
        if (bad && !coolingDown && now - badSince!! >= response.degradeMillis && tier > floor) {
            tier--; lastChange = now; badSince = null; goodSince = null
            reason = String.format(Locale.ROOT, "Lowered to %s: %.0f FPS, 95%% of frames under %.1f ms (target %d FPS)", quality, recentFps, p95, targetFps)
        } else if (good && !coolingDown && now - goodSince!! >= response.restoreMillis && tier < tiers.lastIndex) {
            tier++; lastChange = now; goodSince = null; badSince = null
            reason = String.format(Locale.ROOT, "Raised to %s: %.0f FPS, steady frames (target %d FPS)", quality, recentFps, targetFps)
        } else if (tier < floor) {
            tier = floor; lastChange = now; reason = "Raised to your lowest allowed quality, $quality"
        }
        return quality
    }

    /** Recent averages for the status line. */
    fun summary(now: Long): Pair<Double, Double> {
        val f = fps.filter { it.first >= now - 5_000 }.map { it.second }
        val t = frames.filter { it.first >= now - 5_000 }.map { it.second }
        return (if (f.isEmpty()) 0.0 else f.average()) to percentile(t, 0.95)
    }

    companion object {
        /** Lowest to highest; Auto moves along these. */
        val AUTO_TIERS = listOf(GuiQuality.EXTREMELY_LIGHT, GuiQuality.LIGHT, GuiQuality.MEDIUM, GuiQuality.HIGH)
        const val COOLDOWN_MILLIS = 8_000L
        const val SETTLE_MILLIS = 5_000L

        internal fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
        }
    }
}
