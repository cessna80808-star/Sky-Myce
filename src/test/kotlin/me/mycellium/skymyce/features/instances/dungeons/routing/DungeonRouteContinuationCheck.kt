package me.mycellium.skymyce.features.instances.dungeons.routing

import kotlin.math.floor

/** Synthetic floors and a Fairy-like pillar; no recorded dungeon room coordinates. */
private class ContinuationFixture {
    val rooms = (0..3).map { setOf(RoomSegment(it * 32, 0)) }
    val loaded = rooms.flatten().toMutableSet()
    val standingReads = HashMap<RoomSegment, Int>()
    var blockPillar = true
    val doors = (0..2).map { index ->
        val x = index * 32 + 31
        DungeonDoor(rooms[index], rooms[index + 1], GridCell(x, 69, 15),
            GridCell(x - 3, 69, 15), GridCell(x + 3, 69, 15),
            if (index == 2) "Blood door" else "Wither door", true)
    }

    fun exit(doors: List<DungeonDoor> = this.doors, reachesBlood: Boolean = true) =
        DungeonExit(doors.first().door, doors.first().approach, doors.first().kind,
            doors = doors, reachesBlood = reachesBlood)

    fun point(cell: GridCell) = RoomPoint(cell.x + .5, cell.y.toDouble(), cell.z + .5)

    fun standing(cell: GridCell): RoomPoint? {
        val segment = RoomSegment(Math.floorDiv(cell.x, 32) * 32, Math.floorDiv(cell.z, 32) * 32)
        standingReads[segment] = (standingReads[segment] ?: 0) + 1
        return point(cell).takeIf { cell.y == 69 && segment in loaded && short(it, it) }
    }

    private fun clear(from: RoomPoint, to: RoomPoint): Boolean {
        fun hits(x1: Double, z1: Double, x2: Double, z2: Double) =
            minOf(from.x, to.x) - .3 < x2 && maxOf(from.x, to.x) + .3 > x1 &&
                minOf(from.z, to.z) - .3 < z2 && maxOf(from.z, to.z) + .3 > z1 &&
                minOf(from.y, to.y) < 80 && maxOf(from.y, to.y) + 1.8 > 69
        return (!blockPillar || !hits(44.0, 11.0, 51.0, 20.0)) &&
            doors.filter { it.closed }.none { hits(it.door.x - 1.0, 14.0, it.door.x + 2.0, 17.0) }
    }

    private fun short(from: RoomPoint, to: RoomPoint) = validRoomConnector(from, to,
        loaded = { a, b ->
            listOf(a, b).all { RoomSegment(Math.floorDiv(floor(it.x).toInt(), 32) * 32,
                Math.floorDiv(floor(it.z).toInt(), 32) * 32) in loaded }
        }, clearSegment = ::clear,
        supported = { point, _, radius -> point.y == 69.0 && point.x - radius >= 0 &&
            point.x + radius <= 128 && point.z - radius >= 0 && point.z + radius <= 32 },
        climbable = { false })

    fun connects(from: RoomPoint, to: RoomPoint) = validRoomRouteConnector(from, to, ::short)
    fun controller() = DungeonRouteContinuation(::standing, ::connects, simplify = { simplifyRoomPath(it, ::connects) })

    fun finish(controller: DungeonRouteContinuation, expectedInteriors: Int) {
        for (tick in 0..2_000) controller.step(tick * 10L, Long.MAX_VALUE)
        check(controller.sections().count { !it.gate && it.checked } == expectedInteriors) {
            "Onward room geometry never completed"
        }
    }
}

private fun checkQueuedContinuation() {
    val fixture = ContinuationFixture()
    val queued = ArrayList<Pair<() -> List<RoomPoint>?, (List<RoomPoint>?) -> Unit>>()
    val route = DungeonRouteContinuation(fixture::standing, fixture::connects,
        simplify = { simplifyRoomPath(it, fixture::connects) },
        schedule = { work, publish -> queued.add(work to publish) })
    route.update(fixture.exit(fixture.doors.take(2)))
    var now = 0L
    fun scanToQueue() {
        val count = queued.size
        for (tick in 0..1_000) {
            route.step(now, Long.MAX_VALUE)
            now += 10
            if (queued.size > count) return
        }
        error("Onward planner was never queued")
    }
    scanToQueue()
    fixture.standingReads.clear()
    repeat(10) { route.step(now++, Long.MAX_VALUE) }
    check(queued.size == 1) // An in-flight provisional plan permits bounded scanning, never a second concurrent job.
    val (firstWork, firstPublish) = queued.removeAt(0)
    val firstPath = firstWork()
    check(firstPath != null)
    route.blockChanged(GridCell(44, 69, 15))
    scanToQueue()
    val afterBlock = route.revision
    firstPublish(firstPath)
    check(route.revision == afterBlock && route.sections().none { !it.gate && it.checked })
    fixture.standingReads.clear()
    repeat(10) { route.step(now++, Long.MAX_VALUE) }
    check(queued.size == 1)

    val (secondWork, secondPublish) = queued.removeAt(0)
    val secondPath = secondWork()
    route.chunkChanged(2, 0)
    scanToQueue()
    val afterChunk = route.revision
    secondPublish(secondPath)
    check(route.revision == afterChunk && route.sections().none { !it.gate && it.checked })

    fixture.standingReads.clear()
    repeat(1_000) { route.step(now++, Long.MAX_VALUE) }
    check(queued.size == 1 && fixture.standingReads.isNotEmpty()) // The flood finishes while its provisional worker remains queued.
    fixture.standingReads.clear()
    repeat(10) { route.step(now++, Long.MAX_VALUE) }
    check(queued.size == 1 && fixture.standingReads.isEmpty())
    val (replacementWork, replacementPublish) = queued.removeAt(0)
    replacementPublish(replacementWork())
    check(route.sections().single { !it.gate }.checked)
    check(queued.isEmpty())
    route.step(now++, Long.MAX_VALUE)
    check(queued.size == 1) // Publishing the delayed provisional result queues exactly one completed-graph refinement.
    fixture.standingReads.clear()
    repeat(1_000) { route.step(now++, Long.MAX_VALUE) }
    check(queued.size == 1 && fixture.standingReads.isEmpty())
    val (finalWork, finalPublish) = queued.removeAt(0)
    finalPublish(finalWork())
    check(route.sections().single { !it.gate }.checked)
    fixture.standingReads.clear()
    repeat(10) { route.step(now++, Long.MAX_VALUE) }
    check(queued.isEmpty() && fixture.standingReads.isEmpty()) // Completed refinement never scans or plans again.
    val entry = route.sections().single { !it.gate }.points.first()
    route.update(fixture.exit(listOf(fixture.doors[1])))
    check(route.checkedCurrentPath(entry) != null)
    route.chunkChanged(1, 0)
    val currentInvalidated = route.revision
    replacementPublish(replacementWork())
    check(route.revision == currentInvalidated && route.checkedCurrentPath(entry) == null)
    route.step(now, Long.MAX_VALUE)
    check(queued.isEmpty()) // A stale job cannot restore the cached current-room leg or rescan it.
}

private fun checkFailedContinuationCache() {
    val fixture = ContinuationFixture()
    var blocked = true
    var jobs = 0
    fun connects(from: RoomPoint, to: RoomPoint) = fixture.connects(from, to) &&
        (!blocked || (from.x < 48) == (to.x < 48))
    val route = DungeonRouteContinuation(fixture::standing, ::connects,
        simplify = { simplifyRoomPath(it, ::connects) },
        schedule = { work, publish -> jobs++; publish(work()) })
    route.update(fixture.exit(fixture.doors.take(2)))
    for (tick in 0..1_000) {
        route.step(tick * 10L, Long.MAX_VALUE)
        if (jobs > 0) break
    }
    check(jobs == 1 && route.sections().none { !it.gate && it.checked })
    fixture.standingReads.clear()
    repeat(100) { route.step(100_000L + it * 1_000L, Long.MAX_VALUE) }
    check(jobs == 1 && fixture.standingReads.isEmpty()) // Unchanged unreachable geometry remains a map hint without repeated full scans.

    route.chunkChanged(2, 0)
    for (tick in 0..1_000) {
        route.step(200_000L + tick * 10L, Long.MAX_VALUE)
        if (jobs == 2) break
    }
    check(jobs == 2 && route.sections().none { !it.gate && it.checked })
    fixture.standingReads.clear()
    repeat(100) { route.step(300_000L + it * 1_000L, Long.MAX_VALUE) }
    check(jobs == 2 && fixture.standingReads.isEmpty())

    blocked = false
    val failedRevision = route.revision
    route.blockChanged(GridCell(58, 69, 15)) // Relevant room change lies beyond the failed graph's reachable half.
    check(route.revision > failedRevision)
    fixture.finish(route, 1)
    check(jobs == 4 && route.sections().single { !it.gate }.checked) // One early route and one finished-graph refinement.
}

private fun checkCurrentHandoff() {
    val fixture = ContinuationFixture()
    var joinsChecked = 0
    val route = DungeonRouteContinuation(fixture::standing,
        connects = { from, to -> joinsChecked++; fixture.connects(from, to) },
        simplify = { simplifyRoomPath(it, fixture::connects) })
    route.update(fixture.exit())
    fixture.finish(route, 2)
    val cached = route.sections().first { !it.gate }.points
    check(route.checkedCurrentPath(cached.first()) == null) // This room is still ahead of the player.
    route.update(fixture.exit(fixture.doors.drop(1)))
    check(route.checkedCurrentPath(cached.first()) == cached)

    val span = cached.zipWithNext().withIndex().maxBy { (_, pair) ->
        val (a, b) = pair
        (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z)
    }
    val (a, b) = span.value
    val middle = RoomPoint((a.x + b.x) / 2, a.y, (a.z + b.z) / 2)
    val remaining = route.checkedCurrentPath(middle)!!
    check(remaining == listOf(middle) + cached.drop(span.index + 1)) // Join a simplified corridor at its projection.
    val offset = middle.copy(z = middle.z + .05)
    val joined = route.checkedCurrentPath(offset)!!
    check(joined.first() == offset && joined.last() == cached.last())
    check(joined.zipWithNext().all { (from, to) -> fixture.connects(from, to) }) // Every join and retained span clears the pillar.
    joinsChecked = 0
    check(route.checkedCurrentPath(RoomPoint(47.5, 69.0, 15.5)) == null) // Being near the path cannot join through its pillar.
    check(joinsChecked in 1..8)
    fixture.standingReads.clear()
    repeat(10) { route.step(30_000L + it, Long.MAX_VALUE) }
    check(fixture.standingReads.isEmpty()) // Entering Fairy does not scan its already checked interior again.

    route.blockChanged(GridCell(44, 69, 15))
    check(route.checkedCurrentPath(cached.first()) == null)
    fixture.standingReads.clear()
    route.step(40_000, Long.MAX_VALUE)
    check(fixture.standingReads.isEmpty()) // The current-room builder owns recovery after invalidation.

    val chunkFixture = ContinuationFixture()
    val chunkRoute = chunkFixture.controller()
    chunkRoute.update(chunkFixture.exit(chunkFixture.doors.take(2)))
    chunkFixture.finish(chunkRoute, 1)
    val entry = chunkRoute.sections().first { !it.gate }.points.first()
    chunkRoute.update(chunkFixture.exit(listOf(chunkFixture.doors[1])))
    check(chunkRoute.checkedCurrentPath(entry) != null)
    chunkRoute.chunkChanged(1, 0) // Adjacent collision-margin chunk invalidates the retained current leg too.
    check(chunkRoute.checkedCurrentPath(entry) == null)
    chunkFixture.standingReads.clear()
    chunkRoute.step(40_000, Long.MAX_VALUE)
    check(chunkFixture.standingReads.isEmpty())
    chunkRoute.update(null)
    check(chunkRoute.checkedCurrentPath(entry) == null)
}

private fun checkShortenedPrefixCache() {
    val fixture = ContinuationFixture()
    val route = fixture.controller()
    val full = fixture.exit(fixture.doors.take(2))
    route.update(full)
    fixture.finish(route, 1)
    val cached = route.sections().single { !it.gate }.points
    val shortened = fixture.exit(fixture.doors.take(1), reachesBlood = false)
    route.update(shortened)
    check(route.sections().size == 1 && route.sections().single().gate)
    check(route.checkedCurrentPath(cached.first()) == null) // The uncertain destination interior remains hidden.
    fixture.standingReads.clear()
    repeat(10) { route.step(30_000L + it, Long.MAX_VALUE) }
    check(fixture.standingReads.isEmpty()) // A temporarily shorter confirmed chain does not rebuild checked room geometry.
    route.update(shortened.copy(doors = shortened.doors.map { it.copy(closed = false) }))
    route.update(full)
    check(route.sections().single { !it.gate }.points === cached)
    route.update(fixture.exit(listOf(fixture.doors[1])))
    check(route.checkedCurrentPath(cached.first()) == cached)
    route.update(fixture.exit(listOf(fixture.doors[1].copy(closed = false, kind = "Dungeon door"))))
    check(route.checkedCurrentPath(cached.first()) == cached) // Opening the current gate preserves the entered room's suffix too.
    route.chunkChanged(1, 0)
    check(route.checkedCurrentPath(cached.first()) == null)

    for (change in listOf("none", "chunk", "exit")) {
        val queuedFixture = ContinuationFixture()
        val queued = ArrayList<Pair<() -> List<RoomPoint>?, (List<RoomPoint>?) -> Unit>>()
        val queuedRoute = DungeonRouteContinuation(queuedFixture::standing, queuedFixture::connects,
            simplify = { simplifyRoomPath(it, queuedFixture::connects) },
            schedule = { work, publish -> queued.add(work to publish) })
        queuedRoute.update(queuedFixture.exit(queuedFixture.doors.take(2)))
        for (tick in 0..1_000) {
            queuedRoute.step(tick * 10L, Long.MAX_VALUE)
            if (queued.isNotEmpty()) break
        }
        check(queued.size == 1)
        val (work, publish) = queued.single()
        val result = checkNotNull(work())
        queuedRoute.update(queuedFixture.exit(queuedFixture.doors.take(1), reachesBlood = false))
        if (change == "chunk") queuedRoute.chunkChanged(1, 0)
        val enteredDoor = queuedFixture.doors[1].let { door -> if (change == "exit") door.copy(
            door = door.door.copy(z = door.door.z + 8), approach = door.approach.copy(z = door.approach.z + 8)) else door }
        if (change == "exit") queuedRoute.update(queuedFixture.exit(listOf(enteredDoor)))
        val revision = queuedRoute.revision
        publish(result)
        check(queuedRoute.sections().size == 1 && queuedRoute.sections().single().gate)
        if (change != "none") check(queuedRoute.revision == revision) // A shortened prefix cannot restore work invalidated by geometry or a different exit.
        else check(queuedRoute.revision > revision)
        queuedRoute.update(queuedFixture.exit(listOf(enteredDoor)))
        check((queuedRoute.checkedCurrentPath(result.first()) != null) == (change == "none"))
    }
}

private fun checkCurrentStairConnector() {
    val fixture = ContinuationFixture()
    val entry = GridCell(34, 69, 15)
    val endpoint = GridCell(35, 70, 15)
    val doors = listOf(fixture.doors[0].copy(departure = entry), fixture.doors[1].copy(approach = endpoint))
    fun standing(cell: GridCell) = fixture.point(cell).takeIf { cell == entry || cell == endpoint }
    fun connects(from: RoomPoint, to: RoomPoint) = validRoomRouteConnector(from, to) { start, end ->
        validRoomConnector(start, end, loaded = { _, _ -> true },
            clearSegment = { a, b -> a.y == b.y || a.x == b.x && a.z == b.z },
            supported = { point, depth, _ ->
                val ground = if (point.x < 35.0) 69.0 else 70.0
                point.y >= ground && point.y - ground <= depth
            }, climbable = { false })
    }
    val route = DungeonRouteContinuation(::standing, ::connects, simplify = { points ->
        val elbows = points.zipWithNext().flatMap { (a, b) -> roomConnectorWaypoints(a, b)!! }
        simplifyRoomPath(elbows, ::connects)
    })
    route.update(fixture.exit(doors))
    fixture.finish(route, 1)
    val section = route.sections().first { !it.gate }
    val cached = section.points
    check(cached.size == 4 && cached[1].y == 70.25 && cached[2].y == 70.25)
    check(section.displayPoints == listOf(fixture.point(entry), fixture.point(endpoint)))
    route.update(fixture.exit(doors.drop(1)))
    val position = RoomPoint(34.8, 69.5, 15.5)
    val connector = roomConnectorWaypoints(position, cached.first())!!
    val joined = route.checkedCurrentPath(position)!!
    check(connector.size == 3 && joined == connector + cached.drop(1))
    check(connects(position, cached.first()))
    check(joined.zipWithNext().all { (a, b) -> a.y == b.y || a.x == b.x && a.z == b.z })
    check(joined.takeLast(cached.size - 1) == cached.drop(1)) // New player joins preserve the already checked stair elbows.
    val raw = route.checkedCurrentPath(position, display = true)!!
    check(raw.first() == position && raw.last() == cached.last())
    check(raw.zipWithNext().all { (a, b) -> connects(a, b) })
    check(raw.none { it.y == 70.25 }) // Display smoothing receives standing feet, without the physical jump headroom elbows.
    route.blockChanged(entry)
    check(route.checkedCurrentPath(position) == null && route.checkedCurrentPath(position, display = true) == null)
}

private fun checkCurrentStairDisplaySuffix() {
    val fixture = ContinuationFixture()
    val cells = (0..5).map { GridCell(34 + it, 69 + it, 15) }
    val standingPoints = cells.map(fixture::point)
    val doors = listOf(fixture.doors[0].copy(departure = cells.first()),
        fixture.doors[1].copy(approach = cells.last()))
    fun standing(cell: GridCell) = fixture.point(cell).takeIf { cell in cells }
    fun connects(from: RoomPoint, to: RoomPoint) = validRoomRouteConnector(from, to) { start, end ->
        validRoomConnector(start, end, loaded = { _, _ -> true },
            clearSegment = { a, b -> a.y == b.y || a.x == b.x && a.z == b.z },
            supported = { point, depth, _ ->
                val ground = 69.0 + floor(point.x - 34.0).toInt().coerceIn(0, 5)
                point.y >= ground && point.y - ground <= depth
            }, climbable = { false })
    }
    val route = DungeonRouteContinuation(::standing, ::connects, simplify = { points ->
        val elbows = points.zipWithNext().flatMap { (a, b) -> requireNotNull(roomConnectorWaypoints(a, b)) }
        simplifyRoomPath(elbows, ::connects)
    })
    route.update(fixture.exit(doors))
    fixture.finish(route, 1)
    val cached = route.sections().single { !it.gate }
    check(cached.displayPoints == standingPoints && cached.points.size > standingPoints.size)
    route.update(fixture.exit(doors.drop(1)))
    val position = RoomPoint(34.3, 69.0, 15.55)
    val physical = checkNotNull(route.checkedCurrentPath(position))
    val raw = checkNotNull(route.checkedCurrentPath(position, display = true))
    check(physical.any { it.y % 1.0 == .25 } && physical.drop(2) == cached.points.drop(1))
    check(physical.zipWithNext().all { (a, b) -> a.y == b.y || a.x == b.x && a.z == b.z })
    check(raw.first() == position && raw.drop(1) == standingPoints && raw.none { it.y % 1.0 == .25 })
    check(raw.zipWithNext().all { (a, b) -> connects(a, b) })
    val ramp = smoothRoomDisplayPath(raw.drop(1), ::connects)
    check(ramp == listOf(standingPoints.first(), standingPoints.last()))
    check(!connects(ramp.first(), ramp.last())) // The display ramp never replaces validated walking stair elbows.
    val moved = checkNotNull(route.checkedCurrentPath(position.copy(x = 34.4), display = true))
    check(moved.first() != raw.first() && moved.drop(2) == raw.drop(2))
    check(smoothRoomDisplayPath(moved.drop(2), ::connects) == smoothRoomDisplayPath(raw.drop(2), ::connects)) // Player joins move while the standing-node ramp suffix remains stable.
}

private fun checkAirborneDisplayJoin() {
    val fixture = ContinuationFixture()
    var landingSupported = true
    var landingLoaded = true
    var nearbyClear = true
    fun sight(from: RoomPoint, to: RoomPoint) = validRoomSightConnector(from, to,
        loaded = { _, _ -> landingLoaded },
        clearSegment = { a, b -> nearbyClear && fixture.connects(a.copy(y = 69.0), b.copy(y = 69.0)) },
        canLand = { landingSupported && fixture.connects(it, it) })
    val route = DungeonRouteContinuation(fixture::standing, fixture::connects,
        simplify = { it }, sightConnects = ::sight)
    route.update(fixture.exit(fixture.doors.take(2)))
    fixture.finish(route, 1)
    val interior = route.sections().single { !it.gate }
    val landing = interior.displayPoints.first()
    val airborne = landing.copy(y = landing.y + 1.5)
    check(!sight(airborne, interior.displayPoints.last())) // The pillar blocks a direct shortcut to the exit.
    check(sight(airborne, landing) && !fixture.connects(airborne, landing))
    route.update(fixture.exit(listOf(fixture.doors[1])))
    val retained = route.revision
    val joined = checkNotNull(route.checkedCurrentPath(airborne, display = true))
    check(joined == listOf(airborne) + interior.displayPoints) // Keep every checked standing point after the landing.
    check(route.checkedCurrentPath(airborne) == null) // Sight-only airborne joins do not certify walking paths.

    landingSupported = false
    check(route.checkedCurrentPath(airborne, display = true) == null)
    landingSupported = true
    landingLoaded = false
    check(route.checkedCurrentPath(airborne, display = true) == null)
    landingLoaded = true
    nearbyClear = false
    check(route.checkedCurrentPath(airborne, display = true) == null)
    nearbyClear = true
    check(route.revision == retained && route.checkedCurrentPath(airborne, display = true) == joined)
    check(route.checkedCurrentPath(landing) == interior.points) // Failed joins never discard the safe cached tail.
}

private fun checkEarlyContinuationRefinement() {
    val fixture = ContinuationFixture()
    var jobs = 0
    val route = DungeonRouteContinuation(fixture::standing, fixture::connects,
        simplify = { simplifyRoomPath(it, fixture::connects) },
        schedule = { work, publish -> jobs++; publish(work()) })
    route.update(fixture.exit(fixture.doors.take(2)))
    for (tick in 0..1_000) {
        route.step(tick * 10L, Long.MAX_VALUE)
        if (route.sections().any { !it.gate && it.checked }) break
    }
    val early = route.sections().single { !it.gate }
    val earlyReads = fixture.standingReads.values.sum()
    val earlyRevision = route.revision
    check(early.checked && jobs == 1)
    check(early.points.zipWithNext().all { (a, b) -> fixture.connects(a, b) })
    fixture.finish(route, 1)
    check(jobs == 2 && fixture.standingReads.values.sum() > earlyReads && route.revision > earlyRevision)
    check(route.sections().single { !it.gate }.points.zipWithNext().all { (a, b) -> fixture.connects(a, b) })
    fixture.standingReads.clear()
    repeat(100) { route.step(100_000L + it * 1_000L, Long.MAX_VALUE) }
    check(jobs == 2 && fixture.standingReads.isEmpty()) // One provisional route, one final refinement, then stable reuse.

    val handoffFixture = ContinuationFixture()
    val handoff = handoffFixture.controller()
    handoff.update(handoffFixture.exit(handoffFixture.doors.take(2)))
    handoff.step(0, Long.MAX_VALUE)
    val entry = handoffFixture.point(handoffFixture.doors.first().departure)
    handoff.update(handoffFixture.exit(listOf(handoffFixture.doors[1])))
    check(handoff.checkedCurrentPath(entry) == null)
    handoffFixture.finish(handoff, 0)
    val joined = requireNotNull(handoff.checkedCurrentPath(entry))
    check(joined.first() == entry && joined.last() == handoffFixture.point(handoffFixture.doors[1].approach))
    check(joined.zipWithNext().all { (a, b) -> handoffFixture.connects(a, b) }) // Entering Fairy cannot freeze its already-started scan.
}

private fun checkDirectContinuation() {
    val fixture = ContinuationFixture().apply { blockPillar = false }
    var jobs = 0
    var supported = true
    fun connects(a: RoomPoint, b: RoomPoint) = supported && fixture.connects(a, b)
    val route = DungeonRouteContinuation(fixture::standing, ::connects,
        simplify = { simplifyRoomPath(it, ::connects) },
        schedule = { work, publish -> jobs++; publish(work()) })
    route.update(fixture.exit(fixture.doors.take(2)))
    route.step(0, Long.MAX_VALUE)
    check(jobs == 0 && fixture.standingReads.values.sum() == 2) // Clear sight needs two native surfaces, no room flood or worker job.
    val interior = route.sections().single { !it.gate }
    check(interior.checked && interior.points == listOf(fixture.point(fixture.doors[0].departure),
        fixture.point(fixture.doors[1].approach)))
    check(route.sections().filter { it.gate }.all { !it.checked }) // A direct interior cannot certify closed doors.
    check(route.sections().filter { it.gate }.map { it.points[1] } == fixture.doors.take(2).map { fixture.point(it.door) })
    fixture.standingReads.clear()
    repeat(100) { route.step(1_000L + it, Long.MAX_VALUE) }
    check(jobs == 0 && fixture.standingReads.isEmpty())

    val stable = route.revision
    route.blockChanged(fixture.doors[1].door, collisionRemoved = true)
    check(route.revision == stable && route.sections().single { !it.gate }.points === interior.points)
    route.update(fixture.exit(listOf(fixture.doors[1])))
    val position = fixture.point(GridCell(45, 69, 15))
    check(route.checkedCurrentPath(position) == listOf(position, interior.points.last()))

    supported = false
    route.blockChanged(GridCell(48, 68, 15), collisionRemoved = true)
    check(route.checkedCurrentPath(position) == null) // Removing support invalidates even a cached straight path.

    val wallFixture = ContinuationFixture().apply { blockPillar = false }
    val wallRoute = wallFixture.controller()
    wallRoute.update(wallFixture.exit(wallFixture.doors.take(2)))
    wallRoute.step(0, Long.MAX_VALUE)
    check(wallRoute.sections().single { !it.gate }.checked)
    wallFixture.blockPillar = true
    wallRoute.blockChanged(GridCell(44, 69, 15))
    check(!wallRoute.sections().single { !it.gate }.checked) // New obstacles invalidate direct-only legs without a graph snapshot.
    wallFixture.finish(wallRoute, 1)
    check(wallRoute.sections().single { !it.gate }.points.size > 2)

    val revision = wallRoute.revision
    wallFixture.blockPillar = false
    wallRoute.blockChanged(GridCell(44, 69, 15), collisionRemoved = true)
    check(wallRoute.revision > revision && wallRoute.sections().single { !it.gate }.points.size == 2)
    wallFixture.standingReads.clear()
    repeat(100) { wallRoute.step(50_000L + it, Long.MAX_VALUE) }
    check(wallFixture.standingReads.isEmpty()) // Removing a pillar can straighten the checked path without rebuilding the room.

    val aligned = DungeonRouteContinuation(fixture::standing,
        connects = { a, b -> connects(a.copy(y = 69.0), b.copy(y = 69.0)) }, simplify = { it })
    supported = true
    aligned.update(fixture.exit(fixture.doors.take(2)))
    aligned.step(0, Long.MAX_VALUE)
    aligned.update(fixture.exit(listOf(fixture.doors[1])))
    val carpetFeet = position.copy(y = position.y + 1.0 / 16.0)
    check(aligned.checkedCurrentPath(carpetFeet) == listOf(carpetFeet, interior.points.last())) // Native alignment lets actual carpet feet use clear sight.
}

private fun checkTeleportContinuation() {
    val fixture = ContinuationFixture().apply { blockPillar = false }
    val landing = fixture.doors[1].approach.copy(y = 72)
    val doors = listOf(fixture.doors[0], fixture.doors[1].copy(approach = landing))
    var landingPresent = true
    var wall = false
    fun supported(point: RoomPoint) = if (point == fixture.point(landing)) landingPresent else
        point.y == 69.0 && point.x !in 44.0..51.0
    fun standing(cell: GridCell) = if (cell == landing) fixture.point(cell).takeIf { landingPresent } else
        fixture.standing(cell)?.takeIf(::supported)
    fun walking(a: RoomPoint, b: RoomPoint) = fixture.connects(a, b) && supported(a) && supported(b) &&
        !(minOf(a.x, b.x) <= 51.0 && maxOf(a.x, b.x) >= 44.0)
    fun sight(a: RoomPoint, b: RoomPoint) = !wall && supported(a) && supported(b)
    val walkingRoute = DungeonRouteContinuation(::standing, ::walking, simplify = { it })
    walkingRoute.update(fixture.exit(doors))
    fixture.finish(walkingRoute, 0)
    check(!walkingRoute.sections().single { !it.gate }.checked) // The default policy cannot walk across a full-width pit.

    fun controller() = DungeonRouteContinuation(::standing, ::walking, simplify = { it }, sightConnects = ::sight)
    val teleportRoute = controller()
    teleportRoute.update(fixture.exit(doors))
    teleportRoute.step(0, Long.MAX_VALUE)
    val interior = teleportRoute.sections().single { !it.gate }
    check(interior.checked && interior.points == listOf(fixture.point(doors[0].departure), fixture.point(landing)))
    check(teleportRoute.sections().filter { it.gate }.all { !it.checked && it.points.size == 3 }) // Sight guidance still retains closed door centers.
    teleportRoute.update(fixture.exit(doors.drop(1)))
    val position = fixture.point(GridCell(38, 69, 15))
    check(teleportRoute.checkedCurrentPath(position) == listOf(position, fixture.point(landing)))
    val retained = teleportRoute.revision
    teleportRoute.blockChanged(GridCell(48, 68, 15), collisionRemoved = true)
    check(teleportRoute.revision == retained && teleportRoute.checkedCurrentPath(position) != null) // A pit between supported landings does not invalidate teleport guidance.

    wall = true
    teleportRoute.blockChanged(GridCell(48, 70, 15))
    check(teleportRoute.checkedCurrentPath(position) == null) // A new wall still invalidates the sight path.
    wall = false
    val landingRoute = controller()
    landingRoute.update(fixture.exit(doors))
    landingRoute.step(0, Long.MAX_VALUE)
    landingRoute.update(fixture.exit(doors.drop(1)))
    check(landingRoute.checkedCurrentPath(position) != null)
    landingPresent = false
    landingRoute.blockChanged(GridCell(landing.x, landing.y - 1, landing.z), collisionRemoved = true)
    check(landingRoute.checkedCurrentPath(position) == null) // A clear ray cannot retain a missing destination landing.
}

private fun checkObstructedContinuationAnchors() {
    val fixture = ContinuationFixture()
    val anchors = setOf(fixture.doors[0].departure, fixture.doors[1].approach)
    fun standing(cell: GridCell) = if (anchors.any { cell.x == it.x && cell.z == it.z })
        fixture.point(cell).takeIf { cell.y == 74 } else fixture.standing(cell)
    fun connects(a: RoomPoint, b: RoomPoint) = if (a == b && a.y == 74.0) true else
        fixture.connects(a, b) && anchors.none { anchor ->
            minOf(a.x, b.x) - .3 < anchor.x + 1 && maxOf(a.x, b.x) + .3 > anchor.x &&
                minOf(a.z, b.z) - .3 < anchor.z + 1 && maxOf(a.z, b.z) + .3 > anchor.z
        }
    val route = DungeonRouteContinuation(::standing, ::connects,
        simplify = { simplifyRoomPath(it, ::connects) })
    route.update(fixture.exit(fixture.doors.take(2)))
    fixture.finish(route, 1)
    val interior = route.sections().single { !it.gate }
    fun near(point: RoomPoint, anchor: GridCell) =
        (point.x - anchor.x - .5) * (point.x - anchor.x - .5) +
            (point.z - anchor.z - .5) * (point.z - anchor.z - .5) <= 4.0
    check(interior.points.first() != fixture.point(fixture.doors[0].departure) && near(interior.points.first(), fixture.doors[0].departure))
    check(interior.points.last() != fixture.point(fixture.doors[1].approach) && near(interior.points.last(), fixture.doors[1].approach))
    check(interior.points.first().y == 69.0 && interior.points.last().y == 69.0) // Nearby floor wins over the isolated canonical pillar top.
    check(interior.points.first().x > fixture.doors[0].door.x && interior.points.last().x < fixture.doors[1].door.x)
    check(interior.points.zipWithNext().all { (a, b) -> connects(a, b) })
    check(route.sections().zipWithNext().all { (a, b) -> a.points.last() == b.points.first() })
    check(route.sections().filter { it.gate }.all { !it.checked }) // A nearby surface cannot validate a closed crossing.

    fun wall(from: RoomPoint, to: RoomPoint) = connects(from, to) && (from.x < 48) == (to.x < 48)
    val blocked = DungeonRouteContinuation(::standing, ::wall, simplify = { simplifyRoomPath(it, ::wall) })
    blocked.update(fixture.exit(fixture.doors.take(2)))
    fixture.finish(blocked, 0)
    check(blocked.sections().single { !it.gate }.checked == false) // Nearby anchors still cannot invent a connection through a wall.
}

private fun checkApproachStairSection() {
    val fixture = ContinuationFixture()
    val approach = fixture.point(fixture.doors[0].approach)
    val from = approach.copy(x = approach.x - 1.0, y = approach.y + 1.0)
    fun connects(a: RoomPoint, b: RoomPoint) = validRoomRouteConnector(a, b) { start, end ->
        validRoomConnector(start, end, loaded = { _, _ -> true },
            clearSegment = { left, right -> left.y == right.y || left.x == right.x && left.z == right.z },
            supported = { point, depth, _ ->
                val ground = if (point.x < approach.x - .5) 70.0 else 69.0
                point.y >= ground && point.y - ground <= depth
            }, climbable = { false })
    }
    val route = DungeonRouteContinuation(fixture::standing, ::connects, simplify = { it })
    route.update(fixture.exit(fixture.doors.take(1)))
    val stair = route.sections(from).first()
    check(stair.checked && !stair.gate && stair.points.first() == from && stair.points.last() == approach)
    check(stair.displayPoints == listOf(from, approach))
    check(stair.points.zipWithNext().all { (a, b) -> a.y == b.y || a.x == b.x && a.z == b.z })
    val blocked = DungeonRouteContinuation(fixture::standing, { _, _ -> false }, simplify = { it })
    blocked.update(fixture.exit(fixture.doors.take(1)))
    check(!blocked.sections(from).first().checked)
    check(!route.sections(from.copy(x = from.x - 8.0)).first().checked) // A height-changing long span stays an honest hint.
}

fun checkDungeonRouteContinuation() {
    checkQueuedContinuation()
    checkFailedContinuationCache()
    checkCurrentHandoff()
    checkShortenedPrefixCache()
    checkCurrentStairConnector()
    checkCurrentStairDisplaySuffix()
    checkAirborneDisplayJoin()
    checkEarlyContinuationRefinement()
    checkDirectContinuation()
    checkTeleportContinuation()
    checkObstructedContinuationAnchors()
    checkApproachStairSection()
    val fixture = ContinuationFixture()
    val route = fixture.controller()
    route.update(fixture.exit())
    val hints = route.sections()
    check(hints.count { it.gate } == 3 && hints.count { !it.gate } == 2)
    check(hints.filter { it.gate }.all { !it.checked })
    check(hints.filter { !it.gate }.all { !it.checked }) // Map knowledge precedes loaded interior scanning.
    fixture.finish(route, 2)
    val complete = route.sections()
    val fairy = complete.first { !it.gate }
    check(fairy.checked && fairy.points.first() == fixture.point(fixture.doors[0].departure) &&
        fairy.points.last() == fixture.point(fixture.doors[1].approach))
    check(fairy.points.size > 2 && fairy.points.any { it.z < 11 || it.z > 20 })
    check(fairy.points.zipWithNext().all { (a, b) -> fixture.connects(a, b) })
    check(complete.filter { it.gate }.map { it.points[1] } == fixture.doors.map { fixture.point(it.door) })
    check(complete.filter { it.gate }.all { !it.checked }) // Closed gates preserve the checked onward interiors.
    check(complete.zipWithNext().all { (a, b) -> a.points.last() == b.points.first() })

    val revision = route.revision
    route.update(fixture.exit())
    check(route.revision == revision)
    route.update(fixture.exit(fixture.doors.drop(1))) // Entering Fairy reuses the completed onward suffix.
    val suffix = route.sections().single { !it.gate }
    check(suffix.checked && suffix.points === complete.last { !it.gate }.points)
    fixture.standingReads.clear()
    route.step(30_000, Long.MAX_VALUE)
    check(fixture.standingReads.isEmpty())

    val partialFixture = ContinuationFixture()
    partialFixture.loaded.remove(RoomSegment(64, 0))
    val partial = partialFixture.controller()
    partial.update(partialFixture.exit())
    partialFixture.finish(partial, 1)
    check(partial.sections().filter { !it.gate }.map { it.checked } == listOf(true, false))
    val cachedFairy = partial.sections().first { !it.gate }.points
    partialFixture.loaded.add(RoomSegment(64, 0))
    partial.chunkChanged(5, 0)
    partialFixture.finish(partial, 2)
    check(partial.sections().first { !it.gate }.points === cachedFairy)
    check(partial.sections().filter { !it.gate }.all { it.checked })

    partial.chunkChanged(1, 0) // The collision/support margin also observes the adjacent chunk.
    check(!partial.sections().first { !it.gate }.checked && partial.sections().last { !it.gate }.checked)
    partialFixture.finish(partial, 2)

    partialFixture.blockPillar = false
    val beforeBlock = partial.revision
    partial.blockChanged(GridCell(44, 69, 15))
    check(partial.revision > beforeBlock && !partial.sections().first { !it.gate }.checked)
    partialFixture.finish(partial, 2)
    check(partial.sections().first { !it.gate }.points.size == 2)

    val frontier = fixture.doors[1].copy(to = emptySet())
    route.update(fixture.exit(listOf(fixture.doors[0], frontier), reachesBlood = false))
    check(route.sections().size == 3 && route.sections().count { !it.gate } == 1)
    check(route.sections().last().gate) // Unknown next room adds no invented interior or doorway.
    route.update(null)
    check(route.sections().isEmpty())
    println("Dungeon continuation checks passed: closed gates, pre-entry Fairy handoff, safe cached joins, stair elbows, geometry discovery and partial chains")
}

fun main() = checkDungeonRouteContinuation()
