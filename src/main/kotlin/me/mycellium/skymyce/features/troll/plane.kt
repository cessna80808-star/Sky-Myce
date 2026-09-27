package me.mycellium.skymyce.features.troll

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.utils.Utils.displayMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyIn
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.Scheduling.schedule
import java.io.File
import kotlin.time.Duration.Companion.seconds

object Plane : SkyMyceModule() {
    private const val ENABLED = true
    private const val TARGET_PLAYER = "Cessna808"
    private const val JUMPSCARE_TICKS = 30
    private val targetReadyRegex = Regex("^$TARGET_PLAYER is now ready!$")

    private val jumpscareTexture =
        Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "espe.png")
    private val seenPlayersFile: File
        get() = SkyMyce.configPath.resolve("plane_seen_players.txt").toFile()

    private var jumpscareTicksRemaining = 0
    private val scaredPlayers = mutableSetOf<String>()

    override fun init() {
        loadSeenPlayers()
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "plane_jumpscare")
        ) { graphics, _ -> if (isEnabled()) render(graphics) }
    }

    @Subscription
    @OnlyIn(SkyBlockIsland.THE_CATACOMBS)
    fun onChatMessage(event: ChatReceivedEvent.Pre) {
        if (!ENABLED) return
        if (!targetReadyRegex.matches(event.text)) return
        if (!scaredPlayers.add(TARGET_PLAYER)) return

        saveSeenPlayers()
        jumpscareTicksRemaining = JUMPSCARE_TICKS
        schedule(2.seconds) {
            displayMessage(string = "")
            displayMessage("§b§l[SM] §6Achievement gained: WTF??!!!")
            displayMessage("§6§l Met a creator :3")
            displayMessage(string = "")
            Minecraft.getInstance().player?.playSound(
                SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                1f,
                1f
            )
        }
    }

    @Subscription
    fun onIslandChange(@Suppress("UNUSED_PARAMETER") event: IslandChangeEvent) {
        jumpscareTicksRemaining = 0
    }

    private fun loadSeenPlayers() {
        if (!seenPlayersFile.exists()) return
        seenPlayersFile.readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(scaredPlayers::add)
    }

    private fun saveSeenPlayers() {
        seenPlayersFile.parentFile.mkdirs()
        seenPlayersFile.writeText(scaredPlayers.sorted().joinToString("\n"))
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        if (jumpscareTicksRemaining <= 0) return

        jumpscareTicksRemaining--
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            jumpscareTexture,
            0,
            0,
            0f,
            0f,
            graphics.guiWidth(),
            graphics.guiHeight(),
            graphics.guiWidth(),
            graphics.guiHeight()
        )
    }
}
