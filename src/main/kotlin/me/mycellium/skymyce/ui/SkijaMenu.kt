package me.mycellium.skymyce.ui

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import io.github.humbleui.skija.*
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Font
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.FontStyle
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import io.github.humbleui.skija.TextBlob
import io.github.humbleui.skija.Typeface
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt
import me.mycellium.mixin.SecondaryCameraGuiInvoker
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL30C

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

/** Owned GPU output: extraction retains commands, GuiRenderer paints before sampling its view. */
internal class SkijaMenuTexture : AutoCloseable {
    private var target: Target? = null

    fun render(graphics: GuiGraphicsExtractor, frame: MenuCanvas.Frame, logicalWidth: Int, logicalHeight: Int): Boolean {
        RenderSystem.assertOnRenderThread()
        if (!available()) return false
        require(logicalWidth > 0 && logicalHeight > 0 && frame.width > 0 && frame.height > 0)
        var current = target
        if (current == null || current.width != frame.width || current.height != frame.height) {
            val next = try { Target(frame.width, frame.height) }
            catch (failure: Exception) { disable(failure); return false }
            catch (failure: LinkageError) { disable(failure); return false }
            current?.let(retirement::retire)
            current = next
            target = next
        }
        if (current.revision != frame.revision) pending[current] = frame
        (graphics as SecondaryCameraGuiInvoker).`skyMyce$cameraBlit`(
            RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, current.view,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            0, 0, logicalWidth, logicalHeight, 0f, 1f, 0f, 1f, -1)
        return true
    }

    override fun close() {
        target?.let(retirement::retire)
        target = null
    }

    private class Target(val width: Int, val height: Int) : AutoCloseable {
        val texture: GpuTexture = RenderSystem.getDevice().createTexture("Sky-Myce Skija menu",
            GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING,
            TextureFormat.RGBA8, width, height, 1, 1)
        val view: GpuTextureView = try { RenderSystem.getDevice().createTextureView(texture) }
            catch (failure: Throwable) { texture.close(); throw failure }
        val ownerContext = GLFW.glfwGetCurrentContext()
        init { targets += this }
        var revision = Long.MIN_VALUE
        private var fbo = 0
        private var stencil = 0
        private var backend: BackendRenderTarget? = null
        private var surface: Surface? = null

        fun draw(frame: MenuCanvas.Frame, context: DirectContext) {
            check(ownerContext == GLFW.glfwGetCurrentContext()) { "Minecraft replaced its OpenGL context" }
            context.resetGLAll()
            val glTexture = texture as? GlTexture ?: error("Minecraft texture is not OpenGL-backed")
            var canvasSurface = surface
            if (canvasSurface == null) {
                fbo = GL30C.glGenFramebuffers()
                stencil = GL30C.glGenRenderbuffers()
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, glTexture.glId(), 0)
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, stencil)
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL30C.GL_RENDERBUFFER, stencil)
                check(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) == GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    "Skija menu framebuffer is incomplete"
                }
                backend = BackendRenderTarget.makeGL(width, height, 0, 8, fbo, GL11C.GL_RGBA8)
                // TOP_LEFT stores logical row zero at texture V=0, matching Minecraft GUI blits.
                ColorSpace.getSRGB().use { colorSpace ->
                    canvasSurface = Surface.wrapBackendRenderTarget(context, backend!!, SurfaceOrigin.TOP_LEFT,
                        ColorType.RGBA_8888, colorSpace)
                }
                surface = canvasSurface
            }
            GL11C.glViewport(0, 0, width, height)
            context.resetGLAll()
            val canvas = canvasSurface!!.canvas
            val saved = canvas.save()
            try {
                canvas.clear(0)
                // Skija 0.143.17's Surface.recordingContext wraps a borrowed pointer as owning; pass our owned context instead.
                SkijaMenuPainter.draw(canvas, frame, context)
            } finally { canvas.restoreToCount(saved) }
            // Same context/command stream: submission orders texture writes before Minecraft samples.
            context.flush(canvasSurface)
            check(context.submit(false)) { "Skija GPU submission failed" }
            revision = frame.revision
        }

        override fun close() {
            pending.remove(this)
            targets.remove(this)
            if (ownerContext == GLFW.glfwGetCurrentContext()) {
                SkijaGlState().use { surface?.close(); backend?.close() }
            } else { surface?.close(); backend?.close() }
            surface = null; backend = null
            if (ownerContext == GLFW.glfwGetCurrentContext()) {
                if (stencil != 0) GL30C.glDeleteRenderbuffers(stencil)
                if (fbo != 0) GL30C.glDeleteFramebuffers(fbo)
                view.close(); texture.close()
            }
            stencil = 0; fbo = 0
        }
    }

    companion object {
        private val pending = LinkedHashMap<Target, MenuCanvas.Frame>()
        private val targets = LinkedHashSet<Target>()
        private val retirement = MenuTextureRetirement(RenderSystem::queueFencedTask)
        private var initialized = false
        private var failed = false
        private var context: DirectContext? = null
        private var contextHandle = 0L
        private var hooksRegistered = false

        @JvmStatic fun available(): Boolean {
            if (failed) return false
            RenderSystem.assertOnRenderThread()
            if (initialized) {
                if (contextHandle != GLFW.glfwGetCurrentContext()) {
                    context?.abandon()
                    disable(IllegalStateException("Minecraft replaced its OpenGL context"))
                    return false
                }
                return true
            }
            initialized = true
            try {
                check(RenderSystem.getDevice().backendName.equals("OpenGL", ignoreCase = true)) {
                    "The Minecraft graphics backend is not OpenGL"
                }
                check(GLFW.glfwGetCurrentContext() != 0L && GL.getCapabilities().OpenGL33) {
                    "No compatible Minecraft OpenGL context is current"
                }
                SkijaGlState().use {
                    val created = DirectContext.makeGL()
                    try {
                        SkijaMenuFonts.width('A'.code, false, false, 10.0)
                        context = created
                    } catch (failure: Throwable) {
                        SkijaMenuFonts.close(); created.close(); throw failure
                    }
                }
                contextHandle = GLFW.glfwGetCurrentContext()
                registerHooks()
                SkyMyce.logger.info("Custom menus use Skija 0.143.17 on Minecraft's existing OpenGL context")
                return true
            } catch (failure: Exception) { disable(failure) }
            catch (failure: LinkageError) { disable(failure) }
            return false
        }

        private fun registerHooks() {
            if (hooksRegistered) return
            hooksRegistered = true
            ClientTickEvents.END_CLIENT_TICK.register { retirement.afterTick() }
            ClientLifecycleEvents.CLIENT_STOPPING.register {
                val current = context
                if (current != null && contextHandle == GLFW.glfwGetCurrentContext()) {
                    SkijaGlState().use { current.flushAndSubmit(true) }
                    retirement.close()
                    targets.toList().forEach(Target::close)
                    SkijaGlState().use { SkijaMenuPainter.close(); current.close() }
                } else {
                    current?.abandon()
                    retirement.close()
                    targets.toList().forEach(Target::close)
                    SkijaMenuPainter.close()
                    current?.close()
                }
                pending.clear(); context = null; failed = true
            }
        }

        private fun disable(failure: Throwable) {
            if (!failed) SkyMyce.logger.warn("Skija custom menus unavailable; using the AWT fallback: {}: {}",
                failure.javaClass.simpleName, failure.message)
            failed = true
            SkijaMenuFonts.active = false
        }

        @JvmStatic fun renderPending() {
            if (pending.isEmpty() && SkijaMenuPainter.retiredSnapshots.isEmpty()) return
            RenderSystem.assertOnRenderThread()
            val batch = pending.entries.map { it.key to it.value }
            pending.clear()
            if (!available()) return
            try {
                RuntimeProfiler.measure(RuntimeProfiler.Area.GUI) {
                    SkijaGlState().use {
                        context!!.resetGLAll()
                        if (batch.isNotEmpty()) {
                            SkijaMenuPainter.prepareSnapshots(context!!)
                            batch.forEach { (target, frame) -> target.draw(frame, context!!) }
                        }
                        context!!.flushAndSubmit(false)
                        SkijaMenuPainter.releaseRetired()
                    }
                }
            } catch (failure: Exception) { disable(failure) }
            catch (failure: LinkageError) { disable(failure) }
        }

        fun cacheSizes(): Map<String, Long> = mapOf("gpu.menuTextures" to targets.size.toLong(),
            "gpu.pendingFrames" to pending.size.toLong(),
            "gpu.menuBytes" to targets.sumOf { it.width.toLong() * it.height * 8 })
    }
}
