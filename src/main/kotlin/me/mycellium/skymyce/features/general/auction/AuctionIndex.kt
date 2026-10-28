package me.mycellium.skymyce.features.general.auction

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling

data class AuctionSnapshot(val auctions: List<AuctionAPI.ActiveAuction> = emptyList(), val pages: Int = 0,
    val totalPages: Int = 0, val updatedAt: Long = 0, val complete: Boolean = false,
    val loading: Boolean = false, val stale: Boolean = false, val message: String = "Not loaded", val version: Long = 0) {
    val coverage get() = "${if (complete) "Complete" else "Partial"} • $pages/$totalPages API pages"
}

/** Deterministic snapshot assembly; responses from another refresh or API update are never combined. */
class AuctionIndex {
    @Volatile var view = AuctionSnapshot()
        private set
    private var generation = 0L
    private var sourceTime = 0L
    private var pages = 0
    private var totalPages = 0
    private var itemBytes = 0
    private var skipped = 0
    private val staging = linkedMapOf<String, AuctionAPI.ActiveAuction>()
    @Synchronized fun begin(): Long {
        generation++; sourceTime = 0; pages = 0; totalPages = 0; itemBytes = 0; skipped = 0; staging.clear()
        view = view.copy(loading = true, stale = view.auctions.isNotEmpty(), message = "Indexing auctions…", version = view.version + 1)
        return generation
    }
    @Synchronized fun isCurrent(token: Long) = token == generation && view.loading
    @Synchronized fun accept(token: Long, requested: Int, page: AuctionAPI.AuctionPage): Boolean {
        if (!isCurrent(token)) return false
        if (page.page != requested || requested != pages || page.totalPages !in 1..MAX_PAGES ||
            (pages > 0 && (page.lastUpdated != sourceTime || page.totalPages != totalPages))) {
            fail(token, "API snapshot changed • saved results retained; refresh later")
            return false
        }
        sourceTime = page.lastUpdated; totalPages = page.totalPages; pages++; skipped += page.skipped
        for (auction in page.auctions) {
            if (auction.uuid in staging) continue
            if (staging.size >= MAX_AUCTIONS) {
                publish(false); fail(token, "Index limit reached • partial results"); return false
            }
            val bytes = auction.encodedItem?.length ?: 0
            staging[auction.uuid] = if (itemBytes + bytes <= 48 * 1024 * 1024) { itemBytes += bytes; auction }
                else auction.copy(encodedItem = null)
        }
        val complete = pages == totalPages
        // Keep the previous complete snapshot visible until its replacement is coherent and complete.
        if (!view.complete || complete) publish(complete)
        if (complete) staging.clear() // The published snapshot holds the listings; drop the 80k-entry lookup map.
        else view = view.copy(message = "Refreshing • $pages/$totalPages API pages • showing saved snapshot", version = view.version + 1)
        return !complete
    }
    private fun publish(complete: Boolean) {
        view = AuctionSnapshot(staging.values.toList(), pages, totalPages, sourceTime, complete, !complete,
            false, (if (complete) "Up to date" else "Indexing • partial results") +
                (if (skipped > 0) " • $skipped invalid listings omitted" else ""), view.version + 1)
    }
    @Synchronized fun fail(token: Long, message: String) {
        if (token != generation) return
        view = view.copy(loading = false, stale = view.auctions.isNotEmpty(), message = message, version = view.version + 1)
    }
    @Synchronized fun cancel() {
        generation++
        if (view.loading) view = view.copy(loading = false, message = "Indexing paused • reopen to refresh", version = view.version + 1)
    }
    companion object { const val MAX_PAGES = 200; const val MAX_AUCTIONS = 80000 }
}

private val nameStyle = Regex("§.")

val AUCTION_RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "ULTIMATE", "SPECIAL", "VERY_SPECIAL", "ADMIN")

/** The screen's query state and result selection, kept separate from UI code. */
data class AuctionSearch(
    val query: String = "",
    val category: String? = null,
    val rarity: String? = null,
    val listingType: ListingType = ListingType.ALL,
    val sort: Sort = Sort.ENDING_SOON,
    val page: Int = 0,
    val minPrice: Long? = null,
    val maxPrice: Long? = null,
) {
    fun results(auctions: List<AuctionAPI.ActiveAuction>, now: Long = System.currentTimeMillis()): List<AuctionAPI.ActiveAuction> {
        val terms = query.take(160).trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return auctions.asSequence()
            .filter { it.end > now }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .filter { rarity == null || it.rarity == rarity }
            .filter { listingType.matches(it) }
            .filter { minPrice == null || it.price >= minPrice }
            .filter { maxPrice == null || it.price <= maxPrice }
            .filter { auction -> terms.all { it in auction.searchText } }
            .sortedWith(sort.comparator.thenBy { it.uuid })
            .toList()
    }

    fun withQuery(query: String) = copy(query = query, page = 0)
    fun withCategory(category: String?) = copy(category = category, page = 0)
    fun withRarity(rarity: String?) = copy(rarity = rarity, page = 0)
    fun withListingType(listingType: ListingType) = copy(listingType = listingType, page = 0)
    fun withSort(sort: Sort) = copy(sort = sort, page = 0)

    enum class Sort(val label: String, val comparator: Comparator<AuctionAPI.ActiveAuction>) {
        PRICE_LOW_TO_HIGH("Low to High", compareBy { it.price }),
        PRICE_HIGH_TO_LOW("High to Low", compareByDescending { it.price }),
        ENDING_SOON("Ending soon", compareBy { it.end }),
        RARITY("Rarity", compareBy { AUCTION_RARITIES.indexOf(it.rarity).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }),
        NEWEST("Newest", compareByDescending { it.start }),
        NAME("Item name", compareBy { it.name.replace(nameStyle, "").lowercase() }),
    }

    enum class ListingType(val label: String) {
        ALL("All listings"),
        BIN("BIN only"),
        BID("Auction bids only");

        fun matches(auction: AuctionAPI.ActiveAuction) = this == ALL || (this == BIN) == auction.isBin
    }
}

data class SavedAuctionSearches(val version: Int = 1, val search: AuctionSearch = AuctionSearch(), val favorites: List<AuctionSearch> = emptyList())

internal fun parseAuctionPreferences(json: JsonElement?): SavedAuctionSearches {
    if (json == null) return SavedAuctionSearches()
    val root = json.asJsonObject
    require(root.get("version")?.asInt == 1)
    fun search(value: JsonElement?): AuctionSearch {
        val data = value?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        fun text(key: String, limit: Int) = runCatching { data.get(key)?.asString?.take(limit) }.getOrNull()
        fun money(key: String) = runCatching { data.get(key)?.asLong?.takeIf { it in 0..9007199254740991L } }.getOrNull()
        return AuctionSearch(text("query", 160).orEmpty(), text("category", 32),
            text("rarity", 32)?.takeIf { it in AUCTION_RARITIES },
            AuctionSearch.ListingType.entries.find { it.name == text("listingType", 32) } ?: AuctionSearch.ListingType.ALL,
            AuctionSearch.Sort.entries.find { it.name == text("sort", 32) } ?: AuctionSearch.Sort.ENDING_SOON,
            minPrice = money("minPrice"), maxPrice = money("maxPrice"))
    }
    return SavedAuctionSearches(search = search(root.get("search")), favorites = root.get("favorites")
        ?.takeIf { it.isJsonArray }?.asJsonArray?.take(8)?.map(::search)?.distinct().orEmpty())
}

object AuctionPreferences {
    var value = SavedAuctionSearches()
        private set
    var revision = 0L
        private set
    private var loaded = false
    private var edited = false
    private lateinit var writer: StateWriter<SavedAuctionSearches>
    fun initialize() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("auction_searches.json"))
        writer = StateWriter({ file.write(Gson().toJsonTree(it)) },
            { action -> Scheduling.schedule(500.milliseconds) { action() } },
            { SkyMyce.logger.warn("Could not save auction preferences; previous file retained") })
        Scheduling.schedule(0.milliseconds) {
            val saved = runCatching { parseAuctionPreferences(file.read()) }.getOrElse {
                file.preserveOriginal(); SavedAuctionSearches()
            }
            MC.instance.execute { if (!edited) value = saved; loaded = true; revision++; if (edited) writer.submit(value) }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Hawk-Auction-Preferences"))
    }
    fun update(search: AuctionSearch, favorites: List<AuctionSearch> = value.favorites) {
        edited = true
        value = SavedAuctionSearches(search = search.copy(page = 0), favorites = favorites.take(8).map { it.copy(page = 0) }.distinct())
        revision++
        if (loaded) writer.submit(value)
    }
}
