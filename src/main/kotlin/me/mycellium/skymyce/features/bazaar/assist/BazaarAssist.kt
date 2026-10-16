package me.mycellium.skymyce.features.bazaar.assist

import me.mycellium.mixin.BazaarAssistSignAccessor
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.features.bazaar.BazaarFreshness
import me.mycellium.skymyce.features.bazaar.BazaarNames
import me.mycellium.skymyce.features.bazaar.BazaarOpportunity
import me.mycellium.skymyce.features.bazaar.BazaarPosition
import me.mycellium.skymyce.features.bazaar.BazaarStage
import me.mycellium.skymyce.features.bazaar.BazaarTrading
import me.mycellium.skymyce.features.bazaar.bazaarGuideName
import me.mycellium.skymyce.features.bazaar.bazaarSignedCoins
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.world.entity.player.Inventory
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId

enum class AssistStatus(val label: String) {
    OFF("Off"), ACTIVE("Active"), WAITING("Waiting"), DONE("Complete"), PAUSED("Paused"), ERROR("Needs attention")
}

enum class AssistTone { INFO, SUCCESS, WARNING, ERROR }
internal data class AssistNotice(val text: String, val tone: AssistTone, val at: Long, val until: Long)
internal data class AssistPop(val id: String, val amount: Double, val at: Long)

/**
 * Guides one flip from the existing recommendations and order tracking. It reads screens and highlights; the player
 * clicks every slot, confirms every order and submits every sign. Auto Command sends the shown command only on its
 * own key press, and Auto Sign only pre-types a value. Both are opt-in.
 */
object BazaarAssist : SkyMyceModule() {
    private data class Mission(val target: AssistMission, val startedAt: Long, val positionId: String? = null)

    private var mission: Mission? = null
    var status = AssistStatus.OFF
        private set
    var step: AssistStep? = null
        private set
    var phase: AssistPhase? = null
        private set
    var warning: String? = null
        private set
    internal var notice: AssistNotice? = null
        private set
    internal val pops = ArrayDeque<AssistPop>()
    /** Changes whenever the panel content changes; the HUD rebuilds only then. */
    var revision = 0L
        private set
    var stepChangedAt = 0L
        private set

    private lateinit var startKey: KeyMapping
    private lateinit var stopKey: KeyMapping
    private lateinit var commandKey: KeyMapping
    private var screen = AssistScreen()
    private var screenDirty = true
    private var observedScreen: Any? = null
    private var ticks = 0
    private var filledSign = 0
    private var lastCommand: String? = null
    private var lastCommandAt = 0L
    private var pendingPickUntil = 0L
    private var runStartedAt = 0L
    private val runPositions = linkedSetOf<String>()
    private val seenRealizations = hashSetOf<String>()

    val active get() = status != AssistStatus.OFF
    internal val product get() = mission?.target?.product
    internal val productName get() = mission?.target?.name

    override fun init() {
        startKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Start Bazaar Assistant", GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC))
        stopKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Stop Bazaar Assistant", GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC))
        commandKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Send Bazaar Assistant command", GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC))
        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled()) onTick() }
        ScreenEvents.AFTER_INIT.register { _, opened, _, _ ->
            screenDirty = true
            when (opened) {
                is ChatScreen -> {
                    ScreenKeyboardEvents.allowKeyPress(opened).register { _, key -> !(key.key() == GLFW.GLFW_KEY_TAB && tabFill(opened)) }
                    ScreenEvents.afterExtract(opened).register { _, graphics, _, _, _ -> drawGhost(opened, graphics) }
                }
                is AbstractSignEditScreen -> ScreenEvents.afterExtract(opened).register { _, graphics, _, _, partial ->
                    drawSignHelper(opened, graphics); BazaarAssistHud.drawOverScreen(graphics, partial)
                }
                is AbstractContainerScreen<*> -> ScreenEvents.afterExtract(opened).register { _, graphics, _, _, partial ->
                    BazaarAssistHud.drawOverScreen(graphics, partial)
                }
            }
        }
    }

    override fun onDisable() = stop()

    // ---- Lifecycle ----

    /** Guide [opportunity]; called by the terminal's Guide button and by the start key. */
    fun begin(opportunity: BazaarOpportunity, name: String = bazaarGuideName(opportunity.product) ?: BazaarNames.name(opportunity.product)) {
        val now = System.currentTimeMillis()
        val target = AssistMission(opportunity.product, name, opportunity.quantity, opportunity.buyPrice, opportunity.sellPrice)
        launch(Mission(target, now), "Guiding ${target.name}")
    }

    /** Follow an existing position, for example one waiting to be claimed or sold. */
    private fun follow(position: BazaarPosition) {
        val name = bazaarGuideName(position.product) ?: BazaarNames.name(position.product)
        val target = AssistMission(position.product, name, maxOf(position.remaining, position.acquired - position.sold, 1), null, null)
        launch(Mission(target, position.openedAt, position.id), "Following ${target.name}")
    }

    private fun launch(next: Mission, message: String) {
        if (!Settings.enabled) { say("Bazaar Assistant is turned off in settings", AssistTone.WARNING); return }
        val now = System.currentTimeMillis()
        mission = next; pendingPickUntil = 0
        if (runStartedAt == 0L) runStartedAt = now
        seenRealizations.clear()
        BazaarTrading.assistLedger.realizations.filter { it.product == next.target.product }.mapTo(seenRealizations) { it.id }
        status = AssistStatus.ACTIVE; filledSign = 0; screenDirty = true
        say(message, AssistTone.INFO)
        update(now)
    }

    /** Start key: resume, follow a position that needs you, or take the best current recommendation. */
    fun start() {
        if (!Settings.enabled) { say("Bazaar Assistant is turned off in settings", AssistTone.WARNING); return }
        mission?.takeIf { status != AssistStatus.DONE }?.let { status = AssistStatus.ACTIVE; say("Resumed", AssistTone.INFO); update(System.currentTimeMillis()); return }
        val ledger = BazaarTrading.assistLedger
        val waiting = ledger.positions.filter { it.pendingItems > 0 || it.stage == BazaarStage.READY }.maxByOrNull { it.lastAt }
        if (waiting != null) { follow(waiting); return }
        val best = BazaarTrading.assistOpportunities.firstOrNull()
        if (best != null) { begin(best); return }
        BazaarTrading.assistStartSession()
        pendingPickUntil = System.currentTimeMillis() + 60_000
        status = AssistStatus.WAITING; mission = null; step = null; phase = null
        say("Loading recommendations…", AssistTone.INFO)
        bump()
    }

    fun stop() {
        if (!active && mission == null) return
        mission = null; step = null; phase = null; warning = null; pendingPickUntil = 0
        status = AssistStatus.OFF; filledSign = 0; runStartedAt = 0; runPositions.clear(); pops.clear()
        bump()
    }

    private fun say(text: String, tone: AssistTone, millis: Long = 4000) {
        val now = System.currentTimeMillis()
        notice = AssistNotice(text, tone, now, now + millis)
        bump()
    }

    private fun bump() { revision++ }

    // ---- Per tick ----

    private fun onTick() {
        while (startKey.consumeClick()) start()
        while (stopKey.consumeClick()) { stop(); say("Stopped", AssistTone.INFO) }
        while (commandKey.consumeClick()) sendCommand()
        val now = System.currentTimeMillis()
        notice?.takeIf { now > it.until }?.let { notice = null; bump() }
        while (pops.firstOrNull()?.let { now - it.at > 3500 } == true) { pops.removeFirst(); bump() }
        if (pendingPickUntil > 0) {
            val best = BazaarTrading.assistOpportunities.firstOrNull()
            when {
                best != null -> begin(best)
                now > pendingPickUntil -> { pendingPickUntil = 0; status = AssistStatus.ERROR; say("No flips match your filters right now", AssistTone.WARNING) }
            }
        }
        if (mission == null) return
        if (++ticks % 10 == 0 || MC.screen !== observedScreen) screenDirty = true
        update(now)
        autoSign()
    }

    @Subscription fun containerOpened(@Suppress("UNUSED_PARAMETER") event: ContainerInitializedEvent) { screenDirty = true }
    @Subscription fun containerChanged(event: InventoryChangeEvent) { if (!event.isInPlayerInventory) screenDirty = true }
    @Subscription(ServerDisconnectEvent::class) fun disconnected() {
        if (mission != null) { status = AssistStatus.PAUSED; say("Disconnected; your flip resumes when you're back", AssistTone.WARNING) }
    }

    @Subscription(receiveCancelled = true)
    fun chat(event: ChatReceivedEvent.Pre) {
        val target = mission?.target ?: return
        val text = event.text.trim()
        when {
            text.startsWith("[Bazaar] Buy Order Setup!") && text.contains(target.name, true) -> say("Buy order placed", AssistTone.SUCCESS)
            text.startsWith("[Bazaar] Sell Offer Setup!") && text.contains(target.name, true) -> say("Sell offer listed", AssistTone.SUCCESS)
            text.startsWith("[Bazaar] Your Buy Order for") && text.contains(target.name, true) && text.endsWith("was filled!") ->
                say("Buy order filled: claim your items", AssistTone.SUCCESS)
            text.startsWith("[Bazaar] Your Sell Offer for") && text.contains(target.name, true) && text.endsWith("was filled!") ->
                say("Sold: claim your coins", AssistTone.SUCCESS)
            text.contains("Cookie Buff", true) && (text.contains("need", true) || text.contains("require", true)) ->
                say("No Booster Cookie: use the Bazaar NPC in the Hub instead", AssistTone.ERROR, 8000)
        }
    }

    private fun update(now: Long) {
        val current = mission ?: return
        if (screenDirty) { screen = snapshot(); screenDirty = false; observedScreen = MC.screen }
        if (!LocationAPI.isOnSkyBlock) {
            if (status != AssistStatus.PAUSED) { status = AssistStatus.PAUSED; bump() }
            return
        }
        val ledger = BazaarTrading.assistLedger
        val position = current.positionId?.let { id -> ledger.positions.firstOrNull { it.id == id } }
            ?: ledger.positions.filter { it.product == current.target.product && it.openedAt >= current.startedAt - 10_000 }.singleOrNull()
        if (position != null && current.positionId != position.id) { mission = current.copy(positionId = position.id); runPositions += position.id }
        val quote = BazaarTrading.assistQuote(current.target.product)
        val live = BazaarTrading.assistFreshness() == BazaarFreshness.FRESH
        val sellPrice = quote?.sellOfferPrice?.takeIf { live }?.let { (kotlin.math.ceil(it * 10 - 1e-7) - 1) / 10 } ?: current.target.sellPrice
        val target = current.target.copy(sellPrice = sellPrice)
        val nextPhase = assistPhase(position, ledger.orders)
        val nextStep = assistStep(target, nextPhase, screen, Settings.priceMode, position?.remaining?.takeIf { it > 0 } ?: target.quantity)
        val nextWarning = when {
            nextPhase != AssistPhase.PLACE_BUY -> null
            !BazaarTrading.active -> "Start flipping for live prices before placing the order"
            BazaarTrading.assistFreshness() != BazaarFreshness.FRESH -> "Market data is not live; prices may have moved"
            BazaarTrading.assistOpportunities.none { it.product == target.product } -> "This flip no longer ranks; consider another"
            else -> null
        }
        val nextStatus = when {
            nextPhase == AssistPhase.DONE -> AssistStatus.DONE
            nextStep.kind == AssistKind.WAIT -> AssistStatus.WAITING
            nextStep.kind == AssistKind.CLICK && nextStep.slot == null && screen.kind == AssistScreenKind.CONTAINER -> AssistStatus.ERROR
            else -> AssistStatus.ACTIVE
        }
        if (nextStep != step || nextPhase != phase || nextWarning != warning || nextStatus != status) {
            if (nextStep.kind != step?.kind || nextStep.label != step?.label || nextStep.slot != step?.slot) stepChangedAt = now
            if (nextPhase == AssistPhase.DONE && phase != AssistPhase.DONE) say("Flip complete", AssistTone.SUCCESS)
            step = nextStep; phase = nextPhase; warning = nextWarning; status = nextStatus
            bump()
        }
        position?.let { realized(it.id, now) }
    }

    /** Realized results come from the existing ledger; each sale portion pops once. */
    private fun realized(positionId: String, now: Long) {
        val fresh = BazaarTrading.assistLedger.realizations.filter { it.positionId == positionId && it.netProfit != null && seenRealizations.add(it.id) }
        if (fresh.isEmpty() || !Settings.profitNotices) return
        pops.addLast(AssistPop(fresh.first().id, fresh.sumOf { it.netProfit ?: 0.0 }, now))
        while (pops.size > 3) pops.removeFirst()
        bump()
    }

    /** Realized profit of positions guided since the assistant started, and its hourly rate once meaningful. */
    internal fun runProfit(): Pair<Double, Double?>? {
        if (runPositions.isEmpty() || runStartedAt == 0L) return null
        val profit = BazaarTrading.assistLedger.positions.filter { it.id in runPositions }.sumOf { it.realizedProfit }
        if (profit == 0.0) return null
        val hours = (System.currentTimeMillis() - runStartedAt) / 3_600_000.0
        return profit to (profit / hours).takeIf { hours >= 5.0 / 60 }
    }

    private fun snapshot(): AssistScreen = when (val open = MC.screen) {
        null, is ChatScreen -> AssistScreen(AssistScreenKind.NONE)
        is AbstractSignEditScreen -> AssistScreen(AssistScreenKind.SIGN,
            signLines = (open as BazaarAssistSignAccessor).`skymyce$getMessages`()?.map { it.orEmpty() }.orEmpty(), id = System.identityHashCode(open))
        is AbstractContainerScreen<*> -> AssistScreen(AssistScreenKind.CONTAINER, open.title.string.replace(colorCode, ""),
            open.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }.take(90).map { slot ->
                AssistSlot(slot.index, slot.item.hoverName.string.replace(colorCode, "").trim(), slot.item.getSkyBlockId()?.bazaarId?.uppercase())
            }, id = System.identityHashCode(open))
        else -> AssistScreen(AssistScreenKind.OTHER, id = System.identityHashCode(open))
    }
    private val colorCode = Regex("§.")

    /** Highlight only the current container's own target, never a remembered index from another screen. */
    internal fun highlightTarget(container: AbstractContainerScreen<*>): Int? {
        if (!Settings.enabled || status == AssistStatus.OFF || status == AssistStatus.PAUSED) return null
        val current = step?.takeIf { it.kind == AssistKind.CLICK } ?: return null
        if (screen.id != System.identityHashCode(container)) return null
        return current.slot
    }

    // ---- Command helpers ----

    private fun commandStep() = step?.takeIf { active && status != AssistStatus.PAUSED && it.command != null && it.kind in setOf(AssistKind.COMMAND, AssistKind.CLOSE) }

    /** Auto Command: one key press sends the one command the current step shows, never while a menu is open. */
    private fun sendCommand() {
        val current = commandStep()
        if (!Settings.autoCommand) { say("Turn on Auto Command in the Bazaar terminal to use this key", AssistTone.WARNING); return }
        if (current == null) { say("No command to send for this step", AssistTone.INFO); return }
        if (MC.screen != null) { say("Close the menu first", AssistTone.WARNING); return }
        val command = current.command ?: return
        if (MC.instance.connection == null || MC.instance.player == null || !LocationAPI.isOnSkyBlock) return
        val now = System.currentTimeMillis()
        if (command == lastCommand && now - lastCommandAt < 3000) { say("Already sent; waiting for the menu", AssistTone.INFO); return }
        lastCommand = command; lastCommandAt = now
        MC.instance.connection?.sendCommand(command)
        say("Sent /$command", AssistTone.SUCCESS, 2500)
    }

    /** Tab completes the expected command only while the typed text is a prefix of it; otherwise vanilla Tab runs. */
    private fun tabFill(chat: ChatScreen): Boolean {
        if (!Settings.tabFill || !LocationAPI.isOnSkyBlock) return false
        val command = commandStep()?.command ?: return false
        val input = (chat as IrcChatScreenAccessor).`skymyce$getInput`()
        if (assistGhost(input.value, command) == null) return false
        input.value = "/$command"
        input.moveCursorToEnd(false)
        return true
    }

    private fun drawGhost(chat: ChatScreen, graphics: GuiGraphicsExtractor) {
        if (!Settings.ghostText || !LocationAPI.isOnSkyBlock) return
        val command = commandStep()?.command ?: return
        val input = (chat as IrcChatScreenAccessor).`skymyce$getInput`()
        val typed = input.value
        val ghost = assistGhost(typed, command) ?: return
        val font = MC.instance.font
        val typedWidth = font.width(typed)
        val hint = if (Settings.tabFill) "  Tab" else ""
        if (typedWidth + font.width(ghost + hint) > input.innerWidth) return
        // Our ghost takes the inline slot; vanilla's suggestion list stays open and keeps its arrow keys.
        input.setSuggestion(null)
        val x = input.x + typedWidth
        val y = input.y // The chat box is unbordered, so its text sits at the top edge.
        graphics.text(font, ghost, x, y, 0x80B8C2CC.toInt(), false)
        if (hint.isNotEmpty()) graphics.text(font, hint, x + font.width(ghost), y, 0x80FFC857.toInt(), false)
    }

    // ---- Signs ----

    private fun signStep(signScreen: AbstractSignEditScreen): AssistStep? {
        val current = step?.takeIf { active && it.kind == AssistKind.SIGN && it.value != null } ?: return null
        if (screen.id != System.identityHashCode(signScreen)) return null
        val view = assistView(screen)
        val matches = when (current.sign) {
            AssistSign.SEARCH -> view == AssistView.SIGN_SEARCH
            AssistSign.AMOUNT -> view == AssistView.SIGN_AMOUNT
            AssistSign.PRICE -> view == AssistView.SIGN_PRICE
            null -> false
        }
        return current.takeIf { matches }
    }

    /** Auto Sign types the expected value once per sign, into an empty first line; the player still presses Enter. */
    private fun autoSign() {
        if (!Settings.autoSign) return
        val signScreen = MC.screen as? AbstractSignEditScreen ?: return
        val id = System.identityHashCode(signScreen)
        if (filledSign == id) return
        val current = signStep(signScreen) ?: return
        val value = current.value ?: return
        val accessor = signScreen as BazaarAssistSignAccessor
        filledSign = id
        if (accessor.`skymyce$getLine`() != 0 || accessor.`skymyce$getMessages`()?.firstOrNull()?.isNotEmpty() == true) return
        val field = accessor.`skymyce$getSignField`() ?: return
        field.selectAll()
        field.insertText(value)
        say("Typed $value: check it, then press Enter", AssistTone.INFO, 3000)
    }

    private fun drawSignHelper(signScreen: AbstractSignEditScreen, graphics: GuiGraphicsExtractor) {
        if (!Settings.signHelper) return
        val current = signStep(signScreen) ?: return
        val expected = current.value ?: return
        val typed = (signScreen as BazaarAssistSignAccessor).`skymyce$getMessages`()?.firstOrNull().orEmpty()
        val font = MC.instance.font
        val center = signScreen.width / 2
        val what = when (current.sign) { AssistSign.SEARCH -> "Search"; AssistSign.AMOUNT -> "Amount"; else -> "Price" }
        graphics.centeredText(font, "Bazaar Assistant · $what: $expected", center, 10, 0xFFFFC857.toInt())
        val (line, color) = when (assistSignCheck(typed, expected)) {
            AssistSignCheck.EMPTY -> "Type it on the first line" to 0xFFB8C2CC.toInt()
            AssistSignCheck.PARTIAL -> "Keep typing…" to 0xFFB8C2CC.toInt()
            AssistSignCheck.MATCH -> "$expected is right: press Enter" to 0xFF7CE38B.toInt()
            AssistSignCheck.MISMATCH -> "You typed ${typed.trim().take(24)}; it should be $expected" to 0xFFFF7A7A.toInt()
        }
        graphics.centeredText(font, line, center, 22, color)
    }

    fun commandKeyName(): String? = if (::commandKey.isInitialized && !commandKey.isUnbound) commandKey.translatedKeyMessage.string else null
    fun startKeyName(): String? = if (::startKey.isInitialized && !startKey.isUnbound) startKey.translatedKeyMessage.string else null
}
