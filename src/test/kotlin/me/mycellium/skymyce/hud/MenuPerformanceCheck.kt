package me.mycellium.skymyce.hud

import me.mycellium.skymyce.ui.MenuCanvas
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** Compare the cached rasterizer with the original per-codepoint Java2D path. */
internal fun checkMenuPerformance() {
    val regular = MenuCanvas::class.java.getResourceAsStream("/assets/skymyce/font/lato-regular.ttf")!!.use {
        Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(10f)
    }
    val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 10)
    val frc = FontRenderContext(null, true, true)
    fun font(text: String, bold: Boolean, italic: Boolean, size: Double): Font =
        (if (regular.canDisplayUpTo(text) < 0) regular else fallback).deriveFont(
            (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0), size.toFloat())
    fun width(text: String, bold: Boolean, italic: Boolean, size: Double) = text.codePoints().toArray().sumOf {
        val glyph = String(Character.toChars(it))
        font(glyph, bold, italic, size).getStringBounds(glyph, frc).width
    }
    fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val samples = listOf("Wi AV office café gjQ", "Русский 中文 日本語 ✓ 🗿 😀", "a\u0301 e\u0301 Ж ✓ 💰", "0123456789 /.,_-")
    for (size in listOf(8.0, 10.0, 12.0, 17.25)) for (style in 0..3) for (text in samples) {
        val bold = style and 1 != 0; val italic = style and 2 != 0
        check(MenuCanvas.textWidth(text, bold, italic, size) == width(text, bold, italic, size)) {
            "Cached advances changed wrapping or fallback at size $size/style $style"
        }
        check(text.codePoints().toArray().sumOf { MenuCanvas.textWidth(it, bold, italic, size) } == width(text, bold, italic, size))
    }
    val runs = samples.mapIndexed { index, text ->
        MenuCanvas.Run(text + "  ", if (index % 2 == 0) 0xC0F2E9EC.toInt() else 0x88E9A5B4.toInt(),
            index and 1 != 0, index and 2 != 0, underline = true)
    }
    fun reference(size: Double, scale: Double, transform: MenuCanvas.Transform, clip: MenuCanvas.Clip,
                  premultiplied: Boolean = false): BufferedImage {
        val image = BufferedImage((440 * scale).toInt(), (80 * scale).toInt(),
            if (premultiplied) BufferedImage.TYPE_INT_ARGB_PRE else BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.composite = AlphaComposite.SrcOver
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(scale, scale)
        g.clip(Rectangle2D.Double(clip.x.toDouble(), clip.y.toDouble(), clip.width.toDouble(), clip.height.toDouble()))
        g.transform(AffineTransform(transform.a, transform.b, transform.c, transform.d, transform.x, transform.y))
        var left = 7.25
        val baseline = 4.5 + maxOf(regular.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).ascent,
            fallback.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).ascent)
        runs.forEach { run ->
            g.color = Color(run.color, true)
            val start = left
            run.text.codePoints().forEach {
                val glyph = String(Character.toChars(it))
                g.font = font(glyph, run.bold, run.italic, size)
                g.drawString(glyph, left.toFloat(), baseline.toFloat())
                left += g.font.getStringBounds(glyph, frc).width
            }
            if (run.underline) g.fill(Rectangle2D.Double(start, baseline + 1, left - start, .6))
        }
        g.dispose()
        return image
    }
    fun paint(size: Double, scale: Double, transform: MenuCanvas.Transform, clip: MenuCanvas.Clip): IntArray = MenuCanvas().run {
        begin(); this.transform = transform; this.clip = clip
        text(runs, 7.25, 4.5, size)
        val image = finish(440, 80, scale)
        check(image.type == BufferedImage.TYPE_INT_ARGB) { "Canvas requires straight ARGB for the fast upload path" }
        pixels(image)
    }
    val transforms = listOf(MenuCanvas.Transform(), MenuCanvas.Transform(1.05, .07, -.02, .96, 17.0, 1.0))
    for (scale in listOf(1.0, 1.5, 2.0)) for (size in listOf(9.0, 10.0, 17.25)) for (transform in transforms) {
        for (clip in listOf(MenuCanvas.Clip(0, 0, 440, 80), MenuCanvas.Clip(17, 8, 153, 13), MenuCanvas.Clip(350, 4, 40, 10))) {
            val actual = paint(size, scale, transform, clip)
            check(actual.contentEquals(pixels(reference(size, scale, transform, clip)))) {
                "Cached drawing/culling changed pixels at scale $scale/size $size/clip $clip"
            }
            val old = pixels(reference(size, scale, transform, clip, premultiplied = true))
            check(actual.indices.all { actual[it] ushr 24 == old[it] ushr 24 }) { "Straight ARGB changed glyph alpha coverage" }
        }
    }
    // Arbitrary input and heading sizes evict old entries instead of retaining every string/glyph forever.
    val original = paint(10.0, 2.0, MenuCanvas.Transform(), MenuCanvas.Clip(0, 0, 440, 80))
    for (codepoint in 0x3000 until 0x4400) {
        MenuCanvas.textWidth(codepoint, codepoint and 1 != 0, codepoint and 2 != 0, 8.0 + codepoint % 80 / 4.0)
        MenuCanvas.lineHeight(8.0 + codepoint % 80 / 4.0)
    }
    for ((name, limit) in listOf("glyphs" to 4096, "fonts" to 64, "lines" to 32)) {
        val field = MenuCanvas::class.java.getDeclaredField(name).apply { isAccessible = true }
        check((field.get(null) as Map<*, *>).size <= limit) { "$name cache grew beyond $limit" }
    }
    check(original.contentEquals(paint(10.0, 2.0, MenuCanvas.Transform(), MenuCanvas.Clip(0, 0, 440, 80)))) {
        "Cache eviction changed glyph output"
    }
    val canvas = MenuCanvas()
    fun capture(color: Int = -1) { canvas.begin(); canvas.text(listOf(MenuCanvas.Run("Resize ✓", color)), 5.25, 3.5) }
    capture()
    val first = canvas.finish(160, 80, 2.0); val firstPixels = pixels(first); val firstRevision = canvas.revision
    capture()
    check(canvas.finish(160, 80, 2.0) === first && canvas.revision == firstRevision) { "Steady frame repainted" }
    // Same physical dimensions, different logical scale: size alone cannot validate the previous frame.
    val resized = canvas.finish(320, 160, 1.0)
    check(canvas.revision == firstRevision + 1 && !firstPixels.contentEquals(pixels(resized))) { "Scale change reused stale pixels" }
    val beforeColor = pixels(resized); capture(0xB0DF8196.toInt())
    check(!beforeColor.contentEquals(pixels(canvas.finish(320, 160, 1.0)))) { "Color change reused stale glyph pixels" }
    println("Menu performance checks passed: cached metrics/pixels, fallback/styles, clipping, bounded eviction, alpha and scale invalidation")
}
