package me.mycellium.skymyce.ui

enum class GuiMotion(private val title: String) {
    FULL("Full"), REDUCED("Reduced"), OFF("Off");

    override fun toString() = title
}

enum class GuiQuality(
    private val title: String,
    val rasterScale: Double,
    val shadowLayers: Int,
    val glow: Boolean,
    val ambient: Boolean,
    val ambientIntervalNanos: Long,
) {
    EXTREMELY_LIGHT("Extremely Light", 1.0, 0, false, false, 0),
    LIGHT("Light", 1.25, 1, false, false, 0),
    MEDIUM("Medium", 1.5, 2, true, true, 100_000_000),
    HIGH("High", 2.0, 3, true, true, 50_000_000);

    override fun toString() = title
}

enum class MotionEasing {
    LINEAR, SMOOTH, QUICK, SETTLE;

    fun apply(progress: Double): Double {
        val p = if (progress.isNaN()) 0.0 else progress.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> p
            SMOOTH -> p * p * (3.0 - 2.0 * p)
            QUICK -> 1.0 - (1.0 - p) * (1.0 - p) * (1.0 - p)
            SETTLE -> {
                val tail = p - 1.0
                1.0 + 1.85 * tail * tail * tail + .85 * tail * tail
            }
        }
    }
}

/** Fade an existing timed row entirely inside its logical lifetime; presentation never extends expiry. */
fun motionEnvelope(ageMillis: Double, lifetimeMillis: Double, mode: GuiMotion): Double {
    if (!ageMillis.isFinite() || !lifetimeMillis.isFinite() || lifetimeMillis <= 0.0) return 0.0
    val age = ageMillis.coerceAtLeast(0.0)
    if (age >= lifetimeMillis) return 0.0
    if (mode == GuiMotion.OFF) return 1.0
    val entrance = if (mode == GuiMotion.REDUCED) 90.0 else 180.0
    val exit = if (mode == GuiMotion.REDUCED) 70.0 else 140.0
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
            val weight = easing.apply(progress)
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
        durationNanos = (if (mode == GuiMotion.REDUCED) minOf(durationMillis, 90) else durationMillis).toDouble() * 1_000_000.0
        delayNanos = delayMillis.coerceAtLeast(0).toDouble() * 1_000_000.0
        this.easing = if (mode == GuiMotion.REDUCED && easing == MotionEasing.SETTLE) MotionEasing.QUICK else easing
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
