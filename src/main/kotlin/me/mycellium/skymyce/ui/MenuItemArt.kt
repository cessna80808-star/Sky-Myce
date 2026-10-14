package me.mycellium.skymyce.ui

import java.awt.image.BufferedImage

/** A static icon uses a real sprite frame, with the tint selected by Minecraft. */
internal fun menuItemTextureArt(source: BufferedImage, width: Int, height: Int, frame: Int, tint: Int): BufferedImage? {
    if (width !in 1..2048 || height !in 1..2048 || source.width % width != 0 || source.height % height != 0) return null
    val columns = source.width / width
    val rows = source.height / height
    if (frame < 0 || frame >= columns * rows) return null
    val left = frame % columns * width
    val top = frame / columns * height
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) {
            val color = source.getRGB(left + x, top + y)
            var tinted = 0
            for (shift in 0..24 step 8)
                tinted = tinted or (((color ushr shift and 255) * (tint ushr shift and 255) / 255) shl shift)
            setRGB(x, y, tinted)
        }
    }
}
