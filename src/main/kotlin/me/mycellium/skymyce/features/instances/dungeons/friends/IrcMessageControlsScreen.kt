package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Own-message editing only; Reply and React remain directly inside native chat. */
internal class IrcMessageControlsScreen(private val previous: Screen?, private val key: IrcKey) : Screen(Component.literal("Edit your IRC message")) {
    internal val scope get() = key.scope
    private lateinit var edit: EditBox
    private lateinit var save: Button
    private lateinit var delete: Button
    private var confirmDelete = false
    private var requestAfter = 0L
    private var seenRevision = -1
    private var status = ""

    override fun init() {
        confirmDelete = false
        val draft = if (::edit.isInitialized) edit.value else null
        val message = RelayMessages.chatStore.get(key)
        val panelWidth = minOf(300, width - 24).coerceAtLeast(120)
        val left = (width - panelWidth) / 2
        val top = (height / 2 - 66).coerceAtLeast(54)
        fun button(label: String, x: Int, y: Int, size: Int, action: () -> Unit): Button =
            addRenderableWidget(Button.builder(Component.literal(label)) { action() }.bounds(x, y, size, 20).build())
        edit = addRenderableWidget(EditBox(font, left, top, panelWidth, 20, Component.literal("Edit your message")))
        val maximum = if (key.scope == "room") 256 else 1024
        edit.setMaxLength(maximum)
        edit.value = draft ?: message?.text?.replace('\n', ' ').orEmpty().take(maximum)
        edit.setEditable(RelayMessages.canManage(key))
        val half = (panelWidth - 4) / 2
        save = button("Save edit", left, top + 24, half) {
            if (RelayMessages.operation(key, "edit", text = edit.value)) queued("Edit requested; waiting for chat update.")
        }
        delete = button("Delete", left + half + 4, top + 24, half) {
            if (!confirmDelete) { confirmDelete = true; delete.message = Component.literal("Confirm delete") }
            else if (RelayMessages.operation(key, "delete")) queued("Deletion requested; waiting for chat update.")
        }
        button("Back to chat", left, top + 54, panelWidth) { onClose() }
        updateControls()
    }
    private fun queued(text: String) {
        // A queued operation is not a Discord HTTP delivery receipt. Never optimistically alter the message.
        status = text; requestAfter = System.nanoTime() + 1_000_000_000L
    }
    private fun updateControls() {
        val message = RelayMessages.chatStore.get(key)
        val available = message != null && !message.deleted && message.model == 1 && RelayMessages.currentIrcChannel(key) && DungeonFriendRelay.richChatAvailable
        val waiting = System.nanoTime() < requestAfter
        val own = available && RelayMessages.canManage(key)
        save.active = own && !waiting && ircClientMessageError(key.scope, edit.value) == null
        delete.active = own && !waiting
        edit.setEditable(own)
        if (message != null && seenRevision >= 0 && message.revision != seenRevision) {
            status = if (message.deleted) "Message deleted." else "Chat updated."
            requestAfter = 0
        }
        seenRevision = message?.revision ?: -1
        if (!available) status = "This message is no longer available. Return to chat."
    }
    override fun tick() { super.tick(); updateControls() }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        output.fill(0, 0, width, height, 0xEA111722.toInt())
        val message = RelayMessages.chatStore.get(key)
        output.centeredText(font, "${ircPrefix(key.scope)} ${message?.from.orEmpty()}", width / 2, 17, 0xFF83BBFF.toInt())
        val preview = font.plainSubstrByWidth(message?.text?.replace('\n', ' ').orEmpty(), width - 32)
        output.centeredText(font, preview, width / 2, 33, 0xFFCED8E6.toInt())
        output.centeredText(font, "Own-message edits and deletion are available for 15 minutes.", width / 2, height - 30, 0xFF919EAF.toInt())
        if (status.isNotEmpty()) output.centeredText(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - 17, 0xFFAFBDD0.toInt())
        super.extractRenderState(output, mouseX, mouseY, partialTicks)
    }
    override fun onClose() { minecraft.setScreen(if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous) }
    override fun isPauseScreen() = false
}
