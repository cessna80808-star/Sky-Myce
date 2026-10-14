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
    val archive = AcquisitionArchive(runs = listOf(first.copy(id = "duplicated-old", trackingVersion = 1), first, second),
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
        runs = listOf(first.copy(id = "pre-cutover", trackingVersion = 1), first, partial,
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
