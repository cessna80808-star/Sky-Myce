package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.CommunitySharingConfig
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.features.instances.dungeons.tracker.*
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling

/** Compiled once; checked every tick. */
private val communityUuid = Regex("[a-f0-9]{32}")

/** Synchronize consent before contribution traffic. An unavailable privacy service leaves local features and chat alone. */
object CommunitySharing {
    var status = "Connect Discord to synchronize community sharing"
        private set
    private var initialized = false
    private var generation = Long.MIN_VALUE
    private var epoch = 0L
    private var owner = ""
    private var uuid = ""
    private var lastConfig = SharingPolicy()
    private var server: SharingSnapshot? = null
    private val dirty = mutableSetOf<String>()
    private var busy = false
    private var nextRefresh = 0L
    private var firstSync = true
    private var synchronized = false
    private var contributionJobs = 0

    private fun config() = CommunitySharingConfig.mode.policy

    private fun initialize() {
        if (initialized) return
        initialized = true
        lastConfig = config()
        SharingPolicy.parse(CommunitySharingConfig.syncedPolicy)?.let { previous -> dirty.addAll(SharingPolicy.keys.filter { previous.value(it) != lastConfig.value(it) }) }
        SkyMyce.config.save()
    }

    fun settingsChanged() {
        initialize()
        val current = config()
        // A new mode replaces the whole policy, so On also re-enables categories another device left off.
        if (current != lastConfig) dirty.addAll(SharingPolicy.keys)
        lastConfig = current
        if (dirty.isNotEmpty()) { synchronized = false; nextRefresh = 0; status = "Saving account sharing preferences…" }
    }

    fun canShare(category: SharingCategory): Boolean = CommunityClient.connected && synchronized && config().allows(category) && server?.policy?.allows(category) == true
    fun showPublicName(): Boolean = config().publicName && server?.policy?.publicName == true && synchronized

    /** New observed measurements only. Bounded best-effort submission never replays saved history after reconnect. */
    fun contribute(id: String, metric: String, value: Double, observedAt: Long, category: SharingCategory,
                   source: String = "client-observation", verification: String = "unverified", floor: String? = null) {
        if (!canShare(category) || !canShare(SharingCategory.LEADERBOARDS) || !value.isFinite() || contributionJobs >= 4 ||
            observedAt !in System.currentTimeMillis() - 300000..System.currentTimeMillis() + 60000) return
        val token = generation
        val account = owner
        val player = uuid
        val body = JsonObject().apply {
            addProperty("expectedAccountId", account); addProperty("expectedUuid", player)
            add("contribution", JsonObject().apply {
                addProperty("id", id); addProperty("metric", metric); addProperty("value", value); addProperty("observedAt", observedAt)
                addProperty("complete", true); addProperty("source", source); addProperty("verification", verification)
                floor?.let { addProperty("floor", it) }
            })
        }
        contributionJobs++
        Scheduling.schedule(0.seconds) {
            runCatching {
                check(token == CommunityClient.sessionGeneration && CommunityClient.connected)
                CommunityClient.request("/community/contributions", body)
            }
            MC.instance.execute { contributionJobs-- }
        }
    }

    /** Called on the Minecraft client thread; HTTP and parsing run on the scheduler. */
    fun tick() {
        initialize()
        if (config() != lastConfig) settingsChanged()
        val identity = CommunityClient.identity
        val currentOwner = identity?.get("accountId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val currentUuid = identity?.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString?.replace("-", "").orEmpty()
        if (generation != CommunityClient.sessionGeneration || owner != currentOwner || uuid != currentUuid) {
            generation = CommunityClient.sessionGeneration
            epoch++
            server = null; synchronized = false; busy = false; firstSync = true; nextRefresh = 0
            owner = currentOwner
            uuid = currentUuid
        }
        if (!CommunityClient.connected || owner.isBlank() || !communityUuid.matches(uuid)) {
            synchronized = false; status = "Connect Discord to synchronize community sharing"; return
        }
        val now = System.currentTimeMillis()
        if (busy || now < nextRefresh) return
        val known = server
        if (known == null || dirty.isEmpty() && !firstSync) request(null)
        else {
            val wanted = wanted(known)
            if (wanted == known.policy) accept(known)
            else request(JsonObject().apply {
                addProperty("revision", known.revision); add("policy", wanted.json())
                addProperty("expectedAccountId", owner); addProperty("expectedUuid", uuid)
            })
        }
    }

    private fun wanted(snapshot: SharingSnapshot): SharingPolicy {
        return desiredSharingPolicy(snapshot, config(), dirty, firstSync && CommunitySharingConfig.syncedAccount != owner)
    }

    private fun request(body: JsonObject?) {
        busy = true
        val token = generation
        val requestEpoch = epoch
        val requested = body?.getAsJsonObject("policy")?.let(SharingPolicy::parse)
        val sentFields = dirty.toSet()
        Scheduling.schedule(0.seconds) {
            val response = runCatching {
                check(token == CommunityClient.sessionGeneration && CommunityClient.connected)
                CommunityClient.request("/community/sharing", body, if (body == null) "GET" else "POST")
            }.getOrNull()
            MC.instance.execute {
                if (requestEpoch != epoch || token != generation || token != CommunityClient.sessionGeneration) return@execute
                busy = false
                val snapshot = response?.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject?.let(SharingSnapshot::parse)
                val ok = response?.get("ok")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
                val error = response?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                if (snapshot == null || !ok && error != "sharing_conflict") {
                    synchronized = false; nextRefresh = System.currentTimeMillis() + 15000
                    status = "Sharing preferences not synchronized · contributions paused"; return@execute
                }
                server = snapshot
                if (body != null && !ok) {
                    // Never retry an enabling edit over another device's newer opt-out without a fresh user edit.
                    dirty.removeAll { key -> config().value(key) && !snapshot.policy.value(key) }
                    firstSync = false
                    status = "Account preferences changed on another device; newer opt-outs preserved"
                } else if (requested != null) {
                    dirty.removeAll(sentFields.filter { config().value(it) == requested.value(it) }.toSet())
                    firstSync = false
                }
                if (wanted(snapshot) == snapshot.policy) accept(snapshot) else { synchronized = false; nextRefresh = 0 }
            }
        }
    }

    private fun accept(snapshot: SharingSnapshot) {
        val policy = snapshot.policy
        CommunitySharingConfig.mode = SharingMode.of(policy)
        CommunitySharingConfig.syncedAccount = owner; CommunitySharingConfig.syncedPolicy = policy.json().toString()
        // Sharing requires both the local mode and the server policy, so a partial server policy shares only what both allow.
        lastConfig = config(); dirty.clear(); firstSync = false; synchronized = true
        nextRefresh = System.currentTimeMillis() + 30000
        status = if (policy.enabled) "Account sharing preferences synchronized" else "Statistics sharing off · prior contributions removed or suppressed"
        SkyMyce.config.save()
    }
}

enum class SharingCategory(val wire: String) { DUNGEONS("dungeons"), ECONOMY("economy"), RNG("rng"), ACTIVITY("activity"), LEADERBOARDS("leaderboards") }

/** The one user-facing sharing choice. Each state maps onto the relay's unchanged seven-key policy. */
enum class SharingMode(private val title: String) {
    ON("On"), ANONYMOUS("On (anon)"), OFF("Off");

    override fun toString() = title

    val policy: SharingPolicy get() = when (this) {
        ON -> SharingPolicy(publicName = true)
        ANONYMOUS -> SharingPolicy()
        OFF -> SharingPolicy(enabled = false)
    }

    companion object {
        /** Any withheld category reads as Off, so nothing the player turned off starts being shared. */
        fun of(policy: SharingPolicy): SharingMode = when {
            !policy.enabled || SharingCategory.entries.any { !policy.value(it.wire) } -> OFF
            policy.publicName -> ON
            else -> ANONYMOUS
        }
    }
}

/**
 * One-time migration from the 2.7.29 per-category toggles, read from the raw saved file. Returns null when the file
 * already has a mode or never had sharing settings. Consumed legacy keys are removed so it runs once.
 */
internal fun migratedSharingMode(original: JsonObject): SharingMode? {
    fun JsonObject.child(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    val sharing = original.child("Community Sharing")
    val digest = original.child("General")?.child("Daily Digest")
    if (sharing?.has("mode") == true || sharing == null && digest?.has("shareRng") != true && digest?.has("publicRngName") != true) return null
    fun flag(section: JsonObject?, key: String, default: Boolean) =
        section?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default
    // The Daily Digest RNG toggles only count if they were never folded into Community Sharing; later edits there win.
    val folded = flag(sharing, "legacySharingMigrated", false)
    val categories = listOf("dungeons", "economy", "rng", "activity", "leaderboards").map { flag(sharing, it, true) } +
        if (folded) emptyList() else listOf(flag(digest, "shareRng", true))
    val named = flag(sharing, "publicName", false) || !folded && flag(digest, "publicRngName", false)
    val mode = when {
        !flag(sharing, "enabled", true) || categories.any { !it } -> SharingMode.OFF
        named -> SharingMode.ON
        else -> SharingMode.ANONYMOUS
    }
    listOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName", "legacySharingMigrated").forEach { sharing?.remove(it) }
    digest?.remove("shareRng"); digest?.remove("publicRngName")
    return mode
}

data class SharingPolicy(
    val enabled: Boolean = true, val dungeons: Boolean = true, val economy: Boolean = true,
    val rng: Boolean = true, val activity: Boolean = true, val leaderboards: Boolean = true, val publicName: Boolean = false,
) {
    fun value(key: String): Boolean = when (key) {
        "enabled" -> enabled; "dungeons" -> dungeons; "economy" -> economy; "rng" -> rng
        "activity" -> activity; "leaderboards" -> leaderboards; "publicName" -> publicName
        else -> false
    }
    fun allows(category: SharingCategory): Boolean = enabled && value(category.wire)
    fun json(): JsonObject = JsonObject().apply { addProperty("version", 1); keys.forEach { addProperty(it, value(it)) } }
    fun withValues(values: Map<String, Boolean>): SharingPolicy = parse(json().apply { values.forEach { (key, value) -> addProperty(key, value) } })!!
    fun restrictivelyMerge(other: SharingPolicy, initialPublicName: Boolean = false): SharingPolicy = withValues(keys.associateWith {
        if (it == "publicName" && initialPublicName) other.publicName else value(it) && other.value(it)
    })
    companion object {
        val keys = listOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName")
        fun parse(value: JsonObject?): SharingPolicy? = runCatching {
            require(value != null && value.get("version")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble == 1.0 } == true)
            require(keys.all { value.get(it)?.let { field -> field.isJsonPrimitive && field.asJsonPrimitive.isBoolean } == true })
            SharingPolicy(value.get("enabled").asBoolean, value.get("dungeons").asBoolean, value.get("economy").asBoolean,
                value.get("rng").asBoolean, value.get("activity").asBoolean, value.get("leaderboards").asBoolean, value.get("publicName").asBoolean)
        }.getOrNull()
        fun parse(value: String): SharingPolicy? = runCatching { parse(JsonParser.parseString(value).asJsonObject) }.getOrNull()
    }
}

data class SharingSnapshot(val policy: SharingPolicy, val revision: Long, val notBefore: Map<SharingCategory, Long>) {
    companion object {
        fun parse(json: JsonObject?): SharingSnapshot? = runCatching {
            require(json != null)
            val policy = SharingPolicy.parse(json) ?: error("Invalid sharing policy")
            val number = json.get("revision")
            require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber)
            val revision = number.asLong
            require(revision in 0..9_007_199_254_740_991L)
            require(number.asDouble == revision.toDouble())
            val cutoffs = json.getAsJsonObject("notBefore")
            SharingSnapshot(policy, revision, SharingCategory.entries.associateWith { category ->
                (cutoffs?.get(category.wire)?.asLong ?: 0L).also { require(it >= 0) }
            })
        }.getOrNull()
    }
}

/** Old-device edits may restrict a newly selected account, but cannot enable over that account's saved opt-outs. */
internal fun desiredSharingPolicy(snapshot: SharingSnapshot, local: SharingPolicy, dirty: Set<String>, firstAccountSync: Boolean): SharingPolicy {
    if (!firstAccountSync) return snapshot.policy.withValues(dirty.filter { it in SharingPolicy.keys }.associateWith(local::value))
    val merged = snapshot.policy.restrictivelyMerge(local, initialPublicName = snapshot.revision == 0L)
    // Anonymous is a fresh-device default, not an edit to an existing account's explicitly saved name choice.
    return if (snapshot.revision > 0 && "publicName" !in dirty && !local.publicName) merged.copy(publicName = snapshot.policy.publicName) else merged
}

internal data class JournalCommunityComparison(val period: String, val startAt: Long?, val endAt: Long,
    val participants: Long, val totalDrops: Long, val mean: Double?, val ownSharedCount: Long?,
    val updatedAt: Long, val source: String, val coverage: String)

internal fun journalCommunityPeriod(filter: JournalFilter): String? {
    if (filter.query.isNotBlank() || filter.activity != null || filter.floor != null || filter.includeLegacy ||
        filter.scope == TrackerScope.UNSCOPED_LEGACY) return null
    return when (filter.period) {
        JournalPeriod.ALL_TIME -> "all-time"
        JournalPeriod.WEEK -> "weekly"
        JournalPeriod.MONTH -> "monthly"
        JournalPeriod.CUSTOM -> null
    }
}

internal fun journalPeriodCounts(archive: AcquisitionArchive, filter: JournalFilter, context: AcquisitionContext?, now: Long) =
    listOf(JournalPeriod.ALL_TIME, JournalPeriod.WEEK, JournalPeriod.MONTH).associateWith { period ->
        rngJournal(archive, filter.copy(period = period, includeLegacy = false), context, now).counts.values.sum()
    }

/** Shared rng_drops counts deduplicated acquisition reports, not the quantity of items in each receipt. */
internal fun journalComparableLocalCount(archive: AcquisitionArchive, owner: String, comparison: JournalCommunityComparison): Long {
    require(owner.matches(Regex("[a-f0-9]{32}")))
    return archive.events.asSequence().filter { event ->
        event.origin == "confirmed" && event.context?.account?.replace("-", "")?.lowercase() == owner &&
            DigestRngCatalog.public(event.item, event.activity) &&
            (comparison.startAt == null || event.occurredAt >= comparison.startAt) && event.occurredAt <= comparison.endAt
    }.count().toLong()
}

internal fun parseJournalCommunityComparison(json: JsonObject, expectedPeriod: String): JournalCommunityComparison {
    fun integer(key: String): Long {
        val raw = requireNotNull(json[key]); require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber)
        val number = raw.asDouble; require(number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number == raw.asLong.toDouble())
        return raw.asLong
    }
    fun boolean(key: String) = requireNotNull(json[key]).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    }
    fun text(key: String): String = requireNotNull(json[key]).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { value ->
            require(value.length in 1..1500 && value.none(Char::isISOControl))
        }
    }
    require(boolean("ok") && integer("version") == 1L && text("metric") == "rng_drops")
    val period = text("period"); require(period == expectedPeriod && period in setOf("all-time", "weekly", "monthly"))
    val end = integer("endAt"); require(end > 0)
    val start = requireNotNull(json["startAt"]).takeUnless { it.isJsonNull }?.let { integer("startAt") }
    val selected = when (period) { "weekly" -> JournalPeriod.WEEK; "monthly" -> JournalPeriod.MONTH; else -> JournalPeriod.ALL_TIME }
    require(start == journalBounds(JournalFilter(period = selected), end).first && (start == null || start <= end))
    val participants = integer("participantCount"); val total = integer("totalDrops")
    val rawMean = requireNotNull(json["meanPerIdentity"])
    val mean = if (rawMean.isJsonNull) null else rawMean.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asDouble.also { number -> require(number.isFinite() && number >= 0) } }
    val expectedMean = if (participants > 0) total.toDouble() / participants else null
    require(if (participants == 0L) total == 0L && mean == null else mean != null &&
        abs(mean - expectedMean!!) <= maxOf(1e-9, Math.ulp(expectedMean) * 4))
    val ownAvailable = boolean("ownAvailable"); val ownRaw = requireNotNull(json["ownSharedCount"])
    val own = if (ownRaw.isJsonNull) null else integer("ownSharedCount")
    require((own != null) == ownAvailable && (own == null || own <= total))
    val updatedAt = integer("updatedAt"); require(updatedAt <= end)
    return JournalCommunityComparison(period, start, end, participants, total, mean, own, updatedAt, text("source"), text("coverage"))
}
