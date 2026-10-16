package me.mycellium.skymyce.features.bazaar

/** Personal repairs never upgrade observation evidence or execute a Bazaar action. */
internal fun bazaarCorrection(original: BazaarEvent, fields: Map<String, String>, resolvedProduct: String?, id: String, now: Long): BazaarEvent {
    val kinds = setOf(BazaarEventKind.BUY_FILL, BazaarEventKind.SELL_FILL, BazaarEventKind.BUY_ORDER, BazaarEventKind.SELL_ORDER)
    val classification = original.kind == BazaarEventKind.OBSERVATION
    require(classification || original.kind in kinds) {
        "This observation cannot be repaired as a trade. Dismiss it, or wait for its matching order or collection receipt."
    }
    require(original.replacesId == null) { "Choose the original observation to correct this record." }
    require(now in 1..253402300799999L && original.at <= now) { "This observation's time is invalid; keep it for review instead." }
    val selected = fields["kind"]?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        BazaarEventKind.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Choose Bought items, Sold items, Buy order or Sell offer.")
    }
    require(selected == null || selected in kinds) { "Choose Bought items, Sold items, Buy order or Sell offer." }
    require(classification || selected == null || selected == original.kind) { "An existing trade's type is fixed; correct its values instead." }
    val kind = if (classification) requireNotNull(selected) { "Choose what happened before saving this personal record." } else original.kind
    val suppliedProduct = fields["product"]?.trim()?.takeIf(String::isNotEmpty)
    require(suppliedProduct == null || resolvedProduct != null) { "Choose a recognized Bazaar item name or product ID." }
    val unknownProducts = setOf("BAZAAR", "UNKNOWN")
    val product = resolvedProduct ?: original.product.takeUnless { it in unknownProducts }
    require(product != null && product !in unknownProducts && product.matches(Regex("[A-Z0-9_:;-]{1,100}"))) {
        "Choose the Bazaar item this observation belongs to."
    }
    require(classification || product == original.product) { "An existing trade's item is fixed; correct its values instead." }
    val quantity = fields["quantity"]?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        require(value.matches(Regex("[0-9]+"))) { "Quantity must be a whole number, such as 320." }
        value.toLongOrNull() ?: throw IllegalArgumentException("Quantity must be between 1 and 1,000,000,000.")
    } ?: original.quantity
    require(quantity in 1..1_000_000_000) { "Enter a quantity between 1 and 1,000,000,000." }
    require(classification || original.cumulativeFilled == null || quantity == original.quantity) {
        "This cumulative fill quantity is fixed; correct its actual money values instead."
    }
    fun money(key: String, title: String, previous: Double?): Double? = fields[key]?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1e15 }
            ?: throw IllegalArgumentException("$title must be a finite amount from 0 to 1,000,000,000,000,000.")
    } ?: previous
    val price = money("unitPrice", "Unit price", original.unitPrice)
    val fee = money("fee", "Known fee", original.fee)
    val order = kind in setOf(BazaarEventKind.BUY_ORDER, BazaarEventKind.SELL_ORDER)
    val coins = money("coins", if (kind == BazaarEventKind.SELL_FILL) "Actual NET sale proceeds" else "Actual total acquisition cost", original.coins)
    if (order) require(price != null && price > 0) { "Enter the order's positive price per item. Placing an order is not a completed trade." }
    else require(coins != null) {
        if (kind == BazaarEventKind.SELL_FILL) "Enter the actual NET coins received after fees; estimated profit is not sale proceeds."
        else "Enter the actual total coins paid for these items; the current market price is not their historical cost."
    }
    if (price != null && coins != null && !(order && classification))
        require((price * quantity).let { it.isFinite() && it <= 1e15 }) { "Quantity × unit price exceeds the supported amount; correct these values." }
    if (kind == BazaarEventKind.SELL_FILL && price != null && coins != null)
        require(coins <= price * quantity + .1) { "NET sale proceeds exceed quantity × unit price. Correct the quantity, unit price or NET proceeds." }
    val note = fields["note"]?.trim()?.takeIf(String::isNotEmpty) ?: "Confirmed personal record"
    require(note.length <= 240 && note.none(Char::isISOControl)) { "Keep the optional note to 240 characters without control characters." }
    val effective = original.copy(product = product, kind = kind, quantity = quantity, unitPrice = price,
        coins = if (order && classification) null else coins, fee = fee, evidence = BazaarEvidence.MANUAL, note = note,
        orderId = if (classification) id.takeIf { order } else original.orderId,
        positionId = if (classification) null else original.positionId,
        cumulativeFilled = if (classification) null else original.cumulativeFilled)
    validateBazaarEvent(effective)
    return effective.copy(id = id, kind = if (classification) kind else BazaarEventKind.CORRECTION,
        replacesId = if (classification) null else original.id).also(::validateBazaarEvent)
}
