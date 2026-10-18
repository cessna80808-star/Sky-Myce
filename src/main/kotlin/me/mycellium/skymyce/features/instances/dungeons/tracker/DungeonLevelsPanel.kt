package me.mycellium.skymyce.features.instances.dungeons.tracker

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTheme
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass

/**
 * One block of the Levels page: a title and the metric's column header, then a bar per row toward the target. The
 * strip along the top edge shows the block's overall progress (the average share of the target reached).
 */
internal class LevelBars(private val title: String, private val rows: List<LevelRow>, private val metric: LevelMetric,
                         private val highlight: DungeonClass? = null) : BaseUIComponent() {
    init { sizing(Sizing.fill(), Sizing.fixed(HEADER + rows.size * ROW + 4)) }

    private fun run(text: String, color: Int) = MenuCanvas.Run(text, MenuTheme.alpha(color))

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val canvas = (graphics as? MenuGraphics)?.canvas ?: return
        val left = x().toDouble(); val top = y().toDouble(); val width = width().toDouble()
        val right = left + width
        // Overall progress strip.
        val overall = rows.map { it.progress }.average().takeIf { it.isFinite() } ?: 0.0
        canvas.fill(left, top, width, 2.0, MenuTheme.alpha(MenuTheme.BORDER, 45))
        canvas.fill(left, top, width * overall, 2.0, MenuTheme.alpha(MenuTheme.ACCENT))
        val target = rows.map { it.target }.distinct().singleOrNull()
        val header = "${metric.header} ${target ?: "target"}"
        canvas.text(listOf(run(title, MenuTheme.ACCENT)), left, top + 7, 10.0)
        canvas.text(listOf(run(header, MenuTheme.MUTED)), right - MenuCanvas.textWidth(header, size = 9.0), top + 8, 9.0)

        val values = rows.map { levelValue(it, metric) }
        val valueWidth = (values.maxOfOrNull { MenuCanvas.textWidth(it, size = 10.0) } ?: 0.0).coerceAtLeast(34.0)
        val nameWidth = (rows.maxOfOrNull { MenuCanvas.textWidth("${it.name} ${it.level}", size = 10.0) } ?: 0.0) + 10
        val barLeft = left + nameWidth
        val barRight = right - valueWidth - 10
        rows.forEachIndexed { index, row ->
            val rowY = top + HEADER + index * ROW
            val mine = highlight != null && row.dungeonClass == highlight
            val reached = row.remaining <= 0
            canvas.text(listOf(run(row.name, if (mine) MenuTheme.TEXT else MenuTheme.blend(MenuTheme.TEXT, MenuTheme.MUTED, 25)),
                run(" ${row.level}", if (mine) MenuTheme.ACCENT else MenuTheme.MUTED)), left, rowY, 10.0)
            if (barRight - barLeft > 12) {
                val barY = rowY + 2
                canvas.fill(barLeft, barY, barRight - barLeft, BAR, MenuTheme.alpha(MenuTheme.BORDER, 55))
                val fill = (barRight - barLeft) * row.progress
                if (fill > 0) canvas.fill(barLeft, barY, fill, BAR, MenuTheme.alpha(if (reached) MenuTheme.GREEN else MenuTheme.ACCENT))
            }
            val value = values[index]
            canvas.text(listOf(run(value, if (reached) MenuTheme.GREEN else MenuTheme.TEXT)), right - MenuCanvas.textWidth(value, size = 10.0), rowY, 10.0)
        }
    }

    companion object {
        private const val HEADER = 22
        private const val ROW = 14
        private const val BAR = 7.0
    }
}

/** Targets offered in the Levels page's menu; "next level" is per row. 0 stands for next level. */
internal val LEVEL_TARGETS = listOf(0, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80, 100)

/** A row's target: the chosen level, or the level after the row's own for "next level". */
internal fun levelTarget(choice: Int, xp: Double) =
    if (choice <= 0) me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel(xp) + 1 else choice

/** The class rows in Hypixel's order (Healer, Mage, Berserk, Archer, Tank), each at its own rate. */
fun classLevelRows(experience: Map<DungeonClass, Double>, targetChoice: Int, rates: Map<DungeonClass, Double>, cataRate: Double?): List<LevelRow> =
    listOf(DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.BERSERKER, DungeonClass.ARCHER, DungeonClass.TANK).map { clazz ->
        val xp = experience[clazz] ?: 0.0
        val own = rates[clazz]
        levelRow(levelClassName(clazz), clazz, xp, levelTarget(targetChoice, xp), own ?: cataRate, estimatedRate = own == null)
    }
