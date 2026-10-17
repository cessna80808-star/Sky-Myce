package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonRank
import me.mycellium.skymyce.api.DungeonRank.Companion.formatted
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitStore
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplits
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitOutcome
import me.mycellium.skymyce.features.instances.dungeons.splits.floorSplitStats
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.MotionEasing
import me.mycellium.skymyce.ui.MotionValue
import me.mycellium.skymyce.utils.MC
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
import kotlin.math.roundToInt

object DungeonWinOverlay : SkyMyceModule() {
    const val RANK_WIDTH = 96
    const val RANK_HEIGHT = 48

    val SCORE_REGEX = Regex("^\\s*Team Score: (\\d+) \\((.+?)\\)( \\(NEW RECORD!\\))?$")

    var showOverlay = false
    var currentRank = DungeonRank.UNKNOWN
    var currentStats = mutableListOf<Component>()
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
