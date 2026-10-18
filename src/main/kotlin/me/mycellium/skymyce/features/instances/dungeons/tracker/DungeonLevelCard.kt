package me.mycellium.skymyce.features.instances.dungeons.tracker

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import java.util.Locale
import kotlin.math.ceil

/** Runs needed for [xp] at [xpPerRun], or null without a rate. */
private fun runsFor(xp: Double, xpPerRun: Double?) = xpPerRun?.takeIf { it > 0 && it.isFinite() }?.let { ceil(xp / it).toLong() }

private fun runsText(runs: Long?) = runs?.let { "~${String.format(Locale.ROOT, "%,d", it)} run${if (it == 1L) "" else "s"}" }

/**
 * The lines under the level card's bar: what the next level takes and, when the target is further away, what the
 * target takes. Runs are estimates from the tracked average and are omitted without one.
 */
internal fun levelCardFooter(row: LevelRow, xpPerRun: Double?): List<String> {
    if (row.remaining <= 0) return listOf("Cata ${row.target} reached")
    val next = listOfNotNull(runsText(runsFor(row.nextLevelRemaining, xpPerRun)), "${NumberUtils.condense(row.nextLevelRemaining)} XP").joinToString(" · ")
    val lines = mutableListOf("Next: $next")
    if (row.target > row.level + 1)
        lines += "Cata ${row.target}: " + listOfNotNull(runsText(row.runs), "${NumberUtils.condense(row.remaining)} XP").joinToString(" · ")
    return lines
}

internal fun levelCardPercent(row: LevelRow) = String.format(Locale.ROOT, "%.1f%%", row.nextLevelProgress * 100)

/**
 * "Cata level 52 › Target 53" with the next-level bar, drawn in the HUD palette. Big numerals carry the card; labels
 * stay small and muted so the level and the bar read at a glance mid-run.
 */
class DungeonLevelCard : BaseUIComponent() {
    private var row: LevelRow? = null
    private var footer = emptyList<String>()

    fun update(row: LevelRow, xpPerRun: Double?): DungeonLevelCard {
        this.row = row
        footer = levelCardFooter(row, xpPerRun)
        sizing(Sizing.fixed(WIDTH), Sizing.fixed(HEADER + footer.size * 10))
        return this
    }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val row = row ?: return
        val font = MC.instance.font
        val shadow = HudTheme.SHADOW
        val text = HudTheme.alpha(HudTheme.TEXT, 100)
        val muted = HudTheme.alpha(HudTheme.MUTED, 100)
        val accent = HudTheme.alpha(HudTheme.ACCENT, 100)
        val reached = row.remaining <= 0
        val left = x(); val right = x() + width(); val top = y()

        graphics.text(font, "Cata level", left, top, muted, shadow)
        graphics.text(font, "Target", right - font.width("Target"), top, muted, shadow)
        big(graphics, row.level.toString(), left, top + 11, text, shadow, align = 0)
        big(graphics, "›", (left + right) / 2, top + 11, accent, shadow, align = 1)
        big(graphics, row.target.toString(), right, top + 11, if (reached) HudTheme.alpha(HudTheme.GREEN, 100) else text, shadow, align = 2)

        val percent = levelCardPercent(row)
        graphics.text(font, "Next level", left, top + 32, muted, shadow)
        graphics.text(font, percent, right - font.width(percent), top + 32, text, shadow)

        // Track, fill, and a one-pixel highlight along the fill's top edge.
        val barTop = top + 43
        graphics.fill(left, barTop, right, barTop + BAR, HudTheme.alpha(HudTheme.blend(HudTheme.PANEL, HudTheme.TEXT, 16), 85))
        val filled = left + ((right - left) * row.nextLevelProgress).toInt().coerceIn(0, right - left)
        if (filled > left) {
            graphics.fill(left, barTop, filled, barTop + BAR, accent)
            graphics.fill(left, barTop, filled, barTop + 1, HudTheme.alpha(HudTheme.blend(HudTheme.ACCENT, 0xFFFFFF, 45), 100))
        }

        footer.forEachIndexed { index, line -> graphics.text(font, line, left, top + HEADER + index * 10, if (index == 0) text else muted, shadow) }
    }

    /** Text at twice the size; [align] 0 left, 1 centre, 2 right of [x]. */
    private fun big(graphics: OwoUIGraphics, value: String, x: Int, y: Int, color: Int, shadow: Boolean, align: Int) {
        val width = MC.instance.font.width(value) * 2
        val start = when (align) { 1 -> x - width / 2; 2 -> x - width; else -> x }
        graphics.pose().pushMatrix()
        graphics.pose().translate(start.toFloat(), y.toFloat())
        graphics.pose().scale(2f, 2f)
        graphics.text(MC.instance.font, value, 0, 0, color, shadow)
        graphics.pose().popMatrix()
    }

    companion object {
        const val WIDTH = 150
        private const val BAR = 6
        /** Labels, numerals, the next-level row and the bar, before the footer lines. */
        private const val HEADER = 54
    }
}
