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

class CosmeticsScreen(private val link: Boolean = false) : MenuScreen(Component.literal("Sky-Hawk Cosmetics"), MenuPage.COSMETICS) {
    private lateinit var content: FlowLayout
    private var started = false
    private var seen = -1L
    private var status = ""
    private var lastSecond = -1L
    private var dirty = false
    private var expiry: io.wispforest.owo.ui.component.LabelComponent? = null
    override fun isPauseScreen() = false
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(MenuTheme.PADDING + 3)) }
        root.child(UIContainers.verticalScroll(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight), content).apply {
            surface(MenuTheme.panel()); scrollbarThiccness(2); scrollStep(20)
        })
        update()
        if (!started) { started = true; CosmeticsClient.refreshOwn(); if (link) CosmeticsClient.requestLink() }
    }
    override fun tick() {
        super.tick()
        val second = System.currentTimeMillis() / 1000
        if (seen != CosmeticsClient.revision || status != CosmeticsClient.status) update()
        if (second != lastSecond) {
            lastSecond = second
            expiry?.text(Component.literal(expiryText()).withColor(MenuTheme.MUTED))
        }
    }
    private fun update() {
        if (!::content.isInitialized) return
        seen = CosmeticsClient.revision; status = CosmeticsClient.status
        content.clearChildren()
        expiry = null
        content.child(label("SKY-HAWK / COSMETICS", MenuTheme.ACCENT))
        content.child(label("Real identity: ${MC.instance.user.name}", MenuTheme.TEXT))
        val own = CosmeticsClient.own()
        content.child(label("Server name: ${own?.name ?: if (own == null) "Not synchronized" else "Real IGN"}"))
        content.child(label("Size X: ${own?.scaleX ?: "?"} • Y: ${own?.scaleY ?: "?"} • Z: ${own?.scaleZ ?: "?"}"))
        content.child(label("Revision: ${own?.revision ?: "—"}", MenuTheme.MUTED))
        content.child(label(status, MenuTheme.MUTED))
        content.child(button("Refresh my settings") { CosmeticsClient.refreshOwn() }.wrappedTooltip(Component.literal("Read your public settings through the authenticated relay. Does not query friends or Hypixel APIs.")))
        content.child(button("Show Cosmetic Names: ${if (CosmeticsConfig.names) "On" else "Off"}") { CosmeticsConfig.names = !CosmeticsConfig.names; dirty = true; update() })
        content.child(button("Original tooltip names: ${if (CosmeticsConfig.originalTooltips) "On" else "Off"}") { CosmeticsConfig.originalTooltips = !CosmeticsConfig.originalTooltips; dirty = true; update() })
        content.child(button("Player scaling: ${if (CosmeticsConfig.scaling) "On" else "Off"}") { CosmeticsConfig.scaling = !CosmeticsConfig.scaling; dirty = true; update() })
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
    private fun expiryText() = "Expires in ${((CosmeticsClient.linkExpires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} seconds"
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(CosmeticNames.original(Component.literal(text).withColor(color))).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.themed().apply { sizing(Sizing.fill(), Sizing.fixed(20)) }
}
