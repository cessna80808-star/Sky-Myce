package me.mycellium.skymyce.features.bazaar

import java.security.MessageDigest

/** One Minecraft account, SkyBlock profile and Hypixel network. Accounting never mixes contexts. */
data class BazaarContext(val account: String, val profile: String, val server: String = "main") {
    val key get() = "$account/$server/$profile"
}

/** How a record was established. Only OBSERVED and CORROBORATED records can reach public rankings. */
enum class BazaarEvidence { OBSERVED, CORROBORATED, ESTIMATED, MANUAL }

enum class BazaarEventKind {
    BUY_ORDER, BUY_FILL, BUY_COLLECT, SELL_ORDER, SELL_FILL, COINS_COLLECT,
    CANCEL, EXPIRE, RELIST, INVENTORY_MOVE, GAP, OBSERVATION, CORRECTION,
    /** A complete Manage Orders read no longer lists the order: its items or coins were claimed and any unfilled rest ended. */
    SYNC_CLOSE,
}

/** Hypixel's order menu: "Manage Orders", "Bazaar Orders", "Your Bazaar Orders" or, on co-op profiles, "Co-op Bazaar Orders". */
fun bazaarOrdersMenu(title: String): Boolean {
    val clean = title.replace(Regex("§."), "").trim()
    return clean == "Manage Orders" || clean == "Bazaar ➜ Orders" || clean.endsWith("Bazaar Orders")
}

/**
 * One immutable private journal entry. Fill quantities are deltas unless [cumulativeFilled] is present.
 * [coins] is the actual acquisition cost of a purchase or the NET proceeds of a sale.
 */
data class BazaarEvent(
    val id: String,
    val context: BazaarContext,
    val product: String,
    val kind: BazaarEventKind,
    val at: Long,
    val quantity: Long = 0,
    val unitPrice: Double? = null,
    val coins: Double? = null,
    val fee: Double? = null,
    val orderId: String? = null,
    val positionId: String? = null,
    val sessionId: String? = null,
    val cumulativeFilled: Long? = null,
    val evidence: BazaarEvidence = BazaarEvidence.OBSERVED,
    val note: String? = null,
    val replacesId: String? = null,
)

data class BazaarSession(
    val id: String,
    val context: BazaarContext,
    val startedAt: Long,
    val endedAt: Long? = null,
    val island: String,
    val reason: String? = null,
)

/** The append-only private journal of one Minecraft account. Public market context is cached separately. */
data class BazaarArchive(
    val version: Int = 1,
    val events: List<BazaarEvent> = emptyList(),
    val sessions: List<BazaarSession> = emptyList(),
)

enum class BazaarOrderStatus { OPEN, FILLED, CANCELLED, EXPIRED;
    val live get() = this == OPEN || this == FILLED
    val closed get() = this == CANCELLED || this == EXPIRED
}

/** The next manual step for one acquired position, in priority order. */
enum class BazaarStage(val label: String) {
    BUYING("Buy order open"),
    COLLECT("Collect bought items"),
    READY("Ready to sell"),
    SELLING("Sell offer open"),
    CLOSED("Closed"),
    WAITING("Waiting for a fill"),
}

data class BazaarPosition(
    val id: String,
    val product: String,
    val acquired: Long,
    val sold: Long,
    val remaining: Long,
    val costBasis: Double?,
    val realizedProfit: Double,
    val relistCount: Int,
    val openedAt: Long,
    val lastAt: Long,
    val stage: BazaarStage,
    val uncertain: Boolean,
    val sessionId: String?,
    val pendingItems: Long,
    val listed: Long,
) {
    val open get() = remaining > 0 || pendingItems > 0 || stage == BazaarStage.BUYING
}

data class BazaarOrder(
    val id: String,
    val positionId: String,
    val product: String,
    val buy: Boolean,
    val quantity: Long,
    val filled: Long,
    val collected: Long,
    val price: Double?,
    val status: BazaarOrderStatus,
    val at: Long,
    val lastAt: Long,
    val uncertain: Boolean,
)

data class BazaarRealization(
    val id: String,
    val product: String,
    val positionId: String,
    val at: Long,
    val quantity: Long,
    val gross: Double?,
    val proceeds: Double?,
    val cost: Double?,
    val fees: Double?,
    val netProfit: Double?,
    val eligible: Boolean,
    val evidence: BazaarEvidence,
    val sessionId: String?,
    val durationMillis: Long?,
    val fingerprint: String,
    val sourceEventId: String? = null,
    val orderId: String? = null,
)

/** A record kept out of public rankings. [awaiting] records resolve on their own once the next receipt arrives. */
data class BazaarPending(val eventId: String, val reason: String, val awaiting: Boolean = false)

data class BazaarLedger(
    val positions: List<BazaarPosition> = emptyList(),
    val orders: List<BazaarOrder> = emptyList(),
    val realizations: List<BazaarRealization> = emptyList(),
    val pending: List<BazaarPending> = emptyList(),
    val collectedCoins: Double = 0.0,
)

data class BazaarStats(
    val realizedProfit: Double,
    val eligibleProfit: Double,
    val realizedCost: Double,
    val netProceeds: Double,
    val completedFlips: Int,
    val realizations: Int,
    val roi: Double?,
    val profitPerHour: Double?,
    val durationMillis: Long,
    val uncertainRecords: Int,
)

internal val bazaarProductPattern = Regex("[A-Z0-9_:;-]{1,100}")
private val bazaarAccountPattern = Regex("[A-Fa-f0-9]{32}|[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")

internal fun bazaarRandomId() = java.util.UUID.randomUUID().toString().replace("-", "")

internal fun bazaarFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Readable name from a product ID when the item repository cannot supply the in-game name. */
internal fun bazaarFallbackName(product: String): String = product.lowercase().split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

internal fun reliableBazaarEvidence(evidence: BazaarEvidence) = evidence == BazaarEvidence.OBSERVED || evidence == BazaarEvidence.CORROBORATED
internal fun validBazaarMoney(value: Double?) = value == null || value.isFinite() && value in 0.0..1e15
internal fun validBazaarProductId(value: String?) = value != null && value.matches(bazaarProductPattern)
internal fun validBazaarId(value: String?, max: Int = 128) =
    value == null || value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)
internal fun validBazaarContext(value: BazaarContext) = value.account.matches(bazaarAccountPattern) &&
    value.profile.isNotBlank() && validBazaarId(value.profile, 64) && value.server in setOf("main", "alpha")

/** One explicit activation owns one island instance. Returning to the island never restarts an ended session. */
class BazaarSessionLifecycle {
    var active: BazaarSession? = null
        private set

    fun start(id: String, context: BazaarContext, island: String?, at: Long): BazaarSession? {
        require(validBazaarId(id) && validBazaarContext(context) && at > 0)
        active?.let { return it }
        if (island.isNullOrBlank()) return null
        return BazaarSession(id, context, at, island = island).also { active = it }
    }

    fun observe(context: BazaarContext?, island: String?, at: Long): BazaarSession? {
        val session = active ?: return null
        val left = context != session.context || island == null || island != session.island
        return if (left) stop(at, "Left the island") else null
    }

    fun stop(at: Long, reason: String = "Stopped manually"): BazaarSession? = active?.let {
        active = null
        it.copy(endedAt = maxOf(it.startedAt, at), reason = reason.take(160))
    }
}
