package me.mycellium.skymyce.features.instances.dungeons.goldor

/**
 * How a guide step relates to the route. [PREP] and [REMINDER] are loadout and safety notes; [REQUIRED] steps are
 * the route itself; [CONDITIONAL] steps apply only when their condition holds; [OPTIONAL] steps are allowed but
 * never asked for; [RECOVERY] steps are for when something went wrong.
 */
enum class StepKind(val title: String) {
    PREP("Prep"), REQUIRED("Step"), CONDITIONAL("If"), OPTIONAL("Optional"), RECOVERY("Recovery"), REMINDER("Reminder")
}

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
