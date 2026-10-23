package me.mycellium.skymyce.features.general.auction

import me.mycellium.skymyce.api.AuctionAPI

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
