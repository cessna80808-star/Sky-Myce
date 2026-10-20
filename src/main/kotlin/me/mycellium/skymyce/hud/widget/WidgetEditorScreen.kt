package me.mycellium.skymyce.hud.widget

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.ParentUIComponent
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.CursorStyle
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.ui.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import java.util.Optional
import org.lwjgl.glfw.GLFW

/** The full-screen adapter's empty background must not intercept widget clicks. */
internal fun hudEditorControlAt(root: ParentUIComponent?, x: Double, y: Double, visible: Boolean = true): Boolean =
    visible && root?.childAt(x.toInt(), y.toInt())?.let { it !== root } == true

internal fun hudEditorCoordinate(pointer: Double, grabOffset: Int, anchor: Float, size: Int, screenSize: Int, snap: Boolean): Int {
    var position = (pointer - grabOffset).toInt()
    if (snap) position = (position / 5) * 5
    val edge = (anchor * size).toInt()
    return position.coerceIn(min(edge, edge + screenSize - size), max(edge, edge + screenSize - size))
}

internal fun hudEditorResizeAt(x: Double, y: Double, left: Int, top: Int, width: Int, height: Int): Boolean =
    width > 0 && height > 0 && x >= left + width - 10 && x < left + width && y >= top + height - 10 && y < top + height

internal fun hudEditorToolbar(screenWidth: Int, screenHeight: Int): FlowLayout {
    val toolbarWidth = (screenWidth - 16).coerceIn(1, 253)
    val helpHeight = hudEditorHelp(screenWidth).size * MenuCanvas.lineHeight()
    return UIContainers.horizontalFlow(Sizing.fixed(toolbarWidth), Sizing.fixed(28)).apply {
        padding(Insets.of(4)); gap(5); surface(MenuTheme.panel(true))
        positioning(Positioning.absolute((screenWidth - toolbarWidth) / 2, (screenHeight - helpHeight - 40).coerceAtLeast(0)))
    }
}

internal fun hudEditorHelp(screenWidth: Int) = menuTextLines(Component.literal(
    "Drag: move | Scroll: scale\nCtrl: anchor | Shift: snap | V: visibility | R: reset hovered | H: hide controls"), screenWidth - 16)

/** Keep the exact opening screen, including its selected settings tab and draft state. */
class WidgetEditorScreen(private val parent: Screen? = null) : Screen(MC.instance, MC.font, Component.literal("Widget Editor")) {
    private var selectedWidget: Widget? = null
    private var clickedWidget: Widget? = null
    private var resizingWidget: Widget? = null
    private var resizeLeft = 0
    private var resizeTop = 0
    private var selectedAnchor: Anchor? = null
    private var screenWidgets: List<Widget> = emptyList()
    private var helpLines = emptyList<FormattedCharSequence>()

    private var mouseRelX = 0
    private var mouseRelY = 0
    private var snapPosition = false
    private var anchorSelect = false
    private var ui: OwoUIAdapter<FlowLayout>? = null
    private var controlsVisible = true
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    private val appearance = MotionValue(0.0)
    private val controls = MotionValue(1.0)
    private val tooltip = MenuTooltipMotion()
    private var closing = false

    override fun init() {
        texture.prepareFrame()
        appearance.animateTo(if (closing) 0.0 else 1.0, if (closing) 120 else 180, MotionEasing.QUICK, ThemeConfig.animationMode)
        controls.snap(if (controlsVisible) 1.0 else 0.0)
        ui?.dispose()
        selectedWidget = null
        clickedWidget = null
        resizingWidget = null
        selectedAnchor = null
        snapPosition = false
        anchorSelect = false
        screenWidgets = WidgetManager.widgets.filter { it.prepareEditor() }
        helpLines = hudEditorHelp(width)

        val adapter = OwoUIAdapter.create(this, UIContainers::verticalFlow)
        ui = adapter
        val toolbar = hudEditorToolbar(width, height)
        val resetButton = MenuButton(Component.literal("Reset positions")) {
            screenWidgets.forEach { widget ->
                widget.defaultLayout.applyConfig(widget)
            }
        }.apply {
            themed()
            sizing(Sizing.expand(), Sizing.fill())
            tooltip(Component.literal("Restore each widget's default position, scale and visibility."))
        }

        toolbar.child(resetButton)
        toolbar.child(MenuButton(Component.literal("Bars")) { navigateMenu(me.mycellium.skymyce.features.general.overlays.bars.StatusBarsEditorScreen(this)) }.apply {
            themed(); sizing(Sizing.fixed(40), Sizing.fill())
            tooltip(Component.literal("Arrange the status bars: anchors around the hotbar, snapping, sizes, colours and visibility"))
        })
        toolbar.child(MenuButton(Component.literal("Done")) { onClose() }.sizing(Sizing.fixed(40), Sizing.fill()))
        adapter.rootComponent.child(toolbar)
        adapter.inflateAndMount()
        super.init()
    }

    override fun removed() {
        resizingWidget?.resizeFinished?.invoke(); resizingWidget = null
        appearance.cancel(); controls.cancel(); tooltip.clear(); closing = false
        canvas.close()
        texture.close()
        WidgetManager.save()
        ui?.dispose()
        ui = null
        super.removed()
    }

    override fun onClose() {
        if (closing) return
        resizingWidget?.resizeFinished?.invoke()
        closing = true; clickedWidget = null; resizingWidget = null; selectedAnchor = null; anchorSelect = false; snapPosition = false
        appearance.animateTo(0.0, 120, MotionEasing.QUICK, ThemeConfig.animationMode)
        if (!appearance.running && MC.screen === this) navigateMenu(parent)
    }
    override fun tick() {
        super.tick()
        appearance.sample()
        if (closing && !appearance.running && MC.screen === this) navigateMenu(parent)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (texture.prepareFrame()) { tooltip.clear(); canvas.close(); ui?.inflateAndMount() }
        appearance.animateTo(if (closing) 0.0 else 1.0, if (closing) 120 else 180, MotionEasing.QUICK, ThemeConfig.animationMode)
        val alpha = appearance.sample().coerceIn(0.0, 1.0)
        output.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.SIDEBAR, (25 * alpha).toInt()))
        canvas.begin()
        tooltip.beginFrame()
        val graphics = MenuGraphics(canvas, mouseX, mouseY, width, height, tooltip)
        // Widgets and their stored positions use Minecraft GUI coordinates, exactly like the live HUD.
        // Keep that one coordinate space for outlines, pointer input and saved positions.
        graphics.layer(alpha = alpha) {
            screenWidgets.forEach { widget ->
                // Preview the actual HUD renderer and its measured bounds; editor controls use our canvas.
                if (!widget.render(output, mouseX, mouseY, a, editor = true)) return@forEach
                val outlineColor = MenuTheme.alpha(if (clickedWidget == widget || selectedWidget == widget) MenuTheme.ACCENT else MenuTheme.MUTED)
                graphics.outline(widget.renderX, widget.renderY, widget.width, widget.height, outlineColor)
                if (widget.resizeHandler != null) graphics.outline(widget.renderX + widget.width - 8, widget.renderY + widget.height - 8, 7, 7, outlineColor)
                if (selectedWidget == widget || clickedWidget == widget) {
                    val labelY = (widget.renderY + widget.height + 4).coerceAtMost(height - MenuCanvas.lineHeight()).coerceAtLeast(0)
                    graphics.text(MC.font, "${widget.title}${if (widget.visible) "" else " [hidden in gameplay]"} | X: ${widget.x} Y: ${widget.y} Scale: ${widget.scale}",
                        widget.renderX.coerceAtLeast(0), labelY, outlineColor, false)
                }
            }

            if (anchorSelect) {
                selectedWidget?.let { widget ->
                    Anchor.entries.forEach { anchor ->
                        val x = widget.renderX + (anchor.x * widget.width).toInt()
                        val y = widget.renderY + (anchor.y * widget.height).toInt()
                        graphics.fill(x - 2, y - 2, x + 2, y + 2, MenuTheme.alpha(
                            if (selectedAnchor == anchor) MenuTheme.ACCENT else if (widget.anchor == anchor) MenuTheme.YELLOW else MenuTheme.MUTED))
                    }
                }
            }
        }

        controls.animateTo(if (controlsVisible && clickedWidget == null) 1.0 else 0.0, 110, MotionEasing.QUICK, ThemeConfig.animationMode)
        val controlAlpha = alpha * controls.sample().coerceIn(0.0, 1.0)
        if (controlAlpha > 0.0) graphics.layer(alpha = controlAlpha) {
            val lineHeight = MenuCanvas.lineHeight()
            helpLines.forEachIndexed { index, line -> graphics.text(MC.font, line, 8, height - 6 - (helpLines.size - index) * lineHeight,
                MenuTheme.alpha(MenuTheme.MUTED), false) }
            super.extractRenderState(graphics, mouseX, mouseY, a)
            if (controlsVisible && !closing) {
                canvas.group = "tooltip"
                try { ui?.drawTooltip(graphics, mouseX, mouseY, a) } finally { canvas.group = null }
            }
        }
        if (closing) tooltip.clear() else tooltip.endFrame(canvas)
        val rasterScale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        texture.render(output, canvas, width, height, rasterScale)
    }


    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (closing) return
        selectedWidget = screenWidgets.asReversed().firstOrNull {
            !hudEditorControlAt(ui?.rootComponent, mouseX, mouseY, controlsVisible) && it.inBounds(mouseX.toInt(), mouseY.toInt())
        }

        selectedAnchor = if (anchorSelect) selectedWidget?.getClosestAnchor(mouseX.toInt(), mouseY.toInt()) else null

        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseClicked(button: MouseButtonEvent, doubled: Boolean): Boolean {
        if (closing) return true
        if (super.mouseClicked(button, doubled)) return true
        if (button.button() != 0) return false
        val resize = screenWidgets.asReversed().firstOrNull { widget -> widget.resizeHandler != null &&
            hudEditorResizeAt(button.x, button.y, widget.renderX, widget.renderY, widget.width, widget.height) }
        if (resize != null) {
            resizingWidget = resize
            resizeLeft = resize.renderX; resizeTop = resize.renderY
            return true
        }
        clickedWidget = screenWidgets.asReversed().firstOrNull {
            it.inBounds(button.x.toInt(), button.y.toInt())
        }

        mouseRelX = button.x.toInt() - (clickedWidget?.x ?: 0)
        mouseRelY = button.y.toInt() - (clickedWidget?.y ?: 0)

        return clickedWidget != null
    }

    override fun mouseReleased(button: MouseButtonEvent): Boolean {
        if (closing) return true
        if (button.button() == 0 && resizingWidget != null) {
            resizingWidget?.resizeFinished?.invoke(); resizingWidget = null; WidgetManager.save(); return true
        }
        if (button.button() == 0 && clickedWidget != null) {
            clickedWidget = null
            return true
        }
        return super.mouseReleased(button)
    }

    // Minecraft 26.1 consumes clicks whenever getChildAt finds a child, even if it declines the click.
    // Keep vanilla focus/drag handling for actual controls, and pass empty space to HUD hit testing.
    override fun getChildAt(x: Double, y: Double): Optional<GuiEventListener> =
        super.getChildAt(x, y).filter { it !== ui || hudEditorControlAt(ui?.rootComponent, x, y, controlsVisible && clickedWidget == null && resizingWidget == null) }

    override fun mouseDragged(button: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (closing) return true
        if (button.button() == 0) {
            resizingWidget?.let { widget ->
                widget.resizeHandler?.invoke(((button.x - resizeLeft) / widget.scale).toInt(), ((button.y - resizeTop) / widget.scale).toInt())
                widget.prepareEditor()
                // Resize from a stable corner even for right/bottom anchors.
                widget.x = resizeLeft + (widget.anchor.x * widget.width).toInt()
                widget.y = resizeTop + (widget.anchor.y * widget.height).toInt()
                return true
            }
            clickedWidget?.let { widget ->
                widget.x = hudEditorCoordinate(button.x, mouseRelX, widget.anchor.x, widget.width, width, snapPosition)
                widget.y = hudEditorCoordinate(button.y, mouseRelY, widget.anchor.y, widget.height, height, snapPosition)
                return true
            }
        }

        return super.mouseDragged(button, offsetX, offsetY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmt: Double, verticalAmt: Double): Boolean {
        if (closing) return true
        if (hudEditorControlAt(ui?.rootComponent, mouseX, mouseY, controlsVisible)) return super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
        screenWidgets.asReversed().firstOrNull { it.inBounds(mouseX.toInt(), mouseY.toInt()) }?.let {
            it.scale += verticalAmt.toFloat() * 0.1f
            it.scale = (round(it.scale * 10) / 10).coerceIn(0.1f, 3f) // get rid of weird floating point errors
            return true
        }

        return controlsVisible && super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (closing) return true
        if (input.isEscape) { onClose(); return true }
        if (input.key == GLFW.GLFW_KEY_H && !input.hasControlDown() && !input.hasAltDown()) {
            controlsVisible = !controlsVisible
            if (!controlsVisible) { setFocused(null); ui?.cursorAdapter?.applyStyle(CursorStyle.NONE) }
            return true
        }
        if (controlsVisible && !input.hasControlDown() && !input.hasAltDown()) {
            val widget = selectedWidget
            if (widget != null && input.key == GLFW.GLFW_KEY_V) { widget.visible = !widget.visible; WidgetManager.save(); return true }
            if (widget != null && input.key == GLFW.GLFW_KEY_R) { widget.defaultLayout.applyConfig(widget); WidgetManager.save(); return true }
        }
        snapPosition = input.hasShiftDown()
        anchorSelect = input.hasControlDown()
        if (!controlsVisible) return false
        return super.keyPressed(input)
    }

    override fun keyReleased(input: KeyEvent): Boolean {
        if (closing) return true
        snapPosition = input.hasShiftDown()

        if (anchorSelect && !input.hasControlDown()) {
            anchorSelect = false
            selectedWidget?.let {
                it.setAnchorPreservePosition(selectedAnchor ?: it.anchor)
            }
        }

        return controlsVisible && super.keyReleased(input)
    }
}
