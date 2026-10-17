package me.mycellium.skymyce.features.general.pricehistory

import com.google.gson.JsonParser
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
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

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
        navigateMenu(PriceHistoryScreen(screen, tag, stack.hoverName.string, market, Settings.defaultRange))
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
