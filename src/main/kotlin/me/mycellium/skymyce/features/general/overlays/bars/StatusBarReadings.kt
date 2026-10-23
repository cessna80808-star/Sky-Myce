package me.mycellium.skymyce.features.general.overlays.bars

import me.mycellium.skymyce.utils.NumberUtils
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/** Whole numbers with separators, as SkyBlock writes them. */
internal fun statusNumber(value: Int) = String.format(Locale.ROOT, "%,d", value)

/** Effective health: what it takes to bring [health] to zero through [defense]. */
internal fun effectiveHealth(health: Int, defense: Int) = health * (1 + defense.coerceAtLeast(0) / 100.0)

/**
 * What one bar shows: a value against a maximum, an optional second layer and the label. [fill] is the share of the
 * bar to fill; defense has no maximum, so its fill is the damage it removes.
 */
data class BarReading(val value: Double, val max: Double, val overflow: Double = 0.0, val label: String, val fill: Double = if (max > 0) value / max else 0.0,
                      /** The value alone, for bars too narrow for the full label. */ val short: String = label)

/**
 * Current health from the vanilla hearts. Hypixel keeps the hearts at the same share of full as SkyBlock health and
 * updates them on every hit and heal, while the action bar only refreshes about once a second. So the share of
 * hearts times the action bar's maximum is the faster reading. Without usable hearts the action bar value stands.
 */
internal fun estimateHealth(actionHealth: Int, actionMax: Int, heartsRatio: Double?): Int {
    if (actionMax <= 0) return actionHealth.coerceAtLeast(0)
    val ratio = heartsRatio?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return actionHealth.coerceIn(0, actionMax)
    return (ratio * actionMax).roundToInt().coerceIn(0, actionMax)
}

/**
 * Absorption from an action bar reading. SkyBlock shows health above the maximum while absorbing; with the hearts
 * below full, the excess is measured against the hearts' estimate instead of the maximum.
 */
internal fun absorptionFrom(actionHealth: Int, actionMax: Int, heartsRatio: Double?): Int {
    if (actionMax <= 0) return 0
    val base = heartsRatio?.takeIf { it.isFinite() && it in 0.0..0.999 }?.let { (it * actionMax).roundToInt() } ?: actionMax
    return (actionHealth - base).coerceAtLeast(0)
}

/** SkyBlock speed from the movement speed attribute: 0.1 is 100 speed; sprinting adds 30% that SkyBlock does not count. */
internal fun speedValue(movementSpeed: Double, sprinting: Boolean): Int =
    ((if (sprinting) movementSpeed / 1.3 else movementSpeed) * 1000).roundToInt().coerceAtLeast(0)

/** The usual cap is 400; items and pets that lift it allow 500. */
internal fun speedMax(speed: Int) = if (speed > 400) 500 else 400

/** Defense fills by the share of damage it removes: 100 defense removes half. */
internal fun defenseFill(defense: Int) = defense.coerceAtLeast(0) / (defense.coerceAtLeast(0) + 100.0)

private val pairPattern = Regex("([\\d,]+)\\s*/\\s*([\\d,]+)")
private val formattingCode = Regex("§.")

/** "x/y" pairs from an action bar fragment such as "1,200/1,500♨". */
internal fun parsePair(text: String): Pair<Int, Int>? {
    val match = pairPattern.find(text.replace(formattingCode, "")) ?: return null
    val a = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
    val b = match.groupValues[2].replace(",", "").toIntOrNull() ?: return null
    return a to b
}

/** How the bars write numbers (the Status Bars settings): with a "/max", the extra split off as "+extra", separators. */
data class BarFormat(val showMax: Boolean = false, val split: Boolean = false, val commas: Boolean = true) {
    fun number(value: Int): String = if (commas) statusNumber(value) else value.toString()

    /** The value alone (any extra added in), for bars too narrow for the full label. */
    fun short() = copy(showMax = false, split = false)

    /**
     * [value] with its [extra] (absorption, overflow mana) added in, or after it as "+extra" when [split]. With
     * [showMax] and a [max], "/max" too; unsplit, the maximum counts the extra as well ("4,800/4,800").
     */
    fun numbers(value: Int, max: Int?, extra: Int): String {
        val main = if (split) number(value) else number(value + extra)
        val withMax = if (showMax && max != null) "$main/${number(if (split) max else max + extra)}" else main
        return if (split && extra > 0) "$withMax +${number(extra)}" else withMax
    }
}

/** The label for a reading under [config]'s options and [format]; [health] feeds defense's effective-health option. */
internal fun barLabel(type: BarType, config: BarConfig, format: BarFormat, value: Int, max: Int, overflow: Int, health: Int? = null): String = when (type) {
    BarType.DEFENSE -> if (config.extra && health != null) "${format.number(value)} · ${NumberUtils.condense(effectiveHealth(health, value))} EHP" else format.number(value)
    BarType.RESPIRATION -> "${(value * 100 / max.coerceAtLeast(1))}%"
    else -> format.numbers(value, max.takeIf { type.hasMax }, if (config.showOverflow && type.hasOverflow) overflow else 0)
}

/**
 * One bar's motion, as shares of the bar. The fill follows the value on a critically damped spring (fast, no
 * overshoot). After a loss the old value stays as a pale [trail], holds briefly, then drains faster and faster; a big
 * loss also [flash]es the bar. A gain sweeps a bright band from the old fill end to the new one. The shown number
 * rolls to the new value, and the bar fades in and out when it appears or goes away. Without animation everything
 * is exact and immediate.
 */
class BarMotion {
    var shown = Double.NaN; private set
    var trail = Double.NaN; private set
    var number = Double.NaN; private set
    var alpha = Double.NaN; private set
    var flashAt = NEVER; private set
    var flashStrength = 0.0; private set
    var healFrom = 0.0; private set
    var healTo = 0.0; private set
    var healAt = NEVER; private set
    private var velocity = 0.0
    private var holdUntil = 0L
    private var drain = 0.0
    private var last = Long.MIN_VALUE

    fun update(target: Double, numberTarget: Double, visible: Boolean, now: Long, animate: Boolean) {
        val value = target.coerceIn(0.0, 1.0)
        val dt = if (last == Long.MIN_VALUE) 0L else (now - last).coerceIn(0, 250)
        last = now
        if (shown.isNaN() || !animate) {
            shown = value; trail = value; number = numberTarget; alpha = if (visible) 1.0 else 0.0; velocity = 0.0; flashAt = NEVER
            return
        }
        // Loss: keep the old fill as the trail (a second hit during the hold keeps the higher trail).
        if (value < shown - 1e-4) {
            val loss = (maxOf(trail, shown) - value)
            if (trail <= shown + 1e-4) trail = shown
            holdUntil = now + TRAIL_HOLD; drain = 0.0
            if (loss >= BIG_HIT) { flashAt = now; flashStrength = (loss * 4).coerceAtMost(1.0) }
        }
        if (value > shown + 2e-3 && (now - healAt > HEAL_SWEEP || value > healTo + 1e-3)) {
            healFrom = if (now - healAt > HEAL_SWEEP) shown else healFrom; healTo = value; healAt = now
        }
        // Critically damped spring, integrated in small steps.
        var remaining = dt.toDouble()
        while (remaining > 0) {
            val step = minOf(remaining, 8.0) / 1000.0
            val accel = -2 * OMEGA * velocity - OMEGA * OMEGA * (shown - value)
            velocity += accel * step; shown += velocity * step
            remaining -= 8.0
        }
        if (kotlin.math.abs(value - shown) < 5e-4 && kotlin.math.abs(velocity) < 0.01) { shown = value; velocity = 0.0 }
        shown = shown.coerceIn(0.0, 1.0)
        // The trail: held, then an accelerating drain down to the fill.
        if (value >= trail) trail = maxOf(value, shown)
        else if (now >= holdUntil) { drain = (drain + dt * DRAIN_ACCEL).coerceAtMost(DRAIN_MAX); trail = (trail - drain * dt).coerceAtLeast(shown) }
        // Rolling number and fade.
        number = if (number.isNaN()) numberTarget else numberTarget + (number - numberTarget) * exp(-dt / NUMBER_EASE)
        if (kotlin.math.abs(number - numberTarget) < 0.5) number = numberTarget
        val fade = dt / FADE
        alpha = if (alpha.isNaN()) (if (visible) 1.0 else 0.0) else if (visible) (alpha + fade).coerceAtMost(1.0) else (alpha - fade).coerceAtLeast(0.0)
    }

    /** Flash brightness now, 0 to 1. */
    fun flash(now: Long) = ((1 - (now - flashAt).toDouble() / FLASH).coerceIn(0.0, 1.0) * flashStrength).takeIf { now >= flashAt } ?: 0.0

    /** Heal sweep progress now, or null when none is running. */
    fun heal(now: Long) = ((now - healAt).toDouble() / HEAL_SWEEP).takeIf { now >= healAt && it in 0.0..1.0 }

    companion object {
        /** Long ago, without risking overflow in time differences. */
        const val NEVER = -1_000_000_000L
        const val OMEGA = 20.0
        const val TRAIL_HOLD = 380L
        /** Drain acceleration and top speed, in shares of the bar per ms² and per ms. */
        const val DRAIN_ACCEL = 0.000006
        const val DRAIN_MAX = 0.004
        const val BIG_HIT = 0.08
        const val FLASH = 200L
        const val HEAL_SWEEP = 450L
        const val NUMBER_EASE = 90.0
        const val FADE = 160.0
    }
}

/**
 * The low-health heartbeat: a strong beat and a softer second beat, faster as health drops. 0 to 1; zero above 30%.
 */
internal fun heartbeat(fraction: Double, now: Long): Double {
    if (fraction >= LOW_HEALTH || fraction <= 0) return 0.0
    val urgency = 1 - fraction / LOW_HEALTH
    val period = 1100 - 550 * urgency
    val t = (now % period.toLong()) / period
    fun pulse(at: Double, width: Double) = (1 - kotlin.math.abs(t - at) / width).coerceAtLeast(0.0)
    return maxOf(pulse(0.06, 0.08), pulse(0.26, 0.07) * 0.6)
}

internal const val LOW_HEALTH = 0.3
