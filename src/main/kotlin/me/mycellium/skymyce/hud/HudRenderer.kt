package me.mycellium.skymyce.hud

import io.wispforest.owo.mixin.ui.access.GuiGraphicsExtractorAccessor
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

object HudRenderer : SkyMyceModule() {
    private var pressedWidget: Widget? = null
    private val failedWidgets = mutableMapOf<String, Long>()
    private val interactive get() = isEnabled() && MC.screen is ChatScreen && !MC.instance.options.hideGui && MC.instance.player != null

    override fun init() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "hud_renderer")
        ) { context, ticks -> render(context, ticks.getGameTimeDeltaPartialTick(false)) }

        // Chat releases the mouse without letting HUD controls intercept inventory or editor input.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is ChatScreen) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                val widget = hovered(click.x.toInt(), click.y.toInt())
                val handled = widget?.adapter?.mouseClicked(widget.localClick(click), false) == true
                pressedWidget = widget.takeIf { handled }
                !handled
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { _, click ->
                val widget = pressedWidget
                pressedWidget = null
                if (widget != null) widget.adapter?.mouseReleased(widget.localClick(click))
                widget == null
            }
            ScreenMouseEvents.allowMouseDrag(screen).register { _, click, dx, dy ->
                val widget = pressedWidget?.takeIf { interactive && it.isRenderable }
                if (widget != null) widget.adapter?.mouseDragged(widget.localClick(click), dx / widget.scale, dy / widget.scale)
                widget == null
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, horizontal, vertical ->
                val widget = hovered(mouseX.toInt(), mouseY.toInt())
                widget?.adapter?.mouseScrolled(widget.localX(mouseX), widget.localY(mouseY), horizontal, vertical) != true
            }
            ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, partialTicks ->
                hovered(mouseX, mouseY)?.drawTooltip(graphics, mouseX, mouseY, partialTicks)
            }
            ScreenEvents.remove(screen).register {
                pressedWidget = null
                WidgetManager.forEachWidget { it.adapter?.cursorAdapter?.applyStyle(CursorStyle.NONE) }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { dispose() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { dispose() }
    }

    private fun hovered(mouseX: Int, mouseY: Int): Widget? = if (!interactive ||
        me.mycellium.skymyce.features.instances.dungeons.friends.IrcChatRendering.hasInteractionAt(mouseX, mouseY)) null else
        WidgetManager.widgetAt(mouseX, mouseY)

    fun render(context: GuiGraphicsExtractor, partialTicks: Float) {
        RuntimeProfiler.measure(RuntimeProfiler.Area.HUD) {
        if (!isEnabled() || MC.instance.options.hideGui || MC.instance.player == null || MC.screen is WidgetEditorScreen) return
        val window = MC.instance.window
        val mouseX = if (interactive) (MC.instance.mouseHandler.xpos() * window.guiScaledWidth / window.screenWidth).toInt() else -1000000
        val mouseY = if (interactive) (MC.instance.mouseHandler.ypos() * window.guiScaledHeight / window.screenHeight).toInt() else -1000000
        val now = System.currentTimeMillis()
        WidgetManager.forEachWidget { widget ->
            if ((failedWidgets[widget.id] ?: 0) > now) return@forEachWidget
            try {
                widget.render(context, mouseX, mouseY, partialTicks)
                failedWidgets.remove(widget.id)
            } catch (error: Exception) {
                // The widget transform/theme scopes unwind before this boundary. A bad retained layout cannot stop its peers.
                val firstFailure = widget.id !in failedWidgets
                failedWidgets[widget.id] = now + 5000
                try { widget.dispose() } catch (_: Exception) { /* Rebuild on the next bounded retry. */ }
                if (firstFailure) SkyMyce.logger.warn("HUD widget {} failed; rebuilding after five seconds", widget.id, error)
            }
        }
        }
    }

    private fun dispose() {
        pressedWidget = null
        failedWidgets.clear()
        WidgetManager.forEachWidget { it.dispose() }
    }
}

/** Wrap once when built, using the same viewport limit as the settings menu. */
fun <T : UIComponent> T.wrappedTooltip(text: Component): T = apply {
    val mc = me.mycellium.skymyce.utils.MC.instance
    tooltip(mc.font.split(text, minOf(250, (mc.window.guiScaledWidth - 30).coerceAtLeast(80)))
        .map { net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent.create(it) })
}

fun hudColumn(): FlowLayout = UIContainers.verticalFlow(Sizing.content(), Sizing.content()).gap(2).apply {
    allowOverflow(true) // Text shadows extend beyond the measured glyph bounds.
}

fun hudLabel(text: String = ""): LabelComponent = HudTextLabel(text)

/** Existing HUD strings keep their semantic formatting, mapped through the one shared palette. */
internal fun themedHudText(value: String): Component {
    val result = Component.empty()
    var style = net.minecraft.network.chat.Style.EMPTY.withColor(HudTheme.TEXT)
    var start = 0
    var i = 0
    while (i + 1 < value.length) {
        if (value[i] != '§') { i++; continue }
        val code = value[i + 1].lowercaseChar()
        val formatting = net.minecraft.ChatFormatting.getByCode(code)
        if (formatting == null) { i++; continue }
        if (i > start) result.append(Component.literal(value.substring(start, i)).withStyle(style))
        val color = when (code) {
            'f' -> HudTheme.TEXT; '7', '8' -> HudTheme.MUTED
            '9', 'b' -> HudTheme.ACCENT; '5', 'd' -> HudTheme.SECONDARY
            'a', '2' -> HudTheme.GREEN; 'c', '4' -> HudTheme.RED; 'e', '6' -> HudTheme.YELLOW
            else -> null
        }
        style = if (code == 'r') net.minecraft.network.chat.Style.EMPTY.withColor(HudTheme.TEXT)
            else if (color != null) net.minecraft.network.chat.Style.EMPTY.withColor(color) else style.applyFormat(formatting)
        i += 2; start = i
    }
    if (start < value.length) result.append(Component.literal(value.substring(start)).withStyle(style))
    return result
}

internal class HudTextLabel(initial: String) : LabelComponent(Component.empty()) {
    private var source = ""
    private var theme = 0L
    var opacity = 1.0
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val original = color().get()
        val faded = hudOpacityColor(original.argb(), opacity)
        // Some native text paths treat RGB or nearly transparent colors as opaque.
        if (faded ushr 24 <= 4) return
        if (faded == original.argb()) { super.draw(graphics, mouseX, mouseY, partialTicks, delta); return }
        color(Color.ofArgb(faded))
        try { super.draw(graphics, mouseX, mouseY, partialTicks, delta) }
        finally { color(original) }
    }
    fun update(value: String = source, force: Boolean = false) {
        // Labels belong to one widget; revision also changes whenever its overrides are edited.
        if (!force && value == source && theme == HudTheme.revision) return
        source = value; theme = HudTheme.revision
        text(themedHudText(value.ifEmpty { " " })); shadow(HudTheme.SHADOW)
    }
    init { theme = -1; update(initial) }
}

internal fun hudOpacityColor(color: Int, opacity: Double): Int {
    val amount = opacity.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return ((color ushr 24) * amount).toInt().shl(24) or (color and 0xFFFFFF)
}

/** Presentation only; the original HUD label color and semantic spans remain unchanged. */
fun LabelComponent.hudOpacity(amount: Double): LabelComponent = apply {
    if (this is HudTextLabel) opacity = amount
}

fun LabelComponent.updateText(text: String) {
    if (this is HudTextLabel) { update(text); return }
    val value = text.ifEmpty { " " }
    if (text().string != value) text(Component.literal(value))
}

internal fun FlowLayout.replaceContent(content: UIComponent) {
    clearChildren()
    // Mutations while detached do not invalidate owo's cached parent sizes. Remeasure before remounting.
    content.inflate(Size.zero())
    child(content)
}

/** Keep the layout and existing labels alive as rows change, so owo can update their bounds. */
fun FlowLayout.updateTextLines(lines: List<String>, opacities: List<Double> = emptyList()): FlowLayout = apply {
    configure<FlowLayout> { column ->
        while (column.children().size > lines.size) column.removeChild(column.children().last())
        lines.forEachIndexed { index, text ->
            val label = column.children().getOrNull(index) as? LabelComponent
            val opacity = opacities.getOrNull(index) ?: 1.0
            if (label == null) column.child(hudLabel(text).hudOpacity(opacity))
            else { label.updateText(text); label.hudOpacity(opacity) }
        }
    }
}

/** [fade] scales the background from invisible (0) to the theme's panel opacity (100); text is never faded. */
fun hudPanel(padding: Int = HudTheme.PADDING, decorated: Boolean = true, fade: () -> Int = { 100 }): FlowLayout = hudColumn().apply {
    padding(Insets.of(padding))
    surface(HudTheme.panel(fade = fade).let { background ->
        if (!decorated) background else background.and { graphics, panel ->
            val percent = fade().coerceIn(0, 100)
            if (percent > 0) graphics.fill(panel.x(), panel.y() + 3, panel.x() + 2, panel.y() + panel.height() - 3,
                HudTheme.alpha(HudTheme.HUD_ACCENT, HudTheme.OPACITY * percent / 100))
        }
    })
}

/**
 * Submit to Minecraft's existing GUI state while isolating mutable matrix/clipping stacks.
 * owo's default wrapper shares both stacks and its adapter does not unwind clipping if a draw throws.
 * Current inherited clip rectangles are already transformed; push the rectangle directly rather than transform it twice.
 */
internal class HudWidgetGraphics(parent: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) :
    OwoUIGraphics(MC.instance, parent.guiRenderState, mouseX, mouseY,
        { drawer -> (parent as GuiGraphicsExtractorAccessor).`owo$setDeferredTooltip`(drawer) }) {
    init {
        pose().set(parent.pose())
        parent.scissorStack.peek()?.let { scissorStack.push(it) }
    }
}
