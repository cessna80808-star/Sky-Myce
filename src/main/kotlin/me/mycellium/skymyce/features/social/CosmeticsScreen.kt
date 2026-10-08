package me.mycellium.skymyce.features.social

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.CosmeticsConfig
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

class CosmeticsScreen(private val link: Boolean = false, focusPath: String? = null) : MenuScreen(Component.literal("Sky-Hawk Cosmetics"), MenuPage.COSMETICS) {
    private lateinit var content: FlowLayout
    private lateinit var settingsScroll: MenuScroll<FlowLayout>
    private val settingControls = HashMap<String, MenuButton>()
    private var pendingSetting = focusPath
    override val menuCategory get() = MenuCategory.COSMETICS
    private var started = false
    private var seen = -1L
    private var status = ""
    private var lastSecond = -1L
    private var dirty = false
    private var expiry: io.wispforest.owo.ui.component.LabelComponent? = null
    private lateinit var statusLabel: io.wispforest.owo.ui.component.LabelComponent
    override fun isPauseScreen() = false
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(MenuTheme.PADDING + 3)) }
        settingsScroll = menuScroll(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight), content).apply {
            surface(MenuTheme.panel()); scrollbarThiccness(2); scrollStep(20)
        }
        root.child(settingsScroll)
        update()
        if (!started) { started = true; CosmeticsClient.refreshOwn(); if (link) CosmeticsClient.requestLink() }
    }
    override fun tick() {
        super.tick()
        if (menuClosing) return
        val second = System.currentTimeMillis() / 1000
        if (seen != CosmeticsClient.revision) update(animate = false)
        else if (status != CosmeticsClient.status) {
            status = CosmeticsClient.status
            statusLabel.text(Component.literal(status).withColor(MenuTheme.MUTED))
        }
        if (second != lastSecond) {
            lastSecond = second
            expiry?.text(Component.literal(expiryText()).withColor(MenuTheme.MUTED))
        }
        pendingSetting?.let { path ->
            val control = settingControls[path] ?: return@let
            val handler = control.focusHandler() ?: return@let
            settingsScroll.scrollTo(control); settingsScroll.scrollBy(0.0, true, false)
            handler.focus(control, UIComponent.FocusSource.KEYBOARD_CYCLE)
            pendingSetting = null
        }
    }
    private fun update(animate: Boolean = true) {
        if (!::content.isInitialized) return
        if (animate) contentChanged()
        seen = CosmeticsClient.revision; status = CosmeticsClient.status
        content.clearChildren()
        settingControls.clear()
        expiry = null
        content.child(label("SKY-HAWK / COSMETICS", MenuTheme.ACCENT))
        content.child(label("Real identity: ${MC.instance.user.name}", MenuTheme.TEXT))
        val own = CosmeticsClient.own()
        content.child(label("Server name: ${own?.name ?: if (own == null) "Not synchronized" else "Real IGN"}"))
        content.child(label("Size X: ${own?.scaleX ?: "?"} • Y: ${own?.scaleY ?: "?"} • Z: ${own?.scaleZ ?: "?"}"))
        content.child(label("Revision: ${own?.revision ?: "—"}", MenuTheme.MUTED))
        statusLabel = label(status, MenuTheme.MUTED)
        content.child(statusLabel)
        content.child(button("Refresh my settings") { CosmeticsClient.refreshOwn() }.wrappedTooltip(Component.literal("Read your public settings through the authenticated relay. Does not query friends or Hypixel APIs.")))
        content.child(toggle("Cosmetics/names", "Show Cosmetic Names", { CosmeticsConfig.names }) { CosmeticsConfig.names = !CosmeticsConfig.names; preferenceChanged("Cosmetic names", CosmeticsConfig.names) })
        content.child(toggle("Cosmetics/originalTooltips", "Original tooltip names", { CosmeticsConfig.originalTooltips }) { CosmeticsConfig.originalTooltips = !CosmeticsConfig.originalTooltips; preferenceChanged("Original tooltip names", CosmeticsConfig.originalTooltips) })
        content.child(toggle("Cosmetics/scaling", "Player scaling", { CosmeticsConfig.scaling }) { CosmeticsConfig.scaling = !CosmeticsConfig.scaling; preferenceChanged("Player scaling", CosmeticsConfig.scaling) })
        content.child(label("Names replace complete known usernames only in displayed text. Commands and gameplay use real identities. Size is a third-person visual effect only.", MenuTheme.MUTED))
        content.child(button("Link my Discord account") { CosmeticsClient.requestLink() }.wrappedTooltip(Component.literal("Request a single-use code tied to your authenticated Minecraft UUID. Codes expire in five minutes.")))
        CosmeticsClient.linkCode?.let { code ->
            content.child(label("Link code: $code", MenuTheme.SECONDARY))
            expiry = label(expiryText(), MenuTheme.MUTED); content.child(expiry!!)
            content.child(button("Copy Discord link command") { MC.instance.keyboardHandler.clipboard = "/cosmetics link code:$code" }
                .wrappedTooltip(Component.literal("Use in bot-commands, channel 1552057526969835612. Keep this one-time ownership code private.")))
        }
        content.child(label("After linking, use /cosmetics set, /cosmetics show or /cosmetics reset in Discord. The relay owns these settings; changing a local file cannot change another player's profile.", MenuTheme.MUTED))
        content.child(button("Done") { onClose() })
    }
    override fun removed() {
        if (dirty) Scheduling.schedule(0.milliseconds) { runCatching { SkyMyce.config.save() }.onFailure { SkyMyce.logger.warn("Could not save cosmetic preferences") } }
        super.removed()
    }
    private fun preferenceChanged(name: String, enabled: Boolean) {
        dirty = true; showMenuToast("$name ${if (enabled) "enabled" else "disabled"}", MenuToastKind.SUCCESS)
    }
    private fun expiryText() = "Expires in ${((CosmeticsClient.linkExpires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} seconds"
    private fun toggle(path: String, text: String, state: () -> Boolean, action: () -> Unit) = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(8); verticalAlignment(VerticalAlignment.CENTER)
        child(label(text).horizontalSizing(Sizing.expand()))
        child(MenuButton(Component.literal(text)) { action() }.apply {
            toggled = state; sizing(Sizing.fixed(32), Sizing.fixed(22)); wrappedTooltip(Component.literal(text))
            settingControls[path] = this
        })
    }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(CosmeticNames.original(Component.literal(text).withColor(color))).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.themed().apply { sizing(Sizing.fill(), Sizing.fixed(20)) }
}
