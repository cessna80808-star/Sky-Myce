package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.SliderComponent
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Shared palette used by retained owo HUD components and menus. */
object HudTheme {
    private var widget: ThemeOverrides? = null
    var revision = 0L
        private set
    fun changed() { revision++ }
    fun <T> withWidget(overrides: ThemeOverrides?, action: () -> T): T {
        val previous = widget; widget = overrides
        return try { action() } finally { widget = previous }
    }
    private val base get() = ThemeConfig.preset.palette
    private fun color(local: Int?, global: Int, fallback: Int) = (local ?: global).takeIf { it in 0..0xFFFFFF } ?: fallback
    private fun flag(local: Boolean?, global: Int, fallback: Boolean) = local ?: when (global) { 0 -> false; 1 -> true; else -> fallback }
    val HUD_ACCENT get() = ACCENT
    val ACCENT get() = color(widget?.primary, ThemeConfig.primary, base.primary)
    val SECONDARY get() = color(widget?.secondary, ThemeConfig.secondary, base.secondary)
    val PANEL get() = color(widget?.panel, ThemeConfig.panel, base.panel)
    val CARD get() = color(widget?.card, ThemeConfig.card, base.card)
    val BORDER get() = color(widget?.border, ThemeConfig.border, base.border)
    val TEXT get() = color(widget?.text, ThemeConfig.text, base.text)
    val MUTED get() = color(widget?.muted, ThemeConfig.muted, base.muted)
    val GREEN get() = base.success
    val YELLOW get() = base.warning
    val RED get() = base.error
    val OPACITY get() = (widget?.opacity ?: ThemeConfig.opacity).takeIf { it in 20..100 } ?: base.opacity
    val PADDING get() = (widget?.padding ?: ThemeConfig.padding).takeIf { it in 2..12 } ?: base.padding
    val ROUNDED get() = flag(widget?.rounded, ThemeConfig.rounded, base.rounded)
    val SHADOW get() = flag(widget?.shadow, ThemeConfig.shadow, base.shadow)
    val BORDERS get() = flag(widget?.borders, ThemeConfig.borders, base.borders)
    val STYLE get() = base.style
    val PURPLE get() = SECONDARY

    fun alpha(rgb: Int, percent: Int = OPACITY) = (percent.coerceIn(0, 100) * 255 / 100 shl 24) or (rgb and 0xFFFFFF)
    fun blend(a: Int, b: Int, weight: Int): Int {
        val w = weight.coerceIn(0, 100)
        fun channel(shift: Int) = (((a shr shift and 255) * (100 - w) + (b shr shift and 255) * w) / 100) shl shift
        return channel(16) or channel(8) or channel(0)
    }

    /** Five non-overlapping rectangles, no shaders, textures, allocations, or double-blended edges. */
    fun rounded(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width <= 0 || height <= 0) return
        if (!ROUNDED || width < 6 || height < 6) { g.fill(x, y, x + width, y + height, color); return }
        if (g is me.mycellium.skymyce.ui.MenuGraphics) {
            g.roundedRect(x, y, width, height, STYLE.radius.coerceAtLeast(2).toDouble(), color)
            return
        }
        val corner = STYLE.radius.coerceIn(1, 3)
        g.fill(x + corner, y, x + width - corner, y + 1, color)
        g.fill(x + 1, y + 1, x + width - 1, y + 2, color)
        g.fill(x, y + 2, x + width, y + height - 2, color)
        g.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, color)
        g.fill(x + corner, y + height - 1, x + width - corner, y + height, color)
    }

    /** [fade] scales the whole panel, outline included, from invisible (0) to the theme's opacity (100). */
    fun panel(card: Boolean = false, fade: () -> Int = { 100 }): Surface = Surface { g, c ->
        val percent = fade().coerceIn(0, 100)
        if (percent == 0) return@Surface
        rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(if (card) CARD else PANEL, OPACITY * percent / 100))
        if (BORDERS && c.width() > 5 && c.height() > 5) {
            val x = c.x(); val y = c.y(); val w = c.width(); val h = c.height()
            val inset = if (ROUNDED) STYLE.radius.coerceIn(1, 3) else 0
            // Opaque, one-pixel outline; the panel interior retains exactly the selected opacity.
            val border = alpha(BORDER, percent)
            if (g is me.mycellium.skymyce.ui.MenuGraphics) {
                g.roundedBorder(x, y, w, h, if (ROUNDED) STYLE.radius.coerceAtLeast(2).toDouble() else 0.0, STYLE.borderWidth, border)
            } else {
                val line = STYLE.borderWidth.toInt().coerceIn(1, 2)
                g.fill(x + inset, y, x + w - inset, y + line, border)
                g.fill(x + inset, y + h - line, x + w - inset, y + h, border)
                g.fill(x, y + inset, x + line, y + h - inset, border)
                g.fill(x + w - line, y + inset, x + w, y + h - inset, border)
            }
        }
    }
    fun tintedPanel(color: () -> Int): Surface = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(color())) }
    val backdrop = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(blend(PANEL, 0, 35), 55)) }
    val buttonRenderer = ButtonComponent.Renderer { g, b, _ ->
        val hover = b.isHovered || b.isFocused
        rounded(g, b.x, b.y, b.width, b.height, alpha(blend(CARD, if (b.isFocused) SECONDARY else ACCENT,
            if (!b.active) 2 else if (hover) STYLE.hoverWeight else 5)))
        if (b.isFocused) g.outline(b.x, b.y, b.width, b.height, alpha(ACCENT, 100))
    }
}

/** The existing slider retains keyboard/mouse behavior; only its background and thumb change. */
class ThemeSlider : SliderComponent(Sizing.fill()) {
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        HudTheme.rounded(g, x, y, width, height, HudTheme.alpha(HudTheme.CARD))
        val thumb = (x + value() * (width - 8)).toInt()
        HudTheme.rounded(g, thumb, y + 2, 8, height - 4, HudTheme.alpha(HudTheme.ACCENT))
    }
}

fun ButtonComponent.themed(): ButtonComponent = apply {
    if (this is me.mycellium.skymyce.ui.MenuButton) { textShadow(false); return@apply }
    renderer(HudTheme.buttonRenderer); textShadow(HudTheme.SHADOW)
    if (message.style.color == null) message = message.copy().withColor(HudTheme.TEXT)
}
