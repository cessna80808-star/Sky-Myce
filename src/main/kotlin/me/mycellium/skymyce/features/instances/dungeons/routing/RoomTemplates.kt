package me.mycellium.skymyce.features.instances.dungeons.routing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import kotlin.math.abs

data class RoomGrid(val spacing: Int, val offsetX: Int, val offsetZ: Int, val originY: Int)
data class RoomBounds(val min: GridCell, val max: GridCell) {
    fun contains(cell: GridCell) = cell.x in min.x..max.x && cell.y in min.y..max.y && cell.z in min.z..max.z
}
data class RoomLandmark(val cell: GridCell, val block: String)

/** Author-supplied geometry. An omitted cell or edge is never assumed traversable. */
class RoomTemplate internal constructor(
    val id: String,
    val floors: Set<String>,
    val bounds: RoomBounds,
    val landmarks: List<RoomLandmark>,
    val walkable: Set<GridCell>,
    val adjacency: Map<GridCell, Set<GridCell>>,
    val targets: Map<String, GridCell>,
    val healer: GridCell,
)

data class RoomInstanceKey(val templateId: String, val origin: GridCell, val quarterTurns: Int)
data class DetectedRoom(val template: RoomTemplate, val transform: RoomTransform) {
    val key = RoomInstanceKey(template.id, transform.origin, transform.quarterTurns)
}

/** Valid empty catalogs are distinct from malformed data, which [parse] rejects. */
class RoomTemplateCatalog private constructor(val grid: RoomGrid, val rooms: List<RoomTemplate>) {
    private val byFloor = rooms.flatMap { room -> room.floors.map { it to room } }.groupBy({ it.first }, { it.second })

    /** Only nearby grid origins are considered; null blocks are unloaded, never air. */
    fun recognize(floor: String, player: RoomPoint, readBlock: (GridCell) -> String?): DetectedRoom? {
        val candidates = byFloor[floor] ?: return null
        if (!player.x.isFinite() || !player.y.isFinite() || !player.z.isFinite() ||
            abs(player.x) > 29_999_000 || abs(player.z) > 29_999_000 || abs(player.y) > 4096
        ) return null
        val cell = GridCell(kotlin.math.floor(player.x).toInt(), kotlin.math.floor(player.y).toInt(), kotlin.math.floor(player.z).toInt())
        val nearbyCells = ArrayList<GridCell>()
        for (x in cell.x - 2..cell.x + 2) for (y in cell.y - 1..cell.y + 1) for (z in cell.z - 2..cell.z + 2) {
            val dx = x + 0.5 - player.x
            val dy = y - player.y
            val dz = z + 0.5 - player.z
            if (dx * dx + dy * dy + dz * dz <= 4.0) nearbyCells.add(GridCell(x, y, z))
        }
        val nearbyX = Math.floorDiv(cell.x - grid.offsetX, grid.spacing)
        val nearbyZ = Math.floorDiv(cell.z - grid.offsetZ, grid.spacing)
        val blocks = HashMap<GridCell, String?>()
        var match: DetectedRoom? = null
        for (room in candidates) for (rotation in 0..3) {
            val rotate = RoomTransform(GridCell(0, 0, 0), rotation)
            val a = rotate.toWorld(room.bounds.min)
            val b = rotate.toWorld(room.bounds.max)
            val minX = minOf(a.x, b.x)
            val maxX = maxOf(a.x, b.x)
            val minZ = minOf(a.z, b.z)
            val maxZ = maxOf(a.z, b.z)
            if (cell.y - grid.originY !in room.bounds.min.y..room.bounds.max.y) continue
            val fromX = maxOf(nearbyX - 4, ceilDiv(cell.x - maxX - grid.offsetX, grid.spacing))
            val toX = minOf(nearbyX + 4, Math.floorDiv(cell.x - minX - grid.offsetX, grid.spacing))
            val fromZ = maxOf(nearbyZ - 4, ceilDiv(cell.z - maxZ - grid.offsetZ, grid.spacing))
            val toZ = minOf(nearbyZ + 4, Math.floorDiv(cell.z - minZ - grid.offsetZ, grid.spacing))
            for (x in fromX..toX) for (z in fromZ..toZ) {
                val origin = GridCell(grid.offsetX + x * grid.spacing, grid.originY, grid.offsetZ + z * grid.spacing)
                val transform = RoomTransform(origin, rotation)
                if (!room.bounds.contains(transform.toLocal(cell))) continue
                if (nearbyCells.none { transform.toLocal(it) in room.walkable }) continue
                var matches = true
                for (landmark in room.landmarks) {
                    val world = transform.toWorld(landmark.cell)
                    if (!blocks.containsKey(world)) {
                        if (blocks.size >= MAX_MATCH_READS) return null
                        blocks[world] = readBlock(world)
                    }
                    if (blocks[world] != landmark.block) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    if (match != null) return null // Never guess between layouts, origins or orientations.
                    match = DetectedRoom(room, transform)
                }
            }
        }
        return match
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_MATCH_READS = 8192
        private val idPattern = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
        private val blockPattern = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
        private val floorNames = setOf("E") + (1..7).flatMap { listOf("F$it", "M$it") }

        fun parse(bytes: ByteArray): RoomTemplateCatalog {
            require(bytes.size <= MAX_BYTES) { "Room catalog exceeds 4 MiB" }
            try {
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val root = JsonReader(StringReader(text)).use { reader ->
                    reader.strictness = Strictness.STRICT
                    reader.setNestingLimit(16)
                    val parsed = readJson(reader).objectValue()
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing room catalog content" }
                    parsed
                }
                require(root.integer("schema") == 1) { "Unknown room catalog schema" }
                val rawGrid = root.required("grid").objectValue()
                val grid = RoomGrid(rawGrid.integer("spacing"), rawGrid.integer("offsetX"),
                    rawGrid.integer("offsetZ"), rawGrid.integer("originY"))
                require(grid.spacing == 32 && abs(grid.offsetX.toLong()) <= 29_999_000 &&
                    abs(grid.offsetZ.toLong()) <= 29_999_000 && grid.originY in -4096..4096) { "Invalid room grid" }
                val rows = root.required("rooms").arrayValue(256)
                var totalCells = 0
                val ids = HashSet<String>()
                val rooms = rows.map { value ->
                    val row = value.objectValue()
                    val id = row.required("id").stringValue()
                    require(id.matches(idPattern) && ids.add(id)) { "Invalid or duplicate room ID" }
                    val floors = row.required("floors").arrayValue(15).map { it.stringValue() }
                    require(floors.isNotEmpty() && floors.all { it in floorNames } && floors.distinct().size == floors.size) {
                        "Invalid room floors"
                    }
                    val rawBounds = row.required("bounds").objectValue()
                    val bounds = RoomBounds(rawBounds.required("min").cellValue(), rawBounds.required("max").cellValue())
                    require(listOf(bounds.min.x, bounds.min.y, bounds.min.z, bounds.max.x, bounds.max.y, bounds.max.z)
                        .all { it in -128..128 } && bounds.max.x - bounds.min.x in 0..127 &&
                        bounds.max.y - bounds.min.y in 0..127 && bounds.max.z - bounds.min.z in 0..127) { "Invalid room bounds" }
                    val landmarks = row.required("landmarks").arrayValue(64).map {
                        val landmark = it.objectValue()
                        val block = landmark.required("block").stringValue()
                        val cell = landmark.required("cell").cellValue()
                        require(bounds.contains(cell) && block.length <= 128 && block.matches(blockPattern)) { "Invalid room landmark" }
                        RoomLandmark(cell, block)
                    }
                    require(landmarks.size >= 3 && landmarks.map { it.cell }.distinct().size == landmarks.size &&
                        nonCollinear(landmarks.map { it.cell })) { "Room landmarks must include three noncollinear cells" }
                    val walkableList = row.required("walkable").arrayValue(16_384).map { it.cellValue() }
                    require(walkableList.isNotEmpty() && walkableList.all(bounds::contains) &&
                        walkableList.distinct().size == walkableList.size) { "Invalid or duplicate walkable cell" }
                    totalCells += walkableList.size
                    require(totalCells <= 131_072) { "Room catalog has too many walkable cells" }
                    val walkable = walkableList.toSet()
                    val targets = LinkedHashMap<String, GridCell>()
                    for (target in row.required("targets").arrayValue(12)) {
                        val targetRow = target.objectValue()
                        val targetId = targetRow.required("id").stringValue()
                        val position = targetRow.required("cell").cellValue()
                        require(targetId.matches(idPattern) && position in walkable && targets.putIfAbsent(targetId, position) == null) {
                            "Invalid or duplicate room target"
                        }
                    }
                    val healer = row.required("healer").cellValue()
                    require(healer in walkable) { "Healer endpoint is not walkable" }
                    val adjacency = walkable.associateWith { linkedSetOf<GridCell>() }
                    val edges = HashSet<Set<GridCell>>()
                    for (edge in row.required("edges").arrayValue(65_536)) {
                        val pair = edge.arrayValue(2)
                        require(pair.size() == 2) { "Invalid navigation edge" }
                        val first = pair[0].cellValue()
                        val second = pair[1].cellValue()
                        val horizontal = abs(first.x.toLong() - second.x) + abs(first.z.toLong() - second.z)
                        val vertical = abs(first.y.toLong() - second.y)
                        require(first in walkable && second in walkable && horizontal <= 1 && vertical <= 1 &&
                            horizontal + vertical > 0 && edges.add(setOf(first, second))) { "Invalid or duplicate navigation edge" }
                        adjacency.getValue(first).add(second)
                        adjacency.getValue(second).add(first)
                    }
                    RoomTemplate(id, immutableSet(floors), bounds, Collections.unmodifiableList(landmarks),
                        immutableSet(walkable), Collections.unmodifiableMap(adjacency.mapValues { immutableSet(it.value) }),
                        Collections.unmodifiableMap(targets), healer)
                }
                return RoomTemplateCatalog(grid, Collections.unmodifiableList(rooms))
            } catch (exception: Exception) {
                throw IllegalArgumentException("Invalid room catalog: ${exception.message ?: exception.javaClass.simpleName}", exception)
            }
        }

        /** Gson's tree parser overwrites duplicate keys; reject them before constructing the tree. */
        private fun readJson(reader: JsonReader): JsonElement = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name)) { "Duplicate room catalog property" }
                    add(name, readJson(reader))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(size() < 65_536) { "Oversized room catalog array" }
                    add(readJson(reader))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(number.length <= 11 && number.matches(integerPattern)) { "Expected an integer room catalog number" }
                JsonPrimitive(number.toInt())
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Invalid room catalog value")
        }

        private fun JsonObject.required(name: String): JsonElement = requireNotNull(get(name)) { "Missing $name" }
        private fun JsonObject.integer(name: String) = required(name).integerValue()
        private fun JsonElement.objectValue(): JsonObject {
            require(isJsonObject) { "Expected an object" }
            return asJsonObject
        }
        private fun JsonElement.arrayValue(max: Int): JsonArray {
            require(isJsonArray && asJsonArray.size() <= max) { "Invalid or oversized array" }
            return asJsonArray
        }
        private fun JsonElement.stringValue(): String {
            require(isJsonPrimitive && asJsonPrimitive.isString) { "Expected a string" }
            return asString
        }
        private fun JsonElement.integerValue(): Int {
            require(isJsonPrimitive && asJsonPrimitive.isNumber && asString.matches(integerPattern)) { "Expected an integer" }
            return asString.toInt()
        }
        private fun JsonElement.cellValue(): GridCell {
            val cell = arrayValue(3)
            require(cell.size() == 3) { "A cell requires three integer coordinates" }
            return GridCell(cell[0].integerValue(), cell[1].integerValue(), cell[2].integerValue())
        }
        private fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
        private fun nonCollinear(cells: List<GridCell>): Boolean {
            val a = cells[0]
            val b = cells.firstOrNull { it.x != a.x || it.z != a.z } ?: return false
            val dx = b.x - a.x
            val dz = b.z - a.z
            return cells.any { c ->
                dx * (c.z - a.z) != dz * (c.x - a.x)
            }
        }
        private fun ceilDiv(value: Int, divisor: Int) = -Math.floorDiv(-value, divisor)
    }
}

enum class RoomTargetState { UNKNOWN, ACTIVE, CLEARED }

/** Mob disappearance never proves completion; confirmed deaths survive temporary unloads. */
class RoomTargetStates(targetIds: Collection<String>) {
    private enum class Observation { ACTIVE, UNAVAILABLE, KILLED }
    private val observations = targetIds.associateWith { mutableMapOf<Int, Observation>() }
    private val incomplete = mutableSetOf<String>()

    init {
        require(targetIds.size <= 12 && observations.size == targetIds.size)
    }

    fun state(targetId: String): RoomTargetState {
        val mobs = observations[targetId]?.values ?: return RoomTargetState.UNKNOWN
        return when {
            Observation.ACTIVE in mobs -> RoomTargetState.ACTIVE
            targetId !in incomplete && mobs.isNotEmpty() && mobs.all { it == Observation.KILLED } -> RoomTargetState.CLEARED
            else -> RoomTargetState.UNKNOWN
        }
    }

    fun targetFor(entityId: Int): String? = observations.entries.firstOrNull { entityId in it.value }?.key

    fun observe(targetId: String, entityId: Int): Boolean {
        val mobs = observations[targetId] ?: return false
        val before = state(targetId)
        if (mobs[entityId] == Observation.KILLED || mobs[entityId] == Observation.ACTIVE) return false
        if (observations.any { (id, values) -> id != targetId && entityId in values } || mobs.size >= 128 && entityId !in mobs) {
            incomplete.add(targetId)
            return state(targetId) != before
        }
        mobs[entityId] = Observation.ACTIVE
        return state(targetId) != before
    }

    fun removed(entityId: Int, confirmedKilled: Boolean): Boolean {
        var changed = false
        for ((id, mobs) in observations) {
            val old = mobs[entityId] ?: continue
            if (old == Observation.KILLED) continue
            val before = state(id)
            mobs[entityId] = if (confirmedKilled) Observation.KILLED else Observation.UNAVAILABLE
            changed = changed || state(id) != before
        }
        return changed
    }

    fun unloaded(): Boolean {
        var changed = false
        for ((id, mobs) in observations) {
            val before = state(id)
            mobs.replaceAll { _, state -> if (state == Observation.ACTIVE) Observation.UNAVAILABLE else state }
            changed = changed || state(id) != before
        }
        return changed
    }

    fun remaining(): Set<String> = observations.keys.filterTo(linkedSetOf()) { state(it) != RoomTargetState.CLEARED }
    fun reset() {
        observations.values.forEach { it.clear() }
        incomplete.clear()
    }
}
