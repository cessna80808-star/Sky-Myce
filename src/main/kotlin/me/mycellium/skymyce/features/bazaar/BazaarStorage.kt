package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Background-thread IO for one account's append-only private journal. Events live in fixed-size segments; the
 * small index commits last, so an interrupted write can only leave an uncommitted tail.
 */
class BazaarStorage(folder: Path) {
    private val folder = folder.toAbsolutePath().normalize()
    private val index = AtomicJsonFile(this.folder.resolve("index.json"), 32 * 1024 * 1024)
    private var committed = emptyList<BazaarEvent>()
    private var committedSessions = emptyList<BazaarSession>()
    private var loaded = false
    var writable = true
        private set
    var error: String? = null
        private set

    /** Market context that older versions stored inside the journal index, handed over once to the market cache. */
    @Volatile var legacyMarket: BazaarMarketArchive? = null
        private set

    @Synchronized fun load(): BazaarArchive = try {
        val json = index.read()
        require(!index.damaged) { "Unreadable private Bazaar index; original retained" }
        if (json == null) {
            committed = emptyList(); committedSessions = emptyList(); loaded = true
            BazaarArchive()
        } else {
            val root = json.asJsonObject
            require(root.get("storageVersion")?.asInt == 1)
            val count = requireNotNull(root.get("eventCount")).asBigDecimal.intValueExact()
            require(count in 0..20_000_000)
            val metadata = parseBazaarArchive(root.get("metadata"))
            require(metadata.events.isEmpty())
            legacyMarket = runCatching { legacyBazaarMarket(root.get("metadata")) }.getOrNull()
            val events = ArrayList<BazaarEvent>(minOf(count, 100_000))
            repeat((count + SEGMENT_SIZE - 1) / SEGMENT_SIZE) { part ->
                val file = segment(part)
                val content = file.read()
                require(!file.damaged && content != null && content.isJsonObject) { "Unreadable private Bazaar segment; recording paused" }
                val array = requireNotNull(content.asJsonObject.get("events")).asJsonArray
                val needed = minOf(SEGMENT_SIZE, count - part * SEGMENT_SIZE)
                require(array.size() in needed..SEGMENT_SIZE)
                events += array.take(needed).map { Gson().fromJson(it, BazaarEvent::class.java).also(::validateBazaarEvent) }
            }
            require(events.distinctBy { it.context.key to it.id }.size == events.size)
            validateReplacements(events)
            committed = events.toList(); committedSessions = metadata.sessions
            loaded = true; writable = true; error = null
            metadata.copy(events = events)
        }
    } catch (failure: Exception) {
        writable = false; error = "Private Bazaar history unreadable; originals retained and recording paused"
        throw IllegalStateException(error, failure)
    }

    @Synchronized fun save(archive: BazaarArchive) {
        check(writable) { error ?: "Private Bazaar history is read-only" }
        if (!loaded) load()
        require(archive.version == 1 && archive.events.size <= 20_000_000)
        require(archive.events.size >= committed.size && archive.events.subList(0, committed.size) == committed) {
            "Private history removal requires explicit deletion"
        }
        val metadata = archive.copy(events = emptyList())
        parseBazaarArchive(Gson().toJsonTree(metadata))
        val changedSessions = archive.sessions.associateBy { it.id }
        // Replay metadata may be explicitly deleted independently; its accounting events remain append-only.
        require(committedSessions.all { old ->
            changedSessions[old.id]?.let { next ->
                old.context == next.context && old.startedAt == next.startedAt && old.island == next.island && (old.endedAt == null || old == next)
            } != false
        }) { "Existing private sessions cannot be rewritten" }
        archive.events.drop(committed.size).forEach(::validateBazaarEvent)
        require(archive.events.distinctBy { it.context.key to it.id }.size == archive.events.size)
        validateReplacements(archive.events)
        if (archive.events.size > committed.size) {
            val first = committed.size / SEGMENT_SIZE
            val last = (archive.events.size - 1) / SEGMENT_SIZE
            for (part in first..last) segment(part).write(JsonObject().apply {
                addProperty("version", 1)
                add("events", Gson().toJsonTree(archive.events.subList(part * SEGMENT_SIZE, minOf((part + 1) * SEGMENT_SIZE, archive.events.size))))
            })
        }
        index.write(JsonObject().apply {
            addProperty("storageVersion", 1)
            addProperty("eventCount", archive.events.size)
            add("metadata", Gson().toJsonTree(metadata))
        })
        committed = archive.events.toList(); committedSessions = archive.sessions.toList()
        legacyMarket = null
    }

    fun export(archive: BazaarArchive): String = Gson().toJson(archive)

    /** Explicit local deletion only; does not alter backend contributions or replay publications. */
    @Synchronized fun delete() {
        if (Files.isDirectory(folder)) Files.list(folder).use { files ->
            files.filter { path ->
                val name = path.fileName.toString()
                name.matches(journalFile) && path.toAbsolutePath().normalize().parent == folder
            }.forEach { Files.deleteIfExists(it) }
        }
        committed = emptyList(); committedSessions = emptyList(); writable = true; error = null; loaded = true
    }

    private fun segment(part: Int): AtomicJsonFile {
        require(part in 0..99_999_999)
        return AtomicJsonFile(folder.resolve("events-${part.toString().padStart(8, '0')}.json"), 4 * 1024 * 1024)
    }

    companion object {
        private const val SEGMENT_SIZE = 2048
        private val journalFile = Regex("(?:(?:index\\.json|events-[0-9]{8}\\.json)(?:\\.[A-Za-z0-9-]+)?|\\.skymyce-[A-Za-z0-9-]+\\.tmp)")
    }
}

fun parseBazaarArchive(json: JsonElement?): BazaarArchive {
    if (json == null) return BazaarArchive()
    require(json.isJsonObject && json.asJsonObject.get("version")?.asInt == 1)
    val parsed = Gson().fromJson(json, BazaarArchive::class.java)
    val archive = BazaarArchive(1, requireNotNull(parsed.events), requireNotNull(parsed.sessions))
    require(archive.events.size <= 20_000_000 && archive.sessions.size <= 100_000)
    archive.events.forEach(::validateBazaarEvent)
    require(archive.events.distinctBy { it.context.key to it.id }.size == archive.events.size)
    validateReplacements(archive.events)
    require(archive.sessions.map { it.id }.distinct().size == archive.sessions.size)
    archive.sessions.forEach { session ->
        require(validBazaarId(session.id) && validBazaarContext(session.context) && validBazaarId(session.island, 160) &&
            session.startedAt > 0 && (session.endedAt == null || session.endedAt >= session.startedAt) && validBazaarId(session.reason, 160))
    }
    return archive
}

/** Versions up to 2.7.5 kept public market context in the journal's metadata under these names. */
internal fun legacyBazaarMarket(json: JsonElement?): BazaarMarketArchive? {
    val metadata = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (listOf("marketHistory", "marketAggregates", "marketRegimes").none(metadata::has)) return null
    val legacy = JsonObject().apply {
        addProperty("version", 1)
        metadata.get("marketHistory")?.let { add("snapshots", it) }
        metadata.get("marketAggregates")?.let { add("aggregates", it) }
        metadata.get("marketRegimes")?.let { add("regimes", it) }
    }
    return parseBazaarMarketArchive(legacy)
}

private fun validateReplacements(events: List<BazaarEvent>) {
    val originals = events.associateBy { it.context.key to it.id }
    events.filter { it.replacesId != null }.forEach { event ->
        validateBazaarReplacement(event, requireNotNull(originals[event.context.key to event.replacesId]))
    }
}
