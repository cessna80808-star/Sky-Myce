package me.mycellium.skymyce.features.instances.dungeons.splits

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

internal const val SPLIT_STORE_VERSION = 1
/** Newest runs are kept; older ones are dropped once this many are saved across all floors. */
internal const val SPLIT_STORE_LIMIT = 4000
private const val MAX_MARK = 6 * 60 * 60 * 1000L

internal fun encodeSplitRun(run: SplitRunRecord) = JsonObject().apply {
    addProperty("id", run.id); addProperty("floor", run.floor); addProperty("definition", run.definition)
    addProperty("recordedAt", run.recordedAt); addProperty("startObserved", run.startObserved)
    addProperty("outcome", run.outcome.name)
    if (run.conflicts > 0) addProperty("conflicts", run.conflicts)
    add("marks", JsonObject().apply { run.marks.forEach { (milestone, at) -> addProperty(milestone.name, at) } })
}

/** Rejects malformed rows instead of repairing them, so a damaged record can never produce a better time. */
internal fun parseSplitRun(element: JsonElement?): SplitRunRecord? = runCatching {
    val row = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun string(key: String) = row.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun long(value: JsonElement?) = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
        ?.takeIf { it.matches(Regex("-?[0-9]{1,15}")) }?.toLong()
    val id = string("id")?.takeIf { it.length in 1..64 } ?: return null
    val floor = string("floor")?.takeIf { it in splitDefinitions } ?: return null
    val definition = long(row.get("definition"))?.toInt() ?: return null
    val recordedAt = long(row.get("recordedAt"))?.takeIf { it > 0 } ?: return null
    val start = row.get("startObserved")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
    val outcome = SplitOutcome.entries.firstOrNull { it.name == string("outcome") } ?: return null
    val conflicts = row.get("conflicts")?.let { long(it)?.toInt() ?: return null } ?: 0
    val marks = row.get("marks")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val parsed = LinkedHashMap<SplitMilestone, Long>()
    for ((key, value) in marks.entrySet()) {
        // A milestone from a newer definition is unknown here; the record keeps only what this version understands.
        val milestone = SplitMilestone.entries.firstOrNull { it.name == key } ?: continue
        parsed[milestone] = long(value)?.takeIf { it in 0..MAX_MARK } ?: return null
    }
    if (start && parsed[SplitMilestone.START]?.let { it != 0L } == true) return null
    SplitRunRecord(id, floor, definition, recordedAt, start, parsed, outcome, conflicts.coerceAtLeast(0))
}.getOrNull()

internal fun encodeSplitStore(runs: List<SplitRunRecord>) = JsonObject().apply {
    addProperty("version", SPLIT_STORE_VERSION)
    add("runs", JsonArray().apply { runs.forEach { add(encodeSplitRun(it)) } })
}

internal fun parseSplitStore(root: JsonElement?): List<SplitRunRecord> {
    val source = root?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    if (source.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt != SPLIT_STORE_VERSION) return emptyList()
    val rows = source.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return rows.asSequence().mapNotNull(::parseSplitRun).distinctBy { it.id }.sortedBy { it.recordedAt }.toList().takeLast(SPLIT_STORE_LIMIT)
}

/** Local history in config/skymyce/dungeon-splits.json; written atomically off the render thread. */
object DungeonSplitStore {
    private var saved: List<SplitRunRecord> = emptyList()
    private val pending = mutableListOf<SplitRunRecord>()
    private var loaded = false
    private var initialized = false
    private lateinit var writer: StateWriter<JsonObject>
    var status = "Loading saved splits…"
        private set
    var revision = 0L
        private set
    val runs: List<SplitRunRecord> get() = saved

    fun initialize() {
        if (initialized) return
        initialized = true
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("dungeon-splits.json"), 8 * 1024 * 1024)
        writer = StateWriter({ file.write(it) }, { action -> Scheduling.schedule(250.milliseconds) { action() } }, {
            SkyMyce.logger.warn("Could not save dungeon splits; previous file retained")
        })
        Scheduling.schedule(0.milliseconds) {
            val decoded = runCatching { parseSplitStore(file.read()) }
                .onFailure { SkyMyce.logger.warn("Could not read dungeon splits; starting with an empty history") }.getOrDefault(emptyList())
            val damaged = file.damaged
            MC.instance.execute {
                saved = (decoded + pending).distinctBy { it.id }.takeLast(SPLIT_STORE_LIMIT)
                val edited = pending.isNotEmpty()
                pending.clear(); loaded = true; revision++
                status = if (damaged) "Saved splits were unreadable; the original file is kept" else "${saved.size} saved runs"
                if (edited) persist()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Dungeon-Splits-Save"))
    }

    fun record(run: SplitRunRecord) {
        if (!loaded) { pending.removeAll { it.id == run.id }; pending += run; return }
        saved = (saved.filter { it.id != run.id } + run).takeLast(SPLIT_STORE_LIMIT)
        revision++
        status = "${saved.size} saved runs"
        persist()
    }

    fun clear(floor: String) {
        if (!loaded) return
        saved = saved.filter { it.floor != floor }
        revision++; status = "${saved.size} saved runs"; persist()
    }

    private fun persist() { if (::writer.isInitialized) writer.submit(encodeSplitStore(saved)) }
}
