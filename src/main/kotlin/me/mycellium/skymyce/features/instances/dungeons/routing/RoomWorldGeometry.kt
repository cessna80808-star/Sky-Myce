package me.mycellium.skymyce.features.instances.dungeons.routing

import me.mycellium.skymyce.utils.MC
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val BODY_RADIUS = 0.3
private const val BODY_HEIGHT = 1.8
private const val SUPPORT_DEPTH = 0.6
private const val JUMP_HEADROOM = 0.25
private const val GEOMETRY_EPSILON = 0.00001
private val CAPTURE_DIRECTIONS = arrayOf(GridCell(-1, 0, 0), GridCell(1, 0, 0), GridCell(0, 0, -1), GridCell(0, 0, 1))

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
        abs(to.y - from.y) > 1.0 + GEOMETRY_EPSILON || horizontal > 4.0 + GEOMETRY_EPSILON
    ) return null
    if (from == to) return listOf(from)
    if (horizontal < GEOMETRY_EPSILON * GEOMETRY_EPSILON) return listOf(from, to)
    val upperY = maxOf(from.y, to.y) + if (to.y - from.y > GEOMETRY_EPSILON) JUMP_HEADROOM else 0.0
    return listOf(from, RoomPoint(from.x, upperY, from.z), RoomPoint(to.x, upperY, to.z), to)
        .distinct()
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
        (0..samples).all {
            val point = interpolate(raisedFrom, raisedTo, it.toDouble() / samples)
            climbable(point) || supported(point, depth, supportRadius)
        }
    } catch (_: RuntimeException) { false }
}

private fun interpolate(from: RoomPoint, to: RoomPoint, amount: Double) = RoomPoint(
    from.x + (to.x - from.x) * amount,
    from.y + (to.y - from.y) * amount,
    from.z + (to.z - from.z) * amount,
)

enum class RoomGeometryStatus { BUILDING, READY, INCOMPLETE }

/** An immutable room-local snapshot; only the incremental builder reads the client world. */
class RoomGeometry private constructor(
    cells: Set<GridCell>,
    adjacency: Map<GridCell, Set<GridCell>>,
    targets: Map<String, GridCell>,
    val healer: GridCell,
    copyGeometry: Boolean,
) {
    internal constructor(cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>, targets: Map<String, GridCell>, healer: GridCell) :
        this(cells, adjacency, targets, healer, true)

    internal constructor(snapshot: RoomLiveGeometrySnapshot, targets: Map<String, GridCell>, healer: GridCell) :
        this(snapshot.cells, snapshot.adjacency, targets, healer, false)

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
    fun feet(room: DetectedRoom, cell: GridCell): Vec3 = room.transform.toWorld(cell).let {
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

    /** Snap only through a collision-safe, supported connector, never across a nearby wall. */
    fun playerCell(level: ClientLevel, room: DetectedRoom, geometry: RoomGeometry, actualPlayer: Vec3): GridCell? {
        if (!MC.instance.isSameThread || !finitePosition(actualPlayer)) return null
        return try {
            val x = floor(actualPlayer.x).toInt()
            val y = floor(actualPlayer.y).toInt()
            val z = floor(actualPlayer.z).toInt()
            val candidates = ArrayList<Pair<GridCell, Double>>()
            for (wx in x - 2..x + 2) for (wy in y - 1..y + 1) for (wz in z - 2..z + 2) {
                val cell = room.transform.toLocal(GridCell(wx, wy, wz))
                if (cell !in geometry.cells) continue
                val distance = feet(room, cell).distanceToSqr(actualPlayer)
                if (distance <= 4.0) candidates.add(cell to distance)
            }
            candidates.sortBy { it.second }
            candidates.firstOrNull { validConnector(level, actualPlayer, feet(room, it.first)) }?.first
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

    /** For previously validated graph edges; cache these when publishing a route, not per frame. */
    fun connectorWaypoints(from: Vec3, to: Vec3): List<Vec3>? {
        if (!finitePosition(from) || !finitePosition(to)) return null
        return roomConnectorWaypoints(from.roomPoint(), to.roomPoint())?.map { it.vector() }
    }

    internal fun validCell(level: ClientLevel, feet: Vec3): Boolean = validConnector(level, feet, feet)

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
            level.noBlockCollision(null, body(from).minmax(body(to)), true)

    private fun supported(level: ClientLevel, feet: Vec3, depth: Double, radius: Double): Boolean {
        val probe = AABB(feet.x - radius, feet.y - depth, feet.z - radius,
            feet.x + radius, feet.y - GEOMETRY_EPSILON, feet.z + radius)
        return !level.noBlockCollision(null, probe)
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
