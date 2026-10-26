package me.mycellium.skymyce.features.instances.dungeons.routing

import kotlin.math.abs

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
