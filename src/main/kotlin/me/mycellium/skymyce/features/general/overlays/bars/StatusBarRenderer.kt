package me.mycellium.skymyce.features.general.overlays.bars

import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * Draws one bar into its rectangle: the icon, a framed track, the trail, the fill with a light top edge and a dark
 * bottom edge, a soft sub-pixel end, the overflow layer, the heal sweep and hit flash, the low-health heartbeat and
 * the label. Shared by the HUD and the editor so both look the same.
 */
object StatusBarRenderer {
    private val sprites = HashMap<String, Identifier>()

    fun draw(graphics: GuiGraphicsExtractor, type: BarType, config: BarConfig, rect: BarRect, reading: BarReading, motion: BarMotion,
             now: Long, animate: Boolean, opacity: Double = 1.0) {
        val alpha = (opacity * (motion.alpha.takeIf { !it.isNaN() } ?: 1.0)).coerceIn(0.0, 1.0)
        if (alpha <= 0.01) return
        val iconSize = if (config.icon == IconPosition.OFF) 0 else BAR_HEIGHT + 1
        val barLeft = rect.x + if (config.icon == IconPosition.LEFT) iconSize else 0
        val barRight = rect.right - if (config.icon == IconPosition.RIGHT) iconSize else 0
        val width = (barRight - barLeft - 2).coerceAtLeast(1)
        val top = rect.y; val inner = top + 1; val height = BAR_HEIGHT - 2
        val left = barLeft + 1
        // A big hit shakes the bar by a pixel for a moment.
        val flash = if (animate) motion.flash(now) else 0.0
        val shake = if (animate && flash > 0.4) (if ((now / 30) % 2 == 0L) 1 else -1) else 0

        if (iconSize > 0) icon(graphics, type, if (config.icon == IconPosition.LEFT) rect.x else barRight + 1, top, alpha)

        val fraction = reading.fill.coerceIn(0.0, 1.0)
        val beat = if (type == BarType.HEALTH && animate) heartbeat(fraction, now) else if (type == BarType.HEALTH && fraction in 0.0001..LOW_HEALTH) 0.6 else 0.0
        val frame = if (beat > 0) blend(0x000000, 0xFF3040, beat) else 0x000000
        graphics.fill(barLeft + shake, top, barRight + shake, top + BAR_HEIGHT, argb(frame, alpha * (0.75 + beat * 0.25)))
        val x0 = left + shake
        graphics.fill(x0, inner, x0 + width, inner + height, argb(dark(config.fill, 0.22), alpha))

        val shown = (motion.shown.takeIf { !it.isNaN() } ?: fraction).coerceIn(0.0, 1.0)
        val trail = (motion.trail.takeIf { !it.isNaN() } ?: shown).coerceIn(0.0, 1.0)
        val fillEnd = width * shown
        val trailEnd = (width * trail).roundToInt()
        if (trailEnd > fillEnd.toInt()) graphics.fill(x0 + fillEnd.toInt(), inner, x0 + trailEnd, inner + height, argb(light(config.fill, 0.72), alpha * 0.9))

        val whole = fillEnd.toInt()
        val fillColor = if (beat > 0) light(config.fill, beat * 0.25) else config.fill
        if (whole > 0) {
            graphics.fill(x0, inner, x0 + whole, inner + height, argb(fillColor, alpha))
            graphics.fill(x0, inner, x0 + whole, inner + 1, argb(light(fillColor, 0.45), alpha))
            graphics.fill(x0, inner + height - 1, x0 + whole, inner + height, argb(dark(fillColor, 0.7), alpha))
        }
        // The fractional last column, so the end glides instead of stepping a pixel at a time.
        val part = fillEnd - whole
        if (part > 0.05 && whole < width) graphics.fill(x0 + whole, inner, x0 + whole + 1, inner + height, argb(fillColor, alpha * part))

        // Overflow (absorption, overflow mana) rides on top, against the maximum, with a moving shimmer.
        if (config.showOverflow && type.hasOverflow && reading.overflow > 0 && reading.max > 0) {
            val end = (width * (reading.overflow / reading.max).coerceIn(0.0, 1.0)).roundToInt()
            graphics.fill(x0, inner, x0 + end, inner + 3, argb(config.overflow, alpha))
            graphics.fill(x0, inner, x0 + end, inner + 1, argb(light(config.overflow, 0.5), alpha))
            if (animate && end > 4) {
                val shimmer = x0 + ((now / 12) % (end + 12)).toInt() - 6
                for (i in 0 until 4) { val sx = shimmer + i; if (sx in x0 until x0 + end) graphics.fill(sx, inner, sx + 1, inner + 3, argb(0xFFFFFF, alpha * (0.35 - i * 0.07))) }
            }
        }
        // A heal sweeps a bright band from the old end to the new.
        motion.heal(now)?.takeIf { animate }?.let { progress ->
            val from = width * motion.healFrom; val to = width * motion.healTo
            val head = (from + (to - from) * easeOut(progress)).roundToInt()
            val band = 6
            for (i in 0 until band) {
                val sx = x0 + head - i
                if (sx >= x0 + from.toInt() && sx < x0 + width) graphics.fill(sx, inner, sx + 1, inner + height, argb(0xFFFFFF, alpha * (1 - progress) * (0.6 - i * 0.09)))
            }
        }
        if (flash > 0) graphics.fill(x0, inner, x0 + width, inner + height, argb(0xFFFFFF, alpha * flash * 0.55))
        // Quarter marks, faint enough to read through.
        if (width >= 60) for (q in 1..3) { val qx = x0 + width * q / 4; graphics.fill(qx, inner + 1, qx + 1, inner + height - 1, argb(0x000000, alpha * 0.3)) }

        if (config.text != TextPosition.OFF && reading.label.isNotEmpty()) {
            val font = MC.instance.font
            // The full label if it fits, else the value alone, else nothing.
            val label = listOf(reading.label, reading.short).firstOrNull { font.width(it) <= width - 3 } ?: return
            val textWidth = font.width(label)
            val tx = when (config.text) {
                TextPosition.LEFT -> x0 + 2
                TextPosition.RIGHT -> x0 + width - textWidth - 1
                else -> x0 + (width - textWidth) / 2
            }
            outlined(graphics, label, tx, top + 1, config.textColor, alpha)
        }
    }

    private fun icon(graphics: GuiGraphicsExtractor, type: BarType, x: Int, y: Int, alpha: Double) {
        val sprite = type.sprite
        if (sprite != null) {
            val id = sprites.getOrPut(sprite) { Identifier.withDefaultNamespace(sprite) }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, 9, 9, argb(0xFFFFFF, alpha))
        } else {
            val font = MC.instance.font
            outlined(graphics, type.glyph, x + (9 - font.width(type.glyph)) / 2 + 1, y + 1, type.text, alpha)
        }
    }

    private fun outlined(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int, alpha: Double) {
        val font = MC.instance.font
        val shadow = argb(0x000000, alpha * 0.85)
        graphics.text(font, text, x - 1, y, shadow, false); graphics.text(font, text, x + 1, y, shadow, false)
        graphics.text(font, text, x, y - 1, shadow, false); graphics.text(font, text, x, y + 1, shadow, false)
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
