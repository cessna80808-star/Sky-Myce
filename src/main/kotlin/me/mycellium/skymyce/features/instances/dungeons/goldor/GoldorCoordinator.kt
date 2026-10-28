package me.mycellium.skymyce.features.instances.dungeons.goldor

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.splits.terminalProgress
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils
import me.mycellium.skymyce.utils.Utils
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
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
/**
 * Goldor's line as each gate goes (seen in a 2026-10-18 M7 recording, in order S1, S2, S3). That recording never
 * showed "The gate has been destroyed!", so these count as the gate opening too.
 */
internal val GOLDOR_GATE_LINES = setOf(
    "[BOSS] Goldor: I will replace that gate with a stronger one!",
    "[BOSS] Goldor: The little ants have a brain it seems.",
    "[BOSS] Goldor: YOUR END IS NEAR!!",
)
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
 * Another mod's Melody call in party chat ("Melody Terminal start!", "Melody 33%", "I ❤ Melody 2/3", "ou shii 66%"):
 * the caller's progress in quarters, 0 at the start, or null for any other message. A bare percentage counts too, since
 * some players rename the call; thirds need the word Melody, as blessing procs also say "(1/3)".
 */
internal fun melodyQuarters(message: String): Int? {
    MELODY_PERCENT.find(message)?.let { return it.groupValues[1].toInt() * 4 / 100 }
    if (!message.contains("melody", ignoreCase = true)) return null
    return MELODY_THIRDS.find(message)?.let { it.groupValues[1].toInt() * 4 / 3 } ?: 0
}

private val MELODY_PERCENT = Regex("(?<![\\d.])(25|33|50|66|67|75) ?%")
private val MELODY_THIRDS = Regex("(?<!\\d)([12])/3(?!\\d)")

/**
 * Feeds the Goldor run state from what this client sees (chat, its own terminal windows, loaded player positions)
 * and from other Sky-Myce users' party chat, sends this player's own updates, and keeps the advice the HUD shows.
 * Party chat is the only transport; nothing here touches the Sky-Myce backend.
 */
object GoldorCoordinator : SkyMyceModule() {
    val state = GoldorRunState(GoldorBaseline.GOLD_STANDARD)
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
    private var alertedFor: String? = null
    private var stackAlertedFor: String? = null
    private val repliedTo = HashMap<String, Long>()
    private var openTerminal: ObjectiveId? = null
    private var openPuzzle: PuzzleType? = null
    private var sentRole = false
    /** The section whose finished state has not been learned from yet. */
    private var learnFrom = GoldorSection.S1

    private fun now() = System.currentTimeMillis()
    private fun selfName() = MC.instance.user.name
    private fun floorSupported() = DungeonAPI.dungeonFloor?.name?.let { it == "F7" || it == "M7" } == true
    private fun inBoss() = isEnabled() && Settings.enabled && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && floorSupported()

    /** Party lines are read here rather than from the shared party event, which does not see lines hidden below. */
    private val partyLine = Regex("^Party > ${me.mycellium.skymyce.api.MiscApi.RANK_USERNAME}: (?<message>.{1,64})$")
    /** A teammate calling a failed Simon Says: "SS reset", "SSRS", "ss failed". */
    private val ssReset = Regex("(?i)\\b(ss ?reset|ssrs|ss fail(ed)?)\\b")

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        val text = event.text
        partyLine.matchEntire(text)?.let { match ->
            val message = match.groups["message"]!!.value
            if (!GoldorProtocol.looksLikeProtocol(message)) {
                if (inBoss() && ssReset.containsMatchIn(message)) state.ssReset(now())
                if (inBoss()) melodyQuarters(message)?.let { melodyCall(match.groups["player"]!!.value, it) }
                return@let
            }
            if (Settings.hideMessages) event.cancel()
            onProtocol(match.groups["player"]!!.value, message)
            return
        }
        if (!inBoss()) return
        val now = now()
        when (text) {
            GOLDOR_START -> { begin(now); return }
            GATE_DESTROYED, in GOLDOR_GATE_LINES -> { state.gateOpened(now); return }
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
        state.updatePlayer(sender) { it.copy(sm = true) }
        for (event in events) when (event) {
            is GoldorEvent.Role -> state.updatePlayer(sender) { it.copy(role = overrides[sender] ?: it.role ?: event.role) }
            // A sender's section is accepted on the wire but nothing uses it.
            is GoldorEvent.Section -> Unit
            is GoldorEvent.Working -> state.markActive(event.terminal, sender, event.puzzle, event.quarters, StateSource.SHARED, now)
            is GoldorEvent.Completed -> state.exactComplete(event.terminal, sender, now)
            is GoldorEvent.Lever -> state.exactComplete(event.lever, sender, now)
            is GoldorEvent.Device -> if (event.done) state.markComplete(ObjectiveId.device(event.section), sender, StateSource.SHARED, now)
                else state.markActive(ObjectiveId.device(event.section), sender, null, 0, StateSource.SHARED, now)
            is GoldorEvent.Route -> state.setRoute(event.route, event.state, StateSource.SHARED, now)
            GoldorEvent.SyncRequest -> answerSync(sender, now)
        }
    }

    /**
     * A teammate's Melody call: marks the terminal they are on as Melody with their progress, so plans and leap timing
     * use it without their having Sky-Myce. Their terminal is the one already marked theirs, else the one they stand at,
     * else the next terminal in their plan. Our own Melody is read from the terminal window instead.
     */
    private fun melodyCall(sender: String, quarters: Int) {
        if (!state.active || sender.equals(selfName(), true)) return
        val player = state.player(sender) ?: return
        val now = now()
        val terminal = state.objectives.entries.firstOrNull { (id, o) -> id.kind == ObjectiveKind.TERMINAL && o.status == ObjectiveStatus.ACTIVE && o.by.equals(sender, true) }?.key
            ?: state.position(sender, now)?.let { p -> listOfNotNull(state.section, state.section.next).firstNotNullOfOrNull { GoldorLayout.nearest(it, ObjectiveKind.TERMINAL, p.x, p.y, p.z, emptySet()) }?.objective }
            ?: player.role?.let { role -> GoldorEngine.nextObjective(state, GoldorEngine.effectivePlan(state, state.section, role), sender) }?.takeIf { it.kind == ObjectiveKind.TERMINAL }
            ?: return
        state.markActive(terminal, sender, PuzzleType.MELODY, quarters, StateSource.CHAT, now)
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
        val base = GoldorProfiles.byId(Settings.profile.id)
        // Learned roles are observations; they only replace assignments when the player opts in.
        state.profile = if (Settings.applyLearned) base.withLearned(GoldorLearning.load()) else base
        learnFrom = GoldorSection.S1
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
        val quarters = puzzleQuarters(puzzle, terminalItemIds(event.screen)) ?: 0
        state.markActive(station.objective, selfName(), puzzle, quarters, StateSource.LOCAL, now)
        offer(GoldorEvent.Working(station.objective, puzzle, quarters))
    }

    @Subscription
    fun onSlot(event: InventoryChangeEvent) {
        val terminal = openTerminal ?: return
        val puzzle = openPuzzle ?: return
        if (!inBoss() || PuzzleType.ofTitle(event.title) != puzzle) return
        val quarters = puzzleQuarters(puzzle, terminalItemIds(event.screen)) ?: return
        if (quarters <= state.state(terminal).quarters) return
        state.markActive(terminal, selfName(), puzzle, quarters, StateSource.LOCAL, now())
        offer(GoldorEvent.Working(terminal, puzzle, quarters))
    }

    /** Item ids of the terminal's own slots, without the player inventory below it. */
    private fun terminalItemIds(screen: AbstractContainerScreen<*>) =
        screen.menu.slots.filter { it.container !is Inventory }.map { BuiltInRegistries.ITEM.getKey(it.item.item).toString() }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onLeave() {
        state.reset(); dedupe.clear(); outbox.clear(); repliedTo.clear()
        advice = null; adviceRevision = -1; leaptAt = null; lastSelf = null; alertedFor = null; stackAlertedFor = null
        openTerminal = null; openPuzzle = null; sentRole = false; learnFrom = GoldorSection.S1
    }

    @Subscription(TickEvent::class)
    fun onTick() = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.GOLDOR_TICK) { tickGoldor() }

    private fun tickGoldor() {
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
        if (state.active) state.gateTimeout(now)
        learnFinished()
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
            val next = GoldorEngine.advise(state, SelfContext(selfName(), leaptAt, here, Settings.reactionTime.toLong()), now)
            advice = next
            alert(next.self)
        }
    }

    /** Each section is learned from once, when the run has moved past it (S4 when the Core opens). */
    private fun learnFinished() {
        if (!state.active || !Settings.learnRoles) return
        while (learnFrom.ordinal < state.section.ordinal && learnFrom != GoldorSection.CORE) {
            GoldorLearning.record(observeSection(state, learnFrom))
            learnFrom = learnFrom.next ?: return
        }
    }

    /** A title once per leap that is due now (a timed window opening, or a leap after an objective) and once per new stack. */
    private fun alert(self: Recommendation?) {
        val leap = self?.leap
        if (leap != null && self.urgent) {
            // One title per section, target and Goldor tick (a "leap now" plan's start moves with the clock).
            val key = "${state.section}:${leap.targetName ?: leap.targetRole}:${leap.plan?.tickAt}"
            if (Settings.leapAlert && key != alertedFor) {
                alertedFor = key
                Utils.displayTitle("§c§lLEAP", "§f${leap.targetName ?: leap.targetRole?.title ?: ""} §7· ${leap.reason}")
            }
            return
        }
        val stack = self?.stackWith?.let { "${self.objective?.label}:$it" }
        if (stack != null && Settings.stackAlert && stack != stackAlertedFor) {
            Utils.displayTitle("§e§lSTACK", "§f${self.objective?.label ?: ""} §7with §f${self.stackWith}")
        }
        stackAlertedFor = stack
    }

    /** Classes from Hypixel's dungeon party (or manual overrides), positions and life from loaded players. */
    private fun refreshRoster(now: Long) {
        val players = MC.instance.level?.players().orEmpty()
        val party = DungeonAPI.teammates + listOfNotNull(DungeonAPI.ownPlayer)
        // An empty list means the party is not read yet, not that everyone left.
        if (party.isNotEmpty()) state.retainPlayers(party.map { it.name } + selfName())
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

/**
 * `/skymyce goldor`: status, manual class overrides for parties whose classes cannot be read, a section override for
 * recovery, and a local protocol test that never sends anything.
 */
fun goldorCommand(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("goldor")
    .executes { MC.instance.execute { GoldorCoordinator.status().forEach(::displayModMessage) }; 1 }
    .then(literal("role").then(argument("player", StringArgumentType.word()).then(argument("class", StringArgumentType.word()).executes { context ->
        val player = StringArgumentType.getString(context, "player")
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        MC.instance.execute {
            if (role == null) displayModMessage("§cUnknown class. Use Healer, Mage, Berserk, Archer or Tank.")
            else {
                GoldorCoordinator.overrides.entries.removeIf { it.key.equals(player, true) || it.value == role }
                GoldorCoordinator.overrides[player] = role
                GoldorCoordinator.state.updatePlayer(player) { it.copy(role = role) }
                displayModMessage("§7Goldor: §f$player §7is §f${role.title}")
            }
        }
        1
    })))
    .then(literal("unrole").then(argument("player", StringArgumentType.word()).executes { context ->
        val player = StringArgumentType.getString(context, "player")
        MC.instance.execute { GoldorCoordinator.overrides.entries.removeIf { it.key.equals(player, true) }; displayModMessage("§7Goldor: cleared $player") }
        1
    }))
    .then(literal("section").then(argument("section", StringArgumentType.word()).executes { context ->
        val name = StringArgumentType.getString(context, "section").uppercase()
        MC.instance.execute {
            val section = GoldorSection.entries.firstOrNull { it.name == name || it.title.uppercase() == name }
            if (section == null) displayModMessage("§cUse s1, s2, s3, s4 or core.") else { GoldorCoordinator.state.setSection(section); displayModMessage("§7Goldor: section ${section.title}") }
        }
        1
    }))
    .then(literal("guide").executes {
        MC.instance.execute { guideMessages(GoldorCoordinator.state.profile, null, null).forEach(::displayModMessage) }
        1
    }.then(argument("class", StringArgumentType.word()).executes { context ->
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        MC.instance.execute { guideMessages(GoldorCoordinator.state.profile, role ?: return@execute displayModMessage("§cUnknown class."), null).forEach(::displayModMessage) }
        1
    }.then(argument("section", StringArgumentType.word()).executes { context ->
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        val section = GoldorSection.entries.firstOrNull { it.name.equals(StringArgumentType.getString(context, "section"), true) && it != GoldorSection.CORE }
        MC.instance.execute {
            if (role == null || section == null) displayModMessage("§cUse /sm goldor guide <class> <s1-s4>.")
            else guideMessages(GoldorCoordinator.state.profile, role, section).forEach(::displayModMessage)
        }
        1
    })))
    .then(literal("if").executes {
        MC.instance.execute { conditionLines(GoldorCoordinator.state).forEach(::displayModMessage) }
        1
    }.then(argument("condition", StringArgumentType.word()).then(argument("value", StringArgumentType.word()).executes { context ->
        val condition = GuideCondition.ofId(StringArgumentType.getString(context, "condition"))
        val value = when (StringArgumentType.getString(context, "value").lowercase()) { "yes", "true", "y" -> true; "no", "false", "n" -> false; "clear" -> null; else -> Unit }
        MC.instance.execute {
            when {
                condition == null -> displayModMessage("§cUnknown condition. /sm goldor if lists them.")
                value == Unit -> displayModMessage("§cUse yes, no or clear.")
                else -> {
                    GoldorCoordinator.state.confirm(condition, value as Boolean?)
                    displayModMessage("§7Goldor: ${condition.question} — §f${when (value) { true -> "yes"; false -> "no"; else -> "cleared" }} §8(this run)")
                }
            }
        }
        1
    })))
    .then(literal("learned").executes {
        MC.instance.execute { learnedLines(GoldorLearning.load()).forEach(::displayModMessage) }
        1
    }.then(literal("reset").executes {
        MC.instance.execute { GoldorLearning.reset(); displayModMessage("§7Goldor: learned roles cleared") }
        1
    }))
    .then(literal("test").then(argument("sender", StringArgumentType.word()).then(argument("message", StringArgumentType.greedyString()).executes { context ->
        val sender = StringArgumentType.getString(context, "sender")
        val message = StringArgumentType.getString(context, "message")
        MC.instance.execute {
            val decoded = GoldorProtocol.decode(message)
            if (decoded == null) displayModMessage("§cNot a valid smt1 message")
            else { GoldorCoordinator.apply(sender, decoded.events, System.currentTimeMillis()); displayModMessage("§7Applied ${decoded.events.size} event(s) from $sender locally") }
        }
        1
    })))

/**
 * The guide as chat lines: one class and section in full, one class's sections in brief, or the classes. Steps keep
 * their kind (prep, if, optional, recovery) so a conditional never reads as an order.
 */
internal fun guideText(profile: StrategyProfile, role: GoldorRole?, section: GoldorSection?): List<String> {
    val guide = profile.guide ?: return listOf("§7Goldor: ${profile.title} has no step guide. Choose a Gold standard strategy in Dungeons → Goldor Coordination.")
    if (role == null) return listOf("§b${guide.title} §7(v${guide.version}) — /sm goldor guide <class> [s1-s4]",
        "§7Classes: ${GoldorRole.entries.joinToString(", ") { it.title }}")
    if (section == null) return listOf("§b${role.title} §7— ${guide.title}") + listOf(GoldorSection.S1, GoldorSection.S2, GoldorSection.S3, GoldorSection.S4).mapNotNull { s ->
        guide.section(role, s)?.let { g -> "§f${s.title}: §7" + g.steps.filter { it.kind == StepKind.REQUIRED }.joinToString(" › ") { it.text } }
    }
    val sectionGuide = guide.section(role, section) ?: return listOf("§7No ${section.title} guide for ${role.title}.")
    return listOf("§b${role.title} ${section.title} §7— ${guide.title}") + sectionGuide.steps.map { step ->
        val mark = when (step.kind) {
            StepKind.REQUIRED -> "§f•"; StepKind.PREP -> "§3Prep"; StepKind.REMINDER -> "§8Note"; StepKind.OPTIONAL -> "§7Optional"
            StepKind.CONDITIONAL -> "§6If ${step.condition?.question ?: ""}:"; StepKind.RECOVERY -> "§cRecovery${step.condition?.let { " (${it.question})" } ?: ""}:"
        }
        "$mark §7${step.detail}"
    }
}

/** The guide lines as chat components, with the section's references as clickable links. */
internal fun guideMessages(profile: StrategyProfile, role: GoldorRole?, section: GoldorSection?): List<net.minecraft.network.chat.Component> {
    val lines = guideText(profile, role, section).map { net.minecraft.network.chat.Component.literal(it) }
    val refs = role?.let { r -> section?.let { s -> profile.guide?.section(r, s) } }?.let { g -> (g.refs + g.steps.flatMap { it.refs }).distinctBy { it.url } }.orEmpty()
    return lines + refs.map { ref ->
        net.minecraft.network.chat.Component.literal("§9§n${ref.title}${ref.note?.let { " §r§7($it)" } ?: ""}").withStyle { style ->
            style.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(ref.url)))
        }
    }
}

/** Every guide condition, its current value and how it is known. */
internal fun conditionLines(state: GoldorRunState): List<String> = listOf("§bGoldor conditions §7— /sm goldor if <name> yes|no|clear") +
    GuideCondition.entries.map { c ->
        val value = state.condition(c)
        val text = when (value.value) { Tri.TRUE -> "§ayes"; Tri.FALSE -> "§cno"; Tri.UNKNOWN -> "§8unknown" }
        "§f${c.id} §7${c.question}: $text${if (value.confirmed) " §7(you)" else ""} §8· ${c.observability.title}"
    }

/** What has been learned, per section and class, as chat lines. */
internal fun learnedLines(learned: LearnedRoles): List<String> {
    if (learned.runs == 0) return listOf("§7Goldor: nothing learned yet. Exact completions are recorded as you play F7/M7.")
    val lines = mutableListOf("§bGoldor learned roles §7(${learned.runs} runs; observations — applied only with Apply Learned Roles, after ${LearnedRoles.MIN_SECTIONS})")
    for (section in listOf(GoldorSection.S1, GoldorSection.S2, GoldorSection.S3, GoldorSection.S4)) {
        val parts = GoldorRole.entries.mapNotNull { role ->
            learned.objectives(section, role).takeIf { it.isNotEmpty() }?.let { "§f${role.short} §7${it.joinToString("+") { o -> o.label }}" }
        }
        lines += "§7${section.title}: ${parts.joinToString("§8, ").ifEmpty { "§8not enough yet" }}"
    }
    return lines
}

/**
 * How a guide step relates to the route. [PREP] and [REMINDER] are loadout and safety notes; [REQUIRED] steps are
 * the route itself; [CONDITIONAL] steps apply only when their condition holds; [OPTIONAL] steps are allowed but
 * never asked for; [RECOVERY] steps are for when something went wrong.
 */
enum class StepKind { PREP, REQUIRED, CONDITIONAL, OPTIONAL, RECOVERY, REMINDER }

/** How a condition is known: from the game and party updates, sometimes from them, or only from the player. */
enum class Observability(val title: String) { OBSERVED("seen by the mod"), PARTIAL("seen when Sky-Myce users share it"), CONFIRM("you confirm it") }

/**
 * The run facts a conditional step depends on. [id] is the stable name used by `/sm goldor if`; [question] reads as
 * "If …"; [seen] is shown once it holds. A condition the mod cannot see stays unknown until the player confirms it.
 */
enum class GuideCondition(val id: String, val question: String, val seen: String, val observability: Observability) {
    T3_TAKEN_NOT_MELODY("t3-taken", "someone is at T3 and it is not Melody", "T3 is taken and not Melody", Observability.PARTIAL),
    ARCHER_MELODY("archer-melody", "Archer has Melody", "Archer has Melody", Observability.PARTIAL),
    I4_FAILED("i4-failed", "Berserker failed i4", "Berserker failed i4", Observability.CONFIRM),
    SS_TROUBLE("ss-reset", "SS fails or is late, or Melody is very slow", "SS reset called", Observability.OBSERVED),
    SLOW_PARTY("slow-party", "the party is slow at terminals", "Slow party", Observability.CONFIRM),
    EE2_FAILED("ee2-failed", "your EE2 failed", "EE2 failed", Observability.CONFIRM),
    TANK_ARCHER_SLOW("slow-levers", "Archer and Tank are both slow", "Archer and Tank are slow", Observability.CONFIRM),
    MASK_LEFT("mask", "you have a Mask left", "Mask left", Observability.CONFIRM),
    MAGE_ROUTE_SAFE("mage-direct", "a direct leap to Mage is safe this run", "Direct Mage leap is safe", Observability.CONFIRM),
    LOW_HEALTH("dying", "you are about to die", "About to die", Observability.CONFIRM);

    companion object { fun ofId(id: String) = entries.firstOrNull { it.id.equals(id, true) } }
}

/** A three-valued fact: known true, known false, or unknown. Unknown is never shown as either. */
enum class Tri { TRUE, FALSE, UNKNOWN }

/** A condition's value and whether the player confirmed it (rather than the mod seeing it). */
data class ConditionValue(val value: Tri, val confirmed: Boolean = false)

data class GuideRef(val title: String, val url: String, val note: String? = null)

/**
 * One step of a class's section route. [text] is the short line the HUD shows; [detail] is the full instruction.
 * [objectives] tie the step to map objectives (done when all are complete); [leapTo] marks a leap step; [condition]
 * gates [StepKind.CONDITIONAL] and [StepKind.RECOVERY] steps.
 */
data class GuideStep(
    val id: String,
    val kind: StepKind,
    val text: String,
    val detail: String = text,
    val objectives: List<ObjectiveId> = emptyList(),
    val leapTo: GoldorRole? = null,
    val condition: GuideCondition? = null,
    val refs: List<GuideRef> = emptyList(),
)

data class SectionGuide(val role: GoldorRole, val section: GoldorSection, val steps: List<GuideStep>, val refs: List<GuideRef> = emptyList())

/** Whether a guide change is the supplied baseline or a later proposal, and how far it has been checked. */
enum class ChangeStatus { BASELINE, PROPOSED, TESTED, ACCEPTED }

/** One tracked change to a guide: the version it arrived in, what it touched, and where it came from. */
data class GuideChange(val version: Int, val role: GoldorRole?, val section: GoldorSection?, val summary: String, val source: String, val status: ChangeStatus)

/**
 * A class-by-class route guide. [fingerprint] is checked against [guideFingerprint]: editing any step without bumping
 * [version] and recording a [GuideChange] fails the executable check, so the baseline cannot change silently.
 */
data class StrategyGuide(
    val id: String,
    val version: Int,
    val title: String,
    val source: String,
    val sections: Map<GoldorRole, Map<GoldorSection, SectionGuide>>,
    val changes: List<GuideChange>,
    val fingerprint: String,
) {
    fun section(role: GoldorRole, section: GoldorSection) = sections[role]?.get(section)
}

/** A stable hash of every step and reference, in order. */
fun guideFingerprint(sections: Map<GoldorRole, Map<GoldorSection, SectionGuide>>): String {
    val text = buildString {
        for (role in GoldorRole.entries) for (section in GoldorSection.entries) sections[role]?.get(section)?.let { guide ->
            append(role.code).append(section.number)
            guide.refs.forEach { append('|').append(it.url).append(it.note) }
            guide.steps.forEach { s -> append('\n').append(s.id).append(s.kind).append(s.text).append(s.detail).append(s.objectives).append(s.leapTo).append(s.condition).append(s.refs.joinToString { it.url + it.note }) }
        }
    }
    // FNV-1a, 64-bit: stable across JVMs, unlike String.hashCode collisions on short edits.
    var hash = -0x340d631b7bdddcdbL
    for (c in text) { hash = hash xor c.code.toLong(); hash *= 0x100000001b3L }
    return java.lang.Long.toUnsignedString(hash, 16)
}

/**
 * The value of [condition] in this run. Observations only count when they are reliable: a completion, a shared puzzle
 * type, a party chat line. Anything else is unknown until the player confirms it, and a confirmation always wins.
 */
fun GoldorRunState.condition(condition: GuideCondition): ConditionValue {
    confirmations[condition]?.let { return ConditionValue(if (it) Tri.TRUE else Tri.FALSE, confirmed = true) }
    val value = when (condition) {
        GuideCondition.T3_TAKEN_NOT_MELODY -> state(ObjectiveId.terminal(GoldorSection.S2, 3)).let { t3 ->
            when {
                t3.status == ObjectiveStatus.COMPLETE && !t3.ambiguous -> Tri.TRUE
                t3.status == ObjectiveStatus.ACTIVE && t3.puzzle != null -> if (t3.puzzle == PuzzleType.MELODY) Tri.FALSE else Tri.TRUE
                else -> Tri.UNKNOWN
            }
        }
        GuideCondition.ARCHER_MELODY -> {
            val archer = playerWith(GoldorRole.ARCHER)?.name
            val terminal = objectives.entries.firstOrNull { (id, s) -> id.section == GoldorSection.S2 && id.kind == ObjectiveKind.TERMINAL &&
                s.puzzle != null && s.status != ObjectiveStatus.OPEN && archer != null && s.by.equals(archer, true) }?.value
            when { terminal == null -> Tri.UNKNOWN; terminal.puzzle == PuzzleType.MELODY -> Tri.TRUE; else -> Tri.FALSE }
        }
        // Done means not failed; not seen done is not proof of a failure.
        GuideCondition.I4_FAILED -> state(ObjectiveId.device(GoldorSection.S4)).let {
            if (it.status == ObjectiveStatus.COMPLETE && it.source != StateSource.INFERRED) Tri.FALSE else Tri.UNKNOWN
        }
        GuideCondition.SS_TROUBLE -> if (ssResetAt != null) Tri.TRUE else Tri.UNKNOWN
        GuideCondition.TANK_ARCHER_SLOW -> if (listOf(LeverSide.LEFT, LeverSide.RIGHT).all { state(ObjectiveId.lever(GoldorSection.S3, it)).status == ObjectiveStatus.COMPLETE }) Tri.FALSE else Tri.UNKNOWN
        else -> Tri.UNKNOWN
    }
    return ConditionValue(value)
}

/** What the guide adds to the recommendation: how to do the current step, the next decision, and any recovery. */
data class GuideView(
    val step: GuideStep?,
    /** The next conditional step still in play, with its condition's value. */
    val decision: Pair<GuideStep, ConditionValue>?,
    val recovery: GuideStep?,
    val reminder: GuideStep?,
)

/**
 * The guide lines for [role] now. The current step follows the engine's recommendation (its objective or leap), else
 * the first required step whose objective is still open. Conditional steps whose condition is known false, or whose
 * objective is already done, drop out; recovery shows only when its condition is known true.
 */
fun guideView(guide: StrategyGuide, state: GoldorRunState, role: GoldorRole, recommendation: Recommendation?): GuideView? {
    val section = guide.section(role, state.section) ?: return null
    fun open(step: GuideStep) = step.objectives.isEmpty() || step.objectives.any { state.state(it).status != ObjectiveStatus.COMPLETE }
    val steps = section.steps
    val required = steps.filter { it.kind == StepKind.REQUIRED }
    // With no objective or leap to follow, the step after the last finished objective step (e.g. "wait in core").
    val lastDone = steps.indexOfLast { it.kind == StepKind.REQUIRED && it.objectives.isNotEmpty() && !open(it) }
    val current = recommendation?.objective?.let { o -> steps.firstOrNull { o in it.objectives && it.kind != StepKind.RECOVERY } }
        ?: recommendation?.leap?.targetRole?.let { target -> required.firstOrNull { it.leapTo == target } }
        ?: required.firstOrNull { it.objectives.isNotEmpty() && open(it) }
        ?: steps.withIndex().firstOrNull { (i, s) -> i > lastDone && s.kind == StepKind.REQUIRED && s.objectives.isEmpty() && s.leapTo == null }?.value
        ?: required.lastOrNull()
    val decision = section.steps.asSequence().filter { it.kind == StepKind.CONDITIONAL && it.condition != null && open(it) }
        .map { it to state.condition(it.condition!!) }.firstOrNull { it.second.value != Tri.FALSE }
    val recovery = section.steps.firstOrNull { it.kind == StepKind.RECOVERY && it.condition != null && state.condition(it.condition).value == Tri.TRUE }
    val reminder = section.steps.firstOrNull { it.kind == StepKind.REMINDER }
    return GuideView(current, decision, recovery, reminder)
}

/** The decision line: "If X: do Y" while unknown, "X: do Y" once seen or confirmed. */
fun decisionText(step: GuideStep, value: ConditionValue): String {
    val condition = step.condition ?: return step.text
    return if (value.value == Tri.TRUE) "${condition.seen}${if (value.confirmed) " (you)" else ""}: ${step.text}" else "If ${condition.question}: ${step.text}"
}
