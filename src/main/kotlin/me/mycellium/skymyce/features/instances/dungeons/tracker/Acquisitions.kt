package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.features.digest.DetectedRng
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.features.digest.DigestSavedState
import me.mycellium.skymyce.features.digest.DigestStateStore
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.validObservedItemMetadata
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling

internal val TRACKED_FLOOR = Regex("[FM][1-7]|E|ENTRANCE")
internal val TRACKED_ITEM_ID = Regex("[A-Z0-9_:;-]{1,100}")
private val ACCOUNT_ID = Regex("[a-f0-9-]{32,36}")
private val RECEIPT_ID = Regex("[a-f0-9]{32}")

data class AcquisitionContext(val account: String, val profileId: String? = null, val profileName: String? = null, val server: String = "main") {
    val key get() = "$account/$server/${profileId ?: profileName?.lowercase() ?: "unknown"}"
    fun matches(other: AcquisitionContext?) = other != null && account == other.account && server == other.server &&
        if (profileId != null && other.profileId != null) profileId == other.profileId else profileName != null && profileName.equals(other.profileName, true)
}

internal fun trackingContext(verified: AcquisitionContext?, observed: AcquisitionContext?, profileLoaded: Boolean, inDungeon: Boolean): AcquisitionContext? = when {
    observed == null -> null
    profileLoaded -> observed
    inDungeon && verified?.matches(observed) == true && verified.profileName.equals(observed.profileName, true) -> verified
    else -> null
}
data class AcquisitionEvent(val id: String, val item: String, val occurredAt: Long, val activity: String,
    val context: AcquisitionContext? = null, val floor: String? = null, val chest: String? = null,
    val quantity: Int = 1, val value: Double? = null, val chestCost: Double? = null, val rerollCost: Double? = null,
    val run: String? = null, val origin: String = "confirmed", val player: String? = null,
    /** Local evaluation time, not a claim about the age or authority of the market cache. */
    val valuationAt: Long? = null,
    /** Observed from this newly obtained stack/reward only; historical unknowns are never inferred. */
    val rarity: String? = null, val displayName: String? = null)
data class RunLoot(val item: String, val count: Int, val value: Double? = null,
    val rarity: String? = null, val displayName: String? = null)
data class ChestReceipt(val id: String, val time: Long, val chest: String, val cost: Double?, val loot: List<RunLoot>)
data class DungeonRunRecord(val id: String, val session: String, val context: AcquisitionContext?, val floor: String,
    val startedAt: Long? = null, val completedAt: Long? = null, val duration: Long? = null,
    val cataXp: Double? = null, val classXp: Map<String, Double> = emptyMap(),
    val chests: List<ChestReceipt> = emptyList(), val rerolls: Int = 0, val rerollCost: Double? = null,
    val trackingVersion: Int = 2)
data class PersonalRateSample(val floor: String, val from: Long, val until: Long,
    val completedRuns: Long, val itemCounts: Map<String, Long>)

data class AcquisitionArchive(val version: Int = 2, val events: List<AcquisitionEvent> = emptyList(),
    val runs: List<DungeonRunRecord> = emptyList(), val pins: Set<String> = emptySet(), val imports: Set<String> = emptySet(),
    val cutoverAt: Long? = null, val legacyProfit: Map<String, Double> = emptyMap(),
    val personalRateSamples: List<PersonalRateSample> = emptyList())

/** Aggregate profit already contains pre-cutover receipts; those receipts must never be added again. */
fun cutoverAcquisitions(archive: AcquisitionArchive, tracker: JsonElement?, now: Long): AcquisitionArchive {
    if (archive.version == 2 && archive.cutoverAt != null) return archive
    require(now > 0)
    val profit = if (tracker != null) {
        require(tracker.isJsonObject && tracker.asJsonObject.size() <= 15)
        tracker.asJsonObject.entrySet().associate { (floor, data) ->
            require(floor.matches(TRACKED_FLOOR) && data.isJsonObject)
            val chests = data.asJsonObject.getAsJsonObject("chests")
            require(chests == null || chests.size() <= 6)
            fun amount(value: JsonElement?): Double = value?.asDouble?.also { require(it.isFinite() && it in 0.0..1e16) } ?: 0.0
            val total = chests?.entrySet()?.sumOf { (_, chest) ->
                val content = chest.asJsonObject
                val items = content.getAsJsonObject("trackedItems")
                require(items == null || items.size() <= 10000)
                val gross = items?.entrySet()?.sumOf { (_, item) -> amount(item.asJsonObject.get("value")) } ?: 0.0
                gross - amount(content.get("chestCost")) - amount(content.get("rerollCost"))
            } ?: 0.0
            require(total.isFinite() && kotlin.math.abs(total) <= 1e16)
            floor to total
        }
    } else archive.runs.groupBy { it.floor }.mapValues { (_, runs) ->
        // No old aggregate exists: preserve only the profit that old receipts actually priced.
        runs.sumOf { run -> run.chests.sumOf { chest -> chest.loot.sumOf { it.value ?: 0.0 } - (chest.cost ?: 0.0) } - (run.rerollCost ?: 0.0) }
    }
    return archive.copy(version = 2, cutoverAt = now, legacyProfit = profit,
        runs = archive.runs.map { it.copy(trackingVersion = 1) },
        events = archive.events.map { if (it.origin == "confirmed") it.copy(origin = "legacy-confirmed") else it })
}

/** IDs carry provenance. Repeated legacy timestamps have an occurrence index, never a time-only key. */
internal fun acquisitionId(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
    .take(16).joinToString("") { "%02x".format(it) }

fun importLegacyAcquisitions(archive: AcquisitionArchive, tracker: JsonElement?, digest: DigestSavedState?): AcquisitionArchive {
    val additions = mutableListOf<AcquisitionEvent>()
    if ("tracker-v1" !in archive.imports) tracker?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (floor, value) ->
        value.asJsonObject.getAsJsonObject("chests")?.entrySet()?.forEach { (chest, content) ->
            content.asJsonObject.getAsJsonObject("valuables")?.entrySet()?.forEach { (item, times) ->
                times.asJsonArray.forEachIndexed { index, timestamp ->
                    val time = timestamp.asLong
                    if (time > 0) additions += AcquisitionEvent(acquisitionId("tracker-v1/$floor/$chest/$item/$index/$time"),
                        canonicalItemId(item), time, "dungeon", floor = floor, chest = chest, origin = "legacy-tracker")
                }
            }
        }
    }
    if ("digest-v1" !in archive.imports) digest?.profiles?.forEach { (scope, profile) ->
        val parts = scope.split('/', limit = 3)
        val context = if (parts.size == 3 && parts[0].matches(ACCOUNT_ID))
            AcquisitionContext(parts[0], profileName = parts[2], server = parts[1]) else null
        profile.history.filter { !it.community }.forEachIndexed { index, event -> additions += AcquisitionEvent(
            acquisitionId("digest-v1/$scope/${event.id}/$index"), event.item, event.occurredAt, event.activity,
            context = context, origin = "legacy-digest", player = event.player)
        }
    }
    // Old sources have no shared acquisition receipt. Similar dates are not proof of duplication.
    val events = (archive.events + additions).distinctBy { it.id }
    return archive.copy(events = events, imports = archive.imports + listOfNotNull(if (tracker != null) "tracker-v1" else null, if (digest != null) "digest-v1" else null))
}

fun parseAcquisitionArchive(json: JsonElement?): AcquisitionArchive {
    if (json == null) return AcquisitionArchive()
    require(json.isJsonObject)
    json.asJsonObject.get("personalRateSamples")?.let { samples ->
        require(samples.isJsonArray && samples.asJsonArray.size() <= 15)
        fun integer(value: JsonElement?) {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            value.asBigDecimal.longValueExact()
        }
        samples.asJsonArray.forEach { sample ->
            require(sample.isJsonObject)
            val content = sample.asJsonObject
            integer(content.get("from")); integer(content.get("until")); integer(content.get("completedRuns"))
            val counts = content.get("itemCounts")
            require(counts != null && counts.isJsonObject && counts.asJsonObject.size() <= 1000)
            counts.asJsonObject.entrySet().forEach { integer(it.value) }
        }
    }
    val state = Gson().fromJson(json, AcquisitionArchive::class.java)
    require(state.version in 1..2 && requireNotNull(state.events).size <= 100000 && requireNotNull(state.runs).size <= 100000)
    require(requireNotNull(state.pins).size <= 2048 && requireNotNull(state.imports).size <= 16)
    fun text(value: String?, max: Int) { require(value == null || value.length <= max && value.none(Char::isISOControl)) }
    fun money(value: Double?) { require(value == null || value.isFinite() && value >= 0 && value <= 1e16) }
    fun context(value: AcquisitionContext?) { value?.let { require(it.account.matches(ACCOUNT_ID)); text(it.profileId, 36); text(it.profileName, 64); require(it.server in setOf("main", "alpha")) } }
    state.events.forEach {
        require(it.id.matches(RECEIPT_ID) && it.item.matches(TRACKED_ITEM_ID) && it.occurredAt > 0 && it.quantity in 1..100000)
        require(it.origin in setOf("confirmed", "legacy-confirmed", "legacy-tracker", "legacy-digest")); text(it.activity, 30); text(it.floor, 8); text(it.chest, 16); text(it.run, 40); text(it.player, 16)
        money(it.value); money(it.chestCost); money(it.rerollCost); context(it.context)
        require(it.valuationAt == null || it.valuationAt > 0 && it.value != null)
        require(me.mycellium.skymyce.utils.validObservedItemMetadata(it.rarity, it.displayName))
    }
    state.runs.forEach { run ->
        require(state.version == 1 || run.trackingVersion in 1..2)
        text(run.id, 40); text(run.session, 40); text(run.floor, 8); context(run.context)
        require(run.startedAt == null || run.startedAt > 0); require(run.completedAt == null || run.completedAt > 0)
        require(run.duration == null || run.duration in 0..86400000); money(run.cataXp); money(run.rerollCost)
        require(run.rerolls in 0..1000 && requireNotNull(run.chests).size <= 20 && requireNotNull(run.classXp).size <= 5)
        run.classXp.values.forEach(::money)
        run.chests.forEach { receipt ->
            text(receipt.id, 40); text(receipt.chest, 16); require(receipt.time > 0); money(receipt.cost)
            require(receipt.loot.size <= 64)
            receipt.loot.forEach {
                require(it.item.matches(TRACKED_ITEM_ID) && it.count in 1..100000); money(it.value)
                require(me.mycellium.skymyce.utils.validObservedItemMetadata(it.rarity, it.displayName))
            }
        }
    }
    state.pins.forEach { text(it, 220) }
    val inherited = if (state.version == 1) emptyMap() else requireNotNull(state.legacyProfit)
    require(inherited.size <= 15 && (state.cutoverAt == null || state.cutoverAt > 0))
    inherited.forEach { (floor, profit) -> require(floor.matches(TRACKED_FLOOR) && profit.isFinite() && kotlin.math.abs(profit) <= 1e16) }
    val samples = requireNotNull(state.personalRateSamples)
    require(samples.size <= 15 && samples.map { it.floor }.distinct().size == samples.size)
    samples.forEach { sample ->
        require(sample.floor.matches(TRACKED_FLOOR) && sample.from > 0 && sample.until > sample.from)
        require(sample.completedRuns in 1..1_000_000 && requireNotNull(sample.itemCounts).size <= 1000)
        sample.itemCounts.forEach { (item, count) ->
            require(item.matches(TRACKED_ITEM_ID) && count in 0..1_000_000)
        }
    }
    return state.copy(events = state.events.distinctBy { it.id }, runs = state.runs.distinctBy { it.id }.map { if (state.version == 1) it.copy(trackingVersion = 1) else it }, legacyProfit = inherited)
}

enum class TrackerPage(val title: String) { OVERVIEW("Overview"), LOOT("Loot"), TIMELINE("RNG Timeline"), MUSEUM("RNG Museum"), LEVELS("Levels") }
enum class TrackerScope { CURRENT_PROFILE, ALL_LOCAL_PROFILES, UNSCOPED_LEGACY }
enum class LootOrder { NAME, COUNT, VALUE, OBSERVED_RATE }
data class TrackerFilter(val page: TrackerPage = TrackerPage.OVERVIEW, val floor: String? = null, val mode: String? = null,
    val chest: String? = null, val query: String = "", val rareOnly: Boolean = false, val valuableOnly: Boolean = false,
    val from: String = "", val until: String = "", val session: String? = null, val activity: String? = null,
    val scope: TrackerScope = TrackerScope.CURRENT_PROFILE, val legacyTotals: Boolean = false,
    val sort: LootOrder = LootOrder.VALUE, val ascending: Boolean = false, val oldestFirst: Boolean = false,
    val overviewVersion: Int = 2) {
    fun clearConstraints() = copy(floor = null, mode = null, chest = null, query = "", rareOnly = false,
        valuableOnly = false, from = "", until = "", session = null, activity = null)
    fun floorMatches(value: String?) = (floor == null || floor == value) && (mode == null || value?.startsWith(mode) == true)
    fun scopeMatches(value: AcquisitionContext?, current: AcquisitionContext?) = when (scope) {
        TrackerScope.CURRENT_PROFILE -> current?.matches(value) == true
        TrackerScope.ALL_LOCAL_PROFILES -> value != null
        TrackerScope.UNSCOPED_LEGACY -> value == null
    }
    fun dateMatches(time: Long?): Boolean {
        if (from.isEmpty() && until.isEmpty()) return true
        if (time == null) return false
        val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
        return (from.isEmpty() || date >= LocalDate.parse(from)) && (until.isEmpty() || date <= LocalDate.parse(until))
    }
    val needsRecords get() = from.isNotEmpty() || until.isNotEmpty() || session != null
    fun runs(values: List<DungeonRunRecord>, current: AcquisitionContext?) = values.filter {
        (it.trackingVersion == 2 || needsRecords && it.trackingVersion == 1) && floorMatches(it.floor) &&
            (scopeMatches(it.context, current) || needsRecords && it.trackingVersion == 1 && scope == TrackerScope.ALL_LOCAL_PROFILES && it.context == null) &&
            dateMatches(it.completedAt ?: it.startedAt ?: it.chests.firstOrNull()?.time) && (session == null || session == it.session)
    }
    fun events(values: List<AcquisitionEvent>, current: AcquisitionContext?, runs: List<DungeonRunRecord>): List<AcquisitionEvent> {
        val sessionRuns = if (session == null) emptySet() else runs.filter { it.session == session }.mapTo(hashSetOf()) { it.id }
        val savedHistory = legacyTotals || scope != TrackerScope.CURRENT_PROFILE
        return values.filter {
        (page != TrackerPage.TIMELINE || it.item != "RECOMBOBULATOR_3000") &&
        (it.origin == "confirmed" || savedHistory) && floorMatches(it.floor) &&
            (scopeMatches(it.context, current) || savedHistory && scope == TrackerScope.ALL_LOCAL_PROFILES && it.context == null) && dateMatches(it.occurredAt) && (chest == null || chest == it.chest) &&
            (activity == null || activity == it.activity) && (session == null || it.run in sessionRuns) &&
            (query.isBlank() || "${it.item} ${DigestRngParser.catalog[it.item]?.name.orEmpty()}".contains(query, true)) &&
            (!rareOnly || it.item in DigestRngParser.catalog) && (!valuableOnly || it.value?.let { value -> value >= 1_000_000 } == true)
    }.sortedWith((if (oldestFirst) compareBy<AcquisitionEvent> { it.occurredAt } else compareByDescending { it.occurredAt }).thenBy { it.id })
    }
}
data class TrackerLootRow(val item: String, val count: Long, val value: Double?, val acquiredChests: Int?, val sampledChests: Int,
    val rateSample: PersonalRateSample? = null) {
    val observedRate get() = if (rateSample != null) rateSample.itemCounts[item]?.toDouble()?.div(rateSample.completedRuns)
        else acquiredChests?.takeIf { sampledChests > 0 }?.toDouble()?.div(sampledChests)
}
fun sortedLoot(rows: List<TrackerLootRow>, filter: TrackerFilter): List<TrackerLootRow> {
    val comparator = when (filter.sort) {
        LootOrder.NAME -> compareBy<TrackerLootRow> { it.item }
        LootOrder.COUNT -> compareBy { it.count }
        LootOrder.VALUE -> compareBy { it.value ?: 0.0 }
        LootOrder.OBSERVED_RATE -> compareBy { it.observedRate ?: 0.0 }
    }.let { if (filter.ascending) it else it.reversed() }
    return rows.filter { (filter.page != TrackerPage.TIMELINE || it.item != "RECOMBOBULATOR_3000") &&
        (filter.query.isBlank() || "${it.item} ${DigestRngParser.catalog[it.item]?.name.orEmpty()}".contains(filter.query, true)) &&
        (!filter.rareOnly || it.item in DigestRngParser.catalog) && (!filter.valuableOnly || it.value?.let { value -> value >= 1_000_000 } == true)
    }.sortedWith(compareBy<TrackerLootRow> { when (filter.sort) { LootOrder.VALUE -> it.value == null; LootOrder.OBSERVED_RATE -> it.observedRate == null; else -> false } }
        .then(comparator).thenBy { it.item })
}
fun safeAverage(total: Double, count: Long): Double? = if (total.isFinite() && total >= 0 && count > 0) (total / count).takeIf(Double::isFinite) else null

fun canonicalItemId(value: String) = value.removePrefix("!").let { if (it.startsWith("item:", true)) it.substring(5) else it }.uppercase()

fun mergeAcquisition(archive: AcquisitionArchive, event: AcquisitionEvent): AcquisitionArchive {
    require(me.mycellium.skymyce.utils.validObservedItemMetadata(event.rarity, event.displayName))
    val index = archive.events.indexOfFirst { it.id == event.id }
    if (index < 0) return archive.copy(events = archive.events + event)
    val old = archive.events[index]
    require(old.item == event.item && (old.context == event.context || old.context?.matches(event.context) == true) && old.origin == event.origin) { "Conflicting acquisition receipt" }
    val merged = old.copy(quantity = maxOf(old.quantity, event.quantity), floor = old.floor ?: event.floor, chest = old.chest ?: event.chest,
        value = old.value ?: event.value, chestCost = old.chestCost ?: event.chestCost,
        rerollCost = old.rerollCost ?: event.rerollCost, run = old.run ?: event.run, player = old.player ?: event.player,
        valuationAt = if (old.value != null) old.valuationAt else event.valuationAt,
        rarity = old.rarity ?: event.rarity, displayName = old.displayName ?: event.displayName)
    if (old == merged) return archive
    return archive.copy(events = archive.events.toMutableList().apply { this[index] = merged })
}

/** Late inventory metadata can enrich a recorded personal receipt, never introduce a new drop. */
internal fun enrichAcquisitionMetadata(archive: AcquisitionArchive, id: String, item: String,
    context: AcquisitionContext, metadata: ObservedItemMetadata): AcquisitionArchive {
    require(validObservedItemMetadata(metadata.rarity, metadata.displayName))
    val recorded = archive.events.firstOrNull { it.id == id && it.item == item && it.origin == "confirmed" && context.matches(it.context) }
        ?: return archive
    return mergeAcquisition(archive, recorded.copy(rarity = metadata.rarity, displayName = metadata.displayName))
}

fun receivedChestItems(expected: Map<String, Int>, before: Map<String, Int>, after: Map<String, Int>): Set<String> =
    expected.filter { (item, count) -> count in 1..100000 && (after[item] ?: 0).toLong() - (before[item] ?: 0) >= count }.keys

/** Permanent personal records. Neither community feed retention nor clear-recent-history touches this file. */
object AcquisitionRepository : SkyMyceModule() {
    var state = AcquisitionArchive()
        private set
    var preferences = TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES)
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
    private var nextValuationAttempt = Long.MIN_VALUE
    private var valuationCursor = 0
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
            var saved = runCatching { parseAcquisitionArchive(file.read()) }.getOrElse {
                SkyMyce.logger.warn("Could not read personal acquisition archive; recording paused, original retained", it)
                storageError = true; AcquisitionArchive()
            }
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
                }.onFailure {
                    SkyMyce.logger.warn("Legacy acquisition migration failed; recording paused, originals preserved", it)
                    saved = original; migrationError = true
                }
            }
            var migratedPreferences = false
            val restoredView = runCatching {
                val json = viewFile.read()
                parseTrackerPreferences(json).also {
                    migratedPreferences = json?.isJsonObject == true && json.asJsonObject.get("overviewVersion")?.asString != "2"
                }
            }.getOrElse { viewFile.preserveOriginal(); TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES) }
                .let { if (cutover && !migrationError) it.copy(legacyTotals = false) else it }
            val restoredJournal = runCatching { parseJournalFilter(journalFile.read()) }.getOrElse { journalFile.preserveOriginal(); JournalFilter() }
            MC.instance.execute {
                state = saved; writable = !storageError && !migrationError; loaded = true
                if (!preferencesEdited) preferences = restoredView else preferenceWriter.submit(preferences)
                if (!journalEdited) journalPreferences = restoredJournal else journalWriter.submit(journalPreferences)
                if (cutover && !migrationError || migratedPreferences) preferenceWriter.submit(preferences)
                status = when { storageError -> "Archive unreadable • original preserved; recording paused"; migrationError -> "Legacy cutover needs attention • recording paused; originals preserved"; else -> "Confirmed tracking • historical profit preserved separately" }
                val queued = pending.toList(); pending.clear(); queued.forEach { update(it) }; revision++
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush(); preferenceWriter.flush(); journalWriter.flush() } }, "Sky-Hawk-Acquisitions-Save"))
    }

    private var contextTick = -1L
    private var tickContext: AcquisitionContext? = null
    private var tick = 0L
    /** Memoized per client tick: widgets read it every frame and several features read it every tick. */
    fun currentContext(): AcquisitionContext? {
        if (!LocationAPI.isOnSkyBlock || MC.instance.player == null) return null
        if (contextTick == tick) return tickContext
        return computeContext().also { tickContext = it; contextTick = tick }
    }
    private fun computeContext(): AcquisitionContext? {
        val name = ProfileAPI.profileName?.takeIf { it.isNotBlank() } ?: return null
        val observed = AcquisitionContext(MC.instance.user.profileId.toString(), ProfileAPI.profileId?.toString(), name, if (LocationAPI.onAlpha) "alpha" else "main")
        // Dungeon transfers clear isLoaded even though their tab list omits the profile widget.
        return trackingContext(verifiedContext, observed, ProfileAPI.isLoaded, SkyBlockIsland.THE_CATACOMBS.inIsland())
            .also { if (ProfileAPI.isLoaded) verifiedContext = it }
    }
    @Subscription(TickEvent::class) fun refreshContext() { tick++; if (ProfileAPI.isLoaded) currentContext() }
    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class) fun clearContext() { verifiedContext = null; contextTick = -1 }
    @Subscription(TickEvent::class) fun retryValuations() {
        if (!loaded || !writable || !LocationAPI.isOnSkyBlock || MC.instance.player == null) return
        val now = System.nanoTime()
        if (now < nextValuationAttempt) return
        nextValuationAttempt = now + 10_000_000_000L
        val account = MC.instance.user.profileId.toString()
        val server = if (LocationAPI.onAlpha) "alpha" else "main"
        val missing = missingAcquisitionPrices(state, account, server)
        if (missing.isEmpty()) { valuationCursor = 0; return }
        // Bound cache lookups, and rotate so an unavailable book cannot starve later rewards.
        val prices = buildMap<String, Double?> {
            repeat(minOf(64, missing.size)) { offset ->
                val item = missing[(valuationCursor + offset) % missing.size]
                put(item, cachedLootUnitPrice(item, DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType))
            }
        }
        valuationCursor = (valuationCursor + prices.size) % missing.size
        update { archive -> repairAcquisitionValuations(archive, account, server, prices, System.currentTimeMillis()) }
    }
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
    fun run(record: DungeonRunRecord) = update { archive ->
        val preserved = preserveRunValuations(archive.runs.firstOrNull { it.id == record.id }, record)
        archive.copy(runs = archive.runs.filterNot { it.id == record.id } + preserved)
    }
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
    if (json == null) return TrackerFilter(scope = TrackerScope.ALL_LOCAL_PROFILES)
    require(json.isJsonObject)
    val overviewVersion = json.asJsonObject.get("overviewVersion")
    require(overviewVersion == null || overviewVersion.isJsonPrimitive && overviewVersion.asJsonPrimitive.isNumber && overviewVersion.asString in listOf("1", "2"))
    val filter = Gson().fromJson(json, TrackerFilter::class.java)
    require(filter.page in TrackerPage.entries && filter.sort in LootOrder.entries && filter.scope in TrackerScope.entries)
    require(filter.query.length <= 100 && filter.floor?.matches(TRACKED_FLOOR) != false && filter.mode in listOf(null, "F", "M"))
    if (filter.from.isNotEmpty()) java.time.LocalDate.parse(filter.from)
    if (filter.until.isNotEmpty()) java.time.LocalDate.parse(filter.until)
    require(filter.chest?.length?.let { it <= 16 } != false && filter.session?.length?.let { it <= 40 } != false && filter.activity?.length?.let { it <= 30 } != false)
    val oldDefaultOverview = overviewVersion == null && filter.page == TrackerPage.OVERVIEW
    val oldHistoricalLoot = overviewVersion?.asString != "2" && filter.page == TrackerPage.LOOT && filter.legacyTotals
    return if ((oldDefaultOverview || oldHistoricalLoot) && !filter.needsRecords && filter.scope == TrackerScope.CURRENT_PROFILE)
        filter.copy(scope = TrackerScope.ALL_LOCAL_PROFILES, legacyTotals = false, overviewVersion = 2)
    else filter.copy(overviewVersion = 2)
}

internal fun marketValuedLoot(rows: List<TrackerLootRow>, unitPrice: (String) -> Double?): List<TrackerLootRow> =
    rows.map { it.copy(value = estimatedLootMarketValue(it.count, unitPrice(it.item))) }

internal fun cachedLootUnitPrice(item: String, bazaar: DungeonsConfig.BazaarType, auction: DungeonsConfig.AuctionType): Double? = runCatching {
    val id = if (':' in item) SkyBlockId.CODEC.parse(JsonOps.INSTANCE, JsonPrimitive(item.lowercase(Locale.ROOT))).result().orElse(null)
        else SkyBlockId.item(item)
    id?.getPriceOrNull(bazaar, auction)
}.getOrNull()

/** Display estimate only; this never changes the recorded acquisition value or profit. */
internal fun estimatedLootMarketValue(count: Long, unitPrice: Double?): Double? {
    if (count < 0) return null
    if (count == 0L) return 0.0
    val price = unitPrice?.takeIf { it.isFinite() && it > 0.0 && it <= 1e12 } ?: return null
    return (price * count).takeIf { it.isFinite() && it in 0.0..1e16 }
}

private fun DungeonRunRecord.valuationScope(account: String, server: String) = trackingVersion == 2 &&
    context?.let { it.account == account && it.server == server } == true

internal fun missingAcquisitionPrices(archive: AcquisitionArchive, account: String, server: String): List<String> =
    if (archive.version != 2) emptyList() else archive.runs.asSequence().filter { it.valuationScope(account, server) }
        .flatMap { it.chests.asSequence() }.flatMap { it.loot.asSequence() }.filter { it.value == null }
        .map { it.item }.distinct().toList()

private data class LootValuationKey(val run: String, val item: String, val count: Int, val chest: String)

/** Evaluate existing confirmed receipts locally. Counts, provenance, costs and previously known values stay frozen. */
internal fun repairAcquisitionValuations(archive: AcquisitionArchive, account: String, server: String,
    prices: Map<String, Double?>, now: Long): AcquisitionArchive {
    // Unpriced items stay unpriced: skip the full archive copy when no lookup produced a value.
    if (archive.version != 2 || now <= 0 || prices.values.none { it != null && it.isFinite() && it > 0.0 && it <= 1e12 }) return archive
    val repaired = mutableMapOf<LootValuationKey, Pair<Double, AcquisitionContext>>()
    val receipts = mutableMapOf<LootValuationKey, Int>()
    val runs = archive.runs.map { run ->
        if (!run.valuationScope(account, server)) return@map run
        val chests = run.chests.map { chest -> chest.copy(loot = chest.loot.map loot@{ item ->
            val key = LootValuationKey(run.id, item.item, item.count, chest.chest)
            receipts[key] = (receipts[key] ?: 0) + 1
            if (item.value != null) return@loot item
            val unit = prices[item.item]?.takeIf { it.isFinite() && it > 0.0 && it <= 1e12 } ?: return@loot item
            val value = (unit * item.count).takeIf { it.isFinite() && it in 0.0..1e16 } ?: return@loot item
            repaired[key] = value to run.context!!
            item.copy(value = value)
        }) }
        run.copy(chests = chests)
    }
    if (repaired.isEmpty()) return archive
    val linkedEvents = archive.events.filter { it.origin == "confirmed" && it.run != null && it.chest != null }
        .groupingBy { LootValuationKey(it.run!!, it.item, it.quantity, it.chest!!) }.eachCount()
    val events = archive.events.map { event ->
        if (event.origin != "confirmed" || event.value != null || event.run == null || event.chest == null) return@map event
        val key = LootValuationKey(event.run, event.item, event.quantity, event.chest)
        // The shared receipt ID attaches run/chest, but preserves an earlier inventory announcement's timestamp.
        if (receipts[key] != 1 || linkedEvents[key] != 1) return@map event
        val valuation = repaired[key]
            ?.takeIf { it.second.matches(event.context) } ?: return@map event
        event.copy(value = valuation.first, valuationAt = now)
    }
    return archive.copy(runs = runs, events = events)
}

/** A later XP/chest update may carry an older run snapshot. Keep repaired values for the same physical receipt. */
internal fun preserveRunValuations(saved: DungeonRunRecord?, incoming: DungeonRunRecord): DungeonRunRecord {
    if (saved == null || saved.id != incoming.id || saved.floor != incoming.floor || saved.context != incoming.context ||
        saved.trackingVersion != incoming.trackingVersion) return incoming
    val savedChests = saved.chests.associateBy { it.id }
    val chests = incoming.chests.map { chest ->
        val previous = savedChests[chest.id]?.takeIf { it.time == chest.time && it.chest == chest.chest } ?: return@map chest
        chest.copy(loot = chest.loot.map { item ->
            if (item.value != null) item else previous.loot.singleOrNull { it.item == item.item && it.count == item.count }
                ?.value?.let { item.copy(value = it) } ?: item
        })
    }
    return if (chests == incoming.chests) incoming else incoming.copy(chests = chests)
}

/** Only a matching server receipt and every physical inventory delta can confirm the preview. */
internal data class PendingChestObservation(
    val id: String,
    val context: AcquisitionContext,
    val run: String,
    val contents: Map<String, Int>,
    val baseline: Map<String, Int>,
    val started: Long,
    val closed: Long? = null,
    val rewardHeader: Boolean = false,
    val received: Map<String, String> = emptyMap(),
    /** Rewards that go to essence storage or the Hunting Box, never the inventory. */
    val stored: Set<String> = emptySet(),
) {
    val physical get() = contents.filterKeys { !it.startsWith("ESSENCE_") && it !in stored }
    fun active(now: Long, current: AcquisitionContext?, currentRun: String): Boolean =
        context.matches(current) && run == currentRun && now - started in 0..10000 &&
            (closed == null || now - closed in 0..2000)
    fun inventory(counts: Map<String, Int>, observation: String): PendingChestObservation {
        val additions = receivedChestItems(physical, baseline, counts).filterNot(received::containsKey)
            .associateWith { "$observation/$it" }
        return if (additions.isEmpty()) this else copy(received = received + additions)
    }
    val confirmed get() = contents.isNotEmpty() && rewardHeader && physical.keys.all(received::containsKey)
}

internal fun rerollInventoryConfirmed(before: Int, after: Int) = before in 1..100000 && after == before - 1

data class ChestTracker(
    var chestCount: Int = 0,
    var chestCost: Double = 0.0,
    var rerollCount: Int = 0,
    var rerollCost: Double = 0.0,
    var valuables: MutableMap<SkyBlockId, MutableList<Long>> = mutableMapOf(),
    var trackedItems: MutableMap<SkyBlockId, TrackedItem> = mutableMapOf(),
) {
    val grossProfit: Double
        get() = trackedItems.values.sumOf { it.value }

    val netProfit: Double
        get() = grossProfit - chestCost - rerollCost

    companion object {
        val CODEC: Codec<ChestTracker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("chestCount").forGetter(ChestTracker::chestCount),
                Codec.DOUBLE.fieldOf("chestCost").forGetter(ChestTracker::chestCost),
                Codec.INT.fieldOf("rerollCount").forGetter(ChestTracker::rerollCount),
                Codec.DOUBLE.fieldOf("rerollCost").forGetter(ChestTracker::rerollCost),
                Codec.unboundedMap(
                    SkyBlockId.CODEC,
                    Codec.LONG.listOf().xmap({ it.toMutableList() }, { it })
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("valuables").forGetter(ChestTracker::valuables),
                Codec.unboundedMap(
                    SkyBlockId.CODEC,
                    TrackedItem.CODEC
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("trackedItems").forGetter(ChestTracker::trackedItems)
            ).apply(instance, ::ChestTracker)
        }
    }

    data class TrackedItem(
        var value: Double = 0.0,
        var count: Int = 0
    ) {
        companion object {
            val CODEC: Codec<TrackedItem> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("value").forGetter(TrackedItem::value),
                    Codec.INT.fieldOf("count").forGetter(TrackedItem::count)
                ).apply(instance, ::TrackedItem)
            }
        }
    }
}
