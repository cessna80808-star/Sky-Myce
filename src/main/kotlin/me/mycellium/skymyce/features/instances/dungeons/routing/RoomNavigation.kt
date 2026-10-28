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
        val order = if (retainPrevious) previous else optimal
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
        return RoomRoute(immutable(order), immutable(path), if (retainPrevious) previousCost else optimalCost)
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

/** Checked interiors and map-only hints are separate; neither kind changes the collision graph. */
internal data class DungeonRouteSection(val points: List<RoomPoint>, val checked: Boolean, val gate: Boolean = false,
    val displayPoints: List<RoomPoint> = points)

/** Builds onward room interiors from their known doorway, without waiting for player entry. */
internal class DungeonRouteContinuation(
    private val standing: (GridCell) -> RoomPoint?,
    private val connects: (RoomPoint, RoomPoint) -> Boolean,
    private val simplify: (List<RoomPoint>) -> List<RoomPoint>,
    private val schedule: (work: () -> List<RoomPoint>?, publish: (List<RoomPoint>?) -> Unit) -> Unit = { work, publish -> publish(work()) },
    private val sightConnects: (RoomPoint, RoomPoint) -> Boolean = { a, b -> roomRouteLevelHeight(a, b) && connects(a, b) },
) {
    private data class LegKey(val segments: Set<RoomSegment>, val entry: GridCell, val exit: GridCell,
        val entryDoor: GridCell, val exitDoor: GridCell)
    private class Leg(val key: LegKey) {
        var builder: RoomLiveGeometry? = null
        var snapshot: RoomLiveGeometrySnapshot? = null
        var seed: GridCell? = null
        var endpoint: GridCell? = null
        var path: List<RoomPoint>? = null
        var displayPoints: List<RoomPoint>? = null
        var planning: Any? = null
        var endpointReached = false
        var partialPlanned = false
        var nextPartialProbe = 0L
        var scanned = false
        var complete = false
        var failed = false
        var retryAt = 0L
    }
    private var doors = emptyList<DungeonDoor>()
    private val legs = LinkedHashMap<LegKey, Leg>()
    private var currentLeg: LegKey? = null
    private var cursor = 0
    var revision = 0L
        private set

    fun clear() {
        doors = emptyList(); legs.clear(); currentLeg = null; cursor = 0; revision++
    }

    fun update(exit: DungeonExit?) {
        val next = exit?.doors.orEmpty()
        if (next == doors) return
        // The pre-entry interior remains usable while the new current-room scan catches up.
        currentLeg = next.firstOrNull()?.let { door ->
            legs.keys.firstOrNull { it.segments == door.from && it.exit == door.approach }
        }
        doors = next
        val keys = next.zipWithNext().mapNotNull { (entry, exitDoor) ->
            entry.to.takeIf { it.isNotEmpty() && it == exitDoor.from }?.let {
                LegKey(it, entry.departure, exitDoor.approach, entry.door, exitDoor.door)
            }
        }.toMutableSet()
        currentLeg?.let(keys::add)
        // A shortened confirmed prefix hides its destination's interior without discarding checked work.
        next.lastOrNull()?.to?.let { frontier -> legs.keys.filterTo(keys) { it.segments == frontier } }
        legs.keys.retainAll(keys)
        keys.forEach { legs.getOrPut(it) { Leg(it) } }
        cursor = 0; revision++
    }

    private fun contains(segments: Set<RoomSegment>, cell: GridCell) =
        segments.any { cell.x in it.x..it.x + 31 && cell.z in it.z..it.z + 31 }

    private val surfaceOffsets = (-2..2).flatMap { dx -> (-2..2).flatMap { dz ->
        if (dx * dx + dz * dz > 4) emptyList() else (-6..6).map { GridCell(dx, it, dz) }
    } }.sortedWith(compareBy({ it.x * it.x + it.y * it.y + it.z * it.z },
        { abs(it.y) }, { it.x * it.x + it.z * it.z }, { it.x }, { it.z }, { it.y }))

    private fun surface(anchor: GridCell, segments: Set<RoomSegment>, door: GridCell): Pair<GridCell, RoomPoint>? =
        surfaceOffsets.firstNotNullOfOrNull { offset ->
            val cell = GridCell(anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z)
            val sameSide = (cell.x.toDouble() - door.x) * (anchor.x.toDouble() - door.x) +
                (cell.z.toDouble() - door.z) * (anchor.z.toDouble() - door.z) > 0
            if (!sameSide || !contains(segments, cell)) null else standing(cell)?.let { cell to it }
        }

    /** One bounded room scan per tick; completed legs survive movement and room transitions. */
    fun step(nowMillis: Long, maxNanos: Long = 1_000_000) {
        if (legs.isEmpty()) return
        val pending = legs.values.filter { !it.complete && !it.failed && nowMillis >= it.retryAt &&
            (it.key != currentLeg || it.builder != null || it.scanned) && (it.builder != null || it.planning == null) }
        if (pending.isEmpty()) return
        val leg = pending[cursor++ % pending.size]
        if (leg.builder == null && !leg.scanned) {
            val entry = surface(leg.key.entry, leg.key.segments, leg.key.entryDoor)
            val exit = surface(leg.key.exit, leg.key.segments, leg.key.exitDoor)
            if (entry == null || exit == null) { leg.retryAt = nowMillis + 500; return }
            leg.seed = entry.first; leg.endpoint = exit.first
            if (sightConnects(entry.second, exit.second)) {
                leg.path = listOf(entry.second, exit.second); leg.displayPoints = leg.path
                leg.complete = true; revision++
                return
            }
            val feet = HashMap<GridCell, RoomPoint>()
            leg.builder = RoomLiveGeometry(entry.first,
                { contains(leg.key.segments, it) && it.y in entry.first.y - 16..entry.first.y + 24 },
                { cell -> standing(cell)?.also { feet[cell] = it } != null },
                { a, b -> connects(feet.getValue(a), feet.getValue(b)) }, { cell ->
                    feet.getValue(cell).also { if (cell == leg.endpoint) leg.endpointReached = true }
                }).apply {
                prioritize(listOf(exit.first))
            }
        }
        if (leg.scanned) { plan(leg, leg.snapshot, final = true); return }
        val builder = leg.builder!!
        val finished = builder.step(maxCells = 64, maxEdges = 128, maxNanos = maxNanos)
        if (finished) {
            leg.snapshot = builder.result ?: leg.snapshot
            leg.builder = null
            leg.scanned = true
            if (leg.planning == null) plan(leg, leg.snapshot, final = true)
        } else if (leg.endpointReached && !leg.partialPlanned && nowMillis >= leg.nextPartialProbe) {
            leg.nextPartialProbe = nowMillis + 100
            val snapshot = builder.snapshot()
            if (snapshot != null && leg.endpoint?.let { it in snapshot.adjacency } == true) {
                leg.snapshot = snapshot
                leg.partialPlanned = true
                plan(leg, snapshot, final = false)
            }
        }
    }

    private fun plan(leg: Leg, snapshot: RoomLiveGeometrySnapshot?, final: Boolean) {
        val seed = leg.seed!!
        val endpoint = leg.endpoint
        val work = Any()
        leg.planning = work
        schedule({
            val graph = snapshot?.let { RoomNavigation.create(it.cells, it.adjacency, emptyMap(), endpoint, it.feet) }
            graph?.plan(seed, emptySet())?.path?.map { snapshot.feet.getValue(it) }
        }, { path ->
            if (legs[leg.key] !== leg || leg.planning !== work) return@schedule
            leg.planning = null
            path?.let { raw -> simplify(raw).takeIf { it.isNotEmpty() }?.let {
                leg.path = it; leg.displayPoints = raw
            } }
            if (final) {
                leg.complete = true
                leg.failed = leg.path == null // Static blocked geometry waits for an actual geometry change.
            }
            revision++
        })
    }

    private fun invalidate(leg: Leg) {
        leg.builder = null; leg.snapshot = null; leg.path = null; leg.displayPoints = null; leg.planning = null
        leg.endpointReached = false; leg.partialPlanned = false; leg.nextPartialProbe = 0; leg.scanned = false; leg.complete = false
        leg.failed = false; leg.retryAt = 0; revision++
    }

    fun blockChanged(block: GridCell, collisionRemoved: Boolean = false) {
        legs.values.forEach { leg ->
            if (leg.builder?.observesBlock(block) == true || leg.snapshot?.let { nearbyRoomGeometry(it.cells, block) } == true ||
                leg.complete && leg.snapshot == null && nearby(leg.key.segments, block) ||
                leg.failed && contains(leg.key.segments, block)) {
                val path = leg.path
                if (collisionRemoved && leg.complete && path != null && path.size in 2..65) {
                    val direct = if (sightConnects(path.first(), path.last())) listOf(path.first(), path.last()) else null
                    if (direct != null || path.zipWithNext().all { (a, b) -> sightConnects(a, b) }) {
                        // Sight guidance still needs valid landings; an opening door need not discard a checked interior.
                        leg.snapshot = null
                        if (direct != null && direct != path) {
                            leg.path = direct; leg.displayPoints = direct; revision++
                        }
                        return@forEach
                    }
                }
                invalidate(leg)
            }
        }
    }

    private fun nearby(segments: Set<RoomSegment>, cell: GridCell) =
        segments.any { cell.x in it.x - 1..it.x + 32 && cell.z in it.z - 1..it.z + 32 }

    fun chunkChanged(x: Int, z: Int) {
        legs.values.forEach { leg ->
            if (leg.key.segments.any { it.x - 1 < (x + 1) * 16 && it.x + 32 >= x * 16 && it.z - 1 < (z + 1) * 16 && it.z + 32 >= z * 16 }) {
                invalidate(leg)
            }
        }
    }

    /** Prefer checked sight lines, otherwise rejoin the walking suffix without losing stair elbows. */
    fun checkedCurrentPath(position: RoomPoint, display: Boolean = false): List<RoomPoint>? {
        val leg = currentLeg?.let(legs::get) ?: return null
        val path = (if (display) leg.displayPoints else leg.path) ?: return null
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) return null
        if (sightConnects(position, path.last())) return listOf(position, path.last()).distinct()
        data class Join(val point: RoomPoint, val next: Int, val distance: Double)
        val joins = ArrayList<Join>()
        fun candidate(point: RoomPoint, next: Int) {
            val distance = (point.x - position.x) * (point.x - position.x) +
                (point.y - position.y) * (point.y - position.y) + (point.z - position.z) * (point.z - position.z)
            if (distance <= 64.0) joins.add(Join(point, next, distance))
        }
        path.forEachIndexed { index, point ->
            candidate(point, index + 1)
            val end = path.getOrNull(index + 1) ?: return@forEachIndexed
            if (abs(point.y - end.y) > 0.00001) return@forEachIndexed
            val dx = end.x - point.x; val dz = end.z - point.z
            val length = dx * dx + dz * dz
            if (length < 0.00001) return@forEachIndexed
            val amount = (((position.x - point.x) * dx + (position.z - point.z) * dz) / length).coerceIn(0.0, 1.0)
            candidate(RoomPoint(point.x + dx * amount, point.y, point.z + dz * amount), index + 1)
        }
        // Keep world collision work bounded; long simplified corridors are joined at their nearest projection.
        for (join in joins.sortedWith(compareBy<Join> { it.distance }.thenByDescending { it.next }).distinctBy { it.point }.take(7)) {
            val level = abs(position.y - join.point.y) < 0.00001
            val connector = if (display || level) listOf(position, join.point) else roomConnectorWaypoints(position, join.point) ?: continue
            if (!connects(position, join.point) && (!display || !sightConnects(position, join.point))) continue
            val joined = connector + path.drop(join.next)
            return joined.filterIndexed { index, point -> index == 0 || point != joined[index - 1] }
        }
        return null
    }

    fun sections(firstApproach: RoomPoint? = null): List<DungeonRouteSection> = buildList {
        doors.forEachIndexed { index, door ->
            val previous = doors.getOrNull(index - 1)
            val previousPath = previous?.let { legs[LegKey(door.from, it.departure, door.approach, it.door, door.door)]?.path }
            val next = doors.getOrNull(index + 1)
            val leg = next?.let { legs[LegKey(door.to, door.departure, it.approach, door.door, it.door)] }
            val path = leg?.path
            val before = previousPath?.lastOrNull() ?: surface(door.approach, door.from, door.door)?.second ?: point(door.approach)
            val after = path?.firstOrNull() ?: surface(door.departure, door.to, door.door)?.second ?: point(door.departure)
            if (index == 0 && firstApproach != null && firstApproach != before)
                add(approach(firstApproach, before))
            // Door centers are mandatory waypoints, even when the virtual crossing is closed.
            val center = RoomPoint(door.door.x + .5, (before.y + after.y) / 2, door.door.z + .5)
            add(DungeonRouteSection(listOf(before, center, after), !door.closed && straight(before, center) && straight(center, after), true))
            next ?: return@forEachIndexed
            if (door.to.isEmpty() || door.to != next.from) return@forEachIndexed
            add(if (path != null) DungeonRouteSection(path, true, displayPoints = leg.displayPoints ?: path)
                else DungeonRouteSection(listOf(after, surface(next.approach, next.from, next.door)?.second ?: point(next.approach)), false))
        }
    }

    private fun approach(from: RoomPoint, to: RoomPoint): DungeonRouteSection {
        if (abs(from.y - to.y) < 0.00001) return DungeonRouteSection(listOf(from, to), connects(from, to))
        val elbows = roomConnectorWaypoints(from, to)
        return if (elbows != null && connects(from, to)) DungeonRouteSection(elbows, true, displayPoints = listOf(from, to))
            else DungeonRouteSection(listOf(from, to), false)
    }

    private fun point(cell: GridCell) = RoomPoint(cell.x + .5, cell.y.toDouble(), cell.z + .5)

    // Carpet-height alignment still needs support; larger rises retain their validated stair elbows.
    private fun straight(from: RoomPoint, to: RoomPoint) = roomRouteLevelHeight(from, to) && connects(from, to)
}

data class RoomRoutingContext(val worldEpoch: Long, val runEpoch: Long, val roomKey: String) {
    init { require(roomKey.isNotBlank()) }
}

enum class RoomReplanReason { CONTEXT_CHANGED, TARGETS_CHANGED, GEOMETRY_CHANGED, MOVED, TELEPORTED, RETRY }

/** Cache the known Blood doorway for the run, even when routing stops after opening it. */
internal class BloodDoorReminder {
    private var door: GridCell? = null
    private var blood = emptySet<RoomSegment>()

    fun clear() { door = null; blood = emptySet() }

    fun observe(exit: DungeonExit?) {
        val gate = exit?.doors?.lastOrNull { it.kind == "Blood door" }
        if (gate != null) {
            door = gate.door
            blood = gate.to.ifEmpty { setOf(dungeonGridSegment(gate.departure.x + .5, gate.departure.z + .5)) }
        } else if (exit?.kind == "Blood door") {
            val dx = exit.door.x.compareTo(exit.approach.x)
            val dz = exit.door.z.compareTo(exit.approach.z)
            if ((dx == 0) == (dz == 0)) return
            door = exit.door
            blood = setOf(dungeonGridSegment(exit.door.x + dx * 3 + .5, exit.door.z + dz * 3 + .5))
        }
    }

    fun highlight(mage: Boolean, position: RoomPoint): GridCell? = door.takeIf {
        mage && position.x.isFinite() && position.z.isFinite() && blood.none { room ->
            position.x >= room.x - .5 && position.x < room.x + 31.5 &&
                position.z >= room.z - .5 && position.z < room.z + 31.5
        }
    }
}

/** Keep checked guidance after obstacle removal only while its floor and every movement edge remain valid. */
internal fun checkedRoomPathSurvivesChange(points: List<RoomPoint>, canConnect: (RoomPoint, RoomPoint) -> Boolean): Boolean {
    if (points.isEmpty() || points.size > 65) return false
    return try {
        canConnect(points.last(), points.last()) && points.zipWithNext().all { (a, b) -> canConnect(a, b) }
    } catch (_: RuntimeException) { false }
}

/** An unfinished approach probe cannot discard a previously routed target that still needs a stop. */
internal fun roomApproachesPending(previous: Set<String>, selected: Set<String>, required: Set<String>, pending: Boolean) =
    pending && previous.any { it in required && it !in selected }

/** Partial geometry must not replace a required stop with an empty or exit-only guide. */
internal fun roomLocalGuideRelevant(route: RoomRoute?, required: Set<String>, exit: GridCell?) =
    if (required.isEmpty()) exit != null && route?.path?.lastOrNull() == exit
    else route?.targetOrder?.firstOrNull()?.let(required::contains) == true

@ConsistentCopyVisibility
data class RoomReplanRequest internal constructor(
    val revision: Long,
    val context: RoomRoutingContext,
    val start: GridCell,
    val position: RoomPoint,
    val reason: RoomReplanReason
)

/** Small game-thread gate; workers return their original immutable request to complete(). */
class RoomRoutingUpdates {
    var revision: Long = 0
        private set
    val reason: RoomReplanReason? get() = pending
    val observedPosition: RoomPoint? get() = observed

    private var context: RoomRoutingContext? = null
    private var pending: RoomReplanReason? = null
    private var dueAt = Long.MAX_VALUE
    private var start: GridCell? = null
    private var position: RoomPoint? = null
    private var observed: RoomPoint? = null
    private var planned: RoomPoint? = null
    private var plannedHasPath = false
    private var inFlight: RoomReplanRequest? = null
    private var stableRetries = 0
    private var lastNow = Long.MIN_VALUE

    companion object {
        const val MOVEMENT_BLOCKS = 2.0
        const val TELEPORT_BLOCKS = 8.0
        const val MOVEMENT_DEBOUNCE_MILLIS = 100L
        const val STABLE_RETRY_MILLIS = 500L
        const val MAX_STABLE_RETRIES = 1
    }

    fun updateContext(context: RoomRoutingContext?, nowMillis: Long) {
        val now = time(nowMillis)
        if (this.context == context) return
        clear()
        this.context = context
        if (context != null) { pending = RoomReplanReason.CONTEXT_CHANGED; dueAt = now }
    }

    /** Always advances the revision, including death/reconnect into the same room and epochs. */
    fun clear() {
        revision++
        context = null
        pending = null
        dueAt = Long.MAX_VALUE
        start = null
        position = null
        observed = null
        planned = null
        plannedHasPath = false
        inFlight = null
        stableRetries = 0
    }

    fun invalidate(reason: RoomReplanReason, nowMillis: Long) {
        val now = time(nowMillis)
        if (context == null) return
        request(reason, if (reason == RoomReplanReason.MOVED) after(now, MOVEMENT_DEBOUNCE_MILLIS) else now)
    }

    fun observe(start: GridCell, position: RoomPoint, nowMillis: Long, automaticMovement: Boolean = true, followingPath: Boolean = false) {
        val now = time(nowMillis)
        if (context == null) return
        val previous = observed
        this.start = start
        this.position = position
        observed = position
        if (previous != null && distanceSquared(previous, position) >= TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
            request(RoomReplanReason.TELEPORTED, now)
            return
        }
        val anchor = planned ?: return
        val moved = automaticMovement && !followingPath && distanceSquared(anchor, position) >= MOVEMENT_BLOCKS * MOVEMENT_BLOCKS
        if (!moved && pending == RoomReplanReason.MOVED && plannedHasPath) {
            // Coalesce small threshold oscillations without planning after the player returns.
            pending = null
            dueAt = Long.MAX_VALUE
        } else if (moved && (pending == null || pending == RoomReplanReason.RETRY)) {
            request(RoomReplanReason.MOVED, after(now, MOVEMENT_DEBOUNCE_MILLIS))
        }
    }

    fun due(nowMillis: Long): Boolean {
        val now = time(nowMillis)
        return context != null && pending != null && start != null && position != null && inFlight == null && now >= dueAt
    }

    fun take(nowMillis: Long): RoomReplanRequest? {
        if (!due(nowMillis)) return null
        revision++
        val request = RoomReplanRequest(revision, context!!, start!!, position!!, pending!!)
        pending = null
        dueAt = Long.MAX_VALUE
        planned = request.position
        plannedHasPath = false
        inFlight = request
        return request
    }

    /** False results are stale/duplicate; they neither block a new request nor schedule retries. */
    fun complete(request: RoomReplanRequest, foundPath: Boolean, nowMillis: Long): Boolean {
        val now = time(nowMillis)
        if (inFlight !== request || request.revision != revision || request.context != context) return false
        inFlight = null
        plannedHasPath = foundPath
        if (!foundPath && stableRetries < MAX_STABLE_RETRIES) {
            stableRetries++
            pending = RoomReplanReason.RETRY
            dueAt = after(now, STABLE_RETRY_MILLIS)
        } else {
            pending = null
            dueAt = Long.MAX_VALUE
            if (foundPath) stableRetries = 0
        }
        return true
    }

    private fun request(reason: RoomReplanReason, due: Long) {
        revision++
        inFlight = null
        if (pending == null || priority(reason) >= priority(pending!!)) pending = reason
        dueAt = minOf(dueAt, due)
        if (reason != RoomReplanReason.RETRY) stableRetries = 0
    }

    private fun time(now: Long): Long = maxOf(now, lastNow).also { lastNow = it }

    private fun after(now: Long, delay: Long): Long = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay

    private fun priority(reason: RoomReplanReason): Int = when (reason) {
        RoomReplanReason.CONTEXT_CHANGED -> 4
        RoomReplanReason.TELEPORTED -> 3
        RoomReplanReason.TARGETS_CHANGED, RoomReplanReason.GEOMETRY_CHANGED -> 2
        RoomReplanReason.MOVED -> 1
        RoomReplanReason.RETRY -> 0
    }

    private fun distanceSquared(from: RoomPoint, to: RoomPoint): Double {
        val x = from.x - to.x; val y = from.y - to.y; val z = from.z - to.z
        return x * x + y * y + z * z
    }
}
