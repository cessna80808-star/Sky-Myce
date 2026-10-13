package me.mycellium.skymyce.ui

import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.Font
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.FontStyle
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.TextBlob
import io.github.humbleui.skija.Typeface
import io.github.humbleui.types.Rect
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Render-thread font resources; measuring and drawing share the same positioned native glyphs. */
object SkijaMenuFonts {
    var active = false
    private data class FontKey(val face: Int, val size: Float, val style: Int)
    private data class GlyphKey(var codepoint: Int, var size: Float, var style: Int)
    private data class Glyph(val blob: TextBlob, val width: Double, val bounds: Rect)
    private data class Metrics(val ascent: Double, val height: Int)
    private var base: Typeface? = null
    private var textPaint: Paint? = null
    private val lookup = GlyphKey(0, 0f, 0)
    private val fonts = object : LinkedHashMap<FontKey, Font>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FontKey, Font>?): Boolean =
            (size > 64).also { if (it) eldest?.value?.close() }
    }
    private val glyphs = object : LinkedHashMap<GlyphKey, Glyph>(4096, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphKey, Glyph>?): Boolean =
            (size > 4096).also { if (it) eldest?.value?.blob?.close() }
    }
    private val metrics = object : LinkedHashMap<Float, Metrics>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, Metrics>?) = size > 32
    }

    private fun base(): Typeface = base ?: run {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/assets/skymyce/font/lato-regular.ttf")) {
            "The bundled Lato menu font is missing."
        }.use { it.readBytes() }
        Data.makeFromBytes(bytes).use { data ->
            requireNotNull(FontMgr.getDefault().makeFromData(data)) { "Skija could not load the bundled Lato menu font." }
        }.also { base = it }
    }
    private fun fontSize(value: Double): Float = if (value.isFinite()) value.coerceIn(.5, 256.0).toFloat() else 10f
    private fun style(value: Int) = when (value) {
        1 -> FontStyle.BOLD
        2 -> FontStyle.ITALIC
        3 -> FontStyle.BOLD_ITALIC
        else -> FontStyle.NORMAL
    }
    private fun font(face: Typeface, size: Float, style: Int): Font = fonts.getOrPut(FontKey(face.uniqueId, size, style)) {
        Font(face, size).setSubpixel(true).setMetricsLinear(true)
            .setEmboldened(style and 1 != 0 && !face.isBold)
            .setSkewX(if (style and 2 != 0 && !face.isItalic) -.25f else 0f)
    }
    private fun glyph(codepoint: Int, bold: Boolean, italic: Boolean, size: Double): Glyph {
        require(Character.isValidCodePoint(codepoint))
        lookup.codepoint = codepoint; lookup.size = fontSize(size); lookup.style = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        glyphs[lookup]?.let { return it }
        val key = lookup.copy()
        val base = base()
        val fallback = if (base.getUTF32Glyph(codepoint).toInt() == 0)
            FontMgr.getDefault().matchFamilyStyleCharacter(null, style(key.style), null, codepoint) else null
        val glyph = try {
            val font = font(fallback ?: base, key.size, key.style)
            val id = shortArrayOf(font.getUTF32Glyph(codepoint))
            val width = font.getWidths(id)[0].toDouble()
            val blob = requireNotNull(TextBlob.makeFromPosH(id, floatArrayOf(0f), 0f, font)) { "Skija could not prepare a menu glyph." }
            Glyph(blob, width, blob.bounds)
        } finally { fallback?.close() }
        // A TextBlob retains its typeface independently of the bounded styled-font cache.
        glyphs[key] = glyph
        return glyph
    }
    private fun metrics(size: Double): Metrics = metrics.getOrPut(fontSize(size)) {
        val base = base()
        val own = font(base, fontSize(size), 0).metrics
        val fallback = FontMgr.getDefault().matchFamilyStyle(null, FontStyle.NORMAL)
        val system = try { font(fallback ?: base, fontSize(size), 0).metrics } finally { fallback?.close() }
        val ascent = maxOf(-own.ascent, -system.ascent).toDouble()
        Metrics(ascent, ceil(ascent + maxOf(own.descent, system.descent) + maxOf(own.leading, system.leading) + 1).toInt())
    }
    fun width(codepoint: Int, bold: Boolean, italic: Boolean, size: Double) = glyph(codepoint, bold, italic, size).width
    fun bounds(codepoint: Int, bold: Boolean, italic: Boolean, size: Double) = glyph(codepoint, bold, italic, size).bounds
    fun ascent(size: Double) = metrics(size).ascent
    fun lineHeight(size: Double) = metrics(size).height

    fun draw(canvas: Canvas, runs: List<MenuCanvas.Run>, x: Double, y: Double, size: Double, opacity: Double) {
        if (opacity <= 0 || !opacity.isFinite()) return
        val paint = textPaint ?: Paint().setAntiAlias(true).also { textPaint = it }
        val baseline = y + ascent(size)
        var left = x
        for (run in runs) {
            val alpha = ((run.color ushr 24) * opacity.coerceIn(0.0, 1.0)).roundToInt()
            paint.color = (alpha shl 24) or (run.color and 0xFFFFFF)
            val start = left
            var index = 0
            while (index < run.text.length) {
                val codepoint = run.text.codePointAt(index)
                val glyph = glyph(codepoint, run.bold, run.italic, size)
                canvas.drawTextBlob(glyph.blob, left.toFloat(), baseline.toFloat(), paint)
                left += glyph.width
                index += Character.charCount(codepoint)
            }
            if (run.underline && left > start) {
                paint.strokeWidth = .6f
                canvas.drawLine(start.toFloat(), (baseline + 1).toFloat(), left.toFloat(), (baseline + 1).toFloat(), paint)
            }
        }
    }

    internal fun cacheSizes() = mapOf("fonts" to fonts.size.toLong(), "glyphs" to glyphs.size.toLong(), "lineMetrics" to metrics.size.toLong())
    fun close() {
        active = false
        glyphs.values.forEach { it.blob.close() }; glyphs.clear()
        fonts.values.forEach { it.close() }; fonts.clear(); metrics.clear()
        textPaint?.close(); textPaint = null
        base?.close(); base = null
    }
}
