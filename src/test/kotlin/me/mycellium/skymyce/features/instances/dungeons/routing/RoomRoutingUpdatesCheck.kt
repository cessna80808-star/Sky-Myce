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
    gate.at(2.5, 300)
    gate.at(3.5, 500)
    gate.at(4.5, 699)
    check(gate.revision == movingRevision && !gate.due(699))
    check(gate.due(700)) // Rapid movement does not push the first deadline back forever.
    val request = requireNotNull(gate.take(700))
    check(request.position == RoomPoint(4.5, 0.0, 0.0) && request.start == GridCell(4, 0, 0))
    check(request.reason == RoomReplanReason.MOVED && gate.complete(request, true, 700))

    gate.at(6.5, 800)
    gate.at(4.6, 900)
    check(gate.reason == null && !gate.due(1_300)) // Return to the accepted route cancels the work.
    gate.at(6.5, 1_400)
    check(!gate.due(1_899) && gate.due(1_900))

    val (busy, initial) = started()
    busy.at(2.0, 100)
    busy.at(0.0, 200)
    check(!busy.complete(initial, true, 200))
    check(busy.reason == RoomReplanReason.MOVED && busy.due(600))
    check(busy.complete(requireNotNull(busy.take(600)), true, 600))
    // A return while the first calculation is stale must still produce an initial route.

    val (disabledBusy, disabledInitial) = started()
    disabledBusy.at(2.0, 100)
    disabledBusy.at(0.0, 200, false)
    check(!disabledBusy.complete(disabledInitial, true, 200))
    check(disabledBusy.due(600)) // Turning the setting off cannot strand already invalidated work.
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
    check(vertical.reason == RoomReplanReason.MOVED && vertical.due(600))
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
    oscillating.at(0.0, 200)
    check(oscillating.reason == RoomReplanReason.MOVED && oscillating.due(600))
    val resumed = requireNotNull(oscillating.take(600))
    check(oscillating.complete(resumed, true, 600)) // Returning cannot cancel work when no route exists.

    val (gate, initial) = started()
    check(gate.complete(initial, false, 10))
    check(gate.reason == RoomReplanReason.RETRY)
    for (now in 11L..100L) gate.at(0.1, now)
    check(!gate.due(5_009) && gate.due(5_010))
    val retry = requireNotNull(gate.take(5_010))
    check(retry.reason == RoomReplanReason.RETRY && gate.complete(retry, false, 5_011))
    gate.at(0.2, 50_000)
    check(gate.reason == null && !gate.due(500_000)) // No stable no-path polling loop.
    gate.invalidate(RoomReplanReason.TARGETS_CHANGED, 500_001)
    val changed = requireNotNull(gate.take(500_001))
    check(gate.complete(changed, false, 500_001))
    check(gate.due(505_001)) // A real change receives its own bounded retry allowance.
    val finalRetry = requireNotNull(gate.take(505_001))
    check(gate.complete(finalRetry, true, 505_001) && !gate.due(510_001))

    val backwards = ready()
    backwards.at(2.0, 100)
    check(!backwards.due(-500) && !backwards.due(599) && backwards.due(600))
    val negativeClock = started(-1_000)
    check(negativeClock.first.complete(negativeClock.second, true, -1_000))
    negativeClock.first.at(2.0, -900)
    check(!negativeClock.first.due(-401) && negativeClock.first.due(-400))
    val (saturated, first) = started(Long.MAX_VALUE - 100)
    check(saturated.complete(first, false, Long.MAX_VALUE - 100))
    check(!saturated.due(Long.MAX_VALUE - 1) && saturated.due(Long.MAX_VALUE))
}

fun checkRoomRoutingUpdates() {
    checkMovement()
    checkTeleportAndImmediateChanges()
    checkLifecycle()
    checkRetriesAndClock()
}

fun main() {
    checkRoomRoutingUpdates()
    println("Room routing update checks passed")
}
