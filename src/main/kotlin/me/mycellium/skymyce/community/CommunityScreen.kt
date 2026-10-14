package me.mycellium.skymyce.community

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** State-specific identity actions, independent sharing settings, and explicit update consent. */
class CommunityScreen(private val parent: Screen? = null) : MenuScreen(Component.literal("Sky-Myce Community")) {
    override val menuParent get() = parent ?: super.menuParent
    private lateinit var status: MenuLabel
    private lateinit var heading: MenuLabel
    private lateinit var account: MenuLabel
    private lateinit var autoUpdate: MenuButton
    private lateinit var connectionActions: FlowLayout
    private lateinit var fallback: FlowLayout
    private lateinit var update: MenuLabel
    private lateinit var notes: FlowLayout
    private lateinit var check: MenuButton
    private lateinit var install: MenuButton
    private lateinit var later: MenuButton
    private lateinit var restart: MenuButton
    private lateinit var noteButton: MenuButton
    private val actionButtons = mutableMapOf<CommunityConnectionAction, MenuButton>()
    private var actionKey: Pair<CommunityConnectionKind, List<CommunityConnectionAction>>? = null
    private var fallbackCode: String? = null
    private var shownNotes = ""
    private var notesOpen = false
    override val canonicalFeatureId get() = "community"

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COMMUNITY", MenuTheme.ACCENT))
        root.child(label("Discord connects chat, cosmetics and shared progress. Local features work offline.", MenuTheme.MUTED))
        val content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(10) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), content).apply { scrollStep(20); scrollbarThiccness(2) })
        val connection = card(); content.child(connection)
        heading = label("DISCORD", MenuTheme.SECONDARY); connection.child(heading)
        status = label(CommunityClient.status); connection.child(status)
        account = label("", MenuTheme.MUTED); connection.child(account)
        connectionActions = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6) }; connection.child(connectionActions)
        fallback = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }; connection.child(fallback)

        val services = card(); content.child(services)
        services.child(label("CHAT & COSMETICS", MenuTheme.SECONDARY))
        services.child(label("Global Chat uses your connected identity. Local cosmetic choices work offline; Publish shares them with compatible clients.", MenuTheme.MUTED))
        services.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            child(button("Open Global Chat") { openMenuFeature("global-chat") }.horizontalSizing(Sizing.fill(48)))
            child(button("Player cosmetics") { openMenuFeature("cosmetics") }.horizontalSizing(Sizing.fill(48)))
        })
        content.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Sharing is on by default; your opt-outs are kept.", MenuTheme.MUTED).horizontalSizing(Sizing.expand()))
            child(button("Sharing settings") { navigateMenu(SettingsScreen.forFeature(this@CommunityScreen, "community")) }.horizontalSizing(Sizing.fixed(125)))
        })
        val updates = card(); content.child(updates)
        updates.child(label("UPDATES • installed ${CommunityClient.version}", MenuTheme.SECONDARY))
        update = label(CommunityUpdates.status); updates.child(update)
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Auto-update", MenuTheme.TEXT).horizontalSizing(Sizing.expand()))
            autoUpdate = button(if (CommunityUpdates.autoUpdate) "On" else "Off") { CommunityUpdates.setAutoUpdate(!CommunityUpdates.autoUpdate) }.apply {
                horizontalSizing(Sizing.fixed(64)); toggled = { CommunityUpdates.autoUpdate }
                tooltip(Component.literal("On downloads verified releases and installs after exit. Off leaves installation to Update Now."))
            }; child(autoUpdate)
        })
        updates.child(label("On: download verified releases and install after exit. Off: choose Update Now. Later skips that release. Your current JAR is backed up.", MenuTheme.MUTED))
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            check = button("Check updates") { CommunityUpdates.check(manual = true) }.apply { horizontalSizing(Sizing.fill(31)) }; child(check)
            install = button("Update Now") { CommunityUpdates.install() }.apply { horizontalSizing(Sizing.fill(31)); intent = MenuButtonIntent.PRIMARY }; child(install)
            later = button("Later") { CommunityUpdates.defer() }.apply { horizontalSizing(Sizing.fill(31)) }; child(later)
        })
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            noteButton = button("Release notes") { notesOpen = !notesOpen; updateNotes() }.apply { horizontalSizing(Sizing.fill(48)) }; child(noteButton)
            restart = button("Restart Minecraft") { CommunityUpdates.restart() }.apply {
                horizontalSizing(Sizing.fill(48))
                tooltip(Component.literal("Closes and relaunches Minecraft. A ready update is installed first. Save your work before restarting."))
            }; child(restart)
        })
        notes = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()); updates.child(notes)
        root.child(button("Done") { onClose() })
        actionKey = null; fallbackCode = null; shownNotes = ""; refresh()
    }

    override fun tick() { super.tick(); if (::status.isInitialized && !menuClosing) refresh() }

    private fun refresh() {
        val view = CommunityConnectionView(CommunityClient.connected, CommunityClient.hasSavedSession,
            CommunityClient.connectionPending, CommunityClient.cancellationPending, CommunityClient.busy, CommunityClient.browserUrl != null)
        val key = view.kind to view.actions
        if (key != actionKey) {
            actionKey = key; actionButtons.clear(); connectionActions.clearChildren()
            view.actions.forEach { action ->
                val title = when (action) {
                    CommunityConnectionAction.CONNECT -> "Connect Discord"
                    CommunityConnectionAction.RECONNECT -> "Reconnect saved session"
                    CommunityConnectionAction.BROWSER -> "Open Discord in browser"
                    CommunityConnectionAction.CANCEL -> if (view.cancelling) "Retry cancellation" else "Cancel connection"
                    CommunityConnectionAction.DISCONNECT -> "Disconnect this session"
                }
                val component = button(title) { when (action) {
                    CommunityConnectionAction.CONNECT -> CommunityClient.connect()
                    CommunityConnectionAction.RECONNECT -> CommunityClient.retry()
                    CommunityConnectionAction.BROWSER -> CommunityClient.openBrowser()
                    CommunityConnectionAction.CANCEL -> CommunityClient.cancelConnect()
                    CommunityConnectionAction.DISCONNECT -> CommunityClient.disconnect()
                } }.apply { horizontalSizing(if (view.actions.size == 1) Sizing.fill() else Sizing.fill(96 / view.actions.size)) }
                component.intent = when (action) {
                    CommunityConnectionAction.CONNECT, CommunityConnectionAction.RECONNECT, CommunityConnectionAction.BROWSER -> MenuButtonIntent.PRIMARY
                    CommunityConnectionAction.DISCONNECT -> MenuButtonIntent.DESTRUCTIVE
                    CommunityConnectionAction.CANCEL -> MenuButtonIntent.SECONDARY
                }
                actionButtons[action] = component; connectionActions.child(component)
            }
        }
        actionButtons.forEach { (action, button) -> button.active = view.enabled(action) }
        heading.text(Component.literal(when (view.kind) {
            CommunityConnectionKind.DISCONNECTED -> "DISCORD • not connected"
            CommunityConnectionKind.SAVED -> "DISCORD • saved session"
            CommunityConnectionKind.CONNECTED -> "DISCORD • connected"
            CommunityConnectionKind.PENDING -> "FINISH CONNECTING"
            CommunityConnectionKind.CANCELLING -> "PENDING CONNECTION • cancellation"
        }).withColor(MenuTheme.SECONDARY))
        status.text(Component.literal(CommunityClient.status))
        account.text(Component.literal(if (CommunityClient.connected) {
            val identity = CommunityClient.identity
            val name = identity?.get("displayName")?.asString ?: identity?.get("username")?.asString ?: "Discord user"
            "Discord: $name • Minecraft: ${MC.instance.user.name}"
        } else "Connecting is optional. Saved credentials stay on this installation."))
        val code = CommunityClient.fallbackCode.takeIf { view.kind == CommunityConnectionKind.PENDING }
        if (code != fallbackCode) {
            fallbackCode = code; fallback.clearChildren()
            if (code != null) {
                fallback.child(label("Browser unavailable? Use Mousebot in the Sky-Myce Discord:", MenuTheme.MUTED))
                fallback.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                    gap(6); verticalAlignment(VerticalAlignment.CENTER)
                    child(label("/connect code:$code", MenuTheme.ACCENT).horizontalSizing(Sizing.expand()))
                    child(button("Copy command") { MC.instance.keyboardHandler.clipboard = "/connect code:$code"; showMenuToast("Connection command copied", MenuToastKind.SUCCESS) }.horizontalSizing(Sizing.fixed(112)))
                })
                fallback.child(label("Private, one-use code • expires within ten minutes. Cancel requests server revocation.", MenuTheme.MUTED))
            }
        }
        check.active = CommunityUpdates.canCheck
        install.active = CommunityUpdates.canInstall; later.active = CommunityUpdates.canDefer
        restart.active = CommunityUpdates.canRestart
        update.text(Component.literal(CommunityUpdates.status))
        autoUpdate.message = Component.literal(if (CommunityUpdates.autoUpdate) "On" else "Off")
        autoUpdate.active = CommunityUpdates.canChangePreferences
        noteButton.active = CommunityUpdates.releaseNotes.isNotBlank()
        if (CommunityUpdates.releaseNotes != shownNotes) updateNotes()
    }

    private fun updateNotes() {
        shownNotes = CommunityUpdates.releaseNotes; notes.clearChildren()
        noteButton.message = Component.literal(if (notesOpen) "Hide release notes" else "Release notes")
        if (notesOpen && shownNotes.isNotBlank()) notes.child(label(shownNotes, MenuTheme.MUTED))
    }

    private fun card() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); padding(Insets.of(10)); surface(MenuTheme.panel()) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
