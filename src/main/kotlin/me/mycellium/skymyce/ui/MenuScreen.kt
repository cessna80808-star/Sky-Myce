package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.misc.ThemeConfig
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
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.PreeditEvent
import org.lwjgl.glfw.GLFW

/** Menu density is independent of Minecraft's GUI scale. */
abstract class MenuScreen(title: Component, private val page: MenuPage? = null) : BaseOwoScreen<FlowLayout>(title) {
    protected open val modal = false
    protected val menuLayout get() = MenuLayout.fit(width, height).let {
        if (modal) it.copy(width = minOf(it.width, 560), height = minOf(it.height, 280), sidebar = 0) else it
    }
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
    private var closingDropdown: MenuDropdown? = null
    private var content: FlowLayout? = null
    private var constructing = false
    private val opening = MotionValue(0.0)
    private val sidebarOpening = MotionValue(0.0)
    private val pageOpening = MotionValue(0.0)
    private var pageDirection = 1
    private var outgoing: MenuCanvas.Snapshot? = null
    private var outgoingSize: Pair<Int, Int>? = null
    private var closing = false
    protected val menuClosing get() = closing
    private var closeDestination: net.minecraft.client.gui.screens.Screen? = null
    private var paintedShell = MotionPose()
    private var paintedContent = MotionPose()
    private val tooltip = MenuTooltipMotion()
    private val toasts = MenuToasts()
    private var ambientStep = Long.MIN_VALUE
    private var ambientColor = 0

    /** Capture the visible composition before replacing children, including interrupted transitions. */
    protected fun contentChanged(direction: Int = 1) {
        if (constructing || content == null || closing) return
        outgoing = canvas.snapshot("content")
        pageDirection = if (direction < 0) -1 else 1
        pageOpening.snap(0.0)
        pageOpening.animateTo(1.0, 220, MotionEasing.QUICK, ThemeConfig.animationMode)
        tooltip.clear()
    }
    /** Preserve shell, sidebar, scroll/focus infrastructure and only rebuild the feature body. */
    protected fun rebuildMenu(direction: Int = 1) {
        val body = content ?: return
        contentChanged(direction)
        closeMenuDropdown()
        constructing = true
        try { body.clearChildren(); buildContent(body); uiAdapter.inflateAndMount() }
        finally { constructing = false }
    }
    protected fun showMenuToast(message: String, kind: MenuToastKind = MenuToastKind.INFO) = toasts.show(message, kind)
    protected fun closeMenuTo(destination: net.minecraft.client.gui.screens.Screen?) {
        if (closing) return
        if (invalid || ThemeConfig.animationMode == GuiMotion.OFF) { navigateMenu(destination); return }
        closing = true; closeDestination = destination
        closeMenuDropdown()
        opening.animateTo(0.0, 145, MotionEasing.SMOOTH, ThemeConfig.animationMode)
        sidebarOpening.animateTo(0.0, 90, mode = ThemeConfig.animationMode)
        pageOpening.animateTo(0.0, 110, mode = ThemeConfig.animationMode)
    }
    override fun onClose() = closeMenuTo(menuParent)

    internal fun prepareNavigation(destination: MenuScreen) {
        destination.outgoing = canvas.snapshot("content")
        destination.outgoingSize = width to height
        destination.pageDirection = if (page != null && destination.page != null && destination.page.ordinal < page.ordinal) -1 else 1
        // A normal page swap keeps the shell visible. A confirmation has a separate modal hierarchy.
        destination.opening.snap(if (modal || destination.modal) 0.0 else 1.0)
        destination.sidebarOpening.snap(if (modal || destination.modal) 0.0 else 1.0)
        destination.pageOpening.snap(0.0)
    }

    protected fun openMenuDropdown(anchor: ButtonComponent, options: (DropdownComponent) -> Unit): DropdownComponent {
        closeMenuDropdown()
        // Anchor geometry is retained, so settle its content before opening an interactive popup.
        opening.snap(1.0); sidebarOpening.snap(1.0); pageOpening.snap(1.0); outgoing = null
        paintedShell = shellPose(); paintedContent = contentPose()
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
        popup.beginClose()
        closingDropdown = popup
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
        closing = false; closeDestination = null
        if (outgoingSize != null && outgoingSize != (width to height)) outgoing = null
        outgoingSize = null
        opening.animateTo(1.0, 245, MotionEasing.SETTLE, ThemeConfig.animationMode)
        sidebarOpening.animateTo(1.0, 200, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis = 25)
        pageOpening.animateTo(1.0, 230, MotionEasing.QUICK, ThemeConfig.animationMode, delayMillis = if (opening.sample() < 1.0) 45 else 0)
        super.init()
        if (!invalid && (builtLayout != menuLayout || builtTheme != me.mycellium.skymyce.hud.HudTheme.revision)) {
            uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount()
        }
    }
    final override fun build(root: FlowLayout) {
        closeMenuDropdown()
        if (builtLayout != null && builtLayout != menuLayout) { outgoing = null; pageOpening.snap(1.0) }
        builtLayout = menuLayout
        builtTheme = me.mycellium.skymyce.hud.HudTheme.revision
        constructing = true
        root.surface(Surface { g, c -> (g as MenuGraphics).layer(alpha = opening.sample().coerceIn(0.0, 1.0)) {
            MenuTheme.backdrop.draw(g, c)
        } }).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        val frame = MenuLayer(Sizing.fixed(menuLayout.width), Sizing.fixed(menuLayout.height), FlowLayout.Algorithm.HORIZONTAL, "shell", ::shellPose).apply {
            surface(Surface { g, c -> MenuTheme.panel().draw(g, c); drawAmbient(g as MenuGraphics, c) })
        }
        if (!modal) {
            val side = menuSidebar(menuLayout, page, settingsCategory, ::selectSettings,
                if (settingsCategory == null) this else menuParent, { settingsCategory })
            val sideLayer = MenuLayer(Sizing.fixed(menuLayout.sidebar), Sizing.fixed(menuLayout.height), FlowLayout.Algorithm.VERTICAL, "sidebar", presentation = {
                MotionPose(alpha = sidebarOpening.sample().coerceIn(0.0, 1.0))
            })
            sideLayer.child(side); frame.child(sideLayer)
        }
        val body = MenuLayer(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight), FlowLayout.Algorithm.VERTICAL, "content", ::contentPose, ::drawOutgoing)
        content = body
        if (menuHeight > menuLayout.height) frame.child(menuScroll(Sizing.fixed(menuWidth), Sizing.fill(), body).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
        }) else frame.child(body)
        root.child(frame)
        try { buildContent(body) } finally { constructing = false }
    }
    protected abstract fun buildContent(root: FlowLayout)

    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (invalid) { onClose(); return }
        val mode = ThemeConfig.animationMode
        opening.animateTo(if (closing) 0.0 else 1.0, if (closing) 145 else 245, MotionEasing.SETTLE, mode)
        sidebarOpening.animateTo(if (closing) 0.0 else 1.0, 200, mode = mode)
        pageOpening.animateTo(if (closing) 0.0 else 1.0, 230, mode = mode)
        if (closing && opening.sample() == 0.0 && !opening.running) {
            if (MC.screen === this) navigateMenu(closeDestination)
            return
        }
        canvas.begin()
        clearDetachedFocus()
        paintedShell = shellPose(); paintedContent = contentPose()
        val point = pointer(mouseX.toDouble(), mouseY.toDouble())
        val mx = point.first.toInt(); val my = point.second.toInt()
        tooltip.beginFrame()
        val custom = MenuGraphics(canvas, mx, my, width, height, tooltip)
        uiAdapter.extractRenderState(custom, mx, my, partialTicks)
        closingDropdown?.let { popup ->
            if (popup.finishedClosing) closingDropdown = null else popup.draw(custom, mx, my, partialTicks, 0f)
        }
        if (!closing) {
            canvas.group = "tooltip"
            uiAdapter.drawTooltip(custom, mx, my, partialTicks)
            tooltip.endFrame(canvas)
            canvas.group = null
            toasts.draw(custom, width, height)
        }
        val scale = minOf(ThemeConfig.graphicsQuality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        texture.render(g, canvas.finish(width, height, scale), viewport.screenWidth, viewport.screenHeight, canvas.revision, canvas.dirtyRegion)
    }

    private fun shellPose(): MotionPose {
        val progress = opening.sample()
        val movement = if (ThemeConfig.animationMode == GuiMotion.FULL) 1 - progress else 0.0
        return MotionPose(progress.coerceIn(0.0, 1.0), 1 - movement * .024, y = movement * 8,
            pivotX = width / 2.0, pivotY = height / 2.0)
    }
    private fun contentPose(): MotionPose {
        val progress = pageOpening.sample().coerceIn(0.0, 1.0)
        val offset = if (ThemeConfig.animationMode == GuiMotion.FULL) (1 - progress) * 18 * pageDirection else 0.0
        return MotionPose(alpha = progress, x = offset)
    }
    private fun drawAmbient(g: MenuGraphics, component: UIComponent) {
        val quality = ThemeConfig.graphicsQuality
        if (!quality.ambient || ThemeConfig.animationMode != GuiMotion.FULL || closing) return
        val step = System.nanoTime() / quality.ambientIntervalNanos
        if (ambientStep != step) {
            ambientStep = step
            ambientColor = MenuTheme.alpha(MenuTheme.SECONDARY, (12 + 6 * kotlin.math.sin(step * .08)).toInt())
        }
        // Only a small decorative corner changes; never invalidate the entire cached backdrop.
        val count = if (quality == GuiQuality.HIGH) 5 else 3
        for (i in 0 until count) {
            g.drawCircle(component.x() + component.width() - 16 - i * 11, component.y() + 9,
                12, 1.3 + (i % 2) * .4, Color.ofArgb(ambientColor))
        }
    }
    /** Called by the content layer before its new children. Both pages remain clipped to the body. */
    private fun drawOutgoing(g: MenuGraphics) {
        val body = content ?: return
        val progress = pageOpening.sample().coerceIn(0.0, 1.0)
        if (progress >= 1.0 || outgoing == null) { outgoing = null; return }
        val oldGroup = canvas.group
        canvas.group = "content"
        val offset = if (ThemeConfig.animationMode == GuiMotion.FULL) -progress * 18 * pageDirection else 0.0
        canvas.replay(outgoing!!, 1 - progress, offset, boundary = MenuCanvas.Clip(body.x(), body.y(), body.width(), minOf(body.height(), menuLayout.height)))
        canvas.group = oldGroup
    }
    private fun pointer(x: Double, y: Double): Pair<Double, Double> {
        val sx = paintedShell.inverseX(viewport.x(x)); val sy = paintedShell.inverseY(viewport.y(y))
        if (dropdown?.isInBoundingBox(sx, sy) == true) return sx to sy
        val body = content ?: return sx to sy
        val bx = paintedContent.inverseX(sx); val by = paintedContent.inverseY(sy)
        if (body.isInBoundingBox(bx, by)) return bx to by
        // The strip vacated by moving content is paint-only; it must not activate retained controls.
        return if (body.isInBoundingBox(sx, sy)) -1.0 to -1.0 else sx to sy
    }
    private fun pointer(event: MouseButtonEvent): MouseButtonEvent {
        val point = pointer(event.x(), event.y())
        return MouseButtonEvent(point.first, point.second, event.buttonInfo())
    }
    private fun clearDetachedFocus() {
        val root = uiAdapter?.rootComponent ?: return
        val handler = root.focusHandler() ?: return
        val focused = handler.focused() ?: return
        if (!menuComponentAttached(root, focused)) handler.focus(null, UIComponent.FocusSource.KEYBOARD_CYCLE)
    }
    // The adapter tooltip is already painted into the same canvas above the content.
    final override fun drawComponentTooltip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}
    override fun removed() {
        dropdown?.parent()?.removeChild(dropdown); dropdown = null; closingDropdown = null; dropdownAnchor = null
        fun release(component: UIComponent) {
            if (component is MenuCollapsible) component.releaseMotion()
            if (component is ParentUIComponent) component.children().forEach(::release)
        }
        uiAdapter?.rootComponent?.let(::release)
        outgoing = null; opening.cancel(); sidebarOpening.cancel(); pageOpening.cancel(); tooltip.clear(); toasts.clear()
        closeDestination = null
        canvas.close(); texture.close(); super.removed()
    }
    override fun mouseMoved(x: Double, y: Double) { val p = pointer(x, y); super.mouseMoved(p.first, p.second) }
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (closing) return true
        clearDetachedFocus()
        val mapped = pointer(event)
        dropdown?.let { if (dismissMenuDropdownOutside(it, mapped)) closeMenuDropdown() }
        return super.mouseClicked(mapped, doubled)
    }
    override fun keyPressed(event: KeyEvent): Boolean {
        if (closing) return true
        clearDetachedFocus()
        if (event.isEscape && closeMenuDropdown()) return true
        dropdown?.let {
            if (event.key() in listOf(GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN)) {
                it.cycleFocus(event.key() == GLFW.GLFW_KEY_DOWN || event.key() == GLFW.GLFW_KEY_TAB && !event.hasShiftDown())
                return true
            }
        }
        return super.keyPressed(event)
    }
    final override fun charTyped(event: CharacterEvent): Boolean {
        if (closing) return true
        clearDetachedFocus(); return super.charTyped(event)
    }
    final override fun preeditUpdated(event: PreeditEvent?): Boolean {
        if (closing) return true
        clearDetachedFocus(); return super.preeditUpdated(event)
    }
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (closing) return true
        clearDetachedFocus(); return super.mouseReleased(pointer(event))
    }
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (closing) return true
        clearDetachedFocus()
        return super.mouseDragged(pointer(event), viewport.x(dx) / paintedShell.scale, viewport.y(dy) / paintedShell.scale)
    }
    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
        if (closing) return true
        val p = pointer(x, y); return super.mouseScrolled(p.first, p.second, horizontal, vertical)
    }

}

internal fun menuSidebar(layout: MenuLayout, page: MenuPage?, settingsCategory: SettingsTab? = null,
                         selectSettings: (SettingsTab) -> Unit = {}, parent: net.minecraft.client.gui.screens.Screen? = null,
                         currentSettings: () -> SettingsTab? = { settingsCategory }): FlowLayout =
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
            SettingsTab.entries.forEach { tab -> choices.child(nav(tab.title, { currentSettings() == tab }) { selectSettings(tab) }) }
        } else {
            MenuPage.entries.forEach { destination -> choices.child(nav(destination.title, { page == destination }) {
                if (page != destination) openMenu(destination)
            }) }
        }
        child(menuScroll(Sizing.fill(), Sizing.expand(), choices).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(1)
        })
        child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(1)).surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER))))
        child(nav(if (settingsCategory == null) "Settings" else "Back to menus", { false }) {
            if (settingsCategory == null) navigateMenu(SettingsScreen(parent))
            else navigateMenu(parent ?: DailyDigestScreen())
        })
        child(MenuLabel(Component.literal(me.mycellium.skymyce.SkyMyce.mod.metadata.version.friendlyString)).color(Color.ofRgb(MenuTheme.MUTED)))
    }
private fun nav(title: String, active: () -> Boolean, action: () -> Unit) = MenuButton(Component.literal(title)) { action() }.apply {
        sizing(Sizing.fill(), Sizing.fixed(27)); navigation = true; selected = active
        tooltip(Component.literal(title))
    }

fun openMenu(page: MenuPage = MenuPage.DIGEST) {
    navigateMenu(when (page) {
        MenuPage.DIGEST -> DailyDigestScreen()
        MenuPage.PARTY -> DungeonFriendsScreen()
        MenuPage.FRIENDS -> FriendsSocialScreen()
        MenuPage.DUNGEONS -> DungeonScreen()
        MenuPage.AUCTIONS -> AuctionHouseScreen()
        MenuPage.COSMETICS -> CosmeticsScreen()
        MenuPage.HUD -> WidgetEditorScreen(MC.screen)
    })
}

/** State/action changes happen immediately; only inert outgoing paint survives a navigation. */
fun navigateMenu(destination: net.minecraft.client.gui.screens.Screen?) {
    val previous = MC.screen
    if (previous === destination) return
    if (previous is MenuScreen && destination is MenuScreen) previous.prepareNavigation(destination)
    MC.instance.setScreen(destination)
}
