package me.mycellium.skymyce.features.general.overlays.bars

import kotlin.math.abs
import kotlin.math.roundToInt

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
