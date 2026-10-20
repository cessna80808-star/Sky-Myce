package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import me.mycellium.mixin.MinecraftAccountKeyAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

internal class RelayRetry {
    private var authenticatedAt: Long? = null
    var nextAttempt = 0L
        private set
    var delay = 1000L
        private set
    fun authenticated(now: Long) { authenticatedAt = now }
    fun stable(now: Long) {
        if (authenticatedAt?.let { now - it >= 60000 } == true) reset()
    }
    fun failed(now: Long, pause: Boolean, minimumDelay: Long = 0) {
        stable(now); authenticatedAt = null
        val wait = maxOf(delay, minimumDelay.coerceIn(0, 60000))
        nextAttempt = if (pause) Long.MAX_VALUE else now + wait
        delay = (wait * 2).coerceAtMost(60000)
    }
    fun reset() { nextAttempt = 0; delay = 1000; authenticatedAt = null }
}

internal fun relayNeedsReconnect(code: Int) = code == 4001 || code == 4004
internal fun relayCloseRetryDelay(code: Int, reason: String) = when {
    code == 1013 && reason == "traffic_cooldown" -> 60000L
    code == 1008 || code == 1013 -> 5000L
    else -> 0L
}
internal fun relayBazaarCapability(json: JsonObject) = json.get("bazaarVersion")?.let {
    it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
} == true
internal fun relayBazaarPacketAllowed(type: String, supported: Boolean, currentSession: Boolean) =
    supported && currentSession && type in setOf("bazaar_session", "bazaar_alert")
internal fun validRelayBazaarReceipt(json: JsonObject): Boolean = json.keySet() == setOf("type", "id", "active", "dmEnabled") &&
    json.get("type")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "bazaar_session_result" } == true &&
    json.get("id")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(Regex("[a-f0-9]{32}")) } == true &&
    listOf("active", "dmEnabled").all { json.get(it)?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean } == true }

internal fun retainCosmeticsDuringOutage(localAllowed: Boolean, active: Boolean, identity: String, requestedIdentity: String) =
    localAllowed && active && identity.isNotEmpty() && identity == requestedIdentity

internal fun relayPacketSizeAllowed(length: Int, type: String?) = length in 0..65536 &&
    (length <= 8192 || type in setOf("digest_news_result", "rng_result", "cosmetics_result", "irc_message", "irc_update"))

/** Turning a feature off unsubscribes its messages while the world's shared authorization remains open. */
internal fun relayActive(inWorld: Boolean, globalEnabled: Boolean, friendsActive: Boolean, connected: Boolean) =
    inWorld && (globalEnabled || friendsActive || connected)

internal fun globalRelaySessionCurrent(identity: String, activeLease: String?, endpoint: String, account: UUID, lease: String?) =
    activeLease != null && activeLease == lease && identity == "$endpoint|$account"

/** Allocate a callback snapshot only when something actually expires. Completions may enqueue new requests. */
internal fun expireRelayRequests(requests: MutableMap<String, Pair<Long, CompletableFuture<JsonObject?>>>, now: Long, cancelled: Boolean = true) {
    if (requests.isEmpty()) return
    var expired: MutableList<String>? = null
    for ((id, request) in requests) if (request.first <= now || cancelled && request.second.isCancelled) {
        if (expired == null) expired = mutableListOf()
        expired.add(id)
    }
    expired?.forEach { requests.remove(it)?.second?.complete(null) }
}

/** Exception messages can contain URLs, tokens or response bodies. Keep types and call sites only. */
internal fun relayErrorDetail(error: Throwable?): String = generateSequence(error) { it.cause }
    .take(8).joinToString(" <- ") { cause ->
        cause.javaClass.name + (cause.stackTrace.firstOrNull()?.let { " at ${it.className}.${it.methodName}:${it.lineNumber}" } ?: "")
    }

/** Incoming broadcasts must not keep a stalled outgoing write alive indefinitely. */
internal fun sendRelayPacket(previous: CompletableFuture<*>, ws: WebSocket, text: String, current: () -> Boolean,
    timeoutMillis: Long = 10000): CompletableFuture<WebSocket> = previous.thenCompose {
    if (current()) ws.sendText(text, true).orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
    else CompletableFuture.completedFuture(ws)
}

internal fun closeRelaySocket(ws: WebSocket, sending: CompletableFuture<*>, timeoutMillis: Long = 2000) {
    sending.handle { _, _ -> null }.thenCompose { ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnected") }
        .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete { _, error -> if (error != null) ws.abort() }
    CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute {
        if (!ws.isInputClosed || !ws.isOutputClosed) ws.abort()
    }
}

/** Migrate only the retired default; preserve custom endpoints and an intentionally disabled relay. */
fun currentRelayUrl(saved: String?): String = saved?.trim()
    ?.takeUnless { it == "wss://skyblock-relay.skyblock-relay.workers.dev/websocket" }
    ?: "wss://sky-myce-backend.skyblock-relay.workers.dev/websocket"

fun relayUri(text: String): URI? = runCatching {
    require(text.length <= 512)
    URI(text.trim()).also {
        require(it.scheme == "wss" || (it.scheme == "ws" && it.host in setOf("localhost", "127.0.0.1", "[::1]")))
        require(!it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.path == "/websocket")
        require(it.rawQuery == null || it.rawQuery.matches(Regex("room=[a-z0-9_-]{1,32}")))
    }
}.getOrNull()

/** Only a receipt from the addressed player's mod cancels the fallback. */
class RelayDeliveries {
    private data class Pending(val name: String, var deadline: Long, val received: () -> Unit,
        val failed: (String) -> Unit, var transmit: (() -> Boolean)?, val notifyOnClear: Boolean)
    private val pending = linkedMapOf<String, Pending>()
    val full get() = pending.size >= 16
    val size get() = pending.size
    fun add(id: String, name: String, now: Long, received: () -> Unit, failed: (String) -> Unit,
            notifyOnClear: Boolean = false, transmit: (() -> Boolean)? = null) {
        check(!full && id !in pending)
        pending[id] = Pending(name, now + 5000, received, failed, transmit, notifyOnClear)
    }
    fun acknowledge(id: String, from: String) {
        val item = pending[id]?.takeIf { it.transmit == null && it.name.equals(from, true) } ?: return
        pending.remove(id)
        item.received()
    }
    fun fail(id: String, reason: String = "receipt_timeout") { pending.remove(id)?.failed?.invoke(reason) }
    fun tick(now: Long) {
        for ((id, item) in pending.toMap()) {
            if (now >= item.deadline) fail(id, if (item.transmit != null) "connection_not_ready" else "receipt_timeout")
            else if (item.transmit?.invoke() == true) {
                item.transmit = null
                item.deadline = now + 5000
            }
        }
    }
    fun connectionFailed() { pending.filterValues { it.transmit == null }.keys.toList().forEach { fail(it, "connection_lost") } }
    fun clear(failed: Boolean = false) {
        val old = pending.values.toList()
        pending.clear()
        old.filter { failed || it.notifyOnClear }.forEach { it.failed("connection_lost") }
    }
}

/** Network callbacks enter the Minecraft thread before touching game state or pending deliveries. */
object DungeonFriendRelay {
    data class PartyPolicy(val uuid: String, val policy: DungeonJoinPolicy)
    private val gson = Gson()
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val deliveries = RelayDeliveries()
    private val statsRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val digestRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val cosmeticsRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val globalRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val partyRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    var cosmeticsAvailable = false
        private set
    var cosmeticsNamesAvailable = false
        private set
    var digestNewsAvailable = false
        private set
    var rngFeedAvailable = false
        private set
    var userMessagesAvailable = false
        private set
    var ircAvailable = false
        private set
    var globalChatAvailable = false
        private set
    var richChatAvailable = false
        private set
    var partyChatAvailable = false
        private set
    var leapPovAvailable = false
        private set
    var bazaarAvailable = false
        private set
    internal val sessionGeneration get() = generation
    internal val ircRoom get() = relayUri(DungeonFriendsSettings.relayUrl)?.rawQuery?.substringAfter("room=") ?: "friends"
    private var activeLease: String? = null
    var onDigestEvent: ((JsonObject) -> Unit)? = null
    var onDigestReady: (() -> Unit)? = null
    var onDigestClosed: (() -> Unit)? = null
    internal var onLeapPovEvent: ((JsonObject) -> Unit)? = null
    internal var onLeapPovClosed: (() -> Unit)? = null
    private val partyPolicies = object : LinkedHashMap<String, Pair<Long, PartyPolicy?>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, PartyPolicy?>>) = size > 1024
    }
    private var policiesAvailable = false
    private var policyRequest: Pair<String, List<String>>? = null
    private var policyDeadline = 0L
    private var nextPolicyRequest = 0L
    private var sentPolicy: DungeonJoinPolicy? = null
    private var sentClass: DungeonClass? = null
    private var nextPolicyUpdate = 0L
    var sharedStatsAvailable = false
        private set
    private var socket: WebSocket? = null
    private var sending: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    @Volatile private var generation = 0L
    private var identity = ""
    private var connecting = false
    private var authenticating = false
    private val retry = RelayRetry()
    private var stage = "idle"
    private var deadline = 0L
    private var nextPing = 0L
    var connected = false
        private set
    var status = "Relay not configured"
        private set
    fun diagnostics(): Map<String, Long> = mapOf("relay.socket" to if (socket != null) 1L else 0L,
        "relay.connecting" to if (connecting) 1L else 0L, "relay.deliveries" to deliveries.size.toLong(),
        "relay.writePending" to if (sending.isDone) 0L else 1L,
        "relay.statsRequests" to statsRequests.size.toLong(), "relay.digestRequests" to digestRequests.size.toLong(),
        "relay.cosmeticsRequests" to cosmeticsRequests.size.toLong(), "relay.globalRequests" to globalRequests.size.toLong(),
        "relay.partyPolicies" to partyPolicies.size.toLong())

    fun tick(active: Boolean) {
        val url = DungeonFriendsSettings.relayUrl
        val user = MC.instance.user
        val key = "$url|${user.profileId}"
        if (!CommunityClient.connected) {
            if (identity.isNotEmpty()) {
                val retain = retainCosmeticsDuringOutage(true, active && url.isNotBlank(), identity, key)
                if (!retain || connected || connecting || socket != null) {
                    disconnect(clearCosmetics = !retain)
                    // Keep the boundary through the outage so reconnect does not clear this same cache.
                    if (retain) identity = key
                }
            }
            status = "Connect Discord in Sky-Myce Community settings"; return
        }
        if (!active || url.isBlank()) {
            if (identity.isNotEmpty()) disconnect()
            status = if (url.isBlank()) "Relay not configured" else "Join a world to connect the relay"
            return
        }
        if (key != identity) { disconnect(); identity = key }
        if (activeLease != CommunityClient.sessionToken) {
            // Renew the authenticated socket without discarding this same player's bounded public appearance cache.
            disconnect(clearCosmetics = false); identity = key; activeLease = CommunityClient.sessionToken
        }
        val now = DungeonFriends.now()
        if (connected) retry.stable(now)
        if (now >= policyDeadline) policyRequest = null
        deliveries.tick(now)
        expireRelayRequests(statsRequests, now, cancelled = false)
        expireRelayRequests(digestRequests, now)
        expireRelayRequests(cosmeticsRequests, now)
        expireRelayRequests(globalRequests, now, cancelled = false)
        expireRelayRequests(partyRequests, now, cancelled = false)
        if ((connecting || socket != null) && now >= deadline) failed("Relay timed out; reconnecting")
        if (connected && now >= nextPing) { packet("ping"); nextPing = now + 45000 }
        if (socket != null || connecting || now < retry.nextAttempt) return
        val uri = relayUri(url) ?: run { status = "Invalid relay URL in Settings"; return }
        connecting = true
        authenticating = false
        deadline = now + 35000
        status = "Connecting to relay..."
        stage = "websocket_upgrade"
        val token = generation
        val sessionService = MC.instance.services().sessionService()
        // No Chat Reports masks the public getter. Use the existing account manager only for our
        // domain-separated relay proof; never enable chat signing or replace Minecraft's manager.
        val keyManager = (MC.instance as MinecraftAccountKeyAccessor).`skymyce$getAccountKeyPairManager`()
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) {
                MC.instance.execute {
                    if (token != generation) closeRelaySocket(webSocket, CompletableFuture.completedFuture(null))
                    else { socket = webSocket; connecting = false; stage = "challenge" }
                }
                webSocket.request(1)
            }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                if (text.length + data.length > 65536) {
                    MC.instance.execute { if (token == generation) failed("Relay sent an oversized message") }
                    return null
                }
                text.append(data)
                if (last) {
                    val message = text.toString()
                    text.setLength(0)
                    // Decode bounded batch packets on the WebSocket executor, never during a client tick.
                    if (RuntimeProfiler.active) RuntimeProfiler.network(RuntimeProfiler.Area.RELAY_MESSAGE, message.toByteArray(Charsets.UTF_8).size.toLong())
                    val decoded = if (message == "pong") null else RuntimeProfiler.measure(RuntimeProfiler.Area.RELAY_MESSAGE) { runCatching {
                        JsonParser.parseString(message).asJsonObject.also {
                            check(relayPacketSizeAllowed(message.length, it.get("type")?.asString))
                        }
                    } }
                    MC.instance.execute {
                        if (token == generation) {
                            deadline = DungeonFriends.now() + if (connected) 90000 else 30000
                            if (message != "pong") runCatching {
                                val json = decoded!!.getOrThrow()
                                when (json.get("type")?.asString) {
                                    "challenge" -> {
                                        check(!authenticating && !connected && json.get("protocol")?.asInt == 3)
                                        val serverId = json.get("serverId").asString
                                        check(serverId.matches(Regex("[a-f0-9]{32}")))
                                        authenticating = true
                                        stage = "account_proof"
                                        status = "Verifying Minecraft account..."
                                        var proofStage = "account_key"
                                        keyManager.prepareKeyPair().thenApplyAsync { optional ->
                                            val pair = optional.orElseThrow { IllegalStateException("Minecraft account key unavailable") }
                                            val certificate = pair.publicKey().data()
                                            check(!certificate.hasExpired())
                                            proofStage = "signed_profile"
                                            val profile = sessionService.fetchProfile(user.profileId, true)?.profile()
                                                ?: error("Signed Minecraft profile unavailable")
                                            check(profile.id() == user.profileId && profile.name().equals(user.name, true))
                                            val textures = sessionService.getPackedTextures(profile)
                                            check(textures != null && textures.hasSignature())
                                            proofStage = "sign_challenge"
                                            // Minecraft manages the private key. Only public, signed proof leaves the client.
                                            val uuid = user.profileId.toString().replace("-", "")
                                            val proof = Signature.getInstance("SHA256withRSA").run {
                                                initSign(pair.privateKey())
                                                update("SkyMyce relay v2\n$serverId\n$uuid\n${user.name}".toByteArray(Charsets.UTF_8))
                                                sign()
                                            }
                                            val base64 = Base64.getEncoder()
                                            mapOf("type" to "authenticate", "name" to user.name, "uuid" to uuid, "liveUpdates" to true, "userMessages" to true, "globalChat" to true, "cosmetics" to true, "cosmeticsVersion" to 5, "rngMetadataVersion" to 1,
                                                "expires" to certificate.expiresAt().toEpochMilli(),
                                                "publicKey" to base64.encodeToString(certificate.key().encoded),
                                                "keySignature" to base64.encodeToString(certificate.keySignature()),
                                                "proof" to base64.encodeToString(proof),
                                                "profile" to textures.value(), "profileSignature" to textures.signature()) + CommunityClient.relayAuthorization(serverId)
                                        }.whenComplete { proof, error -> MC.instance.execute {
                                            if (token == generation) {
                                                if (error != null) {
                                                    val accountKeyUnavailable = proofStage == "account_key"
                                                    failed(
                                                        if (accountKeyUnavailable) {
                                                            "Minecraft account verification unavailable; restart Minecraft or use a Microsoft-authenticated account"
                                                        } else {
                                                            "Minecraft account verification unavailable; retrying (restart Minecraft if it persists)"
                                                        },
                                                        error,
                                                        pause = accountKeyUnavailable,
                                                        failingStage = proofStage
                                                    )
                                                }
                                                else { stage = "authentication"; packet(proof) }
                                            }
                                        } }
                                    }
                                    "ready" -> {
                                        check(authenticating && !connected && json.get("protocol")?.asInt == 3)
                                        check(json.get("uuid").asString == user.profileId.toString().replace("-", ""))
                                        check(json.get("name").asString.equals(user.name, true))
                                        connected = true
                                        sharedStatsAvailable = json.get("statsCache")?.asBoolean == true
                                        policiesAvailable = json.get("partyPolicies")?.asBoolean == true
                                        digestNewsAvailable = json.get("digestNews")?.asBoolean == true
                                        rngFeedAvailable = json.get("rngFeed")?.asBoolean == true
                                        userMessagesAvailable = json.get("userMessages")?.asBoolean == true
                                        cosmeticsAvailable = json.get("cosmetics")?.asBoolean == true
                                        cosmeticsNamesAvailable = cosmeticsAvailable && json.get("cosmeticsNames")?.asBoolean == true
                                        ircAvailable = json.get("irc")?.asBoolean == true
                                        globalChatAvailable = json.get("globalChat")?.asBoolean == true
                                        richChatAvailable = richIrcCapability(json)
                                        partyChatAvailable = partyIrcCapability(json)
                                        leapPovAvailable = json.get("leapPovVersion")?.let {
                                            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
                                        } == true
                                        bazaarAvailable = relayBazaarCapability(json)
                                        retry.authenticated(DungeonFriends.now())
                                        stage = "connected"
                                        deadline = DungeonFriends.now() + 90000
                                        nextPing = DungeonFriends.now() + 45000
                                        status = "Relay connected"
                                        SkyMyce.logger.info("[Dungeon relay] Connected")
                                        onDigestReady?.invoke()
                                        me.mycellium.skymyce.features.social.CosmeticsClient.ready()
                                        RelayMessages.globalReady()
                                    }
                                    "irc_message" -> {
                                        check(connected)
                                        if (json.get("scope")?.asString == "global") {
                                            check(globalChatAvailable)
                                            RelayMessages.receiveGlobal(json, user.profileId.toString())
                                        } else if (json.get("scope")?.asString == "party") {
                                            check(partyChatAvailable)
                                            RelayMessages.receiveParty(json, user.profileId.toString())
                                        }
                                    }
                                    "irc_update" -> {
                                        check(connected && richChatAvailable)
                                        RelayMessages.receiveUpdate(json, user.profileId.toString())
                                    }
                                    "irc_result" -> {
                                        check(connected)
                                        if (json.get("scope")?.asString == "global") {
                                            globalRequests.remove(json.get("id")?.asString)?.second?.complete(json)
                                        } else if (json.get("scope")?.asString == "party") {
                                            partyRequests.remove(json.get("id")?.asString)?.second?.complete(json)
                                        }
                                    }
                                    "leap_pov_result", "leap_pov_pose", "leap_pov_remove" -> {
                                        check(connected && leapPovAvailable)
                                        onLeapPovEvent?.invoke(json)
                                    }
                                    "bazaar_session_result" -> { check(connected && bazaarAvailable && validRelayBazaarReceipt(json)) }
                                    "authorization_revoked" -> {
                                        val reason = json.get("reason")?.asString ?: "authorization_revoked"
                                        CommunityClient.onlineDenied(reason); disconnect()
                                    }
                                    "message", "user_message", "ack" -> {
                                        check(connected)
                                        val id = json.get("id").asString
                                        val from = json.get("from").asString
                                        val uuid = json.get("uuid").asString
                                        check(id.matches(Regex("[a-f0-9]{32}")) && from.matches(Regex("[A-Za-z0-9_]{1,16}")) && uuid.matches(Regex("[a-f0-9]{32}")))
                                        me.mycellium.skymyce.features.social.CosmeticsClient.observe(from,
                                            UUID.fromString("${uuid.take(8)}-${uuid.substring(8, 12)}-${uuid.substring(12, 16)}-${uuid.substring(16, 20)}-${uuid.substring(20)}"))
                                        if (json.get("type").asString == "ack") deliveries.acknowledge(id, from)
                                        else {
                                            val body = json.get("text").asString
                                            check(body.length in 1..256 && body.none { it < ' ' || it == '\u007f' || it == '§' })
                                            val ordinary = json.get("type").asString == "user_message"
                                            if (if (ordinary) RelayMessages.receive(from, uuid, body) else DungeonFriends.onRelayMessage(from, uuid, body)) {
                                                packet(mapOf("type" to "ack", "to" to from, "id" to id))
                                                SkyMyce.logger.info("[Dungeon relay] Accepted delivery {}", id)
                                            } else {
                                                if (ordinary) packet(mapOf("type" to "reject", "to" to from, "id" to id))
                                                SkyMyce.logger.info("[Dungeon relay] Rejected delivery {}", id)
                                            }
                                        }
                                    }
                                    "stats_result" -> { check(connected); statsRequests.remove(json.get("id").asString)?.second?.complete(json) }
                                    // Player Wealth was removed; relays deployed before that may still send its messages.
                                    "wealth_result", "wealth_removed" -> check(connected)
                                    "digest_news_result", "rng_result" -> { check(connected); runCatching { digestRequests.remove(json.get("id").asString)?.second?.complete(json) } }
                                    "rng_event" -> { check(connected); runCatching { onDigestEvent?.invoke(json) } }
                                    "rng_removed" -> { check(connected); runCatching { onDigestEvent?.invoke(json) } }
                                    "stats_removed" -> {
                                        check(connected)
                                        val ids = json.getAsJsonArray("uuids")?.map { it.asString }?.take(256)?.filter { it.matches(Regex("[a-f0-9]{32}")) }?.toSet().orEmpty()
                                        DungeonFriendStatsCache.removeSharedSubjects(ids)
                                    }
                                    "cosmetics_result" -> { check(connected && cosmeticsAvailable); cosmeticsRequests.remove(json.get("id").asString)?.second?.complete(json) }
                                    "cosmetics_event" -> { check(connected && cosmeticsAvailable); runCatching { me.mycellium.skymyce.features.social.CosmeticsClient.event(json.getAsJsonObject("record")) } }
                                    "stats_update" -> {
                                        check(connected)
                                        val record = json.getAsJsonObject("record")
                                        val name = record.get("name").asString
                                        val friend = tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI.getFriend(name)
                                        val self = name.equals(user.name, true)
                                        if (self || friend != null || DungeonFriendStatsCache.get(name) != null) {
                                            val uuid = (if (self) user.profileId else friend?.uuid)?.toString()?.replace("-", "")
                                            DungeonFriendStatsCache.receiveShared(record, name, uuid, System.currentTimeMillis())
                                        }
                                    }
                                    "party_update" -> {
                                        check(connected)
                                        val name = json.get("name").asString.lowercase()
                                        check(name.matches(Regex("[a-z0-9_]{1,16}")))
                                        cachePolicy(name, json)
                                    }
                                    "party_result" -> {
                                        check(connected)
                                        val request = policyRequest
                                        if (json.get("id") == null && json.has("error")) {
                                            sentPolicy = null
                                            nextPolicyUpdate = DungeonFriends.now() + 5000
                                        }
                                        if (request != null && json.get("id")?.asString == request.first) {
                                            policyRequest = null
                                            val parties = json.get("parties")?.takeIf { it.isJsonObject }?.asJsonObject
                                            if (parties == null) nextPolicyRequest = DungeonFriends.now() + 5000
                                            else for (name in request.second) {
                                                val value = parties.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
                                                cachePolicy(name, value)
                                            }
                                        }
                                    }
                                    "error" -> {
                                        check(connected)
                                        val reason = json.get("code")?.asString?.takeIf { it.matches(Regex("[a-z_]{1,32}")) } ?: "rejected"
                                        deliveries.fail(json.get("id").asString, reason)
                                    }
                                    else -> error("Unknown relay packet")
                                }
                            }.onFailure { failed("Invalid relay response; retrying with backoff", it, minimumRetry = 5000) }
                        }
                    }
                }
                webSocket.request(1)
                return null
            }
            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                MC.instance.execute { if (token == generation) failed("Unexpected relay data") }
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                MC.instance.execute { if (token == generation) {
                    val safeReason = reason.takeIf { it in setOf("traffic_cooldown", "rate_limited", "service_busy", "Invalid relay message", "Authentication required", "Incoming message rate exceeded") } ?: "unspecified"
                    SkyMyce.logger.info("[Dungeon relay] Close code={} authenticated={} stage={} reason={}", statusCode, connected, stage, safeReason)
                    failed(when (statusCode) {
                        4001 -> "Account connected elsewhere; use Reconnect in Settings"
                        4004 -> "Minecraft proof rejected; restart Minecraft or update the mod, then use Reconnect in Settings"
                        1008 -> "Relay rejected a protocol message; retrying with backoff"
                        1013 -> if (reason == "traffic_cooldown") "Community traffic cooldown; retrying after one minute" else "Community service busy or rate limited; retrying with backoff"
                        else -> "Relay closed ($statusCode); reconnecting"
                    }, pause = relayNeedsReconnect(statusCode), graceful = true, minimumRetry = relayCloseRetryDelay(statusCode, reason))
                    if (statusCode == 4005) CommunityClient.onlineDenied("session_revoked")
                } }
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) {
                MC.instance.execute { if (token == generation) failed("Relay unavailable; reconnecting", error) }
            }
        }
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener)
            .whenComplete { _, error -> if (error != null) MC.instance.execute {
                if (token == generation) failed("Relay unavailable; reconnecting", error)
            } }
    }

    fun send(name: String, text: String, received: () -> Unit = {}, failed: () -> Unit = {}): Boolean {
        return sendDelivery("message", name, text, received) { failed() }
    }

    fun globalPacket(type: String, fields: Map<String, Any>, result: (String?) -> Unit): Boolean = ircPacket("global", type, fields, result)

    internal fun ircPacket(scope: String, type: String, fields: Map<String, Any>, result: (String?) -> Unit,
        receipt: (JsonObject) -> Unit = {}): Boolean {
        if (scope !in setOf("global", "party")) return false
        val requests = if (scope == "global") globalRequests else partyRequests
        val available = if (scope == "global") globalChatAvailable else partyChatAvailable
        if (!connected || !available || !currentGlobalSession() ||
            requests.size >= 8 || type !in setOf("irc_send", "irc_subscribe", "irc_operation") || type == "irc_operation" && !richChatAvailable) return false
        val expectedParty = if (scope == "party") RelayMessages.partyId else null
        if (scope == "party" && type != "irc_subscribe" && expectedParty == null) return false
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        requests[id] = DungeonFriends.now() + 12000 to future
        future.thenAccept { response ->
            val reason = if (response == null) "receipt_timeout" else response.get("error")?.asString
                ?: if (scope == "party" && fields["enabled"] != false && response.get("room")?.asString != expectedParty) "invalid_route"
                else if (scope == "party" && type == "irc_subscribe" && fields["enabled"] == true &&
                    ircPartyGrantMillis(response, expectedParty, System.currentTimeMillis()) == null) "not_subscribed" else null
            if (reason == null && response != null) receipt(response)
            result(reason)
        }
        val route = if (scope == "global") mapOf("scope" to "global", "room" to "global-chat")
            else if (type == "irc_subscribe") mapOf("scope" to "party") else mapOf("scope" to "party", "room" to (expectedParty ?: return false))
        packet(fields + route + mapOf("type" to type, "id" to id))
        return true
    }

    internal fun currentGlobalSession() = connected && CommunityClient.connected &&
        globalRelaySessionCurrent(identity, activeLease, DungeonFriendsSettings.relayUrl, MC.instance.user.profileId,
            CommunityClient.sessionToken)

    internal fun bazaarPacket(type: String, fields: Map<String, Any>, current: () -> Boolean): Boolean {
        if (!relayBazaarPacketAllowed(type, bazaarAvailable, currentGlobalSession()) || !current()) return false
        packet(fields + ("type" to type), current)
        return true
    }

    internal fun leapPovPacket(type: String, fields: Map<String, Any>, current: () -> Boolean, dropIfBusy: Boolean = false): Boolean {
        if (type !in setOf("leap_pov_subscribe", "leap_pov_pose") || !leapPovAvailable || !currentGlobalSession() ||
            !current() || dropIfBusy && !sending.isDone) return false
        packet(fields + ("type" to type), current)
        return true
    }

    fun sendUserMessage(name: String, text: String, received: () -> Unit, failed: (String) -> Unit): Boolean {
        if (!connected || !userMessagesAvailable) return false
        return sendDelivery("user_message", name, text, received, failed)
    }

    private fun sendDelivery(type: String, name: String, text: String, received: () -> Unit, failed: (String) -> Unit): Boolean {
        if (deliveries.full || relayUri(DungeonFriendsSettings.relayUrl) == null || !name.matches(Regex("[A-Za-z0-9_]{1,16}")) ||
            text.length !in 1..256 || text.any { it < ' ' || it == '\u007f' || it == '§' }) {
            SkyMyce.logger.info("[Dungeon relay] Send rejected: invalid message/configuration or full delivery queue")
            return false
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        deliveries.add(id, name, DungeonFriends.now(), {
            SkyMyce.logger.info("[Dungeon relay] Delivery {} to {} acknowledged", id, name)
            received()
        }, { reason ->
            SkyMyce.logger.info("[Dungeon relay] Delivery {} to {} failed: {}", id, name, reason)
            failed(reason)
        }, notifyOnClear = type == "user_message", transmit = {
            if (!connected) false else {
                packet(mapOf("type" to type, "id" to id, "to" to name, "text" to text))
                SkyMyce.logger.info("[Dungeon relay] Sent delivery {} to {}", id, name)
                true
            }
        })
        deliveries.tick(DungeonFriends.now())
        return true
    }

    fun lookupStats(name: String, uuid: String?): CompletableFuture<JsonObject?>? {
        if (!sharedStatsAvailable) return null
        return lookupCache("stats_get", name, uuid)
    }

    fun cosmeticsRequest(type: String, fields: Map<String, Any> = emptyMap()): CompletableFuture<JsonObject?>? {
        cosmeticsRequests.entries.removeIf { it.value.second.isDone }
        if (!connected || !cosmeticsAvailable || cosmeticsRequests.size >= 2 || type !in setOf("cosmetics_get", "cosmetics_link", "cosmetics_subscribe")) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        cosmeticsRequests[id] = DungeonFriends.now() + 8000 to future
        packet(fields + mapOf("type" to type, "id" to id))
        return future
    }

    fun disableCosmetics() {
        if (connected && cosmeticsAvailable) packet(mapOf("type" to "cosmetics_subscribe", "id" to UUID.randomUUID().toString().replace("-", ""), "enabled" to false))
    }

    /** Separate small queue so digest refreshes cannot consume party/stats lookup slots. */
    fun digestRequest(type: String, fields: Map<String, Any> = emptyMap()): CompletableFuture<JsonObject?>? {
        digestRequests.entries.removeIf { it.value.second.isDone }
        if (!connected || digestRequests.size >= 4 || type !in setOf("digest_news_get", "rng_subscribe", "rng_publish")) return null
        if (type == "digest_news_get" && !digestNewsAvailable || type != "digest_news_get" && !rngFeedAvailable) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        digestRequests[id] = DungeonFriends.now() + 12000 to future
        packet(fields + mapOf("type" to type, "id" to id))
        return future
    }

    /** Privacy opt-out cannot wait for a free request slot. Its optional receipt is deliberately untracked. */
    fun disableRngFeed() {
        if (connected && rngFeedAvailable) packet(mapOf("type" to "rng_subscribe", "id" to UUID.randomUUID().toString().replace("-", ""), "enabled" to false))
    }

    private fun lookupCache(type: String, name: String, uuid: String?): CompletableFuture<JsonObject?>? {
        if (statsRequests.size >= 8) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        statsRequests[id] = DungeonFriends.now() + 5000 to future
        packet(buildMap {
            put("type", type); put("id", id); put("name", name)
            uuid?.let { put("uuid", it) }
        })
        return future
    }

    fun partyPolicy(name: String): PartyPolicy? = partyPolicies[name.lowercase()]?.takeIf { it.first > DungeonFriends.now() }?.second

    private fun cachePolicy(name: String, value: JsonObject?) {
        val now = DungeonFriends.now()
        partyPolicies.entries.removeIf { it.value.first <= now }
        val policy = value?.let {
            val uuid = it.get("uuid").asString
            val floor = tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.valueOf(it.get("floor").asString)
            val limit = it.get("maxPbMillis")?.takeUnless { it.isJsonNull }?.asLong
            check(uuid.matches(Regex("[a-f0-9]{32}")) && floor in FRIEND_FLOORS && (limit == null || limit in 1..59999999))
            it.get("selectedClass")?.takeUnless { value -> value.isJsonNull }?.asString?.let { clazz ->
                DungeonFriends.onClassChange(name, uuid, DungeonClass.valueOf(clazz))
            }
            PartyPolicy(uuid, DungeonJoinPolicy(floor, limit, it.get("open").asBoolean))
        }
        partyPolicies[name] = now + 20000 to policy
    }

    fun requestPolicies(names: List<String>) {
        val now = DungeonFriends.now()
        if (!policiesAvailable || !connected || policyRequest != null || now < nextPolicyRequest) return
        val missing = names.map { it.lowercase() }.distinct().filter {
            it.matches(Regex("[a-z0-9_]{1,16}")) && (partyPolicies[it]?.first ?: 0L) <= now
        }.take(32)
        if (missing.isEmpty()) return
        val id = UUID.randomUUID().toString().replace("-", "")
        policyRequest = id to missing
        policyDeadline = now + 5000
        nextPolicyRequest = now + 1000
        packet(mapOf("type" to "party_get", "id" to id, "names" to missing))
    }

    fun publishPolicy(policy: DungeonJoinPolicy, selectedClass: DungeonClass?) {
        val now = DungeonFriends.now()
        if (!connected || !policiesAvailable || (sentPolicy == policy && sentClass == selectedClass) || now < nextPolicyUpdate) return
        sentPolicy = policy
        sentClass = selectedClass
        nextPolicyUpdate = now + 1000
        packet(mapOf("type" to "party_set", "floor" to policy.floor, "maxPbMillis" to policy.maxPbMillis, "open" to policy.open, "selectedClass" to selectedClass))
    }

    fun publishStats(name: String, uuid: String, stats: DungeonFriendStats, fetchedAt: Long, upload: String) {
        if (!sharedStatsAvailable || !me.mycellium.skymyce.community.CommunitySharing.canShare(me.mycellium.skymyce.community.SharingCategory.DUNGEONS)) return
        packet(mapOf("type" to "stats_put", "id" to UUID.randomUUID().toString().replace("-", ""), "name" to name,
            "uuid" to uuid, "stats" to stats, "fetchedAt" to fetchedAt, "upload" to upload))
    }

    private fun packet(data: Any, current: () -> Boolean = { true }) = RuntimeProfiler.measure(RuntimeProfiler.Area.RELAY_SEND) {
        val ws = socket ?: return@measure
        val token = generation
        val text = if (data is String) data else gson.toJson(data)
        if (RuntimeProfiler.active) RuntimeProfiler.network(RuntimeProfiler.Area.RELAY_SEND, text.toByteArray(Charsets.UTF_8).size.toLong())
        sending = sendRelayPacket(sending, ws, text, { token == generation && current() })
        sending.whenComplete { _, error ->
            if (error != null) MC.instance.execute { if (token == generation) failed("Relay send failed; reconnecting", error) }
        }
    }

    private fun failed(message: String, error: Throwable? = null, pause: Boolean = false, graceful: Boolean = false, failingStage: String = stage, minimumRetry: Long = 0) {
        if (message != "Relay disconnected") SkyMyce.logger.warn(
            "[Dungeon relay] {} stage={} authenticated={} retry={} exception={}",
            message, failingStage, connected, if (pause) "paused" else "${maxOf(retry.delay, minimumRetry)}ms", relayErrorDetail(error))
        generation++
        socket?.let { if (graceful) closeRelaySocket(it, sending) else it.abort() }
        socket = null
        connected = false
        sharedStatsAvailable = false
        digestNewsAvailable = false
        rngFeedAvailable = false
        userMessagesAvailable = false
        ircAvailable = false
        globalChatAvailable = false
        richChatAvailable = false
        partyChatAvailable = false
        leapPovAvailable = false
        bazaarAvailable = false
        cosmeticsAvailable = false
        cosmeticsNamesAvailable = false
        policiesAvailable = false
        partyPolicies.clear()
        policyRequest = null
        sentPolicy = null
        sentClass = null
        nextPolicyRequest = 0L
        nextPolicyUpdate = 0L
        statsRequests.values.forEach { it.second.complete(null) }
        statsRequests.clear()
        digestRequests.values.forEach { it.second.complete(null) }
        digestRequests.clear()
        me.mycellium.skymyce.features.social.CosmeticsClient.closed()
        cosmeticsRequests.values.forEach { it.second.complete(null) }
        cosmeticsRequests.clear()
        val pendingGlobal = globalRequests.values.toList()
        globalRequests.clear()
        val globalFailure = JsonObject().apply { addProperty("error", "connection_lost") }
        pendingGlobal.forEach { it.second.complete(globalFailure) }
        val pendingParty = partyRequests.values.toList()
        partyRequests.clear()
        pendingParty.forEach { it.second.complete(globalFailure) }
        onDigestClosed?.invoke()
        onLeapPovClosed?.invoke()
        connecting = false
        authenticating = false
        stage = "idle"
        sending = CompletableFuture.completedFuture(null)
        retry.failed(DungeonFriends.now(), pause, minimumRetry)
        status = message
        deliveries.connectionFailed()
    }

    fun disconnect(clearCosmetics: Boolean = true) {
        RelayMessages.reset()
        if (clearCosmetics) me.mycellium.skymyce.features.social.CosmeticsClient.clear()
        deliveries.clear()
        failed("Relay disconnected", graceful = true)
        identity = ""
        retry.reset()
    }

    fun reconnect() {
        deliveries.clear(failed = true)
        DungeonFriends.joining.reconnect()
        disconnect()
        status = "Reconnecting..."
    }
}
