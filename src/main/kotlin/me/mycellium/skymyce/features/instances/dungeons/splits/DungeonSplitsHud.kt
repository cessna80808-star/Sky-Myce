package me.mycellium.skymyce.features.instances.dungeons.splits

import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig as Settings
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

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
 * The current run only: each finished section with its time on the chosen clock, the other clock in brackets (as
 * NoammAddons shows them) and its difference from the section's best, average or worst; the running section; and
 * optionally upcoming ones. Grouped sections (Terms) get a header row with their total and are indented beneath it.
 * History stays in the menu.
 */
internal fun splitHudView(definition: SplitDefinition, run: SplitHudRun, stats: FloorSplitStats?, options: SplitHudOptions): SplitHudView {
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
        val key = listOf(DungeonSplitStore.revision, run.definition.floor, clock, Settings.averageWindow)
        if (key != statsKey) { statsKey = key; stats = floorSplitStats(DungeonSplitStore.runs, run.definition, Settings.averageWindow, clock) }
        val timeline = run.timeline
        live.show(splitHudView(run.definition, SplitHudRun(timeline.marks, timeline.ticks, timeline.startObserved, timeline.elapsed(now),
            timeline.elapsedTicks(DungeonSplits.serverTicks), timeline.finished, run.estimate, run.lag.shown, run.scoreExact), stats,
            SplitHudOptions(Settings.comparison.target, Settings.showUpcoming, Settings.showTotal, Settings.precise, Settings.showServerTime, clock)))
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
