package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.ParentUIComponent
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

enum class GuiMotion(private val title: String) {
    LIGHT("Light"), MEDIUM("Medium"), HEAVY("Heavy"),
    /** Kept as a saved-config alias; old Full configurations use the Medium personality. */
    FULL("Medium"), REDUCED("Reduced"), OFF("Off");

    override fun toString() = title

    val profile: MotionProfile get() = when (this) {
        LIGHT -> MotionProfile.LIGHT
        MEDIUM, FULL -> MotionProfile.MEDIUM
        HEAVY -> MotionProfile.HEAVY
        REDUCED, OFF -> MotionProfile.REDUCED
    }

    companion object {
        val choices = listOf(LIGHT, MEDIUM, HEAVY, REDUCED, OFF)
    }
}

/** Shared physical treatment; raster quality remains a separate rendering preference. */
data class MotionProfile(
    val cardLift: Double,
    val hoverScale: Double,
    val pressScale: Double,
    val shadowStrength: Double,
    val glowStrength: Double,
    val shellOffset: Double,
    val shellScale: Double,
    val pageOffset: Double,
    val pageScale: Double,
    val revealOffset: Double,
    val staggerMillis: Long,
    val hoverEasing: MotionEasing,
    val layoutMillis: Long,
    val durationScale: Double = 1.0,
    val ambient: Boolean = false,
    val settleStrength: Double = .85,
) {
    companion object {
        val LIGHT = MotionProfile(1.25, .0015, .012, .22, 0.0, 3.0, .008, 6.0, .003, 3.0, 8, MotionEasing.QUICK, 185, .68)
        val MEDIUM = MotionProfile(3.5, .0055, .025, .65, .55, 8.0, .018, 14.0, .008, 7.0, 18, MotionEasing.SETTLE, 260)
        val HEAVY = MotionProfile(6.0, .009, .035, 1.0, 1.0, 12.0, .026, 20.0, .013, 11.0, 28, MotionEasing.SETTLE, 290, ambient = true, settleStrength = 1.0)
        val REDUCED = MotionProfile(0.0, 0.0, 0.0, .15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, MotionEasing.QUICK, 90)
    }
}

enum class GuiQuality(
    private val title: String,
    val rasterScale: Double,
    val shadowLayers: Int,
    val glow: Boolean,
    val ambient: Boolean,
    val ambientIntervalNanos: Long,
) {
    /** Adapts between the tiers below from measured frame times (see [AutoGraphics]); renderers read [AutoGraphics.quality]. */
    AUTO("Auto (recommended)", 1.5, 2, true, true, 100_000_000),
    EXTREMELY_LIGHT("Extremely Light", 1.0, 0, false, false, 0),
    LIGHT("Light", 1.25, 1, false, false, 0),
    MEDIUM("Medium", 1.5, 2, true, true, 100_000_000),
    HIGH("High", 2.0, 3, true, true, 50_000_000);

    override fun toString() = title
}

enum class MotionEasing {
    LINEAR, SMOOTH, QUICK, SETTLE;

    fun apply(progress: Double, settleStrength: Double = .85): Double {
        val p = if (progress.isNaN()) 0.0 else progress.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> p
            SMOOTH -> p * p * (3.0 - 2.0 * p)
            QUICK -> 1.0 - (1.0 - p) * (1.0 - p) * (1.0 - p)
            SETTLE -> {
                val tail = p - 1.0
                val strength = settleStrength.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: .85
                1.0 + (1.0 + strength) * tail * tail * tail + strength * tail * tail
            }
        }
    }
}

/** Decorative detail follows a transition, then keeps its last pose without dirtying idle frames. */
internal fun menuAmbientStep(previous: Long, now: Long, interval: Long, changing: Boolean): Long =
    if (previous == Long.MIN_VALUE || changing) now / interval.coerceAtLeast(1) else previous

/** Fade an existing timed row entirely inside its logical lifetime; presentation never extends expiry. */
fun motionEnvelope(ageMillis: Double, lifetimeMillis: Double, mode: GuiMotion): Double {
    if (!ageMillis.isFinite() || !lifetimeMillis.isFinite() || lifetimeMillis <= 0.0) return 0.0
    val age = ageMillis.coerceAtLeast(0.0)
    if (age >= lifetimeMillis) return 0.0
    if (mode == GuiMotion.OFF) return 1.0
    val entrance = if (mode == GuiMotion.REDUCED) 90.0 else 180.0 * mode.profile.durationScale
    val exit = if (mode == GuiMotion.REDUCED) 70.0 else 140.0 * mode.profile.durationScale
    return minOf(MotionEasing.SMOOTH.apply(age / entrance), MotionEasing.SMOOTH.apply((lifetimeMillis - age) / exit))
}

/** A sampled timeline, owned by its control/screen. No timers or background work outlive the owner. */
class MotionValue(initial: Double, private val clock: () -> Long = System::nanoTime) {
    private var value = initial.takeIf(Double::isFinite) ?: 0.0
    private var from = value
    private var destination = value
    private var startedAt = clock()
    private var lastSampleAt = startedAt
    private var durationNanos = 0.0
    private var delayNanos = 0.0
    private var easing = MotionEasing.SMOOTH
    private var mode = GuiMotion.FULL
    private var active = false

    /** True until the next sample reaches the destination, or the owner cancels the timeline. */
    val running get() = active
    val target get() = destination

    fun sample(now: Long = clock()): Double {
        lastSampleAt = maxOf(lastSampleAt, now)
        if (!active) return value
        // Subtract as doubles so an unusually large timestamp jump cannot overflow a signed Long.
        val elapsed = lastSampleAt.toDouble() - startedAt.toDouble() - delayNanos
        if (elapsed <= 0.0) return from
        val progress = (elapsed / durationNanos).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            value = destination
            active = false
        } else {
            val weight = easing.apply(progress, mode.profile.settleStrength)
            // Weighted interpolation also handles opposite, very large finite endpoints safely.
            value = (from * (1.0 - weight) + destination * weight).takeIf(Double::isFinite) ?: destination
        }
        return value
    }

    fun animateTo(
        target: Double,
        durationMillis: Long = 180,
        easing: MotionEasing = MotionEasing.SMOOTH,
        mode: GuiMotion = GuiMotion.FULL,
        delayMillis: Long = 0,
        now: Long = clock(),
    ) {
        sample(now)
        if (!target.isFinite()) return
        if (mode == GuiMotion.OFF || durationMillis <= 0) {
            snap(target)
            this.mode = mode
            return
        }
        // Render callers may request the same state every frame; its original deadline must survive.
        if (target == destination && this.mode == mode && (active || value == target)) return
        from = value
        destination = target
        startedAt = lastSampleAt
        durationNanos = (if (mode == GuiMotion.REDUCED) minOf(durationMillis, 90).toDouble()
            else durationMillis * mode.profile.durationScale) * 1_000_000.0
        // Reduced motion keeps feedback immediate, including controls with a staggered entrance.
        delayNanos = (if (mode == GuiMotion.REDUCED) 0.0 else delayMillis.coerceAtLeast(0) * mode.profile.durationScale) * 1_000_000.0
        this.easing = if ((mode == GuiMotion.REDUCED || mode == GuiMotion.LIGHT) && easing == MotionEasing.SETTLE) MotionEasing.QUICK else easing
        this.mode = mode
        active = from != destination
    }

    fun snap(value: Double) {
        if (!value.isFinite()) return
        this.value = value
        from = value
        destination = value
        active = false
    }

    /** Preserve the displayed pose when an interaction or screen transition is abandoned. */
    fun cancel() = snap(sample())
}

/** Introduction belongs to the application, rather than to any rebuilt UI tree. */
internal class MenuIntroduction(private val readPlayed: () -> Boolean, private val persistPlayed: () -> Unit) {
    private var claimed = false
    private var completed = false

    fun claim(): Boolean {
        if (claimed || readPlayed()) return false
        claimed = true
        return true
    }

    fun complete() {
        if (!claimed || completed) return
        completed = true
        if (!readPlayed()) persistPlayed()
    }
}

/** Stable logical keys survive refresh, navigation and scrolling; unseen offscreen items stay inert. */
internal class MenuRevealState(private val maximum: Int = 4096, private val clock: () -> Long = System::nanoTime) {
    private val revealed = HashMap<String, MotionValue>()
    val size get() = revealed.size

    fun progress(key: String, visible: Boolean, mode: GuiMotion, delayMillis: Long = 0, now: Long = clock()): Double {
        var timeline = revealed[key]
        if (timeline == null) {
            if (!visible) return 0.0
            // ponytail: cap retained identities; extra items appear immediately instead of evicting/replaying old reveals.
            if (revealed.size >= maximum.coerceAtLeast(0)) return 1.0
            timeline = MotionValue(0.0, clock)
            revealed[key] = timeline
        }
        timeline.animateTo(1.0, 160, MotionEasing.QUICK, mode, delayMillis, now)
        return timeline.sample(now).coerceIn(0.0, 1.0)
    }
}

/** Wheel/keyboard motion shares one retargeting path; native scrollbar drags remain immediate. */
internal fun MotionValue.scrollTo(target: Double, mode: GuiMotion, instant: Boolean = false): Double {
    if (instant) snap(target) else animateTo(target, 145, MotionEasing.QUICK, mode)
    return sample()
}

/** A shorter retained list cannot leave its visible timeline past the new scroll boundary. */
internal fun MotionValue.clampScroll(maximum: Double): Double {
    val sampled = sample()
    val displayed = sampled.coerceIn(0.0, maximum.coerceAtLeast(0.0))
    if (displayed != sampled) snap(displayed)
    return displayed
}

/** Screen-space transform shared by paint and input; alpha does not alter pointer coordinates. */
data class MotionPose(
    val alpha: Double = 1.0,
    val scale: Double = 1.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val pivotX: Double = 0.0,
    val pivotY: Double = 0.0,
) {
    fun inverseX(pointerX: Double) = inverse(pointerX, x, pivotX)
    fun inverseY(pointerY: Double) = inverse(pointerY, y, pivotY)

    private fun inverse(pointer: Double, translation: Double, pivot: Double): Double {
        val point = pointer.takeIf(Double::isFinite) ?: 0.0
        val offset = translation.takeIf(Double::isFinite) ?: 0.0
        val origin = pivot.takeIf(Double::isFinite) ?: 0.0
        val factor = scale.takeIf { it.isFinite() && kotlin.math.abs(it) > 1e-9 } ?: 1.0
        return (origin + (point - origin - offset) / factor).takeIf(Double::isFinite) ?: point
    }
}

internal val menuIntroduction = MenuIntroduction({ ThemeConfig.menuIntroPlayed }, {
    ThemeConfig.menuIntroPlayed = true
    SkyMyce.config.save()
})
internal val menuReveals = MenuRevealState()

/** Layout rows reserve logical space; only scroll viewports should clip moving menu controls. */
internal fun allowMenuMotionOverflow(component: UIComponent) {
    if (component is FlowLayout) component.allowOverflow(true)
    if (component is ParentUIComponent) {
        val children = component.children()
        var index = 0
        while (index < children.size) allowMenuMotionOverflow(children[index++])
    }
}

/** A retained results region fades independently of its header, tabs and filter controls. */
internal class MenuSwap(key: String, horizontal: Sizing, vertical: Sizing,
                        clock: () -> Long = System::nanoTime) : FlowLayout(horizontal, vertical, Algorithm.VERTICAL) {
    private val groupKey = "swap:$key:${System.identityHashCode(this)}"
    private val progress = MotionValue(1.0, clock)
    private var outgoing: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    private var lastCanvas: MenuCanvas? = null
    private var lastGroup: String? = null
    private var paintedWidth = 0
    private var paintedHeight = 0
    internal val transitioning get() = outgoing != null

    /** Call before replacing results. An interrupted fade captures its last displayed mixture. */
    fun change() {
        outgoing = if (ThemeConfig.animationMode == GuiMotion.OFF) null else lastGroup?.let { lastCanvas?.snapshot(it) }
        progress.snap(if (outgoing == null) 1.0 else 0.0)
        if (outgoing != null) progress.animateTo(1.0, 180, MotionEasing.QUICK, ThemeConfig.animationMode)
    }

    internal fun releaseMotion() {
        outgoing = null; progress.snap(1.0); lastCanvas = null; lastGroup = null
    }

    override fun dismount(reason: UIComponent.DismountReason) {
        if (reason == UIComponent.DismountReason.REMOVED) releaseMotion()
        super.dismount(reason)
    }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val g = graphics as MenuGraphics
        if (outgoing != null && (width() != paintedWidth || height() != paintedHeight)) releaseMotion()
        progress.animateTo(1.0, 180, MotionEasing.QUICK, ThemeConfig.animationMode)
        val amount = progress.sample().coerceIn(0.0, 1.0)
        if (amount >= 1.0) outgoing = null
        val previousGroup = g.canvas.group
        val tag = "${previousGroup ?: "ui"}/$groupKey"
        g.canvas.group = tag
        // This boundary stays stationary even when menu layout rows allow hover-motion overflow.
        g.enableScissor(x(), y(), x() + width(), y() + height())
        try {
            val boundary = g.canvasClip
            val snapshot = outgoing
            if (snapshot == null || boundary == null) super.draw(graphics, mouseX, mouseY, partialTicks, delta)
            else g.canvas.transition(snapshot, amount, boundary) {
                super.draw(graphics, mouseX, mouseY, partialTicks, delta)
            }
            lastCanvas = g.canvas; lastGroup = tag
            paintedWidth = width(); paintedHeight = height()
        } finally {
            g.disableScissor(); g.canvas.group = previousGroup
        }
    }
}

/** Keep the canvas's reusable raster blend buffers until the final regional fade has settled. */
internal fun menuSwapTransitionActive(component: UIComponent): Boolean {
    if (component is MenuSwap && component.transitioning) return true
    if (component is ParentUIComponent) {
        val children = component.children()
        var index = 0
        while (index < children.size) if (menuSwapTransitionActive(children[index++])) return true
    }
    return false
}

/** Cards reveal and highlight in place; only the shared page transition moves their controls. */
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
        val emphasis = hover.sample().coerceIn(0.0, 1.0)
        painted = MotionPose(alpha = reveal)
        g.layer(alpha = painted.alpha) {
            super.draw(graphics, mouseX, mouseY, partialTicks, delta)
            if (emphasis > 0) g.roundedBorder(x(), y(), width(), height(), MenuTheme.RADIUS.toDouble(),
                MenuTheme.style.borderWidth, MenuTheme.alpha(MenuTheme.ACCENT, (emphasis * 45).toInt()))
        }
    }
    fun pointer(x: Double, y: Double): Pair<Double, Double>? {
        logicalHover = painted.alpha >= .01 && paintedClip?.contains(x, y) != false && isInBoundingBox(x, y)
        if (painted.alpha < .01 || paintedClip?.contains(x, y) == false) return null
        return if (isInBoundingBox(x, y)) x to y else null
    }
    fun vacated(x: Double, y: Double) = paintedClip?.contains(x, y) != false && isInBoundingBox(x, y)
}

/** Unrevealed or clipped cards cannot activate their retained controls. */
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
