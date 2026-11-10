package me.mycellium.skymyce.features.general.auction

import com.google.gson.JsonParser
import me.mycellium.skymyce.api.*

fun checkAuctions() {
    val now = 1700000000000L
    fun auction(id: String, name: String = "Sword", price: Long = 100) = AuctionAPI.ActiveAuction(
        id.padStart(32, '0'), "a".repeat(32), now - 1000, now + 600000, name,
        "Sharpness VI", "weapon", "LEGENDARY", true, price)
    fun page(number: Int, time: Long = now, rows: List<AuctionAPI.ActiveAuction>) = AuctionAPI.AuctionPage(number, 2, 3, time, rows)
    check(auctionPageUrl(0).endsWith("?page=0") && auctionPageUrl(7).endsWith("?page=7"))
    check(runCatching { auctionPageUrl(-1) }.isFailure)
    fun response(page: Int, name: String) = JsonParser.parseString("""{"success":true,"page":$page,"totalPages":2,"totalAuctions":2,"lastUpdated":$now,"auctions":[{"uuid":"${"a".repeat(32)}","auctioneer":"${"b".repeat(32)}","start":${now - 1},"end":${now + 9999},"starting_bid":10,"highest_bid_amount":20,"bin":false,"item_name":"$name","tier":"LEGENDARY","category":"weapon"}]}""").asJsonObject
    check(parseAuctionPage(response(0, "First"), 0).auctions.single().name == "First")
    check(parseAuctionPage(response(1, "Later"), 1).auctions.single().name == "Later")
    check(runCatching { parseAuctionPage(response(0, "Wrong"), 1) }.isFailure)
    check(parseAuctionPage(response(0, "Bid"), 0).auctions.single().let { it.price == 20L && it.priceLabel == "Current bid" })
    val invalid = response(0, "Valid").apply { getAsJsonArray("auctions").add(JsonParser.parseString("{}")) }
    val parsedInvalid = parseAuctionPage(invalid, 0)
    check(parsedInvalid.auctions.size == 1 && parsedInvalid.skipped == 1)
    val bounded = AuctionIndex(); val generation = bounded.begin()
    bounded.accept(generation, 0, parsedInvalid.copy(totalPages = 1))
    check("invalid listings omitted" in bounded.view.message)
    val index = AuctionIndex()
    val token = index.begin()
    check(index.accept(token, 0, page(0, rows = listOf(auction("1")))))
    check(!index.view.complete && index.view.coverage.startsWith("Partial"))
    val query = AuctionSearch(query = "Hyperion sharpness")
    check(query.results(index.view.auctions, now).isEmpty())
    check(!index.accept(token, 1, page(1, rows = listOf(auction("1"), auction("2", "Hyperion")))))
    check(index.view.complete && index.view.auctions.size == 2 && query.results(index.view.auctions, now).single().name == "Hyperion")
    val saved = index.view.auctions
    val refresh = index.begin()
    index.accept(refresh, 0, page(0, now + 100, listOf(auction("3"))))
    check(index.view.auctions == saved && index.view.loading && index.view.stale)
    index.accept(refresh, 1, page(1, now + 200, listOf(auction("4"))))
    check(index.view.auctions == saved && !index.view.loading && index.view.stale)
    val newer = index.begin()
    check(!index.accept(refresh, 0, page(0, rows = listOf(auction("5")))))
    index.cancel()
    check(!index.accept(newer, 0, page(0, rows = listOf(auction("5")))))
    val rows = listOf(auction("3"), auction("1"), auction("2", price = 200), auction("4").copy(end = now))
    check(AuctionSearch(sort = AuctionSearch.Sort.PRICE_LOW_TO_HIGH, maxPrice = 150).results(rows, now).map { it.uuid.last() } == listOf('1', '3'))
    check(AuctionSearch(listingType = AuctionSearch.ListingType.BID).results(rows, now).isEmpty())
    check(AuctionSearch(query = "missing").results(rows, now).isEmpty())
    check(decodeItem("!invalid!") == null && decodeItem("a".repeat(MAX_ITEM_ENCODED + 1)) == null)
    check(parseAuctionPreferences(null).favorites.isEmpty())
    val restored = parseAuctionPreferences(JsonParser.parseString("""{"version":1,"search":{"query":"Saved","sort":"BAD","minPrice":-1},"favorites":[{"query":"x"},{"query":"x"}]}"""))
    check(restored.search.query == "Saved" && restored.search.minPrice == null && restored.favorites.size == 1)
    println("Auction checks passed: requested pages, later-page discovery, coherent snapshots, bounded item data, stable filters and saved searches")
}

fun main() = checkAuctions()
