package me.mycellium.skymyce.features.instances.dungeons.routing

import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Owned by the completed builder; its backing collections are never mutated after publication. */
class RoomLiveGeometrySnapshot internal constructor(cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>,
    feet: Map<GridCell, RoomPoint> = emptyMap()) {
    val cells: Set<GridCell> = Collections.unmodifiableSet(cells)
    val adjacency: Map<GridCell, Set<GridCell>> = Collections.unmodifiableMap(adjacency)
    val feet: Map<GridCell, RoomPoint> = Collections.unmodifiableMap(feet)
}

/** Floods only safe directed connections from an actual player start; callbacks run in step(). */
class RoomLiveGeometry(
    private val seed: GridCell,
    private val contains: (GridCell) -> Boolean,
    private val canStand: (GridCell) -> Boolean,
    private val canConnect: (GridCell, GridCell) -> Boolean,
    private val resolveFeet: (GridCell) -> RoomPoint = { RoomPoint(it.x + 0.5, it.y.toDouble(), it.z + 0.5) },
) {
    var status = RoomGeometryStatus.BUILDING
        private set
    var result: RoomLiveGeometrySnapshot? = null
        private set
    var failure: String? = null
        private set
    private val accepted = LinkedHashSet<GridCell>()
    private val standing = HashMap<GridCell, Boolean>()
    private val feet = LinkedHashMap<GridCell, RoomPoint>()
    private val adjacent = LinkedHashMap<GridCell, Set<GridCell>>()
    private data class Frontier(val cell: GridCell, val cost: Double, val priority: Double)
    private val queue = PriorityQueue(compareBy<Frontier>({ it.priority }, { it.cost }, { it.cell.x }, { it.cell.y }, { it.cell.z }))
    private val distances = HashMap<GridCell, Double>()
    private var goals = emptyList<GridCell>()
    private var current: GridCell? = null
    private var connections = linkedSetOf<GridCell>()
    private var neighbor = 0
    private var started = false
    val nodeCount get() = accepted.size
    var revision = 0L
        private set

    fun observesBlock(block: GridCell): Boolean = nearbyRoomGeometry(standing.keys, block)

    /** Focus the same flood on missing targets; the horizontal heuristic never crosses unchecked edges. */
    fun prioritize(targets: Collection<GridCell>) {
        val next = targets.distinct().take(RoomNavigation.MAX_TARGETS).sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
        if (next == goals) return
        goals = next
        val pending = queue.map { it.cell }.distinct()
        queue.clear()
        for (cell in pending) enqueue(cell, distances.getValue(cell))
    }

    private fun enqueue(cell: GridCell, cost: Double) {
        val remaining = goals.minOfOrNull { hypot(it.x.toDouble() - cell.x, it.z.toDouble() - cell.z) } ?: 0.0
        queue.add(Frontier(cell, cost, cost + remaining))
    }

    companion object {
        private val offsets = (-1..1).flatMap { x -> (-1..1).mapNotNull { z ->
            GridCell(x, 0, z).takeIf { x != 0 || z != 0 }
        } }
            .flatMap { direction -> (-1..1).map { GridCell(direction.x, it, direction.z) } } +
            listOf(GridCell(0, -1, 0), GridCell(0, 1, 0)) +
            (2..3).flatMap { distance -> listOf(GridCell(-distance, 0, 0), GridCell(distance, 0, 0),
                GridCell(0, 0, -distance), GridCell(0, 0, distance)) }
    }

    /** Finished means READY or INCOMPLETE; a failed or capped flood never exposes a partial graph. */
    fun step(maxCells: Int = 128, maxEdges: Int = 256, maxNanos: Long = 2_000_000): Boolean {
        if (status != RoomGeometryStatus.BUILDING) return true
        if (maxCells <= 0 || maxEdges <= 0 || maxNanos <= 0) return incomplete("Invalid live geometry budget")
        val begin = System.nanoTime()
        var cellReads = 0
        var edgeReads = 0
        var worked = false
        try {
            if (!started) {
                started = true
                if (!contains(seed) || !canStand(seed)) return incomplete("Player has no loaded safe standing cell")
                standing[seed] = true
                feet[seed] = resolveFeet(seed)
                accepted.add(seed)
                revision++
                distances[seed] = 0.0
                enqueue(seed, 0.0)
                cellReads++
                worked = true
            }
            while (current != null || queue.isNotEmpty()) {
                if (worked && System.nanoTime() - begin >= maxNanos) return false
                if (current == null) {
                    val entry = queue.remove()
                    if (entry.cell in adjacent || entry.cost != distances[entry.cell]) continue
                    current = entry.cell
                }
                val from = current!!
                if (neighbor == offsets.size) {
                    adjacent[from] = Collections.unmodifiableSet(connections)
                    connections = linkedSetOf()
                    neighbor = 0
                    current = null
                    continue
                }
                val offset = offsets[neighbor]
                val to = GridCell(Math.addExact(from.x, offset.x), Math.addExact(from.y, offset.y), Math.addExact(from.z, offset.z))
                worked = true
                if (!contains(to)) { neighbor++; continue }
                if (abs(offset.x) + abs(offset.z) > 1 && offset.y == 0 && (offset.x == 0 || offset.z == 0)) {
                    var gap = true
                    for (distance in 1 until maxOf(abs(offset.x), abs(offset.z))) {
                        val between = GridCell(from.x + offset.x.sign * distance, from.y, from.z + offset.z.sign * distance)
                        if (!contains(between)) { gap = false; break }
                        val middleStanding = standing[between] ?: run {
                            if (cellReads >= maxCells) return false
                            cellReads++
                            canStand(between).also { standing[between] = it }
                        }
                        if (middleStanding) { gap = false; break }
                    }
                    if (!gap) { neighbor++; continue }
                }
                val safe = standing[to] ?: run {
                    if (cellReads >= maxCells) return false
                    cellReads++
                    canStand(to).also { standing[to] = it }
                }
                if (!safe) { neighbor++; continue }
                if (cellReads + edgeReads > 0 && System.nanoTime() - begin >= maxNanos) return false
                if (edgeReads >= maxEdges) return false
                edgeReads++
                if (canConnect(from, to)) {
                    if (to !in accepted) {
                        if (accepted.size >= RoomNavigation.MAX_NODES) return incomplete("Live geometry exceeds 16384 reachable cells")
                        accepted.add(to)
                        feet[to] = resolveFeet(to)
                    }
                    if (connections.add(to)) revision++
                    val a = feet.getValue(from)
                    val b = feet.getValue(to)
                    val distance = distances.getValue(from) + hypot(b.x - a.x, b.z - a.z) + abs(b.y - a.y)
                    if (to !in adjacent && distance < (distances[to] ?: Double.POSITIVE_INFINITY)) {
                        distances[to] = distance
                        enqueue(to, distance)
                    }
                }
                neighbor++
            }
            result = RoomLiveGeometrySnapshot(accepted, adjacent, feet)
            status = RoomGeometryStatus.READY
            return true
        } catch (_: RuntimeException) {
            return incomplete("Live collision geometry became unavailable")
        }
    }

    /** A frozen graph of checked edges can guide nearby targets while the rest of the room is scanned. */
    fun snapshot(): RoomLiveGeometrySnapshot? {
        result?.let { return it }
        if (status != RoomGeometryStatus.BUILDING || accepted.isEmpty()) return null
        val edges = LinkedHashMap(adjacent)
        current?.let { edges[it] = Collections.unmodifiableSet(LinkedHashSet(connections)) }
        return RoomLiveGeometrySnapshot(LinkedHashSet(accepted), edges, LinkedHashMap(feet))
    }

    private fun incomplete(reason: String): Boolean {
        status = RoomGeometryStatus.INCOMPLETE
        failure = reason
        result = null
        accepted.clear()
        standing.clear()
        feet.clear()
        adjacent.clear()
        queue.clear()
        distances.clear()
        current = null
        connections.clear()
        return true
    }
}

/** Body, support and neighboring collision-shape reads around a standing cell. */
internal fun nearbyRoomGeometry(cells: Set<GridCell>, block: GridCell): Boolean {
    for (x in block.x - 1..block.x + 1) for (y in block.y - 3..block.y + 2) for (z in block.z - 1..block.z + 1)
        if (GridCell(x, y, z) in cells) return true
    return false
}
