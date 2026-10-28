package me.mycellium.skymyce.features.instances.dungeons

import kotlin.math.roundToInt
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonRank
import me.mycellium.skymyce.api.DungeonRank.Companion.formatted
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitStore
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplits
import me.mycellium.skymyce.features.instances.dungeons.splits.FloorSplitStats
import me.mycellium.skymyce.features.instances.dungeons.splits.ScoreInputs
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitComparison
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitOutcome
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitRunRecord
import me.mycellium.skymyce.features.instances.dungeons.splits.floorSplitStats
import me.mycellium.skymyce.features.instances.dungeons.splits.formatSplitDelta
import me.mycellium.skymyce.features.instances.dungeons.splits.formatSplitTime
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.MotionEasing
import me.mycellium.skymyce.ui.MotionValue
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyIn
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

object DungeonWinOverlay : SkyMyceModule() {
    private const val RANK_WIDTH = 96
    private const val RANK_HEIGHT = 48

    private val SCORE_REGEX = Regex("^\\s*Team Score: (\\d+) \\((.+?)\\)( \\(NEW RECORD!\\))?$")

    private var showOverlay = false
    private var currentRank = DungeonRank.UNKNOWN
    private var currentStats = mutableListOf<Component>()
    /** The summary lines that follow the score arrive over the next few seconds. */
    private var summary = WinSummary()
    private var collectUntil = 0L
    private var details = emptyList<Component>()
    private var detailsKey: List<Any?>? = null
    private val rankTravel = MotionValue(0.0)
    private val rankScale = MotionValue(0.05)
    private var statReveals = emptyList<MotionValue>()

    override fun init() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "dungeon_win_screen")
        ) { context, _ -> if (isEnabled()) render(context) }
    }

    // Chat filters may hide the summary lines; they are still read.
    @Subscription(receiveCancelled = true)
    @OnlyIn(SkyBlockIsland.THE_CATACOMBS)
    fun onChatEvent(event: ChatReceivedEvent.Pre) {
        if (showOverlay && System.currentTimeMillis() < collectUntil) readWinSummaryLine(summary, event.text)?.let { summary = it; return }
        SCORE_REGEX.find(event.text)?.let {
            showOverlay = true
            currentRank = DungeonRank.fromLabel(it.groupValues[2])
            currentStats = mutableListOf<Component>().apply {
                add(Component.literal("§c${DungeonAPI.dungeonFloor?.longName}"))
                add(Component.literal("§fTeam Score: §a${it.groupValues[1]}§f (${currentRank.formatted()}§f)${if (it.groupValues[3].isNotEmpty()) " §6NEW RECORD!" else ""}"))
                add(Component.literal("§7Time Elapsed: §e${DungeonAPI.time}"))
            }
            summary = WinSummary(); collectUntil = System.currentTimeMillis() + 8_000; details = emptyList(); detailsKey = null
            rankTravel.snap(0.0)
            rankScale.snap(0.05)
            statReveals = currentStats.map { MotionValue(0.0) }
            animateReveal()
        }
    }

    @Subscription
    fun onIslandChange(event: IslandChangeEvent) {
        showOverlay = false
        rankTravel.cancel(); rankScale.cancel()
        statReveals.forEach(MotionValue::cancel)
        statReveals = emptyList()
    }

    private fun animateReveal() {
        val mode = ThemeConfig.animationMode
        // Reduced motion keeps the rank at its final position and only fades the run details.
        val rankMode = if (mode.profile.pageOffset > 0.0) mode else GuiMotion.OFF
        rankTravel.animateTo(1.0, 1000, MotionEasing.SMOOTH, rankMode)
        rankScale.animateTo(1.0, 650, MotionEasing.SETTLE, rankMode)
        statReveals.forEachIndexed { index, reveal ->
            reveal.animateTo(1.0, 200, MotionEasing.QUICK, mode,
                delayMillis = if (mode.profile.pageOffset > 0.0) 650L + index * mode.profile.staggerMillis else 0)
        }
    }

    /** Details are rebuilt only when the summary, the splits or the saved runs change. */
    private fun refreshDetails() {
        if (!DungeonsConfig.dungeonWinScreenDetails) { if (details.isNotEmpty()) details = emptyList(); return }
        val key = listOf(summary, DungeonSplits.revision, DungeonSplitStore.revision, DungeonSplitsConfig.bestsClock, DungeonSplitsConfig.comparison)
        if (key == detailsKey) return
        detailsKey = key
        val run = DungeonSplits.live?.takeIf { it.timeline.finished }
        val clock = DungeonSplitsConfig.bestsClock
        val record = run?.let { it.timeline.record(it.id, it.recordedAt, it.outcome ?: SplitOutcome.FAILED) }
        val splitLine = record?.let { r ->
            // Compared with the runs before this one, which the store already holds.
            val stats = floorSplitStats(DungeonSplitStore.runs.filter { it.id != r.id }, run.definition, DungeonSplitsConfig.averageWindow, clock)
            winSplitLine(r, stats, clock, DungeonSplitsConfig.comparison.target)
        }
        details = winDetailLines(summary, run?.score, splitLine, record?.lagLost).map(Component::literal)
        while (statReveals.size < currentStats.size + details.size) statReveals = statReveals + MotionValue(0.0)
    }

    fun render(graphics: GuiGraphicsExtractor) {
        if (!DungeonsConfig.dungeonWinScreen) return
        if (!showOverlay) return
        if (MC.instance.options.hideGui) return
        refreshDetails()

        animateReveal()
        val widgetX = graphics.guiWidth() / 2F
        val widgetY = graphics.guiHeight() * (0.5 - rankTravel.sample() / 3.0).toFloat()
        val scale = rankScale.sample().coerceAtLeast(0.05)

        val rankWidth = (RANK_WIDTH * scale).roundToInt()
        val rankHeight = (RANK_HEIGHT * scale).roundToInt()

        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            currentRank.texture,
            (widgetX + rankWidth / -2).toInt(),
            (widgetY + rankHeight / -2).toInt(),
            0f,
            0f,
            rankWidth,
            rankHeight,
            rankWidth,
            rankHeight
        )

        for ((index, line) in (currentStats + details).withIndex()) {
            val textAlpha = ((statReveals.getOrNull(index)?.sample() ?: 1.0).coerceIn(0.0, 1.0) * 255).roundToInt()
            if (textAlpha == 0) continue
            val lineColor = (textAlpha shl 24) or 0xFFFFFF
            graphics.centeredText(
                MC.font,
                line,
                widgetX.toInt(),
                (widgetY.toInt() + RANK_HEIGHT / 2 + 15) + index * (MC.font.lineHeight + 3),
                lineColor
            )
        }
    }
}

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
