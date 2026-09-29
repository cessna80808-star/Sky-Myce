package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.api.events.DungeonChestOpenEvent
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ItemUtils.getPrice
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyIn
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonEnterEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.party.DungeonPartyFinderQueueEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

object DungeonTracker : SkyMyceModule() {
    val saveFile = SkyMyce.configPath.resolve("dungeon_tracker.json").toFile()
    val profitData = mutableMapOf<DungeonFloor, FloorTracker>()
    var revision = 0L
        private set
    var storageStatus = "Loading all-time totals…"
        private set
    private var loaded = false
    private var writable = true
    private lateinit var writer: StateWriter<JsonElement>
    private val queued = mutableListOf<() -> Unit>()

    override fun init() {
        val file = AtomicJsonFile(saveFile.toPath(), 16 * 1024 * 1024)
        writer = StateWriter({ file.write(it) }, { work -> Scheduling.schedule(500.milliseconds) { work() } }, {
            SkyMyce.logger.warn("Could not save dungeon totals; previous file retained")
            MC.instance.execute { storageStatus = "Save failed • previous totals retained"; revision++ }
        })
        Scheduling.schedule(0.milliseconds) {
            val parsed = runCatching { readDungeonTotals(file) }
            MC.instance.execute {
                writable = parsed.isSuccess && !file.damaged
                if (writable) profitData.putAll(parsed.getOrThrow())
                loaded = true; storageStatus = if (writable) "All-time totals • legacy records have no account or date" else "Could not load or repair totals • original preserved"
                val work = queued.toList(); queued.clear(); if (writable) work.forEach { it() }; revision++
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Hawk-Dungeon-Save"))
    }
    private fun aggregate(action: () -> Unit) {
        if (!loaded) { if (queued.size < 200) queued += action; return }
        if (!writable) return
        action(); save()
    }
    fun save() {
        if (!loaded || !writable) return
        val json = dungeonTotalsCodec.encodeStart(JsonOps.INSTANCE, profitData).result().orElse(null) ?: return
        revision++; writer.submit(json)
    }

    private val floorRegex = Regex("^\\s*((?:Master Mode )?The Catacombs - (?:Floor ([IVX]+)|Entrance))$")
    private val xpRegex = Regex("^\\s*\\+([\\d,.]+) (.+) Experience(?: \\(Team Bonus\\))?$")
    private var session = UUID.randomUUID().toString()
    private var run: DungeonRunRecord? = null
    private var runStarted: TimeMark? = null
    var currentFloor: DungeonFloor? = null
        private set
    private data class PendingChest(val event: DungeonChestOpenEvent, val context: AcquisitionContext, val baseline: Map<String, Int>, val time: Long)
    private data class PendingReroll(val floor: DungeonFloor, val chest: DungeonChest, val context: AcquisitionContext, val count: Int, val time: Long, val price: Double?)
    private var pendingChest: PendingChest? = null
    private var pendingReroll: PendingReroll? = null

    @Subscription fun onDungeonQueue(event: DungeonPartyFinderQueueEvent) { currentFloor = event.floor }
    @Subscription fun onDungeonEnter(event: DungeonEnterEvent) {
        currentFloor = event.floor; run = null; runStarted = null; pendingChest = null; pendingReroll = null
    }
    @Subscription fun onDungeonStart(event: DungeonStartEvent) {
        val context = AcquisitionRepository.currentContext() ?: return
        val floor = DungeonAPI.dungeonFloor ?: currentFloor ?: return
        currentFloor = floor
        runStarted = TimeSource.Monotonic.markNow()
        run = DungeonRunRecord(UUID.randomUUID().toString(), session, context, floor.name, startedAt = System.currentTimeMillis())
        if (DungeonTrackerConfig.dungeonTracker) AcquisitionRepository.run(run!!)
    }
    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class) fun resetContext() {
        run = null; runStarted = null; session = UUID.randomUUID().toString(); currentFloor = null; pendingChest = null; pendingReroll = null
    }

    @Subscription @OnlyIn(SkyBlockIsland.THE_CATACOMBS)
    fun onChatMessage(event: ChatReceivedEvent.Pre) {
        if (!DungeonTrackerConfig.dungeonTracker) return
        val active = run?.takeIf { it.context?.matches(AcquisitionRepository.currentContext()) == true } ?: return
        val floor = DungeonFloor.entries.find { it.name == active.floor } ?: return
        val now = System.currentTimeMillis()
        if (floorRegex.matches(event.text) && active.completedAt == null) {
            val duration = dungeonRunDuration(runStarted)
            runStarted = null
            run = active.copy(completedAt = now, duration = duration)
            AcquisitionRepository.run(run!!)
            aggregate { profitData.getOrPut(floor) { FloorTracker() }.recordRun(duration) }
        }
        xpRegex.matchEntire(event.text)?.let { match ->
            val amount = match.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1e12 } ?: return
            val current = run ?: return
            val type = match.groupValues[2]
            if (type == "Catacombs" && current.cataXp == null) {
                run = current.copy(cataXp = amount)
                aggregate { profitData.getOrPut(floor) { FloorTracker() }.totalXp += amount }
            } else {
                val clazz = DungeonClass.getByName(type) ?: return
                if (clazz.name in current.classXp) return
                run = current.copy(classXp = current.classXp + (clazz.name to amount))
                aggregate { val data = profitData.getOrPut(floor) { FloorTracker() }; data.classXp[clazz] = (data.classXp[clazz] ?: 0.0) + amount }
            }
            AcquisitionRepository.run(run!!)
        }
    }

    @Subscription fun onChestReroll(event: DungeonChestRerollEvent) {
        if (!DungeonTrackerConfig.dungeonTracker) return
        val context = AcquisitionRepository.currentContext() ?: return
        pendingReroll = PendingReroll(event.floor, event.chest, context, DigestRng.inventoryCounts()["KISMET_FEATHER"] ?: 0,
            System.currentTimeMillis(), price(SkyBlockId.item("KISMET_FEATHER")))
    }
    @Subscription fun onChestOpen(event: DungeonChestOpenEvent) {
        if (!DungeonTrackerConfig.dungeonTracker || !event.costKnown || event.cost < 0 || event.contents.size > 64 || event.contents.values.any { it !in 1..100000 }) return
        val context = AcquisitionRepository.currentContext() ?: return
        currentFloor = event.floor
        pendingChest = PendingChest(event, context, DigestRng.inventoryCounts(), System.currentTimeMillis())
    }
    fun awaitsInventory(): Boolean {
        val now = System.currentTimeMillis()
        if (pendingChest?.let { now - it.time !in 0..10000 } == true) pendingChest = null
        if (pendingReroll?.let { now - it.time !in 0..10000 } == true) pendingReroll = null
        return pendingChest != null || pendingReroll != null
    }
    /** Called by the Digest's existing coalesced inventory observer, before its rare-drop detector. */
    fun inventoryObserved(counts: Map<String, Int>, now: Long, observation: String) {
        if (!awaitsInventory() || !DungeonTrackerConfig.dungeonTracker) return
        val context = AcquisitionRepository.currentContext() ?: return
        pendingReroll?.takeIf { it.context.matches(context) && (counts["KISMET_FEATHER"] ?: 0) < it.count }?.let { pending ->
            pendingReroll = null
            val current = receiptRun(pending.floor, context)
            run = current.copy(rerolls = current.rerolls + 1, rerollCost = if (pending.price == null || current.rerolls > 0 && current.rerollCost == null) null else (current.rerollCost ?: 0.0) + pending.price)
            AcquisitionRepository.run(run!!)
            aggregate { val chest = profitData.getOrPut(pending.floor) { FloorTracker() }.chests.getOrPut(pending.chest) { ChestTracker() }; chest.rerollCount++; chest.rerollCost += pending.price ?: 0.0 }
        }
        pendingChest?.takeIf { it.context.matches(context) }?.let { pending ->
            val event = pending.event
            val receivedIds = receivedChestItems(event.contents.mapKeys { canonicalItemId(it.key.id) }, pending.baseline, counts)
            val received = event.contents.filterKeys { canonicalItemId(it.id) in receivedIds }
            if (received.isEmpty()) return
            pendingChest = null
            val current = receiptRun(event.floor, context)
            val receipt = ChestReceipt(observation, now, event.chest.name, event.cost.toDouble(), event.contents.map { (id, count) -> RunLoot(canonicalItemId(id.id), count, price(id)?.times(count)) })
            run = current.copy(chests = current.chests + receipt); AcquisitionRepository.run(run!!)
            received.forEach { (id, count) ->
                val item = canonicalItemId(id.id)
                if (DigestRngCatalog.valid(item, "dungeon")) AcquisitionRepository.record(AcquisitionEvent(acquisitionId("$observation/$item"),
                    item, now, "dungeon", context, event.floor.name, event.chest.name, quantity = count,
                    value = price(id)?.times(count), chestCost = event.cost.toDouble(), run = current.id, player = MC.instance.user.name))
            }
            aggregate { profitData.getOrPut(event.floor) { FloorTracker() }.chests.getOrPut(event.chest) { ChestTracker() }.trackChest(event.contents, event.cost) }
        }
    }
    private fun receiptRun(floor: DungeonFloor, context: AcquisitionContext): DungeonRunRecord = run?.takeIf {
        LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && it.floor == floor.name && it.context?.matches(context) == true
    } ?: DungeonRunRecord(UUID.randomUUID().toString(), session, context, floor.name)
    private fun price(id: SkyBlockId): Double? = id.getPrice(DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType).takeIf { it.isFinite() && it > 0 }
}
