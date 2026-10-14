package me.mycellium.skymyce.hud

import me.mycellium.skymyce.ui.menuSkullArt
import me.mycellium.skymyce.ui.menuItemTextureArt
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal fun checkMenuSkullArt() {
    val spriteSheet = BufferedImage(4, 8, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, if (y < 4) 0xFFFF0000.toInt() else 0x8080FF40.toInt())
    }
    val firstSprite = checkNotNull(menuItemTextureArt(spriteSheet, 4, 4, 0, -1))
    val secondSprite = checkNotNull(menuItemTextureArt(spriteSheet, 4, 4, 1, 0xFF80FF80.toInt()))
    check(firstSprite.width == 4 && firstSprite.height == 4 && firstSprite.getRGB(2, 2) == 0xFFFF0000.toInt())
    check(secondSprite.getRGB(2, 2) == 0x8040FF20.toInt()) { "Item sprite frame or Minecraft tint was lost" }
    check(spriteSheet.getRGB(2, 6) == 0x8080FF40.toInt()) { "Menu tint changed a shared resource image" }
    check(menuItemTextureArt(spriteSheet, 4, 4, 2, -1) == null)
    check(menuItemTextureArt(spriteSheet, 3, 4, 0, -1) == null)
    check(menuItemTextureArt(spriteSheet, 0, 4, 0, -1) == null)
    fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    fun sheet(height: Int = 64) = BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB).apply {
        val faces = listOf(Triple(0, 8, 0xFF40DE50.toInt()), Triple(8, 8, 0xFFEF3040.toInt()),
            Triple(16, 8, 0xFF40DEF0.toInt()), Triple(24, 8, 0xFFEFDE40.toInt()),
            Triple(8, 0, 0xFF4050F0.toInt()), Triple(16, 0, 0xFFEF40F0.toInt()))
        for ((x, y, color) in faces) for (row in y until y + 8) for (column in x until x + 8) setRGB(column, row, color)
    }
    fun overlay(image: BufferedImage, alpha: Int) {
        for ((x, y, color) in listOf(Triple(32, 8, 0xFF40F0), Triple(40, 8, 0xEFDE40), Triple(40, 0, 0x40DEF0)))
            for (row in y until y + 8) for (column in x until x + 8) image.setRGB(column, row, color or (alpha shl 24))
    }
    fun red(color: Int) = color ushr 16 and 255
    fun green(color: Int) = color ushr 8 and 255
    fun blue(color: Int) = color and 255
    val source = sheet(); val untouched = pixels(source)
    val bare = menuSkullArt(source)!!
    check(bare.width == 64 && bare.height == 64 && bare.getRGB(0, 0) == 0) { "Skull icon lost transparent padding" }
    val front = bare.getRGB(44, 38); val right = bare.getRGB(20, 38); val top = bare.getRGB(32, 17)
    check(red(front) > green(front) * 2 && green(right) > red(right) * 2 && blue(top) > red(top) * 2) { "Skull face UVs/orientation are wrong" }
    check(front ushr 24 == 255 && right ushr 24 == 255 && top ushr 24 == 255) { "Opaque skull faces lost alpha" }
    check(green(right) < 222 && blue(top) == 240) { "Skull face shading is missing or affects the top" }
    check(pixels(bare).all { it ushr 24 == 0 || it ushr 24 == 255 }) { "Nearest-neighbour skull edges gained blended alpha" }
    check(pixels(source).contentEquals(untouched)) { "Skull baking mutated the shared skin" }
    val asymmetric = menuSkullArt(sheet().apply {
        for (y in 8 until 16) for (x in 8 until 12) setRGB(x, y, 0xFF4050F0.toInt())
    })!!
    check(blue(asymmetric.getRGB(36, 42)) > red(asymmetric.getRGB(36, 42)) * 2 &&
        red(asymmetric.getRGB(50, 35)) > blue(asymmetric.getRGB(50, 35)) * 2) { "Front face was mirrored" }
    check(pixels(menuSkullArt(sheet(32))!!).contentEquals(pixels(bare))) { "Legacy skin head layout changed" }
    val hd = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until hd.height) for (x in 0 until hd.width) hd.setRGB(x, y, source.getRGB(x / 2, y / 2))
    check(pixels(menuSkullArt(hd)!!).contentEquals(pixels(bare))) { "Integral HD skin UVs changed" }
    val opaque = sheet().apply { overlay(this, 255) }; val hat = menuSkullArt(opaque)!!
    val hatFront = hat.getRGB(44, 38); val hatRight = hat.getRGB(20, 38); val hatTop = hat.getRGB(32, 17)
    check(red(hatFront) > blue(hatFront) * 2 && green(hatFront) > blue(hatFront) * 2 &&
        red(hatRight) > green(hatRight) * 2 && blue(hatRight) > green(hatRight) * 2 &&
        green(hatTop) > red(hatTop) * 2 && blue(hatTop) > red(hatTop) * 2) { "Hat UVs did not cover the corresponding skull faces" }
    val partial = menuSkullArt(sheet().apply { overlay(this, 128) })!!
    val partialFront = partial.getRGB(44, 38)
    check(partialFront ushr 24 == 255 && green(partialFront) in (green(front) + 1) until green(hatFront)) { "Hat alpha was flattened instead of blended" }
    val transparent = menuSkullArt(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))!!
    check(pixels(transparent).all { it == 0 }) { "Empty skin gained opaque pixels" }
    val translucent = menuSkullArt(sheet().apply {
        for (y in 8 until 16) for (x in 8 until 16) setRGB(x, y, getRGB(x, y) and 0x00FFFFFF or 0x80000000.toInt())
    })!!
    check(translucent.getRGB(44, 38) ushr 24 == 128) { "Face alpha was discarded" }
    for ((width, height) in listOf(63 to 64, 64 to 31, 64 to 48, 96 to 96, 128 to 96))
        check(menuSkullArt(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)) == null) { "Invalid skin dimensions $width x $height accepted" }
    val preview = BufferedImage(480, 128, BufferedImage.TYPE_INT_ARGB)
    val graphics = preview.createGraphics()
    graphics.color = Color(0x201116); graphics.fillRect(0, 0, preview.width, preview.height)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    listOf(bare, hat, partial, translucent).forEachIndexed { index, image -> graphics.drawImage(image, index * 120 + 4, 4, 112, 112, null) }
    graphics.dispose()
    val previewFile = File("build/menu-skull-check.png"); previewFile.parentFile.mkdirs()
    ImageIO.write(preview, "png", previewFile)
    println("Menu skull checks passed: face/hat UVs, legacy/HD sheets, alpha, shading and invalid dimensions; preview ${previewFile.absolutePath}")
}
