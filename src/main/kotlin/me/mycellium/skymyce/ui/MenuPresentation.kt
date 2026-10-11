package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import io.wispforest.owo.ui.core.UIComponent
import io.wispforest.owo.ui.core.ParentUIComponent
import me.mycellium.skymyce.SkyMyce

internal val menuIntroduction = MenuIntroduction({ ThemeConfig.menuIntroPlayed }, {
    ThemeConfig.menuIntroPlayed = true
    SkyMyce.config.save()
})
internal val menuReveals = MenuRevealState()

/** First viewport reveal and elevation share one pose, without changing retained layout. */
internal class MenuRevealCard(private val key: String, horizontal: Sizing, vertical: Sizing,
                              algorithm: FlowLayout.Algorithm = FlowLayout.Algorithm.VERTICAL) : FlowLayout(horizontal, vertical, algorithm) {
    private val hover = MotionValue(0.0)
    private var painted = MotionPose(alpha = 0.0)
    private var paintedClip: MenuRect? = null
    private var logicalHover = false
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        val clip = g.canvasClip
        val matrix = g.pose()
        val clipRect = clip?.let { MenuRect((it.x - matrix.m20()) / matrix.m00().toDouble(),
            (it.y - matrix.m21()) / matrix.m11().toDouble(), it.width / matrix.m00().toDouble(), it.height / matrix.m11().toDouble()) }
        val visible = clipRect == null || x() < clipRect.x + clipRect.width && x() + width() > clipRect.x &&
            y() < clipRect.y + clipRect.height && y() + height() > clipRect.y
        paintedClip = if (visible) clipRect else MenuRect(0.0, 0.0, 0.0, 0.0)
        val mode = ThemeConfig.animationMode; val profile = mode.profile
        val reveal = menuReveals.progress(key, visible, mode)
        if (!visible) return
        hover.animateTo(if (logicalHover ||
            menuComponentAttached(this, focusHandler()?.focused())) 1.0 else 0.0, 120, profile.hoverEasing, mode)
        val lift = hover.sample().coerceIn(0.0, 1.04)
        painted = MotionPose(alpha = reveal, y = (1 - reveal) * profile.revealOffset - lift * profile.cardLift,
            scale = 1 + lift * profile.hoverScale, pivotX = x() + width() / 2.0, pivotY = y() + height() / 2.0)
        g.layer(painted.alpha, painted.x, painted.y, painted.scale, painted.pivotX, painted.pivotY) {
            super.draw(graphics, mouseX, mouseY, partialTicks, delta)
        }
    }
    fun pointer(x: Double, y: Double): Pair<Double, Double>? {
        logicalHover = painted.alpha >= .01 && paintedClip?.contains(x, y) != false && isInBoundingBox(x, y)
        if (painted.alpha < .01 || paintedClip?.contains(x, y) == false) return null
        val px = painted.inverseX(x); val py = painted.inverseY(y)
        return if (isInBoundingBox(px, py)) px to py else null
    }
    fun vacated(x: Double, y: Double) = paintedClip?.contains(x, y) != false && isInBoundingBox(x, y)
}

/** Resolve the visible surface first; its old layout strip must not activate hidden controls. */
internal fun menuCardPointer(root: UIComponent, x: Double, y: Double): Pair<Double, Double>? {
    var vacated = false
    var hit: Pair<Double, Double>? = null
    fun visit(component: UIComponent) {
        if (component is MenuRevealCard) {
            val point = component.pointer(x, y)
            if (hit == null) hit = point
            if (component.vacated(x, y)) vacated = true
        }
        if (component is ParentUIComponent) for (index in component.children().indices.reversed()) {
            visit(component.children()[index])
        }
    }
    visit(root)
    return hit ?: if (vacated) -1.0 to -1.0 else null
}

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
                         private val before: (MenuGraphics) -> Unit = {},
                         private val around: ((MenuGraphics, () -> Unit) -> Unit)? = null) : FlowLayout(horizontal, vertical, algorithm) {
    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val menu = graphics as MenuGraphics
        val pose = presentation()
        val previousGroup = menu.canvas.group
        menu.canvas.group = group
        try {
            before(menu)
            val paint = {
                menu.layer(pose.alpha, pose.x, pose.y, pose.scale, pose.pivotX, pose.pivotY) {
                    super.draw(graphics, mouseX, mouseY, partialTicks, delta)
                }
            }
            if (around == null) paint() else around.invoke(menu, paint)
        } finally { menu.canvas.group = previousGroup }
    }
}

enum class MenuToastKind { SUCCESS, INFO, WARNING, ERROR }

/** A bounded screen-local stack. Messages are queued by actions, never by paint or every tick. */
internal class MenuToasts(private val clock: () -> Long = System::nanoTime) {
    private class Toast(val text: String, val kind: MenuToastKind, val born: Long, clock: () -> Long) {
        val position = MotionValue(0.0, clock)
        var positioned = false
        var wrapWidth = -1
        var lines = emptyList<FormattedCharSequence>()
        fun age(now: Long) = (now.toDouble() - born.toDouble()).coerceAtLeast(0.0) / 1_000_000.0
    }
    private val messages = ArrayList<Toast>(3)
    private val lifetimeMillis = 3600.0
    fun show(message: String, kind: MenuToastKind) {
        val now = clock()
        messages.removeAll { it.age(now) >= lifetimeMillis }
        val text = message.take(256).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        if (text.isBlank()) return
        if (messages.lastOrNull()?.text == text && messages.lastOrNull()?.kind == kind) return
        if (messages.size == 3) messages.removeAt(0)
        messages += Toast(text, kind, now, clock)
    }
    fun clear() { messages.clear() }
    fun draw(g: MenuGraphics, width: Int, height: Int) {
        val now = clock()
        messages.removeAll { it.age(now) >= lifetimeMillis }
        if (messages.isEmpty()) return
        val mode = ThemeConfig.animationMode
        val w = minOf(280, width - 24).coerceAtLeast(1)
        val lineHeight = MenuCanvas.lineHeight()
        var bottom = height - 12
        val iterator = messages.asReversed().iterator()
        while (iterator.hasNext()) {
            val toast = iterator.next()
            val age = toast.age(now)
            // Birth and expiry belong to the action, so render stalls cannot restart or extend a toast.
            val visible = motionEnvelope(age, lifetimeMillis, mode)
            if (toast.wrapWidth != w) {
                toast.wrapWidth = w
                toast.lines = menuTextLines(Component.literal(toast.text), (w - 24).coerceAtLeast(1)).take(3)
            }
            val h = toast.lines.size * lineHeight + 20
            bottom -= h
            if (!toast.positioned || mode.profile.pageOffset == 0.0) {
                toast.position.snap(bottom.toDouble()); toast.positioned = true
            } else toast.position.animateTo(bottom.toDouble(), 180, mode = mode, now = now)
            val top = toast.position.sample(now)
            val move = (1 - visible) * mode.profile.pageOffset
            g.layer(alpha = visible, x = move, y = top) {
                val x = width - w - 12
                MenuTheme.shadow(g, x, 0, w, h, .7)
                MenuTheme.box(g, x, 0, w, h, MenuTheme.SIDEBAR)
                val color = when (toast.kind) {
                    MenuToastKind.SUCCESS -> MenuTheme.GREEN; MenuToastKind.INFO -> MenuTheme.ACCENT
                    MenuToastKind.WARNING -> MenuTheme.YELLOW; MenuToastKind.ERROR -> MenuTheme.RED
                }
                MenuTheme.rounded(g, x + 7, 8, 3, h - 18, color, 1)
                toast.lines.forEachIndexed { i, line -> g.styledText(line, x + 16.0, 7.0 + i * lineHeight, MenuTheme.alpha(MenuTheme.TEXT)) }
                g.fill(x + 7, h - 4, x + 7 + ((w - 14) * (1 - age / lifetimeMillis).coerceIn(0.0, 1.0)).toInt(), h - 3, MenuTheme.alpha(color, 70))
            }
            bottom -= 6
        }
    }
}
