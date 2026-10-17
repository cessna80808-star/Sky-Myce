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
class DungeonSplitsScreen : MenuScreen(Component.literal("Dungeon Splits"), MenuPage.SPLITS) {
    override val menuCategory get() = MenuCategory.DUNGEONS
    private companion object {
        var floor: String? = null
        var window = SplitWindow.ALL
    }
    private lateinit var body: FlowLayout
    private lateinit var results: ScrollContainer<FlowLayout>
    private var seenRevision = -1L
    private val selectedFloor get() = floor ?: DungeonSplits.live?.definition?.floor ?: DungeonSplitStore.runs.lastOrNull()?.floor ?: "M7"

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(6)
        root.child(row().apply {
            child(label("DUNGEON SPLITS", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
            child(button("Close", 46) { onClose() })
        })
        root.child(row().apply {
            child(button(selectedFloor, 56) { b -> openMenuDropdown(b) { d ->
                splitDefinitions.keys.sortedWith(compareBy({ it.first() }, { it.drop(1).toInt() })).forEach { name ->
                    d.button(Component.literal(name)) { floor = name; rebuildMenu() }
                }
            } }.wrappedTooltip(Component.literal("Floor. Normal and Master Mode are always kept apart.")))
            child(button(window.title, 70) { b -> openMenuDropdown(b) { d ->
                SplitWindow.entries.forEach { option -> d.button(Component.literal(option.title)) { window = option; rebuildMenu() } }
            } }.wrappedTooltip(Component.literal("Which runs the averages use. Bests always use every saved run.")))
            // Run counts are in the summary; only loading and unreadable-file states need a note here.
            child(label(DungeonSplitStore.status.takeUnless { it.endsWith("saved runs") }.orEmpty(), MenuTheme.YELLOW).apply { horizontalSizing(Sizing.expand()) })
        })
        body = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(4) }
        results = menuScroll(Sizing.fill(), Sizing.expand(), body).apply { scrollStep(24); scrollbarThiccness(2) }
        root.child(results)
        render()
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
        val stats = floorSplitStats(runs, definition, window)
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
        body.child(label("${stats.completedRuns} completed · ${stats.savedRuns} saved on ${definition.floor}", MenuTheme.MUTED))

        body.child(tableRow("SECTION", "BEST", "AVERAGE", "BEST FROM", MenuTheme.SECONDARY))
        for (section in stats.sections) {
            body.child(tableRow(definition.title(section.section.milestone), time(section.best?.duration),
                if (section.samples == 0) "—" else "${average(section.average)} (${section.samples})", source(section.best), MenuTheme.TEXT)
                .wrappedTooltip(Component.literal("From ${definition.title(section.section.from)} to ${definition.title(section.section.milestone)}")))
        }
        for (marker in stats.markers) {
            body.child(tableRow("${definition.title(marker.milestone)} ✱", time(marker.best?.duration),
                if (marker.samples == 0) "—" else "${average(marker.average)} (${marker.samples})", source(marker.best), MenuTheme.MUTED)
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
                "", notes.joinToString(" · "), if (run.total != null) MenuTheme.TEXT else MenuTheme.MUTED))
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
