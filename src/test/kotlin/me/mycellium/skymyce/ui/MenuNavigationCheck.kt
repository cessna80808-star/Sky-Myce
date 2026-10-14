package me.mycellium.skymyce.ui

import me.mycellium.skymyce.config.misc.ThemeConfig
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import java.lang.management.ManagementFactory
import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.ImageInfo
import io.github.humbleui.skija.Surface

@Volatile private var navigationGeometrySink: MenuRect? = null

private fun measureNavigationGeometry() {
    val layout = MenuLayout(760, 440, 52)
    val geometry = MenuNavigationGeometry(layout)
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    bean?.takeIf { it.isThreadAllocatedMemorySupported }?.isThreadAllocatedMemoryEnabled = true
    fun frame() { geometry.update(); menuSectionTitles.indices.forEach { navigationGeometrySink = geometry.rect(it) } }
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
    for ((originX, originY) in listOf(0.0 to 0.0, 36.0 to 80.0)) {
        geometry.update(originX, originY)
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
                    geometry.update(originX, originY)
                    val selected = geometry.indexAt(px, py)
                    check(selected == expected) { "Hover/click/tooltip changed at $mode/card$index edge while feedback moved" }
                    hover.animateTo(if (selected == index) 1.0 else 0.0, 110, mode.profile.hoverEasing, mode)
                    val amount = hover.sample()
                    val press = if (frame in 20..25) .6 else 0.0
                    val painted = r.transformed(menuNavigationPose(r, amount, press, mode))
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
    geometry.update()
    val before = geometry.rect(0); val revision = geometry.revision
    geometry.update(36.0, 80.0)
    check(geometry.rect(0) != before && geometry.revision == revision + 1)
    check(geometry.indexAt(Double.NaN, Double.POSITIVE_INFINITY) == null)
}

/** Explicit geometry overrides reach the same surfaces as shared controls. */
private fun checkNavigationCornerOverrides() {
    val previousPreset = ThemeConfig.preset
    val previousRounded = ThemeConfig.rounded
    val canvas = MenuCanvas()
    try {
        for (preset in listOf(me.mycellium.skymyce.hud.ThemePreset.MINIMAL, me.mycellium.skymyce.hud.ThemePreset.HIGH_CONTRAST)) {
            ThemeConfig.preset = preset
            for (rounded in listOf(0, 1)) {
                ThemeConfig.rounded = rounded
                canvas.begin()
                paintMenuNavigationTile(canvas, MenuRect(8.0, 64.0, 128.0, 34.0), 0, hover = 1.0, focused = true)
                paintMenuHomeTile(canvas, MenuRect(8.0, 110.0, 128.0, 70.0), listOf("Dungeons"), emptyList(), 4, focused = true)
                val surfaces = canvas.finishFrame(160, 220, 1.0).commands.filter { it.kind == 7 || it.kind == 8 }
                val radius = if (rounded == 1) 2.0 else 0.0
                check(surfaces.size >= 4 && surfaces.all { it.extra == radius }) {
                    "Home cards or category navigation ignored the explicit corner override in $preset"
                }
            }
        }
    } finally {
        canvas.close(); ThemeConfig.preset = previousPreset; ThemeConfig.rounded = previousRounded
    }
}

/** Compare real Home text commands with unclipped native pixels through fractional shell transforms. */
private fun checkNativeHomeGlyphEdges() {
    val previousActive = SkijaMenuFonts.active
    val previousMode = ThemeConfig.animationMode
    val canvas = MenuCanvas()
    fun pixels(frame: MenuCanvas.Frame): IntArray = Surface.makeRaster(ImageInfo.makeN32Premul(frame.width, frame.height)).use { surface ->
        surface.canvas.clear(0)
        SkijaMenuPainter.draw(surface.canvas, frame)
        surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
            IntArray(frame.width * frame.height) { bitmap.getColor(it % frame.width, it / frame.width) }
        } }
    }
    try {
        SkijaMenuFonts.active = true
        val rect = MenuRect(110.25, 72.5, 400.0, 86.0)
        for (mode in listOf(GuiMotion.LIGHT, GuiMotion.MEDIUM, GuiMotion.HEAVY)) {
            ThemeConfig.animationMode = mode
            for (hover in listOf(0.0, 1.0)) for (scale in listOf(1.0, 1.5, 2.0)) for (shellScale in listOf(1.0, .975)) {
                val pose = MotionPose(scale = shellScale, x = 7.5, y = -2.25, pivotX = 350.0, pivotY = 180.0)
                canvas.begin()
                canvas.transform = MenuCanvas.Transform(a = pose.scale, d = pose.scale,
                    x = pose.x + pose.pivotX * (1 - pose.scale), y = pose.y + pose.pivotY * (1 - pose.scale))
                paintMenuHomeTile(canvas, rect, listOf("Dungeons"), listOf("Parties, routes and run history"), 4, hover)
                val frame = canvas.finishFrame(700, 360, scale)
                val text = frame.commands.filter { it.kind == 3 }
                check(text.any { it.runs.any { run -> run.text == "Dungeons" } })
                check(text.any { it.runs.any { run -> run.text == "Parties, routes and run history" } })
                val clipped = MenuCanvas.Frame(frame.width, frame.height, frame.scale, frame.revision, text)
                val reference = MenuCanvas.Frame(frame.width, frame.height, frame.scale, frame.revision,
                    text.map { it.copy(clip = null) })
                check(pixels(clipped).contentEquals(pixels(reference))) {
                    "Home title/description glyph ink was clipped at $mode hover=$hover scale=$scale shell=$shellScale"
                }
            }
        }
    } finally {
        canvas.close(); SkijaMenuPainter.close()
        SkijaMenuFonts.active = previousActive; ThemeConfig.animationMode = previousMode
    }
}

fun main() {
    repeat(1000) { iteration ->
        val category = MenuCategory.entries[iteration % MenuCategory.entries.size]
        check(menuBackTarget(category) == MenuBackTarget.Category(category))
        check(menuBackTarget(category, openedFromHome = true) == MenuBackTarget.Home)
        check(menuBackTarget(category, categoryRoot = true) == MenuBackTarget.Home)
        check(menuBackTarget(null) == MenuBackTarget.Home) // Search, Quick, Favorites and Recent
        check(menuBackTarget(null, home = true) == MenuBackTarget.Game)
        check(menuBackTarget(null, home = true, openedFromHome = true) == MenuBackTarget.Game)
    }
    measureNavigationGeometry()
    check(menuSectionTitles == listOf("Dungeons", "Combat", "Economy", "Cosmetics", "Utils", "Settings"))
    val homeTools = defaultMenuHomeTools.indices.map { menuHomeFeature(it) }
    check(homeTools.map { it.id } == listOf("party-finder", "dungeon-tracker", "auction-browser", "loans", "cosmetics", "daily-digest"))
    check(homeTools.all(::canUseMenuHomeTool)) { "A Home card opens a category or settings-only feature instead of a tool" }
    val customized = defaultMenuHomeTools.toMutableList().apply { this[0] = "rng-journal" }
    check(menuHomeFeature(0, customized).tool == "rng-journal")
    for (index in MenuCategory.entries.indices) for (width in listOf(110, 140, 220, 400)) {
        val descriptions = menuNavigationDescription(index, width)
        check(descriptions.size in 1..2 && descriptions.all { MenuCanvas.textWidth(it, size = 9.0) <= width }) {
            "A Home tool description was clipped instead of wrapping at width $width"
        }
        check(menuNavigationDescription(index, width) === descriptions) { "Settled home cards repeatedly rewrapped descriptions" }
        if (width == 400) check(descriptions.joinToString(" ") == homeTools[index].description)
    }
    val defaultDescription = menuNavigationDescription(0, 400)
    val customDescription = menuNavigationDescription(0, 400, menuHomeFeature(0, customized))
    check(customDescription.joinToString(" ") == menuHomeFeature(0, customized).description && customDescription != defaultDescription)
    val longTitle = MenuCatalog.definitions.first { it.id == "leaderboards" }
    for (width in listOf(90, 110, 220, 400)) {
        val title = menuNavigationTitle(0, width, 11.0, 2, longTitle)
        check(title.size in 1..2 && title.all { MenuCanvas.textWidth(it, bold = true, size = 11.0) <= width })
        check(menuNavigationTitle(0, width, 11.0, 2, longTitle) === title) { "Settled customized Home titles rewrapped each frame" }
        if (width == 400) check(title.joinToString(" ") == longTitle.title)
    }
    val actionSets = listOf(listOf(90, 24, 24, 60), listOf(98, 105), listOf(94, 54), listOf(76, 76))
    for (available in listOf(160, 300, 579, 580, 900, 1048)) {
        val columns = menuFeatureColumns(available)
        val width = (available - (columns - 1) * 8) / columns
        check(columns in 1..2 && width > 0 && width * columns + (columns - 1) * 8 <= available)
        if (columns == 2) check(width >= 286) { "Feature controls were squeezed into narrow cards" }
        check(menuActionRows(listOf(76, 76, 64), width - 20).all { indices ->
            indices.sumOf { listOf(76, 76, 64)[it] } + (indices.size - 1) * 6 <= width - 20
        })
    }
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
        check(layout.sidebar <= 144 && layout.contentWidth >= layout.width * .65)
        val railLayout = layout.copy(height = maxOf(260, layout.height))
        val rail = menuSectionTitles.indices.map { menuNavigationRect(railLayout, it) }
        for (index in rail.indices) {
            val r = rail[index]
            check(r.width > 0 && r.height > 0 && r.x >= 0 && r.y >= 0)
            check(r.x + r.width <= railLayout.sidebar && r.y + r.height < railLayout.height - 34)
            check(r.x + r.width < railLayout.sidebar + 1) { "Categories covered their content panel" }
            for (other in rail.indices.filter { it != index }) {
                check(!rail[other].contains(r.x + r.width / 2, r.y + r.height / 2))
            }
            repeat(2000) { check(menuNavigationRect(railLayout, index) == r) { "Rapid navigation moved the category rail" } }
        }
        val available = (layout.contentWidth - 36).coerceAtLeast(1)
        val columns = menuHomeColumns(available)
        val tileWidth = (available - (columns - 1) * 8) / columns
        check(columns in 1..3 && tileWidth > 0 && tileWidth * columns + (columns - 1) * 8 <= available)
        if (columns > 1) check(tileWidth >= 180) { "Category descriptions were squeezed into narrow tiles" }
        val toolColumns = menuHomeToolColumns(available)
        val toolWidth = (available - (toolColumns - 1) * 8) / toolColumns
        check(toolWidth > 0 && toolWidth * toolColumns + (toolColumns - 1) * 8 <= available)
        if (toolColumns > 1) check(toolWidth >= 174) { "Home shortcuts were squeezed into unreadable columns" }
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
            for (name in listOf("home", "submenu")) {
                canvas.begin()
                canvas.fill(0.0, 0.0, 760.0, 440.0, MenuTheme.alpha(MenuTheme.PANEL))
                canvas.fill(0.0, 0.0, 144.0, 440.0, MenuTheme.alpha(MenuTheme.SIDEBAR))
                canvas.fill(143.0, 0.0, 1.0, 440.0, MenuTheme.alpha(MenuTheme.BORDER))
                canvas.text(listOf(MenuCanvas.Run("SKY-MYCE", MenuTheme.alpha(MenuTheme.TEXT), bold = true)), 14.0, 10.0, 10.0)
                canvas.text(listOf(MenuCanvas.Run(if (name == "home") "Your SkyBlock workspace" else "Dungeons", MenuTheme.alpha(MenuTheme.TEXT), bold = true)),
                    160.0, 16.0, 19.0)
                menuSectionTitles.indices.forEach { index ->
                    val r = menuNavigationRect(layout.copy(sidebar = 144), index)
                    paintMenuNavigationTile(canvas, r, index, if (index == 3) 1.0 else 0.0, if (name == "submenu" && index == 0) 1.0 else 0.0)
                    if (name == "home") {
                        val category = MenuCategory.entries[index]
                        paintMenuHomeTile(canvas, MenuRect(160.0 + index % 2 * 300, 70.0 + index / 2 * 85, 284.0, 73.0),
                            listOf(category.title), listOf(category.description.take(38)), category.iconId)
                    }
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
    checkNavigationCornerOverrides()
    checkNativeHomeGlyphEdges()
    println("Menu navigation checks passed: stable logical card edges across hover/press/reveal profiles, native Home glyph edges, stationary categories and responsive home tiles, rapid final intent, bounded shortcuts, real tile previews and idle geometry/raster reuse")
}
