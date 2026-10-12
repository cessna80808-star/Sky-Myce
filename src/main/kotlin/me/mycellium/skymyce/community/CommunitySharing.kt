package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.CommunitySharingConfig
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.seconds

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

    private fun config() = SharingPolicy(CommunitySharingConfig.enabled, CommunitySharingConfig.dungeons, CommunitySharingConfig.economy,
        CommunitySharingConfig.rng, CommunitySharingConfig.activity, CommunitySharingConfig.leaderboards, CommunitySharingConfig.publicName)

    private fun initialize() {
        if (initialized) return
        initialized = true
        CommunitySharingConfig.migrateLegacy()
        lastConfig = config()
        SharingPolicy.parse(CommunitySharingConfig.syncedPolicy)?.let { previous -> dirty.addAll(SharingPolicy.keys.filter { previous.value(it) != lastConfig.value(it) }) }
        SkyMyce.config.save()
    }

    fun settingsChanged() {
        initialize()
        val current = config()
        dirty.addAll(SharingPolicy.keys.filter { current.value(it) != lastConfig.value(it) })
        lastConfig = current
        if (dirty.isNotEmpty()) { synchronized = false; nextRefresh = 0; status = "Saving account sharing preferences…" }
    }

    fun canShare(category: SharingCategory): Boolean = CommunityClient.connected && synchronized && config().allows(category) && server?.policy?.allows(category) == true
    fun showPublicName(): Boolean = config().publicName && server?.policy?.publicName == true && synchronized

    /** New observed measurements only. Bounded best-effort submission never replays saved history after reconnect. */
    fun contribute(id: String, metric: String, value: Double, observedAt: Long, category: SharingCategory,
                   source: String = "client-observation", verification: String = "unverified") {
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
        if (!CommunityClient.connected || owner.isBlank() || !uuid.matches(Regex("[a-f0-9]{32}"))) {
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
        CommunitySharingConfig.enabled = policy.enabled; CommunitySharingConfig.dungeons = policy.dungeons
        CommunitySharingConfig.economy = policy.economy; CommunitySharingConfig.rng = policy.rng
        CommunitySharingConfig.activity = policy.activity; CommunitySharingConfig.leaderboards = policy.leaderboards
        CommunitySharingConfig.publicName = policy.publicName
        CommunitySharingConfig.syncedAccount = owner; CommunitySharingConfig.syncedPolicy = policy.json().toString()
        lastConfig = policy; dirty.clear(); firstSync = false; synchronized = true
        nextRefresh = System.currentTimeMillis() + 30000
        status = if (policy.enabled) "Account sharing preferences synchronized" else "Statistics sharing off · prior contributions removed or suppressed"
        SkyMyce.config.save()
        me.mycellium.skymyce.features.instances.dungeons.friends.FriendWealthCache.publishOwnContribution()
    }
}
