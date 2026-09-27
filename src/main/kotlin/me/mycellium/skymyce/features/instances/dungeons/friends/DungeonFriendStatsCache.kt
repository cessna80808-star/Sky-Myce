package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/** Cache lookups run independently of the single, rate-limited provider/API request. */
object DungeonFriendStatsCache {
    private data class Request(val uuid: UUID?, val force: Boolean, val refreshExpired: Boolean)
    private data class SharedLookup(val response: CompletableFuture<JsonObject?>, val expires: Long)
    private val cache = mutableMapOf<String, CachedDungeonFriend>()
    private val liveClasses = mutableMapOf<String, Pair<String, DungeonClass>>()
    private val revisions = mutableMapOf<String, Long>()
    private val invalidProfiles = mutableMapOf<String, Long>()
    private val pending = linkedMapOf<String, Request>()
    private val sharedLookups = mutableMapOf<String, SharedLookup>()
    private var relayCredits = 20.0 // Leave room below the relay's 30-message burst limit for uploads.
    private var relayUpdated = 0L
    private var store: DungeonFriendStatsStore? = null
    private var dirty = false
    private var nextSave = 0L
    private var inFlight: String? = null
    private var generation = 0L
    private var nextRequest = 0L
    private var apiKey = ""
    private var feedback = ""
    val pendingCount: Int get() = pending.size + if (inFlight != null) 1 else 0
    var completedCount = 0L
        private set
    val status: String get() = feedback.ifEmpty {
        inFlight?.let { "Loading PBs: $it (${pending.size} queued)" }
            ?: if (pending.isNotEmpty()) "Loading PBs: ${pending.size} queued" else ""
    }
    var version = 0L
        private set

    val stats: Map<String, DungeonFriendStats> get() = cache.mapValues { it.value.stats }
    fun get(name: String): DungeonFriendStats? = cache[name.lowercase()]?.stats
    fun isPending(name: String): Boolean = name.lowercase().let { it == inFlight || it in pending }
    fun liveClass(name: String): DungeonClass? = name.lowercase().let { key ->
        liveClasses[key]?.takeIf { it.first == cache[key]?.uuid }?.second
    }
    fun verified(name: String): DungeonFriendStats? = cache[name.lowercase()]
        ?.takeIf { it.verifiedUntil > System.currentTimeMillis() }?.stats
    val canFetch: Boolean get() = DungeonFriendRelay.sharedStatsAvailable || DungeonFriendProfileProvider.available || PartyCommandsConfig.hypixelApiKey.isNotBlank()

    fun updateClass(name: String, uuid: String?, clazz: DungeonClass): Boolean {
        val key = name.lowercase()
        val previous = cache[key]
        val id = uuid ?: previous?.uuid ?: return false
        if (!key.matches(Regex("[a-z0-9_]{1,16}")) || !id.matches(Regex("[a-f0-9]{32}")) ||
            (previous?.uuid != null && previous.uuid != id)) return false
        liveClasses[key] = id to clazz
        if (previous?.stats?.selectedClass == clazz && previous.uuid == id) return false
        val record = previous ?: CachedDungeonFriend(DungeonFriendStats(StatsState.UNAVAILABLE), id, 0L, 0L)
        cache[key] = record.copy(uuid = id, stats = record.stats.copy(selectedClass = clazz))
        dirty = true
        version++
        return true
    }

    fun forgetLiveClass(name: String) { liveClasses.remove(name.lowercase()) }

    fun invalidateProfile(name: String, now: Long = System.currentTimeMillis()) {
        val key = name.lowercase()
        forgetLiveClass(key)
        cache.remove(key)
        sharedLookups.remove(key)
        invalidProfiles[key] = now
        revisions[key] = (revisions[key] ?: 0) + 1
        dirty = true
        version++
    }

    private fun withLiveClass(name: String, record: CachedDungeonFriend): CachedDungeonFriend =
        liveClasses[name]?.takeIf { it.first == record.uuid }?.let { record.copy(stats = record.stats.copy(selectedClass = it.second)) } ?: record

    fun request(name: String, uuid: UUID? = null, force: Boolean = false, priority: Boolean = false, bypassShared: Boolean = false, refreshExpired: Boolean = false) {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        val key = name.lowercase()
        val existing = cache[key]
        val changedPlayer = uuid != null && existing?.uuid != null && existing.uuid != uuid.toString().replace("-", "")
        if (changedPlayer) { cache.remove(key); dirty = true; version++ }
        if (key == inFlight && !force && !changedPlayer) return
        if (!force && !changedPlayer && existing != null &&
            !(if (refreshExpired) System.currentTimeMillis() >= existing.expires else existing.shouldRefresh(System.currentTimeMillis()))) return
        if (force || changedPlayer) {
            revisions[key] = (revisions[key] ?: 0) + 1
            sharedLookups.remove(key)
        }
        val queued = pending[key]
        if (bypassShared) invalidProfiles.putIfAbsent(key, System.currentTimeMillis())
        val request = Request(uuid ?: queued?.uuid, force || changedPlayer || queued?.force == true, refreshExpired || queued?.refreshExpired == true)
        if (priority) {
            pending.remove(key)
            val rest = pending.toMap()
            pending.clear()
            pending[key] = request
            pending.putAll(rest)
        } else pending[key] = request
    }

    /** Recheck at dequeue: another request or relay report may have refreshed this player meanwhile. */
    internal fun queuedNeedsFetch(name: String, now: Long): Boolean {
        val request = pending[name] ?: return false
        val record = cache[name] ?: return true
        return request.force || if (request.refreshExpired) now >= record.expires else record.shouldRefresh(now)
    }

    fun initialize(file: Path) {
        val persistence = DungeonFriendStatsStore(file)
        try {
            cache.putAll(persistence.load())
            store = persistence
            version++
        } catch (_: Exception) {
            // Preserve unreadable data rather than silently overwriting it.
            SkyMyce.logger.warn("Could not read saved dungeon friend stats; using memory for this session")
        }
    }

    fun save(force: Boolean = false) {
        val persistence = store ?: return
        val now = System.currentTimeMillis()
        if (!dirty || (!force && now < nextSave)) return
        nextSave = now + 30000
        try {
            persistence.save(cache.toMap())
            dirty = false
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend stats; will retry")
        }
    }

    fun disconnect() {
        save(true)
        liveClasses.clear()
        generation++
        pending.clear()
        sharedLookups.clear()
        feedback = ""
    }

    fun clear() {
        generation++
        liveClasses.clear()
        cache.clear()
        revisions.clear()
        invalidProfiles.clear()
        pending.clear()
        sharedLookups.clear()
        feedback = ""
        version++
    }

    /** Retain displayed PBs and the server's retry deadline while requesting fresh stats. */
    fun refresh() {
        cache.replaceAll { _, value -> if ((value.stats.catacombs ?: 0) > 40) value.copy(expires = 0L) else value }
    }

    /** Live relay reports update eligibility and visible rows without another refresh or API lookup. */
    internal fun receiveShared(json: JsonObject, name: String, uuid: String?, now: Long): Boolean {
        val key = name.lowercase()
        if (!key.matches(Regex("[a-z0-9_]{1,16}"))) return false
        val previous = cache[key]
        val record = sharedDungeonFriend(json, name, uuid ?: previous?.uuid, now)?.let { withLiveClass(key, it) } ?: return false
        if (!afterProfileChange(key, record)) return false
        if (previous != null && previous.verifiedUntil >= record.verifiedUntil) return false
        cache[key] = record
        invalidProfiles.remove(key)
        if (pending.containsKey(key)) {
            pending.remove(key)
            sharedLookups.remove(key)
            completedCount++
        }
        dirty = true
        version++
        return true
    }

    // A profile change rejects older reports, not every future relay report. Clients without a local
    // API provider must still be able to recover when another client publishes fresh selected-profile data.
    private fun afterProfileChange(name: String, record: CachedDungeonFriend): Boolean =
        invalidProfiles[name]?.let { record.verifiedUntil - 600000 > it } ?: true

    /** A forced follow-up invalidates the older response, including its eligibility and upload. */
    internal fun storeFetched(name: String, revision: Long, fetched: DungeonFriendStats, uuid: String?, started: Long, time: Long): Boolean {
        if (revision != (revisions[name] ?: 0L)) return false
        val previous = cache[name]
        if ((previous?.verifiedUntil ?: 0L) > started + 600000) return false
        val failed = fetched.state == StatsState.UNAVAILABLE
        val value = if (failed) previous?.stats ?: fetched else fetched
        cache[name] = withLiveClass(name, CachedDungeonFriend(value, uuid, time + if (failed) 60000 else 600000,
            if (failed) previous?.verifiedUntil ?: 0L else started + 600000))
        if (!failed) invalidProfiles.remove(name)
        dirty = true
        version++
        return true
    }

    /** Runs on the client thread, including while an API request or its retry deadline is pending. */
    internal fun pollShared(now: Long, lookup: (String, String?) -> CompletableFuture<JsonObject?>?) {
        relayCredits = minOf(20.0, relayCredits + (now - relayUpdated).coerceAtLeast(0) / 1000.0)
        relayUpdated = now
        sharedLookups.entries.removeIf { it.key !in pending || it.value.expires <= now }
        for ((name, query) in sharedLookups.toMap()) {
            if (!query.response.isDone) continue
            val response = runCatching { query.response.getNow(null) }.getOrNull() ?: continue
            if (response.get("error")?.takeIf { it.isJsonPrimitive }?.asString == "rate_limited") {
                relayCredits = 0.0
                sharedLookups.remove(name)
                continue
            }
            val request = pending[name] ?: continue
            val uuid = request.uuid?.toString()?.replace("-", "") ?: cache[name]?.uuid
            val record = response.get("record")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.let { sharedDungeonFriend(it, name, uuid, now) }?.let { withLiveClass(name, it) } ?: continue
            if (!afterProfileChange(name, record)) continue
            val previous = cache[name]
            // Refresh clears expires. A valid hit must restore it even if the report is unchanged.
            cache[name] = if (previous != null && previous.verifiedUntil >= record.verifiedUntil)
                previous.copy(expires = maxOf(previous.expires, previous.verifiedUntil)) else record
            invalidProfiles.remove(name)
            pending.remove(name)
            sharedLookups.remove(name)
            completedCount++
            dirty = true
            version++
        }
        // At most three prefetched misses plus the active API request: the relay keeps four upload grants.
        if (sharedLookups.size >= 3 || relayCredits < 1) return
        val request = pending.entries.firstOrNull { it.key !in sharedLookups } ?: return
        val uuid = request.value.uuid?.toString()?.replace("-", "") ?: cache[request.key]?.uuid
        val response = lookup(request.key, uuid) ?: return
        relayCredits--
        sharedLookups[request.key] = SharedLookup(response, now + 110000)
    }

    fun tick() {
        save()
        val key = PartyCommandsConfig.hypixelApiKey.trim()
        if (key != apiKey) {
            apiKey = key
            disconnect()
            cache.replaceAll { _, value -> if (value.stats.state == StatsState.UNAVAILABLE) value.copy(expires = 0L) else value }
            nextRequest = 0L
        }
        if (!canFetch) {
            feedback = "Use SkyBlockPv / SkyBlocker or add an API key"
            pending.clear()
            sharedLookups.clear()
            return
        }
        val now = System.currentTimeMillis()
        if (DungeonFriendRelay.sharedStatsAvailable) pollShared(now, DungeonFriendRelay::lookupStats)
        else sharedLookups.clear()
        if (inFlight != null || now < nextRequest) return
        val request = pending.entries.firstOrNull {
            !DungeonFriendRelay.sharedStatsAvailable || sharedLookups[it.key]?.response?.isDone == true
        } ?: return
        val fetch = queuedNeedsFetch(request.key, now)
        pending.remove(request.key)
        val sharedLookup = sharedLookups.remove(request.key)
        if (!fetch) { completedCount++; return }
        val knownUuid = request.value.uuid?.toString()?.replace("-", "") ?: cache[request.key]?.uuid
        inFlight = request.key
        val token = generation
        val revision = revisions[request.key] ?: 0L
        feedback = ""
        Scheduling.schedule(0.seconds) {
            var resolvedUuid = knownUuid
            var result = DungeonFriendStats(StatsState.UNAVAILABLE)
            var retryAfter = 2000L
            var message = ""
            var upload: String? = null
            try {
                val response = runCatching { sharedLookup?.response?.getNow(null) }.getOrNull()
                upload = response?.get("upload")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                if (resolvedUuid == null) {
                    resolvedUuid = Http.get("https://api.mojang.com/users/profiles/minecraft/${request.key}") {
                        val body = asText()
                        check(isOk) { "Name lookup unavailable" }
                        JsonParser.parseString(body).asJsonObject.get("id").asString
                    }
                }
                require(resolvedUuid!!.matches(Regex("[a-fA-F0-9]{32}")))
                val profileUuid = UUID.fromString(resolvedUuid.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                val provided = DungeonFriendProfileProvider.fetch(request.key, profileUuid)
                result = if (provided != null) provided else {
                    check(key.isNotEmpty()) { "Profile providers unavailable" }
                    Http.get(
                        "https://api.hypixel.net/v2/skyblock/profiles",
                        queries = mapOf("uuid" to resolvedUuid),
                        headers = mapOf("API-Key" to key),
                    ) {
                        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                        val reset = header("RateLimit-Reset")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
                        if (header("RateLimit-Remaining")?.toIntOrNull() == 0) retryAfter = maxOf(retryAfter, reset * 1000)
                        val body = asText() // Consume/close the response even on errors.
                        when (statusCode) {
                            429 -> {
                                retryAfter = maxOf(reset, header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60) * 1000
                                message = "API rate limit reached; waiting before retrying"
                                DungeonFriendStats(StatsState.UNAVAILABLE)
                            }
                            401, 403 -> {
                                retryAfter = 300000
                                message = "API key rejected; update it in API setup"
                                DungeonFriendStats(StatsState.UNAVAILABLE)
                            }
                            else -> {
                                check(isOk)
                                DungeonFriendStats.fromProfiles(JsonParser.parseString(body).asJsonObject, resolvedUuid)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Stats temporarily unavailable; retrying later"
                retryAfter = maxOf(retryAfter, 10000)
            } finally {
                val fetched = result
                val uuid = resolvedUuid?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                val retry = retryAfter
                val requestFeedback = message
                MC.instance.execute {
                    inFlight = null
                    if (token == generation) {
                        completedCount++
                        val time = System.currentTimeMillis()
                        val failed = fetched.state == StatsState.UNAVAILABLE
                        val stored = storeFetched(request.key, revision, fetched, uuid, now, time)
                        if (stored && !failed && uuid != null && upload != null && sharedLookup != null && time < sharedLookup.expires) {
                            DungeonFriendRelay.publishStats(request.key, uuid, cache.getValue(request.key).stats, now, upload)
                            relayCredits--
                        }
                        nextRequest = time + retry
                        feedback = requestFeedback
                    }
                }
            }
        }
    }
}
