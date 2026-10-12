package me.mycellium.skymyce.community

import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.ui.MenuScreen
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuLabel
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.ui.menuScroll
import net.minecraft.network.chat.Component

/** One place for Discord identity, sharing controls and updates; local gameplay always stays available. */
class CommunityScreen : MenuScreen(Component.literal("Sky-Myce Community")) {
    private lateinit var status: LabelComponent
    private lateinit var fallback: LabelComponent
    private lateinit var update: LabelComponent
    private lateinit var notes: LabelComponent
    private var displayed = emptyList<Any?>()
    override val canonicalFeatureId get() = "community"
    override fun buildContent(root: FlowLayout) {
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER).surface(Surface.BLANK)
        val content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(8); padding(Insets.of(12)) }
        root.child(menuScroll(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight), content))
        fun label(text: String) = MenuLabel(Component.literal(text)).apply { horizontalSizing(Sizing.fill()); color(Color.ofRgb(MenuTheme.TEXT)) }
        fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
        content.child(label("SKY-MYCE COMMUNITY"))
        status = label(CommunityClient.status); content.child(status)
        content.child(label("Connect Discord for chat, cosmetics and community progress. Minecraft and local Sky-Myce features work while disconnected or offline."))
        content.child(button("Connect Discord") { CommunityClient.connect() })
        content.child(button("Open connection in browser") { CommunityClient.openBrowser() })
        fallback = label(""); content.child(fallback)
        content.child(button("Cancel pending connection") { CommunityClient.cancelConnect() })
        content.child(button("Reconnect saved session") { CommunityClient.retry() })
        content.child(button("Disconnect this Minecraft session") { CommunityClient.disconnect() })
        content.child(label("Community statistics are shared by default. Your explicit opt-outs are kept. Sharing controls apply to statistics, progress and feeds; chat and cosmetics stay connected."))
        content.child(button("Community Sharing settings") { navigateMenu(SettingsScreen.forFeature(this, "community")) })
        content.child(label("UPDATES"))
        update = label(CommunityUpdates.status); content.child(update)
        notes = label(""); content.child(notes)
        content.child(label("Update Now downloads a verified update and installs it after you exit Minecraft. Your current JAR is backed up. Later keeps this version working."))
        content.child(button("Check for updates") { CommunityUpdates.check(manual = true) })
        content.child(button("Update Now") { CommunityUpdates.install() })
        content.child(button("Later") { CommunityUpdates.defer() })
        content.child(button("Done") { onClose() })
        refresh()
    }
    override fun tick() { super.tick(); if (::status.isInitialized) refresh() }
    private fun refresh() {
        val state = listOf(CommunityClient.status, CommunityClient.fallbackCode, CommunityUpdates.status, CommunityUpdates.releaseNotes)
        if (state == displayed) return
        displayed = state
        status.text(Component.literal(CommunityClient.status))
        fallback.text(Component.literal(CommunityClient.fallbackCode?.let { "Browser unavailable? In Mousebot, use /connect code:$it. This code expires shortly and works once." } ?: ""))
        update.text(Component.literal(CommunityUpdates.status))
        notes.text(Component.literal(CommunityUpdates.releaseNotes))
    }
}
