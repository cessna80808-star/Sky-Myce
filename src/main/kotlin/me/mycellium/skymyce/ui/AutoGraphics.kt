package me.mycellium.skymyce.ui

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.ProgressScreen
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import java.util.Locale

/** How quickly Auto reacts: how long trouble must last before it steps down, and good frames before it steps up. */
enum class AutoResponse(private val title: String, val degradeMillis: Long, val restoreMillis: Long) {
    GENTLE("Gentle (keeps quality longest)", 10_000, 12_000),
    NORMAL("Normal", 5_000, 15_000),
    QUICK("Quick (protects frame rate)", 3_000, 25_000);

    override fun toString() = title
}

/**
 * The adaptive part of Auto graphics, kept free of Minecraft so it can be checked: rolling frame measurements in, one
 * of the fixed quality tiers out.
 *
 * Inputs are the client's frames-per-second (Minecraft's one-second count) and the last frame's duration, sampled every
 * client tick. A step down needs sustained trouble — average FPS under 90% of the target, or the 95th-percentile frame
 * time over 1.6× the target frame time — for the response's degrade time; a step up needs FPS over 115% of the target
 * and steady frames for its (longer) restore time. Changes are at least 8 s apart, move one tier at a time, and are
 * suspended while loading and for 5 s after. With no usable data the tier holds. The target never exceeds the frame
 * limit, so a capped game is not mistaken for a slow one.
 */
class AutoGraphicsController(private val tiers: List<GuiQuality> = AUTO_TIERS) {
    var tier = tiers.indexOf(GuiQuality.MEDIUM).coerceAtLeast(0)
        private set
    var reason = "Starting at ${tiers[tier]}"
        private set
    var lastChange = Long.MIN_VALUE / 2
        private set

    // Insertion-ordered rings replace boxed Pair deques; every query below scans them in the same order as before.
    private val fps = Samples()
    private val frames = Samples()
    private var scratch = DoubleArray(64)
    private var badSince: Long? = null
    private var goodSince: Long? = null
    private var pausedUntil = Long.MIN_VALUE / 2

    val quality get() = tiers[tier]

    /** A loading screen or world change: forget the measurements and wait. */
    fun pause(now: Long) { pausedUntil = now + SETTLE_MILLIS; fps.clear(); frames.clear(); badSince = null; goodSince = null }

    /** Growable ring of (time, value) pairs; values are stored as doubles, which represent every Int FPS exactly. */
    private class Samples {
        var times = LongArray(64); var values = DoubleArray(64)
        var head = 0; var size = 0
        fun clear() { head = 0; size = 0 }
        fun time(i: Int) = times[(head + i) % times.size]
        fun value(i: Int) = values[(head + i) % times.size]
        fun add(time: Long, value: Double) {
            if (size == times.size) {
                val t = LongArray(size * 2); val v = DoubleArray(size * 2)
                for (i in 0 until size) { t[i] = time(i); v[i] = value(i) }
                times = t; values = v; head = 0
            }
            val slot = (head + size) % times.size
            times[slot] = time; values[slot] = value; size++
        }
        fun removeFirst() { head = (head + 1) % times.size; size-- }
        /** Same summation order as `filter { time >= since }.map { value }.average()`, including NaN when nothing matches. */
        fun averageSince(since: Long): Double {
            var sum = 0.0; var count = 0
            for (i in 0 until size) if (time(i) >= since) { sum += value(i); count++ }
            return if (count == 0) Double.NaN else sum / count
        }
    }

    /** `percentile(values since [since])` without boxing: copies into a reused array and sorts with Double.compare order. */
    private fun percentileSince(samples: Samples, since: Long, p: Double): Double {
        if (scratch.size < samples.size) scratch = DoubleArray(samples.times.size)
        var n = 0
        for (i in 0 until samples.size) if (samples.time(i) >= since) scratch[n++] = samples.value(i)
        if (n == 0) return 0.0
        java.util.Arrays.sort(scratch, 0, n)
        return scratch[((n - 1) * p).toInt().coerceIn(0, n - 1)]
    }

    /**
     * One measurement. [currentFps] is the last second's frame count; [frameMillis] the last frame's duration.
     * [targetFps] is the user's target already limited by the frame cap; [lowest] the lowest tier Auto may use.
     */
    fun sample(now: Long, currentFps: Int, frameMillis: Double, targetFps: Int, response: AutoResponse, lowest: GuiQuality = tiers.first()): GuiQuality {
        if (now < pausedUntil) return quality
        if (currentFps > 0) fps.add(now, currentFps.toDouble())
        if (frameMillis.isFinite() && frameMillis > 0 && frameMillis < 2_000) frames.add(now, frameMillis)
        val window = maxOf(response.degradeMillis, response.restoreMillis)
        while (fps.size > 0 && fps.time(0) < now - window) fps.removeFirst()
        while (frames.size > 0 && frames.time(0) < now - window) frames.removeFirst()
        if (targetFps <= 0 || fps.size < 2 || frames.size < 10) return quality

        val floor = tiers.indexOf(lowest).coerceAtLeast(0)
        val targetMillis = 1000.0 / targetFps
        val recentFps = fps.averageSince(now - response.degradeMillis)
        val p95 = percentileSince(frames, now - response.degradeMillis, 0.95)
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
        val f = fps.averageSince(now - 5_000)
        return (if (f.isNaN()) 0.0 else f) to percentileSince(frames, now - 5_000, 0.95)
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

/**
 * Feeds [AutoGraphicsController] once per client tick (20 Hz, two numbers read from the client) and exposes the
 * effective quality every renderer reads. Only Sky-Myce's own effects change; Minecraft's options are never touched.
 */
object AutoGraphics : SkyMyceModule() {
    val controller = AutoGraphicsController()

    /** The quality to draw with now: the user's fixed choice, or Auto's current tier. */
    val quality: GuiQuality
        get() = ThemeConfig.graphicsQuality.let { if (it == GuiQuality.AUTO) controller.quality else it }

    @Subscription(TickEvent::class)
    fun onTick() = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.AUTO_GRAPHICS) { sampleQuality() }

    private fun sampleQuality() {
        if (ThemeConfig.graphicsQuality != GuiQuality.AUTO) return
        val now = System.currentTimeMillis()
        val screen = MC.screen
        if (MC.instance.level == null || screen is LevelLoadingScreen || screen is ProgressScreen) { controller.pause(now); return }
        controller.sample(now, MC.instance.fps, MC.instance.frameTimeNs / 1_000_000.0, target(), ThemeConfig.autoResponse, ThemeConfig.autoLowest)
    }

    @Subscription(ServerDisconnectEvent::class)
    fun onDisconnect() = controller.pause(System.currentTimeMillis())

    /** The user's target, but never above the frame limit (a capped game is not a slow one). */
    fun target(): Int {
        val limit = MC.instance.options.framerateLimit().get()
        val capped = if (limit in 1 until 260) (limit * 0.95).toInt() else Int.MAX_VALUE
        return minOf(ThemeConfig.autoTargetFps, capped).coerceAtLeast(20)
    }

    /** `/sm graphics`: mode, effective quality, recent measurements and the last change. */
    fun status(): List<String> {
        val mode = ThemeConfig.graphicsQuality
        if (mode != GuiQuality.AUTO) return listOf("§bGraphics: §f$mode §7(fixed). Choose Auto in Settings → Performance to adapt.")
        val (fps, p95) = controller.summary(System.currentTimeMillis())
        return listOf("§bGraphics: §fAuto §7→ now §f${controller.quality}",
            String.format(Locale.ROOT, "§7Recent: %.0f FPS, 95%% of frames under %.1f ms; target %d FPS", fps, p95, target()),
            "§7${controller.reason}")
    }
}
