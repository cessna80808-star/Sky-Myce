package me.mycellium.skymyce.hud.widget

import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.ParentUIComponent
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.hud.HudTheme
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

/** The full-screen adapter's empty background must not intercept widget clicks. */
internal fun hudEditorControlAt(root: ParentUIComponent?, x: Double, y: Double): Boolean =
    root?.childAt(x.toInt(), y.toInt())?.let { it !== root } == true

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
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()

    override fun init() {
        ui?.dispose()
        selectedWidget = null
        clickedWidget = null
        selectedAnchor = null
        snapPosition = false
        anchorSelect = false
        screenWidgets = WidgetManager.getActiveWidgets()

        val adapter = OwoUIAdapter.create(this, UIContainers::verticalFlow)
        ui = adapter
        adapter.rootComponent.child(menuSidebar(MenuLayout.fit(width, height), MenuPage.HUD, parent = this)
            .positioning(Positioning.absolute(8, 8)))
        val resetButton = MenuButton(Component.literal("Reset HUD Positions")) {
            screenWidgets.forEach { widget ->
                widget.x = width / 2
                widget.y = height / 2
                widget.scale = 1f
                widget.anchor = Anchor.CENTER
            }
        }.apply {
            themed()
            sizing(Sizing.fixed(150), Sizing.fixed(20))
            positioning(Positioning.absolute(this@WidgetEditorScreen.width / 2 - 75, this@WidgetEditorScreen.height - 72))
        }

        adapter.rootComponent.child(resetButton)
        adapter.inflateAndMount()
        super.init()
    }

    override fun removed() {
        texture.close()
        WidgetManager.save()
        ui?.dispose()
        ui = null
        super.removed()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX, mouseY)
        graphics.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.SIDEBAR, 85))
        screenWidgets.forEach { widget ->
            val backgroundColor = if (clickedWidget == widget) 0x88FFFF55.toInt() else if (selectedWidget == widget) 0x55FFFFFF.toInt() else 0x55000000.toInt()
            val outlineColor = 0xFF000000.toInt() or if (clickedWidget == widget) HudTheme.SECONDARY else HudTheme.ACCENT

            HudTheme.rounded(graphics, widget.renderX, widget.renderY, widget.width, widget.height, backgroundColor)
            graphics.outline(widget.renderX, widget.renderY, widget.width, widget.height, outlineColor)
            graphics.centeredText(MC.font, widget.title, widget.renderX + (widget.width / 2), widget.renderY + ((widget.height - MC.font.lineHeight) / 2), outlineColor)
            graphics.centeredText(MC.font, "X: ${widget.x} Y: ${widget.y} Scale: ${widget.scale}", widget.renderX + (widget.width / 2), widget.renderY + (widget.height) + 10, outlineColor)
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

        graphics.text(MC.font, "§e[SHIFT]§7 Toggle Grid Snap ${if (snapPosition) "§a[ON]" else "§c[OFF]"}", 10, height - 10 - MC.font.lineHeight, 0xFFFFFFFF.toInt())
        graphics.text(MC.font, "§e[SCROLL]§7 Change Scale", 10, height - 10 - (MC.font.lineHeight * 2) - 2, 0xFFFFFFFF.toInt())
        graphics.text(MC.font, "§e[CTRL]§7 Change Anchor", 10, height - 10 - (MC.font.lineHeight * 3) - 4, 0xFFFFFFFF.toInt())
        graphics.text(MC.font, "§e[RIGHT CLICK]§7 Widget theme", 10, height - 10 - (MC.font.lineHeight * 4) - 6, 0xFFFFFFFF.toInt())

        super.extractRenderState(graphics, mouseX, mouseY, a)
        ui?.drawTooltip(graphics, mouseX, mouseY, a)
        texture.render(output, canvas.finish(width, height), width, height, canvas.revision)
    }


    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        selectedWidget = screenWidgets.asReversed().firstOrNull {
            !hudEditorControlAt(ui?.rootComponent, mouseX, mouseY) && it.inBounds(mouseX.toInt(), mouseY.toInt())
        }

        if (anchorSelect) {
            selectedWidget?.let {
                selectedAnchor = it.getClosestAnchor(mouseX.toInt(), mouseY.toInt())
            }
        }

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
        super.getChildAt(x, y).filter { it !== ui || hudEditorControlAt(ui?.rootComponent, x, y) }

    override fun mouseDragged(button: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (button.button() == 0) {
            clickedWidget?.let { widget ->
                widget.x = (button.x - mouseRelX).toInt()
                widget.y = (button.y - mouseRelY).toInt()

                if (snapPosition) {
                    widget.x = (widget.x / 5) * 5
                    widget.y = (widget.y / 5) * 5
                }

                val anchorX = (widget.anchor.x * widget.width).toInt()
                val anchorY = (widget.anchor.y * widget.height).toInt()

                widget.x = widget.x.coerceIn(
                    min(anchorX, anchorX + width - widget.width),
                    max(anchorX, anchorX + width - widget.width)
                )

                widget.y = widget.y.coerceIn(
                    min(anchorY, anchorY + height - widget.height),
                    max(anchorY, anchorY + height - widget.height)
                )
                return true
            }
        }

        return super.mouseDragged(button, offsetX, offsetY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmt: Double, verticalAmt: Double): Boolean {
        if (hudEditorControlAt(ui?.rootComponent, mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
        selectedWidget?.let {
            it.scale += verticalAmt.toFloat() * 0.1f
            it.scale = (round(it.scale * 10) / 10).coerceIn(0.1f, 3f) // get rid of weird floating point errors
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmt, verticalAmt)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        snapPosition = input.hasShiftDown()
        anchorSelect = input.hasControlDown()
        return super.keyPressed(input)
    }

    override fun keyReleased(input: KeyEvent): Boolean {
        snapPosition = input.hasShiftDown()

        if (anchorSelect && !input.hasControlDown()) {
            anchorSelect = false
            selectedWidget?.let {
                it.anchor
                it.setAnchorPreservePosition(selectedAnchor ?: it.anchor)
            }
        }

        return super.keyReleased(input)
    }
}
