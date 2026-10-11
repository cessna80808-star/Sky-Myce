package me.mycellium.skymyce.features.instances.dungeons.routing

private val room = RoomRoutingContext(10, 20, "room.alpha")

private fun RoomRoutingUpdates.at(x: Double, now: Long, automaticMovement: Boolean = true) =
    observe(GridCell(kotlin.math.floor(x).toInt(), 0, 0), RoomPoint(x, 0.0, 0.0), now, automaticMovement)

private fun started(now: Long = 0): Pair<RoomRoutingUpdates, RoomReplanRequest> {
    val gate = RoomRoutingUpdates()
    gate.updateContext(room, now)
    check(!gate.due(now)) // Context alone cannot fabricate a player start.
    gate.at(0.0, now)
    val request = requireNotNull(gate.take(now))
    check(request.context == room && request.reason == RoomReplanReason.CONTEXT_CHANGED)
    check(request.start == GridCell(0, 0, 0) && request.position == RoomPoint(0.0, 0.0, 0.0))
    check(!gate.due(now) && gate.take(now) == null)
    return gate to request
}

private fun ready(): RoomRoutingUpdates {
    val (gate, request) = started()
    check(gate.complete(request, true, 0))
    check(!gate.complete(request, true, 0))
    check(!gate.due(0))
    return gate
}

private fun checkMovement() {
    val gate = ready()
    val revision = gate.revision
    for (tick in 1..100) gate.at(if (tick % 2 == 0) 1.99 else -1.99, tick.toLong())
    check(gate.revision == revision && !gate.due(100))

    gate.at(2.0, 200)
    check(gate.reason == RoomReplanReason.MOVED && gate.revision > revision)
    val movingRevision = gate.revision
    gate.at(2.5, 240)
    gate.at(3.5, 280)
    gate.at(4.5, 299)
    check(gate.revision == movingRevision && !gate.due(299))
    check(gate.due(300)) // Continuous movement still publishes within 100 ms of its first request.
    val request = requireNotNull(gate.take(300))
    check(request.position == RoomPoint(4.5, 0.0, 0.0) && request.start == GridCell(4, 0, 0))
    check(request.reason == RoomReplanReason.MOVED && gate.complete(request, true, 300))

    gate.at(6.5, 400)
    gate.at(4.6, 450)
    check(gate.reason == null && !gate.due(700)) // Return to the accepted route cancels the work.
    gate.at(6.5, 800)
    check(!gate.due(899) && gate.due(900))

    val (busy, initial) = started()
    busy.at(2.0, 100)
    busy.at(0.0, 150)
    check(!busy.complete(initial, true, 150))
    check(busy.reason == RoomReplanReason.MOVED && busy.due(200))
    check(busy.complete(requireNotNull(busy.take(200)), true, 200))
    // A return while the first calculation is stale must still produce an initial route.

    val (disabledBusy, disabledInitial) = started()
    disabledBusy.at(2.0, 100)
    disabledBusy.at(0.0, 150, false)
    check(!disabledBusy.complete(disabledInitial, true, 150))
    check(disabledBusy.due(200)) // Turning the setting off cannot strand already invalidated work.
}

private fun checkTeleportAndImmediateChanges() {
    val gate = ready()
    val revision = gate.revision
    gate.at(2.0, 100, false)
    gate.at(4.0, 200, false)
    check(gate.revision == revision && !gate.due(700))
    gate.at(12.0, 800, false)
    check(gate.reason == RoomReplanReason.TELEPORTED && gate.due(800))
    val teleport = requireNotNull(gate.take(800))
    check(teleport.position.x == 12.0 && gate.complete(teleport, true, 800))

    gate.at(14.0, 900)
    gate.invalidate(RoomReplanReason.TARGETS_CHANGED, 950)
    gate.at(15.0, 951)
    check(gate.reason == RoomReplanReason.TARGETS_CHANGED && gate.due(951))
    val targets = requireNotNull(gate.take(951))
    gate.invalidate(RoomReplanReason.GEOMETRY_CHANGED, 952)
    check(!gate.complete(targets, true, 952) && gate.due(952))
    val replacement = requireNotNull(gate.take(952))
    check(!gate.complete(targets, false, 953)) // Old completion does not clear the newer job.
    check(!gate.due(953) && !gate.complete(replacement.copy(), true, 953))
    check(gate.complete(replacement, true, 954))
    check(!gate.complete(replacement, true, 954))

    val (busy, initial) = started()
    busy.at(8.0, 1)
    check(!busy.complete(initial, true, 1) && busy.due(1))
    check(requireNotNull(busy.take(1)).reason == RoomReplanReason.TELEPORTED)

    val vertical = ready()
    vertical.observe(GridCell(0, 2, 0), RoomPoint(0.0, 2.0, 0.0), 100)
    check(vertical.reason == RoomReplanReason.MOVED && vertical.due(200))
}

private fun checkFollowingRoute() {
    val gate = ready()
    val revision = gate.revision
    for (x in 1..20) gate.observe(GridCell(x, 0, 0), RoomPoint(x.toDouble(), 0.0, 0.0), x * 50L, followingPath = true)
    check(gate.revision == revision && !gate.due(1000)) // Consuming a valid route does not reorder it.
    gate.observe(GridCell(21, 0, 0), RoomPoint(21.0, 0.0, 0.0), 1050, followingPath = false)
    check(gate.reason == RoomReplanReason.MOVED && gate.due(1150))
    gate.observe(GridCell(22, 0, 0), RoomPoint(22.0, 0.0, 0.0), 1100, followingPath = true)
    check(gate.reason == null) // Rejoining the checked suffix cancels a movement-only request.
    gate.observe(GridCell(30, 0, 0), RoomPoint(30.0, 0.0, 0.0), 1200, followingPath = true)
    check(gate.reason == RoomReplanReason.TELEPORTED && gate.due(1200))
}

private fun checkLifecycle() {
    for (next in listOf(room.copy(roomKey = "room.beta"), room.copy(worldEpoch = 11), room.copy(runEpoch = 21))) {
        val (gate, old) = started()
        gate.updateContext(next, 1)
        check(!gate.complete(old, true, 1) && !gate.due(1))
        gate.at(0.0, 1)
        val request = requireNotNull(gate.take(1))
        check(request.context == next && request.reason == RoomReplanReason.CONTEXT_CHANGED)
        check(gate.complete(request, true, 1))
    }
    // Death/restart/reconnect clear even if the room and epoch identifiers are reused.
    repeat(3) {
        val (gate, old) = started()
        val revision = gate.revision
        gate.clear()
        check(gate.revision > revision && !gate.due(1) && !gate.complete(old, true, 1))
        gate.updateContext(room, 2)
        gate.at(0.0, 2)
        val fresh = requireNotNull(gate.take(2))
        check(!gate.complete(old, false, 3) && gate.complete(fresh, true, 3))
    }
    val (gate, old) = started()
    gate.updateContext(null, 1)
    gate.at(10.0, 2)
    gate.invalidate(RoomReplanReason.TARGETS_CHANGED, 2)
    check(!gate.complete(old, true, 2) && !gate.due(100_000))
    check(gate.reason == null)
    check(runCatching { RoomRoutingContext(0, 0, " ") }.isFailure)
}

private fun checkRetriesAndClock() {
    val (oscillating, failed) = started()
    check(oscillating.complete(failed, false, 0))
    oscillating.at(2.0, 100)
    oscillating.at(0.0, 150)
    check(oscillating.reason == RoomReplanReason.MOVED && oscillating.due(200))
    val resumed = requireNotNull(oscillating.take(200))
    check(oscillating.complete(resumed, true, 200)) // Returning cannot cancel work when no route exists.

    val (gate, initial) = started()
    check(gate.complete(initial, false, 10))
    check(gate.reason == RoomReplanReason.RETRY)
    for (now in 11L..100L) gate.at(0.1, now)
    check(!gate.due(509) && gate.due(510))
    val retry = requireNotNull(gate.take(510))
    check(retry.reason == RoomReplanReason.RETRY && gate.complete(retry, false, 511))
    gate.at(0.2, 50_000)
    check(gate.reason == null && !gate.due(500_000)) // No stable no-path polling loop.
    gate.invalidate(RoomReplanReason.TARGETS_CHANGED, 500_001)
    val changed = requireNotNull(gate.take(500_001))
    check(gate.complete(changed, false, 500_001))
    check(gate.due(500_501)) // A real change receives its own bounded retry allowance.
    val finalRetry = requireNotNull(gate.take(500_501))
    check(gate.complete(finalRetry, true, 500_501) && !gate.due(501_001))

    val backwards = ready()
    backwards.at(2.0, 100)
    check(!backwards.due(-500) && !backwards.due(199) && backwards.due(200))
    val negativeClock = started(-1_000)
    check(negativeClock.first.complete(negativeClock.second, true, -1_000))
    negativeClock.first.at(2.0, -900)
    check(!negativeClock.first.due(-801) && negativeClock.first.due(-800))
    val (saturated, first) = started(Long.MAX_VALUE - 100)
    check(saturated.complete(first, false, Long.MAX_VALUE - 100))
    check(!saturated.due(Long.MAX_VALUE - 1) && saturated.due(Long.MAX_VALUE))
}

private fun checkApproachProbeBudgets() {
    // A room full of inaccessible candidates must finish over bounded ticks without repeating work.
    val blocked = List(10) { RoomApproachProbe() }
    val candidates = (0 until 20).map { GridCell(it, 0, 0) }
    var nativeChecks = 0
    repeat(5) {
        var tickChecks = 0
        for (probe in blocked) tickChecks += probe.step(1, { candidates }, 64 - tickChecks, Long.MAX_VALUE,
            { nativeChecks++; false }, { 0 })
        check(tickChecks <= 64)
    }
    check(nativeChecks == 200 && blocked.all { it.complete(1) && it.result(1) == null })
    for (probe in blocked) check(probe.step(1, { error("Negative cache rebuilt") }, 64, Long.MAX_VALUE,
        { error("Rejected candidate was probed again") }, { 0 }) == 0)

    val resumed = RoomApproachProbe()
    var clock = 0L
    var layoutsRead = 0
    val layout = { layoutsRead++; (0 until 70).map { GridCell(it, 0, 0) } }
    check(resumed.step(2, layout, 64, 3, { clock++; it.x == 5 }, { clock }) == 3)
    check(!resumed.complete(2) && resumed.result(2) == null)
    check(resumed.step(2, layout, 64, Long.MAX_VALUE, { it.x == 5 }, { clock }) == 3)
    check(resumed.result(2) == GridCell(5, 0, 0) && layoutsRead == 1)

    // A position/geometry change invalidates both success and failure caches.
    resumed.invalidate()
    check(resumed.result(2) == null && !resumed.complete(2))
    check(resumed.step(2, layout, Int.MAX_VALUE, Long.MAX_VALUE, { false }, { 0 }) == 64)
    check(resumed.step(2, layout, 64, Long.MAX_VALUE, { it.x == 69 }, { 0 }) == 6)
    check(resumed.result(2) == GridCell(69, 0, 0) && layoutsRead == 2)
    check(resumed.step(3, { error("Growing geometry replaced a checked success") }, 0, 0,
        { error("Cached success queried native geometry") }, { error("Cached success consumed the deadline") }) == 0)
    check(resumed.result(2) == null && resumed.result(3) == GridCell(69, 0, 0) && resumed.complete(3))
    resumed.invalidate()
    check(resumed.step(3, { listOf(GridCell(99, 0, 0)) }, 64, Long.MAX_VALUE, { true }, { 0 }) == 1)
    check(resumed.result(3) == GridCell(99, 0, 0))
}

private fun checkGrowingApproachProbes() {
    val probes = List(12) { RoomApproachProbe() }
    val late = RoomApproachProbe()
    val lateCell = GridCell(300, 0, 0)
    val queried = HashSet<Pair<Int, GridCell>>()
    var lateChecks = 0
    for (tick in 0..9) {
        val revision = tick / 2L
        var checks = 0
        for ((index, probe) in probes.withIndex()) checks += probe.step(revision, {
            (0 until 16).map { GridCell(index * 16 + it, 0, 0) }
        }, 64 - checks, Long.MAX_VALUE, { cell ->
            check(queried.add(index to cell)) // Snapshot growth cannot recheck a rejected candidate prefix.
            false
        }, { 0 })
        checks += late.step(revision, { if (tick >= 2) listOf(lateCell) else emptyList() },
            64 - checks, Long.MAX_VALUE, { lateChecks++; it == lateCell }, { 0 })
        check(checks <= 64)
        if (tick == 3) check(late.result(revision) == lateCell) // Twelve blocked mobs cannot starve a later reachable target.
    }
    check(queried.size == 12 * 16 && lateChecks == 1 && probes.all { it.complete(4) })
    check(late.complete(4) && late.result(4) == lateCell)

    val growing = RoomApproachProbe()
    val old = (0 until 16).map { GridCell(it, 0, 0) }
    val added = GridCell(16, 0, 0)
    check(growing.step(1, { old }, 64, Long.MAX_VALUE, { false }, { 0 }) == 16)
    check(growing.complete(1))
    check(growing.step(2, { error("Zero-budget growth read candidate layout") }, 0, Long.MAX_VALUE,
        { error("Zero-budget growth queried native geometry") }, { error("Zero budget read the clock") }) == 0)
    check(!growing.complete(2) && growing.result(2) == null)
    check(growing.step(2, { error("Expired growth read candidate layout") }, 64, 10,
        { error("Expired growth queried native geometry") }, { 10 }) == 0)
    check(!growing.complete(2))
    check(growing.step(2, { old + added }, 1, Long.MAX_VALUE, { cell ->
        check(cell == added); true
    }, { 0 }) == 1)
    check(growing.complete(2) && growing.result(2) == added)
    growing.invalidate()
    check(!growing.complete(2) && growing.result(2) == null)
    check(growing.step(2, { old }, 1, Long.MAX_VALUE, { cell ->
        check(cell == old.first()); true
    }, { 0 }) == 1) // An actual collision change permits a formerly rejected cell to become reachable.
    check(growing.result(2) == old.first())

    val pending = RoomApproachProbe()
    check(pending.step(10, { old }, 2, Long.MAX_VALUE, { false }, { 0 }) == 2)
    check(!pending.complete(10))
    check(pending.step(11, { old + added }, 1, Long.MAX_VALUE, { cell ->
        check(cell == old[2]); true
    }, { 0 }) == 1) // A new snapshot resumes an unfinished probe after its rejected prefix.
    check(pending.complete(11) && pending.result(11) == old[2])
}

private fun checkPendingApproachGuidance() {
    val cells = (0..2).map { GridCell(it, 0, 0) }
    val start = cells[0]
    val oldAnchor = cells[1]
    val movedAnchor = cells[2]
    val oldTargets = mapOf("star" to oldAnchor)
    val checkedEdges = cells.associateWith { from -> cells.filter { kotlin.math.abs(it.x - from.x) == 1 }.toSet() }
    val oldGraph = requireNotNull(RoomNavigation.create(cells, checkedEdges, oldTargets, start))
    val oldRoute = requireNotNull(oldGraph.plan(start, oldTargets.keys))
    check(oldRoute.path == listOf(start, oldAnchor, start) && movedAnchor !in oldRoute.path)
    fun joins(targets: Map<String, GridCell>) = oldRoute.liveStart(0, targets, { it == start }) { it.x.toDouble() * it.x }
    check(joins(oldTargets) == 0)
    check(joins(mapOf("star" to movedAnchor)) == null) // New anchors cannot locate the old route's mandatory stop.

    val moving = RoomApproachProbe()
    check(moving.step(1, { listOf(movedAnchor) }, 0, Long.MAX_VALUE, { true }, { 0 }) == 0)
    check(!moving.complete(1) && moving.result(1) == null)
    check(roomApproachesPending(oldTargets.keys, emptySet(), oldTargets.keys, !moving.complete(1)))
    check(!roomApproachesPending(oldTargets.keys, emptySet(), emptySet(), true)) // Cleared or disabled stops are no longer required.
    check(!roomApproachesPending(setOf("star", "cleared", "disabled"), oldTargets.keys, oldTargets.keys, true))
    check(!roomApproachesPending(oldTargets.keys, oldTargets.keys, setOf("star", "new"), true)) // A newly discovered stop cannot erase an existing guide.
    check(moving.step(1, { listOf(movedAnchor) }, 1, Long.MAX_VALUE, { true }, { 0 }) == 1)
    check(moving.result(1) == movedAnchor && moving.complete(1))
    check(!roomApproachesPending(oldTargets.keys, setOf("star"), oldTargets.keys, !moving.complete(1)))

    val blocked = RoomApproachProbe()
    check(blocked.step(1, { listOf(movedAnchor) }, 1, Long.MAX_VALUE, { false }, { 0 }) == 1)
    check(blocked.complete(1) && blocked.result(1) == null)
    check(!roomApproachesPending(oldTargets.keys, emptySet(), oldTargets.keys, !blocked.complete(1)))

    // A published frontier can reach a stop before its checked return edge exists.
    val growing = RoomLiveGeometry(start, cells::contains, cells::contains, { _, _ -> true })
    check(!growing.step(maxEdges = 1, maxNanos = Long.MAX_VALUE))
    val partial = requireNotNull(growing.snapshot())
    check(oldAnchor in partial.cells)
    val partialGraph = requireNotNull(RoomNavigation.create(partial.cells, partial.adjacency, oldTargets, start))
    check(partialGraph.plan(start, oldTargets.keys) == null)
    val oldPoints = oldRoute.path.map { RoomPoint(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
    check(checkedRoomPathSurvivesChange(oldPoints) { a, b ->
        val from = GridCell(a.x.toInt(), a.y.toInt(), a.z.toInt())
        val to = GridCell(b.x.toInt(), b.y.toInt(), b.z.toInt())
        from == to || to in checkedEdges.getValue(from)
    }) // An incomplete replacement does not invalidate the already checked guide.
    check(joins(oldTargets) == 0)
    while (!growing.step(maxEdges = 1, maxNanos = Long.MAX_VALUE)) Unit
    val finished = requireNotNull(growing.result)
    val replacement = requireNotNull(RoomNavigation.create(finished.cells, finished.adjacency, oldTargets, start))
    check(replacement.plan(start, oldTargets.keys)?.path == oldRoute.path)
}

private fun checkLocalGuideRelevance() {
    val cells = (0..3).map { GridCell(it, 0, 0) }
    val edges = cells.associateWith { from -> cells.filter { kotlin.math.abs(from.x - it.x) == 1 } }
    val start = cells.first()
    val exit = cells.last()
    val required = setOf("star")
    val partial = requireNotNull(RoomNavigation.create(cells, edges, emptyMap())?.plan(start, emptySet()))
    check(partial.path == listOf(start) && partial.targetOrder.isEmpty())
    check(!roomLocalGuideRelevant(partial, required, null))
    check(!roomLocalGuideRelevant(partial, emptySet(), null))
    val exitOnly = requireNotNull(RoomNavigation.create(cells, edges, emptyMap(), exit)?.plan(start, emptySet()))
    check(!roomLocalGuideRelevant(exitOnly, required, exit)) // A reachable door cannot replace an unapproached required mob.
    check(roomLocalGuideRelevant(exitOnly, emptySet(), exit))
    check(!roomLocalGuideRelevant(exitOnly, emptySet(), start))
    val stopped = requireNotNull(RoomNavigation.create(cells, edges, mapOf("star" to cells[1]), exit)?.plan(start, required))
    check(roomLocalGuideRelevant(stopped, required, exit))
    check(!roomLocalGuideRelevant(stopped, setOf("another-star"), exit)) // A cleared or disabled first stop is no longer useful.
    check(!roomLocalGuideRelevant(null, required, exit) && !roomLocalGuideRelevant(null, emptySet(), exit))
}

private fun checkSightApproachSelection() {
    val hidden = GridCell(0, 0, 0)
    val visible = GridCell(1, 0, 0)
    val candidates = listOf(hidden, visible)
    val probe = RoomApproachProbe()
    var reads = 0
    val playerSight = { cell: GridCell -> reads++; cell == visible }
    check(probe.step(1, { candidates }, 1, Long.MAX_VALUE, playerSight, { 0 }) == 1)
    check(probe.result(1) == null && !probe.complete(1)) // Support alone cannot cache the hidden first anchor.
    check(probe.step(1, { error("Pending sight search restarted") }, 1, Long.MAX_VALUE, playerSight, { 0 }) == 1)
    check(probe.result(1) == visible && probe.complete(1) && reads == 2)
    check(probe.step(2, { error("Visible anchor was replaced") }, 8, Long.MAX_VALUE,
        { error("Visible positive cache repeated selection") }, { 0 }) == 0)
    check(probe.result(2) == visible)

    probe.invalidate() // Its current player sight failed; another anchor can now be visible.
    check(probe.step(2, { candidates }, 1, Long.MAX_VALUE, { it == hidden }, { 0 }) == 1)
    check(probe.result(2) == hidden)

    val moving = RoomApproachProbe()
    val late = (0..16).map { GridCell(it, 0, 0) }
    var player = 0.0
    var movingChecks = 0
    val sight = { cell: GridCell -> movingChecks++; cell == late.last() && player >= 1.0 }
    check(moving.step(3, { late }, 8, Long.MAX_VALUE, sight, { 0 }) == 8)
    player = 1.0
    check(moving.step(3, { error("Moving player restarted an unfinished probe") }, 8, Long.MAX_VALUE, sight, { 0 }) == 8)
    player = 2.0
    check(moving.step(3, { error("Late candidate starved") }, 8, Long.MAX_VALUE, sight, { 0 }) == 1)
    check(moving.result(3) == late.last() && movingChecks == 17)

    val negative = RoomApproachProbe()
    check(negative.step(4, { candidates }, 8, Long.MAX_VALUE, { false }, { 0 }) == 2)
    check(negative.complete(4) && negative.result(4) == null)
    check(negative.step(5, { candidates }, 8, Long.MAX_VALUE,
        { error("Stationary negative cache queried geometry") }, { 0 }) == 0)
    negative.invalidate() // Meaningful movement changes player LOS and permits a rejected anchor.
    check(negative.step(5, { candidates }, 8, Long.MAX_VALUE, { it == hidden }, { 0 }) == 1)
    check(negative.result(5) == hidden)
}

private fun checkBloodDoorReminder() {
    val rooms = (0..2).map { setOf(RoomSegment(-200 + it * 32, -200)) }
    val wither = DungeonDoor(rooms[0], rooms[1], GridCell(-169, 69, -185), GridCell(-172, 69, -185),
        GridCell(-166, 69, -185), "Wither door", true)
    val blood = DungeonDoor(rooms[1], rooms[2], GridCell(-137, 69, -185), GridCell(-140, 69, -185),
        GridCell(-134, 69, -185), "Blood door", true)
    val exit = DungeonExit(wither.door, wither.approach, wither.kind, doors = listOf(wither, blood), reachesBlood = true)
    val reminder = BloodDoorReminder()
    val outside = RoomPoint(-150.0, 69.0, -185.0)
    val inside = RoomPoint(-120.0, 79.0, -185.0)
    check(reminder.highlight(mage = true, outside) == null)
    reminder.observe(exit)
    check(reminder.highlight(mage = false, outside) == null)
    check(reminder.highlight(mage = true, outside) == blood.door) // Available before opening, from the end of the full chain.
    check(reminder.highlight(mage = true, inside) == null)
    check(reminder.highlight(mage = true, outside.copy(x = -136.5001)) == blood.door)
    check(reminder.highlight(mage = true, outside.copy(x = -136.5)) == null)
    check(reminder.highlight(mage = true, outside.copy(x = -104.5)) == blood.door)
    reminder.observe(exit.copy(doors = listOf(wither.copy(closed = false), blood.copy(closed = false))))
    repeat(100) { reminder.observe(null) }
    check(reminder.highlight(mage = true, inside) == null && reminder.highlight(mage = true, outside) == blood.door)
    // Leaving Blood restores the reminder; opening it and missing map refreshes cannot expire it.
    reminder.clear()
    check(reminder.highlight(mage = true, outside) == null)

    val late = BloodDoorReminder()
    late.observe(exit.copy(doors = listOf(wither)))
    check(late.highlight(mage = true, outside) == null) // Wither cannot substitute for unknown Blood coordinates.
    late.observe(exit)
    check(late.highlight(mage = true, outside) == blood.door && late.highlight(mage = true, inside) == null)
    late.clear()
    late.observe(DungeonExit(blood.door, blood.approach, blood.kind))
    check(late.highlight(mage = true, outside) == blood.door && late.highlight(mage = true, inside) == null)
    late.clear()
    late.observe(exit.copy(doors = listOf(wither, blood.copy(to = emptySet()))))
    check(late.highlight(mage = true, outside) == blood.door && late.highlight(mage = true, inside) == null)
}

fun checkRoomRoutingUpdates() {
    check(roomRouteLineOffset(1.2) == 0.0)
    check(roomRouteLineOffset(-5.0) == -1.2 && roomRouteLineOffset(9.0) == 2.8)
    check(roomRouteLineOffset(Double.NaN) == 0.0 && roomRouteLineOffset(Double.POSITIVE_INFINITY) == 0.0)
    val retained = (0..4).map { RoomPoint(it.toDouble(), 0.0, 0.0) }
    check(checkedRoomPathSurvivesChange(retained) { _, _ -> true })
    check(!checkedRoomPathSurvivesChange(retained) { a, b -> a.x < 2.0 && b.x < 2.0 }) // A removed floor breaks support.
    check(!checkedRoomPathSurvivesChange(retained) { _, _ -> error("Unavailable geometry") })
    check(!checkedRoomPathSurvivesChange(emptyList()) { _, _ -> error("Empty route read geometry") })
    check(!checkedRoomPathSurvivesChange(List(66) { retained.first() }) { _, _ -> error("Over-budget route read geometry") })
    check(kotlin.math.abs(roomWaypointTextScale(2.0) / 2 - roomWaypointTextScale(40.0) / 40) < 0.00001)
    check(roomWaypointTextScale(0.0) == 0.035f && roomWaypointTextScale(200.0) == 2.8f)
    checkMovement()
    checkTeleportAndImmediateChanges()
    checkFollowingRoute()
    checkLifecycle()
    checkRetriesAndClock()
    checkApproachProbeBudgets()
    checkGrowingApproachProbes()
    checkPendingApproachGuidance()
    checkLocalGuideRelevance()
    checkSightApproachSelection()
    checkBloodDoorReminder()
}

fun main() {
    checkRoomRoutingUpdates()
    println("Room routing update checks passed")
}
