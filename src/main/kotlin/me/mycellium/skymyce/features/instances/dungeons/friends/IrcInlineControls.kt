package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.mixin.IrcChatScreenAccessor
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

/** Overlay controls belong to the existing native composer, with no separate reply/reaction screen. */
internal object IrcInlineControls {
    private data class Picker(val screen: ChatScreen, val key: IrcKey, val x: Int, val y: Int)
    private var picker: Picker? = null
    fun open(key: IrcKey, x: Int, y: Int) {
        val screen = MC.screen as? ChatScreen ?: return
        picker = Picker(screen, key, x, y)
    }
    fun clear() { picker = null }
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
                val row = (y - bounds.y - 20) / 20
                val column = ((x - bounds.x) * 3 / bounds.width).coerceIn(0, 2)
                if (y >= bounds.y + 20 && row in 0..1) {
                    val emoji = IRC_REACTIONS[row * 3 + column]
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
        val font = MC.instance.font
        RelayMessages.replyMessage()?.let { message ->
            val cancel = cancelBounds(screen)
            val y = screen.height - 40
            output.fill(2, y, screen.width - 2, y + 18, 0xEF172332.toInt())
            val preview = ircReadableText(message.text.replace('\n', ' ')).joinToString("") { it.text }
                .ifBlank { message.media.firstOrNull()?.name ?: "Media" }
            val from = ircReadableText(message.from).joinToString("") { it.text }
            output.text(font, font.plainSubstrByWidth("Reply to $from: $preview", (cancel.x - 12).coerceAtLeast(1)), 6, y + 5, 0xFFAACBFF.toInt())
            output.text(font, "[Cancel]", cancel.x + 1, y + 5, 0xFF68B9F5.toInt())
            if (cancel.contains(mouseX, mouseY)) output.setTooltipForNextFrame(Component.literal("Cancel this reply; keep your message draft"), mouseX, mouseY)
        }
        val selected = current(screen) ?: return
        val message = RelayMessages.chatStore.get(selected.key) ?: return
        val bounds = ircPickerBounds(selected.x, selected.y, screen.width, screen.height)
        output.fill(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, 0xF0172332.toInt())
        val from = ircReadableText(message.from).joinToString("") { it.text }
        output.text(font, font.plainSubstrByWidth("React · $from", bounds.width - 10), bounds.x + 5, bounds.y + 5, 0xFFAACBFF.toInt())
        IRC_REACTIONS.forEachIndexed { index, emoji ->
            val cell = IrcControlBounds(bounds.x + (index % 3) * bounds.width / 3, bounds.y + 20 + (index / 3) * 20, bounds.width / 3, 20)
            val reaction = message.reactions.firstOrNull { it.emoji == emoji }
            if (cell.contains(mouseX, mouseY)) {
                output.fill(cell.x, cell.y, cell.x + cell.width, cell.y + cell.height, 0xFF293B52.toInt())
                output.setTooltipForNextFrame(Component.literal("${if (reaction?.mine == true) "Remove" else "Add"} ${IRC_REACTION_NAMES[index]} reaction"), mouseX, mouseY)
            }
            val text = "${if (reaction?.mine == true) "* " else ""}${IRC_REACTION_NAMES[index]}${reaction?.count?.takeIf { it > 0 }?.let { " $it" }.orEmpty()}"
            output.text(font, font.plainSubstrByWidth(text, cell.width - 8), cell.x + 4, cell.y + 5,
                if (reaction?.mine == true) 0xFF68B9F5.toInt() else 0xFFDCE5F0.toInt())
        }
    }
    fun focusComposer(screen: ChatScreen) {
        val input = (screen as IrcChatScreenAccessor).`skymyce$getInput`()
        screen.setFocused(input)
        input.setFocused(true)
    }
}
