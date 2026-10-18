package me.mycellium.skymyce.features.instances.dungeons.goldor

import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.*

private val P = GoldorProfiles.SKY_MYCE
private fun t(section: GoldorSection, n: Int) = ObjectiveId.terminal(section, n)
private const val T0 = 1_000_000L

/** A started run with the five classes, all seen at [where] (default: the S1 Tank terminals). */
private fun run(profile: StrategyProfile = P, where: BossPoint = BossPoint(110.0, 113.0, 73.0)): GoldorRunState = GoldorRunState(profile).apply {
    start(T0)
    listOf("Tanky" to GoldorRole.TANK, "Bers" to GoldorRole.BERSERKER, "Healz" to GoldorRole.HEALER, "Mago" to GoldorRole.MAGE, "Arch" to GoldorRole.ARCHER)
        .forEach { (name, role) -> updatePlayer(name) { it.copy(role = role, position = where, seenAt = T0) } }
}
private fun GoldorRunState.seen(name: String, at: BossPoint, now: Long) = updatePlayer(name) { it.copy(position = at, seenAt = now) }
private fun GoldorRunState.station(objective: ObjectiveId) = GoldorLayout.station(objective)!!.points.first()
/** Completes every objective of the current section through Hypixel lines and opens the gate. */
private fun GoldorRunState.finishSection(now: Long) {
    val stations = GoldorLayout.stations(section)
    stations.forEachIndexed { index, s ->
        if (state(s.objective).status != ObjectiveStatus.COMPLETE) markComplete(s.objective, "x", StateSource.CHAT, now)
    }
    chatCompletion("x", ObjectiveKind.LEVER, stations.size, stations.size, now)
    gateOpened(now)
}
private fun advise(state: GoldorRunState, self: String, now: Long, leaptAt: Long? = null, position: BossPoint? = null) =
    GoldorEngine.advise(state, SelfContext(self, leaptAt, position), now)

private fun checkModel() {
    check(PuzzleType.ofTitle("§aClick the button on time!") == PuzzleType.MELODY)
    check(PuzzleType.ofTitle("Click in order!") == PuzzleType.NUMBERS && PuzzleType.ofTitle("Change all to same color!") == PuzzleType.RUBIKS)
    check(PuzzleType.ofTitle("What starts with: 'B'?") == PuzzleType.STARTS_WITH && PuzzleType.ofTitle("Select all the LIGHT BLUE items!") == PuzzleType.SELECT_ALL)
    check(PuzzleType.ofTitle("Correct all the panes!") == PuzzleType.PANES && PuzzleType.ofTitle("Large Chest") == null)
    check(GoldorRole.ofName("Berserk") == GoldorRole.BERSERKER && GoldorRole.ofName("Mage") == GoldorRole.MAGE && GoldorRole.ofName("Bob") == null)
    check(GoldorLayout.total(S2) == 8 && GoldorLayout.total(S1) == 7 && GoldorLayout.total(S3) == 7 && GoldorLayout.total(S4) == 7) { "Hypixel's (n/N) counts" }
    check(ObjectiveId.lever(S4, LeverSide.RIGHT).label == "Top" && ObjectiveId.lever(S4, LeverSide.LEFT).label == "Bot" && ObjectiveId.lever(S1, LeverSide.LEFT).label == "LL")
    check(GoldorLayout.station(ObjectiveId.lever(S4, LeverSide.RIGHT))!!.points.first().y > GoldorLayout.station(ObjectiveId.lever(S4, LeverSide.LEFT))!!.points.first().y) { "S4 right is the top lever" }
    check(puzzleQuarters(PuzzleType.NUMBERS, List(5) { "minecraft:lime_stained_glass_pane" } + List(5) { "minecraft:red_stained_glass_pane" }) == 2)
    check(puzzleQuarters(PuzzleType.MELODY, listOf("minecraft:lime_stained_glass_pane")) == null)
    // Every profile objective exists on the map, and every objective is assigned once per section.
    for (profile in GoldorProfiles.all) for ((section, plans) in profile.sections) {
        val ids = plans.values.flatMap { it.objectives }.filter { it.section == section }
        check(ids.all { GoldorLayout.station(it) != null }) { "${profile.id} $section has unknown objectives" }
        // Only stack partners or a backup share an objective (the S3 device, the S4 levers).
        for (id in ids.distinct()) {
            val owners = plans.filter { id in it.value.objectives }
            check(owners.size == 1 || owners.size == 2 && (owners.values.any { plan -> plan.stackWith in owners.keys } || owners.values.any { id in it.backup }))
                { "${profile.id} $section assigns $id twice" }
        }
    }
}

private fun checkPuzzles() {
    val timing = P.puzzles
    check(timing.speed(PuzzleType.MELODY) == PuzzleSpeed.SLOW && timing.speed(PuzzleType.NUMBERS) == PuzzleSpeed.SLOW && timing.speed(PuzzleType.RUBIKS) == PuzzleSpeed.SLOW)
    check(listOf(PuzzleType.STARTS_WITH, PuzzleType.SELECT_ALL, PuzzleType.PANES).all { timing.speed(it) == PuzzleSpeed.FAST })
    val slowest = PuzzleType.entries.maxBy(timing::millis)
    check(slowest == PuzzleType.MELODY && timing.millis(PuzzleType.MELODY) > timing.millis(PuzzleType.NUMBERS) && timing.millis(PuzzleType.MELODY) > timing.millis(PuzzleType.RUBIKS)) { "Melody weighs most" }
    // Remaining work: progress removes its share; time spent counts but a started terminal keeps a quarter until done.
    check(remainingWork(TerminalWork(PuzzleType.MELODY), timing, T0).millis == timing.millis(PuzzleType.MELODY))
    check(remainingWork(TerminalWork(PuzzleType.NUMBERS, quarters = 2), timing, T0).millis == timing.millis(PuzzleType.NUMBERS) / 2)
    check(remainingWork(TerminalWork(PuzzleType.MELODY, startedAt = T0 - 60_000), timing, T0).millis == timing.millis(PuzzleType.MELODY) / 4)
    check(remainingWork(TerminalWork(null), timing, T0).confidence == Confidence.LOW) { "Unknown puzzles are low confidence" }
    check(remainingWork(TerminalWork(PuzzleType.PANES, complete = true), timing, T0) == Estimate.ZERO)
}

private fun checkS1() {
    val s = run()
    val bersPlan = P.plan(S1, GoldorRole.BERSERKER)
    check(bersPlan.stackWith == GoldorRole.TANK && bersPlan.objectives == listOf(ObjectiveId.device(S4))) { "Berserker pre-devs i4 and stacks Tank" }
    val before = advise(s, "Bers", T0 + 1_000).self!!
    // Tank's terminal types never change Berserker's S1 plan.
    s.markActive(t(S1, 1), "Tanky", PuzzleType.MELODY, 0, StateSource.SHARED, T0 + 1_000)
    check(advise(s, "Bers", T0 + 1_000).self == before && before.headline.contains("stack Tanky")) { before.headline }
    // The device line during S1 is the i4 pre-dev, then Berserker always leaps to Tank…
    check(s.chatCompletion("Bers", ObjectiveKind.DEVICE, 1, 7, T0 + 2_000) == ObjectiveId.device(S4))
    val leap = advise(s, "Bers", T0 + 2_100).self!!
    check(leap.leap?.targetName == "Tanky" && leap.leap.mandatory && leap.headline == "Leap to Tanky") { leap }
    // …even when another player is closer.
    s.seen("Healz", s.station(ObjectiveId.device(S4)), T0 + 2_100)
    check(advise(s, "Bers", T0 + 2_100).self!!.leap?.targetName == "Tanky")
    // After the leap it stacks with Tank.
    check(advise(s, "Bers", T0 + 3_000, leaptAt = T0 + 2_500).self!!.headline == "Stack with Tanky")
    // S2 follow-up: T4, then T3.
    check(P.plan(S2, GoldorRole.BERSERKER).objectives == listOf(t(S2, 4), t(S2, 3)) && P.berserkerS2Order == listOf(t(S2, 4), t(S2, 3)))
    check(GoldorProfiles.ADG_2026.plan(S2, GoldorRole.BERSERKER).objectives == listOf(t(S2, 3), t(S2, 5))) { "The ADG preset stays a separate profile" }
}

/** A run in S2 with Tank's own terminal done and Berserker's two terminals of the given puzzles. */
private fun s2(first: PuzzleType?, second: PuzzleType?): GoldorRunState = run().apply {
    finishSection(T0 + 10_000)
    check(section == S2)
    markComplete(t(S2, 1), "Tanky", StateSource.LOCAL, T0 + 11_000)
    if (first != null) markActive(t(S2, 4), "Bers", first, 0, StateSource.SHARED, T0 + 11_000)
    if (second != null) markActive(t(S2, 3), "Bers", second, 0, StateSource.SHARED, T0 + 11_000)
    seen("Tanky", station(t(S2, 1)), T0 + 11_500); seen("Healz", BossPoint(2.5, 109.0, 104.5), T0 + 11_500)
    seen("Arch", station(t(S2, 5)), T0 + 11_500); seen("Bers", station(t(S2, 4)), T0 + 11_500)
    setRoute(EarlyEnter.HEE3, RouteState.IN, StateSource.SHARED, T0 + 11_500)
}

private fun checkS2Table() {
    val slow = listOf(PuzzleType.MELODY, PuzzleType.NUMBERS, PuzzleType.RUBIKS)
    val fast = listOf(PuzzleType.STARTS_WITH, PuzzleType.SELECT_ALL, PuzzleType.PANES)
    // The table covers every combination, including unknown types.
    val speeds = listOf(PuzzleSpeed.SLOW, PuzzleSpeed.FAST, null)
    for (a in speeds) for (b in speeds) check((a to b) in P.tankS2Table) { "Missing S2 row $a/$b" }
    check(P.tankS2(PuzzleSpeed.SLOW, PuzzleSpeed.SLOW).action == TankS2Action.STACK_T3 && P.tankS2(PuzzleSpeed.SLOW, PuzzleSpeed.FAST).action == TankS2Action.STACK_T3)
    check(P.tankS2(PuzzleSpeed.FAST, PuzzleSpeed.SLOW).action == TankS2Action.PRE_LEAP && P.tankS2(PuzzleSpeed.FAST, PuzzleSpeed.FAST).action == TankS2Action.PRE_LEAP)
    check(P.tankS2(null, PuzzleSpeed.FAST).action == TankS2Action.HOLD) { "T4 decides first; without it Tank holds" }
    check(P.tankS2(PuzzleSpeed.SLOW, PuzzleSpeed.SLOW).extraDelayMillis > P.tankS2(PuzzleSpeed.FAST, PuzzleSpeed.SLOW).extraDelayMillis &&
        P.tankS2(PuzzleSpeed.FAST, PuzzleSpeed.SLOW).extraDelayMillis > 0) { "Slow T3 delays; both slow delays most" }
    check(P.tankS2Reason(P.tankS2(PuzzleSpeed.FAST, PuzzleSpeed.SLOW)) == "T3 slow — delay")
    check(GoldorProfiles.ADG_2026.tankS2Reason(GoldorProfiles.ADG_2026.tankS2(PuzzleSpeed.SLOW, PuzzleSpeed.FAST)) == "T3 slow — stack T5") { "Labels follow the profile" }

    val now = T0 + 11_600
    for (a in slow) for (b in slow + fast) {
        val rec = advise(s2(a, b), "Tanky", now).self!!
        check(rec.headline == "Stack T3 with Bers" && rec.objective == t(S2, 3)) { "$a/$b → ${rec.headline}" }
    }
    for (b in slow) {
        val rec = advise(s2(PuzzleType.PANES, b), "Tanky", now).self!!
        check(rec.leap?.targetName == "Healz" && rec.reason.startsWith("T3 slow — delay")) { "fast/$b → $rec" }
    }
    for (b in fast) {
        val rec = advise(s2(PuzzleType.STARTS_WITH, b), "Tanky", now).self!!
        check(rec.leap?.targetName == "Healz" && rec.reason.startsWith("T4/T3 fast")) { "fast/$b → $rec" }
    }
    check(advise(s2(null, null), "Tanky", now).self!!.headline.startsWith("Hold")) { "Unknown T4: hold" }
    // A slow T3 keeps the pre-leap but pushes it later; Melody pushes it furthest.
    val fastPlan = advise(s2(PuzzleType.PANES, PuzzleType.PANES), "Tanky", now).self!!.leap!!.plan!!
    val numbersPlan = advise(s2(PuzzleType.PANES, PuzzleType.NUMBERS), "Tanky", now).self!!.leap!!.plan!!
    val melodyPlan = advise(s2(PuzzleType.PANES, PuzzleType.MELODY), "Tanky", now).self!!.leap!!.plan!!
    check(fastPlan.opensAt!! < numbersPlan.opensAt!! && numbersPlan.opensAt < melodyPlan.opensAt!!) { "${fastPlan.opensAt} ${numbersPlan.opensAt} ${melodyPlan.opensAt}" }
    // Finished terminals stop counting as slow.
    val done = s2(PuzzleType.MELODY, PuzzleType.MELODY).apply { markComplete(t(S2, 4), "Bers", StateSource.SHARED, now); markComplete(t(S2, 3), "Bers", StateSource.SHARED, now) }
    check(advise(done, "Tanky", now).self!!.leap != null)
}

private fun checkPreLeapFallback() {
    val now = T0 + 11_600
    // HEE3 is a mandatory priority: Healer is the target even when Archer is closer to S3 T1…
    val s = s2(PuzzleType.PANES, PuzzleType.PANES)
    s.seen("Arch", s.station(t(S3, 1)), now)
    check(advise(s, "Tanky", now).self!!.leap?.targetName == "Healz")
    // …and stays the target when Healer's position is unknown.
    s.updatePlayer("Healz") { it.copy(seenAt = now - 10_000) }
    val unknown = advise(s, "Tanky", now).self!!
    check(unknown.leap?.targetName == "Healz" && unknown.leap.plan?.phase == LeapPhase.UNCERTAIN) { "Location uncertainty keeps the role: $unknown" }
    // Only an abandoned route or a dead Healer opens the proximity fallback to S3 T1.
    s.setRoute(EarlyEnter.HEE3, RouteState.ABANDONED, StateSource.SHARED, now)
    s.updatePlayer("Healz") { it.copy(seenAt = now, position = BossPoint(60.0, 109.0, 125.0)) }
    check(advise(s, "Tanky", now).self!!.leap?.targetName == "Arch") { "Closest of Healer/Archer to S3 T1" }
    s.seen("Arch", BossPoint(110.0, 113.0, 73.0), now)
    check(advise(s, "Tanky", now).self!!.leap?.targetName == "Healz") { "The target changes when positions change" }
}

private fun checkS3S4() {
    val s = run()
    s.finishSection(T0 + 10_000); s.finishSection(T0 + 20_000)
    check(s.section == S3)
    check(P.s3DeviceStack == (GoldorRole.BERSERKER to GoldorRole.HEALER))
    s.markActive(t(S3, 3), "Bers", PuzzleType.PANES, 0, StateSource.SHARED, T0 + 21_000)
    s.markActive(t(S3, 2), "Healz", PuzzleType.MELODY, 0, StateSource.SHARED, T0 + 21_000)
    val arrival = GoldorEngine.deviceArrival(s, T0 + 21_000)!!
    check(arrival.first == "Bers" && arrival.second == "Healz") { "A fast terminal gets Berserker to the device first: $arrival" }
    s.markComplete(t(S3, 3), "Bers", StateSource.SHARED, T0 + 22_000)
    check(advise(s, "Bers", T0 + 22_000).self!!.headline == "Stack device with Healz")
    // Unknown puzzle for Healer: no invented type, estimate marked low.
    val unknown = run().apply { finishSection(T0 + 10_000); finishSection(T0 + 20_000) }
    check(GoldorEngine.deviceArrival(unknown, T0 + 21_000)!!.firstEta.confidence == Confidence.LOW)
    check(unknown.state(t(S3, 2)).puzzle == null)

    s.markComplete(ObjectiveId.device(S3), "Bers", StateSource.SHARED, T0 + 23_000)
    s.finishSection(T0 + 30_000)
    check(s.section == S4)
    s.markComplete(t(S4, 3), "Bers", StateSource.SHARED, T0 + 31_000)
    s.markComplete(t(S4, 4), "Healz", StateSource.SHARED, T0 + 31_000)
    check(advise(s, "Bers", T0 + 31_000).self!!.headline == "Take the bottom lever")
    check(advise(s, "Healz", T0 + 31_000).self!!.headline == "Take the top lever")
    // Standing at the other lever never swaps the priority.
    s.seen("Healz", s.station(ObjectiveId.lever(S4, LeverSide.LEFT)), T0 + 31_000)
    check(advise(s, "Healz", T0 + 31_000).self!!.headline == "Take the top lever")
}

private fun checkRoutes() {
    check(P.routes == mapOf(EarlyEnter.MEE2 to GoldorRole.MAGE, EarlyEnter.HEE3 to GoldorRole.HEALER, EarlyEnter.MEE4 to GoldorRole.MAGE))
    check(P.leapRule(S1, GoldorRole.ARCHER)!!.let { it.target == GoldorRole.MAGE && it.mandatory && it.route == EarlyEnter.MEE2 })
    check(P.leapRule(S2, GoldorRole.ARCHER)!!.let { it.target == GoldorRole.HEALER && it.mandatory && it.route == EarlyEnter.HEE3 })
    check(P.leapRule(S4, GoldorRole.TANK)!!.let { it.target == GoldorRole.MAGE && it.mandatory && it.route == EarlyEnter.MEE4 })
    // Archer done in S1 → leap Mage (MEE2), whether Mage is seen or not.
    val s = run()
    listOf(t(S1, 3), t(S1, 4)).forEach { s.markComplete(it, "Arch", StateSource.CHAT, T0 + 5_000) }
    val rec = advise(s, "Arch", T0 + 5_000).self!!
    check(rec.leap?.targetName == "Mago" && rec.leap.mandatory && rec.reason.startsWith("MEE2")) { rec }
    s.updatePlayer("Mago") { it.copy(seenAt = T0 - 60_000) }
    check(advise(s, "Arch", T0 + 5_000).self!!.leap?.targetName == "Mago") { "A stale position keeps the MEE2 priority" }
    check(advise(s, "Arch", T0 + 5_000).routes.getValue(EarlyEnter.MEE2) == "position unknown")
    // Entering: a position at the EE2 spot is "in".
    s.seen("Mago", GoldorLayout.routePoints.getValue(EarlyEnter.MEE2), T0 + 5_000)
    s.setRoute(EarlyEnter.MEE2, RouteState.IN, StateSource.INFERRED, T0 + 5_000)
    check(advise(s, "Arch", T0 + 5_000).routes.getValue(EarlyEnter.MEE2) == "in")
    // In never goes back to going; abandoned is final.
    s.setRoute(EarlyEnter.MEE2, RouteState.GOING, StateSource.SHARED, T0 + 6_000)
    check(s.route(EarlyEnter.MEE2).state == RouteState.IN)
    s.setRoute(EarlyEnter.MEE2, RouteState.ABANDONED, StateSource.SHARED, T0 + 7_000)
    s.setRoute(EarlyEnter.MEE2, RouteState.IN, StateSource.SHARED, T0 + 8_000)
    check(s.route(EarlyEnter.MEE2).state == RouteState.ABANDONED)
    // Deviation: a dead runner shows as such, the assignment stays in the profile.
    s.updatePlayer("Healz") { it.copy(alive = false) }
    check(advise(s, "Arch", T0 + 8_000).routes.getValue(EarlyEnter.HEE3) == "Healer dead" && P.routes[EarlyEnter.HEE3] == GoldorRole.HEALER)
}

private fun checkTiming() {
    val timing = P.leap
    val tick = GoldorTick(T0, 3_000, observed = true)
    check(tick.nextAfter(T0 + 100) == T0 + 3_000 && tick.lastAtOrBefore(T0 + 5_900) == T0 + 3_000 && tick.nextAfter(T0 - 10) == T0)
    val travel = 2_000L
    // Opening in 10 s: leap after the last tick that still leaves time to leap, travel and keep the margin.
    val plan = planPreLeap(T0, Estimate(10_000, Confidence.HIGH), tick, timing, travel)
    val latest = T0 + 10_000 - timing.leapCastMillis - travel - timing.safetyMarginMillis
    check(plan.tickAt == T0 + 6_000 && plan.leapFrom == T0 + 6_000 + timing.leapDelayAfterTickMillis) { plan }
    check(plan.leapFrom!! <= latest && plan.leapUntil!! <= latest && plan.phase == LeapPhase.WAITING_TICK) { "Tick, leap, travel and arrival form one sequence" }
    check(plan.leapUntil!! + timing.leapCastMillis + travel + timing.safetyMarginMillis <= plan.opensAt!!) { "Arrives before the section opens" }
    // Phases as time passes.
    check(planPreLeap(T0 + 5_000, Estimate(5_000, Confidence.HIGH), tick, timing, travel).phase == LeapPhase.PREPARE)
    check(planPreLeap(T0 + 6_200, Estimate(3_800, Confidence.HIGH), tick, timing, travel).phase == LeapPhase.LEAP)
    // An earlier tick than expected moves the window earlier; a later one later.
    val early = planPreLeap(T0, Estimate(10_000, Confidence.HIGH), GoldorTick(T0 - 1_000, 3_000, true), timing, travel)
    val late = planPreLeap(T0, Estimate(10_000, Confidence.HIGH), GoldorTick(T0 + 1_000, 3_000, true), timing, travel)
    check(early.tickAt!! < late.tickAt!!) { "${early.tickAt} ${late.tickAt}" }
    // The section opening sooner than planned: the window shrinks, then is missed.
    check(planPreLeap(T0, Estimate(2_800, Confidence.HIGH), GoldorTick(T0 - 200, 3_000, true), timing, travel).phase == LeapPhase.LEAP) { "Just after a tick: leap" }
    planPreLeap(T0, Estimate(2_800, Confidence.HIGH), GoldorTick(T0 - 2_000, 3_000, true), timing, travel).let {
        check(it.phase == LeapPhase.MISSED && it.reason == "no safe tick before opening") { "The fitting tick passed and another lands before opening: $it" }
    }
    check(planPreLeap(T0, Estimate(1_000, Confidence.HIGH), tick, timing, travel).phase == LeapPhase.MISSED)
    // Opening later: the plan simply moves to a later tick.
    check(planPreLeap(T0, Estimate(16_000, Confidence.HIGH), tick, timing, travel).tickAt == T0 + 12_000)
    // Not enough information: never a false guarantee.
    check(planPreLeap(T0, null, tick, timing, travel).phase == LeapPhase.UNCERTAIN)
    check(planPreLeap(T0, Estimate(10_000, Confidence.HIGH), tick, timing, null).phase == LeapPhase.UNCERTAIN)
    check(planPreLeap(T0, Estimate(10_000, Confidence.HIGH), GoldorTick(null, 3_000, false), timing, travel).phase == LeapPhase.UNCERTAIN)
    check(planPreLeap(T0, Estimate(10_000, Confidence.LOW), tick, timing, travel).confidence == Confidence.LOW)
    check(timingLine(planPreLeap(T0, Estimate(10_000, Confidence.LOW), tick, timing, travel), T0)!!.contains("~")) { "Low confidence shows no exact countdown" }
    check(planPreLeap(T0, Estimate(10_000, Confidence.HIGH), GoldorTick(T0, 3_000, false), timing, travel).confidence == Confidence.MEDIUM) { "An unobserved tick lowers confidence" }
    // After leaping and arriving.
    check(planPreLeap(T0, Estimate(10_000, Confidence.HIGH), tick, timing, travel, leapt = true).phase == LeapPhase.TRAVELLING)
    check(planPreLeap(T0, Estimate(10_000, Confidence.HIGH), tick, timing, travel, arrived = true).phase == LeapPhase.ARRIVED)
}

private fun checkProtocol() {
    val events = listOf(GoldorEvent.Role(GoldorRole.BERSERKER), GoldorEvent.Working(t(S2, 4), PuzzleType.MELODY, 1), GoldorEvent.Completed(t(S2, 4)))
    val text = GoldorProtocol.encode(4, events)
    check(text == "smt1:4:rb/t24m1/c24" && text.length <= GoldorProtocol.MAX_LENGTH) { text }
    check(GoldorProtocol.decode(text) == GoldorProtocol.Decoded(4, events))
    check(GoldorProtocol.decode("smt1:z:l4r/d3a/e3i/q/p5")!!.events == listOf(GoldorEvent.Lever(ObjectiveId.lever(S4, LeverSide.RIGHT)),
        GoldorEvent.Device(S3, false), GoldorEvent.Route(EarlyEnter.HEE3, RouteState.IN), GoldorEvent.SyncRequest, GoldorEvent.Section(CORE)))
    check(GoldorProtocol.decode("smt1:0:t24u0")!!.events.single() == GoldorEvent.Working(t(S2, 4), null, 0)) { "Unknown puzzle stays unknown" }
    // Anything else is rejected whole: lookalikes, extra text, bad enums, duplicates, too many events, wrong version.
    listOf("smt1: hello", "smt1:4:rb hi", "smt1:4:rx", "smt1:4:t64m0", "smt1:4:t25m4", "smt1:4:c56", "smt1:4:RB", "smt2:4:rb", "smt1:44:rb",
        "smt1:4:rb/rb", "smt1:4:q/q", "smt1:4:p1/p2/p3/p4/p5/p1", "smt1:4:e5i", "smt1:4:l4t", "please run /pc smt1:4:rb", "smt1:4:", "smt1:4:/rb")
        .forEach { check(GoldorProtocol.decode(it) == null) { "Accepted $it" } }
    check(GoldorProtocol.looksLikeProtocol("smt1:4:rb") && !GoldorProtocol.looksLikeProtocol("hey smt1:4:rb"))

    // Dedupe: exact repeats and our own messages are dropped; another sender's identical message is kept.
    val dedupe = ProtocolDedupe(windowMillis = 30_000)
    check(dedupe.accept("Bers", "Me", text, T0) && !dedupe.accept("bers", "Me", text, T0 + 1) && !dedupe.accept("Me", "Me", "smt1:5:rt", T0))
    check(dedupe.accept("Healz", "Me", text, T0 + 2) && dedupe.accept("Bers", "Me", text, T0 + 40_000)) { "Repeats are only dropped within the window" }

    // Outbox: rate limited, coalesced, never repeats an unchanged state.
    val outbox = ProtocolOutbox(minIntervalMillis = 1_500, perMinute = 3)
    outbox.offer(GoldorEvent.Role(GoldorRole.TANK))
    outbox.offer(GoldorEvent.Working(t(S2, 1), PuzzleType.NUMBERS, 0))
    outbox.offer(GoldorEvent.Working(t(S2, 1), PuzzleType.NUMBERS, 2))
    check(outbox.size == 2) { "A newer progress replaces the unsent one" }
    check(outbox.poll(T0) == "smt1:0:rt/t21n2")
    outbox.offer(GoldorEvent.Role(GoldorRole.TANK))
    check(outbox.size == 0) { "An unchanged state is not sent again" }
    outbox.offer(GoldorEvent.Completed(t(S2, 1)))
    check(outbox.poll(T0 + 500) == null && outbox.poll(T0 + 1_500) == "smt1:1:c21") { "One message per 1.5 s" }
    outbox.offer(GoldorEvent.Section(S3)); check(outbox.poll(T0 + 3_000) == "smt1:2:p3")
    outbox.offer(GoldorEvent.Section(S4)); check(outbox.poll(T0 + 5_000) == null) { "At most three per minute" }
    check(outbox.poll(T0 + 60_001) == "smt1:3:p4")
    outbox.resendable(listOf("r")); outbox.offer(GoldorEvent.Role(GoldorRole.TANK)); check(outbox.size == 1) { "A sync reply can resend" }
    // Packing respects the length limit.
    val big = ProtocolOutbox(minIntervalMillis = 0, perMinute = 100)
    (1..4).forEach { big.offer(GoldorEvent.Working(t(S2, it), PuzzleType.PANES, 0)) }
    big.offer(GoldorEvent.Completed(t(S2, 5))); big.offer(GoldorEvent.Role(GoldorRole.MAGE))
    val first = big.poll(T0)!!
    check(first.length <= GoldorProtocol.MAX_LENGTH && GoldorProtocol.decode(first) != null && big.size > 0) { first }
}

private fun checkStateUpdates() {
    val s = run()
    // Position match: the line names a player near S1 T3.
    s.seen("Arch", s.station(t(S1, 3)), T0 + 1_000)
    check(s.chatCompletion("Arch", ObjectiveKind.TERMINAL, 1, 7, T0 + 1_000) == t(S1, 3) && !s.state(t(S1, 3)).ambiguous)
    // No position: assignment order, marked as a guess (non-SM, unobserved player).
    s.updatePlayer("Arch") { it.copy(seenAt = T0 - 10_000) }
    check(s.chatCompletion("Arch", ObjectiveKind.TERMINAL, 2, 7, T0 + 2_000) == t(S1, 4) && s.state(t(S1, 4)).ambiguous)
    // Tank, unobserved, completes; guessed as T1. Its SM client then says it was T2: the guess moves.
    s.updatePlayer("Tanky") { it.copy(seenAt = T0 - 10_000) }
    check(s.chatCompletion("Tanky", ObjectiveKind.TERMINAL, 3, 7, T0 + 3_000) == t(S1, 1) && s.state(t(S1, 1)).ambiguous)
    s.exactComplete(t(S1, 2), "Tanky", T0 + 3_100)
    check(s.state(t(S1, 2)).let { it.status == ObjectiveStatus.COMPLETE && !it.ambiguous } && s.state(t(S1, 1)).status == ObjectiveStatus.OPEN)
    // A shared "working" after a completion does not reopen it; progress never goes down.
    s.markActive(t(S1, 2), "Tanky", PuzzleType.PANES, 0, StateSource.SHARED, T0 + 3_200)
    check(s.state(t(S1, 2)).status == ObjectiveStatus.COMPLETE)
    s.markActive(t(S1, 1), "Tanky", PuzzleType.NUMBERS, 3, StateSource.SHARED, T0 + 3_300)
    s.markActive(t(S1, 1), "Tanky", PuzzleType.NUMBERS, 1, StateSource.SHARED, T0 + 3_400)
    check(s.state(t(S1, 1)).quarters == 3) { "Out-of-order progress keeps the highest" }
    // The section completes on its full counter; the next counter (8) is S2 even if the gate line was missed.
    s.chatCompletion("Healz", ObjectiveKind.DEVICE, 7, 7, T0 + 4_000)
    check(s.sectionDoneAt != null && s.section == S1)
    s.chatCompletion("Mago", ObjectiveKind.TERMINAL, 1, 8, T0 + 9_000)
    check(s.section == S2) { "A missed gate line is recovered from the next counter" }
    // S2 → S3 by gate; S3 → S4 by counter after S3 is full; S4 → Core.
    s.finishSection(T0 + 15_000); check(s.section == S3)
    GoldorLayout.stations(S3).forEachIndexed { i, st -> s.markComplete(st.objective, "x", StateSource.CHAT, T0 + 16_000) }
    s.chatCompletion("x", ObjectiveKind.LEVER, 7, 7, T0 + 16_000)
    s.chatCompletion("Tanky", ObjectiveKind.TERMINAL, 1, 7, T0 + 22_000)
    check(s.section == S4) { "A new 7-count after a full S3 belongs to S4" }
    s.coreOpened(T0 + 30_000); check(s.section == CORE)
    // A completion line outside an active run is ignored; reset clears the run but keeps the roster.
    s.reset()
    check(!s.active && s.chatCompletion("Tanky", ObjectiveKind.TERMINAL, 1, 7, T0) == null && s.player("Tanky") != null && s.objectives.isEmpty())
    // MEE2's S2 device from the EE2 spot during S1 counts for S2 once S1's own device is done.
    val e = run()
    e.chatCompletion("Healz", ObjectiveKind.DEVICE, 1, 7, T0 + 1_000)
    check(e.state(ObjectiveId.device(S1)).status == ObjectiveStatus.COMPLETE)
    check(e.chatCompletion("Mago", ObjectiveKind.DEVICE, 2, 7, T0 + 2_000) == ObjectiveId.device(S2))
}

private fun checkHudLines() {
    val s = s2(PuzzleType.PANES, PuzzleType.MELODY)
    val advice = advise(s, "Tanky", T0 + 11_600)
    val lines = goldorHudLines(advice, s, T0 + 11_600, recommendations = true)
    check(lines[0].contains("S2") && lines.any { it.contains("Healz") } && lines.any { it.contains("T3 slow") }) { lines.toString() }
    check(goldorHudLines(advice, s, T0 + 11_600, recommendations = false).none { it.contains("Healz") && it.contains("Leap") })
    // Deterministic: same state and time, same advice.
    check(advise(s, "Tanky", T0 + 11_600) == advice)
}

/** Every class has a concrete job: early-enter runners go to their spot, finished players help, short parties finish. */
private fun checkEveryClass() {
    val s = run()
    s.markComplete(ObjectiveId.lever(S1, LeverSide.LEFT), "Mago", StateSource.SHARED, T0 + 1_000)
    s.markComplete(ObjectiveId.lever(S1, LeverSide.RIGHT), "Mago", StateSource.SHARED, T0 + 1_500)
    check(advise(s, "Mago", T0 + 2_000).self!!.headline == "Go MEE2") { "Mage runs MEE2 rather than leaping to itself" }
    s.setRoute(EarlyEnter.MEE2, RouteState.IN, StateSource.SHARED, T0 + 2_500)
    check(advise(s, "Mago", T0 + 3_000).self!!.headline == "Hold MEE2")
    s.finishSection(T0 + 10_000)
    s.markComplete(ObjectiveId.lever(S2, LeverSide.LEFT), "Healz", StateSource.SHARED, T0 + 11_000)
    check(advise(s, "Healz", T0 + 11_100).self!!.headline == "Go HEE3") { advise(s, "Healz", T0 + 11_100).self!!.headline }
    s.finishSection(T0 + 20_000)
    val mageS3 = advise(s, "Mago", T0 + 20_100).self!!
    check(s.section == S3 && mageS3.headline.startsWith("Help: ") && mageS3.objective != null) { "Mage has an S3 job: ${mageS3.headline}" }
    s.finishSection(T0 + 30_000)
    check(advise(s, "Mago", T0 + 30_100).self!!.headline == "Go MEE4 Core")
    // Four players, no Archer: Tank finishes its terminals, then takes Archer's instead of leaping away.
    val short = GoldorRunState(P).apply {
        start(T0)
        listOf("Tanky" to GoldorRole.TANK, "Bers" to GoldorRole.BERSERKER, "Healz" to GoldorRole.HEALER, "Mago" to GoldorRole.MAGE)
            .forEach { (name, role) -> updatePlayer(name) { it.copy(role = role, position = BossPoint(110.0, 113.0, 73.0), seenAt = T0) } }
    }
    short.markComplete(t(S1, 1), "Tanky", StateSource.SHARED, T0 + 1_000); short.markComplete(t(S1, 2), "Tanky", StateSource.SHARED, T0 + 2_000)
    val help = advise(short, "Tanky", T0 + 2_100, position = BossPoint(110.0, 113.0, 73.0)).self!!
    check(help.headline == "Help: T3" && help.reason.contains("Archer")) { help }
}

private fun checkLearning() {
    val all = GoldorRole.entries.toSet()
    val obs = SectionObservation(S2, listOf(GoldorRole.BERSERKER to t(S2, 3), GoldorRole.ARCHER to t(S2, 4), GoldorRole.BERSERKER to t(S2, 5)), all)
    var learned = LearnedRoles().record(obs).record(obs)
    check(P.withLearned(learned) == P) { "Two runs are not enough to change anything" }
    learned = learned.record(obs)
    check(learned.objectives(S2, GoldorRole.BERSERKER) == listOf(t(S2, 3), t(S2, 5))) { "Learned in the order done" }
    val profile = P.withLearned(learned)
    check(profile.plan(S2, GoldorRole.BERSERKER).objectives == listOf(t(S2, 3), t(S2, 5)) && profile.berserkerS2Order == listOf(t(S2, 3), t(S2, 5)))
    check(profile.title.contains("learned") && profile.plan(S1, GoldorRole.BERSERKER).objectives == listOf(ObjectiveId.device(S4))) { "Unlearned sections keep the strategy" }
    check(profile.leaps == P.leaps && profile.routes == P.routes) { "Leaps and early-enters never change" }
    check(LearnedRoles().record(SectionObservation(S2, emptyList(), all)) == LearnedRoles()) { "A section without exact completions teaches nothing" }
    // Two classes doing the same terminal: the more frequent one keeps it.
    val a = SectionObservation(S2, listOf(GoldorRole.BERSERKER to t(S2, 4), GoldorRole.ARCHER to t(S2, 4)), all)
    val b = SectionObservation(S2, listOf(GoldorRole.BERSERKER to t(S2, 4), GoldorRole.ARCHER to t(S2, 5)), all)
    val contested = P.withLearned(LearnedRoles().record(a).record(a).record(b))
    check(t(S2, 4) in contested.plan(S2, GoldorRole.BERSERKER).objectives && t(S2, 4) !in contested.plan(S2, GoldorRole.ARCHER).objectives)
    // Only exact completions are observed: guesses and counter-inferred completions are not.
    val s = run()
    s.markComplete(t(S1, 1), "Tanky", StateSource.SHARED, T0 + 1_000)
    s.markComplete(t(S1, 2), "Tanky", StateSource.CHAT, T0 + 2_000, ambiguous = true)
    check(observeSection(s, S1).completions == listOf(GoldorRole.TANK to t(S1, 1)))
    check(learnedLines(LearnedRoles()).single().contains("nothing learned"))
}

/** The map shows who is actually on an objective, not only who it was planned for. */
private fun checkMapClasses() {
    val s = run()
    check(objectiveClass(s, t(S1, 1)) == (GoldorRole.TANK to false))
    s.markActive(t(S1, 1), "Arch", PuzzleType.PANES, 1, StateSource.SHARED, T0 + 1_000)
    check(objectiveClass(s, t(S1, 1)) == (GoldorRole.ARCHER to true))
    val strip = goldorPartyStrip(s, advise(s, "Tanky", T0 + 1_100), T0 + 1_100)
    check(strip.first().role == GoldorRole.HEALER && strip.single { it.name == "Arch" }.let { it.doing == "T1 P" && it.live }) { strip }
    s.updatePlayer("Bers") { it.copy(alive = false) }
    check(goldorPartyStrip(s, advise(s, "Tanky", T0 + 1_200), T0 + 1_200).single { it.name == "Bers" }.doing == "dead")
}

fun main() {
    checkModel(); checkPuzzles(); checkS1(); checkS2Table(); checkPreLeapFallback(); checkS3S4(); checkRoutes(); checkTiming(); checkProtocol(); checkStateUpdates(); checkHudLines()
    checkEveryClass(); checkLearning(); checkMapClasses(); checkGoldStandard()
    println("Goldor coordination checks passed: gold-standard baseline (fidelity, version pin, conditions, unknowns), roles, every-class advice, learned roles, map classes, S2 decision table, S3/S4, early-enter routes, pre-leap timing, chat protocol and state")
}
