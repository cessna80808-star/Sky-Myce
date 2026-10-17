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
import net.minecraft.tags.BlockTags
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
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonEnterEvent
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
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
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
    private var retainedGeometry: RoomGeometry? = null
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
    private var lastRouteThroughMobs = true
    private var lastRouteThroughMinibosses = true
    private val authoredMinibosses = HashSet<String>()
    private var route: RoomRoute? = null
    private var routeCursor = -1
    private var lineStart = 0
    private var lines: List<PathLine> = emptyList()
    private var startLines: List<PathLine> = emptyList()
    private var displayLines: List<PathLine> = emptyList()
    private var displayStartLines: List<PathLine> = emptyList()
    private var displayChunkLines: List<PathLine> = emptyList()
    private var displayLineStart = 0
    private var handoffLines: List<PathLine> = emptyList()
    private var handoffTail = emptyList<RoomPoint>()
    private var handoffTailLines = emptyList<PathLine>()
    private var handoffRevision = -1L
    private var displayRoute: RoomRoute? = null
    private var displayCursor = -1
    private var continuationLines: List<PathLine> = emptyList()
    private var continuationRevision = -1L
    private var continuationStart: GridCell? = null
    private var continuationHints: List<Pair<Vec3, String>> = emptyList()
    private var nextHandoff = 0L
    private val continuation = DungeonRouteContinuation(
        { cell -> level?.let { RoomWorldGeometry.standingPoint(it, cell) } },
        { a, b -> level?.let { RoomWorldGeometry.validRouteConnector(it, a.vector(), b.vector()) } == true },
        { points ->
            val world = level
            val spans = points.zipWithNext().map { (a, b) -> RoomWorldGeometry.connectorWaypoints(a.vector(), b.vector()) }
            val elbows = if (points.size < 2) points.map { it.vector() }
                else if (spans.any { it == null }) emptyList() else spans.flatMap { it!! }.distinctConsecutive()
            if (world == null) points else RoomWorldGeometry.simplifyPath(world, elbows).map { RoomPoint(it.x, it.y, it.z) }
        },
        { work, publish -> Scheduling.schedule(0.milliseconds) {
            val result = runCatching(work).getOrNull()
            MC.instance.execute { publish(result) }
        } },
        sightConnects = { a, b -> level?.let { RoomWorldGeometry.clearSightLine(it, a.vector(), b.vector()) } == true },
    )
    private var markers: List<Marker> = emptyList()
    private val bloodReminder = BloodDoorReminder()
    private var bloodRoutingStopped = false
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
    private val pendingBlocks = LinkedHashMap<BlockPos, BlockState>()
    private val deferredBlocks = HashSet<BlockPos>()
    private var blockOverflow = false
    private var liveProbeOrder = emptyList<LiveTarget>()
    private var liveProbeOrderDirty = true
    private val liveExitProbe = RoomApproachProbe()
    private val sightExitProbe = RoomApproachProbe()
    private var sightExitHint: DungeonExit? = null
    private var sightGoal: GridCell? = null
    private var sightTarget: String? = null
    private var sightExitFeet: RoomPoint? = null
    private var sightProbePosition: Vec3? = null
    private var sightProbeRevision = -1L
    private var liveExitHint: DungeonExit? = null
    private var unsupportedAuthoredExit: Pair<RoomInstanceKey, GridCell>? = null
    private val liveTargets = LinkedHashMap<UUID, LiveTarget>()

    private data class LiveTarget(val id: String, var entityId: Int, var position: Vec3,
        var state: RoomTargetState, var miniboss: Boolean, val approach: RoomApproachProbe = RoomApproachProbe())

    private data class PathLine(val from: Vec3, val to: Vec3, val healer: Boolean, val pathIndex: Int = 0, val checked: Boolean = true)
    private data class Marker(val box: AABB, val pos: Vec3, val label: String, val state: RoomTargetState, val next: Boolean)

    override fun init() {
        reload()
        RoomTemplateRecorder.init()
        ClientTickEvents.END_CLIENT_TICK.register { guardedTick() }
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
        if (!Config.devMode || !Settings.debug) return base
        return "$base; origin=${detected.transform.origin}, rotation=${detected.transform.quarterTurns}, " +
            "targets=${detected.template.targets.mapValues { targets?.state(it.key) }}, healer=${navigation?.healer}, " +
            "graph=${liveBuilder?.status ?: builder?.status ?: if (navigation != null) "READY" else "UNAVAILABLE"}, " +
            "nodes=${geometry?.cells?.size ?: 0}, throughMobs=${Settings.routeThroughMobs}, throughMinibosses=${Settings.routeThroughStarredMinibosses}, " +
            "order=${route?.targetOrder}, reason=$lastReason, plannerMs=$lastMillis"
    }

    private fun reload() {
        val revision = ++catalogRevision
        clearRoom()
        catalog = null
        progress.clear()
        finishedRooms.clear()
        unsupportedAuthoredExit = null
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

    @Subscription(DungeonEnterEvent::class, IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onContextChange() {
        MC.instance.execute { resetRun() }
    }

    private fun resetRun() {
        run++
        bloodReminder.clear()
        bloodRoutingStopped = false
        pendingBlocks.clear()
        deferredBlocks.clear()
        blockOverflow = false
        continuation.clear()
        continuationLines = emptyList()
        RoomDungeonContext.reset()
        observedRoom = null
        RoomTemplateRecorder.cancel()
        clearRoom()
        progress.clear()
        finishedRooms.clear()
        unsupportedAuthoredExit = null
        level = null
        active = false
        status = "Outside a dungeon"
    }

    private fun pauseRoom() {
        continuation.clear()
        continuationLines = emptyList()
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
        authoredMinibosses.clear()
        spawnPoints.clear()
        builder = null
        geometry = null
        navigation = null
        pendingWork = null
        route = null
        hidePath()
        markers = emptyList()
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
        sightExitProbe.invalidate()
        sightExitHint = null
        sightGoal = null
        sightTarget = null
        sightExitFeet = null
        sightProbePosition = null
        sightProbeRevision = -1L
        liveExitHint = null
        liveGeometryDue = Long.MAX_VALUE
        nextLiveContext = 0L
        nextLiveScan = 0L
        nextLivePublish = 0L
        livePublishedRevision = -1L
        nextRecognition = 0L
        nextPositionSample = 0L
        nextHandoff = 0L
    }

    private var lastFailureLog = 0L

    /**
     * Routing is an optional overlay: an unexpected failure clears the route and is logged at most once a minute,
     * instead of crashing the game (2.5.11 crashed here when a starred mob vanished mid-refresh).
     */
    private fun guardedTick() {
        try { tickRouting() } catch (failure: RuntimeException) {
            val now = System.currentTimeMillis()
            if (now - lastFailureLog >= 60_000) {
                lastFailureLog = now
                SkyMyce.logger.error("Dungeon routing failed; clearing the route", failure)
            }
            runCatching { resetRun() }
        }
    }

    private fun tickRouting() {
        val client = MC.instance
        val player = client.player
        val world = client.level
        val inRun = dungeonRoutingActive(LocationAPI.island == SkyBlockIsland.THE_CATACOMBS,
            DungeonAPI.dungeonFloor != null, DungeonAPI.completed, DungeonAPI.inBoss) && player != null && world != null
        if (!inRun) {
            if (active || level != null || progress.isNotEmpty()) resetRun()
            return
        }
        if (level !== world) { resetRun(); level = world }
        val now = System.nanoTime() / 1_000_000
        bloodReminder.observe(observedRoom?.exit)
        if (!isEnabled() || !Settings.enabled || player.isDeadOrDying || DungeonAPI.ownPlayer?.dead == true) {
            if (active || room != null) pauseRoom()
            return
        }
        active = true
        RuntimeProfiler.measure(RuntimeProfiler.Area.DUNGEON_ROUTE) {
            if (DungeonAPI.bloodOpened) {
                if (!bloodRoutingStopped) {
                    clearRoom()
                    continuation.clear()
                    continuationLines = emptyList()
                    continuationHints = emptyList()
                    bloodRoutingStopped = true
                }
                active = false
                status = "Blood opened; routing complete"
                return@measure
            }
            if (!processBlockChanges(world, now)) return@measure
            if (lastRouteThroughMobs != Settings.routeThroughMobs || lastRouteThroughMinibosses != Settings.routeThroughStarredMinibosses) {
                lastRouteThroughMobs = Settings.routeThroughMobs
                lastRouteThroughMinibosses = Settings.routeThroughStarredMinibosses
                liveProbeOrderDirty = true
                liveTargetsDirty = true
                navigation = null
                pendingWork = null
                invalidate(RoomReplanReason.TARGETS_CHANGED, now)
            }
            val position = player.position()
            val point = RoomPoint(position.x, position.y, position.z)
            if (now >= nextLiveContext) {
                nextLiveContext = now + 100
                observedRoom = RoomDungeonContext.current(world, position, liveRoom)
                bloodReminder.observe(observedRoom?.exit)
            }
            if (!dungeonRouteVisible(observedRoom, DungeonAPI.bloodOpened)) {
                if (room != null) clearRoom()
                active = false
                continuation.update(null)
                continuationLines = emptyList()
                status = if (observedRoom?.name?.startsWith("Blood") == true) "Blood reached; routing complete"
                    else "Waiting for confirmed room connections toward Blood"
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
            if (liveExitHint?.approach != observedRoom?.exit?.approach) {
                liveExitHint = observedRoom?.exit
                navigation = null
                pendingWork = null
                invalidate(RoomReplanReason.TARGETS_CHANGED, now)
                finishedRooms.remove(detected.key)
            }
            if (detected.key in finishedRooms && targets!!.remaining().isEmpty()) {
                complete = true
                hidePath()
                refreshContinuation(now)
                return@measure
            }
            trackPlayer(world, detected, position, now)
            refreshContinuation(now)
        }
    }

    private fun recognize(data: RoomTemplateCatalog, world: ClientLevel, point: RoomPoint): DetectedRoom? =
        data.recognize(DungeonAPI.dungeonFloor!!.name, point) { cell ->
            if (!world.hasChunk(Math.floorDiv(cell.x, 16), Math.floorDiv(cell.z, 16))) null
            else BuiltInRegistries.BLOCK.getKey(world.getBlockState(BlockPos(cell.x, cell.y, cell.z)).block).toString()
        }?.takeUnless { unsupportedAuthoredExit == (it.key to observedRoom?.exit?.approach) }

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
        if (!context.containsPositionXZ(position.x, position.z)) {
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
                if (!target.miniboss && entity is Player && dungeonMinibossProfile(mobProfile(entity))) {
                    target.miniboss = true
                    liveTargetsDirty = true
                    liveProbeOrderDirty = true
                }
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
            val missing = liveTargets.values.filter { routesTarget(it) && it.state == RoomTargetState.ACTIVE }.map {
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
        refreshContinuation(now)
    }

    /** Observe each client tick; a ready worker also publishes without waiting for another sample interval. */
    private fun trackPlayer(world: ClientLevel, detected: DetectedRoom, position: Vec3, now: Long) {
        val snapshot = geometry ?: run {
            if (retainedGeometry != null) refreshRouteStart(world, detected, position)
            return
        }
        if (navigation == null) {
            if (!workPending) buildNavigation(snapshot, detected)
            return
        }
        if (now < nextPositionSample) return
        nextPositionSample = now + 50
        val point = RoomPoint(position.x, position.y, position.z)
        if (updates.reason == null && updates.observedPosition == point) return
        val start = RoomWorldGeometry.playerCell(world, detected, snapshot, position)
            ?: RoomWorldGeometry.playerCell(world, detected, snapshot, position, allowSight = true)
        if (start == null) {
            startLines = emptyList()
            if (!refreshSightRouteStart(world, detected, position)) {
                displayStartLines = emptyList()
                displayChunkLines = emptyList()
                displayCursor = -1
            }
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
        val followsPath = refreshRouteStart(world, detected, position)
        val beforeObservation = updates.revision
        updates.observe(start, point, now, Settings.automaticRecalculation, followsPath)
        if (beforeObservation != updates.revision && updates.reason == RoomReplanReason.TELEPORTED) hidePath()
        val context = liveRoom
        val exit = navigation!!.healer
        val exitFeet = exit?.let { RoomWorldGeometry.feet(detected, it, snapshot) }
        if (routedRemaining().isEmpty() && (context == null || context.cleared || context.fairy ||
                liveTargets.values.none { routesTarget(it) && it.state != RoomTargetState.CLEARED }) &&
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

    private fun routesTarget(target: LiveTarget) = dungeonRouteStarTarget(true, target.miniboss,
        Settings.routeThroughMobs, Settings.routeThroughStarredMinibosses)

    private fun routedTargetIds(): Set<String> = if (liveRoom != null)
        liveTargets.values.filter(::routesTarget).mapTo(HashSet()) { it.id }
        else room?.template?.targets?.keys.orEmpty().filterTo(HashSet()) {
            dungeonRouteStarTarget(true, it in authoredMinibosses, Settings.routeThroughMobs, Settings.routeThroughStarredMinibosses)
        }

    private fun routedRemaining(): Set<String> = targets?.remaining().orEmpty().intersect(routedTargetIds())

    private fun markAuthoredMiniboss(id: String) {
        if (authoredMinibosses.add(id) && Settings.routeThroughMobs != Settings.routeThroughStarredMinibosses) {
            navigation = null
            pendingWork = null
            invalidate(RoomReplanReason.TARGETS_CHANGED)
        }
    }

    private fun liveFeet(cell: GridCell, snapshot: RoomLiveGeometrySnapshot? = liveGeometry) =
        snapshot?.feet?.get(cell)?.vector() ?: Vec3(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)

    private fun safeLiveSeed(world: ClientLevel, context: LiveDungeonRoom, position: Vec3): GridCell? {
        val x = floor(position.x).toInt()
        val y = floor(position.y).toInt()
        val z = floor(position.z).toInt()
        return (x - 1..x + 1).flatMap { wx -> (y - 1..y + 1).flatMap { wy ->
            (z - 1..z + 1).map { wz -> GridCell(wx, wy, wz) }
        } }.filter { context.containsRouteXZ(it.x, it.z) && liveFeet(it).distanceToSqr(position) <= 4.0 }
            .sortedBy { liveFeet(it).distanceToSqr(position) }
            .firstOrNull { cell -> RoomWorldGeometry.standingPoint(world, cell)?.let {
                RoomWorldGeometry.validConnector(world, position, it.vector()) || RoomWorldGeometry.clearSightLine(world, position, it.vector())
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
            liveProbeOrder = liveTargets.values.filter { routesTarget(it) && it.state != RoomTargetState.CLEARED }
                .sortedWith(compareBy<LiveTarget>({ it.state != RoomTargetState.ACTIVE }, { it.position.distanceToSqr(position) }))
            liveProbeOrderDirty = false
        }
        if (liveExitHint != context.exit) { liveExitHint = context.exit; liveExitProbe.invalidate() }
        val previousExit = liveExit
        val exit = context.exit
        if (exit != null) {
            checks += liveExitProbe.step(liveGeometryRevision, {
                nearbyCells(exit.approach.x, exit.approach.y, exit.approach.z, 2, 6).filter {
                    it in snapshot.cells && dungeonExitApproach(it, exit)
                }.sortedBy { liveFeet(it, snapshot).distanceToSqr(Vec3(exit.approach.x + 0.5, exit.approach.y.toDouble(), exit.approach.z + 0.5)) }.take(16).toList()
            }, 64, deadline, { candidate ->
                RoomWorldGeometry.validCell(world, liveFeet(candidate, snapshot))
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
        val routeExit = liveExit
        val previous = geometry ?: retainedGeometry
        if (previous != null && roomApproachesPending(previous.targets.keys, selected.keys,
                liveProbeOrder.mapTo(HashSet()) { it.id }, pending)) return
        if (liveBuilder != null && retainedGeometry != null &&
            (route?.targetOrder.orEmpty().any { id -> id !in selected && liveProbeOrder.any { it.id == id } } ||
                route?.path?.lastOrNull() == retainedGeometry!!.healer && routeExit == null)) return
        if (previous?.cells === snapshot.cells && previous.targets == selected && previous.healer == endpoint &&
            previousExit == liveExit && liveRouteExit == routeExit) return
        // A growing flood cannot invalidate an accepted path; refine once when its scan finishes.
        if (liveBuilder != null && previous != null && previous.targets == selected &&
            previous.healer == endpoint && liveRouteExit == routeExit && route != null && navigation != null &&
            room?.let { RoomWorldGeometry.playerCell(world, it, previous, position, allowSight = true) } != null) return
        liveRouteExit = routeExit
        if (route != null && retainedGeometry == null) retainedGeometry = geometry
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

    private fun dirtyLiveGeometry(now: Long = System.nanoTime() / 1_000_000, keepPath: Boolean = false) {
        if (keepPath) {
            retainedGeometry = retainedGeometry ?: geometry
            updates.invalidate(RoomReplanReason.GEOMETRY_CHANGED, now)
            complete = false
        }
        else invalidate(RoomReplanReason.GEOMETRY_CHANGED, now)
        liveExitProbe.invalidate()
        sightExitProbe.invalidate()
        sightExitFeet = null
        liveTargets.values.forEach { it.approach.invalidate() }
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
        val endpoint = if (liveRoom != null) liveRouteExit else {
            val exit = observedRoom?.exit ?: return
            val world = level ?: return
            val desired = exit.approach
            val candidates = snapshot.cells.filter { local ->
                dungeonExitApproach(detected.transform.toWorld(local), exit)
            }.sortedBy { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(Vec3(desired.x + .5, desired.y.toDouble(), desired.z + .5)) }
            val approach = candidates.firstOrNull { local ->
                val feet = RoomWorldGeometry.feet(detected, local, snapshot)
                RoomWorldGeometry.validCell(world, feet)
            }
            if (approach == null) {
                unsupportedAuthoredExit = detected.key to desired
                clearRoom()
                MC.instance.player?.let { tickLive(world, it.position(), System.nanoTime() / 1_000_000) }
                return
            }
            approach
        }
        val work = Any()
        pendingWork = work
        val routed = routedTargetIds()
        val selectedTargets = snapshot.targets.filter { (id, cell) -> id in routed && cell in snapshot.cells }
        Scheduling.schedule(0.milliseconds) {
            val graph = runCatching {
                RoomNavigation.create(snapshot.cells, snapshot.adjacency, selectedTargets,
                    endpoint, snapshot.standingFeet)
            }.getOrNull()
            MC.instance.execute {
                if (pendingWork !== work) return@execute
                pendingWork = null
                if (revision == epoch && room?.key == detected.key && geometry === snapshot && active) {
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
        val remaining = routedRemaining()
        val previous = route?.targetOrder
        val debug = Config.devMode && Settings.debug
        val hasExit = graph.healer != null
        val work = Any()
        pendingWork = work
        Scheduling.schedule(0.milliseconds) {
            val begin = if (debug) System.nanoTime() else 0L
            val result = runCatching { graph.plan(request.start, remaining, previous) }.getOrNull()
            val elapsed = if (debug) (System.nanoTime() - begin) / 1_000_000.0 else 0.0
            MC.instance.execute {
                if (pendingWork !== work) return@execute
                pendingWork = null
                if (updates.revision != request.revision) return@execute
                val world = MC.instance.level
                val player = MC.instance.player
                val actual = player?.position()
                if (world == null || world !== level || actual == null) { clearRoom(); return@execute }
                val cursor = result?.liveStart(0, snapshot.targets,
                    { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it, snapshot)) },
                    { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(actual) })
                val sightCursor = if (result != null && cursor == null) sightRouteCursor(world, detected, snapshot, result, actual, 0) else null
                if (result != null && cursor == null && sightCursor == null) {
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
                if (result == null && liveBuilder != null && routeCursor >= 0 && displayRoute != null) {
                    status = "${liveRoom?.name ?: detected.template.id}: retaining checked guidance while scanning"
                    return@execute
                }
                route = result
                retainedGeometry = null
                displayLines = emptyList()
                displayStartLines = emptyList()
                displayChunkLines = emptyList()
                displayRoute = null
                displayCursor = -1
                lastReason = request.reason.name
                lastMillis = elapsed
                lines = if (result == null) emptyList() else drawPath(world, detected, result,
                    RoomWorldGeometry.feet(detected, result.path.first(), snapshot), hasExit, snapshot)
                routeCursor = cursor ?: sightCursor ?: -1
                lineStart = 0
                refreshRouteStart(world, detected, actual)
                rebuildMarkers()
                val live = liveRoom
                status = if (live != null) {
                    val total = liveTargets.values.count { it.state != RoomTargetState.CLEARED }
                    val unknown = liveTargets.values.count { it.state == RoomTargetState.UNKNOWN }
                    val ending = if (liveExit == null) "exit not yet verified or reachable" else "${live.exit?.kind ?: "dungeon"} exit"
                    if (result == null) "${live.name}: observed targets unreachable; $ending"
                    else if (!Settings.routeThroughMobs) "${live.name}: ${result.targetOrder.size} starred miniboss stops; regular mob stops skipped; $ending"
                    else if (total == 0 && !live.cleared && !live.fairy) "${live.name}: waiting for observed starred mobs; $ending"
                    else if (live.fairy) "Fairy passage: routing to $ending"
                    else "${live.name}: routing ${result.targetOrder.size} of $total observed targets" +
                        (if (unknown > 0) "; $unknown unavailable" else "") +
                        (if (live.cleared) "; map confirms room clear" else "; room clear not confirmed") + "; $ending"
                } else if (result == null) "${detected.template.id}: remaining targets or endpoint unreachable"
                    else if (!Settings.routeThroughMobs) "${detected.template.id}: ${result.targetOrder.size} starred miniboss stops; regular mob stops skipped"
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
        retainedGeometry = null
        lines = emptyList()
        startLines = emptyList()
        handoffLines = emptyList()
        handoffTail = emptyList()
        handoffTailLines = emptyList()
        handoffRevision = -1L
        displayLines = emptyList()
        displayStartLines = emptyList()
        displayChunkLines = emptyList()
        displayLineStart = 0
        displayRoute = null
        displayCursor = -1
        routeCursor = -1
        lineStart = 0
    }

    /** Update only the leading connector; cached pathfinding and target order remain untouched. */
    private fun refreshRouteStart(world: ClientLevel, detected: DetectedRoom, actual: Vec3): Boolean {
        if (routeCursor < 0) return false
        val currentRoute = route ?: return false
        val snapshot = retainedGeometry ?: geometry ?: return false
        val cursor = currentRoute.liveStart(routeCursor, snapshot.targets,
            { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it, snapshot)) },
            { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(actual) })
        if (cursor == null) {
            startLines = emptyList()
            if (!refreshSightRouteStart(world, detected, actual)) {
                displayStartLines = emptyList()
                displayChunkLines = emptyList()
                displayCursor = -1
            }
            if (MC.instance.player?.onGround() == true) updates.invalidate(RoomReplanReason.MOVED, System.nanoTime() / 1_000_000)
            return false
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
            val end = firstLine.to.add(0.0, -ROOM_ROUTE_HEIGHT, 0.0)
            if (!RoomWorldGeometry.validRouteConnector(world, start, end)) {
                lines = drawPath(world, detected, currentRoute, start, navigation?.healer != null, snapshot, cursor)
                lineStart = 0
                startLines = leading
                refreshDisplayPath(world, detected, currentRoute, snapshot, actual, cursor)
                return true
            }
            lineStart++
            leading + PathLine(raisedStart, firstLine.to, firstLine.healer, firstLine.pathIndex)
        } else leading
        refreshDisplayPath(world, detected, currentRoute, snapshot, actual, cursor)
        return true
    }

    private fun sightRouteCursor(world: ClientLevel, detected: DetectedRoom, snapshot: RoomGeometry,
        current: RoomRoute, actual: Vec3, cursor: Int): Int? = current.liveStart(cursor, snapshot.targets,
        { cell -> RoomWorldGeometry.feet(detected, cell, snapshot).let { endpoint ->
            actual.distanceToSqr(endpoint) <= 64.0 && RoomWorldGeometry.clearSightLine(world, actual, endpoint)
        } }, { RoomWorldGeometry.feet(detected, it, snapshot).distanceToSqr(actual) })

    /** Airborne display joins use a checked landing without consuming the physical walking cursor. */
    private fun refreshSightRouteStart(world: ClientLevel, detected: DetectedRoom, actual: Vec3): Boolean {
        if (routeCursor < 0) return false
        val current = route ?: return false
        val snapshot = retainedGeometry ?: geometry ?: return false
        val cursor = sightRouteCursor(world, detected, snapshot, current, actual, routeCursor) ?: return false
        refreshDisplayPath(world, detected, current, snapshot, actual, cursor)
        return true
    }

    private fun refreshDisplayPath(world: ClientLevel, detected: DetectedRoom, currentRoute: RoomRoute,
        snapshot: RoomGeometry, actual: Vec3, cursor: Int) {
        val start = RoomWorldGeometry.feet(detected, currentRoute.path[cursor], snapshot)
        if (displayRoute !== currentRoute) {
            // Display ramps never become connectors or consumed route nodes.
            displayLines = drawPath(world, detected, currentRoute,
                RoomWorldGeometry.feet(detected, currentRoute.path.first(), snapshot), navigation?.healer != null,
                snapshot, display = true)
            displayRoute = currentRoute
            displayCursor = -1
        }
        if (displayCursor != cursor) {
            val end = displayLines.firstOrNull { it.pathIndex > cursor }?.pathIndex
            displayLineStart = if (end == null) displayLines.size else
                displayLines.indexOfFirst { it.pathIndex > end }.takeIf { it >= 0 } ?: displayLines.size
            // Only the partially consumed chunk is resmoothed; later chunks retain their checked curves.
            displayChunkLines = if (end == null) emptyList() else drawPath(world, detected, currentRoute,
                start, navigation?.healer != null, snapshot, cursor, display = true, endIndex = end)
            displayCursor = cursor
        }
        displayStartLines = if (actual == start) emptyList() else listOf(PathLine(
            actual.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), start.add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
            currentRoute.targetOrder.isEmpty() && (liveRoom == null || liveExit != null)))
    }

    @Subscription(NameChangedEvent::class, ComponentAttachEvent::class, EntityInfoLineAttachEvent::class,
        priority = Subscription.LOWEST, receiveCancelled = true)
    fun onName(event: EntityInfoLineEvent) {
        if (!active || room == null || !isEnabled() || !Settings.enabled) return
        val label = event.infoLineEntity as? ArmorStand
        if (label != null) observeNamed(label, event.literalComponent)
        else (event.attachedTo ?: event.infoLineEntity).let { entity ->
            if (entity is LivingEntity && dungeonStarLabel(event.literalComponent) && eligibleStarMob(entity))
                observeStar(entity, dungeonMinibossLabel(event.literalComponent))
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
        } else if (eligibleStarMob(entity)) {
            val labels = listOfNotNull(entity.customName?.string) + entity.getStrippedAttachedLines()
            if (labels.any(::dungeonStarLabel)) observeStar(entity, labels.any(::dungeonMinibossLabel))
        }
    }

    private fun observeNamed(label: ArmorStand, name: String) {
        val world = level ?: return
        val mob = dungeonStarMob(label.id, name, world::getEntity, ::eligibleStarMob) {
            world.getEntities(label, label.boundingBox.move(0.0, -1.0, 0.0))
        } as? LivingEntity ?: return
        observeStar(mob, dungeonMinibossLabel(name))
    }

    private fun observeStar(entity: LivingEntity, minibossLabel: Boolean = false) {
        val detected = room ?: return
        if (!eligibleStarMob(entity) || entity.isDeadOrDying || !contains(detected, entity.position())) return
        val miniboss = minibossLabel || entity is Player && dungeonMinibossProfile(mobProfile(entity))
        if (liveRoom != null) {
            val old = liveTargets[entity.uuid]
            if (old == null) {
                if (liveTargets.size >= 1536) return
                liveTargets[entity.uuid] = LiveTarget("mob-${entity.uuid}", entity.id, entity.position(),
                    if (liveRoom!!.cleared) RoomTargetState.CLEARED else RoomTargetState.ACTIVE, miniboss)
                liveTargetsDirty = true
                liveProbeOrderDirty = true
            } else if (miniboss && !old.miniboss) {
                old.miniboss = true
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
        val known = targets!!.targetFor(entity.id)
        if (entity.id in mobs) {
            if (miniboss && known != null) markAuthoredMiniboss(known)
            return
        }
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
        if (miniboss) markAuthoredMiniboss(targetId)
        if (targets!!.observe(targetId, entity.id)) {
            finishedRooms.remove(detected.key)
            if (targetId in routedTargetIds()) invalidate(RoomReplanReason.TARGETS_CHANGED)
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
                if (routesTarget(target)) invalidate(RoomReplanReason.TARGETS_CHANGED)
            }
            return
        }
        val entity = mobs.remove(event.entity.id) ?: return
        val killed = entity.isDeadOrDying || event.entity.removalReason == Entity.RemovalReason.KILLED
        if (targets?.removed(entity.id, killed) == true) {
            if (Settings.routeThroughMobs || Settings.routeThroughStarredMinibosses) invalidate(RoomReplanReason.TARGETS_CHANGED)
            rebuildMarkers()
        }
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
        if (changed) {
            if (Settings.routeThroughMobs || Settings.routeThroughStarredMinibosses) invalidate(RoomReplanReason.TARGETS_CHANGED)
            rebuildMarkers()
        }
    }

    @Subscription
    fun onBlockChange(event: BlockChangeEvent) {
        if ((!active || room == null) && !RoomTemplateRecorder.isRecording) return
        val pos = event.pos.immutable()
        val observedWorld = level
        val observedRun = run
        // Packets publish before vanilla applies them; batch checks at the end of the client tick.
        MC.instance.execute {
            RoomTemplateRecorder.blockChanged(pos)
            if (observedWorld !== level || observedRun != run || !active) return@execute
            if (pendingBlocks.size < 4096) pendingBlocks[pos] = event.state else blockOverflow = true
        }
    }

    private fun processBlockChanges(world: ClientLevel, now: Long): Boolean {
        if (pendingBlocks.isEmpty() && !blockOverflow) return true
        val changed = pendingBlocks.toMap()
        pendingBlocks.clear()
        val overflow = blockOverflow
        blockOverflow = false
        var relevant = overflow
        var removedOnly = !overflow
        val detected = room
        val bounds = detected?.let(RoomWorldGeometry::geometryBounds)
        if (overflow) continuation.clear()
        for ((pos, expected) in changed) {
            val state = world.getBlockState(pos)
            val mismatch = state != expected
            if (mismatch && equivalentCollision(world, pos, state, expected)) {
                liveBlockStates[pos] = expected
                deferredBlocks.remove(pos)
                continue
            }
            if (mismatch && deferredBlocks.add(pos)) {
                pendingBlocks[pos] = expected
                continuation.blockChanged(GridCell(pos.x, pos.y, pos.z))
                relevant = true
                removedOnly = false
                continue
            }
            deferredBlocks.remove(pos)
            val previous = liveBlockStates.put(pos, state)
            if (liveBlockStates.size > 4096) liveBlockStates.remove(liveBlockStates.keys.first())
            val shape = RoomWorldGeometry.routingCollisionShape(world, pos, state)
            if (!mismatch && previous != null && equivalentCollision(world, pos, previous, state)) continue
            val removed = !mismatch && shape.isEmpty && state.fluidState.isEmpty
            val block = GridCell(pos.x, pos.y, pos.z)
            continuation.blockChanged(block, collisionRemoved = removed)
            if (sightGoal?.let { goal ->
                block.x in goal.x - 3..goal.x + 3 && block.z in goal.z - 3..goal.z + 3 &&
                    block.y in goal.y - 8..goal.y + 9
            } == true) {
                sightExitProbe.invalidate()
                sightExitFeet = null
                nextHandoff = 0L
            }
            if (bounds?.contains(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()) != true) continue
            // Untouched cells will be read in their current state without restarting the flood.
            if (liveRoom != null && liveBuilder?.observesBlock(block) != true &&
                liveGeometry?.let { nearbyRoomGeometry(it.cells, block) } != true &&
                retainedGeometry?.let { nearbyRoomGeometry(it.cells, block) } != true) continue
            relevant = true
            removedOnly = removedOnly && removed
        }
        if (relevant && detected != null) {
            if (liveRoom != null) dirtyLiveGeometry(now, keepPath = removedOnly && checkedRemainingPath(world, detected))
            else if (changed.keys.any { pos -> detected.template.landmarks.any {
                detected.transform.toWorld(it.cell) == GridCell(pos.x, pos.y, pos.z) } }) clearRoom()
            else rebuildGeometry()
        }
        if (pendingBlocks.isNotEmpty()) {
            hidePath()
            continuationLines = emptyList()
            return false
        }
        return true
    }

    private fun equivalentCollision(world: ClientLevel, pos: BlockPos, previous: BlockState, next: BlockState) =
        runCatching { previous.fluidState == next.fluidState &&
            previous.`is`(BlockTags.CLIMBABLE) == next.`is`(BlockTags.CLIMBABLE) &&
            Shapes.equal(RoomWorldGeometry.routingCollisionShape(world, pos, previous),
                RoomWorldGeometry.routingCollisionShape(world, pos, next)) }.getOrDefault(false)

    private fun checkedRemainingPath(world: ClientLevel, detected: DetectedRoom): Boolean {
        val current = route ?: return false
        val snapshot = retainedGeometry ?: geometry ?: return false
        if (routeCursor !in current.path.indices) return false
        val player = MC.instance.player?.position() ?: return false
        val remaining = current.path.drop(routeCursor).map { RoomWorldGeometry.feet(detected, it, snapshot) }
        return checkedRoomPathSurvivesChange((listOf(player) + remaining).map { RoomPoint(it.x, it.y, it.z) }) { a, b ->
            RoomWorldGeometry.validConnector(world, a.vector(), b.vector())
        }
    }

    private fun chunkChanged(world: ClientLevel, x: Int, z: Int, unloaded: Boolean) {
        RoomTemplateRecorder.chunkChanged(world, x, z)
        if (!active || world !== level) return
        continuation.chunkChanged(x, z)
        sightGoal?.let { approach ->
            if (approach.x - 3 < (x + 1) * 16 && approach.x + 3 >= x * 16 &&
                approach.z - 3 < (z + 1) * 16 && approach.z + 3 >= z * 16) {
                sightExitProbe.invalidate()
                sightExitFeet = null
                nextHandoff = 0L
            }
        }
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

    private fun drawPath(world: ClientLevel, detected: DetectedRoom, result: RoomRoute, actual: Vec3, hasExit: Boolean,
        snapshot: RoomGeometry, startIndex: Int = 0, display: Boolean = false,
        endIndex: Int = result.path.lastIndex): List<PathLine> {
        val stops = result.targetStops(snapshot.targets) ?: return emptyList()
        val healerFrom = if (result.targetOrder.isEmpty()) 0 else stops[stops.lastIndex - 1]
        val output = ArrayList<PathLine>()
        var from = actual
        var begin = startIndex
        val directEnds = HashSet<Int>()
        val ends = if (display) buildList {
            var first = startIndex
            for (end in (stops + endIndex).distinct().filter { it in startIndex..endIndex }.sorted()) {
                val start = if (first == startIndex) actual else RoomWorldGeometry.feet(detected, result.path[first], snapshot)
                val finish = RoomWorldGeometry.feet(detected, result.path[end], snapshot)
                if (RoomWorldGeometry.clearSightLine(world, start, finish)) { directEnds.add(end); add(end) }
                else addAll(roomDisplayStops(listOf(end), first, end))
                first = end
            }
        } else stops.distinct().filter { it >= startIndex }
        for (end in ends) {
            val endpoint = RoomWorldGeometry.feet(detected, result.path[end], snapshot)
            val direct = begin != end && if (display) end in directEnds || RoomWorldGeometry.clearSightLine(world, from, endpoint)
                else RoomWorldGeometry.validRouteConnector(world, from, endpoint)
            if (direct) {
                output.add(PathLine(from.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), endpoint.add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
                    (end > healerFrom || result.targetOrder.isEmpty()) && hasExit, end))
                from = endpoint
                begin = end
                continue
            }
            val original = arrayListOf(from to begin)
            for (index in begin..end) {
                val to = RoomWorldGeometry.feet(detected, result.path[index], snapshot)
                val points = if (display) listOf(from, to)
                    else RoomWorldGeometry.connectorWaypoints(from, to) ?: return emptyList()
                for (point in points.drop(1)) if (original.last().first != point) original.add(point to index)
                from = to
            }
            // Each target and doorway remains a leg boundary; LOS never removes a required stop.
            val cleaned = if (display) RoomWorldGeometry.smoothDisplayPath(world, original.map { it.first })
                else RoomWorldGeometry.simplifyPath(world, original.map { it.first })
            var match = 0
            for ((a, b) in cleaned.zipWithNext()) {
                if (!display) while (match < original.lastIndex && original[match].first != b) match++
                output.add(PathLine(a.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), b.add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
                    (end > healerFrom || result.targetOrder.isEmpty()) && hasExit, if (display) end else original[match].second))
            }
            begin = end
        }
        return output
    }

    private fun List<Vec3>.distinctConsecutive(): List<Vec3> = buildList {
        for (point in this@distinctConsecutive) if (lastOrNull() != point) add(point)
    }

    private fun refreshContinuation(now: Long) {
        continuation.update(observedRoom?.exit)
        continuation.step(now)
        val required = liveTargets.values.filter { routesTarget(it) && it.state != RoomTargetState.CLEARED }
        val requiredIds = required.mapTo(HashSet()) { it.id }
        val hasLocalLine = roomLocalGuideRelevant(route, requiredIds, navigation?.healer) && displayCursor >= 0 &&
            (displayStartLines.isNotEmpty() || displayChunkLines.isNotEmpty() ||
                displayLineStart < displayLines.size)
        val needsHandoff = liveRoom != null && !hasLocalLine
        if (!needsHandoff) handoffLines = emptyList()
        if (needsHandoff && now >= nextHandoff) {
            nextHandoff = now + 50
            val position = MC.instance.player?.position()
            val world = level
            val hint = observedRoom?.exit
            val target = required.firstOrNull { it.id == route?.targetOrder?.firstOrNull() }
                ?: required.minByOrNull { position?.distanceToSqr(it.position) ?: 0.0 }
            val exitApproach = if (complete) hint?.doors?.firstOrNull()?.takeIf { !it.closed }?.departure ?: hint?.approach else hint?.approach
            val goal = target?.position?.let { GridCell(floor(it.x).toInt(), ceil(it.y - .00001).toInt(), floor(it.z).toInt()) }
                ?: exitApproach
            if (sightExitHint != hint || sightTarget != target?.id || sightGoal != goal) {
                sightExitHint = hint
                sightTarget = target?.id
                sightGoal = goal
                sightExitProbe.invalidate()
                sightExitFeet = null
                sightProbePosition = position
                sightProbeRevision = -1L
            }
            var visibleEndpoint = sightExitFeet?.takeIf { endpoint -> position != null && world != null &&
                (target == null || endpoint.vector().distanceToSqr(target.position) <= 9.0 &&
                    RoomWorldGeometry.clearSightLine(world, target.position, endpoint.vector())) &&
                RoomWorldGeometry.clearSightLine(world, position, endpoint.vector()) }
            val playerMoved = position != null && sightProbePosition?.distanceToSqr(position)?.let { it >= .25 } == true
            if ((sightExitFeet != null && visibleEndpoint == null) || (sightExitFeet == null && playerMoved &&
                    sightExitProbe.complete(sightProbeRevision))) {
                // Finish an in-progress probe before resetting negatives, even while the player keeps moving.
                sightExitProbe.invalidate()
                sightExitFeet = null
                sightProbePosition = position
                sightProbeRevision = -1L
            }
            if (goal != null && world != null && position != null) {
                if (sightProbePosition == null) sightProbePosition = position
                // Checked sight guidance reaches a required mob or door before its walking flood is ready.
                sightExitProbe.step(liveGeometryRevision, {
                    nearbyCells(goal.x, goal.y, goal.z, 2, if (target == null) 6 else 1)
                        .filter { target != null || hint != null && dungeonExitApproach(it, hint.copy(approach = goal)) }.sortedBy {
                            val dx = it.x - goal.x; val dy = it.y - goal.y; val dz = it.z - goal.z
                            dx * dx + dy * dy + dz * dz
                        }.toList()
                }, 8, System.nanoTime() + 1_000_000, { cell ->
                    RoomWorldGeometry.standingPoint(world, cell)?.takeIf { feet -> target == null ||
                        feet.vector().distanceToSqr(target.position) <= 9.0 &&
                        RoomWorldGeometry.clearSightLine(world, target.position, feet.vector())
                    }?.takeIf { RoomWorldGeometry.clearSightLine(world, position, it.vector()) }
                        ?.also { sightExitFeet = it; visibleEndpoint = it } != null
                })
                sightProbeRevision = liveGeometryRevision
            }
            val actual = position?.let { RoomPoint(it.x, it.y, it.z) }
            val points = if (actual != null && visibleEndpoint != null) listOf(actual, visibleEndpoint!!)
                else if (target == null) actual?.let { continuation.checkedCurrentPath(it, display = true) } else null
            // The projected player join moves every sample; only the standing-node suffix is cached.
            val tail = points?.drop(2).orEmpty()
            if (handoffTail != tail || handoffRevision != continuation.revision) {
                handoffTail = tail
                handoffRevision = continuation.revision
                val display = if (world == null) emptyList() else RoomWorldGeometry.smoothDisplayPath(world, tail.map { it.vector() })
                handoffTailLines = display.zipWithNext { a, b -> PathLine(a.add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
                    b.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), true) }
            }
            handoffLines = points?.take(3)?.zipWithNext { a, b -> PathLine(a.vector().add(0.0, ROOM_ROUTE_HEIGHT, 0.0),
                b.vector().add(0.0, ROOM_ROUTE_HEIGHT, 0.0), target == null) }.orEmpty() + handoffTailLines
        }
        val exit = navigation?.healer
        if (continuationRevision == continuation.revision && continuationStart == exit) return
        continuationRevision = continuation.revision; continuationStart = exit
        val sections = continuation.sections(exit?.let { endpoint -> room?.let { detected ->
            RoomWorldGeometry.feet(detected, endpoint, geometry).let { RoomPoint(it.x, it.y, it.z) }
        } })
        continuationLines = sections.flatMap { section ->
            val points = section.displayPoints.map { it.vector() }
            val world = level
            // Door sections retain their mandatory center unchanged.
            val display = if (!section.checked || section.gate || world == null) points
                else RoomWorldGeometry.smoothDisplayPath(world, points)
            display.zipWithNext { a, b ->
                PathLine(a.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), b.add(0.0, ROOM_ROUTE_HEIGHT, 0.0), true, checked = section.checked)
            }
        }
        continuationHints = sections.filter { !it.checked }.distinctBy { it.gate }.map { section ->
            section.points.first().vector().add(0.0, ROOM_ROUTE_HEIGHT + 1, 0.0) to
                if (section.gate) "Planned door crossing" else "Planned room connection"
        }
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
            return
        }
        markers = detected.template.targets.map { (id, cell) ->
            val feet = RoomWorldGeometry.feet(detected, cell)
            val state = targets!!.state(id)
            val number = order.indexOf(id).takeIf { it >= 0 }?.plus(1)
            Marker(AABB(feet.x - 0.35, feet.y, feet.z - 0.35, feet.x + 0.35, feet.y + 1.0, feet.z + 0.35),
                feet, "${number?.let { "$it. " }.orEmpty()}$id", state, number == 1)
        }
    }

    @Subscription
    fun render(event: RenderWorldEvent.AfterTranslucent) {
        val player = MC.instance.player ?: return
        if (!isEnabled() || !Settings.enabled || player.isDeadOrDying || DungeonAPI.ownPlayer?.dead == true ||
            !dungeonRoutingActive(LocationAPI.island == SkyBlockIsland.THE_CATACOMBS,
                DungeonAPI.dungeonFloor != null, DungeonAPI.completed, DungeonAPI.inBoss)) return
        val door = if (Settings.showHealer) bloodReminder.highlight(DungeonAPI.dungeonClass == DungeonClass.MAGE,
            RoomPoint(player.x, player.y, player.z)) else null
        if (door != null) {
            val color = HudTheme.alpha(HudTheme.blend(HudTheme.RED, 0xFFFFFF, 20), Settings.opacity)
            // Match Noamm's real DoorTile bounds, without the route-height offset or an approach marker.
            val box = AABB(door.x - 1.0, door.y.toDouble(), door.z - 1.0, door.x + 2.0, door.y + 4.0, door.z + 2.0)
            MC.instance.levelRenderer.collectPerFrameGizmos().use {
                Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, Settings.thickness.toFloat() + 1.5f,
                    HudTheme.alpha(color, 15))).setAlwaysOnTop()
            }
        }
        if (DungeonAPI.bloodOpened) return
        if (!active || complete && continuationLines.isEmpty() && handoffLines.isEmpty() || !isEnabled() || !Settings.enabled || room == null ||
            !dungeonRouteVisible(observedRoom, DungeonAPI.bloodOpened)) return
        if (observedRoom?.containsPositionXZ(player.x, player.z) != true) return
        val thickness = Settings.thickness.toFloat() + 1.5f
        val accent = HudTheme.alpha(HudTheme.blend(HudTheme.ACCENT, 0xFFFFFF, 20), Settings.opacity)
        val healer = HudTheme.alpha(HudTheme.blend(HudTheme.GREEN, 0xFFFFFF, 20), Settings.opacity)
        val planned = HudTheme.alpha(HudTheme.blend(HudTheme.YELLOW, 0xFFFFFF, 15), Settings.opacity)
        val heightOffset = roomRouteLineOffset(Settings.lineHeight)
        MC.instance.levelRenderer.collectPerFrameGizmos().use {
            if (Settings.showRoute) {
                var count = 0
                var index = 0
                fun stroke(from: Vec3, to: Vec3, color: Int) {
                    Gizmos.line(from.add(0.0, heightOffset, 0.0), to.add(0.0, heightOffset, 0.0), color, thickness).setAlwaysOnTop()
                    count++
                }
                val local = if (handoffLines.isNotEmpty()) handoffLines
                    else if (displayCursor >= 0) displayStartLines + displayChunkLines + displayLines.drop(displayLineStart)
                    else displayLines.drop(displayLineStart)
                val localCount = local.size
                while (index < localCount + continuationLines.size) {
                    var line = when {
                        index < localCount -> local[index]
                        else -> continuationLines[index - localCount]
                    }
                    if (index == 0 && (handoffLines.isNotEmpty() || displayStartLines.isNotEmpty())) {
                        val delta = player.getPosition(event.partialTicks).subtract(player.position())
                        line = line.copy(from = line.from.add(delta))
                    }
                    index++
                    if (line.healer && !Settings.showHealer) continue
                    if (line.from.distanceToSqr(event.cameraPosition) > 256 * 256 && line.to.distanceToSqr(event.cameraPosition) > 256 * 256) continue
                    // shortcut: cap route strokes at 2048 draws; add frustum indexing for larger routes.
                    if (count >= 2048) break
                    val color = if (!line.checked) planned else if (line.healer) healer else accent
                    if (line.checked) stroke(line.from, line.to, color)
                    else {
                        val distance = line.from.distanceTo(line.to)
                        val direction = line.to.subtract(line.from).normalize()
                        var offset = 0.0
                        while (offset < distance && count < 2048) {
                            stroke(line.from.add(direction.scale(offset)), line.from.add(direction.scale(minOf(distance, offset + .8))), color)
                            offset += 1.4
                        }
                    }
                }
                if (Settings.showHealer) for ((point, text) in continuationHints) {
                    val distance = sqrt(point.distanceToSqr(event.cameraPosition))
                    if (distance <= 96 && count < 2048) {
                        Gizmos.billboardText(text, point.add(0.0, heightOffset, 0.0), TextGizmo.Style.forColorAndCentered(planned)
                            .withScale(roomWaypointTextScale(distance))).setAlwaysOnTop()
                        count++
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
        }
    }

    private fun renderWaypoint(marker: Marker, color: Int, thickness: Float, camera: Vec3, label: Boolean) {
        val cleared = marker.state == RoomTargetState.CLEARED
        val box = marker.box.move(0.0, ROOM_ROUTE_HEIGHT, 0.0)
        val width = if (marker.next) thickness + 1 else thickness
        Gizmos.cuboid(box, GizmoStyle.stroke(HudTheme.alpha(0x071015, if (cleared) 20 else Settings.opacity), width + 2.5f)).setAlwaysOnTop()
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, width,
            HudTheme.alpha(color, if (cleared) 5 else if (marker.next) 25 else 10))).setAlwaysOnTop()
        val distance = sqrt(marker.pos.distanceToSqr(camera))
        if (label && !cleared) Gizmos.billboardText("${marker.label} · ${distance.toInt()}m", marker.pos.add(0.0, 1.6 + ROOM_ROUTE_HEIGHT, 0.0),
            TextGizmo.Style.forColorAndCentered(color).withScale(roomWaypointTextScale(distance)))
            .setAlwaysOnTop()
    }
}

internal const val ROOM_ROUTE_HEIGHT = 1.2

internal fun roomRouteLineOffset(height: Double) = (height.takeIf { it.isFinite() }?.coerceIn(0.0, 4.0)
    ?: ROOM_ROUTE_HEIGHT) - ROOM_ROUTE_HEIGHT

// Gizmo text scales font pixels by scale/16; keep labels readable at distance without giant nearby text.
internal fun roomWaypointTextScale(distance: Double) = (distance.coerceIn(1.0, 80.0) * 0.035).toFloat()

/** Revisions only grow the same checked room graph; callers invalidate for collision, context or anchor changes. */
internal class RoomApproachProbe {
    private var revision: Long? = null
    private var candidates = emptyList<GridCell>()
    private var cursor = 0
    private var found: GridCell? = null
    private val rejected = HashSet<GridCell>()

    fun invalidate() { revision = null; candidates = emptyList(); cursor = 0; found = null; rejected.clear() }
    fun result(revision: Long): GridCell? = if (this.revision == revision) found else null
    fun complete(revision: Long) = this.revision == revision && (found != null || cursor == candidates.size)

    fun step(revision: Long, candidates: () -> List<GridCell>, maxChecks: Int, deadlineNanos: Long,
        canConnect: (GridCell) -> Boolean, clock: () -> Long = System::nanoTime): Int {
        val limit = maxChecks.coerceIn(0, 64)
        if (this.revision != revision) {
            if (found != null) { this.revision = revision; return 0 }
            if (limit == 0 || clock() >= deadlineNanos) return 0
            this.revision = revision
            this.candidates = candidates().distinct().filterNot { it in rejected }
            cursor = 0
        }
        var checks = 0
        while (found == null && cursor < this.candidates.size && checks < limit && clock() < deadlineNanos) {
            val cell = this.candidates[cursor++]
            checks++
            if (canConnect(cell)) found = cell else rejected.add(cell)
        }
        return checks
    }
}
