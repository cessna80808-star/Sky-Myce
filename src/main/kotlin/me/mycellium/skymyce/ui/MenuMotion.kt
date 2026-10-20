package me.mycellium.skymyce.ui

enum class GuiMotion(private val title: String) {
    LIGHT("Light"), MEDIUM("Medium"), HEAVY("Heavy"),
    /** Kept as a saved-config alias; old Full configurations use the Medium personality. */
    FULL("Medium"), REDUCED("Reduced"), OFF("Off");

    override fun toString() = title

    val profile: MotionProfile get() = when (this) {
        LIGHT -> MotionProfile.LIGHT
        MEDIUM, FULL -> MotionProfile.MEDIUM
        HEAVY -> MotionProfile.HEAVY
        REDUCED, OFF -> MotionProfile.REDUCED
    }

    companion object {
        val choices = listOf(LIGHT, MEDIUM, HEAVY, REDUCED, OFF)
    }
}

/** Shared physical treatment; raster quality remains a separate rendering preference. */
data class MotionProfile(
    val cardLift: Double,
    val hoverScale: Double,
    val pressScale: Double,
    val shadowStrength: Double,
    val glowStrength: Double,
    val shellOffset: Double,
    val shellScale: Double,
    val pageOffset: Double,
    val pageScale: Double,
    val revealOffset: Double,
    val staggerMillis: Long,
    val hoverEasing: MotionEasing,
    val layoutMillis: Long,
    val durationScale: Double = 1.0,
    val ambient: Boolean = false,
    val settleStrength: Double = .85,
) {
    companion object {
        val LIGHT = MotionProfile(1.25, .0015, .012, .22, 0.0, 3.0, .008, 6.0, .003, 3.0, 8, MotionEasing.QUICK, 185, .68)
        val MEDIUM = MotionProfile(3.5, .0055, .025, .65, .55, 8.0, .018, 14.0, .008, 7.0, 18, MotionEasing.SETTLE, 260)
        val HEAVY = MotionProfile(6.0, .009, .035, 1.0, 1.0, 12.0, .026, 20.0, .013, 11.0, 28, MotionEasing.SETTLE, 290, ambient = true, settleStrength = 1.0)
        val REDUCED = MotionProfile(0.0, 0.0, 0.0, .15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, MotionEasing.QUICK, 90)
    }
}

enum class GuiQuality(
    private val title: String,
    val rasterScale: Double,
    val shadowLayers: Int,
    val glow: Boolean,
    val ambient: Boolean,
    val ambientIntervalNanos: Long,
) {
    /** Adapts between the tiers below from measured frame times (see [AutoGraphics]); renderers read [AutoGraphics.quality]. */
    AUTO("Auto (recommended)", 1.5, 2, true, true, 100_000_000),
    EXTREMELY_LIGHT("Extremely Light", 1.0, 0, false, false, 0),
    LIGHT("Light", 1.25, 1, false, false, 0),
    MEDIUM("Medium", 1.5, 2, true, true, 100_000_000),
    HIGH("High", 2.0, 3, true, true, 50_000_000);

    override fun toString() = title
}

enum class MotionEasing {
    LINEAR, SMOOTH, QUICK, SETTLE;

    fun apply(progress: Double, settleStrength: Double = .85): Double {
        val p = if (progress.isNaN()) 0.0 else progress.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> p
            SMOOTH -> p * p * (3.0 - 2.0 * p)
            QUICK -> 1.0 - (1.0 - p) * (1.0 - p) * (1.0 - p)
            SETTLE -> {
                val tail = p - 1.0
                val strength = settleStrength.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: .85
                1.0 + (1.0 + strength) * tail * tail * tail + strength * tail * tail
            }
        }
    }
}

/** Decorative detail follows a transition, then keeps its last pose without dirtying idle frames. */
internal fun menuAmbientStep(previous: Long, now: Long, interval: Long, changing: Boolean): Long =
    if (previous == Long.MIN_VALUE || changing) now / interval.coerceAtLeast(1) else previous

/** Fade an existing timed row entirely inside its logical lifetime; presentation never extends expiry. */
fun motionEnvelope(ageMillis: Double, lifetimeMillis: Double, mode: GuiMotion): Double {
    if (!ageMillis.isFinite() || !lifetimeMillis.isFinite() || lifetimeMillis <= 0.0) return 0.0
    val age = ageMillis.coerceAtLeast(0.0)
    if (age >= lifetimeMillis) return 0.0
    if (mode == GuiMotion.OFF) return 1.0
    val entrance = if (mode == GuiMotion.REDUCED) 90.0 else 180.0 * mode.profile.durationScale
    val exit = if (mode == GuiMotion.REDUCED) 70.0 else 140.0 * mode.profile.durationScale
    return minOf(MotionEasing.SMOOTH.apply(age / entrance), MotionEasing.SMOOTH.apply((lifetimeMillis - age) / exit))
}

/** A sampled timeline, owned by its control/screen. No timers or background work outlive the owner. */
class MotionValue(initial: Double, private val clock: () -> Long = System::nanoTime) {
    private var value = initial.takeIf(Double::isFinite) ?: 0.0
    private var from = value
    private var destination = value
    private var startedAt = clock()
    private var lastSampleAt = startedAt
    private var durationNanos = 0.0
    private var delayNanos = 0.0
    private var easing = MotionEasing.SMOOTH
    private var mode = GuiMotion.FULL
    private var active = false

    /** True until the next sample reaches the destination, or the owner cancels the timeline. */
    val running get() = active
    val target get() = destination

    fun sample(now: Long = clock()): Double {
        lastSampleAt = maxOf(lastSampleAt, now)
        if (!active) return value
        // Subtract as doubles so an unusually large timestamp jump cannot overflow a signed Long.
        val elapsed = lastSampleAt.toDouble() - startedAt.toDouble() - delayNanos
        if (elapsed <= 0.0) return from
        val progress = (elapsed / durationNanos).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            value = destination
            active = false
        } else {
            val weight = easing.apply(progress, mode.profile.settleStrength)
            // Weighted interpolation also handles opposite, very large finite endpoints safely.
            value = (from * (1.0 - weight) + destination * weight).takeIf(Double::isFinite) ?: destination
        }
        return value
    }

    fun animateTo(
        target: Double,
        durationMillis: Long = 180,
        easing: MotionEasing = MotionEasing.SMOOTH,
        mode: GuiMotion = GuiMotion.FULL,
        delayMillis: Long = 0,
        now: Long = clock(),
    ) {
        sample(now)
        if (!target.isFinite()) return
        if (mode == GuiMotion.OFF || durationMillis <= 0) {
            snap(target)
            this.mode = mode
            return
        }
        // Render callers may request the same state every frame; its original deadline must survive.
        if (target == destination && this.mode == mode && (active || value == target)) return
        from = value
        destination = target
        startedAt = lastSampleAt
        durationNanos = (if (mode == GuiMotion.REDUCED) minOf(durationMillis, 90).toDouble()
            else durationMillis * mode.profile.durationScale) * 1_000_000.0
        // Reduced motion keeps feedback immediate, including controls with a staggered entrance.
        delayNanos = (if (mode == GuiMotion.REDUCED) 0.0 else delayMillis.coerceAtLeast(0) * mode.profile.durationScale) * 1_000_000.0
        this.easing = if ((mode == GuiMotion.REDUCED || mode == GuiMotion.LIGHT) && easing == MotionEasing.SETTLE) MotionEasing.QUICK else easing
        this.mode = mode
        active = from != destination
    }

    fun snap(value: Double) {
        if (!value.isFinite()) return
        this.value = value
        from = value
        destination = value
        active = false
    }

    /** Preserve the displayed pose when an interaction or screen transition is abandoned. */
    fun cancel() = snap(sample())
}

/** Introduction belongs to the application, rather than to any rebuilt UI tree. */
internal class MenuIntroduction(private val readPlayed: () -> Boolean, private val persistPlayed: () -> Unit) {
    private var claimed = false
    private var completed = false

    fun claim(): Boolean {
        if (claimed || readPlayed()) return false
        claimed = true
        return true
    }

    fun complete() {
        if (!claimed || completed) return
        completed = true
        if (!readPlayed()) persistPlayed()
    }
}

/** Stable logical keys survive refresh, navigation and scrolling; unseen offscreen items stay inert. */
internal class MenuRevealState(private val maximum: Int = 4096, private val clock: () -> Long = System::nanoTime) {
    private val revealed = HashMap<String, MotionValue>()
    val size get() = revealed.size

    fun progress(key: String, visible: Boolean, mode: GuiMotion, delayMillis: Long = 0, now: Long = clock()): Double {
        var timeline = revealed[key]
        if (timeline == null) {
            if (!visible) return 0.0
            // ponytail: cap retained identities; extra items appear immediately instead of evicting/replaying old reveals.
            if (revealed.size >= maximum.coerceAtLeast(0)) return 1.0
            timeline = MotionValue(0.0, clock)
            revealed[key] = timeline
        }
        timeline.animateTo(1.0, 160, MotionEasing.QUICK, mode, delayMillis, now)
        return timeline.sample(now).coerceIn(0.0, 1.0)
    }
}

/** Wheel/keyboard motion shares one retargeting path; native scrollbar drags remain immediate. */
internal fun MotionValue.scrollTo(target: Double, mode: GuiMotion, instant: Boolean = false): Double {
    if (instant) snap(target) else animateTo(target, 145, MotionEasing.QUICK, mode)
    return sample()
}

/** A shorter retained list cannot leave its visible timeline past the new scroll boundary. */
internal fun MotionValue.clampScroll(maximum: Double): Double {
    val sampled = sample()
    val displayed = sampled.coerceIn(0.0, maximum.coerceAtLeast(0.0))
    if (displayed != sampled) snap(displayed)
    return displayed
}

/** Screen-space transform shared by paint and input; alpha does not alter pointer coordinates. */
data class MotionPose(
    val alpha: Double = 1.0,
    val scale: Double = 1.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val pivotX: Double = 0.0,
    val pivotY: Double = 0.0,
) {
    fun inverseX(pointerX: Double) = inverse(pointerX, x, pivotX)
    fun inverseY(pointerY: Double) = inverse(pointerY, y, pivotY)

    private fun inverse(pointer: Double, translation: Double, pivot: Double): Double {
        val point = pointer.takeIf(Double::isFinite) ?: 0.0
        val offset = translation.takeIf(Double::isFinite) ?: 0.0
        val origin = pivot.takeIf(Double::isFinite) ?: 0.0
        val factor = scale.takeIf { it.isFinite() && kotlin.math.abs(it) > 1e-9 } ?: 1.0
        return (origin + (point - origin - offset) / factor).takeIf(Double::isFinite) ?: point
    }
}
