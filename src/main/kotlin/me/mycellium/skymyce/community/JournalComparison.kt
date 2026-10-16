package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.features.instances.dungeons.tracker.*
import kotlin.math.abs

internal data class JournalCommunityComparison(val period: String, val startAt: Long?, val endAt: Long,
    val participants: Long, val totalDrops: Long, val mean: Double?, val ownSharedCount: Long?,
    val updatedAt: Long, val source: String, val coverage: String)

internal fun journalCommunityPeriod(filter: JournalFilter): String? {
    if (filter.query.isNotBlank() || filter.activity != null || filter.floor != null || filter.includeLegacy ||
        filter.scope == TrackerScope.UNSCOPED_LEGACY) return null
    return when (filter.period) {
        JournalPeriod.ALL_TIME -> "all-time"
        JournalPeriod.WEEK -> "weekly"
        JournalPeriod.MONTH -> "monthly"
        JournalPeriod.CUSTOM -> null
    }
}

internal fun journalPeriodCounts(archive: AcquisitionArchive, filter: JournalFilter, context: AcquisitionContext?, now: Long) =
    listOf(JournalPeriod.ALL_TIME, JournalPeriod.WEEK, JournalPeriod.MONTH).associateWith { period ->
        rngJournal(archive, filter.copy(period = period, includeLegacy = false), context, now).counts.values.sum()
    }

/** Shared rng_drops counts deduplicated acquisition reports, not the quantity of items in each receipt. */
internal fun journalComparableLocalCount(archive: AcquisitionArchive, owner: String, comparison: JournalCommunityComparison): Long {
    require(owner.matches(Regex("[a-f0-9]{32}")))
    return archive.events.asSequence().filter { event ->
        event.origin == "confirmed" && event.context?.account?.replace("-", "")?.lowercase() == owner &&
            DigestRngCatalog.public(event.item, event.activity) &&
            (comparison.startAt == null || event.occurredAt >= comparison.startAt) && event.occurredAt <= comparison.endAt
    }.count().toLong()
}

internal fun parseJournalCommunityComparison(json: JsonObject, expectedPeriod: String): JournalCommunityComparison {
    fun integer(key: String): Long {
        val raw = requireNotNull(json[key]); require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber)
        val number = raw.asDouble; require(number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number == raw.asLong.toDouble())
        return raw.asLong
    }
    fun boolean(key: String) = requireNotNull(json[key]).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    }
    fun text(key: String): String = requireNotNull(json[key]).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { value ->
            require(value.length in 1..1500 && value.none(Char::isISOControl))
        }
    }
    require(boolean("ok") && integer("version") == 1L && text("metric") == "rng_drops")
    val period = text("period"); require(period == expectedPeriod && period in setOf("all-time", "weekly", "monthly"))
    val end = integer("endAt"); require(end > 0)
    val start = requireNotNull(json["startAt"]).takeUnless { it.isJsonNull }?.let { integer("startAt") }
    val selected = when (period) { "weekly" -> JournalPeriod.WEEK; "monthly" -> JournalPeriod.MONTH; else -> JournalPeriod.ALL_TIME }
    require(start == journalBounds(JournalFilter(period = selected), end).first && (start == null || start <= end))
    val participants = integer("participantCount"); val total = integer("totalDrops")
    val rawMean = requireNotNull(json["meanPerIdentity"])
    val mean = if (rawMean.isJsonNull) null else rawMean.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asDouble.also { number -> require(number.isFinite() && number >= 0) } }
    val expectedMean = if (participants > 0) total.toDouble() / participants else null
    require(if (participants == 0L) total == 0L && mean == null else mean != null &&
        abs(mean - expectedMean!!) <= maxOf(1e-9, Math.ulp(expectedMean) * 4))
    val ownAvailable = boolean("ownAvailable"); val ownRaw = requireNotNull(json["ownSharedCount"])
    val own = if (ownRaw.isJsonNull) null else integer("ownSharedCount")
    require((own != null) == ownAvailable && (own == null || own <= total))
    val updatedAt = integer("updatedAt"); require(updatedAt <= end)
    return JournalCommunityComparison(period, start, end, participants, total, mean, own, updatedAt, text("source"), text("coverage"))
}
