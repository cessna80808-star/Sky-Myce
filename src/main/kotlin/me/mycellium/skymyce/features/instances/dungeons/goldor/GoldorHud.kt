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

/**
 * The party strip: each player's class letter, name and what they are on right now. "On" is live: the objective
 * they are working (shared or seen), else the station they stand at, else their next planned objective. Dead players
 * and players with no class are marked.
 */
internal data class PartyEntry(val name: String, val role: GoldorRole?, val doing: String, val live: Boolean, val alive: Boolean)

internal fun goldorPartyStrip(state: GoldorRunState, advice: GoldorAdvice, now: Long): List<PartyEntry> =
    state.players.values.sortedBy { it.role?.ordinal ?: 99 }.map { player ->
        val working = state.objectives.entries.firstOrNull { (_, o) -> o.status == ObjectiveStatus.ACTIVE && o.by.equals(player.name, true) }?.key
        val position = state.position(player.name, now)
        val standing = position?.let { p -> GoldorLayout.stations(state.section).firstOrNull { st -> st.points.any { it.distance(p) <= GoldorLayout.MATCH_RADIUS } } }
        val planned = advice.players.firstOrNull { it.name.equals(player.name, true) }?.objective
        val doing = when {
            !player.alive -> "dead"
            working != null -> working.label + (state.state(working).puzzle?.let { " ${it.code.uppercase()}" } ?: "")
            standing != null -> "at ${standing.objective.label}"
            planned != null -> "→ ${planned.label}"
            else -> "free"
        }
        PartyEntry(player.name, player.role, doing, working != null || standing != null, player.alive)
    }

/** The class shown on an objective: whoever completed or is working it, else the class the strategy assigns. */
internal fun objectiveClass(state: GoldorRunState, objective: ObjectiveId): Pair<GoldorRole?, Boolean> {
    val info = state.state(objective)
    val actual = info.by?.takeIf { info.status != ObjectiveStatus.OPEN && !info.ambiguous }?.let { state.player(it)?.role }
    return if (actual != null) actual to true else state.profile.assignee(objective) to false
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
                // The party, pinned to the bottom of the column: class letter, name, and what they are on.
                val strip = goldorPartyStrip(state, advice, now)
                var stripY = y() + height() - 4 - strip.size * 10
                if (stripY > lineY + 4) for (entry in strip) {
                    val colour = entry.role?.let { -0x1000000 or it.color } ?: 0xFF808080.toInt()
                    graphics.fill(textX, stripY + 1, textX + 7, stripY + 8, if (entry.alive) colour else 0xFF505050.toInt())
                    graphics.text(MC.font, entry.role?.short ?: "?", textX + 1, stripY, 0xFF101014.toInt(), false)
                    val name = entry.name.take(10)
                    graphics.text(MC.font, name, textX + 10, stripY, if (entry.alive) 0xFFE0E0E0.toInt() else 0xFF707070.toInt(), true)
                    val doing = entry.doing
                    graphics.text(MC.font, doing, x() + width() - 4 - MC.font.width(doing), stripY,
                        if (!entry.alive) 0xFFFF5555.toInt() else if (entry.live) 0xFFFFFFFF.toInt() else 0xFF909090.toInt(), true)
                    stripY += 10
                }
            }
        }

        /** One objective: role colour border, status fill, label or puzzle letter, and a white ring for your own. */
        private fun marker(graphics: OwoUIGraphics, state: GoldorRunState, station: Station, left: Int, top: Int,
                           selfObjective: ObjectiveId?, selfRole: GoldorRole?, now: Long) {
            val objective = station.objective
            val info = state.state(objective)
            val (role, actual) = objectiveClass(state, objective)
            val cx = left + station.mapX; val cy = top + station.mapY
            val r = 7
            val border = when {
                role != null && actual -> -0x1000000 or role.color
                info.status == ObjectiveStatus.COMPLETE -> 0xFF39D353.toInt()
                // Planned but not started: the class colour, dimmed.
                role != null -> 0x90000000.toInt() or role.color
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
            // Who is on it: their class letter in its colour and their initials; a stale worker shows grey.
            val worker = info.by?.takeIf { info.status == ObjectiveStatus.ACTIVE }
            if (worker != null) {
                val fresh = state.position(worker, now) != null || state.player(worker)?.sm == true
                val letter = state.player(worker)?.role?.short.orEmpty()
                val tag = "$letter${worker.take(3)}"
                val tx = cx - MC.font.width(tag) / 2
                if (letter.isNotEmpty()) graphics.text(MC.font, letter, tx, cy + r + 2, -0x1000000 or (state.player(worker)?.role?.color ?: 0xFFFFFF), true)
                graphics.text(MC.font, worker.take(3), tx + MC.font.width(letter), cy + r + 2, if (fresh) 0xFFFFFFFF.toInt() else 0xFF808080.toInt(), true)
            }
            // Players standing at this station, as class pips along the marker's right edge.
            state.players.values.filter { p -> p.alive && p.role != null && p.name != worker &&
                state.position(p.name, now)?.let { pos -> station.points.any { it.distance(pos) <= GoldorLayout.MATCH_RADIUS } } == true }
                .take(3).forEachIndexed { index, p ->
                    val py = cy - r + index * 5
                    graphics.fill(cx + r + 2, py, cx + r + 6, py + 4, -0x1000000 or p.role!!.color)
                }
        }
    }

    private const val PREVIEW_NOW = 1_000_000L
    /** Fixed editor example in S2; never part of a run. */
    private val previewState = GoldorRunState(GoldorProfiles.SKY_MYCE).apply {
        start(PREVIEW_NOW - 20_000)
        // Each player where they would be: Berserker on T4, Archer on T5, Mage at the lights, Healer already in HEE3.
        listOf(Triple("You", GoldorRole.TANK, BossPoint(68.0, 109.0, 122.0)), Triple("Bers", GoldorRole.BERSERKER, BossPoint(40.0, 124.0, 122.0)),
            Triple("Healz", GoldorRole.HEALER, BossPoint(2.5, 109.0, 104.5)), Triple("Mago", GoldorRole.MAGE, BossPoint(60.5, 132.0, 140.5)),
            Triple("Arch", GoldorRole.ARCHER, BossPoint(39.0, 108.0, 143.0)))
            .forEach { (name, role, at) -> updatePlayer(name) { it.copy(role = role, sm = true, position = at, seenAt = PREVIEW_NOW) } }
        repeat(7) { chatCompletion("Arch", if (it < 4) ObjectiveKind.TERMINAL else if (it == 4) ObjectiveKind.DEVICE else ObjectiveKind.LEVER, it + 1, 7, PREVIEW_NOW - 15_000) }
        gateOpened(PREVIEW_NOW - 14_000)
        markComplete(ObjectiveId.terminal(GoldorSection.S2, 1), "You", StateSource.LOCAL, PREVIEW_NOW - 5_000)
        markActive(ObjectiveId.terminal(GoldorSection.S2, 4), "Bers", PuzzleType.STARTS_WITH, 2, StateSource.SHARED, PREVIEW_NOW - 1_000)
        setRoute(EarlyEnter.HEE3, RouteState.IN, StateSource.SHARED, PREVIEW_NOW)
    }
    private val previewAdvice by lazy { GoldorEngine.advise(previewState, SelfContext("You", position = BossPoint(68.0, 109.0, 122.0)), PREVIEW_NOW) }
}
