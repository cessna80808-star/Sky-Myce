package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.core.Surface
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Shared palette used by retained owo HUD components and menus. */
object HudTheme {
    var revision = 0L
        private set
    fun changed() { revision++ }
    private val base get() = ThemeConfig.preset.palette
    val HUD_ACCENT get() = ACCENT
    val ACCENT get() = base.primary
    val SECONDARY get() = base.secondary
    val PANEL get() = base.panel
    val CARD get() = base.card
    val BORDER get() = base.border
    val TEXT get() = base.text
    val MUTED get() = base.muted
    val GREEN get() = base.success
    val YELLOW get() = base.warning
    val RED get() = base.error
    val OPACITY get() = base.opacity
    val PADDING get() = base.padding
    val ROUNDED get() = base.rounded
    val SHADOW get() = base.shadow
    val BORDERS get() = base.borders
    val STYLE get() = base.style

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
    val buttonRenderer = ButtonComponent.Renderer { g, b, _ ->
        val hover = b.isHovered || b.isFocused
        rounded(g, b.x, b.y, b.width, b.height, alpha(blend(CARD, if (b.isFocused) SECONDARY else ACCENT,
            if (!b.active) 2 else if (hover) STYLE.hoverWeight else 5)))
        if (b.isFocused) g.outline(b.x, b.y, b.width, b.height, alpha(ACCENT, 100))
    }
}

fun ButtonComponent.themed(): ButtonComponent = apply {
    if (this is me.mycellium.skymyce.ui.MenuButton) { textShadow(false); return@apply }
    renderer(HudTheme.buttonRenderer); textShadow(HudTheme.SHADOW)
    if (message.style.color == null) message = message.copy().withColor(HudTheme.TEXT)
}
