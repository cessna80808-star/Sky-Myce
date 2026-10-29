package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.Signature
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import me.mycellium.mixin.MinecraftAccountKeyAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.sounds.SoundEvents
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.events.hypixel.PartyInfoEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI

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
    private val RELAY_ID = Regex("[a-f0-9]{32}")
    private val RELAY_NAME = Regex("[a-z0-9_]{1,16}")
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
    var globalChatAvailable = false
        private set
    var richChatAvailable = false
        private set
    var partyChatAvailable = false
        private set
    var leapPovAvailable = false
        private set
    var greenhouseAvailable = false
        private set
    /** The relay's answer to a Greenhouse reminder upload: stored, unchanged, deleted, throttled, unavailable or invalid. */
    internal var onGreenhouseResult: ((String) -> Unit)? = null
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
                                        check(serverId.matches(RELAY_ID))
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
                                                "profile" to textures.value(), "profileSignature" to textures.signature()) + CommunityClient.relayAuthorization()
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
                                        globalChatAvailable = json.get("globalChat")?.asBoolean == true
                                        richChatAvailable = richIrcCapability(json)
                                        partyChatAvailable = partyIrcCapability(json)
                                        leapPovAvailable = json.get("leapPovVersion")?.let {
                                            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
                                        } == true
                                        greenhouseAvailable = json.get("greenhouseVersion")?.let {
                                            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
                                        } == true
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
                                    "authorization_revoked" -> {
                                        val reason = json.get("reason")?.asString ?: "authorization_revoked"
                                        CommunityClient.onlineDenied(reason); disconnect()
                                    }
                                    "message", "user_message", "ack" -> {
                                        check(connected)
                                        val id = json.get("id").asString
                                        val from = json.get("from").asString
                                        val uuid = json.get("uuid").asString
                                        check(id.matches(RELAY_ID) && from.matches(Regex("[A-Za-z0-9_]{1,16}")) && uuid.matches(RELAY_ID))
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
                                        val ids = json.getAsJsonArray("uuids")?.map { it.asString }?.take(256)?.filter { it.matches(RELAY_ID) }?.toSet().orEmpty()
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
                                        check(name.matches(RELAY_NAME))
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
                                    "greenhouse_result" -> {
                                        check(connected)
                                        val result = (json.get("result") ?: json.get("error"))?.asString?.takeIf { it.matches(Regex("[a-z_]{1,32}")) } ?: "invalid"
                                        onGreenhouseResult?.invoke(result)
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

    internal fun leapPovPacket(type: String, fields: Map<String, Any>, current: () -> Boolean, dropIfBusy: Boolean = false): Boolean {
        if (type !in setOf("leap_pov_subscribe", "leap_pov_pose") || !leapPovAvailable || !currentGlobalSession() ||
            !current() || dropIfBusy && !sending.isDone) return false
        packet(fields + ("type" to type), current)
        return true
    }

    /** Uploads the Greenhouse reminder schedule; the relay answers with a greenhouse_result. */
    internal fun greenhouseSchedule(fields: Map<String, Any>): Boolean {
        if (!connected || !greenhouseAvailable) return false
        packet(fields + mapOf("type" to "greenhouse_schedule", "id" to UUID.randomUUID().toString().replace("-", "")))
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
            check(uuid.matches(RELAY_ID) && floor in FRIEND_FLOORS && (limit == null || limit in 1..59999999))
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
            it.matches(RELAY_NAME) && (partyPolicies[it]?.first ?: 0L) <= now
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
        globalChatAvailable = false
        richChatAvailable = false
        partyChatAvailable = false
        leapPovAvailable = false
        greenhouseAvailable = false
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

internal val lfgButtonAction = Regex("^(yes|no) ([a-f0-9]{16})$")
internal val relayChatExit = Regex("(?i)^(?:sm|skymyce)\\s+chat$")
internal fun ircHistoryBoundary(account: java.util.UUID, endpoint: String) = "$account|$endpoint"
private val chatChannelCommand = Regex("(?i)^chat\\s+(?:a|all|p|party|g|guild|o|officer|c|co|coop|co-op)$")
internal fun userMessageError(name: String, message: String): String? = when {
    !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
    message.isBlank() || message.length > 256 -> "Messages must contain 1–256 characters."
    message.any { it.isISOControl() || it == '§' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } -> "Messages cannot contain control or formatting codes."
    else -> null
}
internal fun relayRecipients(names: List<String>, prefix: String) = names.filter {
    it.matches(Regex("[A-Za-z0-9_]{1,16}")) && it.startsWith(prefix, true)
}.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.take(30)
internal class RelayConversation {
    private var session = ""
    var replyTarget: String? = null
        private set
    var chatTarget: String? = null
        private set
    fun session(key: String) { if (key != session) { clear(); session = key } }
    fun conversation(name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) replyTarget = name }
    fun chat(name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) chatTarget = name }
    fun leaveChat() { chatTarget = null }
    fun channelCommand(command: String): Boolean {
        if (chatTarget == null || !chatChannelCommand.matches(command.trim())) return false
        leaveChat()
        return true
    }
    fun allowChat(text: String, send: (String, String) -> Unit): Boolean {
        val target = chatTarget ?: return true
        send(target, text)
        return false // Even failed delivery must never fall through to public chat.
    }
    fun relayDisconnected() { replyTarget = null }
    fun clear() { session = ""; replyTarget = null; chatTarget = null }
}

/** Ordinary private messages never enter the LFG parser and never fall back to Hypixel chat. */
object RelayMessages {
    private val irc = IrcMode()
    private val global = GlobalChatState()
    private val globalSubscription = GlobalChatSubscription()
    private val partySubscription = GlobalChatSubscription()
    internal val chatStore = IrcChatStore()
    private val partySeen = linkedSetOf<String>()
    private var partySeenBoundary = ""
    private var richBoundary = ""
    private var richSessionBoundary = ""
    private var verifiedParty: IrcPartyRoster? = null
    private var verifiedPartyAt = 0L
    private var activeParty = ""
    private var partySubscribedId = ""
    private var partySubscribedUntil = 0L
    private var nextPartySubscribe = 0L
    internal val partyId get() = livePartyRoster()?.id
    internal val partyRoster get() = livePartyRoster()
    private var replyDraft: IrcKey? = null
    private val reactionRequests = IrcReactionRequests()
    private var globalAccount: java.util.UUID? = null
    private var globalNextSend = 0L
    // One message typed before the subscription receipt; sent once confirmed, never retried after an uncertain send.
    private val heldSend = IrcHeldSend()
    private var nextIrcDiagnostic = Long.MIN_VALUE
    private val conversations = RelayConversation()
    private val quiet = ThreadLocal.withInitial { false }
    private var nextSend = 0L
    @JvmStatic fun suppressChatLog() = quiet.get()
    // Reconnecting the relay must not silently turn a private conversation into public chat.
    fun reset() {
        reactionRequests.clear(); IrcInlineControls.clear(); chatStore.clearPending()
        conversations.relayDisconnected(); nextSend = 0; globalSubscription.reset(); partySubscription.reset(); replyDraft = null
        partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0
        if (MC.screen is IrcMediaViewerScreen || MC.screen is IrcMessageControlsScreen) MC.instance.setScreen(null)
    }
    private fun session() {
        val account = MC.instance.user.profileId
        irc.account(account)
        conversations.session(account.toString())
    }
    fun init() {
        IrcChatRendering.init()
        ClientSendMessageEvents.ALLOW_CHAT.register { text ->
            if (suppressChatLog() || IrcChatRendering.addingMessage()) return@register false
            session()
            if (!irc.chat(text) { val message = IrcEmojiChat.ircText(it); if (irc.party) sendParty(message) else sendGlobal(message) }) return@register false
            conversations.allowChat(text, ::send)
        }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            // This pre-dispatch exit stays available when protected command authorization expires.
            if (relayChatExit.matches(command.trim())) {
                leaveChat()
                return@register false
            }
            val wasSelected = irc.selected
            val allow = irc.command(command)
            if (!allow) {
                conversations.leaveChat()
                enableGlobal()
                globalSelected()
            } else if (wasSelected && !irc.selected) chatClosed()
            allow
        }
        ClientSendMessageEvents.COMMAND.register { command ->
            if (conversations.channelCommand(command)) chatClosed()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            conversations.clear(); nextSend = 0; irc.selected = false; globalSubscription.reset(); partySubscription.reset()
            heldSend.clear(); verifiedParty = null; verifiedPartyAt = 0; activeParty = ""; partySubscribedId = ""; partySubscribedUntil = 0
            clearRichHistory(""); richSessionBoundary = ""
        }
    }
    @JvmStatic fun selectedIrcComposer(): Boolean { session(); return irc.selected }
    @JvmStatic fun allowComposerChat(text: String): Boolean {
        return irc.composer(text, suppressChatLog() || IrcChatRendering.addingMessage()) {
            val message = IrcEmojiChat.ircText(it); if (irc.party) sendParty(message) else sendGlobal(message)
        }
    }
    fun recipients(prefix: String): List<String> = relayRecipients(FriendsAPI.friends.map { it.name } + DungeonFriends.scanner.online.values.map { it.name }, prefix)
    fun send(name: String, text: String) {
        session()
        val problem = userMessageError(name, text)
        if (problem != null) { error(problem); return }
        recipientError(name)?.let { error(it); return }
        val now = System.currentTimeMillis()
        if (now < nextSend) { error("Wait a moment before sending another message."); return }
        nextSend = now + 1000
        val account = MC.instance.user.profileId
        val accepted = DungeonFriendRelay.sendUserMessage(name, text, {
            if (MC.instance.user.profileId == account && LocationAPI.isOnSkyBlock) {
                conversations.conversation(name)
                display(Component.literal("Delivered to $name ✓").withColor(HudTheme.GREEN))
            }
        }, { reason -> if (MC.instance.user.profileId == account) error(deliveryFailure(reason)) })
        if (accepted) display(Component.literal("Me → $name: ").withColor(HudTheme.ACCENT)
            .append(Component.literal(text).withColor(HudTheme.TEXT)))
        else error("Message queue unavailable. Nothing was sent to Hypixel chat.")
    }
    private fun recipientError(name: String): String? = when {
        !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
        !LocationAPI.isOnSkyBlock || MC.instance.player == null -> "Join SkyBlock before messaging friends."
        !DungeonFriends.isRelayFriend(name) -> "That player is not in your cached friends list."
        name.equals(MC.instance.user.name, true) -> "Choose another player."
        !DungeonFriendRelay.connected -> "Relay unavailable. Reconnect and try again; nothing was sent to Hypixel chat."
        !DungeonFriendRelay.userMessagesAvailable -> "This relay does not support private messages yet."
        else -> null
    }
    fun chat(name: String) {
        session()
        recipientError(name)?.let { error(it); return }
        irc.selected = false
        conversations.chat(name)
        display(Component.literal("Relay chat → $name. Type normally to message them; /chat a or /sm chat exits.").withColor(HudTheme.ACCENT))
        val client = MC.instance
        val connection = client.connection
        val account = client.user.profileId
        // Queue after vanilla closes the command input, without opening over another menu.
        client.schedule {
            if (client.connection === connection && client.user.profileId == account && client.player != null &&
                conversations.chatTarget == name && (client.screen == null || client.screen is ChatScreen)) {
                client.setScreen(ChatScreen("", false))
            }
        }
    }
    fun leaveChat() {
        irc.selected = false
        replyDraft = null
        conversations.leaveChat()
        chatClosed()
    }
    private fun chatClosed() = display(Component.literal("Relay chat off. Normal messages use your Hypixel chat channel.").withColor(HudTheme.MUTED))

    fun selectParty() = selectIrc("party")
    fun sendParty(text: String) = sendIrc("party", text)

    fun chatHelp() = notice("/sm irc — global Minecraft/Discord chat; /sm party — Minecraft-only Party IRC. Add a message to send once. Click ↩ to reply, + to react, or ⋯ to edit/delete your own message. /sm ircstatus shows status; /sm chat exits.")

    private fun enableGlobal() {
        if (GlobalChatConfig.enabled) return
        GlobalChatConfig.enabled = true
        runCatching { me.mycellium.skymyce.SkyMyce.config.save() }.onFailure {
            me.mycellium.skymyce.SkyMyce.logger.warn("[Global chat] Could not save the receiving preference")
        }
    }

    private fun enableParty() {
        if (GlobalChatConfig.partyEnabled) return
        GlobalChatConfig.partyEnabled = true
        runCatching { me.mycellium.skymyce.SkyMyce.config.save() }.onFailure {
            me.mycellium.skymyce.SkyMyce.logger.warn("[IRC] Could not save the receiving preference")
        }
    }

    internal fun partyInfo(event: PartyInfoEvent) {
        val client = MC.instance
        if (!client.isSameThread) {
            val connection = client.connection
            val account = client.user.profileId
            client.execute { if (client.connection === connection && client.user.profileId == account) partyInfo(event) }
            return
        }
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        val leaders = event.members.entries.filter { it.value.role.name == "LEADER" }
        verifiedParty = validatedIrcPartyRoster(event.inParty, event.members.keys.toList(), leaders.singleOrNull()?.key, MC.instance.user.profileId)
        verifiedPartyAt = System.nanoTime()
        refreshPartyBoundary()
    }
    private fun livePartyRoster(): IrcPartyRoster? {
        if (!LocationAPI.onHypixel || MC.instance.player == null || MC.connection == null || System.nanoTime() - verifiedPartyAt !in 0..65_000_000_000L) return null
        val verified = verifiedParty ?: return null
        if (MC.instance.user.profileId.toString().replace("-", "") !in verified.members) return null
        if (!PartyAPI.inParty || PartyAPI.members.size != verified.members.size || PartyAPI.leader?.uuid?.toString()?.replace("-", "") != verified.leader) return null
        val members = PartyAPI.members.map { it.uuid?.toString()?.replace("-", "") ?: return null }.sorted()
        return verified.takeIf { members == it.members && PartyAPI.members.count { member -> member.role.name == "LEADER" } == 1 }
    }
    private fun refreshPartyBoundary() {
        val id = if (GlobalChatConfig.partyEnabled) partyId.orEmpty() else ""
        if (id == activeParty) return
        val old = chatStore.values().filter { it.scope == "party" }
        activeParty = id; partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0; partySubscription.reset()
        if (replyDraft?.scope == "party") replyDraft = null
        chatStore.clearScope("party"); IrcChatRendering.clearScope("party")
        if ((MC.screen as? IrcMediaViewerScreen)?.messageKey?.scope == "party" || (MC.screen as? IrcMessageControlsScreen)?.scope == "party") MC.instance.setScreen(null)
        old.flatMap { it.media }.forEach { media -> IrcMediaAssets.cancel(media, false); IrcMediaAssets.cancel(media, true) }
    }

    private fun clearRichHistory(boundary: String) {
        if (richBoundary == boundary) return
        richBoundary = boundary
        chatStore.boundary(boundary)
        replyDraft = null
        partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0
        IrcMediaAssets.reset(boundary)
        if (MC.screen is IrcMediaViewerScreen || MC.screen is IrcMessageControlsScreen) MC.instance.setScreen(null)
        IrcChatRendering.clear()
    }

    /** Changed preferences serialize one scoped subscription; settings also refresh existing chat rows. */
    fun globalTick() {
        val account = MC.instance.user.profileId
        val endpoint = me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl
        clearRichHistory(ircHistoryBoundary(account, endpoint))
        val sessionBoundary = "$richBoundary|${CommunityClient.sessionToken}|${DungeonFriendRelay.sessionGeneration}"
        if (richSessionBoundary != sessionBoundary) { richSessionBoundary = sessionBoundary; reset() }
        refreshPartyBoundary()
        val seenBoundary = "$account|$endpoint|$activeParty"
        if (partySeenBoundary != seenBoundary) { partySeenBoundary = seenBoundary; partySeen.clear() }
        if (globalAccount != account) {
            irc.account(account); globalAccount = account; global.account(account.toString())
            globalNextSend = 0L; globalSubscription.reset(); partySubscription.reset(); heldSend.clear()
            verifiedParty = null; verifiedPartyAt = 0; refreshPartyBoundary()
        }
        chatStore.expire(System.currentTimeMillis())
        IrcChatRendering.tick()
        heldSend.expired(System.nanoTime())?.let { ircError(it.scope, "not_subscribed", "held send") }
        if (!DungeonFriendRelay.connected) return
        for (scope in listOf("global", "party")) {
            if (!(if (scope == "global") DungeonFriendRelay.globalChatAvailable else DungeonFriendRelay.partyChatAvailable)) continue
            val subscription = if (scope == "global") globalSubscription else partySubscription
            val roster = if (scope == "party") livePartyRoster() else null
            val enabled = if (scope == "global") GlobalChatConfig.enabled else GlobalChatConfig.partyEnabled && roster != null
            if (scope == "party" && enabled && System.nanoTime() >= nextPartySubscribe) subscription.reset()
            val revision = subscription.take(enabled, System.nanoTime()) ?: continue
            val fields = buildMap<String, Any> {
                put("enabled", enabled)
                if (DungeonFriendRelay.richChatAvailable) put("richChatVersion", 1)
                if (scope == "party" && roster != null && enabled) { put("members", roster.members); put("leader", roster.leader) }
            }
            var grantMillis = 0L
            if (DungeonFriendRelay.ircPacket(scope, "irc_subscribe", fields, currentGlobalResult { reason ->
                if (scope == "party" && subscription.current(revision)) {
                    if (reason == null && enabled && roster?.id == activeParty) {
                        partySubscribedId = roster.id; partySubscribedUntil = System.nanoTime() + grantMillis * 1_000_000L
                        nextPartySubscribe = System.nanoTime() + ircPartyRefreshMillis(grantMillis) * 1_000_000L
                    } else { partySubscribedId = ""; partySubscribedUntil = 0 }
                }
                val current = subscription.current(revision)
                val notified = subscription.complete(revision, reason, System.nanoTime())
                if (notified) ircError(scope, reason, "subscription receipt")
                val held = if (current && (reason != null || subscription.confirmed)) heldSend.take(scope) else null
                if (held != null) {
                    MC.instance.execute { if (reason == null) sendIrc(scope, held.text, held.retried) else if (!notified) ircError(scope, reason, "held send") }
                }
            }, receipt = { response -> grantMillis = ircPartyGrantMillis(response, roster?.id, System.currentTimeMillis()) ?: 0L })) {
                subscription.queued(revision, enabled)
                if (scope == "party") nextPartySubscribe = System.nanoTime() + 30_000_000_000L
            }
        }
    }

    fun globalReady() { reactionRequests.clear(); IrcInlineControls.clear(); globalSubscription.reset(); partySubscription.reset(); globalTick() }

    /** Late receipts cannot affect a different login, endpoint, authorization or reconnect session. */
    private fun currentGlobalResult(action: (String?) -> Unit): (String?) -> Unit {
        val client = MC.instance
        val account = client.user.profileId
        val lease = CommunityClient.sessionToken
        val endpoint = me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl
        val generation = DungeonFriendRelay.sessionGeneration
        return { reason ->
            if (client.user.profileId == account && CommunityClient.sessionToken == lease &&
                me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl == endpoint &&
                DungeonFriendRelay.sessionGeneration == generation && CommunityClient.connected &&
                client.player != null && client.connection != null) action(reason)
        }
    }

    fun selectGlobal() = selectIrc("global")
    private fun selectIrc(scope: String, openComposer: Boolean = true, announce: Boolean = true) {
        session(); conversations.leaveChat()
        if (scope == "global") { irc.command("chat sky"); enableGlobal() }
        else { irc.selectParty(); enableParty(); DungeonFriends.requestIrcPartyInfo() }
        if (replyDraft?.scope != scope) replyDraft = null
        globalTick()
        if (announce) notice("${if (scope == "global") "Global chat" else "Minecraft-only Party IRC"} selected. /sm chat returns to normal chat.")
        if (!openComposer) return
        val client = MC.instance
        val connection = client.connection
        val account = client.user.profileId
        client.schedule {
            if (client.connection === connection && client.user.profileId == account && client.player != null &&
                irc.selected && (if (scope == "global") irc.global else irc.party) && (client.screen == null || client.screen is ChatScreen)) {
                val screen = client.screen as? ChatScreen
                if (screen != null) IrcInlineControls.focusComposer(screen) else client.setScreen(ChatScreen("", false))
            }
        }
    }
    private fun globalSelected() { globalTick(); notice("Global chat selected. /sm chat returns to normal chat.") }

    fun sendGlobal(text: String) = sendIrc("global", text)
    private fun sendIrc(scope: String, text: String, retried: Boolean = false) {
        if (suppressChatLog() || IrcChatRendering.addingMessage()) return
        val problem = ircClientMessageError(scope, text)
        if (problem != null) { notice(problem, true); return }
        if (!CommunityClient.connected) { notice("Connect Discord in Community settings to use IRC.", true); return }
        if (scope == "global") enableGlobal() else enableParty()
        if (MC.instance.player == null || MC.connection == null || !DungeonFriendRelay.currentGlobalSession()) {
            notice("IRC is unavailable. Reconnect and retry; nothing was sent to normal chat.", true); return
        }
        if (!(if (scope == "global") DungeonFriendRelay.globalChatAvailable else DungeonFriendRelay.partyChatAvailable)) {
            notice("This relay does not support this IRC channel.", true); return
        }
        val now = System.nanoTime()
        if (now < globalNextSend) { notice("Wait a moment before sending another IRC message.", true); return }
        globalTick()
        val subscription = if (scope == "global") globalSubscription else partySubscription
        if (scope == "party" && livePartyRoster() == null) {
            DungeonFriends.requestIrcPartyInfo(); notice(partyRosterError(), true); return
        }
        // Wait for the subscription receipt (or its 12 s timeout) instead of rejecting a send typed before it.
        if (!subscription.confirmed) {
            if (!heldSend.hold(scope, text, now, retried)) notice("Wait a moment before sending another IRC message.", true)
            return
        }
        if (scope == "party" && !currentIrcChannel(IrcKey("party", partyId.orEmpty(), ""))) {
            notice("Party IRC needs a complete current Hypixel roster and a confirmed subscription. Wait for party info, then retry.", true); return
        }
        val draft = replyDraft?.takeIf { it.scope == scope && currentIrcChannel(it) && chatStore.get(it)?.deleted == false }
        val fields = buildMap<String, Any> {
            put("text", text)
            if (draft != null && DungeonFriendRelay.richChatAvailable) put("replyTo", draft.id)
        }
        val sendPartyId = if (scope == "party") partyId else null
        if (DungeonFriendRelay.ircPacket(scope, "irc_send", fields, currentGlobalResult { reason ->
            if (scope == "party" && partyId != sendPartyId) return@currentGlobalResult
            if (reason != null) {
                if (reason == "not_subscribed") subscription.reset()
                // The relay can lose a confirmed subscription; resubscribe and send this rejected (never enqueued) text once.
                if (heldSend.rejected(scope, text, reason, retried, System.nanoTime())) { globalNextSend = 0L; if (replyDraft == null) replyDraft = draft }
                else ircError(scope, reason, "send receipt")
            }
        })) { globalNextSend = now + 500_000_000; replyDraft = null }
        else ircError(scope, null, "send queue")
        // Only the authoritative broadcast renders a send. Uncertain deliveries are never resent.
    }

    private fun ircError(scope: String, reason: String?, operation: String = "relay") {
        val now = System.nanoTime()
        if (now >= nextIrcDiagnostic) {
            nextIrcDiagnostic = now + 60_000_000_000L
            // Fixed failure descriptions keep chat bodies and arbitrary server error text out of logs.
            me.mycellium.skymyce.SkyMyce.logger.warn("[IRC] {} {} failed: {} connected={} currentSession={} writePending={}",
                scope, operation, globalDeliveryError(reason), DungeonFriendRelay.connected,
                DungeonFriendRelay.currentGlobalSession(), DungeonFriendRelay.diagnostics()["relay.writePending"])
        }
        notice(globalDeliveryError(reason).replace("Global chat", if (scope == "party") "Party IRC" else "Global chat"), true)
    }
    internal fun currentIrcChannel(key: IrcKey): Boolean = DungeonFriendRelay.currentGlobalSession() &&
        MC.instance.player != null && MC.connection != null && if (key.scope == "global") {
            key.room == "global-chat" && GlobalChatConfig.enabled && DungeonFriendRelay.globalChatAvailable && globalSubscription.enabled
        } else key.scope == "party" && key.room == partyId && key.room == partySubscribedId &&
            System.nanoTime() < partySubscribedUntil && GlobalChatConfig.partyEnabled && DungeonFriendRelay.partyChatAvailable && partySubscription.enabled

    fun receiveGlobal(json: com.google.gson.JsonObject, deliveredTo: String) {
        val client = MC.instance
        val delivery = global.receive(json, deliveredTo, client.user.profileId.toString(), GlobalChatConfig.enabled,
            DungeonFriendRelay.currentGlobalSession() && DungeonFriendRelay.globalChatAvailable && client.player != null && client.connection != null,
            GlobalChatConfig.mentionSounds) ?: return
        acceptIrc(delivery.message, delivery.ping)
    }
    fun receiveParty(json: com.google.gson.JsonObject, deliveredTo: String) {
        val roster = livePartyRoster() ?: return
        if (deliveredTo != MC.instance.user.profileId.toString() || !currentIrcChannel(IrcKey("party", roster.id, ""))) return
        val message = parsePartyIrc(json, roster.id) ?: return
        if (message.sender?.uuid !in roster.members) return
        if (!partySeen.add(message.id)) return
        while (partySeen.size > 512) partySeen.remove(partySeen.first())
        acceptIrc(message, message.mention && GlobalChatConfig.mentionSounds)
    }
    private fun acceptIrc(message: GlobalChatMessage, ping: Boolean) {
        if (message.model == 1 && !DungeonFriendRelay.richChatAvailable || !chatStore.accept(message, System.currentTimeMillis())) return
        val current = chatStore.get(message.key) ?: message
        IrcChatRendering.add(current)
        if (ping && !current.deleted) MC.instance.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.25f))
    }
    fun receiveUpdate(json: com.google.gson.JsonObject, deliveredTo: String) {
        if (deliveredTo != MC.instance.user.profileId.toString() || !DungeonFriendRelay.richChatAvailable) return
        val update = parseIrcUpdate(json) ?: return
        if (!currentIrcChannel(update.key)) return
        val previous = chatStore.get(update.key)
        if (previous == null) { chatStore.apply(update, System.currentTimeMillis()); return }
        val next = chatStore.apply(update, System.currentTimeMillis()) ?: return
        reactionRequests.observe(next)
        if (next.deleted && replyDraft == next.key) replyDraft = null
        if (previous.media != next.media || next.deleted) {
            if ((MC.screen as? IrcMediaViewerScreen)?.messageKey == next.key) MC.instance.setScreen(MC.instance.gui.chat.restoreChatScreen())
            previous.media.filter { it !in next.media }.forEach { media -> IrcMediaAssets.cancel(media, false); IrcMediaAssets.cancel(media, true) }
        }
        IrcChatRendering.update(next)
    }
    internal fun key(scope: String, id: String) = IrcKey(scope, when (scope) { "global" -> "global-chat"; "party" -> partyId.orEmpty(); else -> DungeonFriendRelay.ircRoom }, id)
    fun chatAction(command: String, mouseX: Int? = null, mouseY: Int? = null) {
        val action = parseIrcChatAction(command) ?: return
        val key = key(action.scope, action.id)
        val message = chatStore.get(key)?.takeUnless { it.deleted } ?: return
        if (!currentIrcChannel(key)) return
        if (message.model == 1 && DungeonFriendRelay.richChatAvailable) {
            if (action.kind == "reply") { prepareReply(key); return }
            if (action.kind == "react") {
                if (MC.screen !is ChatScreen) MC.instance.setScreen(ChatScreen("", false))
                IrcInlineControls.open(key, mouseX ?: 4, mouseY ?: (MC.instance.window.guiScaledHeight - 48))
                return
            }
        }
        if (MC.screen is ChatScreen) MC.instance.gui.chat.preserveCurrentChatScreen()
        if (action.kind == "media") {
            val media = message.media.getOrNull(action.mediaIndex ?: return) ?: return
            val context = message.reply?.let { "Reply to ${it.from}: ${it.text.orEmpty().replace('\n', ' ').take(96)}" }
                ?: message.text.replace('\n', ' ').take(160)
            MC.instance.setScreen(IrcMediaViewerScreen(MC.screen, media, message.from, "${ircPrefix(message.scope)} $context", key))
        } else if (action.kind == "manage" && canManage(key))
            MC.instance.setScreen(IrcMessageControlsScreen(MC.screen, key))
    }
    internal fun prepareReply(key: IrcKey) {
        if (!currentIrcChannel(key) || chatStore.get(key)?.deleted != false || !DungeonFriendRelay.richChatAvailable) return
        if (key.scope !in setOf("global", "party")) return
        selectIrc(key.scope, openComposer = false, announce = false)
        replyDraft = key
        IrcInlineControls.clear()
        val screen = MC.screen as? ChatScreen
        if (screen != null) IrcInlineControls.focusComposer(screen)
        else MC.instance.setScreen(ChatScreen("", false))
    }
    internal fun replyMessage(): GlobalChatMessage? = replyDraft?.takeIf {
        irc.selected && (if (it.scope == "global") irc.global else irc.party) && currentIrcChannel(it)
    }?.let { chatStore.get(it)?.takeUnless { message -> message.deleted } }
    fun cancelReply() { replyDraft = null; notice("Reply cancelled.") }
    internal fun canManage(key: IrcKey): Boolean = currentIrcChannel(key) && DungeonFriendRelay.richChatAvailable &&
        chatStore.manageable(key, MC.instance.user.profileId.toString().replace("-", ""), System.currentTimeMillis())
    internal fun operation(key: IrcKey, operation: String, text: String? = null, emoji: String? = null, active: Boolean? = null): Boolean {
        val message = chatStore.get(key)?.takeUnless { it.deleted } ?: return false
        if (!currentIrcChannel(key) || !DungeonFriendRelay.richChatAvailable || message.model != 1) return false
        if (operation in setOf("edit", "delete") && !canManage(key)) return false
        if (operation !in setOf("edit", "delete", "reaction") || operation == "edit" && (text == null || ircClientMessageError(key.scope, text) != null) ||
            operation == "reaction" && (emoji !in IRC_REACTIONS || active == null)) return false
        if (operation == "reaction" && !reactionRequests.start(key, emoji!!, active!!, System.currentTimeMillis())) return false
        val fields = buildMap<String, Any> {
            put("messageId", key.id); put("operation", operation)
            text?.let { put("text", it) }; emoji?.let { put("emoji", it) }; active?.let { put("active", it) }
        }
        return DungeonFriendRelay.ircPacket(key.scope, "irc_operation", fields, currentGlobalResult { reason ->
            if (reason != null && operation == "reaction") reactionRequests.finish(key, emoji!!)
            if (reason != null && currentIrcChannel(key) && chatStore.get(key) != null) notice(ircOperationError(reason), true)
        }).also { if (!it) {
            if (operation == "reaction") reactionRequests.finish(key, emoji!!)
            notice("Message control unavailable. Reconnect and try again.", true)
        } }
    }
    fun ircStatus() {
        val partyStatus = if (!GlobalChatConfig.partyEnabled) "off" else if (livePartyRoster() == null) partyRosterError()
            else if (currentIrcChannel(IrcKey("party", partyId.orEmpty(), ""))) "in-game only" else "waiting for confirmed subscription"
        notice("${DungeonFriendRelay.status}. Global: ${if (globalSubscription.enabled && GlobalChatConfig.enabled) "receiving" else "off"}; Party: $partyStatus. ${if (DungeonFriendRelay.richChatAvailable) "Media and message controls supported." else "Legacy text/link compatibility."}")
    }
    private fun partyRosterError() = ircPartyRosterError(LocationAPI.onHypixel, PartyAPI.inParty, PartyAPI.members.size,
        verifiedParty?.let { (System.nanoTime() - verifiedPartyAt) / 1_000_000L })
    internal fun notice(text: String, failed: Boolean = false) = display(IrcChatRendering.prefixComponent("room")
        .append(Component.literal(text).withColor(if (failed) HudTheme.RED else HudTheme.MUTED)), "")
    internal fun <T> quietChat(action: () -> T): T {
        val previous = quiet.get(); quiet.set(true)
        return try { action() } finally { quiet.set(previous) }
    }
    fun reply(text: String) {
        session()
        val name = conversations.replyTarget
        if (name == null) error("No current conversation. Use /sm msg <ign> <message> first.") else send(name, text)
    }
    fun receive(name: String, uuid: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock || !DungeonFriends.acceptsRelayIdentity(name, uuid) || userMessageError(name, text) != null) return false
        session(); conversations.conversation(name)
        val reply = Component.literal("  [Reply]").withColor(HudTheme.ACCENT).withStyle {
            it.withClickEvent(ClickEvent.SuggestCommand("/sm msg $name "))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Prepare a private reply to $name. Clicking does not send it.")))
        }
        display(Component.literal("$name → Me: ").withColor(HudTheme.SECONDARY)
            .append(Component.literal(text).withColor(HudTheme.TEXT)).append(reply))
        return true
    }
    fun legacy(name: String, text: String) {
        if (lfgButtonAction.matches(text)) action(name, text)
        else { display(Component.literal("/sm relaymsg is deprecated; use /sm msg.").withColor(HudTheme.MUTED)); send(name, text) }
    }
    fun action(name: String, text: String) {
        if (!lfgButtonAction.matches(text) || !DungeonFriends.relayReply(name, text)) error("Invalid or unavailable LFG action. Open a current invitation.")
    }
    private fun error(text: String) = display(Component.literal(text).withColor(HudTheme.RED))
    private fun display(message: Component, prefix: String = "[Sky-Hawk] ") {
        val previous = quiet.get(); quiet.set(true)
        try { MC.instance.gui.chat.addClientSystemMessage(Component.literal(prefix).withColor(HudTheme.ACCENT).append(message)) }
        finally { quiet.set(previous) }
    }
}

internal fun deliveryFailure(reason: String) = when (reason) {
    "offline" -> "Recipient is offline on this relay. Message was not delivered."
    "recipient_disallowed" -> "Recipient's friend/identity check rejected the message."
    "recipient_unsupported" -> "Recipient needs a version supporting private messages."
    "rate_limited" -> "Relay rate limit reached. Wait a few seconds and retry."
    "recipient_busy" -> "Recipient is busy. Try again shortly."
    "receipt_timeout" -> "No recipient acknowledgement arrived. Delivery is unconfirmed."
    else -> "Connection failed. Delivery is unconfirmed; nothing was sent to Hypixel chat."
}
