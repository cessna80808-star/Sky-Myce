package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ColorPickerComponent
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIGraphics
import me.mycellium.skymyce.config.misc.ThemeConfig

/** Keep color selection input, replace owo's direct shader submission with canvas gradients. */
class MenuColorPicker : ColorPickerComponent() {
    private val hoverMotion = MotionValue(0.0)
    private var pointerX = -1

    override fun cursorStyle(): CursorStyle = if (pointerX in 1..colorAreaWidth() + 1 ||
        pointerX in hueSelectorX() + 1..hueSelectorX() + selectorWidth() + 1 ||
        showAlpha() && pointerX in alphaSelectorX() + 1..alphaSelectorX() + selectorWidth() + 1)
        CursorStyle.MOVE else CursorStyle.POINTER

    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        pointerX = mouseX - x()
        val left = renderX(); val top = renderY(); val w = colorAreaWidth(); val h = renderHeight()
        if (w <= 0 || h <= 0 || selectorWidth() <= 0) return
        hoverMotion.animateTo(if (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble()) || focusHandler()?.focused() === this) 1.0 else 0.0,
            110, MotionEasing.QUICK, ThemeConfig.animationMode)
        val emphasis = hoverMotion.sample().coerceIn(0.0, 1.0)
        val hueColor = java.awt.Color.HSBtoRGB(hue, 1f, 1f)
        MenuTheme.box(g, x(), y(), width(), height(), MenuTheme.CARD,
            MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (emphasis * 70).toInt()))
        g.drawGradientRect(left, top, w, h, -1, hueColor, hueColor, -1)
        g.drawGradientRect(left, top, w, h, 0, 0, 0xFF000000.toInt(), 0xFF000000.toInt())
        val graphics = g as MenuGraphics
        val selectedX = left + saturation * w
        val selectedY = top + (1 - value) * (h - 1)
        graphics.layer(x = (selectedX - selectedX.toInt()).toDouble(), y = (selectedY - selectedY.toInt()).toDouble()) {
            g.drawCircle(selectedX.toInt(), selectedY.toInt(), 24, 3.7 + emphasis * .5, Color.ofArgb(0xB006040C.toInt()))
            g.drawRing(selectedX.toInt(), selectedY.toInt(), 0.0, 360.0, 24, 2.0 + emphasis * .5, 3.0 + emphasis * .5,
                Color.WHITE, Color.WHITE)
        }
        for (section in 0 until 6) {
            val start = h * section / 6; val end = h * (section + 1) / 6
            val first = java.awt.Color.HSBtoRGB(1f - section / 6f, 1f, 1f)
            val last = java.awt.Color.HSBtoRGB(1f - (section + 1) / 6f, 1f, 1f)
            g.drawGradientRect(left + hueSelectorX(), top + start, selectorWidth(), end - start, first, first, last, last)
        }
        drawSelector(graphics, left + hueSelectorX(), top + (1 - hue) * (h - 1))
        if (showAlpha()) {
            val alphaLeft = left + alphaSelectorX()
            for (row in 0 until h step 4) for (column in 0 until selectorWidth() step 4)
                g.fill(alphaLeft + column, top + row, alphaLeft + minOf(column + 4, selectorWidth()), top + minOf(row + 4, h),
                    MenuTheme.alpha(if ((row / 4 + column / 4) % 2 == 0) MenuTheme.CARD else MenuTheme.BORDER))
            val color = 0xFF000000.toInt() or selectedColor().rgb()
            g.drawGradientRect(alphaLeft, top, selectorWidth(), h, color, color, color and 0xFFFFFF, color and 0xFFFFFF)
            drawSelector(graphics, alphaLeft, top + (1 - alpha) * (h - 1))
        }
    }

    private fun drawSelector(g: MenuGraphics, left: Int, top: Float) {
        val pixel = top.toInt()
        g.layer(y = (top - pixel).toDouble()) {
            MenuTheme.rounded(g, left - 2, pixel - 2, selectorWidth() + 4, 5, 0xB006040C.toInt(), 2)
            g.drawRectOutline(left - 1, pixel - 1, selectorWidth() + 2, 3, -1)
        }
    }
}
