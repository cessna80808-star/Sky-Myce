package me.mycellium.skymyce.community

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.time.Duration
import java.util.Base64
import java.util.EnumSet
import java.util.HexFormat
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.mycellium.mixin.MinecraftAccountKeyAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

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

internal data class CommunityCancellationRequest(val owner: String, val flowId: String, val pollSecret: String, val expiresAt: Long) {
    fun body() = JsonObject().apply { addProperty("flowId", flowId); addProperty("pollSecret", pollSecret) }
}

internal data class CommunityCancellationAttempt(val request: CommunityCancellationRequest, val epoch: Long)

/** Render-thread state only; transport uses immutable snapshots and never logs the polling secret. */
internal class CommunityConnectCancellation {
    private var pending: CommunityCancellationRequest? = null
    private var active: CommunityCancellationAttempt? = null
    val hasPending get() = pending != null
    val inFlight get() = active != null

    fun remember(owner: String, flow: JsonObject) {
        check(!inFlight)
        require(owner.matches(Regex("[a-f0-9]{32}")))
        val flowId = flow["flowId"].asString; val secret = flow["pollSecret"].asString; val expiry = flow["expiresAt"].asLong
        require(flowId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) && secret.matches(Regex("[A-Za-z0-9_-]{43}")) && expiry > 0)
        pending = CommunityCancellationRequest(owner, flowId, secret, expiry)
    }

    fun snapshot(owner: String) = pending?.takeIf { it.owner == owner }
    fun restore(request: CommunityCancellationRequest) { check(!inFlight); pending = request }
    fun begin(owner: String, epoch: Long): CommunityCancellationAttempt? {
        if (inFlight) return null
        return snapshot(owner)?.let { CommunityCancellationAttempt(it, epoch).also { attempt -> active = attempt } }
    }

    /** A stale owner/epoch or an older replaced flow can neither clear nor confirm a newer request. */
    fun finish(attempt: CommunityCancellationAttempt, owner: String, epoch: Long, confirmed: Boolean): Boolean {
        if (attempt !== active || attempt.request !== pending || attempt.request.owner != owner || attempt.epoch != epoch) return false
        active = null
        if (confirmed) pending = null
        return true
    }

    fun expire(now: Long): Boolean {
        if (inFlight) return false
        if (pending?.expiresAt?.let { it <= now } != true) return false
        pending = null
        return true
    }

    fun clear() { pending = null; active = null }
}

/** No bearer or typed UUID grants authority: only the exact flow's private polling secret can cancel it. */
internal fun performCommunityCancellation(request: CommunityCancellationRequest, exchange: (String, JsonObject) -> JsonObject): Boolean = runCatching {
    val response = exchange("/community/connect/cancel", request.body())
    require(response["ok"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true &&
            response["status"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "cancelled" } == true)
}.isSuccess

internal data class CommunityDisconnectResult(val storageCleared: Boolean, val flowCancelled: Boolean, val sessionRevoked: Boolean)

/** A relink flow and a saved session are separate authorities; failure of either must not skip the other revocation. */
internal fun performCommunityDisconnect(pending: CommunityCancellationRequest?, clearStorage: () -> Unit,
                                       cancel: (CommunityCancellationRequest) -> Boolean, revokeSession: () -> Boolean): CommunityDisconnectResult {
    val storageCleared = runCatching(clearStorage).isSuccess
    val flowCancelled = pending == null || runCatching { cancel(pending) }.getOrDefault(false)
    val sessionRevoked = runCatching(revokeSession).getOrDefault(false)
    return CommunityDisconnectResult(storageCleared, flowCancelled, sessionRevoked)
}

private val bodyDeadlines = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "Sky-Myce HTTP body deadlines").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/** HttpClient's streaming handler returns after headers; close a stalled body independently of the request timeout. */
internal fun <T> withCommunityBodyDeadline(body: InputStream, timeout: Duration, consume: (InputStream) -> T): T {
    require(!timeout.isNegative && !timeout.isZero)
    // Only the reader or the deadline can complete this body. A cancelled task cannot expire a successful read later.
    val state = AtomicInteger(0)
    val expiry = bodyDeadlines.schedule({
        if (state.compareAndSet(0, 2)) runCatching { body.close() }
    }, timeout.toNanos(), TimeUnit.NANOSECONDS)
    return body.use { input ->
        try {
            val value = consume(input)
            if (!state.compareAndSet(0, 1)) throw HttpTimeoutException("Community response body timed out")
            value
        } catch (failure: Throwable) {
            if (state.get() == 2 && failure !is HttpTimeoutException) {
                throw HttpTimeoutException("Community response body timed out").apply { initCause(failure) }
            }
            throw failure
        } finally {
            state.compareAndSet(0, 1)
            expiry.cancel(false)
        }
    }
}

internal fun readCommunityBody(body: InputStream, maximum: Int, timeout: Duration): ByteArray {
    require(maximum in 1..262144)
    return withCommunityBodyDeadline(body, timeout) { input ->
        input.readNBytes(maximum + 1).also { require(it.size <= maximum) }
    }
}

/** A partial/oversized/expired download never remains as an installable candidate. Existing files are never overwritten. */
internal fun writeCommunityArtifactBody(body: InputStream, next: Path, expectedSize: Long, timeout: Duration) {
    require(expectedSize in 1..MAX_COMMUNITY_JAR)
    var created = false
    try {
        withCommunityBodyDeadline(body, timeout) { input ->
            Files.newOutputStream(next, StandardOpenOption.CREATE_NEW).use { output ->
                created = true
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count; require(total <= expectedSize)
                    output.write(buffer, 0, count)
                }
                require(total == expectedSize)
            }
        }
    } catch (failure: Throwable) {
        if (created) runCatching { Files.deleteIfExists(next) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/** Minecraft owns its signing key. Only its signed public certificate/profile proof leaves the client. */
object CommunityMinecraftProof {
    fun create(nonce: String): CompletableFuture<Map<String, Any>> {
        require(nonce.matches(Regex("[a-f0-9]{32}|[A-Za-z0-9_-]{43}")))
        val minecraft = Minecraft.getInstance()
        val user = minecraft.user
        val service = minecraft.services().sessionService()
        val manager = (minecraft as MinecraftAccountKeyAccessor).`skymyce$getAccountKeyPairManager`()
        return manager.prepareKeyPair().thenApplyAsync { optional ->
            val pair = optional.orElseThrow { IllegalStateException("Minecraft account key unavailable") }
            val certificate = pair.publicKey().data()
            check(!certificate.hasExpired())
            val profile = service.fetchProfile(user.profileId, true)?.profile() ?: error("Minecraft profile unavailable")
            check(profile.id() == user.profileId && profile.name().equals(user.name, true))
            val textures = service.getPackedTextures(profile)
            check(textures != null && textures.hasSignature())
            val id = user.profileId.toString().replace("-", "")
            val proof = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.privateKey())
                update("SkyMyce relay v2\n$nonce\n$id\n${user.name}".toByteArray(Charsets.UTF_8)); sign()
            }
            val base64 = Base64.getEncoder()
            mapOf("type" to "authenticate", "name" to user.name, "uuid" to id,
                "expires" to certificate.expiresAt().toEpochMilli(),
                "publicKey" to base64.encodeToString(certificate.key().encoded),
                "keySignature" to base64.encodeToString(certificate.keySignature()),
                "proof" to base64.encodeToString(proof), "profile" to textures.value(),
                "profileSignature" to requireNotNull(textures.signature()))
        }
    }
}

internal fun restrictCommunityPath(path: Path) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"))
    else {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: error("Private session storage unavailable")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
        acl.acl = listOf(entry.build())
    }
}

/** Refresh credentials are private runtime data, never resources in the distributed JAR. */
internal class CommunityStorage(directory: Path, uuid: String) {
    private val file: AtomicJsonFile
    init {
        require(uuid.matches(Regex("[a-f0-9]{32}")))
        Files.createDirectories(directory); restrictCommunityPath(directory)
        file = AtomicJsonFile(directory.resolve("$uuid.json"), 16384)
    }
    fun read(): JsonObject? = file.read()?.asJsonObject?.takeIf {
        val token = it["refreshToken"]?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
        token?.matches(Regex("[A-Za-z0-9_-]{43}")) == true && it["refreshExpiresAt"]?.asLong?.let { expiry -> expiry > System.currentTimeMillis() } == true
    }
    fun write(json: JsonObject) { file.write(json); restrictCommunityPath(file.path) }
    fun clear() = write(JsonObject())
}

internal fun observationHash(source: String, raw: String): String {
    val normalized = raw.trim().removePrefix("\uFEFF").trim().removeSurrounding("{", "}").lowercase().replace("-", "")
    require(normalized.matches(Regex("[a-f0-9]{32}")) && normalized.toSet().size > 1)
    val family = if (source in setOf("windows-smbios", "linux-dmi", "macos-platform")) "physical-uuid" else source
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("Sky-Myce device observation v1\n$family\n$normalized".toByteArray()))
}

/** One existing OS identifier, hashed locally. Missing/changed/shared metadata never limits a connection. */
object DeviceMetadata {
    private var cached: JsonObject? = null
    private var attempted = false
    @Synchronized fun observe(): JsonObject? {
        if (attempted) return cached?.deepCopy()
        attempted = true
        cached = runCatching {
            val name = System.getProperty("os.name").lowercase()
            val platform = when { name.contains("win") -> "windows"; name.contains("linux") -> "linux"; name.contains("mac") || name.contains("darwin") -> "macos"; else -> "other" }
            val sources = when (platform) {
                "windows" -> listOf("windows-smbios")
                "linux" -> listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id")
                "macos" -> listOf("macos-platform")
                else -> emptyList()
            }
            val observation = sources.firstNotNullOfOrNull { source -> runCatching {
                val raw = read(source) ?: return@runCatching null
                source to observationHash(source, raw)
            }.getOrNull() } ?: software(platform)
            JsonObject().apply { addProperty("identifier", observation.second); addProperty("source", observation.first); addProperty("platform", platform) }
        }.getOrNull()
        return cached?.deepCopy()
    }
    private fun software(platform: String): Pair<String, String> {
        val home = System.getenv(if (platform == "windows") "USERPROFILE" else "HOME")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        val directory = Path.of(home).resolve(".sky-myce/community")
        Files.createDirectories(directory)
        val file = AtomicJsonFile(directory.resolve("device-observation.json"), 1024)
        val saved = file.read()?.asJsonObject?.get("id")?.asString
        val id = saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString().also {
            file.write(JsonObject().apply { addProperty("id", it) })
        }
        return "software-installation" to observationHash("software-installation", id)
    }
    private fun read(source: String): String? = runCatching {
        val paths = when (source) {
            "linux-dmi" -> listOf("/sys/class/dmi/id/product_uuid", "/sys/devices/virtual/dmi/id/product_uuid")
            "linux-machine-id" -> listOf("/etc/machine-id")
            "linux-dbus-machine-id" -> listOf("/var/lib/dbus/machine-id")
            else -> emptyList()
        }
        if (paths.isNotEmpty()) return@runCatching paths.firstNotNullOfOrNull { path -> runCatching {
            val bytes = Files.newInputStream(Path.of(path)).use { it.readNBytes(4097) }; require(bytes.size <= 4096)
            bytes.toString(Charsets.UTF_8).also { observationHash(source, it) }
        }.getOrNull() }
        val command = if (source == "windows-smbios") listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "(Get-CimInstance -ClassName Win32_ComputerSystemProduct -ErrorAction Stop).UUID")
        else listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(16385) } }
        try {
            process.outputStream.close()
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
            val bytes = output.get(1, TimeUnit.SECONDS); require(bytes.size <= 16384)
            val value = bytes.toString(Charsets.UTF_8)
            if (source == "macos-platform") Regex("\"IOPlatformUUID\"\\s*=\\s*\"([a-fA-F0-9-]+)\"").find(value)?.groupValues?.get(1) else value.trim()
        } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close(); output.cancel(true) }
    }.getOrNull()
}

/** One drain task, one running request and one replaceable latest request across screen lifetimes. */
internal class LatestCommunityRequestWorker(private val executor: Executor) {
    private class Work(val owner: Any, val current: () -> Boolean, val run: () -> Unit) {
        @Volatile var cancelled = false
        fun isCurrent() = !cancelled && current()
    }
    private val lock = Any()
    private var pending: Work? = null
    private var active: Work? = null
    private var thread: Thread? = null
    private var draining = false

    fun <T> submit(owner: Any, current: () -> Boolean, load: () -> T, complete: (Result<T>) -> Unit) {
        lateinit var work: Work
        work = Work(owner, current) {
            val result = runCatching(load)
            if (work.isCurrent()) complete(result)
        }
        synchronized(lock) {
            pending = work
            interruptLocked(owner)
            if (!draining) startLocked()
        }
    }

    fun cancel(owner: Any) = synchronized(lock) {
        if (pending?.owner === owner) pending = null
        interruptLocked(owner)
    }

    private fun interruptLocked(owner: Any) {
        active?.takeIf { it.owner === owner && !it.cancelled }?.let {
            it.cancelled = true
            // Interrupt while holding the state lock: this cannot accidentally interrupt the next owner's task.
            thread?.interrupt()
        }
    }

    private fun startLocked() {
        draining = true
        try { executor.execute(::drain) } catch (failure: RuntimeException) { draining = false; throw failure }
    }

    private fun drain() {
        try {
            while (true) {
                val work = synchronized(lock) {
                    val next = pending ?: return
                    pending = null; active = next; thread = Thread.currentThread()
                    next
                }
                // A cancelled request may finish without consuming its interruption. Never carry it into its replacement.
                Thread.interrupted()
                try { if (work.isCurrent()) work.run() } finally {
                    synchronized(lock) { active = null; thread = null }
                    Thread.interrupted()
                }
            }
        } finally {
            synchronized(lock) {
                active = null; thread = null; draining = false
                // A submit can race the empty-queue return, or an unexpected callback failure.
                if (pending != null) startLocked()
            }
        }
    }
}

internal enum class CommunityConnectionKind { DISCONNECTED, SAVED, CONNECTED, PENDING, CANCELLING }
internal enum class CommunityConnectionAction { CONNECT, RECONNECT, BROWSER, CANCEL, DISCONNECT }

/** Presentation mirrors client authorities; local cancellation is never reported as server confirmation. */
internal data class CommunityConnectionView(val connected: Boolean, val saved: Boolean, val pending: Boolean,
                                            val cancelling: Boolean, val busy: Boolean, val browser: Boolean) {
    val kind get() = when {
        cancelling -> CommunityConnectionKind.CANCELLING
        pending -> CommunityConnectionKind.PENDING
        connected -> CommunityConnectionKind.CONNECTED
        saved -> CommunityConnectionKind.SAVED
        else -> CommunityConnectionKind.DISCONNECTED
    }
    val actions get(): List<CommunityConnectionAction> = when (kind) {
        CommunityConnectionKind.CANCELLING -> buildList {
            add(CommunityConnectionAction.CANCEL)
            if (saved || connected) add(CommunityConnectionAction.DISCONNECT)
        }
        CommunityConnectionKind.PENDING -> buildList {
            if (browser) add(CommunityConnectionAction.BROWSER)
            add(CommunityConnectionAction.CANCEL)
            if (saved || connected) add(CommunityConnectionAction.DISCONNECT)
        }
        CommunityConnectionKind.CONNECTED -> listOf(CommunityConnectionAction.DISCONNECT)
        CommunityConnectionKind.SAVED -> listOf(CommunityConnectionAction.RECONNECT, CommunityConnectionAction.DISCONNECT)
        CommunityConnectionKind.DISCONNECTED -> listOf(CommunityConnectionAction.CONNECT)
    }
    fun enabled(action: CommunityConnectionAction): Boolean = action in actions && when (action) {
        CommunityConnectionAction.BROWSER -> browser
        // Start/poll can be cancelled while running; a revocation already in flight cannot be repeated.
        CommunityConnectionAction.CANCEL -> !cancelling || !busy
        else -> !busy
    }
}
