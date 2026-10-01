package me.mycellium.skymyce.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ARGB
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import java.nio.IntBuffer

/** NativeImage stores straight RGBA; accept other raster layouts through getRGB. */
internal fun menuTexturePixels(image: BufferedImage, target: IntArray) {
    require(target.size == Math.multiplyExact(image.width, image.height))
    val raster = image.raster
    val data = raster.dataBuffer as? DataBufferInt
    val model = raster.sampleModel as? SinglePixelPackedSampleModel
    if (image.type == BufferedImage.TYPE_INT_ARGB && data != null && data.numBanks == 1 &&
        data.offset == 0 && raster.sampleModelTranslateX == 0 && raster.sampleModelTranslateY == 0 &&
        model?.scanlineStride == image.width && data.data.size == target.size) {
        data.data.copyInto(target)
    } else {
        image.getRGB(0, 0, image.width, image.height, target, 0, image.width)
    }
    for (index in target.indices) target[index] = ARGB.toABGR(target[index])
}

/** Minecraft only composites the finished custom canvas; it paints no menu widgets or text. */
class MenuTexture : AutoCloseable {
    private var texture: DynamicTexture? = null
    private var pixels = IntArray(0)
    private var nativePixels: IntBuffer? = null
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
            nativePixels = MemoryUtil.memIntBuffer(current.pixels.pointer, pixels.size)
        }
        if (revision == null || uploadedRevision != revision) {
            menuTexturePixels(image, pixels)
            nativePixels!!.clear().put(pixels)
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
        nativePixels = null
        uploadedRevision = null
    }
}
