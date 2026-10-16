package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityScreen
import me.mycellium.skymyce.community.CommunitySharing
import me.mycellium.skymyce.community.LeaderboardScreen
import me.mycellium.skymyce.community.SharingCategory
import me.mycellium.skymyce.config.economy.BazaarConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils
import me.mycellium.skymyce.utils.StateWriter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.hypixel.ServerChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.location.SkyBlockLocationEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.currency.CurrencyAPI
import tech.thatgravyboat.skyblockapi.api.profile.currency.PurseType
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.helpers.McClient
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.extentions.getRawLore
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates observation, private accounting, market data, alerts and the UI. Observers never act on trades;
 * the only game interaction is opening a Bazaar menu after the player explicitly clicks Guide or Sync orders.
 */
object BazaarTrading : SkyMyceModule(), BazaarUiBridge {
    private val lifecycle = BazaarSessionLifecycle()
    private val observer = BazaarObserver(::bazaarProductId)
    private val journal = BazaarJournal({ account -> SkyMyce.configPath.resolve("bazaar-journal").resolve(account.replace("-", "")) }, ::writeFailed)
    private val market by lazy { BazaarMarketFeed(BazaarMarketStore(SkyMyce.configPath.resolve("bazaar-market.json")), BazaarTransport(), ::analyse) }
    private val alerts = BazaarAlerts()
    private val community = BazaarCommunity { refreshView() }
    private val preferenceSync by lazy { BazaarPreferenceSync(community, ::persistPreferences, ::refreshView) }
    private lateinit var configWriter: StateWriter<Map<String, String>>
    private lateinit var toggleKey: KeyMapping
    private lateinit var cycleKey: KeyMapping
    private var boundToggle = 0
    private var boundCycle = 0

    private var context: BazaarContext? = null
    private var purse: Double? = null
    private var lastInteractionAt = 0L
    private var nextTick = 0L
    /** Invalidates in-flight analysis whenever the account, profile, session or journal changes underneath it. */
    private var epoch = 0L

    private data class Observation(val context: BazaarContext, val at: Long, val session: String?, val text: String? = null,
                                   val title: String = "", val rows: List<BazaarObservedSlot> = emptyList())
    private val observations = ArrayDeque<Observation>()
    private var dirtyScreen: AbstractContainerScreen<*>? = null

    private var ledger = BazaarLedger()
    private var opportunities = emptyList<BazaarOpportunity>()
    private var analysisBusy = false
    private var analysisDirty = false
    private var analysedArchive: BazaarArchive? = null
    private var analysedContext: BazaarContext? = null
    private var analysisProblem: String? = null

    private var guidedProduct: String? = null
    private var guidedPosition: String? = null
    private var guidedAt = 0L
    private var guidedPlan: String? = null

    private var relayLease = -1L
    private var relayEpoch: String? = null
    private var relayDm = false

    private var previousPreferences = emptyMap<String, String>()
    private var lastFreshness = BazaarFreshness.UNAVAILABLE
    private var status = "Loading your trading history…"
    private var revision = 0L
    @Volatile private var view = BazaarUiState()

    private const val IDLE_MILLIS = 120_000L
    private val colorCode = Regex("§.")
    private val analysisKeys = setOf("risk", "minimumProfit", "minimumRoiBps", "minimumLiquidity", "maximumFillMinutes", "minimumConfidence",
        "maximumCapital", "concentration", "sellTaxBps")

    override fun init() {
        BazaarUi.bridge = this
        configWriter = StateWriter({ SkyMyce.config.save() }, { job -> Scheduling.schedule(500.milliseconds) { job() } },
            { SkyMyce.logger.warn("Bazaar preferences could not be saved") })
        toggleKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Start or stop Bazaar flipping", BazaarConfig.toggleKey, KeyMapping.Category.MISC))
        cycleKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Next Bazaar HUD position", BazaarConfig.cycleKey, KeyMapping.Category.MISC))
        boundToggle = BazaarConfig.toggleKey; boundCycle = BazaarConfig.cycleKey
        previousPreferences = bazaarPreferences()
        community.initialize()
        market.load()
        openJournal(selectedOwner())
        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled()) update() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { stop("Game closing"); BazaarDashboard.stop() }
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { journal.flush(); market.flush(); configWriter.flush(); community.flush() }
        }, "Sky-Myce-Bazaar-Save"))
    }

    override fun onDisable() { stop("Module disabled"); BazaarDashboard.stop() }
    override fun snapshot() = if (journal.owner == selectedOwner()) view else BazaarUiState(status = "Minecraft account changed; reopen the dashboard")
    internal fun needsRelay() = isEnabled() && lifecycle.active != null && BazaarConfig.discordAlerts
    val active get() = lifecycle.active != null

    // ---- Account, context and lifecycle ----

    private fun selectedOwner() = MC.instance.user.profileId.toString()

    private fun liveContext(): BazaarContext? {
        if (!LocationAPI.isOnSkyBlock || !LocationAPI.onHypixel || MC.instance.player == null || !ProfileAPI.isLoaded) return null
        val profile = ProfileAPI.profileId?.toString() ?: ProfileAPI.profileName?.takeIf(String::isNotBlank) ?: return null
        return BazaarContext(selectedOwner(), profile, if (LocationAPI.onAlpha) "alpha" else "main")
    }

    private fun island() = LocationAPI.island?.name?.let { name -> LocationAPI.serverId?.takeIf(String::isNotBlank)?.let { "$name:$it" } }

    /** The context whose history the UI shows: live if possible, otherwise the most recent one recorded. */
    private fun visibleContext(): BazaarContext? = context
        ?: journal.archive.events.lastOrNull { it.context.account == journal.owner }?.context
        ?: journal.archive.sessions.lastOrNull { it.context.account == journal.owner }?.context

    private fun openJournal(account: String) {
        journal.open(account) { storage ->
            status = storage.error ?: "Not flipping"
            storage.legacyMarket?.let { market.adopt(it, migrated = true) }
            context = liveContext()
            analyse()
        }
    }

    private fun switchOwner(selected: String) {
        stop("Minecraft account changed"); BazaarDashboard.stop()
        observer.reset(); observations.clear(); dirtyScreen = null; epoch++
        context = null; purse = null; ledger = BazaarLedger(); opportunities = emptyList()
        analysedArchive = null; analysedContext = null; clearGuidance()
        view = BazaarUiState(revision = ++revision, status = "Loading the selected account's history…")
        openJournal(selected)
    }

    private fun writeFailed() {
        stop("Local history could not be saved")
        status = "Recording paused: your history could not be saved. Export it and check disk space"
        refreshView()
    }

    private fun update() {
        val selected = selectedOwner()
        if (journal.owner != selected) { switchOwner(selected); return }
        if (!journal.loaded) return
        syncKeys()
        while (toggleKey.consumeClick()) toggle()
        while (cycleKey.consumeClick()) BazaarHud.cycle()
        val now = System.currentTimeMillis()
        lifecycle.observe(liveContext(), island(), now)?.let(::ended)
        dirtyScreen?.let { screen ->
            dirtyScreen = null
            if (MC.instance.screen === screen) observeContainer(screen.title.string, screen.menu.slots.filterNot { it.container is Inventory }.map(::observedSlot))
        }
        drainObservations()
        if (now < nextTick) return
        nextTick = now + 500
        val live = liveContext()
        if (context != live) { context = live; epoch++; observer.reset(); analyse() }
        updatePurse()
        updatePreferences()
        val freshness = market.freshness(now)
        if (freshness != lastFreshness) { lastFreshness = freshness; analyse() }
        market.tick(lifecycle.active != null, now - lastInteractionAt > IDLE_MILLIS, now)
        updateRelay()
        community.tick(context, now)
        preferenceSync.tick(now)
    }

    /** The purse only funds flips when the scoreboard shows the normal purse (not Piggy Bank or a hidden board). */
    private fun updatePurse() {
        val visible = McClient.scoreboard.any { it.string.replace(colorCode, "").trimStart().startsWith("Purse:") }
        val cash = if (context != null && visible && CurrencyAPI.purseType == PurseType.NORMAL) CurrencyAPI.purse.takeIf { it.isFinite() && it >= 0 } else null
        if (cash == purse) return
        purse = cash
        if (lifecycle.active != null) analyse() else refreshView()
    }

    private fun syncKeys() {
        fun sync(mapping: KeyMapping, configured: Int, bound: Int, save: (Int) -> Unit): Int {
            if (configured != bound) {
                mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(configured)); KeyMapping.resetMapping()
                return configured
            }
            val current = KeyMappingHelper.getBoundKeyOf(mapping).value
            if (current != configured) { save(current); persistPreferences() }
            return current
        }
        boundToggle = sync(toggleKey, BazaarConfig.toggleKey, boundToggle) { BazaarConfig.toggleKey = it }
        boundCycle = sync(cycleKey, BazaarConfig.cycleKey, boundCycle) { BazaarConfig.cycleKey = it }
    }

    private fun updatePreferences() {
        val preferences = bazaarPreferences()
        if (preferences == previousPreferences) return
        val changed = preferences.keys.filter { preferences[it] != previousPreferences[it] }
        previousPreferences = preferences
        if (changed.any { !it.startsWith("hud") }) persistPreferences()
        if (changed.all { it.startsWith("hud") }) { view = view.copy(revision = ++revision, settings = view.settings + preferences); return }
        if (changed.any { it in analysisKeys }) analyse() else refreshView()
        if ("discordAlerts" in changed) updateRelay()
    }

    private fun persistPreferences() {
        configWriter.submit(bazaarPreferences() + mapOf("toggleKey" to BazaarConfig.toggleKey.toString(), "cycleKey" to BazaarConfig.cycleKey.toString()))
    }

    /** Menu, keybind and command all land here, so there is only ever one session, poller and HUD. */
    fun toggle() {
        if (lifecycle.active != null) { stop("Stopped"); return }
        if (!isEnabled() || !journal.writable) {
            status = if (!isEnabled()) "Bazaar flipping is turned off in settings" else journal.error ?: "Your trading history isn't ready yet"
            refreshView(); return
        }
        val scope = liveContext()
        val island = island()
        if (scope == null || island == null) { status = "Join a SkyBlock island first"; refreshView(); return }
        if (scope.account != journal.owner) { switchOwner(scope.account); return }
        context = scope
        val now = System.currentTimeMillis()
        val session = lifecycle.start(bazaarRandomId(), scope, island, now) ?: return
        journal.startSession(session)
        epoch++
        lastInteractionAt = now
        clearGuidance(); alerts.clear()
        status = "Flipping · loading market data"
        updateRelay(); analyse()
        market.tick(true, false, now)
    }

    fun start() { if (lifecycle.active == null) toggle() }
    fun end() { if (lifecycle.active != null) toggle() }

    private fun stop(reason: String) { lifecycle.stop(System.currentTimeMillis(), reason)?.let(::ended) }

    private fun ended(session: BazaarSession) {
        journal.endSession(session)
        epoch++
        market.stop()
        opportunities = emptyList(); clearGuidance(); alerts.clear()
        status = "Not flipping · ${session.reason ?: "session ended"}"
        updateRelay(); analyse(); refreshView()
    }

    private fun clearGuidance() { guidedProduct = null; guidedPosition = null; guidedPlan = null }

    @Subscription(ServerDisconnectEvent::class, ProfileChangeEvent::class, SkyBlockLocationEvent.Leave::class)
    fun lostContext() {
        // Orders keep filling offline and Manage Orders shows that progress later, so a disconnect is not a gap.
        stop("Left SkyBlock or disconnected")
        context = null; observer.reset(); observations.clear(); dirtyScreen = null; epoch++
        analyse()
    }

    @Subscription fun changedServer(event: ServerChangeEvent) { stop("Left the island"); observer.reset() }

    // ---- Observation ----

    private fun observedSlot(slot: Slot) = BazaarObservedSlot(slot.index, slot.item.getSkyBlockId()?.bazaarId,
        slot.item.hoverName.string.replace(colorCode, ""), slot.item.getRawLore())

    @Subscription(receiveCancelled = true)
    fun chat(event: ChatReceivedEvent.Pre) {
        if (!isEnabled() || !event.text.trimStart().startsWith("[Bazaar]")) return
        val scope = liveContext() ?: return
        if (journal.writable) queue(Observation(scope, System.currentTimeMillis(), lifecycle.active?.id, text = event.text))
    }

    @Subscription
    fun container(event: ContainerInitializedEvent) {
        if (!isEnabled() || !event.title.contains("Bazaar", true) && !event.title.contains("Orders", true)) return
        observeContainer(event.title, event.containerSlots.filterNot { it.container is Inventory }.map(::observedSlot))
    }

    /** Fill progress changes while Manage Orders stays open; re-read it on the next tick. */
    @Subscription
    fun inventory(event: InventoryChangeEvent) {
        if (!isEnabled() || event.isInPlayerInventory) return
        if (event.title.contains("Bazaar", true) || event.title.contains("Orders", true)) dirtyScreen = event.screen
    }

    private fun observeContainer(title: String, rows: List<BazaarObservedSlot>) {
        val scope = liveContext() ?: return
        if (journal.writable) queue(Observation(scope, System.currentTimeMillis(), lifecycle.active?.id, title = title, rows = rows))
    }

    private fun queue(observation: Observation) {
        lastInteractionAt = System.currentTimeMillis()
        if (context != observation.context) { context = observation.context; epoch++; observer.reset(); analyse() }
        if (observations.size >= 128) {
            // Dropping receipts is a genuine gap: open positions can no longer be trusted for public rankings.
            status = "Too many Bazaar receipts at once; open Manage Orders to resync"
            if (ledger.positions.any { it.remaining > 0 })
                record(listOf(BazaarEvent(bazaarRandomId(), observation.context, "UNKNOWN", BazaarEventKind.GAP, observation.at,
                    note = "Local observation queue overflow")))
            return
        }
        observations.addLast(observation)
        drainObservations()
    }

    /** Each receipt is interpreted against a ledger that already includes every earlier receipt. */
    private fun drainObservations() {
        while (journal.writable && !analysisBusy && analysedArchive === journal.archive && analysedContext == liveContext() && observations.isNotEmpty()) {
            val entry = observations.removeFirst()
            if (entry.context != liveContext()) continue
            val products = market.snapshot?.products.orEmpty()
            val events = if (entry.text != null) observer.chat(entry.text, entry.context, ledger, products, entry.at, entry.session)
                else observer.container(entry.title, entry.rows, entry.context, ledger, products, entry.at, entry.session)
            record(events)
        }
    }

    private fun record(events: List<BazaarEvent>) {
        val before = journal.archive
        val rejected = journal.record(events)
        if (rejected > 0) status = "A Bazaar receipt conflicted with your history and was not counted"
        if (journal.archive !== before) analyse()
    }

    // ---- Analysis ----

    /** Reconciles the journal and ranks flips off-thread; results apply only if nothing changed meanwhile. */
    private fun analyse() {
        if (!journal.loaded) return
        analysisDirty = true
        if (analysisBusy) return
        analysisBusy = true; analysisDirty = false
        val captured = journal.archive
        val scope = visibleContext()
        val token = epoch
        val cash = purse
        val snapshot = market.snapshot
        val settings = runCatching { bazaarFilters() to bazaarFees() }.getOrElse {
            analysisBusy = false; opportunities = emptyList()
            analysisProblem = "Check your Bazaar Finder settings; one of them is invalid"
            refreshView(); return
        }
        val (filters, fees) = settings
        val risk = BazaarConfig.risk
        val scanning = lifecycle.active != null
        Scheduling.schedule(0.seconds) {
            val result = runCatching {
                val reconciled = scope?.let { reconcileBazaar(captured, it) } ?: BazaarLedger()
                reconciled to if (scanning) scanBazaar(snapshot, cash, reconciled, filters, risk, fees, System.currentTimeMillis()) else emptyList()
            }
            MC.instance.execute {
                analysisBusy = false
                if (token == epoch && journal.archive === captured) result
                    .onSuccess { (next, ranked) -> applyAnalysis(scope, next, ranked) }
                    .onFailure { status = "Trading analysis failed; your history is safe"; refreshView() }
                if (analysisDirty || token != epoch || journal.archive !== captured) analyse() else drainObservations()
            }
        }
    }

    private fun applyAnalysis(scope: BazaarContext?, next: BazaarLedger, ranked: List<BazaarOpportunity>) {
        val previousLedger = ledger
        val previousScope = analysedContext
        val known = previousLedger.realizations.associateBy { it.id }
        val current = next.realizations.associateBy { it.id }
        val invalidated = known.values.filter { old -> old.eligible && current[old.id]?.let { it.eligible && it.netProfit == old.netProfit } != true }
            .mapTo(hashSetOf()) { it.id }
        ledger = next; opportunities = ranked; analysisProblem = null
        analysedArchive = journal.archive; analysedContext = scope
        if (scope != null && previousScope == scope) {
            // Retract changed public contributions, upload new ones, and re-upload ones whose flip just completed.
            community.remove(scope, invalidated)
            val promotions = known.values.filter { it.eligible }.mapTo(hashSetOf()) { it.id }
            community.enqueue(scope, next, next.realizations.filter { row ->
                row.eligible && (known[row.id]?.eligible != true ||
                    bazaarFinalRealization(next, row) && known[row.id]?.let { !bazaarFinalRealization(previousLedger, it) } == true)
            }, promotions)
        }
        notifyExceptional()
        refreshView()
        if (lifecycle.active != null)
            market.refreshHistories((next.positions.filter { it.remaining > 0 }.map { it.product } + ranked.map { it.product }).distinct().take(4))
    }

    private fun notifyExceptional() {
        val session = lifecycle.active ?: return
        if (liveContext() != session.context || MC.instance.connection == null) return
        val delivery = community.state
        alerts.consider(opportunities, BazaarAlertContext(session, purse, market.freshness(),
            delivery.webhook.takeIf { delivery.webhookEnabled && it.isNotEmpty() },
            current = { lifecycle.active?.id == session.id && market.freshness() == BazaarFreshness.FRESH },
            webhookCurrent = { url -> community.state.webhookEnabled && community.state.webhook == url }))
    }

    /** Advertises the session to the relay so private DMs can follow it; no per-player record is stored. */
    private fun updateRelay() {
        val active = lifecycle.active
        val lease = DungeonFriendRelay.sessionGeneration
        val wanted = active?.id
        val dm = BazaarConfig.discordAlerts
        if (relayLease == lease && relayEpoch == wanted && relayDm == dm) return
        val packetEpoch = wanted ?: relayEpoch ?: return
        val sent = DungeonFriendRelay.bazaarPacket("bazaar_session", mapOf("id" to bazaarRandomId(), "epoch" to packetEpoch, "active" to (active != null), "dmEnabled" to dm)) {
            DungeonFriendRelay.sessionGeneration == lease && lifecycle.active?.id == wanted && BazaarConfig.discordAlerts == dm
        }
        if (sent) { relayLease = lease; relayEpoch = wanted; relayDm = dm }
    }

    // ---- View ----

    private fun refreshView() {
        if (!journal.loaded) return
        val now = System.currentTimeMillis()
        val scope = visibleContext()
        val snapshot = market.snapshot
        val freshness = market.freshness(now)
        val fees = runCatching(::bazaarFees).getOrNull()
        val quotes = snapshot?.products.orEmpty().associateBy { it.id }
        val portfolio = bazaarPortfolio(ledger, purse, if (fees != null) quotes else emptyMap(), fees ?: BazaarFees())
        val live = freshness == BazaarFreshness.FRESH
        val threshold = BazaarConfig.relistThreshold
        val visibleOpportunities = opportunities.take(100)
        val names = BazaarNames.names(visibleOpportunities.map { it.product } + ledger.positions.map { it.product } +
            ledger.orders.map { it.product } + ledger.realizations.takeLast(500).map { it.product })
        val ordersByPosition = ledger.orders.groupBy { it.positionId }
        val steps = ledger.positions.associate { position ->
            position.id to bazaarNextStep(position, ordersByPosition[position.id].orEmpty(), quotes[position.product], live, threshold,
                names[position.product] ?: bazaarFallbackName(position.product))
        }
        val ignored = community.state.ignored
        val unresolved = ledger.pending.filterNot { it.eventId in ignored }
        val pending = unresolved.filterNot { it.awaiting }.take(64)
        val active = lifecycle.active
        view = BazaarUiState(
            revision = ++revision,
            active = active != null,
            status = analysisProblem ?: when {
                active == null -> status
                live -> "Flipping on ${islandName(active.island)}"
                else -> "Flipping on ${islandName(active.island)} · waiting for live market data"
            },
            marketStatus = market.status,
            source = snapshot?.source ?: "None",
            observedAt = snapshot?.observedAt,
            retrievedAt = snapshot?.retrievedAt,
            freshness = freshness,
            island = active?.let { islandName(it.island) },
            accountLabel = scope?.let { "${MC.instance.user.name} · ${it.profile}${if (it.server == "alpha") " · Alpha" else ""}" } ?: "Offline history",
            purse = purse,
            committed = portfolio.committedOrders,
            inventoryCost = portfolio.inventoryCost,
            inventoryValue = portfolio.estimatedInventoryValue,
            pendingCollection = portfolio.unclaimedCoins,
            opportunities = visibleOpportunities,
            positions = ledger.positions,
            orders = ledger.orders,
            nextSteps = steps,
            guidance = guidance(steps, names),
            history = ledger.realizations.takeLast(1000).map(::uiTrade),
            sessions = uiSessions(scope),
            pending = uiPending(pending),
            awaiting = unresolved.count { it.awaiting },
            stats = bazaarStats(ledger),
            settings = bazaarPreferences() + mapOf("webhookEnabled" to community.state.webhookEnabled.toString(),
                "webhookConfigured" to community.state.webhook.isNotEmpty().toString()),
            wizardDone = BazaarConfig.wizardDone,
            publicSharing = CommunitySharing.canShare(SharingCategory.ECONOMY) && CommunitySharing.canShare(SharingCategory.LEADERBOARDS),
            connectionStatus = "${community.status} · ${preferenceSync.status}",
            priceHistory = quotes.filterKeys { id -> visibleOpportunities.take(12).any { it.product == id } || ledger.positions.any { it.product == id && it.open } }
                .mapValues { (_, quote) -> quote.history.map { BazaarUiPoint(it.at, it.sellPrice) } },
            publications = community.state.publications.filter { it.player == journal.owner }.map { BazaarUiPublication(it.id, it.sessionId, it.url, it.visibility) },
            alert = alerts.latest.takeIf { BazaarConfig.dashboardAlerts && active != null },
            alertSerial = alerts.serial,
            relistAdvice = ledger.positions.filter { it.remaining > 0 }.associate {
                it.id to bazaarRelistAdvice(it, quotes[it.product], fees ?: BazaarFees(), if (fees == null) BazaarFreshness.UNAVAILABLE else freshness, threshold)
            },
            portfolioWarnings = portfolio.warnings,
            names = names,
            dashboardRunning = BazaarDashboard.running(),
            theme = mapOf("bg" to MenuTheme.PANEL, "panel" to MenuTheme.PANEL, "card" to MenuTheme.CARD, "line" to MenuTheme.BORDER,
                "text" to MenuTheme.TEXT, "muted" to MenuTheme.MUTED, "accent" to MenuTheme.ACCENT, "warning" to MenuTheme.YELLOW, "negative" to MenuTheme.RED),
            reduceMotion = ThemeConfig.animationMode in setOf(GuiMotion.REDUCED, GuiMotion.OFF),
        )
    }

    private fun islandName(island: String) = island.substringBefore(':').lowercase(Locale.ROOT).split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Guidance follows the flip the player chose: first the plan, then each observed step of its position. */
    private fun guidance(steps: Map<String, BazaarUiStep>, names: Map<String, String>): String? {
        val product = guidedProduct ?: return null
        val name = names[product] ?: bazaarFallbackName(product)
        val matches = ledger.positions.filter { it.product == product && (it.id == guidedPosition || guidedPosition == null && it.openedAt >= guidedAt) }
        return when {
            matches.size == 1 -> { guidedPosition = matches.single().id; "$name · ${steps[matches.single().id]?.text}" }
            matches.size > 1 -> "Several $name positions match; choose one in Positions"
            else -> guidedPlan
        }
    }

    private fun uiTrade(row: BazaarRealization) = BazaarUiTrade(row.id, row.product, row.at, row.quantity, row.netProfit, row.cost, row.fees, row.eligible, row.evidence.name)

    private fun uiSessions(scope: BazaarContext?): List<BazaarUiSession> {
        val bySession = ledger.realizations.groupBy { it.sessionId }
        return journal.archive.sessions.filter { it.context == scope }.takeLast(100).map { session ->
            val rows = bySession[session.id].orEmpty()
            var running = 0.0
            val stride = maxOf(1, rows.size / 255)
            val series = rows.mapIndexedNotNull { index, row ->
                running += row.netProfit ?: 0.0
                if (index % stride == 0 || index == rows.lastIndex) BazaarUiPoint(row.at, running) else null
            }.takeLast(256)
            BazaarUiSession(session.id, islandName(session.island), session.startedAt, session.endedAt, rows.sumOf { it.netProfit ?: 0.0 },
                ledger.positions.count { it.sessionId == session.id && it.stage == BazaarStage.CLOSED }, series, rows.takeLast(256).map(::uiTrade))
        }
    }

    /** Review rows prefilled with the latest known values; one pass over the journal for all of them. */
    private fun uiPending(rows: List<BazaarPending>): List<BazaarUiPending> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.mapTo(hashSetOf()) { it.eventId }
        val events = journal.archive.events
        val sources = events.filter { it.id in ids }.associateBy { it.id }
        val corrections = events.filter { it.kind == BazaarEventKind.CORRECTION && it.replacesId in ids }.associateBy { it.replacesId }
        val settlements = events.filter { it.kind == BazaarEventKind.SELL_FILL && it.replacesId in ids && it.evidence == BazaarEvidence.CORROBORATED }
            .associateBy { it.replacesId }
        return rows.map { row ->
            val event = sources[row.eventId]
            val correction = corrections[row.eventId]
            val settlement = settlements[row.eventId]
            BazaarUiPending(row.eventId, row.reason,
                kind = event?.kind?.takeUnless { it == BazaarEventKind.OBSERVATION }?.name,
                product = event?.product?.takeUnless { it == "BAZAAR" || it == "UNKNOWN" },
                quantity = correction?.quantity?.takeIf { it > 0 } ?: event?.quantity?.takeIf { it > 0 },
                unitPrice = correction?.unitPrice ?: event?.unitPrice,
                coins = correction?.coins ?: settlement?.coins ?: event?.coins,
                fee = correction?.fee ?: settlement?.fee ?: event?.fee)
        }
    }

    // ---- Actions ----

    override fun action(name: String, fields: Map<String, String>): CompletableFuture<BazaarUiResult> {
        val result = CompletableFuture<BazaarUiResult>()
        val action = BazaarAction.of(name)
        if (action == null || fields.size > 20 || fields.any { it.key.length > 64 || it.value.length > 2048 || it.value.any(Char::isISOControl) }) {
            result.complete(BazaarUiResult("Unsupported or malformed local action")); return result
        }
        val account = selectedOwner()
        MC.instance.execute {
            when {
                account != selectedOwner() -> result.complete(BazaarUiResult("Minecraft account changed"))
                journal.owner != account -> { switchOwner(account); result.complete(BazaarUiResult("Loading the selected account; try again in a moment")) }
                journal.deleting -> result.complete(BazaarUiResult("Your history is being deleted; try again in a moment"))
                else -> runCatching { perform(action, fields, result) }
                    .onFailure { result.complete(BazaarUiResult("That didn't work. Check the values, your connection and whether the record still exists")) }
            }
        }
        return result
    }

    private fun perform(action: BazaarAction, fields: Map<String, String>, result: CompletableFuture<BazaarUiResult>) {
        lastInteractionAt = System.currentTimeMillis()
        fun complete(text: String) { refreshView(); result.complete(BazaarUiResult(text)) }
        when (action) {
            BazaarAction.TOGGLE -> { toggle(); complete(view.status) }
            BazaarAction.REFRESH -> complete(if (lifecycle.active == null) "Start flipping to load market data" else market.requestRefresh(System.currentTimeMillis()))
            BazaarAction.GUIDE -> complete(guide(fields))
            BazaarAction.OPEN_ORDERS -> complete(if (liveContext() == null) "Join SkyBlock to open your Bazaar orders" else { openBazaarOrders(); "Opening Manage Orders to sync fills" })
            BazaarAction.SETUP -> { BazaarConfig.wizardDone = true; persistPreferences(); complete("Setup saved") }
            BazaarAction.PREFERENCE -> {
                val key = requireNotNull(fields["key"])
                val value = requireNotNull(fields["value"])
                if (key == "webhookEnabled") community.enableWebhook(value.toBooleanStrict()) else changeBazaarPreference(key, value)
                persistPreferences(); analyse(); updateRelay()
                complete("Saved")
            }
            BazaarAction.WEBHOOK -> {
                val url = fields["url"].orEmpty().trim()
                if (url.isNotEmpty() && !validBazaarWebhook(url)) { complete("That isn't a Discord webhook URL"); return }
                community.webhook(url)
                complete(if (url.isEmpty()) "Webhook removed" else "Webhook saved on this device; turn on webhook delivery to use it")
            }
            BazaarAction.CONFIRM, BazaarAction.CORRECT -> complete(try { correct(fields) } catch (error: IllegalArgumentException) {
                error.message?.takeIf { it != "Failed requirement." && it.length <= 200 && it.none(Char::isISOControl) }
                    ?: "These values do not match the record. Check the amounts."
            })
            BazaarAction.EXPORT -> export(fields["native"] == "true", result)
            BazaarAction.DELETE_SESSION -> {
                val id = requireNotNull(fields["session"])
                require(lifecycle.active?.id != id)
                journal.deleteSession(id); analyse()
                complete("Local replay deleted; its trades and any published link are kept")
            }
            BazaarAction.DELETE_HISTORY -> deleteHistory(fields, result)
            BazaarAction.PUBLISH -> publish(fields, result)
            BazaarAction.REVOKE, BazaarAction.DELETE_PUBLICATION -> {
                val id = requireNotNull(fields["id"])
                require(community.state.publications.any { it.id == id && it.player == journal.owner })
                val remove = action == BazaarAction.DELETE_PUBLICATION
                if (remove) require(fields["confirm"] == "true")
                community.request("/community/bazaar/replays/${if (remove) "delete" else "revoke"}", { it.addProperty("id", id) }).whenComplete { _, failure ->
                    if (failure == null) { community.revoked(id); complete(if (remove) "Published replay deleted" else "Public link revoked") }
                    else complete("The server didn't confirm; the publication is unchanged")
                }
            }
            BazaarAction.SESSION_SUMMARY, BazaarAction.WEBHOOK_SUMMARY -> summary(action, fields, result)
            BazaarAction.SETTINGS_SYNC -> preferenceSync.setEnabled(fields["enabled"]?.toBooleanStrict() == true).whenComplete { response, failure ->
                result.complete(if (failure == null) response else BazaarUiResult("Preference sync unavailable; local settings kept"))
            }
            BazaarAction.LEADERBOARD -> { navigateMenu(LeaderboardScreen(initialMetric = "bazaar_profit")); complete("Opened Bazaar leaderboards") }
            BazaarAction.SHARING -> { navigateMenu(CommunityScreen()); complete("Opened sharing settings") }
        }
    }

    private fun guide(fields: Map<String, String>): String {
        val positionId = fields["position"]
        if (positionId != null) {
            val position = ledger.positions.find { it.id == positionId } ?: return "That position no longer exists"
            guidedProduct = position.product; guidedPosition = position.id; guidedAt = position.openedAt; guidedPlan = null
            return "Following ${BazaarNames.name(position.product)}"
        }
        val opportunity = opportunities.find { it.product == fields["product"] }
        val snapshot = market.snapshot
        val session = lifecycle.active
        val affordable = purse?.let { opportunity != null && opportunity.capital <= minOf(it, BazaarConfig.maximumCapital.toDouble()) } == true
        if (opportunity == null || session == null || session.context != liveContext() || session.island != island() ||
            !market.freshness().scannable || snapshot?.observedAt != opportunity.observedAt || !affordable) return "Market moved; refresh before opening this flip"
        val name = bazaarGuideName(opportunity.product) ?: return "Item names are still loading; try again"
        openGuidedBazaar(name, opportunity.quantity)
        guidedProduct = opportunity.product; guidedPosition = null; guidedAt = System.currentTimeMillis()
        guidedPlan = "$name · place a buy order for ${opportunity.quantity} at ${bazaarCoins(opportunity.buyPrice)} each (amount copied)"
        return "${opportunity.quantity} copied · place the buy order yourself"
    }

    /** One explicit Guide click opens the Bazaar search; the quantity goes to the clipboard, never into a command. */
    private fun openGuidedBazaar(itemName: String, quantity: Long) {
        val command = requireNotNull(bazaarGuideCommand(itemName, quantity))
        check(MC.instance.connection != null)
        MC.instance.keyboardHandler.clipboard = quantity.toString()
        if (MC.instance.screen is BazaarScreen) navigateMenu(null)
        PlayerUtils.sendCommand(command, false)
        MC.instance.gui.setOverlayMessage(Component.literal("$quantity copied"), false)
    }

    private fun openBazaarOrders() {
        check(MC.instance.connection != null)
        if (MC.instance.screen is BazaarScreen) navigateMenu(null)
        PlayerUtils.sendCommand("managebazaarorders", false)
    }

    private fun correct(fields: Map<String, String>): String {
        require(journal.writable && community.loaded) { "Your history isn't ready. Try again after it finishes loading." }
        val archive = journal.archive
        val eventId = ledger.realizations.find { it.id == fields["id"] }?.sourceEventId ?: fields["id"]
        val original = archive.events.firstOrNull { it.id == eventId && it.context.account == journal.owner }
            ?: throw IllegalArgumentException("This record changed. Reopen Positions or History.")
        if (fields["ignore"] == "true") { community.ignore(original.id); return "Dismissed" }
        val classification = original.kind == BazaarEventKind.OBSERVATION
        val id = if (classification) bazaarFingerprint("personal-classification|${original.context.key}|${original.id}") else bazaarRandomId()
        if (classification && archive.events.any { it.id == id }) { community.ignore(original.id); return "This personal record is already saved." }
        val previous = archive.events.lastOrNull { it.kind == BazaarEventKind.CORRECTION && it.replacesId == original.id }
        val settled = archive.events.lastOrNull { it.kind == BazaarEventKind.SELL_FILL && it.replacesId == original.id && it.evidence == BazaarEvidence.CORROBORATED }
        val effective = original.copy(quantity = previous?.quantity?.takeIf { it > 0 } ?: original.quantity,
            unitPrice = previous?.unitPrice ?: original.unitPrice, coins = previous?.coins ?: settled?.coins ?: original.coins,
            fee = previous?.fee ?: settled?.fee ?: original.fee)
        val product = if (original.product !in setOf("BAZAAR", "UNKNOWN")) original.product else fields["product"]?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            val key = value.uppercase(Locale.ROOT)
            market.snapshot?.products?.firstOrNull { it.id == key }?.id ?: bazaarProductId(value)
                ?: key.takeIf { validBazaarProductId(it) && bazaarGuideName(it) != null }
        }
        val candidate = bazaarCorrection(effective, fields, product, id, System.currentTimeMillis())
        if (!classification && candidate.quantity == effective.quantity && candidate.unitPrice == effective.unitPrice &&
            candidate.coins == effective.coins && candidate.fee == effective.fee && fields["note"].isNullOrBlank()) return "No changes to save"
        if (classification) require(community.state.ignored.size < 10000) { "Too many dismissed records. Export your history before clearing old records." }
        val before = if (analysedContext == original.context && analysedArchive === archive) ledger else reconcileBazaar(archive, original.context)
        val next = journal.preview(candidate)
        val preview = try { reconcileBazaar(next, original.context) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("These amounts do not match this order. Check quantity, price and total.")
        }
        // A personal correction can never stay public: retract every contribution it changes.
        val retained = preview.realizations.associateBy { it.id }
        val removed = before.realizations.filter { it.eligible && retained[it.id]?.let { value -> value.eligible && value.netProfit == it.netProfit } != true }
            .mapTo(hashSetOf()) { it.id }
        community.remove(original.context, removed)
        record(listOf(candidate))
        if (classification) community.ignore(original.id)
        return when {
            candidate.kind in setOf(BazaarEventKind.BUY_ORDER, BazaarEventKind.SELL_ORDER) -> "Saved. Open Manage Orders to sync its fills"
            candidate.kind == BazaarEventKind.SELL_FILL && preview.realizations.any { it.sourceEventId == candidate.id && it.cost == null } -> "Sale saved; its purchase cost is still missing"
            else -> "Saved to your personal history"
        }
    }

    private fun export(native: Boolean, result: CompletableFuture<BazaarUiResult>) {
        journal.export().whenComplete { data, failure ->
            if (failure != null) { result.complete(BazaarUiResult("Export failed; your history is unchanged")); return@whenComplete }
            if (!native) { result.complete(BazaarUiResult("Export ready", data, "sky-myce-bazaar.json")); return@whenComplete }
            Scheduling.schedule(0.seconds) {
                runCatching {
                    val folder = SkyMyce.configPath.resolve("bazaar-exports")
                    Files.createDirectories(folder)
                    Files.createTempFile(folder, "bazaar-", ".json").also { Files.writeString(it, data) }
                }.fold({ result.complete(BazaarUiResult("Exported to ${it.toAbsolutePath()}")) }, { result.complete(BazaarUiResult("Export failed; your history is unchanged")) })
            }
        }
    }

    private fun deleteHistory(fields: Map<String, String>, result: CompletableFuture<BazaarUiResult>) {
        require(fields["confirm"] == "DELETE")
        val account = journal.owner
        stop("History deleted")
        community.clearQueue(account)
        journal.delete { outcome ->
            outcome.onSuccess { selected ->
                if (selected) { ledger = BazaarLedger(); analysedArchive = null; community.clearQueue(account); observer.reset(); analyse() }
                result.complete(BazaarUiResult("Trading history deleted; sharing choices and published replays are unchanged"))
            }.onFailure {
                status = "Deleting your history failed; files were kept. Restart and try again"
                refreshView()
                result.complete(BazaarUiResult(status))
            }
        }
    }

    private fun publish(fields: Map<String, String>, result: CompletableFuture<BazaarUiResult>) {
        val session = journal.archive.sessions.first { it.id == fields["session"] && it.context.account == journal.owner }
        require(session.endedAt != null)
        val options = BazaarReplayOptions(fields["detail"] == "true", fields["hideItems"] != "false", fields["hideTransactions"] != "false",
            fields["hideValues"] == "true", fields["hideIdentity"] != "false" || !CommunitySharing.showPublicName())
        val payload = bazaarPublicationPayload(journal.archive, session.id, options, fields["listed"] == "true")
        val account = CommunityClient.identity?.get("accountId")?.asString ?: error("Connect your linked account first")
        val player = journal.owner
        community.request("/community/bazaar/replays/publish", { it.addProperty("confirm", true); it.add("replay", payload) }).whenComplete { response, failure ->
            if (failure != null) {
                community.refreshPublications()
                result.complete(BazaarUiResult("Publishing may or may not have finished; check your published replays before retrying"))
                return@whenComplete
            }
            runCatching {
                val path = response.text("path", 240) ?: error("Invalid publication response")
                require(path.matches(Regex("/community/bazaar/replays/[A-Za-z0-9_-]{43}")))
                val url = URI(CommunityClient.configuration.getProperty("backend")).resolve(path).toString()
                community.remember(BazaarPublication(player, account, requireNotNull(response.text("id", 32)), session.id, url, response.text("visibility", 16) ?: "unlisted"))
                refreshView()
                result.complete(BazaarUiResult("Replay published", url = url))
            }.onFailure {
                community.refreshPublications()
                result.complete(BazaarUiResult("The server's reply was unexpected; check your published replays"))
            }
        }
    }

    private fun summary(action: BazaarAction, fields: Map<String, String>, result: CompletableFuture<BazaarUiResult>) {
        require(fields["confirm"] == "true")
        val session = journal.archive.sessions.first { it.id == fields["session"] && it.context.account == journal.owner }
        val duration = (requireNotNull(session.endedAt) - session.startedAt).coerceAtLeast(1)
        val stats = bazaarStats(reconcileBazaar(journal.archive, session.context).let { all -> all.copy(realizations = all.realizations.filter { it.sessionId == session.id }) })
        if (action == BazaarAction.SESSION_SUMMARY) {
            community.request("/community/bazaar/session-summary", { body ->
                body.addProperty("confirm", true)
                body.add("summary", JsonObject().apply {
                    addProperty("durationMs", duration); addProperty("realizedProfit", stats.realizedProfit); addProperty("completedFlips", stats.completedFlips)
                })
            }).whenComplete { _, failure -> refreshView(); result.complete(BazaarUiResult(if (failure == null) "Summary sent to your linked account" else "Summary not sent; try again later")) }
            return
        }
        val delivery = community.state
        require(delivery.webhookEnabled && delivery.webhook.isNotEmpty())
        val owner = journal.owner
        alerts.sendReport(delivery.webhook, session.id, bazaarWebhookReport(duration, stats.realizedProfit, stats.completedFlips),
            { journal.owner == owner && community.state.webhookEnabled && community.state.webhook == delivery.webhook }) { sent ->
            result.complete(BazaarUiResult(if (sent) "Session summary sent to your webhook" else "Webhook delivery failed or was sent moments ago"))
        }
    }
}
