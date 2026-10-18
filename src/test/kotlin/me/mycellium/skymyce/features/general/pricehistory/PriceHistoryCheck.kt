package me.mycellium.skymyce.features.general.pricehistory

import com.google.gson.JsonParser
import java.time.Instant

private fun checkParsing() {
    // Shapes as Coflnet returns them (2026-10-17): auction buckets use time/min/avg/volume, Bazaar newest first.
    val auction = JsonParser.parseString("""[
        {"min":1.095E+09,"max":1.095E+09,"avg":1095000000.0,"volume":1,"time":"2026-10-10T19:00:00"},
        {"min":430000000.0,"max":1.9E+09,"avg":896704811.6666666,"volume":12,"time":"2026-10-10T20:00:00"},
        {"min":null,"avg":"bad","volume":3,"time":"2026-10-10T21:00:00"},
        {"min":437000000.0,"avg":853399999.4,"volume":5}
    ]""")
    val samples = parsePriceHistory(auction, PriceMarket.AUCTION)
    check(samples.size == 2) { "Entries without a price or time are dropped: $samples" }
    check(samples[0].at == Instant.parse("2026-10-10T19:00:00Z").toEpochMilli()) { "Times without an offset are UTC" }
    check(samples[1].first == 430_000_000.0 && samples[1].second == 896_704_811.6666666 && samples[1].volume == 12L)

    val bazaar = JsonParser.parseString("""[
        {"maxBuy":1397.7,"buy":1397.4,"sell":1249.1,"timestamp":"2026-10-17T12:00:00"},
        {"buy":1349.2,"sell":1280.0,"timestamp":"2026-10-17T10:00:00.382"},
        {"buy":0,"sell":-5,"timestamp":"2026-10-17T08:00:00"}
    ]""")
    val book = parsePriceHistory(bazaar, PriceMarket.BAZAAR)
    check(book.size == 2 && book.first().first == 1349.2 && book.last().second == 1249.1) { "Sorted oldest first; zero is not a price: $book" }
    check(parsePriceHistory(JsonParser.parseString("""{"slug":"item_not_found"}"""), PriceMarket.AUCTION).isEmpty())
    check(parseCoflnetTime("2026-10-17T10:00:00Z") == Instant.parse("2026-10-17T10:00:00Z").toEpochMilli())
    check(parseCoflnetTime("yesterday") == null)
}

private fun checkEndpoints() {
    val now = Instant.parse("2026-10-17T19:42:10Z").toEpochMilli()
    check(priceHistoryUrl("HYPERION", PriceMarket.AUCTION, PriceRange.ALL, now) == "https://sky.coflnet.com/api/item/price/HYPERION/history/full")
    check(priceHistoryUrl("ENCHANTED_DIAMOND", PriceMarket.BAZAAR, PriceRange.WEEK, now) == "https://sky.coflnet.com/api/bazaar/ENCHANTED_DIAMOND/history/week")
    check(priceHistoryUrl("ENCHANTED_DIAMOND", PriceMarket.BAZAAR, PriceRange.MONTH, now) ==
        "https://sky.coflnet.com/api/bazaar/ENCHANTED_DIAMOND/history?start=2026-09-17T19:00:00&end=2026-10-17T19:00:00")
    check(coflnetTag("golden_dragon;4", pet = true) == "PET_GOLDEN_DRAGON")
    check(coflnetTag("ENCHANTMENT_SHARPNESS_7", pet = false) == "ENCHANTMENT_SHARPNESS_7")
    check(coflnetTag("../admin", pet = false) == null && coflnetTag("", pet = false) == null) { "Only plain item ids reach a URL" }
}

private fun checkDownsampleAndStats() {
    val raw = (0 until 1000).map { PriceSample(it * 60_000L, 100.0 + it % 10, 50.0, volume = 1) }
    val reduced = downsample(raw, 100, PriceMarket.AUCTION)
    check(reduced.size <= 100 && reduced.size >= 90) { "${reduced.size}" }
    check(reduced.sumOf { it.volume } == 1000L) { "Sales are summed, not dropped" }
    // Every full bucket spans all ten values; only the final sample sits in a bucket of its own.
    check(reduced.dropLast(1).all { it.first == 100.0 } && reduced.last().at == raw.last().at) { "The lowest-sale series keeps each bucket's minimum" }
    check(downsample(raw.take(10), 100, PriceMarket.BAZAAR) == raw.take(10))
    val stats = seriesStats(listOf(100.0, 80.0, 150.0, 120.0))!!
    check(stats.low == 80.0 && stats.high == 150.0 && stats.latest == 120.0 && stats.mean == 112.5 && stats.change == 20.0)
    check(seriesStats(emptyList()) == null)
    check(formatCoins(2_152_345_234.0) == "2.15B" && formatCoins(12_400.0) == "12.4k" && formatCoins(950.0) == "950" && formatCoins(1.25e6) == "1.25M")
}

fun main() {
    checkParsing(); checkEndpoints(); checkDownsampleAndStats()
    println("Price history checks passed: Coflnet parsing, endpoints, item tags, downsampling and stats")
}
