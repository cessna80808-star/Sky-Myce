package me.mycellium.skymyce.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ARGB
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage

/** Minecraft only composites the finished custom canvas; it paints no menu widgets or text. */
class MenuTexture : AutoCloseable {
    private var texture: DynamicTexture? = null
    private var pixels = IntArray(0)
    private var uploadedRevision: Long? = null

    fun render(graphics: GuiGraphicsExtractor, image: BufferedImage, logicalWidth: Int, logicalHeight: Int,
               revision: Long? = null) {
        require(logicalWidth > 0 && logicalHeight > 0)
        RenderSystem.assertOnRenderThread()
        var current = texture
        if (current == null || current.pixels.width != image.width || current.pixels.height != image.height) {
            close()
            current = DynamicTexture("Sky-Myce custom menu", image.width, image.height, false)
            texture = current
            pixels = IntArray(Math.multiplyExact(image.width, image.height))
        }
        if (revision == null || uploadedRevision != revision) {
            // getRGB returns straight ARGB even when Java2D's backing raster is premultiplied.
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            val nativePixels = MemoryUtil.memIntBuffer(current.pixels.pointer, pixels.size)
            for (index in pixels.indices) nativePixels.put(index, ARGB.toABGR(pixels[index]))
            current.upload()
            uploadedRevision = revision
        }
        graphics.blit(current.textureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            0, 0, logicalWidth, logicalHeight, 0f, 1f, 0f, 1f)
    }

    override fun close() {
        texture?.close()
        texture = null
        pixels = IntArray(0)
        uploadedRevision = null
    }
}
