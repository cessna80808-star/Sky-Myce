package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.features.digest.*
import java.time.LocalDate
import java.time.ZoneId

fun checkTracker() {
    checkRngJournal()
    checkDungeonTiming()
    checkChestTracking()
    checkAcquisitionValuations()
    checkLifetimeDungeonSummary()
    checkCombinedDungeonLoot()
    checkMuseumLootCounts()
    checkUndatedTrackerLoot()
    checkReceiptHistoryProjection()
    checkTimelineRecombobulators()
    checkPersonalRateSamples()
    checkMarketValuedTrackerSummary()
    checkLegacyCostCoverage()
    checkDatedLegacyRuns()
    checkDungeonWidgetRates()
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
    check(TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES).events(imported.events, null, emptyList()).size == 3)
    check(TrackerFilter(scope = TrackerScope.UNSCOPED_LEGACY).events(imported.events, null, emptyList()).size == 2)
    check(TrackerFilter().events(imported.events, context, emptyList()).isEmpty())
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
    check(trackerView(state, listOf(legacy), TrackerFilter(legacyTotals = true, scope = TrackerScope.UNSCOPED_LEGACY), context).let { it.usingLegacy && it.summary.runs == 20L && it.loot.single().observedRate == null })
    val defaultHistory = trackerView(state, listOf(legacy), TrackerFilter(), context)
    check(!defaultHistory.usingLegacy && defaultHistory.historicalRuns == 20L && defaultHistory.historicalChests == 1)
    check(defaultHistory.summary.runs == 2L && defaultHistory.summary.cataXp == 2000.0) // Historical totals stay visible without entering new measurements.
    check(defaultHistory.loot.none { it.item == "KNOWN" })
    check(!trackerView(state, listOf(legacy), TrackerFilter(from = "2026-09-24"), context).usingLegacy)
    check(trackerView(state, listOf(legacy), TrackerFilter(floor = "M7"), context).historicalRuns == 0L)
    check(trackerView(state, listOf(legacy), TrackerFilter(from = "2026-09-25"), context).let { it.summary.runs == 0L && it.historicalRuns == 20L })
    check(parseTrackerPreferences(Gson().toJsonTree(TrackerFilter(floor = "E"))).floor == "E")
    val oldPreferences = Gson().toJsonTree(TrackerFilter(floor = "M7", legacyTotals = true)).asJsonObject.apply { remove("overviewVersion") }
    check(parseTrackerPreferences(oldPreferences).let { it.scope == TrackerScope.ALL_LOCAL_PROFILES && !it.legacyTotals && it.floor == "M7" })
    check(parseTrackerPreferences(Gson().toJsonTree(TrackerFilter())).scope == TrackerScope.CURRENT_PROFILE) // A new explicit scope choice persists.
    for (selected in listOf(TrackerFilter(from = "2026-09-24"), TrackerFilter(session = "selected-session"))) {
        val datedPreferences = Gson().toJsonTree(selected).asJsonObject.apply { remove("overviewVersion") }
        check(parseTrackerPreferences(datedPreferences) == selected) // Migration cannot attach legacy totals to a profile/date/session.
    }
    check(runCatching { parseTrackerPreferences(oldPreferences.deepCopy().apply { addProperty("overviewVersion", -1) }) }.isFailure)
    checkTrackerPreferenceUpgrade()
    check(parseTrackerPreferences(null).scope == TrackerScope.ALL_LOCAL_PROFILES)
    val entrance = cutoverAcquisitions(AcquisitionArchive(version = 1), JsonParser.parseString("""{"E":{"chests":{}}}"""), time)
    check(parseAcquisitionArchive(Gson().toJsonTree(entrance)).legacyProfit.keys == setOf("E"))
    check(raw.toString().contains("$time,$time")) // Import did not mutate the original save.
    println("Tracker checks passed: dungeon profile continuity, visible historical totals, scoped filters, safe partial rates, legacy preservation, repeat drops, shared receipts, permanent museum and offline access")
}

private fun checkDatedLegacyRuns() {
    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val known = DungeonRunRecord("known-history", "known-session", context, "M7", time - 60000, time, 60000,
        chests = listOf(ChestReceipt("known-chest", time, "BEDROCK", 4.0, listOf(RunLoot("OLD_LOOT", 1, 100.0)))), trackingVersion = 1)
    val unscoped = known.copy(id = "imported-history", session = "imported-session", context = null,
        completedAt = time + 120000, chests = emptyList())
    val other = known.copy(id = "other-history", context = context.copy(profileId = "c".repeat(32), profileName = "Pear"),
        completedAt = time + 180000, chests = listOf(known.chests.single().copy(id = "other-chest")))
    val current = known.copy(id = "current", session = "current-session", completedAt = time + 240000,
        chests = listOf(ChestReceipt("current-chest", time + 240000, "BEDROCK", 2.0, listOf(RunLoot("NEW_LOOT", 1, 200.0)))), trackingVersion = 2)
    val archive = AcquisitionArchive(runs = listOf(known, unscoped, other, current))
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 3, time = 180000, timedRuns = 3, incompleteCosts = true),
        mapOf("BEDROCK" to listOf(TrackerLootRow("OLD_LOOT", 2, 200.0, null, 2))),
        mapOf("BEDROCK" to 8.0), emptyMap(), mapOf("BEDROCK" to 2))
    val filter = TrackerFilter(floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun view(selected: TrackerFilter) = trackerView(archive, listOf(historical), selected, context)
    check(filter.runs(archive.runs, null) == listOf(current))
    check(view(filter).let { it.summary.runs == 4L && it.loot.first { row -> row.item == "OLD_LOOT" }.count == 2L }) // Frozen totals already include all three dated v1 records.
    val dated = filter.copy(from = "2026-09-24", until = "2026-09-24")
    check(dated.runs(archive.runs, null).size == 4)
    check(view(dated).let { it.summary.runs == 4L && it.summary.incompleteCosts && it.summary.time == 240000L })
    check(view(dated.copy(scope = TrackerScope.CURRENT_PROFILE)).summary.runs == 2L)
    check(view(dated.copy(scope = TrackerScope.UNSCOPED_LEGACY)).let { it.summary.runs == 1L && it.summary.incompleteCosts })
    check(view(filter.copy(scope = TrackerScope.CURRENT_PROFILE)).let { it.summary.runs == 1L && !it.summary.incompleteCosts })
    check(view(filter.copy(session = "current-session")).let { it.summary.runs == 1L && !it.summary.incompleteCosts })
    check(view(filter.copy(session = "imported-session")).let { it.summary.runs == 1L && it.summary.incompleteCosts })
    check(view(filter.copy(scope = TrackerScope.CURRENT_PROFILE, session = "imported-session")).summary.runs == 0L)
    check(view(dated.copy(from = "2026-09-25", until = "")).summary.runs == 0L)
    val oldSchema = parseAcquisitionArchive(Gson().toJsonTree(archive.copy(version = 1)))
    check(oldSchema.runs.all { it.trackingVersion == 1 } && dated.runs(oldSchema.runs, null).size == 4)
    check(dated.copy(scope = TrackerScope.CURRENT_PROFILE).runs(oldSchema.runs, context).size == 2)
    check(filter.runs(oldSchema.runs, null).isEmpty())
}

private fun checkLegacyCostCoverage() {
    val floor = tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.M7
    val totals = FloorTracker(totalRuns = 2, totalTimeMillis = 120000, timedRuns = 2,
        chests = mutableMapOf(me.mycellium.skymyce.api.DungeonChest.BEDROCK to ChestTracker(
            chestCount = 2, chestCost = 100.0, rerollCount = 1, rerollCost = 20.0)))
    val oldJson = FloorTracker.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, totals).result().orElseThrow().asJsonObject.apply {
        remove("incompleteCosts")
    }
    val old = FloorTracker.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, oldJson).result().orElseThrow()
    check(!old.incompleteCosts && old.totalChestsCost == 100.0 && old.totalRerolledCost == 20.0)
    val flagged = FloorTracker.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
        oldJson.deepCopy().apply { addProperty("incompleteCosts", true) }).result().orElseThrow()
    val filter = TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES, floor = "M7")
    val archive = AcquisitionArchive(legacyProfit = mapOf("M7" to 500.0))
    val complete = trackerView(archive, legacyTrackerView(mapOf(floor to old)), filter, null).summary
    val partial = trackerView(archive, legacyTrackerView(mapOf(floor to flagged)), filter, null).summary
    check(!complete.incompleteCosts && complete.knownNet == 500.0 && partial.knownNet == complete.knownNet)
    check(partial.incompleteCosts && partial.profitIncomplete && partial.net == null)
    val shown = marketValuedTrackerSummary(partial, listOf(TrackerLootRow("LOOT", 1, 1000.0, null, 2)))
    check(shown.knownNet == 880.0 && shown.net == null && shown.incompleteCosts)
    check(!totals.incompleteCosts && flagged.totalChestsCost == old.totalChestsCost && flagged.totalRerolledCost == old.totalRerolledCost)
}

private fun checkMarketValuedTrackerSummary() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 4), mapOf("BEDROCK" to listOf(
        TrackerLootRow("IMPLOSION_SCROLL", 4, 400.0, null, 4), TrackerLootRow("NECRON_HANDLE", 5, 500.0, null, 4),
        TrackerLootRow("ZERO", 0, 0.0, null, 4))),
        mapOf("BEDROCK" to 100.0), mapOf("BEDROCK" to 50.0), mapOf("BEDROCK" to 4))
    val first = DungeonRunRecord("first", "first-session", context, "M7", time - 60000, time, 60000,
        chests = listOf(ChestReceipt("first-chest", time, "BEDROCK", 20.0, listOf(RunLoot("IMPLOSION_SCROLL", 1, 100.0)))),
        rerolls = 1, rerollCost = 5.0)
    val second = first.copy(id = "second", session = "second-session", context = context.copy(profileName = "Pear"), completedAt = time + 86400000,
        chests = listOf(ChestReceipt("second-chest", time + 86400000, "DIAMOND", 30.0,
            listOf(RunLoot("IMPLOSION_SCROLL", 1, 100.0), RunLoot("OTHER", 2)))), rerollCost = 7.0)
    val old = first.copy(id = "old-copy", session = "old-session", startedAt = time - 86460000, completedAt = time - 86400000,
        chests = first.chests.map { it.copy(id = "old-chest", time = it.time - 86400000) }, trackingVersion = 1)
    val archive = AcquisitionArchive(runs = listOf(old, first, second), legacyProfit = mapOf("M7" to 5000.0))
    val original = Gson().toJson(archive)
    val quotes = mapOf("IMPLOSION_SCROLL" to 10.0, "NECRON_HANDLE" to 100.0, "OTHER" to 20.0)
    val filter = TrackerFilter(page = TrackerPage.OVERVIEW, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun valued(selected: TrackerFilter, prices: Map<String, Double?> = quotes): TrackerSummary {
        val source = trackerView(archive, listOf(historical), selected, context)
        val rows = marketValuedLoot(source.loot) { prices[it] }
        val shown = marketValuedTrackerSummary(source.summary, rows)
        check(shown.gross == rows.sumOf { it.value ?: 0.0 })
        check(shown.copy(gross = source.summary.gross, incompleteValues = source.summary.incompleteValues,
            preservedProfit = source.summary.preservedProfit) == source.summary) // Counts, timing, XP and known fees are unchanged.
        return shown
    }
    val shown = valued(filter)
    check(shown.gross == 600.0 && shown.chestCost == 150.0 && shown.knownRerollCost == 62.0 && shown.net == 388.0)
    check(shown.preservedProfit == 0.0 && !shown.profitIncomplete)
    check(valued(filter.copy(page = TrackerPage.LOOT)) == shown) // Both pages use the identical selected item counts and quotes.
    val widgetSource = trackerView(archive, listOf(historical),
        TrackerFilter(floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES), context)
    val widgetSummary = marketValuedTrackerSummary(widgetSource.summary, marketValuedLoot(widgetSource.loot) { quotes[it] })
    check(widgetSummary == shown && dungeonTrackerWidgetLines(tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.M7, widgetSummary)
        .single { "Profit:" in it }.contains("388"))
    val missing = valued(filter, quotes - "NECRON_HANDLE")
    check(missing.gross == 100.0 && missing.knownNet == -112.0 && missing.net == null && missing.incompleteValues)
    val missingWidget = marketValuedTrackerSummary(widgetSource.summary, marketValuedLoot(widgetSource.loot) { (quotes - "NECRON_HANDLE")[it] })
    check(missingWidget == missing && dungeonTrackerWidgetLines(tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.M7, missingWidget)
        .single { "Profit:" in it }.contains("+ ?"))
    check(valued(filter.copy(scope = TrackerScope.CURRENT_PROFILE)).net == -15.0)
    check(valued(filter.copy(from = "2026-09-24", until = "2026-09-24")).net == -15.0)
    check(valued(filter.copy(session = "second-session")).net == 13.0)
    check(valued(filter.copy(chest = "BEDROCK")).let { it.knownNet == 380.0 && it.profitIncomplete }) // A chest filter cannot assign run-wide reroll costs to that chest.
    val unpriced = trackerView(archive, listOf(historical), filter, context).summary
    val knownZero = listOf(TrackerLootRow("ZERO", 0, estimatedLootMarketValue(0, null), null, 0))
    check(marketValuedTrackerSummary(unpriced, knownZero).let { it.gross == 0.0 && !it.incompleteValues && it.net == -212.0 })
    check(marketValuedTrackerSummary(unpriced.copy(incompleteCosts = true), knownZero).let { it.net == null && it.incompleteCosts && it.knownNet == -212.0 })
    check(marketValuedTrackerSummary(unpriced.copy(rerollCost = null), knownZero).let { it.net == null && it.knownRerollCost == 62.0 })
    check(Gson().toJson(archive) == original && archive.legacyProfit.getValue("M7") == 5000.0 && first.chests.single().loot.single().value == 100.0)
}

private fun checkMuseumLootCounts() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 5), mapOf("BEDROCK" to listOf(
        TrackerLootRow("IMPLOSION_SCROLL", 4, 4000.0, null, 5), TrackerLootRow("NECRON_HANDLE", 5, 5000.0, null, 5))),
        emptyMap(), emptyMap(), mapOf("BEDROCK" to 5))
    val run = DungeonRunRecord("new", "new-session", context, "M7", time - 60000, time, 60000,
        chests = listOf(ChestReceipt("new-chest", time, "BEDROCK", 0.0, listOf(RunLoot("IMPLOSION_SCROLL", 1, 1000.0)))))
    val old = run.copy(id = "old-copy", session = "old-session", startedAt = time - 86460000, completedAt = time - 86400000,
        chests = run.chests.map { it.copy(id = "old-chest", time = it.time - 86400000) }, trackingVersion = 1)
    val archive = AcquisitionArchive(runs = listOf(old, run), events = listOf(
        AcquisitionEvent("e".repeat(32), "IMPLOSION_SCROLL", time - 86400000, "dungeon", floor = "M7", quantity = 2, origin = "legacy-tracker"),
        AcquisitionEvent("f".repeat(32), "NECRON_HANDLE", time - 86400000, "dungeon", floor = "M7", origin = "legacy-tracker"),
        AcquisitionEvent("g".repeat(32), "IMPLOSION_SCROLL", time, "dungeon", context, "M7", "BEDROCK", run = run.id)))
    val filter = TrackerFilter(page = TrackerPage.MUSEUM, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    val museum = trackerView(archive, listOf(historical), filter, null)
    check(museum.loot == trackerView(archive, listOf(historical), filter.copy(page = TrackerPage.LOOT), null).loot)
    check(museum.loot.first { it.item == "IMPLOSION_SCROLL" }.count == 5L && museum.events.filter { it.item == "IMPLOSION_SCROLL" }.sumOf { it.quantity } == 3)
    check(museum.loot.first { it.item == "NECRON_HANDLE" }.count == 5L && museum.events.filter { it.item == "NECRON_HANDLE" }.sumOf { it.quantity } == 1)
    for (selected in listOf(filter.copy(from = "2026-09-24", until = "2026-09-24"), filter.copy(session = "new-session"),
        filter.copy(scope = TrackerScope.CURRENT_PROFILE))) {
        val scoped = trackerView(archive, listOf(historical), selected, context)
        check(scoped.loot.single().let { it.item == "IMPLOSION_SCROLL" && it.count == 1L })
        check(scoped.events.single().origin == "confirmed")
    }
}

private fun checkUndatedTrackerLoot() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 5), mapOf(
        "BEDROCK" to listOf(TrackerLootRow("NECRON_HANDLE", 5, 5_000_000.0, null, 5),
            TrackerLootRow("IMPLOSION_SCROLL", 4, 4_000_000.0, null, 5),
            TrackerLootRow("ORDINARY_LOOT", 8, 4_000_000.0, null, 5), TrackerLootRow("WARDEN_HEART", 7, 7_000_000.0, null, 5)),
        "OBSIDIAN" to listOf(TrackerLootRow("SHADOW_WARP_SCROLL", 2, 0.0, null, 2))),
        emptyMap(), emptyMap(), mapOf("BEDROCK" to 5, "OBSIDIAN" to 2))
    val otherFloor = historical.copy(floor = "F7", loot = mapOf("BEDROCK" to listOf(
        TrackerLootRow("NECRON_HANDLE", 9, 9_000_000.0, null, 9))))
    val run = DungeonRunRecord("new", "new-session", context, "M7", time - 60000, time, 60000,
        chests = listOf(ChestReceipt("new-chest", time, "BEDROCK", 0.0, listOf(RunLoot("IMPLOSION_SCROLL", 1, 1_000_000.0)))))
    val oldRun = run.copy(id = "old-copy", trackingVersion = 1)
    val handle = AcquisitionEvent("e".repeat(32), "NECRON_HANDLE", time - 86400000, "dungeon",
        floor = "M7", chest = "BEDROCK", origin = "legacy-tracker")
    val archive = AcquisitionArchive(runs = listOf(oldRun, run), events = listOf(handle,
        handle.copy(id = "f".repeat(32), item = "IMPLOSION_SCROLL", quantity = 2),
        handle.copy(id = "a".repeat(32), item = "IMPLOSION_SCROLL", occurredAt = time, context = context,
            quantity = 1, origin = "confirmed", value = 1_000_000.0, run = run.id)))
    val before = Gson().toJson(archive)
    val legacy = listOf(historical, otherFloor)
    val filter = TrackerFilter(page = TrackerPage.TIMELINE, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun view(selected: TrackerFilter = filter, state: AcquisitionArchive = archive, details: Boolean = true) =
        trackerView(state, legacy, selected, context, details)
    val timeline = view()
    check(timeline.loot == view(filter.copy(page = TrackerPage.MUSEUM)).loot)
    check(timeline.loot == view(filter.copy(page = TrackerPage.LOOT)).loot)
    check(timeline.loot.first { it.item == "IMPLOSION_SCROLL" }.count == 5L) // The v1 receipt is already in the aggregate.
    check(timeline.undatedLoot == mapOf("NECRON_HANDLE" to 4L, "IMPLOSION_SCROLL" to 2L, "SHADOW_WARP_SCROLL" to 2L))
    check(view(filter.copy(page = TrackerPage.MUSEUM)).undatedLoot == timeline.undatedLoot)
    check(timeline.events == filter.events(archive.events, context, archive.runs)) // No dates or events are invented.
    check(trackerView(archive, legacy, filter, null).undatedLoot == timeline.undatedLoot) // Works offline.
    check(view(filter.copy(scope = TrackerScope.UNSCOPED_LEGACY)).undatedLoot == timeline.undatedLoot)
    check(view(filter.copy(query = "Necron's Handle")).undatedLoot == mapOf("NECRON_HANDLE" to 4L))
    check(view(filter.copy(rareOnly = true)).undatedLoot == timeline.undatedLoot)
    check(view(filter.copy(valuableOnly = true)).let {
        it.events.size == 1 && it.undatedLoot == mapOf("NECRON_HANDLE" to 4L, "IMPLOSION_SCROLL" to 2L)
    }) // Unknown-valued dated drops still reduce the undated balance.
    check(view(filter.copy(chest = "BEDROCK")).undatedLoot == mapOf("NECRON_HANDLE" to 4L, "IMPLOSION_SCROLL" to 2L))
    check(view(filter.copy(chest = "OBSIDIAN")).undatedLoot == mapOf("SHADOW_WARP_SCROLL" to 2L))
    check(view(filter.copy(chest = "DIAMOND")).undatedLoot.isEmpty())
    check(view(filter.copy(floor = null, mode = "M")).undatedLoot == timeline.undatedLoot)
    check(view(filter.copy(floor = null, mode = "F")).undatedLoot == mapOf("NECRON_HANDLE" to 9L))
    check(view(filter.copy(floor = "F7")).undatedLoot == mapOf("NECRON_HANDLE" to 9L))
    check(view(filter.copy(activity = "dungeon")).undatedLoot == timeline.undatedLoot)
    for (selected in listOf(filter.copy(scope = TrackerScope.CURRENT_PROFILE, legacyTotals = true),
        filter.copy(from = "2026-09-24"), filter.copy(until = "2026-09-24"), filter.copy(session = run.session),
        filter.copy(activity = "slayer"), filter.copy(page = TrackerPage.OVERVIEW)))
        check(view(selected).undatedLoot.isEmpty())
    check(view(details = false).undatedLoot.isEmpty())
    val overlapping = archive.copy(events = archive.events + handle.copy(id = "b".repeat(32), quantity = 10))
    check(view(state = overlapping).let { "NECRON_HANDLE" !in it.undatedLoot && it.loot.first { row -> row.item == "NECRON_HANDLE" }.count == 5L })
    val many = archive.copy(runs = emptyList(), events = (0 until 105).map { handle.copy(id = it.toString(16).padStart(32, '0')) })
    val fullHistory = trackerView(many, listOf(historical.copy(loot = mapOf("BEDROCK" to listOf(
        TrackerLootRow("NECRON_HANDLE", 105, 105_000_000.0, null, 105))))), filter, null)
    check(fullHistory.events.size == 105 && fullHistory.undatedLoot.isEmpty()) // Coverage uses more than the first timeline page.
    check(Gson().toJson(archive) == before)
}

private fun checkReceiptHistoryProjection() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val loot = RunLoot("NECRON_HANDLE", 2, 4_000_000.0, "LEGENDARY", "Necron's Handle")
    val chest = ChestReceipt("paid-chest", time, "BEDROCK", 100.0, listOf(loot, RunLoot("ORDINARY_LOOT", 8)))
    val run = DungeonRunRecord("paid-run", "session", context, "M7", time - 60000, time, 60000, chests = listOf(chest))
    val archive = AcquisitionArchive(runs = listOf(run))
    val before = Gson().toJson(archive)
    val legacy = LegacyFloorView("M7", TrackerSummary(), mapOf("BEDROCK" to listOf(
        TrackerLootRow("NECRON_HANDLE", 3, 6_000_000.0, null, 3))), emptyMap(), emptyMap(), mapOf("BEDROCK" to 3))
    val filter = TrackerFilter(page = TrackerPage.TIMELINE, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun view(state: AcquisitionArchive = archive, selected: TrackerFilter = filter) = trackerView(state, listOf(legacy), selected, context)
    val projected = view()
    val event = projected.events.single()
    check(event.item == loot.item && event.quantity == 2 && event.occurredAt == chest.time && event.value == loot.value)
    check(event.context == context && event.floor == run.floor && event.chest == chest.chest && event.run == run.id)
    check(event.rarity == loot.rarity && event.displayName == loot.displayName && event.chestCost == chest.cost)
    check(projected.loot.first { it.item == loot.item }.count == 5L && projected.undatedLoot == mapOf(loot.item to 3L))
    check(view().events.single().id == event.id && view(selected = filter.copy(query = "handle")).events.single().id == event.id)
    check(view(selected = filter.copy(scope = TrackerScope.CURRENT_PROFILE)).events == listOf(event))
    check(view(selected = filter.copy(scope = TrackerScope.UNSCOPED_LEGACY)).events.isEmpty())
    check(view(selected = filter.copy(from = "2026-09-25")).events.isEmpty())
    check(view(selected = filter.copy(session = run.session)).events == listOf(event))
    for (hidden in listOf(event.copy(floor = null), event.copy(occurredAt = time - 86400000))) {
        val dated = view(archive.copy(events = listOf(hidden)), filter.copy(from = "2026-09-24", until = "2026-09-24"))
        check(dated.events.single().let { it.quantity == 2 && it.occurredAt == chest.time && it.floor == run.floor })
    }
    val linked = event.copy(id = "c".repeat(32), quantity = 1, value = 2_000_000.0)
    val partial = archive.copy(events = listOf(linked))
    val remaining = view(partial).events.single { it.id != linked.id }
    check(remaining.quantity == 1 && remaining.value == 2_000_000.0 && remaining.occurredAt == chest.time)
    check(view(partial).events.first { it.id == linked.id } == linked && view(partial).undatedLoot == projected.undatedLoot)
    check(view(archive.copy(events = listOf(event.copy(id = linked.id)))).events == listOf(event.copy(id = linked.id)))
    val wrongContext = linked.copy(context = context.copy(profileName = "Pear"))
    check(view(archive.copy(events = listOf(wrongContext)), filter.copy(scope = TrackerScope.CURRENT_PROFILE)).events.single().quantity == 2)
    val unlinked = linked.copy(run = null, origin = "legacy-tracker")
    check(view(archive.copy(events = listOf(unlinked))).events.sumOf { it.quantity } == 3) // Similar dates do not prove a legacy link.
    check(view(archive.copy(runs = listOf(run.copy(trackingVersion = 1)))).events.isEmpty())
    val second = chest.copy(id = "second-chest", time = time + 1000, chest = "OBSIDIAN")
    val both = archive.copy(runs = listOf(run.copy(chests = listOf(second, chest))),
        events = listOf(linked, linked.copy(id = "d".repeat(32), chest = null)))
    check(view(both).events.sumOf { it.quantity } == 4) // Each linked quantity covers only one paid quantity.
    check(view(both).events.single { it.id !in both.events.map { saved -> saved.id } }.let {
        it.chest == "OBSIDIAN" && it.quantity == 2 && it.occurredAt == second.time
    }) // Receipt order is stable, even when the stored list is reversed.
    check(view(both, filter.copy(chest = "BEDROCK")).let {
        it.events.sumOf { event -> event.quantity } == 2 && it.undatedLoot == mapOf(loot.item to 3L)
    }) // A hidden chest-unspecified announcement cannot hide a receipt's known date.
    check(view(both, filter.copy(chest = "OBSIDIAN")).let {
        it.events.single().quantity == 2 && it.undatedLoot.isEmpty()
    })
    check(Gson().toJson(archive) == before && archive.events.isEmpty())
}

private fun checkTimelineRecombobulators() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val time = 1_000_000L
    val standard = "RECOMBOBULATOR_3000"
    val auto = "AUTO_RECOMBOBULATOR"
    val run = DungeonRunRecord("run", "session", context, "M7", chests = listOf(
        ChestReceipt("paid", time, "BEDROCK", 0.0, listOf(RunLoot(standard, 2), RunLoot(auto, 1)))))
    val standardEvent = AcquisitionEvent("e".repeat(32), standard, time, "dungeon", context, "M7", "BEDROCK", quantity = 2, run = run.id)
    val autoEvent = standardEvent.copy(id = "f".repeat(32), item = auto, quantity = 1)
    val archive = AcquisitionArchive(events = listOf(standardEvent, autoEvent), runs = listOf(run))
    val legacy = LegacyFloorView("M7", TrackerSummary(), mapOf("BEDROCK" to listOf(
        TrackerLootRow(standard, 5, 0.0, null, 5), TrackerLootRow(auto, 2, 0.0, null, 2))), emptyMap(), emptyMap(), emptyMap())
    val before = Gson().toJson(archive) to Gson().toJson(legacy)
    val filter = TrackerFilter(page = TrackerPage.TIMELINE, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    for (query in listOf("", "recomb", "AUTO_RECOMBOBULATOR")) {
        val selected = filter.copy(query = query)
        val timeline = trackerView(archive, listOf(legacy), selected, null)
        check(timeline.events == listOf(autoEvent) && timeline.loot.single().let { it.item == auto && it.count == 3L })
        check(standard !in timeline.undatedLoot && selected.events(archive.events, null, archive.runs) == listOf(autoEvent))
    }
    val searched = trackerView(archive, listOf(legacy), filter.copy(query = standard), null)
    check(searched.events.isEmpty() && searched.loot.isEmpty() && searched.undatedLoot.isEmpty())
    for (page in listOf(TrackerPage.OVERVIEW, TrackerPage.LOOT, TrackerPage.MUSEUM)) {
        val other = trackerView(archive, listOf(legacy), filter.copy(page = page), null)
        check(other.events.toSet() == archive.events.toSet())
        check(other.loot.associate { it.item to it.count } == mapOf(standard to 7L, auto to 3L))
    }
    val standardOnly = archive.copy(events = listOf(standardEvent), runs = listOf(run.copy(chests = run.chests.map {
        it.copy(loot = it.loot.filter { item -> item.item == standard })
    })))
    check(trackerView(standardOnly, emptyList(), filter, null).let { it.events.isEmpty() && it.loot.isEmpty() && it.undatedLoot.isEmpty() })
    check((Gson().toJson(archive) to Gson().toJson(legacy)) == before)
}

private fun checkPersonalRateSamples() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 4), mapOf("BEDROCK" to listOf(
        TrackerLootRow("IMPLOSION_SCROLL", 4, 4000.0, null, 4), TrackerLootRow("ZERO", 3, 0.0, null, 4))),
        emptyMap(), emptyMap(), mapOf("BEDROCK" to 4))
    val run = DungeonRunRecord("new", "new-session", context, "M7", 1000, 61000, 60000,
        chests = listOf(ChestReceipt("new-chest", 61000, "BEDROCK", 0.0, listOf(RunLoot("IMPLOSION_SCROLL", 1, 1000.0)))))
    val sample = PersonalRateSample("M7", 1000, 2000, 792, mapOf("IMPLOSION_SCROLL" to 2L, "ZERO" to 0L))
    val archive = AcquisitionArchive(runs = listOf(run), personalRateSamples = listOf(sample))
    val filter = TrackerFilter(page = TrackerPage.LOOT, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun rows(selected: TrackerFilter = filter, state: AcquisitionArchive = archive) =
        trackerView(state, listOf(historical), selected, context).loot.associateBy { it.item }
    val saved = rows()
    check(saved.getValue("IMPLOSION_SCROLL").let { it.count == 5L && it.value == 5000.0 && it.observedRate == 2.0 / 792 && it.acquiredChests == null })
    check(saved.getValue("ZERO").let { it.count == 3L && it.observedRate == 0.0 }) // All-time counts can include newer acquisitions outside the dated sample.
    check(rows(filter.copy(scope = TrackerScope.UNSCOPED_LEGACY)).getValue("IMPLOSION_SCROLL").let { it.count == 4L && it.rateSample == sample })
    check(rows(filter.copy(scope = TrackerScope.CURRENT_PROFILE)).getValue("IMPLOSION_SCROLL").let { it.count == 1L && it.rateSample == null && it.observedRate == 1.0 })
    for (selected in listOf(filter.copy(from = "1970-01-01"), filter.copy(until = "1970-01-01"),
        filter.copy(session = "new-session"), filter.copy(chest = "BEDROCK")))
        check(rows(selected).values.all { it.rateSample == null })
    check(rows(filter.copy(floor = "F7")).isEmpty() && rows(filter.copy(floor = null, mode = "F")).isEmpty())
    check(rows(state = archive.copy(personalRateSamples = emptyList())).getValue("IMPLOSION_SCROLL").observedRate == null)
    val ambiguous = archive.copy(personalRateSamples = listOf(sample, sample.copy(floor = "F7")))
    check(rows(filter.copy(floor = null), ambiguous).getValue("IMPLOSION_SCROLL").let { it.rateSample == null && it.observedRate == null })
    check(rows(filter.copy(floor = null, mode = "M"), ambiguous).getValue("IMPLOSION_SCROLL").rateSample == sample)
    check(parseAcquisitionArchive(Gson().toJsonTree(archive)) == archive)
    val invalid = listOf(sample.copy(floor = "M8"), sample.copy(from = 0), sample.copy(until = sample.from),
        sample.copy(completedRuns = 0), sample.copy(completedRuns = 1_000_001),
        sample.copy(itemCounts = mapOf("bad item" to 1L)), sample.copy(itemCounts = mapOf("ITEM" to -1L)),
        sample.copy(itemCounts = mapOf("ITEM" to 1_000_001L)),
        sample.copy(itemCounts = (0..1000).associate { "ITEM_$it" to 1L }))
    for (bad in invalid) check(runCatching { parseAcquisitionArchive(Gson().toJsonTree(archive.copy(personalRateSamples = listOf(bad)))) }.isFailure)
    check(runCatching { parseAcquisitionArchive(Gson().toJsonTree(archive.copy(personalRateSamples = listOf(sample, sample)))) }.isFailure)
    check(runCatching { parseAcquisitionArchive(Gson().toJsonTree(archive).asJsonObject.apply { add("personalRateSamples", com.google.gson.JsonNull.INSTANCE) }) }.isFailure)
    for (field in listOf("from", "until", "completedRuns")) for (invalid in listOf("1.5", "\"123\"", "null", "9223372036854775808")) {
        val json = Gson().toJsonTree(archive).asJsonObject
        json.getAsJsonArray("personalRateSamples")[0].asJsonObject.add(field, JsonParser.parseString(invalid))
        check(runCatching { parseAcquisitionArchive(json) }.isFailure)
    }
    for (invalid in listOf("1.5", "\"2\"", "null", "9223372036854775808")) {
        val json = Gson().toJsonTree(archive).asJsonObject
        json.getAsJsonArray("personalRateSamples")[0].asJsonObject.getAsJsonObject("itemCounts")
            .add("IMPLOSION_SCROLL", JsonParser.parseString(invalid))
        check(runCatching { parseAcquisitionArchive(json) }.isFailure)
    }
    val withoutSamples = Gson().toJsonTree(archive).asJsonObject.apply { remove("personalRateSamples") }
    check(parseAcquisitionArchive(withoutSamples).personalRateSamples.isEmpty())
}

private fun checkTrackerPreferenceUpgrade() {
    val saved = TrackerFilter(page = TrackerPage.LOOT, floor = "M7", legacyTotals = true,
        sort = LootOrder.NAME, ascending = true)
    val historical = saved.copy(scope = TrackerScope.ALL_LOCAL_PROFILES, legacyTotals = false)
    for (version in listOf<Int?>(null, 1)) {
        val json = Gson().toJsonTree(saved).asJsonObject.apply {
            if (version == null) remove("overviewVersion") else addProperty("overviewVersion", version)
        }
        val migrated = parseTrackerPreferences(json)
        check(migrated == historical) // The old historical toggle ignored its misleading Current profile scope.
        check(parseTrackerPreferences(Gson().toJsonTree(migrated)) == historical)
        check(Gson().toJsonTree(migrated).asJsonObject.get("overviewVersion").asInt == 2)
        for (selected in listOf(saved.copy(from = "2026-09-24"), saved.copy(until = "2026-09-24"),
            saved.copy(session = "selected-session"), saved.copy(legacyTotals = false), saved.copy(scope = TrackerScope.UNSCOPED_LEGACY))) {
            val preserved = Gson().toJsonTree(selected).asJsonObject.apply {
                if (version == null) remove("overviewVersion") else addProperty("overviewVersion", version)
            }
            check(parseTrackerPreferences(preserved) == selected)
        }
    }
    check(parseTrackerPreferences(Gson().toJsonTree(saved)) == saved) // A new explicit Current profile choice stays scoped.
    for (invalid in listOf("3", "1.1", "2.0", "\"1\"", "\"2\"", "null")) {
        val json = Gson().toJsonTree(saved).asJsonObject.apply { add("overviewVersion", JsonParser.parseString(invalid)) }
        check(runCatching { parseTrackerPreferences(json) }.isFailure)
    }
}

private fun checkCombinedDungeonLoot() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val other = context.copy(profileName = "Pear")
    val time = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 12), mapOf(
        "BEDROCK" to listOf(TrackerLootRow("IMPLOSION_SCROLL", 4, 4000.0, null, 10),
            TrackerLootRow("DYE_NECRON", 3, 3000.0, null, 10), TrackerLootRow("SHADOW_WARP_SCROLL", 4, 4000.0, null, 10)),
        "DIAMOND" to listOf(TrackerLootRow("RECOMBOBULATOR_3000", 2, 200.0, null, 2))),
        emptyMap(), emptyMap(), mapOf("BEDROCK" to 10, "DIAMOND" to 2))
    val normal = historical.copy(floor = "F7", loot = mapOf("BEDROCK" to listOf(TrackerLootRow("NECRON_HANDLE", 7, 7000.0, null, 7))))
    val first = DungeonRunRecord("new", "first-session", context, "M7", time - 60000, time, 60000,
        chests = listOf(ChestReceipt("new-chest", time, "BEDROCK", 0.0,
            listOf(RunLoot("IMPLOSION_SCROLL", 1, 1000.0), RunLoot("DYE_NECRON", 1), RunLoot("NECRON_HANDLE", 1, 5000.0)))))
    val second = first.copy(id = "other", session = "second-session", context = other, completedAt = time + 86400000,
        chests = listOf(ChestReceipt("other-chest", time + 86400000, "DIAMOND", 0.0, listOf(RunLoot("RECOMBOBULATOR_3000", 1, 100.0)))))
    val old = first.copy(id = "duplicated-old", session = "old-session", startedAt = time - 86460000, completedAt = time - 86400000,
        chests = first.chests.map { it.copy(id = "old-chest", time = it.time - 86400000) }, trackingVersion = 1)
    val archive = AcquisitionArchive(runs = listOf(old, first, second),
        legacyProfit = mapOf("M7" to 9000.0))
    val legacy = listOf(historical, normal)
    val filter = TrackerFilter(page = TrackerPage.LOOT, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES)
    fun view(selected: TrackerFilter = filter) = trackerView(archive, legacy, selected, null)
    val saved = view()
    val items = saved.loot.associateBy { it.item }
    check(items.getValue("IMPLOSION_SCROLL").let { it.count == 5L && it.value == 5000.0 && it.acquiredChests == null && it.observedRate == null })
    check(items.getValue("DYE_NECRON").let { it.count == 4L && it.value == null && it.observedRate == null })
    check(items.getValue("SHADOW_WARP_SCROLL").count == 4L)
    check(items.getValue("NECRON_HANDLE").let { it.count == 1L && it.observedRate == 0.5 }) // This row has only recorded receipts.
    check(items.getValue("RECOMBOBULATOR_3000").let { it.count == 3L && it.observedRate == null })
    check(!saved.usingLegacy && saved.summary == dungeonTrackerLifetimeSummary(archive, legacy, "M7"))
    check(view(filter.copy(legacyTotals = true)).loot == saved.loot) // A persisted old toggle cannot hide either period.
    check(view(filter.copy(query = "shadow warp")).loot.single().item == "SHADOW_WARP_SCROLL")
    check(view(filter.copy(query = "shadow warp").clearConstraints()).loot.any { it.item == "IMPLOSION_SCROLL" && it.count == 5L })
    check(view(filter.copy(chest = "BEDROCK")).loot.none { it.item == "RECOMBOBULATOR_3000" })
    check(view(filter.copy(chest = "DIAMOND")).loot.single().count == 3L)
    check(view(filter.copy(chest = "WOOD")).loot.isEmpty())
    check(view(filter.copy(floor = "F7")).loot.single().let { it.item == "NECRON_HANDLE" && it.count == 7L })
    check(view(filter.copy(floor = null, mode = "M")).loot == saved.loot)
    val dated = view(filter.copy(from = "2026-09-24", until = "2026-09-24", legacyTotals = true)).loot.associateBy { it.item }
    check(dated.getValue("IMPLOSION_SCROLL").let { it.count == 1L && it.observedRate == 1.0 })
    check(dated.getValue("DYE_NECRON").count == 1L && "SHADOW_WARP_SCROLL" !in dated && "RECOMBOBULATOR_3000" !in dated)
    check(view(filter.copy(from = "2026-09-26", legacyTotals = true)).loot.isEmpty())
    check(view(filter.copy(session = "second-session", legacyTotals = true)).loot.single().let { it.count == 1L && it.observedRate == 1.0 })
    val scoped = trackerView(archive, legacy, filter.copy(scope = TrackerScope.CURRENT_PROFILE, legacyTotals = true), context).loot.associateBy { it.item }
    check(scoped.getValue("IMPLOSION_SCROLL").count == 1L && scoped.getValue("DYE_NECRON").count == 1L && "SHADOW_WARP_SCROLL" !in scoped)
    check(trackerView(archive, legacy, filter.copy(scope = TrackerScope.CURRENT_PROFILE), null).loot.isEmpty())
    val unscoped = view(filter.copy(scope = TrackerScope.UNSCOPED_LEGACY))
    check(unscoped.usingLegacy && unscoped.loot.first { it.item == "IMPLOSION_SCROLL" }.count == 4L)
    check(view(filter.copy(scope = TrackerScope.UNSCOPED_LEGACY, from = "2026-09-24", legacyTotals = true)).loot.isEmpty())
    val constraints = filter.copy(mode = "M", chest = "BEDROCK", query = "shadow warp", rareOnly = true, valuableOnly = true,
        from = "2026-09-24", until = "2026-09-24", session = "first-session", activity = "dungeon", legacyTotals = true,
        sort = LootOrder.NAME, ascending = true, oldestFirst = true)
    check(constraints.clearConstraints() == TrackerFilter(page = TrackerPage.LOOT, scope = TrackerScope.ALL_LOCAL_PROFILES,
        legacyTotals = true, sort = LootOrder.NAME, ascending = true, oldestFirst = true))
    check(constraints.copy(scope = TrackerScope.CURRENT_PROFILE).clearConstraints().scope == TrackerScope.CURRENT_PROFILE)
    val imported = AcquisitionEvent("e".repeat(32), "SHADOW_WARP_SCROLL", time, "dungeon", null, "M7", origin = "legacy-tracker")
    check(trackerView(archive.copy(events = listOf(imported)), legacy, constraints.clearConstraints(), null).events == listOf(imported))
}

private fun checkDungeonWidgetRates() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val timed = DungeonRunRecord("timed", "session", context, "M7", 1_000, 61_000, 60_000, 1_000.0,
        chests = listOf(ChestReceipt("paid", 61_000, "BEDROCK", 100.0, listOf(RunLoot("LOOT", 1, 5.0)))),
        rerolls = 1, rerollCost = 50.0)
    val untimed = timed.copy(id = "untimed", duration = null, cataXp = 900_000.0,
        chests = listOf(ChestReceipt("untimed-paid", 70_000, "BEDROCK", 1_000.0, listOf(RunLoot("LOOT", 100, 900_000.0)))))
    val historical = LegacyFloorView("M7", TrackerSummary(runs = 20, time = 1_140_000, timedRuns = 19, cataXp = 90_000.0),
        mapOf("BEDROCK" to listOf(TrackerLootRow("LOOT", 20, 1.0, null, 20))),
        mapOf("BEDROCK" to 2_000.0), mapOf("BEDROCK" to 100.0), mapOf("BEDROCK" to 20))
    val archive = AcquisitionArchive(runs = listOf(timed, untimed,
        timed.copy(id = "old-copy", trackingVersion = 1), timed.copy(id = "other-floor", floor = "F7"),
        timed.copy(id = "not-completed", completedAt = null), timed.copy(id = "no-duration", duration = 0)))
    val sample = dungeonTrackerRateSample(archive, listOf(historical), "M7")
    check(sample.summary.runs == 1L && sample.summary.time == 60_000L && sample.summary.cataXp == 1_000.0)
    check(sample.loot.single().count == 1L && sample.summary.chestCost == 100.0 && sample.summary.knownRerollCost == 50.0)
    fun valued(view: TrackerView) = marketValuedTrackerSummary(view.summary, marketValuedLoot(view.loot) { 1_000.0 })
    val rates = valued(sample)
    check(rates.knownNet == 850.0 && rates.hourlyRate(rates.knownNet) == 51_000.0 && rates.hourlyRate(rates.cataXp) == 60_000.0)
    val total = valued(trackerView(archive, listOf(historical), TrackerFilter(floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES), null))
    val rows = dungeonTrackerWidgetLines(tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.M7, total, rates)
        .map { it.replace(Regex("§."), "") }
    check(rows.size == 9 && rows.none { "≥" in it } && rows[3].contains("recorded"))
    check(rows[4].contains("60/h") && rows[6] == "Rates: 1/${total.runs} matched runs")
    check(rows[7].contains("51K/h") && rows[8].contains("60K/h")) // Hourly values use this one run, never lifetime profit/XP.
    check(total.knownNet != rates.knownNet && total.cataXp > rates.cataXp && total.gross == 123_000.0)
    val duplicated = dungeonTrackerRateSample(archive.copy(runs = listOf(timed, timed)), emptyList(), "M7")
    check(duplicated.summary.runs == 1L && duplicated.summary.time == 60_000L && duplicated.loot.single().count == 1L)
    val completeHistorical = historical.copy(summary = historical.summary.copy(time = 1_200_000, timedRuns = 20))
    val imported = dungeonTrackerRateSample(AcquisitionArchive(), listOf(completeHistorical), "M7")
    check(imported.summary.runs == 20L && imported.summary.time == 1_200_000L && imported.loot.single().count == 20L)
    check(valued(imported).hourlyRate(imported.summary.cataXp) == 270_000.0) // Complete imported totals use their own complete timing.
    val incompleteImported = dungeonTrackerRateSample(AcquisitionArchive(), listOf(historical), "M7")
    check(incompleteImported.summary.runs == 0L && incompleteImported.summary.hourlyRate(100.0) == null)
    val noElapsed = dungeonTrackerRateSample(archive.copy(runs = listOf(untimed, timed.copy(duration = 0))), emptyList(), "M7")
    check(noElapsed.summary.time == 0L && noElapsed.summary.hourlyRate(1.0) == null)
    val unknownPrice = marketValuedTrackerSummary(sample.summary, marketValuedLoot(sample.loot) { null })
    val unknownCost = valued(dungeonTrackerRateSample(archive.copy(runs = listOf(timed.copy(rerollCost = null))), emptyList(), "M7"))
    for (unknown in listOf(unknownPrice, unknownCost)) {
        check(unknown.profitIncomplete && unknown.net == null)
        val uncertain = dungeonTrackerWidgetLines(null, total, unknown)
        check(uncertain[7].contains("—") && !uncertain[4].contains("—") && !uncertain[8].contains("—"))
    }
    val unknownXp = dungeonTrackerRateSample(archive.copy(runs = listOf(timed.copy(cataXp = null))), emptyList(), "M7")
    check(!unknownXp.summary.xpTimingComplete && dungeonTrackerWidgetLines(null, total, valued(unknownXp))[8].contains("—"))
    check(archive.runs.first() == timed && historical.summary.timedRuns == 19L) // This is a read-only projection.
}

private fun checkLifetimeDungeonSummary() {
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val other = context.copy(profileName = "Pear")
    val historical = LegacyFloorView("F7", TrackerSummary(runs = 20, time = 1200000, timedRuns = 20, cataXp = 10000.0, rerolls = 2),
        mapOf("BEDROCK" to listOf(TrackerLootRow("OLD_LOOT", 1, 1000.0, null, 1))),
        mapOf("BEDROCK" to 100.0), mapOf("BEDROCK" to 50.0), mapOf("BEDROCK" to 1))
    val first = DungeonRunRecord("first", "first-session", context, "F7", 1000, 61000, 60000, 1000.0,
        chests = listOf(ChestReceipt("first-chest", 61000, "BEDROCK", 100.0, listOf(RunLoot("KNOWN", 1, 1000.0)))),
        rerolls = 1, rerollCost = 50.0)
    val partial = first.copy(id = "second", session = "second-session", context = other, completedAt = 181000, duration = 120000,
        cataXp = 2000.0, chests = listOf(ChestReceipt("second-chest", 181000, "BEDROCK", 100.0,
            listOf(RunLoot("KNOWN", 1, 500.0), RunLoot("UNPRICED", 1)))), rerollCost = null)
    val archive = AcquisitionArchive(cutoverAt = 1000, legacyProfit = mapOf("F7" to 850.0),
        runs = listOf(first.copy(id = "pre-cutover", session = "historical-session", trackingVersion = 1), first, partial,
            first.copy(id = "other-floor", floor = "M7")))
    val combined = dungeonTrackerLifetimeSummary(archive, listOf(historical), "F7")
    check(combined.runs == 22L && combined.timedRuns == 22L && combined.time == 1380000L)
    check(combined.chests == 3 && combined.rerolls == 4 && combined.cataXp == 13000.0)
    check(combined.knownRerollCost == 100.0 && combined.knownNet == 2100.0)
    check(combined.profitIncomplete && combined.net == null) // Unknown prices never become valid zeros.
    check(combined.completeTiming && combined.hourlyRate(combined.runs.toDouble()) != null)
    check(trackerView(archive, listOf(historical), TrackerFilter(floor = "F7", scope = TrackerScope.ALL_LOCAL_PROFILES), null).summary == combined)
    val scoped = trackerView(archive, listOf(historical), TrackerFilter(floor = "F7"), context).summary
    check(scoped.runs == 1L && scoped.knownNet == 850.0 && !scoped.profitIncomplete)
    check(trackerView(archive, listOf(historical), TrackerFilter(floor = "F7", legacyTotals = true), context).summary == scoped)
    val session = trackerView(archive, listOf(historical), TrackerFilter(floor = "F7", scope = TrackerScope.ALL_LOCAL_PROFILES, session = "first-session"), null).summary
    check(session == scoped) // Session filters cannot include untimestamped legacy totals or another session.
    val date = java.time.Instant.ofEpochMilli(181000).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).toString()
    check(trackerView(archive, listOf(historical), TrackerFilter(floor = "F7", scope = TrackerScope.ALL_LOCAL_PROFILES, from = date), null).summary.runs == 0L)
    check(dungeonTrackerLifetimeSummary(archive, listOf(historical), mode = "M").let { it.runs == 1L && it.knownNet == 850.0 })
    val partialTime = dungeonTrackerLifetimeSummary(archive, listOf(historical.copy(summary = historical.summary.copy(timedRuns = 19))), "F7")
    check(!partialTime.completeTiming && partialTime.hourlyRate(partialTime.runs.toDouble()) == null)
    check(TrackerSummary().hourlyRate(1.0) == null && combined.hourlyRate(Double.NaN) == null && combined.hourlyRate(Double.MAX_VALUE) == null)
    val missingAggregate = dungeonTrackerLifetimeSummary(archive, emptyList(), "F7")
    check(missingAggregate.knownNet == 2100.0 && !missingAggregate.profitTimingComplete) // Cutover net survives a missing aggregate file.
    val uncompleted = first.copy(id = "uncompleted", startedAt = null, completedAt = null, duration = null)
    val unmatched = dungeonTrackerLifetimeSummary(AcquisitionArchive(runs = listOf(first, uncompleted)), emptyList())
    check(unmatched.runs == 1L && unmatched.completeTiming && !unmatched.profitTimingComplete && !unmatched.xpTimingComplete)
    val zero = first.copy(chests = listOf(first.chests.single().copy(cost = 0.0, loot = listOf(RunLoot("ZERO", 1, 0.0)))), rerolls = 0, rerollCost = 0.0)
    check(dungeonTrackerLifetimeSummary(AcquisitionArchive(runs = listOf(zero)), emptyList(), chest = "BEDROCK").let {
        it.knownNet == 0.0 && it.net == 0.0 && !it.profitIncomplete
    })
    val filteredChest = dungeonTrackerLifetimeSummary(archive, listOf(historical), "F7", chest = "WOOD")
    check(filteredChest.chests == 0 && filteredChest.gross == 0.0 && filteredChest.chestCost == 0.0 && filteredChest.profitIncomplete)
}
