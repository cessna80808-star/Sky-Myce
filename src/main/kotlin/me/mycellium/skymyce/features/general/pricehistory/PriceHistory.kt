package me.mycellium.skymyce.features.general.pricehistory

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import me.mycellium.mixin.BazaarAssistContainerAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.PriceHistoryConfig as Settings
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ScreenKeyPressedEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI

/**
 * NEU-style price graphs: hover an item in a menu and press the configured key. History comes from Coflnet's public
 * API; responses are cached for ten minutes, so switching ranges back and forth does not refetch.
 */
object PriceHistoryFeature : SkyMyceModule() {
    private const val MAX_BYTES = 2_000_000
    private const val TTL = 10 * 60_000L
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Sky-Myce price history").apply { isDaemon = true } }
    private data class Entry(val at: Long, val result: Result<PriceHistory>)
    private val cache = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 48
    }
    private val loading = HashSet<String>()

    @Subscription
    fun onKey(event: ScreenKeyPressedEvent.Pre) {
        if (!isEnabled() || !Settings.enabled || Settings.key == GLFW.GLFW_KEY_UNKNOWN || event.key != Settings.key) return
        val screen = event.screen as? AbstractContainerScreen<*> ?: return
        if (screen.focused is EditBox) return
        val stack = (screen as BazaarAssistContainerAccessor).`skymyce$getHoveredSlot`()?.item?.takeUnless { it.isEmpty } ?: return
        val id = stack.getSkyBlockId() ?: return
        val market = if (BazaarAPI.getProduct(id.bazaarId) != null) PriceMarket.BAZAAR else PriceMarket.AUCTION
        val tag = coflnetTag(id.bazaarId, id.isPet) ?: return
        event.cancel()
        // History covers every reforge and star level, so title it with the base item rather than the hovered variant.
        val name = if (id.isPet) stack.hoverName.string else id.toItem().takeUnless { it.isEmpty }?.hoverName?.string ?: stack.hoverName.string
        navigateMenu(PriceHistoryScreen(screen, tag, name, market, Settings.defaultRange))
    }

    /** Cached history, or null while it loads; [ready] runs on the client thread once a fetch finishes. */
    fun history(tag: String, market: PriceMarket, range: PriceRange, ready: () -> Unit): Result<PriceHistory>? {
        val key = "$market:$tag:$range"
        val now = System.currentTimeMillis()
        // Failures retry after a minute rather than ten.
        cache[key]?.takeIf { now - it.at < if (it.result.isSuccess) TTL else 60_000L }?.let { return it.result }
        if (!loading.add(key)) return null
        worker.execute {
            val result = runCatching { fetch(tag, market, range, now) }
            result.exceptionOrNull()?.let { SkyMyce.logger.warn("Price history for {} ({}) unavailable: {}", tag, range, it.message) }
            MC.instance.execute { loading.remove(key); cache[key] = Entry(System.currentTimeMillis(), result); ready() }
        }
        return null
    }

    private fun fetch(tag: String, market: PriceMarket, range: PriceRange, now: Long): PriceHistory {
        val request = HttpRequest.newBuilder(URI(priceHistoryUrl(tag, market, range, now))).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").header("User-Agent", "Sky-Myce price history").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            when (response.statusCode()) {
                200 -> {}
                400, 404 -> throw PriceHistoryUnavailable("No price history for this item")
                429 -> throw PriceHistoryUnavailable("Coflnet is rate limiting requests; try again in a minute")
                else -> throw PriceHistoryUnavailable("Coflnet returned HTTP ${response.statusCode()}")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_BYTES) throw PriceHistoryUnavailable("Price history response was too large")
                output.write(buffer, 0, read)
            }
            val samples = parsePriceHistory(JsonParser.parseString(output.toString(Charsets.UTF_8)), market)
            if (samples.isEmpty()) throw PriceHistoryUnavailable("No sales or prices recorded in this range")
            return PriceHistory(market, range, samples)
        }
    }
}

class PriceHistoryUnavailable(message: String) : Exception(message)

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
