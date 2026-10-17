package me.mycellium.skymyce.features.instances.dungeons.splits

import kotlin.math.floor

/**
 * The inputs Hypixel shows during a run. Null means not yet read; the estimate then counts it as NoammAddons does
 * (nothing found, 36 rooms, no time spent), and [complete] says whether the estimate rests on what the run shows.
 */
data class ScoreInputs(
    val floor: String,
    val secretPercent: Double? = null,
    val clearedPercent: Int? = null,
    val completedRooms: Int? = null,
    val crypts: Int = 0,
    val deaths: Int = 0,
    /** Puzzles not yet completed (unopened, discovered or failed). */
    val openPuzzles: Int = 0,
    val elapsedSeconds: Int? = null,
    val bloodDone: Boolean = false,
    val inBoss: Boolean = false,
    val mimic: Boolean = false,
    val prince: Boolean = false,
    val paul: Boolean = false,
) {
    val complete get() = secretPercent != null && clearedPercent != null && completedRooms != null && elapsedSeconds != null
}

private val requiredSecrets = mapOf("F1" to 0.3, "F2" to 0.4, "F3" to 0.5, "F4" to 0.6, "F5" to 0.7, "F6" to 0.85, "F7" to 1.0)
private val timeLimits = mapOf("F1" to 600, "F2" to 600, "F3" to 600, "F4" to 720, "F5" to 600, "F6" to 720, "F7" to 840,
    "M1" to 480, "M2" to 480, "M3" to 480, "M4" to 480, "M5" to 480, "M6" to 600, "M7" to 840)

internal fun speedDeduction(percentOver: Double): Double {
    var over = percentOver
    var deduction = 0.0
    for ((cap, divisor) in listOf(20.0 to 2.0, 20.0 to 3.5, 10.0 to 4.0, 10.0 to 5.0)) {
        if (over <= 0) return deduction
        deduction += over.coerceAtMost(cap) / divisor
        over -= cap
    }
    return if (over > 0) deduction + over / 6.0 else deduction
}

/**
 * The run's score if the team finishes it now: NoammAddons `ScoreCalculation.kt` (CC0), the calculator its map shows.
 * Rooms still to be completed by blood and boss are counted, as Hypixel does at the end. Null only for unknown floors.
 */
fun estimateDungeonScore(input: ScoreInputs): Int? {
    val secrets = input.secretPercent ?: 0.0
    val cleared = input.clearedPercent ?: 0
    val completed = input.completedRooms ?: 0
    val elapsed = input.elapsedSeconds ?: 0
    val number = input.floor.drop(1).toIntOrNull() ?: return null
    val required = if (input.floor.startsWith("M")) 1.0 else requiredSecrets[input.floor] ?: return null
    val limit = timeLimits[input.floor] ?: return null
    val totalRooms = if (completed > 0 && cleared > 0) floor(completed / (cleared / 100.0) + 0.4) else 36.0
    val effective = completed + (if (input.bloodDone) 0 else 1) + (if (input.inBoss) 0 else 1)
    val secretScore = floor(secrets / required / 100.0 * 40.0).coerceIn(0.0, 40.0).toInt()
    val roomScore = (effective / totalRooms * 60.0).coerceIn(0.0, 60.0).toInt()
    val skillRooms = floor(effective / totalRooms * 80.0).coerceIn(0.0, 80.0).toInt()
    val deathPenalty = (input.deaths * 2 - 1).coerceAtLeast(0)
    val skill = (20 + skillRooms - input.openPuzzles * 10 - deathPenalty).coerceIn(20, 100)
    val bonus = input.crypts.coerceIn(0, 5) + (if (input.mimic && number > 5) 2 else 0) + (if (input.prince) 1 else 0) +
        (if (input.paul) 10 else 0)
    val speed = if (elapsed <= limit) 100 else (100 - speedDeduction((elapsed - limit) * 100.0 / limit)).toInt().coerceAtLeast(0)
    return secretScore + roomScore + skill + bonus + speed
}

private val secretPercentLine = Regex("^\\s*Secrets Found: ([\\d.]+)%$")
private val cryptsLine = Regex("^\\s*Crypts: (\\d+)$")
private val completedRoomsLine = Regex("^\\s*Completed Rooms: (\\d+)$")
private val deathsLine = Regex("^\\s*Team Deaths: (\\d+)$")
private val puzzlesHeader = Regex("^\\s*Puzzles: \\((\\d+)\\)$")
private val puzzleLine = Regex("^\\s*.+: \\[([✦✔✖])].*$")
private val clearedLine = Regex("^\\s*Cleared: (\\d+)%.*$")
private val elapsedLine = Regex("^\\s*Time Elapsed: (?:(\\d+)h )?(?:(\\d+)m )?(?:(\\d+)s)?$")

/** Reads the tab list (stripped lines in column order) into [input]; lines that are absent leave values unchanged. */
fun readScoreTabList(input: ScoreInputs, columns: List<List<String>>): ScoreInputs {
    var result = input
    for (column in columns) {
        var puzzleCount: Int? = null
        var puzzlesDone = 0
        var puzzleLines = 0
        for (line in column) {
            secretPercentLine.matchEntire(line)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }?.let { result = result.copy(secretPercent = it) }
            }
            cryptsLine.matchEntire(line)?.let { result = result.copy(crypts = it.groupValues[1].toInt().coerceIn(0, 1000)) }
            completedRoomsLine.matchEntire(line)?.let { result = result.copy(completedRooms = it.groupValues[1].toInt().coerceIn(0, 100)) }
            deathsLine.matchEntire(line)?.let { result = result.copy(deaths = it.groupValues[1].toInt().coerceIn(0, 1000)) }
            puzzlesHeader.matchEntire(line)?.let { puzzleCount = it.groupValues[1].toInt().coerceIn(0, 10); puzzlesDone = 0; puzzleLines = 0; continue }
            if (puzzleCount != null && puzzleLines < puzzleCount) puzzleLine.matchEntire(line)?.let {
                puzzleLines++
                if (it.groupValues[1] == "✔") puzzlesDone++
            }
        }
        puzzleCount?.let { result = result.copy(openPuzzles = (it - puzzlesDone).coerceAtLeast(0)) }
    }
    return result
}

/** Reads the sidebar; `Cleared` changing after the Watcher is done means the blood room now counts as completed. */
fun readScoreSidebar(input: ScoreInputs, lines: List<String>, watcherCleared: Boolean): ScoreInputs {
    var result = input
    for (line in lines) {
        clearedLine.matchEntire(line)?.let { match ->
            val cleared = match.groupValues[1].toInt().coerceIn(0, 100)
            if (watcherCleared && result.clearedPercent != null && cleared != result.clearedPercent) result = result.copy(bloodDone = true)
            result = result.copy(clearedPercent = cleared)
        }
        elapsedLine.matchEntire(line)?.takeIf { match -> match.groupValues.drop(1).any { it.isNotEmpty() } }?.let { match ->
            val (h, m, s) = match.destructured
            result = result.copy(elapsedSeconds = (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0))
        }
    }
    return result
}

/**
 * Mimic and Prince kills: Hypixel's own Prince line, and what teammates' mods announce in party chat, so a kill out of
 * sight still counts (NoammAddons `ScoreCalculation`). Matched case-insensitively anywhere in a chat line, as there.
 */
private val mimicMessages = setOf("mimic dead!", "mimic dead", "mimic killed!", "mimic killed", "\$skytils-dungeon-score-mimic\$",
    "child destroyed!", "mimic obliterated!", "mimic exorcised!", "mimic destroyed!", "mimic annhilated!", "breefing killed", "breefing dead")
private val princeMessages = setOf("prince dead", "prince dead!", "\$skytils-dungeon-score-prince\$", "prince killed", "prince slain",
    "prince killed!", "a prince falls. +1 bonus score")

internal fun announcesMimic(text: String) = text.lowercase().let { line -> mimicMessages.any { it in line } }
internal fun announcesPrince(text: String) = text.lowercase().let { line -> princeMessages.any { it in line } }
internal val mimicCharm = Regex("^CHARM You charmed a Mimic and captured (?:\\d+ shards from it|its shard)\\.$", RegexOption.IGNORE_CASE)
