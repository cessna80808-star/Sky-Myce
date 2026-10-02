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
        supported = { point, depth ->
            worldReads++
            solids.any { box ->
                box.max.y <= point.y && box.max.y > point.y - depth &&
                    box.min.x < point.x + 0.3 && box.max.x > point.x - 0.3 &&
                    box.min.z < point.z + 0.3 && box.max.z > point.z - 0.3
            }
        },
        climbable = { point -> worldReads++; ladder(point) })
}

fun checkRoomWorldGeometry() {
    val floor = solid(-4.0, -1.0, -4.0, 4.0, 0.0, 4.0)
    val start = RoomPoint(0.5, 0.0, 0.5)
    val end = RoomPoint(1.5, 0.0, 0.5)
    val open = GeometryFixture(listOf(floor))
    check(open.route(start, start) && open.route(start, end) && open.route(end, start))
    check(roomConnectorWaypoints(start, end) == listOf(start, end))

    val wall = GeometryFixture(listOf(floor, solid(0.95, 0.0, 0.0, 1.05, 2.0, 1.0)))
    check(wall.clear(start, start) && wall.clear(end, end))
    check(!wall.route(start, end) && !wall.route(end, start)) // Empty endpoints cannot prove an edge.
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
    check(!GeometryFixture(emptyList()).route(start, start))
    check(GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, -0.5, 4.0))).route(start, start))
    check(!GeometryFixture(listOf(solid(-4.0, -1.0, -4.0, 4.0, -0.61, 4.0))).route(start, start))

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
    check(!open.route(start, ladderTop)) // Same-column rises require a ladder, not a guessed jump.
    val brokenLadder = GeometryFixture(emptyList(), ladder = { it.y == 0.0 || it.y == 1.0 })
    check(!brokenLadder.route(start, ladderTop))

    val unloaded = GeometryFixture(listOf(floor), loaded = false)
    check(!unloaded.route(start, end) && unloaded.worldReads == 0)
    val invalid = GeometryFixture(listOf(floor))
    check(!invalid.route(start, RoomPoint(2.51, 0.0, 0.5)))
    check(!invalid.route(start, RoomPoint(0.5, 2.0, 0.5)))
    check(!invalid.route(RoomPoint(30_000_000.0, 0.0, 0.5), start) && invalid.worldReads == 0)
    check(!validRoomConnector(start, end, { _, _ -> true }, { _, _ -> error("Unavailable shape") },
        { _, _ -> true }, { false }))

    println("Room world geometry checks passed: wall/fence sweeps, headroom, supports, stairs, ladders, unloaded geometry and render elbows")
}

fun main() = checkRoomWorldGeometry()
