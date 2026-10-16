package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.economy.BazaarConfig
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.concurrent.ScheduledFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The single market poller for every surface (terminal, HUD, dashboard, alerts). It polls only while a flipping
 * session is active, backs off on failure, and keeps the last good snapshot so its freshness decays honestly
 * instead of disappearing after one failed request.
 */
internal class BazaarMarketFeed(private val store: BazaarMarketStore, private val transport: BazaarTransport, private val changed: () -> Unit) {
    var snapshot: BazaarSnapshot? = null
        private set
    var status = "Start flipping to load live market data"
        private set
    private var archive = BazaarMarketArchive()
    private var archiveDirty = false
    private var savedAt = 0L
    private val writer = StateWriter<BazaarMarketArchive>({ store.save(it) }, { job -> Scheduling.schedule(500.milliseconds) { job() } }) {
        SkyMyce.logger.warn("Bazaar market cache could not be saved; it will be rebuilt from new data")
    }
    private var generation = 0L
    private var busy = false
    private var job: ScheduledFuture<*>? = null
    private var nextAt = 0L
    private var failures = 0
    private var requestedAt = 0L
    private val histories = linkedMapOf<String, Pair<Long, List<BazaarSample>>>()
    private var historyBusy = false

    fun freshness(now: Long = System.currentTimeMillis()) = bazaarFreshness(snapshot, now)

    fun load() {
        Scheduling.schedule(0.seconds) {
            val saved = store.load()
            MC.instance.execute { adopt(saved, migrated = false) }
        }
    }

    /**
     * Keeps whichever cached archive has more context. Migrated context (which older versions kept inside a
     * private journal) is written to the market cache.
     */
    fun adopt(cached: BazaarMarketArchive, migrated: Boolean) {
        if (cached.aggregates.size <= archive.aggregates.size && cached.snapshots.size <= archive.snapshots.size) return
        archive = cached
        archiveDirty = archiveDirty || migrated
        if (snapshot == null) snapshot = cached.snapshots.lastOrNull()?.let { enrichBazaarMarket(cached, it) }
        changed()
    }

    fun tick(active: Boolean, idle: Boolean, now: Long) {
        if (active && !busy && now >= nextAt) refresh(now, idle)
        if (archiveDirty && now - savedAt >= SAVE_INTERVAL_MILLIS) persist(now)
    }

    /** A user-requested refresh, still subject to source backoff. */
    fun requestRefresh(now: Long): String = when {
        busy -> "Already refreshing market data"
        now < nextAt && failures > 0 -> "Market source is backing off; retrying in ${(nextAt - now + 999) / 1000}s"
        now - requestedAt < MANUAL_GAP_MILLIS -> "Market data was just refreshed"
        else -> { refresh(now, idle = false); "Refreshing market data" }
    }

    fun stop() {
        generation++
        job?.cancel(true); job = null; busy = false; historyBusy = false; nextAt = 0
        if (archiveDirty) persist(System.currentTimeMillis())
    }

    fun flush() { if (archiveDirty) persist(System.currentTimeMillis()); runCatching { writer.flush() } }

    private fun persist(now: Long) {
        archiveDirty = false; savedAt = now
        writer.submit(archive)
    }

    private fun refresh(now: Long, idle: Boolean) {
        busy = true
        requestedAt = now
        val token = generation
        nextAt = now + if (idle) IDLE_POLL_MILLIS else ACTIVE_POLL_MILLIS
        job = Scheduling.schedule(0.seconds) {
            val result = runCatching { transport.fetch(now) }
            MC.instance.execute {
                if (token != generation) return@execute
                busy = false; job = null
                result.onSuccess(::accept).onFailure(::reject)
                changed()
            }
        }
    }

    private fun accept(fetched: BazaarSnapshot) {
        val now = System.currentTimeMillis()
        failures = 0
        val enriched = withSharedHistories(enrichBazaarMarket(archive, fetched))
        snapshot = enriched
        status = if ("fallback" in fetched.source) "Direct Hypixel data · Sky-Myce market service unavailable" else "Hypixel Bazaar via Sky-Myce"
        archive = rememberBazaarMarket(archive, enriched, now, BazaarConfig.marketRetentionDays)
        archiveDirty = true
    }

    private fun reject(failure: Throwable) {
        failures = (failures + 1).coerceAtMost(6)
        val now = System.currentTimeMillis()
        nextAt = maxOf(now + minOf(MAX_BACKOFF_MILLIS, ACTIVE_POLL_MILLIS shl (failures - 1)), (failure as? BazaarMarketFailure)?.retryAt ?: 0L)
        status = when (failure) {
            is BazaarMarketFailure -> if (failure.rateLimited) "Market source rate limit · paused" else "Market request rejected"
            is java.net.http.HttpTimeoutException -> "Market download timed out"
            is java.io.IOException -> "Market download failed"
            is IllegalArgumentException -> "Market response failed validation"
            else -> "Market data unavailable"
        } + " · retrying in ${(nextAt - now + 999) / 1000}s"
        SkyMyce.logger.warn("Bazaar market refresh failed ({}); next retry in {}s", failure.javaClass.simpleName, (nextAt - now).coerceAtLeast(0) / 1000)
    }

    /** Fetches shared hourly history for a few products the player is looking at, at most every 15 minutes each. */
    fun refreshHistories(products: List<String>) {
        if (historyBusy) return
        val now = System.currentTimeMillis()
        val wanted = products.filter { histories[it]?.first?.let { fetched -> now - fetched < HISTORY_TTL_MILLIS } != true }.take(4)
        if (wanted.isEmpty()) return
        historyBusy = true
        val token = generation
        Scheduling.schedule(0.seconds) {
            val points = linkedMapOf<String, List<BazaarSample>>()
            for (id in wanted) {
                if (token != generation) break
                points[id] = runCatching { transport.history(id, now) }.getOrDefault(emptyList())
            }
            MC.instance.execute {
                historyBusy = false
                if (token != generation) return@execute
                points.forEach { (id, history) -> histories[id] = now to history }
                while (histories.size > 64) histories.remove(histories.keys.first())
                if (points.values.any { it.isNotEmpty() }) {
                    snapshot = snapshot?.let(::withSharedHistories)
                    changed()
                }
            }
        }
    }

    private fun withSharedHistories(snapshot: BazaarSnapshot) = snapshot.copy(products = snapshot.products.map { product ->
        val shared = histories[product.id]?.second ?: return@map product
        product.copy(history = (shared + product.history).filter { it.at <= snapshot.observedAt }.distinctBy { it.at }.sortedBy { it.at }.takeLast(96))
    })

    private companion object {
        const val ACTIVE_POLL_MILLIS = 45_000L
        const val IDLE_POLL_MILLIS = 90_000L
        const val MANUAL_GAP_MILLIS = 10_000L
        const val MAX_BACKOFF_MILLIS = 15 * 60_000L
        const val SAVE_INTERVAL_MILLIS = 10 * 60_000L
        const val HISTORY_TTL_MILLIS = 15 * 60_000L
    }
}
