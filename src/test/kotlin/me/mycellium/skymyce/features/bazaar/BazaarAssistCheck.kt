package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.config.economy.parseAssistColor
import me.mycellium.skymyce.features.bazaar.assist.*

private val carrot = AssistMission("ENCHANTED_CARROT", "Enchanted Carrot", 640, 152.3, 171.0)

private fun container(title: String, vararg slots: AssistSlot) = AssistScreen(AssistScreenKind.CONTAINER, title, slots.toList(), id = 7)
private fun sign(vararg lines: String) = AssistScreen(AssistScreenKind.SIGN, signLines = lines.toList(), id = 8)
private fun slot(index: Int, name: String, product: String? = null) = AssistSlot(index, name, product)

private fun position(stage: BazaarStage, pending: Long = 0, remaining: Long = 0, listed: Long = 0) = BazaarPosition("p", carrot.product,
    640, 0, remaining, 97_000.0, 0.0, 0, 1_000, 2_000, stage, false, "s", pending, listed)

private fun order(buy: Boolean, quantity: Long, filled: Long, collected: Long, status: BazaarOrderStatus) =
    BazaarOrder(if (buy) "b" else "s", "p", carrot.product, buy, quantity, filled, collected, 1.0, status, 1_000, 2_000, false)

fun main() {
    checkAssistViews()
    checkAssistPhases()
    checkAssistBuySteps()
    checkAssistSellAndClaimSteps()
    checkAssistNeverGuesses()
    checkAssistTextHelpers()
    println("Bazaar Assistant checks passed: screen recognition, phases, step sequence, unambiguous highlights, ghost text, sign checks and colors")
}

private fun checkAssistViews() {
    check(assistView(AssistScreen()) == AssistView.NONE)
    check(assistView(container("Bazaar ➜ Farming")) == AssistView.MAIN)
    check(assistView(container("Bazaar ➜ \"carrot\"")) == AssistView.SEARCH_RESULTS)
    check(assistView(container("Farming ➜ Enchanted Carrot", slot(10, "Create Buy Order"), slot(11, "Create Sell Offer"))) == AssistView.PRODUCT)
    check(assistView(container("How many do you want?")) == AssistView.BUY_AMOUNT)
    check(assistView(container("How much do you want to pay?")) == AssistView.BUY_PRICE)
    check(assistView(container("Confirm Buy Order")) == AssistView.BUY_CONFIRM)
    check(assistView(container("How many are you selling?")) == AssistView.SELL_AMOUNT)
    check(assistView(container("At what price are you selling?")) == AssistView.SELL_PRICE)
    check(assistView(container("Confirm Sell Offer")) == AssistView.SELL_CONFIRM)
    check(assistView(container("Bazaar Orders")) == AssistView.ORDERS)
    check(assistView(container("Large Chest")) == AssistView.OTHER)
    check(assistView(sign("", "^^^^^^^^^^^^^^^", "Enter query", "")) == AssistView.SIGN_OTHER) { "Search signs are identified by their fourth line" }
    check(assistView(sign("", "^^^^^^^^^^^^^^^", "", "Enter query")) == AssistView.SIGN_SEARCH)
    check(assistView(sign("", "^^^^^^^^^^^^^^^", "Enter amount", "to order")) == AssistView.SIGN_AMOUNT)
    check(assistView(sign("", "^^^^^^^^^^^^^^^", "Enter price", "big nerd")) == AssistView.SIGN_PRICE)
    check(assistView(sign("Hello", "", "", "")) == AssistView.SIGN_OTHER)
}

private fun checkAssistPhases() {
    check(assistPhase(null, emptyList()) == AssistPhase.PLACE_BUY)
    check(assistPhase(position(BazaarStage.BUYING), listOf(order(true, 640, 120, 0, BazaarOrderStatus.OPEN))) == AssistPhase.WAIT_BUY)
    check(assistPhase(position(BazaarStage.COLLECT, pending = 640), listOf(order(true, 640, 640, 0, BazaarOrderStatus.FILLED))) == AssistPhase.CLAIM_ITEMS)
    check(assistPhase(position(BazaarStage.READY, remaining = 640), emptyList()) == AssistPhase.PLACE_SELL)
    check(assistPhase(position(BazaarStage.SELLING, remaining = 640, listed = 640), listOf(order(false, 640, 0, 0, BazaarOrderStatus.OPEN))) == AssistPhase.WAIT_SELL)
    check(assistPhase(position(BazaarStage.SELLING, remaining = 0), listOf(order(false, 640, 640, 0, BazaarOrderStatus.FILLED))) == AssistPhase.CLAIM_COINS)
    check(assistPhase(position(BazaarStage.CLOSED), listOf(order(false, 640, 640, 640, BazaarOrderStatus.FILLED))) == AssistPhase.DONE)
}

private fun checkAssistBuySteps() {
    val phase = AssistPhase.PLACE_BUY
    val open = assistStep(carrot, phase, AssistScreen())
    check(open.kind == AssistKind.COMMAND && open.command == "bz Enchanted Carrot")
    val search = assistStep(carrot, phase, sign("", "", "", "Enter query"))
    check(search.kind == AssistKind.SIGN && search.sign == AssistSign.SEARCH && search.value == "Enchanted Carrot")
    val results = assistStep(carrot, phase, container("Bazaar ➜ \"carrot\"", slot(11, "Carrot", "CARROT_ITEM"), slot(12, "Enchanted Carrot", "ENCHANTED_CARROT"),
        slot(13, "Enchanted Golden Carrot", "ENCHANTED_GOLDEN_CARROT")))
    check(results.kind == AssistKind.CLICK && results.slot == 12)
    val product = container("Farming ➜ Enchanted Carrot", slot(15, "Create Buy Order"), slot(16, "Create Sell Offer"))
    check(assistStep(carrot, phase, product).slot == 15)
    val wrong = assistStep(carrot, phase, container("Farming ➜ Enchanted Potato", slot(15, "Create Buy Order")))
    check(wrong.kind == AssistKind.CLOSE && wrong.slot == null) { "Another product's page must not highlight its buy button" }
    check(assistStep(carrot, phase, container("How many do you want?", slot(10, "Buy a stack!"), slot(16, "Custom Amount"))).slot == 16)
    check(assistStep(carrot, phase, sign("", "", "Enter amount", "to order")).value == "640")
    val price = container("How much do you want to pay?", slot(10, "Same as Top Order"), slot(12, "Top Order +0.1"), slot(16, "Custom Price"))
    check(assistStep(carrot, phase, price).slot == 12)
    check(assistStep(carrot, phase, price, AssistPriceMode.CUSTOM).slot == 16)
    check(assistStep(carrot, phase, sign("", "", "Enter price", "big nerd"), AssistPriceMode.CUSTOM).value == "152.3")
    check(assistStep(carrot, phase, container("Confirm Buy Order", slot(13, "Buy Order"))).slot == 13)
    check(assistStep(carrot, phase, container("Large Chest", slot(13, "Buy Order"))).kind == AssistKind.CLOSE) { "Unrelated chests are never guided" }
}

private fun checkAssistSellAndClaimSteps() {
    val sell = AssistPhase.PLACE_SELL
    check(assistStep(carrot, sell, container("Farming ➜ Enchanted Carrot", slot(15, "Create Buy Order"), slot(16, "Create Sell Offer"))).slot == 16)
    check(assistStep(carrot, sell, container("How many are you selling?", slot(16, "Custom Amount")), remaining = 320).hint.contains("320"))
    check(assistStep(carrot, sell, sign("", "", "Enter amount", "to sell"), remaining = 320).value == "320")
    check(assistStep(carrot, sell, container("At what price are you selling?", slot(10, "Best Offer -0.1"))).slot == 10)
    check(assistStep(carrot, sell, sign("", "", "Enter price", "per unit")).value == "171")
    check(assistStep(carrot, sell, container("Confirm Sell Offer", slot(13, "Sell Offer"))).slot == 13)
    val orders = container("Bazaar Orders", slot(10, "BUY Enchanted Carrot"), slot(11, "SELL Enchanted Carrot"), slot(12, "SELL Enchanted Potato"))
    check(assistStep(carrot, AssistPhase.CLAIM_ITEMS, orders).slot == 10)
    check(assistStep(carrot, AssistPhase.CLAIM_COINS, orders).slot == 11)
    check(assistStep(carrot, AssistPhase.CLAIM_COINS, AssistScreen()).command == "bz")
    check(assistStep(carrot, AssistPhase.CLAIM_COINS, container("Bazaar ➜ Farming", slot(50, "Manage Orders"))).slot == 50)
    check(assistStep(carrot, AssistPhase.WAIT_SELL, orders).kind == AssistKind.WAIT)
    check(assistStep(carrot, AssistPhase.DONE, AssistScreen()).kind == AssistKind.DONE)
}

private fun checkAssistNeverGuesses() {
    val duplicated = container("Bazaar ➜ \"carrot\"", slot(11, "Enchanted Carrot"), slot(12, "Enchanted Carrot"))
    check(assistStep(carrot, AssistPhase.PLACE_BUY, duplicated).slot == null) { "Two matching slots must not produce a highlight" }
    check(assistStep(carrot, AssistPhase.PLACE_BUY, container("How many do you want?", slot(10, "Buy a stack!"))).slot == null)
    val twoOrders = container("Bazaar Orders", slot(10, "BUY Enchanted Carrot"), slot(11, "BUY Enchanted Carrot"))
    check(assistStep(carrot, AssistPhase.CLAIM_ITEMS, twoOrders).slot == null)
    check(assistSlotNamed(container("x", slot(1, "Custom Amount")), "Custom Amount ") == null) { "Names match exactly" }
    check(assistStep(carrot.copy(buyPrice = null), AssistPhase.PLACE_BUY, sign("", "", "Enter price", "big nerd"), AssistPriceMode.CUSTOM).kind == AssistKind.WAIT)
    check(assistStep(carrot.copy(buyPrice = Double.NaN), AssistPhase.PLACE_BUY, sign("", "", "Enter price", "big nerd"), AssistPriceMode.CUSTOM).value == null)
}

private fun checkAssistTextHelpers() {
    check(assistGhost("", "bz Enchanted Carrot") == "/bz Enchanted Carrot")
    check(assistGhost("/bz ench", "bz Enchanted Carrot") == "anted Carrot")
    check(assistGhost("/bz Enchanted Carrot", "bz Enchanted Carrot") == null)
    check(assistGhost("/warp hub", "bz Enchanted Carrot") == null) { "Other commands keep vanilla completion" }
    check(assistSignCheck("", "640") == AssistSignCheck.EMPTY)
    check(assistSignCheck("64", "640") == AssistSignCheck.PARTIAL)
    check(assistSignCheck(" 640 ", "640") == AssistSignCheck.MATCH)
    check(assistSignCheck("6400", "640") == AssistSignCheck.MISMATCH)
    check(assistPrice(152.0) == "152" && assistPrice(152.34) == "152.3" && assistPrice(0.0) == null && assistPrice(Double.POSITIVE_INFINITY) == null)
    check(parseAssistColor("#FFC857") == 0xFFC857 && parseAssistColor("ffc857") == 0xFFC857)
    check(parseAssistColor("#FFF") == null && parseAssistColor("red") == null && parseAssistColor("") == null)
}
