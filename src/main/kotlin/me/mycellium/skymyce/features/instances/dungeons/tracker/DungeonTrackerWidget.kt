package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.TextUtils
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun dungeonTrackerWidgetVisible(enabled: Boolean, inDungeonArea: Boolean, started: Boolean, completed: Boolean) =
    enabled && inDungeonArea && (!started || completed)

internal fun dungeonTrackerDisplayFloor(current: DungeonFloor?, api: DungeonFloor?, runs: List<DungeonRunRecord>,
    context: AcquisitionContext?): DungeonFloor? = current ?: api ?: context?.let { verified ->
    runs.asSequence().filter { it.trackingVersion == 2 && it.completedAt != null && verified.matches(it.context) && DungeonFloor.getByName(it.floor) != null }
        .maxByOrNull { it.completedAt!! }?.floor?.let { DungeonFloor.getByName(it) }
}

internal fun dungeonTrackerWidgetLines(floor: DungeonFloor?, stats: TrackerSummary): List<String> {
    fun number(value: Double) = if (value.isFinite()) NumberUtils.condense(value) else "Unknown"
    fun duration(millis: Long?) = millis?.takeIf { it >= 0 }?.milliseconds?.takeIf { it.isFinite() }
        ?.inWholeSeconds?.seconds?.toString() ?: "Unknown"
    fun rate(value: Double) = stats.hourlyRate(value)?.let(::number) ?: "—"
    val partialTime = stats.timedRuns < stats.runs
    val empty = stats.runs == 0L && stats.time == 0L
    val time = if (stats.timedRuns > 0) (if (partialTime) "≥" else "") + duration(stats.time) else if (empty) "0s" else "Unknown"
    val average = duration(stats.average?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: 0L.takeIf { empty })
    val profit = stats.knownNet.takeIf(Double::isFinite)?.let { "§7~${TextUtils.parseProfit(it)}" } ?: "§eUnknown"
    val profitRate = if (!stats.profitIncomplete && stats.profitTimingComplete) rate(stats.knownNet) else "—"
    val xpRate = if (stats.xpTimingComplete) rate(stats.cataXp) else "—"
    return listOf(
        "§b§l${floor?.name ?: "All floors"} Dungeon Tracker",
        "§8/skymyce dungeon",
        "",
        "§7Time: §e$time§7 (§e$average§7/run${if (partialTime && stats.timedRuns > 0) " known" else ""})",
        "§7Runs: §a${stats.runs.coerceAtLeast(0)}§7 (§a${rate(stats.runs.toDouble())}§7/h)",
        "§7Chests: §a${stats.chests.coerceAtLeast(0)}§7 (§b${stats.rerolls.coerceAtLeast(0)} rerolls§7)",
        "",
        "§7Profit: $profit${if (stats.profitIncomplete) " §8+ ?" else ""}§7 ($profitRate§7/h)",
        "§7Cata Exp: §b${number(stats.cataXp)} §7(§b$xpRate§7/h)",
    )
}

/** Fixed example data belongs only to the editor, never the recorded dungeon totals. */
private val dungeonTrackerPreview = dungeonTrackerWidgetLines(DungeonFloor.M7,
    TrackerSummary(runs = 10, time = 3_600_000, timedRuns = 10, gross = 12_000_000.0, cataXp = 400_000.0, chests = 10, rerolls = 2))

internal fun dungeonTrackerPreviewLines(enabled: Boolean): List<String>? = dungeonTrackerPreview.takeIf { enabled }

object DungeonTrackerWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }
    private val previewContent by lazy { hudPanel(padding = 6) }
    private var seenRevision = -1L
    private var seenHistoricalRevision = -1L
    private var seenFloor: DungeonFloor? = null
    private var seenContext: AcquisitionContext? = null
    private var cachedLines = emptyList<String>()

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100, previewBuilder = {
        dungeonTrackerPreviewLines(DungeonTrackerConfig.dungeonTrackerWidget)?.let { previewContent.updateTextLines(it) }
    }) {
        if (!dungeonTrackerWidgetVisible(DungeonTrackerConfig.dungeonTrackerWidget,
                SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS),
                DungeonAPI.started, DungeonAPI.completed)) return@Widget null

        val context = AcquisitionRepository.currentContext()
        val activeFloor = DungeonTracker.currentFloor ?: DungeonAPI.dungeonFloor
        if (seenRevision != AcquisitionRepository.revision || seenHistoricalRevision != DungeonTracker.revision || seenFloor != activeFloor || seenContext != context) {
            seenRevision = AcquisitionRepository.revision; seenHistoricalRevision = DungeonTracker.revision; seenFloor = activeFloor; seenContext = context
            val floor = dungeonTrackerDisplayFloor(activeFloor, null, AcquisitionRepository.state.runs, context)
            cachedLines = dungeonTrackerWidgetLines(floor, dungeonTrackerLifetimeSummary(AcquisitionRepository.state, legacyTrackerView(), floor?.name))
        }
        content.updateTextLines(cachedLines)
    }

}
