package me.mycellium.skymyce.loader

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

/** Bootstrap UI must also work before the protected mod and its theme configuration exist. */
class AuthorizationScreen : BaseOwoScreen<FlowLayout>(Component.literal("Sky-Myce Loader")) {
    private class ActivationField : TextBoxComponent(Sizing.fill()) {
        init {
            setMaxLength(256)
            setHint(Component.literal("Paste the code from your Discord DM"))
            addFormatter { text, _ -> FormattedCharSequence.forward("•".repeat(text.length), Style.EMPTY) }
        }
        override fun createNarrationMessage() = Component.literal("Activation code, hidden")
    }

    private lateinit var activation: ActivationField
    private lateinit var summary: LabelComponent
    private lateinit var status: LabelComponent
    private lateinit var activate: ButtonComponent
    private lateinit var retry: ButtonComponent
    private lateinit var update: ButtonComponent
    private lateinit var quit: ButtonComponent
    private lateinit var restartHint: LabelComponent
    private lateinit var done: ButtonComponent
    private lateinit var steps: FlowLayout
    private lateinit var content: FlowLayout
    private lateinit var viewport: ScrollContainer<FlowLayout>
    private var stepsShown = true
    private var displayedState: List<Any?> = emptyList()

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)
    override fun isPauseScreen() = false
    override fun init() {
        super.init()
        if (::viewport.isInitialized) viewport.horizontalSizing(Sizing.fixed((width - 16).coerceIn(120, 500)))
    }
    override fun build(root: FlowLayout) {
        stepsShown = true
        displayedState = emptyList()
        root.surface(Surface { g, c -> g.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), 0xBB090D12.toInt()) })
            .alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(8); padding(Insets.of(12)) }
        viewport = UIContainers.verticalScroll(Sizing.fixed((width - 16).coerceIn(120, 500)), Sizing.fill(92), content).apply {
            surface(Surface { g, c -> panel(g, c.x(), c.y(), c.width(), c.height(), 0xEE10171E.toInt()) })
            scrollbarThiccness(2); scrollStep(20)
        }
        root.child(viewport)
        content.child(label("SKY-MYCE / LOADER", 0x67CCF2))
        summary = label(""); content.child(summary)
        status = label("", 0x91A2AF); content.child(status)
        steps = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7) }
        steps.child(label("ACTIVATION CODE", 0x67CCF2))
        steps.child(label("Paste the code sent with your loader in Discord, then press Confirm. Use the Minecraft account you want to activate. Never put the code in chat.", 0x91A2AF))
        activation = ActivationField(); steps.child(activation)
        activate = button("Confirm") {
            val key = activation.value.trim(); activation.value = ""; LoaderClient.confirmActivation(key)
        }; steps.child(activate)
        content.child(steps)
        retry = button("Retry existing authorization") { LoaderClient.retry() }; content.child(retry)
        update = button("Check for update") { LoaderClient.retry() }; content.child(update)
        quit = button("Restart Minecraft") {
            activation.value = ""
            if (LoaderClient.canRestart) LoaderClient.requestRestart() else LoaderClient.quitForUpdate()
        }; content.child(quit)
        restartHint = label("", 0x91A2AF); content.child(restartHint)
        done = button("Continue without Sky-Myce") { onClose() }; content.child(done)
        refresh()
    }

    override fun tick() { super.tick(); if (::activation.isInitialized) refresh() }
    private fun refresh() {
        val busy = LoaderClient.isBusy
        val restart = LoaderClient.restartRequired
        val ready = LoaderClient.onlineAllowed && !restart
        val state = listOf(busy, restart, ready, LoaderClient.hasPayload, LoaderClient.status, LoaderClient.canRestart, activation.value.isNotBlank())
        if (state == displayedState) return
        displayedState = state
        summary.text(Component.literal(when {
            restart && LoaderClient.hasPayload -> "Update ready. Restart to install it automatically. Keep both the loader and mod installed."
            restart -> "First installation is ready. Restart once to load Sky-Myce. Keep the loader installed."
            ready -> "Sky-Myce is ready. You can return to Minecraft."
            LoaderClient.hasPayload -> "Your existing installation authorizes automatically. Retry if needed, or enter a new activation code."
            else -> "Enter your code and confirm. Sky-Myce downloads automatically, then needs one restart. Keep the loader installed for future updates."
        }).withColor(if (ready || restart) 0x87D6A2 else 0xEDF3F7))
        status.text(Component.literal(LoaderClient.status).withColor(0x91A2AF))
        val showSteps = !restart && !ready
        if (stepsShown != showSteps) {
            if (showSteps) content.child(3, steps) else { activation.value = ""; content.removeChild(steps) }
            stepsShown = showSteps
        }
        activate.active(!busy && showSteps && activation.value.isNotBlank())
        activation.setEditable(!busy && showSteps)
        retry.active(!busy && !restart && !ready)
        update.active(!busy && ready)
        quit.active(!busy)
        quit.message = Component.literal(if (LoaderClient.canRestart) "Restart Minecraft" else "Quit Minecraft").withColor(0xEDF3F7)
        restartHint.text(Component.literal(if (LoaderClient.canRestart) "Restarts this instance through Prism Launcher. Updates apply after Minecraft closes."
            else "Automatic restart is unavailable for this launcher. Quit, wait for any update to finish, then launch this instance again.").withColor(0x91A2AF))
        done.message = Component.literal(if (ready) "Continue to Minecraft" else "Continue without Sky-Myce").withColor(0xEDF3F7)
    }

    override fun removed() { if (::activation.isInitialized) activation.value = ""; super.removed() }
    override fun onClose() { if (::activation.isInitialized) activation.value = ""; minecraft.setScreen(if (minecraft.level == null) TitleScreen() else null) }
    private fun label(text: String, color: Int = 0xEDF3F7) = UIComponents.label(Component.literal(text).withColor(color))
        .apply { horizontalSizing(Sizing.fill()); shadow(false) }
    private fun button(text: String, action: () -> Unit) = UIComponents.button(Component.literal(text).withColor(0xEDF3F7)) { action() }.apply {
        sizing(Sizing.fill(), Sizing.fixed(22)); textShadow(false)
        renderer { g, b, _ -> panel(g, b.x, b.y, b.width, b.height,
            if (!b.active) 0xCC17212A.toInt() else if (b.isHovered || b.isFocused) 0xFF334C60.toInt() else 0xFF20313F.toInt()) }
    }
    private fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x + 2, y, x + w - 2, y + h, color)
        g.fill(x, y + 2, x + 2, y + h - 2, color)
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, color)
        g.fill(x + 2, y, x + w - 2, y + 1, 0xFF2C3944.toInt())
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, 0xFF2C3944.toInt())
    }
}
