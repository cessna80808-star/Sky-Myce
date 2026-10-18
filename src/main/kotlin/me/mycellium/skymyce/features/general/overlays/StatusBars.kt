package me.mycellium.skymyce.features.general.overlays

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.StatusBarsConfig as Settings
import me.mycellium.skymyce.features.general.overlays.bars.BarMotion
import me.mycellium.skymyce.features.general.overlays.bars.BarRect
import me.mycellium.skymyce.features.general.overlays.bars.BarReading
import me.mycellium.skymyce.features.general.overlays.bars.BarType
import me.mycellium.skymyce.features.general.overlays.bars.StatusBarLayout
import me.mycellium.skymyce.features.general.overlays.bars.StatusBarRenderer
import me.mycellium.skymyce.features.general.overlays.bars.StatusBarsEditorScreen
import me.mycellium.skymyce.features.general.overlays.bars.absorptionFrom
import me.mycellium.skymyce.features.general.overlays.bars.barLabel
import me.mycellium.skymyce.features.general.overlays.bars.defenseFill
import me.mycellium.skymyce.features.general.overlays.bars.estimateHealth
import me.mycellium.skymyce.features.general.overlays.bars.layoutRects
import me.mycellium.skymyce.features.general.overlays.bars.parsePair
import me.mycellium.skymyce.features.general.overlays.bars.speedMax
import me.mycellium.skymyce.features.general.overlays.bars.speedValue
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.info.ActionBarWidget
import tech.thatgravyboat.skyblockapi.api.events.info.ActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.DefenseActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.HealthActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.ManaActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.OverflowManaActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.RenderActionBarWidgetEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI

/**
 * Health, mana, defense, speed, vitality and respiration as separate bars that can sit on anchors around the hotbar
 * and screen or float anywhere (`/sm bars` arranges them). Health reads the vanilla hearts every tick for speed and
 * the action bar for the maximum and absorption. The action bar's own readouts and the vanilla rows the bars replace
 * are hidden while the bars show.
 */
object StatusBars : SkyMyceModule() {
    private var actionHealth = 0; private var maxHealth = 0; private var absorption = 0
    private var mana = 0; private var maxMana = 0
    private var overflow = 0; private var overflowSeen = 0L
    private var defense: Int? = null
    private var vitality = 0; private var maxVitality = 0; private var vitalitySeen = 0L
    private var heartsRatio: Double? = null
    private var speed = 0
    private var air = 300; private var maxAir = 300; private var underwater = false
    private var known = false

    private val gson: Gson = GsonBuilder().create()
    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("status-bars.json"), 32768) }
    private var loaded: StatusBarLayout? = null
    private val motions = BarType.entries.associateWith { BarMotion() }

    var layout: StatusBarLayout
        get() = loaded ?: load().also { loaded = it }
        set(value) { loaded = value.normalized() }

    /** The bars are up: hiding the originals is only safe while they are. */
    private val showing get() = isEnabled() && Settings.enabled && known && LocationAPI.isOnSkyBlock
    private fun barOn(type: BarType) = layout.bar(type).enabled

    override fun init() {
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR) { original -> hide(original) { true } }
        HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR) { original -> hide(original) { true } }
        HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR) { original -> hide(original) { true } }
        HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR) { original -> hide(original) { barOn(BarType.RESPIRATION) } }
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "status_bars")) { graphics, _ ->
            if (showing && MC.screen !is StatusBarsEditorScreen) render(graphics)
        }
    }

    private fun hide(original: HudElement, also: () -> Boolean) = HudElement { graphics, ticks ->
        if (!(showing && Settings.hideVanilla && also())) original.extractRenderState(graphics, ticks)
    }

    private fun load(): StatusBarLayout {
        val saved = runCatching { file.read()?.let { gson.fromJson(it, StatusBarLayout::class.java) } }
            .onFailure { SkyMyce.logger.warn("Could not read status bar layout; using the default", it) }.getOrNull()
        @Suppress("SENSELESS_COMPARISON") // Gson can leave a missing list null.
        if (saved != null && saved.version == StatusBarLayout.VERSION && saved.bars != null) return saved.normalized()
        // First run: carry over the old single-panel toggles.
        var fresh = StatusBarLayout.default()
        if (!Settings.showMana) fresh = fresh.update(BarType.MANA) { it.copy(enabled = false) }
        if (!Settings.showDefense) fresh = fresh.update(BarType.DEFENSE) { it.copy(enabled = false) }
        return fresh
    }

    fun save() {
        val value = loaded ?: return
        runCatching { file.write(gson.toJsonTree(value)) }.onFailure { SkyMyce.logger.warn("Could not save status bar layout", it) }
    }

    @Subscription
    fun onHealth(event: HealthActionBarWidgetChangeEvent) {
        actionHealth = event.current; maxHealth = event.max; known = event.max > 0
        absorption = absorptionFrom(event.current, event.max, heartsRatio)
    }

    @Subscription
    fun onMana(event: ManaActionBarWidgetChangeEvent) { mana = event.current; maxMana = event.max }

    @Subscription
    fun onOverflow(event: OverflowManaActionBarWidgetChangeEvent) { overflow = event.current; overflowSeen = System.currentTimeMillis() }

    @Subscription
    fun onDefense(event: DefenseActionBarWidgetChangeEvent) { defense = event.current }

    @Subscription
    fun onWidget(event: ActionBarWidgetChangeEvent) {
        if (event.widget != ActionBarWidget.VITALITY) return
        parsePair(event.new)?.let { (value, max) -> vitality = value; maxVitality = max; vitalitySeen = System.currentTimeMillis() }
    }

    @Subscription(TickEvent::class)
    fun onTick() {
        val player = MC.instance.player ?: return
        heartsRatio = if (Settings.useHearts && player.isAlive && player.maxHealth > 0) (player.health / player.maxHealth).toDouble() else null
        speed = speedValue(player.speed.toDouble(), player.isSprinting)
        air = player.airSupply.coerceIn(0, player.maxAirSupply); maxAir = player.maxAirSupply; underwater = player.isUnderWater
    }

    @Subscription
    fun onRender(event: RenderActionBarWidgetEvent) {
        // Overflow mana disappears from the action bar when spent; only a recent render counts as present.
        if (event.widget == ActionBarWidget.OVERFLOW_MANA) overflowSeen = System.currentTimeMillis()
        if (event.widget == ActionBarWidget.VITALITY) vitalitySeen = System.currentTimeMillis()
        if (!showing || !Settings.hideActionBar) return
        when (event.widget) {
            ActionBarWidget.HEALTH -> if (barOn(BarType.HEALTH)) event.cancel()
            ActionBarWidget.DEFENSE -> if (barOn(BarType.DEFENSE)) event.cancel()
            ActionBarWidget.MANA, ActionBarWidget.OVERFLOW_MANA, ActionBarWidget.NO_MANA -> if (barOn(BarType.MANA)) event.cancel()
            ActionBarWidget.VITALITY -> if (barOn(BarType.VITALITY)) event.cancel()
            else -> {}
        }
    }

    @Subscription
    fun onDisconnect(event: ServerDisconnectEvent) { known = false; defense = null; overflow = 0; vitalitySeen = 0; heartsRatio = null }

    /** Current health: the hearts' share of the maximum when they are usable, else the action bar value. */
    internal fun health() = estimateHealth(actionHealth, maxHealth, heartsRatio)

    /** What [type] shows now, and whether it should be on screen. [preview] uses fixed example values. */
    fun reading(type: BarType, preview: Boolean): Pair<BarReading, Boolean> {
        val config = layout.bar(type)
        val now = System.currentTimeMillis()
        fun of(value: Int, max: Int, extra: Int = 0, fill: Double? = null, health: Int? = null) =
            BarReading(value.toDouble(), max.toDouble(), extra.toDouble(), barLabel(type, config, value, max, extra, health), fill ?: if (max > 0) value.toDouble() / max else 0.0,
                short = barLabel(type, config.copy(showMax = false, showOverflow = false, extra = false), value, max, 0))
        if (preview) return when (type) {
            BarType.HEALTH -> of(1834, 2140, 220)
            BarType.MANA -> of(640, 900, 120)
            BarType.DEFENSE -> of(612, 0, fill = defenseFill(612), health = 1834)
            BarType.SPEED -> of(312, 400)
            BarType.VITALITY -> of(860, 1000)
            BarType.RESPIRATION -> of(180, 300)
        } to true
        return when (type) {
            BarType.HEALTH -> of(health(), maxHealth, absorption) to known
            BarType.MANA -> of(mana, maxMana, if (now - overflowSeen < 1500) overflow else 0) to (maxMana > 0)
            BarType.DEFENSE -> of(defense ?: 0, 0, fill = defenseFill(defense ?: 0), health = health()) to (defense != null)
            BarType.SPEED -> of(speed, speedMax(speed)) to true
            BarType.VITALITY -> of(vitality, maxVitality) to (now - vitalitySeen < 10_000 && maxVitality > 0)
            BarType.RESPIRATION -> of(air, maxAir) to (underwater || air < maxAir)
        }
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        val rects = layoutRects(layout, graphics.guiWidth(), graphics.guiHeight())
        drawBars(graphics, rects, preview = false)
    }

    /** Draws [rects] with live (or preview) readings. Shared with the editor. */
    fun drawBars(graphics: GuiGraphicsExtractor, rects: Map<BarType, BarRect>, preview: Boolean, opacity: (BarType) -> Double = { 1.0 }) {
        val now = System.currentTimeMillis()
        val animate = Settings.animate
        for ((type, rect) in rects) {
            val (reading, visible) = reading(type, preview)
            val motion = motions.getValue(type)
            motion.update(reading.fill, reading.value, visible || preview, now, animate)
            val config = layout.bar(type)
            // Counted values roll to their new number; the final number is always exact.
            val shown = if (animate && type in ROLLING && !motion.number.isNaN() && motion.number != reading.value)
                kotlin.math.round(motion.number).toInt().let { n -> reading.copy(label = barLabel(type, config, n, reading.max.toInt(), reading.overflow.toInt()),
                    short = barLabel(type, config.copy(showMax = false, showOverflow = false), n, reading.max.toInt(), 0)) } else reading
            StatusBarRenderer.draw(graphics, type, config, rect, shown, motion, now, animate, opacity(type))
        }
    }

    /** Live readings exist for the editor to preview only once the action bar has been seen. */
    val hasLiveReadings get() = known && LocationAPI.isOnSkyBlock

    private val ROLLING = setOf(BarType.HEALTH, BarType.MANA, BarType.SPEED, BarType.VITALITY)

    fun openEditor() = MC.instance.execute { me.mycellium.skymyce.ui.navigateMenu(StatusBarsEditorScreen(MC.screen)) }
}
