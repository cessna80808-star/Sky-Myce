package me.mycellium.skymyce.features.instances.dungeons.camera

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.HealerCrushCameraConfig as Settings
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.helpers.getStrippedAttachedLines
import java.util.concurrent.atomic.AtomicReference

/** Detection never sends packets, moves a camera/player, requests chunks, or interprets item use as a leap. */
object HealerCrushCamera : SkyMyceModule() {
    private val sequence = HealerCrushSequence()
    private val clockStart = System.nanoTime()
    private fun now() = (System.nanoTime() - clockStart) / 1_000_000L
    private var level: ClientLevel? = null
    @Volatile private var epoch = 0L
    private var calibrationRevision = -1L
    private val calibrations = mutableMapOf<CrushStage, CrushPillarCalibration>()
    private val observers = mutableMapOf<CrushStage, CrushPillarObserver>()
    private val serverBlockRevision = mutableMapOf<CrushStage, Long>()
    private val serverBlockAt = mutableMapOf<CrushStage, Long>()
    private var nextObservation = 0L
    @Volatile private var lastPosition: CrushPoint? = null
    private data class PositionCorrection(val epoch: Long, val id: Int, val before: CrushPoint, val at: Long)
    private val pendingCorrection = AtomicReference<PositionCorrection?>()
    private val crushMessages = StormCrushMessages()
    private var consumedCrushSerial = 0L
    val stage: CrushStage? get() = sequence.stage
    val status: String get() = sequence.status
    var captureStatus = "Capture actual hanging pillars during a real Healer F7/M7 Storm encounter"
        private set

    override fun init() {
        CrushPillarStore.initialize()
        ClientTickEvents.END_CLIENT_TICK.register { update() }
    }

    override fun onDisable() { reset("Disabled") }

    private fun realDungeon(): Boolean {
        val address = MC.instance.currentServer?.ip?.substringBefore(':')?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return false
        return (address == "hypixel.net" || address.endsWith(".hypixel.net")) &&
            LocationAPI.isOnSkyBlock && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS
    }

    private fun refreshContext(time: Long) {
        val client = MC.instance
        if (client.level !== level) {
            reset("World changed; waiting for verified encounter entry")
            level = client.level
            calibrationRevision = -1L
        }
        if (calibrationRevision != CrushPillarStore.revision) {
            calibrations.clear(); observers.clear(); serverBlockRevision.clear(); serverBlockAt.clear()
            for (value in CrushStage.entries) CrushPillarStore.get(CameraViewpoints.context(), value)?.let {
                calibrations[value] = it; observers[value] = CrushPillarObserver(it)
            }
            calibrationRevision = CrushPillarStore.revision
            if (stage != null) sequence.cancelCurrent("Pillar calibration changed; waiting for the next Storm phase")
        }
        val configured = CrushStage.entries.all { calibrations[it] != null && CameraViewpoints.get(it.name.lowercase(java.util.Locale.ROOT)) != null }
        val player = client.player
        sequence.context(CrushEncounterContext(epoch, isEnabled() && Settings.enabled, DungeonAPI.dungeonFloor?.name,
            DungeonAPI.ownPlayer?.dungeonClass == DungeonClass.HEALER,
            player?.isAlive == true && DungeonAPI.ownPlayer?.dead == false, realDungeon() && DungeonAPI.inBoss && !DungeonAPI.completed, configured), time)
    }

    private fun update() {
        val time = now()
        refreshContext(time)
        val player = MC.instance.player
        val current = player?.position()?.let { CrushPoint(it.x, it.y, it.z) }
        pendingCorrection.getAndSet(null)?.takeIf { it.epoch == epoch && current != null && time - it.at in 0..2_000L }?.let {
            sequence.positionCorrected(it.id, it.before, current!!, time, pendingLeapDestination())
        }
        lastPosition = current
        sequence.tick(time)
        if (!sequence.stormActive || !realDungeon() || time < nextObservation) return
        nextObservation = time + 100L
        val world = level ?: return
        val storm = identifiedStorm(world)
        if (storm == null) {
            if (stage != null) sequence.cancelCurrent("Storm entity identity unavailable; automatic sequence stopped")
            else sequence.evidenceUnavailable("Storm entity identity unavailable; automatic crush confirmation is unavailable")
            return
        }
        val box = storm.boundingBox
        val stormBounds = CrushBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
        val notice = crushMessages.latest?.takeIf { it.serial > consumedCrushSerial }
        val evidence = linkedMapOf<CrushStage, CrushEvidence>()
        for (value in CrushStage.entries) {
            val calibration = calibrations[value] ?: continue
            val column = observedCrushColumn(calibration) { readBlock(world, it) }
            if (column == null) {
                if (stage == value) sequence.cancelCurrent("${value.name.lowercase()} pillar geometry or chunks unavailable; automatic sequence stopped")
                else sequence.evidenceUnavailable("${value.name.lowercase()} pillar geometry is not currently verifiable")
                continue
            }
            val result = observers[value]?.observe(PillarColumnObservation(column.first, column.second,
                serverBlockRevision[value] ?: 0L, time, storm.uuid.toString(), stormBounds,
                notice?.at, notice?.serial ?: 0L, serverBlockAt[value] ?: time)) ?: continue
            evidence[value] = result
        }
        if (ambiguousCrushEvidence(evidence)) {
            sequence.cancelCurrent("Multiple calibrated pillars overlap current Storm evidence; automatic sequence stopped")
            consumedCrushSerial = notice?.serial ?: consumedCrushSerial
            return
        }
        for ((value, result) in evidence) {
            // Do not open a stale purple view if its first observed descent already confirms the crush.
            if (result.underway && result.confirmationId == null) sequence.underway(value, time)
            result.confirmationId?.let {
                if (stage == value) {
                    sequence.confirmed(value, it, time)
                    consumedCrushSerial = notice?.serial ?: consumedCrushSerial
                } else if (stage == null && value == CrushStage.PURPLE) {
                    sequence.cancelCurrent("Purple was already crushed before a verified visible sequence; waiting for the next encounter")
                    consumedCrushSerial = notice?.serial ?: consumedCrushSerial
                }
            }
        }
    }

    private var leapName: String? = null
    private fun pendingLeapDestination(): LeapDestination? = leapName?.let(::destination)
    private fun destination(name: String): LeapDestination? {
        val teammate = DungeonAPI.teammates.singleOrNull { it.name == name } ?: return null
        val entity = teammate.realPlayer ?: return null
        if (entity.level() !== level || entity.uuid == MC.instance.player?.uuid) return null
        val position = entity.position()
        return LeapDestination(name, entity.uuid.toString(), teammate.dungeonClass == DungeonClass.BERSERKER,
            !teammate.dead && entity.isAlive && !entity.isRemoved, CrushPoint(position.x, position.y, position.z))
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        val text = event.text
        val capturedEpoch = epoch
        MC.instance.execute {
            val time = now(); refreshContext(time)
            if (epoch != capturedEpoch) return@execute
            when {
                text == STORM_START -> {
                    val wasActive = sequence.stormActive
                    sequence.stormStarted(time)
                    if (!wasActive && sequence.stormActive) resetObservations()
                }
                text in STORM_END -> reset("Storm phase ended")
                else -> {
                    if (sequence.stormActive) crushMessages.accept(text, time)
                    successfulLeapName(text)?.let {
                        leapName = it
                        sequence.leapSucceeded(it, time, destination(it))
                    }
                }
            }
        }
    }

    @Subscription
    fun onPacket(event: PacketReceivedEvent) {
        val capturedEpoch = epoch
        when (val packet = event.packet) {
            is ClientboundPlayerPositionPacket -> lastPosition?.let {
                // Read the real final player position at END_CLIENT_TICK, after native packet handling.
                pendingCorrection.set(PositionCorrection(capturedEpoch, packet.id(), it, now()))
            }
            is ClientboundBlockUpdatePacket -> {
                val cell = packet.pos.let { CrushBlockCell(it.x, it.y, it.z) }; val time = now()
                MC.instance.execute { if (epoch == capturedEpoch) serverBlockChanged(cell, time) }
            }
            is ClientboundSectionBlocksUpdatePacket -> {
                val cells = ArrayList<CrushBlockCell>(4096)
                packet.runUpdates { pos, _ -> if (cells.size < 4096) cells.add(CrushBlockCell(pos.x, pos.y, pos.z)) }
                val time = now()
                MC.instance.execute { if (epoch == capturedEpoch) cells.forEach { serverBlockChanged(it, time) } }
            }
        }
    }

    private fun serverBlockChanged(cell: CrushBlockCell, time: Long) {
        if (!sequence.stormActive) return
        calibrations.forEach { (value, calibration) -> if (calibration.contains(cell)) {
            serverBlockRevision[value] = (serverBlockRevision[value] ?: 0L) + 1L
            serverBlockAt[value] = time
        } }
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun encounterChanged() { MC.instance.execute { reset("Left the encounter") } }

    fun capturePillar(stage: String): Boolean = runCatching { CrushStage.valueOf(stage.uppercase(java.util.Locale.ROOT)) }
        .getOrNull()?.let(::capturePillar) ?: false

    fun capturePillar(value: CrushStage): Boolean {
        fun fail(reason: String): Boolean { captureStatus = reason; return false }
        refreshContext(now())
        val world = level ?: return fail("No world is loaded")
        if (!realDungeon() || !sequence.stormActive || DungeonAPI.dungeonFloor?.name !in setOf("F7", "M7") ||
            DungeonAPI.ownPlayer?.dungeonClass != DungeonClass.HEALER || MC.instance.player?.isAlive != true)
            return fail("Capture requires a living Healer in a real Hypixel F7/M7 Storm phase; preview worlds cannot authorize automatic activation")
        if (identifiedStorm(world) == null) return fail("A uniquely identified loaded Storm Wither is required")
        val hit = (MC.instance.hitResult as? BlockHitResult)?.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }
            ?: return fail("Look directly at the actual hanging diorite pillar you have verified as ${value.name.lowercase()}")
        val context = CameraViewpoints.context() ?: return fail("World identity unavailable")
        val geometry = captureCrushPillar(value, context, hit.blockPos.let { CrushBlockCell(it.x, it.y, it.z) }) { readBlock(world, it) }
            ?: return fail("No complete loaded hanging diorite column was observed. Flat pads, unknown materials, and missing chunks are rejected")
        val other = CrushPillarStore.get(context, if (value == CrushStage.PURPLE) CrushStage.YELLOW else CrushStage.PURPLE)
        if (other != null && geometry.bounds(geometry.bottom, geometry.top).intersects(other.bounds(other.bottom, other.top)))
            return fail("Purple and yellow pillars must be distinct")
        CrushPillarStore.put(geometry)
        captureStatus = "Measured pillar saved with your ${value.name.lowercase()} label; descent/collision and server crush confirmation remain required"
        return true
    }

    private fun identifiedStorm(world: ClientLevel): WitherBoss? = world.entitiesForRendering().filterIsInstance<WitherBoss>()
        .filter { it.isAlive && !it.isRemoved && (listOfNotNull(it.customName?.string) + it.getStrippedAttachedLines())
            .any { label -> label.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "").trim() == "Storm" } }.singleOrNull()

    private fun readBlock(world: ClientLevel, cell: CrushBlockCell): ObservedCrushBlock? {
        val pos = BlockPos(cell.x, cell.y, cell.z)
        if (!world.isInWorldBounds(pos) || !world.hasChunk(cell.x shr 4, cell.z shr 4)) return null
        val block = world.getBlockState(pos)
        return ObservedCrushBlock(BuiltInRegistries.BLOCK.getKey(block.block).toString(), Block.isShapeFullBlock(block.getCollisionShape(world, pos)))
    }

    private fun resetObservations() {
        observers.clear(); calibrations.forEach { (value, calibration) -> observers[value] = CrushPillarObserver(calibration) }
        crushMessages.reset(); consumedCrushSerial = 0L; serverBlockRevision.clear(); serverBlockAt.clear(); nextObservation = 0L
    }
    private fun reset(reason: String) {
        epoch++; sequence.reset(reason); resetObservations(); leapName = null; pendingCorrection.set(null); lastPosition = null
    }
}
