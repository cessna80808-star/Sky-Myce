package me.mycellium.skymyce.ui

import me.mycellium.skymyce.config.misc.ThemeConfig
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

fun main() {
    check(menuSectionTitles == listOf("Dungeons", "Combat", "Economy", "Cosmetics", "Utils", "Settings"))
    val actionSets = listOf(listOf(68, 24, 24, 60), listOf(98, 105), listOf(94, 54), listOf(76, 70))
    for (available in listOf(80, 139, 163, 240, 400)) for (widths in actionSets) {
        val rows = menuActionRows(widths, available)
        check(rows.flatten() == widths.indices.toList())
        check(rows.all { row -> row.sumOf { widths[it].coerceAtMost(available) } + (row.size - 1) * 6 <= available }) {
            "A shortcut action was clipped at width $available"
        }
    }
    val layouts = listOf(320 to 260, 480 to 320, 960 to 540, 1440 to 900).map { (w, h) -> MenuLayout.fit(w, h) }
    for (layout in layouts) {
        check(layout.sidebar <= 52 && layout.contentWidth >= layout.width * .66)
        val home = menuSectionTitles.indices.map { menuNavigationRect(layout, it, 0.0) }
        val mini = menuSectionTitles.indices.map { menuNavigationRect(layout, it, 1.0) }
        for (index in home.indices) {
            for (r in listOf(home[index], mini[index])) {
                check(r.width > 0 && r.height > 0 && r.x >= 0 && r.y >= 0)
                check(r.x + r.width <= layout.width && r.y + r.height <= layout.height)
            }
            check(home[index].y + home[index].height <= layout.height - minOf(56.0, layout.height * .2))
            check(mini[index].y + mini[index].height < layout.height - 34)
            for (other in home.indices.filter { it != index }) {
                check(!home[other].contains(home[index].x + home[index].width / 2, home[index].y + home[index].height / 2))
                check(!mini[other].contains(mini[index].x + mini[index].width / 2, mini[index].y + mini[index].height / 2))
            }
            for (step in 0..20) {
                val p = step / 20.0
                check(home[index].mix(mini[index], p) == menuNavigationRect(layout, index, p))
            }
            val lifted = MotionPose(scale = 1.009, y = -6.0, pivotX = home[index].x + home[index].width / 2,
                pivotY = home[index].y + home[index].height / 2)
            val painted = home[index].transformed(lifted)
            check(painted.contains(painted.x + 1, painted.y + 1))
            check(home[index].contains(lifted.inverseX(painted.x + 1), lifted.inverseY(painted.y + 1)))
        }
    }
    var now = 0L
    val progress = MotionValue(0.0) { now }
    for (mode in GuiMotion.choices) {
        progress.snap(0.0)
        for (index in 0 until 50) {
            now += 2_000_000
            val before = progress.sample()
            progress.animateTo(if (index % 2 == 0) 0.0 else 1.0, mode.profile.layoutMillis, mode.profile.hoverEasing, mode)
            if (mode != GuiMotion.OFF) check(abs(progress.sample() - before) < 1e-9)
        }
        progress.animateTo(0.0, mode.profile.layoutMillis, mode.profile.hoverEasing, mode)
        now += 1_000_000_000
        check(progress.sample() == 0.0 && !progress.running) { "Rapid return to home queued an intermediate destination" }
    }
    val layout = MenuLayout(760, 440, 52)
    val canvas = MenuCanvas()
    val modeBefore = ThemeConfig.animationMode; val qualityBefore = ThemeConfig.graphicsQuality
    try {
        ThemeConfig.graphicsQuality = GuiQuality.MEDIUM
        for (mode in listOf(GuiMotion.LIGHT, GuiMotion.MEDIUM, GuiMotion.HEAVY)) {
            ThemeConfig.animationMode = mode
            for ((name, compact) in listOf("home" to 0.0, "transform" to .55, "submenu" to 1.0)) {
                canvas.begin()
                canvas.fill(0.0, 0.0, 760.0, 440.0, MenuTheme.alpha(MenuTheme.PANEL))
                canvas.opacity = (1 - compact * 3).coerceIn(0.0, 1.0)
                canvas.text(listOf(MenuCanvas.Run("SKY-MYCE", MenuTheme.alpha(MenuTheme.TEXT), bold = true)), 22.0, 17.0, 18.0)
                canvas.opacity = 1.0
                if (compact == 0.0) canvas.text(listOf(MenuCanvas.Run("Search / Quick Settings", MenuTheme.alpha(MenuTheme.ACCENT))), 275.0, 407.0, 11.0)
                if (compact > 0) {
                    canvas.fill(52.0, 0.0, 1.0, 440.0, MenuTheme.alpha(MenuTheme.BORDER))
                    canvas.text(listOf(MenuCanvas.Run("Dungeons", MenuTheme.alpha(MenuTheme.TEXT), bold = true)), 78.0, 30.0, 18.0)
                }
                menuSectionTitles.indices.forEach { index ->
                    val hovered = if (index == 3) 1.0 else 0.0
                    val r = menuNavigationRect(layout, index, compact)
                    val physical = r.transformed(MotionPose(scale = 1 + mode.profile.hoverScale * hovered,
                        y = -mode.profile.cardLift * hovered * (1 - compact), pivotX = r.x + r.width / 2, pivotY = r.y + r.height / 2))
                    paintMenuNavigationTile(canvas, physical, index, compact, hovered, if (index == 3) compact else 0.0,
                        homeRect = menuNavigationRect(layout, index, 0.0))
                }
                val image = canvas.finish(760, 440, 1.5)
                check(image.width == 1140 && image.height == 660)
                ImageIO.write(image, "png", File("navigation-${mode.name.lowercase()}-$name.png"))
            }
            // A settled frame must reuse the canvas rather than rasterizing it again.
            val revision = canvas.revision
            canvas.finish(760, 440, 1.5)
            check(canvas.revision == revision && canvas.dirtyRegion == null)
        }
    } finally {
        ThemeConfig.animationMode = modeBefore; ThemeConfig.graphicsQuality = qualityBefore; canvas.close()
    }
    println("Menu navigation checks passed: responsive cards/rail, reversible geometry, lifted hit coordinates, rapid final intent, real tile previews and settled raster reuse")
}
