package me.mycellium.skymyce.features.instances.dungeons.routing

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
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
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

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

    private fun feetCell(): GridCell {
        val point = requireNotNull(MC.instance.player).position()
        // Slabs/stairs use the next integer candidate height, checked against live collision/support.
        return GridCell(floor(point.x).toInt(), ceil(point.y - 0.00001).toInt(), floor(point.z).toInt())
    }

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
