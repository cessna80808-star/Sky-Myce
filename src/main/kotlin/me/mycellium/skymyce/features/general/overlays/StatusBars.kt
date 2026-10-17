package me.mycellium.skymyce.features.general.overlays

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.StatusBarsConfig as Settings
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.info.ActionBarWidget
import tech.thatgravyboat.skyblockapi.api.events.info.DefenseActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.HealthActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.ManaActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.OverflowManaActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.RenderActionBarWidgetEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sin

/** Whole numbers with separators, as SkyBlock writes them. */
internal fun statusNumber(value: Int) = String.format(Locale.ROOT, "%,d", value)

/** Effective health: what it takes to bring [health] to zero through [defense]. */
internal fun effectiveHealth(health: Int, defense: Int) = health * (1 + defense.coerceAtLeast(0) / 100.0)

/**
 * One animated bar, as fractions of its maximum. [shown] eases toward the value; after a loss [trail] holds the old
 * value briefly and then drains, so a hit reads as a pale segment that shrinks away. Gains have no trail but mark
 * [healAt] for a short glint. Without animation every value is exact.
 */
class BarAnimator {
    var shown = Double.NaN
        private set
    var trail = Double.NaN
        private set
    var healFrom = 0.0
        private set
    var healAt = 0L
        private set
    private var holdUntil = 0L
    private var last = 0L

    fun update(target: Double, now: Long, animate: Boolean) {
        val value = target.coerceIn(0.0, 1.0)
        val dt = (now - last).coerceIn(0, 250)
        last = now
        if (shown.isNaN() || !animate) { shown = value; trail = value; return }
        if (value < shown - 1e-4 && trail <= shown + 1e-4) { trail = shown; holdUntil = now + TRAIL_HOLD }
        if (value > shown + 1e-3) { healFrom = shown; healAt = now }
        shown += (value - shown) * (1 - exp(-dt / EASE))
        if (kotlin.math.abs(value - shown) < 1e-3) shown = value
        if (value >= trail) trail = value
        else if (now >= holdUntil) trail = (trail - dt * TRAIL_SPEED).coerceAtLeast(shown)
    }

    companion object {
        const val TRAIL_HOLD = 450L
        /** Fraction of the bar the trail drains per millisecond. */
        const val TRAIL_SPEED = 0.0009
        const val EASE = 70.0
        const val HEAL_GLINT = 320L
    }
}

/**
 * Health, mana and defense from SkyBlock's action bar, drawn as bars. The action bar's own readouts and the vanilla
 * hearts, armor and hunger rows are hidden while the bars show, so nothing is shown twice.
 */
object StatusBars : SkyMyceModule() {
    private var health = 0; private var maxHealth = 0
    private var mana = 0; private var maxMana = 0
    private var overflow = 0; private var overflowSeen = 0L
    private var defense: Int? = null
    private var known = false
    private val view = BarsView(false)
    private val preview = BarsView(true)

    /** The bars are up: hiding the originals is only safe while they are. */
    private val showing get() = isEnabled() && Settings.enabled && known && LocationAPI.isOnSkyBlock

    val widget = Widget("status_bars", "Status Bars", 320, 322, anchor = Anchor.BOTTOM_CENTER, previewBuilder = { preview.prepare() }) {
        if (showing) view.prepare() else null
    }

    override fun init() {
        for (element in listOf(VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR)) {
            HudElementRegistry.replaceElement(element) { original ->
                HudElement { graphics, ticks -> if (!(showing && Settings.hideVanilla)) original.extractRenderState(graphics, ticks) }
            }
        }
    }

    @Subscription
    fun onHealth(event: HealthActionBarWidgetChangeEvent) { health = event.current; maxHealth = event.max; known = event.max > 0 }

    @Subscription
    fun onMana(event: ManaActionBarWidgetChangeEvent) { mana = event.current; maxMana = event.max }

    @Subscription
    fun onOverflow(event: OverflowManaActionBarWidgetChangeEvent) { overflow = event.current; overflowSeen = System.currentTimeMillis() }

    @Subscription
    fun onDefense(event: DefenseActionBarWidgetChangeEvent) { defense = event.current }

    @Subscription
    fun onRender(event: RenderActionBarWidgetEvent) {
        // Overflow mana disappears from the action bar when spent; only a recent render counts as present.
        if (event.widget == ActionBarWidget.OVERFLOW_MANA) overflowSeen = System.currentTimeMillis()
        if (!showing || !Settings.hideActionBar) return
        when (event.widget) {
            ActionBarWidget.HEALTH, ActionBarWidget.DEFENSE -> event.cancel()
            ActionBarWidget.MANA, ActionBarWidget.OVERFLOW_MANA, ActionBarWidget.NO_MANA -> if (Settings.showMana) event.cancel()
            else -> {}
        }
    }

    @Subscription
    fun onDisconnect(event: ServerDisconnectEvent) { known = false; defense = null; overflow = 0 }

    private data class Stats(val health: Int, val maxHealth: Int, val mana: Int, val maxMana: Int, val overflow: Int, val defense: Int?)

    private class BarsView(private val editor: Boolean) : BaseUIComponent() {
        private val healthBar = BarAnimator()
        private val manaBar = BarAnimator()

        fun prepare(): BarsView {
            sizing(Sizing.fixed(WIDTH), Sizing.fixed(if (Settings.showMana) 37 else 19))
            return this
        }

        private fun stats(now: Long) = if (editor) Stats(1834, 2140, 640, 900, 120, 612)
            else Stats(health, maxHealth, mana, maxMana, if (now - overflowSeen < 1500) overflow else 0, defense)

        override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val now = System.currentTimeMillis()
            val s = stats(now)
            val animate = Settings.animate && !editor
            val font = MC.instance.font
            val left = x(); val right = x() + WIDTH; val top = y()

            // Health: label row, then the bar.
            val fraction = if (s.maxHealth > 0) s.health.toDouble() / s.maxHealth else 0.0
            healthBar.update(fraction, now, animate)
            var textX = left
            textX = text(graphics, "❤ ", textX, top, HEART)
            textX = text(graphics, "${statusNumber(s.health.coerceAtMost(s.maxHealth))}§7/${statusNumber(s.maxHealth)}", textX, top, WHITE)
            if (s.health > s.maxHealth) text(graphics, " +${statusNumber(s.health - s.maxHealth)}", textX, top, GOLD)
            if (Settings.showDefense && s.defense != null) {
                val ehp = " EHP ${NumberUtils.condense(effectiveHealth(s.health, s.defense))}"
                val def = "❈ ${statusNumber(s.defense)}"
                val ehpX = right - font.width(ehp)
                text(graphics, ehp, ehpX, top, MUTED)
                text(graphics, def, ehpX - font.width(def), top, DEFENSE)
            }
            val barTop = top + 10
            val low = fraction < 0.3 && s.health > 0
            val pulse = if (low && animate) ((sin(now / 140.0) + 1) / 2 * 200).toInt() + 55 else if (low) 255 else 0
            graphics.fill(left - 1, barTop - 1, right + 1, barTop + HEALTH_HEIGHT + 1, if (pulse > 0) (pulse shl 24) or 0xFF3040 else 0xC0000000.toInt())
            bar(graphics, left, barTop, HEALTH_HEIGHT, healthBar, now, HEALTH_TRACK, HEALTH_TRAIL, HEALTH_FILL, HEALTH_LIGHT, HEALTH_SHADE)
            if (s.health > s.maxHealth && s.maxHealth > 0) {
                // Absorption rides on top of the bar as a gold layer, in proportion to maximum health.
                val extra = ((s.health - s.maxHealth).toDouble() / s.maxHealth).coerceIn(0.0, 1.0)
                val end = left + (WIDTH * extra).toInt()
                graphics.fill(left, barTop, end, barTop + 3, GOLD)
                graphics.fill(left, barTop, end, barTop + 1, GOLD_LIGHT)
            }
            notches(graphics, left, barTop, HEALTH_HEIGHT)

            if (!Settings.showMana) return
            val manaTop = top + 22
            manaBar.update(if (s.maxMana > 0) s.mana.toDouble() / s.maxMana else 0.0, now, animate)
            var manaX = text(graphics, "✎ ", left, manaTop, MANA_ICON)
            manaX = text(graphics, "${statusNumber(s.mana)}§7/${statusNumber(s.maxMana)}", manaX, manaTop, WHITE)
            if (s.overflow > 0) text(graphics, " +${statusNumber(s.overflow)}ʬ", manaX, manaTop, OVERFLOW)
            val manaBarTop = manaTop + 10
            graphics.fill(left - 1, manaBarTop - 1, right + 1, manaBarTop + MANA_HEIGHT + 1, 0xC0000000.toInt())
            bar(graphics, left, manaBarTop, MANA_HEIGHT, manaBar, now, MANA_TRACK, MANA_TRAIL, MANA_FILL, MANA_LIGHT, MANA_SHADE)
            if (s.overflow > 0 && s.maxMana > 0) {
                val end = left + (WIDTH * (s.overflow.toDouble() / s.maxMana).coerceIn(0.0, 1.0)).toInt()
                graphics.fill(left, manaBarTop, end, manaBarTop + 2, OVERFLOW)
            }
            notches(graphics, left, manaBarTop, MANA_HEIGHT)
        }

        private fun text(graphics: OwoUIGraphics, value: String, x: Int, y: Int, color: Int): Int {
            graphics.text(MC.instance.font, value, x, y, color, true)
            return x + MC.instance.font.width(value)
        }

        /** Track, trail, fill with a light top edge and a dark bottom edge, then a fading glint over a recent gain. */
        private fun bar(graphics: OwoUIGraphics, left: Int, top: Int, height: Int, anim: BarAnimator, now: Long,
                        track: Int, trail: Int, fill: Int, light: Int, shade: Int) {
            graphics.fill(left, top, left + WIDTH, top + height, track)
            val trailEnd = left + (WIDTH * anim.trail).toInt()
            val end = left + (WIDTH * anim.shown).toInt()
            if (trailEnd > end) graphics.fill(end, top, trailEnd, top + height, trail)
            if (end > left) {
                graphics.fill(left, top, end, top + height, fill)
                graphics.fill(left, top, end, top + 1, light)
                graphics.fill(left, top + height - 1, end, top + height, shade)
            }
            val age = now - anim.healAt
            if (age in 0 until BarAnimator.HEAL_GLINT) {
                val from = left + (WIDTH * anim.healFrom).toInt()
                val alpha = ((1 - age.toDouble() / BarAnimator.HEAL_GLINT) * 150).toInt()
                if (end > from) graphics.fill(from, top, end, top + height, (alpha shl 24) or 0xFFFFFF)
            }
        }

        /** Quarter marks, faint enough to read through the fill. */
        private fun notches(graphics: OwoUIGraphics, left: Int, top: Int, height: Int) {
            for (quarter in 1..3) {
                val x = left + WIDTH * quarter / 4
                graphics.fill(x, top + 1, x + 1, top + height - 1, 0x50000000)
            }
        }
    }

    private const val WIDTH = 182
    private const val HEALTH_HEIGHT = 8
    private const val MANA_HEIGHT = 5
    private const val WHITE = 0xFFF2F2F2.toInt()
    private const val MUTED = 0xFF9AA0A8.toInt()
    private const val HEART = 0xFFFF5E6C.toInt()
    private const val DEFENSE = 0xFF5BD67A.toInt()
    private const val GOLD = 0xFFF2B33D.toInt()
    private const val GOLD_LIGHT = 0xFFFFE08A.toInt()
    private const val HEALTH_TRACK = 0xFF2A1216.toInt()
    private const val HEALTH_TRAIL = 0xFFF1C9CC.toInt()
    private const val HEALTH_FILL = 0xFFD8323F.toInt()
    private const val HEALTH_LIGHT = 0xFFFF7D86.toInt()
    private const val HEALTH_SHADE = 0xFF96202B.toInt()
    private const val MANA_ICON = 0xFF7FA8FF.toInt()
    private const val MANA_TRACK = 0xFF101A33.toInt()
    private const val MANA_TRAIL = 0xFFC9D8FF.toInt()
    private const val MANA_FILL = 0xFF3B78F0.toInt()
    private const val MANA_LIGHT = 0xFF93B6FF.toInt()
    private const val MANA_SHADE = 0xFF22489C.toInt()
    private const val OVERFLOW = 0xFF3FE0E8.toInt()
}
