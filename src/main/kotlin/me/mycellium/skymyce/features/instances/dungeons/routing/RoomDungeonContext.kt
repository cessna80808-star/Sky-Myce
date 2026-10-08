package me.mycellium.skymyce.features.instances.dungeons.routing

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
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.floor

data class RoomSegment(val x: Int, val z: Int)
data class DungeonExit(val door: GridCell, val approach: GridCell, val kind: String,
    val via: Set<RoomSegment> = emptySet())
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
}

internal fun dungeonRouteVisible(bloodOpened: Boolean, room: LiveDungeonRoom?) =
    !bloodOpened && room?.exit?.kind == "Wither door"

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
private val ROOM_MAP_COLOURS = setOf(18, 30, 62, 63, 66, 74, 82, 85)
private fun ByteArray.mapColour(x: Int, z: Int) = if (size == 16384 && x in 0..127 && z in 0..127) this[x + z * 128].toInt() and 255 else -1
private fun roomKey(segments: Set<RoomSegment>) = "live:" + segments.sortedWith(compareBy({ it.x }, { it.z })).joinToString(";") { "${it.x},${it.z}" }

internal fun dungeonMapRoom(colours: ByteArray, layout: DungeonMapLayout, current: RoomSegment): LiveDungeonRoom? {
    if (colours.size != 16384 || layout.roomSize !in 6..24) return null
    val tile = layout.map(current)
    val colour = colours.mapColour(tile.x, tile.z)
    if (colour !in ROOM_MAP_COLOURS || colour == 85) return null
    val tiles = linkedSetOf(tile)
    val queue = ArrayDeque<RoomSegment>().apply { add(tile) }
    // A joined room extends across the tile's corner; an ordinary door is only at its middle.
    if (colour == 63) while (queue.isNotEmpty() && tiles.size <= 4) {
        val from = queue.removeFirst()
        for (direction in MAP_DIRECTIONS) {
            val next = RoomSegment(from.x + direction.x * layout.stride, from.z + direction.z * layout.stride)
            val seamX = from.x + if (direction.x < 0) -1 else if (direction.x > 0) layout.roomSize else 0
            val seamZ = from.z + if (direction.z < 0) -1 else if (direction.z > 0) layout.roomSize else 0
            if (colours.mapColour(next.x, next.z) == colour && colours.mapColour(seamX, seamZ) == colour && tiles.add(next)) queue.add(next)
        }
    }
    if (tiles.size > 4) return null
    val segments = tiles.mapTo(linkedSetOf(), layout::world)
    val checked = tiles.any { colours.mapColour(it.x + layout.roomSize / 2, it.z + layout.roomSize / 2) in setOf(30, 34) }
    val name = when (colour) { 18 -> "Blood room"; 30 -> "Entrance"; 62 -> "Trap room"; 66 -> "Puzzle room"; 74 -> "Miniboss room"; 82 -> "Fairy room"; else -> "Dungeon room" }
    return LiveDungeonRoom(roomKey(segments), name, segments, checked && colour != 30, dungeonMapExit(colours, layout, segments), colour == 82)
}

/** Select a closed Wither gate, continuing through an adjoining Fairy room's open passage. */
internal fun dungeonMapExit(colours: ByteArray, layout: DungeonMapLayout, segments: Set<RoomSegment>): DungeonExit? {
    if (colours.size != 16384 || layout.roomSize !in 6..24 || segments.isEmpty()) return null
    val tiles = linkedSetOf<RoomSegment>()
    for (x in Math.floorMod(layout.entranceX, layout.stride)..127 step layout.stride)
        for (z in Math.floorMod(layout.entranceZ, layout.stride)..127 step layout.stride)
            if (colours.mapColour(x, z) in ROOM_MAP_COLOURS) tiles.add(RoomSegment(x, z))
    if (tiles.size > 64) return null
    fun neighbour(from: RoomSegment, direction: RoomSegment) = RoomSegment(from.x + direction.x * layout.stride, from.z + direction.z * layout.stride)
    fun doorColour(from: RoomSegment, direction: RoomSegment) = colours.mapColour(
        from.x + when (direction.x) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 },
        from.z + when (direction.z) { -1 -> -2; 1 -> layout.roomSize + 2; else -> layout.roomSize / 2 },
    )
    fun connected(from: RoomSegment, direction: RoomSegment) = neighbour(from, direction) in tiles && doorColour(from, direction) in setOf(18, 30, 63, 119)
    val distance = linkedMapOf<RoomSegment, Int>()
    val queue = ArrayDeque<RoomSegment>()
    for (tile in tiles) if (colours.mapColour(tile.x, tile.z) == 18) { distance[tile] = 0; queue.add(tile) }
    while (queue.isNotEmpty()) {
        val from = queue.removeFirst()
        for (direction in MAP_DIRECTIONS) {
            val next = neighbour(from, direction)
            if (connected(from, direction) && next !in distance) { distance[next] = distance.getValue(from) + 1; queue.add(next) }
        }
    }
    val currentTiles = segments.mapTo(hashSetOf(), layout::map)
    if (currentTiles.any { colours.mapColour(it.x, it.z) == 18 }) return null
    val fairyTiles = currentTiles.flatMap { tile -> MAP_DIRECTIONS.mapNotNull { direction ->
        neighbour(tile, direction).takeIf { it !in currentTiles && colours.mapColour(it.x, it.z) == 82 &&
            doorColour(tile, direction) in setOf(30, 63) }
    } }.toSet()
    val routingTiles = currentTiles + fairyTiles
    data class Candidate(val tile: RoomSegment, val direction: RoomSegment, val distance: Int?)
    val exits = routingTiles.flatMap { tile -> MAP_DIRECTIONS.mapNotNull { direction ->
        val next = neighbour(tile, direction)
        if (next in routingTiles || doorColour(tile, direction) != 119) null
        else Candidate(tile, direction, distance[next])
    } }
    val selected = exits.filter { it.distance != null }.minByOrNull { it.distance!! } ?: exits.singleOrNull()
    selected ?: return null
    val segment = layout.world(selected.tile)
    val door = GridCell(segment.x + 15 + selected.direction.x * 16, 69, segment.z + 15 + selected.direction.z * 16)
    val approach = GridCell(door.x - selected.direction.x * 3, door.y, door.z - selected.direction.z * 3)
    return DungeonExit(door, approach, "Wither door",
        if (selected.tile in fairyTiles) setOf(segment) else emptySet())
}

/** Without map direction, only a unique actual closed Wither gate is an established destination. */
internal fun dungeonClosedExit(segments: Set<RoomSegment>, read: (GridCell) -> Int?): DungeonExit? {
    val exits = mutableListOf<DungeonExit>()
    for (segment in segments) for (direction in MAP_DIRECTIONS) {
        if (RoomSegment(segment.x + direction.x * 32, segment.z + direction.z * 32) in segments) continue
        val door = GridCell(segment.x + 15 + direction.x * 16, 69, segment.z + 15 + direction.z * 16)
        val colour = read(door)?.takeIf { it == 119 } ?: continue
        // Hypixel gates occupy three blocks across and four high at the room-grid boundary.
        if ((0..3).any { dy -> (-1..1).any { across -> read(GridCell(door.x + direction.z * across, door.y + dy, door.z + direction.x * across)) != colour } }) continue
        exits.add(DungeonExit(door, GridCell(door.x - direction.x * 3, door.y, door.z - direction.z * 3),
            "Wither door"))
    }
    return exits.singleOrNull()
}

/** Optional public Skyblocker metadata; native map fallback needs no other mod or secret-waypoint setting. */
object RoomDungeonContext {
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

    fun reset() { lastWorld = null; mapId = null; nativeLayout = null }

    fun current(world: ClientLevel, position: Vec3, previous: LiveDungeonRoom? = null): LiveDungeonRoom? {
        if (lastWorld !== world) { lastWorld = world; mapId = null; nativeLayout = null }
        val player = MC.instance.player ?: return null
        player.inventory.nonEquipmentItems[8].get(DataComponents.MAP_ID)?.let { mapId = it }
        // Hypixel's dungeon map fallback ID is also used by Skyblocker's public DungeonMap helper.
        val map = MapItem.getSavedData(mapId ?: MapId(1024), world)
        var bridgeLayout: DungeonMapLayout? = null
        val fromBridge = bridge?.let { api -> runCatching {
            val current = api.current.invoke(null) ?: return@runCatching null
            val segments = (api.segments.invoke(current) as? Set<*>)?.mapNotNull { (it as? Vector2ic)?.let { point -> RoomSegment(point.x(), point.y()) } }?.toSet()
                ?.takeIf { it.size in 1..4 } ?: return@runCatching null
            if (segments.none { position.x >= it.x - 0.5 && position.x < it.x + 31.5 && position.z >= it.z - 0.5 && position.z < it.z + 31.5 }) return@runCatching null
            val entrance = api.mapEntrance.invoke(null) as? Vector2ic
            val physical = api.physicalEntrance.invoke(null) as? Vector2ic
            val size = api.mapSize.invoke(null) as? Int
            val layout = if (entrance != null && physical != null && size != null && size in 6..24)
                DungeonMapLayout(entrance.x(), entrance.y(), size, physical.x(), physical.y()) else null
            bridgeLayout = layout
            val exit = if (map != null && layout != null) dungeonMapExit(map.colors, layout, segments) else dungeonClosedExit(segments) { cell ->
                if (!world.hasChunk(Math.floorDiv(cell.x, 16), Math.floorDiv(cell.z, 16))) null
                else when (world.getBlockState(BlockPos(cell.x, cell.y, cell.z)).block) {
                    Blocks.COAL_BLOCK -> 119
                    Blocks.RED_TERRACOTTA -> 18
                    else -> 0
                }
            }
            val state = (api.cleared.get(current) as? Enum<*>)?.name
            LiveDungeonRoom(roomKey(segments), api.name.invoke(current) as? String ?: "Dungeon room", segments,
                state == "GREEN_CHECKED" || state == "WHITE_CHECKED", exit,
                map != null && layout != null && segments.any { layout.map(it).let { tile -> map.colors.mapColour(tile.x, tile.z) == 82 } })
        }.getOrNull() }
        val layout = bridgeLayout?.also { nativeLayout = it } ?: nativeLayout ?: map?.let { nativeMapLayout(it, position) }
            ?.also { nativeLayout = it }
        val observed = fromBridge ?: if (map != null && layout != null)
            dungeonMapRoom(map.colors, layout, dungeonGridSegment(position.x, position.z)) else null
        return if (map != null && layout != null && observed != null)
            dungeonContinueFairyRoute(map.colors, layout, observed, previous) else observed
    }

    private fun nativeMapLayout(map: MapItemSavedData, position: Vec3): DungeonMapLayout? {
        val marker = map.decorations.filter { it.type().value() == MapDecorationTypes.FRAME.value() }.singleOrNull() ?: return null
        return dungeonNativeMapLayout(map.colors, (marker.x().toInt() shr 1) + 64, (marker.y().toInt() shr 1) + 64, position.x, position.z)
    }
}

/** Keep the checked room graph while crossing into Fairy; still refresh its server clear state and gate. */
internal fun dungeonContinueFairyRoute(colours: ByteArray, layout: DungeonMapLayout,
    observed: LiveDungeonRoom, previous: LiveDungeonRoom?): LiveDungeonRoom {
    previous ?: return observed
    if (!observed.fairy || previous.exit?.door != observed.exit?.door ||
        previous.exit?.via.orEmpty().intersect(observed.segments).isEmpty()) return observed
    val source = dungeonMapRoom(colours, layout, previous.segments.first()) ?: return observed
    return if (source.exit?.door == observed.exit?.door) source.copy(name = previous.name) else observed
}

internal fun dungeonNativeMapLayout(colours: ByteArray, markerX: Int, markerZ: Int, playerX: Double, playerZ: Double): DungeonMapLayout? {
    if (colours.size != 16384 || !playerX.isFinite() || !playerZ.isFinite() || abs(playerX) > 29_999_000 || abs(playerZ) > 29_999_000 || markerX !in 0..127 || markerZ !in 0..127) return null
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
    val entrance = entrances.singleOrNull() ?: return null
    val stride = entrance.third + 4
    val tileX = Math.floorDiv(markerX + 2 - entrance.first, stride)
    val tileZ = Math.floorDiv(markerZ + 2 - entrance.second, stride)
    val physical = dungeonGridSegment(playerX, playerZ)
    if (tileX !in -5..5 || tileZ !in -5..5) return null
    return DungeonMapLayout(entrance.first, entrance.second, entrance.third, physical.x - tileX * 32, physical.z - tileZ * 32)
}
