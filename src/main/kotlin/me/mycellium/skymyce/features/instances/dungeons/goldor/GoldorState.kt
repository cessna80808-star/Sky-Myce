package me.mycellium.skymyce.features.instances.dungeons.goldor

enum class ObjectiveStatus { OPEN, ACTIVE, COMPLETE }

/**
 * What is known about one objective. [ambiguous] is set when a completion could not be pinned to this exact objective
 * (no position, no shared id) and it was chosen by assignment order; a later exact report moves it.
 */
data class ObjectiveState(
    val status: ObjectiveStatus = ObjectiveStatus.OPEN,
    val by: String? = null,
    val puzzle: PuzzleType? = null,
    val quarters: Int = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val source: StateSource = StateSource.ASSIGNED,
    val ambiguous: Boolean = false,
) {
    val work get() = TerminalWork(puzzle, quarters, startedAt, status == ObjectiveStatus.COMPLETE)
}

/** A party member as this client sees them. Positions older than [GoldorRunState.POSITION_TTL] are stale. */
data class GoldorPlayer(
    val name: String,
    val role: GoldorRole?,
    val sm: Boolean = false,
    val position: BossPoint? = null,
    val seenAt: Long? = null,
    val alive: Boolean = true,
)

/** An early-enter route's progress: the state, where it came from, and when it last changed. */
data class RouteObservation(val state: RouteState = RouteState.PENDING, val source: StateSource = StateSource.ASSIGNED, val at: Long? = null)

/**
 * The live phase-3 state for one run. Every input is a method taking the current time, so the same sequence of inputs
 * always produces the same state. It knows the profile only to resolve completions by assignment when nothing more
 * precise is available.
 */
class GoldorRunState(var profile: StrategyProfile) {
    companion object {
        const val POSITION_TTL = 3_000L
        /** How long a gate line seen before n/n still counts for that section. */
        const val GATE_EARLY_MILLIS = 3_000L
    }

    var active = false
        private set
    var section = GoldorSection.S1
        private set
    var startedAt: Long? = null
        private set
    /** When the current section's counter completed; null while work remains. */
    var sectionDoneAt: Long? = null
        private set
    /** When the current section's gate was seen opening (or the next section's first completion arrived). */
    var gateOpenedAt: Long? = null
        private set
    val objectives = HashMap<ObjectiveId, ObjectiveState>()
    val players = LinkedHashMap<String, GoldorPlayer>()
    val routes = HashMap<EarlyEnter, RouteObservation>()
    /** The latest `(n/N)` per section. */
    val counters = HashMap<GoldorSection, Int>()
    var tickOrigin: Long? = null
        private set
    var revision = 0L
        private set
    /** Conditions the player confirmed (`/sm goldor if`), for this run only. */
    val confirmations = HashMap<GuideCondition, Boolean>()
    /** When a party member last called "SS reset" / "SSRS" in party chat this run. */
    var ssResetAt: Long? = null
        private set

    private fun changed() { revision++ }

    /** The player says [condition] holds (true), does not (false), or clears it (null). */
    fun confirm(condition: GuideCondition, value: Boolean?) {
        if (value == null) confirmations.remove(condition) else confirmations[condition] = value
        changed()
    }

    /** A party member called an SS reset; only counts while the phase is on. */
    fun ssReset(now: Long) { if (active && ssResetAt == null) { ssResetAt = now; changed() } }

    fun reset() {
        active = false; section = GoldorSection.S1; startedAt = null; sectionDoneAt = null; gateOpenedAt = null
        objectives.clear(); routes.clear(); counters.clear(); tickOrigin = null
        confirmations.clear(); ssResetAt = null; pendingGateAt = null
        players.replaceAll { _, p -> p.copy(sm = false) }
        changed()
    }

    /** Goldor's opening line: phase 3 starts, S1 is open and the first tick follows at the profile's delay. */
    fun start(now: Long) {
        if (active) return
        active = true; section = GoldorSection.S1; startedAt = now; gateOpenedAt = now
        tickOrigin = now + profile.tick.firstAfterStartMillis
        changed()
    }

    /** The tick is only ever predicted from Goldor's opening line, never observed. */
    fun tick() = GoldorTick(tickOrigin, profile.tick.periodMillis, false)

    fun state(objective: ObjectiveId) = objectives[objective] ?: ObjectiveState()

    fun player(name: String) = players.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun playerWith(role: GoldorRole) = players.values.firstOrNull { it.role == role }

    fun updatePlayer(name: String, change: (GoldorPlayer) -> GoldorPlayer) {
        val key = players.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        val before = players[key] ?: GoldorPlayer(name, null)
        val after = change(before)
        if (after != before) { players[key] = after; if (after.role != before.role || after.sm != before.sm || after.alive != before.alive) changed() }
    }

    /** Drops everyone not in [names] (this run's party), so players from an earlier run or who left stop getting plans. */
    fun retainPlayers(names: Collection<String>) {
        if (players.keys.removeIf { key -> names.none { it.equals(key, ignoreCase = true) } }) changed()
    }

    /** Fresh position for [name], or null if unseen or older than [POSITION_TTL]. */
    fun position(name: String, now: Long): BossPoint? = player(name)?.takeIf { (it.seenAt ?: return@takeIf false) >= now - POSITION_TTL }?.position

    fun route(route: EarlyEnter) = routes[route] ?: RouteObservation()

    fun setRoute(route: EarlyEnter, state: RouteState, source: StateSource, now: Long) {
        val before = route(route)
        // Abandoned is final for the run; in only goes back on an explicit abandon.
        if (before.state == RouteState.ABANDONED || before.state == state) return
        if (before.state == RouteState.IN && state == RouteState.GOING) return
        routes[route] = RouteObservation(state, source, now); changed()
    }

    fun markActive(objective: ObjectiveId, by: String, puzzle: PuzzleType?, quarters: Int, source: StateSource, now: Long) {
        val before = state(objective)
        if (before.status == ObjectiveStatus.COMPLETE) return
        val after = before.copy(status = ObjectiveStatus.ACTIVE, by = by, puzzle = puzzle ?: before.puzzle,
            quarters = maxOf(before.quarters, quarters.coerceIn(0, 3)), startedAt = before.startedAt ?: now, source = source)
        if (after != before) { objectives[objective] = after; changed() }
    }

    fun markComplete(objective: ObjectiveId, by: String?, source: StateSource, now: Long, ambiguous: Boolean = false) {
        val before = state(objective)
        if (before.status == ObjectiveStatus.COMPLETE && !before.ambiguous) return
        if (before.status == ObjectiveStatus.COMPLETE && ambiguous) return
        objectives[objective] = before.copy(status = ObjectiveStatus.COMPLETE, by = by ?: before.by, completedAt = before.completedAt ?: now,
            source = source, ambiguous = ambiguous)
        changed()
    }

    /**
     * An exact completion from a player's SM client. If an assignment-order guess already marked a different objective
     * for the same player, that guess is reopened so the count stays right.
     */
    fun exactComplete(objective: ObjectiveId, by: String, now: Long) {
        val guessed = objectives.entries.firstOrNull { (id, state) ->
            id != objective && id.section == objective.section && id.kind == objective.kind && state.ambiguous && state.by.equals(by, ignoreCase = true)
        }
        if (guessed != null && state(objective).status != ObjectiveStatus.COMPLETE) objectives[guessed.key] = ObjectiveState()
        markComplete(objective, by, StateSource.SHARED, now)
    }

    /**
     * Hypixel's `<player> activated a terminal! (n/N)` (or device, lever). Chooses the objective: the one the sender's
     * SM client already named, then a station within [GoldorLayout.MATCH_RADIUS] of their position, then the next open
     * objective of that kind assigned to their role, then the first open one of that kind (ambiguous).
     * Returns the objective chosen, or null when the line does not fit the current section.
     */
    fun chatCompletion(playerName: String, kind: ObjectiveKind, done: Int, total: Int, now: Long): ObjectiveId? {
        if (!active) return null
        val section = sectionForCounter(total) ?: return null
        val role = player(playerName)?.role
        counters[section] = maxOf(counters[section] ?: 0, done)
        val chosen = when (kind) {
            ObjectiveKind.DEVICE -> deviceFor(role, section)
            else -> {
                // Their SM client said which one it was working on.
                val active = objectives.entries.firstOrNull { (id, s) -> id.section == section && id.kind == kind && s.status == ObjectiveStatus.ACTIVE && s.by.equals(playerName, true) }?.key
                active ?: position(playerName, now)?.let { GoldorLayout.nearest(section, kind, it.x, it.y, it.z, completed(section))?.objective }
            }
        }
        val exact = chosen != null
        val pick = chosen ?: role?.let { profile.plan(section, it).objectives.firstOrNull { id -> id.kind == kind && state(id).status != ObjectiveStatus.COMPLETE } }
            ?: GoldorLayout.stations(section).map { it.objective }.firstOrNull { it.kind == kind && state(it).status != ObjectiveStatus.COMPLETE }
        if (pick != null) markComplete(pick, playerName, StateSource.CHAT, now, ambiguous = !exact && kind != ObjectiveKind.DEVICE)
        if (done >= total && section == this.section) completeSection(now)
        return pick
    }

    /**
     * The device a completion belongs to. Pre-dev (Berserker's i4 during S1) and an early-enter device (MEE2's S2
     * device done from the EE2 spot during S1) count for their own sections; otherwise it is the section's device.
     */
    private fun deviceFor(role: GoldorRole?, section: GoldorSection): ObjectiveId {
        val own = role?.let { profile.plan(section, it).objectives.firstOrNull { id -> id.kind == ObjectiveKind.DEVICE && id.section != section } }
        if (own != null && state(own).status != ObjectiveStatus.COMPLETE) return own
        val next = section.next
        val early = profile.routes.entries.firstOrNull { it.value == role && it.key.section == next }?.let { ObjectiveId.device(next!!) }
        val current = ObjectiveId.device(section)
        if (early != null && profile.assignee(early) == role && state(current).status == ObjectiveStatus.COMPLETE) return early
        return current
    }

    private fun completed(section: GoldorSection) = objectives.filter { it.key.section == section && it.value.status == ObjectiveStatus.COMPLETE && !it.value.ambiguous }.keys

    /** Which section a `(n/N)` line belongs to: S2 has 8 objectives, the others 7, so 7 means the current or next one. */
    private fun sectionForCounter(total: Int): GoldorSection? {
        val candidates = GoldorLayout.sectionsWithTotal(total)
        // Once a section is full, a new counter belongs to the next one: its gate opened without us seeing it.
        val next = section.next
        if (sectionDoneAt != null && next != null && next in candidates) { advance(next); return next }
        return section.takeIf { it in candidates && sectionDoneAt == null }
    }

    private fun completeSection(now: Long) {
        if (sectionDoneAt != null) return
        sectionDoneAt = now
        GoldorLayout.stations(section).forEach { if (state(it.objective).status != ObjectiveStatus.COMPLETE) markComplete(it.objective, null, StateSource.INFERRED, now, ambiguous = true) }
        changed()
        // The gate line can land just before the last completion line; it still opens this gate.
        val early = pendingGateAt?.let { now - it <= GATE_EARLY_MILLIS } == true
        pendingGateAt = null
        if (early) gateOpened(now)
    }

    /** A gate line seen while the section was still short of n/n. */
    private var pendingGateAt: Long? = null

    private fun advance(next: GoldorSection) { section = next; sectionDoneAt = null; gateOpenedAt = null; pendingGateAt = null; changed() }

    /**
     * The gate is blown (`The gate has been destroyed!` or Goldor's line for that gate): once the section is n/n the
     * next section begins at once. A gate line before n/n is held briefly for the last completion.
     */
    fun gateOpened(now: Long) {
        if (!active) return
        // S4 has no gate; the Core line ends it.
        if (section == GoldorSection.S4 || section == GoldorSection.CORE) return
        if (sectionDoneAt == null) { pendingGateAt = now; return }
        advance(section.next!!); gateOpenedAt = now
    }

    /**
     * A finished section whose gate line was never seen (hidden by another mod, or missed) still moves on once the gate
     * must have opened by itself: the profile's gate delay after n/n, plus a second of slack.
     */
    fun gateTimeout(now: Long) {
        val done = sectionDoneAt ?: return
        if (!active || section == GoldorSection.S4 || section == GoldorSection.CORE) return
        if (now - done >= profile.objectives.gateDelayMillis + 1_000) { advance(section.next!!); gateOpenedAt = now }
    }

    /** `The Core entrance is opening!`: S4 is done and the Core run begins. */
    fun coreOpened(now: Long) {
        if (!active) return
        if (section == GoldorSection.S4) completeSection(now)
        section = GoldorSection.CORE; sectionDoneAt = null; gateOpenedAt = now; changed()
    }

    /** Debug or recovery: jump to a section without touching objectives. */
    fun setSection(target: GoldorSection) { if (target != section) { section = target; sectionDoneAt = null; changed() } }
}
