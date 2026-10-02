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
import me.mycellium.skymyce.ui.MotionValue
import me.mycellium.skymyce.ui.MotionEasing
import me.mycellium.skymyce.ui.GuiMotion
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
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
    private lateinit var status: LabelComponent
    private lateinit var activate: ButtonComponent
    private lateinit var retry: ButtonComponent
    private lateinit var update: ButtonComponent
    private lateinit var autoUpdate: ButtonComponent
    private lateinit var restartButton: ButtonComponent
    private lateinit var restartHint: LabelComponent
    private lateinit var done: ButtonComponent
    private lateinit var steps: FlowLayout
    private lateinit var content: FlowLayout
    private lateinit var actions: FlowLayout
    private lateinit var viewport: ScrollContainer<FlowLayout>
    private var stepsShown = true
    private var displayedState: List<Any?> = emptyList()
    private val appearance = MotionValue(0.0)
    private val labels = mutableListOf<Pair<LabelComponent, Int>>()
    private var displayedLabelAlpha = -1
    private var closing = false

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)
    override fun isPauseScreen() = false
    override fun init() {
        closing = false
        appearance.snap(0.0); appearance.animateTo(1.0, 160, MotionEasing.QUICK, GuiMotion.REDUCED)
        super.init()
        if (::viewport.isInitialized) viewport.horizontalSizing(Sizing.fixed((width - 16).coerceIn(120, 500)))
    }
    override fun build(root: FlowLayout) {
        labels.clear()
        displayedLabelAlpha = -1
        stepsShown = true
        displayedState = emptyList()
        root.surface(Surface { g, c -> g.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), faded(0xBB0B0811.toInt())) })
            .alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(8); padding(Insets.of(12)) }
        viewport = UIContainers.verticalScroll(Sizing.fixed((width - 16).coerceIn(120, 500)), Sizing.fill(92), content).apply {
            surface(Surface { g, c -> panel(g, c.x(), c.y(), c.width(), c.height(), faded(0xEE191522.toInt())) })
            scrollbarThiccness(2); scrollStep(20)
        }
        root.child(viewport)
        content.child(label("SKY-MYCE / LOADER", 0xB698EF))
        status = label("", 0xAF9FC0); content.child(status)
        steps = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7) }
        steps.child(label("ACTIVATION CODE", 0xB698EF))
        steps.child(label("Paste the code from your Discord DM and press Confirm. Sky-Myce downloads automatically, then needs one restart. Keep the loader installed for updates. Never put the code in chat.", 0xAF9FC0))
        activation = ActivationField(); steps.child(activation)
        activate = button("Confirm") {
            val key = activation.value.trim(); activation.value = ""; LoaderClient.confirmActivation(key)
        }; steps.child(activate)
        content.child(steps)
        retry = button("Retry authorization") { LoaderClient.retry() }
        update = button("Check for update") { LoaderClient.retry() }
        autoUpdate = button("") { LoaderClient.setLoaderAutoUpdate(!LoaderClient.autoUpdateLoader) }
        restartButton = button("Restart Minecraft") {
            activation.value = ""
            LoaderClient.requestRestart()
        }
        restartHint = label("", 0xAF9FC0)
        done = button("Continue without Sky-Myce") { onClose() }
        actions = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(8) }
        content.child(actions)
        refresh()
    }

    override fun tick() {
        super.tick()
        appearance.sample()
        if (closing && !appearance.running && minecraft.screen === this) finishClose()
        else if (!closing && ::activation.isInitialized) refresh()
    }
    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val alpha = (appearance.sample().coerceIn(0.0, 1.0) * 255).toInt()
        if (alpha != displayedLabelAlpha) {
            displayedLabelAlpha = alpha
            labels.forEach { (label, color) -> label.color(Color.ofArgb((alpha shl 24) or color)) }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTicks)
    }
    override fun keyPressed(input: KeyEvent): Boolean = closing || super.keyPressed(input)
    override fun mouseClicked(input: MouseButtonEvent, doubled: Boolean): Boolean = closing || super.mouseClicked(input, doubled)
    private fun refresh() {
        val busy = LoaderClient.isBusy
        val restart = LoaderClient.restartRequired
        val ready = LoaderClient.onlineAllowed && !restart
        val state = listOf(busy, restart, ready, LoaderClient.status, LoaderClient.canRestart, LoaderClient.autoUpdateLoader, activation.value.isNotBlank())
        if (state == displayedState) return
        displayedState = state
        status.text(Component.literal(LoaderClient.status).withColor(if (ready || restart) 0x87D6A2 else 0xAF9FC0))
        val showSteps = !restart && !ready
        if (stepsShown != showSteps) {
            if (showSteps) content.child(2, steps) else { activation.value = ""; content.removeChild(steps) }
            stepsShown = showSteps
        }
        activate.active(!busy && showSteps && activation.value.isNotBlank())
        activation.setEditable(!busy && showSteps)
        retry.active(!busy && !restart && !ready)
        update.active(!busy && ready)
        autoUpdate.active(!busy)
        autoUpdate.message = Component.literal("Automatic loader updates: " + if (LoaderClient.autoUpdateLoader) "ON" else "OFF").withColor(0xF3EEF9)
        restartButton.active(!busy && LoaderClient.canRestart)
        restartHint.text(Component.literal(if (LoaderClient.canRestart) "Restarts this Minecraft instance after applying any verified update."
            else "Restart is not ready yet. Minecraft stays open if preparation fails.").withColor(0xAF9FC0))
        done.message = Component.literal(if (ready) "Continue to Minecraft" else "Continue without Sky-Myce").withColor(0xF3EEF9)
        actions.clearChildren()
        if (showSteps) actions.child(retry)
        if (ready) actions.child(update)
        actions.child(autoUpdate)
        actions.child(restartButton).child(restartHint).child(done)
    }

    override fun removed() { if (::activation.isInitialized) activation.value = ""; appearance.cancel(); super.removed() }
    override fun onClose() {
        if (::activation.isInitialized) { activation.value = ""; activation.setEditable(false) }
        if (closing) return
        closing = true; appearance.animateTo(0.0, 100, MotionEasing.QUICK, GuiMotion.REDUCED)
        if (!appearance.running && minecraft.screen === this) finishClose()
    }
    private fun finishClose() { minecraft.setScreen(if (minecraft.level == null) TitleScreen() else null) }
    private fun faded(color: Int): Int = ((color ushr 24) * appearance.sample().coerceIn(0.0, 1.0)).toInt().shl(24) or (color and 0xFFFFFF)
    private fun label(text: String, color: Int = 0xF3EEF9) = UIComponents.label(Component.literal(text).withColor(color))
        .apply { horizontalSizing(Sizing.fill()); shadow(false); labels += this to color }
    private fun button(text: String, action: () -> Unit) = object : ButtonComponent(Component.literal(text), { if (!closing) action() }) {
        init { sizing(Sizing.fill(), Sizing.fixed(22)); textShadow(false) }
        override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
            panel(g, x, y, width, height, faded(if (!active) 0xCC251F32.toInt() else if (isHovered || isFocused) 0xFF443752.toInt() else 0xFF30283F.toInt()))
            g.text(minecraft.font, message, x + (width - minecraft.font.width(message)) / 2, y + (height - minecraft.font.lineHeight) / 2, faded(if (active) 0xFFF3EEF9.toInt() else 0xFFAF9FC0.toInt()), false)
        }
        override fun drawFocusHighlight(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            if (isFocused) g.drawRectOutline(x, y, width, height, faded(0xFFB698EF.toInt()))
        }
    }
    private fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x + 2, y, x + w - 2, y + h, color)
        g.fill(x, y + 2, x + 2, y + h - 2, color)
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, color)
        g.fill(x + 2, y, x + w - 2, y + 1, faded(0xFF443752.toInt()))
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, faded(0xFF443752.toInt()))
    }
}
