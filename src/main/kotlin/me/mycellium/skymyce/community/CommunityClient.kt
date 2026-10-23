package me.mycellium.skymyce.community

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.SkyMyce
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class CommunityFailure(val code: String) : Exception()
internal fun communityNonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

/** Connecting online services never enables/disables a local module or blocks Minecraft startup. */
object CommunityClient {
    val configuration: Properties by lazy {
        Properties().apply { CommunityClient::class.java.getResourceAsStream("/sky-myce-community.properties")?.use(::load) }
    }
    val version get() = SkyMyce.mod.metadata.version.friendlyString
    @Volatile var identity: JsonObject? = null; private set
    @Volatile var sessionToken: String? = null; private set
    @Volatile var sessionGeneration = 0L; private set
    var status = "Connect Discord for community features"; private set
    var busy = false; private set
    var fallbackCode: String? = null; private set
    var browserUrl: String? = null; private set
    private var accessExpiresAt = 0L
    private var refreshToken: String? = null
    private var refreshExpiresAt = 0L
    private var uuid = ""
    private var epoch = 0L
    private var storage: CommunityStorage? = null
    private var flow: JsonObject? = null
    private val cancellation = CommunityConnectCancellation()
    private var connectingEpoch: Long? = null
    private var cancelledConnectEpoch: Long? = null
    private var nextAttempt = 0L
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Sky-Myce community").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    val connected get() = sessionToken != null && accessExpiresAt > System.currentTimeMillis()
    val hasSavedSession get() = refreshToken != null && refreshExpiresAt > System.currentTimeMillis()
    val connectionPending get() = flow != null || connectingEpoch != null && busy || cancellation.hasPending
    val cancellationPending get() = cancellation.hasPending || cancelledConnectEpoch != null

    fun initialize() {
        ClientTickEvents.END_CLIENT_TICK.register { me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.COMMUNITY_TICK) { tick() } }
        ScreenEvents.AFTER_INIT.register { minecraft, screen, width, _ ->
            if (screen is TitleScreen) Screens.getWidgets(screen).add(Button.builder(Component.literal("Sky-Myce Community")) {
                minecraft.setScreen(CommunityScreen(screen))
            }.bounds((width - 162).coerceAtLeast(4), 6, 156, 20).build())
        }
        CommunityUpdates.initialize()
    }
    private val compactProfile = me.mycellium.skymyce.utils.CompactProfileId()
    private fun tick() {
        val current = compactProfile.of(Minecraft.getInstance().user.profileId)
        if (current != uuid) {
            epoch++; uuid = current; sessionGeneration++; sessionToken = null; identity = null; refreshToken = null
            accessExpiresAt = 0; flow = null; cancellation.clear(); connectingEpoch = null; cancelledConnectEpoch = null
            fallbackCode = null; browserUrl = null; busy = false; nextAttempt = 0; storage = null
            work({ CommunityStorage(SkyMyce.configPath.resolve("community-sessions"), current).let { it to it.read() } }) { (store, saved) ->
                storage = store; refreshToken = saved?.get("refreshToken")?.asString
                refreshExpiresAt = saved?.get("refreshExpiresAt")?.asLong ?: 0
                nextAttempt = 0
            }
        }
        val now = System.currentTimeMillis()
        if (!busy && now >= nextAttempt) {
            if (cancellation.hasPending) cancelConnect()
            else if (flow != null) poll()
            else if (refreshToken != null && now < refreshExpiresAt && now >= accessExpiresAt - 60_000) refresh()
        }
        CommunitySharing.tick()
        CommunityUpdates.tick()
    }
    /** Called from background work; credentials are transmitted only to the compiled HTTPS backend. */
    fun request(path: String, body: JsonObject? = null, method: String = "POST"): JsonObject = exchange(path, body, method, sessionToken)
    internal fun exchange(path: String, body: JsonObject?, method: String = "POST", token: String? = null): JsonObject {
        val relative = URI(path)
        require(path.startsWith("/community/") && !relative.isAbsolute && relative.rawAuthority == null && relative.fragment == null && ".." !in relative.path.split('/'))
        val endpoint = URI(configuration.getProperty("backend") ?: error("Community backend unavailable"))
        require(endpoint.scheme == "https" && endpoint.userInfo == null && endpoint.rawQuery == null && endpoint.fragment == null)
        val builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (method == "GET") builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString((body ?: JsonObject()).toString()))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = readCommunityBody(response.body(), 65536, Duration.ofSeconds(20))
        val json = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrElse { throw CommunityFailure("service_unavailable") }
        if (response.statusCode() !in 200..299 || json["ok"]?.asBoolean == false) throw CommunityFailure(json["error"]?.asString ?: "service_unavailable")
        return json
    }
    private fun proofBody(purpose: String): JsonObject {
        val challenge = exchange("/community/challenge", Gson().toJsonTree(mapOf("purpose" to purpose, "clientNonce" to communityNonce())).asJsonObject)
        return Gson().toJsonTree(mapOf("challengeId" to challenge["challengeId"].asString,
            "minecraftProof" to CommunityMinecraftProof.create(challenge["nonce"].asString).get(30, TimeUnit.SECONDS), "modVersion" to version)).asJsonObject
    }
    fun connect() {
        if (busy) return
        if (flow != null || cancellation.hasPending) { cancelConnect(); return }
        flow = null; fallbackCode = null; browserUrl = null
        val owner = uuid; val boundary = epoch; connectingEpoch = boundary
        status = "Verifying Minecraft, then opening Discord…"
        work(action = {
            val secret = communityNonce()
            val body = proofBody("connect").apply { addProperty("pollSecret", secret); DeviceMetadata.observe()?.let { add("device", it) } }
            exchange("/community/connect/start", body).apply { addProperty("pollSecret", secret) }
        }, discarded = { result ->
            // Cancellation can precede the start response. Never open its browser; revoke the returned flow instead.
            if (owner == uuid && cancelledConnectEpoch == boundary) {
                connectingEpoch = null; cancelledConnectEpoch = null; busy = false
                val remembered = result.getOrNull()?.let { pending -> runCatching { cancellation.remember(owner, pending) }.isSuccess } == true
                if (remembered) cancelConnect() else {
                    status = "Stopped connecting locally. Server cancellation is unconfirmed; any pending request expires within ten minutes."
                    nextAttempt = System.currentTimeMillis() + 60_000
                }
            }
        }) { result ->
            connectingEpoch = null
            flow = result; fallbackCode = result["fallbackCode"]?.asString
            browserUrl = result["browserUrl"]?.takeUnless { it.isJsonNull }?.asString
            status = "Finish connecting in your browser, or use /connect code:$fallbackCode with Mousebot."
            nextAttempt = System.currentTimeMillis() + 3000
            browserUrl?.let { openBrowser(it) }
        }
    }
    fun openBrowser() { browserUrl?.let(::openBrowser) }
    fun openBrowser(url: String) {
        val parsed = URI(url); val backend = URI(configuration.getProperty("backend"))
        require(parsed.scheme == "https" && parsed.host == backend.host && parsed.userInfo == null)
        Util.getPlatform().openUri(parsed)
    }
    fun cancelConnect() {
        // Duplicate button presses cannot invalidate the sole revocation callback or queue more requests.
        if (cancellation.inFlight || busy && (flow == null && cancellation.hasPending || cancelledConnectEpoch != null)) return
        val pending = flow
        if (pending != null) cancellation.remember(uuid, pending)
        if (!cancellation.hasPending) {
            if (busy && connectingEpoch != null) {
                cancelledConnectEpoch = connectingEpoch; epoch++; fallbackCode = null; browserUrl = null
                status = "Stopping Discord connection; waiting to cancel the pending server request…"
            } else status = if (connected) "Connected to Discord; no pending connection to cancel." else "No pending Discord connection."
            return
        }
        epoch++; flow = null; fallbackCode = null; browserUrl = null
        if (cancellation.expire(System.currentTimeMillis())) {
            busy = false; nextAttempt = System.currentTimeMillis() + 60_000
            status = "Pending Discord connection expired. Choose Connect Discord to try again."
            return
        }
        val attempt = cancellation.begin(uuid, epoch) ?: return
        nextAttempt = System.currentTimeMillis() + 60_000
        status = "Cancelling the pending Discord connection on the server…"
        work({ performCommunityCancellation(attempt.request) { path, body -> exchange(path, body) } }) { confirmed ->
            if (!cancellation.finish(attempt, uuid, epoch, confirmed)) return@work
            status = if (confirmed) "Pending Discord connection cancelled on server. Existing account associations are retained."
            else "Cancelled locally; server cancellation is unconfirmed. An opened browser or fallback code may still complete until expiry. Retry Cancel pending connection."
        }
    }
    private fun poll() {
        val pending = flow ?: return
        if (System.currentTimeMillis() >= pending["expiresAt"].asLong) { cancelConnect(); return }
        nextAttempt = System.currentTimeMillis() + 3000
        work({ exchange("/community/connect/poll", JsonObject().apply { addProperty("flowId", pending["flowId"].asString); addProperty("pollSecret", pending["pollSecret"].asString) }) }) {
            if (it["status"]?.asString == "complete") accept(it)
        }
    }
    fun retry() { nextAttempt = 0; if (refreshToken == null) connect() }
    private fun refresh() {
        val saved = refreshToken ?: return
        status = "Reconnecting Discord…"; nextAttempt = System.currentTimeMillis() + 60_000
        work({ exchange("/community/session/refresh", proofBody("refresh").apply { addProperty("refreshToken", saved); DeviceMetadata.observe()?.let { add("device", it) } }) }) { accept(it) }
    }
    private fun accept(result: JsonObject) {
        val credentials = result.getAsJsonObject("credentials")
        require(credentials["accessToken"].asString.matches(Regex("[A-Za-z0-9_-]{43}")) && credentials["refreshToken"].asString.matches(Regex("[A-Za-z0-9_-]{43}")))
        sessionToken = credentials["accessToken"].asString; refreshToken = credentials["refreshToken"].asString
        accessExpiresAt = credentials["accessExpiresAt"].asLong; refreshExpiresAt = credentials["refreshExpiresAt"].asLong
        identity = result.getAsJsonObject("identity"); flow = null; fallbackCode = null; browserUrl = null; sessionGeneration++
        status = "Connected as ${identity?.get("displayName")?.asString ?: identity?.get("username")?.asString ?: "Discord user"}"
        val saved = JsonObject().apply { addProperty("refreshToken", refreshToken); addProperty("refreshExpiresAt", refreshExpiresAt) }
        val store = storage
        work({ store?.write(saved) }) { }
    }
    fun disconnect() {
        if (busy) return
        flow?.let { cancellation.remember(uuid, it) }
        val pending = cancellation.snapshot(uuid)
        val saved = refreshToken
        val store = storage
        clearConnection("Disconnected from Discord. Local features remain available.")
        // Retain failed flow cancellation for retry even after local session credentials have been cleared.
        pending?.let(cancellation::restore)
        nextAttempt = System.currentTimeMillis() + 60_000
        work({
            performCommunityDisconnect(pending, { store?.clear() },
                { request -> performCommunityCancellation(request) { path, body -> exchange(path, body) } },
                { saved == null || runCatching { exchange("/community/session/disconnect", proofBody("disconnect").apply { addProperty("refreshToken", saved) }) }.isSuccess })
        }) { result ->
            if (result.flowCancelled) cancellation.clear()
            status = when {
                !result.flowCancelled -> "Disconnected locally; pending browser/code cancellation is unconfirmed and may still complete until expiry. Retry Cancel pending connection." +
                        if (!result.sessionRevoked) " Use Mousebot /disconnect to revoke remaining server sessions." else ""
                !result.sessionRevoked -> "Disconnected locally. Server session revocation is unconfirmed; use Mousebot /disconnect."
                pending != null -> "Disconnected from Discord; pending connection cancelled on server. Existing account associations are retained."
                else -> "Disconnected from Discord. Local features remain available."
            }
            if (!result.storageCleared) status += " Saved session storage could not be cleared."
        }
    }
    fun onlineDenied(code: String) {
        sessionToken = null; accessExpiresAt = 0; sessionGeneration++; nextAttempt = System.currentTimeMillis() + 60_000
        status = if (code.contains("blacklist")) "Online services are restricted for this identity. Local features remain available." else "Discord session ended. Reconnect in Community settings."
    }
    private fun clearConnection(message: String) { epoch++; sessionToken = null; refreshToken = null; identity = null; accessExpiresAt = 0; flow = null; cancellation.clear(); connectingEpoch = null; cancelledConnectEpoch = null; fallbackCode = null; browserUrl = null; sessionGeneration++; status = message }
    fun relayAuthorization(): Map<String, String> = mapOf("communityToken" to requireNotNull(sessionToken), "modVersion" to version)
    private fun <T> work(action: () -> T, discarded: ((Result<T>) -> Unit)? = null, success: (T) -> Unit) {
        val boundary = epoch; busy = true
        worker.submit {
            val result = runCatching(action)
            Minecraft.getInstance().execute {
                if (epoch != boundary) { discarded?.let { runCatching { it(result) } }; return@execute }
                busy = false
                result.fold({ value -> runCatching { success(value) }.onFailure { status = "Community response unavailable. Local features remain available." } }, { error ->
                    // A failed start has no flow to poll/cancel; later session refresh work must not look like a start.
                    if (connectingEpoch == boundary) connectingEpoch = null
                    val code = (error as? CommunityFailure)?.code
                    status = when (code) {
                        "discord_blacklisted", "minecraft_blacklisted", "identity_blacklisted" -> "Online services are restricted for this identity. Local features remain available."
                        "invalid_session", "session_expired", "session_revoked", "invalid_refresh_token" -> { refreshToken = null; sessionToken = null; "Discord session expired. Choose Connect Discord." }
                        else -> "Community connection unavailable. Try again; local features remain available."
                    }
                    nextAttempt = System.currentTimeMillis() + 60_000
                })
            }
        }
    }
}
