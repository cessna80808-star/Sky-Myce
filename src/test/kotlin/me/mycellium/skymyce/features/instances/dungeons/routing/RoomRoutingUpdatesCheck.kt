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
    check(resumed.step(3, { listOf(GridCell(99, 0, 0)) }, 64, Long.MAX_VALUE, { true }, { 0 }) == 1)
    check(resumed.result(2) == null && resumed.result(3) == GridCell(99, 0, 0))
}

fun checkRoomRoutingUpdates() {
    check(kotlin.math.abs(roomWaypointTextScale(2.0) / 2 - roomWaypointTextScale(40.0) / 40) < 0.00001)
    check(roomWaypointTextScale(0.0) == 0.035f && roomWaypointTextScale(200.0) == 2.8f)
    checkMovement()
    checkTeleportAndImmediateChanges()
    checkLifecycle()
    checkRetriesAndClock()
    checkApproachProbeBudgets()
}

fun main() {
    checkRoomRoutingUpdates()
    println("Room routing update checks passed")
}
