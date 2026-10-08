package me.mycellium.skymyce.features.instances.dungeons.routing

/** Synthetic collision shapes only: none of these coordinates describe a real dungeon room. */
private data class SolidBox(val min: RoomPoint, val max: RoomPoint)

private fun solid(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) =
    SolidBox(RoomPoint(x1, y1, z1), RoomPoint(x2, y2, z2))

private class GeometryFixture(
    private val solids: List<SolidBox>,
    private val loaded: Boolean = true,
    private val ladder: (RoomPoint) -> Boolean = { false },
) {
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
        supported = { point, depth, radius ->
            worldReads++
            solids.any { box ->
                box.max.y <= point.y && box.max.y > point.y - depth &&
                    box.min.x < point.x + radius && box.max.x > point.x - radius &&
                    box.min.z < point.z + radius && box.max.z > point.z - radius
            }
        },
        climbable = { point -> worldReads++; ladder(point) })
}

private fun checkLiveFlood() {
    val maze = listOf("S#A", ".#.", "...")
    val seed = GridCell(0, 0, 0)
    val allowed = maze.flatMapIndexed { z, row -> row.mapIndexedNotNull { x, cell ->
        GridCell(x, 0, z).takeIf { cell != '#' }
    } }.toSet()
    val reads = hashMapOf<GridCell, Int>()
    var edgeReads = 0
    val builder = RoomLiveGeometry(seed,
        { it.x in 0..2 && it.y == 0 && it.z in 0..2 },
        { cell -> reads[cell] = (reads[cell] ?: 0) + 1; cell in allowed },
        { from, to -> edgeReads++; from in allowed && to in allowed })
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
    checkLiveFlood()
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
    check(!gap.route(start, end)) // A hole between supported endpoints still invalidates walking.
    val betweenSamples = GeometryFixture(listOf(
        solid(0.0, -1.0, 0.0, 0.53, 0.0, 1.0),
        solid(1.27, -1.0, 0.0, 2.0, 0.0, 1.0),
    ))
    check(betweenSamples.route(start, start) && betweenSamples.route(end, end))
    check(!betweenSamples.route(start, end) && !betweenSamples.route(end, start))
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
    check(!invalid.route(start, RoomPoint(2.51, 0.0, 0.5)))
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

    println("Room world geometry checks passed: wall/fence sweeps, headroom, continuous support, stairs, ladders, unloaded geometry, player starts and rotations")
}

fun main() = checkRoomWorldGeometry()
