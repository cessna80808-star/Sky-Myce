package me.mycellium.skymyce.features.instances.dungeons.splits

import java.util.Locale

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
) {
    val total: Long? get() = marks[SplitMilestone.END]?.takeIf { startObserved && outcome == SplitOutcome.COMPLETED && conflicts == 0 }
}

/** A section ending at [milestone], measured from the preceding milestone of the floor's chain. */
data class SplitSection(val milestone: SplitMilestone, val from: SplitMilestone)

/** Duration of every section both of whose boundaries were observed, in the order of the floor's chain. */
fun sectionDurations(record: SplitRunRecord, definition: SplitDefinition): Map<SplitMilestone, Long> {
    if (record.definition != definition.version || record.floor != definition.floor) return emptyMap()
    val result = LinkedHashMap<SplitMilestone, Long>()
    for (section in definition.sections) {
        val from = if (section.from == SplitMilestone.START) { if (record.startObserved) 0L else null } else record.marks[section.from]
        val to = record.marks[section.milestone]
        if (from == null || to == null || to < from) continue
        result[section.milestone] = to - from
    }
    return result
}

/** A run that may supply section PBs and the theoretical PB: completed, started in view, conflict-free and fully split. */
fun splitRunEligible(record: SplitRunRecord, definition: SplitDefinition): Boolean =
    record.total != null && record.definition == definition.version && record.floor == definition.floor &&
        definition.sections.all { it.milestone in record.marks } && sectionDurations(record, definition).size == definition.sections.size

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

data class FloorSplitStats(
    val definition: SplitDefinition,
    val markers: List<MarkerStats>,
    val actualPb: SectionBest?,
    /** Null when any section has no eligible best; a missing section is never counted as zero. */
    val theoreticalPb: Long?,
    val sections: List<SectionStats>,
    val averageTotal: Double?,
    val averageSamples: Int,
    val completedRuns: Int,
    val savedRuns: Int,
)

/**
 * Actual PB, per-section PBs with their source runs, the theoretical PB and windowed averages for one floor.
 * [runs] may contain any floor and definition; only matching records are used.
 */
fun floorSplitStats(runs: List<SplitRunRecord>, definition: SplitDefinition, window: SplitWindow): FloorSplitStats {
    val floorRuns = runs.filter { it.floor == definition.floor && it.definition == definition.version }.sortedByDescending { it.recordedAt }
    val completed = floorRuns.filter { it.total != null }
    val eligible = floorRuns.filter { splitRunEligible(it, definition) }
    val actual = completed.minWithOrNull(compareBy<SplitRunRecord> { it.total!! }.thenBy { it.recordedAt })
        ?.let { SectionBest(it.total!!, it.id, it.recordedAt) }
    val durations = floorRuns.associate { it.id to sectionDurations(it, definition) }
    fun windowed(values: List<Long>) = window.runs?.let { values.take(it) } ?: values
    val sections = definition.sections.map { section ->
        val best = eligible.mapNotNull { run -> durations.getValue(run.id)[section.milestone]?.let { SectionBest(it, run.id, run.recordedAt) } }
            .minWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { it.recordedAt })
        // Conflicting runs never contribute; partial runs only contribute sections they observed directly.
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { durations.getValue(it.id)[section.milestone] })
        SectionStats(section, best, observed.takeIf { it.isNotEmpty() }?.average(), observed.size)
    }
    val theoretical = sections.takeIf { list -> list.isNotEmpty() && list.all { it.best != null } }?.sumOf { it.best!!.duration }
    val totals = windowed(completed.map { it.total!! })
    // Time from the observed start; the run need not finish for the moment it reached 300 to be real.
    val markers = definition.markers.map { marker ->
        val observed = floorRuns.filter { it.startObserved && it.conflicts == 0 }.mapNotNull { run -> run.marks[marker]?.let { SectionBest(it, run.id, run.recordedAt) } }
        val best = observed.minWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { it.recordedAt })
        val window = windowed(observed.map { it.duration })
        MarkerStats(marker, best, window.takeIf { it.isNotEmpty() }?.average(), window.size)
    }
    return FloorSplitStats(definition, markers, actual, theoretical, sections, totals.takeIf { it.isNotEmpty() }?.average(), totals.size,
        completed.size, floorRuns.size)
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
