package me.mycellium.skymyce.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ARGB
import org.lwjgl.system.MemoryUtil
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import java.nio.IntBuffer
import java.awt.Rectangle

/** Extracted GUI frames retain texture views until rendering, even after their screen changes. */
internal class MenuTextureRetirement(private val fence: (Runnable) -> Unit) : AutoCloseable {
    private val waiting = LinkedHashSet<AutoCloseable>()
    private val fenced = LinkedHashSet<AutoCloseable>()

    fun retire(resource: AutoCloseable) { if (resource !in fenced) waiting += resource }
    fun afterTick() {
        if (waiting.isEmpty()) return
        val batch = waiting.toList()
        waiting.clear(); fenced.addAll(batch)
        fence(Runnable { batch.forEach { if (fenced.remove(it)) it.close() } })
    }
    override fun close() {
        val batch = waiting + fenced
        waiting.clear(); fenced.clear()
        batch.forEach(AutoCloseable::close)
    }
}

/** NativeImage stores straight RGBA; accept other raster layouts through getRGB. */
internal fun menuTexturePixels(image: BufferedImage, target: IntArray, region: Rectangle = Rectangle(0, 0, image.width, image.height)) {
    require(region.x >= 0 && region.y >= 0 && region.width >= 0 && region.height >= 0 &&
        region.x <= image.width && region.y <= image.height && region.width <= image.width - region.x && region.height <= image.height - region.y)
    val count = Math.multiplyExact(region.width, region.height)
    require(target.size >= count)
    if (count == 0) return
    val raster = image.raster
    val data = raster.dataBuffer as? DataBufferInt
    val model = raster.sampleModel as? SinglePixelPackedSampleModel
    if (image.type == BufferedImage.TYPE_INT_ARGB && data != null && data.numBanks == 1 &&
        data.offset == 0 && raster.sampleModelTranslateX == 0 && raster.sampleModelTranslateY == 0 &&
        model?.scanlineStride == image.width && data.data.size == image.width * image.height) {
        val source = data.data
        for (row in 0 until region.height) {
            val offset = (region.y + row) * image.width + region.x
            val destination = row * region.width
            for (column in 0 until region.width) target[destination + column] = ARGB.toABGR(source[offset + column])
        }
    } else {
        image.getRGB(region.x, region.y, region.width, region.height, target, 0, region.width)
        for (index in 0 until count) target[index] = ARGB.toABGR(target[index])
    }
}

/** Minecraft only composites the finished custom canvas; it paints no menu widgets or text. */
class MenuTexture : AutoCloseable {
    private var texture: DynamicTexture? = null
    private var pixels = IntArray(0)
    private var nativePixels: IntBuffer? = null
    private var uploadedRevision: Long? = null

    fun render(graphics: GuiGraphicsExtractor, image: BufferedImage, logicalWidth: Int, logicalHeight: Int,
               revision: Long? = null, dirtyRegion: Rectangle? = null) {
        require(logicalWidth > 0 && logicalHeight > 0)
        RenderSystem.assertOnRenderThread()
        var current = texture
        val resized = current == null || current.pixels.width != image.width || current.pixels.height != image.height
        if (resized) {
            val nextPixels = IntArray(Math.multiplyExact(image.width, image.height))
            val next = DynamicTexture("Sky-Myce custom menu", image.width, image.height, false)
            val nextNativePixels = MemoryUtil.memIntBuffer(next.pixels.pointer, nextPixels.size)
            current?.let(retirement::retire)
            current = next; texture = next; pixels = nextPixels; nativePixels = nextNativePixels
            uploadedRevision = null
        }
        if (revision == null || uploadedRevision != revision) {
            val whole = Rectangle(0, 0, image.width, image.height)
            val region = if (resized || dirtyRegion == null) whole else dirtyRegion.intersection(whole)
            if (!region.isEmpty) {
                menuTexturePixels(image, pixels, region)
                for (row in 0 until region.height) {
                    nativePixels!!.position((region.y + row) * image.width + region.x)
                        .put(pixels, row * region.width, region.width)
                }
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(current.texture, current.pixels,
                    0, 0, region.x, region.y, region.width, region.height, region.x, region.y)
            }
            uploadedRevision = revision
        }
        graphics.blit(current.textureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            0, 0, logicalWidth, logicalHeight, 0f, 1f, 0f, 1f)
    }

    override fun close() {
        texture?.let { RenderSystem.assertOnRenderThread(); retirement.retire(it) }
        texture = null
        pixels = IntArray(0)
        nativePixels = null
        uploadedRevision = null
    }

    companion object {
        private val retirement by lazy {
            MenuTextureRetirement(RenderSystem::queueFencedTask).also { resources ->
                // Fences queued during extraction may run before that frame's GUI renders.
                // The next tick is the first safe point to fence the old extracted frame.
                ClientTickEvents.END_CLIENT_TICK.register { resources.afterTick() }
                ClientLifecycleEvents.CLIENT_STOPPING.register { resources.close() }
            }
        }
    }
}
