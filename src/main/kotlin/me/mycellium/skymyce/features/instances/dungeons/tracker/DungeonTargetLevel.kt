package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendProfileProvider
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel
import me.mycellium.skymyce.utils.MC
import kotlin.math.ceil

/** Progress toward a target Catacombs level; [runs] is null without a known experience-per-run figure. */
data class TargetProgress(val target: Int, val xp: Double, val level: Int, val remaining: Double, val runs: Long?,
                          /** Share of the way from level 0 to the target, 0–1. */
                          val progress: Double, val nextLevelProgress: Double)

fun targetProgress(xp: Double, target: Int, xpPerRun: Double?): TargetProgress {
    val level = dungeonLevel(xp)
    val goal = dungeonXpForLevel(target)
    val remaining = (goal - xp).coerceAtLeast(0.0)
    val floor = dungeonXpForLevel(level)
    val next = dungeonXpForLevel(level + 1)
    return TargetProgress(target, xp, level, remaining,
        xpPerRun?.takeIf { it > 0 && it.isFinite() }?.let { ceil(remaining / it).toLong() },
        if (goal <= 0) 1.0 else (xp / goal).coerceIn(0.0, 1.0), ((xp - floor) / (next - floor)).coerceIn(0.0, 1.0))
}

/**
 * Experience gained since a profile reading: tracked runs completed after it on the same profile. Profile responses lag
 * behind the game, so this keeps the target moving between readings; it never counts a run from before the reading.
 */
fun experienceSince(runs: List<DungeonRunRecord>, since: Long, context: AcquisitionContext?): Double =
    runs.asSequence().filter { run -> (run.completedAt ?: 0L) > since && (context == null || context.matches(run.context)) }
        .sumOf { it.cataXp?.takeIf { xp -> xp.isFinite() && xp > 0 } ?: 0.0 }

/** The target level from DungeonProgressHud, read from the player's own profile stats plus tracked runs since. */
object DungeonTargetLevel {
    private var baseXp: Double? = null
    private var baseAt = 0L
    private var nextRequest = 0L

    /** Current Catacombs experience, or null when no profile reading exists yet; asks for one at most every 5 minutes. */
    fun experience(): Double? {
        val name = MC.instance.user.name
        val read = DungeonFriendStatsCache.get(name)?.catacombsXp
        val now = System.currentTimeMillis()
        if (read != baseXp) { baseXp = read; baseAt = now }
        // Shared relay reports carry only the level, so only a profile provider or API key can supply the experience.
        if (now >= nextRequest && profileSource) {
            nextRequest = now + 300_000
            // Records saved before the experience was kept have none, so they are fetched again rather than waiting to expire.
            DungeonFriendStatsCache.request(name, MC.instance.user.profileId, force = read == null, bypassShared = true, refreshExpired = true)
        }
        val base = read ?: return null
        return base + experienceSince(AcquisitionRepository.state.runs, baseAt, AcquisitionRepository.currentContext())
    }

    fun progress(xpPerRun: Double?): TargetProgress? = experience()?.let { targetProgress(it, DungeonTrackerConfig.targetLevel, xpPerRun) }

    /** Why there is no reading, for the widget and screen. */
    val unavailableReason: String get() = if (profileSource) "loading your profile" else "needs Skyblocker, SkyBlockPV or a Hypixel API key"

    private val profileSource get() = DungeonFriendProfileProvider.available || PartyCommandsConfig.hypixelApiKey.isNotBlank()
}
