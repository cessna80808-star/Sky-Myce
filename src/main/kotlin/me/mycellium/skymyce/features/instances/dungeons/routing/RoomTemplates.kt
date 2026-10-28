package me.mycellium.skymyce.features.instances.dungeons.routing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.Collections
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling

data class RoomGrid(val spacing: Int, val offsetX: Int, val offsetZ: Int, val originY: Int)
data class RoomBounds(val min: GridCell, val max: GridCell) {
    fun contains(cell: GridCell) = cell.x in min.x..max.x && cell.y in min.y..max.y && cell.z in min.z..max.z

    /** Player membership tolerates an ordinary jump; geometry and authored nodes still use strict [contains]. */
    fun containsPlayer(cell: GridCell, airborne: Boolean) = cell.x in min.x..max.x && cell.z in min.z..max.z &&
        cell.y.toLong() in min.y.toLong()..(max.y.toLong() + if (airborne) 2 else 0)
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
    val targetCounts: Map<String, Int?>,
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
        internal val idPattern = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
        internal val blockPattern = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
        internal val floorNames = setOf("E") + (1..7).flatMap { listOf("F$it", "M$it") }

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
                    val targetCounts = LinkedHashMap<String, Int?>()
                    for (target in row.required("targets").arrayValue(12)) {
                        val targetRow = target.objectValue()
                        val targetId = targetRow.required("id").stringValue()
                        val position = targetRow.required("cell").cellValue()
                        require(targetId.matches(idPattern) && position in walkable && targets.putIfAbsent(targetId, position) == null) {
                            "Invalid or duplicate room target"
                        }
                        val count = targetRow.get("count")?.integerValue()
                        require(count == null || count in 1..128) { "Invalid starred spawn count" }
                        targetCounts[targetId] = count
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
                        Collections.unmodifiableMap(targets), healer, Collections.unmodifiableMap(targetCounts))
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
        internal fun nonCollinear(cells: List<GridCell>): Boolean {
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

/** Completion needs the authored group count; disappearance or unseen members never prove a clear. */
class RoomTargetStates(targetCounts: Map<String, Int?>) {
    private enum class Observation { ACTIVE, UNAVAILABLE, KILLED }
    private val expectedCounts = targetCounts.toMap()
    private val observations = expectedCounts.mapValues { mutableMapOf<Int, Observation>() }
    private val incomplete = mutableSetOf<String>()

    init {
        require(expectedCounts.size <= 12 && expectedCounts.values.all { it == null || it in 1..128 })
    }

    constructor(targetIds: Collection<String>) : this(targetIds.associateWith { null }) {
        require(observations.size == targetIds.size)
    }

    fun state(targetId: String): RoomTargetState {
        val mobs = observations[targetId]?.values ?: return RoomTargetState.UNKNOWN
        return when {
            Observation.ACTIVE in mobs -> RoomTargetState.ACTIVE
            targetId !in incomplete && mobs.size == expectedCounts[targetId] &&
                mobs.all { it == Observation.KILLED } -> RoomTargetState.CLEARED
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

/** Explicit, local room authoring. Only the client tick reads live collision geometry. */
object RoomTemplateRecorder {
    @Volatile private var draft: RoomTemplateDraft? = null
    val isRecording: Boolean get() = draft != null
    private var world: ClientLevel? = null
    private var roomId = ""
    private var builder: RoomGeometryBuilder? = null
    private var scannedRoom: DetectedRoom? = null
    private var scanComplete = false
    private var scanStatus = "Not scanned"
    private var saving = false

    fun init() { ClientTickEvents.END_CLIENT_TICK.register { tick() } }

    fun command(catalog: () -> RoomTemplateCatalog?, reload: () -> Unit) = literal("record")
        .executes { respond(it.source) { summary() } }
        .then(literal("status").executes { respond(it.source) { summary() } })
        .then(literal("start").then(argument("id", StringArgumentType.word())
            .then(argument("quarterTurns", IntegerArgumentType.integer(0, 3)).executes {
                respond(it.source) {
                    require(!saving) { "A catalog save is still running" }
                    require(draft == null) { "Cancel the current draft before starting another" }
                    val player = requireNotNull(MC.instance.player) { "No player" }
                    val level = requireNotNull(MC.instance.level) { "No world" }
                    require(DungeonAPI.started && !DungeonAPI.completed && !DungeonAPI.inBoss && !player.isDeadOrDying) {
                        "Start recording inside an active dungeon room"
                    }
                    val floor = requireNotNull(DungeonAPI.dungeonFloor) { "Unknown dungeon floor" }.name
                    val block = player.blockPosition()
                    val grid = catalog()?.grid ?: RoomGrid(32, Math.floorMod(block.x, 32), Math.floorMod(block.z, 32), block.y)
                    val origin = GridCell(block.x, grid.originY, block.z)
                    val id = StringArgumentType.getString(it, "id")
                    val turns = IntegerArgumentType.getInteger(it, "quarterTurns")
                    draft = RoomTemplateDraft(id, setOf(floor), grid, RoomTransform(origin, turns))
                    world = level
                    roomId = id
                    "Recording $id on $floor; origin=$origin, rotation=$turns. Aim at two opposite structural /corner blocks."
                }
            })))
        .then(literal("corner").executes { respond(it.source) {
            val pos = structuralBlock()
            edit { it.corner(GridCell(pos.x, pos.y, pos.z)) }; "Structural room corner recorded; ${summary()}"
        } })
        .then(literal("landmark").executes { respond(it.source) {
            val level = requireNotNull(world) { "Start a room draft first" }
            val pos = structuralBlock()
            val state = level.getBlockState(pos)
            edit { it.landmark(GridCell(pos.x, pos.y, pos.z), BuiltInRegistries.BLOCK.getKey(state.block).toString()) }
            "Landmark recorded; ${summary()}"
        } })
        .then(literal("target").then(argument("id", StringArgumentType.word())
            .then(argument("count", IntegerArgumentType.integer(1, 128)).executes { respond(it.source) {
                edit { draft -> draft.target(StringArgumentType.getString(it, "id"), feetCell(), IntegerArgumentType.getInteger(it, "count")) }
                "Starred spawn group recorded; ${summary()}"
            } })))
        .then(literal("healer").executes { respond(it.source) {
            edit { it.healer(feetCell()) }; "Healer approach recorded; ${summary()}"
        } })
        .then(literal("scan").executes { respond(it.source) {
            require(!saving) { "A catalog save is still running" }
            val candidate = current().candidateTemplate()
            scannedRoom = candidate
            builder = RoomGeometryBuilder(requireNotNull(world), candidate, captureNeighbors = true)
            scanComplete = false
            scanStatus = "Scanning loaded room geometry"
            "Scanning $roomId incrementally; keep the room loaded."
        } })
        .then(literal("save").executes { respond(it.source) {
            require(!saving) { "A catalog save is still running" }
            require(scanComplete) { "Complete /scan after the latest room changes before saving" }
            val snapshot = current()
            saving = true
            Scheduling.schedule(0.milliseconds) {
                val saved = runCatching {
                    val path = SkyMyce.configPath.resolve("dungeon-routing-rooms.json")
                    val existing = if (!Files.exists(path)) null else Files.newInputStream(path).use { stream ->
                        RoomTemplateCatalog.parse(stream.readNBytes(RoomTemplateCatalog.MAX_BYTES + 1))
                    }
                    val json = snapshot.export(existing)
                    AtomicJsonFile(path, RoomTemplateCatalog.MAX_BYTES).apply {
                        preserveOriginal("before-room-recording")
                        write(json)
                    }
                }
                MC.instance.execute {
                    saving = false
                    if (saved.isSuccess) {
                        if (draft === snapshot) cancel()
                        feedback("Room saved locally to dungeon-routing-rooms.json; reloading catalog.")
                        reload()
                    } else feedback("Room save rejected: ${saved.exceptionOrNull()?.message}")
                }
            }
            "Saving the recorded draft locally; existing room IDs will not be replaced."
        } })
        .then(literal("cancel").executes { respond(it.source) {
            cancel(); if (saving) "Draft discarded; the previously requested save is still finishing." else "Room recording cancelled."
        } })

    private fun respond(source: FabricClientCommandSource, action: () -> String): Int = try {
        source.sendFeedback(Component.literal(action())); 1
    } catch (error: Exception) {
        source.sendError(Component.literal("Room recording: ${error.message ?: error.javaClass.simpleName}")); 0
    }

    private fun current(): RoomTemplateDraft {
        require(world === MC.instance.level && MC.instance.player?.isDeadOrDying == false) { "Recording world or player changed" }
        return requireNotNull(draft) { "Start a room draft first" }
    }

    private fun edit(action: (RoomTemplateDraft) -> Unit) {
        require(!saving) { "A catalog save is still running" }
        action(current())
        invalidateScan()
    }

    private fun structuralBlock(): BlockPos {
        val level = requireNotNull(world) { "Start a room draft first" }
        val hit = MC.instance.hitResult as? BlockHitResult
        require(hit != null && hit.type == HitResult.Type.BLOCK) { "Look at a structural room block" }
        val pos = hit.blockPos
        require(level.hasChunk(Math.floorDiv(pos.x, 16), Math.floorDiv(pos.z, 16))) { "Selected block chunk is not loaded" }
        val state = level.getBlockState(pos)
        require(!state.isAir && state.fluidState.isEmpty && !state.getCollisionShape(level, pos).isEmpty) {
            "Choose a solid structural block, not air, fluid or decoration"
        }
        return pos
    }

    private fun feetCell(): GridCell = routeFeetCell(requireNotNull(MC.instance.player).position())

    fun cancel() {
        draft = null
        world = null
        roomId = ""
        invalidateScan()
    }

    private fun invalidateScan() {
        builder = null
        scannedRoom = null
        scanComplete = false
        scanStatus = "Not scanned"
    }

    fun blockChanged(pos: BlockPos) {
        val room = scannedRoom ?: return
        if (RoomWorldGeometry.geometryBounds(room).contains(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())) {
            invalidateScan()
            feedback("Recorded room geometry changed; run /sm route record scan again before saving.")
        }
    }

    fun chunkChanged(level: ClientLevel, x: Int, z: Int) {
        if (world !== level) return
        val room = scannedRoom ?: return
        val bounds = RoomWorldGeometry.geometryBounds(room)
        if ((x + 1) * 16 <= bounds.minX || x * 16 >= bounds.maxX ||
            (z + 1) * 16 <= bounds.minZ || z * 16 >= bounds.maxZ) return
        invalidateScan()
        feedback("Recorded room chunks changed; run /sm route record scan again before saving.")
    }

    private fun tick() {
        val recording = draft ?: return
        val player = MC.instance.player
        if (world !== MC.instance.level || player == null || player.isDeadOrDying || DungeonAPI.ownPlayer?.dead == true ||
            !DungeonAPI.started || DungeonAPI.completed || DungeonAPI.inBoss) {
            cancel()
            return
        }
        val scan = builder ?: return
        if (!scan.step()) return
        builder = null
        val geometry = scan.result
        if (geometry == null) {
            scannedRoom = null
            scanStatus = "Scan incomplete: ${scan.failure}; adjust bounds or load chunks, then /scan again"
        } else try {
            recording.geometry(geometry.cells, geometry.adjacency)
            scanComplete = true
            scanStatus = "${geometry.cells.size} walkable cells; ready to /save"
        } catch (error: Exception) {
            scannedRoom = null
            scanStatus = "Scan rejected: ${error.message}"
        }
        feedback(scanStatus)
    }

    private fun summary(): String {
        val recording = draft ?: return if (saving) "Room save is finishing" else "No room draft; use /sm route record start <id> <quarterTurns>"
        return "$roomId; bounds=${recording.bounds}, landmarks=${recording.landmarks.size}, " +
            "targets=${recording.targets.size}, healer=${recording.healer}; $scanStatus"
    }

    private fun feedback(message: String) {
        MC.instance.player?.sendSystemMessage(Component.literal("Sky-Hawk recording: $message"))
    }
}

private val minibossProfiles = setOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")
private val minibossHealthLabel = Regex("""(?:\[Lv\s*\d+\]\s*)?(?:${minibossProfiles.joinToString("|", transform = Regex::escape)})\s+\d[\d.,]*[kKmMbB]?(?:/\d[\d.,]*[kKmMbB]?)?❤""")

internal fun dungeonMinibossProfile(name: String?) = name != null && name in minibossProfiles

internal fun dungeonStarPlayer(uuidVersion: Int, local: Boolean, invisible: Boolean, profile: String? = null) =
    !local && (dungeonMinibossProfile(profile) || uuidVersion == 2 && !invisible)

internal fun dungeonStarLabel(name: String?) = name?.contains('✯') == true

/** A complete starred health label can identify a miniboss before its body profile arrives. */
internal fun dungeonMinibossLabel(name: String?) = dungeonStarLabel(name) &&
    minibossHealthLabel.matches(name!!.substringAfterLast('✯').trim())

/** Profile eligibility never supplies the star that routing requires. */
internal fun dungeonRouteStarTarget(starred: Boolean, miniboss: Boolean, throughMobs: Boolean, throughMinibosses: Boolean) =
    starred && if (miniboss) throughMinibosses else throughMobs

/** NoammAddons StarMobESP: resolve the health label to its real entity, not the armor stand's feet. */
internal fun <T> dungeonStarMob(labelId: Int, name: String, entity: (Int) -> T?,
    eligible: (T) -> Boolean, beneath: () -> Iterable<T>): T? {
    if (!dungeonStarLabel(name) || !name.trimEnd().endsWith('❤')) return null
    // Withermancers have two projectile entities between their body and their health label.
    val offset = if (name.contains("Withermancer", ignoreCase = true)) 3 else 1
    return entity(labelId - offset)?.takeIf(eligible) ?: beneath().firstOrNull(eligible)
}
