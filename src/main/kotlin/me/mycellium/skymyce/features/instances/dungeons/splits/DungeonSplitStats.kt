package me.mycellium.skymyce.features.instances.dungeons.splits

import java.util.Locale

/**
 * How a saved run ended. Only [COMPLETED] runs with an observed start can supply an actual PB, a theoretical PB section
 * or an overall average; other runs keep their directly observed sections for section averages only.
 */
/**
 * Which clock a time is measured on. [REAL] is the stopwatch and includes lag; [SERVER] counts server ticks × 50 ms,
 * so lag does not add to it. The difference between them is the time lost to lag.
 */
enum class SplitClock(val title: String) { REAL("Real time"), SERVER("Server time") }

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
    ALL("All time", null), LAST_10("Last 10", 10), LAST_25("Last 25", 25), LAST_50("Last 50", 50)
}

data class SectionBest(val duration: Long, val run: String, val recordedAt: Long)

data class SectionStats(
    val section: SplitSection,
    val best: SectionBest?,
    val average: Double?,
    val samples: Int,
)

/** A marker such as 300 score, timed from the run start and kept out of the theoretical total. */
data class MarkerStats(val milestone: SplitMilestone, val best: SectionBest?, val average: Double?, val samples: Int)

/** A block of sections (Terms) timed within single runs; its best is a real run's time, not a sum of section bests. */
data class GroupStats(val group: SplitGroup, val best: SectionBest?, val average: Double?, val samples: Int)

data class FloorSplitStats(
    val definition: SplitDefinition,
    val clock: SplitClock,
    val markers: List<MarkerStats>,
    val actualPb: SectionBest?,
    /** Null when any section has no eligible best; a missing section is never counted as zero. */
    val theoreticalPb: Long?,
    val sections: List<SectionStats>,
    val groups: List<GroupStats>,
    val averageTotal: Double?,
    val averageSamples: Int,
    val completedRuns: Int,
    val savedRuns: Int,
    /** Average real-minus-server time over the window's completed runs that have both clocks. */
    val averageLag: Double?,
)

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
    val durations = floorRuns.associate { it.id to sectionDurations(it, definition, clock) }
    fun windowed(values: List<Long>) = window.runs?.let { values.take(it) } ?: values
    fun fastest(values: List<SectionBest>) = values.minWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { it.recordedAt })
    val sections = definition.sections.map { section ->
        val best = fastest(eligible.mapNotNull { run -> durations.getValue(run.id)[section.milestone]?.let { SectionBest(it, run.id, run.recordedAt) } })
        // Conflicting runs never contribute; partial runs only contribute sections they observed directly.
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { durations.getValue(it.id)[section.milestone] })
        SectionStats(section, best, observed.takeIf { it.isNotEmpty() }?.average(), observed.size)
    }
    val theoretical = sections.takeIf { list -> list.isNotEmpty() && list.all { it.best != null } }?.sumOf { it.best!!.duration }
    val groups = definition.groups.map { group ->
        val best = fastest(eligible.mapNotNull { run -> spanDuration(run, group.from, group.to, clock)?.let { SectionBest(it, run.id, run.recordedAt) } })
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { spanDuration(it, group.from, group.to, clock) })
        GroupStats(group, best, observed.takeIf { it.isNotEmpty() }?.average(), observed.size)
    }
    val totals = windowed(completed.map { it.total(clock)!! })
    // Time from the observed start; the run need not finish for the moment it reached 300 to be real.
    val markers = definition.markers.map { marker ->
        val observed = floorRuns.filter { it.startObserved && it.conflicts == 0 }
            .mapNotNull { run -> spanDuration(run, SplitMilestone.START, marker, clock)?.let { SectionBest(it, run.id, run.recordedAt) } }
        val window = windowed(observed.map { it.duration })
        MarkerStats(marker, fastest(observed), window.takeIf { it.isNotEmpty() }?.average(), window.size)
    }
    val lag = windowed(floorRuns.mapNotNull { it.lagLost })
    return FloorSplitStats(definition, clock, markers, actual, theoretical, sections, groups, totals.takeIf { it.isNotEmpty() }?.average(),
        totals.size, completed.size, floorRuns.size, lag.takeIf { it.isNotEmpty() }?.average())
}

/** Live comparison against the saved section PB; null when no comparable best exists yet. */
fun sectionDelta(duration: Long, best: SectionBest?): Long? = best?.let { duration - it.duration }

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
