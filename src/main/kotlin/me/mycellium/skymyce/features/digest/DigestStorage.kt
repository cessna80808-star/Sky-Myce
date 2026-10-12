package me.mycellium.skymyce.features.digest

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.validObservedItemMetadata
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DigestRngEntry(val id: String, val item: String, val activity: String, val occurredAt: Long,
                          val player: String, val community: Boolean = false)
data class DigestProfileState(val tasks: List<DailyTask> = emptyList(), val history: List<DigestRngEntry> = emptyList())
data class DigestSavedState(val version: Int = 1, val lastOpened: Map<String, String> = emptyMap(),
                           val profiles: Map<String, DigestProfileState> = emptyMap(),
                           val news: Map<String, DigestNewsState> = emptyMap(), val global: GlobalDigest? = null)

/** Pure file store: caller must use the existing background scheduler. A damaged original is backed up before replacement. */
class DigestStateStore(private val file: Path) {
    private val gson = Gson()
    var recovered = false
        private set

    fun load(): DigestSavedState = try {
        if (!Files.exists(file)) DigestSavedState() else {
            require(Files.size(file) <= 8 * 1024 * 1024)
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            require(json.get("version")?.asInt == 1)
            gson.fromJson(json, DigestSavedState::class.java).also(::validate)
        }
    } catch (_: Exception) {
        recovered = true
        DigestSavedState()
    }

    @Synchronized fun save(state: DigestSavedState) {
        validate(state)
        Files.createDirectories(file.parent)
        if (recovered && Files.exists(file)) {
            Files.copy(file, file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}"))
            recovered = false
        }
        val temporary = Files.createTempFile(file.parent, "digest-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(state, it) }
            require(Files.size(temporary) <= 8 * 1024 * 1024)
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun validate(state: DigestSavedState) {
        require(state.version == 1 && requireNotNull(state.lastOpened).size <= 64 && requireNotNull(state.profiles).size <= 64)
        state.lastOpened.forEach { (account, date) -> require(account.matches(Regex("[a-f0-9-]{32,36}"))); LocalDate.parse(date) }
        state.profiles.forEach { (key, profile) ->
            require(key.length in 1..128 && key.none(Char::isISOControl))
            require(requireNotNull(profile.tasks).size <= 6 && requireNotNull(profile.history).size <= 530)
            profile.tasks.forEach { task ->
                require(requireNotNull(task.id).length in 1..32 && requireNotNull(task.title).length <= 100)
                require(task.state in DailyState.entries && requireNotNull(task.detail).length <= 1000 && requireNotNull(task.source).length <= 200)
                require(task.updatedAt >= 0 && task.readyAt >= 0 && task.progress >= 0 && task.total in 0..100)
                require(requireNotNull(task.slots).size <= 7)
                task.slots.forEach { require(it.index in 0..7 && requireNotNull(it.item).length <= 160 && it.state in DailyState.entries && it.readyAt >= 0) }
            }
            profile.history.forEach { require(validDigestRng(it, Long.MAX_VALUE / 2)) }
        }
        require(requireNotNull(state.news).size <= 2)
        state.news.forEach { (source, news) ->
            require(source in NewsSource.entries.map { it.wire } && news.fetchedAt >= 0 && news.retryAt >= 0)
            require(requireNotNull(news.items).size <= 8)
            news.items.forEach { item ->
                require(item.source.wire == source && requireNotNull(item.id).length in 1..80 && requireNotNull(item.title).length in 1..160)
                require(requireNotNull(item.content).length <= 2500 && item.publishedAt > 0 && (item.url == null || safeDigestUrl(item.url) != null))
            }
        }
        state.global?.let { global ->
            require(global.mayor == null || global.mayor.length <= 100)
            require(global.updatedAt >= 0 && requireNotNull(global.perks).size <= 16 && requireNotNull(global.events).size <= 32)
            global.perks.forEach { require(requireNotNull(it.name).length <= 160 && requireNotNull(it.description).length <= 2000) }
            global.events.forEach { require(requireNotNull(it.name).length <= 160 && it.startsAt >= 0 && (it.endsAt == 0L || it.endsAt >= it.startsAt) && requireNotNull(it.source).length <= 200) }
        }
    }
}

internal fun validDigestRng(entry: DigestRngEntry, now: Long): Boolean = runCatching {
    entry.id.matches(Regex("[a-f0-9]{32}")) && entry.item.length in 1..80 && entry.activity in setOf("dungeon", "slayer", "kuudra", "fishing") &&
        entry.occurredAt in 1..now + 60000 && entry.player.matches(Regex("[A-Za-z0-9_]{1,16}")) &&
        DigestRngCatalog.valid(entry.item, entry.activity)
}.getOrDefault(false)

/** Immutable bounded local history; received events never become publishable personal events. */
class DigestRngHistory {
    private val removedCommunity = linkedSetOf<String>()
    var entries: List<DigestRngEntry> = emptyList()
        private set
    fun restore(saved: List<DigestRngEntry>, limit: Int, now: Long) {
        val valid = saved.filter { validDigestRng(it, now) && (!it.community || it.id !in removedCommunity) }.distinctBy { it.id }.sortedByDescending { it.occurredAt }
        entries = (valid.filter { !it.community }.take(limit.coerceIn(20, 500)) + valid.filter { it.community }.take(30))
            .sortedByDescending { it.occurredAt }
    }
    fun add(entry: DigestRngEntry, limit: Int, now: Long): Boolean {
        if (!validDigestRng(entry, now) || entry.community && entry.id in removedCommunity || entries.any { it.id == entry.id }) return false
        restore(entries + entry, limit, now)
        return true
    }
    fun clear() { entries = emptyList() }
    fun removeCommunity(ids: Set<String>? = null) {
        if (ids != null) {
            removedCommunity.addAll(ids)
            while (removedCommunity.size > 10000) removedCommunity.remove(removedCommunity.first())
        }
        entries = entries.filter { !it.community || ids != null && it.id !in ids }
    }
}

/** One pending snapshot. submit never waits for disk; flush runs only on a background shutdown thread. */
class DigestStateWriter(private val store: DigestStateStore, private val schedule: (() -> Unit) -> Unit,
                        private val result: (Exception?) -> Unit = {}) {
    private val latest = AtomicReference<DigestSavedState?>()
    private val written = AtomicReference<DigestSavedState?>()
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val ioLock = Any()
    val scheduled get() = running.get()

    fun submit(state: DigestSavedState) {
        latest.set(state)
        start()
    }

    private fun start() {
        if (closed.get() || written.get() == latest.get() || !running.compareAndSet(false, true)) return
        schedule {
            var attempted: DigestSavedState? = null
            try {
                synchronized(ioLock) {
                    if (!closed.get()) {
                        attempted = latest.get()
                        attempted?.let { store.save(it); written.set(it) }
                    }
                }
                result(null)
            } catch (error: Exception) { result(error) }
            finally {
                running.set(false)
                // Coalesce changes arriving during a write; a failed write retries only on a later submit.
                if (latest.get() != attempted && !closed.get()) start()
            }
        }
    }

    fun flush() = synchronized(ioLock) {
        closed.set(true)
        latest.get()?.takeIf { it != written.get() }?.let { store.save(it); written.set(it) }
    }
}

internal fun digestRngSubmission(drop: DetectedRng, sharing: Boolean, showName: Boolean, now: Long,
    acquisition: AcquisitionEvent? = null, context: AcquisitionContext? = null): Map<String, Any>? {
    if (!sharing || !drop.id.matches(Regex("[a-f0-9]{32}")) || !DigestRngCatalog.valid(drop.item, drop.activity) ||
        drop.occurredAt <= 0 || drop.occurredAt <= now - 300000 || drop.occurredAt > now + 60000) return null
    // Only this live receipt can supply context. Old history and another profile's matching item are not metadata sources.
    val confirmed = acquisition?.takeIf { it.id == drop.id && it.item == drop.item && it.activity == drop.activity &&
        it.origin == "confirmed" && context?.matches(it.context) == true &&
        kotlin.math.abs(it.occurredAt - drop.occurredAt) <= 15000 }
    val metadata = observedItemMetadata(confirmed?.rarity ?: drop.rarity, confirmed?.displayName ?: drop.displayName)
    val floor = confirmed?.floor?.takeIf { drop.activity == "dungeon" && it.matches(Regex("E|[FM][1-7]")) }
    val event = buildMap<String, Any> {
        put("id", drop.id); put("item", drop.item); put("activity", drop.activity); put("occurredAt", drop.occurredAt)
        metadata?.rarity?.let { put("rarity", it) }
        metadata?.displayName?.let { put("displayName", it) }
        floor?.let { put("floor", it) }
    }
    return mapOf("event" to event, "showName" to showName)
}

internal fun parseDigestCommunityEvent(json: com.google.gson.JsonObject, now: Long): DigestRngEntry? = runCatching {
    require(json.get("unverified")?.asBoolean == true)
    require(json.keySet().all { it in setOf("id", "item", "activity", "occurredAt", "player", "unverified", "rarity", "displayName", "floor") })
    fun optionalText(key: String): String? = json[key]?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
    }
    require(validObservedItemMetadata(optionalText("rarity"), optionalText("displayName")))
    val floor = optionalText("floor")
    require(floor == null || json["activity"]?.asString == "dungeon" && floor.matches(Regex("E|[FM][1-7]")))
    DigestRngEntry(json.get("id").asString, json.get("item").asString, json.get("activity").asString,
        json.get("occurredAt").asLong, json.get("player").asString, true).also {
        require(it.occurredAt >= now - 7 * 86400000L && validDigestRng(it, now))
    }
}.getOrNull()

/** A malformed report cannot hide healthy siblings; a malformed or oversized envelope still fails closed. */
internal fun parseDigestCommunityHistory(json: com.google.gson.JsonElement?, now: Long): List<DigestRngEntry> {
    require(json?.isJsonArray == true)
    val events = requireNotNull(json).asJsonArray
    require(events.size() <= 30)
    return events.mapNotNull { event -> event.takeIf { it.isJsonObject }?.asJsonObject?.let { parseDigestCommunityEvent(it, now) } }
        .distinctBy { it.id }
}
