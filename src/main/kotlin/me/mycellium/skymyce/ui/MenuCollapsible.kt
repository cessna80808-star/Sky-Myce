package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.container.CollapsibleContainer
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.network.chat.Component
import java.util.function.Consumer
import kotlin.math.ceil

/** Native toggle/input/focus behavior with a clipped, clock-driven content height. */
class MenuCollapsible(horizontal: Sizing, vertical: Sizing, title: Component, expanded: Boolean = false) :
    CollapsibleContainer(horizontal, vertical, title, expanded) {
    private val heightMotion = MotionValue(0.0)
    private val arrowMotion = MotionValue(if (expanded) 1.0 else 0.0)
    private val hoverMotion = MotionValue(0.0)
    private val groupKey = "collapsible:${System.identityHashCode(this)}"
    private var initialized = false
    private var expandedHeight = 0.0
    private var collapseHeight = 1.0
    private var outgoing: MenuCanvas.Snapshot? = null
    private var lastCanvas: MenuCanvas? = null
    private var lastGroup: String? = null
    private var paintedX = 0.0
    private var paintedY = 0.0

    private val body = object : FlowLayout(Sizing.content(), Sizing.fixed(0), Algorithm.VERTICAL) {
        override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val g = graphics as MenuGraphics
            val oldGroup = g.canvas.group
            val tag = "${oldGroup ?: "ui"}/$groupKey"
            g.canvas.group = tag
            try {
                if (this@MenuCollapsible.expanded) {
                    val alpha = if (expandedHeight > 0) heightMotion.sample() / expandedHeight else 1.0
                    g.layer(alpha = alpha) { super.draw(graphics, mouseX, mouseY, partialTicks, delta) }
                    lastCanvas = g.canvas; lastGroup = tag
                    paintedX = worldX(g); paintedY = worldY(g)
                } else outgoing?.let { image ->
                    g.enableScissor(x(), y(), x() + width(), y() + height())
                    try {
                        g.layer {
                            g.canvas.replay(image, heightMotion.sample() / collapseHeight,
                                worldX(g) - paintedX, worldY(g) - paintedY, g.canvas.clip)
                        }
                    } finally { g.disableScissor() }
                }
            } finally { g.canvas.group = oldGroup }
        }

        // Keep owo's traversal, excluding controls that are still below the unfolding clip.
        override fun forEachDescendant(action: Consumer<UIComponent>) {
            super.forEachDescendant { component ->
                if (component === this || component.y() >= y() && component.y() + component.height() <= y() + height())
                    action.accept(component)
            }
        }

        private fun worldX(g: MenuGraphics) = g.pose().m00() * x().toDouble() + g.pose().m10() * y() + g.pose().m20()
        private fun worldY(g: MenuGraphics) = g.pose().m01() * x().toDouble() + g.pose().m11() * y() + g.pose().m21()
    }

    init {
        titleLayout.clearChildren()
        titleLayout.child(MenuLabel(title).color(Color.ofRgb(MenuTheme.TEXT)).cursorStyle(CursorStyle.HAND))
        titleLayout.child(object : BaseUIComponent() {
            init { sizing(Sizing.fixed(8), Sizing.fixed(10)); margins(Insets.of(0, 0, 5, 10)); cursorStyle(CursorStyle.HAND) }
            override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
                graphics.pose().pushMatrix()
                try {
                    graphics.pose().translate(x() + 4f, y() + 5f)
                    graphics.pose().rotate((arrowMotion.sample() * Math.PI / 2).toFloat())
                    graphics.pose().translate(-x() - 4f, -y() - 5f)
                    val color = Color.ofRgb(MenuTheme.blend(MenuTheme.MUTED, MenuTheme.ACCENT, (hoverMotion.sample() * 100).toInt()))
                    graphics.drawLine(x() + 2, y() + 1, x() + 6, y() + 5, 1.0, color)
                    graphics.drawLine(x() + 6, y() + 5, x() + 2, y() + 9, 1.0, color)
                } finally { graphics.pose().popMatrix() }
            }
        })
        titleLayout.surface(Surface { g, c ->
            val opacity = (hoverMotion.sample().coerceIn(0.0, 1.0) * 24).toInt()
            if (opacity > 0) MenuTheme.rounded(g, c.x(), c.y(), c.width(), c.height(), MenuTheme.alpha(MenuTheme.CARD, opacity))
        })
        // Preserve the original content layout used by native child()/children()/toggleExpansion().
        children.remove(contentLayout)
        body.child(contentLayout)
        children.add(body)
    }

    override fun toggleExpansion() {
        val currentHeight = heightMotion.sample().coerceAtLeast(0.0)
        if (expanded) {
            outgoing = if (ThemeConfig.animationMode == GuiMotion.OFF) null else lastGroup?.let { lastCanvas?.snapshot(it) }
            collapseHeight = currentHeight.coerceAtLeast(1.0)
            body.horizontalSizing(Sizing.fixed(body.width()))
            var focused = focusHandler()?.focused()
            while (focused != null) {
                if (focused === contentLayout) {
                    focusHandler()?.focus(this, UIComponent.FocusSource.KEYBOARD_CYCLE)
                    break
                }
                focused = focused.parent()
            }
        } else {
            outgoing = null
            body.horizontalSizing(Sizing.content())
        }
        super.toggleExpansion()
        expandedHeight = if (expanded) contentLayout.fullSize().height().toDouble() else expandedHeight
        heightMotion.animateTo(if (expanded) expandedHeight else 0.0, 190, MotionEasing.QUICK, ThemeConfig.animationMode)
        arrowMotion.animateTo(if (expanded) 1.0 else 0.0, 160, MotionEasing.QUICK, ThemeConfig.animationMode)
        applyHeight()
    }

    override fun parentUpdate(delta: Float, mouseX: Int, mouseY: Int) {
        super.parentUpdate(delta, mouseX, mouseY)
        if (expanded) expandedHeight = contentLayout.fullSize().height().toDouble()
        if (!initialized) {
            heightMotion.snap(if (expanded) expandedHeight else 0.0)
            initialized = true
        }
        heightMotion.animateTo(if (expanded) expandedHeight else 0.0, 190, MotionEasing.QUICK, ThemeConfig.animationMode)
        arrowMotion.animateTo(if (expanded) 1.0 else 0.0, 160, MotionEasing.QUICK, ThemeConfig.animationMode)
        val headerHovered = mouseX >= x() && mouseX < x() + width() &&
            mouseY >= titleLayout.y() && mouseY < titleLayout.y() + titleLayout.height()
        hoverMotion.animateTo(if (headerHovered || focusHandler()?.focused() === this) 1.0 else 0.0,
            110, MotionEasing.QUICK, ThemeConfig.animationMode)
        applyHeight()
        if (!expanded && !heightMotion.running) {
            outgoing = null
            if (!body.horizontalSizing().get().isContent) body.horizontalSizing(Sizing.content())
        }
    }

    private fun applyHeight() {
        val height = ceil(heightMotion.sample().coerceAtLeast(0.0)).toInt()
        if (body.verticalSizing().get().value != height) body.verticalSizing(Sizing.fixed(height))
    }

    internal fun releaseMotion() {
        heightMotion.snap(if (expanded) expandedHeight else 0.0)
        arrowMotion.snap(if (expanded) 1.0 else 0.0)
        hoverMotion.snap(0.0)
        outgoing = null; lastCanvas = null; lastGroup = null
        applyHeight()
        if (!expanded && !body.horizontalSizing().get().isContent) body.horizontalSizing(Sizing.content())
    }

    override fun dismount(reason: UIComponent.DismountReason) {
        if (reason == UIComponent.DismountReason.REMOVED) releaseMotion()
        super.dismount(reason)
    }
}
