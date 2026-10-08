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
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
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
import kotlin.math.floor
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
    private var workPending = false
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

    private data class PathLine(val from: Vec3, val to: Vec3, val healer: Boolean, val pathIndex: Int = 0)
    private data class Marker(val box: AABB, val pos: BlockPos, val label: String, val state: RoomTargetState, val next: Boolean)

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
        if (!Config.devMode || !Settings.debug) return base
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
                        parsed.isFailure -> "Invalid room catalog; see docs/dungeon-routing.md"
                        catalog == null -> "No room catalog; add config/skymyce/dungeon-routing-rooms.json"
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
        route = null
        hidePath()
        markers = emptyList()
        healerMarker = null
        complete = false
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
        active = true
        val data = catalog ?: return
        if (data.rooms.isEmpty()) return
        RuntimeProfiler.measure(RuntimeProfiler.Area.DUNGEON_ROUTE) {
            val now = System.nanoTime() / 1_000_000
            val position = player.position()
            val point = RoomPoint(position.x, position.y, position.z)
            val current = room
            if (current != null && !current.template.bounds.containsPlayer(current.transform.toLocal(
                    GridCell(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())), !player.onGround())) clearRoom()
            if (room == null) {
                if (now < nextRecognition) return@measure
                nextRecognition = now + 2000
                val found = data.recognize(DungeonAPI.dungeonFloor!!.name, point) { cell ->
                    if (!world.hasChunk(Math.floorDiv(cell.x, 16), Math.floorDiv(cell.z, 16))) null
                    else BuiltInRegistries.BLOCK.getKey(world.getBlockState(BlockPos(cell.x, cell.y, cell.z)).block).toString()
                }
                if (found == null) { status = "Room unknown, ambiguous or unloaded"; return@measure }
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
            val detected = room ?: return@measure
            val build = builder
            if (build != null && build.step()) {
                builder = null
                geometry = build.result
                if (geometry == null) status = "${detected.template.id}: incomplete geometry"
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
            val snapshot = geometry ?: return@measure
            if (navigation == null) {
                if (!workPending) buildNavigation(snapshot, detected)
                return@measure
            }
            if (now < nextPositionSample) return@measure
            nextPositionSample = now + 250
            val start = RoomWorldGeometry.playerCell(world, detected, snapshot, position)
            if (start == null) {
                // The graph remains valid during an ordinary jump; sample again on landing.
                if (!player.onGround()) {
                    startLines = emptyList()
                    return@measure
                }
                clearRoom()
                nextRecognition = now + 2000
                status = "No safe room connection; recognition deferred"
                return@measure
            }
            val beforeObservation = updates.revision
            updates.observe(start, point, now, Settings.automaticRecalculation)
            if (updates.revision != beforeObservation && updates.reason == RoomReplanReason.TELEPORTED) hidePath()
            refreshRouteStart(world, detected, position)
            val healerFeet = RoomWorldGeometry.feet(detected, snapshot.healer)
            if (targets!!.remaining().isEmpty() && position.distanceToSqr(healerFeet) < 2.25 &&
                RoomWorldGeometry.validConnector(world, position, healerFeet)) {
                complete = true
                finishedRooms.add(detected.key)
                hidePath()
                status = "${detected.template.id}: targets cleared and healer reached"
                return@measure
            }
            if (complete) return@measure
            if (!workPending && updates.due(now)) updates.take(now)?.let { plan(it, detected) }
        }
    }

    private fun buildNavigation(snapshot: RoomGeometry, detected: DetectedRoom) {
        val revision = epoch
        workPending = true
        Scheduling.schedule(0.milliseconds) {
            val graph = runCatching {
                RoomNavigation.create(snapshot.cells, snapshot.adjacency, snapshot.targets.filterValues { it in snapshot.cells }, snapshot.healer)
            }.getOrNull()
            MC.instance.execute {
                workPending = false
                if (revision == epoch && room?.key == detected.key && geometry === snapshot) {
                    navigation = graph
                    if (graph == null) { geometry = null; status = "${detected.template.id}: blocked target or healer endpoint" }
                }
            }
        }
    }

    private fun plan(request: RoomReplanRequest, detected: DetectedRoom) {
        val graph = navigation ?: return
        val remaining = targets!!.remaining()
        val previous = route?.targetOrder
        val debug = Config.devMode && Settings.debug
        workPending = true
        Scheduling.schedule(0.milliseconds) {
            val begin = if (debug) System.nanoTime() else 0L
            val result = runCatching { graph.plan(request.start, remaining, previous) }.getOrNull()
            val prepared = if (result == null) emptyList() else drawPath(detected, result, RoomWorldGeometry.feet(detected, result.path.first()))
            val elapsed = if (debug) (System.nanoTime() - begin) / 1_000_000.0 else 0.0
            MC.instance.execute {
                workPending = false
                if (updates.revision != request.revision) return@execute
                val world = MC.instance.level
                val player = MC.instance.player
                val actual = player?.position()
                if (world == null || world !== level || actual == null) { clearRoom(); return@execute }
                val cursor = result?.liveStart(0, detected.template.targets,
                    { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it)) },
                    { RoomWorldGeometry.feet(detected, it).distanceToSqr(actual) })
                if (result != null && cursor == null) {
                    if (!player.onGround()) {
                        updates.invalidate(RoomReplanReason.MOVED, System.nanoTime() / 1_000_000)
                        startLines = emptyList()
                        status = "Waiting for a safe landing connection"
                        return@execute
                    }
                    invalidate(RoomReplanReason.TELEPORTED)
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
                status = if (result == null) "${detected.template.id}: remaining targets or healer unreachable"
                    else "${detected.template.id}: ${result.targetOrder.size} targets, ${result.cost} movement blocks"
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
        val cursor = currentRoute.liveStart(routeCursor, detected.template.targets,
            { RoomWorldGeometry.validConnector(world, actual, RoomWorldGeometry.feet(detected, it)) },
            { RoomWorldGeometry.feet(detected, it).distanceToSqr(actual) })
        if (cursor == null) {
            invalidate(RoomReplanReason.TELEPORTED)
            return
        }
        routeCursor = cursor
        lineStart = lines.indexOfFirst { it.pathIndex > cursor }.takeIf { it >= 0 } ?: lines.size
        val start = RoomWorldGeometry.feet(detected, currentRoute.path[cursor])
        val leading = RoomWorldGeometry.connectorWaypoints(actual, start).orEmpty().zipWithNext { a, b ->
            PathLine(a.add(0.0, 0.1, 0.0), b.add(0.0, 0.1, 0.0), currentRoute.targetOrder.isEmpty())
        }
        val firstLine = lines.getOrNull(lineStart)
        val raisedStart = start.add(0.0, 0.1, 0.0)
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
        val entity = event.attachedTo as? LivingEntity ?: return
        observeStar(entity, event.literalComponent)
    }

    @Subscription
    fun onEntityAdded(event: EntityAddedEvent) {
        if (!active || !isEnabled() || !Settings.enabled) return
        val entity = event.entity as? LivingEntity ?: return
        val detected = room ?: return
        if (contains(detected, entity.position()) && spawnPoints.size < 2048) spawnPoints.putIfAbsent(entity.id, entity.position())
        entity.getStrippedAttachedLines().firstOrNull { '✯' in it }?.let { observeStar(entity, it) }
    }

    private fun scanRoom(world: ClientLevel, detected: DetectedRoom) {
        val bounds = worldBounds(detected)
        // One bounded room scan on entry; names arriving later are handled by attachment events.
        for (entity in world.getEntities(null, bounds).take(2048)) {
            if (entity !is LivingEntity) continue
            if (spawnPoints.size < 2048) spawnPoints.putIfAbsent(entity.id, entity.position())
            entity.getStrippedAttachedLines().firstOrNull { '✯' in it }?.let { observeStar(entity, it) }
        }
    }

    private fun observeStar(entity: LivingEntity, name: String) {
        val detected = room ?: return
        if ('✯' !in name || entity.isDeadOrDying || !contains(detected, entity.position())) return
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
                if (detected.template.landmarks.any { detected.transform.toWorld(it.cell) == GridCell(pos.x, pos.y, pos.z) }) clearRoom()
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
        if (unloaded) { observeDeaths(); targets?.unloaded(); mobs.clear(); spawnPoints.clear(); rebuildMarkers() }
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val detected = room ?: return
        val world = level ?: return
        epoch++
        updates.updateContext(RoomRoutingContext(epoch, run, detected.key.toString()), System.nanoTime() / 1_000_000)
        invalidate(RoomReplanReason.GEOMETRY_CHANGED)
        navigation = null
        geometry = null
        builder = RoomGeometryBuilder(world, detected)
    }

    private fun contains(detected: DetectedRoom, point: Vec3) = detected.template.bounds.contains(
        detected.transform.toLocal(GridCell(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())))

    private fun worldBounds(detected: DetectedRoom): AABB {
        val a = detected.transform.toWorld(detected.template.bounds.min)
        val b = detected.transform.toWorld(detected.template.bounds.max)
        return AABB(minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
            maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 1.0, maxOf(a.z, b.z) + 1.0)
    }

    private fun drawPath(detected: DetectedRoom, result: RoomRoute, actual: Vec3): List<PathLine> {
        var healerFrom = 0
        for (id in result.targetOrder) {
            val index = result.path.subList(healerFrom, result.path.size).indexOf(detected.template.targets.getValue(id))
            if (index < 0) return emptyList()
            healerFrom += index
        }
        val output = ArrayList<PathLine>()
        var from = actual
        for ((index, cell) in result.path.withIndex()) {
            val to = RoomWorldGeometry.feet(detected, cell)
            val points = RoomWorldGeometry.connectorWaypoints(from, to) ?: return emptyList()
            for (i in 1 until points.size) {
                val a = points[i - 1].add(0.0, 0.1, 0.0)
                val b = points[i].add(0.0, 0.1, 0.0)
                if (a.distanceToSqr(b) < 0.000001) continue
                val healer = index > healerFrom || result.targetOrder.isEmpty()
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
        markers = detected.template.targets.map { (id, cell) ->
            val feet = RoomWorldGeometry.feet(detected, cell)
            val state = targets!!.state(id)
            val number = order.indexOf(id).takeIf { it >= 0 }?.plus(1)
            Marker(AABB(feet.x - 0.35, feet.y, feet.z - 0.35, feet.x + 0.35, feet.y + 1.0, feet.z + 0.35),
                BlockPos.containing(feet), "${number?.let { "$it. " }.orEmpty()}$id (${state.name.lowercase()})", state, number == 1)
        }
        val feet = RoomWorldGeometry.feet(detected, detected.template.healer)
        healerMarker = Marker(AABB(feet.x - 0.5, feet.y, feet.z - 0.5, feet.x + 0.5, feet.y + 1.5, feet.z + 0.5),
            BlockPos.containing(feet), "Healer", RoomTargetState.UNKNOWN, false)
    }

    @Subscription
    fun render(event: RenderWorldEvent.AfterTranslucent) {
        if (!active || complete || !isEnabled() || !Settings.enabled || room == null) return
        val thickness = Settings.thickness.toFloat()
        val accent = HudTheme.alpha(HudTheme.ACCENT, Settings.opacity)
        val healer = HudTheme.alpha(HudTheme.GREEN, Settings.opacity)
        MC.instance.levelRenderer.collectPerFrameGizmos().use {
            if (Settings.showRoute) {
                var count = 0
                var index = 0
                while (index < startLines.size + lines.size - lineStart) {
                    val line = if (index < startLines.size) startLines[index] else lines[lineStart + index - startLines.size]
                    index++
                    if (line.healer && !Settings.showHealer) continue
                    if (line.from.distanceToSqr(event.cameraPosition) > 128 * 128 && line.to.distanceToSqr(event.cameraPosition) > 128 * 128) continue
                    // ponytail: cap nearby segments at 2048; add frustum indexing if authored rooms exceed it.
                    if (count++ >= 2048) break
                    Gizmos.line(line.from, line.to, if (line.healer) healer else accent, thickness)
                }
            }
            if (Settings.showTargets) for (marker in markers) {
                if (marker.state == RoomTargetState.CLEARED && !Settings.showCleared) continue
                val color = when {
                    marker.state == RoomTargetState.CLEARED -> HudTheme.alpha(HudTheme.MUTED, 30)
                    marker.next -> accent
                    else -> HudTheme.alpha(HudTheme.SECONDARY, Settings.opacity / 2)
                }
                Gizmos.cuboid(marker.box, GizmoStyle.stroke(color, if (marker.next) thickness + 1 else thickness))
                if (Settings.showNumbers) Gizmos.billboardTextOverBlock(marker.label, marker.pos, 2, color, 0.7f)
            }
            if (Settings.showHealer) healerMarker?.let {
                Gizmos.cuboid(it.box, GizmoStyle.stroke(healer, thickness))
                Gizmos.billboardTextOverBlock(it.label, it.pos, 2, healer, 0.8f)
            }
        }
    }
}
