package me.mycellium.skymyce.features.instances.dungeons.goldor

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig as Settings
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/** The text beside the map: section, recommendation, timing and route status. Pure, so it can be checked. */
internal fun goldorHudLines(advice: GoldorAdvice, state: GoldorRunState, now: Long, recommendations: Boolean): List<String> {
    val lines = ArrayList<String>()
    val tick = state.tick().nextAfter(now)?.let { " §7· tick §f${seconds(it - now)}${if (state.tick().observed) "" else "§8~"}" } ?: ""
    lines += "§b§l${advice.section.title}§r §7${state.counters[advice.section]?.let { "$it/${GoldorLayout.total(advice.section)}" } ?: ""}$tick"
    if (recommendations) advice.self?.let { rec ->
        val color = when { rec.urgent -> "§c§l"; rec.confidence == Confidence.LOW -> "§7"; else -> "§e" }
        lines += "$color${rec.headline}"
        lines += "§7${rec.reason}"
        rec.leap?.plan?.let { plan -> timingLine(plan, now)?.let { lines += it } }
    }
    advice.deviceArrival?.let { a ->
        val gap = a.secondEta?.let { " §7by §f${seconds(it.millis - a.firstEta.millis)}" } ?: ""
        lines += "§7Device: §f${a.first}§7 first${if (a.firstEta.confidence == Confidence.LOW) " §8(est.)" else gap}"
    }
    // Only routes still ahead; an early enter into the current section is behind us.
    advice.routes.forEach { (route, text) -> if (route.section.ordinal > advice.section.ordinal) lines += "§7${route.title}: §f$text" }
    return lines
}

/** The pre-leap timing line. Low confidence shows no exact countdown. */
internal fun timingLine(plan: LeapPlan, now: Long): String? {
    val approx = plan.confidence == Confidence.LOW
    return when (plan.phase) {
        LeapPhase.WAITING_TICK, LeapPhase.PREPARE -> plan.leapFrom?.let { "§7Leap in ${if (approx) "~" else ""}§f${seconds(it - now)}${plan.opensAt?.let { o -> " §8· opens ${seconds(o - now)}" } ?: ""}" }
        LeapPhase.LEAP -> plan.leapUntil?.let { "§c§lLEAP NOW §7(${seconds(it - now)} left)" }
        LeapPhase.MISSED -> "§cToo late for the tick — go now"
        LeapPhase.UNCERTAIN -> "§8Timing uncertain: ${plan.reason}"
        LeapPhase.TRAVELLING, LeapPhase.ARRIVED -> null
    }
}

object GoldorHud : SkyMyceModule() {
    private const val TEXT_WIDTH = 132
    private val textures = (1..4).associate { GoldorSection.ofNumber(it)!! to Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "textures/goldor/s$it.png") }
    private val live = MapView(false)
    private val preview = MapView(true)

    val widget = Widget("goldor_map", "Goldor Map", 6, 60, scale = 0.75f, previewBuilder = { preview.prepare() }) {
        val active = isEnabled() && Settings.enabled && (Settings.map || Settings.recommendations) && GoldorCoordinator.state.active && GoldorCoordinator.advice != null
        if (active) live.prepare() else null
    }

    private class MapView(private val editor: Boolean) : BaseUIComponent() {
        fun prepare(): MapView {
            val mapWidth = if (Settings.map) GoldorLayout.MAP_WIDTH + 8 else 0
            val textWidth = if (Settings.recommendations) TEXT_WIDTH else 0
            sizing(Sizing.fixed(mapWidth + textWidth + 4), Sizing.fixed(GoldorLayout.MAP_HEIGHT + 8))
            return this
        }

        override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val state = if (editor) previewState else GoldorCoordinator.state
            val advice = if (editor) previewAdvice else GoldorCoordinator.advice ?: return
            val now = if (editor) PREVIEW_NOW else System.currentTimeMillis()
            graphics.fill(x(), y(), x() + width(), y() + height(), (Settings.backgroundOpacity.coerceIn(0, 100) * 255 / 100 shl 24) or 0x101014)
            var textX = x() + 4
            if (Settings.map) {
                val section = if (advice.section == GoldorSection.CORE) GoldorSection.S4 else advice.section
                val left = x() + 4; val top = y() + 4
                graphics.blit(RenderPipelines.GUI_TEXTURED, textures.getValue(section), left, top, 0f, 0f, GoldorLayout.MAP_WIDTH, GoldorLayout.MAP_HEIGHT,
                    GoldorLayout.MAP_WIDTH, GoldorLayout.MAP_HEIGHT)
                val selfObjective = advice.self?.objective
                val selfRole = state.player(if (editor) "You" else MC.instance.user.name)?.role
                for (station in GoldorLayout.stations(section)) marker(graphics, state, station, left, top, selfObjective, selfRole, now)
                textX = left + GoldorLayout.MAP_WIDTH + 6
            }
            if (Settings.recommendations) {
                var lineY = y() + 6
                for (line in goldorHudLines(advice, state, now, true)) {
                    // Wrap long reasons to the column; continuation lines keep the line's colour.
                    val colour = line.take(2).takeIf { it.startsWith("§") }.orEmpty()
                    for ((index, part) in MC.font.splitter.splitLines(line, TEXT_WIDTH - 4, net.minecraft.network.chat.Style.EMPTY).withIndex()) {
                        graphics.text(MC.font, if (index == 0) part.string else colour + part.string, textX, lineY, -1, true)
                        lineY += 10
                    }
                    if (lineY > y() + height() - 10) break
                }
            }
        }

        /** One objective: role colour border, status fill, label or puzzle letter, and a white ring for your own. */
        private fun marker(graphics: OwoUIGraphics, state: GoldorRunState, station: Station, left: Int, top: Int,
                           selfObjective: ObjectiveId?, selfRole: GoldorRole?, now: Long) {
            val objective = station.objective
            val info = state.state(objective)
            val role = state.profile.assignee(objective)
            val cx = left + station.mapX; val cy = top + station.mapY
            val r = 7
            val border = when {
                info.status == ObjectiveStatus.COMPLETE -> 0xFF39D353.toInt()
                role != null -> -0x1000000 or role.color
                else -> 0xFF8A8F98.toInt()
            }
            val fill = when {
                info.status == ObjectiveStatus.COMPLETE -> if (info.ambiguous) 0xB0398F53.toInt() else 0xD039D353.toInt()
                info.status == ObjectiveStatus.ACTIVE -> 0xD0806010.toInt()
                else -> 0xD0101014.toInt()
            }
            if (objective == selfObjective) graphics.fill(cx - r - 2, cy - r - 2, cx + r + 3, cy + r + 3, 0xFFFFFFFF.toInt())
            else if (role != null && role == selfRole) graphics.fill(cx - r - 1, cy - r - 1, cx + r + 2, cy + r + 2, 0x90FFFFFF.toInt())
            graphics.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, border)
            graphics.fill(cx - r + 2, cy - r + 2, cx + r - 1, cy + r - 1, fill)
            val label = when {
                info.status == ObjectiveStatus.COMPLETE -> if (info.ambiguous) "~" else "✓"
                info.puzzle != null -> info.puzzle.code.uppercase()
                else -> objective.label.take(2)
            }
            val width = MC.font.width(label)
            graphics.text(MC.font, label, cx - width / 2 + 1, cy - 3, -1, true)
            // Who is on it, by initial, under the marker; a stale worker shows grey.
            val worker = info.by?.takeIf { info.status == ObjectiveStatus.ACTIVE }
            if (worker != null) {
                val fresh = state.position(worker, now) != null || state.player(worker)?.sm == true
                graphics.text(MC.font, worker.take(3), cx - MC.font.width(worker.take(3)) / 2, cy + r + 2, if (fresh) 0xFFFFFFFF.toInt() else 0xFF808080.toInt(), true)
            }
        }
    }

    private const val PREVIEW_NOW = 1_000_000L
    /** Fixed editor example in S2; never part of a run. */
    private val previewState = GoldorRunState(GoldorProfiles.SKY_MYCE).apply {
        start(PREVIEW_NOW - 20_000)
        listOf("You" to GoldorRole.TANK, "Bers" to GoldorRole.BERSERKER, "Healz" to GoldorRole.HEALER, "Mago" to GoldorRole.MAGE, "Arch" to GoldorRole.ARCHER)
            .forEach { (name, role) -> updatePlayer(name) { it.copy(role = role, sm = true, position = BossPoint(50.0, 110.0, 125.0), seenAt = PREVIEW_NOW) } }
        repeat(7) { chatCompletion("Arch", if (it < 4) ObjectiveKind.TERMINAL else if (it == 4) ObjectiveKind.DEVICE else ObjectiveKind.LEVER, it + 1, 7, PREVIEW_NOW - 15_000) }
        gateOpened(PREVIEW_NOW - 14_000)
        markComplete(ObjectiveId.terminal(GoldorSection.S2, 1), "You", StateSource.LOCAL, PREVIEW_NOW - 5_000)
        markActive(ObjectiveId.terminal(GoldorSection.S2, 4), "Bers", PuzzleType.STARTS_WITH, 2, StateSource.SHARED, PREVIEW_NOW - 1_000)
        setRoute(EarlyEnter.HEE3, RouteState.IN, StateSource.SHARED, PREVIEW_NOW)
    }
    private val previewAdvice by lazy { GoldorEngine.advise(previewState, SelfContext("You", position = BossPoint(68.0, 109.0, 122.0)), PREVIEW_NOW) }
}
