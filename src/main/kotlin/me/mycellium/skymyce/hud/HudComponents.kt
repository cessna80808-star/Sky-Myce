package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.UIComponent
import net.minecraft.network.chat.Component

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

fun hudPanel(padding: Int = HudTheme.PADDING, decorated: Boolean = true): FlowLayout = hudColumn().apply {
    padding(Insets.of(padding))
    surface(HudTheme.panel().let { background ->
        if (!decorated) background else background.and { graphics, panel ->
            graphics.fill(panel.x(), panel.y() + 3, panel.x() + 2, panel.y() + panel.height() - 3, HudTheme.alpha(HudTheme.HUD_ACCENT))
        }
    })
}
