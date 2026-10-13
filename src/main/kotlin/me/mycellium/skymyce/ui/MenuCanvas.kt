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
import java.awt.image.DataBufferInt

/** Retained menu commands shared by the native GPU painter and compatibility fallback. */
class MenuCanvas {
    data class Transform(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0)
    data class Clip(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Run(val text: String, val color: Int, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false)
    internal data class Paint(val kind: Int, val x: Double, val y: Double, val width: Double = 0.0,
        val height: Double = 0.0, val extra: Double = 0.0, val inner: Double = 0.0,
        val color: Int = 0, val topRight: Int = 0, val bottomRight: Int = 0, val bottomLeft: Int = 0,
        val runs: List<Run> = emptyList(), val transform: Transform, val clip: Clip?,
        val picture: BufferedImage? = null, val opacity: Double = 1.0, val group: String? = null,
        val snapshot: SkijaMenuSnapshot? = null, val blend: Blend? = null) {
        // The six geometry slots retain each command's original interpretation without boxed lists.
        operator fun get(index: Int): Double = when (index) {
            0 -> x; 1 -> y; 2 -> width; 3 -> height; 4 -> extra; 5 -> inner
            else -> throw IndexOutOfBoundsException(index)
        }
    }
    internal data class Blend(val outgoing: List<Paint>, val incoming: List<Paint>, val amount: Double)
    class Frame internal constructor(val width: Int, val height: Int, val scale: Double, val revision: Long,
                                     internal val commands: List<Paint>)
    var transform = Transform()
    var clip: Clip? = null
    var opacity = 1.0
    var group: String? = null
    /** A bounded inert raster captured on interaction, never a live screen, adapter, or texture. */
    class Snapshot internal constructor(private val resource: AutoCloseable? = null,
        internal val replay: (MenuCanvas, Double, Double, Double, Clip?) -> Unit) : AutoCloseable {
        private var references = 1
        fun retain(): Snapshot { check(references > 0); references++; return this }
        override fun close() { if (references > 0 && --references == 0) resource?.close() }
    }
    private val pending = ArrayList<Paint>()
    private var previous = emptyList<Paint>()
    private var previousScale = Double.NaN
    private var image: BufferedImage? = null
    private var frame: Frame? = null
    private var transitionOld: BufferedImage? = null
    private var transitionNew: BufferedImage? = null
    private var transitionBlend: BufferedImage? = null
    private var transitionRevision = 0L
    private val transitionGradients = HashSet<Paint>()
    private val gradients = object : LinkedHashMap<Paint, Pair<Rectangle, BufferedImage>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Paint, Pair<Rectangle, BufferedImage>>?) = size > 4
    }
    var revision = 0L
        private set
    /** Physical pixels changed by the last finish; null means no rasterization or upload is needed. */
    var dirtyRegion: Rectangle? = null
        private set

    fun begin() { pending.clear(); transitionGradients.clear(); transform = Transform(); clip = null; opacity = 1.0; group = null }
    private fun add(kind: Int, x: Double, y: Double, width: Double = 0.0, height: Double = 0.0,
                    extra: Double = 0.0, inner: Double = 0.0, color: Int = 0, topRight: Int = 0,
                    bottomRight: Int = 0, bottomLeft: Int = 0, runs: List<Run> = emptyList(), picture: BufferedImage? = null) {
        if (opacity <= 0 || opacity.isNaN()) return
        val alpha = opacity.coerceIn(0.0, 1.0)
        val retained = previous.getOrNull(pending.size)
        // Reuse only an identical immutable command at the same position, never a mutable pool.
        if (retained != null && retained.kind == kind && retained.x.toBits() == x.toBits() && retained.y.toBits() == y.toBits() &&
            retained.width.toBits() == width.toBits() && retained.height.toBits() == height.toBits() &&
            retained.extra.toBits() == extra.toBits() && retained.inner.toBits() == inner.toBits() &&
            retained.color == color && retained.topRight == topRight && retained.bottomRight == bottomRight && retained.bottomLeft == bottomLeft &&
            retained.runs == runs && retained.transform == transform && retained.clip == clip && retained.picture === picture &&
            retained.opacity.toBits() == alpha.toBits() && retained.group == group) {
            pending += retained
        } else pending += Paint(kind, x, y, width, height, extra, inner, color, topRight, bottomRight,
            bottomLeft, runs, transform, clip, picture = picture, opacity = alpha, group = group)
    }
    fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
        if (width > 0 && height > 0 && color ushr 24 != 0) add(0, x, y, width, height, color = color)
    }
    fun arc(x: Double, y: Double, radius: Double, from: Double, to: Double, color: Int, innerRadius: Double = 0.0) =
        add(1, x, y, radius, from, to, innerRadius, color)
    fun gradient(x: Double, y: Double, width: Double, height: Double, tl: Int, tr: Int, br: Int, bl: Int) =
        add(2, x, y, width, height, color = tl, topRight = tr, bottomRight = br, bottomLeft = bl)
    fun text(runs: List<Run>, x: Double, y: Double, size: Double = 10.0) = add(3, x, y, size, runs = runs)
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, thickness: Double, color: Int) = add(4, x1, y1, x2, y2, thickness, color = color)
    fun picture(image: BufferedImage, x: Double, y: Double, width: Double, height: Double) {
        add(5, x, y, width, height, picture = image)
    }
    fun roundedRect(x: Double, y: Double, width: Double, height: Double, radius: Double, color: Int) =
        add(7, x, y, width, height, radius.coerceIn(0.0, minOf(width, height).coerceAtLeast(0.0) / 2), color = color)
    fun roundedBorder(x: Double, y: Double, width: Double, height: Double, radius: Double, thickness: Double, color: Int) =
        add(8, x, y, width, height, radius.coerceIn(0.0, minOf(width, height).coerceAtLeast(0.0) / 2), thickness.coerceAtLeast(0.0), color)
    fun shadow(x: Double, y: Double, width: Double, height: Double, radius: Double, sigma: Double, color: Int) {
        if (width > 0 && height > 0 && sigma.isFinite() && sigma > 0)
            add(10, x, y, width, height, radius.coerceAtLeast(0.0), sigma.coerceAtMost(8.0), color)
    }

    fun snapshot(group: String? = null): Snapshot? {
        val prefix = "$group/"
        val commands = previous.filter { if (group == null) it.group != "tooltip" && it.group != "toast"
            else it.group == group || it.group?.startsWith(prefix) == true }
        if (commands.isEmpty()) return null
        frame?.let { source ->
            var area: Rectangle? = null
            commands.forEach { physicalBounds(it, source.width, source.height, source.scale)?.let { bounds -> area = area?.union(bounds) ?: bounds } }
            val region = area?.takeUnless { it.isEmpty } ?: return null
            val capture = SkijaMenuSnapshot(commands, region, source.scale)
            val scale = source.scale
            return Snapshot(capture) { destination, alpha, dx, dy, boundary ->
                if (alpha > 0) destination.pending += Paint(6, region.x / scale, region.y / scale, region.width / scale, region.height / scale,
                    transform = Transform(x = dx, y = dy), clip = boundary, snapshot = capture,
                    opacity = alpha.coerceIn(0.0, 1.0), group = destination.group)
            }
        }
        val source = image ?: return null
        val scale = previousScale
        var area: Rectangle? = null
        commands.forEach { command -> physicalBounds(command, source.width, source.height, scale)?.let {
            area = area?.union(it) ?: it
        } }
        val region = area?.takeUnless { it.isEmpty } ?: return null
        val captured = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = captured.createGraphics()
        graphics.translate(-region.x, -region.y)
        prepare(graphics, scale)
        // Capture this layer's paint, not the composited screen beneath/above its bounds.
        // Baking the backdrop into every page makes rapid transitions progressively darker.
        commands.forEach { paint(graphics, it, scale) }
        graphics.dispose()
        val values = listOf(region.x / scale, region.y / scale, region.width / scale, region.height / scale)
        // Flatten interrupted transitions once, so repeated A→B→C never grows a history of commands.
        return Snapshot { destination, alpha, dx, dy, boundary ->
            if (alpha > 0) destination.pending += Paint(5, values[0], values[1], values[2], values[3],
                transform = Transform(x = dx, y = dy), clip = boundary, picture = captured,
                opacity = alpha.coerceIn(0.0, 1.0), group = destination.group)
        }
    }
    fun replay(snapshot: Snapshot, alpha: Double = 1.0, x: Double = 0.0, y: Double = 0.0, boundary: Clip? = null) =
        snapshot.replay(this, alpha * opacity, x, y, boundary)

    /** Blend isolated premultiplied layers once, so translucent panels retain their opacity during a swap. */
    fun transition(snapshot: Snapshot, progress: Double, boundary: Clip, outgoingX: Double = 0.0, drawIncoming: () -> Unit) {
        val amount = progress.coerceIn(0.0, 1.0)
        if (amount >= 1.0 || !previousScale.isFinite() || boundary.width <= 0 || boundary.height <= 0) { drawIncoming(); return }
        val first = pending.size
        drawIncoming()
        val incoming = pending.subList(first, pending.size).toList()
        incoming.filter { it.kind == 2 }.forEach { transitionGradients += gradientKey(it) }
        pending.subList(first, pending.size).clear()
        // The capture already contains its displayed parent opacity; apply it exactly once.
        snapshot.replay(this, 1.0, outgoingX, 0.0, boundary)
        val outgoing = pending.subList(first, pending.size).toList()
        pending.subList(first, pending.size).clear()
        if (frame != null) {
            pending += Paint(9, boundary.x.toDouble(), boundary.y.toDouble(), boundary.width.toDouble(), boundary.height.toDouble(),
                transform = Transform(), clip = boundary, group = group, blend = Blend(outgoing, incoming, amount))
            return
        }
        val scale = previousScale
        val left = kotlin.math.floor(boundary.x * scale).toInt()
        val top = kotlin.math.floor(boundary.y * scale).toInt()
        val w = (kotlin.math.ceil((boundary.x + boundary.width) * scale).toInt() - left).coerceAtLeast(1)
        val h = (kotlin.math.ceil((boundary.y + boundary.height) * scale).toInt() - top).coerceAtLeast(1)
        if (transitionBlend?.width != w || transitionBlend?.height != h) {
            transitionOld = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
            transitionNew = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
            transitionBlend = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        }
        fun rasterize(target: BufferedImage, commands: List<Paint>) {
            val graphics = target.createGraphics()
            try {
                graphics.composite = AlphaComposite.Clear; graphics.fillRect(0, 0, w, h)
                graphics.composite = AlphaComposite.SrcOver
                graphics.translate(-left, -top); prepare(graphics, scale)
                graphics.clip(Rectangle2D.Double(boundary.x.toDouble(), boundary.y.toDouble(), boundary.width.toDouble(), boundary.height.toDouble()))
                commands.forEach { paint(graphics, it, scale) }
            } finally { graphics.dispose() }
        }
        rasterize(transitionOld!!, outgoing); rasterize(transitionNew!!, incoming)
        val oldPixels = (transitionOld!!.raster.dataBuffer as DataBufferInt).data
        val newPixels = (transitionNew!!.raster.dataBuffer as DataBufferInt).data
        val blended = (transitionBlend!!.raster.dataBuffer as DataBufferInt).data
        val weight = (amount * 256).toInt(); val inverse = 256 - weight
        for (index in blended.indices) {
            val a = oldPixels[index]; val b = newPixels[index]
            val rb = (((a and 0x00FF00FF) * inverse + (b and 0x00FF00FF) * weight) ushr 8) and 0x00FF00FF
            val ag = (((a ushr 8 and 0x00FF00FF) * inverse + (b ushr 8 and 0x00FF00FF) * weight) ushr 8) and 0x00FF00FF
            blended[index] = rb or (ag shl 8)
        }
        // This reusable raster changes in place; its command revision must invalidate the damage cache.
        pending += Paint(5, left / scale, top / scale, w / scale, h / scale, extra = (++transitionRevision).toDouble(),
            transform = Transform(), clip = boundary, picture = transitionBlend, group = group)
    }

    fun close() {
        pending.clear(); previous = emptyList(); gradients.clear(); transitionGradients.clear(); image = null; frame = null; dirtyRegion = null
        releaseTransition()
    }

    fun releaseTransition() { transitionOld = null; transitionNew = null; transitionBlend = null }

    /** Cold developer diagnostics; no map allocation occurs during ordinary paint. */
    fun cacheSizes(): Map<String, Long> = mapOf(
        "commands.pending" to pending.size.toLong(), "commands.retained" to previous.size.toLong(),
        "gradients" to gradients.size.toLong(), "glyphs" to glyphs.size.toLong(),
        "fonts" to fonts.size.toLong(), "lineMetrics" to lines.size.toLong(),
        "rasterBytes" to ((image?.width ?: 0).toLong() * (image?.height ?: 0) * 4),
        "transitionBytes" to ((transitionBlend?.width ?: 0).toLong() * (transitionBlend?.height ?: 0) * 12),
        "gradientBytes" to gradients.values.sumOf { it.second.width.toLong() * it.second.height * 4 })

    /** Hover/tooltips only repaint their old and new bounds, including the underlying menu layers. */
    fun finish(width: Int, height: Int, scale: Double = 2.0): BufferedImage {
        if (frame != null) {
            // A failed GPU frame cannot replay native textures; retain the current incoming page.
            val fallback = pending.flatMap { command ->
                if (command.kind == 9) command.blend!!.incoming else listOf(command)
            }.filter { it.snapshot == null }
            pending.clear(); pending.addAll(fallback)
        }
        frame = null
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
        prepare(g, scale)
        pending.forEach { paint(g, it, scale) }
        g.dispose()
        gradients.keys.retainAll(pending.filter { it.kind == 2 }.map { gradientKey(it) }.toSet() + transitionGradients)
        previous = pending.toList(); previousScale = scale; revision++
        return image!!
    }
    fun finishFrame(width: Int, height: Int, scale: Double = 2.0): Frame {
        require(width > 0 && height > 0 && scale.isFinite() && scale > 0)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val current = frame
        if (current != null && current.width == w && current.height == h && current.scale == scale && pending == previous) return current
        previous = pending.toList(); previousScale = scale; revision++
        image = null; gradients.clear(); dirtyRegion = null
        return Frame(w, h, scale, revision, previous).also { frame = it }
    }

    private fun prepare(g: Graphics2D, scale: Double) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(scale, scale)
    }

    private fun paint(g: Graphics2D, command: Paint, scale: Double) {
        val paint = g.create() as Graphics2D
        try {
            command.clip?.let { paint.clip(Rectangle2D.Double(it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())) }
            val t = command.transform
            paint.transform(AffineTransform(t.a, t.b, t.c, t.d, t.x, t.y))
            if (command.opacity < 1.0) paint.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, command.opacity.toFloat())
            draw(paint, command, scale)
        } finally { paint.dispose() }
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
        val v = paint
        return when (paint.kind) {
            0, 2, 5, 6, 7, 8, 9 -> Rectangle2D.Double(v[0], v[1], v[2], v[3])
            10 -> Rectangle2D.Double(paint.x - paint.inner * 3, paint.y - paint.inner * 3,
                paint.width + paint.inner * 6, paint.height + paint.inner * 6)
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
                        if (SkijaMenuFonts.active) {
                            val b = SkijaMenuFonts.bounds(codepoint, run.bold, run.italic, v[2])
                            include(Rectangle2D.Double(left + b.left, baseline + b.top, b.width.toDouble(), b.height.toDouble()))
                        } else {
                            val b = glyph(codepoint, run.bold, run.italic, v[2]).bounds
                            include(Rectangle2D.Double(left + b.x, baseline + b.y, b.width, b.height))
                        }
                        left += textWidth(codepoint, run.bold, run.italic, v[2])
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
        val v = p
        when (p.kind) {
            0 -> {
                if (!visible(g, v[0], v[1], v[2], v[3])) return
                g.color = Color(p.color, true); g.fill(Rectangle2D.Double(v[0], v[1], v[2], v[3]))
            }
            1 -> {
                g.color = Color(p.color, true)
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
                val line = metrics(v[2])
                val baseline = v[1] + ascent(v[2])
                val clip = g.clipBounds
                // All eight fonts used below contribute their maximum glyph bounds. A whole line
                // outside the inverse clip cannot contribute pixels, including styled fallback glyphs.
                if (clip != null && (baseline + line.maxY + 2 < clip.minY || baseline + line.minY - 2 > clip.maxY)) return
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
                        left += textWidth(codepoint, run.bold, run.italic, v[2])
                        index += Character.charCount(codepoint)
                    }
                    if (run.underline) g.fill(Rectangle2D.Double(start, baseline + 1, left - start, .6))
                }
            }
            4 -> {
                g.color = Color(p.color, true)
                g.stroke = java.awt.BasicStroke(v[4].toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.draw(java.awt.geom.Line2D.Double(v[0], v[1], v[2], v[3]))
            }
            5 -> {
                if (!visible(g, v[0], v[1], v[2], v[3])) return
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(p.picture, AffineTransform(v[2] / p.picture!!.width, 0.0, 0.0, v[3] / p.picture.height, v[0], v[1]), null)
            }
            7, 8 -> {
                g.color = Color(p.color, true)
                val shape = java.awt.geom.RoundRectangle2D.Double(p.x, p.y, p.width, p.height, p.extra * 2, p.extra * 2)
                if (p.kind == 7) g.fill(shape) else {
                    g.stroke = java.awt.BasicStroke(p.inner.toFloat()); g.draw(shape)
                }
            }
            10 -> {
                for (layer in 3 downTo 1) {
                    val spread = p.inner * layer / 3
                    g.color = Color((p.color and 0xFFFFFF) or ((p.color ushr 24) / 4 shl 24), true)
                    g.fill(java.awt.geom.RoundRectangle2D.Double(p.x - spread, p.y - spread,
                        p.width + spread * 2, p.height + spread * 2, (p.extra + spread) * 2, (p.extra + spread) * 2))
                }
            }
        }
    }

    private fun drawGradient(g: Graphics2D, p: Paint) {
        // Adjacent strips must share a hard boundary, including at fractional raster scales.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        val v = p
        val clip = g.clipBounds
        val first = kotlin.math.floor((clip?.minY ?: v[1]) - v[1] - 1).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil((clip?.maxY ?: (v[1] + v[3])) - v[1] + 1).toInt().coerceAtMost(kotlin.math.ceil(v[3]).toInt())
        for (row in first until last) {
            val amount = row / v[3]
            g.paint = java.awt.GradientPaint(v[0].toFloat(), 0f, Color(mix(p.color, p.bottomLeft, amount), true),
                (v[0] + v[2]).toFloat(), 0f, Color(mix(p.topRight, p.bottomRight, amount), true))
            g.fill(Rectangle2D.Double(v[0], v[1] + row, v[2], minOf(1.0, v[3] - row)))
        }
    }
    companion object {
        private fun gradientKey(paint: Paint) = paint.copy(transform = Transform(), clip = null, opacity = 1.0, group = null)
        private val font: Font = MenuCanvas::class.java.getResourceAsStream("/assets/skymyce/font/lato-regular.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(10f)
        private val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        private val frc = FontRenderContext(null, true, true)
        private data class FontKey(val size: Float, val style: Int, val fallback: Boolean)
        private data class GlyphKey(var codepoint: Int, var size: Float, var style: Int)
        private val glyphLookup = GlyphKey(0, 0f, 0)
        private data class Glyph(val vector: GlyphVector, val width: Double, val bounds: Rectangle2D)
        private data class LineMetrics(val ascent: Double, val height: Int, val minY: Double, val maxY: Double)
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
            val lookup = glyphLookup
            lookup.codepoint = codepoint; lookup.size = size.toFloat()
            lookup.style = (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0)
            glyphs[lookup]?.let { return it }
            // Client-render-thread cache: the reusable probe is never inserted into the map.
            val key = lookup.copy()
            val text = String(Character.toChars(codepoint))
            val fontKey = FontKey(key.size, key.style, font.canDisplayUpTo(text) >= 0)
            val styled = fonts.getOrPut(fontKey) { (if (fontKey.fallback) fallback else font).deriveFont(key.style, key.size) }
            val vector = styled.createGlyphVector(frc, text)
            return Glyph(vector, styled.getStringBounds(text, frc).width, vector.visualBounds).also { glyphs[key] = it }
        }
        private fun metrics(size: Double): LineMetrics = lines.getOrPut(size.toFloat()) {
            val a = font.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            val b = fallback.deriveFont(size.toFloat()).getLineMetrics("Ag", frc)
            val ascent = maxOf(a.ascent, b.ascent).toDouble()
            var minY = 0.0
            var maxY = 1.6 // The original underline extends below the baseline even for tiny fonts.
            for (source in arrayOf(font, fallback)) for (style in 0..3) {
                val bounds = source.deriveFont(style, size.toFloat()).getMaxCharBounds(frc)
                minY = minOf(minY, bounds.minY); maxY = maxOf(maxY, bounds.maxY)
            }
            LineMetrics(ascent, kotlin.math.ceil(ascent + maxOf(a.descent, b.descent) + maxOf(a.leading, b.leading) + 1).toInt(), minY, maxY)
        }
        private fun ascent(size: Double) = if (SkijaMenuFonts.active) SkijaMenuFonts.ascent(size) else metrics(size).ascent
        fun lineHeight(size: Double = 10.0): Int = if (SkijaMenuFonts.active) SkijaMenuFonts.lineHeight(size) else metrics(size).height
        fun textWidth(codepoint: Int, bold: Boolean = false, italic: Boolean = false, size: Double = 10.0): Double =
            if (SkijaMenuFonts.active) SkijaMenuFonts.width(codepoint, bold, italic, size) else glyph(codepoint, bold, italic, size).width
        fun textWidth(text: String, bold: Boolean = false, italic: Boolean = false, size: Double = 10.0): Double {
            var width = 0.0
            var index = 0
            while (index < text.length) {
                val codepoint = text.codePointAt(index)
                width += textWidth(codepoint, bold, italic, size)
                index += Character.charCount(codepoint)
            }
            return width
        }
        private fun mix(a: Int, b: Int, f: Double): Int {
            if (a == b) return a
            var result = 0
            for (shift in listOf(0, 8, 16, 24)) {
                val start = a ushr shift and 255
                val end = b ushr shift and 255
                result = result or (kotlin.math.round(start + (end - start) * f).toInt().coerceIn(0, 255) shl shift)
            }
            return result
        }
    }
}
