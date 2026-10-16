package me.mycellium.skymyce.features.bazaar

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

enum class BazaarRisk { CONSERVATIVE, BALANCED, AGGRESSIVE }

enum class BazaarFreshness(val label: String) {
    FRESH("Live"), AGING("Aging"), STALE("Stale"), UNAVAILABLE("Unavailable");
    val scannable get() = this == FRESH || this == AGING
}

data class BazaarSample(val at: Long, val buyPrice: Double, val sellPrice: Double, val volume: Double? = null, val mayor: String? = null)

/**
 * One product's top of book. [buyOrderPrice] is the best bid (what a new buy order competes with) and
 * [sellOfferPrice] the best ask. Volumes are the summed amounts of the best five levels on each side.
 */
data class BazaarProduct(
    val id: String,
    val name: String,
    val buyOrderPrice: Double?,
    val sellOfferPrice: Double?,
    val buyVolume: Double? = null,
    val sellVolume: Double? = null,
    val buyMovingWeek: Double? = null,
    val sellMovingWeek: Double? = null,
    val buyOrders: Int? = null,
    val sellOrders: Int? = null,
    val history: List<BazaarSample> = emptyList(),
)

data class BazaarSnapshot(
    val source: String,
    val observedAt: Long,
    val retrievedAt: Long,
    val products: List<BazaarProduct>,
    val permitted: Boolean = true,
    val mayor: String? = null,
    val mayorObservedAt: Long? = null,
)

data class BazaarFilters(
    val minProfit: Double = 1000.0,
    val minRoi: Double = .005,
    val minLiquidity: Double = 1000.0,
    val maxFillSeconds: Double? = null,
    val minConfidence: Double = .35,
    val maxCapital: Double = 1e7,
    val maxConcentration: Double = .4,
)

/** Defaults are configurable estimates, not a claim that an account's current perks or taxes were observed. */
data class BazaarFees(val sellTaxRate: Double = .0125, val extraBuyRate: Double = 0.0, val extraSellRate: Double = 0.0, val verified: Boolean = false) {
    init { require(listOf(sellTaxRate, extraBuyRate, extraSellRate).all { it.isFinite() && it in 0.0..0.25 }) }
    fun buyCost(gross: Double) = gross * (1 + extraBuyRate)
    fun sellProceeds(gross: Double) = gross * (1 - sellTaxRate - extraSellRate)
}

data class BazaarOpportunity(
    val product: String,
    val name: String,
    val quantity: Long,
    val buyPrice: Double,
    val sellPrice: Double,
    val grossMargin: Double,
    val fees: Double,
    val netProfit: Double,
    val roi: Double,
    val capital: Double,
    val fillSecondsLow: Double?,
    val fillSecondsHigh: Double?,
    val liquidity: Double,
    val confidence: Double,
    val score: Double,
    val source: String,
    val observedAt: Long,
    val warnings: List<String>,
    val trend: Double?,
    val volatility: Double?,
)

data class BazaarPortfolio(
    val purse: Double?,
    val availableCash: Double?,
    val committedOrders: Double,
    val inventoryCost: Double?,
    val estimatedInventoryValue: Double?,
    val unclaimedCoins: Double?,
    val expectedCapacity: Double?,
    val realizedProfit: Double,
    val unknownInventoryQuantity: Long,
    val warnings: List<String>,
)

data class BazaarRelistAdvice(
    val warn: Boolean,
    val recommendation: String,
    val waitProceeds: Double?,
    val instantProceeds: Double?,
    val instantProfit: Double?,
    val costBasis: Double?,
    val capitalTied: Double?,
    val warnings: List<String>,
)

/**
 * The shared snapshot is refreshed about every 30 seconds and the client polls about every 45 seconds,
 * so a healthy pipeline always stays inside the live window.
 */
const val BAZAAR_FRESH_MILLIS = 120_000L
const val BAZAAR_STALE_MILLIS = 300_000L

fun bazaarFreshness(snapshot: BazaarSnapshot?, now: Long, freshMillis: Long = BAZAAR_FRESH_MILLIS, staleMillis: Long = BAZAAR_STALE_MILLIS): BazaarFreshness {
    require(freshMillis > 0 && staleMillis >= freshMillis)
    if (snapshot == null || !snapshot.permitted || snapshot.products.isEmpty() || snapshot.observedAt <= 0 || snapshot.retrievedAt <= 0 ||
        snapshot.observedAt > now + 10_000 || snapshot.retrievedAt > now + 10_000) return BazaarFreshness.UNAVAILABLE
    val age = maxOf(0, now - minOf(snapshot.observedAt, snapshot.retrievedAt))
    return when {
        age <= freshMillis -> BazaarFreshness.FRESH
        age <= staleMillis -> BazaarFreshness.AGING
        else -> BazaarFreshness.STALE
    }
}

fun validBazaarProduct(product: BazaarProduct): Boolean = validBazaarProductId(product.id) &&
    validBazaarId(product.name, 120) && validBazaarMoney(product.buyOrderPrice) && validBazaarMoney(product.sellOfferPrice) &&
    listOf(product.buyVolume, product.sellVolume, product.buyMovingWeek, product.sellMovingWeek).all(::validBazaarMoney) &&
    listOf(product.buyOrders, product.sellOrders).all { it == null || it in 0..10_000_000 } && product.history.size <= 192 &&
    product.history.all { it.at > 0 && validBazaarMoney(it.buyPrice) && validBazaarMoney(it.sellPrice) && validBazaarMoney(it.volume) }

/** Capital the player already has at risk per product, and products whose exposure cannot be measured. */
private class BazaarExposure(ledger: BazaarLedger) {
    val unknown = ledger.positions.filter { it.remaining > 0 && it.costBasis == null }.mapTo(hashSetOf()) { it.product }.also { unknown ->
        ledger.orders.filter { it.buy && it.status == BazaarOrderStatus.OPEN && it.quantity > it.filled && it.price == null }.mapTo(unknown) { it.product }
    }
    private val inventory = ledger.positions.filter { it.remaining > 0 }.groupBy { it.product }
        .mapValues { (_, positions) -> positions.mapNotNull { it.costBasis }.sum() }
    private val orders = ledger.orders.filter { it.buy && it.status == BazaarOrderStatus.OPEN }.groupBy { it.product }
        .mapValues { (_, orders) -> orders.mapNotNull { order -> order.price?.let { (order.quantity - order.filled) * it } }.sum() }
    val total = inventory.values.sum() + orders.values.sum()
    fun of(product: String) = (inventory[product] ?: 0.0) + (orders[product] ?: 0.0)
}

/**
 * Ranks manual buy-order → sell-offer flips across the whole Bazaar. Prices step one tenth of a coin inside the
 * current spread; quantity is limited by purse, per-flip capital, product concentration and visible depth.
 */
fun scanBazaar(snapshot: BazaarSnapshot?, purse: Double?, ledger: BazaarLedger = BazaarLedger(), filters: BazaarFilters = BazaarFilters(),
               risk: BazaarRisk = BazaarRisk.BALANCED, fees: BazaarFees = BazaarFees(), now: Long = System.currentTimeMillis()): List<BazaarOpportunity> {
    require(listOf(filters.minProfit, filters.minRoi, filters.minLiquidity, filters.maxCapital, filters.maxConcentration, filters.minConfidence)
        .all { it.isFinite() && it >= 0 })
    require(filters.maxConcentration in 0.0..1.0 && filters.minConfidence in 0.0..1.0 &&
        (filters.maxFillSeconds == null || filters.maxFillSeconds.isFinite() && filters.maxFillSeconds > 0))
    val freshness = bazaarFreshness(snapshot, now)
    if (snapshot == null || !freshness.scannable || purse == null || !validBazaarMoney(purse) || purse <= 0) return emptyList()
    val exposure = BazaarExposure(ledger)
    // Known capital is a conservative lower bound; a product with unknown exposure is never added to.
    val totalCapital = purse + exposure.total
    val duplicates = snapshot.products.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    return snapshot.products.asSequence()
        .filter { it.id !in duplicates && it.id !in exposure.unknown && validBazaarProduct(it) }
        .mapNotNull { product ->
            val allocation = minOf(purse, filters.maxCapital, (totalCapital * filters.maxConcentration - exposure.of(product.id)).coerceAtLeast(0.0))
            bazaarOpportunity(product, snapshot, allocation, freshness, filters, risk, fees, now)
        }
        .sortedWith(compareByDescending<BazaarOpportunity> { it.score }.thenBy { it.product })
        .take(1000).toList()
}

private fun bazaarOpportunity(product: BazaarProduct, snapshot: BazaarSnapshot, allocation: Double, freshness: BazaarFreshness,
                              filters: BazaarFilters, risk: BazaarRisk, fees: BazaarFees, now: Long): BazaarOpportunity? {
    val bid = product.buyOrderPrice?.takeIf { it > 0 } ?: return null
    val ask = product.sellOfferPrice?.takeIf { it > bid } ?: return null
    val liquidity = minOf(product.buyMovingWeek ?: 0.0, product.sellMovingWeek ?: 0.0)
    val depth = minOf(product.buyVolume ?: 0.0, product.sellVolume ?: 0.0)
    if (liquidity < filters.minLiquidity || depth <= 0) return null
    val buy = (floor(bid * 10 + 1e-7) + 1) / 10
    val sell = (ceil(ask * 10 - 1e-7) - 1) / 10
    if (sell <= buy || sell <= 0) return null
    val quantity = minOf(floor(allocation / fees.buyCost(buy)), floor(depth * .05), 1_000_000.0).toLong()
    if (quantity <= 0) return null
    val capital = fees.buyCost(buy * quantity)
    val proceeds = fees.sellProceeds(sell * quantity)
    val net = proceeds - capital
    val roi = net / capital
    if (net < filters.minProfit || roi < filters.minRoi) return null

    val history = product.history.filter { it.at < snapshot.observedAt && it.at >= snapshot.observedAt - 31L * 86_400_000 }
        .distinctBy { it.at }.sortedBy { it.at }
    val mean = history.map { it.sellPrice }.average().takeIf { it.isFinite() && it > 0 }
    val volatility = mean?.let { average -> sqrt(history.sumOf { (it.sellPrice - average) * (it.sellPrice - average) } / history.size) / average }
    val trend = history.firstOrNull()?.sellPrice?.takeIf { it > 0 }?.let { (history.last().sellPrice - it) / it }
    // Public weekly flow is shared with every competing order, so the range is deliberately wide.
    val flow = liquidity / (7 * 86_400.0)
    val fillLow = if (flow > 0) quantity / flow * 2 else null
    val fillHigh = fillLow?.let { it * 5 + depth * .1 / flow }

    val warnings = mutableListOf("Estimated top-of-book prices; queue position and fills are not guaranteed")
    var confidence = .6 + minOf(history.size, 24) / 60.0
    if (history.size < 6) { confidence *= .9; warnings += "Sparse price history" }
    confidence *= 1 - (volatility ?: .08).coerceIn(0.0, .8)
    if (freshness == BazaarFreshness.AGING) { confidence *= .6; warnings += "Aging market snapshot" }
    if (!fees.verified) { confidence *= .9; warnings += "Account tax/perks unverified; configured fee estimate" }
    if (snapshot.mayor == null || snapshot.mayorObservedAt == null || now - snapshot.mayorObservedAt > 3_600_000) {
        confidence *= .9; warnings += "Mayor/minister impact unknown"
    }
    if (snapshot.mayor != null && history.any { it.mayor != null && it.mayor != snapshot.mayor }) {
        confidence *= .9; warnings += "Mayor/minister regime changed; a causal price effect has not been established"
    }
    if ((trend ?: 0.0) < -.05) { confidence *= .8; warnings += "Recent falling-price trend" }
    confidence = confidence.coerceIn(0.0, 1.0)
    if (confidence < filters.minConfidence || filters.maxFillSeconds?.let { fillHigh == null || fillHigh > it } == true) return null

    val timePenalty = 1 + ln(1 + (fillHigh ?: 86_400.0) / 3600)
    val riskWeight = when (risk) {
        BazaarRisk.CONSERVATIVE -> confidence * confidence * (1 - (volatility ?: .15).coerceIn(0.0, .95)) / (timePenalty * timePenalty)
        BazaarRisk.BALANCED -> confidence / timePenalty
        BazaarRisk.AGGRESSIVE -> sqrt(confidence) / sqrt(timePenalty)
    }
    val grossMargin = (sell - buy) * quantity
    return BazaarOpportunity(product.id, product.name, quantity, buy, sell, grossMargin, grossMargin - net, net, roi, capital,
        fillLow, fillHigh, liquidity, confidence, net * riskWeight * (1 + ln(1 + liquidity) / 20), snapshot.source, snapshot.observedAt,
        warnings, trend, volatility)
}

/**
 * Exceptional means affordable, live, liquid and confident, and clearly ahead of the current market's typical
 * candidate. Stale or aging data never qualifies.
 */
internal fun exceptionalBazaarOpportunity(opportunity: BazaarOpportunity, ranked: List<BazaarOpportunity>, available: Double?,
                                          active: Boolean, freshness: BazaarFreshness, minimumProfit: Double): Boolean {
    if (!active || freshness != BazaarFreshness.FRESH || available == null || opportunity.capital > available ||
        opportunity.netProfit < minimumProfit || opportunity.roi < .01 || opportunity.confidence < .65 || opportunity.liquidity < 10_000) return false
    val scores = ranked.map { it.score }.filter(Double::isFinite).sorted()
    val median = scores.getOrNull(scores.size / 2) ?: return false
    return opportunity.score >= median * 1.2
}

/** Purse, reservations, inventory cost and estimates stay separate; nothing is counted twice. */
fun bazaarPortfolio(ledger: BazaarLedger, purse: Double?, quotes: Map<String, BazaarProduct> = emptyMap(), fees: BazaarFees = BazaarFees(),
                    purseExcludesReservations: Boolean = true): BazaarPortfolio {
    require(validBazaarMoney(purse))
    val buys = ledger.orders.filter { it.buy && it.status == BazaarOrderStatus.OPEN }
    val committed = buys.sumOf { (it.quantity - it.filled).coerceAtLeast(0) * (it.price ?: 0.0) }
    val held = ledger.positions.filter { it.remaining > 0 }
    val unknown = held.filter { it.costBasis == null }.sumOf { it.remaining }
    val basis = held.takeIf { unknown == 0L }?.sumOf { it.costBasis ?: 0.0 }
    val priced = held.all { quotes[it.product]?.buyOrderPrice?.let { price -> price > 0 && price.isFinite() } == true }
    val value = if (priced) held.sumOf { fees.sellProceeds(it.remaining * checkNotNull(quotes[it.product]?.buyOrderPrice)) } else null
    val sales = ledger.realizations
    val unclaimed = if (sales.all { it.proceeds != null }) (sales.sumOf { it.proceeds ?: 0.0 } - ledger.collectedCoins).coerceAtLeast(0.0) else null
    // Hypixel removes buy-order coins from the purse when the order is placed.
    val available = purse?.let { if (purseExcludesReservations) it else (it - committed).coerceAtLeast(0.0) }
    val warnings = buildList {
        if (purse == null) add("Purse unavailable; no new capital allocated")
        if (buys.any { it.price == null }) add("Some outstanding order commitments are unknown")
        if (unknown > 0) add("Some inventory cost basis is unknown")
        if (value == null && held.isNotEmpty()) add("Inventory liquidation estimate unavailable")
    }
    return BazaarPortfolio(purse, available, committed, basis, value, unclaimed,
        if (available != null && unclaimed != null) available + unclaimed else null,
        sales.sumOf { it.netProfit ?: 0.0 }, unknown, warnings)
}

/** Waiting versus selling instantly, using live prices only. The adviser never changes an order. */
fun bazaarRelistAdvice(position: BazaarPosition, quote: BazaarProduct?, fees: BazaarFees = BazaarFees(),
                       freshness: BazaarFreshness = BazaarFreshness.UNAVAILABLE, threshold: Int = 6): BazaarRelistAdvice {
    require(threshold >= 0)
    val current = quote?.takeIf { freshness == BazaarFreshness.FRESH && validBazaarProduct(it) }
    val instant = current?.buyOrderPrice?.let { fees.sellProceeds(it * position.remaining) }
    val wait = current?.sellOfferPrice?.let { fees.sellProceeds(it * position.remaining) }
    val history = current?.history.orEmpty().sortedBy { it.at }
    val falling = history.size >= 3 && history.last().sellPrice < history.first().sellPrice * .95
    val worthwhile = instant != null && wait != null && wait > instant * 1.03 && !falling
    val recommendation = when {
        instant == null -> "Keep waiting; fresh liquidation data is unavailable"
        worthwhile -> "Leave it / keep waiting"
        else -> "Consider a manual insta-sell to release capital"
    }
    return BazaarRelistAdvice(position.relistCount > threshold, recommendation, wait, instant,
        if (instant != null && position.costBasis != null) instant - position.costBasis else null, position.costBasis, position.costBasis,
        buildList {
            if (position.uncertain) add("Position association/cost basis needs confirmation")
            if (!fees.verified) add("Fee estimate unverified")
            if (falling) add("Recent prices are falling")
        })
}

data class BazaarForecastEvaluation(val samples: Int, val meanAbsoluteError: Double?, val rangeCoverage: Double?)

/** Walk-forward: each prediction uses only observations earlier than its held-out target. */
fun evaluateBazaarHistory(samples: List<BazaarSample>): BazaarForecastEvaluation {
    val ordered = samples.filter { it.at > 0 && it.sellPrice.isFinite() && it.sellPrice > 0 }.distinctBy { it.at }.sortedBy { it.at }
    val errors = mutableListOf<Double>()
    var covered = 0
    for (index in 3 until ordered.size) {
        val past = ordered.subList(maxOf(0, index - 12), index).map { it.sellPrice }
        val average = past.average()
        val deviation = sqrt(past.sumOf { (it - average) * (it - average) } / past.size)
        val actual = ordered[index].sellPrice
        errors += abs(actual - average)
        if (actual in (average - 2 * deviation)..(average + 2 * deviation)) covered++
    }
    return BazaarForecastEvaluation(errors.size, errors.takeIf { it.isNotEmpty() }?.average(),
        errors.takeIf { it.isNotEmpty() }?.let { covered.toDouble() / it.size })
}
