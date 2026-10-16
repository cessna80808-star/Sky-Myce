package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.api.dungeonRewardHeader
import me.mycellium.skymyce.api.dungeonStoredReward
import me.mycellium.skymyce.api.events.DungeonChestOpenEvent
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonEnterEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.party.DungeonPartyFinderQueueEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    val saveFile = SkyMyce.configPath.resolve("dungeon_tracker.json").toFile()
    /** Frozen historical reference; all post-cutover measurements live in acquisitions.json. */
    val profitData = mutableMapOf<DungeonFloor, FloorTracker>()
    var revision = 0L
        private set
    var storageStatus = "Loading historical totals…"
        private set

    override fun init() {
        val file = AtomicJsonFile(saveFile.toPath(), 16 * 1024 * 1024)
        Scheduling.schedule(0.milliseconds) {
            val parsed = runCatching { readDungeonTotals(file) }
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
                    me.mycellium.skymyce.community.SharingCategory.DUNGEONS)
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
        if (contents.keys.any { !it.matches(Regex("[A-Z0-9_:;-]{1,100}")) }) return
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
