package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import me.mycellium.skymyce.community.readCommunityBody
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opening the terminal never starts a listener. The explicit dashboard action owns this service. */
object BazaarDashboard {
    private var current: BazaarDashboardServer? = null
    @Synchronized fun start(bridge: BazaarUiBridge, session: String? = null): URI {
        current?.close()
        return BazaarDashboardServer(bridge).also { current = it }.launch(session)
    }
    @Synchronized fun stop() { current?.close(); current = null }
    @Synchronized fun running() = current?.running == true
    fun open(bridge: BazaarUiBridge, session: String? = null) {
        // Minecraft's platform opener may log failed URLs, including a fragment capability.
        try { java.awt.Desktop.getDesktop().browse(start(bridge, session)) }
        catch (failure: Exception) { stop(); throw IllegalStateException("The system browser could not open the local dashboard") }
    }
}

/** Capability bootstrap is fragment-only, single use, and removed by the page before its first request. */
internal class BazaarDashboardServer(private val bridge: BazaarUiBridge, private val idleMillis: Long = 120_000) : AutoCloseable {
    private val gson = Gson()
    private val bootstrap = secret()
    private val cookie = secret()
    private val csrf = secret()
    private val consumed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val workers = ThreadPoolExecutor(2, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(12),
        { runnable -> Thread(runnable, "Sky-Myce local dashboard").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val expiry = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "Sky-Myce dashboard expiry").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 12)
    private val cookieName = "SkyMyceBazaar_${server.address.port}"
    private val completed = LinkedHashMap<String, Pair<String, CompletableFuture<BazaarUiResult>>>()
    @Volatile private var lastUse = System.currentTimeMillis()
    val running get() = !closed.get()
    val origin = "http://127.0.0.1:${server.address.port}"
    init {
        require(idleMillis in 100..600_000)
        server.executor = workers
        server.createContext("/") { exchange ->
            try { handle(exchange) }
            catch (_: Exception) { runCatching { respond(exchange, 400, "application/json", "{\"message\":\"Request rejected\"}".toByteArray()) } }
            finally { exchange.close() }
        }
        server.start()
        expiry.scheduleWithFixedDelay({ if (System.currentTimeMillis() - lastUse >= idleMillis) close() }, minOf(idleMillis, 15_000), minOf(idleMillis, 15_000), TimeUnit.MILLISECONDS)
    }
    fun launch(session: String? = null): URI = URI("$origin/#$bootstrap${session?.let { "&session=" + URLEncoder.encode(it, Charsets.UTF_8) }.orEmpty()}")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0); workers.shutdownNow(); expiry.shutdownNow()
        synchronized(completed) { completed.clear() }
    }

    private fun handle(exchange: HttpExchange) {
        if (!exchange.remoteAddress.address.isLoopbackAddress || exchange.requestHeaders["Host"]?.singleOrNull() != "127.0.0.1:${server.address.port}" ||
            exchange.requestURI.rawQuery != null || !validOrigin(exchange, false)) return deny(exchange, 403)
        val path = exchange.requestURI.rawPath
        if (exchange.requestMethod == "GET" && path in setOf("/", "/dashboard.js", "/dashboard.css")) {
            val resource = when (path) { "/" -> "index.html"; else -> path.removePrefix("/") }
            val type = when { resource.endsWith(".js") -> "text/javascript"; resource.endsWith(".css") -> "text/css"; else -> "text/html" }
            val bytes = javaClass.getResourceAsStream("/bazaar/$resource")?.use { it.readNBytes(262_145) } ?: return deny(exchange, 404)
            require(bytes.size <= 262_144)
            respond(exchange, 200, "$type; charset=utf-8", bytes); return
        }
        if (path == "/api/bootstrap") {
            if (exchange.requestMethod != "POST" || !validOrigin(exchange, true)) return deny(exchange, 403)
            val input = body(exchange)
            if (input.keySet() != setOf("secret") || !constant(input["secret"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString, bootstrap) || !consumed.compareAndSet(false, true)) return deny(exchange, 403)
            exchange.responseHeaders.add("Set-Cookie", "$cookieName=$cookie; Path=/; HttpOnly; SameSite=Strict")
            lastUse = System.currentTimeMillis(); json(exchange, 200, mapOf("csrf" to csrf)); return
        }
        if (!authenticated(exchange)) return deny(exchange, 401)
        if (path == "/api/session" && exchange.requestMethod == "GET") {
            lastUse = System.currentTimeMillis(); json(exchange, 200, mapOf("csrf" to csrf)); return
        }
        if (!constant(exchange.requestHeaders["X-SkyMyce-CSRF"]?.singleOrNull(), csrf)) return deny(exchange, 403)
        lastUse = System.currentTimeMillis()
        when {
            path == "/api/state" && exchange.requestMethod == "GET" -> {
                val state = bridge.snapshot()
                // Transport bounds do not delete the permanent private archive; export retains its complete data.
                val bounded = state.copy(opportunities = state.opportunities.take(100), positions = state.positions.take(500), orders = state.orders.take(500),
                    history = state.history.takeLast(500), sessions = state.sessions.takeLast(100).map { it.copy(series = it.series.takeLast(1024), timeline = it.timeline.takeLast(500)) },
                    pending = state.pending.take(100), priceHistory = state.priceHistory.entries.take(100).associate { it.key to it.value.takeLast(192) }, publications = state.publications.take(200))
                json(exchange, 200, bounded)
            }
            path == "/api/action" && exchange.requestMethod == "POST" && validOrigin(exchange, true) -> {
                val input = body(exchange)
                require(input.keySet() == setOf("id", "action", "fields"))
                val id = string(input, "id", 64); require(id.matches(Regex("[A-Za-z0-9-]{16,64}")))
                val action = string(input, "action", 32); require(action in BazaarUi.actions)
                val fields = input.getAsJsonObject("fields"); require(fields.size() <= 24)
                val values = fields.entrySet().associate { (key, value) ->
                    require(key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,39}")) && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                    val text = value.asString; require(text.length <= 2048 && text.none { it == '\u0000' }); key to text
                }
                val signature = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(gson.toJson(listOf(action, values.toSortedMap())).toByteArray()))
                val pending = synchronized(completed) {
                    completed[id]?.let { require(it.first == signature); it.second } ?: run {
                        while (completed.size >= 128) {
                            val oldest = completed.entries.firstOrNull { it.value.second.isDone } ?: error("Busy")
                            completed.remove(oldest.key)
                        }
                        bridge.action(action, values).also { completed[id] = signature to it }
                    }
                }
                // A timeout keeps the original future, so retrying its ID cannot repeat a publication or deletion.
                json(exchange, 200, pending.get(8, TimeUnit.SECONDS))
            }
            path == "/api/close" && exchange.requestMethod == "POST" && validOrigin(exchange, true) -> {
                body(exchange)
                exchange.responseHeaders.add("Set-Cookie", "$cookieName=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict")
                json(exchange, 200, mapOf("message" to "Dashboard stopped"))
                expiry.schedule({ close() }, 100, TimeUnit.MILLISECONDS)
            }
            else -> deny(exchange, 405)
        }
    }
    private fun validOrigin(exchange: HttpExchange, required: Boolean): Boolean {
        val headers = exchange.requestHeaders["Origin"]
        return if (headers == null) !required else headers.size == 1 && headers.single() == origin
    }
    private fun authenticated(exchange: HttpExchange): Boolean {
        val cookies = exchange.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }.map { it.trim() }
            .filter { it.startsWith("$cookieName=") }.map { it.substringAfter('=') }
        return consumed.get() && cookies.size == 1 && constant(cookies.single(), cookie)
    }
    private fun body(exchange: HttpExchange): JsonObject {
        require(exchange.requestHeaders["Content-Type"]?.singleOrNull()?.substringBefore(';')?.trim()?.lowercase() == "application/json")
        exchange.requestHeaders["Content-Length"]?.singleOrNull()?.let { require((it.toLongOrNull() ?: -1) in 0L..16_384L) }
        val bytes = readCommunityBody(exchange.requestBody, 16_384, Duration.ofSeconds(3))
        val reader = JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).apply { strictness = Strictness.STRICT }
        val value = JsonParser.parseReader(reader); require(value.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT)
        return value.asJsonObject
    }
    private fun string(value: JsonObject, key: String, maximum: Int): String {
        val property = value[key]; require(property?.isJsonPrimitive == true && property.asJsonPrimitive.isString)
        return property.asString.also { require(it.length in 1..maximum) }
    }
    private fun json(exchange: HttpExchange, status: Int, value: Any) = respond(exchange, status, "application/json; charset=utf-8", gson.toJson(value).toByteArray(Charsets.UTF_8))
    private fun deny(exchange: HttpExchange, status: Int) = respond(exchange, status, "application/json", "{\"message\":\"Request rejected\"}".toByteArray())
    private fun respond(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        require(bytes.size <= 8_388_608)
        exchange.responseHeaders.apply {
            set("Content-Type", type); set("Cache-Control", "no-store"); set("Referrer-Policy", "no-referrer")
            set("X-Content-Type-Options", "nosniff"); set("X-Frame-Options", "DENY")
            set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    private fun constant(first: String?, second: String) = first != null && first.length <= 128 && MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
