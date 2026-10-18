package me.mycellium.skymyce.features.bazaar.assist

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.economy.AssistHighlightShape
import me.mycellium.skymyce.config.economy.AssistPulse
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.config.economy.parseAssistColor
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.MenuTheme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.PI
import kotlin.math.sin

/** A resolved highlight style; read from settings once per frame, never per rectangle. */
internal data class AssistHighlightStyle(val rgb: Int, val shape: AssistHighlightShape, val thickness: Int, val opacity: Int,
                                         val pulse: AssistPulse, val animated: Boolean) {
    companion object {
        fun current() = AssistHighlightStyle(parseAssistColor(Settings.highlightColor) ?: MenuTheme.ACCENT, Settings.highlightShape,
            Settings.highlightThickness.coerceIn(1, 4), Settings.highlightOpacity.coerceIn(20, 100), Settings.highlightPulse, assistAnimated())
    }
}

internal fun assistAnimated() = Settings.animations && ThemeConfig.animationMode !in setOf(GuiMotion.OFF, GuiMotion.REDUCED)

/** Pulse strength 0..1: steady, a short burst after the target moves, or a gentle loop. */
internal fun assistPulse(style: AssistHighlightStyle, now: Long, changedAt: Long): Double {
    if (!style.animated) return 0.0
    return when (style.pulse) {
        AssistPulse.NONE -> 0.0
        AssistPulse.ALWAYS -> (sin(now / 1000.0 * 2 * PI * .8) + 1) / 2
        AssistPulse.ON_CHANGE -> {
            val since = now - changedAt
            if (since !in 0..1200) 0.0 else (1 - since / 1200.0) * (sin(since / 1000.0 * 2 * PI * 2.5) + 1) / 2
        }
    }
}

/** Paints one 18×18 slot frame through [rect], so menus and container screens share the exact look. */
internal fun paintAssistHighlight(x: Int, y: Int, style: AssistHighlightStyle, pulse: Double, rect: (Int, Int, Int, Int, Int) -> Unit) {
    val size = 18
    val t = style.thickness
    val base = style.opacity / 100.0
    fun argb(alpha: Double) = ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (style.rgb and 0xFFFFFF)
    val line = argb(base * (.75 + .25 * pulse))
    val fill = argb(base * (.22 + .18 * pulse))
    // The item stays readable: fills are translucent and outlines sit on the slot border.
    if (style.shape == AssistHighlightShape.FILL || style.shape == AssistHighlightShape.OUTLINE_FILL) rect(x + t, y + t, size - 2 * t, size - 2 * t, fill)
    when (style.shape) {
        AssistHighlightShape.OUTLINE, AssistHighlightShape.OUTLINE_FILL -> {
            rect(x, y, size, t, line); rect(x, y + size - t, size, t, line)
            rect(x, y + t, t, size - 2 * t, line); rect(x + size - t, y + t, t, size - 2 * t, line)
        }
        AssistHighlightShape.CORNERS -> {
            val arm = 6
            for ((cx, cy) in listOf(0 to 0, size - arm to 0, 0 to size - t, size - arm to size - t)) rect(x + cx, y + cy, arm, t, line)
            for ((cx, cy) in listOf(0 to 0, size - t to 0, 0 to size - arm, size - t to size - arm)) rect(x + cx, y + cy, t, arm, line)
        }
        AssistHighlightShape.FILL -> Unit
    }
    // A soft outer glow while pulsing.
    if (pulse > .05 && style.shape != AssistHighlightShape.FILL) {
        val glow = argb(base * .35 * pulse)
        rect(x - 1, y - 1, size + 2, 1, glow); rect(x - 1, y + size, size + 2, 1, glow)
        rect(x - 1, y, 1, size, glow); rect(x + size, y, 1, size, glow)
    }
}

/** Container overlay: follows the live slot, glides between targets, and vanishes with an invalid step. */
object BazaarAssistHighlight {
    private var screenId = 0
    private var slot = -1
    private var fromX = 0.0
    private var fromY = 0.0
    private var shownX = 0.0
    private var shownY = 0.0
    private var changedAt = 0L

    @JvmStatic
    fun drawSlots(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor) {
        val target = BazaarAssist.highlightTarget(screen)
        val match = target?.let { index -> screen.menu.slots.firstOrNull { it.index == index } }
        if (match == null) { slot = -1; screenId = 0; return }
        val now = System.currentTimeMillis()
        val style = AssistHighlightStyle.current()
        val id = System.identityHashCode(screen)
        val x = match.x - 1.0
        val y = match.y - 1.0
        if (id != screenId || slot < 0) {
            screenId = id; slot = match.index; fromX = x; fromY = y; shownX = x; shownY = y; changedAt = now
        } else if (match.index != slot) {
            slot = match.index; fromX = shownX; fromY = shownY; changedAt = now
        }
        val progress = if (style.animated) ((now - changedAt) / 160.0).coerceIn(0.0, 1.0) else 1.0
        val eased = 1 - (1 - progress) * (1 - progress) * (1 - progress)
        shownX = fromX + (x - fromX) * eased
        shownY = fromY + (y - fromY) * eased
        graphics.nextStratum()
        paintAssistHighlight(shownX.toInt(), shownY.toInt(), style, assistPulse(style, now, changedAt)) { rx, ry, w, h, color ->
            graphics.fill(rx, ry, rx + w, ry + h, color)
        }
    }
}

/** Live settings preview: three slots with the target hopping between them, using the real style. */
internal class AssistHighlightPreview : BaseUIComponent() {
    init { sizing(Sizing.fixed(3 * 22 + 4), Sizing.fixed(26)) }
    private var slot = 1
    private var movedAt = 0L
    private var fromX = 0.0

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val now = System.currentTimeMillis()
        val style = AssistHighlightStyle.current()
        fun slotX(index: Int) = x() + 2 + index * 22
        val top = y() + 4
        for (index in 0..2) MenuTheme.rounded(graphics, slotX(index) + 1, top + 1, 16, 16, MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, 0, 40)), 0)
        if (now - movedAt > 1600) { fromX = slotX(slot).toDouble(); slot = (slot + 1) % 3; movedAt = now }
        val progress = if (style.animated) ((now - movedAt) / 160.0).coerceIn(0.0, 1.0) else 1.0
        val eased = 1 - (1 - progress) * (1 - progress) * (1 - progress)
        val shown = fromX + (slotX(slot) - fromX) * eased
        paintAssistHighlight(shown.toInt(), top, style, assistPulse(style, now, movedAt)) { rx, ry, w, h, color ->
            MenuTheme.rounded(graphics, rx, ry, w, h, color, 0)
        }
    }
}
