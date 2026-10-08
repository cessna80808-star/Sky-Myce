package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonRank
import me.mycellium.skymyce.api.DungeonRank.Companion.formatted
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
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
    private val rankTravel = MotionValue(0.0)
    private val rankScale = MotionValue(0.05)
    private var statReveals = emptyList<MotionValue>()

    override fun init() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "dungeon_win_screen")
        ) { context, _ -> if (isEnabled()) render(context) }
    }

    @Subscription
    @OnlyIn(SkyBlockIsland.THE_CATACOMBS)
    fun onChatEvent(event: ChatReceivedEvent.Pre) {
        SCORE_REGEX.find(event.text)?.let {
            showOverlay = true
            currentRank = DungeonRank.fromLabel(it.groupValues[2])
            currentStats = mutableListOf<Component>().apply {
                add(Component.literal("§c${DungeonAPI.dungeonFloor?.longName}"))
                add(Component.literal("§fTeam Score: §a${it.groupValues[1]}§f (${currentRank.formatted()}§f)"))
                add(Component.literal("§7Time Elapsed: §e${DungeonAPI.time}"))
            }
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

    fun render(graphics: GuiGraphicsExtractor) {
        if (!DungeonsConfig.dungeonWinScreen) return
        if (!showOverlay) return
        if (MC.instance.options.hideGui) return

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

        for ((index, line) in currentStats.withIndex()) {
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
