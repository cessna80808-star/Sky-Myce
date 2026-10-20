package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.SliderComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import io.wispforest.owo.ui.inject.GreedyInputUIComponent
import io.wispforest.owo.ui.util.FocusHandler
import io.wispforest.owo.ui.util.UISounds
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import java.util.function.Consumer

/** Menu density is independent of Minecraft's GUI scale. */
data class MenuViewport(val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int) {
    fun x(value: Double) = value * width / screenWidth.coerceAtLeast(1)
    fun y(value: Double) = value * height / screenHeight.coerceAtLeast(1)
    fun mouse(event: MouseButtonEvent) = MouseButtonEvent(x(event.x()), y(event.y()), event.buttonInfo())
    companion object {
        fun fit(pixelsWidth: Int, pixelsHeight: Int, guiWidth: Int, guiHeight: Int): MenuViewport {
            val scale = (pixelsHeight / 540.0).coerceIn(1.0, 2.0)
            return MenuViewport((pixelsWidth / scale).toInt().coerceAtLeast(1), (pixelsHeight / scale).toInt().coerceAtLeast(1), guiWidth, guiHeight)
        }
    }
}
/** Logical GUI pixels; the game owns the window, owo owns layout/input, our canvas owns paint. */
data class MenuLayout(val width: Int, val height: Int, val sidebar: Int) {
    val contentWidth get() = width - sidebar
    companion object {
        fun fit(width: Int, height: Int): MenuLayout {
            val w = minOf(width - 16, (width * .86).toInt()).coerceIn(1, 1100)
            val h = minOf(height - 16, maxOf(260, (height * .86).toInt())).coerceIn(1, 650)
            val rail = when { w >= 700 -> 144; w >= 540 -> 132; else -> 52 }
            return MenuLayout(w, h, minOf(rail, w / 3).coerceAtLeast(1))
        }
    }
}

/** Existing caller labels remain compatible; their destinations use the shared feature identities. */
enum class MenuPage(val title: String, val featureId: String) {
    DIGEST("Daily Digest", "daily-digest"), PARTY("Party Finder", "party-finder"), FRIENDS("Friends", "player-utilities"),
    DUNGEONS("Dungeons", "dungeon-tracker"), SPLITS("Dungeon Splits", "dungeon-splits"), CRUSH_CAMERA("Crush Camera", "healer-crush-camera"), JOURNAL("RNG Journal", "rng-journal"), AUCTIONS("Auctions", "auction-browser"), COSMETICS("Cosmetics", "cosmetics"), HUD("HUD editor", "settings-hud")
}

object MenuTheme {
    val style get() = ThemeConfig.preset.palette.style
    private val palette get() = ThemeConfig.preset.palette
    val ACCENT get() = palette.primary
    val SECONDARY get() = palette.secondary
    val PANEL get() = palette.panel
    val SIDEBAR get() = palette.sidebar
    val CARD get() = palette.card
    val BORDER get() = palette.border
    val TEXT get() = palette.text
    val MUTED get() = palette.muted
    val GREEN get() = palette.success
    val YELLOW get() = palette.warning
    val RED get() = palette.error
    val PURPLE get() = SECONDARY
    val PADDING get() = style.menuPadding
    val RADIUS get() = if (ROUNDED) style.radius.coerceAtLeast(2) else 0
    val SHADOW get() = me.mycellium.skymyce.ui.AutoGraphics.quality.shadowLayers > 0 && palette.shadow
    val OPACITY get() = palette.opacity
    val ROUNDED get() = palette.rounded
    val BORDERS get() = palette.borders
    fun blend(a: Int, b: Int, weight: Int) = me.mycellium.skymyce.hud.HudTheme.blend(a, b, weight)
    private val font = FontDescription.Resource(Identifier.fromNamespaceAndPath("skymyce", "menu"))

    fun text(value: Component): Component = value.copy().withStyle { it.withFont(font) }
    fun alpha(rgb: Int, percent: Int = 100) = (percent.coerceIn(0, 100) * 255 / 100 shl 24) or (rgb and 0xFFFFFF)
    fun rounded(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, rgb: Int, radius: Int = RADIUS) {
        if (w <= 0 || h <= 0) return
        val r = if (ROUNDED) minOf(radius, w / 2, h / 2) else 0
        val color = if (rgb ushr 24 == 0) alpha(rgb) else rgb
        if (g is MenuGraphics) {
            g.roundedRect(x, y, w, h, r.toDouble(), color)
            return
        }
        g.fill(x + r, y, x + w - r, y + h, color)
        g.fill(x, y + r, x + r, y + h - r, color)
        g.fill(x + w - r, y + r, x + w, y + h - r, color)
        if (r > 0) {
            val tint = Color.ofArgb(color)
            g.drawCircle(x + r, y + r, 0.0, 90.0, 12, r.toDouble(), tint)
            g.drawCircle(x + w - r, y + r, 90.0, 180.0, 12, r.toDouble(), tint)
            g.drawCircle(x + w - r, y + h - r, 180.0, 270.0, 12, r.toDouble(), tint)
            g.drawCircle(x + r, y + h - r, 270.0, 360.0, 12, r.toDouble(), tint)
        }
    }
    fun box(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, background: Int, border: Int = BORDER) {
        if (g is MenuGraphics) {
            rounded(g, x, y, w, h, alpha(background, OPACITY))
            if (BORDERS && w >= 8 && h >= 8)
                g.roundedBorder(x, y, w, h, RADIUS.toDouble(), style.borderWidth, alpha(border))
            return
        }
        rounded(g, x + 1, y + 1, w - 2, h - 2, alpha(background, OPACITY), 3)
        if (!BORDERS || w < 8 || h < 8) return
        if (!ROUNDED) { g.drawRectOutline(x, y, w, h, alpha(border)); return }
        g.fill(x + 4, y, x + w - 4, y + 1, alpha(border))
        g.fill(x + 4, y + h - 1, x + w - 4, y + h, alpha(border))
        g.fill(x, y + 4, x + 1, y + h - 4, alpha(border))
        g.fill(x + w - 1, y + 4, x + w, y + h - 4, alpha(border))
        val color = Color.ofRgb(border)
        g.drawRing(x + 4, y + 4, 0.0, 90.0, 12, 3.0, 4.0, color, color)
        g.drawRing(x + w - 4, y + 4, 90.0, 180.0, 12, 3.0, 4.0, color, color)
        g.drawRing(x + w - 4, y + h - 4, 180.0, 270.0, 12, 3.0, 4.0, color, color)
        g.drawRing(x + 4, y + h - 4, 270.0, 360.0, 12, 3.0, 4.0, color, color)
    }
    /** Native blur is reserved for expressive modes; lighter modes retain one cheap silhouette. */
    fun shadow(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, strength: Double = 1.0) {
        if (!SHADOW || strength <= 0.0 || w <= 0 || h <= 0) return
        val profile = ThemeConfig.animationMode.profile
        val qualityLayers = me.mycellium.skymyce.ui.AutoGraphics.quality.shadowLayers.coerceIn(0, 3)
        if (g is MenuGraphics && SkijaMenuFonts.active && qualityLayers >= 2 && profile.glowStrength > 0.0) {
            g.shadow(x, y + 2, w, h, RADIUS.toDouble(), if (profile.ambient) 4.0 else 3.0,
                alpha(style.shadowTint, (strength.coerceIn(0.0, 1.0) * profile.shadowStrength * style.shadowOpacity).toInt()))
            return
        }
        val layers = if (profile.glowStrength > 0.0) qualityLayers else minOf(qualityLayers, 1)
        for (layer in layers downTo 1) {
            val opacity = (strength.coerceIn(0.0, 1.0) * style.shadowOpacity * (11 - layer * 2) / 24).toInt()
            if (opacity > 0) rounded(g, x - layer, y + 1, w + layer * 2, h + layer * 2, alpha(style.shadowTint, opacity), RADIUS + layer)
        }
    }
    fun glow(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, strength: Double) {
        val profile = ThemeConfig.animationMode.profile
        if (!me.mycellium.skymyce.ui.AutoGraphics.quality.glow || style.glow <= 0.0 || profile.glowStrength <= 0.0 || strength <= 0.0 || w <= 0 || h <= 0) return
        if (g is MenuGraphics && SkijaMenuFonts.active) {
            g.shadow(x, y, w, h, RADIUS.toDouble(), if (profile.ambient) 5.0 else 4.0,
                alpha(ACCENT, (strength.coerceIn(0.0, 1.0) * style.glow * 18).toInt()))
            return
        }
        val opacity = (strength.coerceIn(0.0, 1.0) * style.glow * 9).toInt()
        if (opacity > 0) rounded(g, x - 1, y - 1, w + 2, h + 2, alpha(ACCENT, opacity), 5)
    }
    /** Transparent lighting keeps the configured panel opacity intact. */
    fun sheen(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, strength: Double = 1.0) {
        if (me.mycellium.skymyce.ui.AutoGraphics.quality == GuiQuality.EXTREMELY_LIGHT || style.sheen <= 0.0 || w < 10 || h < 10 || strength <= 0.0) return
        val opacity = (strength.coerceIn(0.0, 1.0) * style.sheen * OPACITY * .12).toInt()
        if (opacity == 0) return
        val tint = blend(ACCENT, SECONDARY, 55)
        g.drawGradientRect(x + 4, y + 2, w - 8, minOf(h - 4, 44),
            alpha(tint, opacity), alpha(tint, opacity / 2), alpha(tint, 0), alpha(tint, 0))
        g.fill(x + 6, y + 1, x + w - 6, y + 2, alpha(blend(BORDER, TEXT, 35), opacity + 8))
    }
    fun panel(card: Boolean = false): Surface = Surface { g, c ->
        shadow(g, c.x(), c.y(), c.width(), c.height(), if (card) .35 else 1.0)
        box(g, c.x(), c.y(), c.width(), c.height(), if (card) CARD else PANEL)
        sheen(g, c.x(), c.y(), c.width(), c.height(), if (card) .65 else 1.0)
    }
    fun tintedPanel(color: () -> Int): Surface = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), color()) }
    val backdrop = Surface { g, c -> g.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), alpha(blend(PANEL, 0, 60), 64)) }

    fun focus(g: MenuGraphics, x: Int, y: Int, w: Int, h: Int) {
        if (w > 0 && h > 0) g.roundedBorder(x, y, w, h, minOf(RADIUS.toDouble(), w / 2.0, h / 2.0),
            style.focusWidth.toDouble(), alpha(ACCENT))
    }

    @JvmStatic fun drawField(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, focused: Boolean) {
        val graphics = g as MenuGraphics
        box(graphics, x, y, w, h, CARD, if (focused) ACCENT else BORDER)
        if (focused) focus(graphics, x, y, w, h)
    }
}

/** No vanilla button renderer or texture; inherit its tested input and narration. */
enum class MenuButtonIntent { PRIMARY, SECONDARY, DESTRUCTIVE }

open class MenuButton(message: Component, action: (ButtonComponent) -> Unit) : ButtonComponent(message, action) {
    var intent = MenuButtonIntent.SECONDARY
    var selected: () -> Boolean = { false }
    var navigation = false
    var centeredNavigation = false
    var background = true
    var trailingChevron = false
    var toggled: (() -> Boolean)? = null
    private val hoverMotion = MotionValue(0.0)
    private val selectionMotion = MotionValue(0.0)
    private val pressMotion = MotionValue(0.0)
    private val toggleMotion = MotionValue(0.0)
    private var toggleInitialized = false
    private var selectionInitialized = false
    private var pressed = false
    internal var paintedInteraction = MotionPose()
        private set
    private var wrappedMessage: Component? = null
    private var wrappedWidth = -1
    private var wrappedNavigation = false
    private var wrappedNativeMetrics = false
    private var wrappedLanguage: net.minecraft.locale.Language? = null
    private var lines = emptyList<net.minecraft.util.FormattedCharSequence>()
    private var lineWidths = IntArray(0)
    private var lineRuns = emptyList<List<MenuCanvas.Run>>()
    private var runColor = 0

    init {
        focusLost().subscribe { releasePress() }
        onPress { button ->
            pressMotion.snap(if (ThemeConfig.animationMode == GuiMotion.OFF) 0.0 else 1.0)
            if (!pressed) releasePress()
            action(button)
        }
    }

    override fun onMouseDown(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (active && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressed = true
            pressMotion.animateTo(1.0, 65, MotionEasing.QUICK, ThemeConfig.animationMode)
        }
        return super.onMouseDown(click, doubled)
    }
    override fun onMouseUp(click: MouseButtonEvent): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) releasePress()
        return super.onMouseUp(click)
    }
    private fun releasePress() {
        pressed = false
        pressMotion.animateTo(0.0, 155, MotionEasing.SETTLE, ThemeConfig.animationMode)
    }
    /** Carry category feedback across page navigation. */
    internal fun inheritMotion(previous: MenuButton) {
        hoverMotion.snap(previous.hoverMotion.sample())
        selectionMotion.snap(previous.selectionMotion.sample())
        selectionInitialized = true
        pressMotion.snap(previous.pressMotion.sample())
        releasePress()
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        if (isHovered && !menuHoverAllowed(this, mouseX, mouseY)) isHovered = false
        hoverMotion.animateTo(if (active && (isHovered || isFocused)) 1.0 else 0.0, 110, ThemeConfig.animationMode.profile.hoverEasing, ThemeConfig.animationMode)
        val selection = if (selected()) 1.0 else 0.0
        // Rebuilt navigation keeps its selected state; only subsequent selections animate.
        if (!selectionInitialized) { selectionMotion.snap(selection); selectionInitialized = true }
        selectionMotion.animateTo(selection, MenuTheme.style.transitionMillis.toLong(), MotionEasing.QUICK, ThemeConfig.animationMode)
        if (!active && pressed) releasePress()
        if (ThemeConfig.animationMode == GuiMotion.OFF) pressMotion.snap(0.0)
        val hover = hoverMotion.sample().coerceIn(0.0, 1.04)
        val chosen = selectionMotion.sample().coerceIn(0.0, 1.0)
        val press = pressMotion.sample().coerceIn(0.0, 1.0)
        val pose = interactionPose(hover, press)
        paintedInteraction = pose
        graphics.layer(y = pose.y, scale = pose.scale, pivotX = pose.pivotX, pivotY = pose.pivotY) {
            paint(graphics, delta, hover, chosen, press)
        }
    }
    protected open fun interactionPose(hover: Double, press: Double): MotionPose {
        val profile = ThemeConfig.animationMode.profile
        return MotionPose(scale = 1.0 + hover * profile.hoverScale - press * profile.pressScale,
            y = if (navigation || toggled != null) 0.0 else -hover * profile.cardLift * .25 + press,
            pivotX = x + width / 2.0, pivotY = y + height / 2.0)
    }
    protected open fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
        toggled?.let { state ->
            val on = state()
            val target = if (on) 1.0 else 0.0
            if (!toggleInitialized) { toggleMotion.snap(target); toggleInitialized = true }
            toggleMotion.animateTo(target, 180, MotionEasing.SMOOTH, ThemeConfig.animationMode)
            val toggleProgress = toggleMotion.sample().coerceIn(0.0, 1.0)
            val w = minOf(26, width - 4)
            val h = minOf(14, height - 4)
            if (w < 6 || h < 6) return
            val left = if (width >= 54) x + 4 else x + (width - w) / 2
            val top = y + (height - h) / 2
            MenuTheme.rounded(graphics, left - 1, top - 1, w + 2, h + 2, MenuTheme.blend(MenuTheme.PANEL, MenuTheme.BORDER, 45), h / 2 + 1)
            MenuTheme.rounded(graphics, left, top, w, h, MenuTheme.blend(MenuTheme.BORDER, if (active) MenuTheme.ACCENT else MenuTheme.MUTED, (toggleProgress * 100).toInt()), h / 2)
            graphics.layer(x = toggleProgress * (w - h)) {
                graphics.drawCircle(left + h / 2, top + h / 2, 24,
                    h / 2 - 2 + hover * .5, Color.ofRgb(MenuTheme.blend(MenuTheme.TEXT,
                        me.mycellium.skymyce.hud.themeForeground(MenuTheme.ACCENT), (toggleProgress * 100).toInt())))
            }
            if (isFocused && background) MenuTheme.focus(graphics, x, y, width, height)
            if (width >= 54) graphics.styledText(listOf(MenuCanvas.Run(if (on) "On" else "Off", MenuTheme.alpha(if (active) MenuTheme.TEXT else MenuTheme.MUTED))),
                (left + w + 6).toDouble(), (y + (height - MenuCanvas.lineHeight()) / 2).toDouble())
            return
        }
        if (background && renderer() !== Renderer.VANILLA) {
            renderer().draw(graphics, this, delta)
        } else if (background && (!navigation || chosen > 0.0 || hover > 0.0)) {
            graphics.layer(alpha = if (navigation) maxOf(chosen, hover) else 1.0) {
                MenuTheme.shadow(graphics, x, y, width, height, (.2 + hover * .7) * ThemeConfig.animationMode.profile.shadowStrength)
                MenuTheme.glow(graphics, x, y, width, height, hover * ThemeConfig.animationMode.profile.glowStrength)
                val accent = if (!active) MenuTheme.MUTED else if (intent == MenuButtonIntent.DESTRUCTIVE) MenuTheme.RED else MenuTheme.ACCENT
                MenuTheme.box(graphics, x, y, width, height,
                    MenuTheme.blend(MenuTheme.CARD, accent, (hover * MenuTheme.style.hoverWeight + chosen * MenuTheme.style.selectionWeight + press * 9 + if (active && intent == MenuButtonIntent.PRIMARY) 12 else 0).toInt()),
                    MenuTheme.blend(MenuTheme.BORDER, accent, (maxOf(chosen, hover * .65).coerceAtLeast(if (active && intent != MenuButtonIntent.SECONDARY) .65 else 0.0) * 100).toInt()))
                MenuTheme.sheen(graphics, x, y, width, height, .45 + hover * .55)
                if (navigation && !centeredNavigation && chosen > 0.0) {
                    val indicatorHeight = ((height - 10) * chosen).toInt()
                    MenuTheme.rounded(graphics, x + 1, y + (height - indicatorHeight) / 2, MenuTheme.style.focusWidth, indicatorHeight, MenuTheme.ACCENT, 1)
                }
                if (!navigation && chosen >= .99 && width >= 38) {
                    // A geometric check remains readable even when the active menu font lacks a glyph.
                    for (i in 0..2) graphics.fill(x + 4 + i, y + height / 2 + i, x + 5 + i, y + height / 2 + i + 2, MenuTheme.alpha(MenuTheme.TEXT))
                    for (i in 0..4) graphics.fill(x + 6 + i, y + height / 2 + 2 - i, x + 7 + i, y + height / 2 + 4 - i, MenuTheme.alpha(MenuTheme.TEXT))
                }
            }
        }
        if (isFocused && active) MenuTheme.focus(graphics, x, y, width, height)
        val contentWidth = width - if (trailingChevron) 12 else 0
        val available = (contentWidth - 12).coerceAtLeast(1)
        val language = net.minecraft.locale.Language.getInstance()
        if (message != wrappedMessage || available != wrappedWidth || navigation != wrappedNavigation || language !== wrappedLanguage ||
            wrappedNativeMetrics != SkijaMenuFonts.active) {
            wrappedMessage = message.copy()
            wrappedWidth = available; wrappedNavigation = navigation
            wrappedLanguage = language
            wrappedNativeMetrics = SkijaMenuFonts.active
            lines = menuTextLines(wrappedMessage!!, available).take(if (navigation) 2 else 1)
            lineWidths = IntArray(lines.size) { kotlin.math.ceil(menuTextWidth(lines[it])).toInt() }
            lineRuns = emptyList()
        }
        val textColor = if (!active) MenuTheme.MUTED else if (navigation)
            MenuTheme.blend(MenuTheme.MUTED, MenuTheme.TEXT, (60 + maxOf(chosen, hover) * 40).toInt()) else MenuTheme.TEXT
        val color = MenuTheme.alpha(textColor)
        if (lineRuns.size != lines.size || runColor != color) {
            lineRuns = lines.map { menuTextRuns(it, color) }; runColor = color
        }
        val lineHeight = MenuCanvas.lineHeight()
        graphics.enableScissor(x + 3, y + 1, x + contentWidth - 3, y + height - 1)
        lines.forEachIndexed { index, _ ->
            val left = if (navigation && !centeredNavigation) x + 8 else x + (contentWidth - lineWidths[index]) / 2
            val shift = if (navigation && !centeredNavigation) maxOf(chosen, hover) * ThemeConfig.animationMode.profile.cardLift * .3 else 0.0
            graphics.styledText(lineRuns[index], left + shift,
                (y + (height - lines.size * lineHeight) / 2 + index * lineHeight).toDouble())
        }
        graphics.disableScissor()
        if (trailingChevron) {
            val tipX = x + width - 10; val tipY = y + height / 2 + 2
            graphics.drawLine(tipX - 3, tipY - 3, tipX, tipY, 1.25, Color.ofArgb(color))
            graphics.drawLine(tipX, tipY, tipX + 3, tipY - 3, 1.25, Color.ofArgb(color))
        }
    }
    // Focus is painted with the button/card, so owo must not add a second rectangle after it.
    override fun drawFocusHighlight(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {}
    override fun shouldDrawTooltip(mouseX: Double, mouseY: Double) = visible && isInBoundingBox(mouseX, mouseY) && !tooltip().isNullOrEmpty()
}

open class MenuLabel(value: Component) : LabelComponent(MenuTheme.text(value)) {
    private var measuredLines: List<net.minecraft.util.FormattedCharSequence>? = null
    private var measuredFontSize = 0f
    private var lineWidths = IntArray(0)
    private var lineRuns = emptyList<List<MenuCanvas.Run>>()
    private var runColor = 0
    var fontSize = 10f
        set(value) { field = value; lineHeight(MenuCanvas.lineHeight(value.toDouble())) }
    init {
        lineHeight(MenuCanvas.lineHeight())
        color(Color.ofRgb(value.style.color?.value ?: MenuTheme.TEXT))
    }
    override fun text(value: Component): LabelComponent = super.text(MenuTheme.text(value))
    private fun wrap() {
        wrappedText = menuTextLines(text(), if (horizontalSizing().get().isContent) maxWidth() else width(), fontSize.toDouble())
    }
    private fun cacheLines() {
        if (measuredLines === wrappedText && measuredFontSize == fontSize) return
        measuredLines = wrappedText; measuredFontSize = fontSize
        lineWidths = IntArray(wrappedText.size) { kotlin.math.ceil(menuTextWidth(wrappedText[it], fontSize.toDouble())).toInt() }
        lineRuns = emptyList()
    }
    override fun determineHorizontalContentSize(sizing: Sizing): Int =
        kotlin.math.ceil(menuTextLines(text(), maxWidth(), fontSize.toDouble()).maxOfOrNull { menuTextWidth(it, fontSize.toDouble()) } ?: 0.0).toInt()
    override fun determineVerticalContentSize(sizing: Sizing): Int { wrap(); return textHeight() }
    override fun inflate(space: Size) {
        lineHeight(MenuCanvas.lineHeight(fontSize.toDouble()))
        super.inflate(space); wrap()
    }
    // Owo's default drawing adds a Minecraft baseline offset. Canvas positions are top-of-line.
    override fun drawText(draw: LabelDrawFunction) {
        cacheLines()
        val left = x() + if (horizontalSizing().get().isContent) horizontalSizing().get().value else 0
        var top = y() + if (verticalSizing().get().isContent) verticalSizing().get().value else 0
        top += when (verticalTextAlignment()) {
            VerticalAlignment.CENTER -> (height() - textHeight()) / 2
            VerticalAlignment.BOTTOM -> height() - textHeight()
            else -> 0
        }
        wrappedText.forEachIndexed { index, line ->
            val offset = when (horizontalTextAlignment()) {
                HorizontalAlignment.CENTER -> (width() - lineWidths[index]) / 2
                HorizontalAlignment.RIGHT -> width() - lineWidths[index]
                else -> 0
            }
            draw.draw(left + offset, top + index * (lineHeight() + lineSpacing()), line, shadow(), color().get())
        }
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        cacheLines()
        val color = color().get().argb()
        if (lineRuns.size != wrappedText.size || runColor != color) {
            lineRuns = wrappedText.map { menuTextRuns(it, color) }; runColor = color
        }
        var index = 0
        drawText { x, y, _, _, _ ->
            (g as MenuGraphics).styledText(lineRuns[index++], x.toDouble(), y.toDouble(), fontSize.toDouble())
        }
    }
    override fun styleAt(mouseX: Int, mouseY: Int): Style? {
        val targetX = x() + mouseX
        val targetY = y() + mouseY
        var result: Style? = null
        drawText { left, top, text, _, _ ->
            if (result == null) result = menuTextStyleAt(text, (targetX - left).toDouble(), (targetY - top).toDouble(), fontSize.toDouble(), lineHeight())
        }
        return result
    }
}

/** Hover and click hit tests use painted advances; no Minecraft glyph preparation is needed. */
internal fun menuTextStyleAt(text: net.minecraft.util.FormattedCharSequence, x: Double, y: Double,
                             size: Double = 10.0, lineHeight: Int = MenuCanvas.lineHeight(size)): Style? {
    if (x < 0 || y < 0 || y >= lineHeight) return null
    var left = 0.0
    var result: Style? = null
    text.accept { _, style, codepoint ->
        val right = left + MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size)
        if (x < right) { result = style; false } else { left = right; true }
    }
    return result
}

/** Keep owo's popup layout, but give its entries the same font, paint and input as the menus. */
internal class MenuDropdown(minimumWidth: Int, private val close: () -> Unit) : DropdownComponent(Sizing.fixed(minimumWidth)) {
    private var entryWidth = minimumWidth.coerceAtLeast(32)
    private val entryViewport = UIContainers.verticalScroll(Sizing.fixed(entryWidth), Sizing.fixed(1), entries)
    private val localFocus = FocusHandler(this)
    private val appearance = MotionValue(0.0)
    private var painted = MotionPose()
    private var opened = false
    var closing = false
        private set
    val finishedClosing get() = closing && appearance.sample() == 0.0 && !appearance.running
    init {
        entries.padding(Insets.of(5)); entries.surface(Surface.BLANK)
        // DropdownComponent.removeChild(entries) dismisses the popup through its parent queue.
        // This constructor is unmounted; clear the inherited flow's initial child directly.
        clearChildren(); child(entryViewport)
        entryViewport.surface(Surface { g, c ->
            MenuTheme.shadow(g, c.x(), c.y(), c.width(), c.height())
            MenuTheme.rounded(g, c.x(), c.y(), c.width(), c.height(), MenuTheme.alpha(MenuTheme.CARD))
            if (MenuTheme.BORDERS) (g as MenuGraphics).roundedBorder(c.x(), c.y(), c.width(), c.height(),
                MenuTheme.RADIUS.toDouble(), MenuTheme.style.borderWidth, MenuTheme.alpha(MenuTheme.BORDER))
        })
        entryViewport.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.ACCENT)))
        allowOverflow(true)
    }

    fun fitViewport(space: Size) = fitMenuDropdownScroll(entryViewport, entries, entryWidth, space)

    // Owo paints during reparenting and detached exits; its child painter requires a focus context in both cases.
    override fun focusHandler(): FocusHandler = menuDropdownFocus(super.focusHandler(), localFocus)

    fun beginClose() {
        if (closing) return
        closing = true
        appearance.animateTo(0.0, 100, MotionEasing.QUICK, ThemeConfig.animationMode)
        children().filterIsInstance<MenuDropdown>().forEach { it.beginClose() }
    }
    private fun beginOpen() {
        closing = false; opened = false; appearance.snap(0.0)
        entries.children().filterIsInstance<MenuDropdownEntry>().forEach { it.resetAppearance() }
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (!opened && !closing) {
            opened = true
            appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode)
        }
        appearance.animateTo(if (closing) 0.0 else 1.0, if (closing) 100 else 130, MotionEasing.QUICK, ThemeConfig.animationMode)
        val amount = appearance.sample().coerceIn(0.0, 1.0)
        val profile = ThemeConfig.animationMode.profile
        painted = MotionPose(alpha = amount, y = (1 - amount) * profile.revealOffset * .35,
            scale = 1 - (1 - amount) * profile.pageScale * 2, pivotX = x().toDouble(), pivotY = y().toDouble())
        (g as MenuGraphics).layer(painted.alpha, painted.x, painted.y, painted.scale, painted.pivotX, painted.pivotY) {
            super.draw(g, mouseX, mouseY, partialTicks, delta)
        }
        children().filterIsInstance<MenuDropdown>().filter { it.finishedClosing }.toList().forEach(::removeChild)
    }
    private fun closeNested() { children().filterIsInstance<MenuDropdown>().forEach { it.beginClose() } }

    private fun entry(text: Component, indicator: (() -> Boolean)? = null, nested: Boolean = false, action: () -> Unit): MenuDropdownEntry {
        entryWidth = maxOf(entryWidth, menuDropdownWidth(text, indicator != null || nested))
        entries.horizontalSizing(Sizing.fixed(entryWidth))
        return MenuDropdownEntry(text, indicator, nested, minOf(entries.children().size, 5) * ThemeConfig.animationMode.profile.staggerMillis, action).apply {
            sizing(Sizing.fill(), Sizing.fixed(menuDropdownRowHeight()))
            margins(Insets.horizontal(3))
            entries.child(this)
            mouseEnter().subscribe { queue { closeNested() } }
        }
    }
    override fun text(text: Component): DropdownComponent {
        entryWidth = maxOf(entryWidth, menuDropdownWidth(text, false))
        entries.horizontalSizing(Sizing.fixed(entryWidth))
        entries.child(MenuLabel(text).color(Color.ofRgb(MenuTheme.MUTED)).apply {
            sizing(Sizing.fill(), Sizing.fixed(menuDropdownRowHeight())); margins(Insets.horizontal(3))
            verticalTextAlignment(VerticalAlignment.CENTER)
        })
        return this
    }
    override fun button(text: Component, onClick: Consumer<DropdownComponent>): DropdownComponent {
        entry(text) { close(); onClick.accept(this); UISounds.playButtonSound() }
        return this
    }
    override fun checkbox(text: Component, state: Boolean, onClick: Consumer<Boolean>): DropdownComponent {
        var checked = state
        entry(text, { checked }) { checked = !checked; onClick.accept(checked); UISounds.playInteractionSound() }
        return this
    }
    override fun nested(text: Component, horizontalSizing: Sizing, builder: Consumer<DropdownComponent>): DropdownComponent {
        val child = MenuDropdown(if (horizontalSizing.isContent) 32 else horizontalSizing.value, close)
        builder.accept(child)
        lateinit var row: MenuDropdownEntry
        val open: () -> Unit = {
            if (child.parent() !== this || child.closing) {
                closeNested()
                if (child.parent() === this) removeChild(child)
                child.beginOpen()
            }
            val root = generateSequence(parent()) { it.parent() }.lastOrNull()
            val bounds = root ?: this
            val space = Size.of((bounds.width() - 8).coerceAtLeast(1), (bounds.height() - 8).coerceAtLeast(1))
            child.fitViewport(space); child.inflate(space)
            val left = (x() + entries.width() - 1).coerceIn(bounds.x() + 4,
                (bounds.x() + bounds.width() - child.width() - 4).coerceAtLeast(bounds.x() + 4))
            val top = row.y().coerceIn(bounds.y() + 4,
                (bounds.y() + bounds.height() - child.height() - 4).coerceAtLeast(bounds.y() + 4))
            child.positioning(Positioning.absolute(left - x(), top - y()))
            if (child.parent() !== this) child(child)
            child.mount(this, left, top)
        }
        row = entry(text, nested = true) { open(); child.focusFirst() }
        row.mouseEnter().subscribe { queue(open) }
        return this
    }
    fun focusFirst() {
        if (closing) return
        entries.children().firstOrNull { it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) }?.let {
            entryViewport.scrollTo(it)
            focusHandler()?.focus(it, UIComponent.FocusSource.KEYBOARD_CYCLE)
        }
    }
    fun pointer(x: Double, y: Double): Pair<Double, Double>? {
        if (closing) return null
        val px = painted.inverseX(x); val py = painted.inverseY(y)
        children().filterIsInstance<MenuDropdown>().asReversed().forEach { it.pointer(px, py)?.let { point -> return point } }
        return if (px >= x() && py >= y() && px < x() + width() && py < y() + height()) px to py else null
    }
    override fun isInBoundingBox(x: Double, y: Double): Boolean = !closing && (super.isInBoundingBox(x, y) ||
        children().filterIsInstance<MenuDropdown>().any { it.isInBoundingBox(x, y) })
    fun hoverOwner(x: Int, y: Int): MenuDropdown? {
        if (closing) return null
        for (index in children().indices.reversed()) {
            (children()[index] as? MenuDropdown)?.hoverOwner(x, y)?.let { return it }
        }
        return if (super.isInBoundingBox(x.toDouble(), y.toDouble())) this else null
    }
    fun cycleFocus(forward: Boolean) {
        if (closing) return
        val rows = ArrayList<UIComponent>()
        collectDescendants(rows)
        rows.removeAll { it !is MenuDropdownEntry || !it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) }
        if (rows.isEmpty()) return
        val current = rows.indexOf(focusHandler()?.focused())
        val next = Math.floorMod(current + if (forward) 1 else -1, rows.size)
        val selected = rows[next]
        generateSequence(selected.parent()) { it.parent() }.filterIsInstance<ScrollContainer<*>>().firstOrNull()?.scrollTo(selected)
        focusHandler()?.focus(selected, UIComponent.FocusSource.KEYBOARD_CYCLE)
    }
}

internal fun menuDropdownFocus(parent: FocusHandler?, local: FocusHandler): FocusHandler = parent ?: local

/** Owo updates every overlapping child; pointer emphasis belongs to the uppermost popup. */
internal fun menuHoverAllowed(component: UIComponent, mouseX: Int, mouseY: Int): Boolean {
    var root = component
    while (true) root = root.parent() ?: break
    if (root !is ParentUIComponent) return true
    for (index in root.children().indices.reversed()) {
        val owner = (root.children()[index] as? MenuDropdown)?.hoverOwner(mouseX, mouseY) ?: continue
        return menuComponentAttached(owner, component)
    }
    return true
}

internal fun menuDropdownRowHeight() = MenuCanvas.lineHeight() + 8
internal fun menuDropdownWidth(text: Component, indicator: Boolean) =
    kotlin.math.ceil(menuTextLines(text, Int.MAX_VALUE).maxOfOrNull { menuTextWidth(it) } ?: 0.0).toInt() + 26 + if (indicator) 22 else 0

/** Measure the actual rows before mounting; a long option list remains reachable through native scrolling. */
internal fun fitMenuDropdownScroll(viewport: ScrollContainer<FlowLayout>, entries: FlowLayout, wantedWidth: Int, space: Size) {
    val size = menuDropdownContentSize(entries, wantedWidth, space)
    viewport.sizing(Sizing.fixed(size.width()), Sizing.fixed(size.height()))
}

internal fun menuDropdownContentSize(entries: FlowLayout, wantedWidth: Int, space: Size): Size {
    val width = wantedWidth.coerceIn(1, space.width().coerceAtLeast(1))
    entries.horizontalSizing(Sizing.fixed(width))
    entries.inflate(Size.of(width, space.height().coerceAtLeast(1)))
    return Size.of(width, entries.height().coerceIn(1, space.height().coerceAtLeast(1)))
}

private class MenuDropdownEntry(value: Component, private val checked: (() -> Boolean)?, private val nested: Boolean, private val delayMillis: Long,
                                private val action: () -> Unit) : MenuLabel(value) {
    private val hoverMotion = MotionValue(0.0)
    private val checkMotion = MotionValue(if (checked?.invoke() == true) 1.0 else 0.0)
    private val pressMotion = MotionValue(0.0)
    private val appearance = MotionValue(0.0)
    private var appeared = false
    private val interactive get() = generateSequence(parent()) { it.parent() }.filterIsInstance<MenuDropdown>().none { it.closing }
    init { color(Color.ofRgb(MenuTheme.TEXT)); verticalTextAlignment(VerticalAlignment.CENTER); cursorStyle(CursorStyle.HAND) }
    override fun canFocus(source: UIComponent.FocusSource) = interactive
    override fun onMouseDown(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (!interactive || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        activate(); return true
    }
    override fun onKeyPress(input: KeyEvent): Boolean {
        if (!interactive) return false
        if (input.key() !in listOf(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE) && !(nested && input.key() == GLFW.GLFW_KEY_RIGHT)) return super.onKeyPress(input)
        activate(); return true
    }
    private fun activate() {
        pressMotion.snap(if (ThemeConfig.animationMode == GuiMotion.OFF) 0.0 else 1.0)
        pressMotion.animateTo(0.0, 145, MotionEasing.SETTLE, ThemeConfig.animationMode)
        action()
    }
    fun resetAppearance() { appeared = false; appearance.snap(0.0); hoverMotion.snap(0.0); pressMotion.snap(0.0) }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (!appeared) {
            appeared = true
            appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis)
        } else appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis)
        val hover = interactive && (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble()) && menuHoverAllowed(this, mouseX, mouseY) ||
            focusHandler()?.focused() === this)
        hoverMotion.animateTo(if (hover) 1.0 else 0.0, 100, MotionEasing.QUICK, ThemeConfig.animationMode)
        val emphasis = hoverMotion.sample().coerceIn(0.0, 1.0)
        if (ThemeConfig.animationMode == GuiMotion.OFF) pressMotion.snap(0.0)
        val press = pressMotion.sample().coerceIn(0.0, 1.0)
        (g as MenuGraphics).layer(alpha = appearance.sample().coerceIn(0.0, 1.0)) {
            if (emphasis > 0.0) g.layer(alpha = emphasis) {
                MenuTheme.rounded(g, x() - 3, y(), width() + 6, height(), MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, MenuTheme.style.hoverWeight + (press * 10).toInt()))
                MenuTheme.sheen(g, x() - 3, y(), width() + 6, height(), .7)
                MenuTheme.rounded(g, x() - 3, y() + 4, 2, height() - 8, MenuTheme.ACCENT, 1)
            }
            super.draw(g, mouseX, mouseY, partialTicks, delta)
            checked?.let {
                checkMotion.animateTo(if (it()) 1.0 else 0.0, 170, MotionEasing.SMOOTH, ThemeConfig.animationMode)
                val progress = checkMotion.sample().coerceIn(0.0, 1.0)
                val left = x() + width() - 18; val top = y() + (height() - 10) / 2
                MenuTheme.rounded(g, left, top, 18, 10, MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (progress * 100).toInt()), 5)
                g.layer(x = progress * 8) { g.drawCircle(left + 5, top + 5, 20, 3.0,
                    Color.ofRgb(MenuTheme.blend(MenuTheme.TEXT, me.mycellium.skymyce.hud.themeForeground(MenuTheme.ACCENT), (progress * 100).toInt()))) }
            }
            if (nested) {
                val left = x() + width() - 5; val top = y() + height() / 2
                for (i in 0..3) g.fill(left + i, top - 3 + i, left + i + 1, top + 4 - i,
                    MenuTheme.alpha(MenuTheme.blend(MenuTheme.MUTED, MenuTheme.ACCENT, (emphasis * 100).toInt())))
            }
        }
    }
    override fun drawFocusHighlight(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {}
}

/** Mount and hit-test in the custom viewport, never Fabric's native GUI coordinates. */
internal fun mountMenuDropdown(root: FlowLayout, dropdown: ParentUIComponent, x: Int, y: Int, anchorHeight: Int = 0) {
    var space = Size.of((root.width() - 8).coerceAtLeast(1), (root.height() - 8).coerceAtLeast(1))
    if (dropdown is MenuDropdown) dropdown.fitViewport(space)
    dropdown.inflate(space)
    val placement = if (dropdown is MenuDropdown) menuDropdownPlacement(root.height(), y - root.y(), anchorHeight, dropdown.height()) else null
    if (placement != null) {
        space = Size.of(space.width(), placement.second)
        (dropdown as MenuDropdown).fitViewport(space)
        dropdown.inflate(space)
    }
    dropdown.positioning(Positioning.absolute(0, 0))
    root.child(dropdown)
    val left = (x - root.x()).coerceIn(4, (root.width() - dropdown.width() - 4).coerceAtLeast(4))
    val top = placement?.first ?: (y - root.y()).coerceIn(4, (root.height() - dropdown.height() - 4).coerceAtLeast(4))
    dropdown.positioning(Positioning.absolute(left, top))
    // Mouse dispatch batches the parent's layout events. Mount now so bounds/focus are valid
    // during this event too, before that deferred layout is flushed.
    dropdown.mount(root, root.x() + left, root.y() + top)
}

/** Keep short lists below the opener; long lists scroll on whichever side has more room. */
internal fun menuDropdownPlacement(viewportHeight: Int, anchorBottom: Int, anchorHeight: Int, wantedHeight: Int): Pair<Int, Int> {
    val height = viewportHeight.coerceAtLeast(1)
    val margin = minOf(4, (height - 1) / 2)
    val end = height - margin
    val bottom = anchorBottom.coerceIn(margin, end)
    val top = (anchorBottom - anchorHeight.coerceAtLeast(0)).coerceIn(margin, end)
    val below = end - bottom
    val above = top - margin
    val opensAbove = wantedHeight > below && above > below
    val size = wantedHeight.coerceAtLeast(1).coerceAtMost((if (opensAbove) above else below).coerceAtLeast(1))
    return (if (opensAbove) top - size else bottom).coerceIn(margin, (end - size).coerceAtLeast(margin)) to size
}

internal fun dismissMenuDropdownOutside(dropdown: ParentUIComponent, event: MouseButtonEvent): Boolean {
    if (dropdown.parent() == null || dropdown.isInBoundingBox(event.x(), event.y()) ||
        dropdown.children().any { it.isInBoundingBox(event.x(), event.y()) }) return false
    if (dropdown is MenuDropdown) dropdown.beginClose() else dropdown.parent()?.removeChild(dropdown)
    return true
}

/** Apply one keyboard step through the native public value setter, independent of Minecraft's edit-mode flag. */
internal fun adjustMenuSliderKey(input: KeyEvent, active: Boolean, focused: Boolean, current: Double, step: Double,
                                 apply: (Double) -> Unit): Boolean {
    if (!active || !focused || input.hasAltDown() || input.hasControlDown() || !current.isFinite() || !step.isFinite() || step <= 0) return false
    val direction = when (input.key()) { GLFW.GLFW_KEY_LEFT -> -1; GLFW.GLFW_KEY_RIGHT -> 1; else -> return false }
    val next = (current + direction * step).coerceIn(0.0, 1.0)
    if (next != current) apply(next)
    return true
}

class MenuSlider : SliderComponent(Sizing.fill()), GreedyInputUIComponent {
    private val hoverMotion = MotionValue(0.0)
    private val pressMotion = MotionValue(0.0)
    private val valueMotion = MotionValue(0.0)
    private val thumbMotion = MotionValue(0.0)
    private var thumbInitialized = false
    private var dragging = false
    init {
        focusLost().subscribe { releaseDrag() }
        onChanged().subscribe {
            if (thumbInitialized) {
                valueMotion.snap(if (ThemeConfig.animationMode == GuiMotion.OFF) 0.0 else 1.0)
                valueMotion.animateTo(0.0, 180, MotionEasing.QUICK, ThemeConfig.animationMode)
            }
        }
    }
    override fun onMouseDown(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (active && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true
            pressMotion.animateTo(1.0, 70, MotionEasing.QUICK, ThemeConfig.animationMode)
        }
        return super.onMouseDown(click, doubled)
    }
    override fun onMouseUp(click: MouseButtonEvent): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) releaseDrag()
        return super.onMouseUp(click)
    }
    // Let the enclosing menu scroll even after this slider gained focus. Click and drag
    // retain their native handlers; keyboard changes follow the logical focus below.
    override fun onMouseScroll(mouseX: Double, mouseY: Double, amount: Double): Boolean = false
    override fun onKeyPress(input: KeyEvent): Boolean {
        if (input.key() == GLFW.GLFW_KEY_LEFT || input.key() == GLFW.GLFW_KEY_RIGHT)
            return adjustMenuSliderKey(input, active, focusHandler()?.focused() === this, value(), scrollStep()) { value(it) }
        return super.onKeyPress(input)
    }
    private fun releaseDrag() {
        dragging = false
        pressMotion.animateTo(0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
    }
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        if (isHovered && !menuHoverAllowed(this, mouseX, mouseY)) isHovered = false
        if (!active && dragging) releaseDrag()
        if (!thumbInitialized || dragging) { thumbMotion.snap(value()); thumbInitialized = true }
        else thumbMotion.animateTo(value(), 90, MotionEasing.QUICK, ThemeConfig.animationMode)
        hoverMotion.animateTo(if (active && (isHovered || isFocused)) 1.0 else 0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
        if (ThemeConfig.animationMode == GuiMotion.OFF) { pressMotion.snap(0.0); valueMotion.snap(0.0) }
        val hover = hoverMotion.sample().coerceIn(0.0, 1.0)
        val press = pressMotion.sample().coerceIn(0.0, 1.0)
        val changed = valueMotion.sample().coerceIn(0.0, 1.0)
        val center = y + height / 2
        MenuTheme.rounded(graphics, x, center - 3, width, 6, MenuTheme.blend(MenuTheme.PANEL, MenuTheme.BORDER, 45), 3)
        MenuTheme.rounded(graphics, x, center - 2, width, 4, MenuTheme.BORDER, 2)
        // Pointer tracking stays immediate; keyboard and programmatic changes settle visually.
        val thumb = x + 4 + thumbMotion.sample().coerceIn(0.0, 1.0) * (width - 8).coerceAtLeast(0)
        MenuTheme.rounded(graphics, x, center - 2, (thumb - x).toInt(), 4,
            MenuTheme.blend(MenuTheme.ACCENT, MenuTheme.SECONDARY, ((hover + changed) * 25).toInt()), 2)
        val halo = ((hover * .5 + changed * .5) * MenuTheme.style.glow * 12).toInt()
        val thumbPixel = thumb.toInt()
        graphics.layer(x = thumb - thumbPixel) {
            if (me.mycellium.skymyce.ui.AutoGraphics.quality.glow && halo > 0)
                graphics.drawCircle(thumbPixel, center, 24, 7.0, Color.ofArgb(MenuTheme.alpha(MenuTheme.ACCENT, halo)))
            graphics.drawCircle(thumbPixel, center + 1, 24, 4.5 + hover * .7, Color.ofArgb(MenuTheme.alpha(MenuTheme.style.shadowTint, 20)))
            graphics.drawCircle(thumbPixel, center, 24, 3.5 + hover * .7 + press * .4,
                Color.ofRgb(if (active) MenuTheme.TEXT else MenuTheme.MUTED))
        }
        if (isFocused) MenuTheme.focus(graphics, x, y, width, height)
    }
}
