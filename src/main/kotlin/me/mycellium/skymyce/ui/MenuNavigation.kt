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
    val step = ((layout.height - 110.0) / count).coerceIn(1.0, 40.0)
    val mini = MenuRect(8.0.coerceAtMost(layout.sidebar / 5.0), 64 + index * step,
        (layout.sidebar - 16.0).coerceAtLeast(1.0), (step - 4).coerceAtLeast(1.0))
    return card.mix(mini, progress)
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
        if (roomy && homeRect.height > 95) canvas.text(listOf(MenuCanvas.Run(MenuCategory.entries[index].description, MenuTheme.alpha(MenuTheme.MUTED))), left, titleTop + 22, 9.0)
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
        else -> { ring(8.0, 8.0, 4.0); ring(8.0, 8.0, 1.8); for (i in 0..7) { val angle = i * Math.PI / 4; line(8 + kotlin.math.cos(angle) * 5, 8 + kotlin.math.sin(angle) * 5, 8 + kotlin.math.cos(angle) * 7, 8 + kotlin.math.sin(angle) * 7) } }
    }
}

internal class MenuNavigation(private val layout: MenuLayout, private val progress: () -> Double,
                              private val activeSection: Int, private val indicator: MotionValue,
                              private val action: (Int?) -> Unit, search: () -> Unit) :
    FlowLayout(Sizing.fixed(layout.sidebar), Sizing.fixed(layout.height), Algorithm.VERTICAL) {
    private val buttons = ArrayList<MenuButton>()
    private val paintedRects = arrayOfNulls<MenuRect>(menuSectionTitles.size)
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
        child(object : MenuButton(Component.literal("Search"), { search() }) {
            override fun canFocus(source: UIComponent.FocusSource) = progress() > .95 && super.canFocus(source)
            override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                graphics.layer(alpha = progress()) {
                    MenuTheme.box(graphics, x, y, width, height, MenuTheme.CARD, if (hover > 0 || isFocused) MenuTheme.ACCENT else MenuTheme.BORDER)
                    paintMenuIcon(graphics.canvas, 10, x + (width - 14) / 2.0, y + (height - 14) / 2.0, 14.0, MenuTheme.alpha(MenuTheme.TEXT))
                }
            }
        }.apply { sizing(Sizing.fixed((layout.sidebar - 16).coerceAtLeast(1)), Sizing.fixed(24)); positioning(Positioning.absolute(8, (layout.height - 34).coerceAtLeast(0))); navigation = true; tooltip(Component.literal("Search, Quick Settings, favorites and recent features")) })
        menuSectionTitles.forEachIndexed { index, title ->
            val mini = menuNavigationRect(layout, index, 1.0)
            child(object : MenuButton(Component.literal(title), { action(index) }) {
                private fun rect() = menuNavigationRect(layout, index, progress()).let { it.copy(x = this@MenuNavigation.x() + it.x, y = this@MenuNavigation.y() + it.y) }
                override fun interactionPose(hover: Double, press: Double): MotionPose {
                    val r = rect(); val profile = ThemeConfig.animationMode.profile
                    return MotionPose(scale = 1 + profile.hoverScale * hover - profile.pressScale * press,
                        y = -profile.cardLift * hover * (1 - progress()) + press * profile.cardLift * .3,
                        pivotX = r.x + r.width / 2, pivotY = r.y + r.height / 2)
                }
                override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                    val r = rect()
                    val reveal = menuReveals.progress("navigation/$index", true, ThemeConfig.animationMode,
                        index * ThemeConfig.animationMode.profile.staggerMillis)
                    revealsSettled = revealsSettled && reveal >= 1.0
                    paintedRects[index] = r.copy(y = r.y + (1 - reveal) * ThemeConfig.animationMode.profile.revealOffset)
                        .transformed(paintedInteraction)
                    graphics.layer(alpha = reveal, y = (1 - reveal) * ThemeConfig.animationMode.profile.revealOffset) {
                        paintMenuNavigationTile(graphics.canvas, r, index, progress(), hover, chosen, isFocused,
                            menuNavigationRect(layout, index, 0.0))
                    }
                }
                override fun shouldDrawTooltip(mouseX: Double, mouseY: Double) = progress() > .95 && super.shouldDrawTooltip(mouseX, mouseY)
            }.apply {
                sizing(Sizing.fixed(mini.width.toInt()), Sizing.fixed(mini.height.toInt()))
                positioning(Positioning.absolute(mini.x.toInt(), mini.y.toInt())); navigation = true; selected = { activeSection == index }
                tooltip(Component.literal(title)); buttons += this
            })
        }
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val graphics = g as MenuGraphics; val p = progress()
        revealsSettled = true
        graphics.layer(alpha = p) {
            MenuTheme.rounded(g, x(), y(), layout.sidebar, height(), MenuTheme.SIDEBAR, 0)
            g.fill(x() + layout.sidebar - 1, y(), x() + layout.sidebar, y() + height(), MenuTheme.alpha(MenuTheme.BORDER))
            graphics.canvas.text(listOf(MenuCanvas.Run("SM", MenuTheme.alpha(MenuTheme.ACCENT), bold = true)), x() + 14.0, y() + 10.0, 10.0)
            if (activeSection in menuSectionTitles.indices) {
                val selected = menuNavigationRect(layout, activeSection, 1.0)
                val target = selected.y + selected.height / 2
                if (indicator.target == 0.0) indicator.snap(target)
                indicator.animateTo(target, 180, ThemeConfig.animationMode.profile.hoverEasing, ThemeConfig.animationMode)
                val center = indicator.sample()
                graphics.canvas.fill(x() + 2.0, y() + center - 6, 2.0, 12.0, MenuTheme.alpha(MenuTheme.ACCENT))
            }
        }
        graphics.layer(alpha = (1 - p * 3).coerceIn(0.0, 1.0)) {
            graphics.canvas.text(listOf(MenuCanvas.Run("SKY-MYCE", MenuTheme.alpha(MenuTheme.TEXT), bold = true)), x() + 22.0, y() + 17.0, 18.0)
            if (layout.height > 300) graphics.canvas.text(listOf(MenuCanvas.Run("Your tools. Your team. Your next adventure.", MenuTheme.alpha(MenuTheme.MUTED))), x() + 22.0, y() + 43.0, 10.0)
        }
        super.draw(g, mouseX, mouseY, partialTicks, delta)
    }
    /** Translate painted card positions to the retained mini buttons for native focus/input dispatch. */
    fun pointer(px: Double, py: Double): Pair<Double, Double>? {
        for (index in buttons.indices.reversed()) {
            val button = buttons[index]
            val r = paintedRects[index] ?: menuNavigationRect(layout, index, progress()).let { it.copy(x = x() + it.x, y = y() + it.y) }
            if (r.contains(px, py)) return button.x + button.width / 2.0 to button.y + button.height / 2.0
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
