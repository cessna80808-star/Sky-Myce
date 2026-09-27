package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import me.mycellium.mixin.MinecraftAccountKeyAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.loader.LoaderClient
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
    var nextAttempt = 0L
        private set
    var delay = 1000L
        private set
    fun failed(now: Long, pause: Boolean) {
        nextAttempt = if (pause) Long.MAX_VALUE else now + delay
        delay = (delay * 2).coerceAtMost(60000)
    }
    fun reset() { nextAttempt = 0; delay = 1000 }
}

internal fun relayNeedsReconnect(code: Int) = code == 4001 || code == 4004

internal fun relayPacketSizeAllowed(length: Int, type: String?) = length in 0..65536 &&
    (length <= 8192 || type in setOf("digest_news_result", "rng_result", "cosmetics_result"))

/** Exception messages can contain URLs, tokens or response bodies. Keep types and call sites only. */
internal fun relayErrorDetail(error: Throwable?): String = generateSequence(error) { it.cause }
    .take(8).joinToString(" <- ") { cause ->
        cause.javaClass.name + (cause.stackTrace.firstOrNull()?.let { " at ${it.className}.${it.methodName}:${it.lineNumber}" } ?: "")
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
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val deliveries = RelayDeliveries()
    private val statsRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val digestRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    private val cosmeticsRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
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
    private var activeLease: String? = null
    var onDigestEvent: ((JsonObject) -> Unit)? = null
    var onDigestReady: (() -> Unit)? = null
    var onDigestClosed: (() -> Unit)? = null
    private val partyPolicies = mutableMapOf<String, Pair<Long, PartyPolicy?>>()
    private var policiesAvailable = false
    private var policyRequest: Pair<String, List<String>>? = null
    private var policyDeadline = 0L
    private var nextPolicyRequest = 0L
    private var sentPolicy: DungeonJoinPolicy? = null
    private var sentClass: DungeonClass? = null
    private var nextPolicyUpdate = 0L
    var sharedStatsAvailable = false
        private set
    var sharedWealthAvailable = false
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

    fun tick(active: Boolean) {
        if (!LoaderClient.onlineAllowed) { if (identity.isNotEmpty()) disconnect(); status = "Sky-Myce online authorization required"; return }
        val url = DungeonFriendsSettings.relayUrl
        val user = MC.instance.user
        val key = "$url|${user.profileId}"
        if (!active || url.isBlank()) {
            if (identity.isNotEmpty()) disconnect()
            status = if (url.isBlank()) "Relay not configured" else "Relay connects on Hypixel"
            return
        }
        if (key != identity) { disconnect(); identity = key }
        if (activeLease != LoaderClient.authorization.lease) {
            // Renew the authenticated socket without discarding this same player's bounded public appearance cache.
            disconnect(clearCosmetics = false); identity = key; activeLease = LoaderClient.authorization.lease
        }
        val now = DungeonFriends.now()
        if (now >= policyDeadline) policyRequest = null
        deliveries.tick(now)
        statsRequests.filterValues { it.first <= now }.keys.toList().forEach { statsRequests.remove(it)?.second?.complete(null) }
        digestRequests.filterValues { it.first <= now || it.second.isCancelled }.keys.toList().forEach { digestRequests.remove(it)?.second?.complete(null) }
        cosmeticsRequests.filterValues { it.first <= now || it.second.isCancelled }.keys.toList().forEach { cosmeticsRequests.remove(it)?.second?.complete(null) }
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
                    val decoded = if (message == "pong") null else runCatching {
                        JsonParser.parseString(message).asJsonObject.also {
                            check(relayPacketSizeAllowed(message.length, it.get("type")?.asString))
                        }
                    }
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
                                            mapOf("type" to "authenticate", "name" to user.name, "uuid" to uuid, "liveUpdates" to true, "userMessages" to true, "cosmetics" to true, "cosmeticsVersion" to 3,
                                                "expires" to certificate.expiresAt().toEpochMilli(),
                                                "publicKey" to base64.encodeToString(certificate.key().encoded),
                                                "keySignature" to base64.encodeToString(certificate.keySignature()),
                                                "proof" to base64.encodeToString(proof),
                                                "profile" to textures.value(), "profileSignature" to textures.signature()) + LoaderClient.relayAuthorization(serverId)
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
                                        check(authenticating && json.get("protocol")?.asInt == 3)
                                        check(json.get("uuid").asString == user.profileId.toString().replace("-", ""))
                                        check(json.get("name").asString.equals(user.name, true))
                                        connected = true
                                        sharedStatsAvailable = json.get("statsCache")?.asBoolean == true
                                        sharedWealthAvailable = json.get("wealthCache")?.asBoolean == true
                                        policiesAvailable = json.get("partyPolicies")?.asBoolean == true
                                        digestNewsAvailable = json.get("digestNews")?.asBoolean == true
                                        rngFeedAvailable = json.get("rngFeed")?.asBoolean == true
                                        userMessagesAvailable = json.get("userMessages")?.asBoolean == true
                                        cosmeticsAvailable = json.get("cosmetics")?.asBoolean == true
                                        cosmeticsNamesAvailable = cosmeticsAvailable && json.get("cosmeticsNames")?.asBoolean == true
                                        ircAvailable = json.get("irc")?.asBoolean == true
                                        retry.reset()
                                        stage = "connected"
                                        deadline = DungeonFriends.now() + 90000
                                        nextPing = DungeonFriends.now() + 45000
                                        status = "Relay connected"
                                        SkyMyce.logger.info("[Dungeon relay] Connected (shared wealth={})", sharedWealthAvailable)
                                        onDigestReady?.invoke()
                                        me.mycellium.skymyce.features.social.CosmeticsClient.ready()
                                        RelayMessages.ircReady()
                                    }
                                    "irc_message" -> { check(connected && ircAvailable); RelayMessages.receiveIrc(json) }
                                    "irc_result" -> { check(connected); if (json.has("error")) RelayMessages.ircError() }
                                    "authorization_revoked" -> { LoaderClient.deny(json.get("reason")?.asString == "license_revoked"); disconnect() }
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
                                    "stats_result", "wealth_result" -> { check(connected); statsRequests.remove(json.get("id").asString)?.second?.complete(json) }
                                    "digest_news_result", "rng_result" -> { check(connected); runCatching { digestRequests.remove(json.get("id").asString)?.second?.complete(json) } }
                                    "rng_event" -> { check(connected); runCatching { onDigestEvent?.invoke(json) } }
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
                            }.onFailure { failed("Invalid relay response; reconnecting", it) }
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
                    SkyMyce.logger.info("[Dungeon relay] Close code={} authenticated={} stage={}", statusCode, connected, stage)
                    failed(when (statusCode) {
                        4001 -> "Account connected elsewhere; use Reconnect in Settings"
                        4004 -> "Minecraft proof rejected; restart Minecraft or update the mod, then use Reconnect in Settings"
                        else -> "Relay closed ($statusCode); reconnecting"
                    }, pause = relayNeedsReconnect(statusCode), graceful = true)
                    if (statusCode == 4005) LoaderClient.deny()
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

    fun ircPacket(type: String, fields: Map<String, Any>): Boolean {
        if (!connected || !ircAvailable || !LoaderClient.onlineAllowed || type !in setOf("irc_send", "irc_subscribe")) return false
        packet(fields + mapOf("type" to type, "id" to UUID.randomUUID().toString().replace("-", "")))
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

    fun lookupWealth(name: String, uuid: String?, refresh: Boolean, canFetch: Boolean): CompletableFuture<JsonObject?>? {
        if (!sharedWealthAvailable) return null
        return lookupCache("wealth_get", name, uuid, refresh, canFetch)
    }

    /** Separate small queue so digest refreshes cannot consume party/wealth lookup slots. */
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

    private fun lookupCache(type: String, name: String, uuid: String?, refresh: Boolean = false, canFetch: Boolean = true): CompletableFuture<JsonObject?>? {
        if (statsRequests.size >= 8) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        statsRequests[id] = DungeonFriends.now() + 5000 to future
        packet(buildMap {
            put("type", type); put("id", id); put("name", name)
            uuid?.let { put("uuid", it) }
            if (refresh) put("refresh", true)
            if (type == "wealth_get") put("canFetch", canFetch)
        })
        return future
    }

    fun partyPolicy(name: String): PartyPolicy? = partyPolicies[name.lowercase()]?.takeIf { it.first > DungeonFriends.now() }?.second

    private fun cachePolicy(name: String, value: JsonObject?) {
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
        partyPolicies[name] = DungeonFriends.now() + 20000 to policy
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
        if (!sharedStatsAvailable) return
        packet(mapOf("type" to "stats_put", "id" to UUID.randomUUID().toString().replace("-", ""), "name" to name,
            "uuid" to uuid, "stats" to stats, "fetchedAt" to fetchedAt, "upload" to upload))
    }

    fun publishWealth(name: String, wealth: FriendWealth, upload: String) {
        if (!sharedWealthAvailable || wealth.uuid == null) return
        packet(mapOf("type" to "wealth_put", "id" to UUID.randomUUID().toString().replace("-", ""), "name" to name,
            "uuid" to wealth.uuid.replace("-", ""), "wealth" to wealth, "fetchedAt" to wealth.fetchedAt, "upload" to upload))
    }

    private fun packet(data: Any) {
        val ws = socket ?: return
        val token = generation
        val text = if (data is String) data else gson.toJson(data)
        sending = sending.thenCompose {
            if (token == generation) ws.sendText(text, true) else CompletableFuture.completedFuture(ws)
        }.whenComplete { _, error ->
            if (error != null) MC.instance.execute { if (token == generation) failed("Relay send failed; reconnecting", error) }
        }
    }

    private fun failed(message: String, error: Throwable? = null, pause: Boolean = false, graceful: Boolean = false, failingStage: String = stage) {
        if (message != "Relay disconnected") SkyMyce.logger.warn(
            "[Dungeon relay] {} stage={} authenticated={} retry={} exception={}",
            message, failingStage, connected, if (pause) "paused" else "${retry.delay}ms", relayErrorDetail(error))
        generation++
        socket?.let { if (graceful) closeRelaySocket(it, sending) else it.abort() }
        socket = null
        connected = false
        sharedStatsAvailable = false
        sharedWealthAvailable = false
        digestNewsAvailable = false
        rngFeedAvailable = false
        userMessagesAvailable = false
        ircAvailable = false
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
        onDigestClosed?.invoke()
        connecting = false
        authenticating = false
        stage = "idle"
        sending = CompletableFuture.completedFuture(null)
        retry.failed(DungeonFriends.now(), pause)
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
