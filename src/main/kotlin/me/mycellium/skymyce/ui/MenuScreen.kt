package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.SettingsScreen
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
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

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
        if (focused != null && focused !== previous && handler.lastFocusSource() == UIComponent.FocusSource.KEYBOARD_CYCLE)
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
        "greenhouse-planner" -> me.mycellium.skymyce.features.garden.greenhouse.GreenhouseScreen()
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

/** Space under the category rail for the utility rows: Search, then Quick settings, then Edit HUD when they fit. */
internal fun menuNavigationFooter(height: Int) = when { height >= 360 -> 100; height >= 320 -> 70; else -> 34 }

/** Categories keep their identity and position on Home and every submenu. */
internal fun menuNavigationRect(layout: MenuLayout, index: Int): MenuRect {
    require(index in menuSectionTitles.indices)
    val footer = menuNavigationFooter(layout.height).toDouble()
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

internal fun menuHomeColumns(available: Int) = when { available >= 420 -> 3; available >= 264 -> 2; else -> 1 }
internal fun menuHomeToolColumns(available: Int) = when { available >= 540 -> 3; available >= 390 -> 2; else -> 1 }

/** A complete tile uses the shared surfaces; geometry does not change with hover or theme selection. */
internal fun paintMenuHomeTile(canvas: MenuCanvas, rect: MenuRect, title: List<String>, description: List<String>,
                               icon: Int, hover: Double = 0.0, focused: Boolean = false, compact: Boolean = false) {
    val style = ThemeConfig.preset.palette.style
    val radius = MenuTheme.RADIUS.toDouble()
    canvas.roundedRect(rect.x, rect.y, rect.width, rect.height, radius,
        MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, (hover * style.hoverWeight).toInt()), MenuTheme.OPACITY))
    if (MenuTheme.BORDERS || focused) canvas.roundedBorder(rect.x, rect.y, rect.width, rect.height, radius,
        if (focused) style.focusWidth.toDouble() else style.borderWidth,
        MenuTheme.alpha(if (focused) MenuTheme.ACCENT else MenuTheme.BORDER))
    if (!compact) canvas.fill(rect.x + 10, rect.y + 10, 2.0, (rect.height - 20).coerceAtLeast(1.0), MenuTheme.alpha(MenuTheme.ACCENT, 60))
    val size = if (compact) 14.0 else 18.0
    paintMenuIcon(canvas, icon, rect.x + if (compact) 12 else 22, rect.y + if (compact) (rect.height - size) / 2 else 14.0,
        size, MenuTheme.alpha(if (hover > .1 || focused) MenuTheme.ACCENT else MenuTheme.MUTED))
    val left = rect.x + if (compact) 36 else 50
    val titleSize = if (compact) 10.0 else 12.0
    val top = rect.y + if (compact) (rect.height - title.size * MenuCanvas.lineHeight(titleSize)) / 2 else 12.0
    val oldClip = canvas.clip
    val clip = menuNavigationTextClip(rect, canvas.transform)
    canvas.clip = oldClip?.let { intersectMenuClip(it, clip) } ?: clip
    title.forEachIndexed { index, line -> canvas.text(listOf(MenuCanvas.Run(line, MenuTheme.alpha(MenuTheme.TEXT), bold = true)),
        left, top + index * MenuCanvas.lineHeight(titleSize), titleSize) }
    if (!compact) description.forEachIndexed { index, line -> canvas.text(listOf(MenuCanvas.Run(line, MenuTheme.alpha(MenuTheme.MUTED))),
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
        // A screen with two placed widgets.
        12 -> { line(1.0, 2.0, 15.0, 2.0); line(1.0, 2.0, 1.0, 14.0); line(1.0, 14.0, 15.0, 14.0); line(15.0, 14.0, 15.0, 2.0)
            line(3.5, 5.0, 7.5, 5.0); line(3.5, 5.0, 3.5, 8.0); line(3.5, 8.0, 7.5, 8.0); line(7.5, 8.0, 7.5, 5.0); line(9.5, 11.0, 12.5, 11.0) }
        11 -> { for (index in 0..2) { val row = 3.0 + index * 5; val knob = when (index) { 0 -> 5.0; 1 -> 11.0; else -> 7.0 }; line(1.0, row, 15.0, row); ring(knob, row, 2.0) } }
        else -> { ring(8.0, 8.0, 4.0); ring(8.0, 8.0, 1.8); for (i in 0..7) { val angle = i * Math.PI / 4; line(8 + kotlin.math.cos(angle) * 5, 8 + kotlin.math.sin(angle) * 5, 8 + kotlin.math.cos(angle) * 7, 8 + kotlin.math.sin(angle) * 7) } }
    }
}

internal class MenuNavigation(private val layout: MenuLayout, private val activeSection: () -> Int,
                              private val indicator: MotionValue, private val action: (Int?) -> Unit,
                              search: () -> Unit, quick: () -> Unit, editHud: () -> Unit,
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
            val revealKey = "navigation/$index"
            child(object : MenuButton(Component.literal(title), { action(index) }) {
                override fun interactionPose(hover: Double, press: Double) = menuNavigationPose(geometry.rect(index), hover, press, ThemeConfig.animationMode)
                override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) {
                    val mode = revealMode()
                    val reveal = menuReveals.progress(revealKey, revealReady(), mode, index * mode.profile.staggerMillis)
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
        if (layout.height >= 360) child(utility("Edit HUD", 12, layout.height - 94) { editHud() })
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
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6); verticalAlignment(VerticalAlignment.CENTER)
            child(MenuLabel(Component.literal("Your tools")).apply {
                fontSize = 19f; color(Color.ofRgb(MenuTheme.TEXT)); horizontalSizing(Sizing.expand())
            })
            child(MenuButton(Component.literal("Edit")) { navigateMenu(MenuHomeShortcutsScreen()) }.apply {
                sizing(Sizing.fixed(42), Sizing.fixed(22)); tooltip(Component.literal("Customize the six Home tools"))
            })
        })
        val available = (menuWidth - padding * 2).coerceAtLeast(1)
        val list = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(10); padding(Insets.of(2))
        }
        val columns = menuHomeColumns(available - 4)
        val tileWidth = ((available - 4 - (columns - 1) * 8) / columns).coerceAtLeast(1)
        val toolColumns = menuHomeToolColumns(available - 4)
        val toolWidth = ((available - 4 - (toolColumns - 1) * 8) / toolColumns).coerceAtLeast(1)
        val tools = MenuShortcuts.homeTools.indices.map { index ->
            val feature = menuHomeFeature(index, MenuShortcuts.homeTools)
            Triple(feature, menuNavigationTitle(index, toolWidth - 60, 12.0, 2, feature),
                menuNavigationDescription(index, toolWidth - 60, feature))
        }
        val toolRows = (tools.size + toolColumns - 1) / toolColumns
        val categoryRows = (MenuCategory.entries.size + columns - 1) / columns
        val minimumHeight = tools.maxOf { (_, title, description) ->
            29 + title.size * MenuCanvas.lineHeight(12.0) + description.size * MenuCanvas.lineHeight(9.0)
        }.coerceAtLeast(86)
        val fixedHeight = padding * 2 + maxOf(22, MenuCanvas.lineHeight(19.0)) + 26 + 20 + 4 +
            MenuCanvas.lineHeight(11.0) + categoryRows * 36 + (toolRows + categoryRows) * 10
        val toolHeight = maxOf(minimumHeight, (menuHeight - fixedHeight) / toolRows.coerceAtLeast(1))
        tools.chunked(toolColumns).forEach { row -> list.child(
            UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(8)
                row.forEach { (feature, title, description) ->
                    child(object : MenuButton(Component.literal(feature.title), { openMenuFeature(feature.id) }) {
                        override fun interactionPose(hover: Double, press: Double) = MotionPose()
                        override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) =
                            paintMenuHomeTile(graphics.canvas, MenuRect(x.toDouble(), y.toDouble(), width.toDouble(), this.height.toDouble()),
                                title, description, feature.iconId, hover, isFocused)
                    }.apply {
                        sizing(Sizing.fixed(toolWidth), Sizing.fixed(toolHeight)); tooltip(Component.literal(feature.description))
                    })
                }
            }) }
        list.child(MenuLabel(Component.literal("Categories")).apply {
            fontSize = 11f; color(Color.ofRgb(MenuTheme.MUTED)); horizontalSizing(Sizing.fill())
        })
        MenuCategory.entries.chunked(columns).forEach { categories -> list.child(
            UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(8)
                categories.forEach { category ->
                    val title = navigationTextLines(category.title, (tileWidth - 48).coerceAtLeast(1), 10.0, true, 2)
                    child(object : MenuButton(Component.literal(category.title), { openMenuCategory(category) }) {
                        override fun interactionPose(hover: Double, press: Double) = MotionPose()
                        override fun paint(graphics: MenuGraphics, delta: Float, hover: Double, chosen: Double, press: Double) =
                            paintMenuHomeTile(graphics.canvas, MenuRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble()),
                                title, emptyList(), category.iconId, hover, isFocused, compact = true)
                    }.apply {
                        sizing(Sizing.fixed(tileWidth), Sizing.fixed(36)); tooltip(Component.literal(category.description))
                    })
                }
            }) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), list))
        root.child(MenuButton(Component.literal("Search features and settings")) { navigateMenu(MenuDirectoryScreen()) }.apply {
            sizing(Sizing.fill(), Sizing.fixed(26))
        })
    }
}

/** Tools opened from Home return there; other entries keep their category parent. */
internal sealed interface MenuBackTarget {
    data object Game : MenuBackTarget
    data object Home : MenuBackTarget
    data class Category(val category: MenuCategory) : MenuBackTarget
}

internal fun menuBackTarget(category: MenuCategory?, home: Boolean = false, categoryRoot: Boolean = false,
                            openedFromHome: Boolean = false): MenuBackTarget = when {
    home -> MenuBackTarget.Game
    openedFromHome || categoryRoot || category == null -> MenuBackTarget.Home
    else -> MenuBackTarget.Category(category)
}

internal fun menuBackScreen(target: MenuBackTarget): MenuScreen? = when (target) {
    MenuBackTarget.Game -> null
    MenuBackTarget.Home -> MenuHomeScreen()
    is MenuBackTarget.Category -> menuCategoryScreen(target.category)
}

/** Destructive actions and browser links keep confirmation inside the custom menu. */
class MenuConfirmScreen(private val parent: Screen, private val heading: String, private val detail: String,
                        private val confirmLabel: String = "Confirm", private val confirmed: () -> Unit) : MenuScreen(Component.literal(heading)) {
    override val modal = true
    override val menuParent get() = parent
    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(24)); root.gap(16)
        root.child(MenuLabel(Component.literal(heading)).apply { fontSize = 17f; color(Color.ofRgb(MenuTheme.TEXT)); horizontalSizing(Sizing.fill()) })
        root.child(MenuLabel(Component.literal(detail)).apply { color(Color.ofRgb(MenuTheme.MUTED)); horizontalSizing(Sizing.fill()) })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).gap(8).apply {
            child(MenuButton(Component.literal(confirmLabel)) { closeMenuTo(parent); confirmed() }.sizing(Sizing.fixed(90), Sizing.fixed(28)))
            child(MenuButton(Component.literal("Cancel")) { onClose() }.sizing(Sizing.fixed(70), Sizing.fixed(28)))
        })
    }
    override fun onClose() { closeMenuTo(parent) }
}
