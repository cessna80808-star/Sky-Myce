package me.mycellium.skymyce.features.instances.dungeons.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Collections
import kotlin.math.abs

/** Explicit local measurements, never guessed spawn locations or a guessed door. */
class RoomTemplateDraft(val id: String, floors: Set<String>, val grid: RoomGrid, val transform: RoomTransform) {
    val floors: Set<String> = Collections.unmodifiableSet(LinkedHashSet(floors))
    var bounds: RoomBounds? = null
        private set
    private var firstCorner: GridCell? = null
    private val recordedLandmarks = LinkedHashMap<GridCell, String>()
    private val recordedTargets = LinkedHashMap<String, GridCell>()
    private val recordedCounts = LinkedHashMap<String, Int>()
    val landmarks: List<RoomLandmark> get() = recordedLandmarks.map { RoomLandmark(it.key, it.value) }
    val targets: Map<String, GridCell> get() = Collections.unmodifiableMap(recordedTargets)
    val targetCounts: Map<String, Int> get() = Collections.unmodifiableMap(recordedCounts)
    var healer: GridCell? = null
        private set
    private var capturedCells: Set<GridCell>? = null
    private var capturedAdjacency: Map<GridCell, Set<GridCell>>? = null

    init {
        require(id.matches(RoomTemplateCatalog.idPattern)) { "Invalid room ID" }
        require(floors.isNotEmpty() && floors.all { it in RoomTemplateCatalog.floorNames }) { "Invalid room floors" }
        require(grid.spacing == 32 && abs(grid.offsetX.toLong()) <= 29_999_000 &&
            abs(grid.offsetZ.toLong()) <= 29_999_000 && grid.originY in -4096..4096) { "Invalid room grid" }
        require(transform.origin.y == grid.originY &&
            (transform.origin.x.toLong() - grid.offsetX) % grid.spacing == 0L &&
            (transform.origin.z.toLong() - grid.offsetZ) % grid.spacing == 0L &&
            abs(transform.origin.x.toLong()) <= 29_999_000 && abs(transform.origin.z.toLong()) <= 29_999_000
        ) { "Room origin must lie on the catalog grid" }
    }

    fun corner(world: GridCell) {
        require(bounds == null) { "Both bounds corners are already recorded" }
        val cell = transform.toLocal(world)
        require(listOf(cell.x, cell.y, cell.z).all { it in -128..128 }) { "Corner exceeds room limits" }
        val first = firstCorner
        if (first == null) { firstCorner = cell; return }
        val candidate = RoomBounds(GridCell(minOf(first.x, cell.x), minOf(first.y, cell.y), minOf(first.z, cell.z)),
            GridCell(maxOf(first.x, cell.x), maxOf(first.y, cell.y), maxOf(first.z, cell.z)))
        require(candidate.max.x - candidate.min.x <= 127 && candidate.max.y - candidate.min.y <= 127 &&
            candidate.max.z - candidate.min.z <= 127) { "Bounds exceed 128 cells on an axis" }
        require(volume(candidate) <= MAX_CAPTURE_CELLS) { "Bounds exceed $MAX_CAPTURE_CELLS candidate cells" }
        bounds = candidate
    }

    fun landmark(world: GridCell, block: String) {
        val cell = local(world)
        require(block.length <= 128 && block.matches(RoomTemplateCatalog.blockPattern)) { "Invalid landmark block" }
        require(recordedLandmarks.size < 64 && cell !in recordedLandmarks) { "Duplicate or too many landmarks" }
        recordedLandmarks[cell] = block
    }

    fun target(id: String, world: GridCell, count: Int) {
        val cell = local(world)
        require(id.matches(RoomTemplateCatalog.idPattern) && id !in recordedTargets && recordedTargets.size < 12) {
            "Invalid, duplicate or too many target IDs"
        }
        require(count in 1..128) { "A target requires a verified starred count from 1 to 128" }
        recordedTargets[id] = cell
        recordedCounts[id] = count
    }

    fun healer(world: GridCell) { healer = local(world) }

    /** Receives the completed collision-checked room-local snapshot, not a partial scan. */
    fun geometry(cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>) {
        val area = requireNotNull(bounds) { "Record both bounds corners first" }
        require(cells.isNotEmpty() && cells.size <= RoomNavigation.MAX_NODES && cells.all(area::contains)) {
            "Captured walkable cells exceed limits or bounds"
        }
        require(adjacency.size <= cells.size && adjacency.all { (from, next) ->
            from in cells && next.size <= 14 && next.all { it in cells && adjacent(from, it) }
        }) { "Invalid captured navigation connectors" }
        capturedCells = Collections.unmodifiableSet(LinkedHashSet(cells))
        capturedAdjacency = Collections.unmodifiableMap(adjacency.mapValues { Collections.unmodifiableSet(LinkedHashSet(it.value)) })
    }

    /** Candidate cells still need native collision/support checks before export or routing. */
    fun candidateTemplate(): DetectedRoom {
        val area = requireNotNull(bounds) { "Record both bounds corners first" }
        val endpoint = requireNotNull(healer) { "Record the healer approach first" }
        require(recordedLandmarks.size >= 3 && RoomTemplateCatalog.nonCollinear(recordedLandmarks.keys.toList())) {
            "Record three structural landmarks that are noncollinear in X/Z first"
        }
        val cells = LinkedHashSet<GridCell>()
        for (x in area.min.x..area.max.x) for (y in area.min.y..area.max.y) for (z in area.min.z..area.max.z) {
            cells.add(GridCell(x, y, z))
        }
        val template = RoomTemplate(id, floors, area, Collections.unmodifiableList(landmarks), Collections.unmodifiableSet(cells),
            emptyMap(), Collections.unmodifiableMap(recordedTargets.toMap()), endpoint,
            Collections.unmodifiableMap(recordedCounts.toMap()))
        return DetectedRoom(template, transform)
    }

    /** The full merged catalog passes the same strict parser as runtime imports before it can be written. */
    fun export(existing: RoomTemplateCatalog?): JsonObject {
        require(existing == null || existing.grid == grid) { "Recorded room uses a different catalog grid" }
        require(existing?.rooms?.none { it.id == id } != false) { "Room ID already exists; existing data will not be overwritten" }
        val cells = requireNotNull(capturedCells) { "Capture complete room geometry first" }
        val adjacency = requireNotNull(capturedAdjacency)
        val template = RoomTemplate(id, floors, requireNotNull(bounds), landmarks, cells, adjacency,
            recordedTargets.toMap(), requireNotNull(healer) { "Record the healer approach first" }, recordedCounts.toMap())
        val json = JsonObject().apply {
            addProperty("schema", 1)
            add("grid", JsonObject().apply {
                addProperty("spacing", grid.spacing); addProperty("offsetX", grid.offsetX)
                addProperty("offsetZ", grid.offsetZ); addProperty("originY", grid.originY)
            })
            add("rooms", JsonArray().apply {
                existing?.rooms?.forEach { add(roomJson(it)) }
                add(roomJson(template))
            })
        }
        RoomTemplateCatalog.parse(json.toString().toByteArray(Charsets.UTF_8))
        return json
    }

    private fun local(world: GridCell): GridCell {
        val cell = transform.toLocal(world)
        require(requireNotNull(bounds) { "Record both bounds corners first" }.contains(cell)) { "Position lies outside room bounds" }
        return cell
    }

    companion object {
        const val MAX_CAPTURE_CELLS = 65_536
        private val CELL_ORDER = compareBy<GridCell>({ it.x }, { it.y }, { it.z })
        private fun volume(bounds: RoomBounds) = (bounds.max.x.toLong() - bounds.min.x + 1) *
            (bounds.max.y - bounds.min.y + 1) * (bounds.max.z - bounds.min.z + 1)
        private fun adjacent(a: GridCell, b: GridCell): Boolean {
            val horizontal = abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z)
            val vertical = abs(a.y.toLong() - b.y)
            return horizontal <= 1 && vertical <= 1 && horizontal + vertical > 0
        }
        private fun cellJson(cell: GridCell) = JsonArray().apply { add(cell.x); add(cell.y); add(cell.z) }
        private fun roomJson(room: RoomTemplate) = JsonObject().apply {
            addProperty("id", room.id)
            add("floors", JsonArray().apply { room.floors.sorted().forEach { add(it) } })
            add("bounds", JsonObject().apply { add("min", cellJson(room.bounds.min)); add("max", cellJson(room.bounds.max)) })
            add("landmarks", JsonArray().apply { room.landmarks.forEach { landmark -> add(JsonObject().apply {
                add("cell", cellJson(landmark.cell)); addProperty("block", landmark.block)
            }) } })
            add("walkable", JsonArray().apply { room.walkable.sortedWith(CELL_ORDER).forEach { add(cellJson(it)) } })
            add("edges", JsonArray().apply {
                val edges = HashSet<Set<GridCell>>()
                for (from in room.adjacency.keys.sortedWith(CELL_ORDER)) for (to in room.adjacency.getValue(from).sortedWith(CELL_ORDER)) {
                    if (edges.add(setOf(from, to))) add(JsonArray().apply { add(cellJson(from)); add(cellJson(to)) })
                }
            })
            add("targets", JsonArray().apply { room.targets.toSortedMap().forEach { (id, cell) -> add(JsonObject().apply {
                addProperty("id", id); add("cell", cellJson(cell)); room.targetCounts[id]?.let { addProperty("count", it) }
            }) } })
            add("healer", cellJson(room.healer))
        }
    }
}
