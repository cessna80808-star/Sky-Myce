package me.mycellium.skymyce.features.bazaar

private val orderKinds = setOf(BazaarEventKind.BUY_ORDER, BazaarEventKind.SELL_ORDER)
private val fillKinds = setOf(BazaarEventKind.BUY_FILL, BazaarEventKind.SELL_FILL)

fun validateBazaarEvent(event: BazaarEvent) {
    require(event.id.isNotBlank() && validBazaarId(event.id) && validBazaarContext(event.context) && event.at in 1..253402300799999L)
    require(validBazaarProductId(event.product) && event.quantity in 0..1_000_000_000)
    require(listOf(event.unitPrice, event.coins, event.fee).all(::validBazaarMoney))
    require(listOf(event.orderId, event.positionId, event.sessionId, event.replacesId).all { validBazaarId(it) })
    require(event.note == null || event.note.length <= 240 && event.note.none(Char::isISOControl))
    require(event.cumulativeFilled == null || event.cumulativeFilled in 0..1_000_000_000 && event.orderId != null)
    if (event.kind in orderKinds) require(event.quantity > 0 && event.orderId != null)
    if (event.kind in fillKinds) require(event.quantity > 0 || event.cumulativeFilled != null)
    if (event.kind == BazaarEventKind.CORRECTION) require(event.replacesId != null && event.evidence == BazaarEvidence.MANUAL)
    if (event.coins != null && event.unitPrice != null && event.quantity > 0 && event.cumulativeFilled == null) {
        val gross = event.unitPrice * event.quantity
        require(gross.isFinite() && gross <= 1e15)
        if (event.kind == BazaarEventKind.SELL_FILL) require(event.coins <= gross + .1)
    }
}

/** The caller supplies a stable receipt identity. Similar text is never proof of a second transaction. */
fun recordBazaarEvent(archive: BazaarArchive, event: BazaarEvent): BazaarArchive {
    validateBazaarEvent(event)
    val existing = archive.events.firstOrNull { it.id == event.id && it.context == event.context }
    if (existing != null) {
        require(existing == event) { "Conflicting Bazaar event identity; confirmation required" }
        return archive
    }
    if (event.replacesId != null) {
        val original = requireNotNull(archive.events.firstOrNull { it.id == event.replacesId && it.context == event.context })
        validateBazaarReplacement(event, original)
    }
    return archive.copy(events = archive.events + event)
}

/** A replacement is either a manual correction or a corroborated sale-proceeds settlement of an unpriced sale. */
internal fun validateBazaarReplacement(event: BazaarEvent, original: BazaarEvent) {
    require(event.replacesId == original.id && event.id != original.id && event.context == original.context &&
        event.product == original.product && original.replacesId == null)
    if (event.kind == BazaarEventKind.CORRECTION) {
        validateBazaarEvent(original.copy(
            quantity = event.quantity.takeIf { it > 0 } ?: original.quantity,
            unitPrice = event.unitPrice ?: original.unitPrice,
            coins = event.coins ?: original.coins,
            fee = event.fee ?: original.fee,
            evidence = BazaarEvidence.MANUAL,
        ))
        return
    }
    require(event.kind == BazaarEventKind.SELL_FILL && event.evidence == BazaarEvidence.CORROBORATED &&
        original.kind == BazaarEventKind.SELL_FILL && event.orderId != null && original.orderId == event.orderId &&
        event.coins != null && original.coins == null && event.quantity == original.quantity)
}

/** Corrections and settlements folded into the events they replace, in journal order. */
private fun effectiveBazaarEvents(journal: List<BazaarEvent>): List<BazaarEvent> {
    val order = journal.withIndex().associate { it.value.id to it.index }
    val originals = journal.associateBy { it.id }
    journal.filter { it.replacesId != null }.forEach { validateBazaarReplacement(it, requireNotNull(originals[it.replacesId])) }
    val corrections = journal.filter { it.kind == BazaarEventKind.CORRECTION }.associateBy { it.replacesId }
    val settlements = journal.filter { it.kind == BazaarEventKind.SELL_FILL && it.replacesId != null && it.evidence == BazaarEvidence.CORROBORATED }
        .associateBy { it.replacesId }
    return journal.filter { it.kind != BazaarEventKind.CORRECTION && it.replacesId == null }.map { original ->
        val settled = settlements[original.id]?.let {
            original.copy(coins = it.coins, fee = it.fee ?: original.fee,
                evidence = if (reliableBazaarEvidence(original.evidence)) BazaarEvidence.CORROBORATED else original.evidence)
        } ?: original
        corrections[original.id]?.let { correction ->
            settled.copy(
                quantity = correction.quantity.takeIf { it > 0 } ?: original.quantity,
                unitPrice = correction.unitPrice ?: settled.unitPrice,
                coins = correction.coins ?: settled.coins,
                fee = correction.fee ?: settled.fee,
                evidence = BazaarEvidence.MANUAL,
            )
        } ?: settled
    }.sortedWith(compareBy<BazaarEvent> { it.at }.thenBy { order[it.id] })
}

private class BazaarLot(val event: BazaarEvent, val position: String, var quantity: Long, var cost: Double?, var reliable: Boolean)

private class BazaarPositionState(val id: String, val product: String, val opened: Long, val session: String?) {
    var acquired = 0L
    var sold = 0L
    var relists = 0
    var last = opened
    var uncertain = false
}

/**
 * Deterministic FIFO replay of one context's journal. Lots are matched within the acquired position when the
 * sale identifies one, otherwise across the product. Unknown cost stays unknown and never becomes profit.
 */
private class BazaarReconciler(private val context: BazaarContext, events: List<BazaarEvent>) {
    private val lotsByPosition = mutableMapOf<String, ArrayDeque<BazaarLot>>()
    private val lotsByProduct = mutableMapOf<String, ArrayDeque<BazaarLot>>()
    private val positions = linkedMapOf<String, BazaarPositionState>()
    private val orders = linkedMapOf<String, BazaarOrder>()
    private val realized = mutableListOf<BazaarRealization>()
    private val pending = mutableListOf<BazaarPending>()
    private var collectedCoins = 0.0
    private val corroboratedOrders = events
        .filter { it.kind in orderKinds && reliableBazaarEvidence(it.evidence) }
        .mapNotNull { it.orderId }.toSet()

    init {
        for (event in events) {
            validateBazaarEvent(event)
            apply(event)
        }
    }

    private fun position(event: BazaarEvent, id: String) = positions.getOrPut(id) { BazaarPositionState(id, event.product, event.at, event.sessionId) }
    private fun fail(event: BazaarEvent, reason: String, awaiting: Boolean = false) { pending += BazaarPending(event.id, reason, awaiting) }

    private fun apply(event: BazaarEvent) {
        when (event.kind) {
            BazaarEventKind.GAP -> return gap(event)
            BazaarEventKind.OBSERVATION -> {
                if (event.orderId == null || event.orderId !in corroboratedOrders) fail(event, event.note ?: "Observation requires confirmation")
                return
            }
            else -> Unit
        }
        val existing = event.orderId?.let(orders::get)
        val positionId = event.positionId ?: existing?.positionId ?: "${event.product}:${event.orderId ?: event.id}"
        if (existing != null && (existing.product != event.product || event.positionId != null && existing.positionId != event.positionId)) {
            fail(event, "Order/product/position identity changed")
            return
        }
        when (event.kind) {
            BazaarEventKind.BUY_ORDER, BazaarEventKind.SELL_ORDER -> placeOrder(event, existing, positionId)
            BazaarEventKind.BUY_FILL, BazaarEventKind.SELL_FILL -> fill(event, existing, positionId)
            BazaarEventKind.BUY_COLLECT -> collectItems(event, existing)
            BazaarEventKind.COINS_COLLECT -> collectCoins(event, existing)
            BazaarEventKind.CANCEL, BazaarEventKind.EXPIRE -> close(event, existing)
            BazaarEventKind.RELIST -> relist(event, positionId)
            BazaarEventKind.SYNC_CLOSE -> syncClose(event, existing)
            else -> Unit
        }
    }

    /** A missed observation window makes every open lot and order unsuitable for public sharing. */
    private fun gap(event: BazaarEvent) {
        lotsByPosition.values.forEach { queue -> queue.forEach { if (it.quantity > 0) it.reliable = false } }
        orders.replaceAll { _, order -> if (order.status == BazaarOrderStatus.OPEN) order.copy(uncertain = true) else order }
        positions.values.filter { it.acquired > it.sold }.forEach { it.uncertain = true }
        fail(event, "Observation gap; existing inventory/orders need reconciliation before public sharing")
    }

    private fun placeOrder(event: BazaarEvent, existing: BazaarOrder?, positionId: String) {
        if (existing != null) { fail(event, "Order already observed; use cumulative fills rather than replaying placement"); return }
        val state = position(event, positionId)
        val buy = event.kind == BazaarEventKind.BUY_ORDER
        val held = lotsByPosition[positionId].orEmpty().sumOf { it.quantity }
        val othersListed = orders.values.filter { !it.buy && it.positionId == positionId && it.status == BazaarOrderStatus.OPEN }
            .sumOf { it.quantity - it.filled }
        val oversold = !buy && held < event.quantity + othersListed
        if (oversold) {
            state.uncertain = true
            fail(event, "Sell offer exceeds matched inventory; cost basis needs confirmation")
        }
        val reliable = reliableBazaarEvidence(event.evidence)
        orders[checkNotNull(event.orderId)] = BazaarOrder(event.orderId, positionId, event.product, buy, event.quantity, 0, 0,
            event.unitPrice, BazaarOrderStatus.OPEN, event.at, event.at, !reliable || oversold)
        if (!reliable) fail(event, event.note ?: "Order identity/association needs confirmation; kept local")
        state.last = maxOf(state.last, event.at)
        if (event.fee != null && event.fee > 0) allocateCost(lotsByPosition[positionId].orEmpty(), event.fee)
    }

    private fun fill(event: BazaarEvent, existing: BazaarOrder?, positionId: String) {
        val buy = event.kind == BazaarEventKind.BUY_FILL
        if (existing != null && (existing.buy != buy || !existing.status.live)) { fail(event, "Fill conflicts with order state"); return }
        if (event.cumulativeFilled != null && existing == null) { fail(event, "Cumulative fill has no observed original order"); return }
        val quantity = event.cumulativeFilled?.let { it - checkNotNull(existing).filled } ?: event.quantity
        if (quantity <= 0) return
        if (existing != null && existing.filled + quantity > existing.quantity) { fail(event, "Fill exceeds observed order quantity"); return }
        val state = if (buy || event.positionId != null || existing != null) position(event, positionId) else null
        state?.let { it.last = maxOf(it.last, event.at) }
        // Cumulative snapshots carry delta cash or leave it unknown; cumulative cash is never allocated twice.
        val reliable = event.evidence == BazaarEvidence.CORROBORATED || reliableBazaarEvidence(event.evidence) && existing?.uncertain != true
        if (buy) acquire(event, checkNotNull(state), positionId, quantity, reliable)
        else sell(event, existing, state, positionId, quantity, reliable)
        if (existing != null) {
            val filled = existing.filled + quantity
            orders[existing.id] = existing.copy(filled = filled,
                status = if (filled == existing.quantity) BazaarOrderStatus.FILLED else BazaarOrderStatus.OPEN,
                lastAt = event.at, uncertain = existing.uncertain || !reliable)
        }
    }

    private fun acquire(event: BazaarEvent, state: BazaarPositionState, positionId: String, quantity: Long, reliable: Boolean) {
        state.acquired += quantity
        val lot = BazaarLot(event, positionId, quantity, event.coins, reliable && event.coins != null)
        lotsByPosition.getOrPut(positionId) { ArrayDeque() }.addLast(lot)
        lotsByProduct.getOrPut(event.product) { ArrayDeque() }.addLast(lot)
        if (event.coins == null) {
            state.uncertain = true
            fail(event, "Acquisition cost unknown; market prices are not historical cost basis")
        }
    }

    private fun sell(event: BazaarEvent, existing: BazaarOrder?, state: BazaarPositionState?, positionId: String, quantity: Long, reliable: Boolean) {
        val proceeds = event.coins
        // An order's sale is settled by its later coin-claim receipt, so that wait is expected rather than a problem.
        if (proceeds == null) fail(event, "Actual net sale proceeds unknown; corroborate the collection receipt before public sharing",
            awaiting = existing != null && reliable)
        val matched = if (event.positionId == null && existing == null) lotsByProduct[event.product] else lotsByPosition[positionId]
        var remaining = quantity
        var portion = 0
        while (remaining > 0 && !matched.isNullOrEmpty()) {
            val lot = matched.first()
            if (lot.quantity == 0L) { matched.removeFirst(); continue }
            val sold = minOf(remaining, lot.quantity)
            val allocatedCost = lot.cost?.let { it * sold / lot.quantity }
            val share = sold.toDouble() / quantity
            val portionProceeds = proceeds?.times(share)
            val gross = event.unitPrice?.let { it * sold }
            val fees = event.fee?.times(share) ?: if (gross != null && portionProceeds != null && gross >= portionProceeds) gross - portionProceeds else null
            val profit = if (portionProceeds != null && allocatedCost != null) portionProceeds - allocatedCost else null
            val owner = positions.getValue(lot.position)
            owner.sold += sold
            owner.last = maxOf(owner.last, event.at)
            val eligible = reliable && lot.reliable && profit != null && event.at >= lot.event.at
            val evidence = when {
                eligible -> event.evidence
                event.evidence == BazaarEvidence.MANUAL || lot.event.evidence == BazaarEvidence.MANUAL -> BazaarEvidence.MANUAL
                else -> BazaarEvidence.ESTIMATED
            }
            val fingerprint = bazaarFingerprint(listOf(context.account, context.server, context.profile, event.orderId ?: event.id,
                event.cumulativeFilled ?: event.id, lot.event.id, sold).joinToString("|"))
            realized += BazaarRealization("${event.id}:${portion++}", event.product, lot.position, event.at, sold, gross, portionProceeds,
                allocatedCost, fees, profit, eligible, evidence, event.sessionId, (event.at - lot.event.at).takeIf { it >= 0 },
                fingerprint, event.id, event.orderId)
            lot.quantity -= sold
            lot.cost = if (lot.quantity == 0L) 0.0 else lot.cost?.minus(allocatedCost ?: 0.0)
            remaining -= sold
        }
        if (remaining <= 0) return
        val unmatched = state ?: position(event, positionId)
        unmatched.sold += remaining
        unmatched.uncertain = true
        val share = remaining.toDouble() / quantity
        realized += BazaarRealization("${event.id}:unknown", event.product, positionId, event.at, remaining,
            event.unitPrice?.times(remaining), proceeds?.times(share), null, event.fee?.times(share), null, false,
            BazaarEvidence.ESTIMATED, event.sessionId, null, bazaarFingerprint("${context.key}|${event.id}|unknown"), event.id, event.orderId)
        fail(event, "Sold quantity has no observed acquisition; no realized profit inferred")
    }

    private fun collectItems(event: BazaarEvent, existing: BazaarOrder?) {
        if (existing == null || !existing.buy || event.quantity > existing.filled - existing.collected) {
            fail(event, "Item collection cannot be matched to acquired items")
            return
        }
        orders[existing.id] = existing.copy(collected = existing.collected + event.quantity, lastAt = event.at)
        positions[existing.positionId]?.let { it.last = event.at }
    }

    /** Collected coins only settle supported sale proceeds; a collection is never profit by itself. */
    private fun collectCoins(event: BazaarEvent, existing: BazaarOrder?) {
        if (!reliableBazaarEvidence(event.evidence) || event.coins == null) { fail(event, "Coin collection amount/order requires confirmation"); return }
        val proceeds = realized.sumOf { it.proceeds ?: 0.0 }
        if (collectedCoins + event.coins > proceeds + .1) { fail(event, "Collection exceeds supported sale proceeds; collection is not profit"); return }
        collectedCoins += event.coins
        if (existing != null && !existing.buy)
            orders[existing.id] = existing.copy(collected = minOf(existing.filled, existing.collected + event.quantity), lastAt = event.at)
    }

    private fun close(event: BazaarEvent, existing: BazaarOrder?) {
        if (existing == null || !existing.status.live) { fail(event, "Cancellation/expiry has no matching open order"); return }
        val status = if (event.kind == BazaarEventKind.CANCEL) BazaarOrderStatus.CANCELLED else BazaarOrderStatus.EXPIRED
        orders[existing.id] = existing.copy(status = status, lastAt = event.at)
    }

    /**
     * Manage Orders no longer lists the order, so whatever filled was claimed and the unfilled rest was cancelled or
     * expired. A buy order that vanished before filling may have filled unseen, so its position needs a check.
     */
    private fun syncClose(event: BazaarEvent, existing: BazaarOrder?) {
        if (existing == null || !existing.status.live) return
        val complete = existing.filled >= existing.quantity
        orders[existing.id] = existing.copy(collected = existing.filled, lastAt = event.at,
            status = if (complete) BazaarOrderStatus.FILLED else BazaarOrderStatus.CANCELLED)
        positions[existing.positionId]?.let { state ->
            state.last = maxOf(state.last, event.at)
            if (existing.buy && !complete && existing.filled > 0) state.uncertain = true
        }
    }

    /** Relists count against the acquired position, so recreating an offer never resets fatigue. */
    private fun relist(event: BazaarEvent, positionId: String) {
        val state = positions[positionId]
        if (!reliableBazaarEvidence(event.evidence) || state == null || state.acquired <= state.sold || event.positionId == null) {
            fail(event, "Relist must identify the same acquired position with confirmed evidence")
            return
        }
        state.relists++
        state.last = event.at
        if (event.fee != null && event.fee > 0) allocateCost(lotsByPosition[positionId].orEmpty(), event.fee)
    }

    private fun allocateCost(lots: Collection<BazaarLot>, cost: Double) {
        val held = lots.filter { it.quantity > 0 }
        val quantity = held.sumOf { it.quantity }
        if (quantity > 0) held.forEach { it.cost = it.cost?.plus(cost * it.quantity / quantity) }
    }

    fun ledger(): BazaarLedger {
        val ordersByPosition = orders.values.groupBy { it.positionId }
        val realizationsByPosition = realized.groupBy { it.positionId }
        val result = positions.values.map { state ->
            val held = lotsByPosition[state.id].orEmpty().filter { it.quantity > 0 }
            val quantity = held.sumOf { it.quantity }
            val basis = if (held.all { it.cost != null }) held.sumOf { it.cost ?: 0.0 } else null
            val own = ordersByPosition[state.id].orEmpty()
            val listed = own.filter { !it.buy && it.status == BazaarOrderStatus.OPEN }.sumOf { it.quantity - it.filled }
            val pendingItems = own.filter { it.buy }.sumOf { (it.filled - it.collected).coerceAtLeast(0) }
            val stage = when {
                own.any { it.buy && it.status == BazaarOrderStatus.OPEN } -> BazaarStage.BUYING
                listed > 0 -> BazaarStage.SELLING
                pendingItems > 0 -> BazaarStage.COLLECT
                quantity > 0 -> BazaarStage.READY
                state.sold > 0 -> BazaarStage.CLOSED
                else -> BazaarStage.WAITING
            }
            BazaarPosition(state.id, state.product, state.acquired, state.sold, quantity, basis,
                realizationsByPosition[state.id].orEmpty().sumOf { it.netProfit ?: 0.0 }, state.relists, state.opened, state.last,
                stage, state.uncertain || held.any { !it.reliable }, state.session, pendingItems, listed)
        }
        return BazaarLedger(result, orders.values.toList(), realized, pending, collectedCoins)
    }
}

/** FIFO within one Minecraft account/profile/server, product and (when supplied) acquired position. */
fun reconcileBazaar(archive: BazaarArchive, context: BazaarContext): BazaarLedger {
    require(validBazaarContext(context))
    val journal = archive.events.filter { it.context == context }.distinctBy { it.id }
    return BazaarReconciler(context, effectiveBazaarEvents(journal)).ledger()
}

internal fun bazaarClosedPositions(ledger: BazaarLedger, records: Collection<BazaarRealization>): Int {
    val sold = records.mapTo(hashSetOf()) { it.positionId }
    return ledger.positions.count { it.stage == BazaarStage.CLOSED && it.id in sold }
}

/** Profit/hour uses summed matched-position holding time; concurrent positions add time, partial sales do not duplicate it. */
fun bazaarStats(ledger: BazaarLedger, from: Long? = null, until: Long? = null): BazaarStats {
    val records = ledger.realizations.filter { (from == null || it.at >= from) && (until == null || it.at < until) }
    val known = records.filter { it.netProfit != null }
    val cost = known.sumOf { it.cost ?: 0.0 }
    val profit = known.sumOf { it.netProfit ?: 0.0 }
    val duration = records.groupBy { it.positionId }.values
        .sumOf { portions -> portions.mapNotNull { it.durationMillis }.maxOrNull() ?: 0L }.coerceAtLeast(0)
    return BazaarStats(
        realizedProfit = profit,
        eligibleProfit = records.filter { it.eligible }.sumOf { it.netProfit ?: 0.0 },
        realizedCost = cost,
        netProceeds = known.sumOf { it.proceeds ?: 0.0 },
        completedFlips = bazaarClosedPositions(ledger, records),
        realizations = records.size,
        roi = if (cost > 0) profit / cost else null,
        profitPerHour = if (duration > 0) profit * 3_600_000 / duration else null,
        durationMillis = duration,
        uncertainRecords = records.count { !it.eligible } + ledger.pending.size,
    )
}
