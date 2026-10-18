package me.mycellium.skymyce.features.general.overlays.bars

import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * Draws one bar into its rectangle, in the shape SkyBlock players know from Skyblocker's bars: a 9-pixel icon, a
 * 7-pixel rounded dark track and a 5-pixel rounded fill with a light top edge, an overflow layer drawn over the fill
 * from the left, and the value in the stat's own colour sitting across the top edge with a crisp black outline.
 * Motion (trail, heal sweep, hit flash, low-health beat) is layered inside the same shape. Shared by HUD and editor.
 */
object StatusBarRenderer {
    private val sprites = HashMap<String, Identifier>()
    private const val ICON = 9

    fun draw(graphics: GuiGraphicsExtractor, type: BarType, config: BarConfig, rect: BarRect, reading: BarReading, motion: BarMotion,
             now: Long, animate: Boolean, opacity: Double = 1.0) {
        val alpha = (opacity * (motion.alpha.takeIf { !it.isNaN() } ?: 1.0)).coerceIn(0.0, 1.0)
        if (alpha <= 0.01) return
        val iconSpace = if (config.icon == IconPosition.OFF) 0 else ICON + 1
        val barLeft = rect.x + if (config.icon == IconPosition.LEFT) iconSpace else 0
        val barRight = rect.right - if (config.icon == IconPosition.RIGHT) iconSpace else 0
        val barWidth = barRight - barLeft
        if (barWidth < 4) return
        val flash = if (animate) motion.flash(now) else 0.0
        // A big hit nudges the bar a pixel for a moment.
        val shake = if (animate && flash > 0.4) (if ((now / 30) % 2 == 0L) 1 else -1) else 0
        val top = rect.y + 1 // The 7-pixel body.
        val x = barLeft + shake

        if (iconSpace > 0) icon(graphics, type, config, if (config.icon == IconPosition.LEFT) rect.x else barRight + 1, rect.y, alpha)

        val fraction = reading.fill.coerceIn(0.0, 1.0)
        val beat = if (type == BarType.HEALTH) (if (animate) heartbeat(fraction, now) else if (fraction in 0.0001..LOW_HEALTH) 0.6 else 0.0) else 0.0

        // Body: a near-black rounded frame (corner pixels left out) around a dark track tinted by the fill.
        val frame = if (beat > 0) blend(0x0E0E10, 0xC81E28, beat) else 0x0E0E10
        pill(graphics, x, top, barWidth, 7, argb(frame, alpha * 0.92))
        val inner = x + 1; val innerWidth = barWidth - 2
        graphics.fill(inner, top + 1, inner + innerWidth, top + 6, argb(dark(config.fill, 0.28), alpha * 0.95))

        val shown = (motion.shown.takeIf { !it.isNaN() } ?: fraction).coerceIn(0.0, 1.0)
        val trail = (motion.trail.takeIf { !it.isNaN() } ?: shown).coerceIn(0.0, 1.0)
        val fillPx = innerWidth * shown
        val trailPx = (innerWidth * trail).roundToInt()
        if (trailPx > fillPx.toInt() + 1) fillPill(graphics, inner, top + 1, trailPx, light(config.fill, 0.62), alpha * 0.85, highlight = false)

        val color = if (beat > 0) light(config.fill, beat * 0.2) else config.fill
        val whole = fillPx.toInt()
        fillPill(graphics, inner, top + 1, whole, color, alpha, highlight = true)
        // The fractional end column, so the edge glides instead of stepping.
        val part = fillPx - whole
        if (part > 0.05 && whole in 2 until innerWidth) graphics.fill(inner + whole, top + 2, inner + whole + 1, top + 5, argb(color, alpha * part))

        // Overflow (absorption, overflow mana) over the fill from the left, with a slow shimmer.
        if (config.showOverflow && type.hasOverflow && reading.overflow > 0 && reading.max > 0) {
            val end = (innerWidth * (reading.overflow / reading.max).coerceIn(0.0, 1.0)).roundToInt()
            fillPill(graphics, inner, top + 1, end, config.overflow, alpha, highlight = true)
            if (animate && end > 6) {
                val head = inner + ((now / 14) % (end + 10)).toInt() - 5
                for (i in 0 until 3) { val sx = head + i; if (sx in inner + 1 until inner + end - 1) graphics.fill(sx, top + 2, sx + 1, top + 5, argb(0xFFFFFF, alpha * (0.32 - i * 0.09))) }
            }
        }
        // A heal sweeps a bright band from the old end to the new one.
        motion.heal(now)?.takeIf { animate }?.let { progress ->
            val from = innerWidth * motion.healFrom; val to = innerWidth * motion.healTo
            val head = (from + (to - from) * easeOut(progress)).roundToInt()
            for (i in 0 until 5) {
                val sx = inner + head - i
                if (sx >= inner + from.toInt() && sx in inner + 1 until inner + innerWidth - 1) graphics.fill(sx, top + 1, sx + 1, top + 6, argb(0xFFFFFF, alpha * (1 - progress) * (0.55 - i * 0.1)))
            }
        }
        if (flash > 0 && whole > 2) fillPill(graphics, inner, top + 1, maxOf(whole, trailPx), 0xFFFFFF, alpha * flash * 0.5, highlight = false)

        if (config.text != TextPosition.OFF && reading.label.isNotEmpty()) label(graphics, config, reading, rect, barLeft, barWidth, alpha)
    }

    /** The value straddling the top edge, in the stat's colour; any "+overflow" part in the overflow colour. */
    private fun label(graphics: GuiGraphicsExtractor, config: BarConfig, reading: BarReading, rect: BarRect, barLeft: Int, barWidth: Int, alpha: Double) {
        val font = MC.instance.font
        // The full label if it fits over the whole bar (icon included), else the value alone.
        val label = listOf(reading.label, reading.short).firstOrNull { font.width(it) <= rect.width } ?: reading.short
        val split = label.lastIndexOf(" +")
        val main = if (split > 0) label.substring(0, split) else label
        val extra = if (split > 0) label.substring(split) else ""
        val width = font.width(label)
        val x = when (config.text) {
            TextPosition.LEFT -> barLeft + 1
            TextPosition.RIGHT -> barLeft + barWidth - width - 1
            else -> barLeft + (barWidth - width) / 2
        }.coerceIn(rect.x - 2, rect.right - width + 2)
        val y = rect.y - 3
        outlined(graphics, main, x, y, config.textColor, alpha)
        if (extra.isNotEmpty()) outlined(graphics, extra, x + font.width(main), y, config.overflow, alpha)
    }

    private fun icon(graphics: GuiGraphicsExtractor, type: BarType, config: BarConfig, x: Int, y: Int, alpha: Double) {
        val sprite = type.sprite
        if (sprite != null) {
            val id = sprites.getOrPut(sprite) { Identifier.withDefaultNamespace(sprite) }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, ICON, ICON, argb(0xFFFFFF, alpha))
        } else {
            val font = MC.instance.font
            outlined(graphics, type.glyph, x + (ICON - font.width(type.glyph)) / 2 + 1, y + 1, config.textColor, alpha)
        }
    }

    /** A filled rectangle with its four corner pixels left out. */
    private fun pill(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        if (w < 3 || h < 3) { graphics.fill(x, y, x + w, y + h, color); return }
        graphics.fill(x + 1, y, x + w - 1, y + 1, color)
        graphics.fill(x, y + 1, x + w, y + h - 1, color)
        graphics.fill(x + 1, y + h - 1, x + w - 1, y + h, color)
    }

    /** A 5-pixel rounded fill; with [highlight], a lighter top row and a darker bottom row. */
    private fun fillPill(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, rgb: Int, alpha: Double, highlight: Boolean) {
        if (w <= 0) return
        pill(graphics, x, y, w, 5, argb(rgb, alpha))
        if (highlight && w >= 3) {
            graphics.fill(x + 1, y, x + w - 1, y + 1, argb(light(rgb, 0.38), alpha))
            graphics.fill(x + 1, y + 4, x + w - 1, y + 5, argb(dark(rgb, 0.72), alpha))
        }
    }

    /** Text with a one-pixel black outline on all eight sides, like a glowing sign's. */
    private fun outlined(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int, alpha: Double) {
        val font = MC.instance.font
        val outline = argb(0x000000, alpha)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) graphics.text(font, text, x + dx, y + dy, outline, false)
        graphics.text(font, text, x, y, argb(color, alpha), false)
    }

    private fun easeOut(t: Double) = 1 - (1 - t) * (1 - t) * (1 - t)

    internal fun argb(rgb: Int, alpha: Double) = ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
    internal fun blend(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = (((a shr shift) and 255) + (((b shr shift) and 255) - ((a shr shift) and 255)) * t.coerceIn(0.0, 1.0)).roundToInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    internal fun light(rgb: Int, t: Double) = blend(rgb, 0xFFFFFF, t)
    internal fun dark(rgb: Int, t: Double) = blend(0x000000, rgb, t)
}
