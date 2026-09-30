package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ColorPickerComponent
import io.wispforest.owo.ui.core.OwoUIGraphics

/** Keep color selection input, replace owo's direct shader submission with canvas gradients. */
class MenuColorPicker : ColorPickerComponent() {
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val hsv = selectedColor().hsv()
        val left = renderX(); val top = renderY(); val w = colorAreaWidth(); val h = renderHeight()
        val hue = java.awt.Color.HSBtoRGB(hsv[0], 1f, 1f)
        g.drawGradientRect(left, top, w, h, -1, hue, hue, -1)
        g.drawGradientRect(left, top, w, h, 0, 0, 0xFF000000.toInt(), 0xFF000000.toInt())
        g.drawRectOutline((left + hsv[1] * w - 1).toInt(), (top + (1 - hsv[2]) * (h - 1) - 1).toInt(), 3, 3, -1)
        for (row in 0 until h) g.fill(left + hueSelectorX(), top + row, left + hueSelectorX() + selectorWidth(), top + row + 1,
            java.awt.Color.HSBtoRGB(1f - row.toFloat() / h, 1f, 1f))
        g.drawRectOutline(left + hueSelectorX() - 1, (top + (1 - hsv[0]) * (h - 1) - 1).toInt(), selectorWidth() + 2, 3, -1)
    }
}
