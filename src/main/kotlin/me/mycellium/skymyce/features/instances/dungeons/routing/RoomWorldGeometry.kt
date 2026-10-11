package me.mycellium.skymyce.features.instances.dungeons.routing

import me.mycellium.skymyce.utils.MC
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.BlockCollisions
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.MossyCarpetBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private const val BODY_RADIUS = 0.3
private const val BODY_HEIGHT = 1.8
private const val SUPPORT_DEPTH = 0.6
private const val JUMP_HEADROOM = 0.25
private const val GAP_JUMP_HEIGHT = 1.25
private const val GEOMETRY_EPSILON = 0.00001
private const val CARPET_HEIGHT = 1.0 / 16.0
private const val MAX_ROOM_SIGHT_BLOCKS = 128.0
private val CAPTURE_DIRECTIONS = arrayOf(GridCell(-1, 0, 0), GridCell(1, 0, 0), GridCell(0, 0, -1), GridCell(0, 0, 1))

/** Ignored decorations contribute neither an obstruction nor a supporting floor to routing. */
internal fun roomRoutingCollisionShape(ignored: Boolean, nativeShape: () -> VoxelShape): VoxelShape =
    if (ignored) Shapes.empty() else nativeShape()

/** Height eligibility only; native clearance and support still have to validate the connector. */
internal fun roomRouteLevelHeight(from: RoomPoint, to: RoomPoint): Boolean =
    abs(to.y - from.y) <= CARPET_HEIGHT + GEOMETRY_EPSILON

/** Explicit recording only; ordinary routing never invents template connections. */
internal fun roomCaptureEdges(cells: Set<GridCell>): Sequence<Pair<GridCell, GridCell>> = cells.asSequence().flatMap { from ->
    sequence {
        for (direction in CAPTURE_DIRECTIONS) for (dy in -1..1) {
            val to = GridCell(from.x + direction.x, from.y + dy, from.z + direction.z)
            if (to in cells) yield(from to to)
        }
        for (dy in -1..1 step 2) {
            val to = GridCell(from.x, from.y + dy, from.z)
            if (to in cells) yield(from to to)
        }
    }
}

/** The same elbow is used for collision checking and cached route rendering. */
internal fun roomConnectorWaypoints(from: RoomPoint, to: RoomPoint): List<RoomPoint>? {
    val horizontal = (to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z)
    if (abs(from.x) >= 29_999_999 || abs(from.z) >= 29_999_999 || abs(from.y) > 4096 ||
        abs(to.x) >= 29_999_999 || abs(to.z) >= 29_999_999 || abs(to.y) > 4096 ||
        abs(to.y - from.y) > 1.0 + GEOMETRY_EPSILON ||
        horizontal > 4.0 + GEOMETRY_EPSILON && !roomJumpSpan(from, to)
    ) return null
    if (from == to) return listOf(from)
    if (horizontal < GEOMETRY_EPSILON * GEOMETRY_EPSILON) return listOf(from, to)
    val upperY = maxOf(from.y, to.y) + if (to.y - from.y > SUPPORT_DEPTH) JUMP_HEADROOM else 0.0
    return listOf(from, RoomPoint(from.x, upperY, from.z), RoomPoint(to.x, upperY, to.z), to)
        .distinct()
}

private fun roomJumpSpan(from: RoomPoint, to: RoomPoint): Boolean {
    val dx = abs(to.x - from.x)
    val dz = abs(to.z - from.z)
    return roomRouteLevelHeight(from, to) && (dx <= GEOMETRY_EPSILON || dz <= GEOMETRY_EPSILON) &&
        maxOf(dx, dz) > GEOMETRY_EPSILON && maxOf(dx, dz) <= 3.0 + GEOMETRY_EPSILON
}

/** Short cardinal jumps keep real launch/landing support and require clearance over the full jump volume. */
internal fun validRoomJumpConnector(
    from: RoomPoint,
    to: RoomPoint,
    loaded: (RoomPoint, RoomPoint) -> Boolean,
    clearSegment: (RoomPoint, RoomPoint) -> Boolean,
    supported: (RoomPoint, Double, Double) -> Boolean,
): Boolean {
    if (roomConnectorWaypoints(from, from) == null || roomConnectorWaypoints(to, to) == null || !roomJumpSpan(from, to)) return false
    return try {
        loaded(from, to) && supported(from, SUPPORT_DEPTH, BODY_RADIUS) && supported(to, SUPPORT_DEPTH, BODY_RADIUS) &&
            clearSegment(RoomPoint(from.x, minOf(from.y, to.y), from.z),
                RoomPoint(to.x, maxOf(from.y, to.y) + GAP_JUMP_HEIGHT, to.z))
    } catch (_: RuntimeException) { false }
}

/** Headless movement policy; callbacks provide the native world collision/support observations. */
internal fun validRoomConnector(
    from: RoomPoint,
    to: RoomPoint,
    loaded: (RoomPoint, RoomPoint) -> Boolean,
    clearSegment: (RoomPoint, RoomPoint) -> Boolean,
    supported: (RoomPoint, Double, Double) -> Boolean,
    climbable: (RoomPoint) -> Boolean,
): Boolean {
    val waypoints = roomConnectorWaypoints(from, to) ?: return false
    return try {
        if (!loaded(from, to)) return false
        fun validCell(point: RoomPoint) = clearSegment(point, point) &&
            (climbable(point) || supported(point, SUPPORT_DEPTH, BODY_RADIUS))
        if (!validCell(from)) return false
        if (from == to) return true
        if (!validCell(to)) return false
        val horizontal = (to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z)
        if (horizontal < GEOMETRY_EPSILON * GEOMETRY_EPSILON && abs(to.y - from.y) > GEOMETRY_EPSILON) {
            if (!clearSegment(from, to)) return false
            val samples = ceil(abs(to.y - from.y) * 4).toInt().coerceAtLeast(1)
            val supportAlignment = abs(to.y - from.y) <= SUPPORT_DEPTH
            // Integer nodes can sit just above actual slab feet. Align only while every
            // sample remains supported; a full-block vertical rise still needs a ladder.
            return (0..samples).all {
                val point = interpolate(from, to, it.toDouble() / samples)
                climbable(point) || supportAlignment && supported(point, SUPPORT_DEPTH, BODY_RADIUS)
            }
        }
        for (index in 1 until waypoints.size) if (!clearSegment(waypoints[index - 1], waypoints[index])) return false
        val upperY = waypoints.maxOf { it.y }
        val raisedFrom = RoomPoint(from.x, upperY, from.z)
        val raisedTo = RoomPoint(to.x, upperY, to.z)
        val distance = kotlin.math.sqrt(horizontal)
        val samples = ceil(distance * 4).toInt().coerceAtLeast(1)
        // A full-width probe can miss a narrow hole between samples. Shrinking by the
        // maximum distance to a sample guarantees support throughout each interval.
        val supportRadius = BODY_RADIUS - distance / (2 * samples)
        val depth = SUPPORT_DEPTH + upperY - minOf(from.y, to.y)
        val walking = (0..samples).all {
            val point = interpolate(raisedFrom, raisedTo, it.toDouble() / samples)
            climbable(point) || supported(point, depth, supportRadius)
        }
        walking || validRoomJumpConnector(from, to, loaded, clearSegment, supported)
    } catch (_: RuntimeException) { false }
}

private fun interpolate(from: RoomPoint, to: RoomPoint, amount: Double) = RoomPoint(
    from.x + (to.x - from.x) * amount,
    from.y + (to.y - from.y) * amount,
    from.z + (to.z - from.z) * amount,
)

/** Teleport display guidance checks the body corridor and landing; intervening floor is unnecessary. */
internal fun validRoomSightConnector(
    from: RoomPoint,
    to: RoomPoint,
    loaded: (RoomPoint, RoomPoint) -> Boolean,
    clearSegment: (RoomPoint, RoomPoint) -> Boolean,
    canLand: (RoomPoint) -> Boolean,
): Boolean {
    if (listOf(from, to).any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() ||
            abs(it.x) >= 29_999_999 || abs(it.z) >= 29_999_999 || abs(it.y) > 4096 }) return false
    val distance = kotlin.math.sqrt((to.x - from.x) * (to.x - from.x) +
        (to.y - from.y) * (to.y - from.y) + (to.z - from.z) * (to.z - from.z))
    if (distance > MAX_ROOM_SIGHT_BLOCKS) return false
    return try {
        if (!loaded(from, to) || !canLand(to)) return false
        val pieces = ceil(distance / 2.0).toInt().coerceAtLeast(1)
        var previous = from
        for (index in 1..pieces) {
            val next = interpolate(from, to, index.toDouble() / pieces)
            if (!clearSegment(previous, next)) return false
            previous = next
        }
        true
    } catch (_: RuntimeException) { false }
}

/** Longer level connectors reuse the short swept-body/support policy over every interval. */
internal fun validRoomRouteConnector(from: RoomPoint, to: RoomPoint, canConnect: (RoomPoint, RoomPoint) -> Boolean): Boolean {
    if (roomConnectorWaypoints(from, from) == null || roomConnectorWaypoints(to, to) == null) return false
    val distance = hypot(to.x - from.x, to.z - from.z)
    if (distance > RoomNavigation.MAX_NODES) return false
    val pieces = ceil(distance / 2.0).toInt().coerceAtLeast(1)
    return try {
        if (abs(to.y - from.y) > GEOMETRY_EPSILON) {
            if (!roomRouteLevelHeight(from, to)) return canConnect(from, to)
            // Actual feet can be one carpet above the floor-normalized graph; check the alignment first.
            val height = maxOf(from.y, to.y)
            val alignedFrom = RoomPoint(from.x, height, from.z)
            val alignedTo = RoomPoint(to.x, height, to.z)
            return canConnect(from, alignedFrom) && canConnect(alignedTo, to) &&
                validRoomRouteConnector(alignedFrom, alignedTo, canConnect)
        }
        // Subdividing a checked short jump would place an unsupported endpoint in the gap.
        if (pieces > 1 && roomJumpSpan(from, to) && canConnect(from, to)) return true
        var previous = from
        for (index in 1..pieces) {
            val next = interpolate(from, to, index.toDouble() / pieces)
            if (!canConnect(previous, next)) return false
            previous = next
        }
        true
    } catch (_: RuntimeException) { false }
}

/** Retain jump/stair elbows; the caller splits paths at mandatory targets and door crossings. */
internal fun simplifyRoomPath(points: List<RoomPoint>, canConnect: (RoomPoint, RoomPoint) -> Boolean): List<RoomPoint> {
    val unique = points.filterIndexed { index, point -> index == 0 || point != points[index - 1] }
    if (unique.size < 3) return unique
    if (unique.all { abs(it.y - unique.first().y) <= GEOMETRY_EPSILON } && canConnect(unique.first(), unique.last())) {
        return listOf(unique.first(), unique.last())
    }
    val result = arrayListOf(unique.first())
    var anchor = 0
    while (anchor < unique.lastIndex) {
        var end = anchor + 1
        if (abs(unique[anchor].y - unique[end].y) <= GEOMETRY_EPSILON) {
            // shortcut: inspect at most 64 waypoints per span; extend only if longer local corridors need it.
            while (end < minOf(unique.lastIndex, anchor + 64) && abs(unique[end].y - unique[end + 1].y) <= GEOMETRY_EPSILON) end++
        }
        var next = end
        while (next > anchor + 1 && !canConnect(unique[anchor], unique[next])) next--
        result.add(unique[next])
        anchor = next
    }
    return result
}

/** Fixed display chunks bound resmoothing while preserving every required target/door boundary. */
internal fun roomDisplayStops(stops: List<Int>, start: Int, end: Int): List<Int> = buildList {
    var from = start
    for (to in (stops + end).distinct().filter { it in start..end }.sorted()) {
        while (to - from > 64) { from += 64; add(from) }
        add(to)
        from = to
    }
}

/** Display only: source edges remain the movement route; targets and doors delimit separate calls. */
internal fun smoothRoomDisplayPath(points: List<RoomPoint>, canConnect: (RoomPoint, RoomPoint) -> Boolean): List<RoomPoint> {
    fun safe(from: RoomPoint, to: RoomPoint) = try { canConnect(from, to) } catch (_: RuntimeException) { false }
    val clean = simplifyRoomPath(points, ::safe)
    if (clean.size < 3) return clean
    val ramps = arrayListOf(clean.first())
    val stairDeviation = minOf(ROOM_ROUTE_HEIGHT, BODY_HEIGHT - ROOM_ROUTE_HEIGHT)
    var anchor = 0
    while (anchor < clean.lastIndex) {
        var best = anchor + 1
        val start = clean[anchor]
        val dx = clean[best].x - start.x
        val dz = clean[best].z - start.z
        var trend = 0
        for (end in anchor + 1..minOf(clean.lastIndex, anchor + 64)) {
            val previous = clean[end - 1]
            val next = clean[end]
            val stepX = next.x - previous.x
            val stepZ = next.z - previous.z
            val stepY = next.y - previous.y
            if (hypot(dx, dz) < GEOMETRY_EPSILON || abs(dx * stepZ - dz * stepX) > GEOMETRY_EPSILON ||
                dx * stepX + dz * stepZ <= 0 || abs(stepY) > 1.0 + GEOMETRY_EPSILON || !safe(previous, next)) break
            val direction = if (abs(stepY) <= GEOMETRY_EPSILON) 0 else if (stepY > 0) 1 else -1
            if (trend != 0 && direction != 0 && direction != trend) break
            if (direction != 0) trend = direction
            if (trend == 0) continue
            val distance = hypot(next.x - start.x, next.z - start.z)
            // Keep the elevated display ramp inside the original collision-checked player-height corridor.
            if ((anchor + 1 until end).all { index ->
                val point = clean[index]
                val amount = hypot(point.x - start.x, point.z - start.z) / distance
                abs(point.y - interpolate(start, next, amount).y) <= stairDeviation + GEOMETRY_EPSILON
            }) best = end
        }
        ramps.add(clean[best])
        anchor = best
    }
    val result = arrayListOf(ramps.first())
    var rounded = 0
    for (index in 1 until ramps.lastIndex) {
        val before = ramps[index - 1]
        val corner = ramps[index]
        val after = ramps[index + 1]
        val incoming = hypot(corner.x - before.x, corner.z - before.z)
        val outgoing = hypot(after.x - corner.x, after.z - corner.z)
        if (rounded >= 32 || abs(before.y - corner.y) > GEOMETRY_EPSILON || abs(after.y - corner.y) > GEOMETRY_EPSILON ||
            incoming < GEOMETRY_EPSILON || outgoing < GEOMETRY_EPSILON ||
            abs((corner.x - before.x) * (after.z - corner.z) - (corner.z - before.z) * (after.x - corner.x)) <= GEOMETRY_EPSILON) {
            result.add(corner)
            continue
        }
        val radius = minOf(0.45, incoming / 3, outgoing / 3)
        val entry = interpolate(corner, before, radius / incoming)
        val exit = interpolate(corner, after, radius / outgoing)
        val curve = (0..4).map { sample ->
            val amount = sample / 4.0
            interpolate(interpolate(entry, corner, amount), interpolate(corner, exit, amount), amount)
        }
        if ((listOf(result.last()) + curve + after).zipWithNext().all { (a, b) -> safe(a, b) }) {
            result.addAll(curve)
            rounded++
        } else result.add(corner)
    }
    result.add(ramps.last())
    return result
}

internal fun roomStandingPoint(cell: GridCell, heights: Sequence<Double>, canStand: (RoomPoint) -> Boolean,
    climbable: Boolean = false): RoomPoint? {
    for (height in heights.filter { it.isFinite() && it > cell.y - 1.0 + GEOMETRY_EPSILON && it <= cell.y + GEOMETRY_EPSILON }
        .distinct().sortedDescending()) {
        val point = RoomPoint(cell.x + 0.5, height, cell.z + 0.5)
        if (canStand(point)) return point
    }
    val center = RoomPoint(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
    return center.takeIf { climbable && canStand(it) }
}

enum class RoomGeometryStatus { BUILDING, READY, INCOMPLETE }

/** An immutable room-local snapshot; only the incremental builder reads the client world. */
class RoomGeometry private constructor(
    cells: Set<GridCell>,
    adjacency: Map<GridCell, Set<GridCell>>,
    targets: Map<String, GridCell>,
    val healer: GridCell,
    copyGeometry: Boolean,
    val standingFeet: Map<GridCell, RoomPoint> = emptyMap(),
) {
    internal constructor(cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>, targets: Map<String, GridCell>, healer: GridCell) :
        this(cells, adjacency, targets, healer, true)

    internal constructor(snapshot: RoomLiveGeometrySnapshot, targets: Map<String, GridCell>, healer: GridCell) :
        this(snapshot.cells, snapshot.adjacency, targets, healer, false, snapshot.feet)

    val cells: Set<GridCell> = if (copyGeometry) Collections.unmodifiableSet(LinkedHashSet(cells)) else cells
    val adjacency: Map<GridCell, Set<GridCell>> = if (copyGeometry) Collections.unmodifiableMap(adjacency.mapValues {
        Collections.unmodifiableSet(LinkedHashSet(it.value))
    }) else adjacency
    val targets: Map<String, GridCell> = Collections.unmodifiableMap(LinkedHashMap(targets))
}

/** Run on the client thread and discard on relevant block/chunk changes. Never loads chunks. */
class RoomGeometryBuilder(private val level: ClientLevel, private val room: DetectedRoom, private val captureNeighbors: Boolean = false) {
    var status = RoomGeometryStatus.BUILDING
        private set
    var result: RoomGeometry? = null
        private set
    var failure: String? = null
        private set
    private val cells = room.template.walkable.iterator()
    private val accepted = LinkedHashSet<GridCell>()
    private val adjacent = LinkedHashMap<GridCell, MutableSet<GridCell>>()
    private val edges by lazy {
        val connections = if (captureNeighbors) roomCaptureEdges(accepted) else room.template.adjacency.asSequence().flatMap { (from, next) ->
            next.asSequence().map { from to it }
        }
        connections.iterator()
    }

    /** True means finished (READY or INCOMPLETE), not necessarily a usable route. */
    fun step(maxCells: Int = 128, maxEdges: Int = 256): Boolean {
        if (status != RoomGeometryStatus.BUILDING) return true
        if (!MC.instance.isSameThread || maxCells <= 0 || maxEdges <= 0) return incomplete("Invalid scan thread or budget")
        try {
            repeat(maxCells) {
                if (!cells.hasNext()) return@repeat
                val local = cells.next()
                val point = RoomWorldGeometry.feet(room, local)
                if (!RoomWorldGeometry.loadedEnvelope(level, point, point)) return incomplete("Required room chunk is unloaded")
                if (RoomWorldGeometry.validCell(level, point)) {
                    if (captureNeighbors && accepted.size >= RoomNavigation.MAX_NODES) return incomplete("More than 16384 walkable cells")
                    accepted.add(local)
                    adjacent[local] = linkedSetOf()
                }
            }
            if (cells.hasNext()) return false
            repeat(maxEdges) {
                if (!edges.hasNext()) return@repeat
                val (from, to) = edges.next()
                if (from !in accepted || to !in accepted) return@repeat
                val a = RoomWorldGeometry.feet(room, from)
                val b = RoomWorldGeometry.feet(room, to)
                if (!RoomWorldGeometry.loadedEnvelope(level, a, b)) return incomplete("Required connector chunk is unloaded")
                if (RoomWorldGeometry.validConnector(level, a, b)) adjacent.getValue(from).add(to)
            }
            if (edges.hasNext()) return false
            result = RoomGeometry(accepted, adjacent, room.template.targets, room.template.healer)
            status = RoomGeometryStatus.READY
            return true
        } catch (_: RuntimeException) {
            // Malformed coordinates or a changing/unavailable world never publish a partial graph.
            return incomplete("World collision geometry became unavailable")
        }
    }

    private fun incomplete(reason: String): Boolean {
        status = RoomGeometryStatus.INCOMPLETE
        result = null
        failure = reason
        accepted.clear()
        adjacent.clear()
        return true
    }
}

object RoomWorldGeometry {
    internal fun ignoredRoutingBlock(state: BlockState): Boolean =
        state.block is CarpetBlock || state.block is MossyCarpetBlock || state.`is`(Blocks.IRON_BARS)

    internal fun routingCollisionShape(level: ClientLevel, pos: BlockPos, state: BlockState): VoxelShape =
        roomRoutingCollisionShape(ignoredRoutingBlock(state)) { state.getCollisionShape(level, pos) }

    private fun routingContext(native: CollisionContext) = object : CollisionContext by native {
        override fun getCollisionShape(state: BlockState, level: CollisionGetter, pos: BlockPos): VoxelShape =
            roomRoutingCollisionShape(ignoredRoutingBlock(state)) { native.getCollisionShape(state, level, pos) }
    }

    private val blockContext by lazy { routingContext(CollisionContext.empty()) }
    private val bodyContext by lazy { routingContext(CollisionContext.emptyWithFluidCollisions()) }

    private fun collisions(level: ClientLevel, bounds: AABB, fluids: Boolean = false): Iterable<VoxelShape> = Iterable {
        BlockCollisions(level, if (fluids) bodyContext else blockContext, bounds, false) { _, shape -> shape }
    }

    fun feet(room: DetectedRoom, cell: GridCell, geometry: RoomGeometry? = null): Vec3 = geometry?.standingFeet?.get(cell)?.let {
        Vec3(it.x, it.y, it.z)
    } ?: room.transform.toWorld(cell).let {
        Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5)
    }

    /** Bounds of block coordinates read by graph validation, including collision margins. */
    fun geometryBounds(room: DetectedRoom): AABB {
        val a = room.transform.toWorld(room.template.bounds.min)
        val b = room.transform.toWorld(room.template.bounds.max)
        return AABB(
            floor(minOf(a.x, b.x) + 0.5 - BODY_RADIUS) - 1,
            floor(minOf(a.y, b.y) - SUPPORT_DEPTH) - 1,
            floor(minOf(a.z, b.z) + 0.5 - BODY_RADIUS) - 1,
            floor(maxOf(a.x, b.x) + 0.5 + BODY_RADIUS) + 2,
            floor(maxOf(a.y, b.y) + JUMP_HEADROOM + BODY_HEIGHT) + 2,
            floor(maxOf(a.z, b.z) + 0.5 + BODY_RADIUS) + 2,
        )
    }

    /** Checked nearby floors can also provide an airborne sight join when explicitly requested. */
    fun playerCell(level: ClientLevel, room: DetectedRoom, geometry: RoomGeometry, actualPlayer: Vec3,
        allowSight: Boolean = false): GridCell? {
        if (!MC.instance.isSameThread || !finitePosition(actualPlayer)) return null
        return try {
            val x = floor(actualPlayer.x).toInt()
            val y = floor(actualPlayer.y).toInt()
            val z = floor(actualPlayer.z).toInt()
            val candidates = ArrayList<Pair<GridCell, Double>>()
            for (wx in x - 2..x + 2) for (wy in y - 1..y + 1) for (wz in z - 2..z + 2) {
                val cell = room.transform.toLocal(GridCell(wx, wy, wz))
                if (cell !in geometry.cells) continue
                val distance = feet(room, cell, geometry).distanceToSqr(actualPlayer)
                if (distance <= 4.0) candidates.add(cell to distance)
            }
            candidates.sortBy { it.second }
            candidates.firstOrNull {
                val target = feet(room, it.first, geometry)
                validConnector(level, actualPlayer, target) || allowSight && clearSightLine(level, actualPlayer, target)
            }?.first
        } catch (_: RuntimeException) { null }
    }

    /**
     * Checks a swept player body, not just empty endpoints. One-block ascents require
     * conservative jump headroom; descents cross at the upper height before dropping.
     * Bounds/support checks also make short player-to-node connectors safe over holes.
     */
    fun validConnector(level: ClientLevel, from: Vec3, to: Vec3): Boolean {
        if (!MC.instance.isSameThread || !finitePosition(from) || !finitePosition(to)) return false
        return validRoomConnector(from.roomPoint(), to.roomPoint(),
            { a, b -> loadedEnvelope(level, a.vector(), b.vector()) },
            { a, b -> clearSegment(level, a.vector(), b.vector()) },
            { point, depth, radius -> supported(level, point.vector(), depth, radius) },
            { point -> climbable(level, point.vector()) })
    }

    /** Loaded player-clear level routes require floor support or a bounded safe gap jump. */
    fun validRouteConnector(level: ClientLevel, from: Vec3, to: Vec3): Boolean {
        if (!MC.instance.isSameThread || !finitePosition(from) || !finitePosition(to)) return false
        return validRoomRouteConnector(from.roomPoint(), to.roomPoint()) { a, b -> validConnector(level, a.vector(), b.vector()) }
    }

    /** Display guidance may cross a pit; it never adds walking graph edges or sends player input. */
    fun clearSightLine(level: ClientLevel, from: Vec3, to: Vec3): Boolean {
        if (!MC.instance.isSameThread || !finitePosition(from) || !finitePosition(to)) return false
        return validRoomSightConnector(from.roomPoint(), to.roomPoint(),
            { a, b -> loadedEnvelope(level, a.vector(), b.vector()) },
            { a, b -> clearSegment(level, a.vector(), b.vector()) },
            { point -> validCell(level, point.vector()) })
    }

    /** Build/cache cleaned paths on the client thread; mandatory crossings are separate input spans. */
    fun simplifyPath(level: ClientLevel, points: List<Vec3>): List<Vec3> {
        if (points.any { !finitePosition(it) }) return emptyList()
        if (!MC.instance.isSameThread) return points
        return simplifyRoomPath(points.map { it.roomPoint() }) { a, b ->
            validRouteConnector(level, a.vector(), b.vector())
        }.map { it.vector() }
    }

    /** Raw validated standing nodes only; cache the returned display line separately from movement geometry. */
    fun smoothDisplayPath(level: ClientLevel, standingPath: List<Vec3>): List<Vec3> {
        if (standingPath.any { !finitePosition(it) }) return emptyList()
        if (!MC.instance.isSameThread) return standingPath
        return smoothRoomDisplayPath(standingPath.map { it.roomPoint() }) { a, b ->
            validRouteConnector(level, a.vector(), b.vector())
        }.map { it.vector() }
    }

    /** For previously validated graph edges; cache these when publishing a route, not per frame. */
    fun connectorWaypoints(from: Vec3, to: Vec3): List<Vec3>? {
        if (!finitePosition(from) || !finitePosition(to)) return null
        return roomConnectorWaypoints(from.roomPoint(), to.roomPoint())?.map { it.vector() }
    }

    internal fun validCell(level: ClientLevel, feet: Vec3): Boolean = validConnector(level, feet, feet)

    /** A grid Y identifies (Y-1, Y]; ignored decorations are absent, while slab/stair surfaces retain their height. */
    internal fun standingPoint(level: ClientLevel, cell: GridCell): RoomPoint? {
        val center = Vec3(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
        if (!MC.instance.isSameThread || !loadedEnvelope(level, center, center)) return null
        val probe = AABB(center.x - BODY_RADIUS, center.y - 1.0, center.z - BODY_RADIUS,
            center.x + BODY_RADIUS, center.y + GEOMETRY_EPSILON, center.z + BODY_RADIUS)
        val heights = collisions(level, probe).flatMap { it.toAabbs() }.asSequence()
            .filter { it.minX < probe.maxX && it.maxX > probe.minX && it.minZ < probe.maxZ && it.maxZ > probe.minZ }
            .map { it.maxY }
        return roomStandingPoint(cell, heights, { validCell(level, it.vector()) }, climbable(level, center))
    }

    /** Collision iteration examines adjacent blocks, so require a one-block chunk margin. */
    internal fun loadedEnvelope(level: ClientLevel, from: Vec3, to: Vec3): Boolean {
        if (!finitePosition(from) || !finitePosition(to)) return false
        val minX = Math.floorDiv(floor(minOf(from.x, to.x) - BODY_RADIUS).toInt() - 1, 16)
        val maxX = Math.floorDiv(floor(maxOf(from.x, to.x) + BODY_RADIUS).toInt() + 1, 16)
        val minZ = Math.floorDiv(floor(minOf(from.z, to.z) - BODY_RADIUS).toInt() - 1, 16)
        val maxZ = Math.floorDiv(floor(maxOf(from.z, to.z) + BODY_RADIUS).toInt() + 1, 16)
        for (x in minX..maxX) for (z in minZ..maxZ) if (!level.hasChunk(x, z)) return false
        return true
    }

    private fun clearSegment(level: ClientLevel, from: Vec3, to: Vec3): Boolean =
        minOf(from.y, to.y) >= level.minY && maxOf(from.y, to.y) + BODY_HEIGHT <= level.maxY &&
            !collisions(level, body(from).minmax(body(to)), fluids = true).iterator().hasNext()

    private fun supported(level: ClientLevel, feet: Vec3, depth: Double, radius: Double): Boolean {
        val probe = AABB(feet.x - radius, feet.y - depth, feet.z - radius,
            feet.x + radius, feet.y - GEOMETRY_EPSILON, feet.z + radius)
        return collisions(level, probe).iterator().hasNext()
    }

    private fun climbable(level: ClientLevel, feet: Vec3): Boolean =
        level.getBlockState(BlockPos.containing(feet)).`is`(BlockTags.CLIMBABLE)

    private fun body(feet: Vec3) = AABB(feet.x - BODY_RADIUS, feet.y, feet.z - BODY_RADIUS,
        feet.x + BODY_RADIUS, feet.y + BODY_HEIGHT, feet.z + BODY_RADIUS)

    private fun Vec3.roomPoint() = RoomPoint(x, y, z)
    private fun RoomPoint.vector() = Vec3(x, y, z)

    private fun finitePosition(point: Vec3) = point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
        abs(point.x) < 29_999_999 && abs(point.z) < 29_999_999 && abs(point.y) <= 4096
}
