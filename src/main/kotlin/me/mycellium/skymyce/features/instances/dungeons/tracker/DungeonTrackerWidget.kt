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

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100, previewBuilder = {
        dungeonTrackerPreviewLines(DungeonTrackerConfig.dungeonTrackerWidget)?.let { previewContent.updateTextLines(it) }
    }) {
        if (!dungeonTrackerWidgetVisible(DungeonTrackerConfig.dungeonTrackerWidget,
                SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS),
                DungeonAPI.started, DungeonAPI.completed)) return@Widget null

        val stats = DungeonTracker.profitData[DungeonTracker.currentFloor]
        val title = DungeonTracker.currentFloor?.name ?: "?"

        val lines = mutableListOf<String>()

        lines += "§b§l$title Dungeon Tracker"
        when {
            (DungeonTracker.currentFloor == null) -> {
                lines += "§7Unknown floor!"
                lines += "§8(Try queueing a party)"
            }

            (stats == null) -> {
                lines += "§7No dungeon data yet."
                lines += "§8(Try playing runs)"
            }

            else -> {
                lines += "§8/skymyce dungeon"
                lines += ""
                val time = if (stats.timedRunCount > 0) stats.totalTimeMillis.milliseconds.inWholeSeconds.seconds.toString() else "Unknown"
                val average = stats.averageRunMillis?.toLong()?.milliseconds?.inWholeSeconds?.seconds?.toString() ?: "Unknown"
                lines += "§7Time: §e$time§7 (§e$average§7/run)"
                if (stats.incompleteTime) lines += "§8Time known for ${stats.timedRunCount}/${stats.totalRuns} runs"
                fun rate(value: Double) = stats.hourlyRate(value)?.let(NumberUtils::condense) ?: "—"
                lines += "§7Runs: §a${stats.totalRuns}§7 (§a${rate(stats.totalRuns.toDouble())}§7/h)"
                lines += "§7Chests: §a${stats.totalChestsOpened}§7 (§b${stats.totalRerolled} rerolls§7)"
                lines += ""
                lines += "§7Profit: ${TextUtils.parseProfit(stats.netProfit)}§7 (${rate(stats.netProfit)}§7/h)"

                lines +=
                    "§7Cata Exp: §b${NumberUtils.condense(stats.totalXp)} §7(§b${
                        rate(stats.totalXp)
                    }§7/h)"
            }
        }

        content.updateTextLines(lines)
    }
}
