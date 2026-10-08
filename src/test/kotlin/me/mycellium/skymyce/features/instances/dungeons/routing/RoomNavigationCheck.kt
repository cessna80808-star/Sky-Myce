package me.mycellium.skymyce.features.instances.dungeons.routing

import java.util.ArrayDeque
import kotlin.math.abs

private data class Fixture(
    val cells: List<GridCell>,
    val adjacency: Map<GridCell, Collection<GridCell>>,
    val targets: Map<String, GridCell>,
    val healer: GridCell,
    val navigation: RoomNavigation
)

private fun fixture(cells: List<GridCell>, adjacency: Map<GridCell, Collection<GridCell>>, targets: Map<String, GridCell>, healer: GridCell) =
    Fixture(cells, adjacency, targets, healer, requireNotNull(RoomNavigation.create(cells, adjacency, targets, healer)))

private fun line(size: Int, targets: Map<String, Int>, healer: Int): Fixture {
    val cells = (0 until size).map { GridCell(it, 0, 0) }
    val adjacency = cells.withIndex().associate { (index, cell) ->
        cell to listOfNotNull(cells.getOrNull(index - 1), cells.getOrNull(index + 1))
    }
    return fixture(cells, adjacency, targets.mapValues { cells[it.value] }, cells[healer])
}

private fun maze(vararg rows: String): Pair<Fixture, GridCell> {
    check(rows.isNotEmpty() && rows.all { it.length == rows.first().length })
    val cells = ArrayList<GridCell>()
    val labels = HashMap<Char, GridCell>()
    for ((z, row) in rows.withIndex()) for ((x, symbol) in row.withIndex()) {
        if (symbol == '#') continue
        val cell = GridCell(x, 0, z)
        cells.add(cell)
        if (symbol != '.') check(labels.put(symbol, cell) == null)
    }
    val available = cells.toSet()
    val adjacency = cells.associateWith { cell -> listOf(
        GridCell(cell.x - 1, 0, cell.z), GridCell(cell.x + 1, 0, cell.z),
        GridCell(cell.x, 0, cell.z - 1), GridCell(cell.x, 0, cell.z + 1)
    ).filter(available::contains) }
    val targets = labels.filterKeys { it != 'S' && it != 'H' }.mapKeys { it.key.toString() }
    return fixture(cells, adjacency, targets, labels.getValue('H')) to labels.getValue('S')
}

private fun verifyPath(fixture: Fixture, start: GridCell, remaining: Set<String>, route: RoomRoute) {
    check(route.path.first() == start && route.path.last() == fixture.healer)
    check(route.cost == route.path.zipWithNext().sumOf { (from, to) ->
        abs(from.x - to.x) + abs(from.y - to.y) + abs(from.z - to.z)
    })
    check(route.targetOrder.toSet() == remaining && route.targetOrder.size == remaining.size)
    check(route.path.all { it in fixture.cells })
    check(route.path.zipWithNext().all { (from, to) -> to in fixture.adjacency[from].orEmpty() })
    var visited = 0
    for (id in route.targetOrder) {
        visited = (visited until route.path.size).firstOrNull { route.path[it] == fixture.targets.getValue(id) }
            ?: error("The route skips target $id")
    }
}

/** Independent exhaustive-order oracle; tests include the final healer distance. */
private fun minimumCost(fixture: Fixture, start: GridCell, remaining: Set<String>): Int? {
    val distances = HashMap<Pair<GridCell, GridCell>, Int?>()
    fun distance(from: GridCell, to: GridCell): Int? = distances.getOrPut(from to to) {
        val queue = ArrayDeque<GridCell>()
        val costs = hashMapOf(from to 0)
        queue.add(from)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (next in fixture.adjacency[cell].orEmpty()) {
                val candidate = costs.getValue(cell) + abs(cell.x - next.x) + abs(cell.y - next.y) + abs(cell.z - next.z)
                if (candidate >= (costs[next] ?: Int.MAX_VALUE)) continue
                costs[next] = candidate
                queue.add(next)
            }
        }
        costs[to]
    }
    fun visit(from: GridCell, left: Set<String>): Int? {
        if (left.isEmpty()) return distance(from, fixture.healer)
        var best: Int? = null
        for (id in left) {
            val next = fixture.targets.getValue(id)
            val first = distance(from, next) ?: continue
            val rest = visit(next, left - id) ?: continue
            val cost = first + rest
            if (best == null || cost < best) best = cost
        }
        return best
    }
    return visit(start, remaining)
}

private fun checkRotations() {
    val cell = GridCell(3, 2, -5)
    val expected = listOf(cell, GridCell(5, 2, 3), GridCell(-3, 2, 5), GridCell(-5, 2, -3))
    for (origin in listOf(GridCell(-105, 73, 230), GridCell(250, -64, -301))) {
        for (turns in 0..3) {
            val transform = RoomTransform(origin, turns)
            val rotated = expected[turns]
            val world = GridCell(origin.x + rotated.x, origin.y + rotated.y, origin.z + rotated.z)
            check(transform.toWorld(cell) == world && transform.toLocal(world) == cell)
            val point = RoomPoint(0.125, 2.75, -3.5)
            val transformed = transform.toWorld(point)
            val restored = transform.toLocal(transformed)
            check(abs(restored.x - point.x) < 1e-9 && abs(restored.y - point.y) < 1e-9 && abs(restored.z - point.z) < 1e-9)
            check(transform.toWorld(RoomPoint(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())) ==
                RoomPoint(world.x.toDouble(), world.y.toDouble(), world.z.toDouble()))
        }
    }
    check(runCatching { RoomTransform(GridCell(0, 0, 0), 4) }.isFailure)
    check(runCatching { RoomPoint(Double.NaN, 0.0, 0.0) }.isFailure)
    check(runCatching { RoomPoint(0.0, Double.POSITIVE_INFINITY, 0.0) }.isFailure)
    check(runCatching { RoomTransform(GridCell(Int.MAX_VALUE, 0, 0), 0).toWorld(GridCell(1, 0, 0)) }.isFailure)
    check(runCatching { RoomTransform(GridCell(0, 0, 0), 1).toWorld(GridCell(0, 0, Int.MIN_VALUE)) }.isFailure)
}

private fun checkOptimalRoutes() {
    val (wall, start) = maze("S#A", ".#.", "...", "H..")
    val detour = requireNotNull(wall.navigation.plan(start, wall.targets.keys))
    check(detour.cost == 11) // Two horizontal blocks cannot be traversed through the wall.
    verifyPath(wall, start, wall.targets.keys, detour)

    val straight = line(5, mapOf("A" to 1, "B" to 4), 0)
    val route = requireNotNull(straight.navigation.plan(straight.cells[2], straight.targets.keys))
    check(route.targetOrder == listOf("B", "A") && route.cost == 6)
    val nearestFirstCost = 1 + 3 + 4
    check(route.cost < nearestFirstCost) // Greedy A-first is worse once the healer is included.
    verifyPath(straight, straight.cells[2], straight.targets.keys, route)
    for (cell in straight.cells) for (remaining in listOf(emptySet(), setOf("A"), setOf("B"), setOf("A", "B"))) {
        val fromActualPosition = requireNotNull(straight.navigation.plan(cell, remaining))
        check(fromActualPosition.cost == minimumCost(straight, cell, remaining))
        verifyPath(straight, cell, remaining, fromActualPosition)
    }
    val healerOnly = requireNotNull(straight.navigation.plan(straight.cells[2], emptySet()))
    check(healerOnly.targetOrder.isEmpty() && healerOnly.cost == 2)
    val alreadyThere = requireNotNull(straight.navigation.plan(straight.healer, emptySet()))
    check(alreadyThere.path == listOf(straight.healer) && alreadyThere.cost == 0)

    val (room, _) = maze("S..#..A", ".#.#.#.", "B#...#H", ".#.##..", "..C...D")
    val ids = room.targets.keys.sorted()
    for (mask in 0 until (1 shl ids.size)) {
        val remaining = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        for (position in room.cells) {
            val result = room.navigation.plan(position, remaining)
            val expected = minimumCost(room, position, remaining)
            check(result?.cost == expected) { "Total route differs from exhaustive target orders" }
            if (result != null) verifyPath(room, position, remaining, result)
        }
    }
    val reordered = fixture(room.cells.reversed(), room.adjacency.mapValues { it.value.reversed() }, room.targets.toList().reversed().toMap(), room.healer)
    val position = room.cells.first()
    check(room.navigation.plan(position, room.targets.keys) == reordered.navigation.plan(position, reordered.targets.keys))
}

private fun checkElevationsAndDirection() {
    val cells = listOf(GridCell(0, 0, 0), GridCell(1, 1, 0), GridCell(2, 1, 0), GridCell(3, 0, 0))
    val adjacency = cells.withIndex().associate { (index, cell) -> cell to listOfNotNull(cells.getOrNull(index - 1), cells.getOrNull(index + 1)) }
    val elevation = fixture(cells, adjacency, mapOf("upper" to cells[2]), cells.last())
    val route = requireNotNull(elevation.navigation.plan(cells.first(), elevation.targets.keys))
    check(route.path == cells && route.cost == 5)
    verifyPath(elevation, cells.first(), elevation.targets.keys, route)

    // Four uphill/downhill edges cost eight blocks; the six-edge level corridor is cheaper.
    val slope = listOf(GridCell(0, 0, 0), GridCell(1, 1, 0), GridCell(2, 2, 0), GridCell(3, 1, 0), GridCell(4, 0, 0))
    val level = listOf(slope.first()) + (0..4).map { GridCell(it, 0, 1) } + slope.last()
    val paths = listOf(slope, level)
    val walkable = paths.flatten().distinct()
    val edges = walkable.associateWith { cell ->
        paths.flatMap { it.zipWithNext() }.flatMap { (from, to) ->
            when (cell) { from -> listOf(to); to -> listOf(from); else -> emptyList() }
        }.distinct()
    }
    val practical = fixture(walkable, edges, mapOf("A" to slope.last()), slope.first())
    val levelRoute = requireNotNull(practical.navigation.plan(slope.first(), practical.targets.keys))
    check(levelRoute.cost == 12 && levelRoute.path.all { it.y == 0 })
    verifyPath(practical, slope.first(), practical.targets.keys, levelRoute)
    for (position in walkable) for (remaining in listOf(emptySet(), setOf("A"))) {
        val result = requireNotNull(practical.navigation.plan(position, remaining))
        check(result.cost == minimumCost(practical, position, remaining))
        verifyPath(practical, position, remaining, result)
    }
    val multiple = fixture(walkable, edges, mapOf("A" to slope.last(), "B" to slope[2], "C" to level[3]), slope.first())
    val ids = multiple.targets.keys.toList()
    for (mask in 0 until (1 shl ids.size)) for (position in walkable) {
        val remaining = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        val result = requireNotNull(multiple.navigation.plan(position, remaining))
        check(result.cost == minimumCost(multiple, position, remaining))
        verifyPath(multiple, position, remaining, result)
    }

    val ladderCells = (0..3).map { GridCell(0, it, 0) }
    val ladder = fixture(ladderCells, ladderCells.withIndex().associate { (index, cell) ->
        cell to listOfNotNull(ladderCells.getOrNull(index + 1))
    }, emptyMap(), ladderCells.last())
    check(ladder.navigation.plan(ladderCells.first(), emptySet())?.cost == 3)
    check(ladder.navigation.plan(ladderCells.last(), emptySet())?.cost == 0)

    // Forward target searches are necessary: reversed terminal searches give incorrect reachability.
    val forward = (0..4).map { GridCell(it, 0, 0) }
    val oneWay = fixture(forward, forward.withIndex().associate { (index, cell) -> cell to listOfNotNull(forward.getOrNull(index + 1)) },
        mapOf("A" to forward[2], "B" to forward[1]), forward.last())
    val directed = requireNotNull(oneWay.navigation.plan(forward.first(), oneWay.targets.keys, listOf("A", "B")))
    check(directed.targetOrder == listOf("B", "A") && directed.cost == 4)
    verifyPath(oneWay, forward.first(), oneWay.targets.keys, directed)
    check(oneWay.navigation.plan(forward[2], oneWay.targets.keys) == null)
}

private fun checkStability() {
    val room = line(5, mapOf("A" to 1, "B" to 4), 0)
    val previous = listOf("A", "B")
    val smallChange = requireNotNull(room.navigation.plan(room.cells[2], room.targets.keys, previous, 3))
    check(smallChange.targetOrder == previous && smallChange.cost == 8)
    check(room.navigation.plan(room.cells[2], room.targets.keys, previous, 2)?.targetOrder == previous)
    check(room.navigation.plan(room.cells[2], room.targets.keys, previous, 1)?.targetOrder == listOf("B", "A"))
    check(room.navigation.plan(room.cells[3], room.targets.keys, previous, 3)?.targetOrder == listOf("B", "A"))
    val partlyCleared = requireNotNull(room.navigation.plan(room.cells[4], setOf("A"), listOf("B", "A")))
    check(partlyCleared.targetOrder == listOf("A") && partlyCleared.cost == 4)
    check(room.navigation.plan(room.cells[2], room.targets.keys, listOf("A", "A", "B"))?.targetOrder == listOf("B", "A"))
    check(room.navigation.plan(room.cells[2], room.targets.keys, listOf("unknown", "A", "B"))?.targetOrder == listOf("B", "A"))
    check(room.navigation.plan(room.cells[2], room.targets.keys, minimumImprovement = -1) == null)
}

private fun checkLiveStart() {
    val room = line(24, mapOf("A" to 10, "B" to 18), 23)
    val route = requireNotNull(room.navigation.plan(room.cells.first(), room.targets.keys))
    fun squared(cell: GridCell, x: Double) = (cell.x - x) * (cell.x - x)
    check(route.liveStart(0, room.targets, { true }) { squared(it, 5.0) } == 5)
    check(route.liveStart(0, room.targets, { true }) { squared(it, 9.0) } == 8) // Forward window is bounded.
    check(route.liveStart(8, room.targets, { true }) { squared(it, 18.0) } == 10) // Never skip A to reach B.
    check(route.liveStart(8, room.targets, { true }) { squared(it, 5.0) } == 5)
    check(route.liveStart(8, room.targets, { true }) { squared(it, 0.0) } == 4) // Backward window is bounded.
    check(route.liveStart(Int.MAX_VALUE, room.targets, { true }) { squared(it, 23.0) } == 10)
    check(route.liveStart(Int.MIN_VALUE, room.targets, { true }) { squared(it, 0.0) } == 0)
    check(route.liveStart(4, room.targets, { false }) { squared(it, 4.0) } == null)
    check(route.liveStart(4, emptyMap(), { true }) { 0.0 } == null)
    check(route.copy(path = emptyList()).liveStart(0, room.targets, { true }) { 0.0 } == null)
    check(route.copy(path = room.cells.take(5)).liveStart(0, room.targets, { true }) { 0.0 } == null)
    check(route.liveStart(4, room.targets, { true }) { Double.NaN } == null)
    check(route.liveStart(4, room.targets, { true }) { Double.POSITIVE_INFINITY } == null)
    check(route.liveStart(4, room.targets, { true }) { -1.0 } == null)
    check(route.liveStart(4, room.targets, { true }) { 1.0 } == 4) // Equal distances retain current progress.

    val healerOnly = requireNotNull(room.navigation.plan(room.cells.first(), emptySet()))
    check(healerOnly.liveStart(15, room.targets, { true }) { squared(it, 23.0) } == 23)
    check(healerOnly.liveStart(23, emptyMap(), { true }) { squared(it, 20.0) } == 20)

    val (wall, start) = maze("S#A", ".#.", "...", "H..")
    val detour = requireNotNull(wall.navigation.plan(start, wall.targets.keys))
    val rejected = mutableSetOf<GridCell>()
    val selected = detour.liveStart(0, wall.targets, { cell ->
        if (cell.x == 2) { rejected.add(cell); false } else true
    }) { cell -> if (cell.x == 2) 0.0 else 1.0 + cell.z }
    check(rejected.isNotEmpty() && selected == 0) // Nearby nodes behind the wall require a safe connector.
    check(detour.liveStart(0, wall.targets, { false }) { 0.0 } == null)
}

private fun checkValidationAndSnapshots() {
    val start = GridCell(0, 0, 0)
    val target = GridCell(3, 0, 0)
    val healer = GridCell(4, 0, 0)
    val disconnected = fixture(listOf(start, target, healer), mapOf(target to listOf(healer), healer to listOf(target)), mapOf("A" to target), healer)
    check(disconnected.navigation.plan(start, setOf("A")) == null)
    check(disconnected.navigation.plan(start, emptySet()) == null)
    check(disconnected.navigation.plan(target, setOf("missing")) == null)
    check(disconnected.navigation.plan(GridCell(2, 0, 0), emptySet()) == null)
    val blockedTarget = fixture(listOf(start, target), emptyMap(), mapOf("A" to target), start)
    check(blockedTarget.navigation.plan(start, setOf("A")) == null)
    check(blockedTarget.navigation.plan(start, emptySet())?.cost == 0) // A completed inaccessible target no longer blocks the healer.
    check(RoomNavigation.create(listOf(start), emptyMap(), mapOf("starred.alpha" to start), start)
        ?.plan(start, setOf("starred.alpha"))?.targetOrder == listOf("starred.alpha"))
    check(RoomNavigation.create(emptyList(), emptyMap(), emptyMap(), start) == null)
    check(RoomNavigation.create(listOf(start, start), emptyMap(), emptyMap(), start) == null)
    check(RoomNavigation.create(listOf(start), emptyMap(), emptyMap(), healer) == null)
    check(RoomNavigation.create(listOf(start), emptyMap(), mapOf("missing" to target), start) == null)
    check(RoomNavigation.create(listOf(start), emptyMap(), mapOf("" to start), start) == null)
    check(RoomNavigation.create(listOf(start), mapOf(healer to emptyList()), emptyMap(), start) == null)
    check(RoomNavigation.create(listOf(start), mapOf(start to listOf(healer)), emptyMap(), start) == null)
    for (unsafe in listOf(GridCell(2, 0, 0), GridCell(1, 0, 1), GridCell(0, 2, 0), GridCell(1, 2, 0))) {
        check(RoomNavigation.create(listOf(start, unsafe), mapOf(start to listOf(unsafe)), emptyMap(), unsafe) == null)
    }
    check(RoomNavigation.create(listOf(start), mapOf(start to listOf(start)), emptyMap(), start) == null)
    val neighbor = GridCell(1, 0, 0)
    check(RoomNavigation.create(listOf(start, neighbor), mapOf(start to listOf(neighbor, neighbor)), emptyMap(), start) == null)
    check(RoomNavigation.create(listOf(start), emptyMap(), (0..RoomNavigation.MAX_TARGETS).associate { "target$it" to start }, start) == null)
    check(RoomNavigation.create((0..RoomNavigation.MAX_NODES).map { GridCell(it, 0, 0) }, emptyMap(), emptyMap(), start) == null)

    val inputCells = (0..4).map { GridCell(it, 0, 0) }.toMutableList()
    val inputEdges = inputCells.withIndex().associate { (index, cell) -> cell to listOfNotNull(inputCells.getOrNull(index - 1), inputCells.getOrNull(index + 1)).toMutableList() }.toMutableMap()
    val inputTargets = mutableMapOf("A" to inputCells[1], "B" to inputCells[4])
    val navigation = requireNotNull(RoomNavigation.create(inputCells, inputEdges, inputTargets, inputCells[0]))
    inputCells.clear(); inputEdges.values.forEach { it.clear() }; inputEdges.clear(); inputTargets.clear()
    val retained = requireNotNull(navigation.plan(GridCell(2, 0, 0), setOf("A", "B")))
    check(retained.cost == 6 && navigation.targets.size == 2 && navigation.cells.size == 5)
    check(runCatching { (retained.path as MutableList).add(start) }.isFailure)
    check(runCatching { (retained.targetOrder as MutableList).clear() }.isFailure)
    check(runCatching { (navigation.targets as MutableMap).clear() }.isFailure)
    check(runCatching { (navigation.cells as MutableList).clear() }.isFailure)

    val maximumTargets = line(14, (1..RoomNavigation.MAX_TARGETS).associate { "star-${it.toString().padStart(2, '0')}" to it }, 13)
    val bounded = requireNotNull(maximumTargets.navigation.plan(maximumTargets.cells.first(), maximumTargets.targets.keys))
    check(bounded.cost == 13 && bounded.targetOrder.size == RoomNavigation.MAX_TARGETS)
    verifyPath(maximumTargets, maximumTargets.cells.first(), maximumTargets.targets.keys, bounded)
    val maximumNodes = line(RoomNavigation.MAX_NODES, emptyMap(), RoomNavigation.MAX_NODES - 1)
    check(maximumNodes.navigation.plan(maximumNodes.cells.first(), emptySet())?.cost == RoomNavigation.MAX_NODES - 1)
}

fun checkRoomNavigation() {
    checkRotations()
    checkOptimalRoutes()
    checkElevationsAndDirection()
    checkStability()
    checkLiveStart()
    checkValidationAndSnapshots()
    println("Room navigation checks passed: supplied safe geometry, four rotations, wall detours, exact total-route optimization, healer, weighted elevations, directed paths, bounded inputs and stable replanning")
}

fun main() = checkRoomNavigation()
