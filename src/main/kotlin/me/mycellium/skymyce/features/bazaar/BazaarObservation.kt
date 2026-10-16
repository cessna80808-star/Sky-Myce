package me.mycellium.skymyce.features.bazaar

import java.util.Locale
import kotlin.math.abs

data class BazaarObservedSlot(val slot: Int, val productId: String? = null, val name: String, val lore: List<String>, val orderId: String? = null)

/**
 * Turns server chat receipts and the native Manage Orders menu into journal events. It only reads; it has no
 * input, screen or packet API. Event identities derive from order progress, so replays and duplicates collapse.
 */
class BazaarObserver(private val canonicalProduct: (String) -> String? = { null }) {
    private val emitted = linkedSetOf<String>()
    private val messages = linkedMapOf<String, Long>()
    private val aliases = linkedMapOf<String, String?>()
    private val pendingSetups = ArrayDeque<PendingSetup>()
    private val pendingFlips = ArrayDeque<PendingFlip>()
    private var scope: BazaarContext? = null
    private var sequence = 0L

    fun reset() {
        emitted.clear(); messages.clear(); aliases.clear(); pendingSetups.clear(); pendingFlips.clear()
        scope = null; sequence = 0
    }

    private fun enter(value: BazaarContext) {
        require(validBazaarContext(value))
        if (scope != value) { reset(); scope = value }
    }

    private fun emit(event: BazaarEvent, result: MutableList<BazaarEvent>) {
        if (!emitted.add(event.id)) return
        while (emitted.size > 4096) emitted.remove(emitted.first())
        result += event
    }

    private fun id(value: String) = bazaarFingerprint("${scope?.key}|$value")

    private fun note(text: String, product: String, context: BazaarContext, now: Long, session: String?) =
        BazaarEvent(id("note|$now|${sequence++}|$text"), context, product, BazaarEventKind.OBSERVATION, now,
            sessionId = session, evidence = BazaarEvidence.ESTIMATED, note = text.take(240))

    /** Exact name matches only; two products with one display name stay ambiguous. */
    private fun product(name: String, names: Map<String, List<BazaarProduct>>): String? {
        val key = normalize(name)
        if (aliases.containsKey(key)) return aliases[key]
        val matches = names[key].orEmpty()
        if (matches.size > 1) return null
        val market = matches.singleOrNull()?.id
        val canonical = runCatching { canonicalProduct(key) }.getOrNull()?.uppercase(Locale.ROOT)?.takeIf(::validBazaarProductId)
        return if (market != null && canonical != null && market != canonical) null else market ?: canonical
    }

    fun chat(text: String, context: BazaarContext, ledger: BazaarLedger, products: List<BazaarProduct>, now: Long, sessionId: String?): List<BazaarEvent> {
        enter(context)
        val clean = strip(text)
        if (!clean.startsWith("[Bazaar] ") || clean.length > 1000 || now <= 0) return emptyList()
        val previous = messages.put(clean, now)
        while (messages.size > 256) messages.remove(messages.keys.first())
        val shortDuplicate = previous != null && now >= previous && now - previous < 250
        if (shortDuplicate && !claimItems.matches(clean) && !claimCoins.matches(clean)) return emptyList()
        val names = products.groupBy { normalize(it.name) }
        val receipt = Receipt(clean, context, ledger, now, sessionId, previous, shortDuplicate) { product(it, names) }
        return receipt.flipped() ?: receipt.setup() ?: receipt.filled() ?: receipt.claimedItems() ?: receipt.claimedCoins()
            ?: receipt.instant() ?: receipt.cancelled() ?: receipt.unrecognized()
    }

    /** One chat receipt; each matcher returns null when the text is not its kind. */
    private inner class Receipt(val text: String, val context: BazaarContext, val ledger: BazaarLedger, val now: Long,
        val session: String?, val previous: Long?, val shortDuplicate: Boolean, val product: (String) -> String?) {
        val result = mutableListOf<BazaarEvent>()
        fun done() = result.toList()
        fun note(message: String, product: String) = emit(note(message, product, context, now, session), result)

        fun flipped(): List<BazaarEvent>? {
            val match = flipPattern.matchEntire(text) ?: return null
            val quantity = integer(match.groupValues[1])?.takeIf { it > 0 } ?: return emptyList()
            val pending = PendingFlip("observed-${id("flip|$text|$now|${sequence++}").take(32)}", normalize(match.groupValues[2]), quantity, now, session)
            pendingFlips.addLast(pending)
            while (pendingFlips.size > 64) pendingFlips.removeFirst()
            emit(note("Manual order flip observed; awaiting its actual native offer price. Expected profit is not proceeds",
                product(match.groupValues[2]) ?: "BAZAAR", context, now, session).copy(orderId = pending.id), result)
            return done()
        }

        fun setup(): List<BazaarEvent>? {
            val match = setupPattern.matchEntire(text) ?: return null
            val product = product(match.groupValues[3]) ?: "BAZAAR"
            val quantity = integer(match.groupValues[2])
            val coins = money(match.groupValues[4])
            val buy = match.groupValues[1] == "Buy Order"
            val price = if (quantity != null && quantity > 0 && coins != null) coins / quantity else null
            val duplicate = previous != null || ledger.orders.any {
                it.product == product && it.buy == buy && it.quantity == quantity && it.status == BazaarOrderStatus.OPEN &&
                    price != null && it.price?.let { p -> abs(p - price) <= PRICE_EPSILON } == true
            }
            val held = ledger.positions.filter { it.product == product && it.remaining - it.pendingItems >= (quantity ?: Long.MAX_VALUE) && !it.uncertain }
            // An unrecognized item name waits for the native order menu to supply its product identity.
            val deferred = if (product == "BAZAAR" && quantity != null && quantity > 0 && price != null && price > 0 && previous == null) {
                PendingSetup("observed-${id("setup|$text|$now|${sequence++}").take(32)}", normalize(match.groupValues[3]), buy, quantity, price, now, session)
                    .also { pendingSetups.addLast(it); while (pendingSetups.size > 64) pendingSetups.removeFirst() }
            } else null
            if (product == "BAZAAR" || quantity == null || quantity <= 0 || price == null || price <= 0 || duplicate || !buy && held.size != 1) {
                emit(note("Order setup needs a unique receipt/position association; inspect Manage Orders or confirm this separate action", product, context, now, session)
                    .copy(quantity = quantity ?: 0, unitPrice = price, coins = if (buy) coins else null, orderId = deferred?.id), result)
                return done()
            }
            val order = "observed-${id("setup|$text|$now|${sequence++}").take(32)}"
            val position = if (buy) "$product:$order" else held.single().id
            emit(BazaarEvent(id("order|$order"), context, product, if (buy) BazaarEventKind.BUY_ORDER else BazaarEventKind.SELL_ORDER,
                now, quantity, price, coins = if (buy) coins else null, orderId = order, positionId = position, sessionId = session,
                note = "Observed server setup receipt; later fills require unique unchanged native order association"), result)
            if (!buy && ledger.orders.any { !it.buy && it.positionId == position && it.status.closed })
                emit(BazaarEvent(id("relist|$order"), context, product, BazaarEventKind.RELIST, now, orderId = order, positionId = position, sessionId = session), result)
            return done()
        }

        /** "Your order was filled!" names the whole order; the fill uses the same identity as Manage Orders progress. */
        fun filled(): List<BazaarEvent>? {
            val match = filledPattern.matchEntire(text) ?: return null
            val buy = match.groupValues[1] == "Buy Order"
            val amount = integer(match.groupValues[2])?.takeIf { it > 0 } ?: return emptyList()
            val product = product(match.groupValues[3]) ?: "BAZAAR"
            // Orders placed before tracking stay unknown; their later claim receipt asks for review instead.
            val order = ledger.orders.filter { it.buy == buy && it.product == product && it.status == BazaarOrderStatus.OPEN && it.quantity == amount }
                .singleOrNull() ?: return done()
            if (order.filled >= order.quantity) return done()
            val delta = order.quantity - order.filled
            val evidence = if (order.uncertain) BazaarEvidence.ESTIMATED else BazaarEvidence.OBSERVED
            // A filled buy order cost exactly its order price. Sale proceeds stay unknown until the coins are claimed.
            emit(BazaarEvent(id("${if (buy) "buy" else "sell"}-fill|${order.id}|${order.quantity}"), context, product,
                if (buy) BazaarEventKind.BUY_FILL else BazaarEventKind.SELL_FILL, now, delta, order.price,
                coins = if (buy) order.price?.times(delta) else null, orderId = order.id, positionId = order.positionId,
                sessionId = session, cumulativeFilled = order.quantity, evidence = evidence), result)
            return done()
        }

        fun claimedItems(): List<BazaarEvent>? {
            val match = claimItems.matchEntire(text) ?: return null
            val quantity = integer(match.groupValues[1]) ?: return emptyList()
            val product = product(match.groupValues[2]) ?: "BAZAAR"
            val total = money(match.groupValues[3])
            val price = money(match.groupValues[4])
            val order = ledger.orders.filter {
                it.buy && it.product == product && claimable(it) >= quantity &&
                    (price == null || it.price == null || abs(price - it.price) <= PRICE_EPSILON)
            }.singleOrNull()
            if (shortDuplicate && (order == null || order.filled - order.collected < quantity)) return emptyList()
            if (order == null || total == null || quantity <= 0 || price == null || abs(price * quantity - total) > maxOf(1.0, total * .00001)) {
                note("Item collection has no unique matching order/cost receipt; confirm acquisition quantities and cost", product)
                return done()
            }
            val alreadyFilled = order.filled - order.collected
            if (alreadyFilled < quantity) {
                // A later receipt can be a replay after cache expiry/reconnect; require new native fill progress.
                if (order.collected > 0 || alreadyFilled != 0L || !order.status.live) {
                    note("Collection has no new native fill progress; reopen Manage Orders or confirm this separate receipt", product)
                    return done()
                }
                val additional = quantity - alreadyFilled
                emit(BazaarEvent(id("buy-fill|${order.id}|${order.filled + additional}"), context, product, BazaarEventKind.BUY_FILL, now,
                    additional, price, total, orderId = order.id, positionId = order.positionId, sessionId = session,
                    cumulativeFilled = order.filled + additional, evidence = corroboration(order)), result)
            }
            emit(BazaarEvent(id("buy-collect|${order.id}|${order.collected + quantity}"), context, product, BazaarEventKind.BUY_COLLECT, now,
                quantity, orderId = order.id, positionId = order.positionId, sessionId = session, evidence = corroboration(order)), result)
            return done()
        }

        fun claimedCoins(): List<BazaarEvent>? {
            val match = claimCoins.matchEntire(text) ?: return null
            val total = money(match.groupValues[1]) ?: return emptyList()
            val quantity = integer(match.groupValues[2]) ?: return emptyList()
            val product = product(match.groupValues[3]) ?: "BAZAAR"
            val price = money(match.groupValues[4]) ?: return emptyList()
            val order = ledger.orders.filter {
                !it.buy && it.product == product && claimable(it) >= quantity && (it.price == null || abs(it.price - price) <= PRICE_EPSILON)
            }.singleOrNull()
            if (shortDuplicate && (order == null || order.filled - order.collected < quantity)) return emptyList()
            if (order == null || quantity <= 0 || total > price * quantity + 1) {
                note("Coin collection lacks a unique supported sale/order; it is not net profit by itself", product)
                return done()
            }
            // Sales filled without known proceeds are settled by this receipt's actual net coins.
            val unknown = ledger.realizations.filter { it.orderId == order.id && it.proceeds == null && it.sourceEventId != null }.groupBy { it.sourceEventId }
            when {
                unknown.isNotEmpty() && unknown.values.sumOf { rows -> rows.sumOf { it.quantity } } == quantity -> unknown.forEach { (source, portions) ->
                    val units = portions.sumOf { it.quantity }
                    emit(BazaarEvent(id("sale-proceeds|$source"), context, product, BazaarEventKind.SELL_FILL, now, units, price,
                        total * units / quantity, orderId = order.id, positionId = order.positionId, sessionId = session,
                        evidence = BazaarEvidence.CORROBORATED, replacesId = source), result)
                }
                order.filled - order.collected < quantity && order.filled == 0L && order.collected == 0L && order.status == BazaarOrderStatus.OPEN ->
                    emit(BazaarEvent(id("sell-fill|${order.id}|${order.filled + quantity}"), context, product, BazaarEventKind.SELL_FILL, now,
                        quantity, price, total, orderId = order.id, positionId = order.positionId, sessionId = session,
                        cumulativeFilled = order.filled + quantity, evidence = corroboration(order)), result)
                order.filled - order.collected != quantity || unknown.isNotEmpty() -> {
                    note("Collection overlaps partial sale receipts; actual net proceeds need reconciliation", product)
                    return done()
                }
            }
            emit(BazaarEvent(id("coin-collect|${order.id}|${order.collected + quantity}"), context, product, BazaarEventKind.COINS_COLLECT, now,
                quantity, coins = total, orderId = order.id, positionId = order.positionId, sessionId = session, evidence = corroboration(order)), result)
            return done()
        }

        fun instant(): List<BazaarEvent>? {
            val match = instantTrade.matchEntire(text) ?: return null
            note("Instant ${match.groupValues[1].lowercase(Locale.ROOT)} receipt needs an unambiguous acquisition/sale association; confirm quantity, actual net coins and cost basis",
                product(match.groupValues[3]) ?: "BAZAAR")
            return done()
        }

        fun cancelled(): List<BazaarEvent>? {
            val buyRefund = cancelBuy.matchEntire(text)?.let { money(it.groupValues[1]) }
            val order = buyRefund?.let { refund ->
                ledger.orders.filter {
                    it.buy && it.status == BazaarOrderStatus.OPEN && it.price != null &&
                        abs((it.quantity - it.filled) * it.price - refund) <= maxOf(1.0, refund * .00001)
                }.singleOrNull()
            } ?: cancelSell.matchEntire(text)?.let { match ->
                val quantity = integer(match.groupValues[1])
                val product = product(match.groupValues[2])
                ledger.orders.filter { !it.buy && it.status == BazaarOrderStatus.OPEN && it.product == product && it.quantity - it.filled == quantity }.singleOrNull()
            } ?: return null
            emit(BazaarEvent(id("cancel|${order.id}"), context, order.product, BazaarEventKind.CANCEL, now,
                coins = if (order.buy) buyRefund else null, orderId = order.id, positionId = order.positionId, sessionId = session), result)
            return done()
        }

        fun unrecognized(): List<BazaarEvent> {
            if (listOf("Cancelled!", "Expired!", "Claimed ", "Order Setup!", "Order Flipped!").any { it in text })
                note("Bazaar receipt needs order identity/amount confirmation; no transaction inferred", "BAZAAR")
            return done()
        }

        private fun claimable(order: BazaarOrder) =
            if (order.status.closed) order.filled - order.collected else order.quantity - order.collected
        private fun corroboration(order: BazaarOrder) = if (order.uncertain) BazaarEvidence.ESTIMATED else BazaarEvidence.CORROBORATED
    }

    fun container(title: String, rows: List<BazaarObservedSlot>, context: BazaarContext, ledger: BazaarLedger,
                  products: List<BazaarProduct>, now: Long, sessionId: String?): List<BazaarEvent> {
        enter(context)
        if (strip(title) !in orderMenus || rows.size > 90 || now <= 0) return emptyList()
        val result = mutableListOf<BazaarEvent>()
        while (pendingSetups.firstOrNull()?.let { now - it.at > PENDING_MILLIS } == true) pendingSetups.removeFirst()
        while (pendingFlips.firstOrNull()?.let { now - it.at > PENDING_MILLIS } == true) pendingFlips.removeFirst()
        val names = products.groupBy { normalize(it.name) }
        val parsed = rows.mapNotNull { nativeOrder(it, products, names) }
        val duplicateStable = parsed.mapNotNull { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        parsed.filter { it.id !in duplicateStable }.forEach { observeOrder(it, parsed, context, ledger, now, sessionId, result) }
        return result
    }

    private fun nativeOrder(slot: BazaarObservedSlot, products: List<BazaarProduct>, names: Map<String, List<BazaarProduct>>): NativeOrder? {
        if (slot.slot !in 0..89 || slot.lore.size > 64 || slot.name.length > 160 || slot.lore.any { it.length > 400 }) return null
        val match = orderName.matchEntire(strip(slot.name)) ?: return null
        val buy = match.groupValues[1].equals("BUY", true) || match.groupValues[1].equals("Buy Order", true)
        val product = slot.productId?.uppercase(Locale.ROOT)?.takeIf { id -> validBazaarProductId(id) && products.count { it.id == id } <= 1 }
            ?: product(match.groupValues[2], names) ?: return null
        val lore = slot.lore.map(::strip)
        val ratio = lore.firstNotNullOfOrNull { fillRatio.matchEntire(it) }
        val amount = lore.firstNotNullOfOrNull { amountLine.matchEntire(it)?.groupValues?.get(1)?.let(::integer) }
            ?: ratio?.groupValues?.get(2)?.let(::integer) ?: return null
        val price = lore.firstNotNullOfOrNull { priceLine.matchEntire(it)?.groupValues?.get(1)?.let(::money) } ?: return null
        val filled = ratio?.groupValues?.get(1)?.let(::integer)
            ?: if (lore.any { it.startsWith("Filled: ") && it.endsWith("100%!") }) amount else null
        val claimable = lore.firstNotNullOfOrNull { claimableItems.matchEntire(it)?.groupValues?.get(1)?.let(::integer) }
        if (amount !in 1..1_000_000_000 || filled != null && filled !in 0..amount || claimable != null && claimable !in 0..amount ||
            price <= 0 || price * amount > 1e15) return null
        val display = normalize(match.groupValues[2])
        if (!aliases.containsKey(display)) aliases[display] = product else if (aliases[display] != product) aliases[display] = null
        while (aliases.size > 4096) aliases.remove(aliases.keys.first())
        return NativeOrder(slot.orderId?.takeIf { validBazaarId(it) }, product, buy, amount, filled, price,
            lore.firstNotNullOfOrNull { netLine.matchEntire(it)?.groupValues?.get(1)?.let(::money) },
            lore.firstOrNull { it in closedLines }, claimable, display)
    }

    private fun observeOrder(native: NativeOrder, parsed: List<NativeOrder>, context: BazaarContext, ledger: BazaarLedger,
                             now: Long, sessionId: String?, result: MutableList<BazaarEvent>) {
        val same = ledger.orders.filter {
            it.product == native.product && it.buy == native.buy && it.quantity == native.amount &&
                (it.status == BazaarOrderStatus.OPEN || it.status == BazaarOrderStatus.FILLED && it.filled > it.collected) &&
                it.price?.let { price -> abs(price - native.price) <= PRICE_EPSILON } == true
        }
        val original = if (native.id != null) ledger.orders.firstOrNull { it.id == native.id } else same.singleOrNull()
        val twins = parsed.count { it.product == native.product && it.buy == native.buy && it.amount == native.amount && it.price == native.price }
        if (native.id == null && (same.size > 1 || twins > 1)) {
            val pendingId = id("ambiguous|${native.product}|${native.buy}|${native.amount}|${native.price}")
            if (ledger.pending.none { it.eventId == pendingId })
                emit(note("Identical Bazaar orders cannot be distinguished; keep them local until order identity is confirmed", native.product, context, now, sessionId)
                    .copy(id = pendingId), result)
            return
        }
        val setup = pendingSetups.filter {
            it.name == native.name && it.buy == native.buy && it.quantity == native.amount && abs(it.price - native.price) <= PRICE_EPSILON && it.at <= now
        }.singleOrNull()
        val flip = if (!native.buy) pendingFlips.filter { it.name == native.name && it.quantity == native.amount && it.at <= now }.singleOrNull() else null
        val orderId = original?.id ?: native.id ?: setup?.id ?: flip?.id
            ?: "local-${id("${native.product}|${native.buy}|${native.amount}|${native.price}").take(32)}"
        val held = ledger.positions.filter {
            it.product == native.product && !it.uncertain && it.remaining >= native.amount &&
                (it.remaining - it.pendingItems >= native.amount || flip != null && it.pendingItems >= native.amount)
        }
        val positionId = original?.positionId ?: if (native.buy) "${native.product}:$orderId" else held.singleOrNull()?.id ?: "${native.product}:$orderId"
        val reliable = (native.id != null || setup != null || flip != null || original != null && !original.uncertain && same.size == 1) &&
            (native.buy || held.singleOrNull() != null || original != null && !original.uncertain)
        if (original == null) {
            if (flip != null && held.singleOrNull()?.pendingItems?.let { it > 0 } == true) {
                // A flipped order moves the bought items straight into the new offer without a chat collection.
                val purchase = ledger.orders.filter { it.buy && it.positionId == positionId && it.filled - it.collected >= native.amount }.singleOrNull()
                if (purchase == null) {
                    emit(note("Manual flip has no unique acquired-item transfer; confirm its position", native.product, context, now, sessionId), result)
                    return
                }
                emit(BazaarEvent(id("buy-collect|${purchase.id}|${purchase.collected + native.amount}"), context, native.product, BazaarEventKind.BUY_COLLECT,
                    flip.at, native.amount, orderId = purchase.id, positionId = positionId, sessionId = flip.session, evidence = BazaarEvidence.CORROBORATED), result)
            }
            emit(BazaarEvent(id("order|$orderId"), context, native.product,
                if (native.buy) BazaarEventKind.BUY_ORDER else BazaarEventKind.SELL_ORDER, setup?.at ?: flip?.at ?: now, native.amount, native.price,
                orderId = orderId, positionId = positionId, sessionId = setup?.session ?: flip?.session ?: sessionId,
                evidence = if (reliable) BazaarEvidence.OBSERVED else BazaarEvidence.ESTIMATED,
                note = if (reliable) null else "No stable server order identity or acquired-position association"), result)
            if (setup != null) pendingSetups.remove(setup)
            if (flip != null) pendingFlips.remove(flip)
            if (!native.buy && reliable && ledger.orders.any { !it.buy && it.positionId == positionId && it.status.closed })
                emit(BazaarEvent(id("relist|$orderId"), context, native.product, BazaarEventKind.RELIST, now,
                    orderId = orderId, positionId = positionId, sessionId = sessionId), result)
        }
        val previous = original?.filled ?: 0
        val filled = native.filled ?: if (native.buy && native.claimable != null) (original?.collected ?: 0) + native.claimable else null
        if (filled != null && filled in (previous + 1)..native.amount) {
            val delta = filled - previous
            val coins = if (native.buy) delta * native.price else native.net?.takeIf { previous == 0L }
            emit(BazaarEvent(id("${if (native.buy) "buy" else "sell"}-fill|$orderId|$filled"), context, native.product,
                if (native.buy) BazaarEventKind.BUY_FILL else BazaarEventKind.SELL_FILL, now, delta, native.price, coins,
                orderId = orderId, positionId = positionId, sessionId = sessionId, cumulativeFilled = filled,
                evidence = if (reliable) BazaarEvidence.OBSERVED else BazaarEvidence.ESTIMATED), result)
        }
        if (native.closed != null && original != null && original.status.live)
            emit(BazaarEvent(id("closed|$orderId|${native.closed}"), context, native.product,
                if (native.closed == "Status: Cancelled") BazaarEventKind.CANCEL else BazaarEventKind.EXPIRE, now,
                orderId = orderId, positionId = positionId, sessionId = sessionId), result)
    }

    private data class NativeOrder(val id: String?, val product: String, val buy: Boolean, val amount: Long, val filled: Long?,
        val price: Double, val net: Double?, val closed: String?, val claimable: Long?, val name: String)
    private data class PendingSetup(val id: String, val name: String, val buy: Boolean, val quantity: Long, val price: Double, val at: Long, val session: String?)
    private data class PendingFlip(val id: String, val name: String, val quantity: Long, val at: Long, val session: String?)

    companion object {
        private const val PRICE_EPSILON = .000001
        private const val PENDING_MILLIS = 600_000L
        private const val NUMBER = "([0-9][0-9,]*(?:\\.[0-9]{1,6})?)"
        private val orderMenus = setOf("Manage Orders", "Your Bazaar Orders", "Bazaar Orders", "Bazaar ➜ Orders")
        private val closedLines = setOf("Status: Cancelled", "Status: Expired", "Expired!")
        private val setupPattern = Regex("^\\[Bazaar] (Buy Order|Sell Offer) Setup! $NUMBER[x×] (.+) for $NUMBER coins[.!]$")
        private val filledPattern = Regex("^\\[Bazaar] Your (Buy Order|Sell Offer) for $NUMBER[x×] (.+) was filled!$")
        private val flipPattern = Regex("^\\[Bazaar] Order Flipped! $NUMBER[x×] (.+) for $NUMBER coins of total expected profit\\.$")
        private val claimItems = Regex("^\\[Bazaar] Claimed $NUMBER[x×] (.+) worth $NUMBER coins bought for $NUMBER each!$")
        private val claimCoins = Regex("^\\[Bazaar] Claimed $NUMBER coins from selling $NUMBER[x×] (.+) at $NUMBER each!$")
        private val instantTrade = Regex("^\\[Bazaar] (Bought|Sold) $NUMBER[x×] (.+) for $NUMBER coins!$")
        private val orderName = Regex("^(Buy Order|Sell Offer|BUY|SELL):?\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val amountLine = Regex("^(?:Order amount|Offer amount|Amount): $NUMBER[x×]?(?: items)?$")
        private val priceLine = Regex("^(?:Price per unit|Price per item|Unit price): $NUMBER coins$")
        private val fillRatio = Regex("^Filled: $NUMBER/$NUMBER(?:\\s+(?:\\([0-9.,]+%\\)|[0-9.,]+%))?!?$")
        private val netLine = Regex("^(?:Net proceeds|After tax): $NUMBER coins$")
        private val claimableItems = Regex("^You have $NUMBER items? to claim!$")
        private val cancelBuy = Regex("^\\[Bazaar] Cancelled! Refunded $NUMBER coins from cancelling Buy Order!$")
        private val cancelSell = Regex("^\\[Bazaar] Cancelled! Refunded $NUMBER[x×] (.+) from cancelling Sell Offer!$")
        private val moneyPattern = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,6})?")
        private val colorCode = Regex("§.")
        private val whitespace = Regex("\\s+")
        private fun strip(value: String) = value.replace(colorCode, "").trim()
        private fun normalize(value: String) = strip(value).lowercase(Locale.ROOT).replace(whitespace, " ")
        private fun money(value: String): Double? {
            if (!value.matches(moneyPattern)) return null
            return value.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1e15 }
        }
        private fun integer(value: String) = money(value)?.takeIf { it % 1.0 == 0.0 && it <= 1_000_000_000 }?.toLong()
    }
}
