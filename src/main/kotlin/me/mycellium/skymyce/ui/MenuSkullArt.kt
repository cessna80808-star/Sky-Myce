package me.mycellium.skymyce.ui

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Skin UVs and the skull item's 30°/45° GUI pose, baked without Minecraft rendering. */
internal fun menuSkullArt(skin: BufferedImage): BufferedImage? {
    if (skin.width < 64 || skin.width % 64 != 0) return null
    val factor = skin.width / 64
    if (skin.height != 32 * factor && skin.height != 64 * factor) return null
    val result = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    val scale = 56.0 / (8.5 * (sqrt(3.0) / 2 + 1 / sqrt(2.0)))
    val horizontal = scale / sqrt(2.0)
    val slope = horizontal / 2
    val vertical = scale * sqrt(3.0) / 2
    fun face(u: Int, v: Int, light: Double, transform: AffineTransform) {
        val size = 8 * factor
        val pixels = skin.getRGB(u * factor, v * factor, size, size, null, 0, size)
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = (color and 0xFF000000.toInt()) or
                (((color ushr 16 and 255) * light).roundToInt() shl 16) or
                (((color ushr 8 and 255) * light).roundToInt() shl 8) or
                ((color and 255) * light).roundToInt()
        }
        val face = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        face.setRGB(0, 0, size, size, pixels, 0, size)
        graphics.drawImage(face, transform, null)
    }
    for ((offset, radius) in listOf(0 to 4.0, 32 to 4.25)) {
        val edge = radius * 2 / (8 * factor)
        val left = 32 - radius * 2 * horizontal
        val top = 32 - radius * vertical
        // The player's right face is on screen-left; the front face remains unmirrored on screen-right.
        face(offset + 8, 0, 1.0, AffineTransform(edge * horizontal, -edge * slope,
            edge * horizontal, edge * slope, left, top))
        face(offset, 8, .72, AffineTransform(edge * horizontal, edge * slope,
            0.0, edge * vertical, left, top))
        face(offset + 8, 8, .9, AffineTransform(edge * horizontal, -edge * slope,
            0.0, edge * vertical, 32.0, top + radius * 2 * slope))
    }
    graphics.dispose()
    return result
}
