package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.utils.NumberUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class BazaarUiPoint(val at: Long, val value: Double)
data class BazaarUiTrade(val id: String, val product: String, val at: Long, val quantity: Long, val netProfit: Double?, val cost: Double?,
                         val fees: Double?, val eligible: Boolean, val evidence: String)
data class BazaarUiSession(val id: String, val title: String, val startedAt: Long, val endedAt: Long?, val profit: Double, val flips: Int,
                           val series: List<BazaarUiPoint> = emptyList(), val timeline: List<BazaarUiTrade> = emptyList())
data class BazaarUiPending(val id: String, val message: String, val kind: String? = null, val product: String? = null, val quantity: Long? = null,
                           val unitPrice: Double? = null, val coins: Double? = null, val fee: Double? = null)
data class BazaarUiPublication(val id: String, val sessionId: String, val url: String, val visibility: String)
/** The next manual step for one position; [actionable] steps need the player rather than the market. */
data class BazaarUiStep(val text: String, val actionable: Boolean)

/** One immutable snapshot for the terminal, HUD and local dashboard. Nothing here can trigger a trade. */
data class BazaarUiState(
    val revision: Long = 0,
    val active: Boolean = false,
    val status: String = "Loading your trading history…",
    val marketStatus: String = "Start flipping to load live market data",
    val source: String = "None",
    val observedAt: Long? = null,
    val retrievedAt: Long? = null,
    val freshness: BazaarFreshness = BazaarFreshness.UNAVAILABLE,
    val island: String? = null,
    val accountLabel: String = "Local account",
    val purse: Double? = null,
    val committed: Double = 0.0,
    val inventoryCost: Double? = null,
    val inventoryValue: Double? = null,
    val pendingCollection: Double? = null,
    val opportunities: List<BazaarOpportunity> = emptyList(),
    val positions: List<BazaarPosition> = emptyList(),
    val orders: List<BazaarOrder> = emptyList(),
    val nextSteps: Map<String, BazaarUiStep> = emptyMap(),
    val guidance: String? = null,
    val history: List<BazaarUiTrade> = emptyList(),
    val sessions: List<BazaarUiSession> = emptyList(),
    val pending: List<BazaarUiPending> = emptyList(),
    val awaiting: Int = 0,
    val stats: BazaarStats? = null,
    val settings: Map<String, String> = emptyMap(),
    val wizardDone: Boolean = false,
    val publicSharing: Boolean = false,
    val connectionStatus: String = "Local history works without Discord",
    val priceHistory: Map<String, List<BazaarUiPoint>> = emptyMap(),
    val publications: List<BazaarUiPublication> = emptyList(),
    val alert: String? = null,
    val alertSerial: Long = 0,
    val relistAdvice: Map<String, BazaarRelistAdvice> = emptyMap(),
    val portfolioWarnings: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val dashboardRunning: Boolean = false,
    val theme: Map<String, Int> = emptyMap(),
    val reduceMotion: Boolean = false,
    /** Last time the native Manage Orders menu was read on this profile; null until it has been read. */
    val ordersSyncedAt: Long? = null,
    /** A short, player-facing result of the last sync, such as "Read 3 orders"; null until the first sync. */
    val ordersSyncNote: String? = null,
    /** Order id → when Manage Orders last showed it. Orders missing from the latest read may be stale. */
    val orderSeenAt: Map<String, Long> = emptyMap(),
) {
    fun name(product: String) = names[product] ?: bazaarFallbackName(product)
    /** Positions still in progress, including closed ones whose sale coins wait to be claimed. */
    val openPositions get() = positions.filter { it.open || nextSteps[it.id]?.actionable == true }
}

data class BazaarUiResult(val message: String, val download: String? = null, val fileName: String? = null, val url: String? = null)

interface BazaarUiBridge {
    fun snapshot(): BazaarUiState
    fun action(name: String, fields: Map<String, String> = emptyMap()): CompletableFuture<BazaarUiResult>
}

/** Every local action either surface may request. Wire names are the dashboard protocol. */
enum class BazaarAction(val wire: String) {
    TOGGLE("toggle"), REFRESH("refresh"), GUIDE("guide"), OPEN_ORDERS("open-orders"), OPEN_BAZAAR("open-bazaar"),
    CONFIRM("confirm"), CORRECT("correct"), PREFERENCE("preference"), SETUP("setup"), WEBHOOK("webhook"), SETTINGS_SYNC("settings-sync"),
    EXPORT("export"), DELETE_HISTORY("delete-history"), DELETE_SESSION("delete-session"),
    PUBLISH("publish"), REVOKE("revoke"), DELETE_PUBLICATION("delete-publication"), SESSION_SUMMARY("session-summary"), WEBHOOK_SUMMARY("webhook-summary"),
    LEADERBOARD("leaderboard"), SHARING("sharing");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun of(wire: String) = byWire[wire]
    }
}

object BazaarUi {
    @Volatile var bridge: BazaarUiBridge? = null
    val actions = BazaarAction.entries.mapTo(linkedSetOf()) { it.wire }
}

/** Exact coins: tenths matter for prices, not for large totals. */
internal fun bazaarCoins(value: Double?): String = value?.takeIf(Double::isFinite)?.let {
    String.format(Locale.ROOT, if (kotlin.math.abs(it) >= 10_000) "%,.0f" else "%,.1f", it)
} ?: "Unknown"
internal fun bazaarCompactCoins(value: Double?): String = value?.takeIf(Double::isFinite)?.let(NumberUtils::condense) ?: "—"
internal fun bazaarSignedCoins(value: Double?): String = value?.takeIf(Double::isFinite)?.let { (if (it >= 0) "+" else "−") + NumberUtils.condense(kotlin.math.abs(it)) } ?: "—"
internal fun bazaarPercent(value: Double?): String = value?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "—"
internal fun bazaarTime(at: Long?): String = at?.let { DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "Ongoing"
internal fun bazaarDuration(seconds: Double?): String = seconds?.takeIf(Double::isFinite)?.let {
    when {
        it < 60 -> "${it.toInt()}s"
        it < 3600 -> "${(it / 60).toInt()}m"
        it < 86_400 -> String.format(Locale.ROOT, "%.1fh", it / 3600)
        else -> String.format(Locale.ROOT, "%.1fd", it / 86_400)
    }
} ?: "unknown"
internal fun bazaarAge(at: Long?, now: Long = System.currentTimeMillis()): String = at?.let {
    val seconds = ((now - it) / 1000).coerceAtLeast(0)
    if (seconds < 90) "${seconds}s ago" else if (seconds < 5400) "${seconds / 60}m ago" else "${seconds / 3600}h ago"
} ?: "never"

/**
 * The next manual step for an open position, in priority order: items or coins to claim, an offer to create,
 * relist fatigue, then waiting. Suggested prices sit one tenth of a coin inside the live spread.
 */
internal fun bazaarNextStep(position: BazaarPosition, orders: List<BazaarOrder>, quote: BazaarProduct?, live: Boolean,
                            relistThreshold: Int, name: String): BazaarUiStep {
    val own = orders.filter { it.positionId == position.id }
    val unclaimedSales = own.filter { !it.buy }.sumOf { (it.filled - it.collected).coerceAtLeast(0) }
    val ask = quote?.sellOfferPrice?.takeIf { live && it > 0 }?.let { (kotlin.math.ceil(it * 10 - 1e-7) - 1) / 10 }?.takeIf { it > 0 }
    return when {
        position.pendingItems > 0 -> BazaarUiStep("Claim ${position.pendingItems} $name from Manage Orders", true)
        unclaimedSales > 0 && position.stage != BazaarStage.SELLING -> BazaarUiStep("Claim the coins for $unclaimedSales sold from Manage Orders", true)
        position.stage == BazaarStage.READY -> BazaarUiStep("Create a sell offer for ${position.remaining}" +
            (ask?.let { " at about ${bazaarCoins(it)} each" } ?: ""), true)
        position.stage == BazaarStage.SELLING && position.relistCount > relistThreshold ->
            BazaarUiStep("Relisted ${position.relistCount} times: compare waiting with selling instantly", true)
        position.stage == BazaarStage.SELLING -> BazaarUiStep(if (unclaimedSales > 0) "Sell offer open · coins for $unclaimedSales ready to claim"
            else "Sell offer open · ${position.listed} left to sell", unclaimedSales > 0)
        position.stage == BazaarStage.BUYING -> {
            val order = own.firstOrNull { it.buy && it.status == BazaarOrderStatus.OPEN }
            BazaarUiStep(order?.let { "Buy order open · ${it.filled}/${it.quantity} filled" } ?: "Buy order open", false)
        }
        position.stage == BazaarStage.CLOSED -> BazaarUiStep("Done · ${bazaarSignedCoins(position.realizedProfit)} realized", false)
        else -> BazaarUiStep("Waiting for the first fill", false)
    }
}

/** Open positions most in need of the player first. */
internal fun bazaarPositionPriority(state: BazaarUiState): List<BazaarPosition> = state.openPositions.sortedWith(
    compareByDescending<BazaarPosition> { state.nextSteps[it.id]?.actionable == true }.thenByDescending { it.lastAt })
