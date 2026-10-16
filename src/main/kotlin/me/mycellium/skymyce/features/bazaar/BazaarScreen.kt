package me.mycellium.skymyce.features.bazaar

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Assist
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.*
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/**
 * The Economy → Bazaar terminal. It shows the shared [BazaarUiState] and sends actions through the bridge; none
 * of them can place, change, cancel or claim an order.
 */
class BazaarScreen : MenuScreen(Component.literal("Bazaar")) {
    override val menuCategory get() = MenuCategory.ECONOMY
    internal override val canonicalFeatureId get() = "bazaar-flipping"

    private enum class Tab(val title: String) { FLIPS("Flips"), POSITIONS("Positions"), HISTORY("History"), REPLAYS("Replays"), MORE("More") }

    private var tab = Tab.FLIPS
    private var query = ""
    private var page = 0
    private var expanded: String? = null
    private var state = BazaarUiState()
    private var seenRevision = -1L
    private var renderedSignature: Any? = null
    private var renderedGuidance: String? = null
    private var bodyDirty = false
    private var submitting = false
    private var lastSecond = 0L
    private val publicationChoices = mutableMapOf<String, LinkedHashMap<String, Boolean>>()
    private val manualDrafts = mutableMapOf<String, MutableMap<String, String>>()
    private val manualAdvanced = mutableSetOf<String>()
    private val reviewDetails = mutableSetOf<String>()
    private val repairKinds = linkedMapOf("BUY_FILL" to "Bought items", "SELL_FILL" to "Sold items", "BUY_ORDER" to "Buy order", "SELL_ORDER" to "Sell offer")
    private lateinit var body: FlowLayout
    private lateinit var results: MenuScroll<FlowLayout>
    private lateinit var toolbar: FlowLayout
    private lateinit var statusLabel: MenuLabel
    private lateinit var marketLabel: MenuLabel
    private lateinit var guidanceHost: FlowLayout
    private lateinit var sessionButton: MenuButton
    private lateinit var positionsTab: MenuButton
    private lateinit var kpiValues: List<MenuLabel>
    private val innerWidth get() = (menuWidth - 28).coerceAtLeast(1)
    private val narrow get() = innerWidth < 470

    // ---- Shell ----

    override fun buildContent(root: FlowLayout) {
        state = BazaarUi.bridge?.snapshot() ?: BazaarUiState()
        seenRevision = state.revision
        renderedSignature = null
        root.padding(Insets.of(10, 10, 14, 14)); root.gap(6)
        root.child(row().apply {
            child(label("BAZAAR", MenuTheme.ACCENT, 15f).apply { horizontalSizing(Sizing.expand()) })
            sessionButton = button(sessionTitle(), 112) { submit(BazaarAction.TOGGLE) }.apply { id("bazaar-toggle") }
            child(sessionButton)
            child(button("More", 52) { anchor -> moreMenu(anchor) }.apply { id("bazaar-more") })
        })
        statusLabel = label(state.status, MenuTheme.TEXT, 10f); root.child(statusLabel)
        marketLabel = label(marketLine(), MenuTheme.MUTED, 8.5f); root.child(marketLabel)
        guidanceHost = column(); root.child(guidanceHost)
        root.child(kpis())
        root.child(tabs())
        toolbar = column(); root.child(toolbar)
        body = column().apply { gap(6); padding(Insets.of(2, 6, 0, 4)) }
        results = menuScroll(Sizing.fill(), Sizing.expand(), body).apply { id("bazaar-results"); scrollStep(30); scrollbarThiccness(2) }
        root.child(results)
        root.child(label("Suggestions only · you place, change and claim every order yourself", MenuTheme.MUTED, 8f))
        updateChrome()
        renderToolbar()
        render(force = true)
    }

    override fun tick() {
        super.tick()
        if (!::body.isInitialized || menuClosing) return
        val second = System.currentTimeMillis() / 1000
        if (second != lastSecond) { lastSecond = second; marketLabel.text(Component.literal(marketLine())) }
        val next = BazaarUi.bridge?.snapshot() ?: return
        if (next.revision == seenRevision) {
            if (bodyDirty && !editing()) render()
            return
        }
        state = next; seenRevision = next.revision
        updateChrome()
        if (editing()) bodyDirty = true else render()
    }

    private fun editing(): Boolean {
        val focused = uiAdapter.rootComponent.focusHandler()?.focused()
        return focused is MenuTextBox || generateSequence(focused?.parent()) { it.parent() }.any { it is MenuDropdown }
    }

    private fun sessionTitle() = if (state.active) "Stop flipping" else "Start flipping"

    private fun updateChrome() {
        sessionButton.message = Component.literal(sessionTitle())
        sessionButton.intent = if (state.active) MenuButtonIntent.SECONDARY else MenuButtonIntent.PRIMARY
        statusLabel.text(Component.literal(state.status))
        marketLabel.text(Component.literal(marketLine()))
        positionsTab.message = Component.literal(positionsTitle())
        val stats = state.stats
        val values = listOf(bazaarCompactCoins(state.purse), bazaarCompactCoins(state.committed), bazaarCompactCoins(state.inventoryCost),
            bazaarSignedCoins(stats?.realizedProfit))
        kpiValues.zip(values).forEach { (label, value) -> label.text(Component.literal(value)) }
        kpiValues[3].color(Color.ofRgb(if ((stats?.realizedProfit ?: 0.0) < 0) MenuTheme.RED else MenuTheme.GREEN))
        if (state.guidance == renderedGuidance && guidanceHost.children().isNotEmpty() == (state.guidance != null)) return
        renderedGuidance = state.guidance
        guidanceHost.clearChildren()
        state.guidance?.let { text ->
            guidanceHost.child(column().apply {
                padding(Insets.of(6, 6, 8, 8)); surface(MenuTheme.tintedPanel { MenuTheme.alpha(MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 22), 92) })
                child(label("NEXT STEP", MenuTheme.ACCENT, 8f))
                child(label(text, MenuTheme.TEXT, 10f).apply { id("bazaar-guidance") })
            })
        }
    }

    private fun marketLine(): String {
        val observed = state.observedAt ?: return state.marketStatus
        return "Market ${state.freshness.label.lowercase()} · updated ${bazaarAge(observed)} · ${state.marketStatus}"
    }

    private fun positionsTitle(): String {
        val attention = state.pending.size + state.openPositions.count { state.nextSteps[it.id]?.actionable == true }
        return if (attention > 0) "Positions • $attention" else "Positions"
    }

    private fun kpis(): FlowLayout {
        val tiles = listOf(
            Triple("PURSE", "PURSE", "Coins you can spend now. Buy orders already took their coins out of it; bank coins and items are not counted."),
            Triple("IN BUY ORDERS", "ORDERS", "Coins reserved by your open buy orders."),
            Triple("INVENTORY COST", "ITEMS", "What you paid for items you still hold. Estimated sale value is shown under Positions."),
            Triple("REALIZED PROFIT", "PROFIT", "Net coins from completed sales after tax, minus what those items cost. Never includes unsold items or forecasts."),
        )
        val width = (innerWidth - 3 * 6) / 4
        val values = mutableListOf<MenuLabel>()
        val strip = row().apply {
            gap(6)
            tiles.forEach { (title, short, help) ->
                val value = label("—", MenuTheme.TEXT, if (narrow) 11f else 13f).also(values::add)
                child(UIContainers.verticalFlow(Sizing.fixed(width), Sizing.content()).apply {
                    padding(if (narrow) Insets.of(4, 4, 6, 6) else Insets.of(6, 6, 8, 8)); gap(2); surface(MenuTheme.panel(true))
                    child(label(if (narrow) short else title, MenuTheme.MUTED, 7.5f)); child(value)
                }.wrappedTooltip(Component.literal(help)))
            }
        }
        kpiValues = values
        return strip
    }

    private fun tabs(): FlowLayout {
        val buttons = Tab.entries.map { entry ->
            button(if (entry == Tab.POSITIONS) positionsTitle() else entry.title, if (entry == Tab.POSITIONS) (if (narrow) 80 else 92) else if (narrow) 58 else 70) {
                if (tab == entry) return@button
                tab = entry; page = 0; expanded = null; query = ""
                renderToolbar(); render(force = true, scrollTop = true)
            }.apply {
                id("bazaar-tab-${entry.name.lowercase()}"); navigation = true; centeredNavigation = true; selected = { tab == entry }
                if (entry == Tab.POSITIONS) positionsTab = this
            }
        }
        return actions(buttons)
    }

    private fun moreMenu(anchor: MenuButton) {
        openMenuDropdown(anchor) { menu ->
            menu.button(Component.literal("Refresh market data")) { submit(BazaarAction.REFRESH) }
            menu.button(Component.literal("Sync orders (Manage Orders)")) { submit(BazaarAction.OPEN_ORDERS) }
            menu.button(Component.literal(if (state.dashboardRunning) "Close browser dashboard" else "Open browser dashboard")) { toggleDashboard() }
            menu.button(Component.literal("All Bazaar settings")) { openSettings() }
            menu.button(Component.literal("Move the HUD")) { openMenu(MenuPage.HUD) }
            menu.button(Component.literal("Leaderboards")) { submit(BazaarAction.LEADERBOARD) }
        }
    }

    private fun openSettings() = navigateMenu(SettingsScreen.forFeature(this, "bazaar-flipping"))

    private fun toggleDashboard(session: String? = null) {
        if (BazaarDashboard.running() && session == null) { BazaarDashboard.stop(); feedback("Browser dashboard closed"); return }
        val bridge = BazaarUi.bridge ?: return
        runCatching { BazaarDashboard.open(bridge, session) }.onSuccess { feedback(if (session == null) "Dashboard opened in your browser" else "Replay opened in your browser") }
            .onFailure { feedback("Your browser could not be opened", MenuToastKind.ERROR) }
    }

    // ---- Body ----

    private fun renderToolbar() {
        if (!::toolbar.isInitialized) return
        toolbar.clearChildren()
        val search = { hint: String ->
            MenuTextBox(Sizing.expand()).apply {
                id("bazaar-search"); setMaxLength(80); text(query); setHint(Component.literal(hint))
                onChanged().subscribe { query = it; page = 0; render(force = true) }
            }
        }
        when (tab) {
            Tab.FLIPS -> toolbar.child(row().apply {
                listOf(BazaarRisk.CONSERVATIVE to "Steady", BazaarRisk.BALANCED to "Balanced", BazaarRisk.AGGRESSIVE to "Bold").forEach { (risk, title) ->
                    child(button(title, 64) { submit(BazaarAction.PREFERENCE, mapOf("key" to "risk", "value" to risk.name)) }.apply {
                        id("bazaar-risk-${risk.name.lowercase()}"); selected = { state.settings["risk"] == risk.name }
                    }.wrappedTooltip(Component.literal(riskHelp(risk))))
                }
                child(search("Search items…"))
            })
            Tab.POSITIONS -> toolbar.child(row().apply {
                child(button("Sync orders", 84) { submit(BazaarAction.OPEN_ORDERS) }.apply { id("bazaar-open-orders") }
                    .wrappedTooltip(Component.literal("Opens Manage Orders so Sky-Myce can read your current fills")))
                child(search("Search positions…"))
            })
            Tab.HISTORY -> toolbar.child(row().apply { child(search("Search sales…")) })
            else -> Unit
        }
    }

    private fun riskHelp(risk: BazaarRisk) = when (risk) {
        BazaarRisk.CONSERVATIVE -> "Favours liquid, stable items that fill quickly"
        BazaarRisk.BALANCED -> "Weighs profit against fill time, liquidity and uncertainty"
        BazaarRisk.AGGRESSIVE -> "Accepts slower fills and more volatility for bigger returns"
    }

    /** Rebuild the body only when the data this tab shows changed, so routine updates never flicker. */
    private fun render(force: Boolean = false, scrollTop: Boolean = false) {
        if (!::body.isInitialized) return
        val signature = listOf(tab, query, page, expanded, signature())
        if (!force && signature == renderedSignature) { bodyDirty = false; return }
        renderedSignature = signature; bodyDirty = false
        val offset = if (scrollTop) 0.0 else results.scrollTarget
        body.clearChildren()
        when (tab) {
            Tab.FLIPS -> flips()
            Tab.POSITIONS -> positions()
            Tab.HISTORY -> history()
            Tab.REPLAYS -> replays()
            Tab.MORE -> more()
        }
        results.scrollBy(offset - results.scrollTarget, true, false)
    }

    private fun signature(): Any = when (tab) {
        Tab.FLIPS -> listOf(state.opportunities, state.priceHistory, state.settings["risk"], state.freshness, state.active, state.wizardDone, state.publicSharing,
            state.settings["inGameAlerts"], state.settings["discordAlerts"], state.purse == null)
        Tab.POSITIONS -> listOf(state.positions, state.orders, state.nextSteps, state.pending, state.awaiting, state.relistAdvice, state.portfolioWarnings,
            state.purse, state.committed, state.inventoryCost, state.inventoryValue, state.pendingCollection)
        Tab.HISTORY -> listOf(state.history, state.stats, state.connectionStatus)
        Tab.REPLAYS -> listOf(state.sessions, state.publications, state.settings["webhookEnabled"], state.settings["webhookConfigured"])
        Tab.MORE -> listOf(state.settings, state.connectionStatus, state.dashboardRunning, state.publicSharing)
    }

    private fun flips() {
        if (!state.wizardDone) body.child(setupCard())
        when {
            !state.active -> body.child(emptyCard("Start flipping to scan the Bazaar",
                "Sky-Myce ranks every Bazaar item by estimated profit after tax, how fast it trades and how much of your purse it needs. " +
                    "Nothing is ever bought or sold for you.", "Start flipping") { submit(BazaarAction.TOGGLE) })
            !state.freshness.scannable -> body.child(emptyCard("Waiting for live market data", state.marketStatus))
            state.purse == null -> body.child(emptyCard("Your purse isn't visible",
                "Open the SkyBlock scoreboard so your purse shows. Suggestions are sized to the coins you can actually spend."))
            state.opportunities.isEmpty() -> body.child(emptyCard("No flips match your filters",
                "Try another risk profile, or lower the minimum profit or volume in settings.", "Open settings") { openSettings() })
        }
        val entries = state.opportunities.filter { matches(state.name(it.product), it.product) }
        paginate(entries) { opportunityCard(it) }
    }

    private fun opportunityCard(item: BazaarOpportunity) = card().apply {
        id("bazaar-opportunity-${item.product}")
        child(row().apply {
            icon(item.product)?.let(::child)
            child(column().apply {
                horizontalSizing(Sizing.expand()); gap(2)
                child(label(state.name(item.product), MenuTheme.TEXT, 11f))
                child(label("Buy ${bazaarCoins(item.buyPrice)} → sell ${bazaarCoins(item.sellPrice)} · ×${item.quantity}", MenuTheme.MUTED, 8.5f))
            })
            child(column().apply {
                horizontalSizing(Sizing.content()); gap(2); horizontalAlignment(HorizontalAlignment.RIGHT)
                child(MenuLabel(Component.literal(bazaarSignedCoins(item.netProfit))).apply { fontSize = 12f; color(Color.ofRgb(if (item.netProfit >= 0) MenuTheme.GREEN else MenuTheme.RED)) })
                child(MenuLabel(Component.literal("${bazaarPercent(item.roi)} return")).apply { fontSize = 8.5f; color(Color.ofRgb(MenuTheme.MUTED)) })
            })
        })
        child(label("${bazaarCompactCoins(item.capital)} needed · fills in ~${bazaarDuration(item.fillSecondsLow)}–${bazaarDuration(item.fillSecondsHigh)} · " +
            "${(item.confidence * 100).toInt()}% confidence", MenuTheme.MUTED, 8.5f))
        val open = expanded == item.product
        child(actions(listOf(
            button("Guide this flip", 98) { submit(BazaarAction.GUIDE, mapOf("product" to item.product)) }.apply { intent = MenuButtonIntent.PRIMARY }
                .wrappedTooltip(Component.literal("Opens the Bazaar search for this item and copies the suggested amount. You place the order.")),
            button(if (open) "Hide details" else "Details", 80) { expanded = item.product.takeUnless { open }; render(force = true) },
        )))
        if (!open) return@apply
        child(divider())
        child(label("Gross spread ${bazaarCoins(item.grossMargin)} − estimated tax ${bazaarCoins(item.fees)} = ${bazaarCoins(item.netProfit)} profit", MenuTheme.TEXT, 9f))
        child(label("Coins needed ${bazaarCoins(item.capital)} · weekly volume ${bazaarCompactCoins(item.liquidity)} · " +
            "trend ${bazaarPercent(item.trend)} · volatility ${bazaarPercent(item.volatility)}", MenuTheme.MUTED, 9f))
        state.priceHistory[item.product]?.takeIf { it.size > 1 }?.let {
            child(label("Sell price history", MenuTheme.MUTED, 8f)); child(BazaarChart(it))
        }
        item.warnings.forEach { child(label("• $it", MenuTheme.YELLOW, 8.5f)) }
        child(label("Fill times come from public weekly volume; your place in the queue is unknown. ${item.source} · ${bazaarTime(item.observedAt)}",
            MenuTheme.MUTED, 8f))
    }

    private fun setupCard() = card().apply {
        id("bazaar-setup")
        child(label("Set up Bazaar flipping", MenuTheme.ACCENT, 12f))
        child(label("Your purse is your budget: bank coins and unsold items are never treated as spendable. Choose how adventurous suggestions should be " +
            "with the buttons above; you can fine-tune everything in settings.", MenuTheme.MUTED, 9f))
        child(toggleRow("In-game alerts for exceptional flips", "inGameAlerts"))
        child(toggleRow("Private Discord DM alerts", "discordAlerts"))
        child(label(if (state.publicSharing) "Completed, verified sales count toward the Bazaar leaderboard under your community sharing settings."
            else "Bazaar leaderboard sharing is off in your community sharing settings.", MenuTheme.MUTED, 9f))
        child(actions(listOf(
            button("Done", 64) { submit(BazaarAction.SETUP) }.apply { intent = MenuButtonIntent.PRIMARY; id("bazaar-setup-done") },
            button("Sharing settings", 112) { submit(BazaarAction.SHARING) },
        )))
    }

    private fun positions() {
        if (state.pending.isNotEmpty()) review()
        body.child(card().apply {
            id("bazaar-capital")
            child(label("Where your coins are", MenuTheme.TEXT, 11f))
            child(label("${bazaarCoins(state.purse)} in your purse is free to spend. Open buy orders hold ${bazaarCoins(state.committed)}.", MenuTheme.MUTED, 9f))
            child(label("Items you hold cost ${bazaarCoins(state.inventoryCost)} and would sell for about ${bazaarCoins(state.inventoryValue)} now " +
                "(an estimate, not profit).", MenuTheme.MUTED, 9f))
            state.pendingCollection?.takeIf { it > 0 }?.let { child(label("${bazaarCoins(it)} from sales is waiting to be claimed.", MenuTheme.YELLOW, 9f)) }
            state.portfolioWarnings.forEach { child(label("• $it", MenuTheme.YELLOW, 8.5f)) }
        })
        if (state.awaiting > 0) body.child(label(if (state.awaiting == 1) "1 sale is waiting for its coins to be claimed; profit is counted once you claim them."
            else "${state.awaiting} sales are waiting for their coins to be claimed; profit is counted once you claim them.", MenuTheme.MUTED, 8.5f))
        val entries = bazaarPositionPriority(state).filter { matches(state.name(it.product), it.product) }
        if (entries.isEmpty()) body.child(emptyCard("No open positions",
            "Place a buy order in the Bazaar and Sky-Myce will follow it from fill to sale. Use Sync orders if something seems missing."))
        paginate(entries) { positionCard(it) }
    }

    private fun positionCard(position: BazaarPosition) = card().apply {
        id("bazaar-position-${position.id}")
        val step = state.nextSteps[position.id]
        child(row().apply {
            icon(position.product)?.let(::child)
            child(column().apply {
                horizontalSizing(Sizing.expand()); gap(2)
                child(label(state.name(position.product), MenuTheme.TEXT, 11f))
                child(label(position.stage.label, if (step?.actionable == true) MenuTheme.YELLOW else MenuTheme.ACCENT, 9f))
            })
            if (position.realizedProfit != 0.0) child(MenuLabel(Component.literal(bazaarSignedCoins(position.realizedProfit))).apply {
                fontSize = 11f; color(Color.ofRgb(if (position.realizedProfit >= 0) MenuTheme.GREEN else MenuTheme.RED))
            })
        })
        child(label(listOfNotNull("${position.remaining} held", "${position.listed} listed".takeIf { position.listed > 0 },
            "${position.pendingItems} to claim".takeIf { position.pendingItems > 0 }, "cost ${bazaarCompactCoins(position.costBasis)}",
            "relisted ${position.relistCount}×".takeIf { position.relistCount > 0 }).joinToString(" · "), MenuTheme.MUTED, 8.5f))
        step?.let { child(label("→ ${it.text}", if (it.actionable) MenuTheme.YELLOW else MenuTheme.TEXT, 9.5f)) }
        state.relistAdvice[position.id]?.takeIf { it.warn }?.let { advice ->
            child(label("Relist check: ${advice.recommendation}", MenuTheme.YELLOW, 9f))
            child(label("Waiting would return ~${bazaarCoins(advice.waitProceeds)}; selling instantly ~${bazaarCoins(advice.instantProceeds)} " +
                "(${bazaarSignedCoins(advice.instantProfit)} vs cost). You decide.", MenuTheme.MUTED, 8.5f))
        }
        state.orders.filter { it.positionId == position.id && (it.status.live || it.filled > it.collected) }.forEach { order ->
            child(label("${if (order.buy) "Buy" else "Sell"} ${order.quantity} at ${bazaarCoins(order.price)} · ${order.filled}/${order.quantity} filled · " +
                "${order.collected} claimed${if (order.uncertain) " · unconfirmed" else ""}", MenuTheme.MUTED, 8.5f))
        }
        if (position.uncertain) child(label("Some of this position was reconstructed, so it stays out of public rankings.", MenuTheme.MUTED, 8f))
        child(actions(listOf(button("Follow this flip", 104) { submit(BazaarAction.GUIDE, mapOf("position" to position.id)) }
            .wrappedTooltip(Component.literal("Show its next step at the top of the terminal")))))
    }

    private fun review() {
        body.child(label("Needs your review (${state.pending.size})", MenuTheme.YELLOW, 11f))
        body.child(label("These Bazaar receipts couldn't be matched to an order with certainty. Syncing orders usually fixes them; " +
            "fixed records stay personal and never count toward leaderboards.", MenuTheme.MUTED, 8.5f))
        state.pending.take(12).forEach { record ->
            body.child(card().apply {
                id("bazaar-pending-${record.id}")
                child(label(record.product?.let { "${state.name(it)} · unmatched receipt" } ?: "Unmatched Bazaar receipt", MenuTheme.TEXT, 10f))
                val fixing = expanded == "pending/${record.id}"
                child(actions(listOf(
                    button("Sync orders", 84) { submit(BazaarAction.OPEN_ORDERS) }.apply { id("bazaar-open-orders-${record.id}") },
                    button(if (fixing) "Close" else "Fix record", 76) { expanded = "pending/${record.id}".takeUnless { fixing }; render(force = true) }
                        .apply { id("bazaar-fix-${record.id}") },
                    button("Dismiss", 64) { submit(BazaarAction.CONFIRM, mapOf("id" to record.id, "ignore" to "true")) }.apply { id("bazaar-dismiss-${record.id}") },
                    button(if (record.id in reviewDetails) "Hide why" else "Why?", 58) {
                        if (!reviewDetails.add(record.id)) reviewDetails.remove(record.id); render(force = true)
                    }.apply { id("bazaar-why-${record.id}") },
                )))
                if (record.id in reviewDetails) child(label(record.message, MenuTheme.MUTED, 8.5f))
                if (fixing) manualInputs(this, record, BazaarAction.CONFIRM)
            })
        }
    }

    private fun history() {
        val stats = state.stats
        body.child(card().apply {
            id("bazaar-stats")
            child(row().apply {
                child(column().apply {
                    horizontalSizing(Sizing.expand()); gap(2)
                    child(label("Realized profit", MenuTheme.MUTED, 8.5f))
                    child(MenuLabel(Component.literal(bazaarSignedCoins(stats?.realizedProfit))).apply {
                        fontSize = 15f; color(Color.ofRgb(if ((stats?.realizedProfit ?: 0.0) < 0) MenuTheme.RED else MenuTheme.GREEN))
                    })
                })
                child(column().apply {
                    horizontalSizing(Sizing.content()); gap(2); horizontalAlignment(HorizontalAlignment.RIGHT)
                    child(label(plural(stats?.completedFlips ?: 0, "completed flip"), MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.content()) })
                    child(label("ROI ${bazaarPercent(stats?.roi)} · ${bazaarCompactCoins(stats?.profitPerHour)}/hour held", MenuTheme.MUTED, 8.5f).apply { horizontalSizing(Sizing.content()) })
                })
            })
            val points = state.history.filter { it.netProfit != null }.sortedBy { it.at }
                .runningFold(BazaarUiPoint(0, 0.0)) { previous, trade -> BazaarUiPoint(trade.at, previous.value + (trade.netProfit ?: 0.0)) }.drop(1)
            if (points.size > 1) child(BazaarChart(points, zeroLine = true))
            child(label("Profit = coins received after tax − what those items cost. ROI divides by that cost; profit per hour uses the time each " +
                "position was held.", MenuTheme.MUTED, 8f))
            child(actions(listOf(button("Leaderboards", 92) { submit(BazaarAction.LEADERBOARD) }, button("Sharing settings", 112) { submit(BazaarAction.SHARING) })))
        })
        val trades = state.history.asReversed().filter { matches(state.name(it.product), it.product) }
        if (trades.isEmpty()) body.child(emptyCard("No completed sales yet", "Sales appear here once their coins are claimed and matched to what you paid."))
        paginate(trades) { tradeCard(it) }
    }

    private fun tradeCard(trade: BazaarUiTrade) = card().apply {
        id("bazaar-trade-${trade.id}")
        child(row().apply {
            icon(trade.product)?.let(::child)
            child(column().apply {
                horizontalSizing(Sizing.expand()); gap(2)
                child(label("${state.name(trade.product)} ×${trade.quantity}", MenuTheme.TEXT, 10f))
                child(label("${bazaarTime(trade.at)} · cost ${bazaarCompactCoins(trade.cost)} · tax ${bazaarCompactCoins(trade.fees)}", MenuTheme.MUTED, 8.5f))
            })
            child(MenuLabel(Component.literal(trade.netProfit?.let(::bazaarSignedCoins) ?: "Cost unknown")).apply {
                fontSize = 11f; color(Color.ofRgb(when { trade.netProfit == null -> MenuTheme.MUTED; trade.netProfit >= 0 -> MenuTheme.GREEN; else -> MenuTheme.RED }))
            })
        })
        val editing = expanded == trade.id
        child(row().apply {
            child(label(if (trade.eligible) "Counts toward leaderboards" else "Personal only (${trade.evidence.lowercase()})", MenuTheme.MUTED, 8f)
                .apply { horizontalSizing(Sizing.expand()) })
            child(button(if (editing) "Close" else "Correct", 64) { expanded = trade.id.takeUnless { editing }; render(force = true) }
                .apply { id("bazaar-correct-${trade.id}") })
        })
        if (editing) manualInputs(this, BazaarUiPending(trade.id, "", kind = "SELL_FILL", product = trade.product), BazaarAction.CORRECT)
    }

    private fun replays() {
        body.child(label("Every flipping session is recorded privately on this device. Publishing is always a separate, explicit choice.", MenuTheme.MUTED, 9f))
        val sessions = state.sessions.asReversed()
        if (sessions.isEmpty()) body.child(emptyCard("No sessions yet", "Start flipping and your session will appear here when it ends."))
        paginate(sessions) { session -> sessionCard(session) }
        if (state.publications.isEmpty()) return
        body.child(label("Published replays", MenuTheme.TEXT, 11f))
        body.child(label("Published links are independent of your local history; revoke or delete them here at any time.", MenuTheme.MUTED, 8.5f))
        state.publications.forEach { publication ->
            body.child(card().apply {
                child(label(if (publication.sessionId == "recovered") "Published replay" else "Published session replay", MenuTheme.TEXT, 10f))
                child(label("${publication.visibility} · ${publication.url}", MenuTheme.MUTED, 8.5f))
                child(actions(listOf(
                    button("Revoke link", 84) { confirm("Revoke this public link?", "The link and any gallery listing stop working. Your local recording stays.") {
                        submit(BazaarAction.REVOKE, mapOf("id" to publication.id))
                    } },
                    button("Delete", 64) { confirm("Delete this published replay?", "The published copy and its gallery listing are permanently deleted. Your local history stays.") {
                        submit(BazaarAction.DELETE_PUBLICATION, mapOf("id" to publication.id, "confirm" to "true"))
                    } }.apply { intent = MenuButtonIntent.DESTRUCTIVE },
                )))
            })
        }
    }

    private fun sessionCard(session: BazaarUiSession) = card().apply {
        id("bazaar-session-${session.id}")
        child(row().apply {
            child(column().apply {
                horizontalSizing(Sizing.expand()); gap(2)
                child(label(session.title, MenuTheme.TEXT, 11f))
                child(label("${bazaarTime(session.startedAt)} → ${bazaarTime(session.endedAt)} · ${plural(session.flips, "completed flip")}", MenuTheme.MUTED, 8.5f))
            })
            child(MenuLabel(Component.literal(bazaarSignedCoins(session.profit))).apply {
                fontSize = 11f; color(Color.ofRgb(if (session.profit < 0) MenuTheme.RED else MenuTheme.GREEN))
            })
        })
        val ended = session.endedAt != null
        val publishing = expanded == session.id
        child(actions(listOf(
            button("Watch replay", 88) { toggleDashboard(session.id) },
            button(if (publishing) "Cancel" else "Publish…", 72) { expanded = session.id.takeUnless { publishing }; render(force = true) }.apply { active(ended) },
            button("Send summary", 92) { anchor -> openMenuDropdown(anchor) { menu ->
                menu.button(Component.literal("To my linked account")) {
                    confirm("Send a session summary?", "Only this session's duration, realized profit and number of completed flips go to your linked account.") {
                        submit(BazaarAction.SESSION_SUMMARY, mapOf("session" to session.id, "confirm" to "true"))
                    }
                }
                if (state.settings["webhookEnabled"] == "true" && state.settings["webhookConfigured"] == "true") menu.button(Component.literal("To my webhook")) {
                    confirm("Send a webhook report?", "Only this session's duration, realized profit and number of completed flips go to your personal webhook.") {
                        submit(BazaarAction.WEBHOOK_SUMMARY, mapOf("session" to session.id, "confirm" to "true"))
                    }
                }
            } }.apply { active(ended) },
            button("Delete", 60) { confirm("Delete this local replay?", "Only the recording is removed. Its trades and any published link are kept.") {
                submit(BazaarAction.DELETE_SESSION, mapOf("session" to session.id))
            } }.apply { intent = MenuButtonIntent.DESTRUCTIVE; active(ended) },
        )))
        if (publishing) publicationControls(this, session)
    }

    private fun publicationControls(card: FlowLayout, session: BazaarUiSession) {
        val choices = publicationChoices.getOrPut(session.id) {
            linkedMapOf("detail" to false, "hideItems" to true, "hideTransactions" to true, "hideValues" to false, "hideIdentity" to true, "listed" to false)
        }
        card.child(divider())
        card.child(label("Choose exactly what leaves this device. Hidden fields are removed from the upload, not just hidden on the page.", MenuTheme.YELLOW, 9f))
        choices.keys.forEach { key ->
            card.child(row().apply {
                child(label(choiceTitle(key), MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.expand()) })
                child(MenuButton(Component.empty()) { choices[key] = choices[key] != true }.apply {
                    sizing(Sizing.fixed(54), Sizing.fixed(20)); toggled = { choices[key] == true }
                })
            })
        }
        card.child(button("Publish replay", 104) {
            val fields = choices.mapValues { it.value.toString() } + ("session" to session.id)
            confirm("Publish this replay?", "Only the selected parts of this session are uploaded, as ${if (choices["listed"] == true) "a public gallery entry" else "an unlisted link"}.") {
                submit(BazaarAction.PUBLISH, fields)
            }
        }.apply { intent = MenuButtonIntent.PRIMARY })
    }

    private fun choiceTitle(key: String) = when (key) {
        "detail" -> "Include each flip (instead of a summary)"
        "hideItems" -> "Hide item names"
        "hideTransactions" -> "Hide individual transactions"
        "hideValues" -> "Hide exact coin amounts"
        "hideIdentity" -> "Hide my Minecraft name"
        else -> "List in the community gallery"
    }

    private fun more() {
        body.child(assistantCard())
        body.child(card().apply {
            child(label("Settings", MenuTheme.TEXT, 11f))
            child(label("Filters, tax estimate, alert thresholds and cooldowns live in the Bazaar settings page.", MenuTheme.MUTED, 8.5f))
            child(toggleRow("In-game alerts", "inGameAlerts"))
            child(toggleRow("Dashboard alerts", "dashboardAlerts"))
            child(toggleRow("Private Discord DM alerts", "discordAlerts"))
            child(actions(listOf(button("All Bazaar settings", 124) { openSettings() }.apply { id("bazaar-settings") },
                button("Move the HUD", 92) { openMenu(MenuPage.HUD) })))
        })
        body.child(card().apply {
            child(label("Browser dashboard", MenuTheme.TEXT, 11f))
            child(label("A private page on this computer only (127.0.0.1). It shuts down two minutes after you close or hide the page.", MenuTheme.MUTED, 8.5f))
            child(actions(listOf(button(if (state.dashboardRunning) "Close dashboard" else "Open dashboard", 110) { toggleDashboard() }.apply { id("bazaar-dashboard") })))
        })
        body.child(card().apply {
            child(label("Personal Discord webhook", MenuTheme.TEXT, 11f))
            child(label("Optional. The URL stays on this device and is never shown again or uploaded to Sky-Myce.", MenuTheme.MUTED, 8.5f))
            val webhook = MenuTextBox(Sizing.expand()).apply {
                id("bazaar-webhook"); setMaxLength(240)
                setHint(Component.literal(if (state.settings["webhookConfigured"] == "true") "Saved · paste a new URL to replace it" else "https://discord.com/api/webhooks/…"))
            }
            child(row().apply {
                child(webhook)
                child(button("Save", 48) { val value = webhook.value; webhook.text(""); submit(BazaarAction.WEBHOOK, mapOf("url" to value)) })
            })
            if (state.settings["webhookConfigured"] == "true") {
                child(toggleRow("Send alerts and reports to it", "webhookEnabled"))
                child(actions(listOf(button("Remove webhook", 104) { submit(BazaarAction.WEBHOOK, mapOf("url" to "")) })))
            }
        })
        body.child(card().apply {
            child(label("Your data", MenuTheme.TEXT, 11f))
            child(label(if (state.publicSharing) "Verified completed sales are shared to the Bazaar leaderboard. Everything else stays on this device."
                else "Leaderboard sharing is off. Your trading history stays on this device.", MenuTheme.MUTED, 8.5f))
            child(row().apply {
                child(label("Sync settings to my account", MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.expand()) })
                child(MenuButton(Component.empty()) {
                    val enable = state.settings["settingsSync"] != "true"
                    confirm(if (enable) "Sync Bazaar settings?" else "Stop syncing settings?",
                        if (enable) "Your Bazaar settings and HUD layout are copied to your linked account. Trades, replays and webhooks stay on this device."
                        else "Sync turns off and the copy on your account is removed. Your local settings stay.") {
                        submit(BazaarAction.SETTINGS_SYNC, mapOf("enabled" to enable.toString()))
                    }
                }.apply { sizing(Sizing.fixed(54), Sizing.fixed(20)); toggled = { state.settings["settingsSync"] == "true" } })
            })
            child(actions(listOf(
                button("Sharing settings", 112) { submit(BazaarAction.SHARING) },
                button("Export history", 100) { submit(BazaarAction.EXPORT, mapOf("native" to "true")) },
                button("Delete history", 100) { confirm("Delete your trading history?", "This permanently removes your local Bazaar records and replays. " +
                    "It does not change sharing choices or revoke published replays.") { submit(BazaarAction.DELETE_HISTORY, mapOf("confirm" to "DELETE")) } }
                    .apply { intent = MenuButtonIntent.DESTRUCTIVE },
            )))
            child(label(state.connectionStatus, MenuTheme.MUTED, 8f))
        })
    }

    // ---- Bazaar Assistant ----

    private fun assistantCard() = card().apply {
        id("bazaar-assistant")
        val assist = me.mycellium.skymyce.features.bazaar.assist.BazaarAssist
        child(row().apply {
            child(label("Bazaar Assistant", MenuTheme.TEXT, 11f).apply { horizontalSizing(Sizing.expand()) })
            child(label(assist.status.label + (assist.productName?.let { " · $it" } ?: ""), MenuTheme.MUTED, 8.5f).apply { horizontalSizing(Sizing.content()) })
        })
        child(label("Guides one flip step by step: the command to run, the button to click and the value to type. You click, type and confirm everything yourself.",
            MenuTheme.MUTED, 8.5f))
        child(actions(listOf(
            if (assist.active) button("Stop guiding", 96) { assist.stop(); render(force = true) }.apply { id("bazaar-assist-stop") }
            else button("Guide the best flip", 120) { assist.start(); feedback("Bazaar Assistant started"); render(force = true) }
                .apply { id("bazaar-assist-start"); intent = MenuButtonIntent.PRIMARY; active(Assist.enabled) },
            button(if (Assist.priceMode == me.mycellium.skymyce.features.bazaar.assist.AssistPriceMode.CUSTOM) "Price: type it" else "Price: match the book", 132) { anchor ->
                openMenuDropdown(anchor) { menu ->
                    menu.button(Component.literal("Match the order book (Top Order +0.1 / Best Offer -0.1)")) {
                        Assist.priceMode = me.mycellium.skymyce.features.bazaar.assist.AssistPriceMode.MATCH_ORDER_BOOK; saveAssist() }
                    menu.button(Component.literal("Type the recommended price on a sign")) {
                        Assist.priceMode = me.mycellium.skymyce.features.bazaar.assist.AssistPriceMode.CUSTOM; saveAssist() }
                }
            }.apply { id("bazaar-assist-price") },
        )))
        child(assistToggle("Assistant enabled", "enabled", { Assist.enabled }) { Assist.enabled = it; if (!it) assist.stop() })
        child(assistToggle("Auto Command", "auto-command", { Assist.autoCommand }, "Turn on Auto Command?",
            "Sends the command the assistant shows (such as /bz Enchanted Carrot) when you press Send Bazaar Assistant command (set it in Options → Controls). " +
                "One key press sends one command, only with no menu open, and never twice in a row. It never clicks, " +
                "confirms or places anything. Hypixel's rules on modifications still apply to you; leave this off if you're unsure.") { Assist.autoCommand = it })
        child(assistToggle("Auto Sign", "auto-sign", { Assist.autoSign }, "Turn on Auto Sign?",
            "Pre-types the expected search, amount or price into an empty Bazaar sign. You still check it and click Done yourself; " +
                "it never submits, closes or confirms. Hypixel's rules on modifications still apply to you.") { Assist.autoSign = it })
        child(row().apply {
            child(label("Highlight", MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.expand()) })
            child(me.mycellium.skymyce.features.bazaar.assist.AssistHighlightPreview())
            child(button(Assist.highlightShape.label, 104) { anchor ->
                openMenuDropdown(anchor) { menu ->
                    me.mycellium.skymyce.config.economy.AssistHighlightShape.entries.forEach { shape ->
                        menu.button(Component.literal(shape.label)) { Assist.highlightShape = shape; saveAssist() }
                    }
                }
            }.apply { id("bazaar-assist-shape") })
        })
        assist.lastRun?.let { run ->
            child(label("Last run: ${bazaarSignedCoins(run.profit)} from ${plural(run.sales, "sale")} in ${me.mycellium.skymyce.features.bazaar.assist.assistDuration(run.millis)}" +
                (run.perHour?.let { " · ${bazaarCompactCoins(it)}/h" } ?: ""), MenuTheme.MUTED, 8.5f))
        }
        val keys = listOfNotNull(assist.startKeyName()?.let { "start $it" }, assist.stopKeyName()?.let { "stop $it" },
            assist.commandKeyName()?.let { "send command $it" })
        child(label(if (keys.isEmpty()) "Set start, stop and send-command keys in Options → Controls → Miscellaneous. Color, pulse and animations are in the settings."
            else "Keys: ${keys.joinToString(" · ")}. Change them in Options → Controls.", MenuTheme.MUTED, 8f))
        child(actions(listOf(
            button("All assistant settings", 132) { openSettings() }.apply { id("bazaar-assist-settings") },
            button("Move the HUD", 92) { openMenu(MenuPage.HUD) },
            button("Reset to defaults", 108) {
                confirm("Reset the Bazaar Assistant?", "Its settings return to their defaults, Auto Command and Auto Sign turn off, and its HUD returns to its default place.") {
                    Assist.resetAppearance()
                    val hud = me.mycellium.skymyce.features.bazaar.assist.BazaarAssistHud
                    listOf(hud.panel, hud.popups).forEach { it.defaultLayout.copy(theme = it.theme).applyConfig(it) }
                    me.mycellium.skymyce.hud.widget.WidgetManager.save()
                    saveAssist()
                }
            }.apply { id("bazaar-assist-reset") },
        )))
    }

    private fun assistToggle(title: String, key: String, value: () -> Boolean, confirmTitle: String? = null, explanation: String = "",
                             set: (Boolean) -> Unit) = row().apply {
        child(label(title, MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.expand()) })
        child(MenuButton(Component.empty()) {
            val enable = !value()
            if (enable && confirmTitle != null) confirm(confirmTitle, explanation) { set(true); saveAssist() }
            else { set(enable); saveAssist() }
        }.apply { id("bazaar-assist-$key"); sizing(Sizing.fixed(54), Sizing.fixed(20)); toggled = value })
    }

    private fun saveAssist() { me.mycellium.skymyce.SkyMyce.config.save(); render(force = true) }

    // ---- Record repair ----

    private fun manualInputs(parent: FlowLayout, record: BazaarUiPending, action: BazaarAction) {
        val id = record.id
        val draft = manualDrafts.getOrPut(id) {
            buildMap {
                record.quantity?.let { put("quantity", it.toString()) }; record.unitPrice?.let { put("unitPrice", it.toString()) }
                record.coins?.let { put("coins", it.toString()) }; record.fee?.let { put("fee", it.toString()) }
            }.toMutableMap()
        }
        val kind = record.kind ?: draft["kind"].orEmpty()
        parent.child(divider())
        parent.child(label("A fixed record stays personal and never counts toward leaderboards.", MenuTheme.MUTED, 8.5f))
        if (record.kind == null) parent.child(button(repairKinds[kind] ?: "What happened?", 150) { anchor ->
            openMenuDropdown(anchor) { menu -> repairKinds.forEach { (value, title) -> menu.button(Component.literal(title)) { draft["kind"] = value; render(force = true) } } }
        }.apply { id("bazaar-repair-kind-$id") })
        else parent.child(label(repairKinds[kind] ?: kind.replace('_', ' '), MenuTheme.TEXT, 10f))
        if (kind.isEmpty()) return
        if (kind !in repairKinds) { parent.child(label("This receipt needs its order. Sync orders, or dismiss it.", MenuTheme.MUTED, 8.5f)); return }
        val order = kind.endsWith("ORDER")
        var save: MenuButton? = null
        val required = (if (order) listOf("quantity", "unitPrice") else listOf("quantity", "coins")) + if (record.product == null) listOf("product") else emptyList()
        fun ready() { save?.active(action == BazaarAction.CORRECT || required.all { !draft[it].isNullOrBlank() }) }
        fun field(key: String, title: String) {
            parent.child(label(title, MenuTheme.MUTED, 8.5f))
            parent.child(MenuTextBox(Sizing.fill()).apply {
                id("bazaar-repair-$id-$key"); setMaxLength(if (key == "note") 160 else if (key == "product") 100 else 32)
                text(draft[key].orEmpty()); setHint(Component.literal(title))
                onChanged().subscribe { draft[key] = it; ready() }
            })
        }
        if (record.product == null) field("product", "Item name or ID")
        else parent.child(label("Item: ${state.name(record.product)}", MenuTheme.MUTED, 8.5f))
        field("quantity", "Quantity")
        if (order) field("unitPrice", "Price per item") else field("coins", if (kind == "BUY_FILL") "Total paid (coins)" else "Coins received after tax")
        if (action == BazaarAction.CORRECT) parent.child(label("Leave a field blank to keep its saved value.", MenuTheme.MUTED, 8f))
        parent.child(button(if (id in manualAdvanced) "Fewer fields" else "More fields", 84) {
            if (!manualAdvanced.add(id)) manualAdvanced.remove(id); render(force = true)
        }.apply { id("bazaar-repair-advanced-$id") })
        if (id in manualAdvanced) {
            if (!order) field("unitPrice", "Price per item (optional)")
            field("fee", "Known tax or fee (optional)"); field("note", "Note (optional)")
        }
        draft["_message"]?.let { parent.child(label(it, MenuTheme.YELLOW, 8.5f)) }
        val allowed = setOf("quantity", "unitPrice", "fee", "note") + (if (order) emptySet() else setOf("coins")) +
            (if (record.product == null) setOf("product") else emptySet())
        save = button("Save record", 92) {
            submit(action, mapOf("id" to id) + draft.filter { (key, value) -> key in allowed && value.isNotBlank() } +
                if (record.kind == null) mapOf("kind" to kind) else emptyMap())
        }.apply { id("bazaar-repair-save-$id"); intent = MenuButtonIntent.PRIMARY }
        ready(); parent.child(requireNotNull(save))
    }

    // ---- Actions ----

    private fun submit(action: BazaarAction, fields: Map<String, String> = emptyMap()) {
        if (submitting) { feedback("Still working on the last change"); return }
        val bridge = BazaarUi.bridge ?: return feedback("Bazaar trading is unavailable", MenuToastKind.ERROR)
        submitting = true
        val result: CompletableFuture<BazaarUiResult> = bridge.action(action.wire, fields)
        result.whenComplete { value, failure ->
            Minecraft.getInstance().execute {
                submitting = false
                if (Minecraft.getInstance().screen !== this@BazaarScreen) return@execute
                val message = if (failure == null) value.message else "That change could not be completed"
                if (action == BazaarAction.CONFIRM || action == BazaarAction.CORRECT) fields["id"]?.let { manualDrafts[it]?.set("_message", message) }
                feedback(message, if (failure == null) MenuToastKind.INFO else MenuToastKind.ERROR)
                if (failure == null) {
                    state = bridge.snapshot(); seenRevision = state.revision
                    updateChrome(); render(force = true)
                }
            }
        }
    }

    private fun feedback(message: String, kind: MenuToastKind = MenuToastKind.INFO) { showMenuToast(message, kind) }
    private fun confirm(title: String, detail: String, run: () -> Unit) = navigateMenu(MenuConfirmScreen(this, title, detail, "Confirm", run))

    // ---- Building blocks ----

    private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
    private fun matches(name: String, id: String) = query.isBlank() || name.contains(query, true) || id.contains(query, true)

    private fun <T> paginate(entries: List<T>, render: (T) -> UIComponent) {
        val pages = ((entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        entries.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEach { body.child(render(it)) }
        if (pages > 1) body.child(row().apply {
            child(button("‹ Previous", 76) { page--; render(force = true, scrollTop = true) }.apply { active(page > 0) })
            child(label("${page + 1} / $pages", MenuTheme.MUTED, 9f).apply { horizontalSizing(Sizing.expand()); horizontalTextAlignment(HorizontalAlignment.CENTER) })
            child(button("Next ›", 64) { page++; render(force = true, scrollTop = true) }.apply { active(page + 1 < pages) })
        })
    }

    private fun emptyCard(title: String, detail: String, actionTitle: String? = null, action: (() -> Unit)? = null) = card().apply {
        id("bazaar-empty")
        child(label(title, MenuTheme.TEXT, 11f))
        child(label(detail, MenuTheme.MUTED, 9f))
        if (actionTitle != null && action != null) child(actions(listOf(button(actionTitle, 104) { action() }.apply { intent = MenuButtonIntent.PRIMARY })))
    }

    private fun toggleRow(title: String, key: String) = row().apply {
        child(label(title, MenuTheme.TEXT, 9f).apply { horizontalSizing(Sizing.expand()) })
        child(MenuButton(Component.empty()) { submit(BazaarAction.PREFERENCE, mapOf("key" to key, "value" to (state.settings[key] != "true").toString())) }.apply {
            id("bazaar-toggle-$key"); sizing(Sizing.fixed(54), Sizing.fixed(20)); toggled = { state.settings[key] == "true" }
        })
    }

    private fun icon(product: String): UIComponent? = BazaarNames.icon(product)?.let { stack ->
        MenuItem(stack).apply { sizing(Sizing.fixed(20), Sizing.fixed(20)); setTooltipFromStack(true) }
    }

    private fun divider() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.fixed(1)).apply {
        surface(MenuTheme.tintedPanel { MenuTheme.alpha(MenuTheme.BORDER, 70) })
    }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); verticalAlignment(VerticalAlignment.CENTER) }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(4) }
    private fun card() = column().apply { padding(Insets.of(8, 8, 10, 10)); gap(4); surface(MenuTheme.panel(true)) }
    private fun label(value: String, color: Int = MenuTheme.TEXT, size: Float = 10f) = MenuLabel(Component.literal(value)).apply {
        color(Color.ofRgb(color)); fontSize = size; horizontalSizing(Sizing.fill())
    }
    private fun button(title: String, width: Int, run: (MenuButton) -> Unit) = MenuButton(Component.literal(title)) { run(it as MenuButton) }.apply {
        sizing(Sizing.fixed(width.coerceIn(1, innerWidth)), Sizing.fixed(21))
    }
    private fun actions(buttons: List<MenuButton>) = column().apply {
        menuActionRows(buttons.map { it.horizontalSizing().get().value }, innerWidth).forEach { line ->
            child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); line.forEach { child(buttons[it]) } })
        }
    }

    private companion object { const val PAGE_SIZE = 12 }
}

/** A small static line chart on the shared canvas; no separate renderer and no idle animation. */
internal class BazaarChart(private val points: List<BazaarUiPoint>, private val zeroLine: Boolean = false) : BaseUIComponent() {
    init { sizing(Sizing.fill(), Sizing.fixed(72)) }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val canvas = (graphics as MenuGraphics).canvas
        val sampled = points.filter { it.value.isFinite() }.takeLast(256)
        if (sampled.size < 2) return
        val low = minOf(sampled.minOf { it.value }, if (zeroLine) 0.0 else Double.MAX_VALUE)
        val high = maxOf(sampled.maxOf { it.value }, if (zeroLine) 0.0 else -Double.MAX_VALUE)
        val range = (high - low).coerceAtLeast(abs(high) * .001).coerceAtLeast(1.0)
        val start = sampled.first().at
        val span = (sampled.last().at - start).coerceAtLeast(1)
        fun px(at: Long) = x() + 4.0 + (at - start).toDouble() / span * (width() - 8).coerceAtLeast(1)
        fun py(value: Double) = y() + height() - 5.0 - (value - low) / range * (height() - 10).coerceAtLeast(1)
        canvas.line(x().toDouble(), (y() + height() - 1).toDouble(), (x() + width()).toDouble(), (y() + height() - 1).toDouble(), 1.0, MenuTheme.alpha(MenuTheme.BORDER, 70))
        if (zeroLine && low < 0) canvas.line(x().toDouble(), py(0.0), (x() + width()).toDouble(), py(0.0), 1.0, MenuTheme.alpha(MenuTheme.MUTED, 45))
        val color = if (sampled.last().value >= sampled.first().value) MenuTheme.GREEN else MenuTheme.RED
        sampled.zipWithNext().forEach { (a, b) -> canvas.line(px(a.at), py(a.value), px(b.at), py(b.value), 1.5, MenuTheme.alpha(if (zeroLine) color else MenuTheme.ACCENT)) }
    }
}
