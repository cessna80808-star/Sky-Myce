package me.mycellium.skymyce.features.instances.dungeons.splits

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.*
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val runDate = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** Statistics for one floor at a time; the live HUD never shows history. */
class DungeonSplitsScreen(startClock: SplitClock? = null) : MenuScreen(Component.literal("Dungeon Splits"), MenuPage.SPLITS) {
    override val menuCategory get() = MenuCategory.DUNGEONS
    private companion object {
        var floor: String? = null
        var window = SplitWindow.ALL
        var clock = SplitClock.REAL
    }
    init { startClock?.let { clock = it } }
    private lateinit var body: FlowLayout
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var swap: MenuSwap
    private lateinit var floorButton: ButtonComponent
    private lateinit var windowButton: ButtonComponent
    private lateinit var clockButton: ButtonComponent
    private var seenRevision = -1L
    private val selectedFloor get() = floor ?: DungeonSplits.live?.definition?.floor ?: DungeonSplitStore.runs.lastOrNull()?.floor ?: "M7"

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(6)
        root.child(row().apply {
            child(label("DUNGEON SPLITS", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
            child(button("Close", 46) { onClose() })
        })
        root.child(row().apply {
            // Switching keeps the controls and fades only the results, as the Dungeon Tracker does.
            floorButton = button(selectedFloor, 56) { b -> openMenuDropdown(b) { d ->
                splitDefinitions.keys.sortedWith(compareBy({ it.first() }, { it.drop(1).toInt() })).forEach { name ->
                    d.button(Component.literal(name)) { floor = name; switched() }
                }
            } }
            child(floorButton.wrappedTooltip(Component.literal("Floor. Normal and Master Mode are always kept apart.")))
            windowButton = button(window.title, 70) { b -> openMenuDropdown(b) { d ->
                SplitWindow.entries.forEach { option -> d.button(Component.literal(option.title)) { window = option; switched() } }
            } }
            child(windowButton.wrappedTooltip(Component.literal("Which runs the averages use. Bests always use every saved run.")))
            clockButton = button(clock.title, 84) { b -> openMenuDropdown(b) { d ->
                SplitClock.entries.forEach { option -> d.button(Component.literal(option.title)) { clock = option; switched() } }
            } }
            child(clockButton.wrappedTooltip(Component.literal("Real time is the stopwatch and includes lag. Server time counts server ticks, so lag is left out. Runs from before 2.7.14 only have real time.")))
            // Run counts are in the summary; only loading and unreadable-file states need a note here.
            child(label(DungeonSplitStore.status.takeUnless { it.endsWith("saved runs") }.orEmpty(), MenuTheme.YELLOW).apply { horizontalSizing(Sizing.expand()) })
        })
        body = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(4) }
        results = menuScroll(Sizing.fill(), Sizing.expand(), body).apply { scrollStep(24); scrollbarThiccness(2) }
        swap = MenuSwap("dungeon-splits-results", Sizing.fill(), Sizing.expand()).apply { child(results) }
        root.child(swap)
        render()
    }

    private fun switched() {
        floorButton.message = Component.literal(selectedFloor)
        windowButton.message = Component.literal(window.title)
        clockButton.message = Component.literal(clock.title)
        swap.change(); render()
    }

    override fun tick() {
        super.tick()
        if (!menuClosing && ::body.isInitialized && seenRevision != DungeonSplitStore.revision) render()
    }

    private fun render() {
        seenRevision = DungeonSplitStore.revision
        body.clearChildren()
        val definition = splitDefinitions[selectedFloor] ?: return
        val runs = DungeonSplitStore.runs
        val stats = floorSplitStats(runs, definition, window, clock)
        fun time(value: Long?) = formatSplitTime(value)
        fun average(value: Double?) = value?.let { formatSplitTime(it.toLong()) } ?: "—"
        fun source(best: SectionBest?) = best?.let { runDate.format(Instant.ofEpochMilli(it.recordedAt).atZone(ZoneId.systemDefault())) } ?: ""

        body.child(row().apply {
            child(card("Personal best", time(stats.actualPb?.duration), stats.actualPb?.let { "Set ${source(it)}" } ?: "No completed run yet"))
            child(card("Best possible", time(stats.theoreticalPb), when {
                stats.theoreticalPb != null -> "Sum of your best sections"
                stats.completedRuns == 0 -> "Needs a complete run"
                else -> "A section has no complete time yet"
            }).wrappedTooltip(Component.literal("Adds your best time for each section, each from any fully split completed run. Those bests can come from different runs.")))
            child(card("Average run", average(stats.averageTotal), "${window.title} · ${stats.averageSamples} run${if (stats.averageSamples == 1) "" else "s"}"))
        })
        body.child(label(listOfNotNull("${stats.completedRuns} completed · ${stats.savedRuns} saved on ${definition.floor}",
            stats.averageLag?.let { "lag lost ${formatSplitDelta(it.toLong())} per run on average" }).joinToString(" · "), MenuTheme.MUTED))

        body.child(tableRow("SECTION", "BEST", "AVERAGE", "BEST FROM", MenuTheme.SECONDARY))
        for (section in stats.sections) {
            val position = definition.chain.indexOf(section.section.milestone)
            val group = definition.groups.firstOrNull { definition.chain.indexOf(it.from) < position && position <= definition.chain.indexOf(it.to) }
            // A group's total heads its sections, which are indented beneath it; it is a real run's time, not a sum of bests.
            if (group != null && section.section.from == group.from) stats.groups.firstOrNull { it.group == group }?.let { total ->
                body.child(tableRow(group.title, time(total.best?.duration), average(total.average),
                    source(total.best), MenuTheme.ACCENT)
                    .wrappedTooltip(Component.literal("All terminal sections, from ${definition.title(group.from)} to ${definition.title(group.to)}, timed within one run. Not added to Best possible, which already counts each section.")))
            }
            body.child(tableRow((if (group != null) "    " else "") + definition.sectionTitle(section.section.milestone), time(section.best?.duration),
                average(section.average), source(section.best), MenuTheme.TEXT)
                .wrappedTooltip(Component.literal("From ${definition.title(section.section.from)} to ${definition.title(section.section.milestone)}")))
            stats.checkpoints.firstOrNull { it.milestone == section.section.milestone }?.let { checkpoint ->
                body.child(tableRow("${definition.title(checkpoint.milestone)} ✱", time(checkpoint.best?.duration), average(checkpoint.average),
                    source(checkpoint.best), MenuTheme.ACCENT)
                    .wrappedTooltip(Component.literal("Time from the run start to ${definition.title(checkpoint.milestone).lowercase()}: the whole clear in one number. Its best is a single run's time.")))
            }
        }
        for (marker in stats.markers) {
            body.child(tableRow("${definition.title(marker.milestone)} ✱", time(marker.best?.duration),
                average(marker.average), source(marker.best), MenuTheme.MUTED)
                .wrappedTooltip(Component.literal("Time from the run start until the estimated score reached 300. It can happen before or after boss entry, so it is not a section.")))
        }
        body.child(label("✱ Timed from the start; not part of Best possible. Bests use completed runs with every split seen; averages also use sections seen in other runs.", MenuTheme.MUTED))

        body.child(label("RECENT RUNS", MenuTheme.SECONDARY).apply { margins(Insets.top(6)) })
        val recent = runs.filter { it.floor == definition.floor }.sortedByDescending { it.recordedAt }.take(15)
        if (recent.isEmpty()) body.child(label("No runs on ${definition.floor} yet. Splits are saved automatically when a run ends.", MenuTheme.MUTED))
        recent.forEach { run ->
            val notes = listOfNotNull(
                run.outcome.takeIf { it != SplitOutcome.COMPLETED }?.title,
                "Joined late".takeIf { !run.startObserved },
                "Not every split seen".takeIf { run.outcome == SplitOutcome.COMPLETED && run.startObserved && !splitRunEligible(run, definition) && run.conflicts == 0 },
                "Conflicting events".takeIf { run.conflicts > 0 },
                "Older split layout".takeIf { run.definition != definition.version },
            )
            body.child(tableRow(runDate.format(Instant.ofEpochMilli(run.recordedAt).atZone(ZoneId.systemDefault())), time(run.total),
                run.total(SplitClock.SERVER)?.let { "(${time(it)})" } ?: "", (listOfNotNull(run.lagLost?.let { "lag ${formatSplitDelta(it)}" }) + notes).joinToString(" · "),
                if (run.total != null) MenuTheme.TEXT else MenuTheme.MUTED))
        }
    }

    private fun card(title: String, value: String, caption: String) = UIContainers.verticalFlow(Sizing.fill(32), Sizing.content()).apply {
        gap(2); padding(Insets.of(5)); surface(MenuTheme.panel(true))
        child(label(title, MenuTheme.MUTED))
        child(MenuLabel(Component.literal(value).withColor(MenuTheme.TEXT)).apply { fontSize = 14f; horizontalSizing(Sizing.fill()) })
        child(label(caption, MenuTheme.MUTED).apply { fontSize = 8f })
    }

    private fun tableRow(name: String, best: String, average: String, from: String, color: Int) = row().apply {
        padding(Insets.of(2, 2, 4, 4))
        child(label(name, color).apply { horizontalSizing(Sizing.fill(28)) })
        child(label(best, color).apply { horizontalSizing(Sizing.fill(18)) })
        child(label(average, color).apply { horizontalSizing(Sizing.fill(22)) })
        child(label(from, MenuTheme.MUTED).apply { horizontalSizing(Sizing.fill(28)) })
    }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) =
        MenuButton(Component.literal(text), action).apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}
