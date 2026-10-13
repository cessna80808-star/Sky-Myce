package me.mycellium.skymyce.ui

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
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
