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
    checkAssistChatGuide()
    checkAssistPopsAndRuns()
    println("Bazaar Assistant checks passed: screen recognition, phases, step sequence, unambiguous highlights, ghost text, mismatch marking, sign checks, colors, pop merging, run summaries and panel placement")
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

private fun checkAssistChatGuide() {
    val command = "bz Enchanted Carrot"
    check(assistChatGuide("/", command) == AssistChatGuide("bz Enchanted Carrot"))
    check(assistChatGuide("/b", command) == AssistChatGuide("z Enchanted Carrot"))
    check(assistChatGuide("/ba", command) == null) { "/bank, /back and other commands keep vanilla completion" }
    check(assistChatGuide("/bzz", command) == null)
    check(assistChatGuide("/bz Enchanted Potato", command) == AssistChatGuide("", 14)) { "A wrong item after the right command is marked from where it diverges" }
    check(assistChatGuide("/BZ enchanted", command) == AssistChatGuide(" Carrot")) { "Case does not matter on Hypixel" }
    check(assistChatGuide("hello", command) == null && assistChatGuide("/bz Enchanted Carrot", command) == null)
    check(assistChatGuide("/b", "bz") == AssistChatGuide("z") && assistChatGuide("/bz ", "bz") == AssistChatGuide("", 3))
    check(assistStep(carrot, AssistPhase.PLACE_BUY, sign("", "", "Enter amount", "to order")).instruction.endsWith("click Done")) {
        "Enter moves to the next sign line; the instruction must say Done"
    }
}

private fun checkAssistPopsAndRuns() {
    val first = assistMergePop(emptyList(), AssistPopEvent(100.0, 1_000, 40, 50))
    val merged = assistMergePop(first, AssistPopEvent(50.0, 1_900, 40, 50))
    check(merged.size == 1 && merged.single().amount == 150.0) { "Portions claimed together pop once" }
    check(assistMergePop(merged, AssistPopEvent(10.0, 3_500, 40, 50)).size == 2) { "Later claims pop separately" }
    check(assistMergePop(merged, AssistPopEvent(10.0, 2_000, null, null)).size == 2) { "Different places never merge" }
    check(assistMergePop((1..10).map { AssistPopEvent(1.0, it * 5_000L) }, AssistPopEvent(1.0, 60_000)).size == 6)
    check(assistPopTone(-5.0) == AssistPopTone.LOSS && assistPopTone(99_999.0) == AssistPopTone.SMALL &&
        assistPopTone(100_000.0) == AssistPopTone.GAIN && assistPopTone(1_000_000.0) == AssistPopTone.BIG)

    fun realization(id: String, position: String, product: String, at: Long, profit: Double?) = BazaarRealization(id, product, position, at, 10,
        null, null, null, null, profit, true, BazaarEvidence.OBSERVED, null, null, "fp-$id")
    val rows = listOf(realization("a", "p1", "ENCHANTED_CARROT", 2_000, 120_000.0), realization("b", "p1", "ENCHANTED_CARROT", 3_000, 30_000.0),
        realization("c", "p2", "ENCHANTED_SUGAR", 4_000, 200_000.0), realization("d", "p3", "WHEAT", 5_000, 999_999.0),
        realization("e", "p1", "ENCHANTED_CARROT", 500, 7.0), realization("f", "p2", "ENCHANTED_SUGAR", 4_500, null))
    val run = checkNotNull(assistRunSummary(rows, setOf("p1", "p2"), 1_000, 1_000 + 30 * 60_000))
    check(run.profit == 350_000.0 && run.sales == 3) { "Only guided positions, after the run started, with known profit count: $run" }
    check(run.bestProduct == "ENCHANTED_SUGAR" && run.bestProfit == 200_000.0)
    check(run.perHour != null && kotlin.math.abs(run.perHour!! - 700_000.0) < 1e-6)
    check(assistRunSummary(rows, setOf("p1"), 1_000, 1_000 + 60_000)?.perHour == null) { "Rates wait for five minutes" }
    check(assistRunSummary(rows, setOf("p9"), 0, 10) == null)

    check(assistBesideMenu(480, 152, 176, 120) == 334) { "Right of the menu when it fits" }
    check(assistBesideMenu(400, 200, 176, 120) == 74) { "Left of the menu otherwise" }
    check(assistBesideMenu(300, 62, 176, 120) == null) { "The saved position when neither side fits" }
    check(assistFitBesideMenu(640, 232, 176, 200, 1f) == AssistPlacement(414, 1f)) { "Full size when it fits" }
    val fitted = checkNotNull(assistFitBesideMenu(426, 125, 176, 250, 1f, minScale = 1f / 3)) { "A narrow screen shrinks the panel instead of covering the menu" }
    check(fitted.x == 307 && fitted.scale < 1f && 307 + kotlin.math.ceil(250 * fitted.scale) <= 424)
    check(assistFitBesideMenu(320, 72, 176, 250, 1f) == null) { "Below half size the saved position is used" }
    val left = checkNotNull(assistFitBesideMenu(500, 300, 176, 200, 1f))
    check(left.x + 200 * left.scale <= 294.01) { "The left side is used when it has more room: $left" }

    check(assistWrap("§7short") == listOf("§7short"))
    val wrapped = assistWrap("§7Needs a Booster Cookie; otherwise visit the Bazaar NPC in the Hub")
    check(wrapped.size == 2 && wrapped.all { it.startsWith("§7") && it.length <= 42 } && wrapped[1].startsWith("§7  ")) { wrapped.toString() }
    check(assistDuration(5_000) == "under a minute" && assistDuration(125_000) == "2 min" && assistDuration(3_900_000) == "1 h 5 min")
}
