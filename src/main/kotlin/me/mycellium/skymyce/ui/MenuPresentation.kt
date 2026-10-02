package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.network.chat.Component
import io.wispforest.owo.ui.core.UIComponent

/** Removed descendants can remain in owo's focus handler; never dispatch input to that old tree. */
internal fun menuComponentAttached(root: UIComponent, focused: UIComponent?): Boolean {
    var component = focused
    while (component != null) {
        if (component === root) return true
        component = component.parent()
    }
    return false
}

/** Retained owo layout plus one visual layer; its pose is also used by MenuScreen hit testing. */
internal class MenuLayer(horizontal: Sizing, vertical: Sizing, algorithm: Algorithm,
                         private val group: String, private val presentation: () -> MotionPose,
                         private val before: (MenuGraphics) -> Unit = {}) : FlowLayout(horizontal, vertical, algorithm) {
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val menu = graphics as MenuGraphics
        val pose = presentation()
        val previousGroup = menu.canvas.group
        menu.canvas.group = group
        try {
            before(menu)
            menu.layer(pose.alpha, pose.x, pose.y, pose.scale, pose.pivotX, pose.pivotY) {
                super.draw(graphics, mouseX, mouseY, partialTicks, delta)
            }
        } finally { menu.canvas.group = previousGroup }
    }
}

enum class MenuToastKind { SUCCESS, INFO, WARNING, ERROR }

/** A bounded screen-local stack. Messages are queued by actions, never by paint or every tick. */
internal class MenuToasts {
    private class Toast(val text: String, val kind: MenuToastKind, val born: Long) {
        val visibility = MotionValue(0.0)
        val position = MotionValue(0.0)
        var leaving = false
    }
    private val messages = ArrayList<Toast>(3)
    fun show(message: String, kind: MenuToastKind) {
        val text = message.take(256)
        if (text.isBlank()) return
        if (messages.lastOrNull()?.text == text && messages.lastOrNull()?.kind == kind) return
        if (messages.size == 3) messages.removeAt(0)
        messages += Toast(text, kind, System.nanoTime())
    }
    fun clear() { messages.clear() }
    fun draw(g: MenuGraphics, width: Int, height: Int) {
        val now = System.nanoTime()
        var bottom = height - 12
        val iterator = messages.asReversed().iterator()
        while (iterator.hasNext()) {
            val toast = iterator.next()
            val age = (now - toast.born).coerceAtLeast(0) / 1_000_000.0
            toast.leaving = age >= 3600
            toast.visibility.animateTo(if (toast.leaving) 0.0 else 1.0, if (toast.leaving) 140 else 200,
                MotionEasing.QUICK, ThemeConfig.animationMode, now = now)
            val visible = toast.visibility.sample(now).coerceIn(0.0, 1.0)
            val w = minOf(280, width - 24).coerceAtLeast(1)
            val lines = menuTextLines(Component.literal(toast.text), (w - 24).coerceAtLeast(1)).take(3)
            val h = lines.size * MenuCanvas.lineHeight() + 20
            bottom -= h
            if (toast.position.target == 0.0) toast.position.snap(bottom.toDouble())
            toast.position.animateTo(bottom.toDouble(), 180, mode = ThemeConfig.animationMode, now = now)
            val top = toast.position.sample(now)
            val move = if (ThemeConfig.animationMode == GuiMotion.FULL) (1 - visible) * 12 else 0.0
            g.layer(alpha = visible, x = move) {
                val x = width - w - 12
                MenuTheme.box(g, x, top.toInt(), w, h, MenuTheme.SIDEBAR)
                val color = when (toast.kind) {
                    MenuToastKind.SUCCESS -> MenuTheme.GREEN; MenuToastKind.INFO -> MenuTheme.ACCENT
                    MenuToastKind.WARNING -> MenuTheme.YELLOW; MenuToastKind.ERROR -> MenuTheme.RED
                }
                MenuTheme.rounded(g, x + 7, top.toInt() + 8, 3, h - 18, color, 1)
                lines.forEachIndexed { i, line -> g.styledText(line, x + 16.0, top + 7 + i * MenuCanvas.lineHeight(), MenuTheme.alpha(MenuTheme.TEXT)) }
                g.fill(x + 7, top.toInt() + h - 4, x + 7 + ((w - 14) * (1 - age / 3600).coerceIn(0.0, 1.0)).toInt(), top.toInt() + h - 3, MenuTheme.alpha(color, 70))
            }
            bottom -= 6
        }
        messages.removeAll { it.leaving && !it.visibility.running && it.visibility.sample() == 0.0 }
    }
}
