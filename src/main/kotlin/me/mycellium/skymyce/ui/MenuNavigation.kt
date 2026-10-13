package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import kotlin.math.ceil

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
private fun homeActionWidths(layout: MenuLayout) = if (layout.contentWidth - 24 >= 312) listOf(64, 108, 124) else listOf(64, 124)
internal fun menuHomeFooterRows(layout: MenuLayout) = menuActionRows(homeActionWidths(layout), (layout.contentWidth - 24).coerceAtLeast(1), 8)
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

/** Card descriptions use measured text advances and a bounded second line, including during the home-to-sidebar morph. */
internal fun menuNavigationDescription(index: Int, width: Int, feature: MenuFeature = menuHomeFeature(index)): List<String> {
    val available = width.coerceAtLeast(1)
    val nativeMetrics = SkijaMenuFonts.active
    descriptionLines[index]?.takeIf { it.id == feature.id && it.width == available && it.nativeMetrics == nativeMetrics }?.let { return it.lines }
    return navigationTextLines(feature.description, available, 9.0, false, 2).also {
        descriptionLines[index] = NavigationDescription(feature.id, available, nativeMetrics, it)
    }
}

/** The same surfaces travel between these two arrangements; content never changes their geometry. */
internal fun menuNavigationRect(layout: MenuLayout, index: Int, progress: Double): MenuRect {
    val count = menuSectionTitles.size
    require(index in 0 until count)
    val columns = when { layout.width >= 600 -> 3; layout.width >= 340 -> 2; else -> 1 }
    val rows = ceil(count.toDouble() / columns).toInt()
    val margin = minOf(22.0, layout.width / 8.0)
    val gap = minOf(12.0, layout.width / 20.0, layout.height / 40.0)
    val top = minOf(68.0, layout.height * .2)
    val w = ((layout.width - margin * 2 - gap * (columns - 1)) / columns).coerceAtLeast(1.0)
    val footer = maxOf(56.0, menuHomeFooterRows(layout).size * 32.0 + 16).coerceAtMost(layout.height * .38)
    val h = ((layout.height - top - footer - gap * (rows - 1)) / rows).coerceAtLeast(1.0)
    val card = MenuRect(margin + index % columns * (w + gap), top + index / columns * (h + gap), w, h)
    val step = ((layout.height - 140.0) / count).coerceIn(1.0, 40.0)
    val mini = MenuRect(8.0.coerceAtMost(layout.sidebar / 5.0), 64 + index * step,
        (layout.sidebar - 16.0).coerceAtLeast(1.0), (step - 4).coerceAtLeast(1.0))
    return card.mix(mini, progress)
}

/** Only the navigation layout moves interaction bounds; hover, press and reveal are paint-only. */
internal class MenuNavigationGeometry(layout: MenuLayout) {
    private val home = Array(menuSectionTitles.size) { menuNavigationRect(layout, it, 0.0) }
    private val mini = Array(menuSectionTitles.size) { menuNavigationRect(layout, it, 1.0) }
    private var rects = home
    private var progress = 0.0
    private var originX = 0.0
    private var originY = 0.0
    var revision = 0L
        private set

    fun update(progress: Double, x: Double = 0.0, y: Double = 0.0) {
        val p = progress.coerceIn(0.0, 1.0)
        if (this.progress == p && originX == x && originY == y) return
        this.progress = p; originX = x; originY = y
        val local = when (p) { 0.0 -> home; 1.0 -> mini; else -> Array(home.size) { home[it].mix(mini[it], p) } }
        rects = if (x == 0.0 && y == 0.0) local else Array(local.size) { local[it].copy(x = x + local[it].x, y = y + local[it].y) }
        revision++
    }
    fun rect(index: Int) = rects[index]
    fun home(index: Int) = home[index]
    fun mini(index: Int) = mini[index]
    fun indexAt(x: Double, y: Double): Int? {
        for (index in rects.indices.reversed()) if (rects[index].contains(x, y)) return index
        return null
    }
}

internal fun menuNavigationPose(rect: MenuRect, compact: Double, hover: Double, press: Double, mode: GuiMotion): MotionPose {
    val profile = mode.profile
    return MotionPose(scale = 1 + profile.hoverScale * hover - profile.pressScale * press,
        y = -profile.cardLift * hover * (1 - compact) + press * profile.cardLift * .3,
        pivotX = rect.x + rect.width / 2, pivotY = rect.y + rect.height / 2)
}

/** Shared production paint is also rendered by the headless navigation checks. */
internal fun paintMenuNavigationTile(canvas: MenuCanvas, rect: MenuRect, index: Int, compact: Double,
                                     hover: Double = 0.0, chosen: Double = 0.0, focused: Boolean = false,
                                     homeRect: MenuRect = rect, homeFeature: MenuFeature = menuHomeFeature(index)) {
    val profile = ThemeConfig.animationMode.profile
    val expansion = 1 - compact.coerceIn(0.0, 1.0)
    fun rounded(r: MenuRect, color: Int, radius: Double = 10.0 - compact * 4) {
        val corner = if (MenuTheme.ROUNDED) minOf(radius, r.width / 2, r.height / 2) else 0.0
        canvas.roundedRect(r.x, r.y, r.width, r.height, corner, color)
    }
    val radius = if (MenuTheme.ROUNDED) 10.0 - compact * 4 else 0.0
    if (MenuTheme.SHADOW) {
        val qualityLayers = ThemeConfig.graphicsQuality.shadowLayers
        val depth = 1 + hover * profile.cardLift * expansion
        if (SkijaMenuFonts.active && qualityLayers >= 2 && profile.glowStrength > 0.0) {
            canvas.shadow(rect.x, rect.y + depth, rect.width, rect.height, radius, if (profile.ambient) 4.0 else 3.0,
                MenuTheme.alpha(0x06040C, ((14 + hover * 12) * profile.shadowStrength).toInt()))
        } else for (layer in (if (profile.glowStrength > 0.0) qualityLayers else minOf(qualityLayers, 1)) downTo 1) {
            rounded(MenuRect(rect.x - layer, rect.y + depth, rect.width + layer * 2, rect.height + layer),
                MenuTheme.alpha(0x06040C, ((3 + hover * 6) * profile.shadowStrength).toInt()))
        }
    }
    if (ThemeConfig.graphicsQuality.glow && profile.glowStrength > 0.0 && hover + chosen > 0) {
        if (SkijaMenuFonts.active) canvas.shadow(rect.x, rect.y, rect.width, rect.height, radius, if (profile.ambient) 5.0 else 4.0,
            MenuTheme.alpha(MenuTheme.ACCENT, ((hover + chosen) * profile.glowStrength * 12).toInt()))
        else rounded(MenuRect(rect.x - 1, rect.y - 1, rect.width + 2, rect.height + 2),
            MenuTheme.alpha(MenuTheme.ACCENT, ((hover + chosen) * profile.glowStrength * 8).toInt()))
    }
    val border = MenuTheme.blend(MenuTheme.BORDER, if (chosen > 0 || focused) MenuTheme.ACCENT else MenuTheme.TEXT,
        if (focused) 100 else (hover * 22 + chosen * 78).toInt())
    rounded(rect, MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, (chosen * 12 + hover * 5).toInt()), MenuTheme.OPACITY))
    if (MenuTheme.BORDERS || focused || chosen > 0) canvas.roundedBorder(rect.x, rect.y, rect.width, rect.height,
        if (MenuTheme.ROUNDED) 10.0 - compact * 4 else 0.0, 1.0, MenuTheme.alpha(border))
    if (expansion > .7 && ThemeConfig.graphicsQuality.glow && profile.glowStrength > 0) {
        canvas.gradient(rect.x + 10, rect.y + 2, (rect.width - 20).coerceAtLeast(1.0), minOf(50.0, rect.height - 4),
            MenuTheme.alpha(MenuTheme.ACCENT, ((3 + hover * 4) * profile.glowStrength).toInt()),
            MenuTheme.alpha(MenuTheme.SECONDARY, ((2 + hover * 3) * profile.glowStrength).toInt()), 0, 0)
    }
    // Typography keeps its home arrangement until it fades, rather than reflowing mid-morph.
    val roomy = homeRect.height > 110 && homeRect.width > 180
    val iconSize = (if (roomy) 20.0 else 15.0) * expansion + 14 * compact
    val iconX = rect.x + 15 * expansion + (rect.width - iconSize) / 2 * compact
    val iconY = rect.y + if (roomy) 20 * expansion + (rect.height - iconSize) / 2 * compact else (rect.height - iconSize) / 2
    if (roomy && expansion > 0) rounded(MenuRect(iconX - 6 * expansion, iconY - 6 * expansion,
        iconSize + 12 * expansion, iconSize + 12 * expansion),
        MenuTheme.alpha(MenuTheme.ACCENT, ((7 + hover * 5) * expansion).toInt()), 8.0)
    val iconColor = MenuTheme.alpha(if (chosen > 0 || hover > .2) MenuTheme.ACCENT else MenuTheme.MUTED)
    val categoryIcon = MenuCategory.entries[index].iconId
    if (homeFeature.iconId == categoryIcon) paintMenuIcon(canvas, categoryIcon, iconX, iconY, iconSize, iconColor)
    else {
        val opacity = canvas.opacity
        if (expansion > 0) {
            canvas.opacity = opacity * expansion
            paintMenuIcon(canvas, homeFeature.iconId, iconX, iconY, iconSize, iconColor)
        }
        if (compact > 0) {
            canvas.opacity = opacity * compact
            paintMenuIcon(canvas, categoryIcon, iconX, iconY, iconSize, iconColor)
        }
        canvas.opacity = opacity
    }
    val textAlpha = (1 - compact / .42).coerceIn(0.0, 1.0)
    if (textAlpha > 0) {
        val opacity = canvas.opacity
        canvas.opacity *= textAlpha
        val left = rect.x + if (roomy) 15.0 else 39.0
        val titleSize = if (roomy) 15.0 else 11.0
        val titleWidth = (homeRect.width - if (roomy) 25 else 49).toInt()
        val title = menuNavigationTitle(index, titleWidth, titleSize, if (homeRect.height >= 35) 2 else 1, homeFeature)
        val titleHeight = title.size * MenuCanvas.lineHeight(titleSize)
        val titleTop = if (roomy) rect.y + 55 else rect.y + (rect.height - titleHeight) / 2
        val oldClip = canvas.clip
        // Clips are global screen coordinates. Hover/shell transforms move the glyphs, including their leading ink.
        val textClip = menuNavigationTextClip(rect, canvas.transform)
        canvas.clip = oldClip?.let { intersectMenuClip(it, textClip) } ?: textClip
        title.forEachIndexed { line, text -> canvas.text(listOf(MenuCanvas.Run(text, MenuTheme.alpha(MenuTheme.TEXT), bold = true)),
            left, titleTop + line * MenuCanvas.lineHeight(titleSize), titleSize) }
        if (roomy) {
            val descriptionTop = titleTop + titleHeight + 8
            val remaining = homeRect.height - (descriptionTop - rect.y) - if (homeRect.height > 140) 36 else 10
            val lines = (remaining / MenuCanvas.lineHeight(9.0)).toInt().coerceIn(0, 2)
            val description = menuNavigationDescription(index, (homeRect.width - 25).toInt(), homeFeature)
            for (line in 0 until minOf(lines, description.size)) {
                canvas.text(listOf(MenuCanvas.Run(description[line], MenuTheme.alpha(MenuTheme.MUTED))), left,
                    descriptionTop + line * MenuCanvas.lineHeight(9.0), 9.0)
            }
        }
        if (roomy && homeRect.height > 140) canvas.text(listOf(MenuCanvas.Run("${homeFeature.category.title} · Open tool", MenuTheme.alpha(MenuTheme.ACCENT))),
            left, rect.y + rect.height - 26, 9.0)
        canvas.clip = oldClip; canvas.opacity = opacity
    }
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

internal class MenuNavigation(private val layout: MenuLayout, private val progress: () -> Double,
                              private val activeSection: () -> Int, private val indicator: MotionValue,
                              private val action: (Int?) -> Unit, search: () -> Unit, quick: () -> Unit,
                              private val revealMode: () -> GuiMotion, private val revealReady: () -> Boolean,
                              private val home: Boolean, homeToolIds: List<String>) :
    FlowLayout(Sizing.fixed(layout.sidebar), Sizing.fixed(layout.height), Algorithm.VERTICAL) {
    private val buttons = ArrayList<MenuButton>()
    private val homeFeatures = homeToolIds.indices.map { menuHomeFeature(it, homeToolIds) }
    private val geometry = MenuNavigationGeometry(layout)
    private var hasPainted = false
    var revealsSettled = false
        private set
    init {
        allowOverflow(true)
        positioning(Positioning.absolute(0, 0))
        child(object : MenuButton(Component.literal("Home"), { action(null) }) {
            override fun canFocus(source: UIComponent.FocusSource) = progress() > .95 && super.canFocus(source)
            override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                graphics.layer(alpha = progress()) {
                    MenuTheme.box(graphics, x, y, width, height, MenuTheme.CARD, if (hover > 0 || isFocused) MenuTheme.ACCENT else MenuTheme.BORDER)
                    paintMenuIcon(graphics.canvas, 0, x + (width - 14) / 2.0, y + (height - 14) / 2.0, 14.0, MenuTheme.alpha(MenuTheme.TEXT))
                }
            }
        }.apply { sizing(Sizing.fixed((layout.sidebar - 16).coerceAtLeast(1)), Sizing.fixed(24)); positioning(Positioning.absolute(8, 30)); navigation = true; tooltip(Component.literal("Home")) })
        val searchButton = object : MenuButton(Component.literal("Search"), { search() }) {
            override fun canFocus(source: UIComponent.FocusSource) = progress() > .95 && super.canFocus(source)
            override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                graphics.layer(alpha = progress()) {
                    MenuTheme.box(graphics, x, y, width, height, MenuTheme.CARD, if (hover > 0 || isFocused) MenuTheme.ACCENT else MenuTheme.BORDER)
                    paintMenuIcon(graphics.canvas, 10, x + (width - 14) / 2.0, y + (height - 14) / 2.0, 14.0, MenuTheme.alpha(MenuTheme.TEXT))
                }
            }
        }.apply { sizing(Sizing.fixed((layout.sidebar - 16).coerceAtLeast(1)), Sizing.fixed(24)); positioning(Positioning.absolute(8, (layout.height - 34).coerceAtLeast(0))); navigation = true; tooltip(Component.literal("Search features and settings")) }
        val quickButton = object : MenuButton(Component.literal("Quick Settings"), { quick() }) {
            override fun canFocus(source: UIComponent.FocusSource) = progress() > .95 && super.canFocus(source)
            override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                graphics.layer(alpha = progress()) {
                    MenuTheme.box(graphics, x, y, width, height, MenuTheme.CARD, if (hover > 0 || isFocused) MenuTheme.ACCENT else MenuTheme.BORDER)
                    paintMenuIcon(graphics.canvas, 11, x + (width - 14) / 2.0, y + (height - 14) / 2.0, 14.0, MenuTheme.alpha(MenuTheme.TEXT))
                }
            }
        }.apply { sizing(Sizing.fixed((layout.sidebar - 16).coerceAtLeast(1)), Sizing.fixed(24)); positioning(Positioning.absolute(8, (layout.height - 64).coerceAtLeast(0))); navigation = true; tooltip(Component.literal("Quick Settings · pinned controls")) }
        menuSectionTitles.forEachIndexed { index, title ->
            val mini = geometry.mini(index)
            val feature = homeFeatures[index]
            child(object : MenuButton(Component.literal(if (home) feature.title else title), { action(index) }) {
                private fun rect() = geometry.rect(index)
                override fun interactionPose(hover: Double, press: Double) = menuNavigationPose(rect(), progress(), hover, press, ThemeConfig.animationMode)
                override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                    val r = rect()
                    val mode = revealMode()
                    val reveal = menuReveals.progress("navigation/$index", revealReady(), mode,
                        index * mode.profile.staggerMillis)
                    revealsSettled = revealsSettled && reveal >= 1.0
                    graphics.layer(alpha = reveal, y = (1 - reveal) * ThemeConfig.animationMode.profile.revealOffset) {
                        paintMenuNavigationTile(graphics.canvas, r, index, progress(), hover, chosen, isFocused,
                            geometry.home(index), feature)
                    }
                }
                override fun shouldDrawTooltip(mouseX: Double, mouseY: Double) = progress() > .95 && super.shouldDrawTooltip(mouseX, mouseY)
            }.apply {
                sizing(Sizing.fixed(mini.width.toInt()), Sizing.fixed(mini.height.toInt()))
                positioning(Positioning.absolute(mini.x.toInt(), mini.y.toInt())); navigation = true; selected = { activeSection() == index }
                tooltip(Component.literal(if (home) "Open ${feature.title}" else title)); buttons += this
            })
        }
        child(quickButton); child(searchButton)
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val graphics = g as MenuGraphics; val p = progress()
        geometry.update(p, x().toDouble(), y().toDouble())
        hasPainted = true
        revealsSettled = true
        graphics.layer(alpha = p) {
            MenuTheme.rounded(g, x(), y(), layout.sidebar, height(), MenuTheme.SIDEBAR, 0)
            g.fill(x() + layout.sidebar - 1, y(), x() + layout.sidebar, y() + height(), MenuTheme.alpha(MenuTheme.BORDER))
            graphics.canvas.text(listOf(MenuCanvas.Run("SM", MenuTheme.alpha(MenuTheme.ACCENT), bold = true)), x() + 14.0, y() + 10.0, 10.0)
            val section = activeSection()
            if (section in menuSectionTitles.indices) {
                val selected = geometry.mini(section)
                val target = selected.y + selected.height / 2
                if (indicator.target == 0.0) indicator.snap(target)
                indicator.animateTo(target, 180, ThemeConfig.animationMode.profile.hoverEasing, ThemeConfig.animationMode)
                val center = indicator.sample()
                graphics.canvas.fill(x() + 2.0, y() + center - 6, 2.0, 12.0, MenuTheme.alpha(MenuTheme.ACCENT))
            }
        }
        graphics.layer(alpha = (1 - p * 3).coerceIn(0.0, 1.0)) {
            graphics.canvas.text(listOf(MenuCanvas.Run("SKY-MYCE", MenuTheme.alpha(MenuTheme.TEXT), bold = true)), x() + 22.0, y() + 17.0, 18.0)
            if (layout.height > 300) graphics.canvas.text(listOf(MenuCanvas.Run("Choose a tool, or search any setting.", MenuTheme.alpha(MenuTheme.MUTED))), x() + 22.0, y() + 43.0, 10.0)
        }
        super.draw(g, mouseX, mouseY, partialTicks, delta)
    }
    /** Hover, click and tooltip dispatch share the same logical card, independent of its feedback animation. */
    fun pointer(px: Double, py: Double): Pair<Double, Double>? {
        if (!hasPainted) geometry.update(progress(), x().toDouble(), y().toDouble())
        geometry.indexAt(px, py)?.let { index ->
            val button = buttons[index]
            return button.x + button.width / 2.0 to button.y + button.height / 2.0
        }
        if (progress() < .95 && px < x() + layout.sidebar && py in y().toDouble()..(y() + height()).toDouble()) return -1.0 to -1.0
        return null
    }
}

internal class MenuHomeScreen : MenuScreen(Component.literal("Sky-Myce")) {
    override val home get() = true
    override val menuCategory get() = null
    override fun buildContent(root: FlowLayout) {
        val widths = homeActionWidths(menuLayout)
        val actions = if (widths.size == 3) listOf(
            MenuButton(Component.literal("Search")) { navigateMenu(MenuDirectoryScreen()) },
            MenuButton(Component.literal("Quick Settings")) { navigateMenu(MenuDirectoryScreen(view = MenuDirectoryView.QUICK)) },
            MenuButton(Component.literal("Customize Home")) { navigateMenu(MenuHomeShortcutsScreen()) },
        ) else listOf(
            MenuButton(Component.literal("Search")) { navigateMenu(MenuDirectoryScreen()) },
            MenuButton(Component.literal("Customize Home")) { navigateMenu(MenuHomeShortcutsScreen()) },
        )
        val rows = menuHomeFooterRows(menuLayout)
        root.child(io.wispforest.owo.ui.container.UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); positioning(Positioning.absolute(0, menuHeight - rows.size * 32 - 8))
            rows.forEach { indices -> child(io.wispforest.owo.ui.container.UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(8); horizontalAlignment(HorizontalAlignment.CENTER)
                indices.forEach { index -> child(actions[index].apply {
                    sizing(Sizing.fixed(widths[index].coerceAtMost((menuWidth - 24).coerceAtLeast(1))), Sizing.fixed(24))
                }) }
            }) }
        })
    }
}
