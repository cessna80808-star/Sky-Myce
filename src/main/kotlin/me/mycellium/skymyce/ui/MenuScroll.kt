package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.config.misc.ThemeConfig

/** Native layout, clipping, focus and scrollbar dragging, with a clock-driven wheel timeline. */
class MenuScroll<C : UIComponent>(horizontal: Sizing, vertical: Sizing, child: C, clock: () -> Long = System::nanoTime) :
    ScrollContainer<C>(ScrollDirection.VERTICAL, horizontal, vertical, child) {
    private val position = MotionValue(0.0, clock)
    private var wheelStep = 15

    override fun parentUpdate(delta: Float, mouseX: Int, mouseY: Int) {
        // The superclass only interpolates this offset; retained component updates run before this hook.
        position.animateTo(scrollOffset, 145, MotionEasing.QUICK, ThemeConfig.animationMode)
        currentScrollPosition = position.sample().coerceIn(0.0, maxScroll + .5)
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
    override fun scrollBy(offset: Double, instant: Boolean, showScrollbar: Boolean) {
        val displayed = position.sample()
        super.scrollBy(offset, instant, showScrollbar)
        if (instant || !showScrollbar || ThemeConfig.animationMode == GuiMotion.OFF) position.snap(scrollOffset)
        else {
            position.snap(displayed)
            position.animateTo(scrollOffset, 145, MotionEasing.QUICK, ThemeConfig.animationMode)
        }
        currentScrollPosition = position.sample()
    }
}

fun <C : UIComponent> menuScroll(horizontal: Sizing, vertical: Sizing, child: C): MenuScroll<C> = MenuScroll(horizontal, vertical, child)
