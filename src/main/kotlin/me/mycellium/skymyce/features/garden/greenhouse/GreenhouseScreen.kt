package me.mycellium.skymyce.features.garden.greenhouse

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.features.bazaar.BazaarBadge
import me.mycellium.skymyce.features.bazaar.BazaarProgressBar
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.network.chat.Component

/** Every Greenhouse plot at a glance: its layout, what the last scan saw, growth timers and estimated and harvested profit. */
class GreenhouseScreen : MenuScreen(Component.literal("Greenhouses"), MenuPage.GREENHOUSE) {
    private lateinit var results: ScrollContainer<FlowLayout>
    private var rendered: List<Any?> = emptyList()
    private val panelWidth get() = menuWidth
    private val compact get() = panelWidth < 600

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(UIContainers.verticalFlow(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)).apply {
            surface(MenuTheme.panel()); padding(Insets.of(12)); gap(8)
            child(row().apply {
                gap(8)
                child(UIContainers.verticalFlow(Sizing.fixed(3), Sizing.fixed(18)).surface(MenuTheme.tintedPanel { MenuTheme.ACCENT }))
                child(label("§lGreenhouses").horizontalSizing(Sizing.expand()))
                child(button("Ranking", 64) { chat(GreenhousePlanner::showRanking) }.tooltip(Component.literal("Profit per plot for each mutation, in chat")))
                child(button("Help", 44) { chat(GreenhousePlanner::showHelp) }.tooltip(Component.literal("Every /sm greenhouse command, in chat")))
                child(button("Settings", 64) { openMenuFeature("greenhouse-planner", configure = true) })
            })
            results = menuScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.ACCENT))); scrollbarThiccness(2); padding(Insets.right(4))
            }
            child(results)
        })
        update()
    }

    override fun tick() {
        super.tick()
        if (menuClosing || !::results.isInitialized) return
        // Timers read in minutes, so a once-a-minute refresh keeps them current without rebuilding every tick.
        val state = listOf(GreenhousePlanner.revision, System.currentTimeMillis() / 60_000)
        if (state != rendered) update()
    }

    private fun update() {
        rendered = listOf(GreenhousePlanner.revision, System.currentTimeMillis() / 60_000)
        val now = System.currentTimeMillis()
        val plots = GreenhousePlanner.plots.toList()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            child(overview(plots, now))
            if (plots.isEmpty()) child(label("No Greenhouse plots yet. In your Garden, look at a plot's corner soil and run /sm greenhouse anchor.", MUTED)
                .horizontalSizing(Sizing.fill()).margins(Insets.of(8)))
            plots.forEach { child(plotCard(it, now)) }
        })
    }

    private fun overview(plots: List<GreenhousePlanner.Plot>, now: Long) = card(MenuTheme.ACCENT).apply {
        val perHour = plots.mapNotNull { it.layout }.sumOf(GreenhousePlanner::estimate)
        val daily = dailyProfit(GreenhousePlanner.history, now, 14)
        child(row().apply {
            child(label("§lAll plots").horizontalSizing(Sizing.expand()))
            child(label("${plots.size} plot${if (plots.size == 1) "" else "s"}", MUTED))
        })
        val history = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
            gap(2)
            child(ProfitBars(daily))
            child(label("Last 14 days ${GreenhousePlanner.signed(daily.sum())}, best day ${GreenhousePlanner.signed(daily.maxOrNull() ?: 0.0)}. Estimated at the price when harvested.", MUTED)
                .apply { fontSize = 8f; horizontalSizing(Sizing.fill()) })
        }
        child(row().apply {
            gap(6); verticalAlignment(VerticalAlignment.TOP)
            val share = if (compact) 49 else 24
            child(estimated(perHour, share)); child(harvested(harvestTotals(GreenhousePlanner.history, now), share))
            if (!compact) child(history)
        })
        if (compact) child(history)
    }

    private fun plotCard(plot: GreenhousePlanner.Plot, now: Long): FlowLayout {
        val layout = plot.layout
        val catalog = GreenhousePlanner.catalog
        val counts = plot.status.groupingBy { it }.eachCount()
        val growing = layout?.targets?.count { plot.status.getOrNull(it.row * GREENHOUSE_SIZE + it.col) == CellStatus.TARGET_GROWING.code } ?: 0
        val missing = plot.missingPlants.values.sum() + plot.missingSoil.values.sum()
        val stripe = when {
            layout == null -> MUTED
            plot.status.isEmpty() -> MUTED
            missing > 0 -> MenuTheme.YELLOW
            growing > 0 -> GREEN
            else -> MenuTheme.ACCENT
        }
        return card(stripe).apply {
            val target = layout?.targets?.firstOrNull()?.let { catalog?.get(it.plantId)?.name }
            val anchorText = plot.anchor?.let { "Anchored at ${it.pos.x} ${it.pos.y} ${it.pos.z}, rows run ${it.facing.name.lowercase()}" } ?: "Not anchored yet"
            child(row().apply {
                gap(6)
                child(label("§l${plot.name}").apply { fontSize = 12f })
                if (plot === GreenhousePlanner.selected()) child(BazaarBadge("Nearest", MenuTheme.ACCENT))
                child(label(anchorText, MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.expand()) })
                child(label(if (layout == null) "No plan" else "$target ×${layout.targets.size}", if (layout == null) MUTED else MenuTheme.ACCENT).apply { fontSize = 12f })
            })
            if (layout == null) {
                child(row().apply {
                    gap(6)
                    child(label("Stand at the plot and run /sm greenhouse import to read what is planted, or plan one with auto or plan <mutation>.", MUTED).horizontalSizing(Sizing.expand()))
                    child(button("Import", 56) { chat { GreenhousePlanner.importPlot(plot, null) } }.tooltip(Component.literal("Read what is planted on this plot (stand near it)")))
                })
                return@apply
            }
            val used = plot.status.count { it != CellStatus.UNUSED.code }
            val built = counts[CellStatus.DONE.code].orZero() + counts[CellStatus.TARGET_WAITING.code].orZero() + counts[CellStatus.TARGET_GROWING.code].orZero()
            val percent = if (used == 0) 0 else built * 100 / used
            child(row().apply {
                gap(6)
                if (plot.status.isEmpty()) {
                    child(label("Not checked yet: visit the plot so the planner can see what is planted", MUTED).horizontalSizing(Sizing.expand()))
                } else {
                    child(label("$percent% built", if (missing > 0) MenuTheme.YELLOW else TEXT))
                    child(BazaarProgressBar(built.toLong(), used.toLong(), if (missing > 0) MenuTheme.YELLOW else GREEN).apply { horizontalSizing(Sizing.fill(if (compact) 25 else 45)) })
                    val detail = (if (missing == 0) "nothing missing" else "missing ${plot.missingPlants.values.sum()} crops, ${plot.missingSoil.values.sum()} soil") +
                        (counts[CellStatus.WRONG_CROP.code]?.let { ", $it wrong crops" } ?: "") + ", checked ${GreenhousePlanner.age(now - plot.scannedAt)} ago"
                    child(label(detail, if (missing > 0) MenuTheme.YELLOW else MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.expand()) })
                }
            })
            val tiles = row().apply {
                gap(6); verticalAlignment(VerticalAlignment.TOP)
                child(growth(plot, layout, growing, now))
                child(estimated(GreenhousePlanner.estimate(layout), 32))
                child(harvested(harvestTotals(GreenhousePlanner.history, now, plot.name), 32))
            }
            if (compact) child(tiles)
            child(row().apply {
                gap(8); verticalAlignment(VerticalAlignment.TOP)
                child(GreenhouseGrid(layout, plot.status, if (panelWidth < 450) 12 else 16))
                child(UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
                    gap(4)
                    if (!compact) child(tiles)
                    child(GreenhouseLegend(layout))
                    child(label("Faded = not planted, red edge = wrong soil, dot = mutation growing. Hover a slot for details.", MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.fill()) })
                    child(actions(listOf(
                        button("Shopping list", 92) { chat { GreenhousePlanner.showMissing(plot) } }.tooltip(Component.literal("Everything still missing, with /bz and /gfs links")),
                        button("Import", 56) { chat { GreenhousePlanner.importPlot(plot, null) } }
                            .tooltip(Component.literal("Replace this layout with what is planted now (stand near the plot)")),
                        button("Recompute", 76) { chat { GreenhousePlanner.plan(plot, force = true) } }
                            .tooltip(Component.literal("Solve a new layout for ${target ?: "the best mutation"}; the current one is replaced")),
                    )))
                })
            })
        }
    }

    /** Growth timers; a per-mutation stage reading belongs in this tile once the planner records one. */
    private fun growth(plot: GreenhousePlanner.Plot, layout: Layout, growing: Int, now: Long): FlowLayout {
        val cycle = GreenhousePlanner.cycleHours(layout) ?: return tile("Growing", "—", MUTED, 32, listOf("No target"))
        val cycleMs = (cycle * 3_600_000).toLong()
        val ready = plot.spawned.values.map { it + cycleMs }
        val due = ready.count { it <= now }
        val next = ready.filter { it > now }.minOrNull()
        val all = ready.maxOrNull()?.takeIf { it > now && ready.size > 1 }
        val lines = ArrayList<String>()
        if (due > 0) lines += "$due ready to harvest"
        if (next != null) lines += "Next ready in ${GreenhousePlanner.age(next - now)}" + (all?.let { ", all in ${GreenhousePlanner.age(it - now)}" } ?: "")
        if (growing > plot.spawned.size) lines += "${growing - plot.spawned.size} appeared while you were away"
        lines += (if (plot.plantedAt > 0) "Planted ${GreenhousePlanner.age(now - plot.plantedAt)} ago" else "Planting time not seen yet") + ", ${"%.1f".format(cycle)}h cycle (est.)"
        return tile("Growing", "$growing of ${layout.targets.size}", if (due > 0) MenuTheme.YELLOW else if (growing > 0) GREEN else TEXT, 32, lines)
    }

    private fun estimated(perHour: Double, share: Int) = tile("Estimated", "${GreenhousePlanner.signed(perHour * 24)}/day", GREEN, share,
        listOf("${GreenhousePlanner.signed(perHour)}/h", "${GreenhousePlanner.signed(perHour * 24 * 7)}/week, ${GreenhousePlanner.signed(perHour * 24 * 30)}/month"))

    private fun harvested(totals: HarvestTotals, share: Int) = if (totals.count == 0) tile("Harvested", "—", MUTED, share, listOf("No harvests recorded yet")) else
        tile("Harvested", "${GreenhousePlanner.signed(totals.week)} last 7d", TEXT, share, listOf(
            "24h ${GreenhousePlanner.signed(totals.day)}, 30d ${GreenhousePlanner.signed(totals.month)}",
            "${GreenhousePlanner.signed(totals.total)} total, ${totals.count} harvest${if (totals.count == 1) "" else "s"}"))

    /** One number that matters, with its context in small print underneath. */
    private fun tile(title: String, value: String, color: Int, share: Int, lines: List<String>) = UIContainers.verticalFlow(Sizing.fill(share), Sizing.content()).apply {
        gap(2); padding(Insets.of(5)); surface(MenuTheme.tintedPanel { MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.PANEL, 60), 80) })
        child(label(title, MUTED).apply { fontSize = 8f })
        child(label(value, color).apply { fontSize = 13f; horizontalSizing(Sizing.fill()) })
        lines.forEach { child(label(it, MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.fill()) }) }
    }

    /** Chat output is hidden behind the menu, so actions that answer in chat close it first. */
    private fun chat(action: () -> Unit) { onClose(); MC.instance.execute(action) }

    private fun Int?.orZero() = this ?: 0
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = TEXT) = MenuLabel(Component.literal(text)).apply { color(Color.ofRgb(color)); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).apply {
        sizing(Sizing.fixed(width), Sizing.fixed(20)); themed(); textShadow(false)
    }
    private fun actions(buttons: List<UIComponent>) = row().apply { gap(4); children(buttons) }
    private fun card(stripe: Int) = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        padding(Insets.of(6)); gap(5)
        surface(MenuTheme.tintedPanel { MenuTheme.CARD }.and(Surface { graphics, component ->
            graphics.fill(component.x(), component.y(), component.x() + 2, component.y() + component.height(), MenuTheme.alpha(stripe))
        }))
    }

    private companion object {
        val TEXT get() = MenuTheme.TEXT
        val MUTED get() = MenuTheme.MUTED
        val GREEN get() = MenuTheme.GREEN
    }
}

/**
 * The plot's 10×10 layout drawn from the pinned plan and the last scan; hovering a slot names it. Row 1 is the anchor's
 * row at the top and columns run right to left, so the grid matches the plot seen from above facing the anchor.
 */
internal class GreenhouseGrid(private val layout: Layout, private val status: String, private val cell: Int = 9) : BaseUIComponent() {
    private var hovered = -1

    init { sizing(Sizing.fixed(cell * GREENHOUSE_SIZE + 1), Sizing.fixed(cell * GREENHOUSE_SIZE + 1)) }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        g.roundedRect(x(), y(), width(), height(), 2.0, MenuTheme.alpha(MenuTheme.BORDER, 35))
        for (index in 0 until GREENHOUSE_CELLS) {
            if (layout.cells[index] != null) continue
            g.roundedRect(x() + (GREENHOUSE_SIZE - 1 - index % GREENHOUSE_SIZE) * cell + 1, y() + index / GREENHOUSE_SIZE * cell + 1, cell - 1, cell - 1, 1.0, MenuTheme.alpha(MenuTheme.MUTED, 12))
        }
        val dot = if (cell >= 14) 5 else 3
        for (placement in layout.placements) {
            val first = placement.row * GREENHOUSE_SIZE + placement.col
            val code = status.getOrNull(first)?.let(CellStatus::of)
            val px = x() + (GREENHOUSE_SIZE - placement.col - placement.size) * cell + 1; val py = y() + placement.row * cell + 1
            val size = placement.size * cell - 1
            val strength = when (code) {
                null -> 60
                CellStatus.DONE, CellStatus.TARGET_GROWING -> 95
                CellStatus.MISSING_CROP, CellStatus.TARGET_WAITING -> 30
                else -> 60
            }
            val color = if (code == CellStatus.WRONG_CROP) MenuTheme.RED else GreenhousePlanner.plantColor(placement.plantId)
            g.roundedRect(px, py, size, size, 1.5, MenuTheme.alpha(color, strength))
            if (placement.role == Role.TARGET) g.roundedBorder(px, py, size, size, 1.5, 1.0, MenuTheme.alpha(MenuTheme.TEXT, 70))
            if (code == CellStatus.TARGET_GROWING) g.roundedRect(px + (size - dot) / 2, py + (size - dot) / 2, dot, dot, dot / 2.0, MenuTheme.alpha(MenuTheme.TEXT))
            val wrongSoil = (0 until placement.size).any { r -> (0 until placement.size).any { c ->
                status.getOrNull(first + r * GREENHOUSE_SIZE + c) == CellStatus.WRONG_SOIL.code
            } }
            if (wrongSoil) g.roundedBorder(px, py, size, size, 1.5, 1.0, MenuTheme.alpha(MenuTheme.RED))
        }
        val inside = mouseX in x() until x() + width() - 1 && mouseY in y() until y() + height() - 1
        val index = if (inside) (mouseY - y()) / cell * GREENHOUSE_SIZE + GREENHOUSE_SIZE - 1 - (mouseX - x()) / cell else -1
        if (index != hovered) {
            hovered = index
            if (index < 0) tooltip(null as List<ClientTooltipComponent>?) else tooltip(Component.literal(describe(index)))
        }
    }

    private fun describe(index: Int): String {
        val plan = layout.cells[index]
        val where = "Row ${index / GREENHOUSE_SIZE + 1}, column ${index % GREENHOUSE_SIZE + 1}"
        if (plan == null) return "$where\nUnused"
        val name = GreenhousePlanner.catalog?.get(plan.plantId)?.name ?: plan.plantId
        val state = when (status.getOrNull(index)?.let(CellStatus::of)) {
            CellStatus.DONE -> "Planted"
            CellStatus.MISSING_CROP -> "Not planted yet"
            CellStatus.WRONG_CROP -> "A different crop is planted here"
            CellStatus.WRONG_SOIL -> "Needs ${GreenhousePlanner.soilName(plan.soil)} soil"
            CellStatus.TARGET_WAITING -> "Empty, waiting for the mutation"
            CellStatus.TARGET_GROWING -> "Mutation growing"
            else -> "Not checked yet"
        }
        return "$where\n$name (${if (plan.role == Role.TARGET) "target" else "ingredient"}) on ${GreenhousePlanner.soilName(plan.soil)}\n$state"
    }
}

/** Colour chips for every crop in the layout, wrapped to the available width; crops planted once share one group. */
internal class GreenhouseLegend(layout: Layout) : BaseUIComponent() {
    private val size = 9.0
    private val chipGap = 10.0
    private val entries: List<Pair<Int?, List<MenuCanvas.Run>>>

    init {
        val catalog = GreenhousePlanner.catalog
        val groups = layout.placements.groupBy { it.plantId to it.role }.entries.sortedWith(compareBy({ it.key.second != Role.TARGET }, { -it.value.size }))
        val many = groups.filter { it.value.size > 1 || it.key.second == Role.TARGET }
        val once = groups - many.toSet()
        entries = many.map { (key, placements) ->
            val name = catalog?.get(key.first)?.name ?: key.first
            GreenhousePlanner.plantColor(key.first) to listOf(MenuCanvas.Run("$name ×${placements.size}", MenuTheme.alpha(MenuTheme.TEXT), bold = key.second == Role.TARGET))
        } + (if (once.isEmpty()) emptyList() else listOf(null to listOf(MenuCanvas.Run("1 each", MenuTheme.alpha(MenuTheme.MUTED))))) +
            once.map { (key, _) -> GreenhousePlanner.plantColor(key.first) to listOf(MenuCanvas.Run(catalog?.get(key.first)?.name ?: key.first, MenuTheme.alpha(MenuTheme.TEXT))) }
        sizing(Sizing.fill(), Sizing.content())
    }

    private fun chipWidth(entry: Pair<Int?, List<MenuCanvas.Run>>) = (if (entry.first == null) 0.0 else 11.0) + entry.second.sumOf { MenuCanvas.textWidth(it.text, it.bold, size = size) }
    private fun lineHeight() = MenuCanvas.lineHeight(size) + 2

    /** Pack chips left to right; each call returns the row index for every chip. */
    private fun rows(width: Int): IntArray {
        var x = 0.0; var row = 0
        return IntArray(entries.size) { i ->
            val w = chipWidth(entries[i])
            if (x > 0 && x + w > width) { x = 0.0; row++ }
            x += w + chipGap
            row
        }
    }

    override fun determineHorizontalContentSize(sizing: Sizing) = entries.maxOfOrNull { kotlin.math.ceil(chipWidth(it)).toInt() } ?: 0
    override fun determineVerticalContentSize(sizing: Sizing) = ((rows(width()).lastOrNull() ?: -1) + 1) * lineHeight()

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        val placed = rows(width())
        var x = 0.0; var row = -1
        entries.forEachIndexed { i, entry ->
            if (placed[i] != row) { row = placed[i]; x = 0.0 }
            val top = y() + row * lineHeight()
            entry.first?.let { g.roundedRect((x() + x).toInt(), top + 2, 7, 7, 1.5, MenuTheme.alpha(it)); }
            g.styledText(entry.second, x() + x + if (entry.first == null) 0.0 else 11.0, top.toDouble(), size)
            x += chipWidth(entry) + chipGap
        }
    }
}

/** Daily harvest profit as static bars; losses hang below the zero line. */
internal class ProfitBars(private val values: DoubleArray) : BaseUIComponent() {
    private var hovered = -2
    init { sizing(Sizing.fill(), Sizing.fixed(48)) }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        g.roundedRect(x(), y(), width(), height(), 2.0, MenuTheme.alpha(MenuTheme.BORDER, 20))
        val high = values.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val low = values.minOrNull()?.coerceAtMost(0.0) ?: 0.0
        val range = (high - low).takeIf { it > 0 } ?: return
        val zero = y() + 3 + ((high / range) * (height() - 6)).toInt()
        val slot = (width() - 4) / values.size.coerceAtLeast(1)
        values.forEachIndexed { i, value ->
            if (value == 0.0) return@forEachIndexed
            val size = (kotlin.math.abs(value) / range * (height() - 6)).toInt().coerceAtLeast(1)
            val top = if (value > 0) zero - size else zero
            g.roundedRect(x() + 2 + i * slot + 1, top, (slot - 2).coerceAtLeast(1), size, 1.0, MenuTheme.alpha(if (value > 0) MenuTheme.GREEN else MenuTheme.RED, 80))
        }
        g.fill(x() + 2, zero, x() + width() - 2, zero + 1, MenuTheme.alpha(MenuTheme.MUTED, 50))
        val index = if (mouseX in x() until x() + width() && mouseY in y() until y() + height()) ((mouseX - x() - 2) / slot.coerceAtLeast(1)).coerceIn(0, values.size - 1) else -1
        if (index != hovered) {
            hovered = index
            val ago = values.size - 1 - index
            if (index < 0) tooltip(null as List<ClientTooltipComponent>?) else tooltip(Component.literal("${if (ago == 0) "Today" else "$ago days ago"}: ${GreenhousePlanner.signed(values[index])}"))
        }
    }
}
