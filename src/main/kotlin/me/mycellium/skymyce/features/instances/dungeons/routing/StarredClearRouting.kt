package me.mycellium.skymyce.features.instances.dungeons.routing

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.config.instances.dungeons.StarredClearRoutingConfig as Settings
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.TextGizmo
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonEnterEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.EntityAddedEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.EntityRemovedEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.NameChangedEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.ComponentAttachEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.EntityInfoLineAttachEvent
import tech.thatgravyboat.skyblockapi.api.events.entity.EntityInfoLineEvent
import tech.thatgravyboat.skyblockapi.api.events.level.BlockChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderWorldEvent
import tech.thatgravyboat.skyblockapi.helpers.getStrippedAttachedLines
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.file.Files
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/** Client-local routing. World reads stay on the client thread; only immutable graph math is scheduled. */
object StarredClearRouting : SkyMyceModule() {
    private var catalog: RoomTemplateCatalog? = null
    private var catalogRevision = 0L
    private var catalogStatus = "Loading room catalog"
    private var status = "Outside a dungeon"
    private var level: ClientLevel? = null
    @Volatile private var epoch = 0L
    private var run = 0L
    @Volatile private var active = false
    @Volatile private var room: DetectedRoom? = null
    private var builder: RoomGeometryBuilder? = null
    private var geometry: RoomGeometry? = null
    private var navigation: RoomNavigation? = null
    private val updates = RoomRoutingUpdates()
    private val progress = LinkedHashMap<RoomInstanceKey, RoomTargetStates>()
    private val finishedRooms = HashSet<RoomInstanceKey>()
    private var targets: RoomTargetStates? = null
    private val mobs = LinkedHashMap<Int, LivingEntity>()
    private val spawnPoints = LinkedHashMap<Int, Vec3>()
    private var pendingWork: Any? = null
    private val workPending get() = pendingWork != null
    private var nextRecognition = 0L
    private var nextObservation = 0L
    private var nextPositionSample = 0L
    private var route: RoomRoute? = null
    private var routeCursor = -1
    private var lineStart = 0
    private var lines: List<PathLine> = emptyList()
    private var startLines: List<PathLine> = emptyList()
    private var markers: List<Marker> = emptyList()
    private var healerMarker: Marker? = null
    private var lastReason = ""
    private var lastMillis = 0.0
    private var complete = false
    private var liveRoom: LiveDungeonRoom? = null
    private var observedRoom: LiveDungeonRoom? = null
    private var liveBuilder: RoomLiveGeometry? = null
    private var liveGeometry: RoomLiveGeometrySnapshot? = null
    private var liveExit: GridCell? = null
    private var liveRouteExit: GridCell? = null
    private var nextLiveContext = 0L
    private var nextLiveScan = 0L
    private var nextLivePublish = 0L
    private var livePublishedRevision = -1L
    private var liveGeometryDue = Long.MAX_VALUE
    private var liveTargetsDirty = false
    private var liveGeometryRevision = 0L
    private var liveSeed: GridCell? = null
    private val liveBlockStates = LinkedHashMap<BlockPos, BlockState>()
    private var liveProbeOrder = emptyList<LiveTarget>()
    private var liveProbeOrderDirty = true
    private val liveExitProbe = RoomApproachProbe()
    private var liveExitHint: DungeonExit? = null
    private val liveTargets = LinkedHashMap<UUID, LiveTarget>()

    private data class LiveTarget(val id: String, var entityId: Int, var position: Vec3,
        var state: RoomTargetState, val approach: RoomApproachProbe = RoomApproachProbe())

    private data class PathLine(val from: Vec3, val to: Vec3, val healer: Boolean, val pathIndex: Int = 0)
    private data class Marker(val box: AABB, val pos: Vec3, val label: String, val state: RoomTargetState, val next: Boolean)

    override fun init() {
        reload()
        RoomTemplateRecorder.init()
        ClientTickEvents.END_CLIENT_TICK.register { tickRouting() }
        ClientChunkEvents.CHUNK_LOAD.register { world, chunk -> chunkChanged(world, chunk.pos.x, chunk.pos.z, false) }
        ClientChunkEvents.CHUNK_UNLOAD.register { world, chunk -> chunkChanged(world, chunk.pos.x, chunk.pos.z, true) }
    }

    override fun onDisable() { MC.instance.execute { RoomTemplateRecorder.cancel(); pauseRoom() } }

    fun command() = literal("route")
        .executes { it.source.sendFeedback(Component.literal(summary())); 1 }
        .then(literal("status").executes { it.source.sendFeedback(Component.literal(summary())); 1 })
        .then(literal("reload").executes {
            reload()
            it.source.sendFeedback(Component.literal("Reloading dungeon-routing-rooms.json")); 1
        })
        .then(RoomTemplateRecorder.command({ catalog }, ::reload))

    private fun summary(): String {
        val base = "Sky-Hawk routing: $catalogStatus. $status"
        val detected = room ?: return base
        if (!Config.devMode || !Settings.debug || liveRoom != null) return base
        return "$base; origin=${detected.transform.origin}, rotation=${detected.transform.quarterTurns}, " +
            "targets=${detected.template.targets.mapValues { targets!!.state(it.key) }}, healer=${detected.template.healer}, " +
            "graph=${builder?.status ?: if (navigation != null) "READY" else "UNAVAILABLE"}, " +
            "nodes=${geometry?.cells?.size ?: 0}, order=${route?.targetOrder}, reason=$lastReason, plannerMs=$lastMillis"
    }

    private fun reload() {
        val revision = ++catalogRevision
        clearRoom()
        catalog = null
        progress.clear()
        finishedRooms.clear()
        catalogStatus = "Loading room catalog"
        val path = SkyMyce.configPath.resolve("dungeon-routing-rooms.json")
        Scheduling.schedule(0.milliseconds) {
            val parsed = runCatching {
                if (!Files.exists(path)) null else Files.newInputStream(path).use {
                    val bytes = it.readNBytes(RoomTemplateCatalog.MAX_BYTES + 1)
                    RoomTemplateCatalog.parse(bytes)
                }
            }
            MC.instance.execute {
                if (revision == catalogRevision) {
                    catalog = parsed.getOrNull()
                    catalogStatus = when {
                        parsed.isFailure -> "Invalid custom room catalog; using automatic discovery"
                        catalog == null -> "Automatic discovery (no custom room templates)"
                        else -> "${catalog!!.rooms.size} room templates loaded"
                    }
                    if (parsed.isFailure) SkyMyce.logger.warn("Dungeon room catalog rejected: {}", parsed.exceptionOrNull()?.message)
                    nextRecognition = 0L
                }
            }
        }
    }

    @Subscription(DungeonEnterEvent::class, DungeonStartEvent::class, IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onContextChange() {
        MC.instance.execute { resetRun() }
    }

    private fun resetRun() {
        run++
        RoomDungeonContext.reset()
        observedRoom = null
        RoomTemplateRecorder.cancel()
        clearRoom()
        progress.clear()
        finishedRooms.clear()
        level = null
        active = false
        status = "Outside a dungeon"
    }

    private fun pauseRoom() {
        clearRoom()
        active = false
        status = "Paused while disabled or dead"
    }

    private fun clearRoom() {
        epoch++
        updates.clear()
        targets?.unloaded()
        room = null
        targets = null
        mobs.clear()
        spawnPoints.clear()
        builder = null
        geometry = null
        navigation = null
        pendingWork = null
        route = null
        hidePath()
        markers = emptyList()
        healerMarker = null
        complete = false
        liveRoom = null
        liveBuilder = null
        liveGeometry = null
        liveExit = null
        liveRouteExit = null
        liveTargets.clear()
        liveTargetsDirty = false
        liveGeometryRevision++
        liveSeed = null
        liveBlockStates.clear()
        liveProbeOrder = emptyList()
        liveProbeOrderDirty = true
        liveExitProbe.invalidate()
        liveExitHint = null
        liveGeometryDue = Long.MAX_VALUE
        nextLiveContext = 0L
        nextLiveScan = 0L
        nextLivePublish = 0L
        livePublishedRevision = -1L
        nextRecognition = 0L
        nextPositionSample = 0L
    }

    private fun tickRouting() {
        val client = MC.instance
        val player = client.player
        val world = client.level
        val inRun = DungeonAPI.started && !DungeonAPI.completed &&
            !DungeonAPI.inBoss && DungeonAPI.dungeonFloor != null && player != null && world != null
        if (!inRun) {
            if (active || level != null || progress.isNotEmpty()) resetRun()
            return
        }
        if (level !== world) { resetRun(); level = world }
        if (!isEnabled() || !Settings.enabled || player.isDeadOrDying || DungeonAPI.ownPlayer?.dead == true) {
            if (active || room != null) pauseRoom()
            return
        }
        if (DungeonAPI.bloodOpened) {
            if (active || room != null) clearRoom()
            active = false
            status = "Blood is open; routing hidden"
            return
        }
        active = true
        RuntimeProfiler.measure(RuntimeProfiler.Area.DUNGEON_ROUTE) {
            val now = System.nanoTime() / 1_000_000
            val position = player.position()
            val point = RoomPoint(position.x, position.y, position.z)
            if (now >= nextLiveContext) {
                nextLiveContext = now + 100
                observedRoom = RoomDungeonContext.current(world, position, liveRoom)
            }
            if (!dungeonRouteVisible(DungeonAPI.bloodOpened, observedRoom)) {
                if (room != null) clearRoom()
                active = false
                status = "No closed Blood / Wither door in this room or adjoining Fairy room"
                return@measure
            }
            val data = catalog
            if (liveRoom != null && data != null && data.rooms.isNotEmpty() &&
                observedRoom!!.exit!!.via.isEmpty() && now >= nextRecognition) {
                nextRecognition = now + 2000
                recognize(data, world, point)?.let { found -> clearRoom(); enterAuthored(world, found, now) }
            }
            if (liveRoom != null || data == null || data.rooms.isEmpty() || observedRoom!!.exit!!.via.isNotEmpty()) {
                tickLive(world, player.position(), now)
                return@measure
            }
            val current = room
            if (current != null && !current.template.bounds.containsPlayer(current.transform.toLocal(
                    GridCell(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())), !player.onGround())) clearRoom()
            if (room == null) {
                if (now < nextRecognition) return@measure
                nextRecognition = now + 2000
                val found = recognize(data, world, point)
                if (found == null) { tickLive(world, position, now); return@measure }
                enterAuthored(world, found, now)
            }
            val detected = room ?: return@measure
            val build = builder
            if (build != null && build.step()) {
                builder = null
                geometry = build.result
                if (geometry == null) status = "${detected.template.id}: ${build.failure ?: "incomplete geometry"}"
                else scanRoom(world, detected)
            }
            if (now >= nextObservation) {
                nextObservation = now + 250
                observeDeaths()
            }
            if (detected.key in finishedRooms && targets!!.remaining().isEmpty()) {
                complete = true
                hidePath()
                return@measure
            }
            trackPlayer(world, detected, position, now)
        }
    }

    private fun recognize(data: RoomTemplateCatalog, world: ClientLevel, point: RoomPoint): DetectedRoom? =
        data.recognize(DungeonAPI.dungeonFloor!!.name, point) { cell ->
            if (!world.hasChunk(Math.floorDiv(cell.x, 16), Math.floorDiv(cell.z, 16))) null
            else BuiltInRegistries.BLOCK.getKey(world.getBlockState(BlockPos(cell.x, cell.y, cell.z)).block).toString()
        }

    private fun enterAuthored(world: ClientLevel, found: DetectedRoom, now: Long) {
        room = found
        if (progress.size >= 128 && found.key !in progress) {
            val oldest = progress.keys.first()
            progress.remove(oldest)
            finishedRooms.remove(oldest)
        }
        targets = progress.getOrPut(found.key) { RoomTargetStates(found.template.targetCounts) }
        complete = found.key in finishedRooms
        updates.updateContext(RoomRoutingContext(epoch, run, found.key.toString()), now)
        builder = RoomGeometryBuilder(world, found)
        rebuildMarkers()
        status = "${found.template.id}: validating geometry"
    }

    private fun tickLive(world: ClientLevel, position: Vec3, now: Long) {
        val observed = observedRoom
        if (observed?.key != liveRoom?.key) {
            clearRoom()
            liveRoom = observed
            nextLiveContext = now + 100
            liveGeometryDue = now
        } else if (observed != liveRoom) {
            val changedRegion = observed?.routingSegments != liveRoom?.routingSegments || observed?.exit?.door != liveRoom?.exit?.door
            liveRoom = observed
            if (changedRegion) dirtyLiveGeometry(now)
            liveTargetsDirty = true
        }
        val context = liveRoom ?: run {
            status = "Automatic routing: waiting for verified dungeon room map data"
            return
        }
        if (context.cleared) for (target in liveTargets.values) if (target.state != RoomTargetState.CLEARED) {
            target.state = RoomTargetState.CLEARED
            liveTargetsDirty = true
            liveProbeOrderDirty = true
        }
        if (!context.containsRouteXZ(floor(position.x).toInt(), floor(position.z).toInt())) {
            clearRoom()
            status = "Automatic routing: updating room map position"
            return
        }
        if (room == null) {
            val seed = GridCell(floor(position.x).toInt(), ceil(position.y - 0.00001).toInt(), floor(position.z).toInt())
            room = liveDetected(context, setOf(seed), mapOf(seed to emptySet()), emptyMap(), seed)
            updates.updateContext(RoomRoutingContext(epoch, run, context.key), now)
            rebuildMarkers()
        }
        if (now >= nextLiveScan) {
            nextLiveScan = now + 100
            scanRoom(world, room!!)
            for (target in liveTargets.values) {
                val entity = (world.getEntity(target.entityId) as? LivingEntity)?.takeIf { target.id == "mob-${it.uuid}" }
                if (context.cleared || entity?.isDeadOrDying == true || entity?.removalReason == Entity.RemovalReason.KILLED) {
                    if (target.state != RoomTargetState.CLEARED) {
                        target.state = RoomTargetState.CLEARED; liveTargetsDirty = true; liveProbeOrderDirty = true
                    }
                } else if (entity == null || entity.isRemoved) {
                    if (target.state == RoomTargetState.ACTIVE) {
                        target.state = RoomTargetState.UNKNOWN; liveTargetsDirty = true; liveProbeOrderDirty = true
                    }
                } else if (contains(room!!, entity.position())) {
                    if (target.position.distanceToSqr(entity.position()) >= 0.25) {
                        target.position = entity.position()
                        target.approach.invalidate()
                        liveTargetsDirty = true
                        liveProbeOrderDirty = true
                    }
                } else if (target.state == RoomTargetState.ACTIVE) {
                    target.state = RoomTargetState.UNKNOWN
                    liveTargetsDirty = true
                    liveProbeOrderDirty = true
                }
            }
            if (liveBuilder != null) status = "${context.name}: scanning loaded room geometry; ${liveTargets.size} observed starred mobs"
        }
        val currentCell = GridCell(floor(position.x).toInt(), ceil(position.y - 0.00001).toInt(), floor(position.z).toInt())
        if (liveBuilder != null && liveSeed?.let { liveFeet(it).distanceToSqr(position) >= 64.0 } == true &&
            currentCell !in liveGeometry?.cells.orEmpty() && MC.instance.player?.onGround() == true) {
            dirtyLiveGeometry(now)
            liveGeometryDue = now
        }
        if (now >= liveGeometryDue) {
            liveGeometryDue = Long.MAX_VALUE
            val seed = safeLiveSeed(world, context, position)
            if (seed == null) {
                liveGeometryDue = now + 500
                status = "${context.name}: waiting for a supported starting position"
            } else {
                val minY = maxOf(world.minY, seed.y - 16)
                val maxY = minOf(world.maxY - 2, seed.y + 24)
                liveSeed = seed
                val surfaces = HashMap<GridCell, RoomPoint>()
                liveBuilder = RoomLiveGeometry(seed,
                    { context.containsRouteXZ(it.x, it.z) && it.y in minY..maxY },
                    { cell -> RoomWorldGeometry.standingPoint(world, cell)?.also { surfaces[cell] = it } != null },
                    { from, to -> RoomWorldGeometry.validConnector(world, surfaces.getValue(from).vector(), surfaces.getValue(to).vector()) },
                    { surfaces.getValue(it) })
                nextLivePublish = now
                livePublishedRevision = -1L
                status = "${context.name}: scanning loaded room geometry; ${liveTargets.size} observed starred mobs"
            }
        }
        val scan = liveBuilder
        if (scan != null) {
            val missing = liveTargets.values.filter { it.state == RoomTargetState.ACTIVE }.map {
                GridCell(floor(it.position.x).toInt(), ceil(it.position.y - 0.00001).toInt(), floor(it.position.z).toInt())
            }.filter { it !in liveGeometry?.cells.orEmpty() }
            val start = listOf(currentCell).filter { it != liveSeed && it !in liveGeometry?.cells.orEmpty() &&
                RoomWorldGeometry.standingPoint(world, it) != null }
            val destinations = missing.ifEmpty { listOfNotNull(context.exit?.approach).filter { it !in liveGeometry?.cells.orEmpty() } }
            scan.prioritize(destinations.ifEmpty { start })
            val finished = scan.step()
            if (finished) liveBuilder = null
            if (finished || now >= nextLivePublish && !workPending && scan.revision != livePublishedRevision) {
                nextLivePublish = now + 100
                livePublishedRevision = scan.revision
                liveGeometry = if (finished) scan.result else scan.snapshot()
                liveGeometryRevision++
                liveProbeOrderDirty = true
                liveTargetsDirty = true
                if (finished && scan.result == null) { geometry = null; navigation = null; hidePath() }
                status = if (finished && scan.result == null) "${context.name}: ${scan.failure ?: "geometry unavailable"}"
                    else "${context.name}: ${scan.nodeCount} verified cells${if (finished) "" else "; scanning remaining geometry"}"
            }
        }
        if (liveTargetsDirty) {
            liveTargetsDirty = false
            val snapshot = liveGeometry
            if (snapshot != null) updateLiveSnapshot(world, context, snapshot, position, now)
            rebuildMarkers()
        }
        trackPlayer(world, room ?: return, position, now)
    }

    /** Observe each client tick; a ready worker also publishes without waiting for another sample interval. */
    private fun trackPlayer(world: ClientLevel, detected: DetectedRoom, position: Vec3, now: Long) {
        val snapshot = geometry ?: return
        if (navigation == null) {
            if (!workPending) buildNavigation(snapshot, detected)
            return
        }
        if (now < nextPositionSample) return
        nextPositionSample = now + 50
        val point = RoomPoint(position.x, position.y, position.z)
        if (updates.reason == null && updates.observedPosition == point) return
        val start = RoomWorldGeometry.playerCell(world, detected, snapshot, position)
        if (start == null) {
            startLines = emptyList()
            if (MC.instance.player?.onGround() == true) {
                if (liveRoom == null) {
                    clearRoom()
                    nextRecognition = now + 100
                } else if (liveBuilder == null && liveGeometryDue == Long.MAX_VALUE) {
                    dirtyLiveGeometry(now)
                    status = "${liveRoom!!.name}: scanning new reachable room geometry"
                }
            }
            return
        }
        val beforeObservation = updates.revision
        updates.observe(start, point, now, Settings.automaticRecalculation)
        if (beforeObservation != updates.revision && updates.reason == RoomReplanReason.TELEPORTED) hidePath()
        refreshRouteStart(world, detected, position)
        val context = liveRoom
        val exit = navigation!!.healer
        val exitFeet = exit?.let { RoomWorldGeometry.feet(detected, it, snapshot) }
        if ((context == null || context.cleared || context.fairy) && targets!!.remaining().isEmpty() &&
            exitFeet != null && position.distanceToSqr(exitFeet) < 2.25 &&
            RoomWorldGeometry.validConnector(world, position, exitFeet)) {
            complete = true
            if (context == null) finishedRooms.add(detected.key)
            hidePath()
            status = "${context?.name ?: detected.template.id}: targets cleared and dungeon exit reached"
            return
        }
        complete = false
        if (!workPending && updates.due(now)) updates.take(now)?.let { plan(it, detected) }
    }

    private fun RoomPoint.vector() = Vec3(x, y, z)

    private fun liveFeet(cell: GridCell, snapshot: RoomLiveGeometrySnapshot? = liveGeometry) =
        snapshot?.feet?.get(cell)?.vector() ?: Vec3(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)

    private fun safeLiveSeed(world: ClientLevel, context: LiveDungeonRoom, position: Vec3): GridCell? {
        val x = floor(position.x).toInt()
        val y = ceil(position.y - 0.00001).toInt()
        val z = floor(position.z).toInt()
        return (x - 1..x + 1).flatMap { wx -> (y - 1..y + 1).flatMap { wy ->
            (z - 1..z + 1).map { wz -> GridCell(wx, wy, wz) }
        } }.filter { context.containsRouteXZ(it.x, it.z) && liveFeet(it).distanceToSqr(position) <= 4.0 }
            .sortedBy { liveFeet(it).distanceToSqr(position) }
            .firstOrNull { cell -> RoomWorldGeometry.standingPoint(world, cell)?.let {
                RoomWorldGeometry.validConnector(world, position, it.vector())
            } == true }
    }

    private fun liveDetected(context: LiveDungeonRoom, cells: Set<GridCell>, adjacency: Map<GridCell, Set<GridCell>>,
        selected: Map<String, GridCell>, endpoint: GridCell): DetectedRoom {
        val bounds = RoomBounds(GridCell(context.minX, maxOf(level!!.minY, cells.minOf { it.y } - 16), context.minZ),
            GridCell(context.maxX, minOf(level!!.maxY - 2, cells.maxOf { it.y } + 24), context.maxZ))
        val template = RoomTemplate("live-${context.key}", setOf(DungeonAPI.dungeonFloor!!.name), bounds,
            emptyList(), cells, adjacency, selected, endpoint, selected.mapValues { 1 })
        return DetectedRoom(template, RoomTransform(GridCell(0, 0, 0), 0))
    }

    private fun updateLiveSnapshot(world: ClientLevel, context: LiveDungeonRoom, snapshot: RoomLiveGeometrySnapshot,
        position: Vec3, now: Long) {
        val deadline = System.nanoTime() + 2_000_000
        var checks = 0
        if (liveProbeOrderDirty) {
            liveProbeOrder = liveTargets.values.filter { it.state != RoomTargetState.CLEARED }
                .sortedWith(compareBy<LiveTarget>({ it.state != RoomTargetState.ACTIVE }, { it.position.distanceToSqr(position) }))
            liveProbeOrderDirty = false
        }
        if (liveExitHint != context.exit) { liveExitHint = context.exit; liveExitProbe.invalidate() }
        val previousExit = liveExit
        val exit = context.exit
        if (exit != null) {
            checks += liveExitProbe.step(liveGeometryRevision, {
                nearbyCells(exit.approach.x, exit.approach.y, exit.approach.z, 2, 6).filter {
                    val dx = it.x - exit.approach.x
                    val dz = it.z - exit.approach.z
                    it in snapshot.cells && dx * dx + dz * dz <= 4 &&
                        (it.x - exit.door.x) * (exit.approach.x - exit.door.x) +
                        (it.z - exit.door.z) * (exit.approach.z - exit.door.z) > 0
                }.sortedBy { liveFeet(it, snapshot).distanceToSqr(Vec3(exit.approach.x + 0.5, exit.approach.y.toDouble(), exit.approach.z + 0.5)) }.take(16).toList()
            }, 64, deadline, { candidate ->
                RoomWorldGeometry.standingPoint(world, GridCell(exit.approach.x, candidate.y, exit.approach.z))?.let {
                    RoomWorldGeometry.validConnector(world, it.vector(), liveFeet(candidate, snapshot))
                } == true
            })
            liveExit = liveExitProbe.result(liveGeometryRevision)
        } else liveExit = null
        var pending = exit != null && !liveExitProbe.complete(liveGeometryRevision)
        val selected = LinkedHashMap<String, GridCell>()
        for (target in liveProbeOrder) {
            if (selected.size == RoomNavigation.MAX_TARGETS) break
            if (target.state == RoomTargetState.CLEARED) continue
            checks += target.approach.step(liveGeometryRevision, {
                val cell = BlockPos.containing(target.position)
                nearbyCells(cell.x, cell.y, cell.z, 2, 1).filter { it in snapshot.cells }
                    .map { it to liveFeet(it, snapshot).distanceToSqr(target.position) }.filter { it.second <= 9.0 }
                    .sortedBy { it.second }.take(16).map { it.first }.toList()
            }, 64 - checks, deadline, { RoomWorldGeometry.validConnector(world, target.position, liveFeet(it, snapshot)) })
            target.approach.result(liveGeometryRevision)?.let { selected[target.id] = it }
            if (!target.approach.complete(liveGeometryRevision)) { pending = true; break }
        }
        liveTargetsDirty = pending
        val endpoint = liveExit ?: snapshot.cells.first()
        val routeExit = liveExit.takeIf { selected.isNotEmpty() || context.cleared || context.fairy }
        val previous = geometry
        if (previous?.cells === snapshot.cells && previous.targets == selected && previous.healer == endpoint &&
            previousExit == liveExit && liveRouteExit == routeExit) return
        liveRouteExit = routeExit
        room = liveDetected(context, snapshot.cells, snapshot.adjacency, selected, endpoint)
        geometry = RoomGeometry(snapshot, selected, endpoint)
        targets = RoomTargetStates(selected.mapValues { 1 }).apply {
            for (target in liveProbeOrder) if (target.id in selected) observe(target.id, target.entityId)
        }
        navigation = null
        pendingWork = null
        updates.invalidate(RoomReplanReason.TARGETS_CHANGED, now)
        // Already checked edges remain safe as the flood grows; keep their guidance until replacement.
        if (previous == null || previous.targets.keys != selected.keys) hidePath()
    }

    private fun nearbyCells(x: Int, y: Int, z: Int, horizontal: Int, vertical: Int): Sequence<GridCell> = sequence {
        for (wx in x - horizontal..x + horizontal) for (wy in y - vertical..y + vertical)
            for (wz in z - horizontal..z + horizontal) yield(GridCell(wx, wy, wz))
    }

    private fun dirtyLiveGeometry(now: Long = System.nanoTime() / 1_000_000) {
        invalidate(RoomReplanReason.GEOMETRY_CHANGED, now)
        navigation = null
        pendingWork = null
        geometry = null
        liveGeometry = null
        liveBuilder = null
        liveExit = null
        liveRouteExit = null
        if (liveGeometryDue == Long.MAX_VALUE) liveGeometryDue = now + 50
    }

    private fun buildNavigation(snapshot: RoomGeometry, detected: DetectedRoom) {
        val revision = epoch
        val endpoint = if (liveRoom != null) liveRouteExit else snapshot.healer
        val work = Any()
        pendingWork = work
        Scheduling.schedule(0.milliseconds) {
            val graph = runCatching {
                RoomNavigation.create(snapshot.cells, snapshot.adjacency, snapshot.targets.filterValues { it in snapshot.cells },
                    endpoint, snapshot.standingFeet)
            }.getOrNull()
            MC.instance.execute {
                if (pendingWork !== work) return@execute
                pendingWork = null
                if (revision == epoch && room?.key == detected.key && geometry === snapshot && active && !DungeonAPI.bloodOpened) {
                    navigation = graph
                    if (graph == null) { geometry = null; status = "${detected.template.id}: blocked target or exit endpoint" }
                    else MC.instance.player?.let { player ->
                        nextPositionSample = 0L
                        trackPlayer(level ?: return@let, detected, player.position(), System.nanoTime() / 1_000_000)
                    }
                }
            }
        }
    }

    private fun plan(request: RoomReplanRequest, detected: DetectedRoom) {
        val graph = navigation ?: return
        val snapshot = geometry ?: return
        val remaining = targets!!.remaining()
        val previous = route?.targetOrder
        val debug = Config.devMode && Settings.debug
        val hasExit = graph.healer != null
        val work = Any()
        pendingWork = work
        Scheduling.schedule(0.milliseconds) {
            val begin = if (debug) System.nanoTime() else 0L
            val result = runCatching { graph.plan(request.start, remaining, previous) }.getOrNull()
            val prepared = if (result == null) emptyList() else drawPath(detected, result,
                RoomWorldGeometry.feet(detected, result.path.first(), snapshot), hasExit, snapshot)
            val elapsed = if (debug) (System.nanoTime() - begin) / 1_000_000.0 else 0.0
            MC.instance.execute {
                if (pendingWork !== work) return@execute
                pendingWork = null
                if (updates.revision != request.revision) return@execute
                val world = MC.instance.level
                val player = MC.instance.player
                val actual = player?.position()
                if (world == null || world !== level || actual == null) { clearRoom(); return@execute }
                val cursor = result?.liveStart(0, detected.template.targets,
                    { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it, snapshot)) },
                    { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(actual) })
                if (result != null && cursor == null) {
                    if (!player.onGround()) {
                        updates.invalidate(RoomReplanReason.MOVED, System.nanoTime() / 1_000_000)
                        startLines = emptyList()
                        status = "Waiting for a safe landing connection"
                        return@execute
                    }
                    updates.invalidate(RoomReplanReason.MOVED, System.nanoTime() / 1_000_000)
                    status = "Player moved before route publication; recalculating"
                    return@execute
                }
                if (!updates.complete(request, result != null, System.nanoTime() / 1_000_000)) return@execute
                route = result
                lastReason = request.reason.name
                lastMillis = elapsed
                lines = prepared
                routeCursor = cursor ?: -1
                lineStart = 0
                refreshRouteStart(world, detected, actual)
                rebuildMarkers()
                val live = liveRoom
                status = if (live != null) {
                    val total = liveTargets.values.count { it.state != RoomTargetState.CLEARED }
                    val unknown = liveTargets.values.count { it.state == RoomTargetState.UNKNOWN }
                    val ending = if (liveExit == null) "exit not yet verified or reachable" else "${live.exit?.kind ?: "dungeon"} exit"
                    if (result == null) "${live.name}: observed targets unreachable; $ending"
                    else if (total == 0 && !live.cleared && !live.fairy) "${live.name}: waiting for observed starred mobs; $ending"
                    else if (live.fairy) "Fairy passage: routing to $ending"
                    else "${live.name}: routing ${result.targetOrder.size} of $total observed targets" +
                        (if (unknown > 0) "; $unknown unavailable" else "") +
                        (if (live.cleared) "; map confirms room clear" else "; room clear not confirmed") + "; $ending"
                } else if (result == null) "${detected.template.id}: remaining targets or endpoint unreachable"
                    else "${detected.template.id}: ${result.targetOrder.size} targets, ${result.cost / graph.costScale.toDouble()} movement blocks"
                if (debug) SkyMyce.logger.info("Dungeon route: {}; origin={}, rotation={}, targets={}, healer={}, nodes={}, order={}, reason={}, plannerMs={}",
                    status, detected.transform.origin, detected.transform.quarterTurns, detected.template.targets.mapValues { targets!!.state(it.key) },
                    detected.template.healer, graph.cells.size, result?.targetOrder, lastReason, elapsed)
            }
        }
    }

    private fun invalidate(reason: RoomReplanReason, now: Long = System.nanoTime() / 1_000_000) {
        updates.invalidate(reason, now)
        hidePath()
        complete = false
    }

    private fun hidePath() {
        lines = emptyList()
        startLines = emptyList()
        routeCursor = -1
        lineStart = 0
    }

    /** Update only the leading connector; cached pathfinding and target order remain untouched. */
    private fun refreshRouteStart(world: ClientLevel, detected: DetectedRoom, actual: Vec3) {
        if (routeCursor < 0) return
        val currentRoute = route ?: return
        val snapshot = geometry ?: return
        val cursor = currentRoute.liveStart(routeCursor, detected.template.targets,
            { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it, snapshot)) },
            { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(actual) })
        if (cursor == null) {
            startLines = emptyList()
            if (MC.instance.player?.onGround() == true) updates.invalidate(RoomReplanReason.MOVED, System.nanoTime() / 1_000_000)
            return
        }
        routeCursor = cursor
        lineStart = lines.indexOfFirst { it.pathIndex > cursor }.takeIf { it >= 0 } ?: lines.size
        val start = RoomWorldGeometry.feet(detected, currentRoute.path[cursor], snapshot)
        val leading = RoomWorldGeometry.connectorWaypoints(actual, start).orEmpty().zipWithNext { a, b ->
            PathLine(a.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), b.add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
                currentRoute.targetOrder.isEmpty() && (liveRoom == null || liveExit != null))
        }
        val firstLine = lines.getOrNull(lineStart)
        val raisedStart = start.add(0.0, ROOM_ROUTE_HEIGHT, 0.0)
        // A compressed straight segment may begin before the consumed route cursor.
        startLines = if (firstLine != null && firstLine.from != raisedStart) {
            lineStart++
            leading + PathLine(raisedStart, firstLine.to, firstLine.healer, firstLine.pathIndex)
        } else leading
    }

    @Subscription(NameChangedEvent::class, ComponentAttachEvent::class, EntityInfoLineAttachEvent::class,
        priority = Subscription.LOWEST, receiveCancelled = true)
    fun onName(event: EntityInfoLineEvent) {
        if (!active || room == null || !isEnabled() || !Settings.enabled) return
        val label = event.infoLineEntity as? ArmorStand
        if (label != null) observeNamed(label, event.literalComponent)
        else (event.attachedTo ?: event.infoLineEntity).let { entity ->
            if (entity is LivingEntity && '✯' in event.literalComponent && eligibleStarMob(entity)) observeStar(entity)
        }
    }

    @Subscription
    fun onEntityAdded(event: EntityAddedEvent) {
        if (!active || !isEnabled() || !Settings.enabled) return
        val entity = event.entity as? LivingEntity ?: return
        val detected = room ?: return
        if (eligibleStarMob(entity) && contains(detected, entity.position()) && spawnPoints.size < 2048)
            spawnPoints.putIfAbsent(entity.id, entity.position())
        observeEntity(entity)
    }

    private fun scanRoom(world: ClientLevel, detected: DetectedRoom) {
        val bounds = worldBounds(detected)
        // Bounded fallback also retries labels that arrived before their body or player profile.
        for (entity in world.getEntities(null, bounds).take(2048)) {
            if (entity !is LivingEntity) continue
            if (eligibleStarMob(entity) && spawnPoints.size < 2048) spawnPoints.putIfAbsent(entity.id, entity.position())
            observeEntity(entity)
        }
    }

    private fun mobProfile(entity: Player) = MC.connection?.getPlayerInfo(entity.uuid)?.profile?.name

    private fun eligibleStarMob(entity: Entity) = entity is LivingEntity && entity !is ArmorStand &&
        (entity !is Player || dungeonStarPlayer(entity.uuid.version(), entity === MC.instance.player, entity.isInvisible, mobProfile(entity)))

    private fun observeEntity(entity: LivingEntity) {
        if (entity is ArmorStand) {
            entity.customName?.string?.let { observeNamed(entity, it) }
        } else if (eligibleStarMob(entity) && (entity is Player && dungeonMinibossProfile(mobProfile(entity)) ||
                entity.customName?.string?.contains('✯') == true || entity.getStrippedAttachedLines().any { '✯' in it })) {
            observeStar(entity)
        }
    }

    private fun observeNamed(label: ArmorStand, name: String) {
        val world = level ?: return
        val mob = dungeonStarMob(label.id, name, world::getEntity, ::eligibleStarMob) {
            world.getEntities(label, label.boundingBox.move(0.0, -1.0, 0.0))
        } as? LivingEntity ?: return
        observeStar(mob)
    }

    private fun observeStar(entity: LivingEntity) {
        val detected = room ?: return
        if (!eligibleStarMob(entity) || entity.isDeadOrDying || !contains(detected, entity.position())) return
        if (liveRoom != null) {
            val old = liveTargets[entity.uuid]
            if (old == null) {
                if (liveTargets.size >= 1536) return
                liveTargets[entity.uuid] = LiveTarget("mob-${entity.uuid}", entity.id, entity.position(),
                    if (liveRoom!!.cleared) RoomTargetState.CLEARED else RoomTargetState.ACTIVE)
                liveTargetsDirty = true
                liveProbeOrderDirty = true
            } else if (old.state != RoomTargetState.CLEARED && (old.state == RoomTargetState.UNKNOWN || old.entityId != entity.id)) {
                old.entityId = entity.id
                old.state = if (liveRoom!!.cleared) RoomTargetState.CLEARED else RoomTargetState.ACTIVE
                old.position = entity.position()
                old.approach.invalidate()
                liveTargetsDirty = true
                liveProbeOrderDirty = true
            }
            return
        }
        if (entity.id in mobs) return
        val known = targets!!.targetFor(entity.id)
        val targetId = known ?: run {
            val position = spawnPoints[entity.id] ?: entity.position()
            val nearest = detected.template.targets.entries.map { it.key to RoomWorldGeometry.feet(detected, it.value).distanceToSqr(position) }
                .sortedBy { it.second }
            val first = nearest.firstOrNull() ?: return
            // Ambiguous overlapping spawn regions remain unknown instead of clearing the wrong group.
            if (first.second > 16 || nearest.getOrNull(1)?.second?.let { it - first.second < 1.0 } == true) return
            first.first
        }
        if (mobs.size >= 1536 && entity.id !in mobs) return
        mobs[entity.id] = entity
        if (targets!!.observe(targetId, entity.id)) {
            finishedRooms.remove(detected.key)
            invalidate(RoomReplanReason.TARGETS_CHANGED)
            rebuildMarkers()
        }
    }

    @Subscription
    fun onEntityRemoved(event: EntityRemovedEvent) {
        if (!active) return
        spawnPoints.remove(event.entity.id)
        if (liveRoom != null) {
            val target = liveTargets[event.entity.uuid] ?: return
            val killed = (event.entity as? LivingEntity)?.isDeadOrDying == true || event.entity.removalReason == Entity.RemovalReason.KILLED
            if (target.state != RoomTargetState.CLEARED) {
                target.state = if (killed) RoomTargetState.CLEARED else RoomTargetState.UNKNOWN
                liveTargetsDirty = true
                liveProbeOrderDirty = true
                invalidate(RoomReplanReason.TARGETS_CHANGED)
            }
            return
        }
        val entity = mobs.remove(event.entity.id) ?: return
        val killed = entity.isDeadOrDying || event.entity.removalReason == Entity.RemovalReason.KILLED
        if (targets?.removed(entity.id, killed) == true) { invalidate(RoomReplanReason.TARGETS_CHANGED); rebuildMarkers() }
    }

    private fun observeDeaths() {
        val iterator = mobs.values.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (entity.isDeadOrDying || entity.isRemoved) {
                changed = targets!!.removed(entity.id, entity.isDeadOrDying || entity.removalReason == Entity.RemovalReason.KILLED) || changed
                iterator.remove()
                spawnPoints.remove(entity.id)
            }
        }
        if (changed) { invalidate(RoomReplanReason.TARGETS_CHANGED); rebuildMarkers() }
    }

    @Subscription
    fun onBlockChange(event: BlockChangeEvent) {
        if ((!active || room == null) && !RoomTemplateRecorder.isRecording) return
        val pos = event.pos.immutable()
        val observedEpoch = epoch
        // Block packets may publish on the network thread. Invalidate only after dispatch to the client.
        MC.instance.execute {
            RoomTemplateRecorder.blockChanged(pos)
            if (observedEpoch != epoch || !active) return@execute
            val detected = room ?: return@execute
            if (RoomWorldGeometry.geometryBounds(detected).contains(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())) {
                if (liveRoom != null) {
                    val block = GridCell(pos.x, pos.y, pos.z)
                    // Untouched areas will be read in their current state; unrelated packets must not restart the flood.
                    if (liveBuilder?.observesBlock(block) != true &&
                        liveGeometry?.let { nearbyRoomGeometry(it.cells, block) } != true) return@execute
                    val previous = liveBlockStates.put(pos, event.state)
                    if (liveBlockStates.size > 4096) liveBlockStates.remove(liveBlockStates.keys.first())
                    val world = level ?: return@execute
                    if (previous != null && runCatching { previous.fluidState == event.state.fluidState &&
                        Shapes.equal(previous.getCollisionShape(world, pos), event.state.getCollisionShape(world, pos)) }.getOrDefault(false)) return@execute
                    dirtyLiveGeometry()
                }
                else if (detected.template.landmarks.any { detected.transform.toWorld(it.cell) == GridCell(pos.x, pos.y, pos.z) }) clearRoom()
                else rebuildGeometry()
            }
        }
    }

    private fun chunkChanged(world: ClientLevel, x: Int, z: Int, unloaded: Boolean) {
        RoomTemplateRecorder.chunkChanged(world, x, z)
        if (!active || world !== level) return
        val detected = room ?: return
        val box = RoomWorldGeometry.geometryBounds(detected)
        if ((x + 1) * 16 <= box.minX || x * 16 > box.maxX || (z + 1) * 16 <= box.minZ || z * 16 > box.maxZ) return
        if (liveRoom != null) {
            if (unloaded) for (target in liveTargets.values) if (target.state == RoomTargetState.ACTIVE &&
                Math.floorDiv(floor(target.position.x).toInt(), 16) == x && Math.floorDiv(floor(target.position.z).toInt(), 16) == z) {
                target.state = RoomTargetState.UNKNOWN
                liveTargetsDirty = true
                liveProbeOrderDirty = true
            }
            dirtyLiveGeometry()
            rebuildMarkers()
            return
        }
        if (unloaded) { observeDeaths(); targets?.unloaded(); mobs.clear(); spawnPoints.clear(); rebuildMarkers() }
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val detected = room ?: return
        val world = level ?: return
        epoch++
        pendingWork = null
        updates.updateContext(RoomRoutingContext(epoch, run, detected.key.toString()), System.nanoTime() / 1_000_000)
        invalidate(RoomReplanReason.GEOMETRY_CHANGED)
        navigation = null
        geometry = null
        builder = RoomGeometryBuilder(world, detected)
    }

    private fun contains(detected: DetectedRoom, point: Vec3): Boolean {
        val cell = GridCell(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())
        val live = liveRoom
        return if (live != null) live.containsXZ(cell.x, cell.z) && cell.y in detected.template.bounds.min.y..detected.template.bounds.max.y
            else detected.template.bounds.contains(detected.transform.toLocal(cell))
    }

    private fun worldBounds(detected: DetectedRoom): AABB {
        val a = detected.transform.toWorld(detected.template.bounds.min)
        val b = detected.transform.toWorld(detected.template.bounds.max)
        return AABB(minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
            maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 1.0, maxOf(a.z, b.z) + 1.0)
    }

    private fun drawPath(detected: DetectedRoom, result: RoomRoute, actual: Vec3, hasExit: Boolean,
        snapshot: RoomGeometry): List<PathLine> {
        var healerFrom = 0
        for (id in result.targetOrder) {
            val index = result.path.subList(healerFrom, result.path.size).indexOf(detected.template.targets.getValue(id))
            if (index < 0) return emptyList()
            healerFrom += index
        }
        val output = ArrayList<PathLine>()
        var from = actual
        for ((index, cell) in result.path.withIndex()) {
            val to = RoomWorldGeometry.feet(detected, cell, snapshot)
            val points = RoomWorldGeometry.connectorWaypoints(from, to) ?: return emptyList()
            for (i in 1 until points.size) {
                val a = points[i - 1].add(0.0, ROOM_ROUTE_HEIGHT, 0.0)
                val b = points[i].add(0.0, ROOM_ROUTE_HEIGHT, 0.0)
                if (a.distanceToSqr(b) < 0.000001) continue
                val healer = (index > healerFrom || result.targetOrder.isEmpty()) && hasExit
                val prior = output.lastOrNull()
                if (prior != null && prior.healer == healer && prior.to == a &&
                    prior.to.subtract(prior.from).cross(b.subtract(a)).lengthSqr() < 0.000001 &&
                    prior.to.subtract(prior.from).dot(b.subtract(a)) > 0) {
                    output[output.lastIndex] = PathLine(prior.from, b, healer, index)
                } else output.add(PathLine(a, b, healer, index))
            }
            from = to
        }
        return output
    }

    private fun rebuildMarkers() {
        val detected = room ?: return
        val order = route?.targetOrder.orEmpty()
        if (liveRoom != null) {
            markers = liveTargets.values.map { target ->
                val feet = target.position
                val number = order.indexOf(target.id).takeIf { it >= 0 }?.plus(1)
                val label = when (target.state) {
                    RoomTargetState.CLEARED -> "Cleared"
                    RoomTargetState.UNKNOWN -> "${number?.let { "$it. " }.orEmpty()}Starred · last seen"
                    RoomTargetState.ACTIVE -> "${number?.let { "$it. " }.orEmpty()}Starred"
                }
                Marker(AABB(feet.x - 0.35, feet.y, feet.z - 0.35, feet.x + 0.35, feet.y + 1.0, feet.z + 0.35),
                    feet, label, target.state, number == 1)
            }
            healerMarker = liveExit?.let { endpoint ->
                val feet = liveFeet(endpoint)
                Marker(AABB(feet.x - 0.5, feet.y, feet.z - 0.5, feet.x + 0.5, feet.y + 1.5, feet.z + 0.5),
                    feet, liveRoom!!.exit?.kind ?: "Dungeon exit", RoomTargetState.UNKNOWN, false)
            }
            return
        }
        markers = detected.template.targets.map { (id, cell) ->
            val feet = RoomWorldGeometry.feet(detected, cell)
            val state = targets!!.state(id)
            val number = order.indexOf(id).takeIf { it >= 0 }?.plus(1)
            Marker(AABB(feet.x - 0.35, feet.y, feet.z - 0.35, feet.x + 0.35, feet.y + 1.0, feet.z + 0.35),
                feet, "${number?.let { "$it. " }.orEmpty()}$id", state, number == 1)
        }
        val feet = RoomWorldGeometry.feet(detected, detected.template.healer)
        healerMarker = Marker(AABB(feet.x - 0.5, feet.y, feet.z - 0.5, feet.x + 0.5, feet.y + 1.5, feet.z + 0.5),
            feet, "Blood / Wither exit", RoomTargetState.UNKNOWN, false)
    }

    @Subscription
    fun render(event: RenderWorldEvent.AfterTranslucent) {
        if (!active || complete || !isEnabled() || !Settings.enabled || room == null ||
            !dungeonRouteVisible(DungeonAPI.bloodOpened, observedRoom)) return
        val player = MC.instance.player ?: return
        if (observedRoom?.containsRouteXZ(floor(player.x).toInt(), floor(player.z).toInt()) != true) return
        // Fill the first checked path between client ticks, without an unbounded render-thread scan.
        if (lineStart >= lines.size && startLines.isEmpty()) liveBuilder?.let { scan ->
            RuntimeProfiler.measure(RuntimeProfiler.Area.DUNGEON_ROUTE) { scan.step(maxNanos = 500_000) }
        }
        val thickness = Settings.thickness.toFloat()
        val accent = HudTheme.alpha(HudTheme.ACCENT, Settings.opacity)
        val healer = HudTheme.alpha(HudTheme.GREEN, Settings.opacity)
        MC.instance.levelRenderer.collectPerFrameGizmos().use {
            if (Settings.showRoute) {
                var count = 0
                var index = 0
                while (index < startLines.size + lines.size - lineStart) {
                    var line = if (index < startLines.size) startLines[index] else lines[lineStart + index - startLines.size]
                    if (index == 0 && startLines.isNotEmpty()) {
                        val delta = player.getPosition(event.partialTicks).subtract(player.position())
                        line = line.copy(from = line.from.add(delta))
                    }
                    index++
                    if (line.healer && !Settings.showHealer) continue
                    if (line.from.distanceToSqr(event.cameraPosition) > 128 * 128 && line.to.distanceToSqr(event.cameraPosition) > 128 * 128) continue
                    // ponytail: cap nearby segments at 2048; add frustum indexing if authored rooms exceed it.
                    if (count++ >= 2048) break
                    val color = if (line.healer) healer else accent
                    Gizmos.line(line.from, line.to, color, thickness).setAlwaysOnTop()
                    if (count <= 24 && line.to.distanceToSqr(line.from) >= 4.0) {
                        val direction = line.to.subtract(line.from).normalize()
                        Gizmos.arrow(line.from.add(direction.scale(0.3)), line.from.add(direction.scale(1.2)), color, thickness)
                            .setAlwaysOnTop()
                    }
                }
            }
            var shown = 0
            if (Settings.showTargets) for (marker in markers) {
                if (marker.state == RoomTargetState.CLEARED && !Settings.showCleared) continue
                if (marker.pos.distanceToSqr(event.cameraPosition) > 96 * 96) continue
                if (shown++ >= 128) break
                val color = when {
                    marker.state == RoomTargetState.CLEARED -> HudTheme.alpha(HudTheme.MUTED, 30)
                    marker.next -> accent
                    else -> HudTheme.alpha(HudTheme.ACCENT, Settings.opacity / 2)
                }
                renderWaypoint(marker, color, thickness, event.cameraPosition, Settings.showNumbers)
            }
            if (Settings.showHealer) healerMarker?.let {
                renderWaypoint(it, healer, thickness, event.cameraPosition, true)
            }
        }
    }

    private fun renderWaypoint(marker: Marker, color: Int, thickness: Float, camera: Vec3, label: Boolean) {
        val cleared = marker.state == RoomTargetState.CLEARED
        Gizmos.cuboid(marker.box.move(0.0, ROOM_ROUTE_HEIGHT, 0.0), GizmoStyle.strokeAndFill(color, if (marker.next) thickness + 1 else thickness,
            HudTheme.alpha(color, if (cleared) 5 else if (marker.next) 25 else 10))).setAlwaysOnTop()
        val distance = sqrt(marker.pos.distanceToSqr(camera))
        if (label && !cleared) Gizmos.billboardText("${marker.label} · ${distance.toInt()}m", marker.pos.add(0.0, 1.6 + ROOM_ROUTE_HEIGHT, 0.0),
            TextGizmo.Style.forColorAndCentered(color).withScale(roomWaypointTextScale(distance)))
            .setAlwaysOnTop()
    }
}

internal const val ROOM_ROUTE_HEIGHT = 1.2

// Gizmo text scales font pixels by scale/16; keep labels readable at distance without giant nearby text.
internal fun roomWaypointTextScale(distance: Double) = (distance.coerceIn(1.0, 80.0) * 0.035).toFloat()

/** Cached native approach probes resume after their shared tick budget instead of retrying rejected prefixes. */
internal class RoomApproachProbe {
    private var revision = Long.MIN_VALUE
    private var candidates = emptyList<GridCell>()
    private var cursor = 0
    private var found: GridCell? = null

    fun invalidate() { revision = Long.MIN_VALUE }
    fun result(revision: Long): GridCell? = if (this.revision == revision) found else null
    fun complete(revision: Long) = this.revision == revision && (found != null || cursor == candidates.size)

    fun step(revision: Long, candidates: () -> List<GridCell>, maxChecks: Int, deadlineNanos: Long,
        canConnect: (GridCell) -> Boolean, clock: () -> Long = System::nanoTime): Int {
        val limit = maxChecks.coerceIn(0, 64)
        if (this.revision != revision) {
            if (limit == 0 || clock() >= deadlineNanos) return 0
            this.revision = revision
            this.candidates = candidates()
            cursor = 0
            found = null
        }
        var checks = 0
        while (found == null && cursor < this.candidates.size && checks < limit && clock() < deadlineNanos) {
            val cell = this.candidates[cursor++]
            checks++
            if (canConnect(cell)) found = cell
        }
        return checks
    }
}
