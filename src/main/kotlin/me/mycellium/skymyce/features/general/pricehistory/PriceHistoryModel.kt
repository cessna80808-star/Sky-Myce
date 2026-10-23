package me.mycellium.skymyce.features.general.pricehistory

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class PriceRange(val title: String, val millis: Long?) {
    DAY("1 day", 86_400_000L), WEEK("1 week", 7 * 86_400_000L), MONTH("1 month", 30 * 86_400_000L), ALL("All time", null);
    override fun toString() = title
}

/** Bazaar items have buy and sell prices; auction items have prices of sold auctions. */
enum class PriceMarket(val first: String, val second: String) {
    BAZAAR("Instant buy", "Instant sell"), AUCTION("Lowest sale", "Average sale")
}

/** One time bucket. A missing value is unknown, never zero; [volume] counts auction sales and is 0 for the Bazaar. */
data class PriceSample(val at: Long, val first: Double?, val second: Double?, val volume: Long = 0)

data class SeriesStats(val latest: Double, val low: Double, val high: Double, val mean: Double, val first: Double) {
    /** Change from the first to the latest value in percent. */
    val change get() = (latest - first) / first * 100.0
}

data class PriceHistory(val market: PriceMarket, val range: PriceRange, val samples: List<PriceSample>) {
    val firstStats = seriesStats(samples.mapNotNull { it.first })
    val secondStats = seriesStats(samples.mapNotNull { it.second })
    val sales get() = samples.sumOf { it.volume }
}

fun seriesStats(values: List<Double>): SeriesStats? =
    values.takeIf { it.isNotEmpty() }?.let { SeriesStats(it.last(), it.min(), it.max(), it.average(), it.first()) }

private const val API = "https://sky.coflnet.com/api"

/**
 * Coflnet's item tag for a SkyBlock id in Bazaar form. Pets are `PET_<TYPE>` there, while ids such as `GOLDEN_DRAGON;4`
 * carry their rarity after a semicolon.
 */
fun coflnetTag(bazaarId: String, pet: Boolean): String? {
    val id = bazaarId.trim().uppercase()
    if (!id.matches(Regex("[A-Z0-9_;:\\-]{1,96}"))) return null
    return if (pet) "PET_" + id.substringBefore(';').removePrefix("PET_") else id
}

/** Coflnet's history endpoint for [tag]; Bazaar month and all-time use the plain history with and without a window. */
fun priceHistoryUrl(tag: String, market: PriceMarket, range: PriceRange, now: Long): String {
    val item = URLEncoder.encode(tag, Charsets.UTF_8)
    if (market == PriceMarket.AUCTION) return "$API/item/price/$item/history/${when (range) {
        PriceRange.DAY -> "day"; PriceRange.WEEK -> "week"; PriceRange.MONTH -> "month"; PriceRange.ALL -> "full"
    }}"
    return when (range) {
        PriceRange.DAY -> "$API/bazaar/$item/history/day"
        PriceRange.WEEK -> "$API/bazaar/$item/history/week"
        PriceRange.MONTH -> {
            val end = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.HOURS)
            val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            "$API/bazaar/$item/history?start=${format.format(end.minus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))}" +
                "&end=${format.format(end.atOffset(ZoneOffset.UTC))}"
        }
        PriceRange.ALL -> "$API/bazaar/$item/history"
    }
}

private val utcOffset = Regex("[+-]\\d{2}:\\d{2}$")

/** Coflnet times are UTC; most carry no offset. Null when unreadable. */
fun parseCoflnetTime(text: String): Long? = runCatching {
    if (text.endsWith("Z") || utcOffset.containsMatchIn(text)) OffsetDateTime.parse(text).toInstant().toEpochMilli()
    else LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()

private fun JsonObject.price(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it > 0 }

/**
 * Samples from a Coflnet history response, oldest first. Malformed entries are skipped; an entry with neither price is
 * dropped. A response that is not an array yields nothing.
 */
fun parsePriceHistory(json: JsonElement, market: PriceMarket): List<PriceSample> {
    val array = json as? JsonArray ?: return emptyList()
    return array.asSequence().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val time = entry.get(if (market == PriceMarket.BAZAAR) "timestamp" else "time")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let(::parseCoflnetTime) ?: return@mapNotNull null
        val sample = if (market == PriceMarket.BAZAAR) PriceSample(time, entry.price("buy"), entry.price("sell"))
            else PriceSample(time, entry.price("min"), entry.price("avg"),
                entry.get("volume")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.coerceIn(0, 1_000_000) ?: 0)
        sample.takeIf { it.first != null || it.second != null }
    }.sortedBy { it.at }.distinctBy { it.at }.toList()
}

/**
 * At most [limit] samples over equal time buckets: the lowest-sale series keeps each bucket's minimum, others average.
 * Stats are taken before downsampling, so lows and highs are never smoothed away.
 */
fun downsample(samples: List<PriceSample>, limit: Int, market: PriceMarket): List<PriceSample> {
    if (samples.size <= limit || limit < 2) return samples
    val start = samples.first().at
    val span = (samples.last().at - start).coerceAtLeast(1)
    return samples.groupBy { ((it.at - start).toDouble() / span * (limit - 1)).toInt() }.toSortedMap().values.map { bucket ->
        val firsts = bucket.mapNotNull { it.first }
        val seconds = bucket.mapNotNull { it.second }
        PriceSample(bucket.last().at,
            firsts.takeIf { it.isNotEmpty() }?.let { if (market == PriceMarket.AUCTION) it.min() else it.average() },
            seconds.takeIf { it.isNotEmpty() }?.average(), bucket.sumOf { it.volume })
    }
}

/** Compact coin amounts for axis labels and stats: 950, 12.4k, 1.25M, 3.1B. */
fun formatCoins(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    fun scaled(divisor: Double, suffix: String): String {
        val scaled = value / divisor
        val text = when {
            kotlin.math.abs(scaled) >= 100 -> "%.0f".format(java.util.Locale.ROOT, scaled)
            kotlin.math.abs(scaled) >= 10 -> "%.1f".format(java.util.Locale.ROOT, scaled)
            else -> "%.2f".format(java.util.Locale.ROOT, scaled)
        }
        return text + suffix
    }
    return when {
        magnitude >= 1e9 -> scaled(1e9, "B")
        magnitude >= 1e6 -> scaled(1e6, "M")
        magnitude >= 1e3 -> scaled(1e3, "k")
        magnitude >= 100 -> "%.0f".format(java.util.Locale.ROOT, value)
        else -> "%.1f".format(java.util.Locale.ROOT, value)
    }
}
