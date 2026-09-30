package me.mycellium.skymyce.ui

import com.mojang.blaze3d.pipeline.RenderPipeline
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.OwoUIGraphics
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import me.mycellium.mixin.MenuTextTooltipAccessor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import net.minecraft.client.StringSplitter
import net.minecraft.locale.Language
import net.minecraft.network.chat.Style

internal fun menuTextWidth(text: FormattedCharSequence, size: Double = 10.0): Double {
    var width = 0.0
    text.accept { _, style, codepoint ->
        width += MenuCanvas.textWidth(String(Character.toChars(codepoint)), style.isBold, style.isItalic, size)
        true
    }
    return width
}

internal fun menuTextLines(text: Component, width: Int, size: Double = 10.0): List<FormattedCharSequence> {
    val splitter = StringSplitter { codepoint, style ->
        MenuCanvas.textWidth(String(Character.toChars(codepoint)), style.isBold, style.isItalic, size).toFloat()
    }
    return Language.getInstance().getVisualOrder(splitter.splitLines(text, width.coerceAtLeast(1), Style.EMPTY))
}

/** Owo supplies retained layout/input; all paint goes to our canvas, never its native render state. */
class MenuGraphics(val canvas: MenuCanvas, mouseX: Int, mouseY: Int, private val menuWidth: Int = Minecraft.getInstance().window.guiScaledWidth,
                   private val menuHeight: Int = Minecraft.getInstance().window.guiScaledHeight) :
    OwoUIGraphics(Minecraft.getInstance(), GuiRenderState(), mouseX, mouseY, {}) {
    override fun guiWidth() = menuWidth
    override fun guiHeight() = menuHeight
    private fun sync() {
        val m = pose()
        canvas.transform = MenuCanvas.Transform(m.m00().toDouble(), m.m01().toDouble(), m.m10().toDouble(), m.m11().toDouble(), m.m20().toDouble(), m.m21().toDouble())
        canvas.clip = scissorStack.peek()?.let { MenuCanvas.Clip(it.position().x(), it.position().y(), it.width(), it.height()) }
    }
    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        sync(); canvas.fill(minOf(x1, x2).toDouble(), minOf(y1, y2).toDouble(), kotlin.math.abs(x2 - x1).toDouble(), kotlin.math.abs(y2 - y1).toDouble(), color)
    }
    override fun fill(pipeline: RenderPipeline, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) = fill(x1, y1, x2, y2, color)
    override fun fillGradient(x1: Int, y1: Int, x2: Int, y2: Int, top: Int, bottom: Int) = drawGradientRect(x1, y1, x2 - x1, y2 - y1, top, top, bottom, bottom)
    override fun drawGradientRect(pipeline: RenderPipeline, x: Int, y: Int, width: Int, height: Int, tl: Int, tr: Int, br: Int, bl: Int) {
        sync(); canvas.gradient(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), tl, tr, br, bl)
    }
    override fun drawCircle(pipeline: RenderPipeline, x: Int, y: Int, from: Double, to: Double, segments: Int, radius: Double, color: Color) {
        sync(); canvas.arc(x.toDouble(), y.toDouble(), radius, from, to, color.argb())
    }
    override fun drawRing(pipeline: RenderPipeline, x: Int, y: Int, from: Double, to: Double, segments: Int, inner: Double, outer: Double, innerColor: Color, outerColor: Color) {
        sync(); canvas.arc(x.toDouble(), y.toDouble(), outer, from, to, outerColor.argb(), inner)
    }
    override fun drawLine(pipeline: RenderPipeline, x1: Int, y1: Int, x2: Int, y2: Int, thickness: Double, color: Color) {
        sync(); canvas.line(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), thickness, color.argb())
    }
    fun picture(image: BufferedImage, x: Int, y: Int, width: Int, height: Int) {
        sync(); canvas.picture(image, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }
    // Dropdown state is a vector check/chevron rather than owo's Minecraft sprite sheet.
    override fun blit(pipeline: RenderPipeline, texture: Identifier, x: Int, y: Int, u: Float, v: Float, width: Int, height: Int, textureWidth: Int, textureHeight: Int) {
        val color = Color.ofRgb(MenuTheme.TEXT)
        if (v > 0) {
            drawLine(x + 3, y + 1, x + 6, y + 4, 1.0, color); drawLine(x + 6, y + 4, x + 3, y + 7, 1.0, color)
        } else {
            MenuTheme.box(this, x, y, width, height, if (u > 0) MenuTheme.ACCENT else MenuTheme.CARD)
            if (u > 0) { drawLine(x + 2, y + 4, x + 4, y + 6, 1.0, color); drawLine(x + 4, y + 6, x + 7, y + 2, 1.0, color) }
        }
    }
    override fun text(font: Font, text: String?, x: Int, y: Int, color: Int, shadow: Boolean) { if (text != null) text(font, Component.literal(text), x, y, color, shadow) }
    override fun text(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) = text(font, text.visualOrderText, x, y, color, shadow)
    override fun text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean) = styledText(text, x.toDouble(), y.toDouble(), color)
    override fun drawTooltip(font: Font, x: Int, y: Int, components: List<ClientTooltipComponent>, texture: Identifier?) {
        val lines = components.mapNotNull { (it as? MenuTextTooltipAccessor)?.`skymyce$text`() }
        if (lines.isEmpty()) return
        val lineHeight = MenuCanvas.lineHeight()
        val width = kotlin.math.ceil(lines.maxOf { menuTextWidth(it) }).toInt().coerceAtMost(guiWidth() - 20) + 14
        val height = lines.size * lineHeight + 12
        val left = (x + 10).coerceAtMost(guiWidth() - width - 4).coerceAtLeast(4)
        val top = (y + 12).coerceAtMost(guiHeight() - height - 4).coerceAtLeast(4)
        pose().pushMatrix(); pose().identity()
        MenuTheme.box(this, left, top, width, height, MenuTheme.SIDEBAR)
        lines.forEachIndexed { i, line -> styledText(line, (left + 7).toDouble(), (top + 6 + i * lineHeight).toDouble(), MenuTheme.alpha(MenuTheme.TEXT)) }
        pose().popMatrix()
    }
    fun styledText(text: FormattedCharSequence, x: Double, y: Double, color: Int, size: Double = 10.0) {
        val runs = ArrayList<MenuCanvas.Run>()
        text.accept { _, style, codepoint ->
            val run = MenuCanvas.Run(String(Character.toChars(codepoint)), (color and -0x1000000) or (style.color?.value ?: (color and 0xFFFFFF)), style.isBold, style.isItalic, style.isUnderlined)
            val last = runs.lastOrNull()
            if (last != null && last.copy(text = run.text) == run) runs[runs.lastIndex] = last.copy(text = last.text + run.text) else runs += run
            true
        }
        sync(); canvas.text(runs, x, y, size)
    }
}
