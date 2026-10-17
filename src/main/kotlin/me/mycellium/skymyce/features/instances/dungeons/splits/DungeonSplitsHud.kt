package me.mycellium.skymyce.features.instances.dungeons.splits

import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig as Settings
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

internal data class SplitHudRow(val name: String, val time: String, val server: String = "", val delta: String = "")

internal data class SplitHudView(val title: String, val rows: List<SplitHudRow>, val footer: List<String>)

/** What the HUD needs from the live run; [ticks] are server ticks since the same origin as [marks]. */
internal data class SplitHudRun(val marks: Map<SplitMilestone, Long>, val ticks: Map<SplitMilestone, Long>, val startObserved: Boolean,
                                val elapsed: Long, val elapsedTicks: Long?, val finished: Boolean, val estimate: Int?)

internal data class SplitHudOptions(val compare: Boolean = true, val upcoming: Boolean = true, val total: Boolean = true,
                                    val precise: Boolean = true, val server: Boolean = true, val clock: SplitClock = SplitClock.REAL)

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
 * The current run only: each finished section with its real time, its server time in brackets (as NoammAddons shows
 * them) and its difference from the section best; the running section; and optionally upcoming ones. Grouped sections
 * (Terms) get a header row with their total and are indented beneath it. History and averages stay in the menu.
 */
internal fun splitHudView(definition: SplitDefinition, run: SplitHudRun, stats: FloorSplitStats?, options: SplitHudOptions): SplitHudView {
    fun time(value: Long?) = formatSplitTime(value, options.precise)
    fun server(value: Long?) = if (!options.server || value == null) "" else "§7(§b${time(value)}§7)"
    fun compared(real: Long?, server: Long?) = if (options.clock == SplitClock.REAL) real else server
    fun delta(value: Long?, best: SectionBest?): String {
        if (!options.compare) return ""
        if (best == null) return if (value != null) "§6new" else ""
        val difference = value?.let { it - best.duration } ?: return ""
        return "${when { difference < 0 -> "§6"; difference == 0L -> "§a"; else -> "§c" }}${formatSplitDelta(difference)}"
    }
    fun pending(best: SectionBest?) = best?.takeIf { options.compare }?.let { "§8${time(it.duration)}" } ?: ""
    val bests = stats?.sections?.associate { it.section.milestone to it.best }.orEmpty()
    val groupBests = stats?.groups?.associate { it.group to it.best }.orEmpty()
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
                group.to in marks && real != null -> SplitHudRow("§f${group.title}", "§f${time(real)}", server(srv), delta(compared(real, srv), best))
                latest >= end -> SplitHudRow("§7${group.title}", "§8—", "", "§8missed")
                group.from in marks && !run.finished -> span(run, group.from, null).let { (now, nowServer) ->
                    SplitHudRow("§e${group.title}", "§e${time(now)}", server(nowServer), pending(best))
                }
                options.upcoming && !run.finished -> SplitHudRow("§8${group.title}", "§8${best?.takeIf { options.compare }?.let { time(it.duration) } ?: "—"}")
                else -> null
            }?.let(rows::add)
        }
        val name = (if (group != null) "  " else "") + definition.title(section.milestone)
        val best = bests[section.milestone]
        val to = marks[section.milestone]
        val (real, srv) = span(run, section.from, section.milestone)
        when {
            to != null && real != null -> rows += SplitHudRow("§f$name", "§f${time(real)}", server(srv), delta(compared(real, srv), best))
            // Ended without a seen start, or skipped by a missed event: no duration exists for it.
            to != null || position <= latest -> rows += SplitHudRow("§7$name", "§8—", "", "§8missed")
            position == latest + 1 && !run.finished -> span(run, section.from, null).let { (now, nowServer) ->
                if (now != null) rows += SplitHudRow("§e$name", "§e${time(now)}", server(nowServer), pending(best))
            }
            options.upcoming && !run.finished -> rows += SplitHudRow("§8$name", "§8${best?.takeIf { options.compare }?.let { time(it.duration) } ?: "—"}")
        }
    }
    for (marker in definition.markers) {
        val bestMarker = stats?.markers?.firstOrNull { it.milestone == marker }?.best
        val (real, srv) = if (marker in marks) span(run, SplitMilestone.START, marker) else null to null
        rows += when {
            real != null -> SplitHudRow("§b${definition.title(marker)}", "§f${time(real)}", server(srv), delta(compared(real, srv), bestMarker))
            marker in marks -> SplitHudRow("§b${definition.title(marker)}", "§7reached")
            marker == SplitMilestone.SCORE_300 && run.estimate != null && !run.finished -> SplitHudRow("§8${definition.title(marker)}", "§7est. ${run.estimate}")
            else -> continue
        }
    }
    val footer = ArrayList<String>()
    if (options.total) {
        if (run.startObserved) {
            val (real, srv) = span(run, SplitMilestone.START, SplitMilestone.END.takeIf { run.finished })
            footer += "§7Run §f${time(real)} ${server(srv)}".trimEnd()
            // Real time keeps running through lag; server ticks do not. The difference is what lag cost this run.
            if (options.server && real != null && srv != null) {
                val lost = (real - srv).coerceAtLeast(0)
                footer += "§7Lag lost ${if (lost >= 1_000) "§c" else "§7"}+${formatSplitTime(lost)}"
            }
        } else footer += "§7Run §8joined late · not timed"
        stats?.let { footer += "§7PB §f${time(it.actualPb?.duration)} §8· §7Best possible §f${time(it.theoreticalPb)}" }
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

    private var statsKey: Triple<Long, String, SplitClock>? = null
    private var stats: FloorSplitStats? = null
    private var builtAt = 0L
    private var builtRevision = -1L

    val widget = Widget("dungeon_splits", "Dungeon Splits", 10, 140, previewBuilder = { preview }) {
        if (!isEnabled() || !Settings.enabled || !Settings.hud || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return@Widget null
        val run = DungeonSplits.live ?: return@Widget null
        val now = System.nanoTime()
        // Text only changes every tenth of a second; finished runs only when an event arrives.
        if (builtRevision == DungeonSplits.revision && (run.timeline.finished || now - builtAt < 100_000_000L)) return@Widget live.panel
        builtRevision = DungeonSplits.revision; builtAt = now
        val clock = Settings.bestsClock
        val key = Triple(DungeonSplitStore.revision, run.definition.floor, clock)
        if (key != statsKey) { statsKey = key; stats = floorSplitStats(DungeonSplitStore.runs, run.definition, SplitWindow.ALL, clock) }
        val timeline = run.timeline
        live.show(splitHudView(run.definition, SplitHudRun(timeline.marks, timeline.ticks, timeline.startObserved, timeline.elapsed(now),
            timeline.elapsedTicks(DungeonSplits.serverTicks), timeline.finished, run.estimate), stats,
            SplitHudOptions(Settings.comparison == Settings.Comparison.SECTION_PB, Settings.showUpcoming, Settings.showTotal, Settings.precise,
                Settings.showServerTime, clock)))
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
            SplitHudOptions(compare = false))
    }
}
