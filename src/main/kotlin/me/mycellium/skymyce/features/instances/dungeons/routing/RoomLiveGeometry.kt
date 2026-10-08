package me.mycellium.skymyce.features.instances.dungeons.routing

import java.util.ArrayDeque
import java.util.Collections

/** Owned by the completed builder; its backing collections are never mutated after publication. */
class RoomLiveGeometrySnapshot internal constructor(cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>) {
    val cells: Set<GridCell> = Collections.unmodifiableSet(cells)
    val adjacency: Map<GridCell, Set<GridCell>> = Collections.unmodifiableMap(adjacency)
}

/** Floods only safe directed connections from an actual player start; callbacks run in step(). */
class RoomLiveGeometry(
    private val seed: GridCell,
    private val contains: (GridCell) -> Boolean,
    private val canStand: (GridCell) -> Boolean,
    private val canConnect: (GridCell, GridCell) -> Boolean,
) {
    var status = RoomGeometryStatus.BUILDING
        private set
    var result: RoomLiveGeometrySnapshot? = null
        private set
    var failure: String? = null
        private set
    private val accepted = LinkedHashSet<GridCell>()
    private val standing = HashMap<GridCell, Boolean>()
    private val adjacent = LinkedHashMap<GridCell, Set<GridCell>>()
    private val queue = ArrayDeque<GridCell>()
    private var connections = linkedSetOf<GridCell>()
    private var neighbor = 0
    private var started = false

    companion object {
        private val offsets = listOf(GridCell(-1, 0, 0), GridCell(1, 0, 0), GridCell(0, 0, -1), GridCell(0, 0, 1))
            .flatMap { direction -> (-1..1).map { GridCell(direction.x, it, direction.z) } } +
            listOf(GridCell(0, -1, 0), GridCell(0, 1, 0))
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
                accepted.add(seed)
                queue.add(seed)
                cellReads++
                worked = true
            }
            while (queue.isNotEmpty()) {
                if (worked && System.nanoTime() - begin >= maxNanos) return false
                val from = queue.first()
                if (neighbor == offsets.size) {
                    adjacent[from] = Collections.unmodifiableSet(connections)
                    connections = linkedSetOf()
                    neighbor = 0
                    queue.removeFirst()
                    continue
                }
                val offset = offsets[neighbor]
                val to = GridCell(Math.addExact(from.x, offset.x), Math.addExact(from.y, offset.y), Math.addExact(from.z, offset.z))
                worked = true
                if (!contains(to)) { neighbor++; continue }
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
                        queue.add(to)
                    }
                    connections.add(to)
                }
                neighbor++
            }
            result = RoomLiveGeometrySnapshot(accepted, adjacent)
            status = RoomGeometryStatus.READY
            return true
        } catch (_: RuntimeException) {
            return incomplete("Live collision geometry became unavailable")
        }
    }

    private fun incomplete(reason: String): Boolean {
        status = RoomGeometryStatus.INCOMPLETE
        failure = reason
        result = null
        accepted.clear()
        standing.clear()
        adjacent.clear()
        queue.clear()
        connections.clear()
        return true
    }
}
