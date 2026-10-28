package me.mycellium.skymyce.features.instances.dungeons.splits

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig as Settings
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

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
                stats.theoreticalPb != null -> "Best entry + best boss sections"
                stats.completedRuns == 0 -> "Needs a complete run"
                else -> "A section has no complete time yet"
            }).wrappedTooltip(Component.literal("Your best boss entry, one run's whole clear, plus your best time for each boss section from any fully split completed run. The clear's fastest sections rarely happen together, so they are not added up.")))
            child(card("Average run", average(stats.averageTotal), "${window.title} · ${stats.averageSamples} run${if (stats.averageSamples == 1) "" else "s"}"))
        })
        body.child(label(listOfNotNull("${stats.completedRuns} completed · ${stats.savedRuns} saved on ${definition.floor}",
            stats.averageLag?.let { "lag lost ${formatSplitDelta(it.toLong())} per run on average" }).joinToString(" · "), MenuTheme.MUTED))

        body.child(tableRow("SECTION", "BEST", "AVERAGE", "WORST", "BEST FROM", MenuTheme.SECONDARY))
        for (section in stats.sections) {
            val position = definition.chain.indexOf(section.section.milestone)
            val group = definition.groups.firstOrNull { definition.chain.indexOf(it.from) < position && position <= definition.chain.indexOf(it.to) }
            // A group's total heads its sections, which are indented beneath it; it is a real run's time, not a sum of bests.
            if (group != null && section.section.from == group.from) stats.groups.firstOrNull { it.group == group }?.let { total ->
                body.child(tableRow(group.title, time(total.best?.duration), average(total.average), time(total.worst?.duration),
                    source(total.best), MenuTheme.ACCENT)
                    .wrappedTooltip(Component.literal("All terminal sections, from ${definition.title(group.from)} to ${definition.title(group.to)}, timed within one run. Not added to Best possible, which already counts each section.")))
            }
            body.child(tableRow((if (group != null) "    " else "") + definition.sectionTitle(section.section.milestone), time(section.best?.duration),
                average(section.average), time(section.worst?.duration), source(section.best), MenuTheme.TEXT)
                .wrappedTooltip(Component.literal("From ${definition.title(section.section.from)} to ${definition.title(section.section.milestone)}")))
            stats.checkpoints.firstOrNull { it.milestone == section.section.milestone }?.let { checkpoint ->
                body.child(tableRow("${definition.title(checkpoint.milestone)} ✱", time(checkpoint.best?.duration), average(checkpoint.average),
                    time(checkpoint.worst?.duration), source(checkpoint.best), MenuTheme.ACCENT)
                    .wrappedTooltip(Component.literal("Time from the run start to ${definition.title(checkpoint.milestone).lowercase()}: the whole clear in one number. Its best is a single run's time and starts Best possible.")))
            }
        }
        for (marker in stats.markers) {
            body.child(tableRow("${definition.title(marker.milestone)} ✱", time(marker.best?.duration),
                average(marker.average), time(marker.worst?.duration), source(marker.best), MenuTheme.MUTED)
                .wrappedTooltip(Component.literal("Time from the run start until the score reached 300. Hypixel's own score in the boss settles it: if it shows 300 the estimate missed, the time is boss entry. It can happen before or after boss entry, so it is not a section.")))
        }
        body.child(label("✱ Timed from the start. Best possible uses Boss Entry in place of the clear sections. Bests and worsts use completed runs with every split seen; averages also use sections seen in other runs.", MenuTheme.MUTED))

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
                run.total(SplitClock.SERVER)?.let { "(${time(it)})" } ?: "", "", (listOfNotNull(run.lagLost?.let { "lag ${formatSplitDelta(it)}" }) + notes).joinToString(" · "),
                if (run.total != null) MenuTheme.TEXT else MenuTheme.MUTED))
        }
    }

    private fun card(title: String, value: String, caption: String) = UIContainers.verticalFlow(Sizing.fill(32), Sizing.content()).apply {
        gap(2); padding(Insets.of(5)); surface(MenuTheme.panel(true))
        child(label(title, MenuTheme.MUTED))
        child(MenuLabel(Component.literal(value).withColor(MenuTheme.TEXT)).apply { fontSize = 14f; horizontalSizing(Sizing.fill()) })
        child(label(caption, MenuTheme.MUTED).apply { fontSize = 8f })
    }

    private fun tableRow(name: String, best: String, average: String, worst: String, from: String, color: Int) = row().apply {
        padding(Insets.of(2, 2, 4, 4))
        child(label(name, color).apply { horizontalSizing(Sizing.fill(27)) })
        child(label(best, color).apply { horizontalSizing(Sizing.fill(15)) })
        child(label(average, color).apply { horizontalSizing(Sizing.fill(16)) })
        child(label(worst, color).apply { horizontalSizing(Sizing.fill(14)) })
        child(label(from, MenuTheme.MUTED).apply { horizontalSizing(Sizing.fill(24)) })
    }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) =
        MenuButton(Component.literal(text), action).apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}

internal data class SplitHudRow(val name: String, val time: String, val server: String = "", val delta: String = "")

internal data class SplitHudView(val title: String, val rows: List<SplitHudRow>, val footer: List<String>)

/** What the HUD needs from the live run; [ticks] are server ticks since the same origin as [marks]. */
internal data class SplitHudRun(val marks: Map<SplitMilestone, Long>, val ticks: Map<SplitMilestone, Long>, val startObserved: Boolean,
                                val elapsed: Long, val elapsedTicks: Long?, val finished: Boolean, val estimate: Int?,
                                /** Steady live lag lost ([LagMeter]); a finished run uses its exact final splits instead. */
                                val lag: Long? = null,
                                /** [estimate] is Hypixel's own score from the boss. */
                                val scoreExact: Boolean = false)

/**
 * [comparison] null shows times only. [clock] is the time each row shows and compares; [server] adds the other clock in
 * brackets (real time when [clock] is server time) and the lag lost.
 */
internal data class SplitHudOptions(val comparison: SplitComparison? = SplitComparison.BEST, val upcoming: Boolean = true, val total: Boolean = true,
                                    val precise: Boolean = true, val server: Boolean = true, val clock: SplitClock = SplitClock.REAL,
                                    val pace: Boolean = false)

/** Real time of a span, then its server time; each is null when an end was not seen. [to] null means "until now". */
private fun span(run: SplitHudRun, from: SplitMilestone, to: SplitMilestone?): Pair<Long?, Long?> {
    fun origin(times: Map<SplitMilestone, Long>) = if (from == SplitMilestone.START) times[from]?.takeIf { run.startObserved && it == 0L } else times[from]
    val realFrom = origin(run.marks)
    val tickFrom = origin(run.ticks)
    val realTo = if (to == null) run.elapsed else run.marks[to]
    val tickTo = if (to == null) run.elapsedTicks else run.ticks[to]
    val real = if (realFrom != null && realTo != null && realTo >= realFrom) realTo - realFrom else null
    val server = if (tickFrom != null && tickTo != null && tickTo >= tickFrom) (tickTo - tickFrom) * TICK_MILLIS else null
    return real to server
}

/**
 * Estimated finish on [clock] if nothing goes wrong: the time so far plus each remaining section's average from
 * [averages] (the last 10 runs); the running section counts at least its time so far. Null without a start, before any
 * remaining section has an average, or once the run is over.
 */
internal fun splitPace(definition: SplitDefinition, run: SplitHudRun, averages: FloorSplitStats, clock: SplitClock): Long? {
    if (!run.startObserved || run.finished) return null
    fun pick(times: Pair<Long?, Long?>) = if (clock == SplitClock.REAL) times.first else times.second
    val latest = definition.chain.indexOfLast { it in run.marks || it == SplitMilestone.START }
    val elapsed = pick(span(run, SplitMilestone.START, null)) ?: return null
    val done = if (latest <= 0) 0L else pick(span(run, SplitMilestone.START, definition.chain[latest])) ?: return null
    var total = done
    definition.sections.forEachIndexed { index, _ ->
        val position = index + 1
        if (position <= latest) return@forEachIndexed
        val average = averages.sections[index].average?.toLong() ?: return null
        total += if (position == latest + 1) maxOf(average, elapsed - done) else average
    }
    return total
}

/**
 * The current run only: each finished section with its time on the chosen clock, the other clock in brackets (as
 * NoammAddons shows them) and its difference from the section's best, average or worst; the running section; and
 * optionally upcoming ones. Grouped sections (Terms) get a header row with their total and are indented beneath it.
 * History stays in the menu.
 */
internal fun splitHudView(definition: SplitDefinition, run: SplitHudRun, stats: FloorSplitStats?, options: SplitHudOptions,
                           paceStats: FloorSplitStats? = null): SplitHudView {
    fun time(value: Long?) = formatSplitTime(value, options.precise)
    fun pick(real: Long?, server: Long?, clock: SplitClock) = if (clock == SplitClock.REAL) real else server
    // Without server time (it stops after a reconnect), a row falls back to real time and is not compared.
    fun shown(real: Long?, server: Long?) = pick(real, server, options.clock) ?: real
    fun other(real: Long?, server: Long?): String {
        val value = pick(real, server, options.clock.other) ?: return ""
        if (!options.server || pick(real, server, options.clock) == null) return ""
        return "§7(${if (options.clock.other == SplitClock.SERVER) "§b" else "§f"}${time(value)}§7)"
    }
    fun compared(real: Long?, server: Long?) = pick(real, server, options.clock)
    val comparison = options.comparison
    fun delta(value: Long?, reference: Long?): String {
        if (comparison == null) return ""
        if (reference == null) return if (value != null) "§6new" else ""
        val difference = value?.let { it - reference } ?: return ""
        // Gold only for beating a best; against an average or worst, faster is simply green.
        val ahead = if (comparison == SplitComparison.BEST) "§6" else "§a"
        return "${when { difference < 0 -> ahead; difference == 0L -> "§a"; else -> "§c" }}${formatSplitDelta(difference)}"
    }
    fun pending(reference: Long?) = reference?.takeIf { comparison != null }?.let { "§8${time(it)}" } ?: ""
    fun SpanStats.target() = comparison?.let(::reference)
    val bests = stats?.sections?.associate { it.section.milestone to it.target() }.orEmpty()
    val groupBests = stats?.groups?.associate { it.group to it.target() }.orEmpty()
    val checkpointBests = stats?.checkpoints?.associate { it.milestone to it.target() }.orEmpty()
    val marks = run.marks
    val rows = ArrayList<SplitHudRow>()
    val latest = definition.chain.indexOfLast { it in marks || it == SplitMilestone.START && run.startObserved }
    // After joining late, sections that ended before the first seen milestone are simply not part of this view.
    val firstSeen = if (run.startObserved) 0 else definition.chain.indexOfFirst { it in marks }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    definition.sections.forEachIndexed { index, section ->
        val position = index + 1 // the section's end in the chain
        if (position < firstSeen) return@forEachIndexed
        val group = definition.groups.firstOrNull { definition.chain.indexOf(it.from) < position && position <= definition.chain.indexOf(it.to) }
        if (group != null && section.from == group.from) {
            val end = definition.chain.indexOf(group.to)
            val best = groupBests[group]
            val (real, srv) = span(run, group.from, group.to)
            when {
                group.to in marks && real != null -> SplitHudRow("§f${group.title}", "§f${time(shown(real, srv))}", other(real, srv), delta(compared(real, srv), best))
                latest >= end -> SplitHudRow("§7${group.title}", "§8—", "", "§8missed")
                group.from in marks && !run.finished -> span(run, group.from, null).let { (now, nowServer) ->
                    SplitHudRow("§e${group.title}", "§e${time(shown(now, nowServer))}", other(now, nowServer), pending(best))
                }
                options.upcoming && !run.finished -> SplitHudRow("§8${group.title}", "§8${best?.let(::time) ?: "—"}")
                else -> null
            }?.let(rows::add)
        }
        val name = (if (group != null) "  " else "") + definition.sectionTitle(section.milestone)
        val best = bests[section.milestone]
        val to = marks[section.milestone]
        val (real, srv) = span(run, section.from, section.milestone)
        when {
            to != null && real != null -> rows += SplitHudRow("§f$name", "§f${time(shown(real, srv))}", other(real, srv), delta(compared(real, srv), best))
            // Ended without a seen start, or skipped by a missed event: no duration exists for it.
            to != null || position <= latest -> rows += SplitHudRow("§7$name", "§8—", "", "§8missed")
            position == latest + 1 && !run.finished -> span(run, section.from, null).let { (now, nowServer) ->
                if (now != null) rows += SplitHudRow("§e$name", "§e${time(shown(now, nowServer))}", other(now, nowServer), pending(best))
            }
            options.upcoming && !run.finished -> rows += SplitHudRow("§8$name", "§8${best?.let(::time) ?: "—"}")
        }
        // A checkpoint (boss entry) follows its section with the time from the run start, counting up until it is reached.
        if (section.milestone in definition.checkpoints && run.startObserved) {
            val title = definition.title(section.milestone)
            val checkpointBest = checkpointBests[section.milestone]
            val (total, totalServer) = span(run, SplitMilestone.START, section.milestone)
            when {
                to != null && total != null ->
                    rows += SplitHudRow("§a$title", "§a${time(shown(total, totalServer))}", other(total, totalServer), delta(compared(total, totalServer), checkpointBest))
                to != null || position <= latest -> rows += SplitHudRow("§7$title", "§8—", "", "§8missed")
                !run.finished -> span(run, SplitMilestone.START, null).let { (now, nowServer) ->
                    if (now != null) rows += SplitHudRow("§e$title", "§e${time(shown(now, nowServer))}", other(now, nowServer), pending(checkpointBest))
                }
            }
        }
    }
    for (marker in definition.markers) {
        val bestMarker = stats?.markers?.firstOrNull { it.milestone == marker }?.target()
        val (real, srv) = if (marker in marks) span(run, SplitMilestone.START, marker) else null to null
        rows += when {
            real != null -> SplitHudRow("§b${definition.title(marker)}", "§f${time(shown(real, srv))}", other(real, srv), delta(compared(real, srv), bestMarker))
            marker in marks -> SplitHudRow("§b${definition.title(marker)}", "§7reached")
            marker == SplitMilestone.SCORE_300 && run.estimate != null && !run.finished -> SplitHudRow("§8${definition.title(marker)}", "§7${if (run.scoreExact) "score" else "est."} ${run.estimate}")
            else -> continue
        }
    }
    val footer = ArrayList<String>()
    if (options.total) {
        if (run.startObserved) {
            val (real, srv) = span(run, SplitMilestone.START, SplitMilestone.END.takeIf { run.finished })
            footer += "§7Run §f${time(shown(real, srv))} ${other(real, srv)}".trimEnd()
            // Real time keeps running through lag; server ticks do not. The difference is what lag cost this run.
            val lost = if (run.finished || run.lag == null) real?.let { r -> srv?.let { (r - it).coerceAtLeast(0) } } else run.lag
            if (options.server && srv != null && lost != null) {
                footer += "§7Lag lost ${if (lost >= 1_000) "§c" else "§7"}+${formatSplitTime(lost)}"
            }
        } else footer += "§7Run §8joined late · not timed"
        stats?.let {
            footer += when (comparison) {
                SplitComparison.AVERAGE -> "§7Average §f${time(it.averageTotal?.toLong())} §8· §7PB §f${time(it.actualPb?.duration)}"
                SplitComparison.WORST -> "§7Worst §f${time(it.worstTotal?.duration)} §8· §7PB §f${time(it.actualPb?.duration)}"
                else -> "§7PB §f${time(it.actualPb?.duration)} §8· §7Best possible §f${time(it.theoreticalPb)}"
            }
        }
    }
    if (options.pace && !run.finished && run.startObserved) {
        val pace = paceStats?.let { splitPace(definition, run, it, options.clock) }
        footer += "§7Pace §f${time(pace)} §8(est. · last 10 avg)"
    }
    return SplitHudView("§b§l${definition.floor} Splits", rows, footer)
}

object DungeonSplitsHud : SkyMyceModule() {
    private class Columns {
        val heading = hudColumn()
        val names = hudColumn()
        val times = hudColumn()
        val servers = hudColumn()
        val deltas = hudColumn()
        val footer = hudColumn()
        val panel = hudPanel(padding = 6, fade = { Settings.backgroundOpacity }).apply {
            child(heading)
            child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(6).apply {
                allowOverflow(true); children(listOf(names, times, servers, deltas))
            })
            child(footer)
        }
        fun show(view: SplitHudView) = panel.also {
            heading.updateTextLines(listOf(view.title))
            names.updateTextLines(view.rows.map { it.name })
            times.updateTextLines(view.rows.map { it.time })
            servers.updateTextLines(view.rows.map { it.server })
            deltas.updateTextLines(view.rows.map { it.delta })
            footer.updateTextLines(view.footer)
        }
    }
    private val live by lazy { Columns() }
    private val preview by lazy { Columns().show(previewView()) }

    private var statsKey: List<Any>? = null
    private var stats: FloorSplitStats? = null
    private var paceStats: FloorSplitStats? = null
    private var builtAt = 0L
    private var builtRevision = -1L
    private lateinit var clockKey: KeyMapping

    override fun init() {
        clockKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Toggle splits timer (real/server time)", GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC))
        ClientTickEvents.END_CLIENT_TICK.register {
            while (clockKey.consumeClick()) if (isEnabled() && Settings.enabled) toggleClock()
        }
    }

    /** Switches what the HUD shows and compares between real and server time, and remembers the choice. */
    fun toggleClock() {
        Settings.bestsClock = Settings.bestsClock.other
        SkyMyce.config.save()
        builtRevision = -1L
        displayModMessage("§7Splits timer: §b${Settings.bestsClock.title}")
    }

    val widget = Widget("dungeon_splits", "Dungeon Splits", 10, 140, previewBuilder = { preview }) {
        if (!isEnabled() || !Settings.enabled || !Settings.hud || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return@Widget null
        val run = DungeonSplits.live ?: return@Widget null
        val now = System.nanoTime()
        // Text only changes every tenth of a second; finished runs only when an event arrives.
        if (builtRevision == DungeonSplits.revision && (run.timeline.finished || now - builtAt < 100_000_000L)) return@Widget live.panel
        builtRevision = DungeonSplits.revision; builtAt = now
        val clock = Settings.bestsClock
        val key = listOf(DungeonSplitStore.revision, run.definition.floor, clock, Settings.averageWindow, Settings.pace)
        if (key != statsKey) {
            statsKey = key; stats = floorSplitStats(DungeonSplitStore.runs, run.definition, Settings.averageWindow, clock)
            paceStats = if (Settings.pace) floorSplitStats(DungeonSplitStore.runs, run.definition, SplitWindow.LAST_10, clock) else null
        }
        val timeline = run.timeline
        live.show(splitHudView(run.definition, SplitHudRun(timeline.marks, timeline.ticks, timeline.startObserved, timeline.elapsed(now),
            timeline.elapsedTicks(DungeonSplits.serverTicks), timeline.finished, run.estimate, run.lag.shown, run.scoreExact), stats,
            SplitHudOptions(Settings.comparison.target, Settings.showUpcoming, Settings.showTotal, Settings.precise, Settings.showServerTime, clock, Settings.pace),
            paceStats))
    }

    /** Fixed editor example; never written to history. */
    private fun previewView(): SplitHudView {
        val definition = splitDefinitions.getValue("M7")
        val marks = linkedMapOf(SplitMilestone.START to 0L, SplitMilestone.BLOOD_OPEN to 21_400L, SplitMilestone.BLOOD_CLEAR to 74_900L,
            SplitMilestone.BOSS_ENTRY to 101_300L, SplitMilestone.SCORE_300 to 118_200L, SplitMilestone.MAXOR to 129_800L,
            SplitMilestone.STORM to 168_100L, SplitMilestone.S1 to 182_600L, SplitMilestone.S2 to 196_300L)
        // Example lag: a little during Storm and the first terminals.
        val lag = mapOf(SplitMilestone.STORM to 600L, SplitMilestone.S1 to 1_150L, SplitMilestone.S2 to 1_400L)
        val ticks = marks.mapValues { (milestone, at) -> (at - (lag[milestone] ?: if (at > 30_000L) 200L else 0L)) / TICK_MILLIS }
        return splitHudView(definition, SplitHudRun(marks, ticks, true, 204_000L, (204_000L - 1_600L) / TICK_MILLIS, false, null), null,
            SplitHudOptions(comparison = null, clock = Settings.bestsClock))
    }
}
