package me.mycellium.skymyce.features.bazaar.assist

import me.mycellium.skymyce.features.bazaar.BazaarOrder
import me.mycellium.skymyce.features.bazaar.BazaarOrderStatus
import me.mycellium.skymyce.features.bazaar.BazaarPosition
import me.mycellium.skymyce.features.bazaar.BazaarRealization
import me.mycellium.skymyce.features.bazaar.BazaarStage
import java.util.Locale

/** One container slot as the player sees it: stripped name and, when known, its Bazaar product. */
data class AssistSlot(val index: Int, val name: String, val product: String? = null)

enum class AssistScreenKind { NONE, CONTAINER, SIGN, OTHER }

/** A read-only snapshot of the open screen. [id] changes whenever a different screen opens. */
data class AssistScreen(
    val kind: AssistScreenKind = AssistScreenKind.NONE,
    val title: String = "",
    val slots: List<AssistSlot> = emptyList(),
    val signLines: List<String> = emptyList(),
    val id: Int = 0,
)

/** Bazaar screens the assistant recognises, by title, sign wording and the buttons they contain. */
enum class AssistView {
    NONE, OTHER, MAIN, SEARCH_RESULTS, PRODUCT, BUY_AMOUNT, BUY_PRICE, BUY_CONFIRM, SELL_AMOUNT, SELL_PRICE, SELL_CONFIRM,
    ORDERS, ORDER_OPTIONS, SIGN_SEARCH, SIGN_AMOUNT, SIGN_PRICE, SIGN_OTHER,
}

/** Where a guided flip stands, derived from the existing order tracking. */
enum class AssistPhase(val label: String) {
    PLACE_BUY("Place buy order"), WAIT_BUY("Buy order filling"), CLAIM_ITEMS("Claim items"),
    PLACE_SELL("Place sell offer"), WAIT_SELL("Sell offer filling"), CLAIM_COINS("Claim coins"), DONE("Flip complete"),
}

enum class AssistKind { COMMAND, CLICK, SIGN, CLOSE, WAIT, DONE }
enum class AssistSign { SEARCH, AMOUNT, PRICE }
enum class AssistPriceMode { MATCH_ORDER_BOOK, CUSTOM }

/** The flip being guided: the product, how many, and the prices the recommendation suggested. */
data class AssistMission(val product: String, val name: String, val quantity: Long, val buyPrice: Double?, val sellPrice: Double?)

/**
 * The single next action. A CLICK carries the live [slot] index only when exactly one slot matched; otherwise
 * [slot] is null and the instruction alone guides the player.
 */
data class AssistStep(
    val kind: AssistKind,
    val label: String,
    val instruction: String,
    val hint: String = "",
    val command: String? = null,
    val slot: Int? = null,
    val target: String? = null,
    val sign: AssistSign? = null,
    val value: String? = null,
)

private const val BAZAAR_TITLE = "Bazaar ➜ "

fun assistView(screen: AssistScreen): AssistView = when (screen.kind) {
    AssistScreenKind.NONE -> AssistView.NONE
    AssistScreenKind.OTHER -> AssistView.OTHER
    AssistScreenKind.SIGN -> {
        val lines = screen.signLines.map { it.trim() }
        when {
            lines.getOrNull(3).equals("Enter query", true) -> AssistView.SIGN_SEARCH
            lines.getOrNull(2).equals("Enter amount", true) -> AssistView.SIGN_AMOUNT
            lines.getOrNull(2).equals("Enter price", true) -> AssistView.SIGN_PRICE
            else -> AssistView.SIGN_OTHER
        }
    }
    AssistScreenKind.CONTAINER -> {
        val title = screen.title.trim()
        val names = screen.slots.mapTo(hashSetOf()) { it.name }
        when {
            title == "How many do you want?" -> AssistView.BUY_AMOUNT
            title == "How much do you want to pay?" -> AssistView.BUY_PRICE
            title == "Confirm Buy Order" -> AssistView.BUY_CONFIRM
            title == "How many are you selling?" -> AssistView.SELL_AMOUNT
            title == "At what price are you selling?" -> AssistView.SELL_PRICE
            title == "Confirm Sell Offer" -> AssistView.SELL_CONFIRM
            me.mycellium.skymyce.features.bazaar.bazaarOrdersMenu(title) -> AssistView.ORDERS
            title == "Order options" -> AssistView.ORDER_OPTIONS
            title.startsWith("$BAZAAR_TITLE\"") -> AssistView.SEARCH_RESULTS
            "Create Buy Order" in names || "Create Sell Offer" in names || "View Graphs" in names -> AssistView.PRODUCT
            title.startsWith(BAZAAR_TITLE) -> AssistView.MAIN
            else -> AssistView.OTHER
        }
    }
}

/**
 * Phase from the guided position's tracked stage. A partially filled buy order keeps waiting; filled items and
 * unclaimed sale coins become claim steps.
 */
fun assistPhase(position: BazaarPosition?, orders: List<BazaarOrder>): AssistPhase {
    if (position == null) return AssistPhase.PLACE_BUY
    val own = orders.filter { it.positionId == position.id }
    val unclaimedSales = own.filter { !it.buy }.sumOf { (it.filled - it.collected).coerceAtLeast(0) }
    val listing = own.any { !it.buy && it.status == BazaarOrderStatus.OPEN }
    return when {
        position.stage == BazaarStage.BUYING -> AssistPhase.WAIT_BUY
        position.pendingItems > 0 -> AssistPhase.CLAIM_ITEMS
        position.stage == BazaarStage.READY -> AssistPhase.PLACE_SELL
        unclaimedSales > 0 && !listing -> AssistPhase.CLAIM_COINS
        position.stage == BazaarStage.SELLING -> AssistPhase.WAIT_SELL
        unclaimedSales > 0 -> AssistPhase.CLAIM_COINS
        position.stage == BazaarStage.CLOSED -> AssistPhase.DONE
        else -> AssistPhase.WAIT_BUY
    }
}

/** A slot matched by exact visible name; ambiguity yields no target rather than a guess. */
fun assistSlotNamed(screen: AssistScreen, name: String): Int? =
    screen.slots.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()?.index

/** The product's own slot in search results: its Bazaar ID when known, else its exact display name. */
fun assistProductSlot(screen: AssistScreen, mission: AssistMission): Int? {
    val byId = screen.slots.filter { it.product?.equals(mission.product, true) == true }
    if (byId.isNotEmpty()) return byId.singleOrNull()?.index
    return assistSlotNamed(screen, mission.name)
}

/** An order in Bazaar Orders, shown as "BUY Name" or "SELL Name". */
fun assistOrderSlot(screen: AssistScreen, mission: AssistMission, buy: Boolean): Int? {
    val prefix = if (buy) listOf("BUY ", "Buy Order: ", "Buy Order ") else listOf("SELL ", "Sell Offer: ", "Sell Offer ")
    return screen.slots.filter { slot ->
        prefix.any { slot.name.startsWith(it, true) && slot.name.substring(it.length).trim().equals(mission.name, true) } ||
            slot.product?.equals(mission.product, true) == true && prefix.any { slot.name.startsWith(it, true) }
    }.singleOrNull()?.index
}

/** Product pages put the item in the title, which Hypixel truncates; any matching prefix counts. */
private fun productPageMatches(screen: AssistScreen, mission: AssistMission): Boolean? {
    val title = screen.title.substringAfterLast("➜").trim()
    if (title.isEmpty()) return null
    val name = mission.name
    return name.equals(title, true) || title.length >= 6 && name.startsWith(title.removeSuffix("...").removeSuffix("…"), true)
}

fun assistBazaarCommand(name: String) = "bz $name"

/** Bazaar prices step in tenths of a coin. */
fun assistPrice(value: Double?): String? = value?.takeIf { it.isFinite() && it > 0 && it < 1e12 }
    ?.let { String.format(Locale.ROOT, "%.1f", Math.round(it * 10) / 10.0).removeSuffix(".0") }

/** The next action for [mission] in [phase], given exactly what is on screen now. */
fun assistStep(mission: AssistMission, phase: AssistPhase, screen: AssistScreen, priceMode: AssistPriceMode = AssistPriceMode.MATCH_ORDER_BOOK,
               remaining: Long = mission.quantity): AssistStep {
    val view = assistView(screen)
    val open = AssistStep(AssistKind.COMMAND, "Open the Bazaar", "Run /${assistBazaarCommand(mission.name)}",
        "Needs a Booster Cookie; otherwise visit the Bazaar NPC in the Hub", command = assistBazaarCommand(mission.name))
    val closeFirst = AssistStep(AssistKind.CLOSE, "Close this menu", "Press Esc, then run /${assistBazaarCommand(mission.name)}")
    fun click(label: String, name: String, hint: String = "") = AssistStep(AssistKind.CLICK, label, "Click $name", hint,
        slot = assistSlotNamed(screen, name), target = name)
    fun searchResults() = AssistStep(AssistKind.CLICK, "Choose the item", "Click ${mission.name}", slot = assistProductSlot(screen, mission), target = mission.name)
    fun wrongProduct() = AssistStep(AssistKind.CLOSE, "Different item open", "Go back and search for ${mission.name}", command = assistBazaarCommand(mission.name))
    fun sign(kind: AssistSign, value: String?, label: String, what: String) = if (value == null)
        AssistStep(AssistKind.WAIT, label, "The suggested $what is unavailable; enter your own")
    else AssistStep(AssistKind.SIGN, label, "Type $value on the first line, then click Done", sign = kind, value = value)
    fun waitOrders(label: String, text: String) = AssistStep(AssistKind.WAIT, label, text)
    fun claim(buy: Boolean): AssistStep {
        val what = if (buy) "items" else "coins"
        return when (view) {
            AssistView.ORDERS -> AssistStep(AssistKind.CLICK, "Claim your $what", "Click your ${if (buy) "BUY" else "SELL"} ${mission.name} order",
                slot = assistOrderSlot(screen, mission, buy), target = "${if (buy) "BUY" else "SELL"} ${mission.name}")
            AssistView.MAIN, AssistView.SEARCH_RESULTS, AssistView.PRODUCT -> click("Open your orders", "Manage Orders")
            AssistView.NONE -> AssistStep(AssistKind.COMMAND, "Open your orders", "Run /managebazaarorders", command = "managebazaarorders")
            AssistView.ORDER_OPTIONS -> AssistStep(AssistKind.WAIT, "Claim your $what", "Choose the claim option in this menu")
            else -> AssistStep(AssistKind.CLOSE, "Close this menu", "Press Esc, then run /managebazaarorders", command = "managebazaarorders")
        }
    }
    return when (phase) {
        AssistPhase.PLACE_BUY -> when (view) {
            AssistView.NONE -> open
            AssistView.SIGN_SEARCH -> sign(AssistSign.SEARCH, mission.name, "Search for the item", "name")
            AssistView.SEARCH_RESULTS -> searchResults()
            AssistView.PRODUCT -> if (productPageMatches(screen, mission) == false) wrongProduct() else click("Start a buy order", "Create Buy Order")
            AssistView.BUY_AMOUNT -> click("Choose the amount", "Custom Amount", "You'll enter ${mission.quantity} next")
            AssistView.SIGN_AMOUNT -> sign(AssistSign.AMOUNT, mission.quantity.takeIf { it > 0 }?.toString(), "Enter the amount", "amount")
            AssistView.BUY_PRICE -> if (priceMode == AssistPriceMode.CUSTOM) click("Choose the price", "Custom Price", "You'll enter ${assistPrice(mission.buyPrice) ?: "a price"} next")
                else click("Choose the price", "Top Order +0.1", "Outbids the current best buy order by 0.1 coins")
            AssistView.SIGN_PRICE -> sign(AssistSign.PRICE, assistPrice(mission.buyPrice), "Enter the price", "price")
            AssistView.BUY_CONFIRM -> click("Confirm the buy order", "Buy Order", "Check the amount and price first")
            AssistView.MAIN -> AssistStep(AssistKind.CLOSE, "Search for the item", "Close this menu, then run /${assistBazaarCommand(mission.name)}", command = assistBazaarCommand(mission.name))
            else -> closeFirst
        }
        AssistPhase.WAIT_BUY -> waitOrders("Buy order placed", "Waiting for your buy order to fill. You can keep playing")
        AssistPhase.CLAIM_ITEMS -> claim(buy = true)
        AssistPhase.PLACE_SELL -> when (view) {
            AssistView.NONE -> open
            AssistView.SIGN_SEARCH -> sign(AssistSign.SEARCH, mission.name, "Search for the item", "name")
            AssistView.SEARCH_RESULTS -> searchResults()
            AssistView.PRODUCT -> if (productPageMatches(screen, mission) == false) wrongProduct() else click("Start a sell offer", "Create Sell Offer")
            AssistView.SELL_AMOUNT -> click("Choose the amount", "Custom Amount", "You'll enter $remaining next")
            AssistView.SIGN_AMOUNT -> sign(AssistSign.AMOUNT, remaining.takeIf { it > 0 }?.toString(), "Enter the amount", "amount")
            AssistView.SELL_PRICE -> if (priceMode == AssistPriceMode.CUSTOM) click("Choose the price", "Custom Price", "You'll enter ${assistPrice(mission.sellPrice) ?: "a price"} next")
                else click("Choose the price", "Best Offer -0.1", "Undercuts the current best sell offer by 0.1 coins")
            AssistView.SIGN_PRICE -> sign(AssistSign.PRICE, assistPrice(mission.sellPrice), "Enter the price", "price")
            AssistView.SELL_CONFIRM -> click("Confirm the sell offer", "Sell Offer", "Check the amount and price first")
            AssistView.MAIN -> AssistStep(AssistKind.CLOSE, "Search for the item", "Close this menu, then run /${assistBazaarCommand(mission.name)}", command = assistBazaarCommand(mission.name))
            else -> closeFirst
        }
        AssistPhase.WAIT_SELL -> waitOrders("Sell offer listed", "Waiting for buyers. You can keep playing")
        AssistPhase.CLAIM_COINS -> claim(buy = false)
        AssistPhase.DONE -> AssistStep(AssistKind.DONE, "Flip complete", "Start the next flip whenever you're ready")
    }
}

/**
 * Chat guidance for the expected command. The box belongs to the assistant only while it is empty, just "/", or its
 * first word is still a prefix of the command's; any other command keeps vanilla completion untouched.
 * [mismatchAt] is where the typed text first diverges, so it can be marked; [ghost] is the untyped rest otherwise.
 */
data class AssistChatGuide(val ghost: String, val mismatchAt: Int? = null)

fun assistChatGuide(typed: String, command: String): AssistChatGuide? {
    val full = "/$command"
    if (typed.isEmpty()) return AssistChatGuide(full)
    if (!typed.startsWith("/")) return null
    val typedWord = typed.drop(1).substringBefore(' ')
    val commandWord = command.substringBefore(' ')
    val ownsWord = if (' ' in typed.drop(1)) typedWord.equals(commandWord, true) else commandWord.startsWith(typedWord, true)
    if (!ownsWord) return null
    val common = typed.zip(full).takeWhile { (a, b) -> a.equals(b, true) }.size
    return when {
        common < typed.length -> AssistChatGuide("", common)
        typed.length < full.length -> AssistChatGuide(full.substring(typed.length))
        else -> null
    }
}

/** The untyped rest of "/command", or null when [typed] is not on track. */
fun assistGhost(typed: String, command: String): String? = assistChatGuide(typed, command)?.takeIf { it.mismatchAt == null }?.ghost

/** Sign feedback: what has been typed against what the step expects. */
enum class AssistSignCheck { EMPTY, PARTIAL, MATCH, MISMATCH }

fun assistSignCheck(typed: String, expected: String): AssistSignCheck {
    val value = typed.trim()
    return when {
        value.isEmpty() -> AssistSignCheck.EMPTY
        value.equals(expected, ignoreCase = true) -> AssistSignCheck.MATCH
        expected.startsWith(value, ignoreCase = true) -> AssistSignCheck.PARTIAL
        else -> AssistSignCheck.MISMATCH
    }
}

/** A profit pop: where it rises from (a clicked slot in screen coordinates, or the panel) and how much. */
data class AssistPopEvent(val amount: Double, val at: Long, val x: Int? = null, val y: Int? = null)

/** Pops at the same place within 1.2 s merge, so one claim of several sale portions reads as one number. */
fun assistMergePop(pops: List<AssistPopEvent>, next: AssistPopEvent, limit: Int = 6): List<AssistPopEvent> {
    val last = pops.lastOrNull()
    val merged = if (last != null && next.at - last.at in 0..1200 && last.x == next.x && last.y == next.y)
        pops.dropLast(1) + last.copy(amount = last.amount + next.amount, at = next.at) else pops + next
    return merged.takeLast(limit)
}

/** Losses red, small gains muted, larger gains bright; one million or more is drawn larger. */
enum class AssistPopTone { LOSS, SMALL, GAIN, BIG }
fun assistPopTone(amount: Double): AssistPopTone = when {
    amount < 0 -> AssistPopTone.LOSS
    amount < 100_000 -> AssistPopTone.SMALL
    amount < 1_000_000 -> AssistPopTone.GAIN
    else -> AssistPopTone.BIG
}

/** One assistant run, summarised from the existing realized records of the positions it guided. */
data class AssistRunSummary(val profit: Double, val sales: Int, val millis: Long, val perHour: Double?, val bestProduct: String?, val bestProfit: Double?)

fun assistRunSummary(realizations: List<BazaarRealization>, positions: Set<String>, startedAt: Long, now: Long): AssistRunSummary? {
    val own = realizations.filter { it.positionId in positions && it.netProfit != null && it.at >= startedAt }
    if (own.isEmpty()) return null
    val profit = own.sumOf { it.netProfit ?: 0.0 }
    val millis = (now - startedAt).coerceAtLeast(0)
    val best = own.groupBy { it.product }.mapValues { (_, rows) -> rows.sumOf { it.netProfit ?: 0.0 } }.maxByOrNull { it.value }
    return AssistRunSummary(profit, own.size, millis, (profit / (millis / 3_600_000.0)).takeIf { millis >= 5 * 60_000 }, best?.key, best?.value)
}

/** Beside the open menu: right of it when the panel fits, else left, else the saved position (null). */
fun assistBesideMenu(screenWidth: Int, menuLeft: Int, menuWidth: Int, panelWidth: Int, gap: Int = 6): Int? = when {
    menuLeft + menuWidth + gap + panelWidth <= screenWidth - 2 -> menuLeft + menuWidth + gap
    menuLeft - gap - panelWidth >= 2 -> menuLeft - gap - panelWidth
    else -> null
}

/** Where and how large the panel sits beside a menu: shrunk (never below [minScale]) rather than covering the menu's slots. */
data class AssistPlacement(val x: Int, val scale: Float)

fun assistFitBesideMenu(screenWidth: Int, menuLeft: Int, menuWidth: Int, naturalWidth: Int, preferredScale: Float, minScale: Float = .5f,
                        gap: Int = 6): AssistPlacement? {
    if (naturalWidth <= 0) return null
    val right = screenWidth - 2 - (menuLeft + menuWidth + gap)
    val left = menuLeft - gap - 2
    val room = maxOf(right, left)
    val scale = minOf(preferredScale, room.toFloat() / naturalWidth)
    if (scale < minScale) return null
    val width = kotlin.math.ceil(naturalWidth * scale).toInt()
    return AssistPlacement(if (right >= left) menuLeft + menuWidth + gap else menuLeft - gap - width, scale)
}

private val formatting = Regex("§.")

/** Wrap a §-formatted line at word boundaries, carrying its colour onto indented continuation lines. */
fun assistWrap(line: String, width: Int = 38): List<String> {
    val color = Regex("^(§.)+").find(line)?.value.orEmpty()
    val body = line.removePrefix(color)
    if (body.replace(formatting, "").length <= width) return listOf(line)
    val out = mutableListOf<String>()
    var current = ""
    for (word in body.split(' ')) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (candidate.replace(formatting, "").length > width && current.isNotEmpty()) { out += current; current = word } else current = candidate
    }
    if (current.isNotEmpty()) out += current
    return out.mapIndexed { index, text -> if (index == 0) color + text else "$color  $text" }
}

fun assistDuration(millis: Long): String = when {
    millis < 60_000 -> "under a minute"
    millis < 3_600_000 -> "${millis / 60_000} min"
    else -> "${millis / 3_600_000} h ${millis % 3_600_000 / 60_000} min"
}
