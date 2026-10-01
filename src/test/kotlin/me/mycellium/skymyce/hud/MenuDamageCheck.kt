package me.mycellium.skymyce.hud

import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.menuTexturePixels
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Every retained frame and its regional upload must equal a fresh full repaint. */
internal fun checkMenuDamage() {
    fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    fun abgr(color: Int) = (color and 0xFF00FF00.toInt()) or (color ushr 16 and 255) or (color and 255 shl 16)
    val icons = List(2) { variant -> BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width)
            setRGB(x, y, if ((x + y + variant) % 2 == 0) 0x8067CCF2.toInt() else 0xCCE9A5B4.toInt())
    } }
    fun scene(c: MenuCanvas, step: Int) {
        c.begin()
        c.fill(0.0, 0.0, 300.0, 180.0, 0xA5090608.toInt())
        c.gradient(8.0, 8.0, 284.0, 164.0, 0xD0321A23.toInt(), 0xB0201116.toInt(), 0xB0201116.toInt(), 0xD0201116.toInt())
        c.text(listOf(MenuCanvas.Run("Static menu: Wi ✓", -1)), 14.25, 11.5)
        c.picture(icons[step % 2], 230.25 + step, 15.5, 27.75, 24.25)
        c.clip = MenuCanvas.Clip(11, 39, 264, 118)
        c.transform = MenuCanvas.Transform(1.0, .12, -.08, 1.0, 13.5, -6.75)
        c.gradient(22.25, 47.5, 138.5, 51.75, 0xB0321A23.toInt(), 0x90201116.toInt(), 0x907F9CEF.toInt(), 0xB0201116.toInt())
        c.line(7.25, 52.5, 151.75 + step, 95.25, 4.5, 0xB0DF8196.toInt())
        c.arc(181.5 + step, 77.25, 14.5, 25.0, 315.0, 0x907F9CEF.toInt(), 9.25)
        c.text(listOf(MenuCanvas.Run("Русский 中文 😀 a\u0301", 0xD0F2E9EC.toInt(), bold = true, italic = true, underline = true)),
            17.25 + step, 51.5, 12.25)
        c.transform = MenuCanvas.Transform(); c.clip = null
        if (step in 1..4) {
            // Hover paint is inserted/removed; tooltip moves over several translucent layers.
            c.fill(14.5 + step * 15, 37.25, 55.0, 19.5, 0x60DF8196)
            val left = 77.25 + step * 17; val top = 94.5 + step * 3
            c.fill(left, top, 142.5, 42.5, 0xDA10090C.toInt())
            c.arc(left + 3, top + 3, 3.0, 0.0, 90.0, 0xDA10090C.toInt())
            c.text(listOf(MenuCanvas.Run("Tooltip ✓ $step", -1, bold = true)), left + 7, top + 5)
            c.text(listOf(MenuCanvas.Run("Old pixels must clear", 0xD0E9A5B4.toInt())), left + 7, top + 20)
        }
    }
    fun compare(scale: Double, steps: List<Int>, width: Int = 300, height: Int = 180,
                emit: (MenuCanvas, Int) -> Unit) {
        val retained = MenuCanvas()
        var previous: IntArray? = null
        var texture: IntArray? = null
        for (step in steps) {
            val beforeRevision = retained.revision
            emit(retained, step)
            val image = retained.finish(width, height, scale)
            val actual = pixels(image)
            val fresh = MenuCanvas(); emit(fresh, step)
            val reference = fresh.finish(width, height, scale)
            val expected = pixels(reference)
            if (!actual.contentEquals(expected)) {
                javax.imageio.ImageIO.write(image, "png", java.io.File("menu-damage-actual-$scale-$step.png"))
                javax.imageio.ImageIO.write(reference, "png", java.io.File("menu-damage-expected-$scale-$step.png"))
                val differences = actual.indices.filter { actual[it] != expected[it] }
                error("Retained pixels differ at scale $scale/step $step/damage ${retained.dirtyRegion}: ${differences.size} pixels; " +
                    differences.take(8).joinToString { "(${it % image.width},${it / image.width}) ${actual[it].toUInt().toString(16)}/${expected[it].toUInt().toString(16)}" })
            }
            val region = retained.dirtyRegion
            val accumulated = texture ?: IntArray(actual.size).also { texture = it }
            if (region == null) {
                check(retained.revision == beforeRevision && previous?.contentEquals(actual) == true) { "No-damage frame changed pixels/revision" }
            } else {
                check(Rectangle(0, 0, image.width, image.height).contains(region)) { "Damage is outside the canvas" }
                previous?.let { old ->
                    for (index in actual.indices) if (!region.contains(index % image.width, index / image.width))
                        check(old[index] == actual[index]) { "Pixels changed outside reported damage" }
                }
                val count = region.width * region.height
                val packed = IntArray(count + 3) { 0x12345678 }
                menuTexturePixels(image, packed, region)
                check(packed.takeLast(3).all { it == 0x12345678 }) { "Regional conversion overwrote unused buffer space" }
                for (row in 0 until region.height) for (column in 0 until region.width) {
                    val destination = (region.y + row) * image.width + region.x + column
                    accumulated[destination] = packed[row * region.width + column]
                }
            }
            check(accumulated.indices.all { accumulated[it] == abgr(expected[it]) }) { "Regional upload retained stale/incorrect pixels" }
            previous = actual
        }
    }
    for (scale in listOf(.75, 1.0, 1.25, 1.5, 2.0)) compare(scale, listOf(0, 1, 2, 3, 4, 5, 5, 0), emit = ::scene)
    // Clip rasterization can include a fractional edge pixel even when exact geometry is outside the clip.
    for ((scale, clipWidth) in listOf(1.25 to 19, .75 to 21)) compare(scale, listOf(0, 1, 2, 2), 40, 30) { c, step ->
        c.begin(); c.clip = MenuCanvas.Clip(0, 0, clipWidth, 20)
        if (step < 2) c.fill(clipWidth + .01, 5.0, 5.0, 5.0, if (step == 0) -1 else 0x80DF8196.toInt())
    }
    compare(2.0, listOf(0, 1, 1), 40, 30) { c, step ->
        c.begin(); c.clip = MenuCanvas.Clip(0, 0, 10, 10)
        c.gradient(11.5, 3.0, 5.0, 5.0, if (step == 0) -1 else 0x80DF8196.toInt(), -1, -1, -1)
    }
    // High-contrast gradients expose interpolation threshold drift hidden by the menu's muted palette.
    compare(2.0, listOf(0, 1, 2)) { c, step ->
        c.begin()
        c.gradient(8.0, 8.0, 284.0, 164.0, 0xFF000000.toInt(), -1, -1, 0xFF000000.toInt())
        c.fill(81.0, 15.5, 50.0, 50.0, if (step == 0) 0xFF7F9CEF.toInt() else 0xFFDF8196.toInt())
    }
    for (scale in listOf(.75, 1.25, 2.0)) compare(scale, (0..12).toList()) { c, step ->
        c.begin(); c.fill(0.0, 0.0, 300.0, 180.0, 0xA5090608.toInt())
        c.clip = MenuCanvas.Clip(13 + step * 7, 11 + step % 4, 143, 127)
        c.gradient(8.25, 8.5, 284.0, 164.0, 0xC0000000.toInt(), -1, 0x80FFFFFF.toInt(), 0xFF000000.toInt())
        c.arc(150.25, 78.5, 32.0, 0.0, 360.0, 0x607F9CEF)
    }
    fun navScene(c: MenuCanvas, step: Int) {
        c.begin(); c.fill(0.0, 0.0, 800.0, 480.0, 0xA5090608.toInt())
        if (step in 1..3) {
            val y = 30.0 + step * 29
            // Navigation boxes insert several paints before the unchanged content and trailing tooltip.
            c.fill(16.0, y, 124.0, 28.0, 0xD029171D.toInt())
            c.fill(12.0, y + 4, 132.0, 20.0, 0xD029171D.toInt())
            for ((x, cornerY, start) in listOf(Triple(16.0, y + 4, 0.0), Triple(140.0, y + 4, 90.0),
                Triple(140.0, y + 24, 180.0), Triple(16.0, y + 24, 270.0)))
                c.arc(x, cornerY, 4.0, start, start + 90, 0xD029171D.toInt())
            c.fill(16.0, y, 124.0, 1.0, 0xFFDF8196.toInt())
        }
        for (index in 0 until 60) {
            val x = 210.0 + index % 2 * 275; val y = 14.0 + index / 2 * 14
            c.fill(x, y, 255.0, 12.0, 0x8029171D.toInt())
            c.text(listOf(MenuCanvas.Run("Unchanged card $index", -1)), x + 4, y)
        }
        if (step in 1..3) {
            c.fill(22.0 + step * 3, 153.0, 139.0, 25.0, 0xDA10090C.toInt())
            c.text(listOf(MenuCanvas.Run("Moving tooltip", -1)), 28.0 + step * 3, 158.0)
        }
    }
    compare(2.0, listOf(0, 1, 2, 3, 0), 800, 480, ::navScene)
    val navigation = MenuCanvas()
    for (step in listOf(0, 1, 2, 3, 0)) {
        navScene(navigation, step); val image = navigation.finish(800, 480, 2.0)
        if (step != 0 || navigation.revision > 1) {
            val damage = navigation.dirtyRegion!!
            check(damage.width * damage.height < image.width * image.height / 3) { "Inserted navigation paints included unchanged content in damage: $damage" }
        }
    }
    val tooltip = MenuCanvas()
    fun tooltipFrame(x: Double) {
        tooltip.begin(); tooltip.fill(0.0, 0.0, 300.0, 180.0, 0xA5090608.toInt())
        tooltip.gradient(10.0, 10.0, 280.0, 160.0, 0xD0201116.toInt(), 0xB029171D.toInt(), 0xB0201116.toInt(), 0xD029171D.toInt())
        tooltip.fill(x, 55.5, 100.0, 24.0, 0xD010090C.toInt())
        tooltip.text(listOf(MenuCanvas.Run("Move ✓", -1)), x + 6, 59.5)
    }
    tooltipFrame(60.0); tooltip.finish(300, 180, 2.0)
    tooltipFrame(61.0); val moved = tooltip.finish(300, 180, 2.0)
    check(tooltip.dirtyRegion!!.width * tooltip.dirtyRegion!!.height < moved.width * moved.height / 4) { "One-pixel tooltip move repainted the full menu" }
    // A scale change with the same physical dimensions must fully replace the old scaled pixels.
    tooltipFrame(61.0); val resized = tooltip.finish(600, 360, 1.0)
    check(tooltip.dirtyRegion == Rectangle(0, 0, resized.width, resized.height)) { "Scale change did not force a full repaint" }
    val hidden = MenuCanvas()
    fun hiddenFrame(color: Int) { hidden.begin(); hidden.clip = MenuCanvas.Clip(0, 0, 10, 10); hidden.fill(40.0, 40.0, 8.0, 8.0, color) }
    hiddenFrame(-1); hidden.finish(100, 60)
    val revision = hidden.revision
    hiddenFrame(0x80DF8196.toInt()); hidden.finish(100, 60)
    check(hidden.dirtyRegion == null && hidden.revision == revision) { "Completely clipped changes triggered repaint/upload" }
    println("Menu damage checks passed: full-frame parity, alpha/clip edges, retained texture rows, hover/tooltip removal and scale changes")
}
