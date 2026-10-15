package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.Gson
import com.google.gson.JsonElement
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.features.digest.DigestSavedState
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.validObservedItemMetadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest

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
            require(floor.matches(Regex("[FM][1-7]|E|ENTRANCE")) && data.isJsonObject)
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
        val context = if (parts.size == 3 && parts[0].matches(Regex("[a-f0-9-]{32,36}")))
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
    fun context(value: AcquisitionContext?) { value?.let { require(it.account.matches(Regex("[a-f0-9-]{32,36}"))); text(it.profileId, 36); text(it.profileName, 64); require(it.server in setOf("main", "alpha")) } }
    state.events.forEach {
        require(it.id.matches(Regex("[a-f0-9]{32}")) && it.item.matches(Regex("[A-Z0-9_:;-]{1,100}")) && it.occurredAt > 0 && it.quantity in 1..100000)
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
                require(it.item.matches(Regex("[A-Z0-9_:;-]{1,100}")) && it.count in 1..100000); money(it.value)
                require(me.mycellium.skymyce.utils.validObservedItemMetadata(it.rarity, it.displayName))
            }
        }
    }
    state.pins.forEach { text(it, 220) }
    val inherited = if (state.version == 1) emptyMap() else requireNotNull(state.legacyProfit)
    require(inherited.size <= 15 && (state.cutoverAt == null || state.cutoverAt > 0))
    inherited.forEach { (floor, profit) -> require(floor.matches(Regex("[FM][1-7]|E|ENTRANCE")) && profit.isFinite() && kotlin.math.abs(profit) <= 1e16) }
    val samples = requireNotNull(state.personalRateSamples)
    require(samples.size <= 15 && samples.map { it.floor }.distinct().size == samples.size)
    samples.forEach { sample ->
        require(sample.floor.matches(Regex("[FM][1-7]|E|ENTRANCE")) && sample.from > 0 && sample.until > sample.from)
        require(sample.completedRuns in 1..1_000_000 && requireNotNull(sample.itemCounts).size <= 1000)
        sample.itemCounts.forEach { (item, count) ->
            require(item.matches(Regex("[A-Z0-9_:;-]{1,100}")) && count in 0..1_000_000)
        }
    }
    return state.copy(events = state.events.distinctBy { it.id }, runs = state.runs.distinctBy { it.id }.map { if (state.version == 1) it.copy(trackingVersion = 1) else it }, legacyProfit = inherited)
}

enum class TrackerPage(val title: String) { OVERVIEW("Overview"), LOOT("Loot"), TIMELINE("RNG Timeline"), MUSEUM("RNG Museum") }
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
