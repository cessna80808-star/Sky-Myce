package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.TextUtils
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun dungeonTrackerWidgetVisible(enabled: Boolean, inDungeonArea: Boolean, started: Boolean, completed: Boolean) =
    enabled && inDungeonArea && (!started || completed)

/** Fixed example data belongs only to the editor, never the recorded dungeon totals. */
private val dungeonTrackerPreview = listOf(
    "§b§lF7 Dungeon Tracker",
    "§8Editor preview",
    "",
    "§7Time: §e1h§7 (§e6m§7/run)",
    "§7Runs: §a10§7 (§a10§7/h)",
    "§7Chests: §a10§7 (§b2 rerolls§7)",
    "",
    "§7Profit: §a12M§7 (§a12M§7/h)",
    "§7Cata Exp: §b400K §7(§b400K§7/h)",
)

internal fun dungeonTrackerPreviewLines(enabled: Boolean): List<String>? = dungeonTrackerPreview.takeIf { enabled }

object DungeonTrackerWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }
    private val previewContent by lazy { hudPanel(padding = 6) }
    private var seenRevision = -1L
    private var seenHistoricalRevision = -1L
    private var seenFloor: tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor? = null
    private var seenContext: AcquisitionContext? = null
    private var cachedLines = emptyList<String>()

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100, previewBuilder = {
        dungeonTrackerPreviewLines(DungeonTrackerConfig.dungeonTrackerWidget)?.let { previewContent.updateTextLines(it) }
    }) {
        if (!dungeonTrackerWidgetVisible(DungeonTrackerConfig.dungeonTrackerWidget,
                SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS),
                DungeonAPI.started, DungeonAPI.completed)) return@Widget null

        val context = AcquisitionRepository.currentContext()
        if (seenRevision != AcquisitionRepository.revision || seenHistoricalRevision != DungeonTracker.revision || seenFloor != DungeonTracker.currentFloor || seenContext != context) {
            seenRevision = AcquisitionRepository.revision; seenHistoricalRevision = DungeonTracker.revision; seenFloor = DungeonTracker.currentFloor; seenContext = context
            cachedLines = trackerLines()
        }
        content.updateTextLines(cachedLines)
    }

    private fun trackerLines(): List<String> {
        val view = DungeonTracker.confirmedView(DungeonTracker.currentFloor)
        val stats = view.summary
        val title = DungeonTracker.currentFloor?.name ?: "All floors"

        val lines = mutableListOf<String>()

        lines += "§b§l$title Dungeon Tracker"
        lines += "§8/skymyce dungeon"
        lines += ""
        lines += view.lifetimeProfit?.let { "§7Lifetime profit (est.): ${TextUtils.parseProfit(it)}" }
            ?: "§7Inherited profit (est.): ${TextUtils.parseProfit(view.inheritedProfit)} §8(new unknown)"
        lines += "§7Historical: §a${view.historicalRuns} runs §7/ §a${view.historicalChests} chests"
        lines += "§8Inherited: ${NumberUtils.condense(view.inheritedProfit)} • all local profiles"
        lines += ""
        lines += "§8Confirmed tracking • current profile"
        val time = if (stats.timedRuns > 0) stats.time.milliseconds.inWholeSeconds.seconds.toString() else "Unknown"
        val average = stats.average?.toLong()?.milliseconds?.inWholeSeconds?.seconds?.toString() ?: "Unknown"
        lines += "§7Time: §e$time§7 (§e$average§7/run)"
        if (stats.timedRuns < stats.runs) lines += "§8Time known for ${stats.timedRuns}/${stats.runs} runs"
        fun rate(value: Double) = if (stats.completeTiming) NumberUtils.condense(value * 3600000 / stats.time) else "—"
        lines += "§7New runs: §a${stats.runs}§7 (§a${rate(stats.runs.toDouble())}§7/h)"
        lines += "§7Chests: §a${stats.chests}§7 (§b${stats.rerolls} rerolls§7)"
        lines += ""
        lines += "§7New profit (est.): ${stats.net?.let(TextUtils::parseProfit) ?: "§eUnknown"}"
        lines += "§7Cata Exp: §b${NumberUtils.condense(stats.cataXp)} §7(§b${rate(stats.cataXp)}§7/h)"

        return lines
    }
}
