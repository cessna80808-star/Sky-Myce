package me.mycellium.skymyce.features.instances.dungeons.routing

import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class GridCell(val x: Int, val y: Int, val z: Int)

data class RoomPoint(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

/** Positive quarter turns map local +X to world +Z; rotations are about the supplied origin. */
class RoomTransform(val origin: GridCell, val quarterTurns: Int) {
    init { require(quarterTurns in 0..3) }

    fun toWorld(local: GridCell): GridCell {
        val rotated = rotate(local, quarterTurns)
        return GridCell(Math.addExact(origin.x, rotated.x), Math.addExact(origin.y, rotated.y), Math.addExact(origin.z, rotated.z))
    }

    fun toLocal(world: GridCell): GridCell = rotate(GridCell(
        Math.subtractExact(world.x, origin.x), Math.subtractExact(world.y, origin.y), Math.subtractExact(world.z, origin.z)
    ), (4 - quarterTurns) % 4)

    fun toWorld(local: RoomPoint): RoomPoint {
        val rotated = rotate(local, quarterTurns)
        return RoomPoint(origin.x + rotated.x, origin.y + rotated.y, origin.z + rotated.z)
    }

    fun toLocal(world: RoomPoint): RoomPoint = rotate(RoomPoint(
        world.x - origin.x, world.y - origin.y, world.z - origin.z
    ), (4 - quarterTurns) % 4)

    private fun rotate(cell: GridCell, turns: Int): GridCell = when (turns) {
        0 -> cell
        1 -> GridCell(Math.negateExact(cell.z), cell.y, cell.x)
        2 -> GridCell(Math.negateExact(cell.x), cell.y, Math.negateExact(cell.z))
        else -> GridCell(cell.z, cell.y, Math.negateExact(cell.x))
    }

    private fun rotate(point: RoomPoint, turns: Int): RoomPoint = when (turns) {
        0 -> point
        1 -> RoomPoint(-point.z, point.y, point.x)
        2 -> RoomPoint(-point.x, point.y, -point.z)
        else -> RoomPoint(point.z, point.y, -point.x)
    }
}

/** Cost uses the planner's costScale, including an optional final endpoint. */
@ConsistentCopyVisibility
data class RoomRoute internal constructor(val targetOrder: List<String>, val path: List<GridCell>, val cost: Int)

/** A live snapshot may drop or move a target before the replacement route is ready. */
internal fun RoomRoute.targetStops(targets: Map<String, GridCell>): List<Int>? {
    if (path.isEmpty()) return null
    val stops = ArrayList<Int>(targetOrder.size + 1)
    var from = 0
    for (id in targetOrder) {
        val target = targets[id] ?: return null
        val index = path.subList(from, path.size).indexOf(target)
        if (index < 0) return null
        from += index
        stops.add(from)
    }
    stops.add(path.lastIndex)
    return stops
}

/** Selects a nearby route suffix only through a caller-validated connector, before the next target. */
fun RoomRoute.liveStart(
    previousCursor: Int,
    targets: Map<String, GridCell>,
    canConnect: (GridCell) -> Boolean,
    distanceSquared: (GridCell) -> Double
): Int? {
    if (path.isEmpty() || targetOrder.any { it !in targets }) return null
    val end = if (targetOrder.isEmpty()) path.lastIndex else path.indexOf(targets[targetOrder.first()] ?: return null)
    if (end < 0) return null
    val cursor = previousCursor.coerceIn(0, end)
    var best = -1
    var nearest = Double.POSITIVE_INFINITY
    for (index in maxOf(0, cursor - 4)..minOf(end, cursor + 8)) {
        val cell = path[index]
        val distance = distanceSquared(cell)
        if (distance.isFinite() && distance >= 0 &&
            (distance < nearest || distance == nearest && abs(index - cursor) < abs(best - cursor)) && canConnect(cell)) {
            best = index
            nearest = distance
        }
    }
    return best.takeIf { it >= 0 }
}

/**
 * Immutable room-local graph. The caller supplies every safe directed connection after
 * checking player clearance, supports, doors and loaded geometry. This class never infers
 * a connection across a wall or a larger elevation change.
 */
class RoomNavigation private constructor(
    val cells: List<GridCell>,
    val targets: Map<String, GridCell>,
    val healer: GridCell?,
    private val indices: Map<GridCell, Int>,
    private val adjacent: Array<IntArray>,
    private val standingFeet: Map<GridCell, RoomPoint>,
) {
    val costScale = if (standingFeet.isEmpty()) 1 else 100
    private class Search(val distances: IntArray, val parents: IntArray, val arrivals: IntArray)
    private data class SearchNode(val state: Int, val distance: Int, val turns: Int)
    private val targetNodes = targets.mapValues { indices.getValue(it.value) }
    private val healerNode = healer?.let(indices::getValue)
    private val fixedSearches by lazy { targetNodes.values.distinct().associateWith(::search) }

    companion object {
        const val MAX_NODES = 16_384
        const val MAX_TARGETS = 12
        private const val MAX_NEIGHBORS = 34
        private const val HEADINGS = 27
        private const val NO_HEADING = 13
        private const val INFINITE = Int.MAX_VALUE / 4
        private val targetId = Regex("[A-Za-z0-9_.-]{1,64}")
        private val cellOrder = compareBy<GridCell>({ it.x }, { it.y }, { it.z })

        /** Malformed/incomplete geometry produces no planner; no real room data is inferred. */
        fun create(
            cells: Collection<GridCell>,
            adjacency: Map<GridCell, Collection<GridCell>>,
            targets: Map<String, GridCell>,
            healer: GridCell? = null,
            standingFeet: Map<GridCell, RoomPoint> = emptyMap(),
        ): RoomNavigation? {
            if (cells.isEmpty() || cells.size > MAX_NODES || targets.size > MAX_TARGETS || adjacency.size > cells.size) return null
            val nodes = cells.toSet()
            if (nodes.size != cells.size || healer != null && healer !in nodes ||
                targets.any { !targetId.matches(it.key) || it.value !in nodes }) return null
            if (standingFeet.isNotEmpty() && (standingFeet.keys != nodes || standingFeet.any { (cell, point) ->
                abs(point.x - cell.x - 0.5) > 0.00001 || abs(point.z - cell.z - 0.5) > 0.00001 ||
                    point.y <= cell.y - 1.0 || point.y > cell.y + 0.00001
            })) return null
            if (adjacency.any { (from, next) ->
                from !in nodes || next.size > MAX_NEIGHBORS || next.toSet().size != next.size ||
                    next.any { it !in nodes || !localConnection(from, it) }
            }) return null
            val ordered = cells.sortedWith(cellOrder)
            val indices = ordered.withIndex().associate { it.value to it.index }
            val adjacent = Array(ordered.size) { index ->
                adjacency[ordered[index]].orEmpty().map { indices.getValue(it) }.sorted().toIntArray()
            }
            return RoomNavigation(immutable(ordered), Collections.unmodifiableMap(targets.toSortedMap()), healer, indices, adjacent,
                Collections.unmodifiableMap(LinkedHashMap(standingFeet)))
        }

        private fun localConnection(from: GridCell, to: GridCell): Boolean {
            val dx = abs(from.x.toLong() - to.x)
            val dz = abs(from.z.toLong() - to.z)
            val vertical = abs(from.y.toLong() - to.y)
            return dx <= 1L && dz <= 1L && dx + dz > 0L && vertical <= 1L || dx + dz == 0L && vertical == 1L ||
                vertical == 0L && (dx == 0L || dz == 0L) && dx + dz in 2L..3L
        }

        private fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }

    /**
     * Reuses endpoint searches, then searches from this actual player node once per replan.
     * Cleared IDs may disappear from previousOrder. A still valid order changes only when
     * the optimal total route saves strictly more than minimumImprovement movement blocks.
     */
    fun plan(
        start: GridCell,
        remaining: Set<String>,
        previousOrder: List<String>? = null,
        minimumImprovement: Int = 3
    ): RoomRoute? {
        if (minimumImprovement < 0 || remaining.size > MAX_TARGETS || remaining.any { it !in targets }) return null
        val startNode = indices[start] ?: return null
        val fromStart = search(startNode)
        val ids = remaining.sorted()
        if (healerNode != null && fromStart.distances[healerNode] < 0 ||
            ids.any { fromStart.distances[targetNodes.getValue(it)] < 0 }) return null
        val optimal = optimalOrder(ids, fromStart) ?: return null
        val previous = previousOrder?.takeIf { it.size <= MAX_TARGETS && it.toSet().size == it.size && it.all(targets::containsKey) }
            ?.filter(remaining::contains)?.takeIf { it.size == ids.size }
        val previousCost = previous?.let { routeCost(it, fromStart) }
        val optimalCost = routeCost(optimal, fromStart) ?: return null
        val retainPrevious = previous != null && previousCost != null &&
            previousCost.toLong() - optimalCost <= minimumImprovement.toLong() * costScale
        val order = if (retainPrevious) previous!! else optimal
        val path = arrayListOf(cells[startNode])
        var source = startNode
        var searched = fromStart
        val destinations = order.map { targetNodes.getValue(it) } + listOfNotNull(healerNode)
        for ((index, destination) in destinations.withIndex()) {
            val segment = pathTo(source, destination, searched) ?: return null
            path.addAll(segment.drop(1))
            source = destination
            if (index < destinations.lastIndex) searched = fixedSearches.getValue(destination)
        }
        return RoomRoute(immutable(order), immutable(path), if (retainPrevious) previousCost!! else optimalCost)
    }

    private fun optimalOrder(ids: List<String>, fromStart: Search): List<String>? {
        if (ids.isEmpty()) return emptyList()
        val count = ids.size
        val nodes = ids.map { targetNodes.getValue(it) }
        val states = 1 shl count
        val costs = IntArray(states * count) { INFINITE }
        val previous = ByteArray(costs.size) { -1 }
        for (next in 0 until count) costs[(1 shl next) * count + next] = fromStart.distances[nodes[next]]
        for (mask in 1 until states) {
            for (last in 0 until count) {
                val cost = costs[mask * count + last]
                if (cost == INFINITE) continue
                val distances = fixedSearches.getValue(nodes[last]).distances
                for (next in 0 until count) {
                    if (mask and (1 shl next) != 0 || distances[nodes[next]] < 0) continue
                    val index = (mask or (1 shl next)) * count + next
                    val candidate = cost + distances[nodes[next]]
                    if (candidate < costs[index]) {
                        costs[index] = candidate
                        previous[index] = last.toByte()
                    }
                }
            }
        }
        val full = states - 1
        var best = INFINITE
        var last = -1
        for (candidate in 0 until count) {
            val finalDistance = healerNode?.let { fixedSearches.getValue(nodes[candidate]).distances[it] } ?: 0
            val cost = costs[full * count + candidate]
            if (finalDistance >= 0 && cost != INFINITE && cost + finalDistance < best) {
                best = cost + finalDistance
                last = candidate
            }
        }
        if (last < 0) return null
        var mask = full
        val order = ArrayList<String>(count)
        while (mask != 0) {
            order.add(ids[last])
            val next = previous[mask * count + last].toInt()
            mask = mask xor (1 shl last)
            last = next
        }
        order.reverse()
        return order
    }

    private fun routeCost(order: List<String>, fromStart: Search): Int? {
        var searched = fromStart
        var cost = 0
        val destinations = order.map { targetNodes.getValue(it) } + listOfNotNull(healerNode)
        for ((index, destination) in destinations.withIndex()) {
            val distance = searched.distances[destination]
            if (distance < 0) return null
            cost += distance
            if (index < destinations.lastIndex) searched = fixedSearches.getValue(destination)
        }
        return cost
    }

    private fun search(start: Int): Search {
        val distances = IntArray(cells.size) { -1 }
        // Equal-distance arrivals need separate headings: a later corridor can favor either one.
        val parents = IntArray(cells.size * HEADINGS) { -1 }
        val turns = IntArray(parents.size) { INFINITE }
        val arrivals = IntArray(cells.size) { -1 }
        val queue = PriorityQueue(compareBy<SearchNode>({ it.distance }, { it.turns }, { it.state }))
        val initial = start * HEADINGS + NO_HEADING
        distances[start] = 0
        turns[initial] = 0
        arrivals[start] = initial
        queue.add(SearchNode(initial, 0, 0))
        while (queue.isNotEmpty()) {
            val (state, distance, changes) = queue.remove()
            val current = state / HEADINGS
            if (distance != distances[current] || changes != turns[state]) continue
            for (next in adjacent[current]) {
                // A step/jump has horizontal and vertical segments; do not favor stairs by edge count.
                val from = cells[current]
                val to = cells[next]
                val a = standingFeet[from]
                val b = standingFeet[to]
                val cost = if (a != null && b != null) {
                    ((hypot(b.x - a.x, b.z - a.z) + abs(b.y - a.y)) * costScale).roundToInt().coerceAtLeast(1)
                } else abs(from.x - to.x) + abs(from.y - to.y) + abs(from.z - to.z)
                val candidate = distance + cost
                if (distances[next] >= 0 && candidate > distances[next]) continue
                val heading = (to.x.compareTo(from.x) + 1) * 9 + (to.y.compareTo(from.y) + 1) * 3 + to.z.compareTo(from.z) + 1
                val arrival = next * HEADINGS + heading
                val candidateTurns = changes + if (state % HEADINGS == NO_HEADING || state % HEADINGS == heading) 0 else 1
                if (candidate < distances[next] || distances[next] < 0) {
                    java.util.Arrays.fill(turns, next * HEADINGS, (next + 1) * HEADINGS, INFINITE)
                    arrivals[next] = -1
                }
                if (candidateTurns >= turns[arrival]) continue
                distances[next] = candidate
                turns[arrival] = candidateTurns
                parents[arrival] = state
                val best = arrivals[next]
                if (best < 0 || candidateTurns < turns[best] || candidateTurns == turns[best] && arrival < best) {
                    arrivals[next] = arrival
                }
                queue.add(SearchNode(arrival, candidate, candidateTurns))
            }
        }
        return Search(distances, parents, arrivals)
    }

    private fun pathTo(start: Int, end: Int, search: Search): List<GridCell>? {
        if (search.distances[end] < 0) return null
        val path = ArrayList<GridCell>(minOf(cells.size, search.distances[end] / costScale + 1))
        var current = search.arrivals[end]
        while (current / HEADINGS != start) {
            path.add(cells[current / HEADINGS])
            current = search.parents[current]
        }
        path.add(cells[start])
        path.reverse()
        return path
    }
}
