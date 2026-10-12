package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.misc.ThemeConfig
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
private val menuSectionPreviews = MenuCategory.entries.map { category ->
    MenuCatalog.definitions.filter { it.category == category }.map { it.subcategory }.distinct().take(3).joinToString(" · ")
}
private val descriptionLines = arrayOfNulls<Pair<Int, List<String>>>(MenuCategory.entries.size)

/** Card descriptions use measured text advances and a bounded second line, including during the home-to-sidebar morph. */
internal fun menuNavigationDescription(index: Int, width: Int): List<String> {
    val available = width.coerceAtLeast(1)
    descriptionLines[index]?.takeIf { it.first == available }?.let { return it.second }
    val wrapped = menuTextLines(Component.literal(MenuCategory.entries[index].description), available, 9.0).map { line ->
        buildString { line.accept { _, _, codepoint -> appendCodePoint(codepoint); true } }
    }
    val lines = wrapped.take(2).toMutableList()
    if (wrapped.size > 2 && lines.isNotEmpty()) {
        var last = lines.last().trimEnd()
        while (last.isNotEmpty() && MenuCanvas.textWidth("$last…", size = 9.0) > available)
            last = last.dropLast(Character.charCount(last.codePointBefore(last.length))).trimEnd()
        lines[lines.lastIndex] = if (MenuCanvas.textWidth("$last…", size = 9.0) <= available) "$last…" else ""
    }
    return lines.also { descriptionLines[index] = available to it }
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
    val h = ((layout.height - top - minOf(56.0, layout.height * .2) - gap * (rows - 1)) / rows).coerceAtLeast(1.0)
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
                                     homeRect: MenuRect = rect) {
    val profile = ThemeConfig.animationMode.profile
    val expansion = 1 - compact.coerceIn(0.0, 1.0)
    fun rounded(r: MenuRect, color: Int, radius: Double = 4.0) {
        val corner = if (MenuTheme.ROUNDED) minOf(radius, r.width / 2, r.height / 2) else 0.0
        canvas.fill(r.x + corner, r.y, r.width - corner * 2, r.height, color)
        canvas.fill(r.x, r.y + corner, corner, r.height - corner * 2, color)
        canvas.fill(r.x + r.width - corner, r.y + corner, corner, r.height - corner * 2, color)
        if (corner > 0) {
            canvas.arc(r.x + corner, r.y + corner, corner, 0.0, 90.0, color)
            canvas.arc(r.x + r.width - corner, r.y + corner, corner, 90.0, 180.0, color)
            canvas.arc(r.x + r.width - corner, r.y + r.height - corner, corner, 180.0, 270.0, color)
            canvas.arc(r.x + corner, r.y + r.height - corner, corner, 270.0, 360.0, color)
        }
    }
    if (MenuTheme.SHADOW) for (layer in ThemeConfig.graphicsQuality.shadowLayers downTo 1) {
        val depth = 1 + hover * profile.cardLift * expansion
        rounded(MenuRect(rect.x - layer, rect.y + depth, rect.width + layer * 2, rect.height + layer),
            MenuTheme.alpha(0x06040C, ((3 + hover * 6) * profile.shadowStrength).toInt()))
    }
    if (ThemeConfig.graphicsQuality.glow && hover + chosen > 0) rounded(
        MenuRect(rect.x - 1, rect.y - 1, rect.width + 2, rect.height + 2),
        MenuTheme.alpha(MenuTheme.ACCENT, ((hover + chosen) * profile.glowStrength * 8).toInt()))
    val border = MenuTheme.blend(MenuTheme.BORDER, if (chosen > 0 || focused) MenuTheme.ACCENT else MenuTheme.TEXT,
        if (focused) 100 else (hover * 22 + chosen * 78).toInt())
    if (MenuTheme.BORDERS || focused || chosen > 0) rounded(rect, MenuTheme.alpha(border))
    rounded(MenuRect(rect.x + 1, rect.y + 1, (rect.width - 2).coerceAtLeast(1.0), (rect.height - 2).coerceAtLeast(1.0)),
        MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, (chosen * 12 + hover * 5).toInt()), MenuTheme.OPACITY))
    // Typography keeps its home arrangement until it fades, rather than reflowing mid-morph.
    val roomy = homeRect.height > 65 && homeRect.width > 135
    val iconSize = (if (roomy) 20.0 else 15.0) * expansion + 14 * compact
    val iconX = rect.x + 15 * expansion + (rect.width - iconSize) / 2 * compact
    val iconY = rect.y + if (roomy) 17 * expansion + (rect.height - iconSize) / 2 * compact else (rect.height - iconSize) / 2
    paintMenuIcon(canvas, MenuCategory.entries[index].iconId, iconX, iconY, iconSize, MenuTheme.alpha(if (chosen > 0 || hover > .2) MenuTheme.ACCENT else MenuTheme.MUTED))
    val textAlpha = (1 - compact / .42).coerceIn(0.0, 1.0)
    if (textAlpha > 0) {
        val opacity = canvas.opacity
        canvas.opacity *= textAlpha
        val left = rect.x + if (roomy) 15.0 else 39.0
        val titleTop = if (roomy) rect.y + 45 else rect.y + (rect.height - MenuCanvas.lineHeight(11.0)) / 2
        val oldClip = canvas.clip
        val textClip = MenuCanvas.Clip(left.toInt(), rect.y.toInt(), (rect.x + rect.width - left - 10).toInt().coerceAtLeast(0), rect.height.toInt())
        canvas.clip = oldClip?.let { intersectMenuClip(it, textClip) } ?: textClip
        canvas.text(listOf(MenuCanvas.Run(menuSectionTitles[index], MenuTheme.alpha(MenuTheme.TEXT), bold = true)), left, titleTop, if (roomy) 13.0 else 11.0)
        if (roomy && homeRect.height > 95) menuNavigationDescription(index, (homeRect.width - 25).toInt()).forEachIndexed { line, text ->
            canvas.text(listOf(MenuCanvas.Run(text, MenuTheme.alpha(MenuTheme.MUTED))), left, titleTop + 22 + line * MenuCanvas.lineHeight(9.0), 9.0)
        }
        if (roomy && homeRect.height > 140) canvas.text(listOf(MenuCanvas.Run(menuSectionPreviews[index], MenuTheme.alpha(MenuTheme.ACCENT))),
            left, rect.y + rect.height - 26, 9.0)
        canvas.clip = oldClip; canvas.opacity = opacity
    }
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
                              private val revealMode: () -> GuiMotion, private val revealReady: () -> Boolean) :
    FlowLayout(Sizing.fixed(layout.sidebar), Sizing.fixed(layout.height), Algorithm.VERTICAL) {
    private val buttons = ArrayList<MenuButton>()
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
            child(object : MenuButton(Component.literal(title), { action(index) }) {
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
                            geometry.home(index))
                    }
                }
                override fun shouldDrawTooltip(mouseX: Double, mouseY: Double) = progress() > .95 && super.shouldDrawTooltip(mouseX, mouseY)
            }.apply {
                sizing(Sizing.fixed(mini.width.toInt()), Sizing.fixed(mini.height.toInt()))
                positioning(Positioning.absolute(mini.x.toInt(), mini.y.toInt())); navigation = true; selected = { activeSection() == index }
                tooltip(Component.literal(title)); buttons += this
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
            if (layout.height > 300) graphics.canvas.text(listOf(MenuCanvas.Run("Choose a category, or search any setting.", MenuTheme.alpha(MenuTheme.MUTED))), x() + 22.0, y() + 43.0, 10.0)
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
        root.child(io.wispforest.owo.ui.container.UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); horizontalAlignment(HorizontalAlignment.CENTER)
            positioning(Positioning.absolute(0, menuHeight - 38))
            listOf("Search" to MenuDirectoryView.SEARCH, "Quick Settings" to MenuDirectoryView.QUICK).forEach { (title, view) ->
                child(MenuButton(Component.literal(title)) { navigateMenu(MenuDirectoryScreen(view = view, parent = this@MenuHomeScreen)) }
                    .apply { sizing(Sizing.fixed(if (view == MenuDirectoryView.QUICK) 108 else 64), Sizing.fixed(24)) })
            }
        })
    }
}
