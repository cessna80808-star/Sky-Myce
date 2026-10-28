package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.api.dungeonRewardHeader
import me.mycellium.skymyce.api.dungeonStoredReward
import me.mycellium.skymyce.api.events.DungeonChestOpenEvent
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendProfileProvider
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel
import me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import me.mycellium.skymyce.utils.observedItemMetadata
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonEnterEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.party.DungeonPartyFinderQueueEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling

private val dungeonCompletionPattern = Regex("^\\s*(The|Master Mode(?: The)?) Catacombs - (?:Floor (I|II|III|IV|V|VI|VII)|Entrance)\\s*$")

/** The Master-mode result header omits 'The', unlike the API's long floor names. */
internal fun dungeonCompletionFloor(text: String): DungeonFloor? {
    if (text.length !in 1..120 || '\n' in text || '\r' in text) return null
    val match = dungeonCompletionPattern.matchEntire(text) ?: return null
    val master = match.groupValues[1] != "The"
    val roman = match.groupValues[2]
    if (roman.isEmpty()) return DungeonFloor.E.takeUnless { master }
    val number = listOf("I", "II", "III", "IV", "V", "VI", "VII").indexOf(roman) + 1
    return DungeonFloor.getByName("${if (master) "M" else "F"}$number")
}

object DungeonTracker : SkyMyceModule() {
    private val saveFile = SkyMyce.configPath.resolve("dungeon_tracker.json")
    /** Frozen historical reference; all post-cutover measurements live in acquisitions.json. */
    val profitData = mutableMapOf<DungeonFloor, FloorTracker>()
    var revision = 0L
        private set
    var storageStatus = "Loading historical totals…"
        private set

    override fun init() {
        val file = AtomicJsonFile(saveFile, 16 * 1024 * 1024)
        Scheduling.schedule(0.milliseconds) {
            val parsed = runCatching { readDungeonTotals(file) }
            parsed.exceptionOrNull()?.let { SkyMyce.logger.warn("Could not read historical dungeon totals; original preserved", it) }
            MC.instance.execute {
                if (parsed.isSuccess && !file.damaged) profitData.putAll(parsed.getOrThrow())
                storageStatus = if (parsed.isSuccess && !file.damaged) "Historical totals • frozen at cutover; no account or date" else "Historical totals unreadable • original preserved"
                revision++
            }
        }
    }

    private val xpRegex = Regex("^\\s*\\+([\\d,.]+) (.+) Experience(?: \\(Team Bonus\\))?$")
    private var session = UUID.randomUUID().toString()
    private var run: DungeonRunRecord? = null
    private var runStarted: TimeMark? = null
    var currentFloor: DungeonFloor? = null
        private set
    private data class PendingChest(val event: DungeonChestOpenEvent, val record: DungeonRunRecord,
        val world: Any, val state: PendingChestObservation, val metadata: Map<String, ObservedItemMetadata> = emptyMap())
    private data class PendingReroll(val id: String, val record: DungeonRunRecord, val context: AcquisitionContext,
        val world: Any, val count: Int, val started: Long, val price: Double?, val closed: Long? = null)
    private var pendingChest: PendingChest? = null
    private var pendingReroll: PendingReroll? = null
    private val consumed = linkedSetOf<String>()
    private fun elapsedMillis() = System.nanoTime() / 1_000_000

    @Subscription fun onDungeonQueue(event: DungeonPartyFinderQueueEvent) { currentFloor = event.floor }
    @Subscription fun onDungeonEnter(event: DungeonEnterEvent) {
        currentFloor = event.floor; run = null; runStarted = null; pendingChest = null; pendingReroll = null
    }
    @Subscription fun onDungeonStart(event: DungeonStartEvent) {
        val context = AcquisitionRepository.currentContext() ?: return
        val floor = event.floor
        currentFloor = floor
        pendingChest = null; pendingReroll = null
        runStarted = TimeSource.Monotonic.markNow()
        run = DungeonRunRecord(UUID.randomUUID().toString(), session, context, floor.name, startedAt = System.currentTimeMillis())
        if (DungeonTrackerConfig.dungeonTracker) AcquisitionRepository.run(run!!)
    }
    @Subscription(TickEvent::class) fun recoverStartedRun() {
        if (!DungeonTrackerConfig.dungeonTracker || run != null || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS ||
            !DungeonAPI.started || DungeonAPI.completed) return
        val floor = DungeonAPI.dungeonFloor ?: return
        val context = AcquisitionRepository.currentContext() ?: return
        // The API can observe Mort before its floor/profile data arrives and omit the start event.
        // Recover the observed run without guessing when it started or inventing a duration.
        currentFloor = floor; runStarted = null
        run = DungeonRunRecord(UUID.randomUUID().toString(), session, context, floor.name)
        AcquisitionRepository.run(run!!)
        displayDevMessage { "Dungeon run recovered after context became available; start time unknown" }
    }
    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class, IslandChangeEvent::class) fun resetContext() {
        run = null; runStarted = null; session = UUID.randomUUID().toString(); currentFloor = null
        pendingChest = null; pendingReroll = null; consumed.clear()
    }
    @Subscription(ContainerCloseEvent::class) fun closeChest() {
        val now = elapsedMillis()
        // The shared observer coalesces packets; permit its already-arriving receipt after GUI close.
        pendingChest = pendingChest?.let { it.copy(state = it.state.copy(closed = it.state.closed ?: now)) }
        pendingReroll = pendingReroll?.let { it.copy(closed = it.closed ?: now) }
    }

    @Subscription(receiveCancelled = true)
    fun onChatMessage(event: ChatReceivedEvent.Pre) {
        if (!DungeonTrackerConfig.dungeonTracker) return
        val header = dungeonRewardHeader(event.text)
        if (header != null && awaitsInventory()) {
            pendingChest?.takeIf { it.event.chest == header }?.let {
                pendingChest = it.copy(state = it.state.copy(rewardHeader = true))
                DigestRng.requestInventoryObservation()
                confirmChest(System.currentTimeMillis())
            }
        }
        if (LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return
        val active = run?.takeIf { it.context?.matches(AcquisitionRepository.currentContext()) == true }?.let(::latestRun) ?: return
        run = active
        val now = System.currentTimeMillis()
        if (dungeonCompletionFloor(event.text)?.name == active.floor && active.completedAt == null) {
            val duration = dungeonRunDuration(runStarted)
            runStarted = null
            run = active.copy(completedAt = now, duration = duration)
            AcquisitionRepository.run(run!!)
            me.mycellium.skymyce.community.CommunitySharing.contribute(acquisitionId("run-count/${active.id}"), "dungeon_runs", 1.0, now,
                me.mycellium.skymyce.community.SharingCategory.DUNGEONS)
            duration?.let {
                me.mycellium.skymyce.community.CommunitySharing.contribute(acquisitionId("run-time/${active.id}"), "dungeon_run_time", it.toDouble(), now,
                    me.mycellium.skymyce.community.SharingCategory.DUNGEONS, floor = active.floor)
            }
        }
        xpRegex.matchEntire(event.text)?.let { match ->
            val amount = match.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1e12 } ?: return
            val current = run ?: return
            val type = match.groupValues[2]
            if (type == "Catacombs" && current.cataXp == null) run = current.copy(cataXp = amount)
            else {
                val clazz = DungeonClass.getByName(type) ?: return
                if (clazz.name in current.classXp) return
                run = current.copy(classXp = current.classXp + (clazz.name to amount))
            }
            AcquisitionRepository.run(run!!)
        }
    }

    @Subscription(priority = Subscription.LOWEST)
    fun onChestReroll(event: DungeonChestRerollEvent) {
        if (!DungeonTrackerConfig.dungeonTracker || event.observation in consumed || pendingReroll != null && awaitsInventory()) return
        val context = AcquisitionRepository.currentContext() ?: return
        val world = MC.instance.level ?: return
        pendingChest = null
        pendingReroll = PendingReroll(event.observation, receiptRun(event.floor, context), context, world,
            DigestRng.inventoryCounts()["KISMET_FEATHER"] ?: 0, elapsedMillis(), price(SkyBlockId.item("KISMET_FEATHER")))
    }
    @Subscription(priority = Subscription.LOWEST)
    fun onChestOpen(event: DungeonChestOpenEvent) {
        if (!DungeonTrackerConfig.dungeonTracker || event.cost < 0 || event.contents.isEmpty() || event.contents.size > 64 || event.contents.values.any { it !in 1..100000 }) return
        if (event.observation in consumed || pendingChest?.event?.observation == event.observation && awaitsInventory()) return
        val context = AcquisitionRepository.currentContext() ?: return
        val world = MC.instance.level ?: return
        currentFloor = event.floor
        val record = receiptRun(event.floor, context)
        val contents = event.contents.mapKeys { canonicalItemId(it.key.id) }
        if (contents.keys.any { !it.matches(TRACKED_ITEM_ID) }) return
        val stored = event.contents.keys.filter { dungeonStoredReward(it.id, event.metadata[it]?.displayName) }.mapTo(hashSetOf()) { canonicalItemId(it.id) }
        pendingChest = PendingChest(event, record, world, PendingChestObservation(event.observation, context,
            record.id, contents, DigestRng.inventoryCounts(), elapsedMillis(), stored = stored))
        displayDevMessage { "Dungeon receipt awaiting server header/inventory: tier=${event.chest.name}, rewardKinds=${contents.size}, costKnown=${event.costKnown}" }
    }
    fun awaitsInventory(): Boolean {
        val now = elapsedMillis()
        val context = AcquisitionRepository.currentContext()
        if (!DungeonTrackerConfig.dungeonTracker) { pendingChest = null; pendingReroll = null; return false }
        pendingChest?.let { pending ->
            val sameRun = pending.record.startedAt == null || run?.id == pending.record.id
            if (!sameRun || pending.world !== MC.instance.level || !pending.state.active(now, context, pending.record.id)) {
                pendingChest = null
                displayDevMessage { "Dungeon chest observation expired or context changed" }
            }
        }
        pendingReroll?.let { pending ->
            if (pending.world !== MC.instance.level || !pending.context.matches(context) || now - pending.started !in 0..10000 ||
                pending.closed?.let { now - it !in 0..2000 } == true || pending.record.startedAt != null && run?.id != pending.record.id) pendingReroll = null
        }
        return pendingChest != null || pendingReroll != null
    }
    /** Called by the Digest's existing coalesced inventory observer, before its rare-drop detector. */
    fun inventoryObserved(counts: Map<String, Int>, now: Long, observation: String, metadata: Map<String, ObservedItemMetadata> = emptyMap()) {
        if (!awaitsInventory()) return
        pendingReroll?.takeIf { rerollInventoryConfirmed(it.count, counts["KISMET_FEATHER"] ?: 0) }?.let { pending ->
            pendingReroll = null
            if (!rememberReceipt(pending.id)) return@let
            val current = latestRun(pending.record)
            val updated = current.copy(rerolls = current.rerolls + 1, rerollCost =
                if (pending.price == null || current.rerolls > 0 && current.rerollCost == null) null else (current.rerollCost ?: 0.0) + pending.price)
            if (run?.id == updated.id) run = updated
            AcquisitionRepository.run(updated)
            pending.price?.let { price -> me.mycellium.skymyce.community.CommunitySharing.contribute(acquisitionId("reroll-profit/${pending.id}"),
                "dungeon_profit", -price, now, me.mycellium.skymyce.community.SharingCategory.DUNGEONS, verification = "estimated") }
        }
        pendingChest?.let {
            val observed = it.state.inventory(counts, observation)
            val arrived = observed.received.keys - it.state.received.keys
            pendingChest = it.copy(state = observed, metadata = it.metadata + metadata.filterKeys(arrived::contains))
        }
        confirmChest(now)
    }
    private fun confirmChest(now: Long) {
        val pending = pendingChest?.takeIf { it.state.confirmed } ?: return
        pendingChest = null
        if (!rememberReceipt(pending.event.observation)) return
        val event = pending.event
        val current = latestRun(pending.record)
        val receiptId = acquisitionId("chest/${event.observation}")
        if (current.chests.any { it.id == receiptId } || current.chests.size >= 20) return
        val cost = event.cost.takeIf { event.costKnown }?.toDouble()
        val loot = event.contents.map { (id, count) ->
            val itemId = canonicalItemId(id.id)
            val metadata = event.metadata[id]?.let { observedItemMetadata(it.rarity, it.displayName) }
                ?: pending.metadata[itemId]?.let { observedItemMetadata(it.rarity, it.displayName) }
            RunLoot(itemId, count, price(id)?.times(count), metadata?.rarity, metadata?.displayName)
        }
        val updated = current.copy(chests = current.chests + ChestReceipt(receiptId, now, event.chest.name, cost, loot))
        if (run?.id == updated.id) run = updated
        AcquisitionRepository.run(updated)
        if (cost != null && loot.none { it.value == null } && (current.rerolls == 0 || current.rerollCost != null))
            me.mycellium.skymyce.community.CommunitySharing.contribute(acquisitionId("chest-profit/$receiptId"), "dungeon_profit",
                loot.sumOf { it.value!! } - cost, now, me.mycellium.skymyce.community.SharingCategory.DUNGEONS, verification = "estimated")
        loot.filter { DigestRngCatalog.valid(it.item, "dungeon") }.forEach { item ->
            val confirmation = pending.state.received[item.item] ?: "chest/${event.observation}/${item.item}"
            AcquisitionRepository.record(AcquisitionEvent(acquisitionId(confirmation), item.item, now, "dungeon",
                pending.state.context, event.floor.name, event.chest.name, quantity = item.count,
                value = item.value, chestCost = cost, run = current.id, player = MC.instance.user.name,
                valuationAt = now.takeIf { item.value != null }, rarity = item.rarity, displayName = item.displayName))
            // The complete server-header/inventory receipt is sufficient even when Hypixel omits a rare announcement.
            DigestRng.receiptConfirmed(me.mycellium.skymyce.features.digest.DetectedRng(acquisitionId(confirmation), item.item, "dungeon", now, confirmation,
                item.rarity, item.displayName))
        }
        displayDevMessage { "Dungeon chest receipt confirmed: tier=${event.chest.name}, rewardKinds=${loot.size}, costKnown=${cost != null}" }
    }
    private fun latestRun(record: DungeonRunRecord): DungeonRunRecord = AcquisitionRepository.state.runs.firstOrNull { it.id == record.id }
        ?: run?.takeIf { it.id == record.id } ?: record
    internal fun observedRun(context: AcquisitionContext): DungeonRunRecord? = run?.takeIf { it.context?.matches(context) == true }
    private fun receiptRun(floor: DungeonFloor, context: AcquisitionContext): DungeonRunRecord = run?.takeIf {
        LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && it.floor == floor.name && it.context?.matches(context) == true
    } ?: DungeonRunRecord(UUID.randomUUID().toString(), session, context, floor.name)
    private fun price(id: SkyBlockId): Double? = id.getPriceOrNull(DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType)
        ?.takeIf { it.isFinite() && it in 0.0..1e12 }
    private fun rememberReceipt(id: String): Boolean {
        if (!consumed.add(id)) return false
        if (consumed.size > 256) consumed.remove(consumed.first())
        return true
    }
    fun confirmedView(floor: DungeonFloor?): TrackerView = trackerView(AcquisitionRepository.state, legacyTrackerView(),
        TrackerFilter(floor = floor?.name), AcquisitionRepository.currentContext(), includeDetails = false)
}

data class FloorTracker(
    var totalRuns: Int = 0,
    var totalTimeMillis: Long = 0L,
    var totalXp: Double = 0.0,
    var classXp: MutableMap<DungeonClass, Double> = mutableMapOf(),
    var chests: MutableMap<DungeonChest, ChestTracker> = mutableMapOf(),
    // -1 preserves the old format's assumption that all runs contributed to a valid total.
    var timedRuns: Int = -1,
    var incompleteCosts: Boolean = false,
) {
    val invalidTime: Boolean get() = totalTimeMillis < 0 || totalTimeMillis > totalRuns.coerceAtLeast(0).toLong() * MAX_DUNGEON_RUN_MILLIS
    val timedRunCount: Int get() = if (invalidTime || totalTimeMillis == 0L) 0 else
        (if (timedRuns < 0) totalRuns else timedRuns).coerceIn(0, totalRuns.coerceAtLeast(0))
    val incompleteTime: Boolean get() = timedRunCount < totalRuns
    val averageRunMillis: Double? get() = safeAverage(totalTimeMillis.toDouble(), timedRunCount.toLong())

    fun recordRun(duration: Long?) {
        val previousTimed = timedRunCount
        totalRuns++
        timedRuns = previousTimed
        if (duration != null && duration in 1..MAX_DUNGEON_RUN_MILLIS) {
            totalTimeMillis += duration
            timedRuns++
        }
    }

    fun hourlyRate(value: Double): Double? = if (!incompleteTime && totalTimeHours > 0 && value.isFinite())
        (value / totalTimeHours).takeIf(Double::isFinite) else null

    val totalChestsCost: Double
        get() = chests.values.sumOf { it.chestCost }

    val totalRerolled: Int
        get() = chests.values.sumOf { it.rerollCount }

    val totalRerolledCost: Double
        get() = chests.values.sumOf { it.rerollCost }

    val netProfit: Double
        get() = chests.values.sumOf { it.netProfit }

    val totalTimeHours: Double
        get() = totalTimeMillis.milliseconds.toDouble(DurationUnit.HOURS)

    companion object {
        val CODEC: Codec<FloorTracker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("totalRuns").forGetter(FloorTracker::totalRuns),
                Codec.LONG.fieldOf("totalTimeMillis").forGetter(FloorTracker::totalTimeMillis),
                Codec.DOUBLE.fieldOf("totalXp").forGetter(FloorTracker::totalXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonClass.valueOf(it) }, { it.name }),
                    Codec.DOUBLE
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("classXp").forGetter(FloorTracker::classXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonChest.valueOf(it) }, { it.name }),
                    ChestTracker.CODEC
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("chests").forGetter(FloorTracker::chests),
                Codec.INT.optionalFieldOf("timedRuns", -1).forGetter(FloorTracker::timedRuns),
                Codec.BOOL.optionalFieldOf("incompleteCosts", false).forGetter(FloorTracker::incompleteCosts),
            ).apply(instance, ::FloorTracker)
        }
    }
}

internal const val MAX_DUNGEON_RUN_MILLIS = 86_400_000L
internal fun dungeonRunDuration(started: TimeMark?): Long? = started?.elapsedNow()?.inWholeMilliseconds
    ?.takeIf { it in 1..MAX_DUNGEON_RUN_MILLIS }

internal val dungeonTotalsCodec: Codec<MutableMap<DungeonFloor, FloorTracker>> = Codec.unboundedMap(
    Codec.STRING.xmap({ DungeonFloor.valueOf(it) }, { it.name }), FloorTracker.CODEC).xmap({ it.toMutableMap() }, { it })

/** Background-thread load. Preserve the exact original before replacing only impossible duration totals. */
internal fun readDungeonTotals(file: AtomicJsonFile): MutableMap<DungeonFloor, FloorTracker> {
    val json = file.read()
    check(!file.damaged) { "Dungeon totals unreadable" }
    if (json == null) return mutableMapOf()
    val totals = dungeonTotalsCodec.parse(JsonOps.INSTANCE, json).result().orElseThrow()
    runCatching { file.preserveOriginal("before-acquisitions") }
    if (totals.values.any { it.invalidTime }) {
        file.preserveOriginal("before-time-repair")
        val repaired = json.deepCopy().asJsonObject
        totals.filterValues { it.invalidTime }.forEach { (floor, data) ->
            data.totalTimeMillis = 0; data.timedRuns = 0
            repaired.getAsJsonObject(floor.name).apply { addProperty("totalTimeMillis", 0); addProperty("timedRuns", 0) }
        }
        file.write(repaired)
    }
    return totals
}

data class TrackerSummary(val runs: Long = 0, val time: Long = 0, val timedRuns: Long = 0,
    val gross: Double = 0.0, val chestCost: Double = 0.0, val rerollCost: Double? = 0.0,
    val cataXp: Double = 0.0, val classXp: Map<String, Double> = emptyMap(), val chests: Int = 0,
    val rerolls: Int = 0, val incompleteValues: Boolean = false, val incompleteCosts: Boolean = false,
    val knownRerollCost: Double = rerollCost ?: 0.0, val preservedProfit: Double = 0.0,
    val profitTimingComplete: Boolean = true, val xpTimingComplete: Boolean = true) {
    val average get() = safeAverage(time.toDouble(), timedRuns)
    val completeTiming get() = time > 0 && timedRuns == runs
    val profitIncomplete get() = incompleteValues || incompleteCosts || rerollCost == null
    val knownNet get() = gross - chestCost - knownRerollCost + preservedProfit
    val net get() = knownNet.takeUnless { profitIncomplete }
    fun hourlyRate(value: Double): Double? = if (completeTiming && value.isFinite())
        (value * 3600000 / time).takeIf(Double::isFinite) else null
}
data class LegacyFloorView(val floor: String, val summary: TrackerSummary, val loot: Map<String, List<TrackerLootRow>>,
    val chestCosts: Map<String, Double>, val rerollCosts: Map<String, Double>, val chestCounts: Map<String, Int>)
data class TrackerView(val summary: TrackerSummary = TrackerSummary(), val loot: List<TrackerLootRow> = emptyList(),
    val events: List<AcquisitionEvent> = emptyList(), val usingLegacy: Boolean = false,
    val inheritedProfit: Double = 0.0, val trackedLifetimeProfit: Double? = 0.0,
    val historicalRuns: Long = 0, val historicalChests: Int = 0,
    val undatedLoot: Map<String, Long> = emptyMap()) {
    val lifetimeProfit get() = trackedLifetimeProfit?.let { inheritedProfit + it }
}

/** Market presentation replaces frozen recorded values; it never changes the saved profit ledger. */
internal fun marketValuedTrackerSummary(recorded: TrackerSummary, marketLoot: List<TrackerLootRow>) = recorded.copy(
    gross = marketLoot.sumOf { it.value ?: 0.0 }, incompleteValues = marketLoot.any { it.value == null }, preservedProfit = 0.0)

/** Incomplete historical timing cannot assign its loot or XP to the runs whose durations survived. */
internal fun dungeonTrackerRateSample(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, floor: String?): TrackerView {
    val filter = TrackerFilter(floor = floor, scope = TrackerScope.ALL_LOCAL_PROFILES)
    val runs = filter.runs(archive.runs, null).filter { it.completedAt != null && it.duration != null && it.duration in 1..MAX_DUNGEON_RUN_MILLIS }
        .distinctBy { it.id }
    val historical = legacy.filter { filter.floorMatches(it.floor) && it.summary.completeTiming }
    val sample = trackerView(AcquisitionArchive(runs = runs), historical, filter, null)
    return sample.copy(summary = sample.summary.copy(xpTimingComplete = runs.all { it.cataXp != null }))
}

/** A saved paid receipt can outlive its journal write; this projection is never persisted or published. */
private fun trackerHistoryEvents(archive: AcquisitionArchive, filter: TrackerFilter, context: AcquisitionContext?): List<AcquisitionEvent> {
    val linked = filter.copy(query = "", rareOnly = false, valuableOnly = false).events(archive.events, context, archive.runs)
        .filter { it.run != null && it.activity == "dungeon" }.groupBy { it.run }
    val additions = mutableListOf<AcquisitionEvent>()
    // Old receipts can overlap unlinked imported reports; their provenance cannot establish a second drop.
    archive.runs.filter { it.trackingVersion == 2 }.forEach { run ->
        val covered = linked[run.id].orEmpty().filter { event ->
            (event.floor == null || event.floor == run.floor) &&
                (event.context == run.context || run.context?.matches(event.context) == true)
        }.groupBy { it.item to it.chest }.mapValues { (_, events) -> events.sumOf { it.quantity.toLong() } }.toMutableMap()
        run.chests.sortedWith(compareBy<ChestReceipt> { it.time }.thenBy { it.id }).forEach { chest ->
            chest.loot.forEachIndexed { index, loot ->
                if (DigestRngParser.catalog[loot.item]?.activity != "dungeon") return@forEachIndexed
                var remaining = loot.count.toLong()
                for (tier in listOf(chest.chest, null)) {
                    val key = loot.item to tier
                    val consumed = minOf(remaining, covered[key] ?: 0L)
                    remaining -= consumed; covered[key] = (covered[key] ?: 0L) - consumed
                }
                if (remaining > 0) additions += AcquisitionEvent(
                    acquisitionId("tracker-history/${run.id}/${chest.id}/$index/${loot.item}"), loot.item, chest.time, "dungeon",
                    run.context, run.floor, chest.chest, remaining.toInt(), loot.value?.times(remaining.toDouble() / loot.count),
                    chestCost = chest.cost, run = run.id, rarity = loot.rarity, displayName = loot.displayName)
            }
        }
    }
    return archive.events + additions
}

/** Runs only when data or filters change, on the background scheduler. */
fun trackerView(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, filter: TrackerFilter, context: AcquisitionContext?, includeDetails: Boolean = true): TrackerView {
    val legacyMode = filter.scope == TrackerScope.UNSCOPED_LEGACY && !filter.needsRecords
    val history = if (includeDetails && filter.page in listOf(TrackerPage.TIMELINE, TrackerPage.MUSEUM))
        trackerHistoryEvents(archive, filter, context) else archive.events
    val events = if (includeDetails) filter.events(history, context, archive.runs) else emptyList()
    val inheritedProfit = archive.legacyProfit.filterKeys(filter::floorMatches).values.sum()
    val trackedLifetimeProfit = summarizeRuns(archive.runs.filter { it.trackingVersion == 2 && filter.floorMatches(it.floor) }, null, false).first.net
    val floors = legacy.filter { filter.floorMatches(it.floor) }
    val historicalRuns = floors.sumOf { it.summary.runs }
    val historicalChests = floors.sumOf { it.chestCounts.values.sum() }
    fun sampledLoot(rows: List<TrackerLootRow>): List<TrackerLootRow> {
        if (filter.scope == TrackerScope.CURRENT_PROFILE || filter.needsRecords || filter.chest != null) return rows
        val samples = archive.personalRateSamples.filter { filter.floorMatches(it.floor) }
        return rows.map { row -> row.copy(rateSample = samples.singleOrNull { row.item in it.itemCounts }) }
    }
    fun undatedLoot(rows: List<TrackerLootRow>): Map<String, Long> {
        if (!includeDetails || filter.page !in listOf(TrackerPage.TIMELINE, TrackerPage.MUSEUM) || filter.needsRecords ||
            filter.scope == TrackerScope.CURRENT_PROFILE || filter.activity != null && filter.activity != "dungeon") return emptyMap()
        // Hidden prices or a paged timeline do not erase a drop's recorded date.
        val dated = filter.copy(query = "", rareOnly = false, valuableOnly = false, activity = "dungeon")
            .events(history, context, archive.runs).groupBy { it.item }.mapValues { (_, events) -> events.sumOf { it.quantity.toLong() } }
        return rows.filter { DigestRngParser.catalog[it.item]?.activity == "dungeon" }
            .associate { it.item to (it.count - (dated[it.item] ?: 0)).coerceAtLeast(0) }.filterValues { it > 0 }
    }
    if (legacyMode) {
        val (summary, loot) = summarizeLegacy(floors, filter.chest)
        val shownLoot = sortedLoot(sampledLoot(loot), filter)
        return TrackerView(summary,
            shownLoot, events, true, inheritedProfit, trackedLifetimeProfit, historicalRuns, historicalChests, undatedLoot(shownLoot))
    }
    val runs = filter.runs(archive.runs, context)
    val (recordedSummary, loot) = summarizeRuns(runs, filter.chest, includeDetails)
    val selectedSummary = recordedSummary.copy(incompleteCosts = recordedSummary.incompleteCosts || runs.any { run ->
        run.trackingVersion == 1 && floors.any { it.floor == run.floor && it.summary.incompleteCosts }
    })
    val combined = filter.page in listOf(TrackerPage.OVERVIEW, TrackerPage.LOOT, TrackerPage.MUSEUM, TrackerPage.TIMELINE) &&
        filter.scope == TrackerScope.ALL_LOCAL_PROFILES && !filter.needsRecords
    val summary = if (combined)
        dungeonTrackerLifetimeSummary(archive, legacy, filter.floor, filter.mode, filter.chest) else selectedSummary
    val savedLoot = if (combined && includeDetails) {
        (summarizeLegacy(floors, filter.chest).second + loot).groupBy { it.item }.map { (item, rows) ->
            TrackerLootRow(item, rows.sumOf { it.count }, if (rows.any { it.value == null }) null else rows.sumOf { it.value!! },
                if (rows.any { it.acquiredChests == null }) null else rows.sumOf { it.acquiredChests!! }, rows.sumOf { it.sampledChests })
        }
    } else loot
    val shownLoot = sortedLoot(sampledLoot(savedLoot), filter)
    return TrackerView(summary, shownLoot, events, false, inheritedProfit, trackedLifetimeProfit, historicalRuns, historicalChests, undatedLoot(shownLoot))
}

/** Frozen aggregates and v2 receipts describe separate periods; v1 receipts already belong to the aggregates. */
fun dungeonTrackerLifetimeSummary(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, floor: String? = null,
    mode: String? = null, chest: String? = null): TrackerSummary {
    val filter = TrackerFilter(floor = floor, mode = mode, chest = chest, scope = TrackerScope.ALL_LOCAL_PROFILES)
    val floors = legacy.filter { filter.floorMatches(it.floor) }
    val historical = summarizeLegacy(floors, chest).first
    val current = summarizeRuns(filter.runs(archive.runs, null), chest, false).first
    val preserved = if (chest == null) {
        archive.legacyProfit.filterKeys(filter::floorMatches).values.sum() +
            floors.filter { it.floor !in archive.legacyProfit }.sumOf { summarizeLegacy(listOf(it), null).first.knownNet }
    } else historical.knownNet
    val missingHistorical = chest == null && archive.legacyProfit.keys.any { filter.floorMatches(it) && floors.none { floor -> floor.floor == it } }
    return TrackerSummary(historical.runs + current.runs, historical.time + current.time, historical.timedRuns + current.timedRuns,
        historical.gross + current.gross, historical.chestCost + current.chestCost,
        if (historical.rerollCost == null || current.rerollCost == null) null else historical.rerollCost + current.rerollCost,
        historical.cataXp + current.cataXp, mergeXp(listOf(historical.classXp, current.classXp)),
        historical.chests + current.chests, historical.rerolls + current.rerolls,
        historical.incompleteValues || current.incompleteValues, historical.incompleteCosts || current.incompleteCosts,
        historical.knownRerollCost + current.knownRerollCost, preserved - historical.knownNet,
        !missingHistorical && historical.profitTimingComplete && current.profitTimingComplete,
        historical.xpTimingComplete && current.xpTimingComplete)
}

private fun summarizeLegacy(floors: List<LegacyFloorView>, chest: String?): Pair<TrackerSummary, List<TrackerLootRow>> {
    val selected = floors.flatMap { floor -> floor.loot.filterKeys { chest == null || chest == it }.values.flatten() }
    val denominator = floors.sumOf { floor -> floor.chestCounts.filterKeys { chest == null || chest == it }.values.sum() }
    val loot = selected.groupBy { it.item }.map { (item, rows) -> TrackerLootRow(item, rows.sumOf { it.count },
        rows.sumOf { it.value ?: 0.0 }, null, denominator) }
    val summaries = floors.map { it.summary }
    return TrackerSummary(summaries.sumOf { it.runs }, summaries.sumOf { it.time }, summaries.sumOf { it.timedRuns },
        loot.sumOf { it.value ?: 0.0 }, floors.sumOf { floor -> floor.chestCosts.filterKeys { chest == null || chest == it }.values.sum() },
        floors.sumOf { floor -> floor.rerollCosts.filterKeys { chest == null || chest == it }.values.sum() },
        summaries.sumOf { it.cataXp }, mergeXp(summaries.map { it.classXp }), denominator, summaries.sumOf { it.rerolls },
        incompleteCosts = summaries.any { it.incompleteCosts }) to loot
}

private fun summarizeRuns(runs: List<DungeonRunRecord>, chest: String?, includeLoot: Boolean): Pair<TrackerSummary, List<TrackerLootRow>> {
    val receipts = runs.flatMap { it.chests }.filter { chest == null || chest == it.chest }
    val loot = if (includeLoot) receipts.flatMap { chest -> chest.loot.map { it to chest.id } }.groupBy { it.first.item }.map { (item, entries) ->
        TrackerLootRow(item, entries.sumOf { it.first.count.toLong() }, if (entries.any { it.first.value == null }) null else entries.sumOf { it.first.value!! }, entries.map { it.second }.distinct().size, receipts.size)
    } else emptyList()
    val timed = runs.filter { it.completedAt != null && it.duration != null && it.duration > 0 }
    fun DungeonRunRecord.timed() = completedAt != null && duration != null && duration > 0
    val rerollsKnown = runs.none { (it.rerolls > 0 || (it.rerollCost ?: 0.0) > 0) && (chest != null || it.rerollCost == null) }
    val knownRerollCost = if (chest == null) runs.sumOf { it.rerollCost ?: 0.0 } else 0.0
    return TrackerSummary(runs.count { it.completedAt != null }.toLong(), timed.sumOf { it.duration!! }, timed.size.toLong(),
        receipts.sumOf { receipt -> receipt.loot.sumOf { it.value ?: 0.0 } }, receipts.sumOf { it.cost ?: 0.0 }, if (rerollsKnown) knownRerollCost else null,
        runs.sumOf { it.cataXp ?: 0.0 }, mergeXp(runs.map { it.classXp }), receipts.size, runs.sumOf { it.rerolls }, receipts.any { receipt -> receipt.loot.any { it.value == null } }, receipts.any { it.cost == null },
        knownRerollCost = knownRerollCost,
        profitTimingComplete = runs.none { run -> !run.timed() && (run.rerolls > 0 || (run.rerollCost ?: 0.0) > 0 || run.chests.any { chest == null || chest == it.chest }) },
        xpTimingComplete = runs.none { !it.timed() && (it.cataXp ?: 0.0) > 0 }) to loot
}
private fun mergeXp(values: List<Map<String, Double>>) = values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

/** Captured on the client thread; immutable primitives are safe for background queries. */
fun legacyTrackerView(totals: Map<DungeonFloor, FloorTracker> = DungeonTracker.profitData): List<LegacyFloorView> = totals.map { (floor, data) ->
    fun Double.safe() = takeIf { it.isFinite() && it >= 0 } ?: 0.0
    LegacyFloorView(floor.name, TrackerSummary(data.totalRuns.coerceAtLeast(0).toLong(), if (data.invalidTime) 0 else data.totalTimeMillis,
        data.timedRunCount.toLong(), cataXp = data.totalXp.safe(),
        classXp = data.classXp.mapKeys { it.key.name }.mapValues { it.value.safe() }, rerolls = data.totalRerolled.coerceAtLeast(0),
        incompleteCosts = data.incompleteCosts),
        data.chests.mapKeys { it.key.name }.mapValues { (_, chest) -> chest.trackedItems.map { (id, loot) ->
            TrackerLootRow(canonicalItemId(id.id), loot.count.coerceAtLeast(0).toLong(), loot.value.safe(), null, chest.chestCount.coerceAtLeast(0)) } },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.rerollCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCount.coerceAtLeast(0) })
}

/** What the levels panel shows on the right of each bar. */
enum class LevelMetric(val title: String, val header: String) {
    RUNS("Runs needed", "Runs to"), XP("XP needed", "XP to"), PERCENT("Percent", "% of");
    override fun toString() = title
}

/**
 * One Catacombs or class row toward [target]. [runs] is null without an experience-per-run figure; [estimatedRate]
 * marks a class whose rate was borrowed from Catacombs because it has no tracked runs of its own.
 */
data class LevelRow(val name: String, val dungeonClass: DungeonClass?, val xp: Double, val level: Int, val target: Int,
                    val remaining: Double, val runs: Long?, val estimatedRate: Boolean,
                    /** Share of the target's total experience, 0–1. */
                    val progress: Double,
                    /** Share of the way through the current level, 0–1. */
                    val nextLevelProgress: Double,
                    /** Experience still needed for the next level. */
                    val nextLevelRemaining: Double)

fun levelRow(name: String, dungeonClass: DungeonClass?, xp: Double, target: Int, xpPerRun: Double?, estimatedRate: Boolean = false): LevelRow {
    val safe = xp.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val level = dungeonLevel(safe)
    val goal = dungeonXpForLevel(target)
    val remaining = (goal - safe).coerceAtLeast(0.0)
    val floor = dungeonXpForLevel(level)
    val next = dungeonXpForLevel(level + 1)
    val rate = xpPerRun?.takeIf { it > 0 && it.isFinite() }
    return LevelRow(name, dungeonClass, safe, level, target, remaining, rate?.let { ceil(remaining / it).toLong() }, estimatedRate && rate != null,
        if (goal <= 0) 1.0 else (safe / goal).coerceIn(0.0, 1.0), ((safe - floor) / (next - floor)).coerceIn(0.0, 1.0), (next - safe).coerceAtLeast(0.0))
}

/** The class a run was played as: the one that gained the most experience (others may get a small share). */
internal fun playedClass(run: DungeonRunRecord): DungeonClass? =
    run.classXp.entries.filter { it.value.isFinite() && it.value > 0 }.maxByOrNull { it.value }?.key
        ?.let { name -> DungeonClass.entries.firstOrNull { it.name == name } }

/** Average class experience per tracked run played as that class, from runs on [context] (any profile when null). */
fun classXpPerRun(runs: List<DungeonRunRecord>, context: AcquisitionContext?): Map<DungeonClass, Double> =
    runs.asSequence().filter { context == null || context.matches(it.context) }
        .mapNotNull { run -> playedClass(run)?.let { it to run.classXp.getValue(it.name) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.average() }

/** Class experience gained in tracked runs completed after [since]. */
fun classExperienceSince(runs: List<DungeonRunRecord>, since: Long, context: AcquisitionContext?): Map<DungeonClass, Double> =
    runs.asSequence().filter { run -> (run.completedAt ?: 0L) > since && (context == null || context.matches(run.context)) }
        .flatMap { it.classXp.entries }.filter { it.value.isFinite() && it.value > 0 }
        .mapNotNull { (name, xp) -> DungeonClass.entries.firstOrNull { it.name == name }?.let { it to xp } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

/** The right-hand value: whole runs with separators, condensed experience, or percent of the target. */
fun levelValue(row: LevelRow, metric: LevelMetric): String = when (metric) {
    LevelMetric.RUNS -> if (row.remaining <= 0) "0" else row.runs?.let { (if (row.estimatedRate) "~" else "") + String.format(Locale.ROOT, "%,d", it) } ?: "—"
    LevelMetric.XP -> if (row.remaining <= 0) "0" else NumberUtils.condense(row.remaining)
    LevelMetric.PERCENT -> String.format(Locale.ROOT, "%.1f%%", row.progress * 100)
}

/** Hypixel's short class names, as the party finder writes them. */
fun levelClassName(dungeonClass: DungeonClass) = if (dungeonClass == DungeonClass.BERSERKER) "Berserk" else dungeonClass.displayName

/** Progress toward a target Catacombs level; [runs] is null without a known experience-per-run figure. */
data class TargetProgress(val target: Int, val xp: Double, val level: Int, val remaining: Double, val runs: Long?,
                          /** Share of the way from level 0 to the target, 0–1. */
                          val progress: Double, val nextLevelProgress: Double)

fun targetProgress(xp: Double, target: Int, xpPerRun: Double?): TargetProgress {
    val level = dungeonLevel(xp)
    val goal = dungeonXpForLevel(target)
    val remaining = (goal - xp).coerceAtLeast(0.0)
    val floor = dungeonXpForLevel(level)
    val next = dungeonXpForLevel(level + 1)
    return TargetProgress(target, xp, level, remaining,
        xpPerRun?.takeIf { it > 0 && it.isFinite() }?.let { ceil(remaining / it).toLong() },
        if (goal <= 0) 1.0 else (xp / goal).coerceIn(0.0, 1.0), ((xp - floor) / (next - floor)).coerceIn(0.0, 1.0))
}

/**
 * Experience gained since a profile reading: tracked runs completed after it on the same profile. Profile responses lag
 * behind the game, so this keeps the target moving between readings; it never counts a run from before the reading.
 */
fun experienceSince(runs: List<DungeonRunRecord>, since: Long, context: AcquisitionContext?): Double =
    runs.asSequence().filter { run -> (run.completedAt ?: 0L) > since && (context == null || context.matches(run.context)) }
        .sumOf { it.cataXp?.takeIf { xp -> xp.isFinite() && xp > 0 } ?: 0.0 }

/** The target level from DungeonProgressHud, read from the player's own profile stats plus tracked runs since. */
object DungeonTargetLevel {
    private var baseXp: Double? = null
    private var baseAt = 0L
    private var nextRequest = 0L

    /** Current Catacombs experience, or null when no profile reading exists yet; asks for one at most every 5 minutes. */
    fun experience(): Double? {
        val name = MC.instance.user.name
        val stats = DungeonFriendStatsCache.get(name)
        val read = stats?.catacombsXp
        val now = System.currentTimeMillis()
        if (read != baseXp) { baseXp = read; baseAt = now }
        // Shared relay reports carry only the level, so only a profile provider or API key can supply the experience.
        if (now >= nextRequest && profileSource) {
            nextRequest = now + 300_000
            // Records saved before the experience was kept have none, so they are fetched again rather than waiting to expire.
            DungeonFriendStatsCache.request(name, MC.instance.user.profileId, force = read == null || stats.classXp == null,
                bypassShared = true, refreshExpired = true)
        }
        val base = read ?: return null
        return base + experienceSince(AcquisitionRepository.state.runs, baseAt, AcquisitionRepository.currentContext())
    }

    /** Class experience from the same reading plus tracked runs since; null until a profile reading includes it. */
    fun classExperience(): Map<DungeonClass, Double>? {
        experience() ?: return null
        val read = DungeonFriendStatsCache.get(MC.instance.user.name)?.classXp ?: return null
        val since = classExperienceSince(AcquisitionRepository.state.runs, baseAt, AcquisitionRepository.currentContext())
        return DungeonClass.entries.associateWith { (read[it] ?: 0.0) + (since[it] ?: 0.0) }
    }

    fun progress(xpPerRun: Double?): TargetProgress? = experience()?.let { targetProgress(it, DungeonTrackerConfig.targetLevel, xpPerRun) }

    /** Why there is no reading, for the widget and screen. */
    val unavailableReason: String get() = if (profileSource) "loading your profile" else "needs Skyblocker, SkyBlockPV or a Hypixel API key"

    private val profileSource get() = DungeonFriendProfileProvider.available || PartyCommandsConfig.hypixelApiKey.isNotBlank()
}
