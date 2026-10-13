package me.mycellium.skymyce.ui

import io.github.humbleui.skija.*
import io.github.humbleui.types.Rect
import io.github.humbleui.types.RRect
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interaction snapshots are flattened once on the render thread, never read back to the CPU. */
internal class SkijaMenuSnapshot(commands: List<MenuCanvas.Paint>, val region: Rectangle, val scale: Double) : AutoCloseable {
    private var commands: List<MenuCanvas.Paint>? = commands
    private var image: Image? = null
    private var retired = false
    init { SkijaMenuPainter.pendingSnapshots += this }
    fun image(context: DirectContext?): Image {
        image?.let { return it }
        check(!retired)
        val info = ImageInfo.makeN32Premul(region.width, region.height)
        val surface = if (context == null) Surface.makeRaster(info) else Surface.makeRenderTarget(context, false, info)
        surface.use {
            val canvas = surface.canvas
            canvas.clear(0); canvas.translate(-region.x.toFloat(), -region.y.toFloat())
            canvas.scale(scale.toFloat(), scale.toFloat())
            SkijaMenuPainter.commands(canvas, checkNotNull(commands), context)
            image = surface.makeImageSnapshot()
        }
        commands = null
        SkijaMenuPainter.pendingSnapshots.remove(this)
        return checkNotNull(image)
    }
    override fun close() { if (!retired) SkijaMenuPainter.retiredSnapshots += this }
    fun dispose() {
        if (retired) return
        retired = true; commands = null; image?.close(); image = null
        SkijaMenuPainter.pendingSnapshots.remove(this)
    }
}

/** Shared primitives for all menus; Minecraft owns composition, Skija owns only its drawing resources. */
internal object SkijaMenuPainter {
    internal val pendingSnapshots = LinkedHashSet<SkijaMenuSnapshot>()
    internal val retiredSnapshots = LinkedHashSet<SkijaMenuSnapshot>()
    private val liveSnapshots = LinkedHashSet<SkijaMenuSnapshot>()
    private var paint: Paint? = null
    private var gradientEffect: RuntimeEffect? = null
    private val images = object : LinkedHashMap<BufferedImage, Image>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BufferedImage, Image>?): Boolean {
            if (size <= 64) return false
            eldest?.value?.close(); return true
        }
    }
    private val gradients = object : LinkedHashMap<MenuCanvas.Paint, Shader>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MenuCanvas.Paint, Shader>?): Boolean {
            if (size <= 32) return false
            eldest?.value?.close(); return true
        }
    }
    private val blurs = object : LinkedHashMap<Float, MaskFilter>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, MaskFilter>?): Boolean {
            if (size <= 8) return false
            eldest?.value?.close(); return true
        }
    }
    fun prepareSnapshots(context: DirectContext?) {
        while (pendingSnapshots.isNotEmpty()) {
            val snapshot = pendingSnapshots.first()
            snapshot.image(context); liveSnapshots += snapshot
        }
    }
    fun releaseRetired() {
        retiredSnapshots.forEach { it.dispose(); liveSnapshots.remove(it) }; retiredSnapshots.clear()
    }
    // Skija 0.143.17's context getter incorrectly owns its borrowed pointer; always pass the owner.
    @JvmOverloads fun draw(canvas: Canvas, frame: MenuCanvas.Frame, context: DirectContext? = null) {
        val saved = canvas.save()
        try {
            prepareSnapshots(context)
            canvas.scale(frame.scale.toFloat(), frame.scale.toFloat())
            commands(canvas, frame.commands, context)
        } finally { canvas.restoreToCount(saved) }
    }
    internal fun commands(canvas: Canvas, commands: List<MenuCanvas.Paint>, context: DirectContext?) {
        for (command in commands) {
            val saved = canvas.save()
            try {
                command.clip?.let { canvas.clipRect(Rect.makeXYWH(it.x.toFloat(), it.y.toFloat(), it.width.toFloat(), it.height.toFloat())) }
                val t = command.transform
                canvas.concat(Matrix33(t.a.toFloat(), t.c.toFloat(), t.x.toFloat(), t.b.toFloat(), t.d.toFloat(), t.y.toFloat(), 0f, 0f, 1f))
                drawCommand(canvas, command, context)
            } finally { canvas.restoreToCount(saved) }
        }
    }
    private fun brush(color: Int = -1, opacity: Double = 1.0): Paint {
        val result = paint ?: Paint().also { paint = it }
        return result.reset().setAntiAlias(true).setColor(color)
            .setAlphaf(((color ushr 24) / 255.0 * opacity).coerceIn(0.0, 1.0).toFloat())
    }
    private fun drawCommand(canvas: Canvas, p: MenuCanvas.Paint, context: DirectContext?) {
        val rect = Rect.makeXYWH(p.x.toFloat(), p.y.toFloat(), p.width.toFloat(), p.height.toFloat())
        when (p.kind) {
            0 -> canvas.drawRect(rect, brush(p.color, p.opacity))
            1 -> {
                val radius = p.width.toFloat()
                if (p.inner > 0) Path.makeCircle(p.x.toFloat(), p.y.toFloat(), p.inner.toFloat()).use { hole ->
                    canvas.clipPath(hole, ClipMode.DIFFERENCE, true)
                }
                if (p.extra - p.height >= 360) canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius, brush(p.color, p.opacity))
                else canvas.drawArc((p.x - radius).toFloat(), (p.y - radius).toFloat(), (p.x + radius).toFloat(), (p.y + radius).toFloat(),
                    (180 + p.height).toFloat(), (p.extra - p.height).toFloat(), true, brush(p.color, p.opacity))
            }
            2 -> canvas.drawRect(rect, brush(opacity = p.opacity).setShader(gradient(p)))
            3 -> SkijaMenuFonts.draw(canvas, p.runs, p.x, p.y, p.width, p.opacity)
            4 -> canvas.drawLine(p.x.toFloat(), p.y.toFloat(), p.width.toFloat(), p.height.toFloat(), brush(p.color, p.opacity)
                .setStrokeWidth(p.extra.toFloat()).setStrokeCap(PaintStrokeCap.ROUND))
            5 -> canvas.drawImageRect(image(checkNotNull(p.picture)), rect, brush(opacity = p.opacity))
            6 -> canvas.drawImageRect(checkNotNull(p.snapshot).image(context), rect, brush(opacity = p.opacity))
            7, 8 -> {
                val style = brush(p.color, p.opacity)
                if (p.kind == 8) style.setMode(PaintMode.STROKE).setStrokeWidth(p.inner.toFloat())
                canvas.drawRRect(RRect.makeXYWH(p.x.toFloat(), p.y.toFloat(), p.width.toFloat(), p.height.toFloat(), p.extra.toFloat()), style)
            }
            9 -> {
                val blend = checkNotNull(p.blend)
                // Add weighted premultiplied layers, preserving panel opacity during page changes.
                val outer = canvas.saveLayer(rect, null)
                try {
                    val old = canvas.saveLayer(rect, brush(opacity = 1 - blend.amount))
                    commands(canvas, blend.outgoing, context); canvas.restoreToCount(old)
                    val next = canvas.saveLayer(rect, brush(opacity = blend.amount).setBlendMode(BlendMode.PLUS))
                    commands(canvas, blend.incoming, context); canvas.restoreToCount(next)
                } finally { canvas.restoreToCount(outer) }
            }
            10 -> {
                val sigma = p.inner.toFloat()
                val blur = blurs.getOrPut(sigma) { MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma, true) }
                canvas.drawRRect(RRect.makeXYWH(p.x.toFloat(), p.y.toFloat(), p.width.toFloat(), p.height.toFloat(), p.extra.toFloat()),
                    brush(p.color, p.opacity).setMaskFilter(blur))
            }
        }
    }
    private fun gradient(p: MenuCanvas.Paint): Shader {
        val key = p.copy(transform = MenuCanvas.Transform(), clip = null, opacity = 1.0, group = null)
        return gradients.getOrPut(key) {
            val effect = gradientEffect ?: RuntimeEffect.makeForShader("""
                uniform float2 origin; uniform float2 size;
                uniform half4 tl; uniform half4 tr; uniform half4 br; uniform half4 bl;
                half4 main(float2 point) {
                    float2 uv = clamp((point - origin) / size, 0.0, 1.0);
                    return mix(mix(tl, tr, uv.x), mix(bl, br, uv.x), uv.y);
                }
            """.trimIndent()).also { gradientEffect = it }
            val bytes = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder())
            bytes.putFloat(p.x.toFloat()).putFloat(p.y.toFloat()).putFloat(p.width.toFloat()).putFloat(p.height.toFloat())
            for (color in intArrayOf(p.color, p.topRight, p.bottomRight, p.bottomLeft)) {
                val alpha = (color ushr 24) / 255f
                bytes.putFloat((color ushr 16 and 255) / 255f * alpha).putFloat((color ushr 8 and 255) / 255f * alpha)
                    .putFloat((color and 255) / 255f * alpha).putFloat(alpha)
            }
            Data.makeFromBytes(bytes.array()).use { effect.makeShader(it, emptyArray<RuntimeEffectChild>()) }
        }
    }
    private fun image(source: BufferedImage): Image = images.getOrPut(source) {
        val pixels = source.getRGB(0, 0, source.width, source.height, null, 0, source.width)
        val bytes = ByteArray(Math.multiplyExact(pixels.size, 4))
        var index = 0
        for (pixel in pixels) {
            bytes[index++] = (pixel ushr 16).toByte(); bytes[index++] = (pixel ushr 8).toByte()
            bytes[index++] = pixel.toByte(); bytes[index++] = (pixel ushr 24).toByte()
        }
        Image.makeRasterFromBytes(ImageInfo(source.width, source.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), bytes, source.width.toLong() * 4)
    }
    fun cacheSizes(): Map<String, Long> = mapOf("snapshots.pending" to pendingSnapshots.size.toLong(),
        "snapshots.live" to liveSnapshots.size.toLong(), "images" to images.size.toLong(), "gradients" to gradients.size.toLong(), "blurs" to blurs.size.toLong())
    fun close() {
        (pendingSnapshots + liveSnapshots + retiredSnapshots).forEach(SkijaMenuSnapshot::dispose)
        pendingSnapshots.clear(); liveSnapshots.clear(); retiredSnapshots.clear()
        images.values.forEach(Image::close); images.clear()
        gradients.values.forEach(Shader::close); gradients.clear()
        blurs.values.forEach(MaskFilter::close); blurs.clear()
        gradientEffect?.close(); gradientEffect = null; paint?.close(); paint = null
        SkijaMenuFonts.close()
    }
}
