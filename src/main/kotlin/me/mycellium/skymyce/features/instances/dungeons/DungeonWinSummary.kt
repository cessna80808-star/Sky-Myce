package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.features.instances.dungeons.splits.FloorSplitStats
import me.mycellium.skymyce.features.instances.dungeons.splits.ScoreInputs
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitComparison
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitRunRecord
import me.mycellium.skymyce.features.instances.dungeons.splits.formatSplitDelta
import me.mycellium.skymyce.features.instances.dungeons.splits.formatSplitTime
import me.mycellium.skymyce.utils.NumberUtils

/** What Hypixel's end-of-run summary reports after the score line; null until its line arrives. */
data class WinSummary(
    val damage: Long? = null,
    val damageClass: String? = null,
    val healing: Long? = null,
    val kills: Int? = null,
    val deaths: Int? = null,
    val secrets: Int? = null,
    val cataXp: Long? = null,
    val classXp: Map<String, Long> = emptyMap(),
    val bits: Int? = null,
    val records: Set<String> = emptySet(),
)

private fun number(text: String) = text.replace(",", "").toLongOrNull()
private val damageLine = Regex("^\\s*Total Damage as (\\w+): ([\\d,]+)(?: \\(NEW RECORD!\\))?\\s*$")
private val healingLine = Regex("^\\s*Ally Healing: ([\\d,]+)(?: \\(NEW RECORD!\\))?\\s*$")
private val killsLine = Regex("^\\s*Enemies Killed: ([\\d,]+)(?: \\(NEW RECORD!\\))?\\s*$")
private val deathsLine = Regex("^\\s*Deaths: (\\d+)\\s*$")
private val secretsLine = Regex("^\\s*Secrets Found: (\\d+)\\s*$")
private val xpLine = Regex("^\\s*\\+([\\d,]+) (\\w+) Experience(?: \\((?:Team|Daily) Bonus\\))?\\s*$")
private val bitsLine = Regex("^\\s*\\+([\\d,]+) Bits\\s*$")

/** [summary] updated by one stripped chat line, or null when the line is not part of the summary. */
fun readWinSummaryLine(summary: WinSummary, text: String): WinSummary? {
    if (text.length > 120) return null
    val record = text.contains("(NEW RECORD!)")
    fun recorded(name: String) = if (record) summary.records + name else summary.records
    damageLine.matchEntire(text)?.let { return summary.copy(damage = number(it.groupValues[2]), damageClass = it.groupValues[1], records = recorded("damage")) }
    healingLine.matchEntire(text)?.let { return summary.copy(healing = number(it.groupValues[1]), records = recorded("healing")) }
    killsLine.matchEntire(text)?.let { return summary.copy(kills = number(it.groupValues[1])?.toInt(), records = recorded("kills")) }
    deathsLine.matchEntire(text)?.let { return summary.copy(deaths = it.groupValues[1].toInt()) }
    secretsLine.matchEntire(text)?.let { return summary.copy(secrets = it.groupValues[1].toInt()) }
    bitsLine.matchEntire(text)?.let { return summary.copy(bits = number(it.groupValues[1])?.toInt()) }
    xpLine.matchEntire(text)?.let {
        val amount = number(it.groupValues[1]) ?: return null
        val type = it.groupValues[2]
        // A bonus line adds to the run's own experience.
        return if (type == "Catacombs") summary.copy(cataXp = (summary.cataXp ?: 0) + amount)
            else summary.copy(classXp = summary.classXp + (type to (summary.classXp[type] ?: 0) + amount))
    }
    return null
}

/**
 * The run's time on [clock] against the chosen comparison, from saved splits that exclude this run. Null when the run
 * was not fully timed from its start.
 */
fun winSplitLine(record: SplitRunRecord, stats: FloorSplitStats, clock: SplitClock, comparison: SplitComparison?): String? {
    val total = record.total(clock) ?: return null
    val text = "§7Splits §f${formatSplitTime(total)}"
    val reference = comparison?.let(stats::totalReference)
    return when {
        comparison == null -> text
        comparison == SplitComparison.BEST && (reference == null || total < reference) ->
            "$text §6${if (reference == null) "first timed clear" else "new PB ${formatSplitDelta(total - reference)}"}"
        reference == null -> text
        else -> {
            val delta = total - reference
            val label = when (comparison) { SplitComparison.BEST -> "PB"; SplitComparison.AVERAGE -> "avg"; SplitComparison.WORST -> "worst" }
            "$text §7($label ${if (delta <= 0) "§a" else "§c"}${formatSplitDelta(delta)}§7)"
        }
    }
}

/** Detail lines under the score: splits, the clear, combat and rewards. Missing values are left out, never shown as zero. */
fun winDetailLines(summary: WinSummary, score: ScoreInputs?, splitLine: String?, lagLost: Long?): List<String> {
    fun compact(value: Long) = NumberUtils.condense(value)
    fun star(name: String) = if (name in summary.records) " §6★" else ""
    val clear = listOfNotNull(
        score?.secretPercent?.let { "§7Secrets §b${"%.0f".format(it)}%" + (summary.secrets?.let { found -> " §8($found)" } ?: "") }
            ?: summary.secrets?.let { "§7Secrets §b$it" },
        score?.crypts?.takeIf { it > 0 }?.let { "§7Crypts §b${it}" },
        (summary.deaths ?: score?.deaths)?.let { "§7Deaths ${if (it == 0) "§a" else "§c"}$it" },
    )
    val combat = listOfNotNull(
        summary.damage?.let { "§7Damage §e${compact(it)}${star("damage")}" },
        summary.kills?.let { "§7Kills §b$it${star("kills")}" },
        summary.healing?.takeIf { it > 0 }?.let { "§7Healing §a${compact(it)}${star("healing")}" },
    )
    val rewards = listOfNotNull(summary.cataXp?.let { "§3+${compact(it)} Cata XP" }) +
        summary.classXp.map { (name, xp) -> "§3+${compact(xp)} $name XP" } + listOfNotNull(summary.bits?.let { "§b+$it Bits" })
    return listOfNotNull(
        listOfNotNull(splitLine, lagLost?.takeIf { it >= 500 }?.let { "§7lag §c+${formatSplitTime(it)}" }).joinToString(" §8· ").ifEmpty { null },
        clear.joinToString(" §8· ").ifEmpty { null },
        combat.joinToString(" §8· ").ifEmpty { null },
        rewards.joinToString(" §8· ").ifEmpty { null },
    )
}
