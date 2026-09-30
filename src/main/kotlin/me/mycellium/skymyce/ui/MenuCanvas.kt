package me.mycellium.skymyce.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** The menu renderer. No Minecraft fonts, sprites, widgets or render pipelines are used here. */
class MenuCanvas {
    data class Transform(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0)
    data class Clip(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Run(val text: String, val color: Int, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false)
    private data class Paint(val kind: Int, val values: List<Double>, val colors: List<Int>, val runs: List<Run>, val transform: Transform, val clip: Clip?, val picture: BufferedImage? = null)
    var transform = Transform()
    var clip: Clip? = null
    private val pending = ArrayList<Paint>()
    private var previous = emptyList<Paint>()
    private var image: BufferedImage? = null
    var revision = 0L
        private set

    fun begin() { pending.clear(); transform = Transform(); clip = null }
    private fun add(kind: Int, values: List<Double>, colors: List<Int> = emptyList(), runs: List<Run> = emptyList()) {
        pending += Paint(kind, values, colors, runs, transform, clip)
    }
    fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
        if (width > 0 && height > 0 && color ushr 24 != 0) add(0, listOf(x, y, width, height), listOf(color))
    }
    fun arc(x: Double, y: Double, radius: Double, from: Double, to: Double, color: Int, innerRadius: Double = 0.0) =
        add(1, listOf(x, y, radius, from, to, innerRadius), listOf(color))
    fun gradient(x: Double, y: Double, width: Double, height: Double, tl: Int, tr: Int, br: Int, bl: Int) =
        add(2, listOf(x, y, width, height), listOf(tl, tr, br, bl))
    fun text(runs: List<Run>, x: Double, y: Double, size: Double = 10.0) = add(3, listOf(x, y, size), runs = runs)
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, thickness: Double, color: Int) = add(4, listOf(x1, y1, x2, y2, thickness), listOf(color))
    fun picture(image: BufferedImage, x: Double, y: Double, width: Double, height: Double) {
        pending += Paint(5, listOf(x, y, width, height), emptyList(), emptyList(), transform, clip, image)
    }

    /** Identical frames do no rasterization or upload. Animation and data changes invalidate naturally. */
    fun finish(width: Int, height: Int, scale: Double = 2.0): BufferedImage {
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (image?.width == w && image?.height == h && pending == previous) return image!!
        if (image?.width != w || image?.height != h) image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image!!.createGraphics()
        g.composite = AlphaComposite.Clear; g.fillRect(0, 0, w, h); g.composite = AlphaComposite.SrcOver
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(scale, scale)
        pending.forEach { command ->
            val paint = g.create() as Graphics2D
            command.clip?.let { paint.clip(Rectangle2D.Double(it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())) }
            val t = command.transform
            paint.transform(AffineTransform(t.a, t.b, t.c, t.d, t.x, t.y))
            draw(paint, command)
            paint.dispose()
        }
        g.dispose()
        previous = pending.toList(); revision++
        return image!!
    }
    private fun draw(g: Graphics2D, p: Paint) {
        val v = p.values
        when (p.kind) {
            0 -> { g.color = Color(p.colors[0], true); g.fill(Rectangle2D.Double(v[0], v[1], v[2], v[3])) }
            1 -> {
                g.color = Color(p.colors[0], true)
                val r = v[2]
                val outer = if (v[4] - v[3] >= 360.0) Ellipse2D.Double(v[0] - r, v[1] - r, r * 2, r * 2)
                    else Arc2D.Double(v[0] - r, v[1] - r, r * 2, r * 2, 180.0 - v[3], -(v[4] - v[3]), Arc2D.PIE)
                val area = java.awt.geom.Area(outer)
                if (v[5] > 0) area.subtract(java.awt.geom.Area(Ellipse2D.Double(v[0] - v[5], v[1] - v[5], v[5] * 2, v[5] * 2)))
                g.fill(area)
            }
            2 -> {
                if (v[2] <= 0 || v[3] <= 0) return
                for (row in 0 until kotlin.math.ceil(v[3]).toInt()) {
                    val amount = row / v[3]
                    g.paint = java.awt.GradientPaint(v[0].toFloat(), 0f, Color(mix(p.colors[0], p.colors[3], amount), true),
                        (v[0] + v[2]).toFloat(), 0f, Color(mix(p.colors[1], p.colors[2], amount), true))
                    g.fill(Rectangle2D.Double(v[0], v[1] + row, v[2], minOf(1.0, v[3] - row)))
                }
            }
            3 -> {
                var left = v[0]
                val baseline = v[1] + ascent(v[2])
                p.runs.forEach { run ->
                    g.color = Color(run.color, true)
                    val start = left
                    run.text.codePoints().forEach { codepoint ->
                        val glyph = String(Character.toChars(codepoint))
                        g.font = styledFont(glyph, run.bold, run.italic, v[2])
                        g.drawString(glyph, left.toFloat(), baseline.toFloat())
                        left += g.font.getStringBounds(glyph, frc).width
                    }
                    if (run.underline) g.fill(Rectangle2D.Double(start, baseline + 1, left - start, .6))
                }
            }
            4 -> {
                g.color = Color(p.colors[0], true)
                g.stroke = java.awt.BasicStroke(v[4].toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.draw(java.awt.geom.Line2D.Double(v[0], v[1], v[2], v[3]))
            }
            5 -> {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(p.picture, AffineTransform(v[2] / p.picture!!.width, 0.0, 0.0, v[3] / p.picture.height, v[0], v[1]), null)
            }
        }
    }
    companion object {
        private val font: Font = MenuCanvas::class.java.getResourceAsStream("/assets/skymyce/font/lato-regular.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(10f)
        private val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        private val frc = FontRenderContext(null, true, true)
        private fun styledFont(text: String, bold: Boolean, italic: Boolean, size: Double) =
            (if (font.canDisplayUpTo(text) < 0) font else fallback).deriveFont((if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0), size.toFloat())
        private fun ascent(size: Double) = maxOf(font.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).ascent,
            fallback.deriveFont(size.toFloat()).getLineMetrics("Ag", frc).ascent).toDouble()
        fun lineHeight(size: Double = 10.0): Int {
            val a = font.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            val b = fallback.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            return kotlin.math.ceil(ascent(size) + maxOf(a.descent, b.descent) + maxOf(a.leading, b.leading) + 1).toInt()
        }
        fun textWidth(text: String, bold: Boolean = false, italic: Boolean = false, size: Double = 10.0): Double =
            text.codePoints().toArray().sumOf { codepoint ->
                val glyph = String(Character.toChars(codepoint))
                styledFont(glyph, bold, italic, size).getStringBounds(glyph, frc).width
            }
        private fun mix(a: Int, b: Int, f: Double): Int {
            var result = 0
            for (shift in listOf(0, 8, 16, 24)) result = result or ((((a ushr shift and 255) * (1 - f) + (b ushr shift and 255) * f).toInt()) shl shift)
            return result
        }
    }
}
