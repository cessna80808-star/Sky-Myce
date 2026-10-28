package me.mycellium.skymyce.ui

import com.mojang.blaze3d.pipeline.RenderPipeline
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.OwoUIGraphics
import java.awt.image.BufferedImage
import java.util.Locale
import me.mycellium.mixin.MenuTextTooltipAccessor
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.Minecraft
import net.minecraft.client.StringSplitter
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.ProgressScreen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent

internal fun menuTextWidth(text: FormattedCharSequence, size: Double = 10.0): Double {
    var width = 0.0
    text.accept { _, style, codepoint ->
        width += MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size)
        true
    }
    return width
}

internal fun menuTextLines(text: Component, width: Int, size: Double = 10.0): List<FormattedCharSequence> {
    val splitter = StringSplitter { codepoint, style ->
        MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size).toFloat()
    }
    return Language.getInstance().getVisualOrder(splitter.splitLines(text, width.coerceAtLeast(1), Style.EMPTY))
}

internal fun menuTextRuns(text: FormattedCharSequence, color: Int): List<MenuCanvas.Run> {
    val runs = ArrayList<MenuCanvas.Run>()
    val content = StringBuilder()
    var tint = color
    var bold = false
    var italic = false
    var underline = false
    fun flush() {
        if (content.isNotEmpty()) {
            runs += MenuCanvas.Run(content.toString(), tint, bold, italic, underline)
            content.setLength(0)
        }
    }
    text.accept { _, style, codepoint ->
        val nextTint = (color and -0x1000000) or (style.color?.value ?: (color and 0xFFFFFF))
        if (nextTint != tint || style.isBold != bold || style.isItalic != italic || style.isUnderlined != underline) {
            flush()
            tint = nextTint; bold = style.isBold; italic = style.isItalic; underline = style.isUnderlined
        }
        content.appendCodePoint(codepoint)
        true
    }
    flush()
    return runs
}

/** Owo supplies retained layout/input; all paint goes to our canvas, never its native render state. */
class MenuGraphics(val canvas: MenuCanvas, mouseX: Int, mouseY: Int, private val menuWidth: Int = Minecraft.getInstance().window.guiScaledWidth,
                   private val menuHeight: Int = Minecraft.getInstance().window.guiScaledHeight,
                   private val tooltipMotion: MenuTooltipMotion? = null) :
    OwoUIGraphics(Minecraft.getInstance(), GuiRenderState(), mouseX, mouseY, {}) {
    override fun guiWidth() = menuWidth
    override fun guiHeight() = menuHeight
    override fun getMatrixStack() = pose()
    internal val canvasClip get(): MenuCanvas.Clip? { sync(); return canvas.clip }
    /** Compose effects without changing retained layout, focus, or component state. */
    fun layer(alpha: Double = 1.0, x: Double = 0.0, y: Double = 0.0, scale: Double = 1.0,
              pivotX: Double = 0.0, pivotY: Double = 0.0, draw: () -> Unit) {
        val previousOpacity = canvas.opacity
        pose().pushMatrix()
        try {
            pose().translate((x + pivotX).toFloat(), (y + pivotY).toFloat())
            pose().scale(scale.toFloat(), scale.toFloat())
            pose().translate(-pivotX.toFloat(), -pivotY.toFloat())
            canvas.opacity *= alpha.coerceIn(0.0, 1.0)
            sync()
            draw()
        } finally {
            canvas.opacity = previousOpacity
            pose().popMatrix()
            sync()
        }
    }
    private fun sync() {
        val m = pose()
        val current = canvas.transform
        val a = m.m00().toDouble(); val b = m.m01().toDouble(); val c = m.m10().toDouble()
        val d = m.m11().toDouble(); val x = m.m20().toDouble(); val y = m.m21().toDouble()
        if (current.a.toBits() != a.toBits() || current.b.toBits() != b.toBits() || current.c.toBits() != c.toBits() ||
            current.d.toBits() != d.toBits() || current.x.toBits() != x.toBits() || current.y.toBits() != y.toBits())
            canvas.transform = MenuCanvas.Transform(a, b, c, d, x, y)
        val scissor = scissorStack.peek()
        val clip = canvas.clip
        if (scissor == null) canvas.clip = null
        else if (clip == null || clip.x != scissor.position().x() || clip.y != scissor.position().y() ||
            clip.width != scissor.width() || clip.height != scissor.height())
            canvas.clip = MenuCanvas.Clip(scissor.position().x(), scissor.position().y(), scissor.width(), scissor.height())
    }
    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        sync(); canvas.fill(minOf(x1, x2).toDouble(), minOf(y1, y2).toDouble(), kotlin.math.abs(x2 - x1).toDouble(), kotlin.math.abs(y2 - y1).toDouble(), color)
    }
    override fun fill(pipeline: RenderPipeline, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) = fill(x1, y1, x2, y2, color)
    override fun fillGradient(x1: Int, y1: Int, x2: Int, y2: Int, top: Int, bottom: Int) = drawGradientRect(x1, y1, x2 - x1, y2 - y1, top, top, bottom, bottom)
    override fun drawGradientRect(pipeline: RenderPipeline, x: Int, y: Int, width: Int, height: Int, tl: Int, tr: Int, br: Int, bl: Int) {
        sync(); canvas.gradient(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), tl, tr, br, bl)
    }
    override fun drawCircle(pipeline: RenderPipeline, x: Int, y: Int, from: Double, to: Double, segments: Int, radius: Double, color: Color) {
        sync(); canvas.arc(x.toDouble(), y.toDouble(), radius, from, to, color.argb())
    }
    override fun drawRing(pipeline: RenderPipeline, x: Int, y: Int, from: Double, to: Double, segments: Int, inner: Double, outer: Double, innerColor: Color, outerColor: Color) {
        sync(); canvas.arc(x.toDouble(), y.toDouble(), outer, from, to, outerColor.argb(), inner)
    }
    override fun drawLine(pipeline: RenderPipeline, x1: Int, y1: Int, x2: Int, y2: Int, thickness: Double, color: Color) {
        sync(); canvas.line(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), thickness, color.argb())
    }
    fun picture(image: BufferedImage, x: Int, y: Int, width: Int, height: Int) {
        sync(); canvas.picture(image, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }
    fun roundedRect(x: Int, y: Int, width: Int, height: Int, radius: Double, color: Int) {
        sync(); canvas.roundedRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, color)
    }
    fun roundedBorder(x: Int, y: Int, width: Int, height: Int, radius: Double, thickness: Double, color: Int) {
        sync(); canvas.roundedBorder(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, thickness, color)
    }
    fun shadow(x: Int, y: Int, width: Int, height: Int, radius: Double, sigma: Double, color: Int) {
        sync(); canvas.shadow(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), radius, sigma, color)
    }
    // Dropdown state is a vector check/chevron rather than owo's Minecraft sprite sheet.
    override fun blit(pipeline: RenderPipeline, texture: Identifier, x: Int, y: Int, u: Float, v: Float, width: Int, height: Int, textureWidth: Int, textureHeight: Int) {
        val color = Color.ofRgb(MenuTheme.TEXT)
        if (v > 0) {
            drawLine(x + 3, y + 1, x + 6, y + 4, 1.0, color); drawLine(x + 6, y + 4, x + 3, y + 7, 1.0, color)
        } else {
            MenuTheme.box(this, x, y, width, height, if (u > 0) MenuTheme.ACCENT else MenuTheme.CARD)
            if (u > 0) { drawLine(x + 2, y + 4, x + 4, y + 6, 1.0, color); drawLine(x + 4, y + 6, x + 7, y + 2, 1.0, color) }
        }
    }
    override fun text(font: Font, text: String?, x: Int, y: Int, color: Int, shadow: Boolean) { if (text != null) text(font, Component.literal(text), x, y, color, shadow) }
    override fun text(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) = text(font, text.visualOrderText, x, y, color, shadow)
    override fun text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean) = styledText(text, x.toDouble(), y.toDouble(), color)
    override fun drawTooltip(font: Font, x: Int, y: Int, components: List<ClientTooltipComponent>, texture: Identifier?) {
        val lines = components.mapNotNull { (it as? MenuTextTooltipAccessor)?.`skymyce$text`() }
        if (lines.isEmpty()) return
        val lineHeight = MenuCanvas.lineHeight()
        val width = kotlin.math.ceil(lines.maxOf { menuTextWidth(it) }).toInt().coerceAtMost(guiWidth() - 20) + 14
        val height = lines.size * lineHeight + 12
        val left = (x + 10).coerceAtMost(guiWidth() - width - 4).coerceAtLeast(4)
        val top = (y + 12).coerceAtMost(guiHeight() - height - 4).coerceAtLeast(4)
        pose().pushMatrix(); pose().identity()
        val progress = tooltipMotion?.show(lines) ?: 1.0
        val lift = (1 - progress) * me.mycellium.skymyce.config.misc.ThemeConfig.animationMode.profile.revealOffset * .4
        layer(alpha = progress, y = lift) {
            MenuTheme.shadow(this, left, top, width, height, .6)
            MenuTheme.box(this, left, top, width, height, MenuTheme.SIDEBAR)
            lines.forEachIndexed { i, line -> styledText(line, (left + 7).toDouble(), (top + 6 + i * lineHeight).toDouble(), MenuTheme.alpha(MenuTheme.TEXT)) }
        }
        pose().popMatrix()
    }
    fun styledText(text: FormattedCharSequence, x: Double, y: Double, color: Int, size: Double = 10.0) {
        styledText(menuTextRuns(text, color), x, y, size)
    }
    internal fun styledText(runs: List<MenuCanvas.Run>, x: Double, y: Double, size: Double = 10.0) {
        sync(); canvas.text(runs, x, y, size)
    }
}

/** Owned by a visible screen; no global tooltip timer survives its removal. */
class MenuTooltipMotion {
    private var key: List<List<MenuCanvas.Run>>? = null
    private var seen = false
    private val fade = MotionValue(0.0)
    private var outgoing: MenuCanvas.Snapshot? = null
        set(value) { if (field !== value) field?.close(); field = value }
    fun beginFrame() { seen = false }
    fun show(lines: List<FormattedCharSequence>): Double {
        seen = true
        val identity = lines.map { menuTextRuns(it, MenuTheme.alpha(MenuTheme.TEXT)) }
        if (key != identity) { key = identity; fade.snap(0.0); outgoing = null }
        fade.animateTo(1.0, 95, mode = me.mycellium.skymyce.config.misc.ThemeConfig.animationMode)
        return fade.sample().coerceIn(0.0, 1.0)
    }
    fun endFrame(canvas: MenuCanvas) {
        if (seen) return
        if (key != null) {
            outgoing = canvas.snapshot("tooltip"); key = null
            fade.snap(1.0)
        }
        fade.animateTo(0.0, 75, mode = me.mycellium.skymyce.config.misc.ThemeConfig.animationMode)
        val alpha = fade.sample().coerceIn(0.0, 1.0)
        if (alpha > 0.0) outgoing?.let { canvas.replay(it, alpha) } else outgoing = null
    }
    fun clear() { key = null; outgoing = null; fade.snap(0.0) }
}

/** How quickly Auto reacts: how long trouble must last before it steps down, and good frames before it steps up. */
enum class AutoResponse(private val title: String, val degradeMillis: Long, val restoreMillis: Long) {
    GENTLE("Gentle (keeps quality longest)", 10_000, 12_000),
    NORMAL("Normal", 5_000, 15_000),
    QUICK("Quick (protects frame rate)", 3_000, 25_000);

    override fun toString() = title
}

/**
 * The adaptive part of Auto graphics, kept free of Minecraft so it can be checked: rolling frame measurements in, one
 * of the fixed quality tiers out.
 *
 * Inputs are the client's frames-per-second (Minecraft's one-second count) and the last frame's duration, sampled every
 * client tick. A step down needs sustained trouble — average FPS under 90% of the target, or the 95th-percentile frame
 * time over 1.6× the target frame time — for the response's degrade time; a step up needs FPS over 115% of the target
 * and steady frames for its (longer) restore time. Changes are at least 8 s apart, move one tier at a time, and are
 * suspended while loading and for 5 s after. With no usable data the tier holds. The target never exceeds the frame
 * limit, so a capped game is not mistaken for a slow one.
 */
class AutoGraphicsController(private val tiers: List<GuiQuality> = AUTO_TIERS) {
    var tier = tiers.indexOf(GuiQuality.MEDIUM).coerceAtLeast(0)
        private set
    var reason = "Starting at ${tiers[tier]}"
        private set
    var lastChange = Long.MIN_VALUE / 2
        private set

    // Insertion-ordered rings replace boxed Pair deques; every query below scans them in the same order as before.
    private val fps = Samples()
    private val frames = Samples()
    private var scratch = DoubleArray(64)
    private var badSince: Long? = null
    private var goodSince: Long? = null
    private var pausedUntil = Long.MIN_VALUE / 2

    val quality get() = tiers[tier]

    /** A loading screen or world change: forget the measurements and wait. */
    fun pause(now: Long) { pausedUntil = now + SETTLE_MILLIS; fps.clear(); frames.clear(); badSince = null; goodSince = null }

    /** Growable ring of (time, value) pairs; values are stored as doubles, which represent every Int FPS exactly. */
    private class Samples {
        var times = LongArray(64); var values = DoubleArray(64)
        var head = 0; var size = 0
        fun clear() { head = 0; size = 0 }
        fun time(i: Int) = times[(head + i) % times.size]
        fun value(i: Int) = values[(head + i) % times.size]
        fun add(time: Long, value: Double) {
            if (size == times.size) {
                val t = LongArray(size * 2); val v = DoubleArray(size * 2)
                for (i in 0 until size) { t[i] = time(i); v[i] = value(i) }
                times = t; values = v; head = 0
            }
            val slot = (head + size) % times.size
            times[slot] = time; values[slot] = value; size++
        }
        fun removeFirst() { head = (head + 1) % times.size; size-- }
        /** Same summation order as `filter { time >= since }.map { value }.average()`, including NaN when nothing matches. */
        fun averageSince(since: Long): Double {
            var sum = 0.0; var count = 0
            for (i in 0 until size) if (time(i) >= since) { sum += value(i); count++ }
            return if (count == 0) Double.NaN else sum / count
        }
    }

    /** `percentile(values since [since])` without boxing: copies into a reused array and sorts with Double.compare order. */
    private fun percentileSince(samples: Samples, since: Long, p: Double): Double {
        if (scratch.size < samples.size) scratch = DoubleArray(samples.times.size)
        var n = 0
        for (i in 0 until samples.size) if (samples.time(i) >= since) scratch[n++] = samples.value(i)
        if (n == 0) return 0.0
        java.util.Arrays.sort(scratch, 0, n)
        return scratch[((n - 1) * p).toInt().coerceIn(0, n - 1)]
    }

    /**
     * One measurement. [currentFps] is the last second's frame count; [frameMillis] the last frame's duration.
     * [targetFps] is the user's target already limited by the frame cap; [lowest] the lowest tier Auto may use.
     */
    fun sample(now: Long, currentFps: Int, frameMillis: Double, targetFps: Int, response: AutoResponse, lowest: GuiQuality = tiers.first()): GuiQuality {
        if (now < pausedUntil) return quality
        if (currentFps > 0) fps.add(now, currentFps.toDouble())
        if (frameMillis.isFinite() && frameMillis > 0 && frameMillis < 2_000) frames.add(now, frameMillis)
        val window = maxOf(response.degradeMillis, response.restoreMillis)
        while (fps.size > 0 && fps.time(0) < now - window) fps.removeFirst()
        while (frames.size > 0 && frames.time(0) < now - window) frames.removeFirst()
        if (targetFps <= 0 || fps.size < 2 || frames.size < 10) return quality

        val floor = tiers.indexOf(lowest).coerceAtLeast(0)
        val targetMillis = 1000.0 / targetFps
        val recentFps = fps.averageSince(now - response.degradeMillis)
        val p95 = percentileSince(frames, now - response.degradeMillis, 0.95)
        val bad = recentFps < targetFps * 0.9 || p95 > targetMillis * 1.6
        val good = recentFps >= targetFps * 1.15 && p95 <= targetMillis * 1.25
        badSince = if (bad) badSince ?: now else null
        goodSince = if (good) goodSince ?: now else null
        val coolingDown = now - lastChange < COOLDOWN_MILLIS
        if (bad && !coolingDown && now - badSince!! >= response.degradeMillis && tier > floor) {
            tier--; lastChange = now; badSince = null; goodSince = null
            reason = String.format(Locale.ROOT, "Lowered to %s: %.0f FPS, 95%% of frames under %.1f ms (target %d FPS)", quality, recentFps, p95, targetFps)
        } else if (good && !coolingDown && now - goodSince!! >= response.restoreMillis && tier < tiers.lastIndex) {
            tier++; lastChange = now; goodSince = null; badSince = null
            reason = String.format(Locale.ROOT, "Raised to %s: %.0f FPS, steady frames (target %d FPS)", quality, recentFps, targetFps)
        } else if (tier < floor) {
            tier = floor; lastChange = now; reason = "Raised to your lowest allowed quality, $quality"
        }
        return quality
    }

    /** Recent averages for the status line. */
    fun summary(now: Long): Pair<Double, Double> {
        val f = fps.averageSince(now - 5_000)
        return (if (f.isNaN()) 0.0 else f) to percentileSince(frames, now - 5_000, 0.95)
    }

    companion object {
        /** Lowest to highest; Auto moves along these. */
        val AUTO_TIERS = listOf(GuiQuality.EXTREMELY_LIGHT, GuiQuality.LIGHT, GuiQuality.MEDIUM, GuiQuality.HIGH)
        const val COOLDOWN_MILLIS = 8_000L
        const val SETTLE_MILLIS = 5_000L

        internal fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
        }
    }
}

/**
 * Feeds [AutoGraphicsController] once per client tick (20 Hz, two numbers read from the client) and exposes the
 * effective quality every renderer reads. Only Sky-Myce's own effects change; Minecraft's options are never touched.
 */
object AutoGraphics : SkyMyceModule() {
    val controller = AutoGraphicsController()

    /** The quality to draw with now: the user's fixed choice, or Auto's current tier. */
    val quality: GuiQuality
        get() = ThemeConfig.graphicsQuality.let { if (it == GuiQuality.AUTO) controller.quality else it }

    @Subscription(TickEvent::class)
    fun onTick() = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.AUTO_GRAPHICS) { sampleQuality() }

    private fun sampleQuality() {
        if (ThemeConfig.graphicsQuality != GuiQuality.AUTO) return
        val now = System.currentTimeMillis()
        val screen = MC.screen
        if (MC.instance.level == null || screen is LevelLoadingScreen || screen is ProgressScreen) { controller.pause(now); return }
        controller.sample(now, MC.instance.fps, MC.instance.frameTimeNs / 1_000_000.0, target(), AutoResponse.NORMAL)
    }

    @Subscription(ServerDisconnectEvent::class)
    fun onDisconnect() = controller.pause(System.currentTimeMillis())

    private const val AUTO_TARGET_FPS = 60

    /** The target, but never above the frame limit (a capped game is not a slow one). */
    fun target(): Int {
        val limit = MC.instance.options.framerateLimit().get()
        val capped = if (limit in 1 until 260) (limit * 0.95).toInt() else Int.MAX_VALUE
        return minOf(AUTO_TARGET_FPS, capped).coerceAtLeast(20)
    }

    /** `/sm graphics`: mode, effective quality, recent measurements and the last change. */
    fun status(): List<String> {
        val mode = ThemeConfig.graphicsQuality
        if (mode != GuiQuality.AUTO) return listOf("§bGraphics: §f$mode §7(fixed). Choose Auto in Settings → Appearance to adapt.")
        val (fps, p95) = controller.summary(System.currentTimeMillis())
        return listOf("§bGraphics: §fAuto §7→ now §f${controller.quality}",
            String.format(Locale.ROOT, "§7Recent: %.0f FPS, 95%% of frames under %.1f ms; target %d FPS", fps, p95, target()),
            "§7${controller.reason}")
    }
}
