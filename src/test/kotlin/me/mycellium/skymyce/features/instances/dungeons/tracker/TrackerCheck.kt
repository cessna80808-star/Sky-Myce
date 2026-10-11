package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.features.digest.*
import java.time.LocalDate
import java.time.ZoneId

fun checkTracker() {
    checkDungeonTiming()
    checkChestTracking()
    check(safeAverage(0.0, 0) == null && safeAverage(Double.NaN, 1) == null && safeAverage(100.0, 2) == 50.0)
    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val other = context.copy(profileId = "c".repeat(32), profileName = "Pear")
    check(!context.matches(other) && !context.matches(context.copy(account = "d".repeat(32))))
    check(trackingContext(null, context, false, true) == null) // A cold dungeon join cannot invent a verified profile.
    check(trackingContext(null, context, true, false) == context)
    check(trackingContext(context, context, false, true) == context)
    check(trackingContext(context, context.copy(profileId = null), false, true) == context)
    check(trackingContext(context, context, false, false) == null)
    check(trackingContext(context, other, false, true) == null)
    check(trackingContext(context, context.copy(profileName = "Pear"), false, true) == null)
    check(trackingContext(context, context.copy(account = "d".repeat(32)), false, true) == null)
    check(trackingContext(context, context.copy(server = "alpha"), false, true) == null)
    check(trackingContext(null, null, true, true) == null)
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val loot = RunLoot("NECRON_HANDLE", 2, 2_000_000.0)
    val receipt = ChestReceipt("receipt", time, "BEDROCK", 100.0, listOf(loot))
    val run = DungeonRunRecord("run", "session", context, "F7", time - 60000, time, 60000, 1000.0, chests = listOf(receipt))
    val event = AcquisitionEvent("e".repeat(32), "NECRON_HANDLE", time, "dungeon", context, "F7", "BEDROCK", quantity = 2, run = run.id)
    val state = AcquisitionArchive(events = listOf(event, event.copy(id = "f".repeat(32), context = other)),
        runs = listOf(run, run.copy(id = "master", floor = "M7"), run.copy(id = "other", context = other)))
    val filter = TrackerFilter(legacyTotals = false, floor = "F7", from = "2026-09-24", until = "2026-09-24", session = "session", chest = "BEDROCK", query = "handle", rareOnly = true)
    val view = trackerView(state, emptyList(), filter, context)
    check(view.summary.runs == 1L && view.summary.average == 60000.0 && view.summary.cataXp == 1000.0)
    check(view.summary.completeTiming && !TrackerSummary(runs = 2, time = 60000, timedRuns = 1).completeTiming)
    check(view.loot.single().let { it.count == 2L && it.observedRate == 1.0 }) // Count is not mistaken for a 200% drop rate.
    check(view.events == listOf(event))
    check(filter.copy(floor = null, mode = "M").runs(state.runs, context).single().floor == "M7")
    check(filter.copy(from = "2026-09-25", until = "").events(state.events, context, state.runs).isEmpty())
    check(trackerView(AcquisitionArchive(), emptyList(), filter, context).summary.average == null)
    check(trackerView(state, emptyList(), filter.copy(scope = TrackerScope.ALL_LOCAL_PROFILES), null).events.size == 2) // Offline view requires no relay.
    val raw = JsonParser.parseString("""{"F7":{"chests":{"BEDROCK":{"valuables":{"item:necron_handle":[$time,$time]}}}}}""")
    val digest = DigestSavedState(profiles = mapOf("${context.account}/main/apple" to DigestProfileState(history = listOf(
        DigestRngEntry("1".repeat(32), "NECRON_HANDLE", "dungeon", time, "Alice"),
        DigestRngEntry("2".repeat(32), "NECRON_HANDLE", "dungeon", time, "Other", true)))))
    val imported = importLegacyAcquisitions(AcquisitionArchive(), raw, digest)
    check(imported.events.size == 3 && imported.events.count { it.context == null } == 2)
    check(TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES, legacyTotals = true).events(imported.events, null, emptyList()).size == 3)
    check(TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES).events(imported.events, null, emptyList()).isEmpty())
    check(imported.events.map { it.id }.distinct().size == 3) // Two same-time acquisitions stay distinct.
    check(importLegacyAcquisitions(imported, raw, digest) == imported)
    check(imported.events.none { it.player == "Other" }) // Community claims never increase the collection.
    check(parseAcquisitionArchive(Gson().toJsonTree(imported)) == imported)
    val pins = imported.copy(pins = setOf("legacy|NECRON_HANDLE"))
    val recent = DigestRngHistory(); recent.restore(digest.profiles.values.first().history, 20, time); recent.clear()
    check(pins.events.size == 3 && pins.pins.isNotEmpty()) // Permanent museum is independent of recent retention.
    val confirmed = event.copy(id = acquisitionId("inventory-receipt/NECRON_HANDLE"), chestCost = 100.0)
    val once = mergeAcquisition(AcquisitionArchive(), confirmed)
    check(mergeAcquisition(once, confirmed.copy(chestCost = null)) == once)
    check(mergeAcquisition(once, confirmed.copy(id = acquisitionId("second-receipt/NECRON_HANDLE"))).events.size == 2)
    val detector = DigestDungeonAcquisition(); detector.seed(emptyMap())
    val drop = DetectedRng("3".repeat(32), "NECRON_HANDLE", "dungeon", time)
    check(detector.announce(drop, time) == null)
    val detected = detector.inventory(mapOf("NECRON_HANDLE" to 1), time, "inventory-receipt").single()
    check(acquisitionId(detected.confirmation!!) == confirmed.id)
    check(canonicalItemId("item:necron_handle") == "NECRON_HANDLE" && canonicalItemId("!item:giants_sword") == "GIANTS_SWORD")
    check(receivedChestItems(mapOf("X" to 2), mapOf("X" to 1), mapOf("X" to 2)).isEmpty())
    check(receivedChestItems(mapOf("X" to 2), mapOf("X" to 1), mapOf("X" to 3)) == setOf("X"))
    val unknown = TrackerLootRow("UNKNOWN", 3, null, null, 0)
    val known = TrackerLootRow("KNOWN", 1, 1.0, 1, 2)
    for (ascending in listOf(false, true)) check(sortedLoot(listOf(unknown, known), TrackerFilter(ascending = ascending)).last() == unknown)
    val legacy = LegacyFloorView("F7", TrackerSummary(runs = 20, time = 0, timedRuns = 0), mapOf("BEDROCK" to listOf(known)), mapOf("BEDROCK" to 10.0), emptyMap(), mapOf("BEDROCK" to 1))
    check(trackerView(state, listOf(legacy), TrackerFilter(legacyTotals = true), context).let { it.usingLegacy && it.summary.runs == 20L && it.loot.single().observedRate == null })
    val defaultHistory = trackerView(state, listOf(legacy), TrackerFilter(), context)
    check(!defaultHistory.usingLegacy && defaultHistory.historicalRuns == 20L && defaultHistory.historicalChests == 1)
    check(defaultHistory.summary.runs == 2L && defaultHistory.summary.cataXp == 2000.0) // Historical totals stay visible without entering new measurements.
    check(defaultHistory.loot.none { it.item == "KNOWN" })
    check(!trackerView(state, listOf(legacy), TrackerFilter(from = "2026-09-24"), context).usingLegacy)
    check(trackerView(state, listOf(legacy), TrackerFilter(floor = "M7"), context).historicalRuns == 0L)
    check(trackerView(state, listOf(legacy), TrackerFilter(from = "2026-09-25"), context).let { it.summary.runs == 0L && it.historicalRuns == 20L })
    check(parseTrackerPreferences(Gson().toJsonTree(TrackerFilter(floor = "E"))).floor == "E")
    val entrance = cutoverAcquisitions(AcquisitionArchive(version = 1), JsonParser.parseString("""{"E":{"chests":{}}}"""), time)
    check(parseAcquisitionArchive(Gson().toJsonTree(entrance)).legacyProfit.keys == setOf("E"))
    check(raw.toString().contains("$time,$time")) // Import did not mutate the original save.
    println("Tracker checks passed: dungeon profile continuity, visible historical totals, scoped filters, safe partial rates, legacy preservation, repeat drops, shared receipts, permanent museum and offline access")
}
