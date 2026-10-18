package me.mycellium.skymyce.hud

import me.mycellium.skymyce.ui.MenuCanvas

/** The selector and rendered checks use the same palette, geometry and state tokens as menu controls. */
internal class ThemePreview(private val palette: ThemePalette) {
    private val heading = listOf(MenuCanvas.Run("Dungeons", opaque(palette.text), bold = true))
    private val title = listOf(MenuCanvas.Run("Run tracker", opaque(palette.text)))
    private val status = listOf(MenuCanvas.Run("Ready", opaque(palette.success)))

    fun paint(canvas: MenuCanvas, x: Double, y: Double, width: Double, height: Double) {
        if (width < 24 || height < 24) return
        val style = palette.style
        val radius = if (palette.rounded) style.radius.coerceAtLeast(2).toDouble() else 0.0
        fun box(left: Double, top: Double, w: Double, h: Double, color: Int, border: Boolean = false) {
            canvas.roundedRect(left, top, w, h, radius, opaque(color))
            if (border && palette.borders) canvas.roundedBorder(left, top, w, h, radius, style.borderWidth, opaque(palette.border))
        }
        box(x, y, width, height, palette.panel, true)
        val rail = (width * .2).coerceIn(16.0, 42.0)
        canvas.roundedRect(x + 1, y + 1, rail, height - 2, radius, opaque(palette.sidebar))
        val gutter = style.menuPadding.coerceAtMost(8).toDouble()
        val left = x + rail + gutter
        val available = (width - rail - gutter * 2).coerceAtLeast(1.0)
        val line = style.focusWidth.toDouble()
        canvas.fill(x + 4, y + 14, line, 13.0, opaque(palette.primary))
        box(x + 9, y + 16, (rail - 14).coerceAtLeast(3.0), 7.0,
            HudTheme.blend(palette.card, palette.primary, style.selectionWeight))
        for (index in 0..1) canvas.fill(x + 9, y + 34 + index * 11, (rail - 14).coerceAtLeast(3.0), 2.0, opaque(palette.muted))
        if (width >= 120) canvas.text(heading, left, y + 6, 8.0)
        val top = y + 21
        val cardHeight = (height - 27).coerceAtLeast(8.0)
        box(left, top, available, cardHeight, palette.card, true)
        if (style.sheen > 0) canvas.gradient(left + 2, top + 2, (available - 4).coerceAtLeast(1.0), cardHeight / 2,
            alpha(palette.primary, (style.sheen * 16).toInt()), alpha(palette.secondary, (style.sheen * 10).toInt()), 0, 0)
        if (width >= 150 && height >= 58) {
            canvas.text(title, left + 6, top + 6, 7.0)
            canvas.text(status, left + 6, top + 20, 6.5)
        }
        val switchWidth = minOf(21.0, available * .25)
        val switchX = left + available - switchWidth - 6
        box(switchX, top + 7, switchWidth, 9.0, palette.primary)
        canvas.roundedRect(switchX + switchWidth - 8, top + 8.5, 6.0, 6.0,
            if (palette.rounded) 3.0 else 0.0, opaque(themeForeground(palette.primary)))
        val trackWidth = (available - 12).coerceAtLeast(1.0)
        canvas.fill(left + 6, top + cardHeight - 7, trackWidth, 2.0, opaque(palette.border))
        canvas.fill(left + 6, top + cardHeight - 7, trackWidth * .6, 2.0, opaque(palette.primary))
        canvas.roundedRect(left + 6 + trackWidth * .6 - 2, top + cardHeight - 9, 4.0, 6.0,
            if (palette.rounded) 2.0 else 0.0, opaque(palette.text))
    }

    private fun opaque(rgb: Int) = rgb or -0x1000000
    private fun alpha(rgb: Int, opacity: Int) = (opacity.coerceIn(0, 100) * 255 / 100 shl 24) or rgb
}

internal fun themeSelectorColumns(width: Int) = when {
    width >= 620 -> 3
    width >= 360 -> 2
    else -> 1
}

internal val themeSelectorPresets = listOf(ThemePreset.SIGNATURE, ThemePreset.DARK_GLASS, ThemePreset.BOREALIS,
    ThemePreset.EMBERFORGE, ThemePreset.VERDANT, ThemePreset.MINIMAL, ThemePreset.LIGHT, ThemePreset.HIGH_CONTRAST)
