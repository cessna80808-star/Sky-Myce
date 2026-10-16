package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonElement
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Path

data class BazaarMarketAggregate(val product: String, val day: Long, val count: Int, val buySum: Double, val sellSum: Double,
                                 val sellSquaredSum: Double, val minSell: Double, val maxSell: Double, val volumeSum: Double)
data class BazaarRegime(val at: Long, val mayor: String)

/**
 * Public market context shared by every account on this install: the latest snapshots, daily price aggregates
 * and observed mayor changes. It is bounded, rebuildable and stored apart from private trading history.
 */
data class BazaarMarketArchive(
    val version: Int = 1,
    val snapshots: List<BazaarSnapshot> = emptyList(),
    val aggregates: List<BazaarMarketAggregate> = emptyList(),
    val regimes: List<BazaarRegime> = emptyList(),
)

private const val DAY = 86_400_000L
private const val MAX_SNAPSHOTS = 3
private const val MAX_AGGREGATES = 50_000
private const val AGGREGATE_DAYS = 90

/** Age-bounded public context only; private events and positions never pass through here. */
fun rememberBazaarMarket(archive: BazaarMarketArchive, snapshot: BazaarSnapshot, now: Long, retentionDays: Int = 7): BazaarMarketArchive {
    require(retentionDays in 1..31 && snapshot.products.size <= 20_000 && snapshot.products.all(::validBazaarProduct))
    require(snapshot.products.map { it.id }.distinct().size == snapshot.products.size)
    if (!snapshot.permitted || bazaarFreshness(snapshot, now) == BazaarFreshness.UNAVAILABLE) return archive
    if (archive.snapshots.any { it.source == snapshot.source && it.observedAt == snapshot.observedAt }) return archive
    val day = snapshot.observedAt / DAY
    val aggregates = archive.aggregates.associateBy { it.product to it.day }.toMutableMap()
    for (product in snapshot.products) {
        val bid = product.buyOrderPrice ?: continue
        val ask = product.sellOfferPrice ?: continue
        val volume = product.buyMovingWeek ?: 0.0
        val previous = aggregates[product.id to day]
        aggregates[product.id to day] = previous?.copy(count = previous.count + 1, buySum = previous.buySum + bid, sellSum = previous.sellSum + ask,
            sellSquaredSum = previous.sellSquaredSum + ask * ask, minSell = minOf(previous.minSell, ask), maxSell = maxOf(previous.maxSell, ask),
            volumeSum = previous.volumeSum + volume)
            ?: BazaarMarketAggregate(product.id, day, 1, bid, ask, ask * ask, ask, ask, volume)
    }
    val regime = snapshot.mayor?.takeIf { validBazaarId(it, 400) }?.let { name ->
        snapshot.mayorObservedAt?.takeIf { it > 0 && it <= now && now - it <= 3_600_000 }?.let { BazaarRegime(it, name) }
    }
    val regimes = if (regime != null && archive.regimes.lastOrNull()?.mayor != regime.mayor)
        (archive.regimes + regime).distinctBy { it.at }.sortedBy { it.at }.takeLast(512) else archive.regimes
    val compact = snapshot.copy(products = snapshot.products.map { it.copy(history = emptyList()) })
    return archive.copy(
        snapshots = (archive.snapshots + compact).filter { it.observedAt >= now - retentionDays * DAY }.takeLast(MAX_SNAPSHOTS),
        aggregates = aggregates.values.filter { it.day >= day - AGGREGATE_DAYS }
            .sortedWith(compareBy<BazaarMarketAggregate> { it.day }.thenBy { it.product }).takeLast(MAX_AGGREGATES),
        regimes = regimes,
    )
}

/** Completed daily means and earlier observations only: current or future data never trains its own forecast. */
fun enrichBazaarMarket(archive: BazaarMarketArchive, snapshot: BazaarSnapshot): BazaarSnapshot {
    val samples = mutableMapOf<String, MutableMap<Long, BazaarSample>>()
    val earliest = snapshot.observedAt - AGGREGATE_DAYS * DAY
    fun add(product: String, sample: BazaarSample) {
        if (sample.at in earliest until snapshot.observedAt && sample.buyPrice.isFinite() && sample.sellPrice.isFinite() &&
            sample.buyPrice > 0 && sample.sellPrice > 0) samples.getOrPut(product) { mutableMapOf() }[sample.at] = sample
    }
    for (point in archive.aggregates) {
        if (point.day >= snapshot.observedAt / DAY || point.count <= 0) continue
        val start = point.day * DAY
        val end = start + DAY - 1
        // A day that saw a regime change has no single mayor context.
        val mayor = archive.regimes.lastOrNull { it.at <= start }?.mayor?.takeIf { archive.regimes.none { regime -> regime.at in (start + 1)..end } }
        add(point.product, BazaarSample(end, point.buySum / point.count, point.sellSum / point.count, point.volumeSum / point.count, mayor))
    }
    for (prior in archive.snapshots) {
        if (prior.observedAt >= snapshot.observedAt) continue
        for (product in prior.products) {
            product.history.forEach { add(product.id, it) }
            val bid = product.buyOrderPrice ?: continue
            val ask = product.sellOfferPrice ?: continue
            add(product.id, BazaarSample(prior.observedAt, bid, ask, product.buyMovingWeek, prior.mayor))
        }
    }
    snapshot.products.forEach { product -> product.history.forEach { add(product.id, it) } }
    return snapshot.copy(products = snapshot.products.map { product ->
        product.copy(history = samples[product.id].orEmpty().values.sortedBy { it.at }.takeLast(96))
    })
}

fun parseBazaarMarketArchive(json: JsonElement?): BazaarMarketArchive {
    if (json == null || json.isJsonNull) return BazaarMarketArchive()
    require(json.isJsonObject && json.asJsonObject.get("version")?.asInt == 1)
    val archive = Gson().fromJson(json, BazaarMarketArchive::class.java)
    return validBazaarMarketArchive(BazaarMarketArchive(1, archive.snapshots.orEmpty(), archive.aggregates.orEmpty(), archive.regimes.orEmpty()))
}

internal fun validBazaarMarketArchive(archive: BazaarMarketArchive): BazaarMarketArchive = archive.also {
    require(it.snapshots.size <= MAX_SNAPSHOTS && it.aggregates.size <= MAX_AGGREGATES && it.regimes.size <= 512)
    it.snapshots.forEach { snapshot ->
        require(validBazaarId(snapshot.source, 120) && snapshot.observedAt > 0 && snapshot.retrievedAt > 0 &&
            snapshot.products.size <= 20_000 && snapshot.products.all(::validBazaarProduct) &&
            snapshot.products.map { product -> product.id }.distinct().size == snapshot.products.size)
    }
    it.aggregates.forEach { point ->
        require(validBazaarProductId(point.product) && point.day > 0 && point.count in 1..1_000_000 && point.minSell <= point.maxSell &&
            listOf(point.buySum, point.sellSum, point.sellSquaredSum, point.minSell, point.maxSell, point.volumeSum).all { value -> value.isFinite() && value >= 0 })
    }
    it.regimes.forEach { regime -> require(regime.at in 1..253402300799999L && validBazaarId(regime.mayor, 400)) }
    require(it.regimes.zipWithNext().all { (first, second) -> second.at > first.at && second.mayor != first.mayor })
}

/** Background-thread IO for the shared market cache. A damaged cache is discarded, never private history. */
internal class BazaarMarketStore(path: Path) {
    private val file = AtomicJsonFile(path, 32 * 1024 * 1024)

    fun load(): BazaarMarketArchive = runCatching { parseBazaarMarketArchive(file.read()).also { check(!file.damaged) } }
        .getOrDefault(BazaarMarketArchive())

    fun save(archive: BazaarMarketArchive) = file.write(Gson().toJsonTree(validBazaarMarketArchive(archive)))
}
