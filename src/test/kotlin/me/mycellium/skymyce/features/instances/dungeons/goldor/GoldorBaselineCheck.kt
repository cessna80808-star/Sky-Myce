package me.mycellium.skymyce.features.instances.dungeons.goldor

import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.ARCHER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.BERSERKER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.HEALER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.MAGE
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.TANK
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.*

private val G = GoldorBaseline.GOLD_STANDARD
private const val B0 = 2_000_000L
private fun t(section: GoldorSection, n: Int) = ObjectiveId.terminal(section, n)
private fun ll(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.LEFT)
private fun rl(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.RIGHT)

private val names = mapOf(TANK to "Tanky", BERSERKER to "Bers", HEALER to "Healz", MAGE to "Mago", ARCHER to "Arch")

private fun goldRun(profile: StrategyProfile = G): GoldorRunState = GoldorRunState(profile).apply {
    start(B0)
    names.forEach { (role, name) -> updatePlayer(name) { it.copy(role = role, position = BossPoint(110.0, 113.0, 73.0), seenAt = B0) } }
}

private fun GoldorRunState.toSection(target: GoldorSection, now: Long) {
    while (section != target) {
        val stations = GoldorLayout.stations(section)
        stations.forEach { if (state(it.objective).status != ObjectiveStatus.COMPLETE) markComplete(it.objective, "x", StateSource.CHAT, now) }
        chatCompletion("x", ObjectiveKind.LEVER, stations.size, stations.size, now)
        gateOpened(now)
    }
}

private fun rec(state: GoldorRunState, role: GoldorRole, now: Long, leaptAt: Long? = null) =
    GoldorEngine.advise(state, SelfContext(names.getValue(role), leaptAt), now)

private fun checkFidelity() {
    val guide = G.guide!!
    // Every class has its own S1–S4 guide, Healer included.
    for (role in GoldorRole.entries) for (section in listOf(S1, S2, S3, S4))
        check(guide.section(role, section)?.steps?.any { it.kind == StepKind.REQUIRED } == true) { "${role.title} ${section.title} missing" }
    check(guide.sections.values.flatMap { it.values }.flatMap { it.steps }.map { it.id }.let { it.size == it.distinct().size }) { "Step ids are unique" }
    // Route order as supplied.
    check(G.plan(S1, TANK).objectives == listOf(t(S1, 2), t(S1, 1))) { "Tank: T2, then T1" }
    check(G.plan(S1, ARCHER).objectives == listOf(t(S1, 4), t(S1, 3))) { "Archer: T4, then T3" }
    check(G.plan(S1, MAGE).objectives == listOf(ll(S1), rl(S1), ObjectiveId.device(S2))) { "Mage: levers, then the lights" }
    check(G.plan(S1, HEALER).objectives == listOf(ObjectiveId.device(S1)) && G.plan(S1, BERSERKER).objectives == listOf(ObjectiveId.device(S4)))
    check(G.plan(S2, BERSERKER).objectives == listOf(t(S2, 5), t(S2, 3)) && G.plan(S2, ARCHER).objectives == listOf(t(S2, 4), rl(S2)))
    check(G.plan(S2, TANK).objectives == listOf(t(S2, 1), t(S2, 3)) && G.plan(S2, MAGE).objectives == listOf(t(S2, 2)) && G.plan(S2, HEALER).objectives == listOf(ll(S2)))
    check(G.plan(S3, ARCHER).objectives == listOf(t(S3, 4), ll(S3), rl(S3)) && G.plan(S3, TANK).objectives == listOf(t(S3, 1), ll(S3), rl(S3)))
    check(G.plan(S3, HEALER).objectives == listOf(t(S3, 2), ObjectiveId.device(S3)) && ObjectiveId.device(S3) in G.plan(S3, HEALER).backup)
    check(G.plan(S4, BERSERKER).objectives == listOf(t(S4, 3), ll(S4), rl(S4)) && G.plan(S4, HEALER).objectives == listOf(t(S4, 4), rl(S4), ll(S4)))
    check(G.plan(S4, TANK).objectives == listOf(t(S4, 1)) && G.plan(S4, ARCHER).objectives == listOf(t(S4, 2)))
    check(G.assignee(ObjectiveId.device(S3)) == BERSERKER && G.assignee(ll(S4)) == BERSERKER && G.assignee(rl(S4)) == HEALER) { "Backups never own an objective" }
    // Leap targets.
    check(G.sectionLeapRule(S1, TANK)!!.target == MAGE && G.leapRule(S1, BERSERKER)!!.let { it.target == TANK && it.afterObjective == ObjectiveId.device(S4) })
    check(G.sectionLeapRule(S2, BERSERKER)!!.target == HEALER && G.sectionLeapRule(S2, MAGE)!!.route == EarlyEnter.MEE4)
    check(G.leapRule(S2, TANK)!!.target == BERSERKER && G.leapRule(S3, TANK)!!.target == ARCHER)
    check(listOf(S3, S4).all { s -> GoldorRole.entries.all { G.sectionLeapRule(s, it)!!.target == MAGE } }) { "S3 and S4 end at Mage in core" }
    // References stay with their class and section.
    val refs = { role: GoldorRole, section: GoldorSection -> guide.section(role, section)!!.let { g -> g.refs + g.steps.flatMap { it.refs } }.map { it.url }.distinct() }
    check("https://www.youtube.com/watch?v=bRY-iNbZY88" in refs(TANK, S1) && "https://youtu.be/re2FH_y9wNU" in refs(TANK, S4))
    check("https://www.youtube.com/watch?v=xSBzOGSAYxA" in refs(MAGE, S1) && refs(HEALER, S2).count { it.contains("ee3_") } == 2)
    check("https://youtu.be/sMa1KOic9dE" in refs(BERSERKER, S1) && refs(BERSERKER, S3).any { it.contains("hit_head_on_andesite") })
    check("https://www.youtube.com/watch?v=0DKzAuNOKNY&t=171s" in refs(ARCHER, S1))
    val archerS4 = guide.section(ARCHER, S4)!!.refs.single()
    check(archerS4.url == "https://youtu.be/xORRDf-tCqY" && archerS4.note!!.contains("Ignore the early entry")) { "Archer S4 video excludes its early entry" }
    // MEE2 is the baseline; AEE2 is a separate profile where only the EE2 runner and target change.
    val aee2 = GoldorBaseline.GOLD_STANDARD_AEE2
    check(G.routes[EarlyEnter.MEE2] == MAGE && G.routeTitle(EarlyEnter.MEE2) == "MEE2")
    check(aee2.routes[EarlyEnter.MEE2] == ARCHER && aee2.routeTitle(EarlyEnter.MEE2) == "AEE2" && aee2.sectionLeapRule(S1, TANK)!!.target == ARCHER)
    check(aee2.guide!!.section(HEALER, S1)!!.steps.single { it.leapTo != null }.leapTo == ARCHER && aee2.sections.filterKeys { it != S1 } == G.sections.filterKeys { it != S1 })
    check(GoldorProfiles.byId("nope") == G && GoldorProfiles.byId("skymyce-adg") == GoldorProfiles.SKY_MYCE) { "Older profiles stay selectable" }
}

/** The baseline is pinned: editing a step without bumping the version and recording the change fails here. */
private fun checkVersioned() {
    val guide = G.guide!!
    val actual = guideFingerprint(guide.sections)
    check(guide.fingerprint == actual && GoldorBaseline.FINGERPRINT == actual) { "Guide changed: fingerprint $actual. Bump GoldorBaseline.VERSION, add a GuideChange, then update FINGERPRINT." }
    check(guide.version == GoldorBaseline.VERSION && guide.changes.first().status == ChangeStatus.BASELINE && guide.changes.all { it.version <= guide.version })
}

private fun checkTank() {
    // S2: after T1 Tank leaps to Berserker and stacks T3 by default; the decision is shown as a condition.
    val s = goldRun(); s.toSection(S2, B0 + 10_000)
    s.markComplete(t(S2, 1), "Tanky", StateSource.LOCAL, B0 + 11_000)
    val leap = rec(s, TANK, B0 + 11_100)
    check(leap.self!!.leap?.targetName == "Bers" && leap.self.headline == "Leap to Bers") { leap.self.toString() }
    check(leap.guide!!.decision!!.let { (step, value) -> step.id == "tank.s2.right-lever" && value.value == Tri.UNKNOWN }) { leap.guide.toString() }
    check(guideLines(leap.guide, leap.self).any { it.contains("If someone is at T3 and it is not Melody") })
    check(rec(s, TANK, B0 + 12_000, leaptAt = B0 + 11_500).self!!.let { it.objective == t(S2, 3) && it.headline == "T3 · stack Bers" })
    // Someone on T3 with Melody: still stack it.
    s.markActive(t(S2, 3), "Bers", PuzzleType.MELODY, 0, StateSource.SHARED, B0 + 12_000)
    check(s.condition(GuideCondition.T3_TAKEN_NOT_MELODY).value == Tri.FALSE && rec(s, TANK, B0 + 12_100).self!!.headline == "Leap to Bers")
    // Someone on T3 and it is not Melody: the right lever instead, no leap to Berserker.
    val fast = goldRun(); fast.toSection(S2, B0 + 10_000)
    fast.markComplete(t(S2, 1), "Tanky", StateSource.LOCAL, B0 + 11_000)
    fast.markActive(t(S2, 3), "Arch", PuzzleType.PANES, 0, StateSource.SHARED, B0 + 11_000)
    val alt = rec(fast, TANK, B0 + 11_100).self!!
    check(alt.objective == rl(S2) && alt.leap == null && alt.reason.contains("right lever")) { alt }
    // S4: T3 only if Berserker failed i4, which the mod never assumes.
    val s4 = goldRun().apply { markComplete(ObjectiveId.device(S4), "Bers", StateSource.SHARED, B0 + 5_000); toSection(S4, B0 + 40_000) }
    check(s4.condition(GuideCondition.I4_FAILED).value == Tri.FALSE) { "i4 seen done: not failed" }
    val unseen = goldRun().apply { toSection(S4, B0 + 40_000) }
    check(unseen.condition(GuideCondition.I4_FAILED).value == Tri.UNKNOWN) { "Not seen done is not a failure" }
    unseen.markComplete(t(S4, 1), "Tanky", StateSource.LOCAL, B0 + 41_000)
    check(rec(unseen, TANK, B0 + 41_100).self!!.leap?.targetRole == MAGE) { "Unknown: no T3" }
    unseen.confirm(GuideCondition.I4_FAILED, true)
    check(rec(unseen, TANK, B0 + 41_200).self!!.objective == t(S4, 3)) { "Confirmed failure: T3 too" }
}

private fun checkMageAndHealer() {
    // Mage S1: levers, lights, then hold EE2; the left-wall safespot only after an SS reset call.
    val s = goldRun()
    check(rec(s, MAGE, B0 + 500).self!!.objective == ll(S1))
    s.markComplete(ll(S1), "Mago", StateSource.SHARED, B0 + 1_000); s.markComplete(rl(S1), "Mago", StateSource.SHARED, B0 + 1_500)
    val lights = rec(s, MAGE, B0 + 1_600)
    check(lights.self!!.objective == ObjectiveId.device(S2) && lights.guide!!.step!!.id == "mage.s1.lights" && lights.guide.recovery == null) { lights }
    check(lights.guide.reminder!!.text.contains("Spirit, then Phoenix, then Bonzo"))
    s.ssReset(B0 + 2_000)
    val reset = rec(s, MAGE, B0 + 2_100)
    check(reset.guide!!.recovery!!.id == "mage.s1.safespot" && guideLines(reset.guide, reset.self).any { it.startsWith("§c! SS reset called") }) { reset.guide.toString() }
    s.markComplete(ObjectiveId.device(S2), "Mago", StateSource.SHARED, B0 + 2_500)
    check(rec(s, MAGE, B0 + 2_600).let { it.self!!.headline == "Go MEE2" && it.guide!!.step!!.id == "mage.s1.wait" })
    // Healer S1: SS, then leap to the EE2 player.
    s.markComplete(ObjectiveId.device(S1), "Healz", StateSource.SHARED, B0 + 3_000)
    check(rec(s, HEALER, B0 + 3_100).self!!.leap?.targetName == "Mago")
    // Mage S2: T2, then into core and hold it.
    s.toSection(S2, B0 + 10_000)
    check(rec(s, MAGE, B0 + 10_100).self!!.objective == t(S2, 2))
    // Healer S2: the high lever, the right lever only for a slow party, then HEE3.
    check(rec(s, HEALER, B0 + 10_100).self!!.objective == ll(S2))
    s.markComplete(ll(S2), "Healz", StateSource.SHARED, B0 + 11_000)
    check(rec(s, HEALER, B0 + 11_100).self!!.headline == "Go HEE3")
    s.confirm(GuideCondition.SLOW_PARTY, true)
    check(rec(s, HEALER, B0 + 11_200).self!!.objective == rl(S2)) { "Slow party: the right lever too" }
    s.markComplete(t(S2, 2), "Mago", StateSource.SHARED, B0 + 11_500)
    check(rec(s, MAGE, B0 + 11_600).let { it.self!!.headline == "Go MEE4 Core" && it.guide!!.step!!.id == "mage.s2.core" })
    // Healer S3: the device only if Berserker has not done it; the levers only when Archer and Tank are slow.
    s.toSection(S3, B0 + 20_000)
    s.objectives.remove(ObjectiveId.device(S3))
    s.markComplete(t(S3, 2), "Healz", StateSource.SHARED, B0 + 21_000)
    check(rec(s, HEALER, B0 + 21_100).self!!.let { it.objective == ObjectiveId.device(S3) && it.reason.contains("unless Bers") })
    s.markActive(ObjectiveId.device(S3), "Bers", null, 0, StateSource.SHARED, B0 + 21_200)
    check(rec(s, HEALER, B0 + 21_300).self!!.let { it.objective != ObjectiveId.device(S3) && it.leap?.targetRole == MAGE }) { "Berserker is on it: leave it" }
    s.confirm(GuideCondition.TANK_ARCHER_SLOW, true)
    s.objectives.remove(rl(S3)); s.objectives.remove(ll(S3))
    check(rec(s, HEALER, B0 + 21_400).self!!.objective == rl(S3)) { "Levers, right first" }
    // Healer S4: right lever, then the left one unless Berserker has it or it is done.
    s.toSection(S4, B0 + 30_000)
    listOf(t(S4, 4), rl(S4), ll(S4)).forEach { s.objectives.remove(it) }
    s.markComplete(t(S4, 4), "Healz", StateSource.SHARED, B0 + 31_000)
    check(rec(s, HEALER, B0 + 31_100).self!!.headline == "Take the top lever")
    s.markComplete(rl(S4), "Healz", StateSource.SHARED, B0 + 31_500)
    check(rec(s, HEALER, B0 + 31_600).self!!.let { it.objective == ll(S4) && it.reason.contains("unless Bers") }) { rec(s, HEALER, B0 + 31_600).self.toString() }
    s.markComplete(ll(S4), "Bers", StateSource.SHARED, B0 + 32_000)
    check(rec(s, HEALER, B0 + 32_100).self!!.leap?.targetRole == MAGE) { "Done: leap to Mage" }
}

private fun checkBerserkerAndArcher() {
    // Post-i4: Tank first, whether i4 was fast or slow and whether S1 is open; Mage only on the player's say-so.
    for (took in listOf(5_000L, 12_000L)) {
        val s = goldRun()
        s.chatCompletion("Bers", ObjectiveKind.DEVICE, 1, 7, B0 + took)
        val leap = rec(s, BERSERKER, B0 + took + 100).self!!
        check(leap.leap?.targetName == "Tanky" && leap.reason.contains("i4 ${took / 1000}.0 s · S1 open") && leap.reason.contains("Mage only if")) { leap }
        check(rec(s, BERSERKER, B0 + took + 900, leaptAt = B0 + took + 500).self!!.headline == "Stack with Tanky")
        s.confirm(GuideCondition.MAGE_ROUTE_SAFE, true)
        check(rec(s, BERSERKER, B0 + took + 200).self!!.leap?.targetRole == MAGE) { "Confirmed safe: direct Mage leap" }
    }
    // S2: T5 then T3; the right lever only when Archer has Melody (seen from Archer's shared puzzle).
    val s = goldRun(); s.toSection(S2, B0 + 10_000)
    check(rec(s, BERSERKER, B0 + 10_100).self!!.objective == t(S2, 5))
    s.markComplete(t(S2, 5), "Bers", StateSource.SHARED, B0 + 11_000); s.markComplete(t(S2, 3), "Bers", StateSource.SHARED, B0 + 12_000)
    check(s.condition(GuideCondition.ARCHER_MELODY).value == Tri.UNKNOWN && rec(s, BERSERKER, B0 + 12_100).self!!.leap?.targetRole == HEALER)
    s.markActive(t(S2, 4), "Arch", PuzzleType.MELODY, 0, StateSource.SHARED, B0 + 12_200)
    check(rec(s, BERSERKER, B0 + 12_300).self!!.let { it.objective == rl(S2) && it.reason.contains("Archer has Melody") })
    // Archer S2: the pre-leap depends on a Mask, which only the player can confirm.
    s.markComplete(t(S2, 4), "Arch", StateSource.SHARED, B0 + 13_000); s.markComplete(rl(S2), "Arch", StateSource.SHARED, B0 + 13_500)
    val unknown = rec(s, ARCHER, B0 + 13_600).self!!
    check(unknown.leap?.targetName == "Healz" && unknown.leap.plan == null && unknown.confidence == Confidence.LOW && unknown.reason.contains("Mask")) { unknown }
    s.confirm(GuideCondition.MASK_LEFT, false)
    check(rec(s, ARCHER, B0 + 13_700).self!!.headline == "Wait for S2, then leap Healz")
    s.confirm(GuideCondition.MASK_LEFT, true)
    check(rec(s, ARCHER, B0 + 13_800).self!!.leap?.plan != null) { "Mask left: the timed pre-leap" }
    s.confirm(GuideCondition.MASK_LEFT, false)
    s.toSection(S3, B0 + 20_000)
    s.confirm(GuideCondition.MASK_LEFT, null)
    // S3: Berserker T3 then the device; the levers only when Archer and Tank are slow.
    check(rec(s, BERSERKER, B0 + 20_100).self!!.objective == t(S3, 3))
    check(rec(s, ARCHER, B0 + 20_100).self!!.objective == t(S3, 4))
    // Tank: T1, leap Archer, stack the levers.
    s.markComplete(t(S3, 1), "Tanky", StateSource.SHARED, B0 + 21_000)
    check(rec(s, TANK, B0 + 21_100).self!!.leap?.targetName == "Arch")
    check(rec(s, TANK, B0 + 21_600, leaptAt = B0 + 21_500).self!!.headline == "LL · stack Arch")
}

/** Unknown states stay unknown: no condition is true by default and nothing reads as already triggered. */
private fun checkUnknowns() {
    val s = goldRun()
    check(GuideCondition.entries.filter { it != GuideCondition.TANK_ARCHER_SLOW && it != GuideCondition.I4_FAILED }.all { s.condition(it).value == Tri.UNKNOWN })
    check(GuideCondition.entries.filter { it.observability == Observability.CONFIRM }.all { s.condition(it).let { v -> v.value != Tri.TRUE } })
    s.confirm(GuideCondition.SLOW_PARTY, true)
    check(s.condition(GuideCondition.SLOW_PARTY) == ConditionValue(Tri.TRUE, confirmed = true))
    s.reset()
    check(s.confirmations.isEmpty() && s.ssResetAt == null) { "Confirmations are per run" }
    check(GuideCondition.ofId("mask") == GuideCondition.MASK_LEFT && GuideCondition.ofId("x") == null)
    // The decision line reads as a condition until the condition holds.
    val step = G.guide!!.section(TANK, S4)!!.steps.single { it.kind == StepKind.CONDITIONAL }
    check(decisionText(step, ConditionValue(Tri.UNKNOWN)) == "If Berserker failed i4: also do T3")
    check(decisionText(step, ConditionValue(Tri.TRUE, confirmed = true)) == "Berserker failed i4 (you): also do T3")
    // A profile without a guide shows no guide lines; the chat guide says how to get one.
    check(GoldorEngine.advise(GoldorRunState(GoldorProfiles.SKY_MYCE).apply { start(B0); updatePlayer("Tanky") { it.copy(role = TANK) } }, SelfContext("Tanky"), B0).guide == null)
    check(guideText(GoldorProfiles.SKY_MYCE, TANK, S1).single().contains("no step guide"))
    check(guideText(G, HEALER, S2).any { it.startsWith("§6If the party is slow at terminals:") } && guideText(G, TANK, null).size == 5)
}

/** The old default moves to the gold standard once; a later explicit choice of Sky-Myce ADG is kept. */
private fun checkMigration() {
    val config = me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig
    val profile = config.profile; val migrated = config.migratedGoldStandard
    try {
        config.migratedGoldStandard = false; config.profile = me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig.Profile.SKY_MYCE
        config.migrateStrategy()
        check(config.profile.id == GoldorBaseline.ID && config.migratedGoldStandard)
        config.profile = me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig.Profile.SKY_MYCE
        config.migrateStrategy()
        check(config.profile.id == "skymyce-adg") { "Chosen after the migration: kept" }
        check(me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig.Profile.entries.all { GoldorProfiles.byId(it.id).id == it.id }) { "Every setting maps to a profile" }
    } finally { config.profile = profile; config.migratedGoldStandard = migrated }
}

/** Sections switch at n/n once the gate goes, not when the next section's first objective is done. */
private fun checkGateSwitch() {
    val s = goldRun()
    val stations = GoldorLayout.stations(S1)
    stations.dropLast(1).forEach { s.markComplete(it.objective, "x", StateSource.CHAT, B0 + 1_000) }
    s.chatCompletion("x", ObjectiveKind.LEVER, 7, 7, B0 + 2_000)
    check(s.section == S1 && s.sectionDoneAt != null)
    s.gateOpened(B0 + 2_100)
    check(s.section == S2 && s.gateOpenedAt == B0 + 2_100) { "n/n plus the gate line: S2 at once" }
    // A gate line just before the last completion still opens this gate.
    GoldorLayout.stations(S2).dropLast(1).forEach { s.markComplete(it.objective, "x", StateSource.CHAT, B0 + 3_000) }
    s.gateOpened(B0 + 3_500)
    check(s.section == S2) { "No switch before n/n" }
    s.chatCompletion("x", ObjectiveKind.LEVER, 8, 8, B0 + 4_000)
    check(s.section == S3) { "The held gate line applies at n/n" }
    // No gate line at all: the gate opens by itself after its delay.
    GoldorLayout.stations(S3).forEach { s.markComplete(it.objective, "x", StateSource.CHAT, B0 + 5_000) }
    s.chatCompletion("x", ObjectiveKind.LEVER, 7, 7, B0 + 5_000)
    s.gateTimeout(B0 + 7_000); check(s.section == S3)
    s.gateTimeout(B0 + 5_000 + G.objectives.gateDelayMillis + 1_000); check(s.section == S4)
    check(GOLDOR_GATE_LINES.size == 3 && GOLDOR_GATE_LINES.all { it.startsWith("[BOSS] Goldor: ") })
    check(me.mycellium.skymyce.features.instances.dungeons.splits.terminalProgress.matchEntire("casper_0302 activated a terminal! (7/7) (24.116s | 24.116s)")?.groupValues?.get(2) == "7") { "Timing suffixes from other mods are ignored" }
}

internal fun checkGoldStandard() {
    checkGateSwitch()
    checkFidelity(); checkVersioned(); checkTank(); checkMageAndHealer(); checkBerserkerAndArcher(); checkUnknowns(); checkMigration()
}
