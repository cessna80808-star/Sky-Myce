package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.*
import io.wispforest.owo.ui.container.UIContainers
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

internal data class MenuRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + width && py < y + height
    fun mix(other: MenuRect, progress: Double): MenuRect {
        val p = progress.coerceIn(0.0, 1.0)
        fun mix(a: Double, b: Double) = a * (1 - p) + b * p
        return MenuRect(mix(x, other.x), mix(y, other.y), mix(width, other.width), mix(height, other.height))
    }
    fun transformed(pose: MotionPose) = MenuRect(pose.pivotX + (x - pose.pivotX) * pose.scale + pose.x,
        pose.pivotY + (y - pose.pivotY) * pose.scale + pose.y, width * pose.scale, height * pose.scale)
}

internal val menuSectionTitles = MenuCategory.entries.map { it.title }
internal fun menuHomeFeature(index: Int, ids: List<String> = defaultMenuHomeTools): MenuFeature =
    MenuCatalog.definitions.first { it.id == ids[index] }
private data class NavigationDescription(val id: String, val width: Int, val nativeMetrics: Boolean, val lines: List<String>)
private val descriptionLines = arrayOfNulls<NavigationDescription>(MenuCategory.entries.size)
private data class NavigationTitle(val id: String, val width: Int, val size: Double, val maximumLines: Int,
                                   val nativeMetrics: Boolean, val lines: List<String>)
private val titleLines = arrayOfNulls<NavigationTitle>(MenuCategory.entries.size)

private fun navigationTextLines(text: String, width: Int, size: Double, bold: Boolean, maximumLines: Int): List<String> {
    val component = Component.literal(text).apply { if (bold) withStyle(ChatFormatting.BOLD) }
    val wrapped = menuTextLines(component, width, size).map { line ->
        buildString { line.accept { _, _, codepoint -> appendCodePoint(codepoint); true } }
    }
    val lines = wrapped.take(maximumLines).toMutableList()
    if (wrapped.size > maximumLines && lines.isNotEmpty()) {
        var last = lines.last().trimEnd()
        while (last.isNotEmpty() && MenuCanvas.textWidth("$last…", bold = bold, size = size) > width)
            last = last.dropLast(Character.charCount(last.codePointBefore(last.length))).trimEnd()
        lines[lines.lastIndex] = if (MenuCanvas.textWidth("$last…", bold = bold, size = size) <= width) "$last…" else ""
    }
    return lines
}

internal fun menuNavigationTitle(index: Int, width: Int, size: Double, maximumLines: Int,
                                 feature: MenuFeature = menuHomeFeature(index)): List<String> {
    val available = width.coerceAtLeast(1)
    val nativeMetrics = SkijaMenuFonts.active
    titleLines[index]?.takeIf { it.id == feature.id && it.width == available && it.size == size &&
        it.maximumLines == maximumLines && it.nativeMetrics == nativeMetrics }?.let { return it.lines }
    return navigationTextLines(feature.title, available, size, true, maximumLines).also {
        titleLines[index] = NavigationTitle(feature.id, available, size, maximumLines, nativeMetrics, it)
    }
}

/** Home descriptions are measured once per width and renderer, with a bounded second line. */
internal fun menuNavigationDescription(index: Int, width: Int, feature: MenuFeature = menuHomeFeature(index)): List<String> {
    val available = width.coerceAtLeast(1)
    val nativeMetrics = SkijaMenuFonts.active
    descriptionLines[index]?.takeIf { it.id == feature.id && it.width == available && it.nativeMetrics == nativeMetrics }?.let { return it.lines }
    return navigationTextLines(feature.description, available, 9.0, false, 2).also {
        descriptionLines[index] = NavigationDescription(feature.id, available, nativeMetrics, it)
    }
}

/** Categories keep their identity and position on Home and every submenu. */
internal fun menuNavigationRect(layout: MenuLayout, index: Int): MenuRect {
    require(index in menuSectionTitles.indices)
    val footer = if (layout.height >= 320) 70.0 else 34.0
    val step = ((layout.height - 64.0 - footer) / menuSectionTitles.size).coerceIn(1.0, 38.0)
    val inset = minOf(8.0, layout.sidebar / 5.0)
    return MenuRect(inset, (64 + index * step).toInt().toDouble(), (layout.sidebar - inset * 2).coerceAtLeast(1.0),
        (step - 4).coerceAtLeast(1.0).toInt().toDouble())
}

/** Origin updates are retained; hover, press, reveal and page navigation never revise input bounds. */
internal class MenuNavigationGeometry(layout: MenuLayout) {
    private val local = Array(menuSectionTitles.size) { menuNavigationRect(layout, it) }
    private var rects = local
    private var originX = 0.0
    private var originY = 0.0
    var revision = 0L
        private set

    fun update(x: Double = 0.0, y: Double = 0.0) {
        if (originX == x && originY == y) return
        originX = x; originY = y
        rects = if (x == 0.0 && y == 0.0) local else Array(local.size) { local[it].copy(x = x + local[it].x, y = y + local[it].y) }
        revision++
    }
    fun rect(index: Int) = rects[index]
    fun indexAt(x: Double, y: Double): Int? {
        for (index in rects.indices.reversed()) if (rects[index].contains(x, y)) return index
        return null
    }
}

/** Small feedback stays inside the rail instead of shifting neighboring destinations. */
internal fun menuNavigationPose(rect: MenuRect, hover: Double, press: Double, mode: GuiMotion): MotionPose =
    MotionPose(scale = 1 - mode.profile.pressScale * press,
        pivotX = rect.x + rect.width / 2, pivotY = rect.y + rect.height / 2)

private fun paintNavigationRow(canvas: MenuCanvas, rect: MenuRect, title: String, icon: Int,
                               hover: Double, chosen: Double, focused: Boolean) {
    val style = ThemeConfig.preset.palette.style
    val radius = minOf(MenuTheme.RADIUS.toDouble(), rect.height / 2)
    val emphasis = maxOf(hover, chosen)
    if (emphasis > 0 || focused) canvas.roundedRect(rect.x, rect.y, rect.width, rect.height, radius,
        MenuTheme.alpha(MenuTheme.blend(MenuTheme.SIDEBAR, MenuTheme.ACCENT,
            (hover * style.hoverWeight + chosen * style.selectionWeight).toInt()), MenuTheme.OPACITY))
    if (focused) canvas.roundedBorder(rect.x, rect.y, rect.width, rect.height, radius, style.focusWidth.toDouble(), MenuTheme.alpha(MenuTheme.ACCENT))
    val expanded = rect.width >= 92
    val size = minOf(14.0, rect.height - 4).coerceAtLeast(1.0)
    val left = if (expanded) rect.x + 10 else rect.x + (rect.width - size) / 2
    paintMenuIcon(canvas, icon, left, rect.y + (rect.height - size) / 2, size,
        MenuTheme.alpha(if (chosen > .1 || hover > .1 || focused) MenuTheme.ACCENT else MenuTheme.MUTED))
    if (expanded) canvas.text(listOf(MenuCanvas.Run(title,
        MenuTheme.alpha(if (chosen > .1 || focused) MenuTheme.TEXT else MenuTheme.MUTED), bold = chosen > .1)),
        rect.x + 32, rect.y + (rect.height - MenuCanvas.lineHeight(10.0)) / 2, 10.0)
}

/** Production rail paint is shared with the native and fallback rendering checks. */
internal fun paintMenuNavigationTile(canvas: MenuCanvas, rect: MenuRect, index: Int,
                                     hover: Double = 0.0, chosen: Double = 0.0, focused: Boolean = false) =
    paintNavigationRow(canvas, rect, MenuCategory.entries[index].title, MenuCategory.entries[index].iconId, hover, chosen, focused)

internal fun menuHomeColumns(available: Int) = when { available >= 660 -> 3; available >= 368 -> 2; else -> 1 }
internal fun menuHomeToolColumns(available: Int) = when { available >= 540 -> 3; available >= 390 -> 2; else -> 1 }

/** A complete tile uses the shared surfaces; geometry does not change with hover or theme selection. */
internal fun paintMenuHomeTile(canvas: MenuCanvas, rect: MenuRect, title: List<String>, description: List<String>,
                               icon: Int, hover: Double = 0.0, focused: Boolean = false, tool: Boolean = false) {
    val style = ThemeConfig.preset.palette.style
    val radius = MenuTheme.RADIUS.toDouble()
    canvas.roundedRect(rect.x, rect.y, rect.width, rect.height, radius,
        MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, (hover * style.hoverWeight).toInt()), MenuTheme.OPACITY))
    if (MenuTheme.BORDERS || focused) canvas.roundedBorder(rect.x, rect.y, rect.width, rect.height, radius,
        if (focused) style.focusWidth.toDouble() else style.borderWidth,
        MenuTheme.alpha(if (focused) MenuTheme.ACCENT else MenuTheme.BORDER))
    if (!tool) canvas.fill(rect.x + 10, rect.y + 10, 2.0, (rect.height - 20).coerceAtLeast(1.0), MenuTheme.alpha(MenuTheme.ACCENT, 60))
    val size = if (tool) 14.0 else 18.0
    paintMenuIcon(canvas, icon, rect.x + if (tool) 12 else 22, rect.y + if (tool) (rect.height - size) / 2 else 14.0,
        size, MenuTheme.alpha(if (hover > .1 || focused) MenuTheme.ACCENT else MenuTheme.MUTED))
    val left = rect.x + if (tool) 36 else 50
    val titleSize = if (tool) 10.0 else 12.0
    val top = rect.y + if (tool) (rect.height - title.size * MenuCanvas.lineHeight(titleSize)) / 2 else 12.0
    val oldClip = canvas.clip
    val clip = menuNavigationTextClip(rect, canvas.transform)
    canvas.clip = oldClip?.let { intersectMenuClip(it, clip) } ?: clip
    title.forEachIndexed { index, line -> canvas.text(listOf(MenuCanvas.Run(line, MenuTheme.alpha(MenuTheme.TEXT), bold = true)),
        left, top + index * MenuCanvas.lineHeight(titleSize), titleSize) }
    if (!tool) description.forEachIndexed { index, line -> canvas.text(listOf(MenuCanvas.Run(line, MenuTheme.alpha(MenuTheme.MUTED))),
        left, top + title.size * MenuCanvas.lineHeight(titleSize) + 5 + index * MenuCanvas.lineHeight(9.0), 9.0) }
    canvas.clip = oldClip
}

private fun menuNavigationTextClip(rect: MenuRect, transform: MenuCanvas.Transform): MenuCanvas.Clip {
    // Keep an ink cushion inside the card rather than clipping exactly at a glyph's advance origin.
    val inset = minOf(6.0, rect.width / 2)
    val left = rect.x + inset; val right = rect.x + rect.width - inset
    val top = rect.y; val bottom = rect.y + rect.height
    val x = kotlin.math.floor(minOf(transform.a * left, transform.a * right) +
        minOf(transform.c * top, transform.c * bottom) + transform.x).toInt()
    val y = kotlin.math.floor(minOf(transform.b * left, transform.b * right) +
        minOf(transform.d * top, transform.d * bottom) + transform.y).toInt()
    val endX = kotlin.math.ceil(maxOf(transform.a * left, transform.a * right) +
        maxOf(transform.c * top, transform.c * bottom) + transform.x).toInt()
    val endY = kotlin.math.ceil(maxOf(transform.b * left, transform.b * right) +
        maxOf(transform.d * top, transform.d * bottom) + transform.y).toInt()
    return MenuCanvas.Clip(x, y, (endX - x).coerceAtLeast(0), (endY - y).coerceAtLeast(0))
}

private fun intersectMenuClip(a: MenuCanvas.Clip, b: MenuCanvas.Clip): MenuCanvas.Clip {
    val x = maxOf(a.x, b.x); val y = maxOf(a.y, b.y)
    return MenuCanvas.Clip(x, y, (minOf(a.x + a.width, b.x + b.width) - x).coerceAtLeast(0),
        (minOf(a.y + a.height, b.y + b.height) - y).coerceAtLeast(0))
}

internal fun paintMenuIcon(canvas: MenuCanvas, id: Int, x: Double, y: Double, size: Double, color: Int) {
    val unit = size / 16
    fun line(a: Double, b: Double, c: Double, d: Double) = canvas.line(x + a * unit, y + b * unit, x + c * unit, y + d * unit, 1.35 * unit, color)
    fun ring(cx: Double, cy: Double, r: Double) = canvas.arc(x + cx * unit, y + cy * unit, r * unit, 0.0, 360.0, color, (r - 1.3) * unit)
    when (id) {
        0 -> { line(1.0, 7.0, 8.0, 1.0); line(8.0, 1.0, 15.0, 7.0); line(3.0, 6.0, 3.0, 15.0); line(3.0, 15.0, 13.0, 15.0); line(13.0, 15.0, 13.0, 6.0); line(8.0, 15.0, 8.0, 10.0) }
        1 -> { line(2.0, 3.0, 14.0, 3.0); line(2.0, 3.0, 2.0, 14.0); line(2.0, 14.0, 14.0, 14.0); line(14.0, 14.0, 14.0, 3.0); line(5.0, 1.0, 5.0, 5.0); line(11.0, 1.0, 11.0, 5.0); line(5.0, 8.0, 11.0, 8.0); line(5.0, 11.0, 9.0, 11.0) }
        2, 3 -> { ring(8.0, 4.0, 2.5); ring(3.0, 7.0, 2.0); ring(13.0, 7.0, 2.0); line(5.0, 14.0, 5.0, 10.0); line(5.0, 10.0, 11.0, 10.0); line(11.0, 10.0, 11.0, 14.0); if (id == 2) line(1.0, 15.0, 15.0, 15.0) }
        4 -> { line(2.0, 14.0, 13.0, 3.0); line(13.0, 3.0, 15.0, 1.0); line(2.0, 2.0, 14.0, 14.0); line(1.0, 10.0, 6.0, 15.0); line(10.0, 15.0, 15.0, 10.0) }
        5 -> { line(4.0, 3.0, 9.0, 8.0); line(7.0, 1.0, 12.0, 6.0); line(4.0, 3.0, 7.0, 1.0); line(9.0, 8.0, 12.0, 6.0); line(8.0, 6.0, 2.0, 14.0); line(9.0, 14.0, 15.0, 14.0) }
        6 -> { line(8.0, 1.0, 10.0, 6.0); line(10.0, 6.0, 15.0, 8.0); line(15.0, 8.0, 10.0, 10.0); line(10.0, 10.0, 8.0, 15.0); line(8.0, 15.0, 6.0, 10.0); line(6.0, 10.0, 1.0, 8.0); line(1.0, 8.0, 6.0, 6.0); line(6.0, 6.0, 8.0, 1.0) }
        7 -> { line(1.0, 1.0, 15.0, 1.0); line(1.0, 1.0, 1.0, 15.0); line(1.0, 15.0, 15.0, 15.0); line(15.0, 15.0, 15.0, 1.0); line(6.0, 1.0, 6.0, 15.0); line(6.0, 7.0, 15.0, 7.0) }
        9 -> { ring(8.0, 8.0, 5.0); ring(8.0, 8.0, 1.5); line(8.0, 0.0, 8.0, 4.0); line(8.0, 12.0, 8.0, 16.0); line(0.0, 8.0, 4.0, 8.0); line(12.0, 8.0, 16.0, 8.0) }
        10 -> { ring(6.0, 6.0, 4.5); line(9.5, 9.5, 15.0, 15.0) }
        11 -> { for (index in 0..2) { val row = 3.0 + index * 5; val knob = when (index) { 0 -> 5.0; 1 -> 11.0; else -> 7.0 }; line(1.0, row, 15.0, row); ring(knob, row, 2.0) } }
        else -> { ring(8.0, 8.0, 4.0); ring(8.0, 8.0, 1.8); for (i in 0..7) { val angle = i * Math.PI / 4; line(8 + kotlin.math.cos(angle) * 5, 8 + kotlin.math.sin(angle) * 5, 8 + kotlin.math.cos(angle) * 7, 8 + kotlin.math.sin(angle) * 7) } }
    }
}

internal class MenuNavigation(private val layout: MenuLayout, private val activeSection: () -> Int,
                              private val indicator: MotionValue, private val action: (Int?) -> Unit,
                              search: () -> Unit, quick: () -> Unit,
                              private val revealMode: () -> GuiMotion, private val revealReady: () -> Boolean,
                              private val home: Boolean) :
    FlowLayout(Sizing.fixed(layout.sidebar), Sizing.fixed(layout.height), Algorithm.VERTICAL) {
    private val geometry = MenuNavigationGeometry(layout)
    var revealsSettled = false
        private set
    init {
        allowOverflow(true)
        positioning(Positioning.absolute(0, 0))
        fun utility(title: String, icon: Int, top: Int, selected: () -> Boolean = { false }, run: () -> Unit) =
            object : MenuButton(Component.literal(title), { run() }) {
                override fun interactionPose(hover: Double, press: Double) = MotionPose()
                override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                    paintNavigationRow(graphics.canvas, MenuRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble()),
                        title, icon, hover, chosen, isFocused)
                }
            }.apply {
                sizing(Sizing.fixed((layout.sidebar - 16).coerceAtLeast(1)), Sizing.fixed(24))
                positioning(Positioning.absolute(8, top)); navigation = true; this.selected = selected
                tooltip(Component.literal(title))
            }
        child(utility("Home", 0, 30, { home }) { action(null) })
        menuSectionTitles.forEachIndexed { index, title ->
            val rect = geometry.rect(index)
            child(object : MenuButton(Component.literal(title), { action(index) }) {
                override fun interactionPose(hover: Double, press: Double) = menuNavigationPose(geometry.rect(index), hover, press, ThemeConfig.animationMode)
                override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                    val mode = revealMode()
                    val reveal = menuReveals.progress("navigation/$index", revealReady(), mode, index * mode.profile.staggerMillis)
                    revealsSettled = revealsSettled && reveal >= 1.0
                    graphics.layer(alpha = reveal) {
                        paintMenuNavigationTile(graphics.canvas, geometry.rect(index), index, hover, chosen, isFocused)
                    }
                }
            }.apply {
                sizing(Sizing.fixed(rect.width.toInt()), Sizing.fixed(rect.height.toInt()))
                positioning(Positioning.absolute(rect.x.toInt(), rect.y.toInt())); navigation = true; selected = { activeSection() == index }
                tooltip(Component.literal("$title · ${MenuCategory.entries[index].description}"))
            })
        }
        if (layout.height >= 320) child(utility("Quick settings", 11, layout.height - 64) { quick() })
        child(utility("Search", 10, (layout.height - 34).coerceAtLeast(0)) { search() })
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val graphics = g as MenuGraphics
        geometry.update(x().toDouble(), y().toDouble())
        revealsSettled = true
        MenuTheme.rounded(g, x(), y(), layout.sidebar, height(), MenuTheme.SIDEBAR, 0)
        g.fill(x() + layout.sidebar - 1, y(), x() + layout.sidebar, y() + height(), MenuTheme.alpha(MenuTheme.BORDER))
        graphics.canvas.text(listOf(MenuCanvas.Run(if (layout.sidebar >= 108) "SKY-MYCE" else "SM",
            MenuTheme.alpha(MenuTheme.TEXT), bold = true)), x() + 14.0, y() + 10.0, 10.0)
        val section = activeSection()
        if (section in menuSectionTitles.indices) {
            val selected = menuNavigationRect(layout, section)
            val target = selected.y + selected.height / 2
            if (indicator.target == 0.0) indicator.snap(target)
            indicator.animateTo(target, 180, ThemeConfig.animationMode.profile.hoverEasing, ThemeConfig.animationMode)
            val center = indicator.sample()
            graphics.canvas.fill(x() + 3.0, y() + center - 6, ThemeConfig.preset.palette.style.focusWidth.toDouble(), 12.0,
                MenuTheme.alpha(MenuTheme.ACCENT))
        }
        super.draw(g, mouseX, mouseY, partialTicks, delta)
    }
    fun pointer(px: Double, py: Double): Pair<Double, Double>? =
        if (isInBoundingBox(px, py)) px to py else null
}

internal class MenuHomeScreen : MenuScreen(Component.literal("Sky-Myce")) {
    override val home get() = true
    override val menuCategory get() = null
    override fun buildContent(root: FlowLayout) {
        val padding = MenuTheme.PADDING + 8
        root.padding(Insets.of(padding)); root.gap(10)
        root.child(MenuLabel(Component.literal("Your SkyBlock workspace")).apply {
            fontSize = 19f; color(Color.ofRgb(MenuTheme.TEXT)); horizontalSizing(Sizing.fill())
        })
        root.child(MenuLabel(Component.literal("Choose a category or jump back into your tools.")).apply {
            color(Color.ofRgb(MenuTheme.MUTED)); horizontalSizing(Sizing.fill())
        })
        val available = (menuWidth - padding * 2).coerceAtLeast(1)
        val list = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(10); padding(Insets.of(2))
        }
        val columns = menuHomeColumns(available - 4)
        val tileWidth = ((available - 4 - (columns - 1) * 8) / columns).coerceAtLeast(1)
        MenuCategory.entries.chunked(columns).forEach { categories -> list.child(
            UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(8)
                categories.forEach { category ->
                    val title = navigationTextLines(category.title, (tileWidth - 60).coerceAtLeast(1), 12.0, true, 2)
                    val description = navigationTextLines(category.description, (tileWidth - 60).coerceAtLeast(1), 9.0, false, 2)
                    val height = 29 + title.size * MenuCanvas.lineHeight(12.0) + description.size * MenuCanvas.lineHeight(9.0)
                    child(object : MenuButton(Component.literal(category.title), { openMenuCategory(category) }) {
                        override fun interactionPose(hover: Double, press: Double) = MotionPose()
                        override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) =
                            paintMenuHomeTile(graphics.canvas, MenuRect(x.toDouble(), y.toDouble(), width.toDouble(), this.height.toDouble()),
                                title, description, category.iconId, hover, isFocused)
                    }.apply {
                        sizing(Sizing.fixed(tileWidth), Sizing.fixed(height)); tooltip(Component.literal(category.description))
                    })
                }
            }) }
        list.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6); verticalAlignment(VerticalAlignment.CENTER)
            child(MenuLabel(Component.literal("Your tools")).apply { color(Color.ofRgb(MenuTheme.MUTED)); horizontalSizing(Sizing.expand()) })
            child(MenuButton(Component.literal("Edit")) { navigateMenu(MenuHomeShortcutsScreen()) }.apply {
                sizing(Sizing.fixed(42.coerceAtMost(available)), Sizing.fixed(22)); tooltip(Component.literal("Customize the six Home tools"))
            })
        })
        val toolColumns = menuHomeToolColumns(available - 4)
        val toolWidth = ((available - 4 - (toolColumns - 1) * 8) / toolColumns).coerceAtLeast(1)
        MenuShortcuts.homeTools.indices.chunked(toolColumns).forEach { indices -> list.child(
            UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(8)
                indices.forEach { index ->
                    val feature = menuHomeFeature(index, MenuShortcuts.homeTools)
                    val title = menuNavigationTitle(index, toolWidth - 48, 10.0, 2, feature)
                    child(object : MenuButton(Component.literal(feature.title), { openMenuFeature(feature.id) }) {
                        override fun interactionPose(hover: Double, press: Double) = MotionPose()
                        override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) =
                            paintMenuHomeTile(graphics.canvas, MenuRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble()),
                                title, emptyList(), feature.iconId, hover, isFocused, tool = true)
                    }.apply {
                        sizing(Sizing.fixed(toolWidth), Sizing.fixed(maxOf(32, title.size * MenuCanvas.lineHeight(10.0) + 12)))
                        tooltip(Component.literal(feature.description))
                    })
                }
            }) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), list))
        root.child(MenuButton(Component.literal("Search features and settings")) { navigateMenu(MenuDirectoryScreen()) }.apply {
            sizing(Sizing.fill(), Sizing.fixed(26))
        })
    }
}
