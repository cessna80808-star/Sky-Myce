package me.mycellium.skymyce.ui

import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Surface
import java.awt.image.BufferedImage
import kotlin.math.abs

/** Native raster fixtures exercise the same immutable commands as the in-game GPU surface. */
fun main() {
    val painter = SkijaMenuPainter
    val canvas = MenuCanvas()
    fun pixels(frame: MenuCanvas.Frame): IntArray = Surface.makeRaster(io.github.humbleui.skija.ImageInfo.makeN32Premul(frame.width, frame.height)).use { surface ->
        painter.prepareSnapshots(null)
        painter.draw(surface.canvas, frame)
        val result = surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                IntArray(frame.width * frame.height) { index -> bitmap.getColor(index % frame.width, index / frame.width) }
            }
        }
        painter.releaseRetired()
        result
    }
    fun alpha(pixel: Int) = pixel ushr 24
    fun red(pixel: Int) = pixel ushr 16 and 255
    fun green(pixel: Int) = pixel ushr 8 and 255
    fun blue(pixel: Int) = pixel and 255
    try {
        val picture = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFFFF0000.toInt()); setRGB(1, 0, 0xFF00FF00.toInt())
            setRGB(0, 1, 0xFF0000FF.toInt()); setRGB(1, 1, 0x80FFFFFF.toInt())
        }
        fun primitives() {
            canvas.begin()
            canvas.fill(8.0, 8.0, 16.0, 16.0, 0x80FF0000.toInt())
            canvas.roundedRect(40.0, 8.0, 24.0, 24.0, 8.0, 0xFF0000FF.toInt())
            canvas.roundedBorder(76.0, 8.0, 24.0, 24.0, 6.0, 2.0, -1)
            canvas.clip = MenuCanvas.Clip(8, 44, 12, 10)
            canvas.fill(0.0, 40.0, 40.0, 25.0, 0xFF00FF00.toInt())
            canvas.clip = MenuCanvas.Clip(68, 50, 10, 8)
            canvas.transform = MenuCanvas.Transform(a = 2.0, d = 1.5, x = 64.0, y = 48.0)
            canvas.fill(0.0, 0.0, 10.0, 10.0, 0xFFFFFF00.toInt())
            canvas.transform = MenuCanvas.Transform()
            canvas.clip = null
            canvas.gradient(92.0, 44.0, 24.0, 24.0, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), -1)
            canvas.line(8.0, 80.0, 32.0, 80.0, 2.0, 0xFF00FFFF.toInt())
            canvas.arc(48.0, 80.0, 8.0, 0.0, 360.0, 0xFFFF00FF.toInt())
            canvas.arc(70.0, 80.0, 8.0, 0.0, 360.0, -1, 6.0)
            canvas.picture(picture, 92.0, 76.0, 16.0, 16.0)
        }
        primitives()
        val first = canvas.finishFrame(128, 96, 1.0)
        val painted = pixels(first)
        fun at(x: Int, y: Int) = painted[y * first.width + x]
        check(at(16, 16) == 0x80FF0000.toInt()) { "Straight ARGB lost alpha at the premultiplied surface boundary" }
        check(alpha(at(40, 8)) == 0 && at(52, 20) == 0xFF0000FF.toInt()) { "Rounded rectangle corners or interior are wrong" }
        check(at(88, 8) == -1 && alpha(at(88, 20)) == 0) { "Rounded border edge or transparent interior is wrong" }
        check(at(12, 48) == 0xFF00FF00.toInt() && alpha(at(4, 48)) == 0 && alpha(at(12, 60)) == 0) { "Global clipping leaked paint" }
        check(at(70, 52) == 0xFFFFFF00.toInt() && alpha(at(66, 52)) == 0 && alpha(at(80, 52)) == 0) { "Affine transform changed global clip coordinates" }
        check(red(at(94, 46)) > green(at(94, 46)) && red(at(94, 46)) > blue(at(94, 46)))
        check(green(at(114, 46)) > red(at(114, 46)) && green(at(114, 46)) > blue(at(114, 46)))
        check(blue(at(114, 66)) > red(at(114, 66)) && blue(at(114, 66)) > green(at(114, 66)))
        check(red(at(94, 66)) > 150 && green(at(94, 66)) > 150 && blue(at(94, 66)) > 150) { "Four-corner gradient orientation changed" }
        check(at(20, 80) == 0xFF00FFFF.toInt() && at(48, 80) == 0xFFFF00FF.toInt())
        check(alpha(at(70, 80)) == 0 && alpha(at(77, 80)) > 200) { "Ring lost its transparent centre" }
        check(red(at(94, 78)) > 240 && blue(at(94, 90)) > 240) { "Prepared image channels or orientation changed" }
        check(abs(alpha(at(106, 90)) - 128) <= 1 && red(at(106, 90)) > 250) { "Prepared image alpha was not preserved" }
        repeat(30) {
            primitives()
            check(canvas.finishFrame(128, 96, 1.0) === first) { "An unchanged frame was rebuilt" }
        }
        primitives()
        val scaled = canvas.finishFrame(128, 96, 1.5)
        check(scaled.width == 192 && scaled.height == 144 && scaled.revision > first.revision)
        val before = first.commands.first()
        canvas.begin(); canvas.fill(1.0, 1.0, 3.0, 3.0, -1); canvas.finishFrame(128, 96, 1.0)
        check(first.commands.first() === before && before.color == 0x80FF0000.toInt()) { "Deferred frame commands were mutated" }

        canvas.begin(); canvas.group = "content"
        canvas.fill(8.0, 8.0, 32.0, 32.0, 0x80FF0000.toInt())
        canvas.group = "tooltip"; canvas.fill(8.0, 8.0, 32.0, 32.0, -1)
        pixels(canvas.finishFrame(64, 64, 1.0))
        var snapshot = checkNotNull(canvas.snapshot("content"))
        val boundary = MenuCanvas.Clip(8, 8, 32, 32)
        for (progress in listOf(0.0, .25, .5, .75, 1.0)) {
            canvas.begin(); canvas.group = "content"
            canvas.transition(snapshot, progress, boundary) { canvas.fill(8.0, 8.0, 32.0, 32.0, 0x800000FF.toInt()) }
            val blended = pixels(canvas.finishFrame(64, 64, 1.0))[16 * 64 + 16]
            check(abs(alpha(blended) - 128) <= 1) { "Translucent page opacity changed during crossfade at $progress" }
            if (progress == .5) check(abs(red(blended) - blue(blended)) <= 2 && green(blended) == 0) { "Pages were composited over each other instead of blended" }
        }
        snapshot.close(); painter.releaseRetired()
        snapshot = checkNotNull(canvas.snapshot("content"))
        repeat(40) { index ->
            canvas.begin(); canvas.group = "content"
            val color = if (index % 2 == 0) 0x80FF0000.toInt() else 0x800000FF.toInt()
            canvas.transition(snapshot, .45, boundary) { canvas.fill(8.0, 8.0, 32.0, 32.0, color) }
            val frame = canvas.finishFrame(64, 64, 1.0)
            val blendedAlpha = alpha(pixels(frame)[16 * 64 + 16])
            check(abs(blendedAlpha - 128) <= 1) { "Interrupted navigation accumulated opacity at $index: $blendedAlpha" }
            val next = checkNotNull(canvas.snapshot("content"))
            snapshot.close(); snapshot = next
            painter.prepareSnapshots(null); painter.releaseRetired()
            val sizes = painter.cacheSizes()
            check(sizes.getValue("snapshots.live") <= 2 && sizes.getValue("snapshots.pending") == 0L) { "Interrupted navigation retained older native snapshots: $sizes" }
        }
        snapshot.close(); painter.releaseRetired()
        check(painter.cacheSizes().getValue("snapshots.live") == 0L) { "Closed native snapshots leaked" }
        canvas.begin(); canvas.group = "content"
        canvas.shadow(24.0, 24.0, 16.0, 16.0, 4.0, 3.0, -1)
        val shadowPixels = pixels(canvas.finishFrame(64, 64, 1.0))
        check(alpha(shadowPixels[32 * 64 + 32]) > 240 && alpha(shadowPixels[21 * 64 + 32]) in 1..100 &&
            alpha(shadowPixels[0]) == 0) { "Native shadow lost its soft edge or exceeded its bounds" }
        val shadowSnapshot = checkNotNull(canvas.snapshot("content"))
        canvas.begin(); canvas.replay(shadowSnapshot)
        check(pixels(canvas.finishFrame(64, 64, 1.0)).contentEquals(shadowPixels)) { "Snapshot clipped the shadow's soft edge" }
        shadowSnapshot.close(); painter.releaseRetired()
        repeat(24) { index ->
            canvas.begin(); canvas.shadow(8.0, 8.0, 8.0, 8.0, 2.0, .25 + index * .25, -1)
            pixels(canvas.finishFrame(24, 24, 1.0))
        }
        check(painter.cacheSizes().getValue("blurs") <= 8) { "Native blur filters grew without bounds" }
        repeat(100) { index ->
            val prepared = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, -1) }
            canvas.begin(); canvas.picture(prepared, 0.0, 0.0, 4.0, 4.0)
            canvas.gradient(4.0, 0.0, 4.0, 4.0, 0xFF000000.toInt() or index, -1, -1, 0)
            pixels(canvas.finishFrame(8, 4, 1.0))
        }
        check(painter.cacheSizes().let { it.getValue("images") <= 64 && it.getValue("gradients") <= 32 }) { "Prepared images or gradient shaders grew without bounds" }
        canvas.begin(); canvas.group = "content"; canvas.fill(2.0, 2.0, 8.0, 8.0, -1)
        pixels(canvas.finishFrame(16, 16, 1.0))
        val nativeSnapshot = checkNotNull(canvas.snapshot("content"))
        canvas.begin()
        canvas.transition(nativeSnapshot, .5, MenuCanvas.Clip(0, 0, 16, 16)) { canvas.fill(2.0, 2.0, 8.0, 8.0, 0xFF0000FF.toInt()) }
        val fallback = canvas.finish(16, 16, 1.0)
        check(fallback.getRGB(4, 4) == 0xFF0000FF.toInt()) { "GPU failure did not preserve the incoming fallback page" }
        nativeSnapshot.close(); painter.releaseRetired()

        SkijaMenuFonts.active = true
        val sample = "AV café Ж ✓ 😀"
        var index = 0
        while (index < sample.length) {
            val next = index + Character.charCount(sample.codePointAt(index))
            val left = MenuCanvas.textWidth(sample.substring(0, index))
            val right = MenuCanvas.textWidth(sample.substring(0, next))
            check(menuCaretIndex(sample, left + (right - left) * .75, MenuCanvas::textWidth) == next) { "Native glyph advances and caret hit testing disagree" }
            index = next
        }
        canvas.begin(); canvas.text(listOf(MenuCanvas.Run(sample, -1, bold = true, italic = true, underline = true)), 8.0, 8.0, 12.0)
        check(pixels(canvas.finishFrame(256, 48, 1.0)).any { alpha(it) > 0 }) { "Bundled/fallback native fonts did not draw" }
        for (codepoint in 0x3000 until 0x4100) SkijaMenuFonts.width(codepoint, false, false, 8.0 + codepoint % 80 / 4.0)
        repeat(80) { SkijaMenuFonts.lineHeight(8.0 + it / 4.0) }
        check(SkijaMenuFonts.cacheSizes().let { it.getValue("glyphs") <= 4096 && it.getValue("fonts") <= 64 && it.getValue("lineMetrics") <= 32 }) { "Native font resources grew without bounds" }
        val firstRun = MenuCanvas.Run("Lato · 你好 · ✈ ", -1, bold = true, italic = true)
        val nextRun = MenuCanvas.Run("aligned", 0xFF00FF00.toInt(), underline = true)
        val advance = firstRun.text.codePoints().toArray().sumOf { SkijaMenuFonts.width(it, firstRun.bold, firstRun.italic, 18.0) }
        fun textPixels(separate: Boolean): IntArray = Surface.makeRaster(io.github.humbleui.skija.ImageInfo.makeN32Premul(400, 64)).use { surface ->
            if (separate) {
                SkijaMenuFonts.draw(surface.canvas, listOf(firstRun), 8.0, 8.0, 18.0, 1.0)
                SkijaMenuFonts.draw(surface.canvas, listOf(nextRun), 8.0 + advance, 8.0, 18.0, 1.0)
            } else SkijaMenuFonts.draw(surface.canvas, listOf(firstRun, nextRun), 8.0, 8.0, 18.0, 1.0)
            surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                IntArray(400 * 64) { bitmap.getColor(it % 400, it / 400) }
            } }
        }
        check(textPixels(false).contentEquals(textPixels(true))) { "Styled text draw positions disagree with measured advances" }
        SkijaMenuFonts.close()
        check(SkijaMenuFonts.cacheSizes().values.all { it == 0L } && !SkijaMenuFonts.active) { "Closed native font resources were retained" }
        check(SkijaMenuFonts.width('A'.code, false, false, 10.0) > 0) { "Fonts did not reopen after renderer cleanup" }
        println("Skija checks passed on ${System.getProperty("os.name")}: native raster, rounded primitives, clipping, transforms, gradients, images, immutable idle frames, constant-alpha transitions and bounded interrupted snapshots")
    } finally {
        canvas.close(); painter.close()
    }
}
