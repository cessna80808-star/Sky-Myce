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

internal data class SplitHudRow(val name: String, val time: String, val delta: String = "")

internal data class SplitHudView(val title: String, val rows: List<SplitHudRow>, val footer: List<String>)

/**
 * The current run only: finished sections with their difference from the section best, the running section, and
 * optionally upcoming ones. History and averages stay in the statistics menu.
 */
internal fun splitHudView(definition: SplitDefinition, marks: Map<SplitMilestone, Long>, startObserved: Boolean, elapsed: Long,
                          stats: FloorSplitStats?, finished: Boolean, estimate: Int?, compare: Boolean, upcoming: Boolean,
                          total: Boolean, precise: Boolean): SplitHudView {
    fun time(value: Long?) = formatSplitTime(value, precise)
    val bests = stats?.sections?.associate { it.section.milestone to it.best }.orEmpty()
    val rows = ArrayList<SplitHudRow>()
    val latest = definition.chain.indexOfLast { it in marks || it == SplitMilestone.START && startObserved }
    // After joining late, sections that ended before the first seen milestone are simply not part of this view.
    val firstSeen = if (startObserved) 0 else definition.chain.indexOfFirst { it in marks }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    definition.sections.forEachIndexed { index, section ->
        if (index + 1 < firstSeen) return@forEachIndexed
        val name = definition.title(section.milestone)
        val from = if (section.from == SplitMilestone.START) 0L.takeIf { startObserved } else marks[section.from]
        val to = marks[section.milestone]
        val best = bests[section.milestone]
        val position = index + 1 // the section's end in the chain
        when {
            to != null && from != null -> {
                val duration = to - from
                val delta = if (compare) sectionDelta(duration, best) else null
                val color = when { delta == null -> "§7"; delta < 0 -> "§6"; delta == 0L -> "§a"; else -> "§c" }
                rows += SplitHudRow("§f$name", "§f${time(duration)}", delta?.let { "$color${formatSplitDelta(it)}" } ?: if (compare && best == null) "§6new" else "")
            }
            // Ended without a seen start, or skipped by a missed event: no duration exists for it.
            to != null || position <= latest -> rows += SplitHudRow("§7$name", "§8—", "§8missed")
            position == latest + 1 && from != null && !finished -> rows += SplitHudRow("§e$name", "§e${time((elapsed - from).coerceAtLeast(0))}",
                best?.takeIf { compare }?.let { "§8${time(it.duration)}" } ?: "")
            upcoming && !finished -> rows += SplitHudRow("§8$name", "§8${best?.takeIf { compare }?.let { time(it.duration) } ?: "—"}")
        }
    }
    for (marker in definition.markers) {
        val at = marks[marker]
        val bestMarker = stats?.markers?.firstOrNull { it.milestone == marker }?.best
        rows += when {
            at != null && startObserved -> SplitHudRow("§b${definition.title(marker)}", "§f${time(at)}",
                if (compare) bestMarker?.let { b -> (at - b.duration).let { d -> "${if (d < 0) "§6" else if (d == 0L) "§a" else "§c"}${formatSplitDelta(d)}" } } ?: "§6new" else "")
            at != null -> SplitHudRow("§b${definition.title(marker)}", "§7reached")
            marker == SplitMilestone.SCORE_300 && estimate != null && !finished -> SplitHudRow("§8${definition.title(marker)}", "§7est. $estimate")
            else -> continue
        }
    }
    val footer = if (!total) emptyList() else listOfNotNull(
        if (startObserved) "§7Run §f${time(if (finished) marks[SplitMilestone.END] ?: elapsed else elapsed)}" else "§7Run §8joined late · not timed",
        stats?.let { "§7PB §f${time(it.actualPb?.duration)} §8· §7Best possible §f${time(it.theoreticalPb)}" },
    )
    return SplitHudView("§b§l${definition.floor} Splits", rows, footer)
}

object DungeonSplitsHud : SkyMyceModule() {
    private val heading by lazy { hudColumn() }
    private val names by lazy { hudColumn() }
    private val times by lazy { hudColumn() }
    private val deltas by lazy { hudColumn() }
    private val footer by lazy { hudColumn() }
    private fun panel() = hudPanel(padding = 6).apply {
        child(heading)
        child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(8).apply { allowOverflow(true); children(listOf(names, times, deltas)) })
        child(footer)
    }
    private val content by lazy { panel() }
    private val previewTitle by lazy { hudColumn() }
    private val previewNames by lazy { hudColumn() }
    private val previewTimes by lazy { hudColumn() }
    private val previewDeltas by lazy { hudColumn() }
    private val previewFooter by lazy { hudColumn() }
    private val preview by lazy {
        val example = previewView()
        hudPanel(padding = 6).apply {
            child(previewTitle.updateTextLines(listOf(example.title)))
            child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(8).apply {
                allowOverflow(true)
                children(listOf(previewNames.updateTextLines(example.rows.map { it.name }), previewTimes.updateTextLines(example.rows.map { it.time }),
                    previewDeltas.updateTextLines(example.rows.map { it.delta })))
            })
            child(previewFooter.updateTextLines(example.footer))
        }
    }

    private var statsKey: Pair<Long, String>? = null
    private var stats: FloorSplitStats? = null
    private var builtAt = 0L
    private var builtRevision = -1L

    val widget = Widget("dungeon_splits", "Dungeon Splits", 10, 140, previewBuilder = { preview }) {
        if (!isEnabled() || !Settings.enabled || !Settings.hud || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return@Widget null
        val run = DungeonSplits.live ?: return@Widget null
        val now = System.nanoTime()
        // Text only changes every tenth of a second; finished runs only when an event arrives.
        if (builtRevision == DungeonSplits.revision && (run.timeline.finished || now - builtAt < 100_000_000L)) return@Widget content
        builtRevision = DungeonSplits.revision; builtAt = now
        val key = DungeonSplitStore.revision to run.definition.floor
        if (key != statsKey) { statsKey = key; stats = floorSplitStats(DungeonSplitStore.runs, run.definition, SplitWindow.ALL) }
        val view = splitHudView(run.definition, run.timeline.marks, run.timeline.startObserved, run.timeline.elapsed(now), stats,
            run.timeline.finished, run.estimate, Settings.comparison == Settings.Comparison.SECTION_PB, Settings.showUpcoming,
            Settings.showTotal, Settings.precise)
        heading.updateTextLines(listOf(view.title))
        names.updateTextLines(view.rows.map { it.name })
        times.updateTextLines(view.rows.map { it.time })
        deltas.updateTextLines(view.rows.map { it.delta })
        footer.updateTextLines(view.footer)
        content
    }

    /** Fixed editor example; never written to history. */
    private fun previewView(): SplitHudView {
        val definition = splitDefinitions.getValue("M7")
        val marks = mapOf(SplitMilestone.START to 0L, SplitMilestone.BLOOD_OPEN to 21_400L, SplitMilestone.BLOOD_CLEAR to 74_900L,
            SplitMilestone.SCORE_300 to 118_200L, SplitMilestone.BOSS_ENTRY to 101_300L, SplitMilestone.MAXOR to 129_800L,
            SplitMilestone.STORM to 168_100L, SplitMilestone.S1 to 182_600L)
        return splitHudView(definition, marks, true, 191_000L, null, false, null, false, true, true, true)
    }
}
