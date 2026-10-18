package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel
import me.mycellium.skymyce.features.instances.dungeons.splits.*
import me.mycellium.skymyce.features.instances.dungeons.tracker.experienceSince
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonRunRecord
import me.mycellium.skymyce.features.instances.dungeons.tracker.targetProgress

private fun checkSummaryLines() {
    var summary = WinSummary()
    listOf(
        "                        Total Damage as Mage: 2,152,345,234 (NEW RECORD!)",
        "                           Ally Healing: 22,600",
        "                           Enemies Killed: 90",
        "                           Deaths: 2",
        "                           Secrets Found: 6",
        "                       +533,715 Catacombs Experience",
        "                       +412,620 Mage Experience",
        "                       +10,000 Mage Experience (Team Bonus)",
        "                            +207 Bits",
    ).forEach { line -> summary = readWinSummaryLine(summary, line) ?: error("Not read: $line") }
    check(summary.damage == 2_152_345_234L && summary.damageClass == "Mage" && "damage" in summary.records)
    check(summary.healing == 22_600L && summary.kills == 90 && summary.deaths == 2 && summary.secrets == 6 && summary.bits == 207)
    check(summary.cataXp == 533_715L && summary.classXp == mapOf("Mage" to 422_620L)) { "Bonus experience adds up: ${summary.classXp}" }
    check(readWinSummaryLine(summary, "[BOSS] Necron: All this, for nothing...") == null)
    check(readWinSummaryLine(summary, "Party > Someone: Deaths: 5") == null) { "Player chat never matches" }

    val lines = winDetailLines(summary, ScoreInputs("M7", secretPercent = 78.4, crypts = 5, deaths = 1), "§7Splits §f4:33.2", 1_200L)
    check(lines.size == 4) { "$lines" }
    check(lines[0] == "§7Splits §f4:33.2 §8· §7lag §c+1.20") { lines[0] }
    check(lines[1] == "§7Secrets §b78% §8(6) §8· §7Crypts §b5 §8· §7Deaths §c2") { "Hypixel's death count wins: ${lines[1]}" }
    check(lines[2].startsWith("§7Damage §e2.15B §6★") && "§7Kills §b90" in lines[2]) { lines[2] }
    check(lines[3] == "§3+533.72K Cata XP §8· §3+422.62K Mage XP §8· §b+207 Bits") { lines[3] }
    check(winDetailLines(WinSummary(), null, null, null).isEmpty()) { "Nothing known, nothing shown" }
}

private fun checkSplitLine() {
    val f7 = splitDefinitions.getValue("F7")
    fun record(id: String, at: Long, section: Long) = SplitRunRecord(id, "F7", f7.version, at, true,
        f7.chain.mapIndexed { index, milestone -> milestone to index * section }.toMap(), SplitOutcome.COMPLETED)
    val history = listOf(record("a", 1, 20_000L), record("b", 2, 24_000L))
    val stats = floorSplitStats(history, f7, SplitWindow.ALL)
    val faster = record("now", 3, 19_000L)
    val slower = record("now", 3, 22_000L)
    check(winSplitLine(faster, stats, SplitClock.REAL, SplitComparison.BEST)!!.contains("§6new PB -")) { winSplitLine(faster, stats, SplitClock.REAL, SplitComparison.BEST)!! }
    check(winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.BEST)!!.contains("(PB §c+")) { winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.BEST)!! }
    check(winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.AVERAGE)!!.endsWith("(avg §a-0.00§7)") ||
        winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.AVERAGE)!!.contains("(avg §a")) { winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.AVERAGE)!! }
    check(winSplitLine(slower, stats, SplitClock.REAL, SplitComparison.WORST)!!.contains("(worst §a-")) { "Faster than the worst run" }
    check(winSplitLine(faster, floorSplitStats(emptyList(), f7, SplitWindow.ALL), SplitClock.REAL, SplitComparison.BEST)!!.endsWith("§6first timed clear"))
    check(winSplitLine(faster, stats, SplitClock.SERVER, SplitComparison.BEST) == null) { "No server time recorded, nothing to compare" }
    check(winSplitLine(faster.copy(outcome = SplitOutcome.FAILED), stats, SplitClock.REAL, SplitComparison.BEST) == null)
}

private fun checkTargetLevel() {
    check(dungeonXpForLevel(0) == 0.0 && dungeonXpForLevel(1) == 50.0 && dungeonXpForLevel(50) == 569_809_640.0) { "${dungeonXpForLevel(50)}" }
    check(dungeonXpForLevel(52) == 569_809_640.0 + 400_000_000.0 && dungeonLevel(dungeonXpForLevel(52)) == 52)
    check(dungeonLevel(dungeonXpForLevel(42) - 1) == 41 && dungeonLevel(dungeonXpForLevel(42)) == 42)
    val progress = targetProgress(dungeonXpForLevel(45), 50, 500_000.0)
    check(progress.level == 45 && progress.remaining == dungeonXpForLevel(50) - dungeonXpForLevel(45) && progress.nextLevelProgress == 0.0)
    check(progress.runs == kotlin.math.ceil(progress.remaining / 500_000.0).toLong())
    val done = targetProgress(dungeonXpForLevel(50) + 1, 50, null)
    check(done.remaining == 0.0 && done.runs == 0L.takeIf { false } && done.progress == 1.0)
    check(targetProgress(100.0, 50, 0.0).runs == null) { "No experience per run, no run estimate" }
    val runs = listOf(DungeonRunRecord("old", "s", null, "M7", completedAt = 100, cataXp = 500_000.0),
        DungeonRunRecord("new", "s", null, "M7", completedAt = 300, cataXp = 520_000.0),
        DungeonRunRecord("none", "s", null, "M7", completedAt = 400))
    check(experienceSince(runs, 200, null) == 520_000.0) { "Only runs after the profile reading count" }
}

fun main() {
    checkSummaryLines(); checkSplitLine(); checkTargetLevel()
    println("Dungeon win summary checks passed: summary lines, splits comparison and target level")
}
