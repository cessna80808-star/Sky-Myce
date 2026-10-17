package me.mycellium.skymyce.features.instances.dungeons.goldor

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.splits.terminalProgress
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils
import me.mycellium.skymyce.utils.Utils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

/** Hypixel's Goldor phase lines (Odin `TickTimers`/`TerminalTimes`, current as of 2026-10-16). */
internal const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
internal const val GATE_DESTROYED = "The gate has been destroyed!"
internal const val CORE_OPENING = "The Core entrance is opening!"

/** Progress through a Numbers or Panes terminal in quarters, from the lime (done) and red (open) panes. */
internal fun puzzleQuarters(puzzle: PuzzleType, itemIds: List<String>): Int? {
    if (puzzle != PuzzleType.NUMBERS && puzzle != PuzzleType.PANES) return null
    val done = itemIds.count { it == "minecraft:lime_stained_glass_pane" }
    val open = itemIds.count { it == "minecraft:red_stained_glass_pane" }
    if (done + open == 0) return null
    return (done * 4 / (done + open)).coerceIn(0, 3)
}

/**
 * Feeds the Goldor run state from what this client sees (chat, its own terminal windows, loaded player positions)
 * and from other Sky-Myce users' party chat, sends this player's own updates, and keeps the advice the HUD shows.
 * Party chat is the only transport; nothing here touches the Sky-Myce backend.
 */
object GoldorCoordinator : SkyMyceModule() {
    val state = GoldorRunState(GoldorProfiles.SKY_MYCE)
    private val dedupe = ProtocolDedupe()
    private val outbox = ProtocolOutbox()
    /** Manual class overrides by player name, for parties where the tab list is wrong or missing. */
    val overrides = LinkedHashMap<String, GoldorRole>()
    @Volatile var advice: GoldorAdvice? = null
        private set
    private var adviceRevision = -1L
    private var adviceAt = 0L
    private var ticks = 0
    private var lastSelf: BossPoint? = null
    private var leaptAt: Long? = null
    private var alertedFor: Long? = null
    private var syncAskedAt = 0L
    private val repliedTo = HashMap<String, Long>()
    private var openTerminal: ObjectiveId? = null
    private var openPuzzle: PuzzleType? = null
    private var sentRole = false

    private fun now() = System.currentTimeMillis()
    private fun selfName() = MC.instance.user.name
    private fun floorSupported() = DungeonAPI.dungeonFloor?.name?.let { it == "F7" || it == "M7" } == true
    private fun inBoss() = isEnabled() && Settings.enabled && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && floorSupported()

    /** Party lines are read here rather than from the shared party event, which does not see lines hidden below. */
    private val partyLine = Regex("^Party > ${me.mycellium.skymyce.api.MiscApi.RANK_USERNAME}: (?<message>.{1,64})$")

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        val text = event.text
        partyLine.matchEntire(text)?.let { match ->
            val message = match.groups["message"]!!.value
            if (!GoldorProtocol.looksLikeProtocol(message)) return@let
            if (Settings.hideMessages) event.cancel()
            onProtocol(match.groups["player"]!!.value, message)
            return
        }
        if (!inBoss()) return
        val now = now()
        when (text) {
            GOLDOR_START -> { begin(now); return }
            GATE_DESTROYED -> { state.gateOpened(now); return }
            CORE_OPENING -> { state.coreOpened(now); return }
        }
        val match = terminalProgress.matchEntire(text) ?: return
        val kind = when { text.contains(" terminal!") -> ObjectiveKind.TERMINAL; text.contains(" device!") -> ObjectiveKind.DEVICE; else -> ObjectiveKind.LEVER }
        if (!state.active) { begin(now); offer(GoldorEvent.SyncRequest) }
        val player = match.groupValues[1]
        val objective = state.chatCompletion(player, kind, match.groupValues[2].toInt(), match.groupValues[3].toInt(), now)
        if (player.equals(selfName(), true) && objective != null) {
            when (kind) {
                ObjectiveKind.TERMINAL -> (openTerminal ?: objective).let { offer(GoldorEvent.Completed(it)) }
                ObjectiveKind.LEVER -> offer(GoldorEvent.Lever(objective))
                ObjectiveKind.DEVICE -> offer(GoldorEvent.Device(objective.section, true))
            }
            if (kind == ObjectiveKind.TERMINAL) { openTerminal = null; openPuzzle = null }
        }
    }

    private fun onProtocol(sender: String, message: String) {
        if (!Settings.receiveMessages || !inBoss()) return
        val now = now()
        if (!dedupe.accept(sender, selfName(), message, now)) return
        val decoded = GoldorProtocol.decode(message) ?: return
        // Only members of this run count; a lookalike from anyone else is ignored.
        if (state.player(sender) == null && DungeonAPI.teammates.none { it.name.equals(sender, true) }) return
        apply(sender, decoded.events, now)
    }

    /** Applies another client's events. Exposed for the `/sm goldor chat` debug command. */
    internal fun apply(sender: String, events: List<GoldorEvent>, now: Long) {
        state.updatePlayer(sender) { it.copy(sm = true, lastMessageAt = now) }
        for (event in events) when (event) {
            is GoldorEvent.Role -> state.updatePlayer(sender) { it.copy(role = overrides[sender] ?: it.role ?: event.role) }
            is GoldorEvent.Section -> state.updatePlayer(sender) { it.copy(reportedSection = event.section) }
            is GoldorEvent.Working -> state.markActive(event.terminal, sender, event.puzzle, event.quarters, StateSource.SHARED, now)
            is GoldorEvent.Completed -> state.exactComplete(event.terminal, sender, now)
            is GoldorEvent.Lever -> state.exactComplete(event.lever, sender, now)
            is GoldorEvent.Device -> if (event.done) state.markComplete(ObjectiveId.device(event.section), sender, StateSource.SHARED, now)
                else state.markActive(ObjectiveId.device(event.section), sender, null, 0, StateSource.SHARED, now)
            is GoldorEvent.Route -> state.setRoute(event.route, event.state, StateSource.SHARED, now)
            GoldorEvent.SyncRequest -> answerSync(sender, now)
        }
    }

    /** Our own state, once per requester per 10 s. Replies are never answered, so two clients cannot loop. */
    private fun answerSync(requester: String, now: Long) {
        if ((repliedTo[requester.lowercase()] ?: 0L) > now - 10_000) return
        repliedTo[requester.lowercase()] = now
        outbox.resendable(listOf("r", "p"))
        selfRole()?.let { offer(GoldorEvent.Role(it)) }
        offer(GoldorEvent.Section(state.section))
        openTerminal?.let { offer(GoldorEvent.Working(it, openPuzzle, state.state(it).quarters)) }
    }

    private fun selfRole() = state.player(selfName())?.role

    private fun begin(now: Long) {
        state.profile = GoldorProfiles.byId(Settings.profile.id)
        state.start(now)
        refreshRoster(now)
        selfRole()?.let { offer(GoldorEvent.Role(it)); sentRole = true }
        offer(GoldorEvent.Section(GoldorSection.S1))
    }

    private fun offer(event: GoldorEvent) { if (Settings.sendMessages) outbox.offer(event) }

    @Subscription
    fun onContainer(event: ContainerInitializedEvent) {
        if (!inBoss() || !state.active) return
        val puzzle = PuzzleType.ofTitle(event.title) ?: return
        val now = now()
        val player = MC.instance.player ?: return
        // The terminal being used is the one beside us; early enters can be a section ahead.
        val sections = listOfNotNull(state.section, state.section.next)
        val station = sections.firstNotNullOfOrNull { GoldorLayout.nearest(it, ObjectiveKind.TERMINAL, player.x, player.y, player.z, emptySet()) } ?: return
        openTerminal = station.objective; openPuzzle = puzzle
        val quarters = puzzleQuarters(puzzle, event.screen.menu.slots.filter { it.container !is Inventory }.map { BuiltInRegistries.ITEM.getKey(it.item.item).toString() }) ?: 0
        state.markActive(station.objective, selfName(), puzzle, quarters, StateSource.LOCAL, now)
        offer(GoldorEvent.Working(station.objective, puzzle, quarters))
    }

    @Subscription
    fun onSlot(event: InventoryChangeEvent) {
        val terminal = openTerminal ?: return
        val puzzle = openPuzzle ?: return
        if (!inBoss() || PuzzleType.ofTitle(event.title) != puzzle) return
        val quarters = puzzleQuarters(puzzle, event.screen.menu.slots.filter { it.container !is Inventory }.map { BuiltInRegistries.ITEM.getKey(it.item.item).toString() }) ?: return
        if (quarters <= state.state(terminal).quarters) return
        state.markActive(terminal, selfName(), puzzle, quarters, StateSource.LOCAL, now())
        offer(GoldorEvent.Working(terminal, puzzle, quarters))
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onLeave() {
        state.reset(); dedupe.clear(); outbox.clear(); repliedTo.clear()
        advice = null; adviceRevision = -1; leaptAt = null; lastSelf = null; alertedFor = null
        openTerminal = null; openPuzzle = null; sentRole = false
    }

    @Subscription(TickEvent::class)
    fun onTick() {
        if (!inBoss()) { if (state.active) onLeave(); return }
        val now = now()
        val player = MC.instance.player ?: return
        val here = BossPoint(player.x, player.y, player.z)
        // A leap is a jump of more than 10 blocks within one tick that lands beside a teammate; an etherwarp lands on a block.
        lastSelf?.let { before ->
            if (state.active && before.distance(here) > 10.0 && MC.instance.level?.players().orEmpty()
                    .any { it !== player && it.distanceToSqr(player.x, player.y, player.z) <= 9.0 }) leaptAt = now
        }
        lastSelf = here
        if (++ticks % 5 == 0) {
            refreshRoster(now)
            inferRoutes(now)
            if (!sentRole) selfRole()?.let { if (state.active) { offer(GoldorEvent.Role(it)); sentRole = true } }
        }
        // Party chat only: outside a party nothing is sent (and nothing falls back to any other transport).
        if (Settings.sendMessages && tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI.inParty) outbox.poll(now)?.let { PlayerUtils.sendCommand("pc $it") }
        // Advice changes with state, and with time while a timed plan counts down.
        if (state.active && (state.revision != adviceRevision || now - adviceAt >= 200)) {
            adviceRevision = state.revision; adviceAt = now
            val next = GoldorEngine.advise(state, SelfContext(selfName(), leaptAt, here), now)
            advice = next
            alert(next.self)
        }
    }

    private fun alert(self: Recommendation?) {
        val plan = self?.leap?.plan ?: return
        if (!Settings.leapAlert || plan.phase != LeapPhase.LEAP || plan.leapFrom == null || alertedFor == plan.leapFrom) return
        alertedFor = plan.leapFrom
        Utils.displayTitle("§c§lLEAP", "§f${self.leap.targetName ?: self.leap.targetRole?.title ?: ""} §7· ${self.leap.reason}")
    }

    /** Classes from Hypixel's dungeon party (or manual overrides), positions and life from loaded players. */
    private fun refreshRoster(now: Long) {
        val players = MC.instance.level?.players().orEmpty()
        val party = DungeonAPI.teammates + listOfNotNull(DungeonAPI.ownPlayer)
        for (member in party) {
            val role = overrides.entries.firstOrNull { it.key.equals(member.name, true) }?.value ?: GoldorRole.ofName(member.dungeonClass?.name)
            val entity = players.firstOrNull { it.gameProfile.name.equals(member.name, true) }
            state.updatePlayer(member.name) { p ->
                p.copy(role = role ?: p.role, alive = !member.dead,
                    position = entity?.let { BossPoint(it.x, it.y, it.z) } ?: p.position, seenAt = if (entity != null) now else p.seenAt)
            }
        }
        if (party.none { it.name.equals(selfName(), true) }) MC.instance.player?.let { me ->
            state.updatePlayer(selfName()) { p -> p.copy(role = overrides[selfName()] ?: GoldorRole.ofName(DungeonAPI.dungeonClass?.name) ?: p.role,
                position = BossPoint(me.x, me.y, me.z), seenAt = now) }
        }
    }

    /**
     * Route progress from positions: a runner within [GoldorLayout.ROUTE_RADIUS] of the route point is in. The route
     * itself is never removed when the runner cannot be seen.
     */
    private fun inferRoutes(now: Long) {
        if (!state.active) return
        for ((route, role) in state.profile.routes) {
            if (state.route(route).state == RouteState.IN || route.section.ordinal <= state.section.ordinal) continue
            val runner = state.playerWith(role) ?: continue
            val position = state.position(runner.name, now) ?: continue
            val point = GoldorLayout.routePoints[route] ?: continue
            if (point.distance(position) <= GoldorLayout.ROUTE_RADIUS) {
                state.setRoute(route, RouteState.IN, StateSource.INFERRED, now)
                if (runner.name.equals(selfName(), true)) offer(GoldorEvent.Route(route, RouteState.IN))
            }
        }
    }

    /** Debug: what the coordinator sees, as chat lines. */
    fun status(): List<String> {
        val now = now()
        val lines = ArrayList<String>()
        lines += "§bGoldor §7${if (state.active) state.section.title else "inactive"} · ${state.profile.title} · rev ${state.revision}"
        state.players.values.forEach { p ->
            lines += "§7${p.role?.short ?: "?"} §f${p.name}${if (p.sm) " §a[SM]" else ""}${if (!p.alive) " §cdead" else ""} §8${state.position(p.name, now)?.let { "seen" } ?: "unseen"}"
        }
        GoldorLayout.stations(state.section).forEach { s ->
            val o = state.state(s.objective)
            lines += "§7${s.objective.label} §f${o.status.name.lowercase()}${o.by?.let { " by $it" } ?: ""}${o.puzzle?.let { " · ${it.title}" } ?: ""}${if (o.ambiguous) " §e(guessed)" else ""}"
        }
        advice?.self?.let { lines += "§e${it.headline} §7— ${it.reason}" }
        return lines
    }

    override fun init() { SkyMyce.logger.debug("Goldor coordination ready") }
}
