package me.mycellium.skymyce.features.instances.dungeons.routing

import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.phys.Vec3
import org.joml.Vector2ic
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

data class RoomSegment(val x: Int, val z: Int)
data class DungeonDoor(val from: Set<RoomSegment>, val to: Set<RoomSegment>, val door: GridCell,
    val approach: GridCell, val departure: GridCell, val kind: String, val closed: Boolean)
data class DungeonExit(val door: GridCell, val approach: GridCell, val kind: String,
    val via: Set<RoomSegment> = emptySet(), val doors: List<DungeonDoor> = emptyList(), val reachesBlood: Boolean = false)

internal fun dungeonExitApproach(cell: GridCell, exit: DungeonExit): Boolean {
    val dx = cell.x.toDouble() - exit.approach.x
    val dz = cell.z.toDouble() - exit.approach.z
    return abs(cell.y.toDouble() - exit.approach.y) <= 6 && dx * dx + dz * dz <= 4 &&
        (cell.x.toDouble() - exit.door.x) * (exit.approach.x.toDouble() - exit.door.x) +
        (cell.z.toDouble() - exit.door.z) * (exit.approach.z.toDouble() - exit.door.z) > 0
}

data class LiveDungeonRoom(
    val key: String,
    val name: String,
    val segments: Set<RoomSegment>,
    val cleared: Boolean,
    val exit: DungeonExit?,
    val fairy: Boolean = false,
) {
    val routingSegments = segments + exit?.via.orEmpty()
    val minX get() = routingSegments.minOf { it.x }
    val minZ get() = routingSegments.minOf { it.z }
    val maxX get() = routingSegments.maxOf { it.x } + 31
    val maxZ get() = routingSegments.maxOf { it.z } + 31
    fun containsXZ(x: Int, z: Int) = segments.any { x in it.x..it.x + 31 && z in it.z..it.z + 31 }
    fun containsRouteXZ(x: Int, z: Int) = routingSegments.any { x in it.x..it.x + 31 && z in it.z..it.z + 31 }
    fun containsPositionXZ(x: Double, z: Double) = routingSegments.any {
        x >= it.x - 0.5 && x < it.x + 31.5 && z >= it.z - 0.5 && z < it.z + 31.5
    }
}

/** Classes that rush Blood. An unknown class (before the run reports one) keeps routing on. */
internal val BLOOD_RUSH_CLASSES = setOf(DungeonClass.MAGE, DungeonClass.ARCHER, DungeonClass.HEALER)
internal fun bloodRoutingForClass(rushClassesOnly: Boolean, dungeonClass: DungeonClass?) =
    !rushClassesOnly || dungeonClass == null || dungeonClass in BLOOD_RUSH_CLASSES

internal fun dungeonRouteVisible(room: LiveDungeonRoom?, bloodOpened: Boolean = false) = !bloodOpened && room?.exit != null

internal fun dungeonRoutingActive(inDungeon: Boolean, knownFloor: Boolean, completed: Boolean, inBoss: Boolean) =
    inDungeon && knownFloor && !completed && !inBoss

internal data class DungeonMapLayout(
    val entranceX: Int, val entranceZ: Int, val roomSize: Int,
    val physicalX: Int, val physicalZ: Int,
) {
    val stride get() = roomSize + 4
    fun map(segment: RoomSegment) = RoomSegment(
        entranceX + Math.floorDiv(segment.x - physicalX, 32) * stride,
        entranceZ + Math.floorDiv(segment.z - physicalZ, 32) * stride,
    )
    fun world(tile: RoomSegment) = RoomSegment(
        physicalX + Math.floorDiv(tile.x - entranceX, stride) * 32,
        physicalZ + Math.floorDiv(tile.z - entranceZ, stride) * 32,
    )
}

/** Verified physical grid: Skyblocker DungeonMapUtils and Noamm DungeonScanner; no room assets are imported. */
internal fun dungeonGridSegment(x: Double, z: Double) = RoomSegment(
    floor((x + 8.5) / 32).toInt() * 32 - 8,
    floor((z + 8.5) / 32).toInt() * 32 - 8,
)

private val MAP_DIRECTIONS = listOf(RoomSegment(-1, 0), RoomSegment(1, 0), RoomSegment(0, -1), RoomSegment(0, 1))
private val ROOM_MAP_COLOURS = setOf(18, 30, 34, 62, 63, 66, 74, 82, 85)
private val OPEN_MAP_DOOR_COLOURS = setOf(30, 34, 62, 63, 66, 74, 82)
/** Normal and unopened room colours, the only ones whose tiles join into larger rooms. */
private val JOINABLE_MAP_COLOURS = setOf(63, 85)
private val CHECKMARK_MAP_COLOURS = setOf(30, 34)
private fun ByteArray.mapColour(x: Int, z: Int) = if (size == 16384 && x in 0..127 && z in 0..127) this[x + z * 128].toInt() and 255 else -1
private fun roomKey(segments: Set<RoomSegment>) = "live:" + segments.sortedWith(compareBy({ it.x }, { it.z })).joinToString(";") { "${it.x},${it.z}" }

internal data class DungeonScannedTile(val x: Int, val z: Int, val type: String, val state: String,
    val door: Boolean = false, val separator: Boolean = false, val opened: Boolean = false, val name: String? = null)

internal class DungeonScannedMap(val colours: ByteArray, val layout: DungeonMapLayout,
    val opened: Map<GridCell, Boolean>, val names: Map<RoomSegment, String>, val cleared: Set<RoomSegment>)

/** A separate topology image reuses the planner; rendered colours and unopened brightness carry no clear-state meaning. */
internal fun dungeonScannedMap(tiles: List<DungeonScannedTile>): DungeonScannedMap? {
    if (tiles.isEmpty() || tiles.size > 121 || tiles.map { it.x to it.z }.distinct().size != tiles.size) return null
    val colours = ByteArray(16384)
    val layout = DungeonMapLayout(5, 5, 16, -200, -200)
    val opened = linkedMapOf<GridCell, Boolean>()
    val names = linkedMapOf<RoomSegment, String>()
    val cleared = linkedSetOf<RoomSegment>()
    for (tile in tiles) {
        if (tile.x !in -185..-25 || tile.z !in -185..-25 || (tile.x + 185) % 16 != 0 || (tile.z + 185) % 16 != 0) return null
        val column = (tile.x + 185) / 16
        val row = (tile.z + 185) / 16
        val oddX = column % 2 != 0
        val oddZ = row % 2 != 0
        if (tile.door && (oddX == oddZ || tile.separator) || !tile.door && tile.separator != (oddX || oddZ)) return null
        val colour = if (tile.door) when (tile.type) {
            "BLOOD" -> 18; "ENTRANCE" -> 30; "WITHER" -> 119; "NORMAL" -> 63; else -> return null
        } else when (tile.type) {
            "BLOOD" -> 18; "ENTRANCE" -> 30; "FAIRY" -> 82; "CHAMPION" -> 74
            "PUZZLE" -> 66; "RARE" -> 34; "TRAP" -> 62; "NORMAL" -> 63; else -> return null
        }
        val x = 5 + column / 2 * 20 + if (oddX) 16 else if (tile.door) 7 else 0
        val z = 5 + row / 2 * 20 + if (oddZ) 16 else if (tile.door) 7 else 0
        val width = if (oddX) 4 else if (tile.door) 3 else 16
        val height = if (oddZ) 4 else if (tile.door) 3 else 16
        for (dx in 0 until width) for (dz in 0 until height) colours[(x + dx) + (z + dz) * 128] = colour.toByte()
        if (tile.door) opened[GridCell(tile.x, 69, tile.z)] = tile.opened
        else if (!tile.separator) {
            val segment = RoomSegment(tile.x - 15, tile.z - 15)
            tile.name?.takeUnless { it == "Unknown" }?.let { names[segment] = it }
            if (tile.state == "GREEN" || tile.state == "CLEARED") {
                cleared.add(segment)
                if (tile.type != "ENTRANCE") colours[(x + 8) + (z + 8) * 128] = if (tile.state == "GREEN") 30 else 34
            }
        }
    }
    return DungeonScannedMap(colours, layout, opened, names, cleared)
}

internal fun dungeonScannedRoom(scan: DungeonScannedMap, current: RoomSegment,
    readGate: ((GridCell) -> Int?)? = null, position: RoomPoint? = null, previous: DungeonExit? = null): LiveDungeonRoom? {
    val observed = dungeonMapRoom(scan.colours, scan.layout, current, { cell ->
        readGate?.invoke(cell) ?: if (scan.opened[cell] == true) 0 else null
    }, position, previous) ?: return null
    return observed.copy(name = observed.segments.firstNotNullOfOrNull { scan.names[it] } ?: observed.name,
        cleared = observed.segments.any { it in scan.cleared })
}

/** Noamm distinguishes a wide room separator from a door by sampling four pixels beside its centre. */
private fun dungeonRoomTiles(colours: ByteArray, layout: DungeonMapLayout, tile: RoomSegment): Set<RoomSegment>? {
    val tiles = linkedSetOf(tile)
    val queue = ArrayDeque<RoomSegment>().apply { add(tile) }
    if (colours.mapColour(tile.x, tile.z) in JOINABLE_MAP_COLOURS) while (queue.isNotEmpty()) {
        val from = queue.removeFirst()
        for (direction in MAP_DIRECTIONS) {
            val next = RoomSegment(from.x + direction.x * layout.stride, from.z + direction.z * layout.stride)
            val seamX = from.x + when (direction.x) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 }
            val seamZ = from.z + when (direction.z) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 }
            if (colours.mapColour(next.x, next.z) in JOINABLE_MAP_COLOURS &&
                colours.mapColour(seamX, seamZ) in JOINABLE_MAP_COLOURS &&
                colours.mapColour(seamX - if (direction.z != 0) 4 else 0, seamZ - if (direction.x != 0) 4 else 0) in JOINABLE_MAP_COLOURS && tiles.add(next)) {
                if (tiles.size > 4) return null
                queue.add(next)
            }
        }
    }
    return tiles
}

internal fun dungeonMapRoom(colours: ByteArray, layout: DungeonMapLayout, current: RoomSegment,
    readGate: ((GridCell) -> Int?)? = null, position: RoomPoint? = null, previous: DungeonExit? = null): LiveDungeonRoom? {
    if (colours.size != 16384 || layout.roomSize !in 6..24) return null
    val tile = layout.map(current)
    val colour = colours.mapColour(tile.x, tile.z)
    if (colour !in ROOM_MAP_COLOURS) return null
    val tiles = dungeonRoomTiles(colours, layout, tile) ?: return null
    val segments = tiles.mapTo(linkedSetOf(), layout::world)
    val checked = tiles.any {
        val centre = colours.mapColour(it.x + layout.roomSize / 2, it.z + layout.roomSize / 2)
        centre in CHECKMARK_MAP_COLOURS && centre != colours.mapColour(it.x, it.z)
    }
    val name = when (colour) { 18 -> "Blood room"; 30 -> "Entrance"; 34 -> "Rare room"; 62 -> "Trap room"; 66 -> "Puzzle room"; 74 -> "Miniboss room"; 82 -> "Fairy room"; else -> "Dungeon room" }
    return LiveDungeonRoom(roomKey(segments), name, segments, checked && colour != 30,
        dungeonMapExit(colours, layout, segments, position, previous, readGate), colour == 82)
}

/** Known map components and confirmed door pixels establish the whole route, independent of gate opening. */
internal fun dungeonMapExit(colours: ByteArray, layout: DungeonMapLayout, segments: Set<RoomSegment>,
    position: RoomPoint? = null, previous: DungeonExit? = null,
    readGate: ((GridCell) -> Int?)? = null): DungeonExit? {
    if (colours.size != 16384 || layout.roomSize !in 6..24 || segments.isEmpty()) return null
    val tiles = linkedSetOf<RoomSegment>()
    for (x in Math.floorMod(layout.entranceX, layout.stride)..127 step layout.stride)
        for (z in Math.floorMod(layout.entranceZ, layout.stride)..127 step layout.stride)
            if (colours.mapColour(x, z) in ROOM_MAP_COLOURS) tiles.add(RoomSegment(x, z))
    if (tiles.size > 64) return null
    fun neighbour(from: RoomSegment, direction: RoomSegment) = RoomSegment(from.x + direction.x * layout.stride, from.z + direction.z * layout.stride)
    fun doorPosition(from: RoomSegment, direction: RoomSegment): GridCell {
        val segment = layout.world(from)
        return GridCell(segment.x + 15 + direction.x * 16, 69, segment.z + 15 + direction.z * 16)
    }
    data class Gate(val kind: String, val closed: Boolean)
    val gates = HashMap<GridCell, Gate?>()
    fun gate(from: RoomSegment, direction: RoomSegment): Gate? {
        val door = doorPosition(from, direction)
        return gates.getOrPut(door) {
            val mapped = colours.mapColour(
                from.x + when (direction.x) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 },
                from.z + when (direction.z) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 },
            )
            if (mapped !in OPEN_MAP_DOOR_COLOURS && mapped != 18 && mapped != 85 && mapped != 119)
                return@getOrPut null // A coal-colored wall is not evidence of a doorway.
            val actual = readGate?.invoke(door)
            val kind = when (if (actual == 18 || actual == 119) actual else mapped) {
                18 -> "Blood door"
                119 -> "Wither door"
                else -> "Dungeon door"
            }
            Gate(kind, actual != 0 && (actual == 18 || actual == 119 || mapped == 18 || mapped == 119))
        }
    }
    val components = linkedMapOf<RoomSegment, Set<RoomSegment>>()
    for (tile in tiles) if (tile !in components) {
        val joined = dungeonRoomTiles(colours, layout, tile) ?: return null
        val physical = joined.mapTo(linkedSetOf(), layout::world)
        for (part in joined) components[part] = physical
    }
    val current = components[layout.map(segments.first())] ?: return null
    if (segments.any { components[layout.map(it)] != current } || current.any { layout.map(it).let { tile -> colours.mapColour(tile.x, tile.z) == 18 } }) return null
    val blood = components.filterKeys { colours.mapColour(it.x, it.z) == 18 }.values.toSet()
    val adjacency = linkedMapOf<Set<RoomSegment>, MutableList<DungeonDoor>>()
    for (tile in tiles) for (direction in MAP_DIRECTIONS) {
        val from = components.getValue(tile)
        val to = components[neighbour(tile, direction)].orEmpty()
        if (from == to) continue
        val gate = gate(tile, direction) ?: continue
        val door = doorPosition(tile, direction)
        adjacency.getOrPut(from) { arrayListOf() }.add(DungeonDoor(from, to, door,
            GridCell(door.x - direction.x * 3, door.y, door.z - direction.z * 3),
            GridCell(door.x + direction.x * 3, door.y, door.z + direction.z * 3), gate.kind, gate.closed))
    }
    fun travel(from: GridCell?, door: DungeonDoor): Double = if (from != null)
        hypot((door.approach.x - from.x).toDouble(), (door.approach.z - from.z).toDouble())
        else if (position != null) hypot(door.approach.x + 0.5 - position.x, door.approach.z + 0.5 - position.z)
        else current.minOf { hypot(door.approach.x - it.x - 15.0, door.approach.z - it.z - 15.0) }
    // Arrival-door states price travel between actual portals instead of arbitrary room centres.
    data class State(val room: Set<RoomSegment>, val entered: GridCell?)
    data class Search(val state: State, val cost: Double, val path: List<DungeonDoor>)
    val queue = PriorityQueue(compareBy<Search>({ it.cost }, { it.path.size }, { it.state.entered?.x ?: Int.MIN_VALUE }, { it.state.entered?.z ?: Int.MIN_VALUE }))
    val initial = State(current, null)
    val distances = hashMapOf(initial to 0.0)
    val routes = linkedMapOf<GridCell, List<DungeonDoor>>()
    val routeCosts = hashMapOf<GridCell, Double>()
    queue.add(Search(initial, 0.0, emptyList()))
    var complete: List<DungeonDoor>? = null
    while (queue.isNotEmpty()) {
        val entry = queue.remove()
        if (entry.cost != distances[entry.state]) continue
        if (entry.state.room in blood) { complete = entry.path; break }
        for (door in adjacency[entry.state.room].orEmpty()) {
            if (door.door == entry.state.entered || door.to == current || entry.path.any { it.from == door.to }) continue
            val cost = entry.cost + travel(entry.path.lastOrNull()?.departure, door) + 6.0
            val path = entry.path + door
            if (door.to.isEmpty()) {
                if (path.any { it.kind != "Dungeon door" } && cost < (routeCosts[door.door] ?: Double.POSITIVE_INFINITY)) {
                    routes[door.door] = path
                    routeCosts[door.door] = cost
                }
                continue
            }
            val state = State(door.to, door.door)
            if (cost >= (distances[state] ?: Double.POSITIVE_INFINITY)) continue
            distances[state] = cost
            if (door.closed && adjacency[door.to].orEmpty().size == 1) routes[door.door] = path
            queue.add(Search(state, cost, path))
        }
    }
    // When Blood is unknown, guide only the prefix shared by every confirmed forward gate frontier.
    var path = complete ?: routes.values.firstOrNull()?.withIndex()?.takeWhile { (index, door) ->
        routes.values.all { it.getOrNull(index)?.door == door.door }
    }?.map { it.value }?.takeIf { it.isNotEmpty() } ?: return null
    if (previous != null && previous.reachesBlood == (complete != null) && previous.doors.isNotEmpty() &&
        (complete != null || previous.doors.last().door == path.last().door)) {
        val retained = arrayListOf<DungeonDoor>()
        var component = current
        var cost = 0.0
        for (old in previous.doors) {
            val door = adjacency[component].orEmpty().firstOrNull { it.door == old.door && it.to == old.to && it.from == old.from } ?: break
            cost += travel(retained.lastOrNull()?.departure, door) + 6.0
            retained.add(door)
            component = door.to
        }
        val selectedCost = path.withIndex().sumOf { (index, door) -> travel(path.getOrNull(index - 1)?.departure, door) + 6.0 }
        if (retained.size == previous.doors.size && (complete == null || component in blood) && cost <= selectedCost + 3.0) path = retained
    }
    val first = path.first()
    return DungeonExit(first.door, first.approach, first.kind, doors = path, reachesBlood = complete != null)
}

/** Without map direction, only a unique actual closed Blood/Wither gate is an established destination. */
internal fun dungeonClosedExit(segments: Set<RoomSegment>, read: (GridCell) -> Int?): DungeonExit? {
    val exits = mutableListOf<DungeonExit>()
    for (segment in segments) for (direction in MAP_DIRECTIONS) {
        if (RoomSegment(segment.x + direction.x * 32, segment.z + direction.z * 32) in segments) continue
        val door = GridCell(segment.x + 15 + direction.x * 16, 69, segment.z + 15 + direction.z * 16)
        val colour = read(door)?.takeIf { it == 119 || it == 18 } ?: continue
        // Hypixel gates occupy three blocks across and four high at the room-grid boundary.
        if ((0..3).any { dy -> (-1..1).any { across -> read(GridCell(door.x + direction.z * across, door.y + dy, door.z + direction.x * across)) != colour } }) continue
        exits.add(DungeonExit(door, GridCell(door.x - direction.x * 3, door.y, door.z - direction.z * 3),
            if (colour == 18) "Blood door" else "Wither door"))
    }
    return exits.singleOrNull()
}

/** Optional public Skyblocker metadata; native map fallback needs no other mod or secret-waypoint setting. */
object RoomDungeonContext {
    /** Verified optional NoammAddons 1.2.9 getters; incompatible/absent versions retain native map routing. */
    private class NoammBridge(loader: ClassLoader) {
        private val scannerClass = Class.forName("com.github.noamm9.utils.dungeons.map.handlers.DungeonScanner", false, loader)
        private val scanner = scannerClass.getField("INSTANCE").get(null)
        private val dungeonList = scannerClass.getMethod("getDungeonList")
        private val room = Class.forName("com.github.noamm9.utils.dungeons.map.core.RoomTile", false, loader)
        private val door = Class.forName("com.github.noamm9.utils.dungeons.map.core.DoorTile", false, loader)
        private val tile = Class.forName("com.github.noamm9.utils.dungeons.map.core.Tile", false, loader)
        private val roomData = Class.forName("com.github.noamm9.utils.dungeons.map.core.RoomData", false, loader)
        private val uniqueRoom = Class.forName("com.github.noamm9.utils.dungeons.map.core.UniqueRoom", false, loader)
        private val x = tile.getMethod("getX")
        private val z = tile.getMethod("getZ")
        private val state = tile.getMethod("getState")
        private val data = room.getMethod("getData")
        private val roomType = roomData.getMethod("getType")
        private val roomName = roomData.getMethod("getName")
        private val separator = room.getMethod("isSeparator")
        private val unique = room.getMethod("getUniqueRoom")
        private val mainRoom = uniqueRoom.getMethod("getMainRoom")
        private val doorType = door.getMethod("getType")
        private val opened = door.getMethod("getOpened")

        fun snapshot(): List<DungeonScannedTile>? {
            val list = dungeonList.invoke(scanner) as? Array<*> ?: return null
            if (list.size != 121) return null
            val snapshot = arrayListOf<DungeonScannedTile>()
            for ((index, value) in list.withIndex()) {
                val isRoom = room.isInstance(value)
                val isDoor = door.isInstance(value)
                if (!isRoom && !isDoor) continue
                val worldX = x.invoke(value) as? Int ?: return null
                val worldZ = z.invoke(value) as? Int ?: return null
                if (worldX != -185 + index % 11 * 16 || worldZ != -185 + index / 11 * 16) return null
                val metadata = if (isRoom) data.invoke(value) else null
                val name = if (isRoom) roomName.invoke(metadata) as? String else null
                val owner = if (isRoom && name != "Unknown") unique.invoke(value)?.let { mainRoom.invoke(it) } ?: value else value
                snapshot.add(DungeonScannedTile(worldX, worldZ,
                    ((if (isRoom) roomType.invoke(metadata) else doorType.invoke(value)) as? Enum<*>)?.name ?: return null,
                    (state.invoke(owner) as? Enum<*>)?.name ?: return null,
                    isDoor, isRoom && separator.invoke(value) == true, isDoor && opened.invoke(value) == true, name))
            }
            return snapshot
        }
    }

    private val noammBridge by lazy { runCatching { NoammBridge(javaClass.classLoader) }.getOrNull() }
    private var scannedTiles: List<DungeonScannedTile>? = null
    private var scannedMap: DungeonScannedMap? = null
    private data class Bridge(val current: Method, val segments: Method, val name: Method, val cleared: Field,
                              val mapEntrance: Method, val physicalEntrance: Method, val mapSize: Method)
    private val bridge by lazy {
        runCatching {
            val manager = Class.forName("de.hysky.skyblocker.skyblock.dungeon.secrets.DungeonManager", false, javaClass.classLoader)
            val room = Class.forName("de.hysky.skyblocker.skyblock.dungeon.secrets.Room", false, javaClass.classLoader)
            Bridge(manager.getMethod("getCurrentRoom"), room.getMethod("getSegments"), room.getMethod("getName"), room.getField("clearState"),
                manager.getMethod("getMapEntrancePos"), manager.getMethod("getPhysicalEntrancePos"), manager.getMethod("getMapRoomSize"))
        }.getOrNull()
    }
    private var lastWorld: ClientLevel? = null
    private var mapId: MapId? = null
    private var nativeLayout: DungeonMapLayout? = null

    fun reset() { lastWorld = null; mapId = null; nativeLayout = null; scannedTiles = null; scannedMap = null }

    fun current(world: ClientLevel, position: Vec3, previous: LiveDungeonRoom? = null): LiveDungeonRoom? {
        if (lastWorld !== world) { reset(); lastWorld = world }
        val player = MC.instance.player ?: return null
        // Keep the received dungeon map when slot nine changes, and reject unrelated puzzle maps.
        val candidates = (listOf(player.inventory.nonEquipmentItems[8].get(DataComponents.MAP_ID), mapId) +
            player.inventory.nonEquipmentItems.mapNotNull { it.get(DataComponents.MAP_ID) } + MapId(1024)).filterNotNull().distinct()
        val received = candidates.firstNotNullOfOrNull { id ->
            MapItem.getSavedData(id, world)?.takeIf { data ->
                nativeLayout?.let { dungeonMapLayoutMatches(data.colors, it) } == true || dungeonMapEntrance(data.colors) != null
            }?.let { id to it }
        }
        if (received != null) {
            if (received.first != mapId) nativeLayout = null
            mapId = received.first
        }
        val map = received?.second
        val routingPosition = RoomPoint(position.x, position.y, position.z)
        val readGate: (GridCell) -> Int? = { cell ->
            if (!world.hasChunk(Math.floorDiv(cell.x, 16), Math.floorDiv(cell.z, 16))) null
            else world.getBlockState(BlockPos(cell.x, cell.y, cell.z)).let { state ->
                when {
                    state.`is`(Blocks.COAL_BLOCK) -> 119
                    state.`is`(Blocks.RED_TERRACOTTA) -> 18
                    state.isAir || state.`is`(Blocks.BARRIER) -> 0
                    else -> -1
                }
            }
        }
        val noammTiles = runCatching { noammBridge?.snapshot() }.getOrNull()
        if (noammTiles != scannedTiles) { scannedTiles = noammTiles; scannedMap = noammTiles?.let(::dungeonScannedMap) }
        val fromNoamm = scannedMap?.let { scan ->
            val current = dungeonGridSegment(position.x, position.z)
            val tile = scan.layout.map(current)
            val observed = dungeonScannedRoom(scan, current, readGate, routingPosition, previous?.exit) ?: return@let null
            observed to (scan.colours.mapColour(tile.x, tile.z) == 18)
        }
        if (fromNoamm?.first?.exit?.reachesBlood == true || fromNoamm?.second == true) return fromNoamm.first
        // Skyblocker's global calibration survives its current-room metadata lagging a doorway transition.
        val bridgeLayout = bridge?.let { api -> runCatching {
            val entrance = api.mapEntrance.invoke(null) as? Vector2ic ?: return@runCatching null
            val physical = api.physicalEntrance.invoke(null) as? Vector2ic ?: return@runCatching null
            val size = api.mapSize.invoke(null) as? Int ?: return@runCatching null
            if (size !in 6..24) return@runCatching null
            DungeonMapLayout(entrance.x(), entrance.y(), size, physical.x(), physical.y())
                .takeIf { map == null || dungeonMapLayoutMatches(map.colors, it) }
        }.getOrNull() }
        val layout = bridgeLayout?.also { nativeLayout = it } ?: nativeLayout?.takeIf {
            map != null && dungeonMapLayoutMatches(map.colors, it)
        } ?: map?.let { nativeMapLayout(it, position) }?.also { nativeLayout = it }
        val fromBridge = bridge?.let { api -> runCatching {
            val current = api.current.invoke(null) ?: return@runCatching null
            val segments = (api.segments.invoke(current) as? Set<*>)?.mapNotNull { (it as? Vector2ic)?.let { point -> RoomSegment(point.x(), point.y()) } }?.toSet()
                ?.takeIf { it.size in 1..4 } ?: return@runCatching null
            if (segments.none { position.x >= it.x - 0.5 && position.x < it.x + 31.5 && position.z >= it.z - 0.5 && position.z < it.z + 31.5 }) return@runCatching null
            val exit = if (map != null && layout != null) dungeonMapExit(map.colors, layout, segments, routingPosition, previous?.exit, readGate)
                else dungeonClosedExit(segments, readGate)
            val state = (api.cleared.get(current) as? Enum<*>)?.name
            LiveDungeonRoom(roomKey(segments), api.name.invoke(current) as? String ?: "Dungeon room", segments,
                state == "GREEN_CHECKED" || state == "WHITE_CHECKED", exit,
                map != null && layout != null && segments.any { layout.map(it).let { tile -> map.colors.mapColour(tile.x, tile.z) == 82 } })
        }.getOrNull() }
        val mapped = if (map != null && layout != null)
            dungeonMapRoom(map.colors, layout, dungeonGridSegment(position.x, position.z), readGate, routingPosition, previous?.exit) else null
        val nativeObserved = if (mapped != null && fromBridge?.segments == mapped.segments)
            mapped.copy(name = fromBridge.name.takeUnless { it == "Dungeon room" } ?: mapped.name, cleared = mapped.cleared || fromBridge.cleared)
            else mapped ?: fromBridge
        val observed = nativeObserved?.takeIf { it.exit?.reachesBlood == true } ?: fromNoamm?.first?.takeIf { it.exit != null } ?: nativeObserved ?: fromNoamm?.first
        return if (map != null && layout != null && observed != null)
            dungeonContinueFairyRoute(map.colors, layout, observed, previous, readGate) else observed
    }

    private fun nativeMapLayout(map: MapItemSavedData, position: Vec3): DungeonMapLayout? {
        val floor = DungeonAPI.dungeonFloor?.name?.let { if (it == "E") 0 else it.drop(1).toIntOrNull() }
        floor?.let { dungeonFloorMapLayout(map.colors, it) }?.let { return it }
        val marker = map.decorations.filter { it.type().value() == MapDecorationTypes.FRAME.value() }.singleOrNull() ?: return null
        return dungeonNativeMapLayout(map.colors, (marker.x().toInt() shr 1) + 64, (marker.y().toInt() shr 1) + 64, position.x, position.z)
    }
}

/** Keep the checked room graph while crossing into Fairy; still refresh its server clear state and gate. */
internal fun dungeonContinueFairyRoute(colours: ByteArray, layout: DungeonMapLayout,
    observed: LiveDungeonRoom, previous: LiveDungeonRoom?, readGate: ((GridCell) -> Int?)? = null): LiveDungeonRoom {
    previous ?: return observed
    if (observed.exit?.doors?.isNotEmpty() == true || previous.exit?.doors?.isNotEmpty() == true) return observed
    if (!observed.fairy || previous.exit?.door != observed.exit?.door ||
        previous.exit?.via.orEmpty().intersect(observed.segments).isEmpty()) return observed
    val source = dungeonMapRoom(colours, layout, previous.segments.first(), readGate) ?: return observed
    return if (source.exit?.door == observed.exit?.door) source.copy(name = previous.name) else observed
}

private fun dungeonMapEntrance(colours: ByteArray): Triple<Int, Int, Int>? {
    if (colours.size != 16384) return null
    val entrances = mutableListOf<Triple<Int, Int, Int>>()
    for (z in 0..121) for (x in 0..121) {
        if (colours.mapColour(x, z) != 30 || colours.mapColour(x - 1, z) == 30 || colours.mapColour(x, z - 1) == 30) continue
        var width = 0
        while (width <= 24 && colours.mapColour(x + width, z) == 30) width++
        if (width !in 6..24) continue
        // Check the border so an actual clear checkmark in the middle does not erase calibration.
        if ((0 until width).all { edge -> colours.mapColour(x, z + edge) == 30 && colours.mapColour(x + width - 1, z + edge) == 30 && colours.mapColour(x + edge, z + width - 1) == 30 })
            entrances.add(Triple(x, z, width))
    }
    return entrances.singleOrNull()
}

private fun dungeonMapLayoutMatches(colours: ByteArray, layout: DungeonMapLayout): Boolean {
    if (layout.roomSize !in 6..24 || colours.mapColour(layout.entranceX - 1, layout.entranceZ) == 30 ||
        colours.mapColour(layout.entranceX, layout.entranceZ - 1) == 30 ||
        colours.mapColour(layout.entranceX + layout.roomSize, layout.entranceZ) == 30) return false
    return (0 until layout.roomSize).all { offset ->
        colours.mapColour(layout.entranceX + offset, layout.entranceZ) == 30 &&
            colours.mapColour(layout.entranceX, layout.entranceZ + offset) == 30 &&
            colours.mapColour(layout.entranceX + layout.roomSize - 1, layout.entranceZ + offset) == 30 &&
            colours.mapColour(layout.entranceX + offset, layout.entranceZ + layout.roomSize - 1) == 30
    }
}

/** Noamm's fixed NW world cell and floor padding avoid aligning a delayed player marker with live movement. */
internal fun dungeonFloorMapLayout(colours: ByteArray, floorNumber: Int): DungeonMapLayout? {
    if (floorNumber !in 0..7) return null
    val entrance = dungeonMapEntrance(colours) ?: return null
    val stride = entrance.third + 4
    val corner = when (floorNumber) {
        0 -> RoomSegment(22, 22)
        1 -> RoomSegment(22, 11)
        2, 3 -> RoomSegment(11, 11)
        else -> RoomSegment(Math.floorMod(entrance.first, stride), Math.floorMod(entrance.second, stride))
    }
    val dx = entrance.first - corner.x
    val dz = entrance.second - corner.z
    if (dx % stride != 0 || dz % stride != 0 || dx / stride !in 0..5 || dz / stride !in 0..5) return null
    return DungeonMapLayout(entrance.first, entrance.second, entrance.third, -200 + dx / stride * 32, -200 + dz / stride * 32)
}

internal fun dungeonNativeMapLayout(colours: ByteArray, markerX: Int, markerZ: Int, playerX: Double, playerZ: Double): DungeonMapLayout? {
    if (!playerX.isFinite() || !playerZ.isFinite() || abs(playerX) > 29_999_000 || abs(playerZ) > 29_999_000 || markerX !in 0..127 || markerZ !in 0..127) return null
    val entrance = dungeonMapEntrance(colours) ?: return null
    val stride = entrance.third + 4
    val tileX = Math.floorDiv(markerX + 2 - entrance.first, stride)
    val tileZ = Math.floorDiv(markerZ + 2 - entrance.second, stride)
    val physical = dungeonGridSegment(playerX, playerZ)
    if (tileX !in -5..5 || tileZ !in -5..5) return null
    return DungeonMapLayout(entrance.first, entrance.second, entrance.third, physical.x - tileX * 32, physical.z - tileZ * 32)
}
