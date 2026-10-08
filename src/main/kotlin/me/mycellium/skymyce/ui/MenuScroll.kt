package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.config.misc.ThemeConfig

/** Native layout, clipping, focus and scrollbar dragging, with a clock-driven wheel timeline. */
class MenuScroll<C : UIComponent>(horizontal: Sizing, vertical: Sizing, child: C, clock: () -> Long = System::nanoTime) :
    ScrollContainer<C>(ScrollDirection.VERTICAL, horizontal, vertical, child) {
    private val position = MotionValue(0.0, clock)
    private var wheelStep = 15
    val scrollPosition get() = currentScrollPosition
    val scrollTarget get() = scrollOffset

    override fun layout(space: Size) {
        super.layout(space)
        currentScrollPosition = position.clampScroll(maxScroll + .5)
    }

    override fun parentUpdate(delta: Float, mouseX: Int, mouseY: Int) {
        // The superclass only interpolates this offset; retained component updates run before this hook.
        currentScrollPosition = position.scrollTo(scrollOffset, ThemeConfig.animationMode).coerceIn(0.0, maxScroll + .5)
    }
    override fun scrollStep(step: Int): MenuScroll<C> {
        wheelStep = if (step > 0) step else 15
        // Keep subpixel drawing and its hit geometry together rather than snapping each wheel event.
        super.scrollStep(0)
        return this
    }
    override fun onMouseScroll(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        if (child.onMouseScroll(x() + mouseX - child.x(), y() + mouseY - child.y(), amount)) return true
        scrollBy(-amount * wheelStep, false, true)
        return true
    }
    public override fun scrollBy(offset: Double, instant: Boolean, showScrollbar: Boolean) {
        super.scrollBy(offset, instant, showScrollbar)
        currentScrollPosition = position.scrollTo(scrollOffset, ThemeConfig.animationMode, instant || !showScrollbar)
    }

    override fun drawChildren(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float,
                              children: MutableList<out UIComponent>) {
        // owo 0.13.1 discards every negative fractional offset in ScrollContainer.draw().
        // Restore that paint translation while retaining its native layout, clip and scrollbar.
        g.pose().pushMatrix()
        try {
            g.pose().translate(0f, -(currentScrollPosition % 1.0).toFloat())
            super.drawChildren(g, mouseX, mouseY, partialTicks, delta, children)
        } finally { g.pose().popMatrix() }
    }
}

fun <C : UIComponent> menuScroll(horizontal: Sizing, vertical: Sizing, child: C): MenuScroll<C> = MenuScroll(horizontal, vertical, child)
