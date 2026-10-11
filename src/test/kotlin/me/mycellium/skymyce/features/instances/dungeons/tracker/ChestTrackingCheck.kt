package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.api.*
import me.mycellium.skymyce.features.digest.DigestDungeonAcquisition
import me.mycellium.skymyce.features.digest.DetectedRng
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files

fun checkChestTracking() {
    for (chest in DungeonChest.entries) {
        check(DungeonChest.fromName(chest.label) == chest)
        check(DungeonChest.fromName("§a  ${chest.label}\u00a0Chest §r") == chest)
        check(dungeonRewardHeader("  ${chest.name} CHEST REWARDS") == chest)
    }
    listOf("My Bedrock Chest", "Bedrock Chest Shop", "Bedrock Reward Chest", "§zBedrock", "Wood\u0000").forEach {
        check(DungeonChest.fromName(it) == null)
    }
    check(dungeonChestControl("§a Open Reward Chest §r") == "Open Reward Chest")
    check(dungeonChestControl("Open Reward Chest again") == null)
    check(dungeonChestControl("§eReroll Chest") == "Reroll Chest")
    check(dungeonChestCost(listOf("§7Cost:", "§6100,000,000 Coins", "Click to open!")) == 100_000_000L)
    check(dungeonChestCost(listOf("§aFREE")) == 0L)
    check(dungeonChestCost(listOf("Cost: 0 Coins")) == 0L)
    for (lore in listOf(emptyList(), listOf("Unavailable"), listOf("-1 Coins"), listOf("1,00 Coins"),
        listOf("999999999999999999999 Coins"), listOf("100 Coins", "200 Coins"), listOf("FREE", "100 Coins"))) {
        check(dungeonChestCost(lore) == null)
    }
    check(dungeonRewardQuantity("§dWither Essence x1,024", 1) == 1024)
    check(dungeonRewardQuantity("Undead Essence", 64) == 1)
    check(dungeonRewardQuantity("Wither Essence x0", 1) == null)
    check(dungeonRewardQuantity("Wither Essence x99999999999999999", 1) == null)
    check(dungeonRewardQuantity("Wither Essence x1,00", 1) == null)
    check(dungeonRewardQuantity("Necron's Handle", 1) == 1)
    check(dungeonEssenceId("§dWither Essence x102") == "ESSENCE_WITHER")
    check(dungeonEssenceId("Unknown Essence x102") == null)
    // The actual current Prism log supplies this normalized header and these quantities.
    check(dungeonRewardHeader("§6  BEDROCK CHEST REWARDS") == DungeonChest.BEDROCK)
    check(dungeonRewardHeader("Party > BEDROCK CHEST REWARDS") == null)
    val currentDrop = "RNG DROP! [MVP+] TestPlayer just found a Necron's Handle!"
    val currentDetected = requireNotNull(DigestRngParser.detect(currentDrop, "TestPlayer", "DUNGEON_HUB", 1000))
    check(currentDetected.item == "NECRON_HANDLE" && currentDetected.activity == "dungeon")
    check(DigestRngParser.detect(currentDrop, "SomeoneElse", "DUNGEON_HUB", 1000) == null)
    check(DigestRngParser.detect(currentDrop, "TestPlayer", "HUB", 1000) == null)
    check(DigestRngParser.detect("Party > $currentDrop", "TestPlayer", "DUNGEON_HUB", 1000) == null)

    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val expected = mapOf("NECRON_HANDLE" to 1, "WITHER_CATALYST" to 2, "ESSENCE_WITHER" to 102)
    val before = mapOf("NECRON_HANDLE" to 1, "WITHER_CATALYST" to 3)
    val initial = PendingChestObservation("preview", context, "run", expected, before, 1000)
    val unrelated = initial.inventory(before + ("DIAMOND" to 64), "unrelated")
    check(unrelated == initial && !unrelated.confirmed)
    val partial = initial.inventory(before + ("NECRON_HANDLE" to 2), "first")
    check(!partial.confirmed && !partial.copy(rewardHeader = true).confirmed)
    val full = partial.inventory(mapOf("NECRON_HANDLE" to 2, "WITHER_CATALYST" to 5), "second")
    check(!full.confirmed && full.copy(rewardHeader = true).confirmed)
    check(full.inventory(mapOf("NECRON_HANDLE" to 2, "WITHER_CATALYST" to 5), "repeat") == full)
    check(full.received["NECRON_HANDLE"] == "first/NECRON_HANDLE") // Later arrivals preserve the Digest's original identity.
    val detector = DigestDungeonAcquisition().apply { seed(before) }
    check(detector.announce(DetectedRng("c".repeat(32), "NECRON_HANDLE", "dungeon", 1000), 1000) == null)
    val detected = detector.inventory(before + ("NECRON_HANDLE" to 2), 1000, "first").single()
    check(acquisitionId(detected.confirmation!!) == acquisitionId(full.received.getValue("NECRON_HANDLE")))
    check(initial.active(11000, context, "run") && !initial.active(11001, context, "run"))
    check(!initial.active(999, context, "run") && !initial.active(1001, context.copy(profileId = "d".repeat(32)), "run"))
    check(!initial.active(1001, context, "next-run") && !initial.active(1001, null, "run"))
    val closed = initial.copy(closed = 2000)
    check(closed.active(4000, context, "run") && !closed.active(4001, context, "run"))
    check(initial.copy(contents = mapOf("ESSENCE_WITHER" to 102), rewardHeader = true).confirmed)
    check(!initial.copy(contents = emptyMap(), rewardHeader = true).confirmed)
    // Cached physical Power Dragon shards resolve to attribute:l30 in both preview and inventory.
    val shard = canonicalItemId("attribute:l30")
    val shardChest = initial.copy(contents = mapOf(shard to 1, "WITHER_CATALYST" to 2, "ESSENCE_WITHER" to 102),
        baseline = mapOf(shard to 4, "WITHER_CATALYST" to 3), rewardHeader = true)
    check(shardChest.physical == mapOf(shard to 1, "WITHER_CATALYST" to 2))
    check(!shardChest.confirmed)
    check(!shardChest.inventory(mapOf(shard to 5, "WITHER_CATALYST" to 3), "shard-only").confirmed)
    // Header-first delivery confirms only after the deferred read sees all physical rewards.
    val directInventory = shardChest.inventory(mapOf(shard to 5, "WITHER_CATALYST" to 5), "direct-packet")
    check(directInventory.confirmed && directInventory.inventory(mapOf(shard to 5, "WITHER_CATALYST" to 5), "repeat") == directInventory)
    check(!directInventory.copy(rewardHeader = false).confirmed)
    check(rerollInventoryConfirmed(2, 1) && rerollInventoryConfirmed(1, 0))
    check(!rerollInventoryConfirmed(2, 2) && !rerollInventoryConfirmed(2, 0) && !rerollInventoryConfirmed(0, -1))

    checkAcquisitionCutover(context)
    println("Chest tracking checks passed: exact aliases, formatted controls/costs, bounded rewards, complete inventory receipts and safe legacy cutover")
}

private fun checkAcquisitionCutover(context: AcquisitionContext) {
    val oldReceipt = ChestReceipt("old-chest", 1000, "BEDROCK", 100.0, listOf(RunLoot("NECRON_HANDLE", 1, 1000.0)))
    val oldRun = DungeonRunRecord("old-run", "old-session", context, "M7", 1, 1000, 999, 500.0, chests = listOf(oldReceipt), rerollCost = 50.0)
    val oldEvent = AcquisitionEvent("e".repeat(32), "NECRON_HANDLE", 1000, "dungeon", context, "M7", "BEDROCK", run = oldRun.id)
    val old = AcquisitionArchive(version = 1, runs = listOf(oldRun), events = listOf(oldEvent), imports = setOf("tracker-v1"), pins = setOf("legacy|NECRON_HANDLE"))
    val legacy = JsonParser.parseString("""{"M7":{"totalRuns":55,"totalTimeMillis":99999999,"chests":{"BEDROCK":{"chestCost":100,"rerollCost":50,"trackedItems":{"item:necron_handle":{"count":1,"value":1000}},"valuables":{"item:necron_handle":[1000]},"unknownField":"keep"}}},"F7":{"chests":{"WOOD":{"chestCost":20,"trackedItems":{}}}}}""")
    val oldJson = Gson().toJsonTree(old).asJsonObject.apply {
        remove("cutoverAt"); remove("legacyProfit")
        getAsJsonArray("runs").forEach { it.asJsonObject.remove("trackingVersion") }
    }
    val parsed = parseAcquisitionArchive(oldJson)
    check(parsed.runs.single().trackingVersion == 1)
    val migrated = cutoverAcquisitions(parsed, legacy, 2000)
    check(migrated.version == 2 && migrated.cutoverAt == 2000L && migrated.legacyProfit == mapOf("M7" to 850.0, "F7" to -20.0))
    check(migrated.events.single().origin == "legacy-confirmed" && migrated.pins == old.pins && migrated.imports == old.imports)
    check(cutoverAcquisitions(migrated, legacy, 3000) == migrated)
    check(importLegacyAcquisitions(migrated, legacy, null).events == migrated.events) // A partially imported marker still permits profit preservation, without loot duplication.
    check(parseAcquisitionArchive(Gson().toJsonTree(migrated)) == migrated)
    val defaultView = trackerView(migrated, emptyList(), TrackerFilter(floor = "M7"), context)
    check(defaultView.summary.runs == 0L && defaultView.summary.time == 0L && defaultView.summary.cataXp == 0.0)
    check(defaultView.loot.isEmpty() && defaultView.events.isEmpty() && defaultView.lifetimeProfit == 850.0)
    check(TrackerFilter(legacyTotals = true).events(migrated.events, context, migrated.runs).size == 1)
    val newRun = oldRun.copy(id = "new-run", session = "new-session", trackingVersion = 2,
        chests = listOf(oldReceipt.copy(id = "new-chest", loot = listOf(RunLoot("NECRON_HANDLE", 1, 2000.0)))))
    val newer = migrated.copy(runs = migrated.runs + newRun)
    val newView = trackerView(newer, emptyList(), TrackerFilter(floor = "M7"), context)
    check(newView.summary.runs == 1L && newView.summary.cataXp == 500.0 && newView.loot.single().count == 1L)
    check(newView.inheritedProfit == 850.0 && newView.trackedLifetimeProfit == 1850.0 && newView.lifetimeProfit == 2700.0)
    val unpriced = newer.copy(runs = newer.runs.dropLast(1) + newRun.copy(chests = listOf(oldReceipt.copy(cost = null))))
    check(trackerView(unpriced, emptyList(), TrackerFilter(), context).let { it.summary.incompleteCosts && it.summary.net == null && it.lifetimeProfit == null && it.inheritedProfit == 830.0 })
    val missingPrice = newer.copy(runs = newer.runs.dropLast(1) + newRun.copy(chests = listOf(oldReceipt.copy(loot = listOf(RunLoot("NECRON_HANDLE", 1))))))
    check(trackerView(missingPrice, emptyList(), TrackerFilter(), context).summary.net == null)
    val missingRerollPrice = newer.copy(runs = newer.runs.dropLast(1) + newRun.copy(rerolls = 1, rerollCost = null))
    check(trackerView(missingRerollPrice, emptyList(), TrackerFilter(), context).summary.let { it.rerolls == 1 && it.rerollCost == null && it.net == null })
    val partial = importLegacyAcquisitions(AcquisitionArchive(version = 1, events = listOf(oldEvent)), legacy, null)
    val partialCutover = cutoverAcquisitions(partial, legacy, 2000)
    check(partialCutover.events.size == 2 && partialCutover.legacyProfit["M7"] == 850.0)
    check(cutoverAcquisitions(partialCutover, legacy, 3000) == partialCutover)
    val invalid = legacy.deepCopy().asJsonObject.apply { getAsJsonObject("M7").getAsJsonObject("chests").getAsJsonObject("BEDROCK").addProperty("chestCost", -1) }
    check(runCatching { cutoverAcquisitions(parsed, invalid, 2000) }.isFailure)

    val directory = Files.createTempDirectory("skymyce-cutover")
    try {
        val file = AtomicJsonFile(directory.resolve("acquisitions.json"))
        val original = Gson().toJson(oldJson)
        Files.writeString(file.path, original)
        Files.writeString(directory.resolve("acquisitions.json.before-migration"), "older-import-backup")
        file.preserveOriginal("before-v2-cutover")
        file.write(Gson().toJsonTree(migrated))
        check(Files.readString(directory.resolve("acquisitions.json.before-v2-cutover")) == original)
        check(Files.readString(directory.resolve("acquisitions.json.before-migration")) == "older-import-backup")
        Files.writeString(directory.resolve(".skymyce-interrupted.tmp"), "incomplete")
        val restarted = parseAcquisitionArchive(file.read())
        check(cutoverAcquisitions(restarted, legacy, 3000) == migrated)
        file.preserveOriginal("before-v2-cutover")
        check(Files.readString(directory.resolve("acquisitions.json.before-v2-cutover")) == original)
        val tooSmall = AtomicJsonFile(file.path, 8)
        check(runCatching { tooSmall.write(Gson().toJsonTree(migrated)) }.isFailure)
        check(parseAcquisitionArchive(file.read()) == migrated)
        Files.writeString(file.path, "corrupted")
        check(file.read() == null && file.damaged && Files.readString(file.path) == "corrupted")
        check(Files.readString(directory.resolve("acquisitions.json.before-v2-cutover")) == original)
    } finally {
        Files.list(directory).use { paths -> paths.forEach(Files::delete) }
        Files.delete(directory)
    }
}
