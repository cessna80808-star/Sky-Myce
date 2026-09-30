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
import me.mycellium.skymyce.hud.ThemeEditorScreen
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.ui.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
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

internal fun hudEditorToolbar(screenWidth: Int, screenHeight: Int): FlowLayout {
    val toolbarWidth = (screenWidth - 16).coerceIn(1, 256)
    val helpHeight = hudEditorHelp(screenWidth).size * MenuCanvas.lineHeight()
    return UIContainers.horizontalFlow(Sizing.fixed(toolbarWidth), Sizing.fixed(28)).apply {
        padding(Insets.of(4)); gap(5); surface(MenuTheme.panel(true))
        positioning(Positioning.absolute((screenWidth - toolbarWidth) / 2, (screenHeight - helpHeight - 40).coerceAtLeast(0)))
    }
}

internal fun hudEditorHelp(screenWidth: Int) = menuTextLines(Component.literal(
    "Drag: move | Scroll: scale | Right click: theme\nCtrl: anchor | Shift: snap | H: hide controls"), screenWidth - 16)

object WidgetEditorScreen : Screen(MC.instance, MC.font, Component.literal("Widget Editor")) {
    private var selectedWidget: Widget? = null
    private var clickedWidget: Widget? = null
    private var selectedAnchor: Anchor? = null
    private var screenWidgets: List<Widget> = emptyList()

    private var mouseRelX = 0
    private var mouseRelY = 0
    private var snapPosition = false
    private var anchorSelect = false
    private var ui: OwoUIAdapter<FlowLayout>? = null
    private var sidebar: FlowLayout? = null
    private var controlsVisible = true
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()

    override fun init() {
        ui?.dispose()
        selectedWidget = null
        clickedWidget = null
        selectedAnchor = null
        snapPosition = false
        anchorSelect = false
        controlsVisible = true
        sidebar = null
        screenWidgets = WidgetManager.getActiveWidgets()

        val adapter = OwoUIAdapter.create(this, UIContainers::verticalFlow)
        ui = adapter
        val toolbar = hudEditorToolbar(width, height).apply {
            child(MenuButton(Component.literal("Menus")) {
                sidebar?.let { adapter.rootComponent.removeChild(it); sidebar = null } ?: run {
                    sidebar = menuSidebar(MenuLayout.fit(this@WidgetEditorScreen.width, this@WidgetEditorScreen.height), MenuPage.HUD, parent = this@WidgetEditorScreen)
                        .positioning(Positioning.absolute(8, 8)) as FlowLayout
                    adapter.rootComponent.child(sidebar!!)
                }
            }.apply {
                sizing(Sizing.fixed(48), Sizing.fill())
                tooltip(Component.literal("Show or hide the menu sidebar. H hides all editor controls."))
            })
        }
        val resetButton = MenuButton(Component.literal("Reset positions")) {
            screenWidgets.forEach { widget ->
                widget.x = width / 2
                widget.y = height / 2
                widget.scale = 1f
                widget.anchor = Anchor.CENTER
            }
        }.apply {
            themed()
            sizing(Sizing.expand(), Sizing.fill())
            tooltip(Component.literal("Center the visible widgets and reset their scales."))
        }

        toolbar.child(resetButton)
        toolbar.child(MenuButton(Component.literal("Done")) { onClose() }.sizing(Sizing.fixed(40), Sizing.fill()))
        adapter.rootComponent.child(toolbar)
        adapter.inflateAndMount()
        super.init()
    }

    override fun removed() {
        texture.close()
        WidgetManager.save()
        ui?.dispose()
        ui = null
        sidebar = null
        super.removed()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX, mouseY)
        // Widgets and their stored positions use Minecraft GUI coordinates, exactly like the live HUD.
        // Keep that one coordinate space for outlines, pointer input and saved positions.
        graphics.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.SIDEBAR, 25))
        screenWidgets.forEach { widget ->
            val outlineColor = MenuTheme.alpha(if (clickedWidget == widget || selectedWidget == widget) MenuTheme.ACCENT else MenuTheme.MUTED)
            graphics.outline(widget.renderX, widget.renderY, widget.width, widget.height, outlineColor)
            if (selectedWidget == widget || clickedWidget == widget) {
                val labelY = (widget.renderY + widget.height + 4).coerceAtMost(height - MenuCanvas.lineHeight()).coerceAtLeast(0)
                graphics.text(MC.font, "${widget.title} | X: ${widget.x} Y: ${widget.y} Scale: ${widget.scale}",
                    widget.renderX.coerceAtLeast(0), labelY, outlineColor, false)
            }
        }

        if (anchorSelect) {
            selectedWidget?.let { widget ->
                Anchor.entries.forEach { anchor ->
                    val x = widget.renderX + (anchor.x * widget.width).toInt()
                    val y = widget.renderY + (anchor.y * widget.height).toInt()
                    graphics.fill(x - 2, y - 2, x + 2, y + 2, if (selectedAnchor == anchor) 0xFF55FF55.toInt() else if (widget.anchor == anchor) 0xFFFFFF55.toInt() else 0xFFFF5555.toInt())
                }
            }
        }

        if (controlsVisible && clickedWidget == null) {
            val help = hudEditorHelp(width)
            val lineHeight = MenuCanvas.lineHeight()
            help.forEachIndexed { index, line -> graphics.text(MC.font, line, 8, height - 6 - (help.size - index) * lineHeight,
                MenuTheme.alpha(MenuTheme.MUTED), false) }
            super.extractRenderState(graphics, mouseX, mouseY, a)
            ui?.drawTooltip(graphics, mouseX, mouseY, a)
        }
        texture.render(output, canvas.finish(width, height), width, height, canvas.revision)
    }


    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        selectedWidget = screenWidgets.asReversed().firstOrNull {
            !hudEditorControlAt(ui?.rootComponent, mouseX, mouseY, controlsVisible) && it.inBounds(mouseX.toInt(), mouseY.toInt())
        }

        selectedAnchor = if (anchorSelect) selectedWidget?.getClosestAnchor(mouseX.toInt(), mouseY.toInt()) else null

        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseClicked(button: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(button, doubled)) return true
        if (button.button() == 1) {
            val widget = screenWidgets.asReversed().firstOrNull { it.inBounds(button.x.toInt(), button.y.toInt()) } ?: return false
            WidgetManager.save()
            MC.instance.setScreen(ThemeEditorScreen(this, widget))
            return true
        }
        if (button.button() != 0) return false
        clickedWidget = screenWidgets.asReversed().firstOrNull {
            it.inBounds(button.x.toInt(), button.y.toInt())
        }

        mouseRelX = button.x.toInt() - (clickedWidget?.x ?: 0)
        mouseRelY = button.y.toInt() - (clickedWidget?.y ?: 0)

        return clickedWidget != null
    }

    override fun mouseReleased(button: MouseButtonEvent): Boolean {
        if (button.button() == 0 && clickedWidget != null) {
            clickedWidget = null
            return true
        }
        return super.mouseReleased(button)
    }

    // Minecraft 26.1 consumes clicks whenever getChildAt finds a child, even if it declines the click.
    // Keep vanilla focus/drag handling for actual controls, and pass empty space to HUD hit testing.
    override fun getChildAt(x: Double, y: Double): Optional<GuiEventListener> =
        super.getChildAt(x, y).filter { it !== ui || hudEditorControlAt(ui?.rootComponent, x, y, controlsVisible && clickedWidget == null) }

    override fun mouseDragged(button: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (button.button() == 0) {
            clickedWidget?.let { widget ->
                widget.x = hudEditorCoordinate(button.x, mouseRelX, widget.anchor.x, widget.width, width, snapPosition)
                widget.y = hudEditorCoordinate(button.y, mouseRelY, widget.anchor.y, widget.height, height, snapPosition)
                return true
            }
        }

        return super.mouseDragged(button, offsetX, offsetY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmt: Double, verticalAmt: Double): Boolean {
        if (hudEditorControlAt(ui?.rootComponent, mouseX, mouseY, controlsVisible)) return super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
        screenWidgets.asReversed().firstOrNull { it.inBounds(mouseX.toInt(), mouseY.toInt()) }?.let {
            it.scale += verticalAmt.toFloat() * 0.1f
            it.scale = (round(it.scale * 10) / 10).coerceIn(0.1f, 3f) // get rid of weird floating point errors
            return true
        }

        return controlsVisible && super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == GLFW.GLFW_KEY_H && !input.hasControlDown() && !input.hasAltDown()) {
            controlsVisible = !controlsVisible
            if (!controlsVisible) { setFocused(null); ui?.cursorAdapter?.applyStyle(CursorStyle.NONE) }
            return true
        }
        snapPosition = input.hasShiftDown()
        anchorSelect = input.hasControlDown()
        if (!controlsVisible) {
            if (input.isEscape) { onClose(); return true }
            return false
        }
        return super.keyPressed(input)
    }

    override fun keyReleased(input: KeyEvent): Boolean {
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
