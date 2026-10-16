package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.math.abs

private val context = BazaarContext("12345678-1234-1234-1234-123456789abc", "Orange")
private const val NOW = 1_800_000_000_000L
private fun near(actual: Double?, expected: Double) { check(actual != null && abs(actual - expected) < .00001) { "$actual != $expected" } }
private fun rejected(work: () -> Unit) { check(runCatching(work).isFailure) }
private fun event(id: String, kind: BazaarEventKind, quantity: Long = 0, coins: Double? = null, order: String? = null,
    position: String? = "position-a", at: Long = NOW, cumulative: Long? = null, evidence: BazaarEvidence = BazaarEvidence.OBSERVED,
    price: Double? = null, fee: Double? = null) = BazaarEvent(id, context, "WHEAT", kind, at, quantity, price, coins, fee,
    order, position, "session-a", cumulative, evidence)
private fun journal(vararg events: BazaarEvent) = events.fold(BazaarArchive()) { archive, value -> recordBazaarEvent(archive, value) }

fun main() {
    checkBazaarAccounting()
    checkBazaarFinder()
    checkBazaarLifecycleAndReplay()
    checkBazaarObservations()
    checkCanonicalBazaarReceipts()
    checkNativeBazaarPartialFills()
    checkDelayedBazaarReceipts()
    checkBazaarStorage()
    checkBazaarFillNotices()
    checkBazaarMarketCacheSeparation()
    checkBazaarDepthAndNames()
    println("Bazaar checks passed: FIFO net realized profit, partial/cumulative receipts, fill notices, unknown basis, corrections, manual-only finder, freshness, capital, relists, next steps, replay redaction, segmented history and separate market cache")
}

private fun checkBazaarAccounting() {
    val buy = event("buy-a", BazaarEventKind.BUY_FILL, 100, 1000.0, at = NOW - 10000, price = 10.0)
    val sell = event("sell-a", BazaarEventKind.SELL_FILL, 40, 474.0, at = NOW, price = 12.0, fee = 6.0)
    var archive = journal(buy, sell)
    var ledger = reconcileBazaar(archive, context)
    near(ledger.realizations.single().netProfit, 74.0)
    near(ledger.realizations.single().cost, 400.0)
    near(ledger.positions.single().costBasis, 600.0)
    check(ledger.positions.single().remaining == 60L && ledger.realizations.single().eligible)
    near(bazaarStats(ledger).realizedProfit, 74.0)
    near(bazaarStats(ledger).eligibleProfit, 74.0)
    near(bazaarStats(ledger).roi, .185)
    check(recordBazaarEvent(archive, sell) == archive)
    rejected { recordBazaarEvent(archive, sell.copy(coins = 475.0)) }
    archive = recordBazaarEvent(archive, event("inventory-a", BazaarEventKind.INVENTORY_MOVE, 50, 9000.0))
    archive = recordBazaarEvent(archive, event("collect-coins", BazaarEventKind.COINS_COLLECT, 40, 474.0))
    ledger = reconcileBazaar(archive, context)
    near(bazaarStats(ledger).realizedProfit, 74.0)
    near(bazaarPortfolio(ledger, 300.0).unclaimedCoins, 0.0)
    check(ledger.positions.single().remaining == 60L)

    val fifo = journal(event("batch-a", BazaarEventKind.BUY_FILL, 10, 100.0, at = NOW - 3),
        event("batch-b", BazaarEventKind.BUY_FILL, 10, 200.0, at = NOW - 2),
        event("sale-fifo", BazaarEventKind.SELL_FILL, 15, 375.0, at = NOW - 1))
    val fifoLedger = reconcileBazaar(fifo, context)
    near(bazaarStats(fifoLedger).realizedProfit, 175.0)
    near(fifoLedger.positions.single().costBasis, 100.0)
    check(fifoLedger.realizations.sumOf { it.quantity } == 15L)

    val cumulative = journal(event("order", BazaarEventKind.BUY_ORDER, 100, order = "order-1", price = 10.0, at = NOW - 4),
        event("fill-20", BazaarEventKind.BUY_FILL, coins = 200.0, order = "order-1", cumulative = 20, at = NOW - 3),
        event("fill-20-reconnect", BazaarEventKind.BUY_FILL, coins = 200.0, order = "order-1", cumulative = 20, at = NOW - 2),
        event("fill-50", BazaarEventKind.BUY_FILL, coins = 300.0, order = "order-1", cumulative = 50, at = NOW - 1),
        event("item-collect", BazaarEventKind.BUY_COLLECT, 50, order = "order-1"))
    val cumulativeLedger = reconcileBazaar(cumulative, context)
    check(cumulativeLedger.positions.single().acquired == 50L && cumulativeLedger.orders.single().filled == 50L)
    near(cumulativeLedger.positions.single().costBasis, 500.0)
    near(bazaarPortfolio(cumulativeLedger, 1000.0).availableCash, 1000.0)
    near(bazaarPortfolio(cumulativeLedger, 1000.0).committedOrders, 500.0)
    near(bazaarPortfolio(cumulativeLedger, 1000.0, purseExcludesReservations = false).availableCash, 500.0)
    check(cumulativeLedger.positions.single().pendingItems == 0L)
    check(reconcileBazaar(recordBazaarEvent(cumulative, event("too-many", BazaarEventKind.BUY_FILL, coins = 10000.0,
        order = "order-1", cumulative = 101)), context).orders.single().filled == 50L)

    val unknown = reconcileBazaar(journal(event("unknown-buy", BazaarEventKind.BUY_FILL, 10), event("unknown-sale", BazaarEventKind.SELL_FILL, 5, 5000.0)), context)
    check(unknown.realizations.single().netProfit == null && !unknown.realizations.single().eligible)
    near(bazaarStats(unknown).realizedProfit, 0.0)
    check(bazaarPortfolio(unknown, 0.0).inventoryCost == null)
    val missing = reconcileBazaar(journal(event("missing-sale", BazaarEventKind.SELL_FILL, 10, 1000.0)), context)
    check(missing.realizations.single().cost == null && !missing.realizations.single().eligible)
    near(bazaarStats(missing).realizedProfit, 0.0)
    val uncertain = reconcileBazaar(journal(buy.copy(evidence = BazaarEvidence.ESTIMATED), sell), context)
    near(bazaarStats(uncertain).realizedProfit, 74.0); near(bazaarStats(uncertain).eligibleProfit, 0.0)
    val corrected = recordBazaarEvent(journal(buy, sell), event("correction", BazaarEventKind.CORRECTION, coins = 420.0,
        evidence = BazaarEvidence.MANUAL).copy(replacesId = "sell-a"))
    near(bazaarStats(reconcileBazaar(corrected, context)).realizedProfit, 20.0)
    near(bazaarStats(reconcileBazaar(corrected, context)).eligibleProfit, 0.0)
    check(reconcileBazaar(archive, context.copy(profile = "Apple")).realizations.isEmpty())
    rejected { journal(buy.copy(coins = Double.NaN)) }
    rejected { journal(buy.copy(quantity = -1)) }

    var relists = journal(buy)
    repeat(6) { i -> relists = recordBazaarEvent(relists, event("relist-$i", BazaarEventKind.RELIST, at = NOW + i)) }
    var held = reconcileBazaar(relists, context).positions.single()
    check(held.relistCount == 6 && !bazaarRelistAdvice(held, null).warn)
    val seventh = event("relist-seven", BazaarEventKind.RELIST, at = NOW + 7)
    relists = recordBazaarEvent(recordBazaarEvent(relists, seventh), seventh)
    relists = recordBazaarEvent(relists, event("replacement", BazaarEventKind.SELL_ORDER, 100, order = "replacement-order", price = 15.0, at = NOW + 8))
    held = reconcileBazaar(relists, context).positions.single()
    check(held.relistCount == 7 && bazaarRelistAdvice(held, null).warn)
    val stable = BazaarProduct("WHEAT", "Wheat", 10.0, 15.0, 10000.0, 10000.0, 1e8, 1e8)
    check(bazaarRelistAdvice(held, stable, freshness = BazaarFreshness.FRESH).recommendation == "Leave it / keep waiting")
    val falling = stable.copy(history = listOf(BazaarSample(NOW - 3, 20.0, 30.0), BazaarSample(NOW - 2, 15.0, 20.0), BazaarSample(NOW - 1, 10.0, 15.0)))
    check("insta-sell" in bazaarRelistAdvice(held, falling, freshness = BazaarFreshness.FRESH).recommendation)
    near(bazaarStats(reconcileBazaar(relists, context)).realizedProfit, 0.0)
}

private fun checkBazaarFinder() {
    val product = BazaarProduct("WHEAT", "Wheat", 10.0, 12.0, 1e6, 1e6, 1e8, 1e8, 100, 100)
    val snapshot = BazaarSnapshot("Allowed fixture", NOW, NOW, listOf(product))
    val filters = BazaarFilters(minProfit = 0.0, minRoi = 0.0, minLiquidity = 0.0, minConfidence = 0.0, maxCapital = 1000.0, maxConcentration = 1.0)
    val found = scanBazaar(snapshot, 1000.0, filters = filters, now = NOW).single()
    near(found.buyPrice, 10.1); near(found.sellPrice, 11.9)
    near(found.capital, found.quantity * 10.1)
    near(found.netProfit, found.quantity * (11.9 * .9875 - 10.1))
    check(found.capital <= 1000.0 && found.fillSecondsLow != null && found.fillSecondsHigh!! >= found.fillSecondsLow)
    check(found.warnings.any { "Estimated" in it } && found.warnings.any { "Mayor" in it })
    check(scanBazaar(snapshot, 1000.0, filters = filters, now = NOW + 300001).isEmpty())
    check(scanBazaar(snapshot.copy(permitted = false), 1000.0, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot.copy(observedAt = NOW + 100000), 1000.0, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot.copy(products = listOf(product, product)), 1000.0, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot.copy(products = listOf(product.copy(buyOrderPrice = null))), 1000.0, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot.copy(products = listOf(product.copy(sellVolume = null))), 1000.0, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot, null, filters = filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot, 1000.0, filters = filters.copy(minConfidence = 1.0), now = NOW).isEmpty())
    check(scanBazaar(snapshot, 1000.0, filters = filters.copy(maxFillSeconds = .001), now = NOW).isEmpty())
    check(scanBazaar(snapshot, 1000.0, filters = filters, now = NOW + 200000).single().confidence < found.confidence)
    check(bazaarFreshness(snapshot, NOW + 120000) == BazaarFreshness.FRESH)
    check(bazaarFreshness(snapshot, NOW + 120001) == BazaarFreshness.AGING)
    check(bazaarFreshness(snapshot, NOW + 300001) == BazaarFreshness.STALE)
    val slow = snapshot.copy(products = listOf(product.copy(buyMovingWeek = 10.0, sellMovingWeek = 10.0)))
    check(scanBazaar(slow, 1000.0, filters = filters, now = NOW).isNotEmpty())
    check(scanBazaar(snapshot, 1000.0, filters = filters, risk = BazaarRisk.CONSERVATIVE, now = NOW).single().score <
        scanBazaar(snapshot, 1000.0, filters = filters, risk = BazaarRisk.AGGRESSIVE, now = NOW).single().score)
    val futureHistory = product.copy(history = listOf(BazaarSample(NOW + 1000, 1e9, 1e9)))
    near(scanBazaar(snapshot.copy(products = listOf(futureHistory)), 1000.0, filters = filters, now = NOW).single().confidence, found.confidence)
    val ledger = reconcileBazaar(journal(event("held", BazaarEventKind.BUY_FILL, 100, 1000.0)), context)
    check(scanBazaar(snapshot, 1000.0, ledger, filters.copy(maxConcentration = .4), now = NOW).isEmpty())
    check(scanBazaar(snapshot, 1000.0, ledger.copy(positions = ledger.positions.map { it.copy(costBasis = null) }), filters, now = NOW).isEmpty())
    check(scanBazaar(snapshot, 1000.0, ledger.copy(positions = ledger.positions.map { it.copy(costBasis = 0.0) }), filters, now = NOW).isNotEmpty())
    val unknownOrder = BazaarOrder("unknown-order", "unknown-position", "WHEAT", true, 100, 0, 0, null, BazaarOrderStatus.OPEN, NOW, NOW, true)
    check(scanBazaar(snapshot, 1000.0, BazaarLedger(orders = listOf(unknownOrder)), filters, now = NOW).isEmpty())
    val otherUnknown = ledger.copy(positions = ledger.positions.map { it.copy(product = "OTHER", costBasis = null) })
    check(scanBazaar(snapshot, 1000.0, otherUnknown, filters, now = NOW).isNotEmpty())
    near(bazaarPortfolio(ledger, 500.0, mapOf("WHEAT" to product)).availableCash, 500.0)
    near(bazaarPortfolio(ledger, 500.0, mapOf("WHEAT" to product)).estimatedInventoryValue, 987.5)
    near(bazaarStats(ledger).realizedProfit, 0.0)
    val history = rememberBazaarMarket(BazaarMarketArchive(), snapshot, NOW)
    check(rememberBazaarMarket(history, snapshot, NOW) == history)
    check(history.aggregates.single().count == 1)
    val past = snapshot.copy(observedAt = NOW - 1000, retrievedAt = NOW - 1000)
    val day = NOW / 86400000
    val enriched = enrichBazaarMarket(BazaarMarketArchive(snapshots = listOf(past, snapshot.copy(observedAt = NOW + 1000)),
        aggregates = listOf(BazaarMarketAggregate("WHEAT", day - 1, 2, 18.0, 22.0, 242.0, 11.0, 11.0, 100.0),
            BazaarMarketAggregate("WHEAT", day, 1, 1e9, 1e9, 1e18, 1e9, 1e9, 1e9))), snapshot)
    check(enriched.products.single().history.size == 2 && enriched.products.single().history.all { it.at < NOW && it.sellPrice < 100 })
    check(rememberBazaarMarket(BazaarMarketArchive(), enriched, NOW).snapshots.single().products.single().history.isEmpty())
    val mayorHistory = rememberBazaarMarket(BazaarMarketArchive(), snapshot.copy(mayor = "Diana", mayorObservedAt = NOW), NOW)
    check(mayorHistory.regimes.single().mayor == "Diana")
    val sameMayor = rememberBazaarMarket(mayorHistory, snapshot.copy(observedAt = NOW + 1000, retrievedAt = NOW + 1000, mayor = "Diana", mayorObservedAt = NOW + 1000), NOW + 1000)
    check(sameMayor.regimes.size == 1)
    val nextMayor = rememberBazaarMarket(sameMayor, snapshot.copy(observedAt = NOW + 2000, retrievedAt = NOW + 2000, mayor = "Derpy", mayorObservedAt = NOW + 2000), NOW + 2000)
    check(nextMayor.regimes.size == 2)
    val samples = (1..12).map { BazaarSample(NOW - it * 1000, 10.0, 12.0) }
    near(evaluateBazaarHistory(samples).meanAbsoluteError, 0.0)
    near(evaluateBazaarHistory(samples).rangeCoverage, 1.0)
}

private fun checkBazaarLifecycleAndReplay() {
    val lifecycle = BazaarSessionLifecycle()
    check(lifecycle.start("session-a", context, null, NOW) == null)
    check(lifecycle.start("session-a", context, "hub:mini1", NOW) != null)
    check(lifecycle.observe(context, "hub:mini1", NOW + 1) == null && lifecycle.active != null)
    check(lifecycle.observe(context, "hub:mini2", NOW + 2)?.endedAt == NOW + 2)
    check(lifecycle.observe(context, "hub:mini1", NOW + 3) == null && lifecycle.active == null)
    lifecycle.start("session-b", context, "hub:mini1", NOW + 4)
    check(lifecycle.observe(context, null, NOW + 5) != null && lifecycle.active == null)
    val session = BazaarSession("session-a", context, NOW - 10000, NOW + 1000, "hub:mini1")
    val archive = journal(event("buy", BazaarEventKind.BUY_FILL, 10, 100.0, at = NOW - 1000),
        event("sell", BazaarEventKind.SELL_FILL, 10, 150.0)).copy(sessions = listOf(session))
    val summary = bazaarPublicationPayload(archive, "session-a", BazaarReplayOptions(), listed = false)
    check(summary.getAsJsonArray("timeline").isEmpty && !summary.has("username") && summary["mode"].asString == "summary")
    near(summary.get("realizedProfit").asDouble, 50.0)
    val hidden = bazaarPublicationPayload(archive, "session-a", BazaarReplayOptions(detail = true, hideTransactions = false, hideValues = true), listed = false)
    val hiddenJson = Gson().toJson(hidden)
    listOf("WHEAT", "12345678", "position-a", "session-a", "netProceeds", "quantity", "150.0", "100.0", "\"profit\"").forEach { check(it !in hiddenJson) { "Redacted value leaked: $it" } }
    near(hidden.get("realizedProfit").asDouble, 0.0)
    check(hidden.getAsJsonArray("timeline").size() == 1 && hidden.getAsJsonArray("timeline")[0].asJsonObject.keySet() == setOf("at"))
    val full = bazaarPublicationPayload(archive, "session-a", BazaarReplayOptions(true, false, false, false, false), listed = true)
    check(full["visibility"].asString == "community" && full.getAsJsonArray("timeline")[0].asJsonObject.get("item").asString == "WHEAT")
    check(bazaarPublicationPayload(archive, "session-a", BazaarReplayOptions(true, false, true), listed = false).getAsJsonArray("timeline").isEmpty)
}

private fun checkBazaarStorage() {
    val folder = Files.createTempDirectory("skymyce-bazaar-check-").toAbsolutePath().normalize()
    try {
        val storage = BazaarStorage(folder)
        val events = (0..2050).map { index -> event("stored-$index", BazaarEventKind.INVENTORY_MOVE, at = NOW + index) }
        var archive = BazaarArchive(events = events)
        storage.save(archive)
        check(Files.list(folder).use { it.count() } == 3L)
        check(BazaarStorage(folder).load() == archive)
        archive = recordBazaarEvent(archive, event("new", BazaarEventKind.BUY_FILL, 3, 12.0))
        storage.save(archive)
        check(BazaarStorage(folder).load() == archive)
        rejected { storage.save(archive.copy(events = archive.events.drop(1))) }
        check(Gson().fromJson(storage.export(archive), BazaarArchive::class.java) == archive)
        val index = folder.resolve("index.json")
        Files.writeString(index, "{\"storageVersion\":999,\"eventCount\":0}")
        val damaged = BazaarStorage(folder)
        rejected { damaged.load() }
        check(!damaged.writable)
        rejected { damaged.save(BazaarArchive()) }
        check(Files.readString(index).contains("999"))
        Files.writeString(folder.resolve(".skymyce-interrupted.tmp"), "private interrupted journal")
        Files.writeString(folder.resolve("unrelated.txt"), "retain unrelated data")
        damaged.delete()
        check(Files.list(folder).use { it.count() } == 1L && Files.readString(folder.resolve("unrelated.txt")) == "retain unrelated data")
        check(damaged.load() == BazaarArchive())
        val invalid = JsonObject().apply { addProperty("version", 999) }
        rejected { parseBazaarArchive(invalid) }
    } finally {
        check(folder.fileName.toString().startsWith("skymyce-bazaar-check-"))
        Files.list(folder).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(folder)
    }
}

private fun checkBazaarObservations() {
    val observer = BazaarObserver()
    val products = listOf(BazaarProduct("WHEAT", "Wheat", 10.0, 12.0))
    var archive = BazaarArchive()
    fun ledger() = reconcileBazaar(archive, context)
    fun apply(events: List<BazaarEvent>) { events.forEach { archive = recordBazaarEvent(archive, it) } }
    fun row(buy: Boolean, amount: Int, filled: Int, order: String?, price: Int = if (buy) 10 else 12) =
        BazaarObservedSlot(11, "WHEAT", if (buy) "§aBUY: Wheat" else "§6SELL: Wheat",
            listOf("Order amount: ${amount}x", "Price per unit: $price coins", "Filled: $filled/$amount (${filled * 100 / amount}%)"), order)
    check(observer.chat("[MVP+] Other: [Bazaar] Claimed 355.5 coins from selling 30x Wheat at 12 each!", context, ledger(), products, NOW, "session-a").isEmpty())
    check(observer.container("A random chest", listOf(row(true, 100, 100, "fake")), context, ledger(), products, NOW, "session-a").isEmpty())
    apply(observer.container("Manage Orders", listOf(row(true, 100, 30, "server-buy")), context, ledger(), products, NOW, "session-a"))
    check(ledger().orders.single().filled == 30L)
    near(ledger().positions.single().costBasis, 300.0)
    check(observer.container("Manage Orders", listOf(row(true, 100, 30, "server-buy")), context, ledger(), products, NOW + 1, "session-a").isEmpty())
    observer.reset()
    check(observer.container("Manage Orders", listOf(row(true, 100, 30, "server-buy")), context, ledger(), products, NOW + 2, "session-a").isEmpty())
    val collectItems = "[Bazaar] Claimed 30x Wheat worth 300 coins bought for 10 each!"
    apply(observer.chat(collectItems, context, ledger(), products, NOW + 3, "session-a"))
    check(ledger().orders.single().collected == 30L && ledger().positions.single().pendingItems == 0L)
    check(observer.chat(collectItems, context, ledger(), products, NOW + 4, "session-a").isEmpty())
    apply(observer.container("Manage Orders", listOf(row(false, 30, 0, "server-sell")), context, ledger(), products, NOW + 5, "session-a"))
    apply(observer.container("Manage Orders", listOf(row(false, 30, 30, "server-sell")), context, ledger(), products, NOW + 6, "session-a"))
    check(ledger().realizations.single().netProfit == null && !ledger().realizations.single().eligible)
    val claim = "[Bazaar] Claimed 355.5 coins from selling 30x Wheat at 12 each!"
    apply(observer.chat(claim, context, ledger(), products, NOW + 7, "session-a"))
    near(bazaarStats(ledger()).realizedProfit, 55.5)
    near(bazaarStats(ledger()).eligibleProfit, 55.5)
    near(bazaarPortfolio(ledger(), 50.0).unclaimedCoins, 0.0)
    check(observer.chat(claim, context, ledger(), products, NOW + 8, "session-a").isEmpty())
    observer.reset()
    check(observer.container("Manage Orders", listOf(row(false, 30, 30, "server-sell")), context, ledger(), products, NOW + 9, "session-a").isEmpty())
    near(bazaarStats(ledger()).realizedProfit, 55.5)

    val fresh = BazaarObserver()
    val empty = BazaarLedger()
    val identical = fresh.container("Manage Orders", listOf(row(true, 100, 0, "one"), row(true, 100, 0, "two")), context, empty, products, NOW, null)
    check(identical.count { it.kind == BazaarEventKind.BUY_ORDER } == 2)
    val ambiguous = BazaarObserver().container("Manage Orders", listOf(row(true, 100, 0, null), row(true, 100, 0, null)), context, empty, products, NOW, null)
    check(ambiguous.all { it.kind == BazaarEventKind.OBSERVATION && it.evidence == BazaarEvidence.ESTIMATED })
    val standalone = BazaarObserver()
    val text = "[Bazaar] Buy Order Setup! 100x Wheat for 1,000 coins."
    val setup = standalone.chat(text, context, empty, products, NOW, null)
    check(setup.single().kind == BazaarEventKind.BUY_ORDER)
    check(standalone.chat(text, context, empty, products, NOW + 1, null).isEmpty())
    val second = standalone.chat(text, context, empty, products, NOW + 1000, null)
    check(second.single().id != setup.single().id && second.single().kind == BazaarEventKind.OBSERVATION)
    check(standalone.chat("[Bazaar] Claimed NaN coins from selling 30x Wheat at 12 each!", context, empty, products, NOW + 2000, null).all { it.kind == BazaarEventKind.OBSERVATION })

    val correlated = BazaarObserver()
    var live = BazaarArchive()
    fun liveLedger() = reconcileBazaar(live, context)
    fun observe(events: List<BazaarEvent>) { events.forEach { live = recordBazaarEvent(live, it) } }
    observe(correlated.chat("[Bazaar] Buy Order Setup! 100x Wheat for 1,000 coins.", context, liveLedger(), products, NOW, "session-a"))
    val actualBuy = BazaarObservedSlot(20, "WHEAT", "BUY Wheat", listOf("Order amount: 100x", "Price per unit: 10 coins", "Filled: 0.03k/0.1k 30%", "You have 30 items to claim!"))
    observe(correlated.container("Your Bazaar Orders", listOf(actualBuy), context, liveLedger(), products, NOW + 1, "session-a"))
    check(liveLedger().orders.single().filled == 30L && !liveLedger().orders.single().uncertain)
    observe(correlated.chat(collectItems, context, liveLedger(), products, NOW + 2, "session-a"))
    observe(correlated.chat("[Bazaar] Sell Offer Setup! 30x Wheat for 360 coins.", context, liveLedger(), products, NOW + 3, "session-a"))
    val actualSell = BazaarObservedSlot(11, "WHEAT", "SELL Wheat", listOf("Offer amount: 30x", "Price per unit: 12 coins", "Filled: 30/30 100%!"))
    observe(correlated.container("Your Bazaar Orders", listOf(actualSell), context, liveLedger(), products, NOW + 4, "session-a"))
    observe(correlated.chat(claim, context, liveLedger(), products, NOW + 5, "session-a"))
    near(bazaarStats(liveLedger()).realizedProfit, 55.5)
    near(bazaarStats(liveLedger()).eligibleProfit, 55.5)
    val buyOrder = liveLedger().orders.single { it.buy }
    observe(correlated.chat("[Bazaar] Cancelled! Refunded 700 coins from cancelling Buy Order!", context, liveLedger(), products, NOW + 6, "session-a"))
    check(liveLedger().orders.first { it.id == buyOrder.id }.status == BazaarOrderStatus.CANCELLED)

    val aliasObserver = BazaarObserver()
    var aliases = BazaarArchive()
    fun aliasLedger() = reconcileBazaar(aliases, context)
    fun aliasApply(events: List<BazaarEvent>) { events.forEach { aliases = recordBazaarEvent(aliases, it) } }
    val books = listOf(BazaarProduct("ENCHANTMENT_ULTIMATE_WISE_5", "Enchantment ultimate wise 5", 100.0, 120.0))
    aliasApply(aliasObserver.chat("[Bazaar] Buy Order Setup! 10x Ultimate Wise V for 1,000 coins.", context, aliasLedger(), books, NOW, null))
    check(aliasLedger().pending.size == 1)
    val book = BazaarObservedSlot(20, "ENCHANTMENT_ULTIMATE_WISE_5", "BUY Ultimate Wise V", listOf("Order amount: 10x", "Price per unit: 100 coins", "Filled: 0/10 0%"))
    aliasApply(aliasObserver.container("Your Bazaar Orders", listOf(book), context, aliasLedger(), books, NOW + 1, null))
    check(aliasLedger().pending.isEmpty() && !aliasLedger().orders.single().uncertain)
    aliasApply(aliasObserver.chat("[Bazaar] Claimed 10x Ultimate Wise V worth 1,000 coins bought for 100 each!", context, aliasLedger(), emptyList(), NOW + 2, null))
    check(aliasLedger().positions.single().remaining == 10L && aliasLedger().positions.single().pendingItems == 0L)
    near(aliasLedger().positions.single().costBasis, 1000.0)

    val flippedObserver = BazaarObserver()
    var flippedArchive = journal(event("flip-buy-order", BazaarEventKind.BUY_ORDER, 100, order = "flip-buy", price = 10.0, at = NOW - 2),
        event("flip-buy-fill", BazaarEventKind.BUY_FILL, 100, 1000.0, order = "flip-buy", cumulative = 100, at = NOW - 1))
    val converted = flippedObserver.chat("[Bazaar] Order Flipped! 100x Wheat for 9,876,543 coins of total expected profit.", context,
        reconcileBazaar(flippedArchive, context), products, NOW, "session-a")
    check(converted.single().kind == BazaarEventKind.OBSERVATION)
    converted.forEach { flippedArchive = recordBazaarEvent(flippedArchive, it) }
    val convertedRows = flippedObserver.container("Your Bazaar Orders", listOf(row(false, 100, 0, null)), context,
        reconcileBazaar(flippedArchive, context), products, NOW + 1, "session-a")
    check(convertedRows.map { it.kind } == listOf(BazaarEventKind.BUY_COLLECT, BazaarEventKind.SELL_ORDER))
    convertedRows.forEach { flippedArchive = recordBazaarEvent(flippedArchive, it) }
    val flippedLedger = reconcileBazaar(flippedArchive, context)
    check(flippedLedger.positions.single().pendingItems == 0L && flippedLedger.positions.single().listed == 100L)
    near(flippedLedger.orders.single { !it.buy }.price, 12.0)
    near(bazaarStats(flippedLedger).realizedProfit, 0.0)
    val cancelled = flippedObserver.chat("[Bazaar] Cancelled! Refunded 100x Wheat from cancelling Sell Offer!", context,
        flippedLedger, products, NOW + 2, "session-a")
    check(cancelled.single().kind == BazaarEventKind.CANCEL)

    val gap = event("gap", BazaarEventKind.GAP, at = NOW + 10)
    val next = event("new-after-gap", BazaarEventKind.BUY_FILL, 10, 100.0, position = "new-position", at = NOW + 11)
    val sale = event("new-after-gap-sale", BazaarEventKind.SELL_FILL, 10, 150.0, position = "new-position", at = NOW + 12)
    val recovered = reconcileBazaar(journal(event("old", BazaarEventKind.BUY_FILL, 10, 100.0), gap, next, sale), context)
    near(bazaarStats(recovered).eligibleProfit, 50.0)
    check(recovered.positions.first { it.id == "position-a" }.uncertain)
}

private fun checkCanonicalBazaarReceipts() {
    val identities = mapOf("undead essence" to "ESSENCE_UNDEAD", "ultimate wise i" to "ENCHANTMENT_ultimate_wise_1", "wiki tiki shard" to "SHARD_WIKI_TIKI")
    val products = listOf(BazaarProduct("ESSENCE_UNDEAD", "Essence undead", 2.0, 3.0),
        BazaarProduct("ENCHANTMENT_ULTIMATE_WISE_1", "Enchantment ultimate wise 1", 2.0, 3.0),
        BazaarProduct("SHARD_WIKI_TIKI", "Shard wiki tiki", 2.0, 3.0))
    fun receipt(name: String) = "[Bazaar] Buy Order Setup! 100x $name for 200 coins."
    for ((name, identity) in identities) {
        val observer = BazaarObserver { identities[it] }
        val placed = observer.chat(receipt(name), context, BazaarLedger(), products, NOW, null).single()
        check(placed.kind == BazaarEventKind.BUY_ORDER && placed.product == identity.uppercase() && placed.quantity == 100L)
        val ledger = reconcileBazaar(journal(placed), context)
        check(ledger.orders.single().quantity == 100L && !ledger.orders.single().uncertain)
        check(ledger.realizations.isEmpty() && ledger.positions.single().acquired == 0L)
        near(bazaarStats(ledger).realizedProfit, 0.0)
    }
    // Passive receipts use known local identities even while no market snapshot is available.
    val withoutMarket = BazaarObserver { identities[it] }.chat(receipt("Undead Essence"), context, BazaarLedger(), emptyList(), NOW, null).single()
    check(withoutMarket.product == "ESSENCE_UNDEAD" && withoutMarket.kind == BazaarEventKind.BUY_ORDER)
    fun pending(observer: BazaarObserver, market: List<BazaarProduct> = products): BazaarEvent {
        val event = observer.chat(receipt("Undead Essence"), context, BazaarLedger(), market, NOW, null).single()
        check(event.kind == BazaarEventKind.OBSERVATION && event.product == "BAZAAR" && event.evidence == BazaarEvidence.ESTIMATED)
        check(event.quantity == 100L); near(event.unitPrice, 2.0); near(event.coins, 200.0)
        val ledger = reconcileBazaar(journal(event), context)
        check(ledger.orders.isEmpty() && ledger.positions.isEmpty() && ledger.realizations.isEmpty() && ledger.pending.size == 1)
        return event
    }
    pending(BazaarObserver())
    pending(BazaarObserver(), emptyList())
    pending(BazaarObserver { "invalid\nidentity" })
    pending(BazaarObserver { error("Repository unavailable") })
    pending(BazaarObserver { identities[it] }, listOf(BazaarProduct("OTHER", "Undead Essence", 2.0, 3.0)))
    pending(BazaarObserver { identities[it] }, listOf(BazaarProduct("ESSENCE_UNDEAD", "Undead Essence", 2.0, 3.0),
        BazaarProduct("OTHER", "Undead Essence", 2.0, 3.0)))

    val nativeBook = BazaarObservedSlot(20, "ENCHANTMENT_ultimate_wise_1", "BUY Ultimate Wise I",
        listOf("Order amount: 100x", "Price per unit: 2 coins", "Filled: 0/100 0%"), "native-book")
    val native = BazaarObserver().container("Your Bazaar Orders", listOf(nativeBook), context, BazaarLedger(), products, NOW, null).single()
    check(native.kind == BazaarEventKind.BUY_ORDER && native.product == "ENCHANTMENT_ULTIMATE_WISE_1")

    // Conflicting observed labels remain ambiguous even if a repository lookup chooses one candidate.
    val ambiguous = BazaarObserver { identities[it] }
    val lore = listOf("Order amount: 100x", "Price per unit: 2 coins", "Filled: 0/100 0%")
    ambiguous.container("Your Bazaar Orders", listOf(BazaarObservedSlot(11, "ESSENCE_UNDEAD", "BUY Undead Essence", lore, "first"),
        BazaarObservedSlot(12, "OTHER", "BUY Undead Essence", lore, "second")), context, BazaarLedger(), products, NOW - 1, null)
    pending(ambiguous)
}

private fun checkNativeBazaarPartialFills() {
    val price = 5450.2
    val product = "ENCHANTED_GUNPOWDER"
    val products = listOf(BazaarProduct(product, "Enchanted gunpowder", price, 5500.0))
    val observer = BazaarObserver()
    var archive = journal(event("partial-order-744", BazaarEventKind.BUY_ORDER, 744, order = "partial-buy-744", price = price)
        .copy(product = product, positionId = "powder-position"))
    fun ledger() = reconcileBazaar(archive, context)
    fun apply(events: List<BazaarEvent>) { events.forEach { archive = recordBazaarEvent(archive, it) } }
    fun row(progress: String) = BazaarObservedSlot(20, product, "BUY Enchanted Gunpowder",
        listOf("Order amount: 744x", "Price per unit: 5,450.2 coins", "Filled: $progress"))
    check(ledger().orders.single().filled == 0L && ledger().orders.single().collected == 0L)

    val first = observer.container("Your Bazaar Orders", listOf(row("32/744 (4.3%)")), context, ledger(), products, NOW + 1, null)
    check(first.single().kind == BazaarEventKind.BUY_FILL && first.single().quantity == 32L && first.single().cumulativeFilled == 32L)
    near(first.single().coins, 32 * price)
    apply(first)
    check(ledger().orders.single().filled == 32L && ledger().orders.single().collected == 0L)
    check(ledger().positions.single().acquired == 32L && ledger().positions.single().pendingItems == 32L)
    near(ledger().positions.single().costBasis, 32 * price)
    check(ledger().realizations.isEmpty()); near(bazaarStats(ledger()).realizedProfit, 0.0)
    check(observer.container("Your Bazaar Orders", listOf(row("32/744 (4.3%)")), context, ledger(), products, NOW + 2, null).isEmpty())

    val next = observer.container("Manage Orders", listOf(row("128/744 17.2%")), context, ledger(), products, NOW + 3, null)
    check(next.single().kind == BazaarEventKind.BUY_FILL && next.single().quantity == 96L && next.single().cumulativeFilled == 128L)
    near(next.single().coins, 96 * price)
    apply(next)
    check(ledger().orders.single().filled == 128L && ledger().orders.single().collected == 0L)
    check(ledger().positions.single().acquired == 128L && ledger().positions.single().pendingItems == 128L)
    near(ledger().positions.single().costBasis, (32 + 96) * price)
    check(archive.events.none { it.kind == BazaarEventKind.BUY_COLLECT } && ledger().realizations.isEmpty())
    near(bazaarStats(ledger()).realizedProfit, 0.0)
}

private fun checkDelayedBazaarReceipts() {
    val products = listOf(BazaarProduct("WHEAT", "Wheat", 10.0, 12.0))
    val buyObserver = BazaarObserver()
    var buys = journal(event("receipt-buy-order", BazaarEventKind.BUY_ORDER, 100, order = "receipt-buy", price = 10.0, at = NOW - 10))
    fun buyLedger() = reconcileBazaar(buys, context)
    fun buyApply(events: List<BazaarEvent>) { events.forEach { buys = recordBazaarEvent(buys, it) } }
    val buyClaim = "[Bazaar] Claimed 10x Wheat worth 100 coins bought for 10 each!"
    buyApply(buyObserver.chat(buyClaim, context, buyLedger(), products, NOW, "session-a"))
    check(buyLedger().orders.single().filled == 10L && buyLedger().orders.single().collected == 10L)
    val delayedBuy = buyObserver.chat(buyClaim, context, buyLedger(), products, NOW + 500, "session-a")
    check(delayedBuy.isNotEmpty() && delayedBuy.all { it.kind == BazaarEventKind.OBSERVATION })
    buyApply(delayedBuy)
    check(buyLedger().positions.single().acquired == 10L)
    near(buyLedger().positions.single().costBasis, 100.0)
    buyApply(buyObserver.container("Your Bazaar Orders", listOf(BazaarObservedSlot(20, "WHEAT", "BUY Wheat",
        listOf("Order amount: 100x", "Price per unit: 10 coins", "Filled: 20/100 20%"))), context, buyLedger(), products, NOW + 600, "session-a"))
    buyApply(buyObserver.chat(buyClaim, context, buyLedger(), products, NOW + 650, "session-a"))
    check(buyLedger().orders.single().filled == 20L && buyLedger().orders.single().collected == 20L)
    check(buyLedger().positions.single().acquired == 20L)
    near(buyLedger().positions.single().costBasis, 200.0)
    buyObserver.reset()
    val replayedBuy = buyObserver.chat(buyClaim, context, buyLedger(), products, NOW + 1500, "session-a")
    check(replayedBuy.all { it.kind == BazaarEventKind.OBSERVATION })
    buyApply(replayedBuy)
    check(buyLedger().positions.single().acquired == 20L)

    val sellObserver = BazaarObserver()
    var sales = journal(event("receipt-stock", BazaarEventKind.BUY_FILL, 100, 1000.0, at = NOW - 10),
        event("receipt-sell-order", BazaarEventKind.SELL_ORDER, 100, order = "receipt-sell", price = 12.0, at = NOW - 9))
    fun sellLedger() = reconcileBazaar(sales, context)
    fun sellApply(events: List<BazaarEvent>) { events.forEach { sales = recordBazaarEvent(sales, it) } }
    val sellClaim = "[Bazaar] Claimed 118.5 coins from selling 10x Wheat at 12 each!"
    sellApply(sellObserver.chat(sellClaim, context, sellLedger(), products, NOW, "session-a"))
    near(bazaarStats(sellLedger()).realizedProfit, 18.5)
    val delayedSell = sellObserver.chat(sellClaim, context, sellLedger(), products, NOW + 500, "session-a")
    check(delayedSell.isNotEmpty() && delayedSell.all { it.kind == BazaarEventKind.OBSERVATION })
    sellApply(delayedSell)
    check(sellLedger().positions.single().sold == 10L && sellLedger().positions.single().remaining == 90L)
    near(bazaarStats(sellLedger()).realizedProfit, 18.5)
    sellApply(sellObserver.container("Your Bazaar Orders", listOf(BazaarObservedSlot(11, "WHEAT", "SELL Wheat",
        listOf("Offer amount: 100x", "Price per unit: 12 coins", "Filled: 20/100 20%"))), context, sellLedger(), products, NOW + 600, "session-a"))
    sellApply(sellObserver.chat(sellClaim, context, sellLedger(), products, NOW + 650, "session-a"))
    check(sellLedger().orders.single().filled == 20L && sellLedger().orders.single().collected == 20L)
    check(sellLedger().positions.single().sold == 20L && sellLedger().positions.single().remaining == 80L)
    near(bazaarStats(sellLedger()).realizedProfit, 37.0)
    near(bazaarStats(sellLedger()).eligibleProfit, 37.0)
    sellObserver.reset()
    val replayedSell = sellObserver.chat(sellClaim, context, sellLedger(), products, NOW + 1500, "session-a")
    check(replayedSell.all { it.kind == BazaarEventKind.OBSERVATION })
    sellApply(replayedSell)
    near(bazaarStats(sellLedger()).realizedProfit, 37.0)
}

/** "Your order was filled!" advances an order without opening Manage Orders, and never double-counts later evidence. */
private fun checkBazaarFillNotices() {
    val products = listOf(BazaarProduct("WHEAT", "Wheat", 10.0, 12.0))
    val quote = BazaarProduct("WHEAT", "Wheat", 10.0, 12.0, 1000.0, 1000.0, 1e6, 1e6)
    val observer = BazaarObserver()
    var archive = journal(event("notice-buy-order", BazaarEventKind.BUY_ORDER, 100, order = "notice-buy", price = 10.0, at = NOW - 10))
    fun ledger() = reconcileBazaar(archive, context)
    fun apply(events: List<BazaarEvent>) { events.forEach { archive = recordBazaarEvent(archive, it) } }
    fun step() = ledger().let { bazaarNextStep(it.positions.single(), it.orders, quote, live = true, relistThreshold = 6, name = "Wheat") }
    check(step().text == "Buy order open · 0/100 filled" && !step().actionable)

    val boughtNotice = "[Bazaar] Your Buy Order for 100x Wheat was filled!"
    val bought = observer.chat(boughtNotice, context, ledger(), products, NOW, "session-a")
    check(bought.single().kind == BazaarEventKind.BUY_FILL && bought.single().quantity == 100L && bought.single().cumulativeFilled == 100L)
    near(bought.single().coins, 1000.0)
    apply(bought)
    check(ledger().orders.single().status == BazaarOrderStatus.FILLED && ledger().positions.single().pendingItems == 100L)
    near(ledger().positions.single().costBasis, 1000.0)
    check(step() == BazaarUiStep("Claim 100 Wheat from Manage Orders", true))
    check(observer.chat(boughtNotice, context, ledger(), products, NOW + 1000, "session-a").isEmpty())
    val sameProgress = BazaarObservedSlot(20, "WHEAT", "BUY Wheat", listOf("Order amount: 100x", "Price per unit: 10 coins", "Filled: 100/100 100%!"), "notice-buy")
    check(observer.container("Manage Orders", listOf(sameProgress), context, ledger(), products, NOW + 2000, "session-a").none { it.kind == BazaarEventKind.BUY_FILL })
    check(observer.chat("[Bazaar] Your Buy Order for 64x Diamond was filled!", context, ledger(), products, NOW + 2500, "session-a").isEmpty()) {
        "A fill notice for an order Sky-Myce never saw must not create review noise"
    }

    apply(observer.chat("[Bazaar] Claimed 100x Wheat worth 1,000 coins bought for 10 each!", context, ledger(), products, NOW + 3000, "session-a"))
    check(ledger().positions.single().pendingItems == 0L && ledger().positions.single().stage == BazaarStage.READY)
    check(step() == BazaarUiStep("Create a sell offer for 100 at about 11.9 each", true))

    apply(listOf(event("notice-sell-order", BazaarEventKind.SELL_ORDER, 100, order = "notice-sell", price = 12.0, at = NOW + 4000)))
    check(ledger().positions.single().stage == BazaarStage.SELLING && step() == BazaarUiStep("Sell offer open · 100 left to sell", false))
    apply(observer.chat("[Bazaar] Your Sell Offer for 100x Wheat was filled!", context, ledger(), products, NOW + 5000, "session-a"))
    check(ledger().realizations.single().proceeds == null && !ledger().realizations.single().eligible)
    check(ledger().pending.single().awaiting) { "A filled sale waiting for its coin claim is expected, not something to review" }
    check(step() == BazaarUiStep("Claim the coins for 100 sold from Manage Orders", true))
    near(bazaarStats(ledger()).realizedProfit, 0.0)

    apply(observer.chat("[Bazaar] Claimed 1,185 coins from selling 100x Wheat at 12 each!", context, ledger(), products, NOW + 6000, "session-a"))
    near(bazaarStats(ledger()).realizedProfit, 185.0)
    near(bazaarStats(ledger()).eligibleProfit, 185.0)
    check(ledger().pending.isEmpty() && ledger().positions.single().stage == BazaarStage.CLOSED)
    check(step().text.startsWith("Done · +") && !step().actionable)

    val position = ledger().positions.single()
    val state = BazaarUiState(active = true, freshness = BazaarFreshness.FRESH, island = "Hub", positions = listOf(position.copy(stage = BazaarStage.READY, remaining = 100)),
        nextSteps = mapOf(position.id to BazaarUiStep("Create a sell offer for 100", true)))
    val lines = bazaarHudLines(state, 0)
    check(lines[0] == "§bBazaar · Wheat" && lines.any { it == "§eCreate a sell offer for 100" }) { "HUD must lead with the actionable step: $lines" }
    check(bazaarHudLines(state.copy(positions = emptyList(), nextSteps = emptyMap()), 0)[0] == "§bBazaar · flipping on Hub")
}

/** Public market context lives in its own bounded cache; older journals hand theirs over once. */
private fun checkBazaarMarketCacheSeparation() {
    val folder = Files.createTempDirectory("skymyce-bazaar-market-check-").toAbsolutePath().normalize()
    try {
        val snapshot = BazaarSnapshot("Legacy source", NOW, NOW, listOf(BazaarProduct("WHEAT", "Wheat", 10.0, 12.0)))
        val aggregate = BazaarMarketAggregate("WHEAT", NOW / 86_400_000, 1, 10.0, 12.0, 144.0, 12.0, 12.0, 0.0)
        val legacy = JsonObject().apply {
            addProperty("storageVersion", 1); addProperty("eventCount", 0)
            add("metadata", Gson().toJsonTree(mapOf("version" to 1, "events" to emptyList<Any>(), "sessions" to emptyList<Any>(),
                "marketHistory" to listOf(snapshot), "marketAggregates" to listOf(aggregate), "marketRegimes" to emptyList<Any>())))
        }
        Files.writeString(folder.resolve("index.json"), Gson().toJson(legacy))
        val storage = BazaarStorage(folder)
        check(storage.load() == BazaarArchive())
        val migrated = checkNotNull(storage.legacyMarket) { "Legacy market context was not handed over" }
        check(migrated.snapshots.single().source == "Legacy source" && migrated.aggregates.single() == aggregate)
        storage.save(BazaarArchive())
        check(storage.legacyMarket == null && "market" !in Files.readString(folder.resolve("index.json"))) {
            "The private journal index must no longer carry market data"
        }
        val cache = BazaarMarketStore(folder.resolve("market.json"))
        cache.save(migrated)
        check(cache.load() == migrated)
        Files.writeString(folder.resolve("market.json"), """{"version":1,"snapshots":"broken"}""")
        check(cache.load() == BazaarMarketArchive()) { "A damaged market cache is discarded, never trusted" }
        check(legacyBazaarMarket(JsonParser.parseString("""{"version":1,"events":[],"sessions":[]}""")) == null)
    } finally {
        check(folder.fileName.toString().startsWith("skymyce-bazaar-market-check-"))
        Files.list(folder).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(folder)
    }
}

/** Both market sources size depth from the best five levels; readable names never depend on the repository. */
private fun checkBazaarDepthAndNames() {
    fun levels(prices: IntProgression) = prices.joinToString(",") { """{"pricePerUnit":$it,"amount":100,"orders":1}""" }
    val raw = JsonParser.parseString("""{"success":true,"lastUpdated":$NOW,"products":{"WHEAT":{"product_id":"WHEAT",
        "sell_summary":[${levels(7 downTo 1)}],"buy_summary":[${levels(8..14)}],
        "quick_status":{"buyMovingWeek":1000,"sellMovingWeek":1000,"buyOrders":7,"sellOrders":7}}}}""").asJsonObject
    val wheat = parseBazaarMarket(raw, NOW).products.single()
    near(wheat.buyOrderPrice, 7.0); near(wheat.sellOfferPrice, 8.0)
    near(wheat.buyVolume, 500.0); near(wheat.sellVolume, 500.0)
    check(wheat.name == "Wheat" && bazaarFallbackName("ENCHANTED_RAW_SALMON") == "Enchanted Raw Salmon")
}
