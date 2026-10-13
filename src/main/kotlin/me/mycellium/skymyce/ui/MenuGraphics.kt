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
        width += MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size)
        true
    }
    return width
}

internal fun menuTextLines(text: Component, width: Int, size: Double = 10.0): List<FormattedCharSequence> {
    val splitter = StringSplitter { codepoint, style ->
        MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size).toFloat()
    }
    return Language.getInstance().getVisualOrder(splitter.splitLines(text, width.coerceAtLeast(1), Style.EMPTY))
}

internal fun menuTextRuns(text: FormattedCharSequence, color: Int): List<MenuCanvas.Run> {
    val runs = ArrayList<MenuCanvas.Run>()
    val content = StringBuilder()
    var tint = color
    var bold = false
    var italic = false
    var underline = false
    fun flush() {
        if (content.isNotEmpty()) {
            runs += MenuCanvas.Run(content.toString(), tint, bold, italic, underline)
            content.setLength(0)
        }
    }
    text.accept { _, style, codepoint ->
        val nextTint = (color and -0x1000000) or (style.color?.value ?: (color and 0xFFFFFF))
        if (nextTint != tint || style.isBold != bold || style.isItalic != italic || style.isUnderlined != underline) {
            flush()
            tint = nextTint; bold = style.isBold; italic = style.isItalic; underline = style.isUnderlined
        }
        content.appendCodePoint(codepoint)
        true
    }
    flush()
    return runs
}

/** Owo supplies retained layout/input; all paint goes to our canvas, never its native render state. */
class MenuGraphics(val canvas: MenuCanvas, mouseX: Int, mouseY: Int, private val menuWidth: Int = Minecraft.getInstance().window.guiScaledWidth,
                   private val menuHeight: Int = Minecraft.getInstance().window.guiScaledHeight,
                   private val tooltipMotion: MenuTooltipMotion? = null) :
    OwoUIGraphics(Minecraft.getInstance(), GuiRenderState(), mouseX, mouseY, {}) {
    override fun guiWidth() = menuWidth
    override fun guiHeight() = menuHeight
    internal val canvasClip get(): MenuCanvas.Clip? { sync(); return canvas.clip }
    /** Compose effects without changing retained layout, focus, or component state. */
    fun layer(alpha: Double = 1.0, x: Double = 0.0, y: Double = 0.0, scale: Double = 1.0,
              pivotX: Double = 0.0, pivotY: Double = 0.0, draw: () -> Unit) {
        val previousOpacity = canvas.opacity
        pose().pushMatrix()
        try {
            pose().translate((x + pivotX).toFloat(), (y + pivotY).toFloat())
            pose().scale(scale.toFloat(), scale.toFloat())
            pose().translate(-pivotX.toFloat(), -pivotY.toFloat())
            canvas.opacity *= alpha.coerceIn(0.0, 1.0)
            sync()
            draw()
        } finally {
            canvas.opacity = previousOpacity
            pose().popMatrix()
            sync()
        }
    }
    private fun sync() {
        val m = pose()
        val current = canvas.transform
        val a = m.m00().toDouble(); val b = m.m01().toDouble(); val c = m.m10().toDouble()
        val d = m.m11().toDouble(); val x = m.m20().toDouble(); val y = m.m21().toDouble()
        if (current.a.toBits() != a.toBits() || current.b.toBits() != b.toBits() || current.c.toBits() != c.toBits() ||
            current.d.toBits() != d.toBits() || current.x.toBits() != x.toBits() || current.y.toBits() != y.toBits())
            canvas.transform = MenuCanvas.Transform(a, b, c, d, x, y)
        val scissor = scissorStack.peek()
        val clip = canvas.clip
        if (scissor == null) canvas.clip = null
        else if (clip == null || clip.x != scissor.position().x() || clip.y != scissor.position().y() ||
            clip.width != scissor.width() || clip.height != scissor.height())
            canvas.clip = MenuCanvas.Clip(scissor.position().x(), scissor.position().y(), scissor.width(), scissor.height())
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
    fun roundedRect(x: Int, y: Int, width: Int, height: Int, radius: Double, color: Int) {
        sync(); canvas.roundedRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, color)
    }
    fun roundedBorder(x: Int, y: Int, width: Int, height: Int, radius: Double, thickness: Double, color: Int) {
        sync(); canvas.roundedBorder(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, thickness, color)
    }
    fun shadow(x: Int, y: Int, width: Int, height: Int, radius: Double, sigma: Double, color: Int) {
        sync(); canvas.shadow(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, sigma, color)
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
        val progress = tooltipMotion?.show(lines) ?: 1.0
        val lift = (1 - progress) * me.mycellium.skymyce.config.misc.ThemeConfig.animationMode.profile.revealOffset * .4
        layer(alpha = progress, y = lift) {
            MenuTheme.shadow(this, left, top, width, height, .6)
            MenuTheme.box(this, left, top, width, height, MenuTheme.SIDEBAR)
            lines.forEachIndexed { i, line -> styledText(line, (left + 7).toDouble(), (top + 6 + i * lineHeight).toDouble(), MenuTheme.alpha(MenuTheme.TEXT)) }
        }
        pose().popMatrix()
    }
    fun styledText(text: FormattedCharSequence, x: Double, y: Double, color: Int, size: Double = 10.0) {
        styledText(menuTextRuns(text, color), x, y, size)
    }
    internal fun styledText(runs: List<MenuCanvas.Run>, x: Double, y: Double, size: Double = 10.0) {
        sync(); canvas.text(runs, x, y, size)
    }
}

/** Owned by a visible screen; no global tooltip timer survives its removal. */
class MenuTooltipMotion {
    private var key: List<List<MenuCanvas.Run>>? = null
    private var seen = false
    private val fade = MotionValue(0.0)
    private var outgoing: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    fun beginFrame() { seen = false }
    fun show(lines: List<FormattedCharSequence>): Double {
        seen = true
        val identity = lines.map { menuTextRuns(it, MenuTheme.alpha(MenuTheme.TEXT)) }
        if (key != identity) { key = identity; fade.snap(0.0); outgoing = null }
        fade.animateTo(1.0, 95, mode = me.mycellium.skymyce.config.misc.ThemeConfig.animationMode)
        return fade.sample().coerceIn(0.0, 1.0)
    }
    fun endFrame(canvas: MenuCanvas) {
        if (seen) return
        if (key != null) {
            outgoing = canvas.snapshot("tooltip"); key = null
            fade.snap(1.0)
        }
        fade.animateTo(0.0, 75, mode = me.mycellium.skymyce.config.misc.ThemeConfig.animationMode)
        val alpha = fade.sample().coerceIn(0.0, 1.0)
        if (alpha > 0.0) outgoing?.let { canvas.replay(it, alpha) } else outgoing = null
    }
    fun clear() { key = null; outgoing = null; fade.snap(0.0) }
}
