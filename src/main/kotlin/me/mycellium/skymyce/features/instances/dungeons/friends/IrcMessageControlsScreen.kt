package me.mycellium.skymyce.features.instances.dungeons.friends

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuButtonIntent
import me.mycellium.skymyce.ui.MenuLabel
import me.mycellium.skymyce.ui.MenuScreen
import me.mycellium.skymyce.ui.MenuTextBox
import me.mycellium.skymyce.ui.MenuTheme
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Own-message editing only; Reply and React remain directly inside native chat. */
internal class IrcMessageControlsScreen(private val previous: Screen?, private val key: IrcKey) : MenuScreen(Component.literal("Edit your IRC message")) {
    internal val scope get() = key.scope
    override val modal = true
    override val menuParent get() = previous
    private lateinit var edit: MenuTextBox
    private lateinit var save: MenuButton
    private lateinit var delete: MenuButton
    private lateinit var feedback: MenuLabel
    private var confirmDelete = false
    private var requestAfter = 0L
    private var seenRevision = -1
    private var status = ""

    override fun buildContent(root: FlowLayout) {
        confirmDelete = false
        val draft = if (::edit.isInitialized) edit.value else null
        val message = RelayMessages.chatStore.get(key)
        root.padding(Insets.of(16)); root.gap(8)
        root.child(label("EDIT MESSAGE", MenuTheme.ACCENT))
        root.child(label("${ircPrefix(key.scope)} ${message?.from.orEmpty()}", MenuTheme.MUTED))
        edit = MenuTextBox(Sizing.fill()).apply { verticalSizing(Sizing.fixed(24)); setHint(Component.literal("Edit your message")) }
        root.child(edit)
        val maximum = if (key.scope == "room") 256 else 1024
        edit.setMaxLength(maximum)
        edit.value = draft ?: message?.text?.replace('\n', ' ').orEmpty().take(maximum)
        edit.setEditable(RelayMessages.canManage(key))
        save = button("Save edit") {
            if (RelayMessages.operation(key, "edit", text = edit.value)) queued("Edit requested; waiting for chat update.")
        }.apply { horizontalSizing(Sizing.fill(48)); intent = MenuButtonIntent.PRIMARY }
        delete = button("Delete") {
            if (!confirmDelete) { confirmDelete = true; delete.message = Component.literal("Confirm delete") }
            else if (RelayMessages.operation(key, "delete")) queued("Deletion requested; waiting for chat update.")
        }.apply { horizontalSizing(Sizing.fill(48)); intent = MenuButtonIntent.DESTRUCTIVE }
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).gap(6).apply { child(save); child(delete) })
        root.child(label("Own-message edits and deletion are available for 15 minutes.", MenuTheme.MUTED))
        feedback = label(status, MenuTheme.MUTED); root.child(feedback)
        root.child(button("Back to chat") { onClose() })
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
        if (feedback.text().string != status) feedback.text(Component.literal(status))
    }
    override fun tick() { super.tick(); if (::edit.isInitialized && !menuClosing) updateControls() }
    override fun onClose() { closeMenuTo(if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
