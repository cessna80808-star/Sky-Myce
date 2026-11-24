package me.mycellium.skymyce.features.instances.dungeons.tracker

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.themeForeground
import me.mycellium.skymyce.hud.themeLuminance
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

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

private const val MAX_DISPLAY_DURATION_MILLIS = 31_536_000_000_000L
private val LEGACY_FORMAT_CODES = Regex("§.")

/** Compact HUD number: up to two decimals for billions/trillions, one decimal for smaller values; "—" when invalid. */
internal fun formatCompactNumber(value: Double): String {
    if (!value.isFinite() || abs(value) >= 1e16) return "—"
    val magnitude = abs(value)
    val sign = if (value < 0 && magnitude >= 0.05) "-" else ""
    val (scaled, suffix, decimals) = when {
        magnitude >= 999_995_000_000.0 -> Triple(magnitude / 1_000_000_000_000.0, "T", 2)
        magnitude >= 999_950_000.0 -> Triple(magnitude / 1_000_000_000.0, "B", 2)
        magnitude >= 999_950.0 -> Triple(magnitude / 1_000_000.0, "M", 1)
        magnitude >= 999.95 -> Triple(magnitude / 1_000.0, "K", 1)
        else -> Triple(magnitude, "", 1)
    }
    val formatted = String.format(Locale.ROOT, if (decimals == 2) "%.2f" else "%.1f", scaled)
        .trimEnd('0').trimEnd('.')
    return "$sign$formatted$suffix"
}

/** At most the two most significant time units (e.g. "3d 21h", "5m 48s"), or "—" when unknown. */
internal fun formatCompactDuration(millis: Long?): String {
    val safe = millis?.takeIf { it in 0..MAX_DISPLAY_DURATION_MILLIS } ?: return "—"
    val totalSeconds = safe / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
        hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0 -> if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
        else -> "${seconds}s"
    }
}

internal enum class TrackerValueTone { POSITIVE, NEGATIVE, ACCENT, NEUTRAL, MUTED }

internal data class TrackerHudRow(
    val label: String,
    val value: String,
    val secondary: String = "",
    val tone: TrackerValueTone = TrackerValueTone.NEUTRAL,
    val prominent: Boolean = false,
)

internal data class TrackerHudView(
    val badge: String?,
    val title: String,
    val rows: List<TrackerHudRow>,
    val targetLine: String? = null,
) {
    val heading: String get() = if (badge != null) "$badge $title" else title
}

internal fun dungeonTrackerWidgetView(floor: DungeonFloor?, stats: TrackerSummary, target: String? = null): TrackerHudView {
    fun count(value: Long) = String.format(Locale.ROOT, "%,d", value.coerceAtLeast(0))
    fun rate(value: Double): String = stats.estimatedHourly(value)
        ?.let(::formatCompactNumber)?.takeUnless { it == "—" }?.let { "$it/h" } ?: "—"

    val net = stats.knownNet
    val profitValid = net.isFinite() && abs(net) < 1e16
    val profitValue = if (!profitValid) "—" else {
        val number = formatCompactNumber(net)
        "~$number${if (stats.profitIncomplete) "+" else ""}"
    }
    val profitTone = when {
        !profitValid -> TrackerValueTone.MUTED
        net >= 0.05 -> TrackerValueTone.POSITIVE
        net <= -0.05 -> TrackerValueTone.NEGATIVE
        else -> TrackerValueTone.NEUTRAL
    }

    val empty = stats.runs == 0L && stats.time == 0L
    val totalTime = when {
        stats.timedRuns > 0 -> formatCompactDuration(stats.time)
        empty -> "0s"
        else -> "—"
    }
    val avgTime = formatCompactDuration(stats.average?.takeIf { it.isFinite() && it >= 0 }?.toLong())
    val rerolls = stats.rerolls.coerceAtLeast(0)
    val rerollText = when (rerolls) {
        0 -> ""
        1 -> "1 reroll"
        else -> "${count(rerolls.toLong())} rerolls"
    }

    val rows = listOf(
        TrackerHudRow("Profit", profitValue, rate(net), profitTone, prominent = true),
        TrackerHudRow("Cata XP", formatCompactNumber(stats.cataXp), rate(stats.cataXp), TrackerValueTone.ACCENT),
        TrackerHudRow("Runs", count(stats.runs), rate(stats.runs.toDouble())),
        TrackerHudRow("Avg run", avgTime),
        TrackerHudRow("Chests", count(stats.chests.toLong()), rerollText),
        TrackerHudRow("Time", totalTime),
    )
    return TrackerHudView(
        badge = floor?.name,
        title = if (floor != null) "Tracker" else "All floors Tracker",
        rows = rows,
        targetLine = target,
    )
}

/** Fixed example data belongs only to the editor, never the recorded dungeon totals. */
private val dungeonTrackerPreview = dungeonTrackerWidgetView(DungeonFloor.M7,
    TrackerSummary(runs = 10, time = 3_600_000, timedRuns = 10, gross = 12_000_000.0, cataXp = 400_000.0, chests = 10, rerolls = 2))

/** The editor's level card: Cata 52, 86.2% of the way to 53, at the preview's 400k per run. */
internal val dungeonTrackerPreviewLevel = levelRow("Catacombs", null,
    me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel(52) + 0.862 * 200_000_000, 53, 400_000.0)
internal const val DUNGEON_TRACKER_PREVIEW_RATE = 400_000.0

internal fun dungeonTrackerPreviewView(enabled: Boolean): TrackerHudView? = dungeonTrackerPreview.takeIf { enabled }

/** Retained header and aligned 3-column stats table; remeasured only when data changes, zero per-frame allocations. */
internal class DungeonTrackerBody : BaseUIComponent() {
    private var view: TrackerHudView = dungeonTrackerPreview
    private var boldTitle = ""
    private var displayValues = emptyList<String>()
    private var valueWidths = IntArray(0)
    private var secondaryWidths = IntArray(0)
    private var badgeWidth = 0
    private var headerWidth = 0
    private var labelColWidth = 0
    private var valueColWidth = 0
    private var secondaryColWidth = 0
    private var hasSecondary = false
    private var bodyWidth = MIN_WIDTH
    private var bodyHeight = ROWS_TOP

    fun update(next: TrackerHudView): DungeonTrackerBody {
        val font = MC.instance.font
        view = next
        boldTitle = "§l${next.title}"
        badgeWidth = next.badge?.let { font.width(it) + BADGE_PAD_X * 2 - 1 } ?: 0
        headerWidth = if (next.badge != null) badgeWidth + BADGE_GAP + font.width(boldTitle) else font.width(boldTitle)
        displayValues = next.rows.map { if (it.prominent) "§l${it.value}" else it.value }
        valueWidths = IntArray(next.rows.size) { i -> font.width(displayValues[i]) }
        secondaryWidths = IntArray(next.rows.size) { i ->
            val sec = next.rows[i].secondary
            if (sec.isEmpty()) 0 else font.width(sec)
        }
        labelColWidth = next.rows.maxOfOrNull { font.width(it.label) } ?: 0
        valueColWidth = valueWidths.maxOrNull() ?: 0
        secondaryColWidth = secondaryWidths.maxOrNull() ?: 0
        hasSecondary = secondaryColWidth > 0
        bodyHeight = ROWS_TOP + (next.rows.size * ROW_HEIGHT - 2).coerceAtLeast(0)
        fitWidth(preferredWidth())
        return this
    }

    fun preferredWidth(): Int {
        val tableWidth = labelColWidth + COL_GAP + valueColWidth + if (hasSecondary) COL_GAP + secondaryColWidth else 0
        return maxOf(MIN_WIDTH, headerWidth, tableWidth)
    }

    fun fitWidth(width: Int): DungeonTrackerBody {
        bodyWidth = width.coerceAtLeast(preferredWidth())
        sizing(Sizing.fixed(bodyWidth), Sizing.fixed(bodyHeight))
        return this
    }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val font = MC.instance.font
        val shadow = HudTheme.SHADOW && themeLuminance(HudTheme.PANEL) < 0.4
        val text = HudTheme.alpha(HudTheme.TEXT, 100)
        val muted = HudTheme.alpha(HudTheme.MUTED, 100)
        val accent = HudTheme.alpha(HudTheme.ACCENT, 100)
        val green = HudTheme.alpha(HudTheme.GREEN, 100)
        val red = HudTheme.alpha(HudTheme.RED, 100)
        val left = x(); val right = x() + width(); val top = y()

        val badge = view.badge
        if (badge != null) {
            if (HudTheme.ROUNDED) {
                graphics.fill(left + 1, top, left + badgeWidth - 1, top + BADGE_HEIGHT, accent)
                graphics.fill(left, top + 1, left + badgeWidth, top + BADGE_HEIGHT - 1, accent)
            } else {
                graphics.fill(left, top, left + badgeWidth, top + BADGE_HEIGHT, accent)
            }
            graphics.text(font, badge, left + BADGE_PAD_X, top + 2, HudTheme.alpha(themeForeground(HudTheme.ACCENT), 100), false)
            graphics.text(font, boldTitle, left + badgeWidth + BADGE_GAP, top + 2, text, shadow)
        } else {
            graphics.text(font, boldTitle, left, top + 2, accent, shadow)
        }

        graphics.fill(left, top + DIVIDER_Y, right, top + DIVIDER_Y + 1, HudTheme.alpha(HudTheme.BORDER, 45))

        val valueRight = if (hasSecondary) right - secondaryColWidth - COL_GAP else right
        view.rows.forEachIndexed { index, row ->
            val rowY = top + ROWS_TOP + index * ROW_HEIGHT
            graphics.text(font, row.label, left, rowY, muted, shadow)
            val valueColor = when (row.tone) {
                TrackerValueTone.POSITIVE -> green
                TrackerValueTone.NEGATIVE -> red
                TrackerValueTone.ACCENT -> accent
                TrackerValueTone.NEUTRAL -> text
                TrackerValueTone.MUTED -> muted
            }
            graphics.text(font, displayValues[index], valueRight - valueWidths[index], rowY, valueColor, shadow)
            if (row.secondary.isNotEmpty()) {
                graphics.text(font, row.secondary, right - secondaryWidths[index], rowY, muted, shadow)
            }
        }
    }

    companion object {
        private const val MIN_WIDTH = 132
        private const val BADGE_HEIGHT = 11
        private const val BADGE_PAD_X = 3
        private const val BADGE_GAP = 4
        private const val DIVIDER_Y = 14
        private const val ROWS_TOP = 18
        private const val ROW_HEIGHT = 11
        private const val COL_GAP = 12
    }
}

object DungeonTrackerWidget : SkyMyceModule() {
    private val body by lazy { DungeonTrackerBody() }
    private val card by lazy { DungeonLevelCard() }
    private val fallback by lazy { hudLabel() }
    private val content by lazy { hudPanel(padding = 6).apply { gap(4); child(body) } }
    private val previewBody by lazy { DungeonTrackerBody() }
    private val previewCard by lazy { DungeonLevelCard() }
    private val previewContent by lazy { hudPanel(padding = 6).apply { gap(4); child(previewBody); child(previewCard) } }
    private var previewReady = false
    private var seenRevision = -1L
    private var seenHistoricalRevision = -1L
    private var seenFloor: DungeonFloor? = null
    private var seenContext: AcquisitionContext? = null
    private var seenBazaar: DungeonsConfig.BazaarType? = null
    private var seenAuction: DungeonsConfig.AuctionType? = null
    private var seenShowTarget: Boolean? = null
    private var seenTargetLevel = -1
    private var nextMarketRefresh = 0L
    private var cachedViews: Pair<DungeonFloor?, TrackerView>? = null

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100, previewBuilder = {
        val view = dungeonTrackerPreviewView(DungeonTrackerConfig.dungeonTrackerWidget) ?: return@Widget null
        if (!previewReady) {
            previewReady = true
            previewBody.update(view)
            previewCard.update(dungeonTrackerPreviewLevel, DUNGEON_TRACKER_PREVIEW_RATE)
            val width = maxOf(previewBody.preferredWidth(), previewCard.preferredWidth())
            previewBody.fitWidth(width)
            previewCard.fitWidth(width)
        }
        previewContent
    }) {
        if (!dungeonTrackerWidgetVisible(DungeonTrackerConfig.dungeonTrackerWidget,
                SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS),
                DungeonAPI.started, DungeonAPI.completed)) return@Widget null

        val context = AcquisitionRepository.currentContext()
        val activeFloor = DungeonTracker.currentFloor ?: DungeonAPI.dungeonFloor
        val bazaar = DungeonTrackerConfig.bazaarPriceType; val auction = DungeonTrackerConfig.auctionPriceType
        val showTarget = DungeonTrackerConfig.showTarget; val targetLevel = DungeonTrackerConfig.targetLevel
        val now = System.currentTimeMillis()
        val dataChanged = seenRevision != AcquisitionRepository.revision || seenHistoricalRevision != DungeonTracker.revision || seenFloor != activeFloor || seenContext != context
        if (dataChanged || seenBazaar != bazaar || seenAuction != auction || seenShowTarget != showTarget || seenTargetLevel != targetLevel || now >= nextMarketRefresh) {
            seenRevision = AcquisitionRepository.revision; seenHistoricalRevision = DungeonTracker.revision; seenFloor = activeFloor; seenContext = context
            seenBazaar = bazaar; seenAuction = auction; seenShowTarget = showTarget; seenTargetLevel = targetLevel; nextMarketRefresh = now + 10_000
            // The run history only changes with the data; the 10 s refresh is for prices and reuses it.
            val views = cachedViews?.takeIf { !dataChanged } ?: run {
                val floor = dungeonTrackerDisplayFloor(activeFloor, null, AcquisitionRepository.state.runs, context)
                floor to trackerView(AcquisitionRepository.state, legacyTrackerView(), TrackerFilter(floor = floor?.name, scope = TrackerScope.ALL_LOCAL_PROFILES), context)
            }.also { cachedViews = it }
            val (floor, selected) = views
            val prices = selected.loot.map { it.item }.distinct().associateWith { cachedLootUnitPrice(it, bazaar, auction) }
            val pricedLoot = marketValuedLoot(selected.loot, prices::get)
            val summary = marketValuedTrackerSummary(selected.summary, pricedLoot)
            val rate = trackedXpPerRun(summary)
            val xp = if (showTarget) DungeonTargetLevel.experience() else null
            val level = xp?.let { levelRow("Catacombs", null, it, targetLevel, rate) }
            // Without a profile reading the card cannot draw; one line says why instead.
            val targetLine = if (showTarget && xp == null) dungeonTargetLine(targetLevel, null, DungeonTargetLevel.unavailableReason) else null
            val view = dungeonTrackerWidgetView(floor, summary, targetLine)
            body.update(view)
            if (level != null) {
                card.update(level, rate)
                val width = maxOf(body.preferredWidth(), card.preferredWidth())
                body.fitWidth(width); card.fitWidth(width)
                if (fallback.parent() != null) content.removeChild(fallback)
                if (card.parent() == null) content.child(card)
            } else {
                if (card.parent() != null) content.removeChild(card)
                if (targetLine != null) {
                    fallback.updateText(targetLine)
                    val fallbackWidth = MC.instance.font.width(targetLine.replace(LEGACY_FORMAT_CODES, ""))
                    body.fitWidth(maxOf(body.preferredWidth(), fallbackWidth))
                    if (fallback.parent() == null) content.child(fallback)
                } else {
                    if (fallback.parent() != null) content.removeChild(fallback)
                    body.fitWidth(body.preferredWidth())
                }
            }
        }
        content
    }

}

private val recentRngTime = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)

internal fun recentRngLines(events: List<AcquisitionEvent>): List<String>? {
    val confirmed = events.filter { it.origin == "confirmed" && DigestRngCatalog.valid(it.item, it.activity) }.take(3)
    if (confirmed.isEmpty()) return null
    return listOf("§b§lRecent RNG §8· personal") + confirmed.map { event ->
        "§f${DigestRngCatalog.name(event.item)}${if (event.quantity > 1) " ×${event.quantity}" else ""} §8${recentRngTime.format(Instant.ofEpochMilli(event.occurredAt))}"
    } + "§8UTC · /sm journal"
}

/** Optional compact presentation of the existing archive, with no network work or invented live statistics. */
object RecentRngWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }
    private val preview by lazy { hudPanel(padding = 6).updateTextLines(listOf(
        "§b§lRecent RNG §8· editor preview", "§fNecron's Handle §8MM-dd HH:mm", "§8UTC · /sm journal")) }
    private var revision = -1L
    private var context: AcquisitionContext? = null
    private var lines: List<String>? = null
    val widget = Widget("recent_personal_rng", "Recent Personal RNG", 10, 100, previewBuilder = { preview }) {
        val current = AcquisitionRepository.currentContext()
        if (revision != AcquisitionRepository.revision || context != current) {
            revision = AcquisitionRepository.revision; context = current
            lines = recentRngLines(AcquisitionRepository.recentPersonal(3))
        }
        lines?.let { content.updateTextLines(it) }
    }
}
