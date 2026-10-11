package me.mycellium.skymyce.features.instances.dungeons.routing

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes

/** Synthetic collision shapes only: none of these coordinates describe a real dungeon room. */
private data class SolidBox(val min: RoomPoint, val max: RoomPoint, val carpet: Boolean = false, val ironBars: Boolean = false)

private fun solid(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) =
    SolidBox(RoomPoint(x1, y1, z1), RoomPoint(x2, y2, z2))

private class GeometryFixture(
    solids: List<SolidBox>,
    private val loaded: Boolean = true,
    private val ladder: (RoomPoint) -> Boolean = { false },
) {
    private val solids = solids.flatMap { box ->
        roomRoutingCollisionShape(box.carpet || box.ironBars) {
            Shapes.create(AABB(box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z))
        }.toAabbs().map { solid(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }
    }

    var worldReads = 0
        private set

    fun clear(from: RoomPoint, to: RoomPoint): Boolean = solids.none { box ->
        // Independent swept-body intersection against the fixture's exact solid shapes.
        minOf(from.x, to.x) - 0.3 < box.max.x && maxOf(from.x, to.x) + 0.3 > box.min.x &&
            minOf(from.y, to.y) < box.max.y && maxOf(from.y, to.y) + 1.8 > box.min.y &&
            minOf(from.z, to.z) - 0.3 < box.max.z && maxOf(from.z, to.z) + 0.3 > box.min.z
    }

    fun route(from: RoomPoint, to: RoomPoint): Boolean = validRoomConnector(from, to,
        loaded = { _, _ -> loaded },
        clearSegment = { a, b -> worldReads++; clear(a, b) },
        supported = ::support,
        climbable = { point -> worldReads++; ladder(point) })

    private fun support(point: RoomPoint, depth: Double, radius: Double): Boolean {
        worldReads++
        return solids.any { box ->
            box.max.y <= point.y && box.max.y > point.y - depth &&
                box.min.x < point.x + radius && box.max.x > point.x - radius &&
                box.min.z < point.z + radius && box.max.z > point.z - radius
        }
    }

    fun jump(from: RoomPoint, to: RoomPoint): Boolean = validRoomJumpConnector(from, to,
        loaded = { _, _ -> loaded }, clearSegment = { a, b -> worldReads++; clear(a, b) }, supported = ::support)

    fun line(from: RoomPoint, to: RoomPoint): Boolean = validRoomRouteConnector(from, to, ::route)

    fun sight(from: RoomPoint, to: RoomPoint): Boolean = validRoomSightConnector(from, to,
        loaded = { _, _ -> loaded },
        clearSegment = { a, b -> worldReads++; clear(a, b) },
        canLand = { route(it, it) })

    fun standing(cell: GridCell): RoomPoint? = roomStandingPoint(cell, solids.asSequence().filter { box ->
        box.min.x < cell.x + 0.8 && box.max.x > cell.x + 0.2 &&
            box.min.z < cell.z + 0.8 && box.max.z > cell.z + 0.2
    }.map { it.max.y }, { route(it, it) }, ladder(RoomPoint(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)))
}

private fun checkPathCleanup() {
    val floor = solid(-2.0, -1.0, -4.0, 302.0, 0.0, 8.0)
    fun point(x: Double, z: Double = 0.5) = RoomPoint(x, 0.0, z)
    val open = GeometryFixture(listOf(floor))
    val straight = (0..10).map { point(it + 0.5) }
    check(!open.route(straight.first(), straight.last()) && open.line(straight.first(), straight.last()))
    check(simplifyRoomPath(straight, open::line) == listOf(straight.first(), straight.last()))
    check(simplifyRoomPath(listOf(straight.first(), straight.first(), straight.last()), open::line) ==
        listOf(straight.first(), straight.last()))
    check(simplifyRoomPath(emptyList(), open::line).isEmpty())
    val long = (0..200).map { point(it + 0.5) }
    check(simplifyRoomPath(long, open::line) == listOf(long.first(), long.last()))
    check(smoothRoomDisplayPath(long, open::line) == listOf(long.first(), long.last()))
    check(simplifyRoomPath(long) { a, b -> b.x - a.x <= 64 && open.line(a, b) } ==
        listOf(long[0], long[64], long[128], long[192], long[200]))

    val pillar = GeometryFixture(listOf(floor, solid(2.0, 0.0, 0.0, 4.0, 3.0, 2.0)))
    val around = listOf(point(0.5), point(1.5), point(1.5, 2.5), point(2.5, 2.5),
        point(3.5, 2.5), point(4.5, 2.5), point(4.5), point(6.5))
    check(around.zipWithNext().all { (a, b) -> pillar.line(a, b) })
    check(!pillar.line(around.first(), around.last()))
    val cleaned = simplifyRoomPath(around, pillar::line)
    check(cleaned.first() == around.first() && cleaned.last() == around.last() && cleaned.size in 3 until around.size)
    check(cleaned.zipWithNext().all { (a, b) -> pillar.line(a, b) })

    val doorway = GeometryFixture(listOf(floor,
        solid(4.0, 0.0, -4.0, 5.0, 3.0, 0.0), solid(4.0, 0.0, 1.0, 5.0, 3.0, 8.0)))
    check(doorway.line(point(0.5), point(9.5)))
    check(!doorway.line(point(0.5, 1.5), point(9.5, 1.5)))
    val narrow = GeometryFixture(listOf(floor,
        solid(4.0, 0.0, -4.0, 5.0, 3.0, 0.25), solid(4.0, 0.0, 0.75, 5.0, 3.0, 8.0)))
    check(!narrow.line(point(0.5), point(9.5))) // An empty center ray is too narrow for the player body.
    val turn = listOf(point(0.5, 2.5), point(3.5, 2.5), point(3.5), point(5.5), point(8.5, 2.5))
    check(turn.zipWithNext().all { (a, b) -> doorway.line(a, b) })
    val throughDoor = simplifyRoomPath(turn, doorway::line)
    check(throughDoor.zipWithNext().all { (a, b) -> doorway.line(a, b) })
    check(throughDoor.size > 2) // Keep the turn into the narrow doorway.

    val hole = GeometryFixture(listOf(solid(-1.0, -1.0, -1.0, 4.0, 0.0, 1.0),
        solid(6.0, -1.0, -1.0, 12.0, 0.0, 1.0)))
    check(!hole.line(point(0.5), point(10.5)))
    val unavailable = GeometryFixture(listOf(floor), loaded = false)
    check(!unavailable.line(point(0.5), point(10.5)) && unavailable.worldReads == 0)
    var intervals = 0
    check(!validRoomRouteConnector(point(0.5), point(10.5)) { a, b ->
        intervals++
        b.x <= 4.5 && open.route(a, b)
    })
    check(intervals == 3) // A loaded start never bypasses an unavailable middle interval.
    check(!validRoomRouteConnector(point(0.5), point(10.5)) { _, _ -> error("Unavailable world") })

    val upper = RoomPoint(1.5, 1.0, 0.5)
    val stairs = GeometryFixture(listOf(floor, solid(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)))
    val elbows = requireNotNull(roomConnectorWaypoints(point(0.5), upper))
    check(stairs.line(point(0.5), upper))
    check(simplifyRoomPath(elbows, stairs::line) == elbows)
    val descending = requireNotNull(roomConnectorWaypoints(upper, point(0.5)))
    check(simplifyRoomPath(descending, stairs::line) == descending)
    check(!stairs.line(point(0.5), RoomPoint(8.5, 1.0, 0.5)))
}

private fun checkCarpetPolicy() {
    val native = Shapes.create(AABB(0.0, 0.0, 0.0, 1.0, 0.0625, 1.0))
    check(roomRoutingCollisionShape(false) { native } === native)
    check(roomRoutingCollisionShape(true) { error("An ignored carpet must not query its shape") }.isEmpty)
    val floor = solid(-1.0, -1.0, -1.0, 12.0, 0.0, 2.0)
    val carpets = (0..10).filter { it % 2 == 0 }.map {
        solid(it.toDouble(), 0.0, 0.0, it + 1.0, 0.0625, 1.0).copy(carpet = true)
    }
    val bare = GeometryFixture(listOf(floor))
    val covered = GeometryFixture(listOf(floor) + carpets)
    val path = (0..10).map { RoomPoint(it + .5, 0.0, .5) }
    for ((index, point) in path.withIndex()) {
        val cell = GridCell(index, 0, 0)
        check(bare.standing(cell) == point && covered.standing(cell) == point)
        check(covered.standing(GridCell(index, 1, 0)) == null)
    }
    check(covered.line(path.first(), path.last()) && bare.line(path.first(), path.last()))
    check(smoothRoomDisplayPath(path, covered::line) == listOf(path.first(), path.last()))
    val actualFeet = path.first().copy(y = 0.0625)
    check(roomRouteLevelHeight(actualFeet, path.last()) && roomRouteLevelHeight(path.last(), actualFeet))
    check(!roomRouteLevelHeight(actualFeet.copy(y = 0.125), path.last()))
    check(covered.line(actualFeet, path.last()) && covered.line(path.last(), actualFeet))
    check(!covered.line(actualFeet.copy(y = 0.125), path.last())) // Larger height changes retain the stair policy.
    val lowCeiling = GeometryFixture(listOf(floor, solid(4.0, 1.82, 0.0, 5.0, 2.0, 1.0)) + carpets)
    check(lowCeiling.line(path.first(), path.last()) && !lowCeiling.line(actualFeet, path.last()))

    val unsupported = GeometryFixture(carpets)
    check(unsupported.standing(GridCell(0, 0, 0)) == null && unsupported.standing(GridCell(0, 1, 0)) == null)
    check(!unsupported.route(path.first(), path.first()) && !unsupported.route(actualFeet, actualFeet))
    val gap = GeometryFixture(listOf(solid(-1.0, -1.0, -1.0, 4.0, 0.0, 2.0),
        solid(6.0, -1.0, -1.0, 12.0, 0.0, 2.0)) + carpets)
    check(!gap.line(path.first(), path.last()) && !gap.line(actualFeet, path.last()))
    val slab = GeometryFixture(listOf(solid(0.0, -1.0, 0.0, 1.0, 0.5, 1.0),
        solid(0.0, 0.5, 0.0, 1.0, 0.5625, 1.0).copy(carpet = true)))
    check(slab.standing(GridCell(0, 1, 0)) == RoomPoint(.5, .5, .5))
}

private fun checkIronBarsPolicy() {
    val floor = solid(-1.0, -1.0, -1.0, 3.0, 0.0, 2.0)
    val bars = solid(.9375, 0.0, .25, 1.0625, 1.5, .75)
    val start = RoomPoint(.5, 0.0, .5)
    val end = RoomPoint(1.5, 0.0, .5)
    val ordinary = GeometryFixture(listOf(floor, bars))
    val ignored = GeometryFixture(listOf(floor, bars.copy(ironBars = true)))
    check(!ordinary.route(start, end) && ignored.route(start, end))
    check(ignored.standing(GridCell(1, 0, 0)) == end)
    check(ignored.line(start, end) && ignored.sight(start, end))
    val barFloor = solid(0.0, -1.0, 0.0, 2.0, 0.0, 1.0).copy(ironBars = true)
    val unsupported = GeometryFixture(listOf(barFloor))
    check(!unsupported.route(start, start) && !unsupported.sight(start, end))
    check(unsupported.standing(GridCell(0, 0, 0)) == null)
}

private fun checkGapJumps() {
    val start = RoomPoint(.5, 0.0, .5)
    val end = RoomPoint(3.5, 0.0, .5)
    val platforms = listOf(solid(0.0, -1.0, 0.0, 1.0, 0.0, 1.0),
        solid(3.0, -1.0, 0.0, 4.0, 0.0, 1.0))
    val gap = GeometryFixture(platforms)
    check(gap.jump(start, end) && gap.jump(end, start) && gap.route(start, end))
    check(gap.line(start, end) && gap.line(end, start)) // Subdivision must not turn the gap into an unsupported waypoint.
    check(roomConnectorWaypoints(start, end) == listOf(start, end))
    check(!gap.jump(start, end.copy(x = 3.6)))
    check(!gap.jump(start, end.copy(y = .125)) && !gap.jump(start, end.copy(z = 1.5)))
    check(!GeometryFixture(platforms.take(1)).jump(start, end))
    check(!GeometryFixture(platforms + solid(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)).jump(start, end))
    check(!GeometryFixture(platforms + solid(1.0, 2.8, 0.0, 3.0, 3.2, 1.0)).jump(start, end))
    check(!GeometryFixture(platforms.map { it.copy(ironBars = true) }).jump(start, end))
    val unloaded = GeometryFixture(platforms, loaded = false)
    check(!unloaded.jump(start, end) && unloaded.worldReads == 0)
    check(!validRoomJumpConnector(start, end, { _, _ -> true },
        { _, _ -> error("Unavailable jump corridor") }, { _, _, _ -> true }))

    val farEnd = end.copy(x = 4.5)
    val wide = GeometryFixture(listOf(solid(0.0, -1.0, 0.0, 1.0, 0.0, 3.0),
        solid(4.0, -1.0, 0.0, 5.0, 0.0, 3.0), solid(0.0, -1.0, 2.0, 5.0, 0.0, 3.0)))
    val detour = listOf(start, start.copy(z = 2.5), farEnd.copy(z = 2.5), farEnd)
    check(detour.zipWithNext().all { (a, b) -> wide.line(a, b) } && !wide.line(start, farEnd))
    val simplified = simplifyRoomPath(detour, wide::line)
    check(simplified.size > 2 && simplified.zipWithNext().all { (a, b) -> wide.line(a, b) })

    val seed = GridCell(0, 0, 0)
    val landing = GridCell(3, 0, 0)
    val surfaces = HashMap<GridCell, RoomPoint>()
    var cellsRead = 0
    var edgesRead = 0
    val scan = RoomLiveGeometry(seed, { it.y == 0 && it.z == 0 && it.x in 0..3 },
        { cellsRead++; gap.standing(it)?.also { point -> surfaces[it] = point } != null },
        { a, b -> edgesRead++; gap.route(surfaces.getValue(a), surfaces.getValue(b)) }, surfaces::getValue)
    while (scan.status == RoomGeometryStatus.BUILDING) {
        val cellsBefore = cellsRead
        val edgesBefore = edgesRead
        scan.step(maxCells = 1, maxEdges = 1, maxNanos = Long.MAX_VALUE)
        check(cellsRead - cellsBefore <= 1 && edgesRead - edgesBefore <= 1)
    }
    val snapshot = requireNotNull(scan.result)
    check(snapshot.cells == setOf(seed, landing) && snapshot.adjacency[seed] == setOf(landing))
    val graph = requireNotNull(RoomNavigation.create(snapshot.cells, snapshot.adjacency, emptyMap(), landing, snapshot.feet))
    val route = requireNotNull(graph.plan(seed, emptySet()))
    check(route.path == listOf(seed, landing) && route.cost == 300)
    check(requireNotNull(RoomNavigation.create(snapshot.cells, snapshot.adjacency, emptyMap(), seed, snapshot.feet))
        .plan(landing, emptySet())?.path == listOf(landing, seed))

    val outside = RoomLiveGeometry(seed, { it == seed || it == landing }, { true }, { _, _ -> true })
    while (!outside.step(maxNanos = Long.MAX_VALUE)) { }
    check(outside.result?.cells == setOf(seed)) // Jump corridors stay within the checked room boundary.
    val solidFloor = GeometryFixture(listOf(solid(0.0, -1.0, 0.0, 4.0, 0.0, 1.0)))
    val plain = RoomLiveGeometry(seed, { it.y == 0 && it.z == 0 && it.x in 0..3 },
        { solidFloor.standing(it) != null }, { a, b -> solidFloor.route(RoomPoint(a.x + .5, 0.0, .5), RoomPoint(b.x + .5, 0.0, .5)) })
    while (!plain.step(maxNanos = Long.MAX_VALUE)) { }
    check(plain.result?.adjacency?.get(seed) == setOf(GridCell(1, 0, 0))) // Open floors do not gain redundant jump edges.
}

private fun checkTeleportSightGuidance() {
    val start = RoomPoint(.5, 0.0, .5)
    val end = RoomPoint(10.5, 0.0, .5)
    val edges = listOf(solid(-1.0, -1.0, -1.0, 2.0, 0.0, 2.0),
        solid(9.0, -1.0, -1.0, 12.0, 0.0, 2.0))
    val pit = GeometryFixture(edges)
    check(!pit.line(start, end) && pit.sight(start, end))
    check(pit.sight(start.copy(y = 0.0625), end))
    val landings = ArrayList<RoomPoint>()
    check(validRoomSightConnector(start, end, { _, _ -> true }, pit::clear) {
        landings.add(it); pit.route(it, it)
    })
    check(landings == listOf(end)) // No floor/support observation is needed in the pit.
    val destinationOnly = GeometryFixture(edges.takeLast(1))
    check(destinationOnly.sight(start, end)) // The player may already be airborne.
    val airborne = start.copy(y = 1.25)
    check(!destinationOnly.route(airborne, end) && destinationOnly.sight(airborne, end))
    check(!GeometryFixture(edges.take(1)).sight(start, end))
    check(!GeometryFixture(edges + solid(4.9, 0.0, 0.0, 5.1, 3.0, 1.0)).sight(start, end))
    check(!GeometryFixture(edges + solid(4.9375, 0.0, .25, 5.0625, 1.5, .75)).sight(start, end))
    check(!GeometryFixture(edges + solid(4.0, 1.75, 0.0, 6.0, 2.0, 1.0)).sight(start, end))
    check(!GeometryFixture(edges + solid(4.0, 0.0, 0.0, 6.0, 2.0, 1.0)).sight(start, end))
    val diagonalEnd = end.copy(z = 10.5)
    val diagonal = GeometryFixture(listOf(solid(9.0, -1.0, 9.0, 12.0, 0.0, 12.0),
        solid(0.0, 0.0, 10.0, 1.0, 3.0, 11.0)))
    check(diagonal.sight(start, diagonalEnd)) // A remote pillar inside the full bounding rectangle is irrelevant.
    val unloaded = GeometryFixture(edges, loaded = false)
    check(!unloaded.sight(start, end) && unloaded.worldReads == 0)
    val invalid = GeometryFixture(edges)
    check(runCatching { start.copy(x = Double.NaN) }.isFailure)
    check(runCatching { end.copy(y = Double.POSITIVE_INFINITY) }.isFailure)
    check(!invalid.sight(start.copy(x = 30_000_000.0), end))
    check(!invalid.sight(start, end.copy(x = 129.0)) && invalid.worldReads == 0)
    check(!validRoomSightConnector(start, end, { _, _ -> error("Unavailable chunk") },
        { _, _ -> true }, { true }))
    check(!validRoomSightConnector(start, end, { _, _ -> true },
        { _, _ -> error("Unavailable collision") }, { true }))
}

private fun checkDisplaySmoothing() {
    val stops = roomDisplayStops(listOf(5, 90, 100), 0, 100)
    check(stops == listOf(5, 69, 90, 100))
    check((listOf(0) + stops).zipWithNext().all { (from, to) -> to - from <= 64 })
    check(roomDisplayStops(listOf(5, 90, 100), 8, 69) == listOf(69))
    check(roomDisplayStops(emptyList(), 0, 0) == listOf(0))
    val full = roomDisplayStops(listOf(140), 0, 140)
    for (cursor in listOf(0, 63, 64, 65, 61, 128, 139)) {
        val end = full.first { it > cursor }
        check(end - cursor <= 64) // Backtracking reuses the original chunk boundaries.
    }
    fun point(x: Double, y: Double = 0.0, z: Double = 0.5) = RoomPoint(x, y, z)
    val floor = solid(-2.0, -1.0, -2.0, 12.0, 0.0, 12.0)
    val stairs = GeometryFixture(listOf(floor) + (1..4).map { height ->
        solid(height.toDouble(), 0.0, -1.0, height + 1.0, height.toDouble(), 2.0)
    })
    val standing = (0..4).map { point(it + .5, it.toDouble()) }
    check(standing.zipWithNext().all { (a, b) -> stairs.line(a, b) })
    check(smoothRoomDisplayPath(standing, stairs::line) == listOf(standing.first(), standing.last()))
    check(smoothRoomDisplayPath(standing.reversed(), stairs::line) == listOf(standing.last(), standing.first()))
    check(!stairs.line(standing.first(), standing.last()) && !stairs.clear(standing.first(), standing.last()))
    val physical = standing.zipWithNext().flatMap { (a, b) -> requireNotNull(roomConnectorWaypoints(a, b)) }
    check(simplifyRoomPath(physical, stairs::line).size > standing.size) // Display ramps never relax the physical elbow policy.

    val treads = GeometryFixture(listOf(floor, solid(2.0, 0.0, -1.0, 4.0, 1.0, 2.0),
        solid(4.0, 0.0, -1.0, 6.0, 2.0, 2.0)))
    val repeated = (0..5).map { point(it + .5, (it / 2).toDouble()) }
    check(repeated.zipWithNext().all { (a, b) -> treads.line(a, b) })
    check(smoothRoomDisplayPath(repeated, treads::line) == listOf(repeated.first(), repeated.last()))
    val landing = GeometryFixture(listOf(floor, solid(1.0, 0.0, -1.0, 2.0, 1.0, 2.0),
        solid(2.0, 0.0, -1.0, 10.0, 2.0, 2.0)))
    val plateau = listOf(point(.5), point(1.5, 1.0), point(2.5, 2.0), point(8.5, 2.0))
    check(smoothRoomDisplayPath(plateau, landing::line) == listOf(plateau[0], plateau[2], plateau[3]))
    val peak = listOf(point(.5), point(1.5, 1.0), point(2.5))
    check(smoothRoomDisplayPath(peak) { _, _ -> true } == peak)
    val bentStairs = listOf(point(.5), point(1.5, 1.0), point(1.5, 2.0, 1.5))
    check(smoothRoomDisplayPath(bentStairs) { _, _ -> true } == bentStairs)

    val corner = listOf(point(.5), point(.5, z = 3.5), point(3.5, z = 3.5))
    val openCorner = GeometryFixture(listOf(floor, solid(1.0, 0.0, 0.0, 3.0, 3.0, 2.5)))
    check(corner.zipWithNext().all { (a, b) -> openCorner.line(a, b) })
    val curved = smoothRoomDisplayPath(corner, openCorner::line)
    check(curved.first() == corner.first() && curved.last() == corner.last() && curved.size == 7)
    check(corner[1] !in curved && curved.zipWithNext().all { (a, b) -> openCorner.line(a, b) })
    val tightCorner = GeometryFixture(listOf(floor, solid(.8, 0.0, 0.0, 3.0, 3.0, 3.2)))
    check(corner.zipWithNext().all { (a, b) -> tightCorner.line(a, b) })
    check(smoothRoomDisplayPath(corner, tightCorner::line) == corner)
    val sparseSupport = GeometryFixture(listOf(solid(-.5, -1.0, -.5, .34, 0.0, 4.0),
        solid(-.5, -1.0, 3.66, 4.0, 0.0, 4.0)))
    check(corner.zipWithNext().all { (a, b) -> sparseSupport.line(a, b) })
    check(smoothRoomDisplayPath(corner, sparseSupport::line) == corner)
    check(smoothRoomDisplayPath(corner) { _, _ -> error("Unavailable world") } == corner)

    // Caller-delimited target and door endpoints survive even when rounding is otherwise safe.
    val left = smoothRoomDisplayPath(corner.take(2), openCorner::line)
    val right = smoothRoomDisplayPath(corner.drop(1), openCorner::line)
    check(left.last() == corner[1] && right.first() == corner[1])
    check(smoothRoomDisplayPath(emptyList(), openCorner::line).isEmpty())
    check(smoothRoomDisplayPath(listOf(corner[0]), openCorner::line) == listOf(corner[0]))
    val manyCorners = (0..50).map { index -> point((index + 1) / 2 * 2.0, z = index / 2 * 2.0) }
    val bounded = smoothRoomDisplayPath(manyCorners) { a, b -> kotlin.math.hypot(b.x - a.x, b.z - a.z) <= 2.0 }
    check(bounded.size <= manyCorners.size + 32 * 4 && bounded.first() == manyCorners.first() && bounded.last() == manyCorners.last())
}

private fun checkPublishedEdges() {
    val cells = (0..2).map { GridCell(it, 0, 0) }.toSet()
    val scan = RoomLiveGeometry(GridCell(0, 0, 0), cells::contains, cells::contains, { _, _ -> true })
    check(!scan.step(maxEdges = 1, maxNanos = Long.MAX_VALUE))
    val before = requireNotNull(scan.snapshot())
    val revision = scan.revision
    fun back(snapshot: RoomLiveGeometrySnapshot) = requireNotNull(RoomNavigation.create(snapshot.cells,
        snapshot.adjacency, emptyMap(), GridCell(0, 0, 0))).plan(GridCell(1, 0, 0), emptySet())
    check(before.cells.size == 2 && back(before) == null)
    check(!scan.step(maxEdges = 1, maxNanos = Long.MAX_VALUE))
    val after = requireNotNull(scan.snapshot())
    // New checked edges must publish even when no new standing cell has been discovered.
    check(after.cells == before.cells && scan.revision > revision && back(after) != null)
    check(back(before) == null)
}

private fun checkSurfaceHeightsAndProgress() {
    checkPublishedEdges()
    val shapes = GeometryFixture(listOf(
        solid(0.0, -1.0, 0.0, 5.0, 0.0, 1.0),
        solid(1.0, 0.0, 0.0, 2.0, 0.0625, 1.0).copy(carpet = true),
        solid(2.0, 0.0, 0.0, 3.0, 0.5, 1.0), // Bottom slab.
        solid(3.0, 0.0, 0.0, 4.0, 0.5, 1.0),
        solid(3.5, 0.5, 0.0, 4.0, 1.0, 1.0), // Stair's upper tread.
        solid(4.0, 0.0, 0.0, 5.0, 1.0, 1.0),
    ))
    val cells = listOf(GridCell(0, 0, 0), GridCell(1, 0, 0)) + (2..4).map { GridCell(it, 1, 0) }
    val surfaces = cells.associateWith { requireNotNull(shapes.standing(it)) }
    check(surfaces.values.map { it.y } == listOf(0.0, 0.0, 0.5, 1.0, 1.0))
    check(shapes.standing(GridCell(1, 1, 0)) == null) // Carpets never add another grid height.
    val builder = RoomLiveGeometry(cells.first(), { it in cells }, { shapes.standing(it) != null },
        { from, to -> shapes.route(surfaces.getValue(from), surfaces.getValue(to)) }, surfaces::getValue)
    while (!builder.step(maxNanos = Long.MAX_VALUE)) { }
    val complete = requireNotNull(builder.result)
    check(complete.feet == surfaces && complete.cells == cells.toSet())
    val route = requireNotNull(RoomNavigation.create(complete.cells, complete.adjacency,
        mapOf("star" to cells.last()), standingFeet = complete.feet)?.plan(cells.first(), setOf("star")))
    check(route.path == cells && route.cost == 500) // Four horizontal blocks and one actual vertical block.
    check(route.path.zipWithNext().all { (a, b) -> shapes.route(complete.feet.getValue(a), complete.feet.getValue(b)) })

    val seed = GridCell(0, 0, 0)
    val diagonal = GridCell(1, 0, 1)
    val open = GeometryFixture(listOf(solid(-1.0, -1.0, -1.0, 26.0, 0.0, 26.0)))
    val growing = RoomLiveGeometry(seed, { it.x in 0..24 && it.y == 0 && it.z in 0..24 },
        { open.standing(it) != null }, { a, b -> open.route(open.standing(a)!!, open.standing(b)!!) })
    check(!growing.step(maxEdges = 26, maxNanos = Long.MAX_VALUE))
    val partial = requireNotNull(growing.snapshot())
    val firstRoute = requireNotNull(RoomNavigation.create(partial.cells, partial.adjacency,
        mapOf("star" to diagonal), standingFeet = partial.feet)?.plan(seed, setOf("star")))
    check(firstRoute.path == listOf(seed, diagonal) && firstRoute.cost == 141)
    check(partial.cells.size < 625 && growing.result == null) // Guidance does not wait for the whole room.
    val frozenSize = partial.cells.size
    val frozenEdges = partial.adjacency.toMap()
    while (!growing.step(maxNanos = Long.MAX_VALUE)) { }
    check(partial.cells.size == frozenSize && partial.adjacency == frozenEdges && partial.feet.keys == partial.cells)
    check(runCatching { (partial.feet as MutableMap).clear() }.isFailure)
    check(growing.result?.cells?.size == 625)
    check(nearbyRoomGeometry(partial.cells, GridCell(0, -1, 0)))
    check(!nearbyRoomGeometry(partial.cells, GridCell(20, 0, 20)))
    check(growing.observesBlock(GridCell(20, 0, 20)))

    val distant = GridCell(20, 0, 20)
    val focused = RoomLiveGeometry(seed, { it.x in 0..24 && it.y == 0 && it.z in 0..24 },
        { open.standing(it) != null }, { a, b -> open.route(open.standing(a)!!, open.standing(b)!!) })
    focused.prioritize(listOf(distant))
    var early: RoomLiveGeometrySnapshot? = null
    for (tick in 1..20) {
        check(!focused.step(maxNanos = Long.MAX_VALUE))
        early = requireNotNull(focused.snapshot())
        if (distant in early.cells) break
    }
    val directedToTarget = requireNotNull(early)
    check(distant in directedToTarget.cells && directedToTarget.cells.size < 200)
    val focusedPath = requireNotNull(RoomNavigation.create(directedToTarget.cells, directedToTarget.adjacency,
        mapOf("star" to distant), standingFeet = directedToTarget.feet)?.plan(seed, setOf("star")))
    check(focusedPath.cost == 2820 && focusedPath.path.size == 21)
    focused.prioritize(listOf(GridCell(24, 0, 0))) // A new target cannot lose pending flood work.
    while (!focused.step(maxNanos = Long.MAX_VALUE)) { }
    check(focused.result?.cells?.size == 625)

    val corner = GeometryFixture(listOf(solid(-1.0, -1.0, -1.0, 3.0, 0.0, 3.0),
        solid(0.95, 0.0, 0.25, 1.05, 2.0, 0.75)))
    val a = RoomPoint(0.5, 0.0, 0.5)
    val b = RoomPoint(1.5, 0.0, 1.5)
    check(corner.route(a, a) && corner.route(b, b) && !corner.route(a, b))
}

private fun checkLiveFlood() {
    val maze = listOf("S#A", ".#.", "...")
    val seed = GridCell(0, 0, 0)
    val allowed = maze.flatMapIndexed { z, row -> row.mapIndexedNotNull { x, cell ->
        GridCell(x, 0, z).takeIf { cell != '#' }
    } }.toSet()
    val walls = maze.flatMapIndexed { z, row -> row.mapIndexedNotNull { x, cell ->
        if (cell == '#') solid(x.toDouble(), 0.0, z.toDouble(), x + 1.0, 3.0, z + 1.0) else null
    } }
    val geometry = GeometryFixture(listOf(solid(-1.0, -1.0, -1.0, 4.0, 0.0, 4.0)) + walls)
    val reads = hashMapOf<GridCell, Int>()
    var edgeReads = 0
    val builder = RoomLiveGeometry(seed,
        { it.x in 0..2 && it.y == 0 && it.z in 0..2 },
        { cell -> reads[cell] = (reads[cell] ?: 0) + 1; cell in allowed },
        { from, to -> edgeReads++; geometry.route(RoomPoint(from.x + .5, 0.0, from.z + .5),
            RoomPoint(to.x + .5, 0.0, to.z + .5)) })
    var ticks = 0
    do {
        val beforeCells = reads.values.sum()
        val beforeEdges = edgeReads
        val finished = builder.step(maxCells = 2, maxEdges = 3, maxNanos = Long.MAX_VALUE)
        check(reads.values.sum() - beforeCells <= 2 && edgeReads - beforeEdges <= 3)
        if (!finished) check(builder.result == null && builder.status == RoomGeometryStatus.BUILDING)
        check(++ticks < 100)
    } while (!finished)
    val snapshot = requireNotNull(builder.result)
    check(builder.status == RoomGeometryStatus.READY && snapshot.cells == allowed)
    check(reads.values.all { it == 1 })
    check(GridCell(2, 0, 0) !in snapshot.adjacency.getValue(seed)) // Solid wall cells cannot become gap jumps.
    val graph = requireNotNull(RoomNavigation.create(snapshot.cells, snapshot.adjacency,
        mapOf("star" to GridCell(2, 0, 0))))
    check(graph.plan(seed, setOf("star"))?.cost == 6)
    check(runCatching { (snapshot.cells as MutableSet).clear() }.isFailure)
    check(runCatching { (snapshot.adjacency as MutableMap).clear() }.isFailure)
    check(runCatching { (snapshot.adjacency.getValue(seed) as MutableSet).clear() }.isFailure)
    check(builder.step() && builder.result === snapshot)

    val targetInput = linkedMapOf("star" to GridCell(2, 0, 0))
    val shared = RoomGeometry(snapshot, targetInput, seed)
    check(shared.cells === snapshot.cells && shared.adjacency === snapshot.adjacency)
    targetInput.clear()
    check(shared.targets == mapOf("star" to GridCell(2, 0, 0)))
    check(runCatching { (shared.targets as MutableMap).clear() }.isFailure)
    val mutableCells = linkedSetOf(seed, GridCell(1, 0, 0))
    val mutableConnections = linkedSetOf(GridCell(1, 0, 0))
    val mutableAdjacency = linkedMapOf(seed to mutableConnections)
    val authored = RoomGeometry(mutableCells, mutableAdjacency, shared.targets, seed)
    mutableCells.clear(); mutableConnections.clear(); mutableAdjacency.clear()
    check(authored.cells == setOf(seed, GridCell(1, 0, 0)) && authored.adjacency[seed] == setOf(GridCell(1, 0, 0)))

    var slowReads = 0
    val timed = RoomLiveGeometry(seed, { true }, { slowReads++; Thread.sleep(5); true }, { _, _ -> true })
    check(!timed.step(maxNanos = 1_000_000) && slowReads == 1 && timed.result == null)

    val end = GridCell(1, 0, 0)
    val disconnected = RoomLiveGeometry(seed, { it == seed || it == end }, { true }, { _, _ -> false })
    while (!disconnected.step(maxNanos = Long.MAX_VALUE)) { }
    check(disconnected.result?.cells == setOf(seed)) // Safe endpoints do not invent a connection through a wall.
    val oneWay = RoomLiveGeometry(seed, { it == seed || it == end }, { true }, { from, _ -> from == seed })
    while (!oneWay.step(maxNanos = Long.MAX_VALUE)) { }
    check(oneWay.result?.adjacency?.get(seed) == setOf(end) && oneWay.result?.adjacency?.get(end).isNullOrEmpty())
    val unloaded = RoomLiveGeometry(seed, { it == seed || it == end }, { it == seed }, { _, _ -> true })
    while (!unloaded.step(maxNanos = Long.MAX_VALUE)) { }
    check(unloaded.result?.cells == setOf(seed))
    val noStart = RoomLiveGeometry(seed, { true }, { false }, { _, _ -> true })
    check(noStart.step() && noStart.status == RoomGeometryStatus.INCOMPLETE && noStart.result == null)
    val unavailable = RoomLiveGeometry(seed, { true }, { error("Unavailable world") }, { _, _ -> true })
    check(unavailable.step() && unavailable.failure != null && unavailable.result == null)
    val capped = RoomLiveGeometry(seed, { it.y == 0 && it.z == 0 && it.x in 0..RoomNavigation.MAX_NODES },
        { true }, { _, _ -> true })
    while (!capped.step(maxNanos = Long.MAX_VALUE)) { }
    check(capped.status == RoomGeometryStatus.INCOMPLETE && capped.failure?.contains("16384") == true && capped.result == null)
}

fun checkRoomWorldGeometry() {
    checkPathCleanup()
    checkCarpetPolicy()
    checkIronBarsPolicy()
    checkGapJumps()
    checkTeleportSightGuidance()
    checkDisplaySmoothing()
    checkLiveFlood()
    checkSurfaceHeightsAndProgress()
    val floor = solid(-4.0, -1.0, -4.0, 4.0, 0.0, 4.0)
    val start = RoomPoint(0.5, 0.0, 0.5)
    val end = RoomPoint(1.5, 0.0, 0.5)
    val open = GeometryFixture(listOf(floor))
    check(open.route(start, start) && open.route(start, end) && open.route(end, start))
    check(roomConnectorWaypoints(start, end) == listOf(start, end))
    val actualPlayer = RoomPoint(0.22, 0.0, 0.78)
    check(open.route(actualPlayer, end))
    check(requireNotNull(roomConnectorWaypoints(actualPlayer, end)).first() == actualPlayer)

    val wall = GeometryFixture(listOf(floor, solid(0.95, 0.0, 0.0, 1.05, 2.0, 1.0)))
    check(wall.clear(start, start) && wall.clear(end, end))
    check(!wall.route(start, end) && !wall.route(end, start)) // Empty endpoints cannot prove an edge.
    check(!wall.route(actualPlayer, end)) // Player-to-node snapping obeys the same wall sweep.
    val fence = GeometryFixture(listOf(floor, solid(0.9375, 0.0, 0.25, 1.0625, 1.5, 0.75)))
    check(fence.clear(start, start) && fence.clear(end, end) && !fence.route(start, end))
    val ceiling = GeometryFixture(listOf(floor, solid(-4.0, 1.7, -4.0, 4.0, 2.0, 4.0)))
    check(!ceiling.route(start, start) && !ceiling.route(start, end))
    val middleCeiling = GeometryFixture(listOf(floor, solid(0.95, 1.75, 0.0, 1.05, 2.0, 1.0)))
    check(middleCeiling.clear(start, start) && middleCeiling.clear(end, end) && !middleCeiling.route(start, end))

    val gap = GeometryFixture(listOf(
        solid(0.0, -1.0, 0.0, 0.6, 0.0, 1.0),
        solid(1.4, -1.0, 0.0, 2.0, 0.0, 1.0),
    ))
    check(gap.route(start, start) && gap.route(end, end))
    check(gap.route(start, end) && gap.route(end, start)) // Short supported gaps have a checked jump corridor.
    val betweenSamples = GeometryFixture(listOf(
        solid(0.0, -1.0, 0.0, 0.53, 0.0, 1.0),
        solid(1.27, -1.0, 0.0, 2.0, 0.0, 1.0),
    ))
    check(betweenSamples.route(start, start) && betweenSamples.route(end, end))
    check(betweenSamples.route(start, end) && betweenSamples.route(end, start))
    check(!GeometryFixture(emptyList()).route(start, start))
    check(GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, -0.5, 4.0))).route(start, start))
    check(!GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, -0.61, 4.0))).route(start, start))

    val slabFeet = RoomPoint(0.5, 0.5, 0.5)
    val slabNode = RoomPoint(0.5, 1.0, 0.5)
    val slab = GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, 0.5, 4.0)))
    check(slab.route(slabFeet, slabNode) && slab.route(slabNode, slabFeet))
    check(roomConnectorWaypoints(slabFeet, slabNode) == listOf(slabFeet, slabNode))
    val slabWall = GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, 0.5, 4.0),
        solid(0.4, 1.5, 0.4, 0.6, 1.6, 0.6)))
    check(!slabWall.route(slabFeet, slabNode))

    val upper = RoomPoint(1.5, 1.0, 0.5)
    val step = solid(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)
    val stairs = GeometryFixture(listOf(floor, step))
    check(stairs.route(start, upper) && stairs.route(upper, start))
    val upElbow = requireNotNull(roomConnectorWaypoints(start, upper))
    check(upElbow == listOf(start, RoomPoint(0.5, 1.25, 0.5), RoomPoint(1.5, 1.25, 0.5), upper))
    check(upElbow.zipWithNext().all { (a, b) -> stairs.clear(a, b) })
    val downElbow = requireNotNull(roomConnectorWaypoints(upper, start))
    check(downElbow == listOf(upper, RoomPoint(0.5, 1.0, 0.5), start))
    check(downElbow.zipWithNext().all { (a, b) -> stairs.clear(a, b) })
    check(!stairs.clear(start, upper)) // A straight rendered segment cuts through the stair.
    val jumpRoof = GeometryFixture(listOf(floor, step, solid(0.0, 2.9, 0.0, 1.0, 3.2, 1.0)))
    check(jumpRoof.clear(start, start) && jumpRoof.clear(upper, upper))
    check(!jumpRoof.route(start, upper) && jumpRoof.route(upper, start))

    val ladderTop = RoomPoint(0.5, 1.0, 0.5)
    val ladder = GeometryFixture(emptyList(), ladder = { it.x == 0.5 && it.z == 0.5 && it.y in 0.0..1.0 })
    check(ladder.route(start, ladderTop) && ladder.route(ladderTop, start))
    check(roomConnectorWaypoints(start, ladderTop) == listOf(start, ladderTop))
    check(!open.route(start, ladderTop)) // Full-block same-column rises need a ladder, not a guessed jump.
    val brokenLadder = GeometryFixture(emptyList(), ladder = { it.y == 0.0 || it.y == 1.0 })
    check(!brokenLadder.route(start, ladderTop))

    val unloaded = GeometryFixture(listOf(floor), loaded = false)
    check(!unloaded.route(start, end) && unloaded.worldReads == 0)
    val invalid = GeometryFixture(listOf(floor))
    check(!invalid.route(start, RoomPoint(3.51, 0.0, 0.5)))
    check(!invalid.route(start, RoomPoint(0.5, 2.0, 0.5)))
    check(!invalid.route(RoomPoint(30_000_000.0, 0.0, 0.5), start) && invalid.worldReads == 0)
    check(!validRoomConnector(start, end, { _, _ -> true }, { _, _ -> error("Unavailable shape") },
        { _, _, _ -> true }, { false }))

    val template = RoomTemplate("synthetic-only", setOf("F1"),
        RoomBounds(GridCell(0, 0, 0), GridCell(2, 1, 2)), emptyList(),
        setOf(GridCell(0, 0, 0)), emptyMap(), emptyMap(), GridCell(0, 0, 0), emptyMap())
    for (turn in 0..3) {
        val transform = RoomTransform(GridCell(-160, 64, 224), turn)
        fun rotate(point: RoomPoint) = transform.toWorld(RoomPoint(point.x - 0.5, point.y, point.z - 0.5))
            .let { RoomPoint(it.x + 0.5, it.y, it.z + 0.5) }
        fun rotate(box: SolidBox): SolidBox {
            val a = rotate(box.min)
            val b = rotate(box.max)
            return solid(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
                maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        }
        val rotatedStart = rotate(start)
        val rotatedEnd = rotate(end)
        check(GeometryFixture(listOf(rotate(floor))).route(rotate(actualPlayer), rotatedEnd))
        check(!GeometryFixture(listOf(rotate(floor), rotate(solid(0.95, 0.0, 0.0, 1.05, 2.0, 1.0))))
            .route(rotatedStart, rotatedEnd))
        check(GeometryFixture(listOf(rotate(floor), rotate(step))).route(rotatedStart, rotate(upper)))
        val bounds = RoomWorldGeometry.geometryBounds(DetectedRoom(template, transform))
        for (cell in listOf(template.bounds.min, template.bounds.max)) {
            val world = transform.toWorld(cell)
            // Changes to support, side collision shapes and the block above jump headroom all invalidate.
            check(bounds.contains(world.x - 1.0, world.y - 2.0, world.z - 1.0))
            check(bounds.contains(world.x + 1.0, world.y + 3.0, world.z + 1.0))
        }
    }

    val center = GridCell(0, 0, 0)
    val candidates = (-1..1).flatMap { x -> (-1..1).flatMap { y -> (-1..1).map { z -> GridCell(x, y, z) } } }.toSet()
    val connections = roomCaptureEdges(candidates).toList()
    val next = connections.filter { it.first == center }.map { it.second }.toSet()
    check(next.size == 14 && GridCell(0, 1, 0) in next && GridCell(1, 1, 0) in next)
    check(center !in next && GridCell(1, 0, 1) !in next && GridCell(1, 1, 1) !in next)
    check(connections.all { (from, to) -> to to from in connections })
    check(roomCaptureEdges(setOf(center, GridCell(2, 0, 0))).none()) // No connector across missing candidate cells.

    println("Room world geometry checks passed: wall/fence sweeps, ignored decorations, supported jumps, headroom, stairs, ladders, unloaded geometry, player starts and rotations")
}

fun main() = checkRoomWorldGeometry()
