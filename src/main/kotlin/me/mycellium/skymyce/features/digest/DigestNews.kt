package me.mycellium.skymyce.features.digest

import com.google.gson.JsonObject
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.instances.dungeons.friends.globalChatSpans
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

enum class NewsSource(val label: String, val wire: String) {
    GAME("Game Updates", "game"), DERAILIOUS("Derailious", "derailious")
}
enum class DigestFailure(val label: String) {
    OFFLINE("Offline"), TIMEOUT("Connection timed out"), INVALID("Update unavailable"),
    AUTHENTICATION("News provider permissions unavailable"), RATE_LIMITED("Waiting before retrying"),
    UNCONFIGURED("News bridge not configured"), UNSUPPORTED("Service not available yet")
}
data class DigestNewsItem(val id: String, val source: NewsSource, val title: String, val content: String,
                          val publishedAt: Long, val url: String? = null, val truncated: Boolean = false,
                          val thumbnailUrl: String? = null, val links: List<String> = emptyList())
data class DigestNewsState(val items: List<DigestNewsItem> = emptyList(), val fetchedAt: Long = 0,
                          val loading: Boolean = false, val failure: DigestFailure? = null, val retryAt: Long = 0)

/** Public announcements use the compiled HTTPS backend without transmitting a community session. */
internal fun requestDigestNews(source: NewsSource): CompletableFuture<JsonObject?> {
    val response = CompletableFuture<JsonObject?>()
    val work = Scheduling.schedule(0.milliseconds) {
        if (!response.isDone) runCatching {
            CommunityClient.exchange("/community/news?source=${source.wire}", null, "GET")
        }.onSuccess { response.complete(it) }.onFailure { response.completeExceptionally(it) }
    }
    response.whenComplete { _, _ -> if (response.isCancelled) work.cancel(true) }
    return response
}

internal fun digestNewsFailure(error: Throwable): DigestFailure {
    val cause = if (error is CompletionException) error.cause ?: error else error
    return when (cause) {
        is TimeoutException, is HttpTimeoutException -> DigestFailure.TIMEOUT
        is IOException -> DigestFailure.OFFLINE
        is CommunityFailure -> when (cause.code) {
            "rate_limited" -> DigestFailure.RATE_LIMITED
            "authentication" -> DigestFailure.AUTHENTICATION
            "invalid_source" -> DigestFailure.INVALID
            else -> DigestFailure.OFFLINE
        }
        else -> DigestFailure.INVALID
    }
}

internal fun safeDigestUrl(value: String?): String? = value?.takeIf { it.length <= 512 }?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == "discord.com" || uri.host == "hypixel.net" || uri.host.endsWith(".hypixel.net"))
    }?.toASCIIString()
}

internal fun safeDigestVideoUrl(value: String?, thumbnail: Boolean = false): String? = value?.takeIf { it.length <= 512 }?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
            if (thumbnail) uri.host in setOf("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com") &&
                uri.path.matches(Regex("/vi/[A-Za-z0-9_-]{11}/hqdefault\\.jpg")) && uri.query == null
            else uri.host in setOf("www.youtube.com", "youtube.com") &&
                (uri.path == "/watch" && uri.query?.matches(Regex("v=[A-Za-z0-9_-]{11}")) == true ||
                    uri.path.matches(Regex("/shorts/[A-Za-z0-9_-]{11}")) && uri.query == null)
    }?.toASCIIString()
}

internal fun safeDigestNewsUrl(value: String?, source: NewsSource): String? =
    if (source == NewsSource.DERAILIOUS) safeDigestVideoUrl(value) else safeDigestUrl(value)

/** Discord text stays inert; only explicitly selected, supported public links can open a page. */
internal fun digestNewsLinks(content: String, links: List<String> = emptyList()): List<String> {
    var length = 0
    return (links.asSequence() + globalChatSpans(content).asSequence().mapNotNull { it.url?.toString() })
        .mapNotNull { BrowserLinks.supported(it)?.toASCIIString() }.distinct()
        .filter { if (length + it.length > 1024) false else { length += it.length; true } }.take(8).toList()
}

internal fun digestPlainText(value: String, limit: Int): String = value.replace(Regex("§."), "")
    .filter { it == '\n' || it == '\t' || (!it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069') }
    .take(limit).trim()

/** Strict bounded DTO parsing; Discord content is inert text, never Minecraft click events. */
internal fun parseDigestNews(json: JsonObject, source: NewsSource, now: Long): List<DigestNewsItem> {
    require(json.get("source")?.asString == source.wire)
    val array = json.getAsJsonArray("items") ?: error("Missing news items")
    require(array.size() <= 8)
    val result = array.mapNotNull { element -> runCatching {
        val entry = element.asJsonObject
        val rawContent = entry.get("content").asString
        val rawTitle = entry.get("title")?.asString.orEmpty()
        require(rawContent.length <= 2500 && rawTitle.length <= 160)
        val content = digestPlainText(rawContent, 2500)
        val title = digestPlainText(rawTitle.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }, 160)
        val time = entry.get("publishedAt").asLong
        require(title.isNotBlank() && time in 1..now + 60000)
        val id = entry.get("id")?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,80}")) }
            ?: MessageDigest.getInstance("SHA-256").digest("${source.wire}|$time|$content".toByteArray())
                .joinToString("") { "%02x".format(it) }
        val url = safeDigestNewsUrl(entry.get("url")?.takeUnless { it.isJsonNull }?.asString, source)
        require(source != NewsSource.DERAILIOUS || url != null)
        val links = entry.get("links")?.let { raw ->
            require(raw.isJsonArray && raw.asJsonArray.size() <= 8)
            raw.asJsonArray.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 2048)
                value.asString
            }
        }.orEmpty()
        DigestNewsItem(id, source, title, content, time, url, entry.get("truncated")?.asBoolean == true,
            if (source == NewsSource.DERAILIOUS) safeDigestVideoUrl(entry.get("thumbnailUrl")?.takeUnless { it.isJsonNull }?.asString, true) else null,
            digestNewsLinks(content, links))
    }.getOrNull() }
    require(array.isEmpty || result.isNotEmpty())
    return result.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(8)
}

/** One request per source, stale-while-revalidate, and no automatic retry loop. Call on the client thread. */
class DigestNewsRepository(
    private val fetch: (NewsSource) -> CompletableFuture<JsonObject?>?,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val states = NewsSource.entries.associateWith { DigestNewsState() }.toMutableMap()
    private val pending = mutableMapOf<NewsSource, CompletableFuture<JsonObject?>>()
    private val nextAttempt = mutableMapOf<NewsSource, Long>()
    private var generation = 0
    val pendingCount get() = pending.size
    fun state(source: NewsSource): DigestNewsState = states.getValue(source)

    fun restore(source: NewsSource, state: DigestNewsState) {
        if (states.getValue(source).fetchedAt > state.fetchedAt) return
        states[source] = state.copy(items = state.items.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(8), loading = source in pending)
    }

    fun refresh(sources: Collection<NewsSource>, force: Boolean = false) {
        val time = now()
        sources.forEach { source ->
            val old = state(source)
            if (source in pending || time < (nextAttempt[source] ?: 0) || time < old.retryAt ||
                (!force && old.fetchedAt > 0 && time - old.fetchedAt in 0..299999)) return@forEach
            val future = runCatching { fetch(source) }.getOrNull()
            if (future == null) { states[source] = old.copy(failure = DigestFailure.OFFLINE); changed(); return@forEach }
            nextAttempt[source] = time + 60000
            val token = generation
            pending[source] = future
            states[source] = old.copy(loading = true)
            changed()
            future.whenComplete { response, error -> dispatch {
                if (token != generation || pending[source] !== future) return@dispatch
                pending.remove(source)
                val current = state(source)
                states[source] = try {
                    if (error != null) throw error
                    if (response == null) current.copy(loading = false, failure = DigestFailure.TIMEOUT)
                    else if (!response.has("status") && response.has("error")) current.copy(loading = false,
                        failure = when (response.get("error")?.asString) {
                            "rate_limited" -> DigestFailure.RATE_LIMITED
                            "authentication" -> DigestFailure.AUTHENTICATION
                            else -> DigestFailure.INVALID
                        }, retryAt = (response.get("retryAt")?.asLong ?: now() + 60000).coerceIn(now(), now() + 86400000))
                    else {
                        val status = response.get("status")?.asString
                        require(status in setOf("ready", "stale", "unavailable"))
                        val items = parseDigestNews(response, source, now())
                        val fetchedAt = response.get("fetchedAt")?.asLong ?: 0L
                        require(fetchedAt in 0..now() + 60000 && (status != "ready" || fetchedAt > 0))
                        val retryAt = (response.get("retryAt")?.asLong ?: 0L).coerceIn(0, now() + 86400000)
                        val failure = when (response.get("error")?.asString) {
                            "not_configured" -> DigestFailure.UNCONFIGURED
                            "timeout" -> DigestFailure.TIMEOUT
                            "invalid_response" -> DigestFailure.INVALID
                            "authentication" -> DigestFailure.AUTHENTICATION
                            "rate_limited" -> DigestFailure.RATE_LIMITED
                            else -> if (status == "ready") null else DigestFailure.OFFLINE
                        }
                        if (status == "unavailable" || (status == "stale" && items.isEmpty()))
                            current.copy(loading = false, failure = failure, retryAt = retryAt)
                        else DigestNewsState(items, fetchedAt, failure = failure, retryAt = retryAt)
                    }
                } catch (error: Exception) {
                    current.copy(loading = false, failure = digestNewsFailure(error))
                }
                changed()
            } }
        }
    }

    fun cancel() {
        generation++
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        states.replaceAll { _, value -> value.copy(loading = false) }
    }
}
