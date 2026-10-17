package me.mycellium.skymyce.features.instances.dungeons.goldor

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

/** What one role should do in a section: its objectives in order, and who it stacks with. */
data class RolePlan(val objectives: List<ObjectiveId> = emptyList(), val stackWith: GoldorRole? = null, val note: String? = null)

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
)

/** What Tank does in S2, decided by Berserker's T4 (first) and T3 (second) puzzle speeds. */
enum class TankS2Action(val title: String) { STACK_T3("Stack T3 with Berserker"), PRE_LEAP("Prepare pre-leap"), HOLD("Hold — waiting for T4 type") }

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
    /** In S3 these two finish one terminal each, then stack the device. */
    val s3DeviceStack: Pair<GoldorRole, GoldorRole>,
    /** In S4 each role's lever priority. Priorities are fixed; position never swaps them. */
    val s4LeverPriority: Map<GoldorRole, ObjectiveId>,
    val puzzles: PuzzleTiming,
    val leap: LeapTiming,
    val tick: TickRule,
    val objectives: ObjectiveTiming,
) {
    fun plan(section: GoldorSection, role: GoldorRole) = sections[section]?.get(role) ?: RolePlan()
    fun assignee(objective: ObjectiveId): GoldorRole? =
        sections[objective.section]?.entries?.firstOrNull { objective in it.value.objectives }?.key
    fun leapRule(section: GoldorSection, role: GoldorRole) = leaps[section]?.let { it[role] ?: it[null] }
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

    val all = listOf(SKY_MYCE, ADG_2026)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: SKY_MYCE
}
