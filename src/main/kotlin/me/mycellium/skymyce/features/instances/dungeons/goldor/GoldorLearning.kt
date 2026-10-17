package me.mycellium.skymyce.features.instances.dungeons.goldor

import com.google.gson.Gson
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile

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
    var learned = LearnedRoles()
        private set
    private var loaded = false

    fun load(): LearnedRoles {
        if (loaded) return learned
        loaded = true
        learned = runCatching {
            file.read()?.let { json -> Gson().fromJson(json, LearnedRoles::class.java) }
                ?.takeIf { it.version == 1 && it.counts != null && it.orders != null && it.sections != null }
        }.getOrNull() ?: LearnedRoles()
        return learned
    }

    fun record(observation: SectionObservation) {
        val next = load().record(observation)
        if (next == learned) return
        learned = next
        save()
    }

    fun reset() { learned = LearnedRoles(); loaded = true; save() }

    private fun save() {
        runCatching { file.write(Gson().toJsonTree(learned) as JsonObject) }
            .onFailure { SkyMyce.logger.warn("Could not save learned Goldor roles", it) }
    }
}
