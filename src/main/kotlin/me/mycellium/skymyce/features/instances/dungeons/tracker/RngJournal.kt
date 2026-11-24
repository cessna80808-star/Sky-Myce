package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import me.mycellium.skymyce.community.*
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.hud.themeLuminance
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.network.chat.Component

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
    require(filter.floor?.matches(TRACKED_FLOOR) != false)
    if (filter.from.isNotEmpty()) LocalDate.parse(filter.from)
    if (filter.until.isNotEmpty()) LocalDate.parse(filter.until)
    require(filter.from.isEmpty() || filter.until.isEmpty() || filter.from <= filter.until)
    return filter
}

enum class JournalTimeGranularity(val title: String) { DAY("Per day"), WEEK("Per week"), MONTH("Per month") }
data class JournalTimeBucket(val start: LocalDate, val end: LocalDate, val count: Long)
data class JournalTimeline(val granularity: JournalTimeGranularity, val buckets: List<JournalTimeBucket>)

/** Real-time UTC buckets for the Trends column chart; zero-count periods stay on the axis. */
internal fun journalTimeline(events: List<AcquisitionEvent>, filter: JournalFilter, now: Long): JournalTimeline {
    val confirmed = events.filter { it.origin == "confirmed" }
    if (confirmed.isEmpty()) return JournalTimeline(JournalTimeGranularity.DAY, emptyList())
    val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val byDay = HashMap<LocalDate, Long>()
    for (event in confirmed) {
        val date = Instant.ofEpochMilli(event.occurredAt).atZone(ZoneOffset.UTC).toLocalDate()
        byDay[date] = (byDay[date] ?: 0L) + event.quantity.toLong()
    }
    val minDate = byDay.keys.min()
    val maxDate = byDay.keys.max()
    val (rangeStart, rangeEnd) = when (filter.period) {
        JournalPeriod.WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            monday to maxOf(monday.plusDays(6), maxDate)
        }
        JournalPeriod.MONTH -> today.withDayOfMonth(1) to maxOf(today, maxDate)
        JournalPeriod.CUSTOM -> {
            val from = filter.from.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: minDate
            val until = filter.until.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { minOf(it, today) } ?: maxOf(today, maxDate)
            minOf(from, minDate) to maxOf(until, maxDate)
        }
        JournalPeriod.ALL_TIME -> {
            val end = maxOf(today, maxDate)
            val span = ChronoUnit.DAYS.between(minDate, end) + 1
            (if (span < 7) end.minusDays(6) else minDate) to end
        }
    }
    val spanDays = ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
    if (spanDays <= 45) {
        val buckets = ArrayList<JournalTimeBucket>(spanDays.toInt())
        var cursor = rangeStart
        while (!cursor.isAfter(rangeEnd)) {
            buckets += JournalTimeBucket(cursor, cursor, byDay[cursor] ?: 0L)
            cursor = cursor.plusDays(1)
        }
        return JournalTimeline(JournalTimeGranularity.DAY, buckets)
    }
    if (spanDays <= 210) {
        val byWeek = HashMap<LocalDate, Long>()
        byDay.forEach { (date, count) ->
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            byWeek[monday] = (byWeek[monday] ?: 0L) + count
        }
        val buckets = ArrayList<JournalTimeBucket>()
        var cursor = rangeStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        while (!cursor.isAfter(rangeEnd)) {
            buckets += JournalTimeBucket(cursor, cursor.plusDays(6), byWeek[cursor] ?: 0L)
            cursor = cursor.plusWeeks(1)
        }
        return JournalTimeline(JournalTimeGranularity.WEEK, buckets)
    }
    val byMonth = HashMap<YearMonth, Long>()
    byDay.forEach { (date, count) ->
        val ym = YearMonth.from(date)
        byMonth[ym] = (byMonth[ym] ?: 0L) + count
    }
    val endMonth = YearMonth.from(rangeEnd)
    val startMonth = maxOf(YearMonth.from(rangeStart), endMonth.minusMonths(59))
    val buckets = ArrayList<JournalTimeBucket>()
    var cursor = startMonth
    while (!cursor.isAfter(endMonth)) {
        buckets += JournalTimeBucket(cursor.atDay(1), cursor.atEndOfMonth(), byMonth[cursor] ?: 0L)
        cursor = cursor.plusMonths(1)
    }
    return JournalTimeline(JournalTimeGranularity.MONTH, buckets)
}

private val rarityTierRank = listOf(
    "ULTIMATE", "VERY_SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON"
).withIndex().associate { (index, name) -> name to index }

internal fun journalRarityOrder(rarity: String): Int = rarityTierRank[rarity] ?: rarityTierRank.size

internal fun formatRarityName(rarity: String): String = if (rarity == "UNKNOWN") "Unknown" else
    rarity.split('_').joinToString(" ") { part ->
        part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/** Picks the most frequently observed non-null rarity per item among confirmed drops. */
internal fun journalItemRarities(events: List<AcquisitionEvent>): Map<String, String> =
    events.asSequence().filter { it.origin == "confirmed" && it.rarity != null }
        .groupBy { it.item }
        .mapValues { (_, records) ->
            records.groupingBy { it.rarity!! }.fold(0L) { acc, e -> acc + e.quantity.toLong() }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { journalRarityOrder(it.key) })
                .first().key
        }

internal fun formatRunDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "—"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private val axisShortDay = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT)
private val axisMonthFmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ROOT)
private val tooltipDayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT)
private val tooltipMonthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT)

private fun rarityColor(rarity: String?): Int {
    if (rarity == null) return MenuTheme.TEXT
    if (rarity == "UNKNOWN") return MenuTheme.MUTED
    val light = themeLuminance(MenuTheme.CARD) > 0.4
    return when (rarity) {
        "COMMON" -> if (light) 0x475569 else 0xDCE3EC
        "UNCOMMON" -> if (light) 0x15803D else 0x55FF55
        "RARE" -> if (light) 0x2563EB else 0x6B8CFF
        "EPIC" -> if (light) 0x7E22CE else 0xC66BFF
        "LEGENDARY" -> if (light) 0xB45309 else 0xFFAA00
        "MYTHIC" -> if (light) 0xC026D3 else 0xFF66FF
        "DIVINE" -> if (light) 0x0891B2 else 0x55FFFF
        "SPECIAL", "VERY_SPECIAL" -> if (light) 0xDC2626 else 0xFF5555
        "ULTIMATE" -> if (light) 0x991B1B else 0xE04040
        else -> MenuTheme.ACCENT
    }
}

private fun canvasRun(text: String, color: Int, bold: Boolean = false) = MenuCanvas.Run(text, MenuTheme.alpha(color), bold = bold)

private fun niceAxisStep(maxValue: Long, maxIntervals: Int): Long {
    val raw = maxValue.toDouble() / maxIntervals.coerceAtLeast(1)
    var magnitude = 1L
    while (magnitude * 10 < raw) magnitude *= 10
    val normalized = raw / magnitude
    val mult = when {
        normalized <= 1.0 -> 1L
        normalized <= 2.0 -> 2L
        normalized <= 5.0 -> 5L
        else -> 10L
    }
    return (mult * magnitude).coerceAtLeast(1L)
}

/** Filled column chart on a real UTC time axis with gridlines, date ticks and hover readout. */
private class JournalBarChart(private val timeline: JournalTimeline) : BaseUIComponent() {
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val buckets = timeline.buckets
        if (buckets.isEmpty()) return
        val canvas = (graphics as MenuGraphics).canvas
        val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1L)
        val ticks = if (maxCount <= 3L) (0L..maxCount).toList() else {
            val step = niceAxisStep(maxCount, 3)
            val top = ((maxCount + step - 1) / step) * step
            (0L..top step step).toList()
        }
        val topTick = ticks.last().coerceAtLeast(1L)
        val yLabelWidth = MenuCanvas.textWidth(topTick.toString(), size = 8.0).coerceAtLeast(14.0) + 6.0
        val left = x() + yLabelWidth
        val right = x() + width() - 6.0
        val plotTop = y() + 6.0
        val plotBottom = y() + height() - 15.0
        val plotW = right - left
        val plotH = plotBottom - plotTop
        if (plotW < 24.0 || plotH < 20.0) return

        // Horizontal gridlines and integer Y-axis labels.
        for (tick in ticks) {
            val ly = plotBottom - tick.toDouble() / topTick * plotH
            canvas.line(left, ly, right, ly, 1.0, MenuTheme.alpha(MenuTheme.BORDER, if (tick == 0L) 70 else 28))
            val label = tick.toString()
            val lw = MenuCanvas.textWidth(label, size = 8.0)
            canvas.text(listOf(canvasRun(label, MenuTheme.MUTED)), left - lw - 4.0, ly - 4.0, 8.0)
        }

        val slotW = plotW / buckets.size
        val gap = when {
            slotW >= 18.0 -> 4.0
            slotW >= 10.0 -> 2.0
            slotW >= 5.0 -> 1.0
            else -> 0.5
        }
        val barW = (slotW - gap).coerceIn(1.5, 28.0)
        val hovered = if (mouseX >= left && mouseX <= right && mouseY >= plotTop && mouseY <= plotBottom && menuHoverAllowed(this, mouseX, mouseY)) {
            ((mouseX - left) / slotW).toInt().coerceIn(0, buckets.lastIndex)
        } else -1

        if (hovered >= 0) {
            canvas.roundedRect(left + hovered * slotW, plotTop, slotW, plotH, 2.0, MenuTheme.alpha(MenuTheme.TEXT, 8))
        }

        val baseColor = MenuTheme.ACCENT
        val hoverColor = MenuTheme.blend(MenuTheme.ACCENT, MenuTheme.TEXT, 24)
        buckets.forEachIndexed { index, bucket ->
            if (bucket.count <= 0L) return@forEachIndexed
            val barH = (bucket.count.toDouble() / topTick * plotH).coerceAtLeast(3.0)
            val barX = left + index * slotW + (slotW - barW) / 2.0
            val barY = plotBottom - barH
            val color = MenuTheme.alpha(if (index == hovered) hoverColor else baseColor)
            val radius = if (MenuTheme.ROUNDED) minOf(2.5, barW / 2.0, barH / 2.0) else 0.0
            canvas.roundedRect(barX, barY, barW, barH, radius, color)
            // Keep top caps rounded while planting the bar base flat on the zero axis.
            if (radius > 0.0 && barH > radius) {
                canvas.fill(barX, plotBottom - radius, barW, radius, color)
            }
        }

        // X-axis date ticks (evenly spaced, never overlapping or clipped).
        val maxLabels = (plotW / 56.0).toInt().coerceIn(2, 5)
        val labelIndices = if (buckets.size <= maxLabels) buckets.indices.toList() else {
            (0 until maxLabels).map { step -> (step * buckets.lastIndex) / (maxLabels - 1) }.distinct()
        }
        var lastRight = Double.NEGATIVE_INFINITY
        for (index in labelIndices) {
            val bucket = buckets[index]
            val text = when (timeline.granularity) {
                JournalTimeGranularity.MONTH -> axisMonthFmt.format(bucket.start)
                else -> axisShortDay.format(bucket.start)
            }
            val tw = MenuCanvas.textWidth(text, size = 8.0)
            val center = left + (index + 0.5) * slotW
            val tx = (center - tw / 2.0).coerceIn(left, (right - tw).coerceAtLeast(left))
            if (tx >= lastRight + 4.0) {
                canvas.text(listOf(canvasRun(text, MenuTheme.MUTED)), tx, plotBottom + 4.0, 8.0)
                lastRight = tx + tw
            }
        }

        if (hovered >= 0) {
            val bucket = buckets[hovered]
            val dateText = when (timeline.granularity) {
                JournalTimeGranularity.DAY -> "${tooltipDayFmt.format(bucket.start)} UTC"
                JournalTimeGranularity.WEEK -> "${axisShortDay.format(bucket.start)} – ${axisShortDay.format(bucket.end)} UTC"
                JournalTimeGranularity.MONTH -> "${tooltipMonthFmt.format(bucket.start)} UTC"
            }
            val countText = "${bucket.count} ${if (bucket.count == 1L) "drop" else "drops"}"
            val lines = listOf(canvasRun(dateText, MenuTheme.MUTED), canvasRun(countText, if (bucket.count > 0) MenuTheme.ACCENT else MenuTheme.TEXT, bold = bucket.count > 0))
            val boxW = lines.maxOf { MenuCanvas.textWidth(it.text, it.bold, size = 8.0) } + 12.0
            val boxH = lines.size * 10.5 + 7.0
            val anchorX = left + (hovered + 0.5) * slotW
            val boxX = (if (anchorX + 8.0 + boxW <= right) anchorX + 8.0 else anchorX - 8.0 - boxW).coerceIn(left, (right - boxW).coerceAtLeast(left))
            val boxY = (mouseY - boxH / 2.0).coerceIn(plotTop, (plotBottom - boxH).coerceAtLeast(plotTop))
            val r = if (MenuTheme.ROUNDED) 3.0 else 0.0
            canvas.roundedRect(boxX, boxY, boxW, boxH, r, MenuTheme.alpha(MenuTheme.PANEL, 96))
            canvas.roundedBorder(boxX, boxY, boxW, boxH, r, 1.0, MenuTheme.alpha(MenuTheme.BORDER, 85))
            lines.forEachIndexed { idx, run -> canvas.text(listOf(run), boxX + 6.0, boxY + 4.0 + idx * 10.5, 8.0) }
        }
    }
}

/** Slim filled horizontal bar on a subtle track (no hollow outlines). */
private class JournalMiniBar(private val fraction: Double, private val barColor: Int) : BaseUIComponent() {
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val canvas = (graphics as MenuGraphics).canvas
        val w = width().toDouble(); val h = height().toDouble()
        if (w <= 0 || h <= 0) return
        val r = if (MenuTheme.ROUNDED) minOf(2.0, h / 2.0) else 0.0
        canvas.roundedRect(x().toDouble(), y().toDouble(), w, h, r, MenuTheme.alpha(MenuTheme.BORDER, 24))
        val fillW = (w * fraction.coerceIn(0.0, 1.0)).coerceAtLeast(if (fraction > 0) 3.0 else 0.0).coerceAtMost(w)
        if (fillW > 0) canvas.roundedRect(x().toDouble(), y().toDouble(), fillW, h, r, MenuTheme.alpha(barColor))
    }
}

private data class RaritySegment(val rarity: String, val label: String, val count: Long, val color: Int)

/** Single slim stacked bar showing observed rarity share with hover highlight. */
private class JournalRarityBar(private val segments: List<RaritySegment>) : BaseUIComponent() {
    private val total = segments.sumOf { it.count }.coerceAtLeast(1L)
    private var lastHover = -1
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (segments.isEmpty()) return
        val canvas = (graphics as MenuGraphics).canvas
        val w = width().toDouble(); val h = height().toDouble()
        if (w <= 0 || h <= 0) return
        val gap = if (segments.size > 1) 1.5 else 0.0
        val usable = (w - gap * (segments.size - 1)).coerceAtLeast(1.0)
        val widths = DoubleArray(segments.size) { index -> (usable * segments[index].count / total).coerceAtLeast(3.0) }
        val scale = usable / widths.sum().coerceAtLeast(1.0)
        var cursor = x().toDouble()
        val hoverAllowed = mouseY >= y() && mouseY <= y() + h && mouseX >= x() && mouseX <= x() + w && menuHoverAllowed(this, mouseX, mouseY)
        var hovered = -1
        segments.forEachIndexed { index, segment ->
            val sw = widths[index] * scale
            val isHover = hoverAllowed && mouseX >= cursor && mouseX <= cursor + sw + gap
            if (isHover) hovered = index
            val color = if (isHover) MenuTheme.blend(segment.color, MenuTheme.TEXT, 22) else segment.color
            val r = if (MenuTheme.ROUNDED) minOf(2.0, sw / 2.0, h / 2.0) else 0.0
            canvas.roundedRect(cursor, y().toDouble(), sw, h, r, MenuTheme.alpha(color))
            cursor += sw + gap
        }
        if (hovered != lastHover) {
            lastHover = hovered
            if (hovered >= 0) {
                val seg = segments[hovered]
                val pct = "%.0f%%".format(Locale.ROOT, seg.count * 100.0 / total)
                wrappedTooltip(Component.literal("${seg.label}: ${seg.count} ${if (seg.count == 1L) "drop" else "drops"} ($pct)"))
            } else {
                val summary = segments.joinToString(" · ") { "${it.label} ${it.count}" }
                wrappedTooltip(Component.literal(summary))
            }
        }
    }
}

private class RarityDot(private val dotColor: Int) : BaseUIComponent() {
    init { sizing(Sizing.fixed(6), Sizing.fixed(6)) }
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val r = if (MenuTheme.ROUNDED) 2.0 else 0.0
        (graphics as MenuGraphics).canvas.roundedRect(x().toDouble(), y().toDouble(), width().toDouble(), height().toDouble(), r, MenuTheme.alpha(dotColor))
    }
}

/** Permanent local archive; opening this page needs neither a relay connection nor Discord. */
class RngJournalScreen : MenuScreen(Component.literal("Sky-Myce RNG Journal"), MenuPage.JOURNAL) {
    private var filter = AcquisitionRepository.journalPreferences
    private var view = JournalView()
    private var seenRevision = -1L
    private var seenContext: AcquisitionContext? = null
    @Volatile private var generation = 0L
    private var requested = -1L
    @Volatile private var closed = false
    private var edited = false
    private var page = 0
    private var charts = false
    private var expanded: String? = null
    private var lastDay = LocalDate.now(ZoneOffset.UTC)
    private var periodCounts = emptyMap<JournalPeriod, Long>()
    private var comparison: JournalCommunityComparison? = null
    private var comparisonLocalCount = 0L
    private var comparisonStatus = "Connect Discord to compare with shared drops."
    private var comparisonKey = ""
    private var nextComparison = 0L
    @Volatile private var comparisonEpoch = 0L
    private companion object {
        val localWorker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce local journal").apply { isDaemon = true }
        })
        val comparisonWorker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce journal comparison").apply { isDaemon = true }
        })
    }
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var status: MenuLabel
    private var previousPage: MenuButton? = null
    private var nextPage: MenuButton? = null
    private var pageLabel: MenuLabel? = null
    override fun isPauseScreen() = false
    override fun init() { closed = false; comparisonKey = ""; super.init() }
    override fun removed() { closed = true; generation++; localWorker.cancel(this); comparisonEpoch++; comparisonWorker.cancel(this); super.removed() }

    private val innerWidth get() = (menuWidth - 20).coerceAtLeast(160)

    override fun buildContent(root: FlowLayout) {
        previousPage = null; nextPage = null; pageLabel = null
        root.surface(Surface.BLANK)
        root.child(column(gap = 4).apply {
            id("journal-panel")
            sizing(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight)); padding(Insets.of(8)); surface(MenuTheme.panel())
            val inlineStatus = menuWidth >= 340
            status = label("Loading…", MenuTheme.MUTED).apply {
                id("journal-status"); fontSize = 8.5f
                horizontalSizing(if (inlineStatus) Sizing.expand() else Sizing.fill())
            }
            child(row().apply {
                child(label("RNG Journal", MenuTheme.ACCENT).apply {
                    horizontalSizing(if (inlineStatus) Sizing.content() else Sizing.expand()); fontSize = 12f
                })
                if (inlineStatus) child(status.apply { margins(Insets.left(6)) })
                listOf("History" to false, "Trends" to true).forEach { (title, trends) ->
                    child(button(title, 46) {
                        if (charts == trends) return@button
                        charts = trends; comparisonKey = ""; comparisonEpoch++; comparisonWorker.cancel(this@RngJournalScreen); comparison = null
                        page = 0; expanded = null; rebuildMenu(if (trends) 1 else -1)
                    }.apply { navigation = true; centeredNavigation = true; selected = { charts == trends } })
                }
            })
            child(row().apply {
                child(MenuTextBox(Sizing.expand()).apply {
                    id("journal-search"); verticalSizing(Sizing.fixed(20))
                    setMaxLength(100); text(this@RngJournalScreen.filter.query); setHint(Component.literal("Search drops or players…"))
                    onChanged().subscribe { change(this@RngJournalScreen.filter.copy(query = it)) }
                })
                child(button("Clear", 44) {
                    change(filter.copy(period = JournalPeriod.ALL_TIME, query = "", activity = null, floor = null, from = "", until = "")); rebuildMenu()
                }.apply { id("journal-clear") }.wrappedTooltip(Component.literal("Reset filters (keeps saved drops and scope)")))
            })
            val controls = buildList {
                add(button(periodShort(filter.period), 72) { b -> openMenuDropdown(b) { d -> JournalPeriod.entries.forEach { period ->
                    d.button(Component.literal(period.title)) { change(filter.copy(period = period)); rebuildMenu() }
                } } })
                add(button(scopeTitle(filter.scope), 86) { b -> openMenuDropdown(b) { d ->
                    TrackerScope.entries.forEach { scope -> d.button(Component.literal(scopeTitle(scope))) {
                        change(filter.copy(scope = scope, includeLegacy = filter.includeLegacy || scope == TrackerScope.UNSCOPED_LEGACY)); rebuildMenu()
                    } }
                } })
                add(button(filter.activity?.replaceFirstChar(Char::uppercase) ?: "All activities", 78) { b -> openMenuDropdown(b) { d ->
                    (listOf<String?>(null) + DigestRngParser.catalog.values.map { it.activity }.distinct()).forEach { activity ->
                        d.button(Component.literal(activity?.replaceFirstChar(Char::uppercase) ?: "All activities")) { change(filter.copy(activity = activity)); rebuildMenu() }
                    }
                } })
                add(button(filter.floor ?: "All floors", 62) { b -> openMenuDropdown(b) { d ->
                    (listOf<String?>(null) + (1..7).flatMap { listOf("F$it", "M$it") }).forEach { floor ->
                        d.button(Component.literal(floor ?: "All floors")) { change(filter.copy(floor = floor)); rebuildMenu() }
                    }
                } })
                if (!charts) add(button(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓", 64) { change(filter.copy(oldestFirst = !filter.oldestFirst)); rebuildMenu() })
                add(button(if (filter.includeLegacy) "Imported ✓" else "Imported off", 74) { change(filter.copy(includeLegacy = !filter.includeLegacy)); rebuildMenu() }
                    .wrappedTooltip(Component.literal("Include older imported drops in History (charts count confirmed drops only)")))
            }
            var line = row().apply { id("journal-controls-0") }
            var used = 0
            controls.forEachIndexed { index, control ->
                val width = control.horizontalSizing().get().value
                if (used > 0 && used + 4 + width > menuWidth - 16) {
                    child(line); line = row().apply { id("journal-controls-$index") }; used = 0
                }
                line.child(control); used += width + if (used == 0) 0 else 4
            }
            child(line)
            if (filter.period == JournalPeriod.CUSTOM) child(row().apply { child(dateField(true)); child(dateField(false)) })
            if (!inlineStatus) child(status)
            results = menuScroll(Sizing.fill(), Sizing.expand(), column()).apply { id("journal-results"); scrollStep(24); scrollbarThiccness(2) }; child(results)
            if (!charts) child(row().apply {
                previousPage = button("‹", 26) { page = (page - 1).coerceAtLeast(0); expanded = null; renderResults() }.apply { id("journal-previous") }
                child(previousPage!!)
                pageLabel = label("").apply { id("journal-page"); fontSize = 9f; horizontalSizing(Sizing.expand()); horizontalTextAlignment(HorizontalAlignment.CENTER) }
                child(pageLabel!!)
                nextPage = button("›", 26) { page++; expanded = null; renderResults() }.apply { id("journal-next") }
                child(nextPage!!)
            })
        })
        renderResults()
    }
    private fun dateField(start: Boolean) = MenuTextBox(Sizing.fill(48)).apply {
        verticalSizing(Sizing.fixed(20))
        setMaxLength(10); text(if (start) this@RngJournalScreen.filter.from else this@RngJournalScreen.filter.until)
        setHint(Component.literal(if (start) "From YYYY-MM-DD" else "To YYYY-MM-DD"))
        onChanged().subscribe { value ->
            if (value.isEmpty() || runCatching { LocalDate.parse(value) }.isSuccess) {
                val next = if (start) this@RngJournalScreen.filter.copy(from = value) else this@RngJournalScreen.filter.copy(until = value)
                if (next.from.isEmpty() || next.until.isEmpty() || next.from <= next.until) change(next)
                else status.text(Component.literal("Start date must be before end date").withColor(MenuTheme.YELLOW))
            } else status.text(Component.literal("Use YYYY-MM-DD (UTC)").withColor(MenuTheme.YELLOW))
        }
    }
    private fun change(next: JournalFilter) {
        filter = next; edited = true; page = 0; expanded = null; generation++; comparisonKey = ""; comparisonEpoch++
        comparisonWorker.cancel(this); comparison = null; AcquisitionRepository.rememberJournal(next)
    }
    override fun tick() {
        super.tick()
        if (menuClosing || !::results.isInitialized) return
        val current = AcquisitionRepository.currentContext()
        val day = LocalDate.now(ZoneOffset.UTC)
        if (!edited && filter != AcquisitionRepository.journalPreferences) { filter = AcquisitionRepository.journalPreferences; generation++; rebuildMenu() }
        if (seenRevision != AcquisitionRepository.revision || seenContext != current || lastDay != day) {
            seenRevision = AcquisitionRepository.revision; seenContext = current; lastDay = day; generation++
        }
        if (charts) updateComparison()
        if (requested == generation) return
        val token = generation; requested = token
        val archive = AcquisitionRepository.state; val query = filter; val now = System.currentTimeMillis()
        localWorker.submit(this, { !closed && token == generation }, {
            rngJournal(archive, query, current, now) to journalPeriodCounts(archive, query, current, now)
        }) { computed ->
            MC.instance.execute {
                if (closed || menuClosing || token != generation) return@execute
                computed.onSuccess { (journal, periods) -> view = journal; periodCounts = periods; renderResults() }
                    .onFailure { status.text(Component.literal("Local journal could not be queried").withColor(MenuTheme.RED)) }
            }
        }
    }
    private fun renderResults() {
        if (!::results.isInitialized) return
        val focus = uiAdapter.rootComponent.focusHandler()
        val focusedId = focus?.focused()?.id()?.takeIf { it.startsWith("journal-details-toggle-") }
        val pages = ((view.events.size + 19) / 20).coerceAtLeast(1); page = page.coerceIn(0, pages - 1)
        val warning = AcquisitionRepository.status.takeUnless { it == "Confirmed tracking • historical profit preserved separately" }
        val totalCount = if (charts) view.counts.values.sum() else view.events.size.toLong()
        val unit = if (totalCount == 1L) "drop" else "drops"
        status.text(Component.literal(listOfNotNull("$totalCount $unit", warning).joinToString(" · ")).withColor(MenuTheme.MUTED))
        previousPage?.active(page > 0); nextPage?.active(page + 1 < pages)
        pageLabel?.text(Component.literal("${page + 1} / $pages").withColor(MenuTheme.MUTED))
        val content = column(gap = if (charts) 6 else 4).apply {
            padding(Insets.right(4))
            if (charts) trends(this) else history(this)
        }
        results.child(content)
        if (focusedId != null) {
            val children = arrayListOf<UIComponent>()
            content.collectDescendants(children)
            children.firstOrNull { it.id() == focusedId }?.let { focus.focus(it, UIComponent.FocusSource.KEYBOARD_CYCLE) }
        }
    }

    private fun history(content: FlowLayout) {
        if (view.events.isEmpty()) {
            content.child(label(if (AcquisitionRepository.currentContext() == null && filter.scope == TrackerScope.CURRENT_PROFILE)
                "Choose All profiles to view saved drops offline." else "No drops match these filters.", MenuTheme.MUTED))
            return
        }
        val wide = menuWidth >= 460
        var day = ""
        view.events.drop(page * 20).take(20).forEach { event ->
            val time = Instant.ofEpochMilli(event.occurredAt).atZone(ZoneOffset.UTC)
            val date = time.toLocalDate().toString()
            if (date != day) {
                day = date
                content.child(label("$date · UTC", MenuTheme.SECONDARY).apply {
                    fontSize = 8.5f; margins(Insets.top(if (content.children().isEmpty()) 1 else 5))
                })
            }
            content.child(column(gap = 2).apply {
                id("journal-event-${event.id}"); surface(MenuTheme.panel(true)); padding(Insets.of(4, 4, 6, 6))
                val context = listOfNotNull(
                    time.format(DateTimeFormatter.ofPattern("HH:mm")),
                    event.floor ?: event.activity.replaceFirstChar(Char::uppercase),
                    event.chest?.lowercase(Locale.ROOT)?.replaceFirstChar(Char::uppercase),
                    if (event.origin != "confirmed") "Imported" else null
                )
                val metadata = label(context.joinToString(" · "), MenuTheme.MUTED).apply {
                    fontSize = 8.5f; horizontalSizing(if (wide) Sizing.fixed(minOf(148, (menuWidth - 260).coerceAtLeast(90))) else Sizing.expand())
                }
                val detailsButton = button(if (expanded == event.id) "Hide" else "Details", 48) {
                    expanded = event.id.takeUnless { expanded == it }; renderResults()
                }.apply { id("journal-details-toggle-${event.id}"); verticalSizing(Sizing.fixed(18)) }
                val baseName = event.displayName ?: DigestRngCatalog.name(event.item)
                val itemTitle = if (event.quantity > 1) "$baseName ×${event.quantity}" else baseName
                val nameColor = event.rarity?.let(::rarityColor) ?: MenuTheme.TEXT
                child(row().apply {
                    child(skyBlockItemIcon(event.item))
                    child(label(itemTitle, nameColor).apply { horizontalSizing(Sizing.expand()); fontSize = 9.5f })
                    if (wide) child(metadata)
                    child(label(event.value?.let { "${money(it)} est." } ?: "Unpriced", if (event.value != null) MenuTheme.TEXT else MenuTheme.MUTED).apply {
                        fontSize = 9f; horizontalSizing(Sizing.fixed(72)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                        wrappedTooltip(Component.literal("Estimated value when dropped (unpriced is not zero)"))
                    })
                    if (wide) child(detailsButton)
                })
                if (!wide) child(row().apply { child(metadata); child(detailsButton) })
                if (expanded == event.id) child(details(event))
            })
        }
    }

    private fun details(event: AcquisitionEvent) = column(gap = 2).apply {
        id("journal-details-${event.id}")
        padding(Insets.of(4, 1, if (menuWidth >= 460) 20 else 2, 2))
        val lines = listOfNotNull(
            "Time: ${timestamp(event.occurredAt)} · ${event.player ?: "Unknown player"} (${event.context?.profileName ?: "Unscoped"})",
            "Value: ${event.value?.let { "${money(it)} est." } ?: "Unknown"} · Chest cost: ${money(event.chestCost)}" +
                (event.rerollCost?.let { " · Rerolls: ${money(it)}" } ?: ""),
            "Item: ${event.item}" + (event.rarity?.let { " · Rarity: ${formatRarityName(it)}" } ?: "") +
                (event.run?.let { run -> " · Run ${run.take(8)}" + (view.runCounts[run]?.let { " ($it drop${if (it == 1L) "" else "s"})" } ?: "") } ?: ""),
            "Source: " + (if (event.origin == "confirmed") "Confirmed locally" else "Imported (${event.origin})") +
                (event.valuationAt?.let { " · Priced ${shortTimestamp(it)}" } ?: "")
        )
        lines.forEach { child(label(it, MenuTheme.MUTED).apply { fontSize = 8.5f }) }
    }

    private fun timestamp(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))

    private fun shortTimestamp(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))

    private fun trends(content: FlowLayout) {
        val width = innerWidth
        val wide = width >= 480
        val totalDrops = view.counts.values.sum()
        val confirmedEvents = view.events.count { it.origin == "confirmed" }
        val profileMissing = filter.scope == TrackerScope.CURRENT_PROFILE && AcquisitionRepository.currentContext() == null

        if (profileMissing) {
            content.child(cardBox().apply {
                child(label("Current profile unavailable offline. Switch scope to All profiles to view saved drops.", MenuTheme.YELLOW).apply { fontSize = 9f })
            })
        }

        // 1. Stat card strip
        data class StatCard(val caption: String, val value: String, val sub: String, val valueColor: Int = MenuTheme.TEXT, val tip: String? = null)
        val valueText = when {
            confirmedEvents > 0 && view.unpriced == confirmedEvents -> "Unpriced"
            else -> "${money(view.knownValue)} est."
        }
        val valueSub = when {
            view.unpriced > 0 -> "${view.unpriced} unpriced"
            confirmedEvents == 0 -> "no drops"
            else -> "known drops"
        }
        val stats = buildList {
            add(StatCard("Drops", formatInt(totalDrops), periodShort(filter.period), MenuTheme.ACCENT))
            add(StatCard("This week", if (profileMissing) "—" else formatInt(periodCounts[JournalPeriod.WEEK] ?: 0L), "UTC week"))
            add(StatCard("This month", if (profileMissing) "—" else formatInt(periodCounts[JournalPeriod.MONTH] ?: 0L), "UTC month"))
            add(StatCard("Est. value", valueText, valueSub, tip = "Estimated at drop time. Unpriced drops are not counted as zero."))
            if (filter.activity != "slayer" && view.observedRuns > 0) {
                val dungeonDrops = view.events.filter { it.origin == "confirmed" && it.activity == "dungeon" }.sumOf { it.quantity.toLong() }
                val ratio = if (dungeonDrops > 0) "${"%.1f".format(Locale.ROOT, view.observedRuns.toDouble() / dungeonDrops)} / drop" else "completed"
                add(StatCard("Runs", formatInt(view.observedRuns.toLong()), ratio, tip = "Completed dungeon runs in this period. Observed ratio, not drop rate."))
            }
        }
        val statRows = when {
            width >= 480 -> listOf(stats)
            width >= 300 && stats.size == 5 -> listOf(stats.take(3), stats.drop(3))
            else -> stats.chunked(2)
        }
        val cardGap = 5
        statRows.forEach { rowCards ->
            content.child(row(gap = cardGap).apply {
                val baseW = (width - cardGap * (rowCards.size - 1)) / rowCards.size
                val remainder = width - cardGap * (rowCards.size - 1) - baseW * rowCards.size
                rowCards.forEachIndexed { idx, stat ->
                    val cardW = baseW + if (idx == rowCards.lastIndex) remainder else 0
                    val card = UIContainers.verticalFlow(Sizing.fixed(cardW), Sizing.content()).apply {
                        gap(1); padding(Insets.of(4, 4, 7, 7)); surface(MenuTheme.panel(true))
                        child(label(stat.caption, MenuTheme.MUTED).apply { fontSize = 8f })
                        child(label(stat.value, stat.valueColor).apply { fontSize = if (width < 320) 12f else 13.5f })
                        child(label(stat.sub, MenuTheme.MUTED).apply { fontSize = 7.5f })
                    }
                    stat.tip?.let { card.wrappedTooltip(Component.literal(it)) }
                    child(card)
                }
            })
        }

        // 2. Filled column chart on a real UTC time axis
        val timeline = journalTimeline(view.events, filter, System.currentTimeMillis())
        content.child(cardBox(pad = 6).apply {
            id("journal-trends-chart")
            child(row().apply {
                child(label("Drops over time", MenuTheme.TEXT).apply { fontSize = 9.5f; horizontalSizing(Sizing.expand()) })
                child(label("${timeline.granularity.title} · UTC", MenuTheme.MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.content()) })
            })
            if (timeline.buckets.isEmpty()) {
                child(label("No confirmed drops in this period.", MenuTheme.MUTED).apply { fontSize = 8.5f; margins(Insets.vertical(6)) })
            } else {
                child(JournalBarChart(timeline).apply { id("journal-trends-bars"); sizing(Sizing.fill(), Sizing.fixed(if (wide) 92 else 80)) })
            }
        })

        // 3. Breakdown: Drops by item (left) + Rarity & Community (right on wide, stacked on narrow)
        val itemRarities = journalItemRarities(view.events)
        val colGap = 6
        val leftW = if (wide) ((width - colGap) * 57) / 100 else width
        val rightW = if (wide) width - colGap - leftW else width
        val itemsCard = buildItemsCard(leftW, itemRarities)
        val rightCol = UIContainers.verticalFlow(Sizing.fixed(rightW), Sizing.content()).apply {
            gap(6)
            child(buildRarityCard(rightW, totalDrops))
            child(buildCommunityCard())
        }
        if (wide) {
            content.child(UIContainers.horizontalFlow(Sizing.fixed(width), Sizing.content()).apply {
                gap(colGap); verticalAlignment(VerticalAlignment.TOP)
                child(itemsCard)
                child(rightCol)
            })
        } else {
            content.child(itemsCard)
            content.child(rightCol)
        }

        // 4. Recent runs table (only when linked runs exist)
        if (view.runComparisons.isNotEmpty()) {
            content.child(buildRunsCard(width))
        }

        // 5. Single short honest caveat footer
        val caveat = buildList {
            add("UTC dates")
            add("values are est. (unpriced ≠ 0)")
            add("observed drops, not drop rates")
            if (view.legacyReports > 0) add("${view.legacyReports} imported excluded")
        }.joinToString(" · ")
        content.child(label(caveat, MenuTheme.MUTED).apply { fontSize = 8f; margins(Insets.of(1, 2, 2, 2)) })
    }

    private fun buildItemsCard(cardWidth: Int, itemRarities: Map<String, String>) =
        UIContainers.verticalFlow(Sizing.fixed(cardWidth), Sizing.content()).apply {
            id("journal-trends-items")
            gap(3); padding(Insets.of(6, 6, 7, 7)); surface(MenuTheme.panel(true))
            val sorted = view.counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }
                .thenBy { DigestRngCatalog.name(it.key) })
            child(row().apply {
                child(label("Drops by item", MenuTheme.TEXT).apply { fontSize = 9.5f; horizontalSizing(Sizing.expand()) })
                child(label("${sorted.size} ${if (sorted.size == 1) "item" else "items"}", MenuTheme.MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.content()) })
            })
            if (sorted.isEmpty()) {
                child(label("No drops yet.", MenuTheme.MUTED).apply { fontSize = 8.5f })
                return@apply
            }
            val maxCount = sorted.first().value.coerceAtLeast(1L)
            val barWidth = when {
                cardWidth >= 320 -> 64
                cardWidth >= 240 -> 44
                else -> 28
            }
            sorted.forEach { (item, count) ->
                val rarity = itemRarities[item]
                val nameColor = rarity?.let(::rarityColor) ?: MenuTheme.TEXT
                val barColor = rarity?.let(::rarityColor) ?: MenuTheme.ACCENT
                child(row(gap = 5).apply {
                    child(skyBlockItemIcon(item).apply { sizing(Sizing.fixed(13), Sizing.fixed(13)) })
                    child(label(DigestRngCatalog.name(item), nameColor).apply { fontSize = 9f; horizontalSizing(Sizing.expand()) })
                    child(JournalMiniBar(count.toDouble() / maxCount, barColor).apply { sizing(Sizing.fixed(barWidth), Sizing.fixed(5)) })
                    child(label(formatInt(count), MenuTheme.TEXT).apply {
                        fontSize = 9f; horizontalSizing(Sizing.fixed(22)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                    })
                })
            }
        }

    private fun buildRarityCard(cardWidth: Int, totalDrops: Long) =
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            id("journal-trends-rarity")
            gap(5); padding(Insets.of(7)); surface(MenuTheme.panel(true))
            child(row().apply {
                child(label("By rarity", MenuTheme.TEXT).apply { fontSize = 9.5f; horizontalSizing(Sizing.expand()) })
                child(label(formatInt(totalDrops), MenuTheme.MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.content()) })
            })
            val segments = view.rarityCounts.entries
                .filter { it.value > 0L }
                .sortedWith(compareBy<Map.Entry<String, Long>> { journalRarityOrder(it.key) }.thenByDescending { it.value })
                .map { (rarity, count) -> RaritySegment(rarity, formatRarityName(rarity), count, rarityColor(rarity)) }
            if (segments.isEmpty()) {
                child(label("No rarity recorded.", MenuTheme.MUTED).apply { fontSize = 8.5f })
                return@apply
            }
            child(JournalRarityBar(segments).apply { sizing(Sizing.fill(), Sizing.fixed(10)) })
            // Wrap legend chips cleanly inside cardWidth - 14
            val available = (cardWidth - 16).coerceAtLeast(80)
            var legendRow = row(gap = 8)
            var used = 0.0
            segments.forEach { seg ->
                val chipText = "${seg.label} ${formatInt(seg.count)}"
                val chipW = 6.0 + 4.0 + ceil(MenuCanvas.textWidth(chipText, size = 8.5)) + 2.0
                if (used > 0.0 && used + 8.0 + chipW > available) {
                    child(legendRow); legendRow = row(gap = 8); used = 0.0
                }
                legendRow.child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).apply {
                    gap(4); verticalAlignment(VerticalAlignment.CENTER)
                    child(RarityDot(seg.color))
                    child(MenuLabel(Component.literal(seg.label).withColor(MenuTheme.MUTED)
                        .append(Component.literal(" ${formatInt(seg.count)}").withColor(MenuTheme.TEXT))).apply { fontSize = 8.5f })
                })
                used += chipW + if (used == 0.0) 0.0 else 8.0
            }
            child(legendRow)
        }

    private fun buildCommunityCard() =
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            id("journal-trends-community")
            gap(4); padding(Insets.of(7)); surface(MenuTheme.panel(true))
            child(row().apply {
                child(label("Community", MenuTheme.TEXT).apply { fontSize = 9.5f; horizontalSizing(Sizing.expand()) })
                child(label("Account · UTC", MenuTheme.MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.content()) })
            })
            val data = comparison
            if (data == null) {
                child(label(comparisonStatus, MenuTheme.MUTED).apply { fontSize = 8.5f })
                return@apply
            }
            child(row().apply {
                child(label("Your drops", MenuTheme.MUTED).apply { fontSize = 8.5f; horizontalSizing(Sizing.expand()) })
                child(label("$comparisonLocalCount" + (data.ownSharedCount?.let { " ($it shared)" } ?: " (sharing off)"), MenuTheme.TEXT).apply {
                    fontSize = 8.5f; horizontalSizing(Sizing.content())
                })
            })
            child(row().apply {
                child(label("Community avg", MenuTheme.MUTED).apply { fontSize = 8.5f; horizontalSizing(Sizing.expand()) })
                child(label(data.mean?.let { "%.2f".format(Locale.ROOT, it) } ?: "—", MenuTheme.ACCENT).apply {
                    fontSize = 8.5f; horizontalSizing(Sizing.content())
                })
            })
            val updated = if (data.updatedAt > 0) shortTimestamp(data.updatedAt) else "no samples"
            child(label("${data.participants} players · ${data.totalDrops} shared drops", MenuTheme.MUTED).apply { fontSize = 8f })
            wrappedTooltip(Component.literal("Counts drop events per Minecraft account across all profiles. Updated $updated · ${data.source} (${data.coverage})."))
        }

    private fun buildRunsCard(tableWidth: Int) =
        UIContainers.verticalFlow(Sizing.fixed(tableWidth), Sizing.content()).apply {
            id("journal-trends-runs")
            gap(2); padding(Insets.of(7)); surface(MenuTheme.panel(true))
            val showTime = tableWidth >= 380
            child(row().apply {
                margins(Insets.bottom(2))
                child(label("Recent runs with drops", MenuTheme.TEXT).apply { fontSize = 9.5f; horizontalSizing(Sizing.expand()) })
                child(label("${view.runComparisons.size} linked", MenuTheme.MUTED).apply { fontSize = 8f; horizontalSizing(Sizing.content()) })
            })
            val rowGap = 4
            val cols = if (showTime) 5 else 4
            val innerRowW = (tableWidth - 14 - 8 - rowGap * (cols - 1)).coerceAtLeast(100)
            val floorW = if (showTime) innerRowW * 14 / 100 else innerRowW * 18 / 100
            val dropsW = if (showTime) innerRowW * 13 / 100 else innerRowW * 16 / 100
            val timeW = if (showTime) innerRowW * 19 / 100 else 0
            val valueW = if (showTime) innerRowW * 27 / 100 else innerRowW * 33 / 100
            val profitW = (innerRowW - floorW - dropsW - timeW - valueW).coerceAtLeast(24)
            fun runRow(floor: String, drops: String, duration: String, value: String, profit: String,
                       header: Boolean = false, shaded: Boolean = false, profitColor: Int = MenuTheme.TEXT) = row(gap = rowGap).apply {
                padding(Insets.of(2, 2, 4, 4))
                if (shaded) surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER, 16)))
                val textColor = if (header) MenuTheme.MUTED else MenuTheme.TEXT
                val size = if (header) 8f else 8.5f
                child(label(floor, if (header) MenuTheme.MUTED else MenuTheme.ACCENT).apply {
                    fontSize = size; horizontalSizing(Sizing.fixed(floorW))
                })
                child(label(drops, textColor).apply {
                    fontSize = size; horizontalSizing(Sizing.fixed(dropsW)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                })
                if (showTime) child(label(duration, MenuTheme.MUTED).apply {
                    fontSize = size; horizontalSizing(Sizing.fixed(timeW)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                })
                child(label(value, if (header || value == "Unpriced") MenuTheme.MUTED else MenuTheme.TEXT).apply {
                    fontSize = size; horizontalSizing(Sizing.fixed(valueW)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                })
                child(label(profit, if (header) MenuTheme.MUTED else profitColor).apply {
                    fontSize = size; horizontalSizing(Sizing.fixed(profitW)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                })
            }
            child(runRow("FLOOR", "DROPS", "TIME", "VALUE", "PROFIT", header = true))
            view.runComparisons.forEachIndexed { index, run ->
                val valueStr = when {
                    run.knownValue == null -> "Unpriced"
                    run.unpricedReports > 0 -> "${money(run.knownValue)}*"
                    else -> "${money(run.knownValue)} est."
                }
                val profitStr = run.estimatedProfit?.let { "${money(it)} est." } ?: "—"
                val profitColor = when {
                    run.estimatedProfit == null -> MenuTheme.MUTED
                    run.estimatedProfit > 0 -> MenuTheme.GREEN
                    run.estimatedProfit < 0 -> MenuTheme.RED
                    else -> MenuTheme.TEXT
                }
                val row = runRow(run.floor ?: "—", formatInt(run.dropCount), formatRunDuration(run.duration), valueStr, profitStr,
                    shaded = index % 2 == 1, profitColor = profitColor)
                val tip = listOfNotNull(
                    "Run ${run.run.take(8)}",
                    run.completedAt?.let { shortTimestamp(it) },
                    run.duration?.let { "Duration ${formatRunDuration(it)}" },
                    if (run.unpricedReports > 0) "${run.unpricedReports} unpriced" else null
                ).joinToString(" · ")
                row.wrappedTooltip(Component.literal(tip))
                child(row)
            }
        }

    private fun updateComparison() {
        val period = journalCommunityPeriod(filter)
        val token = CommunityClient.sessionToken; val identity = CommunityClient.identity?.deepCopy()
        val ownerGeneration = CommunityClient.sessionGeneration
        val minecraftOwner = MC.instance.user.profileId.toString().replace("-", "")
        val key = "$period/$ownerGeneration/$minecraftOwner/${identity?.get("uuid")}/$token/${AcquisitionRepository.revision}"
        if (key == comparisonKey && System.currentTimeMillis() < nextComparison) return
        comparisonKey = key; nextComparison = Long.MAX_VALUE; comparison = null
        val boundary = ++comparisonEpoch; comparisonWorker.cancel(this)
        if (period == null || !CommunityClient.connected || token == null || identity == null) {
            comparisonStatus = if (period == null) "To compare, pick all time, this week or this month and clear the search, activity, floor and imported filters."
                else "Connect Discord to compare with shared drops."
            renderResults(); return
        }
        val owner = identity["uuid"]?.asString
        if (owner?.matches(Regex("[a-f0-9]{32}")) != true || owner != minecraftOwner) {
            comparisonStatus = "Reconnect your Minecraft account in Discord settings."; renderResults(); return
        }
        comparisonStatus = "Loading community comparison…"; renderResults()
        val archive = AcquisitionRepository.state
        fun currentOwner() = !closed && charts && CommunityClient.connected && owner == MC.instance.user.profileId.toString().replace("-", "") &&
            boundary == comparisonEpoch && token == CommunityClient.sessionToken &&
            ownerGeneration == CommunityClient.sessionGeneration && identity == CommunityClient.identity
        comparisonWorker.submit(this, ::currentOwner, {
            val data = parseJournalCommunityComparison(CommunityClient.exchange("/community/journal-comparison?period=$period", null, "GET", token), period)
            data to journalComparableLocalCount(archive, owner, data)
        }) { loaded -> MC.instance.execute {
            if (!currentOwner() || menuClosing) return@execute
            nextComparison = System.currentTimeMillis() + 60_000
            loaded.fold({ (data, local) -> comparison = data; comparisonLocalCount = local; comparisonStatus = "" },
                { comparisonStatus = "Community comparison unavailable. Retry in a minute." })
            renderResults()
        } }
    }

    private fun formatInt(value: Long) = "%,d".format(Locale.ROOT, value)
    private fun money(value: Double?) = value?.let(NumberUtils::condense) ?: "Unknown"
    private fun periodShort(period: JournalPeriod) = when (period) {
        JournalPeriod.ALL_TIME -> "All time"
        JournalPeriod.WEEK -> "This week"
        JournalPeriod.MONTH -> "This month"
        JournalPeriod.CUSTOM -> "Custom"
    }
    private fun scopeTitle(scope: TrackerScope) = when (scope) {
        TrackerScope.CURRENT_PROFILE -> "Current profile"
        TrackerScope.ALL_LOCAL_PROFILES -> "All profiles"
        TrackerScope.UNSCOPED_LEGACY -> "Unscoped"
    }
    private fun cardBox(pad: Int = 6) = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(4); padding(Insets.of(pad)); surface(MenuTheme.panel(true))
    }
    private fun column(gap: Int = 5) = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(gap) }
    private fun row(gap: Int = 4) = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(gap); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, action: (io.wispforest.owo.ui.component.ButtonComponent) -> Unit) =
        MenuButton(Component.literal(text), action).apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}
