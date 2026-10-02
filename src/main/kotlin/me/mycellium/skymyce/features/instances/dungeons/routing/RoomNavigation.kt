package me.mycellium.skymyce.features.instances.dungeons.routing

import java.util.Collections
import kotlin.math.abs

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

/** Cost counts validated movement edges, including the final segment to the healer. */
@ConsistentCopyVisibility
data class RoomRoute internal constructor(val targetOrder: List<String>, val path: List<GridCell>, val cost: Int)

/**
 * Immutable room-local graph. The caller supplies every safe directed connection after
 * checking player clearance, supports, doors and loaded geometry. This class never infers
 * a connection across a wall, a diagonal, or a larger elevation change.
 */
class RoomNavigation private constructor(
    val cells: List<GridCell>,
    val targets: Map<String, GridCell>,
    val healer: GridCell,
    private val indices: Map<GridCell, Int>,
    private val adjacent: Array<IntArray>
) {
    private class Search(val distances: IntArray, val parents: IntArray)
    private val targetNodes = targets.mapValues { indices.getValue(it.value) }
    private val healerNode = indices.getValue(healer)
    private val fixedSearches = (targetNodes.values + healerNode).distinct().associateWith(::search)

    companion object {
        const val MAX_NODES = 16_384
        const val MAX_TARGETS = 12
        private const val MAX_NEIGHBORS = 14
        private const val INFINITE = Int.MAX_VALUE / 4
        private val targetId = Regex("[A-Za-z0-9_.-]{1,64}")
        private val cellOrder = compareBy<GridCell>({ it.x }, { it.y }, { it.z })

        /** Malformed/incomplete geometry produces no planner; no real room data is inferred. */
        fun create(
            cells: Collection<GridCell>,
            adjacency: Map<GridCell, Collection<GridCell>>,
            targets: Map<String, GridCell>,
            healer: GridCell
        ): RoomNavigation? {
            if (cells.isEmpty() || cells.size > MAX_NODES || targets.size > MAX_TARGETS || adjacency.size > cells.size) return null
            val nodes = cells.toSet()
            if (nodes.size != cells.size || healer !in nodes || targets.any { !targetId.matches(it.key) || it.value !in nodes }) return null
            if (adjacency.any { (from, next) ->
                from !in nodes || next.size > MAX_NEIGHBORS || next.toSet().size != next.size ||
                    next.any { it !in nodes || !localConnection(from, it) }
            }) return null
            val ordered = cells.sortedWith(cellOrder)
            val indices = ordered.withIndex().associate { it.value to it.index }
            val adjacent = Array(ordered.size) { index ->
                adjacency[ordered[index]].orEmpty().map { indices.getValue(it) }.sorted().toIntArray()
            }
            return RoomNavigation(immutable(ordered), Collections.unmodifiableMap(targets.toSortedMap()), healer, indices, adjacent)
        }

        private fun localConnection(from: GridCell, to: GridCell): Boolean {
            val horizontal = abs(from.x.toLong() - to.x) + abs(from.z.toLong() - to.z)
            val vertical = abs(from.y.toLong() - to.y)
            return horizontal == 1L && vertical <= 1L || horizontal == 0L && vertical == 1L
        }

        private fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }

    /**
     * Reuses endpoint searches, then searches from this actual player node once per replan.
     * Cleared IDs may disappear from previousOrder. A still valid order changes only when
     * the optimal total route saves strictly more than minimumImprovement movement edges.
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
        if (fromStart.distances[healerNode] < 0 || ids.any { fromStart.distances[targetNodes.getValue(it)] < 0 }) return null
        val optimal = optimalOrder(ids, fromStart) ?: return null
        val previous = previousOrder?.takeIf { it.size <= MAX_TARGETS && it.toSet().size == it.size && it.all(targets::containsKey) }
            ?.filter(remaining::contains)?.takeIf { it.size == ids.size }
        val previousCost = previous?.let { routeCost(it, fromStart) }
        val optimalCost = routeCost(optimal, fromStart) ?: return null
        val order = if (previous != null && previousCost != null && previousCost - optimalCost <= minimumImprovement) previous else optimal
        val path = ArrayList<GridCell>()
        var source = startNode
        var searched = fromStart
        for (destination in order.map { targetNodes.getValue(it) } + healerNode) {
            val segment = pathTo(source, destination, searched) ?: return null
            path.addAll(if (path.isEmpty()) segment else segment.drop(1))
            source = destination
            searched = fixedSearches.getValue(destination)
        }
        return RoomRoute(immutable(order), immutable(path), path.size - 1)
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
            val finalDistance = fixedSearches.getValue(nodes[candidate]).distances[healerNode]
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
        for (destination in order.map { targetNodes.getValue(it) } + healerNode) {
            val distance = searched.distances[destination]
            if (distance < 0) return null
            cost += distance
            searched = fixedSearches.getValue(destination)
        }
        return cost
    }

    private fun search(start: Int): Search {
        val distances = IntArray(cells.size) { -1 }
        val parents = IntArray(cells.size) { -1 }
        val queue = IntArray(cells.size)
        distances[start] = 0
        queue[0] = start
        var head = 0
        var tail = 1
        while (head < tail) {
            val current = queue[head++]
            for (next in adjacent[current]) {
                if (distances[next] >= 0) continue
                distances[next] = distances[current] + 1
                parents[next] = current
                queue[tail++] = next
            }
        }
        return Search(distances, parents)
    }

    private fun pathTo(start: Int, end: Int, search: Search): List<GridCell>? {
        if (search.distances[end] < 0) return null
        val path = ArrayList<GridCell>(search.distances[end] + 1)
        var current = end
        while (current != start) {
            path.add(cells[current])
            current = search.parents[current]
        }
        path.add(cells[start])
        path.reverse()
        return path
    }
}
