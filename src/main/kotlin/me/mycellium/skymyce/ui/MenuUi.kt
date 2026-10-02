package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.SliderComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.*
import io.wispforest.owo.ui.util.UISounds
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.ThemePreset
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
            val w = minOf(width - 16, (width * .76).toInt()).coerceIn(1, 1100)
            val h = minOf(height - 16, maxOf(260, (height * .78).toInt())).coerceIn(1, 650)
            return MenuLayout(w, h, (w * .19).toInt().coerceAtLeast(72).coerceAtMost(w / 3))
        }
    }
}

enum class MenuPage(val title: String) {
    DIGEST("Daily Digest"), PARTY("Party Finder"), FRIENDS("Friends & loans"),
    DUNGEONS("Dungeons"), AUCTIONS("Auctions"), COSMETICS("Cosmetics"), HUD("HUD editor")
}

object MenuTheme {
    private fun color(value: Int, preset: Int) = value.takeIf { it in 0..0xFFFFFF } ?: preset
    val ACCENT get() = color(ThemeConfig.primary, ThemeConfig.preset.palette.primary)
    val SECONDARY get() = color(ThemeConfig.secondary, ThemeConfig.preset.palette.secondary)
    val PANEL get() = color(ThemeConfig.panel, ThemeConfig.preset.palette.panel)
    val SIDEBAR get() = color(ThemeConfig.panel, if (ThemeConfig.preset == ThemePreset.DARK_GLASS) 0x110F19 else ThemeConfig.preset.palette.panel)
    val CARD get() = color(ThemeConfig.card, ThemeConfig.preset.palette.card)
    val BORDER get() = color(ThemeConfig.border, ThemeConfig.preset.palette.border)
    val TEXT get() = color(ThemeConfig.text, ThemeConfig.preset.palette.text)
    val MUTED get() = color(ThemeConfig.muted, ThemeConfig.preset.palette.muted)
    const val GREEN = 0x90CEAA
    const val YELLOW = 0xE8BF71
    const val RED = 0xF18C8C
    const val PURPLE = 0xC2A2F5
    const val PADDING = 8
    val SHADOW get() = ThemeConfig.graphicsQuality.shadowLayers > 0 && when (ThemeConfig.shadow) {
        0 -> false
        1 -> true
        else -> ThemeConfig.preset == ThemePreset.DARK_GLASS || ThemeConfig.preset.palette.shadow
    }
    val OPACITY get() = ThemeConfig.opacity.takeIf { it in 20..100 } ?: ThemeConfig.preset.palette.opacity
    val ROUNDED get() = when (ThemeConfig.rounded) { 0 -> false; 1 -> true; else -> ThemeConfig.preset.palette.rounded }
    val BORDERS get() = when (ThemeConfig.borders) { 0 -> false; 1 -> true; else -> ThemeConfig.preset.palette.borders }
    fun blend(a: Int, b: Int, weight: Int) = me.mycellium.skymyce.hud.HudTheme.blend(a, b, weight)
    private val font = FontDescription.Resource(Identifier.fromNamespaceAndPath("skymyce", "menu"))

    fun text(value: Component): Component = value.copy().withStyle { it.withFont(font) }
    fun alpha(rgb: Int, percent: Int = 100) = (percent.coerceIn(0, 100) * 255 / 100 shl 24) or (rgb and 0xFFFFFF)
    fun rounded(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, rgb: Int, radius: Int = 4) {
        if (w <= 0 || h <= 0) return
        val r = if (ROUNDED) minOf(radius, w / 2, h / 2) else 0
        val color = if (rgb ushr 24 == 0) alpha(rgb) else rgb
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
    /** A few nested silhouettes give depth without blur, extra textures or a post-processing pass. */
    fun shadow(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, strength: Double = 1.0) {
        if (!SHADOW || strength <= 0.0 || w <= 0 || h <= 0) return
        val layers = ThemeConfig.graphicsQuality.shadowLayers.coerceIn(0, 3)
        for (layer in layers downTo 1) {
            val opacity = (strength.coerceIn(0.0, 1.0) * (11 - layer * 2)).toInt()
            if (opacity > 0) rounded(g, x - layer, y + 1, w + layer * 2, h + layer * 2, alpha(0x06040C, opacity), 4 + layer)
        }
    }
    fun glow(g: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, strength: Double) {
        if (!ThemeConfig.graphicsQuality.glow || strength <= 0.0 || w <= 0 || h <= 0) return
        val opacity = (strength.coerceIn(0.0, 1.0) * 9).toInt()
        if (opacity > 0) rounded(g, x - 1, y - 1, w + 2, h + 2, alpha(ACCENT, opacity), 5)
    }
    fun panel(card: Boolean = false): Surface = Surface { g, c ->
        shadow(g, c.x(), c.y(), c.width(), c.height(), if (card) .35 else 1.0)
        box(g, c.x(), c.y(), c.width(), c.height(), if (card) CARD else PANEL)
        if (ThemeConfig.graphicsQuality != GuiQuality.EXTREMELY_LIGHT && c.width() > 10 && c.height() > 10)
            g.drawGradientRect(c.x() + 5, c.y() + 5, c.width() - 10, c.height() - 10,
                alpha(blend(if (card) CARD else PANEL, ACCENT, if (card) 4 else 8), OPACITY),
                alpha(if (card) CARD else PANEL, OPACITY), alpha(if (card) CARD else PANEL, OPACITY), alpha(if (card) CARD else PANEL, OPACITY))
    }
    fun tintedPanel(color: () -> Int): Surface = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), color()) }
    val backdrop = Surface { g, c -> g.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), 0xA50B0811.toInt()) }

    @JvmStatic fun drawField(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, focused: Boolean) {
        val graphics = g as MenuGraphics
        box(graphics, x, y, w, h, CARD, if (focused) ACCENT else BORDER)
        if (focused && !BORDERS) graphics.drawRectOutline(x, y, w, h, alpha(ACCENT))
    }
}

/** No vanilla button renderer or texture; inherit its tested input and narration. */
open class MenuButton(message: Component, action: (ButtonComponent) -> Unit) : ButtonComponent(message, action) {
    var selected: () -> Boolean = { false }
    var navigation = false
    var background = true
    var toggled: (() -> Boolean)? = null
    private val hoverMotion = MotionValue(0.0)
    private val selectionMotion = MotionValue(0.0)
    private val pressMotion = MotionValue(0.0)
    private val toggleMotion = MotionValue(0.0)
    private var toggleInitialized = false
    private var wrappedMessage: Component? = null
    private var wrappedWidth = -1
    private var wrappedNavigation = false
    private var wrappedLanguage: net.minecraft.locale.Language? = null
    private var lines = emptyList<net.minecraft.util.FormattedCharSequence>()
    private var lineWidths = IntArray(0)

    init {
        onPress { button ->
            pressMotion.snap(if (ThemeConfig.animationMode == GuiMotion.OFF) 0.0 else 1.0)
            pressMotion.animateTo(0.0, 160, MotionEasing.SETTLE, ThemeConfig.animationMode)
            action(button)
        }
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        hoverMotion.animateTo(if (active && (isHovered || isFocused)) 1.0 else 0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
        selectionMotion.animateTo(if (selected()) 1.0 else 0.0, 170, MotionEasing.SMOOTH, ThemeConfig.animationMode)
        if (ThemeConfig.animationMode == GuiMotion.OFF) pressMotion.snap(0.0)
        val hover = hoverMotion.sample().coerceIn(0.0, 1.0)
        val chosen = selectionMotion.sample().coerceIn(0.0, 1.0)
        val press = pressMotion.sample().coerceIn(0.0, 1.0)
        graphics.layer(scale = 1.0 - .025 * press, pivotX = x + width / 2.0, pivotY = y + height / 2.0) {
            paint(graphics, delta, hover, chosen)
        }
    }
    private fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double) {
        toggled?.let { state ->
            val on = state()
            val target = if (on) 1.0 else 0.0
            if (!toggleInitialized) { toggleMotion.snap(target); toggleInitialized = true }
            toggleMotion.animateTo(target, 180, MotionEasing.SMOOTH, ThemeConfig.animationMode)
            val toggleProgress = toggleMotion.sample().coerceIn(0.0, 1.0)
            val w = minOf(26, width - 4)
            val h = minOf(14, height - 4)
            if (w < 6 || h < 6) return
            val left = x + (width - w) / 2
            val top = y + (height - h) / 2
            MenuTheme.glow(graphics, left, top, w, h, hover * .7 + toggleProgress * .3)
            MenuTheme.rounded(graphics, left, top, w, h, MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (toggleProgress * 100).toInt()), h / 2)
            graphics.drawCircle(left + h / 2 + (toggleProgress * (w - h)).toInt(), top + h / 2, 24,
                (h / 2 - 2 + hover * .5), Color.ofRgb(MenuTheme.blend(MenuTheme.MUTED, MenuTheme.TEXT, (toggleProgress * 100).toInt())))
            if (isFocused && background) graphics.drawRectOutline(x, y, width, height, MenuTheme.alpha(MenuTheme.ACCENT))
            return
        }
        if (background && renderer() !== Renderer.VANILLA) {
            renderer().draw(graphics, this, delta)
        } else if (background && (!navigation || chosen > 0.0 || hover > 0.0)) {
            graphics.layer(alpha = if (navigation) maxOf(chosen, hover) else 1.0) {
                MenuTheme.shadow(graphics, x, y, width, height, if (navigation) hover * .25 else .3 + hover * .4)
                MenuTheme.glow(graphics, x, y, width, height, hover * .5)
                MenuTheme.box(graphics, x, y, width, height,
                    MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, (hover * 12 + chosen * 16).toInt()),
                    MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (maxOf(chosen, hover * .65) * 100).toInt()))
                if (navigation && chosen > 0.0) {
                    val indicatorHeight = ((height - 10) * chosen).toInt()
                    MenuTheme.rounded(graphics, x + 1, y + (height - indicatorHeight) / 2, 2, indicatorHeight, MenuTheme.ACCENT, 1)
                }
            }
            if (isFocused && !MenuTheme.BORDERS) graphics.drawRectOutline(x, y, width, height, MenuTheme.alpha(MenuTheme.ACCENT))
        }
        val available = (width - 12).coerceAtLeast(1)
        val language = net.minecraft.locale.Language.getInstance()
        if (message != wrappedMessage || available != wrappedWidth || navigation != wrappedNavigation || language !== wrappedLanguage) {
            wrappedMessage = message.copy()
            wrappedWidth = available; wrappedNavigation = navigation
            wrappedLanguage = language
            lines = menuTextLines(wrappedMessage!!, available).take(if (navigation) 2 else 1)
            lineWidths = IntArray(lines.size) { kotlin.math.ceil(menuTextWidth(lines[it])).toInt() }
        }
        val lineHeight = MenuCanvas.lineHeight()
        graphics.enableScissor(x + 3, y + 1, x + width - 3, y + height - 1)
        lines.forEachIndexed { index, line ->
            val left = if (navigation) x + 8 else x + (width - lineWidths[index]) / 2
            graphics.text(MC.font, line, left, y + (height - lines.size * lineHeight) / 2 + index * lineHeight,
                MenuTheme.alpha(if (active) MenuTheme.TEXT else MenuTheme.MUTED), false)
        }
        graphics.disableScissor()
    }
    // Focus is painted with the button/card, so owo must not add a second rectangle after it.
    override fun drawFocusHighlight(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {}
    override fun shouldDrawTooltip(mouseX: Double, mouseY: Double) = visible && isInBoundingBox(mouseX, mouseY) && !tooltip().isNullOrEmpty()
}

open class MenuLabel(value: Component) : LabelComponent(MenuTheme.text(value)) {
    var fontSize = 10f
        set(value) { field = value; lineHeight(MenuCanvas.lineHeight(value.toDouble())) }
    init { lineHeight(MenuCanvas.lineHeight()) }
    override fun text(value: Component): LabelComponent = super.text(MenuTheme.text(value))
    private fun wrap() {
        wrappedText = menuTextLines(text(), if (horizontalSizing().get().isContent) maxWidth() else width(), fontSize.toDouble())
    }
    override fun determineHorizontalContentSize(sizing: Sizing): Int =
        kotlin.math.ceil(menuTextLines(text(), maxWidth(), fontSize.toDouble()).maxOfOrNull { menuTextWidth(it, fontSize.toDouble()) } ?: 0.0).toInt()
    override fun determineVerticalContentSize(sizing: Sizing): Int { wrap(); return textHeight() }
    override fun inflate(space: Size) { super.inflate(space); wrap() }
    // Owo's default drawing adds a Minecraft baseline offset. Canvas positions are top-of-line.
    override fun drawText(draw: LabelDrawFunction) {
        val left = x() + if (horizontalSizing().get().isContent) horizontalSizing().get().value else 0
        var top = y() + if (verticalSizing().get().isContent) verticalSizing().get().value else 0
        top += when (verticalTextAlignment()) {
            VerticalAlignment.CENTER -> (height() - textHeight()) / 2
            VerticalAlignment.BOTTOM -> height() - textHeight()
            else -> 0
        }
        wrappedText.forEachIndexed { index, line ->
            val offset = when (horizontalTextAlignment()) {
                HorizontalAlignment.CENTER -> (width() - kotlin.math.ceil(menuTextWidth(line, fontSize.toDouble())).toInt()) / 2
                HorizontalAlignment.RIGHT -> width() - kotlin.math.ceil(menuTextWidth(line, fontSize.toDouble())).toInt()
                else -> 0
            }
            draw.draw(left + offset, top + index * (lineHeight() + lineSpacing()), line, shadow(), color().get())
        }
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        drawText { x, y, text, _, color ->
            (g as MenuGraphics).styledText(text, x.toDouble(), y.toDouble(), color.argb(), fontSize.toDouble())
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
    private val appearance = MotionValue(0.0)
    private var opened = false
    var closing = false
        private set
    val finishedClosing get() = closing && appearance.sample() == 0.0 && !appearance.running
    init { entries.padding(Insets.of(5)); surface(MenuTheme.panel()); allowOverflow(true) }

    fun beginClose() {
        if (closing) return
        closing = true
        appearance.animateTo(0.0, 100, MotionEasing.QUICK, ThemeConfig.animationMode)
        children().filterIsInstance<MenuDropdown>().forEach { it.beginClose() }
    }
    private fun beginOpen() { closing = false; opened = false; appearance.snap(0.0) }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (!opened && !closing) {
            opened = true
            appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode)
        }
        appearance.animateTo(if (closing) 0.0 else 1.0, if (closing) 100 else 130, MotionEasing.QUICK, ThemeConfig.animationMode)
        (g as MenuGraphics).layer(alpha = appearance.sample().coerceIn(0.0, 1.0)) {
            super.draw(g, mouseX, mouseY, partialTicks, delta)
        }
        children().filterIsInstance<MenuDropdown>().filter { it.finishedClosing }.toList().forEach(::removeChild)
    }
    private fun closeNested() { children().filterIsInstance<MenuDropdown>().forEach { it.beginClose() } }

    private fun entry(text: Component, indicator: (() -> Boolean)? = null, nested: Boolean = false, action: () -> Unit): MenuDropdownEntry {
        entryWidth = maxOf(entryWidth, menuDropdownWidth(text, indicator != null || nested))
        entries.horizontalSizing(Sizing.fixed(entryWidth))
        return MenuDropdownEntry(text, indicator, nested, minOf(entries.children().size, 5) * 14L, action).apply {
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
            child.positioning(Positioning.absolute(entries.width() - 1, row.y() - y()))
            if (child.parent() !== this) child(child)
            val root = generateSequence(parent()) { it.parent() }.lastOrNull()
            root?.let {
                child.positioning(Positioning.absolute(
                    (child.x() - x()).coerceAtMost(it.x() + it.width() - x() - child.width()).coerceAtLeast(it.x() - x()),
                    (child.y() - y()).coerceAtMost(it.y() + it.height() - y() - child.height()).coerceAtLeast(it.y() - y())))
            }
        }
        row = entry(text, nested = true) { open(); child.focusFirst() }
        row.mouseEnter().subscribe { queue(open) }
        return this
    }
    fun focusFirst() {
        if (closing) return
        entries.children().firstOrNull { it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) }?.let {
            focusHandler()?.focus(it, UIComponent.FocusSource.KEYBOARD_CYCLE)
        }
    }
    override fun isInBoundingBox(x: Double, y: Double): Boolean = !closing && (super.isInBoundingBox(x, y) ||
        children().filterIsInstance<MenuDropdown>().any { it.isInBoundingBox(x, y) })
    fun cycleFocus(forward: Boolean) {
        if (closing) return
        val rows = ArrayList<UIComponent>()
        collectDescendants(rows)
        rows.removeAll { !it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) }
        if (rows.isEmpty()) return
        val current = rows.indexOf(focusHandler()?.focused())
        val next = Math.floorMod(current + if (forward) 1 else -1, rows.size)
        focusHandler()?.focus(rows[next], UIComponent.FocusSource.KEYBOARD_CYCLE)
    }
}

internal fun menuDropdownRowHeight() = MenuCanvas.lineHeight() + 8
internal fun menuDropdownWidth(text: Component, indicator: Boolean) =
    kotlin.math.ceil(menuTextLines(text, Int.MAX_VALUE).maxOfOrNull { menuTextWidth(it) } ?: 0.0).toInt() + 26 + if (indicator) 22 else 0

private class MenuDropdownEntry(value: Component, private val checked: (() -> Boolean)?, private val nested: Boolean, private val delayMillis: Long,
                                private val action: () -> Unit) : MenuLabel(value) {
    private val hoverMotion = MotionValue(0.0)
    private val checkMotion = MotionValue(if (checked?.invoke() == true) 1.0 else 0.0)
    private val appearance = MotionValue(0.0)
    private var appeared = false
    private val interactive get() = generateSequence(parent()) { it.parent() }.filterIsInstance<MenuDropdown>().none { it.closing }
    init { color(Color.ofRgb(MenuTheme.TEXT)); verticalTextAlignment(VerticalAlignment.CENTER); cursorStyle(CursorStyle.HAND) }
    override fun canFocus(source: UIComponent.FocusSource) = interactive
    override fun onMouseDown(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (!interactive || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        action(); return true
    }
    override fun onKeyPress(input: KeyEvent): Boolean {
        if (!interactive) return false
        if (input.key() !in listOf(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE) && !(nested && input.key() == GLFW.GLFW_KEY_RIGHT)) return super.onKeyPress(input)
        action(); return true
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (!appeared) {
            appeared = true
            appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis)
        } else appearance.animateTo(1.0, 130, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis)
        val hover = interactive && (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble()) || focusHandler()?.focused() === this)
        hoverMotion.animateTo(if (hover) 1.0 else 0.0, 100, MotionEasing.QUICK, ThemeConfig.animationMode)
        val emphasis = hoverMotion.sample().coerceIn(0.0, 1.0)
        (g as MenuGraphics).layer(alpha = appearance.sample().coerceIn(0.0, 1.0)) {
            if (emphasis > 0.0) g.layer(alpha = emphasis) {
                MenuTheme.rounded(g, x() - 3, y(), width() + 6, height(), MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 16))
            }
            super.draw(g, mouseX, mouseY, partialTicks, delta)
            checked?.let {
                checkMotion.animateTo(if (it()) 1.0 else 0.0, 170, MotionEasing.SMOOTH, ThemeConfig.animationMode)
                val progress = checkMotion.sample().coerceIn(0.0, 1.0)
                val left = x() + width() - 18; val top = y() + (height() - 10) / 2
                MenuTheme.rounded(g, left, top, 18, 10, MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (progress * 100).toInt()), 5)
                g.drawCircle(left + 5 + (progress * 8).toInt(), top + 5, 20, 3.0, Color.ofRgb(MenuTheme.TEXT))
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
internal fun mountMenuDropdown(root: FlowLayout, dropdown: ParentUIComponent, x: Int, y: Int) {
    dropdown.positioning(Positioning.absolute(0, 0))
    root.child(dropdown)
    dropdown.positioning(Positioning.absolute(
        (x - root.x()).coerceIn(0, (root.width() - dropdown.width()).coerceAtLeast(0)),
        (y - root.y()).coerceIn(0, (root.height() - dropdown.height()).coerceAtLeast(0))))
}

internal fun dismissMenuDropdownOutside(dropdown: ParentUIComponent, event: MouseButtonEvent): Boolean {
    if (dropdown.parent() == null || dropdown.isInBoundingBox(event.x(), event.y()) ||
        dropdown.children().any { it.isInBoundingBox(event.x(), event.y()) }) return false
    if (dropdown is MenuDropdown) dropdown.beginClose() else dropdown.parent()?.removeChild(dropdown)
    return true
}

class MenuSlider : SliderComponent(Sizing.fill()) {
    private val hoverMotion = MotionValue(0.0)
    private val pressMotion = MotionValue(0.0)
    private val valueMotion = MotionValue(0.0)
    init {
        mouseDown().subscribe { click, _ ->
            if (active && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
                pressMotion.animateTo(1.0, 70, MotionEasing.QUICK, ThemeConfig.animationMode)
            false
        }
        focusLost().subscribe { pressMotion.animateTo(0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode) }
        onChanged().subscribe {
            valueMotion.snap(if (ThemeConfig.animationMode == GuiMotion.OFF) 0.0 else 1.0)
            valueMotion.animateTo(0.0, 180, MotionEasing.SMOOTH, ThemeConfig.animationMode)
        }
    }
    override fun onMouseUp(click: MouseButtonEvent): Boolean {
        pressMotion.animateTo(0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
        return super.onMouseUp(click)
    }
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        hoverMotion.animateTo(if (active && (isHovered || isFocused)) 1.0 else 0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
        if (ThemeConfig.animationMode == GuiMotion.OFF) { pressMotion.snap(0.0); valueMotion.snap(0.0) }
        val hover = hoverMotion.sample().coerceIn(0.0, 1.0)
        val press = pressMotion.sample().coerceIn(0.0, 1.0)
        val changed = valueMotion.sample().coerceIn(0.0, 1.0)
        val center = y + height / 2
        MenuTheme.rounded(graphics, x, center - 2, width, 4, MenuTheme.BORDER, 2)
        // The logical value and thumb always follow native pointer/keyboard input immediately.
        val thumb = x + 4 + (value() * (width - 8)).toInt()
        MenuTheme.rounded(graphics, x, center - 2, thumb - x, 4,
            MenuTheme.blend(MenuTheme.ACCENT, MenuTheme.SECONDARY, ((hover + changed) * 25).toInt()), 2)
        val halo = ((hover * .5 + changed * .5) * 12).toInt()
        if (ThemeConfig.graphicsQuality.glow && halo > 0)
            graphics.drawCircle(thumb, center, 24, 7.0, Color.ofArgb(MenuTheme.alpha(MenuTheme.ACCENT, halo)))
        graphics.drawCircle(thumb, center, 24, 3.5 + hover * .7 + press * .4,
            Color.ofRgb(if (active) MenuTheme.TEXT else MenuTheme.MUTED))
        if (isFocused) graphics.drawRectOutline(x, y, width, height, MenuTheme.alpha(MenuTheme.ACCENT))
    }
}
