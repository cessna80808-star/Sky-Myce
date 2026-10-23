package me.mycellium.skymyce.features.instances.dungeons.splits

import java.util.Locale

/**
 * Which clock a time is measured on. [REAL] is the stopwatch and includes lag; [SERVER] counts server ticks × 50 ms,
 * so lag does not add to it. The difference between them is the time lost to lag.
 */
enum class SplitClock(val title: String) {
    REAL("Real time"), SERVER("Server time");
    val other get() = if (this == REAL) SERVER else REAL
    override fun toString() = title
}

/** What live splits are compared with: the best, the average over the chosen runs, or the worst completed time. */
enum class SplitComparison(val title: String) {
    BEST("Best"), AVERAGE("Average"), WORST("Worst");
    override fun toString() = title
}

/**
 * How a saved run ended. Only [COMPLETED] runs with an observed start can supply an actual PB, a theoretical PB section
 * or an overall average; other runs keep their directly observed sections for section averages only.
 */
enum class SplitOutcome(val title: String) { COMPLETED("Completed"), FAILED("Failed"), ABANDONED("Left early") }

/**
 * One saved run. [marks] are milliseconds since the run's start when [startObserved], otherwise since the first
 * milestone observed after joining late; values are never filled in for milestones that were not seen.
 */
data class SplitRunRecord(
    val id: String,
    val floor: String,
    val definition: Int,
    val recordedAt: Long,
    val startObserved: Boolean,
    val marks: Map<SplitMilestone, Long>,
    val outcome: SplitOutcome,
    /** Conflicting or out-of-order events that were rejected; the run stays saved but is never eligible for PBs. */
    val conflicts: Int = 0,
    /** Server ticks since the same origin, for milestones where ticks were being counted. */
    val ticks: Map<SplitMilestone, Long> = emptyMap(),
) {
    val total: Long? get() = total(SplitClock.REAL)
    fun times(clock: SplitClock): Map<SplitMilestone, Long> =
        if (clock == SplitClock.REAL) marks else ticks.mapValues { it.value * TICK_MILLIS }
    fun total(clock: SplitClock): Long? {
        if (!startObserved || outcome != SplitOutcome.COMPLETED || conflicts != 0) return null
        val times = times(clock)
        return times[SplitMilestone.END]?.takeIf { clock == SplitClock.REAL || times[SplitMilestone.START] == 0L }
    }
    /** Real minus server time for the whole run: how much the run was slowed by lag. */
    val lagLost: Long? get() = total(SplitClock.SERVER)?.let { server -> total!! - server }
}

/** A section ending at [milestone], measured from the preceding milestone of the floor's chain. */
data class SplitSection(val milestone: SplitMilestone, val from: SplitMilestone)

/** Duration of every section both of whose boundaries were observed, in the order of the floor's chain. */
fun sectionDurations(record: SplitRunRecord, definition: SplitDefinition, clock: SplitClock = SplitClock.REAL): Map<SplitMilestone, Long> {
    if (record.definition != definition.version || record.floor != definition.floor) return emptyMap()
    val result = LinkedHashMap<SplitMilestone, Long>()
    for (section in definition.sections) spanDuration(record, section.from, section.milestone, clock)?.let { result[section.milestone] = it }
    return result
}

/** A run that may supply section PBs and the theoretical PB: completed, started in view, conflict-free and fully split. */
fun splitRunEligible(record: SplitRunRecord, definition: SplitDefinition, clock: SplitClock = SplitClock.REAL): Boolean =
    record.total(clock) != null && record.definition == definition.version && record.floor == definition.floor &&
        sectionDurations(record, definition, clock).size == definition.sections.size

enum class SplitWindow(val title: String, val runs: Int?) {
    ALL("All time", null), LAST_10("Last 10", 10), LAST_25("Last 25", 25), LAST_50("Last 50", 50);
    override fun toString() = title
}

data class SectionBest(val duration: Long, val run: String, val recordedAt: Long)

/** Best, average and worst of one timed span; [worst] comes from the same runs as [best]. */
interface SpanStats {
    val best: SectionBest?
    val average: Double?
    val worst: SectionBest?
    /** The time [comparison] measures against, or null when there is none yet. */
    fun reference(comparison: SplitComparison): Long? = when (comparison) {
        SplitComparison.BEST -> best?.duration
        SplitComparison.AVERAGE -> average?.toLong()
        SplitComparison.WORST -> worst?.duration
    }
}

data class SectionStats(
    val section: SplitSection,
    override val best: SectionBest?,
    override val average: Double?,
    val samples: Int,
    override val worst: SectionBest? = null,
) : SpanStats

/** A marker such as 300 score, or a checkpoint such as boss entry, timed from the run start and kept out of the theoretical total. */
data class MarkerStats(val milestone: SplitMilestone, override val best: SectionBest?, override val average: Double?, val samples: Int,
                       override val worst: SectionBest? = null) : SpanStats

/** A block of sections (Terms) timed within single runs; its best is a real run's time, not a sum of section bests. */
data class GroupStats(val group: SplitGroup, override val best: SectionBest?, override val average: Double?, val samples: Int,
                      override val worst: SectionBest? = null) : SpanStats

data class FloorSplitStats(
    val definition: SplitDefinition,
    val clock: SplitClock,
    val markers: List<MarkerStats>,
    val actualPb: SectionBest?,
    /**
     * The best checkpoint (boss entry) plus the best of each section after it; null when any of those has no best, as a
     * missing section is never counted as zero.
     */
    val theoreticalPb: Long?,
    val sections: List<SectionStats>,
    val groups: List<GroupStats>,
    val averageTotal: Double?,
    val averageSamples: Int,
    val completedRuns: Int,
    val savedRuns: Int,
    /** Average real-minus-server time over the window's completed runs that have both clocks. */
    val averageLag: Double?,
    /** Checkpoints such as boss entry, timed from the start. */
    val checkpoints: List<MarkerStats> = emptyList(),
    /** The slowest completed run, from the same runs as [actualPb]. */
    val worstTotal: SectionBest? = null,
) {
    /** The whole run's time [comparison] measures against. */
    fun totalReference(comparison: SplitComparison): Long? = when (comparison) {
        SplitComparison.BEST -> actualPb?.duration
        SplitComparison.AVERAGE -> averageTotal?.toLong()
        SplitComparison.WORST -> worstTotal?.duration
    }
}

/** Time from [from] to [to] in one run on [clock], when both were observed. */
internal fun spanDuration(record: SplitRunRecord, from: SplitMilestone, to: SplitMilestone, clock: SplitClock): Long? {
    val times = record.times(clock)
    val start = if (from == SplitMilestone.START) times[from]?.takeIf { record.startObserved && it == 0L } else times[from]
    val end = times[to]
    return if (start == null || end == null || end < start) null else end - start
}

/**
 * Actual PB, per-section PBs with their source runs, the theoretical PB and windowed averages for one floor, all on
 * one [clock]. [runs] may contain any floor and definition; only matching records are used.
 */
fun floorSplitStats(runs: List<SplitRunRecord>, definition: SplitDefinition, window: SplitWindow,
                    clock: SplitClock = SplitClock.REAL): FloorSplitStats {
    val floorRuns = runs.filter { it.floor == definition.floor && it.definition == definition.version }.sortedByDescending { it.recordedAt }
    val completed = floorRuns.filter { it.total(clock) != null }
    val eligible = floorRuns.filter { splitRunEligible(it, definition, clock) }
    val actual = completed.minWithOrNull(compareBy<SplitRunRecord> { it.total(clock)!! }.thenBy { it.recordedAt })
        ?.let { SectionBest(it.total(clock)!!, it.id, it.recordedAt) }
    val worstRun = completed.maxWithOrNull(compareBy<SplitRunRecord> { it.total(clock)!! }.thenBy { -it.recordedAt })
        ?.let { SectionBest(it.total(clock)!!, it.id, it.recordedAt) }
    val durations = floorRuns.associate { it.id to sectionDurations(it, definition, clock) }
    fun windowed(values: List<Long>) = window.runs?.let { values.take(it) } ?: values
    fun fastest(values: List<SectionBest>) = values.minWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { it.recordedAt })
    // The earliest of equally slow times, as for the fastest.
    fun slowest(values: List<SectionBest>) = values.maxWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { -it.recordedAt })
    val sections = definition.sections.map { section ->
        val timed = eligible.mapNotNull { run -> durations.getValue(run.id)[section.milestone]?.let { SectionBest(it, run.id, run.recordedAt) } }
        // Conflicting runs never contribute; partial runs only contribute sections they observed directly.
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { durations.getValue(it.id)[section.milestone] })
        SectionStats(section, fastest(timed), observed.takeIf { it.isNotEmpty() }?.average(), observed.size, slowest(timed))
    }
    val groups = definition.groups.map { group ->
        val timed = eligible.mapNotNull { run -> spanDuration(run, group.from, group.to, clock)?.let { SectionBest(it, run.id, run.recordedAt) } }
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { spanDuration(it, group.from, group.to, clock) })
        GroupStats(group, fastest(timed), observed.takeIf { it.isNotEmpty() }?.average(), observed.size, slowest(timed))
    }
    val totals = windowed(completed.map { it.total(clock)!! })
    // Time from the observed start; the run need not finish for the moment it reached 300 to be real.
    fun fromStart(milestone: SplitMilestone): MarkerStats {
        val observed = floorRuns.filter { it.startObserved && it.conflicts == 0 }
            .mapNotNull { run -> spanDuration(run, SplitMilestone.START, milestone, clock)?.let { SectionBest(it, run.id, run.recordedAt) } }
        val window = windowed(observed.map { it.duration })
        return MarkerStats(milestone, fastest(observed), window.takeIf { it.isNotEmpty() }?.average(), window.size, slowest(observed))
    }
    val markers = definition.markers.map(::fromStart)
    val checkpoints = definition.checkpoints.map(::fromStart)
    // The clear's fastest sections rarely happen in one run, so the best boss entry, a real run's time, stands in for
    // every section before it.
    val cut = checkpoints.filter { it.best != null }.maxByOrNull { definition.chain.indexOf(it.milestone) }
    val after = cut?.let { c -> sections.filter { definition.chain.indexOf(it.section.milestone) > definition.chain.indexOf(c.milestone) } } ?: sections
    val theoretical = after.takeIf { list -> (cut != null || list.isNotEmpty()) && list.all { it.best != null } }
        ?.sumOf { it.best!!.duration }?.plus(cut?.best?.duration ?: 0L)
    val lag = windowed(floorRuns.mapNotNull { it.lagLost })
    return FloorSplitStats(definition, clock, markers, actual, theoretical, sections, groups, totals.takeIf { it.isNotEmpty() }?.average(),
        totals.size, completed.size, floorRuns.size, lag.takeIf { it.isNotEmpty() }?.average(), checkpoints, worstRun)
}

/** The chat line for an ended run: real time, server time and the lag between them; null when the start was missed. */
internal fun splitRunSummary(record: SplitRunRecord): String? {
    if (!record.startObserved) return null
    val real = record.marks[SplitMilestone.END] ?: return null
    val ticks = record.times(SplitClock.SERVER)
    val server = ticks[SplitMilestone.END]?.takeIf { ticks[SplitMilestone.START] == 0L }
        ?: return "§b${record.floor} §7run §f${formatSplitTime(real)} §8· §7server time unavailable"
    val lag = (real - server).coerceAtLeast(0)
    return "§b${record.floor} §7run §f${formatSplitTime(real)} §8· §7server §b${formatSplitTime(server)} §8· §7lag ${if (lag >= 1_000) "§c" else "§7"}+${formatSplitTime(lag)}"
}

fun formatSplitTime(millis: Long?, precise: Boolean = true): String {
    if (millis == null || millis < 0) return "—"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (millis % 1000) / 100
    val hundredths = (millis % 1000) / 10
    return when {
        minutes > 0 && precise -> String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths)
        minutes > 0 -> String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        precise -> String.format(Locale.ROOT, "%d.%02d", seconds, hundredths)
        else -> "${seconds}s"
    }
}

fun formatSplitDelta(millis: Long?): String {
    if (millis == null) return ""
    val sign = if (millis < 0) "-" else "+"
    val magnitude = kotlin.math.abs(millis)
    return sign + if (magnitude >= 60_000) formatSplitTime(magnitude) else String.format(Locale.ROOT, "%d.%02d", magnitude / 1000, (magnitude % 1000) / 10)
}
