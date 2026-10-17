package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel
import me.mycellium.skymyce.utils.NumberUtils
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.util.Locale
import kotlin.math.ceil

/** What the levels panel shows on the right of each bar. */
enum class LevelMetric(val title: String, val header: String) {
    RUNS("Runs needed", "Runs to"), XP("XP needed", "XP to"), PERCENT("Percent", "% of");
    override fun toString() = title
}

/**
 * One Catacombs or class row toward [target]. [runs] is null without an experience-per-run figure; [estimatedRate]
 * marks a class whose rate was borrowed from Catacombs because it has no tracked runs of its own.
 */
data class LevelRow(val name: String, val dungeonClass: DungeonClass?, val xp: Double, val level: Int, val target: Int,
                    val remaining: Double, val runs: Long?, val estimatedRate: Boolean,
                    /** Share of the target's total experience, 0–1. */
                    val progress: Double,
                    /** Share of the way through the current level, 0–1. */
                    val nextLevelProgress: Double,
                    /** Experience still needed for the next level. */
                    val nextLevelRemaining: Double)

fun levelRow(name: String, dungeonClass: DungeonClass?, xp: Double, target: Int, xpPerRun: Double?, estimatedRate: Boolean = false): LevelRow {
    val safe = xp.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val level = dungeonLevel(safe)
    val goal = dungeonXpForLevel(target)
    val remaining = (goal - safe).coerceAtLeast(0.0)
    val floor = dungeonXpForLevel(level)
    val next = dungeonXpForLevel(level + 1)
    val rate = xpPerRun?.takeIf { it > 0 && it.isFinite() }
    return LevelRow(name, dungeonClass, safe, level, target, remaining, rate?.let { ceil(remaining / it).toLong() }, estimatedRate && rate != null,
        if (goal <= 0) 1.0 else (safe / goal).coerceIn(0.0, 1.0), ((safe - floor) / (next - floor)).coerceIn(0.0, 1.0), (next - safe).coerceAtLeast(0.0))
}

/** The class a run was played as: the one that gained the most experience (others may get a small share). */
internal fun playedClass(run: DungeonRunRecord): DungeonClass? =
    run.classXp.entries.filter { it.value.isFinite() && it.value > 0 }.maxByOrNull { it.value }?.key
        ?.let { name -> DungeonClass.entries.firstOrNull { it.name == name } }

/** Average class experience per tracked run played as that class, from runs on [context] (any profile when null). */
fun classXpPerRun(runs: List<DungeonRunRecord>, context: AcquisitionContext?): Map<DungeonClass, Double> =
    runs.asSequence().filter { context == null || context.matches(it.context) }
        .mapNotNull { run -> playedClass(run)?.let { it to run.classXp.getValue(it.name) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.average() }

/** Class experience gained in tracked runs completed after [since]. */
fun classExperienceSince(runs: List<DungeonRunRecord>, since: Long, context: AcquisitionContext?): Map<DungeonClass, Double> =
    runs.asSequence().filter { run -> (run.completedAt ?: 0L) > since && (context == null || context.matches(run.context)) }
        .flatMap { it.classXp.entries }.filter { it.value.isFinite() && it.value > 0 }
        .mapNotNull { (name, xp) -> DungeonClass.entries.firstOrNull { it.name == name }?.let { it to xp } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

/** The right-hand value: whole runs with separators, condensed experience, or percent of the target. */
fun levelValue(row: LevelRow, metric: LevelMetric): String = when (metric) {
    LevelMetric.RUNS -> if (row.remaining <= 0) "0" else row.runs?.let { (if (row.estimatedRate) "~" else "") + String.format(Locale.ROOT, "%,d", it) } ?: "—"
    LevelMetric.XP -> if (row.remaining <= 0) "0" else NumberUtils.condense(row.remaining)
    LevelMetric.PERCENT -> String.format(Locale.ROOT, "%.1f%%", row.progress * 100)
}

/** Hypixel's short class names, as the party finder writes them. */
fun levelClassName(dungeonClass: DungeonClass) = if (dungeonClass == DungeonClass.BERSERKER) "Berserk" else dungeonClass.displayName
