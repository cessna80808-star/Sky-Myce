package me.mycellium.skymyce.ui

import me.mycellium.skymyce.config.misc.ThemeConfig
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import java.lang.management.ManagementFactory

@Volatile private var navigationGeometrySink: MenuRect? = null

private fun measureNavigationGeometry() {
    val layout = MenuLayout(760, 440, 52)
    val geometry = MenuNavigationGeometry(layout)
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    bean?.takeIf { it.isThreadAllocatedMemorySupported }?.isThreadAllocatedMemoryEnabled = true
    fun frame() { geometry.update(0.0); menuSectionTitles.indices.forEach { navigationGeometrySink = geometry.rect(it) } }
    repeat(2000) { frame() }
    val id = Thread.currentThread().threadId()
    val bytes = bean?.getThreadAllocatedBytes(id) ?: 0L
    val start = System.nanoTime()
    repeat(10000) { frame() }
    val nanos = System.nanoTime() - start
    val allocated = (bean?.getThreadAllocatedBytes(id) ?: bytes) - bytes
    println("Menu navigation geometry: six settled cards, 10000 warmed frames: ${nanos / 10000 / 1000.0}us/frame ${allocated / 10000}B/frame")
    check(geometry.revision == 0L) { "Idle card geometry was rebuilt" }
}

private fun checkStableCardEdges(layout: MenuLayout) {
    val geometry = MenuNavigationGeometry(layout)
    for (compact in listOf(0.0, .25, .55, 1.0)) for ((originX, originY) in listOf(0.0 to 0.0, 36.0 to 80.0)) {
        geometry.update(compact, originX, originY)
        val rects = menuSectionTitles.indices.map { geometry.rect(it) }
        val revision = geometry.revision
        for (index in rects.indices) {
            val r = rects[index]
            val points = listOf(
                r.x + .25 to r.y + r.height / 2, r.x + r.width - .25 to r.y + r.height / 2,
                r.x + r.width / 2 to r.y + .25, r.x + r.width / 2 to r.y + r.height - .25,
                r.x - .25 to r.y + r.height / 2, r.x + r.width + .25 to r.y + r.height / 2,
                r.x + r.width / 2 to r.y - .25, r.x + r.width / 2 to r.y + r.height + .25,
                r.x + .25 to r.y + .25, r.x + r.width - .25 to r.y + r.height - .25,
            )
            for (mode in GuiMotion.choices + GuiMotion.FULL) for ((px, py) in points) {
                val expected = rects.indices.reversed().firstOrNull { rects[it].contains(px, py) }
                var now = 0L
                val hover = MotionValue(0.0) { now }
                repeat(90) { frame ->
                    now += 16_000_000
                    geometry.update(compact, originX, originY)
                    val selected = geometry.indexAt(px, py)
                    check(selected == expected) { "Hover/click/tooltip changed at $mode/$compact/card$index edge while feedback moved" }
                    hover.animateTo(if (selected == index) 1.0 else 0.0, 110, mode.profile.hoverEasing, mode)
                    val amount = hover.sample()
                    val press = if (frame in 20..25) .6 else 0.0
                    val painted = r.transformed(menuNavigationPose(r, compact, amount, press, mode))
                        .let { it.copy(y = it.y + (1 - minOf(1.0, frame / 20.0)) * mode.profile.revealOffset) }
                    check(painted.width > 0 && painted.height > 0)
                    // Visual hover/press/reveal feedback cannot revise the input footprint.
                    check(geometry.rect(index) === r && geometry.revision == revision)
                    if (frame == 89) check(!hover.running) { "Stationary edge hover never settled" }
                }
            }
            // Repeated leave/re-entry follows the pointer, never the preceding animated card pose.
            repeat(20) {
                check(geometry.indexAt(r.x + r.width / 2, r.y + r.height - .25) ==
                    rects.indices.reversed().firstOrNull { rects[it].contains(r.x + r.width / 2, r.y + r.height - .25) })
                check(geometry.indexAt(-1.0, -1.0) == null)
            }
        }
    }
    geometry.update(0.0)
    val before = geometry.rect(0); val revision = geometry.revision
    geometry.update(1.0)
    check(geometry.rect(0) != before && geometry.revision == revision + 1)
    check(geometry.indexAt(Double.NaN, Double.POSITIVE_INFINITY) == null)
}

fun main() {
    repeat(1000) { iteration ->
        val category = MenuCategory.entries[iteration % MenuCategory.entries.size]
        check(menuBackTarget(category) == MenuBackTarget.Category(category))
        check(menuBackTarget(category, categoryRoot = true) == MenuBackTarget.Home)
        check(menuBackTarget(null) == MenuBackTarget.Home) // Search, Quick, Favorites and Recent
        check(menuBackTarget(null, home = true) == MenuBackTarget.Game)
    }
    measureNavigationGeometry()
    check(menuSectionTitles == listOf("Dungeons", "Combat", "Economy", "Cosmetics", "Utils", "Settings"))
    for (index in MenuCategory.entries.indices) for (width in listOf(110, 140, 220, 400)) {
        val descriptions = menuNavigationDescription(index, width)
        check(descriptions.size in 1..2 && descriptions.all { MenuCanvas.textWidth(it, size = 9.0) <= width }) {
            "A home category description was clipped instead of wrapping at width $width"
        }
        check(menuNavigationDescription(index, width) === descriptions) { "Settled home cards repeatedly rewrapped descriptions" }
        if (width == 400) check(descriptions.joinToString(" ") == MenuCategory.entries[index].description)
    }
    val actionSets = listOf(listOf(90, 24, 24, 60), listOf(98, 105), listOf(94, 54), listOf(76, 76))
    for (available in listOf(80, 139, 163, 240, 400)) for (widths in actionSets) {
        val rows = menuActionRows(widths, available)
        check(rows.flatten() == widths.indices.toList())
        check(rows.all { row -> row.sumOf { widths[it].coerceAtMost(available) } + (row.size - 1) * 6 <= available }) {
            "A shortcut action was clipped at width $available"
        }
    }
    val layouts = listOf(320 to 260, 480 to 320, 960 to 540, 1440 to 900).map { (w, h) -> MenuLayout.fit(w, h) }
    for (layout in layouts) {
        checkStableCardEdges(layout)
        check(layout.sidebar <= 52 && layout.contentWidth >= layout.width * .66)
        val home = menuSectionTitles.indices.map { menuNavigationRect(layout, it, 0.0) }
        val mini = menuSectionTitles.indices.map { menuNavigationRect(layout, it, 1.0) }
        for (index in home.indices) {
            for (r in listOf(home[index], mini[index])) {
                check(r.width > 0 && r.height > 0 && r.x >= 0 && r.y >= 0)
                check(r.x + r.width <= layout.width && r.y + r.height <= layout.height)
            }
            check(home[index].y + home[index].height <= layout.height - minOf(56.0, layout.height * .2))
            check(mini[index].y + mini[index].height < layout.height - 64)
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
    val paths = listOf("animation", "graphics")
    val pinned = mutableMapOf<String, Any?>("animation" to "Medium", "graphics" to "Light")
    val captured = paths.map { pinned[it] }
    repeat(100) { check(!menuQuickValuesChanged(paths, captured) { pinned[it] }) }
    pinned["graphics"] = "High"
    check(menuQuickValuesChanged(paths, captured) { pinned[it] })
    check(menuQuickValuesChanged(paths.dropLast(1), captured) { pinned[it] })
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
                    val physical = r.transformed(menuNavigationPose(r, compact, hovered, 0.0, mode))
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
    println("Menu navigation checks passed: stable logical card edges across hover/press/reveal profiles, responsive cards/rail, rapid final intent, bounded shortcuts, real tile previews and idle geometry/raster reuse")
}
