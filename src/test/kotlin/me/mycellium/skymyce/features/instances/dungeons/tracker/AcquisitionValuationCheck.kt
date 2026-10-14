package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import me.mycellium.skymyce.utils.marketPriceOrNull

fun checkAcquisitionValuations() {
    var auctionLookups = 0
    fun auction() = 25.0.also { auctionLookups++ }
    check(marketPriceOrNull(40.0, ::auction) == 40.0 && auctionLookups == 0)
    for (unavailable in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
        check(marketPriceOrNull(unavailable, ::auction) == 25.0)
    check(auctionLookups == 5)
    for (unavailable in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
        check(marketPriceOrNull(null) { unavailable } == null)

    val context = AcquisitionContext("a".repeat(32), "profile", "Apple")
    val chest = ChestReceipt("chest", 1000, "BEDROCK", 10.0,
        listOf(RunLoot("ENCHANTMENT:ULTIMATE_JERRY:3", 2), RunLoot("FREE", 1, 0.0), RunLoot("VALUED", 1, 15.0)))
    val run = DungeonRunRecord("run", "session", context, "M7", chests = listOf(chest), rerolls = 1)
    val event = AcquisitionEvent("e".repeat(32), chest.loot.first().item, chest.time, "dungeon", context, run.floor,
        chest.chest, quantity = 2, run = run.id)
    val legacy = run.copy(id = "legacy", trackingVersion = 1)
    val otherAccount = run.copy(id = "other-account", context = context.copy(account = "b".repeat(32)))
    val otherServer = run.copy(id = "other-server", context = context.copy(server = "alpha"))
    val altProfile = run.copy(id = "alt-profile", context = context.copy(profileId = "other-profile", profileName = "Pear"))
    val wrongQuantity = event.copy(id = "f".repeat(32), quantity = 1)
    val legacyEvent = event.copy(id = "c".repeat(32), origin = "legacy-confirmed")
    val archive = AcquisitionArchive(runs = listOf(run, legacy, otherAccount, otherServer, altProfile),
        events = listOf(event, wrongQuantity, legacyEvent), cutoverAt = 1, legacyProfit = mapOf("M7" to 123.0),
        pins = setOf("keep"), imports = setOf("tracker-v1"))
    val item = chest.loot.first().item
    check(missingAcquisitionPrices(archive, context.account, "main") == listOf(item))
    check(missingAcquisitionPrices(archive.copy(version = 1), context.account, "main").isEmpty())
    check(repairAcquisitionValuations(archive, context.account, "main", mapOf(item to null), 2000) === archive)
    for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e13))
        check(repairAcquisitionValuations(archive, context.account, "main", mapOf(item to invalid), 2000) === archive)
    val repaired = repairAcquisitionValuations(archive, context.account, "main",
        mapOf(item to 25.0, "FREE" to 900.0, "VALUED" to 900.0), 2000)
    val pricedRun = run.copy(chests = listOf(chest.copy(loot = chest.loot.map { if (it.item == item) it.copy(value = 50.0) else it })))
    check(repaired == archive.copy(runs = listOf(pricedRun, legacy, otherAccount, otherServer,
        altProfile.copy(chests = pricedRun.chests)), events = listOf(event.copy(value = 50.0, valuationAt = 2000), wrongQuantity, legacyEvent)))
    check(repairAcquisitionValuations(repaired, context.account, "main", mapOf(item to 99.0), 3000) === repaired)
    check(repairAcquisitionValuations(archive.copy(version = 1), context.account, "main", mapOf(item to 25.0), 2000).runs == archive.runs)
    // Serialization stays in the existing archive format, and never invents purchase/reroll costs.
    check(parseAcquisitionArchive(Gson().toJsonTree(repaired)) == repaired)
    check(repaired.runs.first().rerollCost == null && repaired.runs.first().chests.first().cost == 10.0)

    // Inventory can confirm the shared acquisition before the delayed reward header attaches its physical receipt.
    val rare = "IMPLOSION_SCROLL"
    val rareChest = chest.copy(loot = listOf(RunLoot(rare, 1)))
    val rareRun = run.copy(chests = listOf(rareChest))
    val inventoryEvent = AcquisitionEvent(acquisitionId("inventory/$rare"), rare, 900, "dungeon", context,
        run.floor, run = run.id)
    val linked = mergeAcquisition(AcquisitionArchive(runs = listOf(rareRun), events = listOf(inventoryEvent)),
        inventoryEvent.copy(occurredAt = rareChest.time, chest = rareChest.chest, chestCost = rareChest.cost))
    check(linked.events.single().occurredAt == 900L && linked.runs.single().chests.single().time == 1000L)
    val repairedLinked = repairAcquisitionValuations(linked, context.account, "main", mapOf(rare to 40.0), 2000)
    check(repairedLinked.events.single() == linked.events.single().copy(value = 40.0, valuationAt = 2000))
    check(repairedLinked.events.single().id == inventoryEvent.id && repairedLinked.runs.single().chests.single().loot.single().value == 40.0)
    for (unrelated in listOf(inventoryEvent, linked.events.single().copy(run = "other-run"),
        linked.events.single().copy(chest = "WOOD"), linked.events.single().copy(quantity = 2),
        linked.events.single().copy(context = otherAccount.context))) {
        val unlinked = linked.copy(events = listOf(unrelated))
        check(repairAcquisitionValuations(unlinked, context.account, "main", mapOf(rare to 40.0), 2000).events == unlinked.events)
    }
    // Multiple physical receipts or linked events cannot prove which acquisition had an unavailable price.
    val duplicateReceipt = linked.copy(runs = listOf(rareRun.copy(chests = listOf(rareChest,
        rareChest.copy(id = "second-chest", time = 1500, loot = listOf(RunLoot(rare, 1, 55.0)))))))
    check(repairAcquisitionValuations(duplicateReceipt, context.account, "main", mapOf(rare to 40.0), 2000).events == linked.events)
    val duplicateEvent = linked.copy(events = linked.events + linked.events.single().copy(id = "d".repeat(32), occurredAt = 800))
    check(repairAcquisitionValuations(duplicateEvent, context.account, "main", mapOf(rare to 40.0), 2000).events == duplicateEvent.events)

    val stale = run.copy(cataXp = 123.0)
    check(preserveRunValuations(pricedRun, stale) == pricedRun.copy(cataXp = 123.0))
    check(preserveRunValuations(pricedRun, run.copy(context = otherAccount.context)) == run.copy(context = otherAccount.context))
    val differentCount = run.copy(chests = listOf(chest.copy(loot = listOf(chest.loot.first().copy(count = 3)))))
    check(preserveRunValuations(pricedRun, differentCount) == differentCount)
    val differentReceipt = run.copy(chests = listOf(chest.copy(id = "another-chest")))
    check(preserveRunValuations(pricedRun, differentReceipt) == differentReceipt)
    val missingZero = run.copy(chests = listOf(chest.copy(loot = listOf(RunLoot("FREE", 1)))))
    check(preserveRunValuations(pricedRun, missingZero).chests.single().loot.single().value == 0.0)
    val edited = run.copy(chests = listOf(chest.copy(loot = listOf(chest.loot.first().copy(value = 77.0)))))
    check(preserveRunValuations(pricedRun, edited) == edited)

    check(parseTrackerPreferences(null).scope == TrackerScope.ALL_LOCAL_PROFILES)
    val preferences = Gson().toJsonTree(TrackerFilter()).asJsonObject.apply { remove("overviewVersion") }
    check(parseTrackerPreferences(preferences).let { it.scope == TrackerScope.ALL_LOCAL_PROFILES && !it.legacyTotals })
    preferences.addProperty("scope", "CURRENT_PROFILE"); preferences.addProperty("overviewVersion", 1)
    check(parseTrackerPreferences(preferences).scope == TrackerScope.CURRENT_PROFILE)
    preferences.addProperty("overviewVersion", 2)
    check(runCatching { parseTrackerPreferences(preferences) }.isFailure)
    preferences.remove("overviewVersion"); preferences.addProperty("page", "LOOT")
    check(parseTrackerPreferences(preferences).scope == TrackerScope.CURRENT_PROFILE)
    preferences.addProperty("page", "OVERVIEW"); preferences.addProperty("from", "2026-10-14")
    check(parseTrackerPreferences(preferences).scope == TrackerScope.CURRENT_PROFILE)
    println("Acquisition valuation checks passed: direct market fallback, null-only scoped repairs, shared receipt timestamps, ambiguous linkage rejection, known zero, no cost inference and overview preference migration")
}
