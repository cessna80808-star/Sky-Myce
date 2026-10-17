package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
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

/** Average Catacombs experience per tracked run, or null without runs that granted experience. */
internal fun trackedXpPerRun(stats: TrackerSummary): Double? = stats.cataXp.takeIf { it > 0 && it.isFinite() && stats.runs > 0 }?.div(stats.runs)

/** The target line: level, experience left and estimated runs; [unavailable] explains a missing profile reading. */
internal fun dungeonTargetLine(target: Int, progress: TargetProgress?, unavailable: String): String {
    if (progress == null) return "§7Target: §bCata $target §8($unavailable)"
    if (progress.remaining <= 0) return "§7Target: §aCata $target reached §7(Cata ${progress.level})"
    val runs = progress.runs?.let { " §7· ~§e${NumberUtils.condense(it)}§7 runs" } ?: ""
    return "§7Target: §bCata $target §7(§b${NumberUtils.condense(progress.remaining)}§7 XP left$runs§7)"
}

internal fun dungeonTrackerWidgetLines(floor: DungeonFloor?, stats: TrackerSummary,
    rateSample: TrackerSummary? = stats.takeIf { it.completeTiming && it.profitTimingComplete && it.xpTimingComplete },
    target: String? = null): List<String> {
    fun number(value: Double) = if (value.isFinite()) NumberUtils.condense(value) else "Unknown"
    fun duration(millis: Long?) = millis?.takeIf { it >= 0 }?.milliseconds?.takeIf { it.isFinite() }
        ?.inWholeSeconds?.seconds?.toString() ?: "Unknown"
    val sample = rateSample?.takeIf { it.completeTiming }
    fun rate(value: Double?) = value?.let { sample?.hourlyRate(it) }?.let(::number) ?: "—"
    val partialTime = stats.timedRuns < stats.runs
    val empty = stats.runs == 0L && stats.time == 0L
    val time = if (stats.timedRuns > 0) duration(stats.time) + (if (partialTime) " recorded" else "") else if (empty) "0s" else "Unknown"
    val average = duration(stats.average?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: 0L.takeIf { empty })
    val profit = stats.knownNet.takeIf(Double::isFinite)?.let { "§7~${TextUtils.parseProfit(it)}" } ?: "§eUnknown"
    val profitRate = if (sample != null && !sample.profitIncomplete && sample.profitTimingComplete) rate(sample.knownNet) else "—"
    val xpRate = if (sample?.xpTimingComplete == true) rate(sample.cataXp) else "—"
    val sampleCaption = when {
        stats.runs == 0L -> ""
        sample == null -> "§8Rates need matched run times"
        partialTime || sample.runs != stats.runs || sample.time != stats.time || !stats.profitTimingComplete || !stats.xpTimingComplete ->
            "§8Rates: ${sample.runs}/${stats.runs} matched runs"
        else -> ""
    }
    return listOf(
        "§b§l${floor?.name ?: "All floors"} Dungeon Tracker",
        "§8/skymyce dungeon",
        "",
        "§7Time: §e$time§7 (§e$average§7/run)",
        "§7Runs: §a${stats.runs.coerceAtLeast(0)}§7 (§a${rate(sample?.runs?.toDouble())}§7/h)",
        "§7Chests: §a${stats.chests.coerceAtLeast(0)}§7 (§b${stats.rerolls.coerceAtLeast(0)} rerolls§7)",
        sampleCaption,
        "§7Profit: $profit${if (stats.profitIncomplete) " §8+ ?" else ""}§7 ($profitRate§7/h)",
        "§7Cata Exp: §b${number(stats.cataXp)} §7(§b$xpRate§7/h)",
    ) + listOfNotNull(target)
}

/** Fixed example data belongs only to the editor, never the recorded dungeon totals. */
private val dungeonTrackerPreview = dungeonTrackerWidgetLines(DungeonFloor.M7,
    TrackerSummary(runs = 10, time = 3_600_000, timedRuns = 10, gross = 12_000_000.0, cataXp = 400_000.0, chests = 10, rerolls = 2),
    target = dungeonTargetLine(50, targetProgress(450_000_000.0, 50, 40_000.0), ""))

internal fun dungeonTrackerPreviewLines(enabled: Boolean): List<String>? = dungeonTrackerPreview.takeIf { enabled }

object DungeonTrackerWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }
    private val previewContent by lazy { hudPanel(padding = 6) }
    private var seenRevision = -1L
    private var seenHistoricalRevision = -1L
    private var seenFloor: DungeonFloor? = null
    private var seenContext: AcquisitionContext? = null
    private var seenBazaar: DungeonsConfig.BazaarType? = null
    private var seenAuction: DungeonsConfig.AuctionType? = null
    private var nextMarketRefresh = 0L
    private var cachedLines = emptyList<String>()

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100, previewBuilder = {
        dungeonTrackerPreviewLines(DungeonTrackerConfig.dungeonTrackerWidget)?.let { previewContent.updateTextLines(it) }
    }) {
        if (!dungeonTrackerWidgetVisible(DungeonTrackerConfig.dungeonTrackerWidget,
                SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS),
                DungeonAPI.started, DungeonAPI.completed)) return@Widget null

        val context = AcquisitionRepository.currentContext()
        val activeFloor = DungeonTracker.currentFloor ?: DungeonAPI.dungeonFloor
        val bazaar = DungeonTrackerConfig.bazaarPriceType; val auction = DungeonTrackerConfig.auctionPriceType
        val now = System.currentTimeMillis()
        if (seenRevision != AcquisitionRepository.revision || seenHistoricalRevision != DungeonTracker.revision || seenFloor != activeFloor || seenContext != context ||
            seenBazaar != bazaar || seenAuction != auction || now >= nextMarketRefresh) {
            seenRevision = AcquisitionRepository.revision; seenHistoricalRevision = DungeonTracker.revision; seenFloor = activeFloor; seenContext = context
            seenBazaar = bazaar; seenAuction = auction; nextMarketRefresh = now + 10_000
            val floor = dungeonTrackerDisplayFloor(activeFloor, null, AcquisitionRepository.state.runs, context)
            val legacy = legacyTrackerView()
            val selected = trackerView(AcquisitionRepository.state, legacy,
                TrackerFilter(floor = floor?.name, scope = TrackerScope.ALL_LOCAL_PROFILES), context)
            val sample = dungeonTrackerRateSample(AcquisitionRepository.state, legacy, floor?.name)
            val prices = (selected.loot + sample.loot).map { it.item }.distinct().associateWith { cachedLootUnitPrice(it, bazaar, auction) }
            val pricedLoot = marketValuedLoot(selected.loot, prices::get)
            val sampleLoot = marketValuedLoot(sample.loot, prices::get)
            val summary = marketValuedTrackerSummary(selected.summary, pricedLoot)
            cachedLines = dungeonTrackerWidgetLines(floor, summary, marketValuedTrackerSummary(sample.summary, sampleLoot),
                if (DungeonTrackerConfig.showTarget) dungeonTargetLine(DungeonTrackerConfig.targetLevel,
                    DungeonTargetLevel.progress(trackedXpPerRun(summary)), DungeonTargetLevel.unavailableReason) else null)
        }
        content.updateTextLines(cachedLines)
    }

}
