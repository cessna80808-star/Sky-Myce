package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.utils.AtomicJsonFile
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import java.nio.file.Files
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

fun checkDungeonTiming() {
    listOf("I", "II", "III", "IV", "V", "VI", "VII").forEachIndexed { index, roman ->
        check(dungeonCompletionFloor("   The Catacombs - Floor $roman") == DungeonFloor.getByName("F${index + 1}"))
        check(dungeonCompletionFloor("   Master Mode Catacombs - Floor $roman   ") == DungeonFloor.getByName("M${index + 1}"))
        check(dungeonCompletionFloor("Master Mode The Catacombs - Floor $roman") == DungeonFloor.getByName("M${index + 1}"))
    }
    check(dungeonCompletionFloor("The Catacombs - Entrance") == DungeonFloor.E)
    listOf("Master Mode Catacombs - Entrance", "The Catacombs - Floor VIII", "The Catacombs - Floor IIII",
        "Player: The Catacombs - Floor VII", "The Catacombs - Floor VII extra", "The Catacombs - Floor VII\n").forEach {
        check(dungeonCompletionFloor(it) == null)
    }

    val recoveredContext = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val recovered = DungeonRunRecord("late-start", "session", recoveredContext, "M7")
    val recoveredArchive = AcquisitionArchive(runs = listOf(recovered.copy(completedAt = 1_700_000_000_000)))
    val recoveredSummary = trackerView(recoveredArchive, emptyList(), TrackerFilter(floor = "M7"), recoveredContext).summary
    check(recovered.startedAt == null && recovered.duration == null)
    check(recoveredSummary.runs == 1L && recoveredSummary.time == 0L && recoveredSummary.timedRuns == 0L && recoveredSummary.average == null)

    val clock = TestTimeSource()
    clock += 20720.days
    check(dungeonRunDuration(null) == null) // Missing start must never become time since 1970.
    val started = clock.markNow()
    check(dungeonRunDuration(started) == null)
    clock += 5.minutes
    check(dungeonRunDuration(started) == 300000L)
    clock += 2.days
    check(dungeonRunDuration(started) == null)

    val directory = Files.createTempDirectory("skymyce-dungeon-timing")
    try {
        val file = AtomicJsonFile(directory.resolve("dungeon_tracker.json"))
        val original = """{
          "M7": {"totalRuns":31,"totalTimeMillis":1790290359488,"totalXp":12345,
            "classXp":{"HEALER":678},"customField":"keep",
            "chests":{"BEDROCK":{"chestCount":4,"chestCost":100,"rerollCount":2,"rerollCost":50,
              "valuables":{"item:necron_handle":[1700000000000,1700000000000]},
              "trackedItems":{"item:necron_handle":{"count":2,"value":2000}}}}},
          "F7": {"totalRuns":2,"totalTimeMillis":600000,"totalXp":10,"classXp":{},"chests":{}}
        }"""
        Files.writeString(file.path, original)
        val totals = readDungeonTotals(file)
        val repaired = totals.getValue(DungeonFloor.M7)
        check(repaired.totalTimeMillis == 0L && repaired.timedRunCount == 0 && repaired.totalRuns == 31)
        check(repaired.incompleteTime && repaired.averageRunMillis == null && repaired.hourlyRate(100.0) == null)
        val chest = repaired.chests.getValue(DungeonChest.BEDROCK)
        check(repaired.totalXp == 12345.0 && repaired.classXp.values.single() == 678.0)
        check(chest.chestCount == 4 && chest.chestCost == 100.0 && chest.rerollCount == 2 && chest.rerollCost == 50.0)
        check(chest.trackedItems.values.single().count == 2 && chest.valuables.values.single().size == 2)
        val backup = directory.resolve("dungeon_tracker.json.before-time-repair")
        check(Files.readString(backup) == original)
        val expected = JsonParser.parseString(original).asJsonObject.apply {
            getAsJsonObject("M7").apply { addProperty("totalTimeMillis", 0); addProperty("timedRuns", 0) }
        }
        check(file.read() == expected) // No loot, XP, unknown fields, or other floors changed.
        val once = Files.readString(file.path)
        readDungeonTotals(file)
        check(Files.readString(file.path) == once && Files.readString(backup) == original)
        check(trackerView(AcquisitionArchive(), legacyTrackerView(totals), TrackerFilter(floor = "M7", legacyTotals = true), null).summary.let {
            it.runs == 31L && it.time == 0L && it.timedRuns == 0L && it.average == null
        })
        repaired.recordRun(300000)
        check(repaired.totalRuns == 32 && repaired.timedRunCount == 1 && repaired.averageRunMillis == 300000.0)
        check(repaired.hourlyRate(repaired.netProfit) == null) // All-time profit cannot use partial time as its denominator.
        file.write(dungeonTotalsCodec.encodeStart(JsonOps.INSTANCE, totals).result().orElseThrow())
        check(readDungeonTotals(file).getValue(DungeonFloor.M7).timedRunCount == 1)
        check(Files.readString(backup) == original)
        val healthy = totals.getValue(DungeonFloor.F7)
        check(healthy.timedRunCount == 2 && !healthy.incompleteTime && healthy.averageRunMillis == 300000.0)
        check(healthy.hourlyRate(100.0) == 600.0)
        val newData = FloorTracker()
        listOf(null, 0L, 1790290359488L).forEach(newData::recordRun)
        check(newData.totalRuns == 3 && newData.totalTimeMillis == 0L && newData.timedRunCount == 0)
        newData.recordRun(60000)
        check(newData.timedRunCount == 1 && newData.averageRunMillis == 60000.0)
        check(FloorTracker().hourlyRate(1.0) == null)
        Files.writeString(file.path, "corrupted")
        check(runCatching { readDungeonTotals(file) }.isFailure && Files.readString(file.path) == "corrupted")
        check(Files.readString(backup) == original)
    } finally {
        Files.list(directory).use { paths -> paths.forEach(Files::delete) }
        Files.delete(directory)
    }
    println("Dungeon timing checks passed: normal/master result headers, missing starts, monotonic duration, backed-up idempotent repair, preserved loot and honest partial rates")
}
