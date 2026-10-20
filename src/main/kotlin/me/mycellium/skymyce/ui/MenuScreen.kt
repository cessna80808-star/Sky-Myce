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
import me.mycellium.skymyce.features.dev.RuntimeProfiler
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
/** Whether the vanilla world blur may run behind Sky-Myce menus at this quality. */
internal fun menuBlurAllowed(quality: GuiQuality) = quality != GuiQuality.EXTREMELY_LIGHT && quality != GuiQuality.LIGHT

abstract class MenuScreen(title: Component, private val page: MenuPage? = null) : BaseOwoScreen<FlowLayout>(title) {
    protected open val modal = false
    protected open val home = false
    internal open val canonicalFeatureId get() = page?.featureId
    protected open val menuCategory: MenuCategory? get() = canonicalFeatureId?.let { id -> MenuCatalog.definitions.firstOrNull { it.id == id }?.category } ?: MenuCategory.SETTINGS
    protected open val menuLayout get() = MenuLayout.fit(width, height).let {
        if (modal) it.copy(width = minOf(it.width, 560), height = minOf(it.height, 280), sidebar = 0) else it
    }
    protected val menuWidth get() = menuLayout.contentWidth
    protected val menuHeight get() = menuLayout.height.coerceAtLeast(260)
    protected open val settingsCategory: SettingsTab? get() = null
    protected open fun selectSettings(tab: SettingsTab) {}
    protected open val menuParent: net.minecraft.client.gui.screens.Screen?
        get() = if (modal) null else menuBackScreen(menuBackTarget(menuCategory, home = home, openedFromHome = openedFromHome))
    private var openedFromHome = false
    private var builtLayout: MenuLayout? = null
    private var builtTheme = -1L
    private var canvas = MenuCanvas()
    private var texture = MenuTexture()
    private lateinit var viewport: MenuViewport
    private var dropdown: MenuDropdown? = null
    private var dropdownAnchor: ButtonComponent? = null
    private var closingDropdown: MenuDropdown? = null
    private var content: FlowLayout? = null
    private var sidebar: FlowLayout? = null
    private var navigationSelection = MotionValue(0.0)
    private var introductionClaimed = false
    private var introductionChecked = false
    private var constructing = false
    private var opening = MotionValue(0.0)
    private var sidebarOpening = MotionValue(0.0)
    private val pageOpening = MotionValue(0.0)
    private var pageDirection = 1
    private var outgoing: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    private var outgoingSize: Pair<Int, Int>? = null
    private var modalUnderlay: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    private var closingContent: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    private var closingShellAlpha = 1.0
    private val contentExit = MotionValue(1.0)
    private var navigationMotion = emptyMap<Int, MenuButton>()
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
        try { releaseContentMotion(body); body.clearChildren(); buildContent(body); uiAdapter.inflateAndMount() }
        finally { constructing = false }
    }
    protected fun showMenuToast(message: String, kind: MenuToastKind = MenuToastKind.INFO) = toasts.show(message, kind)
    protected fun closeMenuTo(destination: net.minecraft.client.gui.screens.Screen?) {
        if (closing) return
        if (!modal && destination is MenuScreen) { navigateMenu(destination); return }
        if (invalid || ThemeConfig.animationMode == GuiMotion.OFF) { navigateMenu(destination); return }
        closing = true; closeDestination = destination
        closingContent = canvas.snapshot("content")
        closingShellAlpha = paintedShell.alpha.coerceAtLeast(.001)
        contentExit.snap(1.0)
        contentExit.animateTo(0.0, 105, MotionEasing.QUICK, ThemeConfig.animationMode)
        closeMenuDropdown()
        opening.animateTo(0.0, 145, MotionEasing.SMOOTH, ThemeConfig.animationMode)
        sidebarOpening.animateTo(0.0, 90, mode = ThemeConfig.animationMode)
        pageOpening.animateTo(0.0, 110, mode = ThemeConfig.animationMode)
    }
    override fun onClose() = closeMenuTo(menuParent)

    internal fun prepareNavigation(destination: MenuScreen) {
        if (home && !destination.home) destination.openedFromHome = true
        destination.outgoing = if (modal || destination.modal) null else canvas.snapshot("content")
        destination.modalUnderlay = if (destination.modal) modalUnderlay?.retain() ?: canvas.snapshot() else null
        destination.outgoingSize = width to height
        destination.pageDirection = if ((destination.menuCategory?.ordinal ?: -1) < (menuCategory?.ordinal ?: -1)) -1 else 1
        if (!modal && !destination.modal) {
            // Keep the shell's current pose and deadline, even when navigation interrupts its entrance.
            val shell = destination.opening; destination.opening = opening; opening = shell
            val side = destination.sidebarOpening; destination.sidebarOpening = sidebarOpening; sidebarOpening = side
            val indicator = destination.navigationSelection
            destination.navigationSelection = navigationSelection; navigationSelection = indicator
            destination.introductionChecked = true
            destination.introductionClaimed = introductionClaimed; introductionClaimed = false
            // Stable category identities retain feedback while only the page content transitions.
            destination.navigationMotion = navigationButtons().withIndex().associate { it.index to it.value }
        } else {
            destination.opening.snap(if (destination.modal) 0.0 else 1.0)
            destination.sidebarOpening.snap(if (destination.modal) 0.0 else 1.0)
        }
        destination.pageOpening.snap(if (modal && !destination.modal) 1.0 else 0.0)
        // The previous frame's raster/texture remain valid until the next page paints into them.
        // Removal closes only the destination's empty resources exchanged back to this screen.
        destination.canvas.close(); destination.texture.close()
        val nextCanvas = destination.canvas; destination.canvas = canvas; canvas = nextCanvas
        val nextTexture = destination.texture; destination.texture = texture; texture = nextTexture
    }

    private fun navigationButtons(): List<MenuButton> {
        val result = ArrayList<MenuButton>()
        fun visit(component: UIComponent) {
            if (component is MenuButton && component.navigation) result += component
            if (component is ParentUIComponent) component.children().forEach(::visit)
        }
        sidebar?.let(::visit)
        return result
    }

    protected fun openMenuDropdown(anchor: ButtonComponent, options: (DropdownComponent) -> Unit): DropdownComponent {
        closeMenuDropdown()
        // Anchor geometry is retained, so settle its content before opening an interactive popup.
        opening.snap(1.0); sidebarOpening.snap(1.0); pageOpening.snap(1.0); outgoing = null
        paintedShell = shellPose(); paintedContent = contentPose()
        val popup = MenuDropdown(anchor.width, ::closeMenuDropdown)
        options(popup)
        dropdown = popup; dropdownAnchor = anchor
        mountMenuDropdown(uiAdapter.rootComponent, popup, anchor.x, anchor.y + anchor.height, anchor.height)
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
    /** The world blur behind menus is a full-screen pass every frame; Light and Extremely Light draw a plain dim instead. */
    override fun extractBlurredBackground(graphics: net.minecraft.client.gui.GuiGraphicsExtractor) {
        if (menuBlurAllowed(AutoGraphics.quality)) super.extractBlurredBackground(graphics)
    }

    override fun init() {
        val rendererChanged = texture.prepareFrame()
        val window = MC.instance.window
        val previousViewport = if (::viewport.isInitialized) viewport else null
        viewport = MenuViewport.fit(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        width = viewport.width; height = viewport.height
        if (!introductionChecked) {
            introductionChecked = true
            introductionClaimed = !modal && menuIntroduction.claim()
            if (!introductionClaimed && !modal && outgoing == null) {
                opening.snap(1.0); sidebarOpening.snap(1.0); pageOpening.snap(1.0)
            }
        }
        if (!closing) { closingContent = null; contentExit.snap(1.0) }
        if (previousViewport != null && previousViewport != viewport) { outgoing = null; modalUnderlay = null; closingContent = null }
        if (outgoingSize != null && outgoingSize != (width to height)) { outgoing = null; modalUnderlay = null }
        outgoingSize = null
        opening.animateTo(if (closing) 0.0 else 1.0, if (closing) 145 else 245, MotionEasing.SETTLE, ThemeConfig.animationMode)
        sidebarOpening.animateTo(if (closing) 0.0 else 1.0, if (closing) 90 else 200, MotionEasing.QUICK, ThemeConfig.animationMode,
            delayMillis = if (closing) 0 else 25)
        pageOpening.animateTo(if (closing) 0.0 else 1.0, if (closing) 110 else 230, MotionEasing.QUICK, ThemeConfig.animationMode,
            delayMillis = if (!closing && opening.sample() < 1.0) 45 else 0)
        super.init()
        if (!invalid && (builtLayout != menuLayout || builtTheme != me.mycellium.skymyce.hud.HudTheme.revision)) {
            uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount()
        }
        navigationButtons().forEachIndexed { index, button -> navigationMotion[index]?.let(button::inheritMotion) }
        navigationMotion = emptyMap()
        if (rendererChanged && !invalid) recoverRenderer()
    }
    final override fun build(root: FlowLayout) {
        closeMenuDropdown()
        if (builtLayout != null && builtLayout != menuLayout) { outgoing = null; pageOpening.snap(1.0) }
        builtLayout = menuLayout
        builtTheme = me.mycellium.skymyce.hud.HudTheme.revision
        constructing = true
        root.surface(Surface { g, c ->
            modalUnderlay?.let { canvas.replay(it) }
            (g as MenuGraphics).layer(alpha = paintedShell.alpha) {
                MenuTheme.backdrop.draw(g, c)
        } }).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        val frame = MenuLayer(Sizing.fixed(menuLayout.width), Sizing.fixed(menuLayout.height), FlowLayout.Algorithm.HORIZONTAL, "shell", { paintedShell }).apply {
            surface(Surface { g, c -> MenuTheme.panel().draw(g, c); drawAmbient(g as MenuGraphics, c) })
        }
        if (!modal) {
            // Category navigation stays on the left at every depth, including Home.
            frame.child(UIContainers.verticalFlow(Sizing.fixed(menuLayout.sidebar), Sizing.fill()))
        }
        val body = MenuLayer(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight), FlowLayout.Algorithm.VERTICAL, "content", { paintedContent }, around = ::drawContent)
        content = body
        if (menuHeight > menuLayout.height) frame.child(menuScroll(Sizing.fixed(menuWidth), Sizing.fill(), body).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
        }) else frame.child(body)
        if (!modal) {
            val side = buildNavigation()
            sidebar = side
            // The stationary rail is separate from content transitions.
            frame.child(MenuLayer(Sizing.fixed(menuLayout.sidebar), Sizing.fixed(menuLayout.height), FlowLayout.Algorithm.VERTICAL,
                "sidebar", { MotionPose(alpha = sidebarProgress) }).apply {
                positioning(Positioning.absolute(0, 0)); allowOverflow(true)
                child(if (menuLayout.height < 260) menuScroll(Sizing.fill(), Sizing.fill(), side) else side)
            })
        }
        root.child(frame)
        try { buildContent(body) } finally { constructing = false }
    }
    protected open fun buildNavigation(): FlowLayout = MenuNavigation(menuLayout.copy(height = maxOf(260, menuLayout.height)),
        { if (home) -1 else menuCategory?.ordinal ?: -1 }, navigationSelection,
        { index -> if (index == null) openMenu() else openMenuCategory(MenuCategory.entries[index]) },
        { navigateMenu(MenuDirectoryScreen()) },
        { navigateMenu(MenuDirectoryScreen(view = MenuDirectoryView.QUICK)) },
        { navigateMenu(me.mycellium.skymyce.hud.widget.WidgetEditorScreen(this)) },
        { if (introductionClaimed) ThemeConfig.animationMode else GuiMotion.OFF },
        { !introductionClaimed || opening.sample() >= .85 }, home)

    protected abstract fun buildContent(root: FlowLayout)
    /** Native player previews cannot be rasterized into MenuCanvas. Paint only on the settled, visible page. */
    protected open fun extractNativeContent(graphics: GuiGraphicsExtractor, screenScaleX: Float, screenScaleY: Float, partialTicks: Float) {}

    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        RuntimeProfiler.measure(RuntimeProfiler.Area.GUI) {
        if (invalid) { onClose(); return }
        if (texture.prepareFrame()) recoverRenderer()
        val mode = ThemeConfig.animationMode
        opening.animateTo(if (closing) 0.0 else 1.0, if (closing) 145 else 245, MotionEasing.SETTLE, mode)
        sidebarOpening.animateTo(if (closing) 0.0 else 1.0, 200, mode = mode)
        pageOpening.animateTo(if (closing) 0.0 else 1.0, 230, mode = mode)
        if (closing && opening.sample() == 0.0 && !opening.running) {
            if (MC.screen === this) {
                val destination = closeDestination
                navigateMenu(destination)
                // Closing can complete during extraction. Paint the destination in this frame too.
                if (destination != null && MC.screen === destination) destination.extractRenderState(g, mouseX, mouseY, partialTicks)
            }
            return
        }
        canvas.begin()
        clearDetachedFocus()
        val now = System.nanoTime()
        paintedShell = shellPose(opening.sample(now))
        sidebarProgress = sidebarOpening.sample(now).coerceIn(0.0, 1.0)
        pageProgress = pageOpening.sample(now).coerceIn(0.0, 1.0)
        if (introductionClaimed && !opening.running && !sidebarOpening.running && !pageOpening.running &&
            (sidebar as? MenuNavigation)?.revealsSettled != false) {
            menuIntroduction.complete(); introductionClaimed = false
        }
        if (pageProgress >= 1.0) outgoing = null
        if (outgoing == null && !menuSwapTransitionActive(uiAdapter.rootComponent)) canvas.releaseTransition()
        paintedContent = contentPose(pageProgress)
        val point = pointer(mouseX.toDouble(), mouseY.toDouble())
        val mx = point.first.toInt(); val my = point.second.toInt()
        tooltip.beginFrame()
        val custom = MenuGraphics(canvas, mx, my, width, height, tooltip)
        allowMenuMotionOverflow(uiAdapter.rootComponent)
        uiAdapter.extractRenderState(custom, mx, my, partialTicks)
        closingDropdown?.let { popup ->
            if (popup.finishedClosing) closingDropdown = null else popup.draw(custom, mx, my, partialTicks, 0f)
        }
        if (!closing) {
            canvas.group = "tooltip"
            uiAdapter.drawTooltip(custom, mx, my, partialTicks)
            tooltip.endFrame(canvas)
            canvas.group = "toast"
            toasts.draw(custom, width, height)
            canvas.group = null
        }
        val scale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        texture.render(g, canvas, viewport.screenWidth, viewport.screenHeight, scale, width, height)
        if (!closing && dropdown == null && closingDropdown == null && pageProgress >= 1.0 && paintedShell.alpha >= .999 && paintedShell.scale >= .999) {
            extractNativeContent(g, viewport.screenWidth.toFloat() / width, viewport.screenHeight.toFloat() / height, partialTicks)
        }
        }
    }

    /** Capture these only when a developer requests a report, on the client thread. */
    fun cacheSizes(): Map<String, Long> = canvas.cacheSizes() + ("snapshots.page" to if (outgoing == null) 0L else 1L)

    private var sidebarProgress = 0.0
    private var pageProgress = 0.0
    private fun shellPose(progress: Double = opening.sample()): MotionPose {
        val profile = ThemeConfig.animationMode.profile
        val movement = 1 - progress
        return MotionPose(progress.coerceIn(0.0, 1.0), 1 - movement * profile.shellScale, y = movement * profile.shellOffset,
            pivotX = width / 2.0, pivotY = height / 2.0)
    }
    private fun contentPose(progress: Double = pageOpening.sample().coerceIn(0.0, 1.0)): MotionPose {
        val profile = ThemeConfig.animationMode.profile
        val offset = (1 - progress) * profile.pageOffset * pageDirection
        return MotionPose(alpha = if (outgoing == null) progress else 1.0, x = offset,
            scale = 1 - (1 - progress) * profile.pageScale,
            pivotX = width / 2.0, pivotY = height / 2.0)
    }
    private fun drawAmbient(g: MenuGraphics, component: UIComponent) {
        val quality = me.mycellium.skymyce.ui.AutoGraphics.quality
        if (!quality.ambient || !ThemeConfig.animationMode.profile.ambient || MenuTheme.style.sheen <= 0.0 || closing) return
        val changing = opening.running || pageOpening.running
        val step = menuAmbientStep(ambientStep, System.nanoTime(), quality.ambientIntervalNanos, changing)
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
    /** Both pages blend as isolated layers; the surrounding shell never fades or darkens during a swap. */
    private fun drawContent(g: MenuGraphics, draw: () -> Unit) {
        closingContent?.let { snapshot ->
            contentExit.animateTo(0.0, 105, MotionEasing.QUICK, ThemeConfig.animationMode)
            val fade = contentExit.sample().coerceIn(0.0, 1.0)
            val parentOpacity = canvas.opacity
            try {
                canvas.opacity = 1.0
                canvas.replay(snapshot, fade * (paintedShell.alpha / closingShellAlpha).coerceIn(0.0, 1.0),
                    y = (1 - fade) * ThemeConfig.animationMode.profile.pageOffset * .25, boundary = g.canvasClip)
            } finally { canvas.opacity = parentOpacity }
            return
        }
        val body = content
        val snapshot = outgoing
        if (body == null || snapshot == null) { draw(); return }
        val offset = -pageProgress * ThemeConfig.animationMode.profile.pageOffset * pageDirection
        val transform = g.pose()
        val left = transform.m00() * body.x() + transform.m20()
        val top = transform.m11() * body.y() + transform.m21()
        val right = left + transform.m00() * body.width()
        val bottom = top + transform.m11() * body.height()
        val scissor = g.canvasClip
        val clipX = maxOf(kotlin.math.floor(left.toDouble()).toInt(), scissor?.x ?: Int.MIN_VALUE)
        val clipY = maxOf(kotlin.math.floor(top.toDouble()).toInt(), scissor?.y ?: Int.MIN_VALUE)
        val clipRight = minOf(kotlin.math.ceil(right.toDouble()).toInt(), scissor?.let { it.x + it.width } ?: Int.MAX_VALUE)
        val clipBottom = minOf(kotlin.math.ceil(bottom.toDouble()).toInt(), scissor?.let { it.y + it.height } ?: Int.MAX_VALUE)
        canvas.transition(snapshot, pageProgress, MenuCanvas.Clip(clipX, clipY,
            (clipRight - clipX).coerceAtLeast(0), (clipBottom - clipY).coerceAtLeast(0)), offset, draw)
    }
    private fun pointer(x: Double, y: Double): Pair<Double, Double> {
        val sx = paintedShell.inverseX(viewport.x(x)); val sy = paintedShell.inverseY(viewport.y(y))
        val body = content
        dropdown?.pointer(sx, sy)?.let { point ->
            body?.let { menuCardPointer(it, -1.0, -1.0) }
            sidebar?.let { menuCardPointer(it, -1.0, -1.0) }
            return point
        }
        val bx = paintedContent.inverseX(sx); val by = paintedContent.inverseY(sy)
        val cardPoint = body?.let { menuCardPointer(it, bx, by) }
        (sidebar as? MenuNavigation)?.pointer(sx, sy)?.let { return it }
        sidebar?.takeIf { it !is MenuNavigation && it.isInBoundingBox(sx, sy) }?.let {
            return menuCardPointer(it, sx, sy) ?: (sx to sy)
        }
        if (body == null) return sx to sy
        if (body.isInBoundingBox(bx, by)) return cardPoint ?: (bx to by)
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
    private fun releaseContentMotion(component: UIComponent) {
        if (component is MenuCollapsible) component.releaseMotion()
        if (component is MenuSwap) component.releaseMotion()
        if (component is ParentUIComponent) component.children().forEach(::releaseContentMotion)
    }
    /** Retain editable controls and scroll state while discarding paint tied to the failed GPU. */
    private fun recoverRenderer() {
        closeMenuDropdown(); closingDropdown = null
        val root = uiAdapter.rootComponent
        val focused = root.focusHandler()?.focused()
        outgoing = null; modalUnderlay = null; closingContent = null; outgoingSize = null
        tooltip.clear(); releaseContentMotion(root); canvas.close()
        opening.snap(if (closing) 0.0 else 1.0)
        sidebarOpening.snap(if (closing) 0.0 else 1.0)
        pageOpening.snap(if (closing) 0.0 else 1.0)
        contentExit.snap(if (closing) 0.0 else 1.0)
        navigationSelection.cancel()
        uiAdapter.inflateAndMount()
        if (focused != null && menuComponentAttached(root, focused))
            root.focusHandler()?.focus(focused, UIComponent.FocusSource.KEYBOARD_CYCLE)
    }
    override fun removed() {
        dropdown?.parent()?.removeChild(dropdown); dropdown = null; closingDropdown = null; dropdownAnchor = null
        uiAdapter?.rootComponent?.let(::releaseContentMotion)
        outgoing = null; modalUnderlay = null; navigationMotion = emptyMap()
        closingContent = null; contentExit.cancel()
        opening.cancel(); sidebarOpening.cancel(); pageOpening.cancel(); navigationSelection.cancel(); tooltip.clear(); toasts.clear()
        closeDestination = null; closing = false
        canvas.close(); texture.close(); super.removed()
    }
    override fun mouseMoved(x: Double, y: Double) { val p = pointer(x, y); super.mouseMoved(p.first, p.second) }
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (closing) return true
        clearDetachedFocus()
        dropdown?.let {
            if (it.pointer(paintedShell.inverseX(viewport.x(event.x())), paintedShell.inverseY(viewport.y(event.y()))) == null) {
                val anchorPoint = pointer(event)
                val clickedAnchor = dropdownAnchor?.isInBoundingBox(anchorPoint.x(), anchorPoint.y()) == true
                closeMenuDropdown()
                if (clickedAnchor) return true
            }
        }
        val mapped = pointer(event)
        val handler = uiAdapter?.rootComponent?.focusHandler()
        val focused = handler?.focused()
        if (focused is MenuTextBox && !focused.isInBoundingBox(mapped.x(), mapped.y()))
            handler.focus(null, UIComponent.FocusSource.MOUSE_CLICK)
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
        val previous = uiAdapter?.rootComponent?.focusHandler()?.focused()
        val handled = super.keyPressed(event)
        val handler = uiAdapter?.rootComponent?.focusHandler()
        val focused = handler?.focused()
        if (focused != null && focused !== previous && handler?.lastFocusSource() == UIComponent.FocusSource.KEYBOARD_CYCLE)
            revealMenuKeyboardFocus(focused)
        return handled
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

fun openMenu(page: MenuPage? = null) {
    val feature = page?.featureId
    if (feature == null) navigateMenu(MenuHomeScreen()) else openMenuFeature(feature)
}

internal fun menuCategoryScreen(category: MenuCategory): MenuScreen = MenuDirectoryScreen(category)

fun openMenuCategory(category: MenuCategory) = navigateMenu(menuCategoryScreen(category))

fun openMenuFeature(id: String, configure: Boolean = false) {
    val feature = MenuDirectory.catalog.findFeature(id) ?: return
    MenuShortcuts.visit(id)
    val parent = menuBackScreen(menuBackTarget(feature.category, openedFromHome = MC.screen is MenuHomeScreen))
    if (!configure && feature.tool == "global-chat") {
        // The composer selector only replaces gameplay or an existing ChatScreen.
        MC.instance.setScreen(null)
        me.mycellium.skymyce.features.instances.dungeons.friends.RelayMessages.selectGlobal()
        return
    }
    navigateMenu(if (configure && id != "cosmetics") SettingsScreen.forFeature(parent, id) else when (feature.tool) {
        "daily-digest" -> DailyDigestScreen()
        "party-finder" -> DungeonFriendsScreen()
        "dungeon-tracker" -> DungeonScreen()
        "dungeon-splits" -> me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen()
        "healer-crush-camera" -> me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraScreen()
        "auction-browser" -> AuctionHouseScreen()
        "bazaar-flipping" -> me.mycellium.skymyce.features.bazaar.BazaarScreen()
        "cosmetics" -> CosmeticsScreen()
        "community" -> me.mycellium.skymyce.community.CommunityScreen()
        "leaderboards" -> me.mycellium.skymyce.community.LeaderboardScreen()
        "rng-journal" -> me.mycellium.skymyce.features.instances.dungeons.tracker.RngJournalScreen()
        "player-utilities" -> FriendsSocialScreen()
        "hud-editor" -> WidgetEditorScreen(parent)
        "status-bars" -> me.mycellium.skymyce.features.general.overlays.bars.StatusBarsEditorScreen(parent)
        else -> SettingsScreen.forFeature(parent, id)
    })
}

fun openMenuSetting(path: String) {
    if (MenuDirectory.catalog.settingEntry(path) == null) return
    val owner = MenuDirectory.catalog.featureForPath(path)
    if (owner != null) MenuShortcuts.visit(owner.id)
    val parent = menuCategoryScreen(owner?.category ?: MenuCategory.SETTINGS)
    navigateMenu(when {
        path.startsWith("Cosmetics/") -> CosmeticsScreen(focusPath = path)
        else -> SettingsScreen.forSetting(parent, path)
    })
}

/** State/action changes happen immediately; only inert outgoing paint survives a navigation. */
fun navigateMenu(destination: net.minecraft.client.gui.screens.Screen?) {
    val previous = MC.screen
    if (previous === destination) return
    if (destination is MenuScreen) destination.canonicalFeatureId?.let(MenuShortcuts::visit)
    if (previous is MenuScreen && destination is MenuScreen) previous.prepareNavigation(destination)
    MC.instance.setScreen(destination)
}
