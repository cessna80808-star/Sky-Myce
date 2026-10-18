package me.mycellium.skymyce.features.general.overlays

import com.mojang.blaze3d.platform.InputConstants
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.NpcDialogueConfig as Settings
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderScreenForegroundEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import java.util.Optional
import kotlin.math.roundToInt

/** One NPC line: who spoke and what they said. */
internal data class NpcLine(val name: String, val message: String)

/** An NPC's offered choice and what clicking it in chat does. */
internal data class DialogueOption(val label: String, val click: ClickEvent?)

private val npcLine = Regex("^\\[NPC] ([^:\\[\\]]{1,40}): (.+)$")
private val optionsLine = Regex("(?s).*(?:Select|Click) an option: (.+)$")

internal fun parseNpcLine(stripped: String): NpcLine? = npcLine.matchEntire(stripped.trim())?.let { NpcLine(it.groupValues[1].trim(), it.groupValues[2].trim()) }

internal fun isOptionsLine(stripped: String) = optionsLine.matches(stripped.trim())

/**
 * The options in an option line, from its styled segments: consecutive segments that share a click action form one
 * option, labelled by their text without brackets. Segments without a click action are separators.
 */
internal fun optionsFrom(segments: List<Pair<String, ClickEvent?>>): List<DialogueOption> {
    val options = ArrayList<DialogueOption>()
    var text = StringBuilder(); var click: ClickEvent? = null
    fun flush() {
        val label = text.toString().replace(Regex("§."), "").trim().removePrefix("[").removeSuffix("]").trim()
        if (click != null && label.isNotEmpty()) options += DialogueOption(label, click)
        text = StringBuilder(); click = null
    }
    for ((segment, event) in segments) {
        if (event != click) flush()
        if (event != null) { click = event; text.append(segment) }
    }
    flush()
    return options.take(9)
}

/** How long a line stays: its words at the reading speed, never less than the minimum. */
internal fun dialogueDuration(message: String, wordsPerMinute: Int, minimumMillis: Long): Long {
    val words = message.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    return (words * 60_000L / wordsPerMinute.coerceAtLeast(1)).coerceAtLeast(minimumMillis)
}

/** Characters revealed by the typewriter after [elapsed] ms: the whole line within a third of its time, at most 50 ms in. */
internal fun revealedChars(length: Int, elapsed: Long, duration: Long): Int {
    val full = (duration / 3).coerceIn(50, 1_200)
    return if (elapsed >= full) length else (length * elapsed / full).toInt().coerceIn(0, length)
}

/**
 * Shows [NPC] chat lines one at a time in a dialogue box, like SkyCubed: the speaker's name on a tab, their portrait
 * when they stand nearby, the line typed out, and each line held for as long as it takes to read. When an NPC offers
 * options they are listed with numbers; pressing a number does what clicking that option in chat does.
 */
object NpcDialogue : SkyMyceModule() {
    private val queue = ArrayDeque<NpcLine>()
    private var current: NpcLine? = null
    private var shownAt = 0L
    private var until = 0L
    private var options: List<DialogueOption> = emptyList()
    private var optionsUntil = 0L
    private var portrait: LivingEntity? = null
    private var openedAt = 0L
    private var keysDown = BooleanArray(9)

    private fun active() = isEnabled() && Settings.enabled && LocationAPI.isOnSkyBlock

    override fun init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "npc_dialogue")) { graphics, _ ->
            val screen = MC.screen
            if (active() && (screen == null || screen is ChatScreen)) draw(graphics, graphics.guiWidth() / 2, graphics.guiHeight() - 70, centred = true, maxWidth = graphics.guiWidth() / 3)
        }
    }

    @Subscription
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!active()) return
        val stripped = event.component.string.replace(Regex("§."), "")
        parseNpcLine(stripped)?.let { line ->
            queue.addLast(line)
            while (queue.size > 20) queue.removeFirst()
            if (Settings.hideChat) event.cancel()
            return
        }
        if (isOptionsLine(stripped)) {
            val segments = ArrayList<Pair<String, ClickEvent?>>()
            event.component.visit({ style: Style, text: String -> segments += text to style.clickEvent; Optional.empty<Unit>() }, Style.EMPTY)
            val parsed = optionsFrom(segments)
            if (parsed.isNotEmpty()) {
                options = parsed; optionsUntil = 0L
                if (Settings.hideOptions) event.cancel()
            }
        }
    }

    @Subscription
    fun onDisconnect(event: ServerDisconnectEvent) = reset()

    private fun reset() { queue.clear(); current = null; options = emptyList(); portrait = null; until = 0 }

    @Subscription(TickEvent::class)
    fun onTick() {
        if (!active()) { if (current != null || options.isNotEmpty()) reset(); return }
        val now = System.currentTimeMillis()
        if (now >= until) {
            val next = queue.removeFirstOrNull()
            if (next != null) {
                if (current == null) openedAt = now
                current = next; shownAt = now
                until = now + dialogueDuration(next.message, Settings.wordsPerMinute, Settings.minimumSeconds * 1000L)
                portrait = if (Settings.portrait) findNpc(next.name) else null
            } else if (options.isNotEmpty()) {
                if (optionsUntil == 0L) optionsUntil = now + OPTIONS_MILLIS
                if (now >= optionsUntil) { options = emptyList(); current = null }
            } else current = null
        }
        chooseByKey()
    }

    /** Number keys pick an option while no screen is open; a key must be pressed fresh, not held from before. */
    private fun chooseByKey() {
        val window = MC.instance.window
        val choices = options
        for (i in 0 until 9) {
            val down = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_1 + i)
            val fresh = down && !keysDown[i]
            keysDown[i] = down
            if (fresh && Settings.optionKeys && MC.screen == null && i < choices.size) { choose(choices[i]); return }
        }
    }

    private fun choose(option: DialogueOption) {
        when (val click = option.click) {
            is ClickEvent.RunCommand -> PlayerUtils.sendCommand(click.command().removePrefix("/"))
            is ClickEvent.Custom -> MC.instance.connection?.send(ServerboundCustomClickActionPacket(click.id(), click.payload()))
            else -> return
        }
        options = emptyList(); current = null; queue.clear(); until = 0
    }

    /** The NPC standing under the name tag [name], within 10 blocks; null if it is not loaded. */
    private fun findNpc(name: String): LivingEntity? {
        val player = MC.instance.player ?: return null
        val level = MC.instance.level ?: return null
        val box = player.boundingBox.inflate(10.0)
        val tag = level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName?.string?.replace(Regex("§."), "")?.trim() == name }.minByOrNull { it.distanceToSqr(player) } ?: return null
        return level.getEntitiesOfClass(LivingEntity::class.java, tag.boundingBox.inflate(1.0, 3.0, 1.0)) { it !is ArmorStand && it !== player }
            .minByOrNull { it.distanceToSqr(tag) }
    }

    @Subscription
    fun onScreen(event: RenderScreenForegroundEvent) {
        if (!active() || !Settings.inInventories) return
        val screen = event.screen as? AbstractContainerScreen<*> ?: return
        val graphics = event.graphics
        val room = ((screen.width - 176) / 2 - 16).coerceAtLeast(0)
        if (room < 120) return
        draw(graphics, 8, graphics.guiHeight() / 2, centred = false, maxWidth = room)
    }

    /** The box: name tab, optional portrait, the typed line, and the numbered options above it. */
    private fun draw(graphics: GuiGraphicsExtractor, anchorX: Int, anchorY: Int, centred: Boolean, maxWidth: Int) {
        val line = current
        if (line == null && options.isEmpty()) return
        val font = MC.font
        val now = System.currentTimeMillis()
        val animate = ThemeConfig.animationMode != GuiMotion.OFF
        val appear = if (animate) ((now - openedAt) / 160.0).coerceIn(0.0, 1.0) else 1.0
        val eased = 1 - (1 - appear) * (1 - appear)
        val alpha = eased
        val panel = HudTheme.PANEL and 0xFFFFFF; val accent = HudTheme.ACCENT and 0xFFFFFF; val text = HudTheme.TEXT and 0xFFFFFF
        val hasPortrait = portrait != null && line != null
        val width = maxWidth.coerceIn(180, 340)
        val textLeftPad = if (hasPortrait) 58 else 10
        val wrap = width - textLeftPad - 10
        val message = line?.message.orEmpty()
        val revealed = if (animate && line != null) revealedChars(message.length, now - shownAt, until - shownAt) else message.length
        val lines = if (line != null) font.split(Component.literal(message), wrap) else emptyList()
        val height = (lines.size * 10 + 18).coerceAtLeast(if (hasPortrait) 52 else 30)
        val left = if (centred) anchorX - width / 2 else anchorX
        val top = (if (centred) anchorY - height else anchorY - height / 2) + ((1 - eased) * 6).roundToInt()
        fun a(rgb: Int, opacity: Double) = (((opacity * alpha).coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or rgb

        if (line != null) {
            graphics.fill(left, top, left + width, top + height, a(panel, 0.88))
            graphics.fill(left, top, left + width, top + 1, a(accent, 0.9))
            // Typed text: whole lines until the reveal point, then the partial line.
            var remaining = revealed
            lines.forEachIndexed { i, seq ->
                val raw = StringBuilder(); seq.accept { _, _, cp -> raw.appendCodePoint(cp); true }
                val shown = raw.toString().take(remaining.coerceAtLeast(0))
                remaining -= raw.length + 1
                if (shown.isNotEmpty()) graphics.text(font, shown, left + textLeftPad, top + 12 + i * 10, a(text, 1.0), true)
            }
            // Name tab over the top edge.
            val nameWidth = font.width(line.name) + 10
            val tabX = left + if (hasPortrait) 52 else 6
            graphics.fill(tabX, top - 6, tabX + nameWidth, top + 7, a(dark(accent), 0.95))
            graphics.text(font, line.name, tabX + 5, top - 4, a(0xFFFF55, 1.0), true)
            portrait?.takeIf { hasPortrait && it.isAlive }?.let { entity ->
                graphics.enableScissor(left + 2, top - 40, left + 52, top + height - 2)
                InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, left + 2, top - 40, left + 52, top + height - 2, 28, 0.0625f,
                    (left + 120).toFloat(), (top - 20).toFloat(), entity)
                graphics.disableScissor()
            }
        }
        if (options.isNotEmpty() && (line == null || queue.isEmpty())) {
            val labels = options.mapIndexed { i, o -> "${i + 1}. ${o.label}" }
            val w = labels.maxOf { font.width(it) } + 12
            var y = top - 10 - labels.size * 14
            val x = left + width - w
            labels.forEachIndexed { i, label ->
                graphics.fill(x, y, x + w, y + 12, a(panel, 0.9))
                graphics.fill(x, y, x + 1, y + 12, a(accent, 1.0))
                graphics.text(font, "${i + 1}.", x + 5, y + 2, a(0xAAAAAA, 1.0), true)
                graphics.text(font, options[i].label, x + 5 + font.width("${i + 1}. "), y + 2, a(text, 1.0), true)
                y += 14
            }
        }
    }

    private fun dark(rgb: Int) = ((rgb shr 16 and 255) * 45 / 100 shl 16) or ((rgb shr 8 and 255) * 45 / 100 shl 8) or ((rgb and 255) * 45 / 100)

    private const val OPTIONS_MILLIS = 10_000L
}
