package me.mycellium.skymyce.features.instances.dungeons.goldor

/** What one player is expected to do now, and how far along they are. */
data class PlayerPlan(
    val name: String,
    val role: GoldorRole,
    /** The next open objective in their plan, or null when they have none left in this section. */
    val objective: ObjectiveId?,
    val stackWith: String?,
    val eta: Estimate?,
    val positionKnown: Boolean,
    val note: String?,
)

/** The leap the local player should make, with the reason and (for timed leaps) the plan. */
data class LeapAdvice(val targetName: String?, val targetRole: GoldorRole?, val reason: String, val plan: LeapPlan?, val mandatory: Boolean)

/**
 * The local player's recommendation: a headline action, its reason, an optional leap and confidence. [urgent] is set
 * only while a leap window is open.
 */
data class Recommendation(
    val headline: String,
    val reason: String,
    val confidence: Confidence,
    val objective: ObjectiveId? = null,
    val leap: LeapAdvice? = null,
    val urgent: Boolean = false,
)

/** S3 device arrival: who should get there first and by how much. */
data class DeviceArrival(val first: String, val firstEta: Estimate, val second: String?, val secondEta: Estimate?)

data class GoldorAdvice(
    val section: GoldorSection,
    val players: List<PlayerPlan>,
    val self: Recommendation?,
    val deviceArrival: DeviceArrival?,
    /** Route state per early-enter the profile expects; position-unknown routes keep their assignment. */
    val routes: Map<EarlyEnter, String>,
    /** The local player's guide lines, when the profile has a guide. */
    val guide: GuideView? = null,
)

/** Local-player inputs the engine cannot read from the shared state. */
data class SelfContext(val name: String, val leaptAt: Long? = null, val position: BossPoint? = null)

/**
 * Turns the run state and a profile into plans and a recommendation. Deterministic: the same state, profile, self
 * context and time give the same advice. It never acts; the player decides.
 */
object GoldorEngine {
    fun advise(state: GoldorRunState, self: SelfContext, now: Long): GoldorAdvice {
        val profile = state.profile
        val section = state.section
        val plans = state.players.values.mapNotNull { player -> player.role?.let { plan(state, player, it, now) } }
        val selfRole = state.player(self.name)?.role
        val recommendation = selfRole?.let { recommend(state, self, it, now) }
        val routes = profile.routes.mapValues { (route, role) -> routeText(state, route, role, now) }
        val guide = selfRole?.let { role -> profile.guide?.let { guideView(it, state, role, recommendation) } }
        return GoldorAdvice(section, plans, recommendation, if (section == GoldorSection.S3) deviceArrival(state, now) else null, routes, guide)
    }

    /** [role]'s plan with its conditional branches applied. Only conditions known to hold change it. */
    fun effectivePlan(state: GoldorRunState, section: GoldorSection, role: GoldorRole): RolePlan {
        val base = state.profile.plan(section, role)
        if (base.branches.isEmpty()) return base
        var objectives = base.objectives
        for (branch in activeBranches(state, base)) {
            objectives = if (branch.replace != null && branch.replace in objectives)
                objectives.flatMap { if (it == branch.replace) branch.add.take(1) else listOf(it) } + branch.add.drop(1).filter { it !in objectives }
            else objectives + branch.add.filter { it !in objectives }
        }
        return base.copy(objectives = objectives)
    }

    private fun activeBranches(state: GoldorRunState, plan: RolePlan) = plan.branches.filter { state.condition(it.condition).value == Tri.TRUE }

    /** The next open objective; a backup objective someone else is already on is left to them. */
    fun nextObjective(state: GoldorRunState, plan: RolePlan, name: String?): ObjectiveId? = plan.objectives.firstOrNull { objective ->
        val info = state.state(objective)
        info.status != ObjectiveStatus.COMPLETE && !(objective in plan.backup && info.status == ObjectiveStatus.ACTIVE && !info.by.equals(name, true))
    }

    private fun plan(state: GoldorRunState, player: GoldorPlayer, role: GoldorRole, now: Long): PlayerPlan {
        val rolePlan = effectivePlan(state, state.section, role)
        val next = nextObjective(state, rolePlan, player.name)
        val partner = rolePlan.stackWith?.let { state.playerWith(it)?.name }
        val eta = next?.let { objectiveEta(state, it, player.name, now) }
        return PlayerPlan(player.name, role, next, partner, eta, state.position(player.name, now) != null, rolePlan.note)
    }

    /** Time until [objective] is done by [by]: remaining work plus walking there if they are not on it yet. */
    fun objectiveEta(state: GoldorRunState, objective: ObjectiveId, by: String?, now: Long): Estimate {
        val current = state.state(objective)
        if (current.status == ObjectiveStatus.COMPLETE) return Estimate.ZERO
        val work = when (objective.kind) {
            ObjectiveKind.TERMINAL -> remainingWork(current.work, state.profile.puzzles, now)
            ObjectiveKind.DEVICE -> Estimate(state.profile.objectives.deviceMillis[objective.section] ?: 5_000, Confidence.LOW)
            ObjectiveKind.LEVER -> Estimate(state.profile.objectives.leverMillis, Confidence.MEDIUM)
        }
        if (current.status == ObjectiveStatus.ACTIVE) return work
        val station = GoldorLayout.station(objective)?.points?.firstOrNull() ?: return work.copy(confidence = Confidence.LOW)
        val position = by?.let { state.position(it, now) } ?: return Estimate(work.millis, weaker(work.confidence, Confidence.LOW))
        return work + Estimate(travelMillis(position.distance(station), state.profile.leap), Confidence.MEDIUM)
    }

    /**
     * Remaining work before the current section is complete: the slowest open objective, since they run in parallel.
     * Objectives nobody is on are estimated at low confidence.
     */
    fun sectionRemaining(state: GoldorRunState, now: Long): Estimate {
        val open = GoldorLayout.stations(state.section).map { it.objective }.filter { state.state(it).status != ObjectiveStatus.COMPLETE }
        if (open.isEmpty()) return Estimate.ZERO
        return open.map { objectiveEta(state, it, state.state(it).by ?: state.profile.assignee(it)?.let { role -> state.playerWith(role)?.name }, now) }
            .reduce { a, b -> Estimate(maxOf(a.millis, b.millis), weaker(a.confidence, b.confidence)) }
    }

    /**
     * Berserker's S2 chain: the first terminal (T4 in the Sky-Myce profile), the walk to the second, then the second
     * (T3). Done terminals contribute nothing.
     */
    fun berserkerChain(state: GoldorRunState, now: Long): Estimate {
        val (first, second) = state.profile.berserkerS2Order
        val bers = state.playerWith(GoldorRole.BERSERKER)?.name
        val firstLeft = objectiveEta(state, first, bers, now)
        val walk = if (state.state(first).status == ObjectiveStatus.COMPLETE) Estimate.ZERO else {
            val a = GoldorLayout.station(first)?.points?.first(); val b = GoldorLayout.station(second)?.points?.first()
            if (a != null && b != null) Estimate(travelMillis(a.distance(b), state.profile.leap), Confidence.MEDIUM) else Estimate.ZERO
        }
        val secondWork = state.state(second).let { if (it.status == ObjectiveStatus.COMPLETE) Estimate.ZERO else remainingWork(it.work, state.profile.puzzles, now) }
        return firstLeft + walk + secondWork
    }

    private fun recommend(state: GoldorRunState, self: SelfContext, role: GoldorRole, now: Long): Recommendation {
        val profile = state.profile
        val section = state.section
        val rolePlan = effectivePlan(state, section, role)
        val next = nextObjective(state, rolePlan, self.name)

        // A leap tied to finishing one objective (Berserker's i4 → Tank, Tank's S2 T1 → Berserker) comes first, unless
        // its skip condition holds.
        val rule = profile.leapRule(section, role)?.takeIf { it.afterObjective != null && it.unless?.let { c -> state.condition(c).value == Tri.TRUE } != true }
        if (rule != null && state.state(rule.afterObjective!!).status == ObjectiveStatus.COMPLETE) {
            val doneAt = state.state(rule.afterObjective).completedAt
            val leapt = self.leaptAt != null && doneAt != null && self.leaptAt >= doneAt
            if (!leapt) {
                val target = rule.target?.let { state.playerWith(it) }
                val reason = listOfNotNull(rule.reason, i4Note(state, rule.afterObjective), rule.alternative).joinToString(" · ")
                return Recommendation("Leap to ${target?.name ?: rule.target?.title}", reason, if (target == null) Confidence.MEDIUM else Confidence.HIGH,
                    leap = LeapAdvice(target?.name, rule.target, rule.reason, null, rule.mandatory))
            }
            // Leapt to a stack partner with nothing of our own left: stack while they still have work.
            val partnerRole = rolePlan.stackWith
            if (next == null && partnerRole != null && partnerRole == rule.target) {
                val partner = state.playerWith(partnerRole)
                val partnerNext = partner?.let { nextObjective(state, effectivePlan(state, section, partnerRole), it.name) }
                if (partner != null && partnerNext != null)
                    return Recommendation("Stack with ${partner.name}", "${role.title} stacks ${partnerRole.title} regardless of terminal type", Confidence.HIGH, partnerNext)
            }
        }
        if (section == GoldorSection.S2 && role == GoldorRole.TANK && next == null && profile.tankS2Table.isNotEmpty()) return tankS2(state, self, now)
        val priority = profile.s4LeverPriority[role]
        if (section == GoldorSection.S4 && priority != null && next?.kind == ObjectiveKind.LEVER && state.state(priority).status != ObjectiveStatus.COMPLETE) {
            return Recommendation("Take the ${priority.side!!.title(section)} lever", "${role.title} has ${priority.side.title(section)} lever priority", Confidence.HIGH, priority)
        }
        val deviceStack = profile.s3DeviceStack
        if (section == GoldorSection.S3 && deviceStack != null && next?.kind == ObjectiveKind.DEVICE && (role == deviceStack.first || role == deviceStack.second)) {
            val partnerRole = if (role == deviceStack.first) deviceStack.second else deviceStack.first
            val arrival = deviceArrival(state, now)
            val order = arrival?.let { if (it.first.equals(self.name, true)) "you first" else "${it.first} first" }
            return Recommendation("Stack device with ${state.playerWith(partnerRole)?.name ?: partnerRole.title}",
                listOfNotNull("S3 device stack", order).joinToString(" · "), arrival?.firstEta?.confidence ?: Confidence.LOW, next)
        }
        if (next != null) {
            // A guided profile names the stack only on the objective both share; older profiles name it throughout.
            val stack = rolePlan.stackWith?.takeIf { profile.guide == null || next in effectivePlan(state, section, it).objectives }
                ?.let { state.playerWith(it)?.name ?: it.title }
            val branch = activeBranches(state, profile.plan(section, role)).firstOrNull { next in it.add }
            val owner = profile.assignee(next)?.takeIf { next in rolePlan.backup && it != role }
            val reason = branch?.reason ?: owner?.let { "${next.label} unless ${state.playerWith(it)?.name ?: it.title} has it or it is done" }
                ?: rolePlan.note ?: "${role.title} ${section.title} assignment"
            return Recommendation("${next.label}${stack?.let { " · stack $it" } ?: ""}", reason, Confidence.HIGH, next)
        }
        // Own work done. The early-enter runner is the leap target: they go to the spot instead of leaping to themselves.
        val leap = profile.sectionLeapRule(section, role)
        if (leap?.route != null && leap.target == role) return routeRunner(state, leap.route)
        // An objective nobody in this party is assigned comes before leaping, so a short party still finishes.
        val help = help(state, self, now)
        if (help?.second == true) return help.first
        if (leap == null) return help?.first ?: Recommendation(if (section == GoldorSection.CORE) "Core" else "Wait for ${section.title} gate",
            "Everything in ${section.title} is taken", Confidence.MEDIUM)
        return sectionLeap(state, self, leap, now, extraDelay = 0)
    }

    /** The runner of an early-enter: go to the spot, then hold it while the party leaps in. */
    private fun routeRunner(state: GoldorRunState, route: EarlyEnter): Recommendation {
        val title = state.profile.routeTitle(route)
        return if (state.route(route).state == RouteState.IN) Recommendation("Hold $title", "The party leaps to you; stay on the spot", Confidence.HIGH)
        else Recommendation("Go $title", "You run $title; the party leaps to you", Confidence.HIGH)
    }

    /** How long i4 took from Goldor's line, and whether S1 was still open, for Berserker's post-i4 leap. */
    private fun i4Note(state: GoldorRunState, objective: ObjectiveId): String? {
        if (objective != ObjectiveId.device(GoldorSection.S4)) return null
        val took = state.state(objective).completedAt?.let { done -> state.startedAt?.let { done - it } } ?: return null
        val s1Open = state.section == GoldorSection.S1 && state.sectionDoneAt == null
        return "i4 ${String.format(java.util.Locale.ROOT, "%.1f", took / 1000.0)} s · S1 ${if (s1Open) "open" else "done"}"
    }

    /**
     * Something useful for a player whose own objectives are done: an open objective nobody alive in the party is
     * assigned (nearest first, flagged true), else the open objective that will take its owner longest. Null when
     * every objective is done or being worked on.
     */
    private fun help(state: GoldorRunState, self: SelfContext, now: Long): Pair<Recommendation, Boolean>? {
        val open = GoldorLayout.stations(state.section).map { it.objective }.filter { state.state(it).status == ObjectiveStatus.OPEN }
        if (open.isEmpty()) return null
        fun owner(objective: ObjectiveId) = state.profile.assignee(objective)?.let { state.playerWith(it) }?.takeIf { it.alive }
        val unclaimed = open.filter { owner(it) == null }
        if (unclaimed.isNotEmpty()) {
            val pick = unclaimed.minBy { o -> self.position?.let { here -> GoldorLayout.station(o)?.points?.firstOrNull()?.distance(here) } ?: 0.0 }
            val assignee = state.profile.assignee(pick)
            return Recommendation("Help: ${pick.label}", if (assignee == null) "${pick.label} has no assigned class" else "No ${assignee.title} to take ${pick.label}",
                Confidence.MEDIUM, pick) to true
        }
        val pick = open.maxBy { objectiveEta(state, it, owner(it)?.name, now).millis }
        return Recommendation("Help: ${pick.label}", "${owner(pick)?.name ?: "Its owner"} still has it queued", Confidence.LOW, pick) to false
    }

    /** The S2 decision table for Tank once its own terminal is done. */
    private fun tankS2(state: GoldorRunState, self: SelfContext, now: Long): Recommendation {
        val profile = state.profile
        val (first, second) = profile.berserkerS2Order
        val firstSpeed = profile.puzzles.speed(state.state(first).puzzle)
        val secondSpeed = profile.puzzles.speed(state.state(second).puzzle)
        // A finished terminal no longer slows anything: treat it as fast.
        val row = profile.tankS2(if (state.state(first).status == ObjectiveStatus.COMPLETE) PuzzleSpeed.FAST else firstSpeed,
            if (state.state(second).status == ObjectiveStatus.COMPLETE) PuzzleSpeed.FAST else secondSpeed)
        val reason = profile.tankS2Reason(row)
        val bers = state.playerWith(GoldorRole.BERSERKER)?.name ?: "Berserker"
        return when (row.action) {
            TankS2Action.STACK_T3 -> Recommendation("Stack ${second.label} with $bers", reason, row.confidence, second)
            TankS2Action.HOLD -> Recommendation("Hold — help ${first.label}", reason, row.confidence, first)
            TankS2Action.PRE_LEAP -> {
                val rule = profile.sectionLeapRule(GoldorSection.S2, GoldorRole.TANK) ?: return Recommendation("Pre-leap", reason, Confidence.LOW)
                val advice = sectionLeap(state, self, rule, now, extraDelay = row.extraDelayMillis, remainingOverride = berserkerChain(state, now))
                advice.copy(reason = "$reason · ${advice.reason}", confidence = weaker(advice.confidence, row.confidence))
            }
        }
    }

    /**
     * A section-end leap. The target is the rule's role; an early-enter target is mandatory and stays the target even
     * when its position is unknown. Only when that player is missing or dead do the fallback roles compete, by
     * distance from their observed position to the fallback goal.
     */
    private fun sectionLeap(state: GoldorRunState, self: SelfContext, rule: LeapRule, now: Long, extraDelay: Long,
                            remainingOverride: Estimate? = null): Recommendation {
        val profile = state.profile
        rule.preLeapIf?.let { condition -> conditionalLeap(state, rule, condition)?.let { return it } }
        val routeState = rule.route?.let { state.route(it) }
        val primary = rule.target?.let { state.playerWith(it) }?.takeIf { it.alive && routeState?.state != RouteState.ABANDONED }
        val target = primary ?: rule.fallback.mapNotNull { state.playerWith(it)?.takeIf { p -> p.alive } }
            .mapNotNull { p -> state.position(p.name, now)?.let { pos -> p to (rule.fallbackGoal?.let { g -> GoldorLayout.station(g)?.points?.first()?.distance(pos) } ?: 0.0) } }
            .minByOrNull { it.second }?.first
        val why = when {
            primary != null -> rule.reason
            target != null -> "${rule.target?.title ?: "Target"} unavailable — closest to ${rule.fallbackGoal?.label ?: "next"}: ${target.role?.title}"
            else -> rule.reason
        }
        val targetPosition = target?.let { state.position(it.name, now) }
        val goal = targetGoal(state, state.player(self.name)?.role, target)
        val travel = if (targetPosition != null && goal != null) travelMillis(targetPosition.distance(goal), profile.leap) else null
        val remaining = (remainingOverride ?: sectionRemaining(state, now)).let { base ->
            // After the work, the gate takes its delay to open unless it has already been seen open.
            Estimate(base.millis + profile.objectives.gateDelayMillis, base.confidence)
        }
        val timing = profile.leap.copy(safetyMarginMillis = profile.leap.safetyMarginMillis + extraDelay)
        val arrived = self.position != null && goal != null && self.position.distance(goal) <= 3.0
        val leapt = self.leaptAt != null && state.sectionDoneAt == null && self.leaptAt >= (state.gateOpenedAt ?: state.startedAt ?: 0L)
        val plan = planPreLeap(now, remaining, state.tick(), timing, travel, leapt = leapt, arrived = arrived)
        val name = target?.name ?: rule.target?.title ?: "?"
        val routeNote = routeState?.let { if (it.state == RouteState.IN) "${profile.routeTitle(rule.route!!)} in" else if (target != null && targetPosition == null) "position unknown" else null }
        val headline = when (plan.phase) {
            LeapPhase.LEAP, LeapPhase.MISSED -> "LEAP to $name"
            LeapPhase.ARRIVED -> "Ready at ${goal?.let { "next terminal" } ?: "target"}"
            LeapPhase.TRAVELLING -> "Go to next terminal"
            else -> "Leap to $name"
        }
        return Recommendation(headline, listOfNotNull(why, routeNote, plan.reason).joinToString(" · "), weaker(plan.confidence, if (target == null) Confidence.LOW else Confidence.HIGH),
            leap = LeapAdvice(target?.name, target?.role ?: rule.target, why, plan, rule.mandatory), urgent = plan.phase == LeapPhase.LEAP)
    }

    /**
     * A leap whose pre-leap depends on [condition] (Archer's Mask). Known true: the normal timed pre-leap (null here).
     * Known false: wait for the section, then leap. Unknown: both branches, no countdown. Once the section is done the
     * leap is simply now.
     */
    private fun conditionalLeap(state: GoldorRunState, rule: LeapRule, condition: GuideCondition): Recommendation? {
        val value = state.condition(condition).value
        if (value == Tri.TRUE) return null
        val target = rule.target?.let { state.playerWith(it) }?.takeIf { it.alive }
        val name = target?.name ?: rule.target?.title ?: "?"
        val advice = LeapAdvice(target?.name, rule.target, rule.reason, null, rule.mandatory)
        if (state.sectionDoneAt != null) return Recommendation("Leap to $name", "${state.section.title} done · ${rule.reason}", Confidence.HIGH, leap = advice)
        val otherwise = rule.otherwise ?: "otherwise wait for the gate"
        return if (value == Tri.FALSE) Recommendation("Wait for ${state.section.title}, then leap $name", otherwise, Confidence.MEDIUM, leap = advice)
        else Recommendation("Leap to $name", "${condition.question.replaceFirstChar { it.uppercase() }}? Pre-leap now · $otherwise", Confidence.LOW, leap = advice)
    }

    /** Where a leaping player heads next: their first objective in the next section, else the target's position. */
    private fun targetGoal(state: GoldorRunState, role: GoldorRole?, target: GoldorPlayer?): BossPoint? {
        val next = state.section.next ?: return null
        val objective = role?.let { state.profile.plan(next, it).objectives.firstOrNull() }
        return objective?.let { GoldorLayout.station(it)?.points?.first() } ?: target?.position
    }

    /** S3: who of the device pair reaches the device first, from their remaining terminal and walking distance. */
    fun deviceArrival(state: GoldorRunState, now: Long): DeviceArrival? {
        val (a, b) = state.profile.s3DeviceStack ?: return null
        val device = ObjectiveId.device(GoldorSection.S3)
        val station = GoldorLayout.station(device)?.points?.firstOrNull() ?: return null
        fun eta(role: GoldorRole): Pair<String, Estimate>? {
            val player = state.playerWith(role) ?: return null
            val terminal = state.profile.plan(GoldorSection.S3, role).objectives.firstOrNull { it.kind == ObjectiveKind.TERMINAL }
            val work = terminal?.let { objectiveEta(state, it, player.name, now) } ?: Estimate.ZERO
            val from = terminal?.let { GoldorLayout.station(it)?.points?.first() } ?: state.position(player.name, now) ?: return player.name to Estimate(work.millis, Confidence.LOW)
            return player.name to (work + Estimate(travelMillis(from.distance(station), state.profile.leap), Confidence.MEDIUM))
        }
        val etas = listOfNotNull(eta(a), eta(b)).sortedBy { it.second.millis }
        val first = etas.firstOrNull() ?: return null
        return DeviceArrival(first.first, first.second, etas.getOrNull(1)?.first, etas.getOrNull(1)?.second)
    }

    /**
     * A route's status line. The route exists because the profile says so; position only says how far along it is.
     */
    private fun routeText(state: GoldorRunState, route: EarlyEnter, role: GoldorRole, now: Long): String {
        val runner = state.playerWith(role)
        val observed = state.route(route)
        val position = runner?.let { state.position(it.name, now) }
        return when {
            observed.state == RouteState.IN -> "in"
            observed.state == RouteState.ABANDONED -> "abandoned"
            runner == null -> "no ${role.title}"
            !runner.alive -> "${role.title} dead"
            position == null -> "position unknown"
            observed.state == RouteState.GOING -> "going"
            else -> "${GoldorLayout.routePoints[route]?.distance(position)?.toInt() ?: "?"} blocks away"
        }
    }
}
