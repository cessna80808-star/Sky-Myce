package me.mycellium.skymyce.features.garden.greenhouse

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.withCommunityBodyDeadline
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One product's top of book. [buyOrderPrice] is the best bid (what a new buy order competes with) and
 * [sellOfferPrice] the best ask. Volumes are the summed amounts of the best five levels on each side.
 */
data class BazaarProduct(
    val id: String,
    val buyOrderPrice: Double?,
    val sellOfferPrice: Double?,
    val buyVolume: Double? = null,
    val sellVolume: Double? = null,
    val buyMovingWeek: Double? = null,
    val sellMovingWeek: Double? = null,
    val buyOrders: Int? = null,
    val sellOrders: Int? = null,
)

data class BazaarSnapshot(val source: String, val observedAt: Long, val retrievedAt: Long, val products: List<BazaarProduct>)

/** Public market traffic has no player identity or credentials. The planner owns its single refresh job. */
internal class BazaarTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private var backendRetryAt = 0L
    private val retryDeadlines = ConcurrentHashMap<String, Long>()

    fun fetch(now: Long): BazaarSnapshot {
        if (now >= backendRetryAt) {
            val origin = runCatching { URI(CommunityClient.configuration.getProperty("backend")) }.getOrNull()
            if (origin?.scheme == "https" && origin.userInfo == null && origin.rawQuery == null && origin.fragment == null) {
                runCatching { return parseBazaarMarket(get(origin.resolve("/community/bazaar/market?active=true")), now, normalized = true) }
                    .exceptionOrNull()?.let { if (it is InterruptedException || it is BazaarMarketFailure && it.rateLimited) throw it }
            }
            backendRetryAt = now + 10 * 60_000
        }
        return parseBazaarMarket(get(URI("https://api.hypixel.net/v2/skyblock/bazaar")), now).copy(source = "Hypixel public Bazaar · direct fallback")
    }

    private fun get(uri: URI): JsonObject {
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        val origin = "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host.lowercase(Locale.ROOT)}:${uri.port.takeIf { it >= 0 } ?: defaultPort}"
        val now = System.currentTimeMillis()
        retryDeadlines[origin]?.let { deadline ->
            if (deadline > now) throw BazaarMarketFailure(true, deadline)
            retryDeadlines.remove(origin, deadline)
        }
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            if (response.statusCode() == 429) {
                val received = System.currentTimeMillis()
                val fallback = if (received > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else received + 60_000
                val requested = parseBazaarRetryAt(response.headers().firstValue("Retry-After").orElse(null), received)
                val deadline = requireNotNull(retryDeadlines.merge(origin, maxOf(fallback, requested ?: fallback)) { previous, next -> maxOf(previous, next) })
                runCatching { response.body().close() }
                throw BazaarMarketFailure(true, deadline)
            }
            response.body().close()
            throw BazaarMarketFailure(false)
        }
        return readBazaarMarketBody(response.body())
    }
}

internal class BazaarMarketFailure(val rateLimited: Boolean, val retryAt: Long? = null) : Exception()

private const val DEPTH_LEVELS = 5
private val PRODUCT_ID = Regex("[A-Z0-9_:;-]{1,100}")

/** Full public order books exceed the community DTO reader's deliberate 256 KiB ceiling. */
internal fun readBazaarMarketBody(body: InputStream): JsonObject = withCommunityBodyDeadline(body, Duration.ofSeconds(15)) { input ->
    val maximum = 8 * 1024 * 1024
    val bytes = input.readNBytes(maximum + 1)
    require(bytes.size <= maximum) { "Bazaar market response exceeds 8 MiB" }
    val json = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) }
        catch (failure: com.google.gson.JsonParseException) { throw IllegalArgumentException("Bazaar market response is invalid JSON", failure) }
    require(json.isJsonObject) { "Bazaar market response must be a JSON object" }
    json.asJsonObject
}

/** Retry-After has no short upper cap: overflowing positive waits saturate rather than retrying early. */
internal fun parseBazaarRetryAt(header: String?, now: Long): Long? {
    if (now < 0) return null
    val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.all { it in '0'..'9' }) {
        var seconds = 0L
        for (character in value) {
            val digit = character - '0'
            if (seconds > (Long.MAX_VALUE - digit) / 10) return Long.MAX_VALUE
            seconds = seconds * 10 + digit
        }
        return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
    }
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull()?.takeIf { it >= now }
}

/** Hypixel's names describe instant transactions: sell_summary is bids; buy_summary is asks. */
internal fun parseBazaarMarket(json: JsonObject, now: Long, normalized: Boolean = false): BazaarSnapshot {
    require(now > 0 && (if (normalized) json["ok"]?.asBoolean else json["success"]?.asBoolean) == true)
    if (normalized) require(json.number("version") == 1.0)
    val observed = json.number(if (normalized) "observedAt" else "lastUpdated")?.toLong() ?: error("Market timestamp unavailable")
    val retrieved = if (normalized) json.number("retrievedAt")?.toLong() ?: error("Retrieval timestamp unavailable") else now
    require(observed > 0 && retrieved > 0 && observed <= now + 10_000 && retrieved <= now + 10_000)
    val entries = if (normalized) {
        val array = json.getAsJsonArray("products") ?: error("Products unavailable")
        require(array.size() <= 10_000)
        array.map { item -> require(item.isJsonObject); val product = item.asJsonObject; (product.text("id", 100) ?: error("Product id unavailable")) to product }
    } else {
        val products = json.getAsJsonObject("products") ?: error("Products unavailable")
        require(products.size() <= 10_000)
        products.entrySet().map { (id, value) -> require(value.isJsonObject); id to value.asJsonObject }
    }
    val seen = hashSetOf<String>()
    val products = entries.map { (id, product) ->
        require(seen.add(id) && id.matches(PRODUCT_ID))
        if (!normalized) require(product.text("product_id", 100) == id)
        val quick = if (normalized) product else product.getAsJsonObject("quick_status") ?: JsonObject()
        // Both sources describe depth as the best five price levels.
        fun depth(name: String, bids: Boolean): Pair<Double?, Double?> {
            val levels = product.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null to null
            require(levels.size() <= 30)
            val valid = levels.map { value ->
                require(value.isJsonObject)
                val level = value.asJsonObject
                val price = level.number(if (normalized) "price" else "pricePerUnit")
                val amount = level.number("amount")
                require(price != null && price > 0 && amount != null && amount >= 0)
                price to amount
            }.filter { it.second > 0 }
            val best = (if (bids) valid.sortedByDescending { it.first } else valid.sortedBy { it.first }).take(DEPTH_LEVELS)
            return best.firstOrNull()?.first to best.sumOf { it.second }
        }
        val bid = depth(if (normalized) "buyDepth" else "sell_summary", true)
        val ask = depth(if (normalized) "sellDepth" else "buy_summary", false)
        BazaarProduct(id,
            if (normalized) product.number("buyPrice") ?: bid.first else bid.first,
            if (normalized) product.number("sellPrice") ?: ask.first else ask.first,
            bid.second, ask.second,
            quick.number(if (normalized) "buyMovingWeek" else "sellMovingWeek"),
            quick.number(if (normalized) "sellMovingWeek" else "buyMovingWeek"),
            quick.number(if (normalized) "buyOrders" else "sellOrders")?.toInt(),
            quick.number(if (normalized) "sellOrders" else "buyOrders")?.toInt())
    }
    return BazaarSnapshot(if (normalized) json.text("source", 160) ?: "Sky-Myce shared Bazaar" else "Hypixel public Bazaar", observed, retrieved, products)
}

private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.asDouble?.takeIf { it.isFinite() && it in 0.0..1e15 }
private fun JsonObject.text(key: String, maximum: Int): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString?.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
