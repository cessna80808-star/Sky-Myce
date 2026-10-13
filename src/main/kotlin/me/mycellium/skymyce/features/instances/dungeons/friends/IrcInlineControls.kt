package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component

internal data class IrcControlBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Int, py: Int) = px >= x && py >= y && px < x + width && py < y + height
}
internal fun ircPickerBounds(x: Int, y: Int, width: Int, height: Int): IrcControlBounds {
    val size = minOf(246, (width - 8).coerceAtLeast(1))
    val maxX = (width - size - 4).coerceAtLeast(0)
    return IrcControlBounds(x.coerceIn(minOf(4, maxX), maxX),
        (y - 68).coerceIn(0, (height - 96).coerceAtLeast(0)), size, 64)
}
internal fun ircReactionCell(bounds: IrcControlBounds, index: Int): IrcControlBounds {
    require(index in IRC_REACTIONS.indices)
    val column = index % 3
    val left = bounds.x + column * bounds.width / 3
    val right = bounds.x + (column + 1) * bounds.width / 3
    return IrcControlBounds(left, bounds.y + 20 + index / 3 * 20, right - left, 20)
}
internal fun ircReactionAt(bounds: IrcControlBounds, x: Int, y: Int): Int? =
    IRC_REACTIONS.indices.firstOrNull { ircReactionCell(bounds, it).contains(x, y) }

/** Overlay controls belong to the existing native composer, with no separate reply/reaction screen. */
internal object IrcInlineControls {
    private data class Picker(val screen: ChatScreen, val key: IrcKey, val x: Int, val y: Int)
    private var picker: Picker? = null
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    fun open(key: IrcKey, x: Int, y: Int) {
        val screen = MC.screen as? ChatScreen ?: return
        picker = Picker(screen, key, x, y)
    }
    fun clear() { picker = null; canvas.close(); texture.close() }
    fun escape(): Boolean = if (picker != null) { clear(); true } else false
    private fun current(screen: ChatScreen): Picker? = picker?.takeIf {
        it.screen === screen && RelayMessages.currentIrcChannel(it.key) &&
            RelayMessages.chatStore.get(it.key)?.deleted == false && DungeonFriendRelay.richChatAvailable
    }.also { if (it == null) picker = null }
    private fun cancelBounds(screen: ChatScreen) = IrcControlBounds((screen.width - 54).coerceAtLeast(4), screen.height - 40, 50, 18)
    fun hasInteractionAt(screen: ChatScreen, x: Int, y: Int): Boolean =
        current(screen)?.let { ircPickerBounds(it.x, it.y, screen.width, screen.height).contains(x, y) } == true ||
            RelayMessages.replyMessage() != null && cancelBounds(screen).contains(x, y)
    fun click(screen: ChatScreen, x: Int, y: Int): Boolean {
        val selected = current(screen)
        if (selected != null) {
            val bounds = ircPickerBounds(selected.x, selected.y, screen.width, screen.height)
            if (bounds.contains(x, y)) {
                ircReactionAt(bounds, x, y)?.let { index ->
                    val emoji = IRC_REACTIONS[index]
                    val message = RelayMessages.chatStore.get(selected.key) ?: return true
                    val mine = message.reactions.firstOrNull { it.emoji == emoji }?.mine == true
                    if (RelayMessages.operation(selected.key, "reaction", emoji = emoji, active = !mine)) clear()
                }
                return true
            }
            clear() // A click outside closes the picker and retains ordinary native chat behavior.
        }
        if (RelayMessages.replyMessage() != null && cancelBounds(screen).contains(x, y)) {
            RelayMessages.cancelReply(); return true
        }
        return false
    }
    fun render(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val selected = current(screen)
        val reply = RelayMessages.replyMessage()
        if (selected == null && reply == null) { canvas.close(); texture.close(); return }
        if (texture.prepareFrame()) canvas.close()
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX, mouseY, screen.width, screen.height)
        val font = MC.instance.font
        reply?.let { message ->
            val cancel = cancelBounds(screen)
            val y = screen.height - 40
            MenuTheme.box(graphics, 2, y, screen.width - 4, 18, MenuTheme.SIDEBAR)
            val preview = ircReadableText(message.text.replace('\n', ' ')).joinToString("") { it.text }
                .ifBlank { message.media.firstOrNull()?.name ?: "Media" }
            val from = ircReadableText(message.from).joinToString("") { it.text }
            graphics.text(font, fit("Reply to $from: $preview", cancel.x - 12), 7, y + 4, MenuTheme.alpha(MenuTheme.TEXT), false)
            if (cancel.contains(mouseX, mouseY)) MenuTheme.rounded(graphics, cancel.x, cancel.y, cancel.width, cancel.height, MenuTheme.CARD)
            graphics.text(font, "Cancel", cancel.x + 7, y + 4, MenuTheme.alpha(MenuTheme.ACCENT), false)
            if (cancel.contains(mouseX, mouseY)) output.setTooltipForNextFrame(Component.literal("Cancel this reply; keep your message draft"), mouseX, mouseY)
        }
        selected?.let { picker ->
            RelayMessages.chatStore.get(picker.key)?.let { message ->
                val bounds = ircPickerBounds(picker.x, picker.y, screen.width, screen.height)
                MenuTheme.box(graphics, bounds.x, bounds.y, bounds.width, bounds.height, MenuTheme.SIDEBAR)
                val from = ircReadableText(message.from).joinToString("") { it.text }
                graphics.text(font, fit("React · $from", bounds.width - 12), bounds.x + 6, bounds.y + 4, MenuTheme.alpha(MenuTheme.MUTED), false)
                IRC_REACTIONS.forEachIndexed { index, emoji ->
                    val cell = ircReactionCell(bounds, index)
                    val reaction = message.reactions.firstOrNull { it.emoji == emoji }
                    if (cell.contains(mouseX, mouseY)) {
                        MenuTheme.rounded(graphics, cell.x + 1, cell.y, cell.width - 2, cell.height, MenuTheme.CARD, 3)
                        output.setTooltipForNextFrame(Component.literal("${if (reaction?.mine == true) "Remove" else "Add"} ${IRC_REACTION_NAMES[index]} reaction"), mouseX, mouseY)
                    }
                    val text = "${if (reaction?.mine == true) "✓ " else ""}${IRC_REACTION_NAMES[index]}${reaction?.count?.takeIf { it > 0 }?.let { " $it" }.orEmpty()}"
                    graphics.text(font, fit(text, cell.width - 12), cell.x + 6, cell.y + 4,
                        MenuTheme.alpha(if (reaction?.mine == true) MenuTheme.ACCENT else MenuTheme.TEXT), false)
                }
            }
        }
        output.nextStratum()
        val scale = minOf(ThemeConfig.graphicsQuality.rasterScale, 2560.0 / screen.width.coerceAtLeast(1), 1440.0 / screen.height.coerceAtLeast(1))
        texture.render(output, canvas, screen.width, screen.height, scale)
    }
    private fun fit(text: String, width: Int): String {
        var end = 0
        var measured = 0.0
        while (end < text.length) {
            val codepoint = text.codePointAt(end)
            measured += MenuCanvas.textWidth(codepoint)
            if (measured > width.coerceAtLeast(1)) break
            end += Character.charCount(codepoint)
        }
        return text.substring(0, end)
    }
    fun focusComposer(screen: ChatScreen) {
        val input = (screen as IrcChatScreenAccessor).`skymyce$getInput`()
        screen.setFocused(input)
        input.setFocused(true)
    }
}
