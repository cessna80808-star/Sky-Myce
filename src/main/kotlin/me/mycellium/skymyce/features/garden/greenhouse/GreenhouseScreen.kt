package me.mycellium.skymyce.features.garden.greenhouse

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
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
        val totals = harvestTotals(GreenhousePlanner.history, now)
        child(row().apply {
            child(label("§lAll plots").horizontalSizing(Sizing.expand()))
            child(label("${plots.size} plot${if (plots.size == 1) "" else "s"}", MUTED))
        })
        child(label("Estimated ${rates(perHour)}", GREEN).horizontalSizing(Sizing.fill()))
        child(label(harvested(totals), TEXT).horizontalSizing(Sizing.fill()))
        val daily = dailyProfit(GreenhousePlanner.history, now, 14)
        child(label("PROFIT HISTORY · last 14 days, from harvests seen by the planner", MUTED).horizontalSizing(Sizing.fill()))
        child(ProfitBars(daily))
        child(label("14 days ${GreenhousePlanner.signed(daily.sum())} · best day ${GreenhousePlanner.signed(daily.maxOrNull() ?: 0.0)} · values are estimates at the price when harvested", MUTED)
            .horizontalSizing(Sizing.fill()))
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
            child(row().apply {
                child(label("§l${plot.name}${if (plot === GreenhousePlanner.selected()) " §7· nearest" else ""}").horizontalSizing(Sizing.expand()))
                child(label(if (layout == null) "No plan" else "$target ×${layout.targets.size}", if (layout == null) MUTED else MenuTheme.ACCENT))
            })
            child(label(plot.anchor?.let { "Anchored at ${it.pos.x} ${it.pos.y} ${it.pos.z}, rows run ${it.facing.name.lowercase()}" } ?: "Not anchored yet", MUTED).horizontalSizing(Sizing.fill()))
            if (layout == null) {
                child(label("Stand at the plot and run /sm greenhouse import to read what is planted, or plan one with auto or plan <mutation>.", MUTED).horizontalSizing(Sizing.fill()))
                child(actions(listOf(button("Import", 56) { chat { GreenhousePlanner.importPlot(plot, null) } }.tooltip(Component.literal("Read what is planted on this plot (stand near it)")))))
                return@apply
            }
            val used = plot.status.count { it != CellStatus.UNUSED.code }
            val built = counts[CellStatus.DONE.code].orZero() + counts[CellStatus.TARGET_WAITING.code].orZero() + counts[CellStatus.TARGET_GROWING.code].orZero()
            child(label(when {
                plot.status.isEmpty() -> "Not checked yet: visit the plot so the planner can see what is planted"
                else -> "Built ${if (used == 0) 0 else built * 100 / used}% · " +
                    (if (missing == 0) "nothing missing" else "missing ${plot.missingPlants.values.sum()} crops, ${plot.missingSoil.values.sum()} soil") +
                    (counts[CellStatus.WRONG_CROP.code]?.let { " · $it wrong crops" } ?: "") + " · checked ${GreenhousePlanner.age(now - plot.scannedAt)} ago"
            }, if (missing > 0) MenuTheme.YELLOW else TEXT).horizontalSizing(Sizing.fill()))
            child(label(timers(plot, layout, growing, now), TEXT).horizontalSizing(Sizing.fill()))
            child(label("Estimated ${rates(GreenhousePlanner.estimate(layout))}", GREEN).horizontalSizing(Sizing.fill()))
            child(label(harvested(harvestTotals(GreenhousePlanner.history, now, plot.name)), TEXT).horizontalSizing(Sizing.fill()))
            child(row().apply {
                gap(10); verticalAlignment(VerticalAlignment.TOP)
                child(GreenhouseGrid(layout, plot.status))
                child(legend(layout))
            })
            child(actions(listOf(
                button("Shopping list", 92) { chat { GreenhousePlanner.showMissing(plot) } }.tooltip(Component.literal("Everything still missing, with /bz and /gfs links")),
                button("Import", 56) { chat { GreenhousePlanner.importPlot(plot, null) } }
                    .tooltip(Component.literal("Replace this layout with what is planted now (stand near the plot)")),
                button("Recompute", 76) { chat { GreenhousePlanner.plan(plot, force = true) } }
                    .tooltip(Component.literal("Solve a new layout for ${target ?: "the best mutation"}; the current one is replaced")),
            )))
        }
    }

    private fun timers(plot: GreenhousePlanner.Plot, layout: Layout, growing: Int, now: Long): String {
        val cycle = GreenhousePlanner.cycleHours(layout) ?: return "No target"
        val cycleMs = (cycle * 3_600_000).toLong()
        val parts = ArrayList<String>()
        parts += if (plot.plantedAt > 0) "planted ${GreenhousePlanner.age(now - plot.plantedAt)} ago" else "planting time not seen yet"
        parts += "$growing/${layout.targets.size} growing"
        val ready = plot.spawned.values.map { it + cycleMs }
        val due = ready.count { it <= now }
        if (due > 0) parts += "$due ready to harvest"
        ready.filter { it > now }.minOrNull()?.let { parts += "next ready in ${GreenhousePlanner.age(it - now)}" }
        ready.maxOrNull()?.takeIf { it > now && ready.size > 1 }?.let { parts += "all ready in ${GreenhousePlanner.age(it - now)}" }
        if (growing > plot.spawned.size) parts += "${growing - plot.spawned.size} appeared while you were away"
        parts += "${"%.1f".format(cycle)}h cycle (est.)"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    private fun legend(layout: Layout) = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
        gap(3)
        val catalog = GreenhousePlanner.catalog
        layout.placements.groupBy { it.plantId to it.role }.entries.sortedWith(compareBy({ it.key.second != Role.TARGET }, { -it.value.size })).forEach { (key, placements) ->
            val (id, role) = key
            child(row().apply {
                gap(4)
                child(label("■", GreenhousePlanner.plantColor(id)))
                child(label("${catalog?.get(id)?.name ?: id} ×${placements.size}${if (role == Role.TARGET) " §7target" else ""}", TEXT).horizontalSizing(Sizing.expand()))
            })
        }
        child(label("Faded: not planted · red edge: wrong soil · dot: mutation growing", MUTED).horizontalSizing(Sizing.fill()))
    }

    private fun rates(perHour: Double) = "${GreenhousePlanner.signed(perHour)}/h · ${GreenhousePlanner.signed(perHour * 24)}/day · " +
        "${GreenhousePlanner.signed(perHour * 24 * 7)}/week · ${GreenhousePlanner.signed(perHour * 24 * 30)}/month"

    private fun harvested(totals: HarvestTotals) = if (totals.count == 0) "No harvests recorded yet" else
        "Harvested: 24h ${GreenhousePlanner.signed(totals.day)} · 7d ${GreenhousePlanner.signed(totals.week)} · 30d ${GreenhousePlanner.signed(totals.month)} · " +
            "total ${GreenhousePlanner.signed(totals.total)} (${totals.count} harvest${if (totals.count == 1) "" else "s"})"

    /** Chat output is hidden behind the menu, so actions that answer in chat close it first. */
    private fun chat(action: () -> Unit) { onClose(); MC.instance.execute(action) }

    private fun Int?.orZero() = this ?: 0
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = TEXT) = MenuLabel(Component.literal(text)).color(Color.ofRgb(color)).shadow(MenuTheme.SHADOW)
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

/** The plot's 10×10 layout drawn from the pinned plan and the last scan; hovering a slot names it. */
internal class GreenhouseGrid(private val layout: Layout, private val status: String) : BaseUIComponent() {
    private val cell = 9
    private var hovered = -1

    init { sizing(Sizing.fixed(cell * GREENHOUSE_SIZE + 1), Sizing.fixed(cell * GREENHOUSE_SIZE + 1)) }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        g.roundedRect(x(), y(), width(), height(), 2.0, MenuTheme.alpha(MenuTheme.BORDER, 35))
        for (index in 0 until GREENHOUSE_CELLS) {
            if (layout.cells[index] != null) continue
            g.roundedRect(x() + index % GREENHOUSE_SIZE * cell + 1, y() + index / GREENHOUSE_SIZE * cell + 1, cell - 1, cell - 1, 1.0, MenuTheme.alpha(MenuTheme.MUTED, 12))
        }
        for (placement in layout.placements) {
            val first = placement.row * GREENHOUSE_SIZE + placement.col
            val code = status.getOrNull(first)?.let(CellStatus::of)
            val px = x() + placement.col * cell + 1; val py = y() + placement.row * cell + 1
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
            if (code == CellStatus.TARGET_GROWING) g.roundedRect(px + size / 2 - 1, py + size / 2 - 1, 3, 3, 1.5, MenuTheme.alpha(MenuTheme.TEXT))
            val wrongSoil = (0 until placement.size).any { r -> (0 until placement.size).any { c ->
                status.getOrNull(first + r * GREENHOUSE_SIZE + c) == CellStatus.WRONG_SOIL.code
            } }
            if (wrongSoil) g.roundedBorder(px, py, size, size, 1.5, 1.0, MenuTheme.alpha(MenuTheme.RED))
        }
        val inside = mouseX in x() until x() + width() - 1 && mouseY in y() until y() + height() - 1
        val index = if (inside) (mouseY - y()) / cell * GREENHOUSE_SIZE + (mouseX - x()) / cell else -1
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
