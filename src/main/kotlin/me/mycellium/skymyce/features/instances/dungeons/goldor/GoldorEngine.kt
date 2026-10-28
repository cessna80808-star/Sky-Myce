package me.mycellium.skymyce.features.instances.dungeons.goldor

import com.google.gson.Gson
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.ARCHER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.BERSERKER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.HEALER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.MAGE
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.TANK
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S1
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S2
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S3
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S4
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.CONDITIONAL
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.OPTIONAL
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.PREP
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.RECOVERY
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.REMINDER
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.REQUIRED
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling

/** What one player is expected to do now. */
data class PlayerPlan(
    val name: String,
    /** The next open objective in their plan, or null when they have none left in this section. */
    val objective: ObjectiveId?,
)

/** The leap the local player should make, with the reason and (for timed leaps) the plan. */
data class LeapAdvice(val targetName: String?, val targetRole: GoldorRole?, val reason: String, val plan: LeapPlan?, val mandatory: Boolean)

/**
 * The local player's recommendation: a headline action, its reason, an optional leap and confidence. [urgent] is set
 * only while a leap is due now; [stackWith] names the player to stack [objective] with.
 */
data class Recommendation(
    val headline: String,
    val reason: String,
    val confidence: Confidence,
    val objective: ObjectiveId? = null,
    val leap: LeapAdvice? = null,
    val urgent: Boolean = false,
    val stackWith: String? = null,
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
data class SelfContext(val name: String, val leaptAt: Long? = null, val position: BossPoint? = null, val reactionMillis: Long = 0)

/**
 * Turns the run state and a profile into plans and a recommendation. Deterministic: the same state, profile, self
 * context and time give the same advice. It never acts; the player decides.
 */
object GoldorEngine {
    fun advise(state: GoldorRunState, self: SelfContext, now: Long): GoldorAdvice {
        val profile = state.profile
        val section = state.section
        val plans = state.players.values.mapNotNull { player -> player.role?.let { plan(state, player, it) } }
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

    private fun plan(state: GoldorRunState, player: GoldorPlayer, role: GoldorRole) =
        PlayerPlan(player.name, nextObjective(state, effectivePlan(state, state.section, role), player.name))

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
                    leap = LeapAdvice(target?.name, rule.target, rule.reason, null, rule.mandatory), urgent = true)
            }
            // Leapt to a stack partner with nothing of our own left: stack while they still have work.
            val partnerRole = rolePlan.stackWith
            if (next == null && partnerRole != null && partnerRole == rule.target) {
                val partner = state.playerWith(partnerRole)
                val partnerNext = partner?.let { nextObjective(state, effectivePlan(state, section, partnerRole), it.name) }
                if (partner != null && partnerNext != null)
                    return Recommendation("Stack with ${partner.name}", "${role.title} stacks ${partnerRole.title} regardless of terminal type", Confidence.HIGH, partnerNext,
                        stackWith = partner.name)
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
            val partner = state.playerWith(partnerRole)?.name ?: partnerRole.title
            return Recommendation("Stack device with $partner",
                listOfNotNull("S3 device stack", order).joinToString(" · "), arrival?.firstEta?.confidence ?: Confidence.LOW, next, stackWith = partner)
        }
        if (next != null) {
            // A guided profile names the stack only on the objective both share; older profiles name it throughout.
            val stack = rolePlan.stackWith?.takeIf { profile.guide == null || next in effectivePlan(state, section, it).objectives }
                ?.let { state.playerWith(it)?.name ?: it.title }
            val branch = activeBranches(state, profile.plan(section, role)).firstOrNull { next in it.add }
            val owner = profile.assignee(next)?.takeIf { next in rolePlan.backup && it != role }
            val reason = branch?.reason ?: owner?.let { "${next.label} unless ${state.playerWith(it)?.name ?: it.title} has it or it is done" }
                ?: rolePlan.note ?: "${role.title} ${section.title} assignment"
            return Recommendation("${next.label}${stack?.let { " · stack $it" } ?: ""}", reason, Confidence.HIGH, next, stackWith = stack)
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
            TankS2Action.STACK_T3 -> Recommendation("Stack ${second.label} with $bers", reason, row.confidence, second, stackWith = bers)
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
        val plan = planPreLeap(now, remaining, state.tick(), timing, travel, leapt = leapt, arrived = arrived, reactionMillis = self.reactionMillis)
        val name = target?.name ?: rule.target?.title ?: "?"
        val routeNote = routeState?.let { if (it.state == RouteState.IN) "${profile.routeTitle(rule.route)} in" else if (target != null && targetPosition == null) "position unknown" else null }
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
        if (state.sectionDoneAt != null) return Recommendation("Leap to $name", "${state.section.title} done · ${rule.reason}", Confidence.HIGH, leap = advice, urgent = true)
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

/** Slow puzzles weigh on a route decision; fast ones do not. */
enum class PuzzleSpeed { SLOW, FAST }

/**
 * Estimated solve times and speed classes per puzzle. These are planning estimates for an experienced player, not
 * measurements; [unknownMillis] is used when nobody has shared the puzzle.
 */
data class PuzzleTiming(val millis: Map<PuzzleType, Long>, val speed: Map<PuzzleType, PuzzleSpeed>, val unknownMillis: Long) {
    fun millis(puzzle: PuzzleType?) = puzzle?.let(millis::get) ?: unknownMillis
    fun speed(puzzle: PuzzleType?) = puzzle?.let(speed::get)
}

/**
 * Leap and travel parameters. [blocksPerSecond] is walking speed between stations; [leapDelayAfterTickMillis] is how
 * long after the intended Goldor tick the leap should happen; [safetyMarginMillis] is kept spare before the opening.
 */
data class LeapTiming(
    val blocksPerSecond: Double,
    val travelOverheadMillis: Long,
    val leapCastMillis: Long,
    val safetyMarginMillis: Long,
    val leapDelayAfterTickMillis: Long,
    val leapWindowMillis: Long,
    val prepareLeadMillis: Long,
)

/**
 * Goldor's tick. [periodMillis] is the interval; [firstAfterStartMillis] is when the first tick follows Goldor's
 * opening line. Both are configurable estimates until a tick is observed.
 */
data class TickRule(val periodMillis: Long, val firstAfterStartMillis: Long)

/**
 * Time the non-terminal objectives take once someone is on them, and how long a finished section's gate takes to
 * open (5 s unless it is blown early). Estimates; the gate line, when seen, replaces [gateDelayMillis].
 */
data class ObjectiveTiming(val deviceMillis: Map<GoldorSection, Long>, val leverMillis: Long, val gateDelayMillis: Long)

/**
 * A conditional change to a role plan: while [condition] holds, [replace] is swapped for the first of [add] (or [add]
 * is appended when nothing is replaced). Unknown conditions leave the plan as it is.
 */
data class PlanBranch(val condition: GuideCondition, val replace: ObjectiveId? = null, val add: List<ObjectiveId> = emptyList(), val reason: String)

/**
 * What one role should do in a section: its objectives in order, and who it stacks with. [backup] objectives are done
 * only if nobody else has done them (Healer's S3 device after Berserker); [branches] are the conditional changes.
 */
data class RolePlan(
    val objectives: List<ObjectiveId> = emptyList(),
    val stackWith: GoldorRole? = null,
    val note: String? = null,
    val backup: Set<ObjectiveId> = emptySet(),
    val branches: List<PlanBranch> = emptyList(),
)

/**
 * Who a role leaps to when a section is done (or, with [afterObjective], once that objective is done). [mandatory] is
 * a route priority (an early-enter) that a proximity fallback never overrides; [fallback] roles are considered by
 * distance to [fallbackGoal] only when the primary target is unavailable.
 */
data class LeapRule(
    val target: GoldorRole?,
    val reason: String,
    val mandatory: Boolean = false,
    val afterObjective: ObjectiveId? = null,
    val fallback: List<GoldorRole> = emptyList(),
    val fallbackGoal: ObjectiveId? = null,
    val route: EarlyEnter? = null,
    /** The rule is skipped while this condition holds (Tank's S2 leap to Berserker when T3 is already taken). */
    val unless: GuideCondition? = null,
    /** A timed pre-leap only when this holds; when it is unknown both branches are shown and no countdown is given. */
    val preLeapIf: GuideCondition? = null,
    /** The no-pre-leap branch, e.g. "no Mask: wait for the gate, then leap". */
    val otherwise: String? = null,
    /** A permitted alternative the player decides on, shown with the reason. */
    val alternative: String? = null,
)

/** What Tank does in S2, decided by Berserker's T4 (first) and T3 (second) puzzle speeds. */
enum class TankS2Action { STACK_T3, PRE_LEAP, HOLD }

/**
 * One row of the S2 decision table. In [reason], `{1}` and `{2}` stand for Berserker's first and second terminal
 * labels (T4 and T3 in the Sky-Myce profile). [extraDelayMillis] is added to the safety margin of the pre-leap timing (a slow T3
 * keeps the pre-leap but pushes it later); [confidence] caps the recommendation when a type is unknown.
 */
data class TankS2Row(val action: TankS2Action, val reason: String, val extraDelayMillis: Long = 0, val confidence: Confidence = Confidence.HIGH)

/**
 * A strategy: who does what, when to leap where, and the timing estimates. Profiles are data so the meta can change
 * without touching event handling; [GoldorEngine] interprets them.
 */
data class StrategyProfile(
    val id: String,
    val version: Int,
    val title: String,
    val floors: Set<String>,
    val description: String,
    val sections: Map<GoldorSection, Map<GoldorRole, RolePlan>>,
    /** Early-enter routes the profile expects, and which role runs each. */
    val routes: Map<EarlyEnter, GoldorRole>,
    /** Leap rules per section, checked in order for each role; a role-specific rule wins over a general one. */
    val leaps: Map<GoldorSection, Map<GoldorRole?, LeapRule>>,
    /** Berserker's two S2 terminals, in the order they are done. */
    val berserkerS2Order: List<ObjectiveId>,
    /** Keyed by (T4 speed, T3 speed); null means the puzzle is not known yet. */
    val tankS2Table: Map<Pair<PuzzleSpeed?, PuzzleSpeed?>, TankS2Row>,
    /** In S3 these two finish one terminal each, then stack the device; null when the profile has no device stack. */
    val s3DeviceStack: Pair<GoldorRole, GoldorRole>?,
    /** In S4 each role's lever priority. Priorities are fixed; position never swaps them. */
    val s4LeverPriority: Map<GoldorRole, ObjectiveId>,
    val puzzles: PuzzleTiming,
    val leap: LeapTiming,
    val tick: TickRule,
    val objectives: ObjectiveTiming,
    /** The class-by-class route guide, when the profile has one. */
    val guide: StrategyGuide? = null,
) {
    fun plan(section: GoldorSection, role: GoldorRole) = sections[section]?.get(role) ?: RolePlan()
    /**
     * Who owns [objective]: a role that does it outright before one that only backs it up, then a role that does it
     * from another section (Berserker's i4 in S1, Mage's lights from the EE2 spot).
     */
    fun assignee(objective: ObjectiveId): GoldorRole? = sections[objective.section]?.entries?.let { plans ->
        plans.firstOrNull { objective in it.value.objectives && objective !in it.value.backup } ?: plans.firstOrNull { objective in it.value.objectives }
    }?.key ?: sections.values.firstNotNullOfOrNull { plans -> plans.entries.firstOrNull { objective in it.value.objectives }?.key }
    fun leapRule(section: GoldorSection, role: GoldorRole) = leaps[section]?.let { it[role] ?: it[null] }
    /** The leap at the end of a section: the role's own rule unless that one is tied to an objective, else the general one. */
    fun sectionLeapRule(section: GoldorSection, role: GoldorRole) = leaps[section]?.let { rules -> rules[role]?.takeIf { it.afterObjective == null } ?: rules[null] }
    /** A route's name for this profile's runner: EE2 is MEE2 or AEE2 depending on who runs it. */
    fun routeTitle(route: EarlyEnter) = if (route == EarlyEnter.MEE2) "${routes[route]?.short ?: "M"}EE2" else route.title
    fun tankS2(first: PuzzleSpeed?, second: PuzzleSpeed?) = tankS2Table.getValue(first to second)
    /** A table reason with Berserker's terminal labels filled in. */
    fun tankS2Reason(row: TankS2Row) = row.reason.replace("{1}", berserkerS2Order[0].label).replace("{2}", berserkerS2Order[1].label)
}

object GoldorProfiles {
    private fun t(section: GoldorSection, n: Int) = ObjectiveId.terminal(section, n)
    private fun dev(section: GoldorSection) = ObjectiveId.device(section)
    private fun lever(section: GoldorSection, side: LeverSide) = ObjectiveId.lever(section, side)
    private val S1 = GoldorSection.S1; private val S2 = GoldorSection.S2; private val S3 = GoldorSection.S3; private val S4 = GoldorSection.S4
    private val H = GoldorRole.HEALER; private val M = GoldorRole.MAGE; private val B = GoldorRole.BERSERKER
    private val A = GoldorRole.ARCHER; private val T = GoldorRole.TANK

    /**
     * Planning estimates; Melody is weighted heaviest, then Numbers and Rubik's. Since 0.27.2 Melody has three rows and
     * Numbers ten numbers, so these are shorter than older guides suggest. Refine with observed runs.
     */
    private val defaultPuzzles = PuzzleTiming(
        millis = mapOf(PuzzleType.MELODY to 6_500, PuzzleType.NUMBERS to 3_500, PuzzleType.RUBIKS to 3_500,
            PuzzleType.STARTS_WITH to 2_000, PuzzleType.SELECT_ALL to 2_000, PuzzleType.PANES to 1_500),
        speed = mapOf(PuzzleType.MELODY to PuzzleSpeed.SLOW, PuzzleType.NUMBERS to PuzzleSpeed.SLOW, PuzzleType.RUBIKS to PuzzleSpeed.SLOW,
            PuzzleType.STARTS_WITH to PuzzleSpeed.FAST, PuzzleType.SELECT_ALL to PuzzleSpeed.FAST, PuzzleType.PANES to PuzzleSpeed.FAST),
        unknownMillis = 4_000,
    )

    private val defaultLeap = LeapTiming(blocksPerSecond = 14.0, travelOverheadMillis = 400, leapCastMillis = 250, safetyMarginMillis = 500,
        leapDelayAfterTickMillis = 150, leapWindowMillis = 1_200, prepareLeadMillis = 1_500)

    /** Goldor's tick every 60 server ticks, the first 60 ticks after his opening line (Odin `TickTimers`). */
    private val defaultTick = TickRule(periodMillis = 3_000, firstAfterStartMillis = 3_000)

    /** Simon Says, lights, arrows and i4; levers are a click once reached. */
    private val defaultObjectives = ObjectiveTiming(
        deviceMillis = mapOf(S1 to 9_000, S2 to 3_000, S3 to 6_000, S4 to 6_000), leverMillis = 500, gateDelayMillis = 5_000)

    /**
     * The S2 decision table: Berserker's T4 decides first, T3 adjusts timing. Every combination is listed so none is
     * decided by accident.
     */
    private val tankS2: Map<Pair<PuzzleSpeed?, PuzzleSpeed?>, TankS2Row> = mapOf(
        (PuzzleSpeed.SLOW to PuzzleSpeed.SLOW) to TankS2Row(TankS2Action.STACK_T3, "{1}+{2} slow — stack {2}, leap late", extraDelayMillis = 1_000),
        (PuzzleSpeed.SLOW to PuzzleSpeed.FAST) to TankS2Row(TankS2Action.STACK_T3, "{1} slow — stack {2}"),
        (PuzzleSpeed.SLOW to null) to TankS2Row(TankS2Action.STACK_T3, "{1} slow — stack {2}", confidence = Confidence.MEDIUM),
        (PuzzleSpeed.FAST to PuzzleSpeed.SLOW) to TankS2Row(TankS2Action.PRE_LEAP, "{2} slow — delay", extraDelayMillis = 500),
        (PuzzleSpeed.FAST to PuzzleSpeed.FAST) to TankS2Row(TankS2Action.PRE_LEAP, "{1}/{2} fast — pre-leap"),
        (PuzzleSpeed.FAST to null) to TankS2Row(TankS2Action.PRE_LEAP, "{1} fast, {2} unknown", confidence = Confidence.MEDIUM),
        (null to PuzzleSpeed.SLOW) to TankS2Row(TankS2Action.HOLD, "{1} unknown, {2} slow", confidence = Confidence.LOW),
        (null to PuzzleSpeed.FAST) to TankS2Row(TankS2Action.HOLD, "{1} unknown", confidence = Confidence.LOW),
        (null to null) to TankS2Row(TankS2Action.HOLD, "{1} unknown", confidence = Confidence.LOW),
    )

    /**
     * Sky-Myce's own strategy, as specified for this feature. It follows the ADG route (MEE2, HEE3, MEE4/Core) with
     * Berserker pre-dev on i4, stacking with Tank in S1, doing S2 T4 then T3, stacking the S3 device with Healer and
     * taking the bottom S4 lever while Healer takes the top one.
     */
    val SKY_MYCE = StrategyProfile(
        id = "skymyce-adg", version = 1, title = "Sky-Myce ADG", floors = setOf("F7", "M7"),
        description = "Sky-Myce's specified route: Berserker pre-dev and Tank stack in S1, T4 then T3 in S2, S3 device stack, S4 top/bottom levers; MEE2, HEE3 and MEE4 Core.",
        sections = mapOf(
            S1 to mapOf(
                T to RolePlan(listOf(t(S1, 1), t(S1, 2))),
                B to RolePlan(listOf(dev(S4)), stackWith = T, note = "Pre-dev i4, leap to Tank, stack"),
                // MEE2: Mage takes the S1 levers, then enters S2 early; Archer does 3 and 4 (as in DungeonTerminalMap).
                M to RolePlan(listOf(lever(S1, LeverSide.LEFT), lever(S1, LeverSide.RIGHT)), note = "Levers, then MEE2"),
                A to RolePlan(listOf(t(S1, 3), t(S1, 4))),
                H to RolePlan(listOf(dev(S1))),
            ),
            S2 to mapOf(
                T to RolePlan(listOf(t(S2, 1))),
                M to RolePlan(listOf(dev(S2), t(S2, 2)), note = "MEE2: device from the EE2 spot"),
                B to RolePlan(listOf(t(S2, 4), t(S2, 3))),
                A to RolePlan(listOf(t(S2, 5), lever(S2, LeverSide.RIGHT))),
                H to RolePlan(listOf(lever(S2, LeverSide.LEFT)), note = "Lever, then HEE3"),
            ),
            S3 to mapOf(
                T to RolePlan(listOf(t(S3, 1))),
                H to RolePlan(listOf(t(S3, 2), dev(S3)), stackWith = B, note = "HEE3, then device stack"),
                B to RolePlan(listOf(t(S3, 3), dev(S3)), stackWith = H),
                A to RolePlan(listOf(t(S3, 4), lever(S3, LeverSide.LEFT), lever(S3, LeverSide.RIGHT))),
                M to RolePlan(emptyList(), note = "Help where needed"),
            ),
            S4 to mapOf(
                T to RolePlan(listOf(t(S4, 1))),
                A to RolePlan(listOf(t(S4, 2))),
                B to RolePlan(listOf(t(S4, 3), lever(S4, LeverSide.LEFT))),
                H to RolePlan(listOf(t(S4, 4), lever(S4, LeverSide.RIGHT))),
                M to RolePlan(emptyList(), note = "MEE4 Core"),
            ),
        ),
        routes = mapOf(EarlyEnter.MEE2 to M, EarlyEnter.HEE3 to H, EarlyEnter.MEE4 to M),
        leaps = mapOf(
            S1 to mapOf(
                B to LeapRule(T, "Pre-dev done — leap Tank", mandatory = true, afterObjective = dev(S4)),
                null to LeapRule(M, "MEE2 — leap Mage", mandatory = true, route = EarlyEnter.MEE2),
            ),
            S2 to mapOf(
                null to LeapRule(H, "HEE3 — leap Healer", mandatory = true, route = EarlyEnter.HEE3,
                    fallback = listOf(H, A), fallbackGoal = t(S3, 1)),
            ),
            S4 to mapOf(
                null to LeapRule(M, "MEE4 Core — leap Mage", mandatory = true, route = EarlyEnter.MEE4),
            ),
        ),
        berserkerS2Order = listOf(t(S2, 4), t(S2, 3)),
        tankS2Table = tankS2,
        s3DeviceStack = B to H,
        s4LeverPriority = mapOf(H to lever(S4, LeverSide.RIGHT), B to lever(S4, LeverSide.LEFT)),
        puzzles = defaultPuzzles,
        leap = defaultLeap,
        tick = defaultTick,
        objectives = defaultObjectives,
    )

    /**
     * The 2026 ADG assignment as DungeonTerminalMap encodes it (published with 0.27.2): it differs from Sky-Myce's
     * profile in S2, where Berserker does 3 and 5 and Archer 4. The S2 Tank table then reads Berserker's first and
     * second terminal (3, then 5). Kept separate so the Sky-Myce defaults are never silently replaced.
     */
    val ADG_2026 = SKY_MYCE.copy(
        id = "adg-2026", version = 1, title = "ADG 2026 (DTM preset)",
        description = "ADG roles as DungeonTerminalMap encodes them: S2 Berserker 3 and 5, Archer 4 and right lever. Unverified against the ADG video.",
        sections = SKY_MYCE.sections + (S2 to mapOf(
            T to RolePlan(listOf(t(S2, 1))),
            M to RolePlan(listOf(dev(S2), t(S2, 2)), note = "MEE2: device from the EE2 spot"),
            B to RolePlan(listOf(t(S2, 3), t(S2, 5))),
            A to RolePlan(listOf(t(S2, 4), lever(S2, LeverSide.RIGHT))),
            H to RolePlan(listOf(lever(S2, LeverSide.LEFT)), note = "Lever, then HEE3"),
        )),
        berserkerS2Order = listOf(t(S2, 3), t(S2, 5)),
    )

    val all by lazy { listOf(GoldorBaseline.GOLD_STANDARD, GoldorBaseline.GOLD_STANDARD_AEE2, SKY_MYCE, ADG_2026) }
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: GoldorBaseline.GOLD_STANDARD
}

/**
 * The supplied gold-standard F7/M7 terminal strategy (baseline v1, received 2026-10-18), as data: the role plans and
 * leap rules the engine follows, and the class-by-class guide the HUD and `/sm goldor guide` show. Routes, terminal
 * numbers, lever sides, leap targets and conditions follow the supplied document; nothing here is inferred from
 * other guides. Changing any step changes the guide fingerprint, which the executable check pins to the version.
 *
 * S4 lever sides use the map's convention (right = top, y 128; left = bottom, y 121), so Healer's "lever on the
 * right" is the top lever and Berserker's bottom-then-top order starts on the left one.
 */
object GoldorBaseline {
    const val ID = "gold-standard"
    const val VERSION = 1
    /** [guideFingerprint] of the Mage-EE2 baseline guide. Update only together with [VERSION] and a [GuideChange]. */
    const val FINGERPRINT = "d7963b72212f200f"

    private fun t(section: GoldorSection, n: Int) = ObjectiveId.terminal(section, n)
    private fun dev(section: GoldorSection) = ObjectiveId.device(section)
    private fun ll(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.LEFT)
    private fun rl(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.RIGHT)

    private fun ref(title: String, url: String, note: String? = null) = GuideRef(title, url, note)
    private val tankSafespot = ref("Tank pre-leap safespot", "https://www.youtube.com/watch?v=bRY-iNbZY88")
    private val tankPov = ref("Full Tank POV by EngineerAndrew", "https://youtu.be/re2FH_y9wNU")
    private val mageEe2 = ref("Mage EE2 (mee2) example", "https://www.youtube.com/watch?v=xSBzOGSAYxA")
    private val ee3Enter = ref("EE3 gate entry", "https://media.discordapp.net/attachments/1556841354003480667/1557148449105911838/ee3_enter.png")
    private val ee3Wait = ref("EE3 waiting position", "https://media.discordapp.net/attachments/1556841354003480667/1557148659286544486/ee3_wait_moved_back.png")
    private val bersShowcase = ref("Berserker terminal movement showcase", "https://youtu.be/g6mpRuUQB-k")
    private val bersI4 = ref("Example i4", "https://youtu.be/sMa1KOic9dE")
    private val bersAndesite = ref("S3 superbounce off the andesite", "https://media.discordapp.net/attachments/1556841354003480667/1557144340608852032/hit_head_on_andesite.png")
    private val archerS1 = ref("Archer S1 POV", "https://www.youtube.com/watch?v=0DKzAuNOKNY&t=171s")
    private val archerS4 = ref("Archer S4 example", "https://youtu.be/xORRDf-tCqY", "Ignore the early entry in this video; it is not part of the Archer S4 route")

    private fun step(id: String, kind: StepKind, text: String, detail: String = text, objectives: List<ObjectiveId> = emptyList(),
                     leapTo: GoldorRole? = null, condition: GuideCondition? = null, refs: List<GuideRef> = emptyList()) =
        GuideStep(id, kind, text, detail, objectives, leapTo, condition, refs)

    /** The guide, with [ee2] as the player the party leaps to after S1 (Mage in the baseline; Archer for AEE2). */
    private fun guide(ee2: GoldorRole): Map<GoldorRole, Map<GoldorSection, SectionGuide>> {
        val ee2Name = ee2.title
        fun sections(role: GoldorRole, vararg guides: Pair<GoldorSection, List<GuideStep>>, refs: Map<GoldorSection, List<GuideRef>> = emptyMap()) =
            role to guides.associate { (section, steps) -> section to SectionGuide(role, section, steps, refs[section].orEmpty()) }
        return mapOf(
            sections(TANK,
                S1 to listOf(
                    step("tank.s1.loadout", PREP, "Equip the P3 loadout"),
                    step("tank.s1.t2", REQUIRED, "T2, left of SS: mine the platform, run off as you open it",
                        "Go to the second terminal, to the left of SS. Mine out the platform using the demonstrated route. Run off the platform as you open the second terminal, then complete it.", listOf(t(S1, 2))),
                    step("tank.s1.t1", REQUIRED, "Then T1", "Complete the first terminal afterward.", listOf(t(S1, 1))),
                    step("tank.s1.lastbreath", REQUIRED, "Last Breath Goldor once", "Last Breath Goldor once, following the demonstrated route."),
                    step("tank.s1.leap", REQUIRED, "Pre-leap to $ee2Name", "Preleap to $ee2Name when your terminals are complete.", leapTo = ee2),
                    step("tank.s1.safespot", OPTIONAL, "Safespot: mine one block into the outer wall",
                        "If needed, mine one block into the side of the outer wall to reach a safe position and avoid Goldor's damage ticks.", refs = listOf(tankSafespot)),
                ),
                S2 to listOf(
                    step("tank.s2.t1", REQUIRED, "T1: turn right, run off without jumping", "Turn right and run off toward terminal 1 without jumping.", listOf(t(S2, 1))),
                    step("tank.s2.leap-bers", REQUIRED, "Leap to Berserker", "Leap to Berserker to stack terminal 3.", leapTo = BERSERKER),
                    step("tank.s2.t3", REQUIRED, "Stack T3 with Berserker", "Stack terminal 3 with Berserker.", listOf(t(S2, 3))),
                    step("tank.s2.right-lever", CONDITIONAL, "stack the right lever instead",
                        "If somebody is at terminal 3 and terminal 3 is not Melody, stack the right lever instead.", listOf(rl(S2)), condition = GuideCondition.T3_TAKEN_NOT_MELODY),
                    step("tank.s2.leap", REQUIRED, "Leap to Healer", "Leap to Healer afterward.", leapTo = HEALER),
                ),
                S3 to listOf(
                    step("tank.s3.t1", REQUIRED, "Run back to T1 without jumping", "Once at Healer and S2 is complete, run back toward terminal 1 without jumping.", listOf(t(S3, 1))),
                    step("tank.s3.leap-archer", REQUIRED, "Leap to Archer", "Leap to Archer.", leapTo = ARCHER),
                    step("tank.s3.levers", REQUIRED, "Stack the levers, blow up the gate", "Stack the levers and blow up the gate.", listOf(ll(S3), rl(S3))),
                    step("tank.s3.leap", REQUIRED, "Leap to Mage", "Leap to Mage.", leapTo = MAGE),
                ),
                S4 to listOf(
                    step("tank.s4.t1", REQUIRED, "Run to T1 without jumping", "Run to terminal 1 without jumping.", listOf(t(S4, 1))),
                    step("tank.s4.t3", CONDITIONAL, "also do T3", "If Berserker fails i4, also complete terminal 3.", listOf(t(S4, 3)), condition = GuideCondition.I4_FAILED),
                    step("tank.s4.leap", REQUIRED, "Leap to Mage in core", "Afterwards, leap to Mage, who should be in core.", leapTo = MAGE),
                ),
                refs = mapOf(S1 to listOf(tankSafespot), S4 to listOf(tankPov)),
            ),
            sections(MAGE,
                S1 to listOf(
                    step("mage.s1.loadout", PREP, "Equip the P3 loadout", "Right after leaping to Healer as Storm dies, equip the P3 loadout."),
                    step("mage.s1.ll", REQUIRED, "Left lever", "Complete the left lever.", listOf(ll(S1))),
                    step("mage.s1.rl", REQUIRED, "Right lever",
                        "Complete the right lever. More consistent: stand on the quartz and Bonzo toward the left. Slightly faster: run and jump over to the left-lever platform at 600 speed.", listOf(rl(S1))),
                    step("mage.s1.gate", REQUIRED, "Blow up the gate and dig through now", "Blow up the gate and dig through immediately. Do not wait for any death ticks."),
                    step("mage.s1.lights", REQUIRED, "Turn right, lava superbounce up, flick one lights lever",
                        "After passing through, turn right. Bonzo off the ledge, or drop down and jump into the lava. Superbounce in the lava while looking up to reach the lights device. Flick one lever to complete it; this can be done before S1 finishes.", listOf(dev(S2))),
                    step("mage.s1.wait", REQUIRED, "Face the S2 left lever; wait for the team", "Look straight ahead toward the left lever in S2 and wait for the team to leap to you."),
                    step("mage.s1.defence", REMINDER, "On Goldor ticks: Spirit, then Phoenix, then Bonzo", "Use invincibility items in this order, in accordance with Goldor's tick timers: Spirit, Phoenix, Bonzo."),
                    step("mage.s1.safespot", RECOVERY, "Step back, dig into the left wall to bedrock",
                        "If Healer is slow at SS or fails SS late, SS reset or SSRS appears in chat, or there is exceptionally bad Melody, move slightly backward and dig into the left wall until reaching bedrock.", condition = GuideCondition.SS_TROUBLE),
                ),
                S2 to listOf(
                    step("mage.s2.wait", REQUIRED, "Wait for the team to leap to you", "Once S1 is complete, wait for the remainder of the team to leap to you."),
                    step("mage.s2.t2", REQUIRED, "Run down to T2", "Run down and complete the second terminal.", listOf(t(S2, 2))),
                    step("mage.s2.core", REQUIRED, "Dig into core; stand on the back half of the gray carpet",
                        "Dig into core. Dig out from the middle and stand on the gray carpet. The back half of the gray carpet is safe; the front half is not."),
                ),
                S3 to listOf(
                    step("mage.s3.flare", REQUIRED, "SOS flare just outside core", "Look directly in front of you and cast the SOS flare just outside core to give Archer and Berserker ferocity for the DPS phase."),
                    step("mage.s3.rag", REQUIRED, "LCM loadout; keep casting Ragnarock", "While waiting at core, equip the LCM loadout and repeatedly cast Ragnarock."),
                ),
                S4 to listOf(
                    step("mage.s4.wait", REQUIRED, "Wait for the team to leap to you", "Wait for the entire team to leap to Mage."),
                    step("mage.s4.enter", REQUIRED, "Re-enter core at the door's corner two blocks", "Re-enter core through the corner two blocks of the door, following the demonstrated position."),
                    step("mage.s4.dig", REQUIRED, "Dig the two stone rows beside that block", "Dig the two rows of stone next to the block used to re-enter."),
                    step("mage.s4.rag", REQUIRED, "Ragnarock beside the door", "Cast Ragnarock while standing next to the door. This skips waiting for the barriers to fall."),
                    step("mage.s4.after", REQUIRED, "Wait for the team; Ragnarock while terminals run",
                        "Afterward, wait for the team to leap back. If Melody takes a long time, keep casting Ragnarock (very short cooldown) until all terminals are complete."),
                ),
                refs = mapOf(S1 to listOf(mageEe2)),
            ),
            sections(HEALER,
                S1 to listOf(
                    step("healer.s1.ss", REQUIRED, "SS: Start ×3 in ~0.5 s, then the last two buttons",
                        "Complete SS as soon as the terminals phase begins. Quickly press the Start Device button three times in approximately 0.5 seconds, then click the last two buttons the device showed (if it showed only two, click those two). This skips the initial one-button sequence.", listOf(dev(S1))),
                    step("healer.s1.retry", RECOVERY, "SS failed: retry it", "If SS fails, retry it.", condition = GuideCondition.SS_TROUBLE),
                    step("healer.s1.callout", OPTIONAL, "Bind a key to send \"SS reset\" in party chat",
                        "Optionally bind a hotkey to send SS reset in chat so teammates know they may need to dig into safespots. Sky-Myce never sends it for you."),
                    step("healer.s1.leap", REQUIRED, "Leap to the EE2 player ($ee2Name)", "Once finished, leap to the EE2 player. That is $ee2Name in this strategy.", leapTo = ee2),
                ),
                S2 to listOf(
                    step("healer.s2.high", REQUIRED, "High lever: jump off, Bonzo off the next quartz pillar",
                        "Run directly forward and jump off the device platform. Look down at the next quartz pillar and Bonzo off it midair. Activate the high lever.", listOf(ll(S2))),
                    step("healer.s2.right", CONDITIONAL, "also the right lever (never in a fast party)",
                        "Only if the party is slow at terminals, also run off to the right lever and activate it. Not in a fast party: it can stop you reaching EE3 in time.", listOf(rl(S2)), condition = GuideCondition.SLOW_PARTY),
                    step("healer.s2.ee2-failed", RECOVERY, "Superbounce back to the device, do the lever, then EE3",
                        "If EE2 fails, superbounce back to the device and complete the lever. Then proceed to EE3 as normal.", condition = GuideCondition.EE2_FAILED),
                    step("healer.s2.gate", REQUIRED, "Superboom the gate, dig through", "Once the required lever or levers are complete, use Superboom TNT to blow up the gate and dig through the door from the demonstrated entry locations.", refs = listOf(ee3Enter)),
                    step("healer.s2.wait", REQUIRED, "Drop to the EE3 spot, face the hole in the wall", "Fall down, move to the demonstrated waiting position and face the hole in the wall directly in front of you.", refs = listOf(ee3Wait)),
                    step("healer.s2.defence", REMINDER, "As needed: Spirit Mask, then Phoenix, then Bonzo's Mask", "Use these defensive items in order, as necessary: Spirit Mask, Phoenix, Bonzo's Mask."),
                    step("healer.s2.safespot", RECOVERY, "Dig to the left and stand at bedrock", "If you are about to die, dig to the left and stand at bedrock to reach a safespot.", condition = GuideCondition.LOW_HEALTH),
                ),
                S3 to listOf(
                    step("healer.s3.t2", REQUIRED, "T2", "Go to the second terminal and complete it.", listOf(t(S3, 2))),
                    step("healer.s3.device", REQUIRED, "Device, unless Berserker has done it", "Go to the device and complete it if Berserker has not already done so.", listOf(dev(S3))),
                    step("healer.s3.levers", CONDITIONAL, "the levers too, right lever first", "If both Archer and Tank are slow, complete the levers as well, starting with the right lever.",
                        listOf(rl(S3), ll(S3)), condition = GuideCondition.TANK_ARCHER_SLOW),
                    step("healer.s3.leap", REQUIRED, "Leap to Mage for core", "Leap to Mage for core.", leapTo = MAGE),
                    step("healer.s3.mask", OPTIONAL, "Mask left: equip it on the way to your terminal", "If a Mask remains, you may equip it while running toward your terminal to open it faster."),
                    step("healer.s3.safespot", OPTIONAL, "S3 slow and you run out early: leap back to Mage", "If S3 is completed slowly and you run out early, you may leap back to Mage to safespot."),
                ),
                S4 to listOf(
                    step("healer.s4.t4", REQUIRED, "T4", "Run to the fourth terminal and complete it.", listOf(t(S4, 4))),
                    step("healer.s4.right", REQUIRED, "Fall into the lava on your left, flick the right lever", "Fall into the lava on your left and flick the lever on the right.", listOf(rl(S4))),
                    step("healer.s4.left", REQUIRED, "Left lever, unless Berserker has it or it is done",
                        "Look toward the left lever. If Berserker is already doing it, or it is already complete, skip it and leap to Mage. Otherwise flick it first.", listOf(ll(S4))),
                    step("healer.s4.leap", REQUIRED, "Leap to Mage", "After the required lever actions are finished, leap to Mage.", leapTo = MAGE),
                ),
                refs = mapOf(S2 to listOf(ee3Enter, ee3Wait)),
            ),
            sections(BERSERKER,
                S1 to listOf(
                    step("bers.s1.hotbar", PREP, "Keybound slots: Duplex Terminator, Last Breath, Infinileap, fishing rod",
                        "During lightning or while falling to the bottom of P3, put a Duplex Terminator, Last Breath, Infinileap and a fishing rod (for pet rules) in individually keybound hotbar slots. Do not rely on the scroll wheel for these switches."),
                    step("bers.s1.loadout", PREP, "Terminal loadout: a Mask, 100 attack speed, Spider pet", "Equip the terminal loadout, including a Mask and 100 attack speed. Use a Spider pet, which slows Goldor down."),
                    step("bers.s1.stats", OPTIONAL, "/stats hotkey for a quick Bonzo's Mask swap", "Optionally configure a /stats hotkey to swap to Bonzo's Mask quickly if more time is needed; Bonzo's Mask in the top-centre slot makes it faster."),
                    step("bers.s1.lastbreath", REQUIRED, "Last Breath Goldor as he spawns, below the quartz platform",
                        "While Storm is dying, pull back Last Breath and shoot Goldor as soon as he spawns, aiming below the quartz platform as in the i4 video. With a P3 start timer (Odin or Devonian) release about 200 ms + your ping; if the timer is broken, shoot as soon as Goldor is visible. The shot debuffs Goldor and activates Lust for Blood through flame ticks. Optionally stand near the centre of the pressure plate, or slightly forward: the device activates only when the centre of your model is on the plate."),
                    step("bers.s1.i4", REQUIRED, "i4: shoot the lit targets, prefire the next pane",
                        "Shoot all nine targets while they are lit. Shoot a glass pane next to the lit target, preferring one with another uncompleted target beside it; Terminator's spread can hit two at once. Immediately prefire a different pane with uncompleted targets (Odin can show the ideal pane). Do not wait for the first arrow to land. At higher ping, prefire several. If a shot missed, return to that target.", listOf(dev(S4))),
                    step("bers.s1.leap-tank", REQUIRED, "Leap to Tank (the default after i4)",
                        "After completing i4, prioritise leaping to Tank. Most parties are too slow for a direct leap to Mage. Equip Bonzo's Mask before continuing if needed.", leapTo = TANK),
                    step("bers.s1.mage", CONDITIONAL, "a direct leap to Mage instead", "Only when the party's timing and the current situation make it appropriate and reliable, leap directly to Mage instead of Tank.",
                        leapTo = ee2, condition = GuideCondition.MAGE_ROUTE_SAFE),
                    step("bers.s1.melody", RECOVERY, "Tank or Archer has a bad Melody: do any unopened terminals", "If Tank or Archer gets a bad Melody, complete any unopened terminals."),
                    step("bers.s1.preleap", REQUIRED, "Nothing left: Bonzo's Mask + Black Cat, pre-leap to $ee2Name",
                        "If no unopened terminals need completing, equip Bonzo's Mask and Black Cat and preleap to the early-entering $ee2Name.", leapTo = ee2),
                    step("bers.s1.missed", RECOVERY, "Last Breath missed: shoot Goldor with it now", "If Last Breath missed at the start of P3, shoot Goldor with Last Breath afterward; Lust for Blood stacks matter for damage."),
                ),
                S2 to listOf(
                    step("bers.s2.t5", REQUIRED, "Bonzo to T5 without jumping; finish it as you jump off",
                        "After leaping to Mage, Bonzo without jumping to the fifth terminal. Complete it while jumping off so that you land on the platform below.", listOf(t(S2, 5))),
                    step("bers.s2.t3", REQUIRED, "T3", "Complete terminal 3.", listOf(t(S2, 3))),
                    step("bers.s2.lever", CONDITIONAL, "stack the right lever before leaping", "If Archer has Melody, stack the right lever before leaping to Healer.", listOf(rl(S2)), condition = GuideCondition.ARCHER_MELODY),
                    step("bers.s2.leap", REQUIRED, "Leap to Healer", "Leap to Healer.", leapTo = HEALER),
                ),
                S3 to listOf(
                    step("bers.s3.t3", REQUIRED, "Run off the side, lava-bounce and Bonzo to T3",
                        "Immediately run off to the side after leaping, then lava-bounce and Bonzo to the third terminal. Alternative: look up to superbounce and hit your head on the low-hanging polished andesite.", listOf(t(S3, 3)), refs = listOf(bersAndesite)),
                    step("bers.s3.device", REQUIRED, "Bonzo to the device and complete it", "Bonzo toward the device and complete it.", listOf(dev(S3))),
                    step("bers.s3.frames", RECOVERY, "Device stuck after a same-tick click: rotate 8 more times",
                        "If Berserker and Healer click an item frame in the same tick and the device does not register, rotate it eight additional times to complete it."),
                    step("bers.s3.levers", CONDITIONAL, "the levers too, right lever first", "If both Archer and Tank are slow, complete the levers as well, starting with the right lever.",
                        listOf(rl(S3), ll(S3)), condition = GuideCondition.TANK_ARCHER_SLOW),
                    step("bers.s3.leap", REQUIRED, "Leap to Mage for core", "Leap to Mage for core.", leapTo = MAGE),
                    step("bers.s3.mask", OPTIONAL, "Mask left: equip it on the way to your terminal", "If any Masks remain, you may equip one while running toward your terminal to open it faster."),
                    step("bers.s3.safespot", OPTIONAL, "S3 slow and you run out early: leap back to Mage", "If S3 is completed slowly and you run out early, you may leap back to Mage to safespot."),
                ),
                S4 to listOf(
                    step("bers.s4.t3", REQUIRED, "Forward and right to T3", "Run forward and right to the third terminal.", listOf(t(S4, 3))),
                    step("bers.s4.bottom", REQUIRED, "Lava-bounce Bonzo to the levers, bottom lever", "After terminal 3, Bonzo toward the levers using the lava bounce and flick the bottom lever.", listOf(ll(S4))),
                    step("bers.s4.top", REQUIRED, "Bonzo off the yellow glass, top lever", "Bonzo off the yellow glass above that lever and flick the top lever.", listOf(rl(S4))),
                    step("bers.s4.leap", REQUIRED, "Leap to Mage", "After terminal 3 and the levers are complete, leap to Mage.", leapTo = MAGE),
                ),
                refs = mapOf(S1 to listOf(bersShowcase, bersI4), S2 to listOf(bersShowcase), S3 to listOf(bersShowcase, bersAndesite), S4 to listOf(bersShowcase)),
            ),
            sections(ARCHER,
                S1 to listOf(
                    step("archer.s1.loadout", PREP, "Equip the P3 loadout", "After leaping to Healer, equip the P3 loadout."),
                    step("archer.s1.t4", REQUIRED, "Turn around, jump to T4, open it as the phase starts",
                        "Turn around and jump to the fourth terminal. Open it as soon as the terminal phase begins. Optionally fall off the platform to the right.", listOf(t(S1, 4))),
                    step("archer.s1.t3", REQUIRED, "Then T3", "Complete the third terminal afterward.", listOf(t(S1, 3))),
                    step("archer.s1.leap", REQUIRED, "Leap to $ee2Name", "Leap to $ee2Name as soon as your terminals are complete.", leapTo = ee2),
                ),
                S2 to listOf(
                    step("archer.s2.t4", REQUIRED, "Hold W, don't jump, to T4; hold S ~1 s while falling",
                        "After leaping to Mage, hold W and do not jump: this takes you straight to the fourth terminal. Once falling, hold S for about one second to line up. Complete it.", listOf(t(S2, 4))),
                    step("archer.s2.lever", REQUIRED, "Lava on your right, Bonzo off the wall to the bottom lever", "Walk into the lava on your right and Bonzo off the wall to reach the bottom lever.", listOf(rl(S2))),
                    step("archer.s2.preleap", CONDITIONAL, "pre-leap to Healer now, then the S3 movement",
                        "If a Mask remains, leap to Healer immediately and perform the S3 route movement.", leapTo = HEALER, condition = GuideCondition.MASK_LEFT),
                    step("archer.s2.leap", REQUIRED, "Leap to Healer: Mask left, now; none, after S2",
                        "If no Masks remain, wait until S2 is complete, then leap to Healer. With a Mask left, pre-leap immediately.", leapTo = HEALER),
                    step("archer.s2.safespot", RECOVERY, "Dig into the terminal's wall to bedrock", "After the pre-leap, if a safespot is needed, dig into the wall where the terminal is located until reaching bedrock.", condition = GuideCondition.LOW_HEALTH),
                ),
                S3 to listOf(
                    step("archer.s3.t4", REQUIRED, "550+ speed: into the hole, jump again to T4",
                        "Run forward with 550+ speed. Jump into the hole shown in the reference video, then jump again to reach the fourth terminal. (The baseline names a reference video here but gives no link.)", listOf(t(S3, 4))),
                    step("archer.s3.left", REQUIRED, "Bonzo to the left lever; Explosive Shot the gate on the way",
                        "Bonzo from the fourth terminal toward the left lever. While flying, use Explosive Shot to blow up the gate.", listOf(ll(S3))),
                    step("archer.s3.right", REQUIRED, "Bonzo down off the glass to the right lever", "Bonzo downward off the glass from the left lever to reach the right lever as quickly as possible.", listOf(rl(S3))),
                    step("archer.s3.leap", REQUIRED, "Leap to Mage at core", "Leap immediately to Mage, who should be at core.", leapTo = MAGE),
                ),
                S4 to listOf(
                    step("archer.s4.t2", REQUIRED, "Forward-left lava-bounce to T2", "Run forward and left to lava-bounce toward the second terminal, then complete it.", listOf(t(S4, 2))),
                    step("archer.s4.early", OPTIONAL, "No Masks: run out early, leap back to Mage",
                        "Mage on the back half of the carpet in core is a safespot from the kill area, so with no Masks left you can run out early and leap back to Mage."),
                    step("archer.s4.leap", REQUIRED, "Leap to Mage", "Leap to Mage.", leapTo = MAGE),
                    step("archer.s4.rag", REQUIRED, "Ragnarock now, then the boss set", "Cast Ragnarock immediately, then swap to the boss set."),
                ),
                refs = mapOf(S1 to listOf(archerS1), S4 to listOf(archerS4)),
            ),
        )
    }

    private val baselineChange = GuideChange(1, null, null, "Initial gold-standard baseline for all five classes, S1–S4, as supplied", "Supplied gold standard document (2026-10-18)", ChangeStatus.BASELINE)

    private fun profile(ee2: GoldorRole, id: String, title: String, description: String, changes: List<GuideChange>): StrategyProfile {
        val sections = guide(ee2)
        val ee2Route = if (ee2 == MAGE) "MEE2" else "AEE2"
        return StrategyProfile(
            id = id, version = VERSION, title = title, floors = setOf("F7", "M7"), description = description,
            sections = mapOf(
                S1 to mapOf(
                    TANK to RolePlan(listOf(t(S1, 2), t(S1, 1)), note = "T2 first, then T1; Last Breath Goldor once"),
                    BERSERKER to RolePlan(listOf(dev(S4)), stackWith = TANK, note = "Last Breath, i4, then Tank"),
                    MAGE to RolePlan(listOf(ll(S1), rl(S1), dev(S2)), note = "Levers, gate, then the lights"),
                    ARCHER to RolePlan(listOf(t(S1, 4), t(S1, 3)), note = "T4 first, then T3"),
                    HEALER to RolePlan(listOf(dev(S1)), note = "SS straight away"),
                ),
                S2 to mapOf(
                    TANK to RolePlan(listOf(t(S2, 1), t(S2, 3)), stackWith = BERSERKER, note = "T1, then stack T3",
                        branches = listOf(PlanBranch(GuideCondition.T3_TAKEN_NOT_MELODY, replace = t(S2, 3), add = listOf(rl(S2)), reason = "T3 is taken and not Melody — stack the right lever"))),
                    MAGE to RolePlan(listOf(t(S2, 2)), note = "T2, then into core"),
                    BERSERKER to RolePlan(listOf(t(S2, 5), t(S2, 3)), note = "T5, then T3",
                        branches = listOf(PlanBranch(GuideCondition.ARCHER_MELODY, add = listOf(rl(S2)), reason = "Archer has Melody — stack the right lever before leaping"))),
                    ARCHER to RolePlan(listOf(t(S2, 4), rl(S2)), note = "T4, then the bottom lever"),
                    HEALER to RolePlan(listOf(ll(S2)), note = "High lever, then HEE3",
                        branches = listOf(PlanBranch(GuideCondition.SLOW_PARTY, add = listOf(rl(S2)), reason = "Slow party — the right lever too"))),
                ),
                S3 to mapOf(
                    TANK to RolePlan(listOf(t(S3, 1), ll(S3), rl(S3)), stackWith = ARCHER, note = "T1, then stack the levers with Archer"),
                    HEALER to RolePlan(listOf(t(S3, 2), dev(S3)), backup = setOf(dev(S3)), note = "T2, then the device if Berserker has not done it",
                        branches = listOf(PlanBranch(GuideCondition.TANK_ARCHER_SLOW, add = listOf(rl(S3), ll(S3)), reason = "Archer and Tank are slow — levers, right first"))),
                    BERSERKER to RolePlan(listOf(t(S3, 3), dev(S3)), note = "T3, then the device",
                        branches = listOf(PlanBranch(GuideCondition.TANK_ARCHER_SLOW, add = listOf(rl(S3), ll(S3)), reason = "Archer and Tank are slow — levers, right first"))),
                    ARCHER to RolePlan(listOf(t(S3, 4), ll(S3), rl(S3)), note = "T4, left lever and gate, right lever"),
                    MAGE to RolePlan(emptyList(), note = "In core: SOS flare, LCM loadout, Ragnarock"),
                ),
                S4 to mapOf(
                    TANK to RolePlan(listOf(t(S4, 1)), note = "T1",
                        branches = listOf(PlanBranch(GuideCondition.I4_FAILED, add = listOf(t(S4, 3)), reason = "Berserker failed i4 — T3 as well"))),
                    ARCHER to RolePlan(listOf(t(S4, 2)), note = "T2, then Ragnarock"),
                    BERSERKER to RolePlan(listOf(t(S4, 3), ll(S4), rl(S4)), backup = setOf(rl(S4)), note = "T3, bottom lever, top lever"),
                    HEALER to RolePlan(listOf(t(S4, 4), rl(S4), ll(S4)), backup = setOf(ll(S4)), note = "T4, right lever, left lever unless Berserker has it"),
                    MAGE to RolePlan(emptyList(), note = "Re-enter core at the door, Ragnarock"),
                ),
            ),
            routes = mapOf(EarlyEnter.MEE2 to ee2, EarlyEnter.HEE3 to HEALER, EarlyEnter.MEE4 to MAGE),
            leaps = mapOf(
                S1 to mapOf(
                    BERSERKER to LeapRule(TANK, "i4 done — leap Tank (default)", mandatory = true, afterObjective = dev(S4), unless = GuideCondition.MAGE_ROUTE_SAFE,
                        alternative = "Mage only if a direct leap is safe this run"),
                    null to LeapRule(ee2, "$ee2Route — leap ${ee2.title}", mandatory = true, route = EarlyEnter.MEE2),
                ),
                S2 to mapOf(
                    TANK to LeapRule(BERSERKER, "T1 done — leap Berserker to stack T3", afterObjective = t(S2, 1), unless = GuideCondition.T3_TAKEN_NOT_MELODY),
                    // Mage goes into core during S2 and stays; the party leaps to Healer.
                    MAGE to LeapRule(MAGE, "Into core", mandatory = true, route = EarlyEnter.MEE4),
                    ARCHER to LeapRule(HEALER, "HEE3 — leap Healer", mandatory = true, route = EarlyEnter.HEE3, fallback = listOf(HEALER), fallbackGoal = t(S3, 1),
                        preLeapIf = GuideCondition.MASK_LEFT, otherwise = "No Mask: wait for S2 to finish, then leap"),
                    null to LeapRule(HEALER, "HEE3 — leap Healer", mandatory = true, route = EarlyEnter.HEE3, fallback = listOf(HEALER, ARCHER), fallbackGoal = t(S3, 1)),
                ),
                S3 to mapOf(
                    TANK to LeapRule(ARCHER, "T1 done — leap Archer to stack the levers", afterObjective = t(S3, 1)),
                    null to LeapRule(MAGE, "Core — leap Mage", mandatory = true, route = EarlyEnter.MEE4),
                ),
                S4 to mapOf(null to LeapRule(MAGE, "Core — leap Mage", mandatory = true, route = EarlyEnter.MEE4)),
            ),
            berserkerS2Order = listOf(t(S2, 5), t(S2, 3)),
            // The baseline has no puzzle-speed table for Tank: T3 occupancy and Melody decide (a plan branch).
            tankS2Table = emptyMap(),
            s3DeviceStack = null,
            s4LeverPriority = mapOf(HEALER to rl(S4), BERSERKER to ll(S4)),
            puzzles = GoldorProfiles.SKY_MYCE.puzzles,
            leap = GoldorProfiles.SKY_MYCE.leap,
            tick = GoldorProfiles.SKY_MYCE.tick,
            objectives = GoldorProfiles.SKY_MYCE.objectives,
            guide = StrategyGuide(id, VERSION, title, "Supplied gold standard F7/M7 terminal strategy", sections, changes,
                if (ee2 == MAGE) FINGERPRINT else guideFingerprint(sections)),
        )
    }

    /** The baseline: Mage early-enters S2 (mee2). */
    val GOLD_STANDARD: StrategyProfile = profile(MAGE, ID, "Gold standard v$VERSION",
        "The supplied baseline for all five classes: Tank T2 then T1, Mage levers, gate and lights (MEE2), Healer SS then HEE3, Berserker i4 then Tank, Archer T4 then T3; Mage holds core from S2.",
        listOf(baselineChange))

    /** The same baseline for parties that choose Archer EE2: only the EE2 runner and the leap target change. */
    val GOLD_STANDARD_AEE2: StrategyProfile = profile(ARCHER, "$ID-aee2", "Gold standard v$VERSION (AEE2)",
        "The baseline with Archer early-entering S2. The baseline does not define how Mage and Archer split S1 in AEE2; agree it with your party.",
        listOf(baselineChange, GuideChange(1, null, S1, "Archer runs EE2: the S1 leap target and EE2 runner are Archer", "Party configuration allowed by the baseline", ChangeStatus.PROPOSED)))
}

/** One finished section as seen: who (by class) completed which objective, in order. Only exact attributions count. */
data class SectionObservation(val section: GoldorSection, val completions: List<Pair<GoldorRole, ObjectiveId>>, val roles: Set<GoldorRole>)

/**
 * Exact completions from a finished section: completed objectives with a named player of known class, never an
 * assignment-order guess or a completion inferred from the counter. Ordered by completion time.
 */
fun observeSection(state: GoldorRunState, section: GoldorSection): SectionObservation {
    val completions = GoldorLayout.stations(section).mapNotNull { station ->
        val info = state.state(station.objective)
        val role = info.by?.let { state.player(it)?.role }
        if (info.status != ObjectiveStatus.COMPLETE || info.ambiguous || info.source == StateSource.INFERRED || role == null) null
        else Triple(role, station.objective, info.completedAt ?: Long.MAX_VALUE)
    }.sortedBy { it.third }.map { it.first to it.second }
    return SectionObservation(section, completions, state.players.values.mapNotNull { it.role }.toSet())
}

/**
 * What each class actually does, learned across runs. [counts] is keyed `section|role|objective` with how many
 * sections that class completed that objective in; [orders] sums the objective's position in that class's sequence;
 * [sections] counts the sections each class was present for. Bounded: keys are a fixed small set.
 */
data class LearnedRoles(
    val version: Int = 1,
    val counts: Map<String, Int> = emptyMap(),
    val orders: Map<String, Int> = emptyMap(),
    val sections: Map<String, Int> = emptyMap(),
) {
    fun record(observation: SectionObservation): LearnedRoles {
        // A section with no exact completions teaches nothing and must not dilute the shares.
        if (observation.completions.isEmpty()) return this
        val counts = counts.toMutableMap(); val orders = orders.toMutableMap(); val sections = sections.toMutableMap()
        for (role in observation.roles) sections.merge(sectionKey(observation.section, role), 1, Int::plus)
        observation.completions.groupBy({ it.first }, { it.second }).forEach { (role, objectives) ->
            objectives.distinct().forEachIndexed { index, objective ->
                val key = key(observation.section, role, objective)
                counts.merge(key, 1, Int::plus); orders.merge(key, index, Int::plus)
            }
        }
        return copy(counts = counts, orders = orders, sections = sections)
    }

    fun seen(section: GoldorSection, role: GoldorRole) = sections[sectionKey(section, role)] ?: 0

    /** Share of [role]'s sections in which it completed [objective]. */
    fun share(section: GoldorSection, role: GoldorRole, objective: ObjectiveId): Double =
        seen(section, role).takeIf { it > 0 }?.let { (counts[key(section, role, objective)] ?: 0).toDouble() / it } ?: 0.0

    /**
     * The objectives [role] reliably does in [section]: done in at least [MIN_SHARE] of at least [MIN_SECTIONS]
     * sections, in their usual order. Empty when there is not enough to go on.
     */
    fun objectives(section: GoldorSection, role: GoldorRole): List<ObjectiveId> {
        if (seen(section, role) < MIN_SECTIONS) return emptyList()
        return GoldorLayout.stations(section).map { it.objective }.filter { share(section, role, it) >= MIN_SHARE }
            .sortedBy { (orders[key(section, role, it)] ?: 0).toDouble() / (counts[key(section, role, it)] ?: 1) }
    }

    val runs get() = sections.values.maxOrNull() ?: 0

    companion object {
        const val MIN_SECTIONS = 3
        const val MIN_SHARE = 0.6
        private fun sectionKey(section: GoldorSection, role: GoldorRole) = "${section.number}|${role.code}"
        private fun key(section: GoldorSection, role: GoldorRole, objective: ObjectiveId) = "${section.number}|${role.code}|${objective.id}"
    }
}

/**
 * The profile with learned assignments laid over it. A class with a learned sequence takes it in place of the
 * profile's objectives for that section; objectives from other sections (pre-dev) and stack partners are kept. When
 * two classes have learned the same objective outside a stack, the one doing it more often keeps it. Leap rules and
 * early-enters never change: they are strategy, not habit. Berserker's S2 order and S4 lever priorities follow what
 * was learned so the Tank table and lever advice read the right objectives.
 */
fun StrategyProfile.withLearned(learned: LearnedRoles): StrategyProfile {
    if (learned.runs < LearnedRoles.MIN_SECTIONS) return this
    var changed = false
    val sections = sections.mapValues { (section, plans) ->
        val claims = plans.keys.associateWith { learned.objectives(section, it) }.filterValues { it.isNotEmpty() }.toMutableMap()
        // Resolve shared claims; an objective both classes stack in the base profile may stay shared.
        for (objective in claims.values.flatten().distinct()) {
            val claimants = claims.filterValues { objective in it }.keys
            if (claimants.size < 2) continue
            val stacked = claimants.all { objective in plan(section, it).objectives }
            if (stacked) continue
            val keep = claimants.maxBy { learned.share(section, it, objective) }
            for (other in claimants - keep) claims[other] = claims.getValue(other) - objective
        }
        plans.mapValues { (role, plan) ->
            val own = claims[role]?.takeIf { it.isNotEmpty() } ?: return@mapValues plan
            val foreign = plan.objectives.filter { it.section != section }
            val objectives = foreign + own
            if (objectives == plan.objectives) plan
            else { changed = true; plan.copy(objectives = objectives, note = "Learned from ${learned.seen(section, role)} runs") }
        }
    }
    if (!changed) return this
    val learnedProfile = copy(sections = sections)
    val bersS2 = learnedProfile.plan(GoldorSection.S2, GoldorRole.BERSERKER).objectives.filter { it.kind == ObjectiveKind.TERMINAL && it.section == GoldorSection.S2 }
    val levers = s4LeverPriority.keys.associateWith { role ->
        learnedProfile.plan(GoldorSection.S4, role).objectives.firstOrNull { it.kind == ObjectiveKind.LEVER } ?: s4LeverPriority.getValue(role)
    }
    return learnedProfile.copy(title = "$title (learned)", berserkerS2Order = if (bersS2.size == 2) bersS2 else berserkerS2Order, s4LeverPriority = levers)
}

/** Learned roles on disk, one small JSON file. Malformed files are kept aside and learning starts over. */
object GoldorLearning {
    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("goldor-learned.json"), 32768) }
    /** Writes off the client thread; the last state is flushed on exit. */
    private val writer by lazy {
        StateWriter<LearnedRoles>({ file.write(Gson().toJsonTree(it)) }, { action -> Scheduling.schedule(100.milliseconds) { action() } },
            { SkyMyce.logger.warn("Could not save learned Goldor roles", it) })
            .also { created -> Runtime.getRuntime().addShutdownHook(Thread({ runCatching { created.flush() } }, "Sky-Myce-Goldor-Learned-Save")) }
    }
    private var learned = LearnedRoles()
    private var loaded = false

    @Suppress("SENSELESS_COMPARISON")
    fun load(): LearnedRoles {
        if (loaded) return learned
        loaded = true
        learned = runCatching {
            // Gson leaves missing fields null despite the Kotlin types.
            file.read()?.let { json -> Gson().fromJson(json, LearnedRoles::class.java)
                ?.takeIf { it.version == 1 && it.counts != null && it.orders != null && it.sections != null } ?: error("unsupported content") }
        }.getOrElse {
            SkyMyce.logger.warn("Could not read learned Goldor roles; keeping the file aside and starting over", it)
            runCatching { file.preserveOriginal("corrupt-${System.currentTimeMillis()}") }
            null
        } ?: LearnedRoles()
        return learned
    }

    fun record(observation: SectionObservation) {
        val next = load().record(observation)
        if (next == learned) return
        learned = next
        save()
    }

    fun reset() { learned = LearnedRoles(); loaded = true; save() }

    private fun save() = writer.submit(learned)
}
