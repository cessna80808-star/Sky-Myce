package me.mycellium.skymyce.features.general.overlays.bars

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/** Where a bar's icon sits, if anywhere. */
enum class IconPosition(val title: String) { LEFT("Left"), RIGHT("Right"), OFF("Off") }

/** Where a bar's value sits on the bar, if anywhere. */
enum class TextPosition(val title: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right"), OFF("Off") }

/**
 * The bars. [sprite] names a vanilla HUD sprite (drawn as is) or, for stats without one, a SkyBlock [glyph] is drawn
 * (experience draws a vanilla orb instead).
 * [hasMax] bars have a meaningful maximum; [hasOverflow] bars show a second layer (absorption, overflow mana).
 * [extra] names the type's own extra option, if it has one.
 */
enum class BarType(
    val id: String, val title: String, val glyph: String, val sprite: String?,
    val defaultAnchor: BarAnchor, val defaultRow: Int,
    val fill: Int, val overflow: Int, val text: Int,
    val hasMax: Boolean, val hasOverflow: Boolean, val extra: String? = null,
) {
    HEALTH("health", "Health", "❤", "hud/heart/full", BarAnchor.HOTBAR_TOP, 0, 0xE5302C, 0xF5C42C, 0xFF5555, true, true),
    MANA("mana", "Mana", "✎", null, BarAnchor.HOTBAR_TOP, 0, 0x2EC4EE, 0xA24BF2, 0x55FFFF, true, true),
    DEFENSE("defense", "Defense", "❈", "hud/armor_full", BarAnchor.HOTBAR_RIGHT, 0, 0x45C25F, 0, 0x55FF55, false, false, "Show effective health"),
    SPEED("speed", "Speed", "✦", null, BarAnchor.HOTBAR_RIGHT, 1, 0xE6E6E6, 0, 0xFFFFFF, true, false),
    VITALITY("vitality", "Vitality", "♨", null, BarAnchor.HOTBAR_LEFT, 0, 0xB8322C, 0, 0xE0504A, true, false),
    RESPIRATION("respiration", "Respiration", "◌", "hud/air", BarAnchor.HOTBAR_LEFT, 1, 0x4AB4E6, 0, 0x6FD8EC, true, false),
    /** The vanilla experience level over the progress to the next one. */
    EXPERIENCE("experience", "Experience", "●", null, BarAnchor.HOTBAR_TOP, 1, 0x6BD62B, 0, 0x80FF20, false, false);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/**
 * How bars on an anchor are sized. Target rows split [totalWidth] between their bars in proportion to their size
 * units, which always add up to [target]. Free rows give each bar [unitWidth] per unit.
 */
data class SizeRule(val target: Int = 0, val totalWidth: Int = 0, val unitWidth: Int = 0, val min: Int, val max: Int) {
    val isTarget get() = target > 0
}

/**
 * Points bars attach to. Rows stack away from the anchor ([up]: towards the top of the screen) and bars in a row
 * line up away from it ([right]: towards the right). Positions are in GUI pixels.
 */
enum class BarAnchor(val id: String, val title: String, val up: Boolean, val right: Boolean, val rule: SizeRule, private val at: (Int, Int) -> Pair<Int, Int>) {
    HOTBAR_TOP("hotbar_top", "Above the hotbar", true, true, SizeRule(target = 12, totalWidth = 182, min = 2, max = 12), { w, h -> w / 2 - 91 to h - 35 }),
    HOTBAR_LEFT("hotbar_left", "Left of the hotbar", true, false, SizeRule(unitWidth = 25, min = 2, max = 6), { w, h -> w / 2 - 91 - 4 to h - 3 }),
    HOTBAR_RIGHT("hotbar_right", "Right of the hotbar", true, true, SizeRule(unitWidth = 25, min = 2, max = 6), { w, h -> w / 2 + 91 + 4 to h - 3 }),
    TOP_LEFT("top_left", "Top left", false, true, SizeRule(unitWidth = 25, min = 2, max = 8), { _, _ -> 5 to 5 }),
    TOP_RIGHT("top_right", "Top right", false, false, SizeRule(unitWidth = 25, min = 2, max = 8), { w, _ -> w - 5 to 5 }),
    BOTTOM_LEFT("bottom_left", "Bottom left", true, true, SizeRule(unitWidth = 25, min = 2, max = 8), { _, h -> 5 to h - 5 }),
    BOTTOM_RIGHT("bottom_right", "Bottom right", true, false, SizeRule(unitWidth = 25, min = 2, max = 8), { w, h -> w - 5 to h - 5 }),
    CROSSHAIR_LEFT("crosshair_left", "Left of the crosshair", false, false, SizeRule(unitWidth = 15, min = 2, max = 8), { w, h -> w / 2 - 10 to h / 2 - 4 }),
    CROSSHAIR_RIGHT("crosshair_right", "Right of the crosshair", false, true, SizeRule(unitWidth = 15, min = 2, max = 8), { w, h -> w / 2 + 10 to h / 2 - 4 });

    fun position(width: Int, height: Int) = at(width, height)

    /** Where a bar can be dropped while the anchor is empty. */
    fun hitbox(width: Int, height: Int): BarRect {
        val (x, y) = position(width, height)
        if (rule.isTarget) return BarRect(x, y - 20, rule.totalWidth, 20)
        return BarRect(if (right) x else x - 20, if (up) y - 20 else y, 20, 20)
    }

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

data class BarRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
    fun expand(by: Int) = BarRect(x - by, y - by, width + by * 2, height + by * 2)
}

/**
 * One bar's placement and look, as saved. With an [anchor] the bar sits in [row] (0 nearest the anchor) at [slot]
 * (0 nearest the anchor) and is [size] units wide. Without one it floats at ([x], [y]) as fractions of the screen,
 * [width] GUI pixels wide. Colours are RGB.
 */
data class BarConfig(
    val type: String,
    val enabled: Boolean = true,
    val anchor: String? = null,
    val row: Int = 0,
    val slot: Int = 0,
    val size: Int = 6,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val width: Int = 100,
    val icon: IconPosition = IconPosition.LEFT,
    val text: TextPosition = TextPosition.CENTER,
    val showOverflow: Boolean = true,
    val extra: Boolean = false,
    val fill: Int = 0,
    val overflow: Int = 0,
    val textColor: Int = 0,
) {
    val barType get() = BarType.of(type)
    val barAnchor get() = BarAnchor.of(anchor)
}

/** Every bar's configuration. [version] guards the saved format. */
data class StatusBarLayout(val version: Int = VERSION, val bars: List<BarConfig> = emptyList()) {
    fun bar(type: BarType) = bars.first { it.type == type.id }
    fun update(type: BarType, change: (BarConfig) -> BarConfig) = copy(bars = bars.map { if (it.type == type.id) change(it) else it }).normalized()

    /**
     * A valid layout: one entry per bar type, known enums, rows and slots renumbered from 0 without gaps, and target
     * rows whose sizes add up to the target. Anything unreadable falls back to the bar's default.
     */
    fun normalized(): StatusBarLayout {
        val byType = BarType.entries.associateWith { type -> bars.firstOrNull { it.type == type.id } ?: defaultBar(type) }
        val fixed = byType.mapValues { (type, bar) ->
            val clean = bar.copy(
                anchor = bar.barAnchor?.id,
                width = bar.width.coerceIn(MIN_FREE_WIDTH, 600),
                x = bar.x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                y = bar.y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                fill = bar.fill.takeIf { it != 0 } ?: type.fill,
                overflow = bar.overflow.takeIf { it != 0 } ?: type.overflow,
                textColor = bar.textColor.takeIf { it != 0 } ?: type.text,
            )
            clean
        }.toMutableMap()
        for (anchor in BarAnchor.entries) {
            val placed = fixed.values.filter { it.enabled && it.anchor == anchor.id }
            val rows = placed.groupBy { it.row }.toSortedMap().values.toList()
            rows.forEachIndexed { rowIndex, rowBars ->
                val ordered = rowBars.sortedBy { it.slot }
                val sizes = if (anchor.rule.isTarget) distribute(ordered.map { it.size }, anchor.rule) else ordered.map { it.size.coerceIn(anchor.rule.min, anchor.rule.max) }
                ordered.forEachIndexed { slot, bar ->
                    fixed[BarType.of(bar.type)!!] = bar.copy(row = rowIndex, slot = slot, size = sizes[slot])
                }
            }
        }
        return StatusBarLayout(VERSION, BarType.entries.map { fixed.getValue(it) })
    }

    companion object {
        /** 2: the Skyblocker-style look with SkyBlock stat colours (version 1 colours are reset to the new defaults). */
        const val VERSION = 2
        const val MIN_FREE_WIDTH = 30

        fun defaultBar(type: BarType) = BarConfig(
            type = type.id, anchor = type.defaultAnchor.id, row = type.defaultRow, slot = 0,
            size = if (type.defaultAnchor.rule.isTarget) 6 else 3,
            icon = IconPosition.LEFT, text = TextPosition.CENTER,
            showOverflow = type.hasOverflow,
            extra = false, fill = type.fill, overflow = type.overflow, textColor = type.text,
        )

        /** Health and mana share the row above the hotbar with experience above them; defense and speed sit right of it, vitality and air left. */
        fun default() = StatusBarLayout(VERSION, BarType.entries.map { type ->
            defaultBar(type).let { if (type == BarType.MANA) it.copy(slot = 1) else it }
        }).normalized()
    }
}

/** Sizes for one target row: proportional to the requested sizes, each at least the minimum, summing to the target. */
internal fun distribute(requested: List<Int>, rule: SizeRule): List<Int> {
    if (requested.isEmpty()) return emptyList()
    val n = requested.size
    val minimum = rule.min.coerceAtMost(rule.target / n).coerceAtLeast(1)
    val wanted = requested.map { it.coerceAtLeast(1).toDouble() }
    val total = wanted.sum()
    val sizes = wanted.map { (it / total * rule.target).roundToInt().coerceAtLeast(minimum) }.toMutableList()
    // Fix rounding: take from (or give to) the largest bars until the row adds up.
    while (sizes.sum() > rule.target) { val i = sizes.indices.filter { sizes[it] > minimum }.maxByOrNull { sizes[it] } ?: break; sizes[i]-- }
    while (sizes.sum() < rule.target) { val i = sizes.indices.minByOrNull { sizes[it] }!!; sizes[i]++ }
    return sizes
}

const val BAR_HEIGHT = 9
const val BAR_GAP = 2

/** Every enabled bar's rectangle on a [width]×[height] GUI. Floating bars stay on screen. */
fun layoutRects(layout: StatusBarLayout, width: Int, height: Int, include: (BarConfig) -> Boolean = { it.enabled }): Map<BarType, BarRect> {
    val rects = LinkedHashMap<BarType, BarRect>()
    for (anchor in BarAnchor.entries) {
        val (ax, ay) = anchor.position(width, height)
        val rows = layout.bars.filter { include(it) && it.anchor == anchor.id }.groupBy { it.row }.toSortedMap()
        for ((row, bars) in rows) {
            val y = if (anchor.up) ay - (row + 1) * BAR_HEIGHT - row * BAR_GAP else ay + row * (BAR_HEIGHT + BAR_GAP)
            val ordered = bars.sortedBy { it.slot }
            val widths = if (anchor.rule.isTarget) {
                val available = anchor.rule.totalWidth - BAR_GAP * (ordered.size - 1)
                val sum = ordered.sumOf { it.size }.coerceAtLeast(1)
                var used = 0
                ordered.mapIndexed { i, bar -> if (i == ordered.lastIndex) available - used else (available * bar.size / sum).also { used += it } }
            } else ordered.map { it.size * anchor.rule.unitWidth }
            var cursor = ax
            ordered.forEachIndexed { i, bar ->
                val w = widths[i]
                val x = if (anchor.right) cursor.also { cursor += w + BAR_GAP } else (cursor - w).also { cursor -= w + BAR_GAP }
                rects[bar.barType!!] = BarRect(x, y, w, BAR_HEIGHT)
            }
        }
    }
    for (bar in layout.bars.filter { include(it) && it.anchor == null }) {
        val w = bar.width.coerceIn(StatusBarLayout.MIN_FREE_WIDTH, width.coerceAtLeast(StatusBarLayout.MIN_FREE_WIDTH))
        val x = (bar.x * width).roundToInt().coerceIn(0, (width - w).coerceAtLeast(0))
        val y = (bar.y * height).roundToInt().coerceIn(0, (height - BAR_HEIGHT).coerceAtLeast(0))
        rects[bar.barType!!] = BarRect(x, y, w, BAR_HEIGHT)
    }
    return rects
}

// ---- Editing ----

/** Takes [type] off its anchor (it floats until dropped). */
fun StatusBarLayout.detach(type: BarType): StatusBarLayout = update(type) { it.copy(anchor = null, enabled = true) }

/**
 * Puts [type] on [anchor]: into an existing [row] at [slot], or, with [newRow], into a new row inserted at [row].
 * A bar joining a target row starts with an equal share.
 */
fun StatusBarLayout.insert(type: BarType, anchor: BarAnchor, row: Int, slot: Int, newRow: Boolean): StatusBarLayout {
    val others = bars.filter { it.type != type.id }
    val shifted = others.map { bar ->
        when {
            bar.anchor != anchor.id || !bar.enabled -> bar
            newRow && bar.row >= row -> bar.copy(row = bar.row + 1)
            !newRow && bar.row == row && bar.slot >= slot -> bar.copy(slot = bar.slot + 1)
            else -> bar
        }
    }
    val rowCount = if (newRow) 0 else shifted.count { it.enabled && it.anchor == anchor.id && it.row == row }
    val size = if (anchor.rule.isTarget) (anchor.rule.target / (rowCount + 1)).coerceAtLeast(anchor.rule.min)
        else bar(type).size.coerceIn(anchor.rule.min, anchor.rule.max).takeIf { bar(type).anchor != null } ?: 3
    val moved = bar(type).copy(enabled = true, anchor = anchor.id, row = row, slot = if (newRow) 0 else slot, size = size)
    return StatusBarLayout(version, shifted + moved).normalized()
}

/** Floats [type] at the GUI position ([x], [y]) on a [width]×[height] screen, [barWidth] pixels wide. */
fun StatusBarLayout.float(type: BarType, x: Int, y: Int, barWidth: Int, width: Int, height: Int): StatusBarLayout = update(type) {
    it.copy(enabled = true, anchor = null, x = x.toFloat() / width.coerceAtLeast(1), y = y.toFloat() / height.coerceAtLeast(1),
        width = barWidth.coerceAtLeast(StatusBarLayout.MIN_FREE_WIDTH))
}

/**
 * Moves the boundary between two neighbours in a target row by [delta] units (positive grows [left]). A free row
 * bar grows alone. Nothing moves past the size limits.
 */
fun StatusBarLayout.resize(left: BarType?, right: BarType?, delta: Int): StatusBarLayout {
    if (delta == 0) return this
    val l = left?.let(::bar); val r = right?.let(::bar)
    val anchor = (l ?: r)?.barAnchor ?: return this
    val rule = anchor.rule
    if (l != null && r != null) {
        val newL = l.size + delta; val newR = r.size - delta
        if (newL !in rule.min..rule.max || newR !in rule.min..rule.max) return this
        return copy(bars = bars.map { when (it.type) { l.type -> it.copy(size = newL); r.type -> it.copy(size = newR); else -> it } }).normalized()
    }
    if (rule.isTarget) return this
    val one = l ?: r!!
    val grow = if (l != null) delta else -delta
    return update(one.barType!!) { it.copy(size = (it.size + grow).coerceIn(rule.min, rule.max)) }
}

fun StatusBarLayout.hide(type: BarType) = update(type) { it.copy(enabled = false) }

/** Shows a hidden bar again: back on its default anchor, in a new outermost row. */
fun StatusBarLayout.show(type: BarType): StatusBarLayout {
    val anchor = type.defaultAnchor
    val rows = bars.filter { it.enabled && it.anchor == anchor.id }.maxOfOrNull { it.row + 1 } ?: 0
    return update(type) { it.copy(enabled = true) }.insert(type, anchor, rows, 0, newRow = true)
}

// ---- Snapping for floating bars ----

/** Where a dragged floating bar lands, and the guide lines it snapped to (GUI x and y). */
data class SnapResult(val x: Int, val y: Int, val guidesX: List<Int>, val guidesY: List<Int>)

/**
 * Snaps a dragged bar ([rect] at the pointer) to nearby edges: other bars' left and right edges, their tops and
 * bottoms (including stacking just above or below with the usual gap), the screen centre and the screen edges.
 * Edges within [threshold] pixels snap; the closest wins per axis.
 */
fun snapFloating(rect: BarRect, others: Collection<BarRect>, width: Int, height: Int, threshold: Int = 4): SnapResult {
    val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
    for (o in others) { xs += o.x; xs += o.right; ys += o.y; ys += o.bottom; ys += o.y - BAR_GAP; ys += o.bottom + BAR_GAP }
    xs += width / 2; xs += 5; xs += width - 5; xs += width / 2 - 91; xs += width / 2 + 91
    ys += height / 2; ys += 5; ys += height - 5
    // Candidates: move the bar so one of its edges (or its centre, for x) meets a line.
    fun best(start: Int, size: Int, lines: List<Int>, centre: Boolean): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null; var distance = threshold + 1
        for (line in lines) {
            // Only the screen centre also centres the bar on it; other lines align an edge.
            val options = buildList { add(line to line); add(line - size to line); if (centre && line == width / 2) add(line - size / 2 to line) }
            for ((position, guide) in options) {
                val d = abs(position - start)
                if (d < distance) { distance = d; result = position to guide }
            }
        }
        return result
    }
    val bx = best(rect.x, rect.width, xs, centre = true)
    val by = best(rect.y, rect.height, ys, centre = false)
    return SnapResult(bx?.first ?: rect.x, by?.first ?: rect.y, listOfNotNull(bx?.second), listOfNotNull(by?.second))
}

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

/**
 * Draws one bar into its rectangle, in the shape SkyBlock players know from Skyblocker's bars: a 9-pixel icon, a
 * 7-pixel rounded dark track and a 5-pixel rounded fill with a light top edge, an overflow layer drawn over the fill
 * from the left, and the value in the stat's own colour sitting across the top edge with a crisp black outline.
 * Motion (trail, heal sweep, hit flash, low-health beat) is layered inside the same shape. Shared by HUD and editor.
 */
object StatusBarRenderer {
    private val sprites = HashMap<String, Identifier>()
    private const val ICON = 9
    private val ORB = Identifier.withDefaultNamespace("textures/entity/experience/experience_orb.png")

    fun draw(graphics: GuiGraphicsExtractor, type: BarType, config: BarConfig, rect: BarRect, reading: BarReading, motion: BarMotion,
             now: Long, animate: Boolean, opacity: Double = 1.0) {
        val alpha = (opacity * (motion.alpha.takeIf { !it.isNaN() } ?: 1.0)).coerceIn(0.0, 1.0)
        if (alpha <= 0.01) return
        val iconSpace = if (config.icon == IconPosition.OFF) 0 else ICON + 1
        val barLeft = rect.x + if (config.icon == IconPosition.LEFT) iconSpace else 0
        val barRight = rect.right - if (config.icon == IconPosition.RIGHT) iconSpace else 0
        val barWidth = barRight - barLeft
        if (barWidth < 4) return
        // Hits and heals are health's language; on mana and the rest they turn steady spending into constant flicker.
        val impacts = animate && type == BarType.HEALTH
        val flash = if (impacts) motion.flash(now) else 0.0
        // A big hit nudges the bar a pixel for a moment.
        val shake = if (animate && flash > 0.4) (if ((now / 30) % 2 == 0L) 1 else -1) else 0
        val top = rect.y + 1 // The 7-pixel body.
        val x = barLeft + shake

        if (iconSpace > 0) icon(graphics, type, config, if (config.icon == IconPosition.LEFT) rect.x else barRight + 1, rect.y, alpha)

        val fraction = reading.fill.coerceIn(0.0, 1.0)
        val beat = if (type == BarType.HEALTH) (if (animate) heartbeat(fraction, now) else if (fraction in 0.0001..LOW_HEALTH) 0.6 else 0.0) else 0.0

        // Body: a near-black rounded frame (corner pixels left out) around a dark track tinted by the fill.
        val frame = if (beat > 0) blend(0x0E0E10, 0xC81E28, beat) else 0x0E0E10
        pill(graphics, x, top, barWidth, 7, argb(frame, alpha * 0.92))
        val inner = x + 1; val innerWidth = barWidth - 2
        graphics.fill(inner, top + 1, inner + innerWidth, top + 6, argb(dark(config.fill, 0.28), alpha * 0.95))

        val shown = (motion.shown.takeIf { !it.isNaN() } ?: fraction).coerceIn(0.0, 1.0)
        val trail = (motion.trail.takeIf { !it.isNaN() } ?: shown).coerceIn(0.0, 1.0)
        val fillPx = innerWidth * shown
        val trailPx = (innerWidth * trail).roundToInt()
        if (trailPx > fillPx.toInt() + 1) fillPill(graphics, inner, top + 1, trailPx, light(config.fill, 0.62), alpha * 0.85, highlight = false)

        val color = if (beat > 0) light(config.fill, beat * 0.2) else config.fill
        val whole = fillPx.toInt()
        fillPill(graphics, inner, top + 1, whole, color, alpha, highlight = true)
        // The fractional end column, so the edge glides instead of stepping.
        val part = fillPx - whole
        if (part > 0.05 && whole in 2 until innerWidth) graphics.fill(inner + whole, top + 2, inner + whole + 1, top + 5, argb(color, alpha * part))

        // Overflow (absorption, overflow mana) over the fill from the left, with a slow shimmer.
        if (config.showOverflow && type.hasOverflow && reading.overflow > 0 && reading.max > 0) {
            val end = (innerWidth * (reading.overflow / reading.max).coerceIn(0.0, 1.0)).roundToInt()
            fillPill(graphics, inner, top + 1, end, config.overflow, alpha, highlight = true)
            if (animate && end > 6) {
                val head = inner + ((now / 14) % (end + 10)).toInt() - 5
                for (i in 0 until 3) { val sx = head + i; if (sx in inner + 1 until inner + end - 1) graphics.fill(sx, top + 2, sx + 1, top + 5, argb(0xFFFFFF, alpha * (0.32 - i * 0.09))) }
            }
        }
        // A heal sweeps a bright band from the old end to the new one.
        motion.heal(now)?.takeIf { impacts }?.let { progress ->
            val from = innerWidth * motion.healFrom; val to = innerWidth * motion.healTo
            val head = (from + (to - from) * easeOut(progress)).roundToInt()
            for (i in 0 until 5) {
                val sx = inner + head - i
                if (sx >= inner + from.toInt() && sx in inner + 1 until inner + innerWidth - 1) graphics.fill(sx, top + 1, sx + 1, top + 6, argb(0xFFFFFF, alpha * (1 - progress) * (0.55 - i * 0.1)))
            }
        }
        if (flash > 0 && whole > 2) fillPill(graphics, inner, top + 1, maxOf(whole, trailPx), 0xFFFFFF, alpha * flash * 0.5, highlight = false)

        if (config.text != TextPosition.OFF && reading.label.isNotEmpty()) label(graphics, config, reading, rect, barLeft, barWidth, alpha)
    }

    /** The value straddling the top edge, in the stat's colour; any "+overflow" part in the overflow colour. */
    private fun label(graphics: GuiGraphicsExtractor, config: BarConfig, reading: BarReading, rect: BarRect, barLeft: Int, barWidth: Int, alpha: Double) {
        val font = MC.instance.font
        // The full label if it fits over the whole bar (icon included), else the value alone.
        val label = if (font.width(reading.label) <= rect.width) reading.label else reading.short
        val split = label.lastIndexOf(" +")
        val main = if (split > 0) label.substring(0, split) else label
        val extra = if (split > 0) label.substring(split) else ""
        val width = font.width(label)
        val x = when (config.text) {
            TextPosition.LEFT -> barLeft + 1
            TextPosition.RIGHT -> barLeft + barWidth - width - 1
            else -> barLeft + (barWidth - width) / 2
        }.coerceIn(rect.x - 2, rect.right - width + 2)
        val y = rect.y - 3
        outlined(graphics, main, x, y, config.textColor, alpha)
        if (extra.isNotEmpty()) outlined(graphics, extra, x + font.width(main), y, config.overflow, alpha)
    }

    private fun icon(graphics: GuiGraphicsExtractor, type: BarType, config: BarConfig, x: Int, y: Int, alpha: Double) {
        val sprite = type.sprite
        // The smallest vanilla orb (8×8 at 4,4 of the 64×64 sheet), greyscale and tinted the way the game tints it.
        if (type == BarType.EXPERIENCE) graphics.blit(RenderPipelines.GUI_TEXTURED, ORB, x, y, 4f, 4f, 8, 8, 64, 64, argb(config.textColor, alpha))
        else if (sprite != null) {
            val id = sprites.getOrPut(sprite) { Identifier.withDefaultNamespace(sprite) }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, ICON, ICON, argb(0xFFFFFF, alpha))
        } else {
            val font = MC.instance.font
            outlined(graphics, type.glyph, x + (ICON - font.width(type.glyph)) / 2 + 1, y + 1, config.textColor, alpha)
        }
    }

    /** A filled rectangle with its four corner pixels left out. */
    private fun pill(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        if (w < 3 || h < 3) { graphics.fill(x, y, x + w, y + h, color); return }
        graphics.fill(x + 1, y, x + w - 1, y + 1, color)
        graphics.fill(x, y + 1, x + w, y + h - 1, color)
        graphics.fill(x + 1, y + h - 1, x + w - 1, y + h, color)
    }

    /** A 5-pixel rounded fill; with [highlight], a lighter top row and a darker bottom row. */
    private fun fillPill(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, rgb: Int, alpha: Double, highlight: Boolean) {
        if (w <= 0) return
        pill(graphics, x, y, w, 5, argb(rgb, alpha))
        if (highlight && w >= 3) {
            graphics.fill(x + 1, y, x + w - 1, y + 1, argb(light(rgb, 0.38), alpha))
            graphics.fill(x + 1, y + 4, x + w - 1, y + 5, argb(dark(rgb, 0.72), alpha))
        }
    }

    /** Text with a one-pixel black outline on all eight sides, like a glowing sign's. */
    private fun outlined(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int, alpha: Double) {
        val font = MC.instance.font
        val outline = argb(0x000000, alpha)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) graphics.text(font, text, x + dx, y + dy, outline, false)
        graphics.text(font, text, x, y, argb(color, alpha), false)
    }

    private fun easeOut(t: Double) = 1 - (1 - t) * (1 - t) * (1 - t)

    internal fun argb(rgb: Int, alpha: Double) = ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
    internal fun blend(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = (((a shr shift) and 255) + (((b shr shift) and 255) - ((a shr shift) and 255)) * t.coerceIn(0.0, 1.0)).roundToInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    internal fun light(rgb: Int, t: Double) = blend(rgb, 0xFFFFFF, t)
    internal fun dark(rgb: Int, t: Double) = blend(0x000000, rgb, t)
}
