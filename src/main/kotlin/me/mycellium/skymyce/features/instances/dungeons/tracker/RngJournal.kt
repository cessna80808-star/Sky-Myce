package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonElement
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class JournalPeriod(val title: String) { ALL_TIME("All time"), WEEK("This week UTC"), MONTH("This month UTC"), CUSTOM("Custom UTC dates") }
data class JournalFilter(val period: JournalPeriod = JournalPeriod.ALL_TIME, val scope: TrackerScope = TrackerScope.CURRENT_PROFILE,
    val query: String = "", val activity: String? = null, val floor: String? = null, val from: String = "", val until: String = "",
    val oldestFirst: Boolean = false, val includeLegacy: Boolean = false)
data class JournalBar(val date: LocalDate, val count: Long)
data class JournalRunComparison(val run: String, val floor: String?, val completedAt: Long?, val duration: Long?,
    val dropCount: Long, val knownValue: Double?, val unpricedReports: Int, val estimatedProfit: Double?)
data class JournalView(val events: List<AcquisitionEvent> = emptyList(), val bars: List<JournalBar> = emptyList(),
    val counts: Map<String, Long> = emptyMap(), val runCounts: Map<String, Long> = emptyMap(), val knownValue: Double = 0.0,
    val unpriced: Int = 0, val observedRuns: Int = 0, val legacyReports: Int = 0,
    val rarityCounts: Map<String, Long> = emptyMap(), val runComparisons: List<JournalRunComparison> = emptyList())

fun recentPersonalEvents(archive: AcquisitionArchive, context: AcquisitionContext?, limit: Int): List<AcquisitionEvent> {
    val count = limit.coerceIn(0, 20)
    if (context == null || count == 0) return emptyList()
    // The compact HUD needs a few rows, not a full sort/allocation of a lifetime archive on a changed frame.
    val recent = mutableListOf<AcquisitionEvent>()
    archive.events.forEach { event ->
        if (event.origin != "confirmed" || event.context?.matches(context) != true || !DigestRngCatalog.valid(event.item, event.activity)) return@forEach
        val position = recent.indexOfFirst { event.occurredAt > it.occurredAt }.let { if (it < 0) recent.size else it }
        if (position < count) { recent.add(position, event); if (recent.size > count) recent.removeLast() }
    }
    return recent
}

fun journalBounds(filter: JournalFilter, now: Long): Pair<Long?, Long?> {
    val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val start = when (filter.period) {
        JournalPeriod.ALL_TIME -> null
        JournalPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        JournalPeriod.MONTH -> today.withDayOfMonth(1)
        JournalPeriod.CUSTOM -> filter.from.takeIf(String::isNotBlank)?.let(LocalDate::parse)
    }
    val end = when (filter.period) {
        JournalPeriod.ALL_TIME -> null
        JournalPeriod.WEEK -> start!!.plusWeeks(1)
        JournalPeriod.MONTH -> start!!.plusMonths(1)
        JournalPeriod.CUSTOM -> filter.until.takeIf(String::isNotBlank)?.let(LocalDate::parse)?.plusDays(1)
    }
    return start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() to end?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
}

/** Queries immutable archive snapshots off-thread. Charts count observed acquisitions, never theoretical drop probability. */
fun rngJournal(archive: AcquisitionArchive, filter: JournalFilter, context: AcquisitionContext?, now: Long): JournalView {
    val (start, end) = journalBounds(filter, now)
    fun within(time: Long) = time <= now && (start == null || time >= start) && (end == null || time < end)
    val scope = TrackerFilter(scope = filter.scope)
    val events = archive.events.asSequence().filter { event ->
        (event.origin == "confirmed" || filter.includeLegacy) && DigestRngCatalog.valid(event.item, event.activity) &&
            (scope.scopeMatches(event.context, context) || filter.includeLegacy && filter.scope == TrackerScope.ALL_LOCAL_PROFILES && event.context == null) &&
            within(event.occurredAt) && (filter.activity == null || filter.activity == event.activity) && (filter.floor == null || filter.floor == event.floor) &&
            (filter.query.isBlank() || "${DigestRngCatalog.name(event.item)} ${event.item} ${event.displayName.orEmpty()} ${event.player.orEmpty()}".contains(filter.query, true))
    }.sortedWith((if (filter.oldestFirst) compareBy<AcquisitionEvent> { it.occurredAt } else compareByDescending { it.occurredAt }).thenBy { it.id }).toList()
    val confirmed = events.filter { it.origin == "confirmed" }
    val dates = confirmed.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneOffset.UTC).toLocalDate() }
    // Explicit latest 30 observed days in an all-time view; no enormous array for years without observations.
    val bars = dates.entries.sortedBy { it.key }.takeLast(30).map { (date, entries) -> JournalBar(date, entries.sumOf { it.quantity.toLong() }) }
    val runs = archive.runs.count { run -> run.trackingVersion == 2 && run.completedAt?.let(::within) == true &&
        (run.startedAt == null || run.startedAt <= now) &&
        scope.scopeMatches(run.context, context) && (filter.floor == null || run.floor == filter.floor) && (filter.activity == null || filter.activity == "dungeon") }
    return JournalView(events, bars, confirmed.groupBy { it.item }.mapValues { (_, records) -> records.sumOf { it.quantity.toLong() } },
        confirmed.mapNotNull { event -> event.run?.let { it to event.quantity.toLong() } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() },
        confirmed.sumOf { it.value ?: 0.0 }, confirmed.count { it.value == null }, runs, events.size - confirmed.size,
        confirmed.groupBy { it.rarity ?: "UNKNOWN" }.mapValues { (_, records) -> records.sumOf { it.quantity.toLong() } },
        journalRunComparisons(archive, confirmed, now))
}

/** Compare only observed linked drops, preserving missing timing/valuation and excluding imported run aggregates. */
private fun journalRunComparisons(archive: AcquisitionArchive, events: List<AcquisitionEvent>, now: Long): List<JournalRunComparison> {
    val linked = events.filter { it.run != null }.groupBy { it.run!! }
    val runs = archive.runs.filter { it.trackingVersion == 2 && it.id in linked &&
        (it.startedAt == null || it.startedAt <= now) && (it.completedAt == null || it.completedAt <= now) }.associateBy { it.id }
    return linked.entries.sortedWith(compareByDescending<Map.Entry<String, List<AcquisitionEvent>>> { it.value.maxOf { event -> event.occurredAt } }
        .thenBy { it.key }).take(20).map { (id, drops) ->
        // A matching run ID from another profile must not lend context or profit to this observation.
        val run = runs[id]?.takeIf { record -> drops.all { record.context?.matches(it.context) == true } }
        val profit = run?.takeIf { it.chests.isNotEmpty() && it.chests.none { chest -> chest.time > now || chest.cost == null || chest.loot.any { item -> item.value == null } } &&
            (it.rerolls == 0 || it.rerollCost != null) }?.let { record ->
            record.chests.sumOf { chest -> chest.loot.sumOf { it.value!! } - chest.cost!! } - (record.rerollCost ?: 0.0)
        }
        JournalRunComparison(id, run?.floor ?: drops.mapNotNull { it.floor }.distinct().singleOrNull(), run?.completedAt, run?.duration,
            drops.sumOf { it.quantity.toLong() }, drops.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.sum(), drops.count { it.value == null }, profit)
    }
}

internal fun parseJournalFilter(json: JsonElement?): JournalFilter {
    if (json == null) return JournalFilter()
    val filter = Gson().fromJson(json, JournalFilter::class.java)
    require(filter.period in JournalPeriod.entries && filter.scope in TrackerScope.entries && filter.query.length <= 100)
    require(filter.activity == null || filter.activity in listOf("dungeon", "slayer"))
    require(filter.floor?.matches(Regex("[FM][1-7]|E|ENTRANCE")) != false)
    if (filter.from.isNotEmpty()) LocalDate.parse(filter.from)
    if (filter.until.isNotEmpty()) LocalDate.parse(filter.until)
    require(filter.from.isEmpty() || filter.until.isEmpty() || filter.from <= filter.until)
    return filter
}
