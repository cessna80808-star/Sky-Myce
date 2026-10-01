package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.SliderComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import io.wispforest.owo.ui.util.UISounds
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.ThemePreset
import me.mycellium.skymyce.features.digest.DailyDigestScreen
import me.mycellium.skymyce.features.general.AuctionHouseScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.FriendsSocialScreen
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.features.social.CosmeticsScreen
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
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
    private fun color(value: Int, dark: Int, preset: Int) = value.takeIf { it in 0..0xFFFFFF }
        ?: if (ThemeConfig.preset == ThemePreset.DARK_GLASS) dark else preset
    val ACCENT get() = color(ThemeConfig.primary, 0xDF8196, ThemeConfig.preset.palette.primary)
    val SECONDARY get() = color(ThemeConfig.secondary, 0xE9A5B4, ThemeConfig.preset.palette.secondary)
    val PANEL get() = color(ThemeConfig.panel, 0x201116, ThemeConfig.preset.palette.panel)
    val SIDEBAR get() = color(ThemeConfig.panel, 0x10090C, ThemeConfig.preset.palette.panel)
    val CARD get() = color(ThemeConfig.card, 0x29171D, ThemeConfig.preset.palette.card)
    val BORDER get() = color(ThemeConfig.border, 0x4A2B35, ThemeConfig.preset.palette.border)
    val TEXT get() = color(ThemeConfig.text, 0xF2E9EC, ThemeConfig.preset.palette.text)
    val MUTED get() = color(ThemeConfig.muted, 0xAF929C, ThemeConfig.preset.palette.muted)
    const val GREEN = 0x90CEAA
    const val YELLOW = 0xE8BF71
    const val RED = 0xF18C8C
    const val PURPLE = 0xC2A2F5
    const val PADDING = 8
    const val SHADOW = false
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
    fun panel(card: Boolean = false): Surface = Surface { g, c ->
        box(g, c.x(), c.y(), c.width(), c.height(), if (card) CARD else PANEL)
        if (!card) g.drawGradientRect(c.x() + 5, c.y() + 5, c.width() - 10, c.height() - 10,
            alpha(blend(PANEL, ACCENT, 6), OPACITY), alpha(PANEL, OPACITY), alpha(PANEL, OPACITY), alpha(PANEL, OPACITY))
    }
    fun tintedPanel(color: () -> Int): Surface = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), color()) }
    val backdrop = Surface { g, c -> g.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), 0xA5090608.toInt()) }

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
    private var toggleProgress = -1.0
    private var lastPaint = System.nanoTime()
    private var wrappedMessage: Component? = null
    private var wrappedWidth = -1
    private var wrappedNavigation = false
    private var wrappedLanguage: net.minecraft.locale.Language? = null
    private var lines = emptyList<net.minecraft.util.FormattedCharSequence>()
    private var lineWidths = IntArray(0)

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        val hover = isHovered || isFocused
        toggled?.let { state ->
            val on = state()
            val elapsed = ((System.nanoTime() - lastPaint) / 1_000_000_000.0).coerceIn(0.0, .1)
            lastPaint = System.nanoTime()
            val target = if (on) 1.0 else 0.0
            if (toggleProgress < 0) toggleProgress = target
            toggleProgress += (target - toggleProgress) * minOf(1.0, elapsed * 18)
            if (kotlin.math.abs(target - toggleProgress) < .01) toggleProgress = target
            val w = minOf(26, width - 4)
            val h = minOf(14, height - 4)
            val left = x + (width - w) / 2
            val top = y + (height - h) / 2
            MenuTheme.rounded(graphics, left, top, w, h, MenuTheme.blend(MenuTheme.BORDER, MenuTheme.ACCENT, (toggleProgress * 100).toInt()), h / 2)
            graphics.drawCircle(left + h / 2 + (toggleProgress * (w - h)).toInt(), top + h / 2, 24,
                (h / 2 - 2).toDouble(), Color.ofRgb(if (on) MenuTheme.TEXT else MenuTheme.MUTED))
            if (isFocused && background) graphics.drawRectOutline(x, y, width, height, MenuTheme.alpha(MenuTheme.ACCENT))
            return
        }
        val chosen = selected()
        if (background && renderer() !== Renderer.VANILLA) {
            renderer().draw(graphics, this, delta)
        } else if (background && (!navigation || chosen || hover)) {
            MenuTheme.box(graphics, x, y, width, height,
                if (chosen || hover) MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 14) else MenuTheme.CARD,
                if (isFocused || chosen) MenuTheme.ACCENT else MenuTheme.BORDER)
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
        g.enableScissor(x + 3, y + 1, x + width - 3, y + height - 1)
        lines.forEachIndexed { index, line ->
            val left = if (navigation) x + 8 else x + (width - lineWidths[index]) / 2
            g.text(MC.font, line, left, y + (height - lines.size * lineHeight) / 2 + index * lineHeight,
                MenuTheme.alpha(if (active) MenuTheme.TEXT else MenuTheme.MUTED), false)
        }
        g.disableScissor()
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
    init { entries.padding(Insets.of(5)); surface(MenuTheme.panel()); allowOverflow(true) }

    private fun entry(text: Component, indicator: (() -> Boolean)? = null, nested: Boolean = false, action: () -> Unit): MenuDropdownEntry {
        entryWidth = maxOf(entryWidth, menuDropdownWidth(text, indicator != null || nested))
        entries.horizontalSizing(Sizing.fixed(entryWidth))
        return MenuDropdownEntry(text, indicator, nested, action).apply {
            sizing(Sizing.fill(), Sizing.fixed(menuDropdownRowHeight()))
            margins(Insets.horizontal(3))
            entries.child(this)
            mouseEnter().subscribe { queue { children().filterIsInstance<MenuDropdown>().toList().forEach(::removeChild) } }
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
            children().filterIsInstance<MenuDropdown>().toList().forEach(::removeChild)
            child.positioning(Positioning.absolute(entries.width() - 1, row.y() - y()))
            child(child)
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
        entries.children().firstOrNull { it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) }?.let {
            focusHandler()?.focus(it, UIComponent.FocusSource.KEYBOARD_CYCLE)
        }
    }
    override fun isInBoundingBox(x: Double, y: Double): Boolean = super.isInBoundingBox(x, y) ||
        children().filterIsInstance<MenuDropdown>().any { it.isInBoundingBox(x, y) }
    fun cycleFocus(forward: Boolean) {
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

private class MenuDropdownEntry(value: Component, private val checked: (() -> Boolean)?, private val nested: Boolean,
                                private val action: () -> Unit) : MenuLabel(value) {
    init { color(Color.ofRgb(MenuTheme.TEXT)); verticalTextAlignment(VerticalAlignment.CENTER); cursorStyle(CursorStyle.HAND) }
    override fun canFocus(source: UIComponent.FocusSource) = true
    override fun onMouseDown(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        action(); return true
    }
    override fun onKeyPress(input: KeyEvent): Boolean {
        if (input.key() !in listOf(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE) && !(nested && input.key() == GLFW.GLFW_KEY_RIGHT)) return super.onKeyPress(input)
        action(); return true
    }
    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble()) || focusHandler()?.focused() === this)
            MenuTheme.rounded(g, x() - 3, y(), width() + 6, height(), MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 14))
        super.draw(g, mouseX, mouseY, partialTicks, delta)
        checked?.let {
            val left = x() + width() - 18; val top = y() + (height() - 10) / 2
            MenuTheme.rounded(g, left, top, 18, 10, if (it()) MenuTheme.ACCENT else MenuTheme.BORDER, 5)
            g.drawCircle(left + if (it()) 13 else 5, top + 5, 20, 3.0, Color.ofRgb(MenuTheme.TEXT))
        }
        if (nested) {
            val left = x() + width() - 5; val top = y() + height() / 2
            for (i in 0..3) g.fill(left + i, top - 3 + i, left + i + 1, top + 4 - i, MenuTheme.alpha(MenuTheme.MUTED))
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
    dropdown.parent()?.removeChild(dropdown)
    return true
}

class MenuSlider : SliderComponent(Sizing.fill()) {
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val graphics = g as MenuGraphics
        val center = y + height / 2
        MenuTheme.rounded(graphics, x, center - 2, width, 4, MenuTheme.BORDER, 2)
        val thumb = x + 4 + (value() * (width - 8)).toInt()
        MenuTheme.rounded(graphics, x, center - 2, thumb - x, 4, MenuTheme.ACCENT, 2)
        graphics.drawCircle(thumb, center, 24, 3.5, Color.ofRgb(MenuTheme.TEXT))
        if (isFocused) graphics.drawRectOutline(x, y, width, height, MenuTheme.alpha(MenuTheme.ACCENT))
    }
}

abstract class MenuScreen(title: Component, private val page: MenuPage? = null) : BaseOwoScreen<FlowLayout>(title) {
    protected val menuLayout get() = MenuLayout.fit(width, height)
    protected val menuWidth get() = menuLayout.contentWidth
    protected val menuHeight get() = menuLayout.height.coerceAtLeast(260)
    protected open val settingsCategory: SettingsTab? get() = null
    protected open fun selectSettings(tab: SettingsTab) {}
    protected open val menuParent: net.minecraft.client.gui.screens.Screen? get() = null
    private var builtLayout: MenuLayout? = null
    private var builtTheme = -1L
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    private lateinit var viewport: MenuViewport
    private var dropdown: MenuDropdown? = null
    private var dropdownAnchor: ButtonComponent? = null

    protected fun openMenuDropdown(anchor: ButtonComponent, options: (DropdownComponent) -> Unit): DropdownComponent {
        closeMenuDropdown()
        val popup = MenuDropdown(anchor.width, ::closeMenuDropdown)
        options(popup)
        dropdown = popup; dropdownAnchor = anchor
        mountMenuDropdown(uiAdapter.rootComponent, popup, anchor.x, anchor.y + anchor.height)
        popup.focusFirst()
        return popup
    }
    protected fun closeMenuDropdown(): Boolean {
        val popup = dropdown ?: return false
        dropdown = null
        popup.parent()?.removeChild(popup)
        uiAdapter?.rootComponent?.focusHandler()?.focus(dropdownAnchor?.takeIf { it.parent() != null }, UIComponent.FocusSource.KEYBOARD_CYCLE)
        dropdownAnchor = null
        return true
    }
    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)
    override fun isPauseScreen() = false
    override fun init() {
        val window = MC.instance.window
        viewport = MenuViewport.fit(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        width = viewport.width; height = viewport.height
        super.init()
        if (!invalid && (builtLayout != menuLayout || builtTheme != me.mycellium.skymyce.hud.HudTheme.revision)) {
            uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount()
        }
    }
    final override fun build(root: FlowLayout) {
        closeMenuDropdown()
        builtLayout = menuLayout
        builtTheme = me.mycellium.skymyce.hud.HudTheme.revision
        root.surface(MenuTheme.backdrop).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        val frame = UIContainers.horizontalFlow(Sizing.fixed(menuLayout.width), Sizing.fixed(menuLayout.height)).apply {
            surface(MenuTheme.panel())
        }
        frame.child(menuSidebar(menuLayout, page, settingsCategory, ::selectSettings, if (settingsCategory == null) this else menuParent))
        val content = UIContainers.verticalFlow(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight))
        if (menuHeight > menuLayout.height) frame.child(UIContainers.verticalScroll(Sizing.fixed(menuWidth), Sizing.fill(), content).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
        }) else frame.child(content)
        root.child(frame)
        buildContent(content)
    }
    protected abstract fun buildContent(root: FlowLayout)

    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (invalid) { onClose(); return }
        canvas.begin()
        val mx = viewport.x(mouseX.toDouble()).toInt(); val my = viewport.y(mouseY.toDouble()).toInt()
        val custom = MenuGraphics(canvas, mx, my, width, height)
        uiAdapter.extractRenderState(custom, mx, my, partialTicks)
        uiAdapter.drawTooltip(custom, mx, my, partialTicks)
        val scale = minOf(2.0, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        texture.render(g, canvas.finish(width, height, scale), viewport.screenWidth, viewport.screenHeight, canvas.revision, canvas.dirtyRegion)
    }
    // The adapter tooltip is already painted into the same canvas above the content.
    final override fun drawComponentTooltip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}
    override fun removed() { closeMenuDropdown(); texture.close(); super.removed() }
    override fun mouseMoved(x: Double, y: Double) = super.mouseMoved(viewport.x(x), viewport.y(y))
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val mapped = viewport.mouse(event)
        dropdown?.let { if (dismissMenuDropdownOutside(it, mapped)) closeMenuDropdown() }
        return super.mouseClicked(mapped, doubled)
    }
    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isEscape && closeMenuDropdown()) return true
        dropdown?.let {
            if (event.key() in listOf(GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN)) {
                it.cycleFocus(event.key() == GLFW.GLFW_KEY_DOWN || event.key() == GLFW.GLFW_KEY_TAB && !event.hasShiftDown())
                return true
            }
        }
        return super.keyPressed(event)
    }
    override fun mouseReleased(event: MouseButtonEvent) = super.mouseReleased(viewport.mouse(event))
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double) = super.mouseDragged(viewport.mouse(event), viewport.x(dx), viewport.y(dy))
    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double) = super.mouseScrolled(viewport.x(x), viewport.y(y), horizontal, vertical)

}

internal fun menuSidebar(layout: MenuLayout, page: MenuPage?, settingsCategory: SettingsTab? = null,
                         selectSettings: (SettingsTab) -> Unit = {}, parent: net.minecraft.client.gui.screens.Screen? = null): FlowLayout =
    UIContainers.verticalFlow(Sizing.fixed(layout.sidebar), Sizing.fixed(layout.height)).apply {
        padding(Insets.of(7)); gap(6)
        surface(Surface { g, c ->
            MenuTheme.rounded(g, c.x() + 1, c.y() + 1, c.width() - 1, c.height() - 2, MenuTheme.SIDEBAR)
            g.fill(c.x() + c.width() - 1, c.y() + 1, c.x() + c.width(), c.y() + c.height() - 1, MenuTheme.alpha(MenuTheme.BORDER))
        })
        child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(7); verticalAlignment(VerticalAlignment.CENTER); margins(Insets.vertical(9))
            child(MenuLabel(Component.literal("SKY-MYCE")).color(Color.ofRgb(MenuTheme.TEXT)).horizontalSizing(Sizing.expand()))
        })
        child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(1)).surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER))))
        val choices = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(3)
        if (settingsCategory != null) {
            SettingsTab.entries.forEach { tab -> choices.child(nav(tab.title, { settingsCategory == tab }) { selectSettings(tab) }) }
        } else {
            MenuPage.entries.forEach { destination -> choices.child(nav(destination.title, { page == destination }) {
                if (page != destination) openMenu(destination)
            }) }
        }
        child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), choices).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(1)
        })
        child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(1)).surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER))))
        child(nav(if (settingsCategory == null) "Settings" else "Back to menus", { false }) {
            if (settingsCategory == null) MC.instance.setScreen(SettingsScreen(parent))
            else MC.instance.setScreen(parent ?: DailyDigestScreen())
        })
        child(MenuLabel(Component.literal(me.mycellium.skymyce.SkyMyce.mod.metadata.version.friendlyString)).color(Color.ofRgb(MenuTheme.MUTED)))
    }
private fun nav(title: String, active: () -> Boolean, action: () -> Unit) = MenuButton(Component.literal(title)) { action() }.apply {
        sizing(Sizing.fill(), Sizing.fixed(27)); navigation = true; selected = active
        tooltip(Component.literal(title))
    }

fun openMenu(page: MenuPage = MenuPage.DIGEST) {
    MC.instance.setScreen(when (page) {
        MenuPage.DIGEST -> DailyDigestScreen()
        MenuPage.PARTY -> DungeonFriendsScreen()
        MenuPage.FRIENDS -> FriendsSocialScreen()
        MenuPage.DUNGEONS -> DungeonScreen()
        MenuPage.AUCTIONS -> AuctionHouseScreen()
        MenuPage.COSMETICS -> CosmeticsScreen()
        MenuPage.HUD -> WidgetEditorScreen(MC.screen)
    })
}
