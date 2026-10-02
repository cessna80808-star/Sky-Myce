package me.mycellium.skymyce.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
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
    private data class Paint(val kind: Int, val values: List<Double>, val colors: List<Int>, val runs: List<Run>, val transform: Transform, val clip: Clip?, val picture: BufferedImage? = null, val opacity: Double = 1.0, val group: String? = null)
    var transform = Transform()
    var clip: Clip? = null
    var opacity = 1.0
    var group: String? = null
    /** A bounded inert raster captured on interaction, never a live screen, adapter, or texture. */
    class Snapshot internal constructor(internal val replay: (MenuCanvas, Double, Double, Double, Clip?) -> Unit)
    private val pending = ArrayList<Paint>()
    private var previous = emptyList<Paint>()
    private var previousScale = Double.NaN
    private var image: BufferedImage? = null
    private val gradients = object : LinkedHashMap<Paint, Pair<Rectangle, BufferedImage>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Paint, Pair<Rectangle, BufferedImage>>?) = size > 4
    }
    var revision = 0L
        private set
    /** Physical pixels changed by the last finish; null means no rasterization or upload is needed. */
    var dirtyRegion: Rectangle? = null
        private set

    fun begin() { pending.clear(); transform = Transform(); clip = null; opacity = 1.0; group = null }
    private fun add(kind: Int, values: List<Double>, colors: List<Int> = emptyList(), runs: List<Run> = emptyList()) {
        if (opacity > 0) pending += Paint(kind, values, colors, runs, transform, clip, opacity = opacity.coerceIn(0.0, 1.0), group = group)
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
        if (opacity > 0) pending += Paint(5, listOf(x, y, width, height), emptyList(), emptyList(), transform, clip, image, opacity.coerceIn(0.0, 1.0), group)
    }

    fun snapshot(group: String): Snapshot? {
        val prefix = "$group/"
        val commands = previous.filter { it.group == group || it.group?.startsWith(prefix) == true }
        if (commands.isEmpty()) return null
        val source = image ?: return null
        val scale = previousScale
        var area: Rectangle? = null
        commands.forEach { command -> physicalBounds(command, source.width, source.height, scale)?.let {
            area = area?.union(it) ?: it
        } }
        val region = area?.takeUnless { it.isEmpty } ?: return null
        val captured = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = captured.createGraphics()
        graphics.composite = AlphaComposite.Src
        graphics.drawImage(source, -region.x, -region.y, null)
        graphics.dispose()
        val values = listOf(region.x / scale, region.y / scale, region.width / scale, region.height / scale)
        // Flatten interrupted transitions once, so repeated A→B→C never grows a history of commands.
        return Snapshot { destination, alpha, dx, dy, boundary ->
            if (alpha > 0) destination.pending += Paint(5, values, emptyList(), emptyList(), Transform(x = dx, y = dy),
                boundary, captured, alpha.coerceIn(0.0, 1.0), destination.group)
        }
    }
    fun replay(snapshot: Snapshot, alpha: Double = 1.0, x: Double = 0.0, y: Double = 0.0, boundary: Clip? = null) =
        snapshot.replay(this, alpha * opacity, x, y, boundary)

    fun close() { pending.clear(); previous = emptyList(); gradients.clear(); image = null; dirtyRegion = null }

    /** Hover/tooltips only repaint their old and new bounds, including the underlying menu layers. */
    fun finish(width: Int, height: Int, scale: Double = 2.0): BufferedImage {
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val resized = image?.width != w || image?.height != h
        if (!resized && scale == previousScale && pending == previous) { dirtyRegion = null; return image!! }
        dirtyRegion = if (resized || scale != previousScale) Rectangle(0, 0, w, h) else damage(w, h, scale)
        if (dirtyRegion == null) {
            previous = pending.toList()
            return image!!
        }
        // Straight ARGB lets the compositor read our pixels without a full-frame unpremultiplication pass.
        if (resized) image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        if (resized || scale != previousScale) gradients.clear()
        val g = image!!.createGraphics()
        g.clip = dirtyRegion
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
            if (command.opacity < 1.0) paint.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, command.opacity.toFloat())
            draw(paint, command, scale)
            paint.dispose()
        }
        g.dispose()
        gradients.keys.retainAll(pending.filter { it.kind == 2 }.map { gradientKey(it) }.toSet())
        previous = pending.toList(); previousScale = scale; revision++
        return image!!
    }

    private fun damage(width: Int, height: Int, scale: Double): Rectangle? {
        var first = 0
        while (first < minOf(previous.size, pending.size) && previous[first] == pending[first]) first++
        var oldLast = previous.lastIndex
        var newLast = pending.lastIndex
        while (oldLast >= first && newLast >= first && previous[oldLast] == pending[newLast]) { oldLast--; newLast-- }
        var area: Rectangle? = null
        fun include(paint: Paint) {
            val region = physicalBounds(paint, width, height, scale) ?: return
            area = area?.union(region) ?: region
        }
        var oldIndex = first
        var newIndex = first
        while (oldIndex <= oldLast && newIndex <= newLast) {
            if (previous[oldIndex] == pending[newIndex]) { oldIndex++; newIndex++; continue }
            if (previous.size == pending.size) {
                include(previous[oldIndex++]); include(pending[newIndex++]); continue
            }
            // Bound lookahead so large, entirely changed lists cannot turn hover work quadratic.
            val oldMatch = (oldIndex + 1..minOf(oldLast, oldIndex + 32)).firstOrNull { previous[it] == pending[newIndex] }
            val newMatch = (newIndex + 1..minOf(newLast, newIndex + 32)).firstOrNull { pending[it] == previous[oldIndex] }
            when {
                oldMatch != null && (newMatch == null || oldMatch - oldIndex <= newMatch - newIndex) ->
                    while (oldIndex < oldMatch) include(previous[oldIndex++])
                newMatch != null -> while (newIndex < newMatch) include(pending[newIndex++])
                else -> { include(previous[oldIndex++]); include(pending[newIndex++]) }
            }
        }
        while (oldIndex <= oldLast) include(previous[oldIndex++])
        while (newIndex <= newLast) include(pending[newIndex++])
        return area
    }

    private fun physicalBounds(paint: Paint, width: Int, height: Int, scale: Double): Rectangle? {
        val t = paint.transform
        var bounds = AffineTransform(t.a, t.b, t.c, t.d, t.x, t.y).createTransformedShape(bounds(paint)).bounds2D
        // Fractional clip edges may include a pixel just beyond the exact logical shape bounds.
        val margin = 2.0 / scale
        bounds = Rectangle2D.Double(bounds.x - margin, bounds.y - margin, bounds.width + margin * 2, bounds.height + margin * 2)
        paint.clip?.let { bounds = bounds.createIntersection(Rectangle2D.Double(it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())) }
        if (bounds.isEmpty) return null
        val x = kotlin.math.floor(bounds.minX * scale - 2).coerceIn(0.0, width.toDouble()).toInt()
        val y = kotlin.math.floor(bounds.minY * scale - 2).coerceIn(0.0, height.toDouble()).toInt()
        val right = kotlin.math.ceil(bounds.maxX * scale + 2).coerceIn(0.0, width.toDouble()).toInt()
        val bottom = kotlin.math.ceil(bounds.maxY * scale + 2).coerceIn(0.0, height.toDouble()).toInt()
        return if (right > x && bottom > y) Rectangle(x, y, right - x, bottom - y) else null
    }

    private fun bounds(paint: Paint): Rectangle2D {
        val v = paint.values
        return when (paint.kind) {
            0, 2, 5 -> Rectangle2D.Double(v[0], v[1], v[2], v[3])
            1 -> Rectangle2D.Double(v[0] - v[2], v[1] - v[2], v[2] * 2, v[2] * 2)
            4 -> Rectangle2D.Double(minOf(v[0], v[2]) - v[4] / 2, minOf(v[1], v[3]) - v[4] / 2,
                kotlin.math.abs(v[2] - v[0]) + v[4], kotlin.math.abs(v[3] - v[1]) + v[4])
            else -> {
                var area: Rectangle2D? = null
                var left = v[0]
                val baseline = v[1] + ascent(v[2])
                fun include(region: Rectangle2D) { if (!region.isEmpty) area = area?.createUnion(region) ?: region }
                paint.runs.forEach { run ->
                    val start = left
                    var index = 0
                    while (index < run.text.length) {
                        val codepoint = run.text.codePointAt(index)
                        val glyph = glyph(codepoint, run.bold, run.italic, v[2])
                        val b = glyph.bounds
                        include(Rectangle2D.Double(left + b.x, baseline + b.y, b.width, b.height))
                        left += glyph.width
                        index += Character.charCount(codepoint)
                    }
                    if (run.underline) include(Rectangle2D.Double(start, baseline + 1, left - start, .6))
                }
                area ?: Rectangle2D.Double()
            }
        }
    }

    private fun visible(g: Graphics2D, x: Double, y: Double, width: Double, height: Double): Boolean =
        width > 0 && height > 0 && g.hitClip(kotlin.math.floor(x).toInt() - 2, kotlin.math.floor(y).toInt() - 2,
            kotlin.math.ceil(width).toInt() + 4, kotlin.math.ceil(height).toInt() + 4)

    private fun draw(g: Graphics2D, p: Paint, scale: Double) {
        val v = p.values
        when (p.kind) {
            0 -> {
                if (!visible(g, v[0], v[1], v[2], v[3])) return
                g.color = Color(p.colors[0], true); g.fill(Rectangle2D.Double(v[0], v[1], v[2], v[3]))
            }
            1 -> {
                g.color = Color(p.colors[0], true)
                val r = v[2]
                if (!visible(g, v[0] - r, v[1] - r, r * 2, r * 2)) return
                val outer = if (v[4] - v[3] >= 360.0) Ellipse2D.Double(v[0] - r, v[1] - r, r * 2, r * 2)
                    else Arc2D.Double(v[0] - r, v[1] - r, r * 2, r * 2, 180.0 - v[3], -(v[4] - v[3]), Arc2D.PIE)
                val area = java.awt.geom.Area(outer)
                if (v[5] > 0) area.subtract(java.awt.geom.Area(Ellipse2D.Double(v[0] - v[5], v[1] - v[5], v[5] * 2, v[5] * 2)))
                g.fill(area)
            }
            2 -> {
                if (!visible(g, v[0], v[1], v[2], v[3])) return
                // Rasterize in local coordinates once. Motion/opacity/clip reuse the same layer.
                val (region, layer) = gradients.getOrPut(gradientKey(p)) {
                    val region = Rectangle(kotlin.math.floor(v[0] * scale).toInt(), kotlin.math.floor(v[1] * scale).toInt(),
                        kotlin.math.ceil(v[2] * scale + 2).toInt().coerceAtLeast(1), kotlin.math.ceil(v[3] * scale + 2).toInt().coerceAtLeast(1))
                    val layer = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
                    val paint = layer.createGraphics()
                    paint.setRenderingHints(g.renderingHints)
                    paint.translate(-region.x.toDouble(), -region.y.toDouble()); paint.scale(scale, scale)
                    drawGradient(paint, p)
                    paint.dispose()
                    region to layer
                }
                g.drawImage(layer, AffineTransform(1 / scale, 0.0, 0.0, 1 / scale, region.x / scale, region.y / scale), null)
            }
            3 -> {
                var left = v[0]
                val baseline = v[1] + ascent(v[2])
                p.runs.forEach { run ->
                    g.color = Color(run.color, true)
                    val start = left
                    var index = 0
                    while (index < run.text.length) {
                        val codepoint = run.text.codePointAt(index)
                        val glyph = glyph(codepoint, run.bold, run.italic, v[2])
                        val bounds = glyph.bounds
                        if (g.hitClip(kotlin.math.floor(left + bounds.x).toInt() - 1, kotlin.math.floor(baseline + bounds.y).toInt() - 1,
                                kotlin.math.ceil(bounds.width).toInt() + 3, kotlin.math.ceil(bounds.height).toInt() + 3))
                            g.drawGlyphVector(glyph.vector, left.toFloat(), baseline.toFloat())
                        left += glyph.width
                        index += Character.charCount(codepoint)
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
                if (!visible(g, v[0], v[1], v[2], v[3])) return
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(p.picture, AffineTransform(v[2] / p.picture!!.width, 0.0, 0.0, v[3] / p.picture.height, v[0], v[1]), null)
            }
        }
    }

    private fun drawGradient(g: Graphics2D, p: Paint) {
        val v = p.values
        val clip = g.clipBounds
        val first = kotlin.math.floor((clip?.minY ?: v[1]) - v[1] - 1).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil((clip?.maxY ?: (v[1] + v[3])) - v[1] + 1).toInt().coerceAtMost(kotlin.math.ceil(v[3]).toInt())
        for (row in first until last) {
            val amount = row / v[3]
            g.paint = java.awt.GradientPaint(v[0].toFloat(), 0f, Color(mix(p.colors[0], p.colors[3], amount), true),
                (v[0] + v[2]).toFloat(), 0f, Color(mix(p.colors[1], p.colors[2], amount), true))
            g.fill(Rectangle2D.Double(v[0], v[1] + row, v[2], minOf(1.0, v[3] - row)))
        }
    }
    companion object {
        private fun gradientKey(paint: Paint) = paint.copy(transform = Transform(), clip = null, opacity = 1.0, group = null)
        private val font: Font = MenuCanvas::class.java.getResourceAsStream("/assets/skymyce/font/lato-regular.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(10f)
        private val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        private val frc = FontRenderContext(null, true, true)
        private data class FontKey(val size: Float, val style: Int, val fallback: Boolean)
        private data class GlyphKey(val codepoint: Int, val size: Float, val style: Int)
        private data class Glyph(val vector: GlyphVector, val width: Double, val bounds: Rectangle2D)
        private data class LineMetrics(val ascent: Double, val height: Int)
        // User input can contain arbitrary Unicode and sizes; retain no whole strings and bound every cache.
        private val fonts = object : LinkedHashMap<FontKey, Font>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FontKey, Font>?) = size > 64
        }
        private val glyphs = object : LinkedHashMap<GlyphKey, Glyph>(4096, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphKey, Glyph>?) = size > 4096
        }
        private val lines = object : LinkedHashMap<Float, LineMetrics>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, LineMetrics>?) = size > 32
        }
        private fun glyph(codepoint: Int, bold: Boolean, italic: Boolean, size: Double): Glyph {
            val key = GlyphKey(codepoint, size.toFloat(), (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0))
            return glyphs.getOrPut(key) {
                val text = String(Character.toChars(codepoint))
                val fontKey = FontKey(key.size, key.style, font.canDisplayUpTo(text) >= 0)
                val styled = fonts.getOrPut(fontKey) { (if (fontKey.fallback) fallback else font).deriveFont(key.style, key.size) }
                val vector = styled.createGlyphVector(frc, text)
                Glyph(vector, styled.getStringBounds(text, frc).width, vector.visualBounds)
            }
        }
        private fun metrics(size: Double): LineMetrics = lines.getOrPut(size.toFloat()) {
            val a = font.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            val b = fallback.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            val ascent = maxOf(a.ascent, b.ascent).toDouble()
            LineMetrics(ascent, kotlin.math.ceil(ascent + maxOf(a.descent, b.descent) + maxOf(a.leading, b.leading) + 1).toInt())
        }
        private fun ascent(size: Double) = metrics(size).ascent
        fun lineHeight(size: Double = 10.0): Int = metrics(size).height
        fun textWidth(codepoint: Int, bold: Boolean = false, italic: Boolean = false, size: Double = 10.0): Double =
            glyph(codepoint, bold, italic, size).width
        fun textWidth(text: String, bold: Boolean = false, italic: Boolean = false, size: Double = 10.0): Double {
            var width = 0.0
            var index = 0
            while (index < text.length) {
                val codepoint = text.codePointAt(index)
                width += glyph(codepoint, bold, italic, size).width
                index += Character.charCount(codepoint)
            }
            return width
        }
        private fun mix(a: Int, b: Int, f: Double): Int {
            var result = 0
            for (shift in listOf(0, 8, 16, 24)) result = result or ((((a ushr shift and 255) * (1 - f) + (b ushr shift and 255) * f).toInt()) shl shift)
            return result
        }
    }
}
