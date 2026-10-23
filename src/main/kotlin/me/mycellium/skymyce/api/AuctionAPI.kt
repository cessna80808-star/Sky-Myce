package me.mycellium.skymyce.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.general.auction.AuctionIndex
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.utils.MC
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.legacyStack
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.seconds

/** One paced page request at a time. Menus only read immutable snapshots. */
object AuctionAPI : SkyMyceModule() {
    override fun init() { me.mycellium.skymyce.features.general.auction.AuctionPreferences.initialize() }
    val index = AuctionIndex()
    private var worker = false
    private var nextRefresh = 0L
    private val itemCache = object : LinkedHashMap<String, ItemStack>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ItemStack>) = size > 128
    }
    private val decoding = mutableSetOf<String>()
    fun diagnostics(): Map<String, Long> = mapOf("auction.worker" to if (worker) 1L else 0L,
        "auction.itemCache" to itemCache.size.toLong(), "auction.decoding" to decoding.size.toLong(),
        "auction.rows" to index.view.auctions.size.toLong())

    fun beginBrowserSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (worker || now < nextRefresh || (!force && index.view.complete && now - index.view.updatedAt < 60000)) return
            worker = true
            nextRefresh = now + 60000
        }
        val token = index.begin()
        Scheduling.schedule(0.seconds) {
            try {
                var page = 0
                while (index.isCurrent(token)) {
                    val loaded = fetchPage(page)
                    if (!index.accept(token, page, loaded)) break
                    page++
                    delay(500)
                }
            } catch (failure: AuctionFetchFailure) { index.fail(token, failure.message ?: "Auction service unavailable") }
            catch (_: Exception) { index.fail(token, "Auction service unavailable or response invalid") }
            finally { synchronized(this@AuctionAPI) { worker = false } }
        }
    }

    fun stopBrowserSession() = index.cancel()

    /** Only visible listings become item stacks; cap both the decode queue and retained stacks. */
    fun item(auction: ActiveAuction, ready: (ItemStack) -> Unit) {
        itemCache[auction.uuid]?.let { ready(it); return }
        if (auction.encodedItem == null || decoding.size >= 40 || !decoding.add(auction.uuid)) return
        Scheduling.schedule(0.seconds) {
            val stack = decodeItem(auction.encodedItem) ?: ItemStack.EMPTY
            MC.instance.execute { decoding.remove(auction.uuid); itemCache[auction.uuid] = stack; ready(stack) }
        }
    }

    internal fun fetchPage(page: Int): AuctionPage = RuntimeProfiler.measure(RuntimeProfiler.Area.AUCTION_HTTP) {
        val connection = URI(auctionPageUrl(page)).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Sky-Hawk/2.4.2")
        try {
            if (connection.responseCode == 429) throw AuctionFetchFailure("Rate limited • try refresh in a minute")
            if (connection.responseCode != 200) throw AuctionFetchFailure("Auction service unavailable")
            require(connection.contentLengthLong <= MAX_PAGE_BYTES)
            val deadline = System.nanoTime() + 12_000_000_000L
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(System.nanoTime() < deadline) { "Auction response timed out" }
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_PAGE_BYTES)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            RuntimeProfiler.network(RuntimeProfiler.Area.AUCTION_HTTP, bytes.size.toLong())
            parseAuctionPage(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject, page)
        } finally { connection.disconnect() }
    }

    data class AuctionPage(val page: Int, val totalPages: Int, val totalAuctions: Int,
                           val lastUpdated: Long, val auctions: List<ActiveAuction>, val skipped: Int = 0)
    data class ActiveAuction(
        val uuid: String, val auctioneer: String, val start: Long, val end: Long,
        val name: String, val lore: String, val category: String, val rarity: String,
        val isBin: Boolean, val price: Long,
        val encodedItem: String? = null, val hasBids: Boolean = false,
    ) {
        val remaining get() = end - System.currentTimeMillis()
        val expired get() = remaining <= 0
        val priceLabel get() = if (isBin) "BIN price" else if (hasBids) "Current bid" else "Starting bid"
        val searchText = (name + " " + lore.replace(AUCTION_STYLE, "") + " " + category).lowercase()
    }
    private class AuctionFetchFailure(message: String) : Exception(message)
}

internal const val MAX_PAGE_BYTES = 8 * 1024 * 1024
internal const val MAX_ITEM_ENCODED = 32768
private val AUCTION_UUID = Regex("[a-f0-9]{32}")
private val AUCTION_STYLE = Regex("§.")
internal fun auctionPageUrl(page: Int): String {
    require(page in 0 until AuctionIndex.MAX_PAGES)
    return "https://api.hypixel.net/v2/skyblock/auctions?page=$page"
}

internal fun parseAuctionPage(json: JsonObject, requestedPage: Int): AuctionAPI.AuctionPage {
    require(json.get("success")?.asBoolean == true)
    val page = json.get("page").asInt
    val total = json.get("totalPages").asInt
    val count = json.get("totalAuctions").asInt
    val updated = json.get("lastUpdated").asLong
    val auctions = json.getAsJsonArray("auctions")
    require(page == requestedPage && page in 0 until total && total in 1..AuctionIndex.MAX_PAGES)
    require(count in 0..1000000 && updated > 0 && auctions.size() <= 2000)
    val parsed = auctions.mapNotNull { runCatching {
        val value = it.asJsonObject
        fun text(key: String, max: Int): String = value.get(key)?.asString.orEmpty().take(max).filter { c -> !c.isISOControl() || c == '\n' }
        val id = text("uuid", 36).replace("-", "")
        val seller = text("auctioneer", 36).replace("-", "")
        require(id.matches(AUCTION_UUID) && seller.matches(AUCTION_UUID))
        val start = value.get("start").asLong; val end = value.get("end").asLong
        val bid = value.get("starting_bid").asLong
        val highest = value.get("highest_bid_amount")?.asLong ?: 0L
        require(start > 0 && end > start && bid in 0..9007199254740991L && highest in 0..9007199254740991L)
        val bin = value.get("bin")?.asBoolean == true
        val bytes = value.get("item_bytes")
        val encoded = runCatching { if (bytes?.isJsonPrimitive == true) bytes.asString else bytes?.asJsonObject?.get("data")?.asString }.getOrNull()
            ?.takeIf { data -> data.length <= MAX_ITEM_ENCODED }
        AuctionAPI.ActiveAuction(id, seller, start, end, text("item_name", 160), text("item_lore", 4096),
            text("category", 32), text("tier", 32).uppercase().ifBlank { "UNKNOWN" }, bin,
            if (bin) bid else maxOf(bid, highest), encodedItem = encoded, hasBids = highest > 0)
    }.getOrNull() }
    return AuctionAPI.AuctionPage(page, total, count, updated, parsed, auctions.size() - parsed.size)
}

internal fun decodeItem(encoded: String): ItemStack? = runCatching {
    require(encoded.length in 1..MAX_ITEM_ENCODED)
    val bytes = Base64.getDecoder().decode(encoded)
    require(bytes.size <= 24576)
    val nbt = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter(2L * 1024 * 1024, 32))
    nbt.getList("i").getOrNull()?.takeIf { it.size <= 1 }?.firstNotNullOfOrNull { it.legacyStack() }
}.getOrNull()
