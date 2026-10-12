package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import me.mycellium.skymyce.features.digest.*
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.consistentItemMetadata
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.validObservedItemMetadata
import java.time.Instant
import java.time.LocalDate

fun checkRngJournal() {
    checkObservedAcquisitionMetadata()
    checkObservedJournalComparisons()
    val monday = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    val now = monday + 3600000
    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val other = context.copy(account = "c".repeat(32))
    val message = "RNG DROP! [MVP+] Player_1 just found a Necron's Handle!"
    val drop = requireNotNull(DigestRngParser.detect(message, "Player_1", "THE_CATACOMBS", monday))
    check(DigestRngParser.detect("§6RNG DROP!\u00a0[MVP+]\u202fPlayer_1 just found a Necron’s Handle!§r", "Player_1", "THE_CATACOMBS", monday)?.item == drop.item)
    check(DigestRngParser.detect("Party > $message", "Player_1", "THE_CATACOMBS", monday) == null)
    check(DigestRngParser.detect("$message\n$message", "Player_1", "THE_CATACOMBS", monday) == null)
    check(DigestRngParser.detect(message, "SomeoneElse", "THE_CATACOMBS", monday) == null)
    check(DigestRngParser.detect(message, "Player_1", "HUB", monday) == null)
    val detector = DigestDungeonAcquisition().apply { seed(mapOf("NECRON_HANDLE" to 1)) }
    check(detector.announce(drop, monday) == null)
    check(detector.inventory(mapOf("NECRON_HANDLE" to 1), monday + 50, "move").isEmpty())
    val observed = confirmedRng(detector.inventory(mapOf("NECRON_HANDLE" to 2), monday + 100, "physical-arrival").single())
    val preview = PendingChestObservation("preview", context, "run", mapOf("NECRON_HANDLE" to 1, "WITHER_CATALYST" to 2, "ESSENCE_WITHER" to 102),
        mapOf("NECRON_HANDLE" to 1), 1000, rewardHeader = true)
    val partial = preview.inventory(mapOf("NECRON_HANDLE" to 2), "physical-arrival")
    check(!partial.confirmed)
    val complete = partial.inventory(mapOf("NECRON_HANDLE" to 2, "WITHER_CATALYST" to 2), "later-packet")
    check(complete.confirmed)
    // A complete receipt can arrive well after short chat dedup. Both observers retain the same permanent identity.
    val receiptDrop = confirmedRng(drop.copy(id = "e".repeat(32), occurredAt = monday + 5000, confirmation = complete.received.getValue(drop.item)))
    check(observed.id == receiptDrop.id)
    val first = AcquisitionEvent(observed.id, drop.item, monday, "dungeon", context.copy(profileId = null), player = "Player_1")
    val archive = mergeAcquisition(mergeAcquisition(AcquisitionArchive(), first), first.copy(context = context, floor = "F7", chest = "BEDROCK", run = "run", value = 1_000_000.0, valuationAt = monday + 5000))
    check(archive.events.size == 1 && archive.events.single().run == "run" && archive.events.single().valuationAt == monday + 5000)
    val olderEstimate = mergeAcquisition(AcquisitionArchive(events = listOf(first.copy(value = 1.0))), first.copy(value = 2.0, valuationAt = monday))
    check(olderEstimate.events.single().value == 1.0 && olderEstimate.events.single().valuationAt == null) { "A new timestamp was invented for an old unknown-age estimate" }
    check(runCatching { mergeAcquisition(archive, first.copy(context = other)) }.isFailure)
    val events = archive.events + listOf(
        first.copy(id = "f".repeat(32), occurredAt = monday - 1),
        first.copy(id = "1".repeat(32), occurredAt = monday, item = "WARDEN_HEART", activity = "slayer", quantity = 2),
        first.copy(id = "2".repeat(32), context = other),
        first.copy(id = "3".repeat(32), origin = "legacy-tracker", context = null))
    val run = DungeonRunRecord("run", "session", context, "F7", completedAt = monday, chests = listOf(
        ChestReceipt("receipt", monday, "BEDROCK", 100.0, listOf(RunLoot("NECRON_HANDLE", 1, 1_000_000.0)))))
    val saved = archive.copy(events = events, runs = listOf(run), pins = setOf("${context.key}|NECRON_HANDLE"), legacyProfit = mapOf("F7" to 123.0))
    val restored = parseAcquisitionArchive(Gson().toJsonTree(saved))
    check(restored == saved && restored.legacyProfit["F7"] == 123.0)
    val week = rngJournal(restored, JournalFilter(period = JournalPeriod.WEEK), context, now)
    check(week.events.size == 2 && week.counts.values.sum() == 3L && week.observedRuns == 1)
    check(week.bars.single().date == LocalDate.of(2026, 10, 12) && week.bars.single().count == 3L)
    check(week.knownValue == 1_000_000.0 && week.unpriced == 1 && week.runCounts["run"] == 1L)
    check(rngJournal(restored, JournalFilter(period = JournalPeriod.WEEK, activity = "slayer"), context, now).counts.values.sum() == 2L)
    check(rngJournal(restored, JournalFilter(period = JournalPeriod.WEEK, floor = "F7"), context, now).events.size == 1)
    check(rngJournal(restored, JournalFilter(query = "handle"), context, now).events.size == 2)
    check(rngJournal(restored, JournalFilter(scope = TrackerScope.ALL_LOCAL_PROFILES, includeLegacy = true), null, now).events.size == 5)
    check(rngJournal(restored, JournalFilter(), null, now).events.isEmpty())
    val custom = JournalFilter(period = JournalPeriod.CUSTOM, from = "2026-10-12", until = "2026-10-12")
    check(journalBounds(custom, now) == (monday to monday + 86400000))
    check(journalBounds(JournalFilter(period = JournalPeriod.MONTH), now).first == Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())
    check(parseJournalFilter(Gson().toJsonTree(custom)) == custom)
    check(runCatching { parseJournalFilter(Gson().toJsonTree(custom.copy(from = "2026-11-01"))) }.isFailure)
    check(recentPersonalEvents(restored, context, 1).single().origin == "confirmed")
    check(recentPersonalEvents(restored, null, 3).isEmpty())
    val unordered = restored.copy(events = (1..80).sortedBy { it % 7 }.map { first.copy(id = "%032x".format(it), occurredAt = monday + it) })
    check(recentPersonalEvents(unordered, context, 3).map { it.occurredAt } == listOf(monday + 80, monday + 79, monday + 78))
    check(recentPersonalEvents(unordered, context, 0).isEmpty())
    check(recentRngLines(emptyList()) == null)
    check(recentRngLines(listOf(first.copy(origin = "legacy-confirmed"))) == null)
    val widgetLines = checkNotNull(recentRngLines(week.events))
    check(widgetLines.any { "Warden Heart ×2" in it } && widgetLines.any { "10-12 00:00" in it })
    check(widgetLines.last().contains("UTC") && widgetLines.none { "probability" in it })
    val history = DigestRngHistory()
    check(history.add(DigestRngEntry(observed.id, drop.item, "dungeon", monday, "Player_1"), 100, now))
    check(!history.add(DigestRngEntry(receiptDrop.id, drop.item, "dungeon", monday + 5000, "Player_1"), 100, now))
    check(trackerView(restored, emptyList(), TrackerFilter(), context).summary.net == 999900.0)
    println("RNG journal checks passed: confirmed receipt identity, observed item metadata, linked run comparisons, UTC periods, offline persistence, observed counts and honest valuation coverage")
}

private fun checkObservedAcquisitionMetadata() {
    val metadata = checkNotNull(observedItemMetadata("LEGENDARY", "  Ancient Necron's Handle  "))
    check(metadata == ObservedItemMetadata("LEGENDARY", "Ancient Necron's Handle"))
    check(observedItemMetadata("UNKNOWN_CATALOG_TIER", null) == null)
    check(observedItemMetadata(null, " ") == null)
    for (name in listOf("x".repeat(121), "bad\nname", "§6Styled", "bad\u0000name")) {
        check(!validObservedItemMetadata(null, name))
        check(observedItemMetadata("MYTHIC", name) == ObservedItemMetadata("MYTHIC"))
    }
    check(!validObservedItemMetadata("MYTHIC", " untrimmed "))
    for (name in listOf("hidden\u200Bname", "bidi\u202Ename", "join\u200Dname", "bad\uD83Dname", "bad\uDE00name",
        "line\u2028name", "paragraph\u2029name", "\nEdge control", "Edge control\u0000")) {
        check(!validObservedItemMetadata(null, name))
        check(observedItemMetadata("MYTHIC", name) == ObservedItemMetadata("MYTHIC")) // Unsafe optional names never erase the observed tier.
    }
    val supplementaryName = "Necron's Handle \uD83D\uDE00"
    check(validObservedItemMetadata("LEGENDARY", supplementaryName))
    check(observedItemMetadata("LEGENDARY", supplementaryName)?.displayName == supplementaryName)
    check(validObservedItemMetadata(null, "\uD83D\uDE00".repeat(60)))
    check(!validObservedItemMetadata(null, "\uD83D\uDE00".repeat(61))) // The shared length bound counts UTF-16 units.
    check(consistentItemMetadata(emptyList()) == null)
    check(consistentItemMetadata(listOf(metadata, metadata)) == metadata)
    check(consistentItemMetadata(listOf(metadata, metadata.copy(rarity = "MYTHIC"))) == metadata.copy(rarity = null))
    check(consistentItemMetadata(listOf(metadata, metadata.copy(displayName = "Another variant"))) == metadata.copy(displayName = null))
    check(consistentItemMetadata(listOf(metadata, null)) == null) // Unknown held variants cannot inherit another stack's fields.

    val dungeon = DetectedRng("a".repeat(32), "NECRON_HANDLE", "dungeon", 1000)
    val detector = DigestDungeonAcquisition().apply { seed(mapOf(dungeon.item to 1)) }
    check(detector.announce(dungeon, 1000) == null)
    check(detector.inventory(mapOf(dungeon.item to 1), 1050, "held-item", mapOf(dungeon.item to metadata)).isEmpty())
    val observed = detector.inventory(mapOf(dungeon.item to 2), 1100, "new-item", mapOf(dungeon.item to metadata)).single()
    check(observed.rarity == metadata.rarity && observed.displayName == metadata.displayName && observed.confirmation == "new-item/${dungeon.item}")
    check(detector.inventory(mapOf(dungeon.item to 2), 1200, "duplicate", mapOf(dungeon.item to metadata)).isEmpty())
    val beforeChat = DigestDungeonAcquisition().apply { seed(emptyMap()) }
    check(beforeChat.inventory(mapOf(dungeon.item to 1), 1000, "early-packet", mapOf(dungeon.item to metadata)).isEmpty())
    check(beforeChat.announce(dungeon.copy(occurredAt = 1100), 1100)?.rarity == "LEGENDARY")
    beforeChat.clear()
    check(beforeChat.announce(dungeon, 1200) == null)

    val slayer = DetectedRng("b".repeat(32), "WARDEN_HEART", "slayer", 2000)
    val slayerMetadata = ObservedItemMetadata("MYTHIC", "Warden Heart")
    val slayerDetector = DigestDungeonAcquisition().apply { seed(mapOf(slayer.item to 1)) }
    check(slayerDetector.annotateSlayer(slayer, 2000) == slayer)
    check(slayerDetector.annotateSlayer(slayer.copy(id = "c".repeat(32), occurredAt = 2050), 2050).rarity == null)
    check(slayerDetector.inventory(mapOf(slayer.item to 1), 2060, metadata = mapOf(slayer.item to slayerMetadata)).isEmpty())
    val late = slayerDetector.inventory(mapOf(slayer.item to 2), 2100, metadata = mapOf(slayer.item to slayerMetadata)).single()
    check(late.id == slayer.id && late.rarity == "MYTHIC") // A duplicate announcement cannot redirect the enrichment ID.
    check(slayerDetector.inventory(mapOf(slayer.item to 2), 2200, metadata = mapOf(slayer.item to slayerMetadata)).isEmpty())
    val expired = DigestDungeonAcquisition().apply { seed(emptyMap()); annotateSlayer(slayer, 2000) }
    check(expired.inventory(mapOf(slayer.item to 1), 17001, metadata = mapOf(slayer.item to slayerMetadata)).isEmpty())
    val earlySlayer = DigestDungeonAcquisition().apply { seed(emptyMap()) }
    check(earlySlayer.inventory(mapOf(slayer.item to 1), 1900, metadata = mapOf(slayer.item to slayerMetadata)).isEmpty())
    check(earlySlayer.annotateSlayer(slayer, 2000).displayName == "Warden Heart")

    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val first = AcquisitionEvent(observed.id, dungeon.item, 1000, "dungeon", context, quantity = 2, value = 100.0)
    val archive = mergeAcquisition(AcquisitionArchive(), first)
    val enriched = enrichAcquisitionMetadata(archive, first.id, first.item, context, metadata)
    check(enriched.events.single() == first.copy(rarity = metadata.rarity, displayName = metadata.displayName))
    check(enrichAcquisitionMetadata(enriched, first.id, first.item, context, metadata.copy(rarity = "MYTHIC")) == enriched)
    check(enrichAcquisitionMetadata(enriched, "d".repeat(32), first.item, context, metadata) == enriched)
    check(enrichAcquisitionMetadata(enriched, first.id, first.item, context.copy(account = "e".repeat(32)), metadata) == enriched)
    check(enrichAcquisitionMetadata(enriched, first.id, slayer.item, context, metadata) == enriched)
    val legacy = archive.copy(events = listOf(first.copy(origin = "legacy-digest")))
    check(enrichAcquisitionMetadata(legacy, first.id, first.item, context, metadata) == legacy)
    val receipt = ChestReceipt("receipt", 1000, "BEDROCK", null, listOf(RunLoot(first.item, 2, null, metadata.rarity, metadata.displayName)))
    val saved = enriched.copy(runs = listOf(DungeonRunRecord("run", "session", context, "F7", chests = listOf(receipt))))
    check(parseAcquisitionArchive(Gson().toJsonTree(saved)) == saved)
    val oldJson = Gson().toJsonTree(saved).asJsonObject.apply {
        getAsJsonArray("events").forEach { it.asJsonObject.remove("rarity"); it.asJsonObject.remove("displayName") }
        getAsJsonArray("runs")[0].asJsonObject.getAsJsonArray("chests")[0].asJsonObject.getAsJsonArray("loot")[0].asJsonObject.apply {
            remove("rarity"); remove("displayName")
        }
    }
    val old = parseAcquisitionArchive(oldJson)
    check(old.events.single().rarity == null && old.events.single().displayName == null)
    check(old.runs.single().chests.single().loot.single().rarity == null && old.runs.single().chests.single().loot.single().value == null)
    check(runCatching { parseAcquisitionArchive(Gson().toJsonTree(saved.copy(events = listOf(first.copy(rarity = "MADE_UP"))))) }.isFailure)
    check(runCatching { parseAcquisitionArchive(Gson().toJsonTree(saved.copy(runs = listOf(saved.runs.single().copy(chests = listOf(
        receipt.copy(loot = listOf(receipt.loot.single().copy(displayName = "x".repeat(121)))))))))) }.isFailure)
}

private fun checkObservedJournalComparisons() {
    val monday = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    val now = monday + 3600000
    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val first = AcquisitionEvent("a".repeat(32), "NECRON_HANDLE", monday, "dungeon", context, "F7", quantity = 2,
        value = 100.0, run = "priced", rarity = "LEGENDARY", displayName = "Ancient Necron's Handle")
    val older = first.copy(id = "b".repeat(32), occurredAt = monday - 1, quantity = 1, value = null,
        run = "unpriced", rarity = null, displayName = null)
    val slayer = first.copy(id = "c".repeat(32), item = "WARDEN_HEART", activity = "slayer", quantity = 1,
        floor = null, value = null, run = null, rarity = "MYTHIC", displayName = "Warden Heart")
    val future = first.copy(id = "d".repeat(32), occurredAt = now + 1, quantity = 9, run = "future")
    val imported = first.copy(id = "e".repeat(32), origin = "legacy-digest", quantity = 4, rarity = "RARE")
    val unrelated = first.copy(id = "f".repeat(32), context = context.copy(account = "c".repeat(32)), quantity = 10)
    val chest = ChestReceipt("receipt", monday, "BEDROCK", 10.0, listOf(RunLoot(first.item, 2, 100.0, first.rarity, first.displayName)))
    val run = DungeonRunRecord("priced", "session", context, "F7", monday - 60000, monday, 60000,
        chests = listOf(chest), rerolls = 1, rerollCost = 5.0)
    val unpriced = run.copy(id = "unpriced", completedAt = monday - 1, duration = null,
        chests = listOf(chest.copy(loot = listOf(RunLoot(first.item, 1)))))
    val futureRun = run.copy(id = "future", completedAt = now + 1)
    val archive = AcquisitionArchive(events = listOf(first, older, slayer, future, imported, unrelated), runs = listOf(run, unpriced, futureRun))
    val view = rngJournal(archive, JournalFilter(), context, now)
    check(view.counts.values.sum() == 4L && view.rarityCounts == mapOf("LEGENDARY" to 2L, "UNKNOWN" to 1L, "MYTHIC" to 1L))
    check(view.events.none { it.id == future.id || it.id == imported.id || it.id == unrelated.id } && view.observedRuns == 2)
    check(rngJournal(archive, JournalFilter(period = JournalPeriod.WEEK), context, now).counts.values.sum() == 3L)
    check(rngJournal(archive, JournalFilter(period = JournalPeriod.MONTH), context, now).counts.values.sum() == 4L)
    check(rngJournal(archive, JournalFilter(period = JournalPeriod.CUSTOM, from = "2026-10-12", until = "2026-10-13"), context, now).counts.values.sum() == 3L)
    check(rngJournal(archive, JournalFilter(query = "ancient"), context, now).events == listOf(first))
    val withLegacy = rngJournal(archive, JournalFilter(includeLegacy = true), context, now)
    check(withLegacy.legacyReports == 1 && withLegacy.rarityCounts == view.rarityCounts)
    check(archive.events.contains(future) && archive.runs.contains(futureRun)) // Query suppression preserves the permanent archive.
    val priced = view.runComparisons.first { it.run == "priced" }
    check(priced.floor == "F7" && priced.completedAt == monday && priced.duration == 60000L && priced.dropCount == 2L)
    check(priced.knownValue == 100.0 && priced.unpricedReports == 0 && priced.estimatedProfit == 85.0)
    check(view.runComparisons.first { it.run == "unpriced" }.let {
        it.duration == null && it.knownValue == null && it.unpricedReports == 1 && it.estimatedProfit == null
    })
    for (changed in listOf(run.copy(context = context.copy(account = "d".repeat(32))), run.copy(trackingVersion = 1), run.copy(completedAt = now + 1))) {
        val comparison = rngJournal(archive.copy(runs = listOf(changed)), JournalFilter(), context, now).runComparisons.first { it.run == "priced" }
        check(comparison.estimatedProfit == null && comparison.duration == null && comparison.completedAt == null)
    }
    for (changed in listOf(run.copy(rerollCost = null), run.copy(chests = listOf(chest.copy(cost = null))),
        run.copy(chests = listOf(chest.copy(time = now + 1))), run.copy(chests = emptyList()))) {
        check(rngJournal(archive.copy(runs = listOf(changed)), JournalFilter(), context, now).runComparisons.first { it.run == "priced" }.estimatedProfit == null)
    }
    check(rngJournal(archive.copy(runs = listOf(run.copy(startedAt = now + 1))), JournalFilter(), context, now).observedRuns == 0)
    val linked = archive.copy(events = (1..25).map { first.copy(id = "%032x".format(it), run = "run-$it", occurredAt = monday + it) }, runs = emptyList())
    val bounded = rngJournal(linked, JournalFilter(), context, now).runComparisons
    check(bounded.size == 20 && bounded.first().run == "run-25" && bounded.last().run == "run-6")
    check(bounded.all { it.estimatedProfit == null && it.completedAt == null && it.duration == null })
}
