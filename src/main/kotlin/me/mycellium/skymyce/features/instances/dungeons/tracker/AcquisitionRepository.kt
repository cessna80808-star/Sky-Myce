package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.digest.DetectedRng
import me.mycellium.skymyce.features.digest.DigestStateStore
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.observedItemMetadata
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/** Permanent personal records. Neither community feed retention nor clear-recent-history touches this file. */
object AcquisitionRepository : SkyMyceModule() {
    var state = AcquisitionArchive()
        private set
    var preferences = TrackerFilter()
        private set
    var journalPreferences = JournalFilter()
        private set
    var revision = 0L
        private set
    var status = "Loading local records…"
        private set
    private var loaded = false
    private var writable = true
    private val pending = mutableListOf<(AcquisitionArchive) -> AcquisitionArchive>()
    private var preferencesEdited = false
    private var journalEdited = false
    private var verifiedContext: AcquisitionContext? = null
    private lateinit var writer: StateWriter<AcquisitionArchive>
    private lateinit var preferenceWriter: StateWriter<TrackerFilter>
    private lateinit var journalWriter: StateWriter<JournalFilter>

    override fun init() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("acquisitions.json"), 32 * 1024 * 1024)
        val viewFile = AtomicJsonFile(SkyMyce.configPath.resolve("tracker_view.json"))
        val journalFile = AtomicJsonFile(SkyMyce.configPath.resolve("rng_journal_view.json"))
        writer = StateWriter({ file.write(Gson().toJsonTree(it)) }, { work -> Scheduling.schedule(500.milliseconds) { work() } }, {
            MC.instance.execute { status = "Local save failed • previous file retained"; revision++ }
            SkyMyce.logger.warn("Could not save personal acquisition archive; previous file retained")
        })
        preferenceWriter = StateWriter({ viewFile.write(Gson().toJsonTree(it)) }, { work -> Scheduling.schedule(500.milliseconds) { work() } })
        journalWriter = StateWriter({ journalFile.write(Gson().toJsonTree(it)) }, { work -> Scheduling.schedule(500.milliseconds) { work() } })
        Scheduling.schedule(0.milliseconds) {
            var storageError = false
            var saved = runCatching { parseAcquisitionArchive(file.read()) }.getOrElse { storageError = true; AcquisitionArchive() }
            // A corrupt/future archive is read-only: never silently replace permanent history.
            storageError = storageError || file.damaged
            val original = saved
            var migrationError = false
            var cutover = false
            if (!storageError) {
                val legacyFile = AtomicJsonFile(SkyMyce.configPath.resolve("dungeon_tracker.json"), 16 * 1024 * 1024)
                val digestPath = SkyMyce.configPath.resolve("daily_digest.json")
                val digestStore = DigestStateStore(digestPath)
                val tracker = if ("tracker-v1" !in saved.imports || saved.cutoverAt == null) legacyFile.read() else null
                val digest = if ("digest-v1" !in saved.imports && Files.exists(digestPath)) digestStore.load().takeUnless { digestStore.recovered } else null
                runCatching {
                    require(!legacyFile.damaged) { "Historical dungeon totals are unreadable" }
                    if (tracker != null) legacyFile.preserveOriginal("before-acquisitions")
                    if (digest != null) AtomicJsonFile(digestPath).preserveOriginal("before-acquisitions")
                    saved = importLegacyAcquisitions(saved, tracker, digest)
                    if (saved.version == 1 || saved.cutoverAt == null) {
                        saved = cutoverAcquisitions(saved, tracker, System.currentTimeMillis())
                        cutover = true
                    }
                    parseAcquisitionArchive(Gson().toJsonTree(saved))
                    if (saved != original) { file.preserveOriginal(if (cutover) "before-v2-cutover" else "before-migration"); file.write(Gson().toJsonTree(saved)) }
                }.onFailure { saved = original; migrationError = true }
            }
            val restoredView = runCatching { parseTrackerPreferences(viewFile.read()) }.getOrElse { viewFile.preserveOriginal(); TrackerFilter() }
                .let { if (cutover && !migrationError) it.copy(legacyTotals = false) else it }
            val restoredJournal = runCatching { parseJournalFilter(journalFile.read()) }.getOrElse { journalFile.preserveOriginal(); JournalFilter() }
            MC.instance.execute {
                state = saved; writable = !storageError && !migrationError; loaded = true
                if (!preferencesEdited) preferences = restoredView else preferenceWriter.submit(preferences)
                if (!journalEdited) journalPreferences = restoredJournal else journalWriter.submit(journalPreferences)
                if (cutover && !migrationError) preferenceWriter.submit(preferences)
                status = when { storageError -> "Archive unreadable • original preserved; recording paused"; migrationError -> "Legacy cutover needs attention • recording paused; originals preserved"; else -> "Confirmed tracking • historical profit preserved separately" }
                val queued = pending.toList(); pending.clear(); queued.forEach { update(it) }; revision++
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush(); preferenceWriter.flush(); journalWriter.flush() } }, "Sky-Hawk-Acquisitions-Save"))
    }

    fun currentContext(): AcquisitionContext? {
        if (!LocationAPI.isOnSkyBlock || MC.instance.player == null) return null
        val name = ProfileAPI.profileName?.takeIf { it.isNotBlank() } ?: return null
        val observed = AcquisitionContext(MC.instance.user.profileId.toString(), ProfileAPI.profileId?.toString(), name, if (LocationAPI.onAlpha) "alpha" else "main")
        // Dungeon transfers clear isLoaded even though their tab list omits the profile widget.
        return trackingContext(verifiedContext, observed, ProfileAPI.isLoaded, SkyBlockIsland.THE_CATACOMBS.inIsland())
            .also { if (ProfileAPI.isLoaded) verifiedContext = it }
    }
    @Subscription(TickEvent::class) fun refreshContext() { if (ProfileAPI.isLoaded) currentContext() }
    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class) fun clearContext() { verifiedContext = null }
    private fun update(change: (AcquisitionArchive) -> AcquisitionArchive) {
        if (!loaded) { if (pending.size < 1000) pending += change; return }
        if (!writable) return
        val next = change(state)
        if (next.events.size > 100000 || next.runs.size > 100000) { status = "Archive capacity reached • existing records retained; back up before archiving"; revision++; return }
        if (next != state) { state = next; revision++; writer.submit(state) }
    }
    fun record(event: AcquisitionEvent) = update { archive -> mergeAcquisition(archive, event) }
    fun confirmed(drop: DetectedRng) {
        val context = currentContext() ?: return
        val run = if (drop.activity == "dungeon") DungeonTracker.observedRun(context) else null
        record(AcquisitionEvent(drop.confirmation?.let(::acquisitionId) ?: drop.id, drop.item, drop.occurredAt,
            drop.activity, context, floor = run?.floor ?: if (drop.activity == "dungeon") tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI.dungeonFloor?.name else null,
            run = run?.id, player = MC.instance.user.name, rarity = drop.rarity, displayName = drop.displayName))
    }
    fun enrichConfirmed(drop: DetectedRng) {
        val context = currentContext() ?: return
        val metadata = observedItemMetadata(drop.rarity, drop.displayName) ?: return
        val id = drop.confirmation?.let(::acquisitionId) ?: drop.id
        update { archive -> enrichAcquisitionMetadata(archive, id, drop.item, context, metadata) }
    }
    fun run(record: DungeonRunRecord) = update { archive -> archive.copy(runs = archive.runs.filterNot { it.id == record.id } + record) }
    fun pin(item: String, context: AcquisitionContext?) = update { archive ->
        val key = "${context?.key ?: "legacy"}|$item"
        archive.copy(pins = if (key in archive.pins) archive.pins - key else if (archive.pins.size < 2048) archive.pins + key else archive.pins)
    }
    fun pinned(item: String, context: AcquisitionContext?) = "${context?.key ?: "legacy"}|$item" in state.pins
    /** Small personal HUD snapshot. Consumers cache by revision; no community reports or inferred drops enter it. */
    fun recentPersonal(limit: Int = 3): List<AcquisitionEvent> = recentPersonalEvents(state, currentContext(), limit)
    fun remember(filter: TrackerFilter) { preferencesEdited = true; preferences = filter; if (loaded) preferenceWriter.submit(filter) }
    fun rememberJournal(filter: JournalFilter) { journalEdited = true; journalPreferences = filter; if (loaded) journalWriter.submit(filter) }
}

internal fun parseTrackerPreferences(json: com.google.gson.JsonElement?): TrackerFilter {
    if (json == null) return TrackerFilter()
    val filter = Gson().fromJson(json, TrackerFilter::class.java)
    require(filter.page in TrackerPage.entries && filter.sort in LootOrder.entries && filter.scope in TrackerScope.entries)
    require(filter.query.length <= 100 && filter.floor?.matches(Regex("[FM][1-7]|E|ENTRANCE")) != false && filter.mode in listOf(null, "F", "M"))
    if (filter.from.isNotEmpty()) java.time.LocalDate.parse(filter.from)
    if (filter.until.isNotEmpty()) java.time.LocalDate.parse(filter.until)
    require(filter.chest?.length?.let { it <= 16 } != false && filter.session?.length?.let { it <= 40 } != false && filter.activity?.length?.let { it <= 30 } != false)
    return filter
}
