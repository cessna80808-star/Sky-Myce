package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import java.util.Locale

internal fun marketValuedLoot(rows: List<TrackerLootRow>, unitPrice: (String) -> Double?): List<TrackerLootRow> =
    rows.map { it.copy(value = estimatedLootMarketValue(it.count, unitPrice(it.item))) }

internal fun cachedLootUnitPrice(item: String, bazaar: DungeonsConfig.BazaarType, auction: DungeonsConfig.AuctionType): Double? = runCatching {
    val id = if (':' in item) SkyBlockId.CODEC.parse(JsonOps.INSTANCE, JsonPrimitive(item.lowercase(Locale.ROOT))).result().orElse(null)
        else SkyBlockId.item(item)
    id?.getPriceOrNull(bazaar, auction)
}.getOrNull()

/** Display estimate only; this never changes the recorded acquisition value or profit. */
internal fun estimatedLootMarketValue(count: Long, unitPrice: Double?): Double? {
    if (count < 0) return null
    if (count == 0L) return 0.0
    val price = unitPrice?.takeIf { it.isFinite() && it > 0.0 && it <= 1e12 } ?: return null
    return (price * count).takeIf { it.isFinite() && it in 0.0..1e16 }
}

private fun DungeonRunRecord.valuationScope(account: String, server: String) = trackingVersion == 2 &&
    context?.let { it.account == account && it.server == server } == true

internal fun missingAcquisitionPrices(archive: AcquisitionArchive, account: String, server: String): List<String> =
    if (archive.version != 2) emptyList() else archive.runs.asSequence().filter { it.valuationScope(account, server) }
        .flatMap { it.chests.asSequence() }.flatMap { it.loot.asSequence() }.filter { it.value == null }
        .map { it.item }.distinct().toList()

private data class LootValuationKey(val run: String, val item: String, val count: Int, val chest: String)

/** Evaluate existing confirmed receipts locally. Counts, provenance, costs and previously known values stay frozen. */
internal fun repairAcquisitionValuations(archive: AcquisitionArchive, account: String, server: String,
    prices: Map<String, Double?>, now: Long): AcquisitionArchive {
    if (archive.version != 2 || prices.isEmpty() || now <= 0) return archive
    val repaired = mutableMapOf<LootValuationKey, Pair<Double, AcquisitionContext>>()
    val receipts = mutableMapOf<LootValuationKey, Int>()
    val runs = archive.runs.map { run ->
        if (!run.valuationScope(account, server)) return@map run
        val chests = run.chests.map { chest -> chest.copy(loot = chest.loot.map loot@{ item ->
            val key = LootValuationKey(run.id, item.item, item.count, chest.chest)
            receipts[key] = (receipts[key] ?: 0) + 1
            if (item.value != null) return@loot item
            val unit = prices[item.item]?.takeIf { it.isFinite() && it > 0.0 && it <= 1e12 } ?: return@loot item
            val value = (unit * item.count).takeIf { it.isFinite() && it in 0.0..1e16 } ?: return@loot item
            repaired[key] = value to run.context!!
            item.copy(value = value)
        }) }
        run.copy(chests = chests)
    }
    if (repaired.isEmpty()) return archive
    val linkedEvents = archive.events.filter { it.origin == "confirmed" && it.run != null && it.chest != null }
        .groupingBy { LootValuationKey(it.run!!, it.item, it.quantity, it.chest!!) }.eachCount()
    val events = archive.events.map { event ->
        if (event.origin != "confirmed" || event.value != null || event.run == null || event.chest == null) return@map event
        val key = LootValuationKey(event.run, event.item, event.quantity, event.chest)
        // The shared receipt ID attaches run/chest, but preserves an earlier inventory announcement's timestamp.
        if (receipts[key] != 1 || linkedEvents[key] != 1) return@map event
        val valuation = repaired[key]
            ?.takeIf { it.second.matches(event.context) } ?: return@map event
        event.copy(value = valuation.first, valuationAt = now)
    }
    return archive.copy(runs = runs, events = events)
}

/** A later XP/chest update may carry an older run snapshot. Keep repaired values for the same physical receipt. */
internal fun preserveRunValuations(saved: DungeonRunRecord?, incoming: DungeonRunRecord): DungeonRunRecord {
    if (saved == null || saved.id != incoming.id || saved.floor != incoming.floor || saved.context != incoming.context ||
        saved.trackingVersion != incoming.trackingVersion) return incoming
    val savedChests = saved.chests.associateBy { it.id }
    val chests = incoming.chests.map { chest ->
        val previous = savedChests[chest.id]?.takeIf { it.time == chest.time && it.chest == chest.chest } ?: return@map chest
        chest.copy(loot = chest.loot.map { item ->
            if (item.value != null) item else previous.loot.singleOrNull { it.item == item.item && it.count == item.count }
                ?.value?.let { item.copy(value = it) } ?: item
        })
    }
    return if (chests == incoming.chests) incoming else incoming.copy(chests = chests)
}
