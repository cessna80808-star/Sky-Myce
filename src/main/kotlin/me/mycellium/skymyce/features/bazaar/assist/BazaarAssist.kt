package me.mycellium.skymyce.features.bazaar.assist

import me.mycellium.mixin.BazaarAssistContainerAccessor
import me.mycellium.mixin.BazaarAssistSignAccessor
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.features.bazaar.BazaarFreshness
import me.mycellium.skymyce.features.bazaar.BazaarLedger
import me.mycellium.skymyce.features.bazaar.BazaarNames
import me.mycellium.skymyce.features.bazaar.BazaarOpportunity
import me.mycellium.skymyce.features.bazaar.BazaarPosition
import me.mycellium.skymyce.features.bazaar.BazaarProduct
import me.mycellium.skymyce.features.bazaar.BazaarStage
import me.mycellium.skymyce.features.bazaar.BazaarTrading
import me.mycellium.skymyce.features.bazaar.bazaarCompactCoins
import me.mycellium.skymyce.features.bazaar.bazaarGuideName
import me.mycellium.skymyce.features.bazaar.bazaarSignedCoins
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.input.KeyEvent
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

/** What the assistant reads from the existing Bazaar systems; one seam, so a test world can supply its own records. */
internal interface AssistSource {
    val ledger: BazaarLedger
    val opportunities: List<BazaarOpportunity>
    val trading: Boolean
    fun quote(product: String): BazaarProduct?
    fun freshness(): BazaarFreshness
    fun startSession()
}

internal object TradingAssistSource : AssistSource {
    override val ledger get() = BazaarTrading.assistLedger
    override val opportunities get() = BazaarTrading.assistOpportunities
    override val trading get() = BazaarTrading.active
    override fun quote(product: String) = BazaarTrading.assistQuote(product)
    override fun freshness() = BazaarTrading.assistFreshness()
    override fun startSession() = BazaarTrading.assistStartSession()
}

/**
 * Guides one flip from the existing recommendations and order tracking. It reads screens and highlights; the player
 * clicks every slot, confirms every order and submits every sign. Auto Command sends the shown command only on its
 * own key press, and Auto Sign only pre-types a value. Both are opt-in.
 */
object BazaarAssist : SkyMyceModule() {
    private data class Mission(val target: AssistMission, val startedAt: Long, val positionId: String? = null)
    private data class SlotClick(val screenId: Int, val x: Int, val y: Int, val at: Long)

    internal var source: AssistSource = TradingAssistSource
    /** Lets the in-game test world stand in for a SkyBlock island; never set by the mod itself. */
    internal var simulatedSkyBlock = false

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
    internal var pops = emptyList<AssistPopEvent>()
        private set
    internal var lastRun: AssistRunSummary? = null
        private set
    /** Changes whenever the panel content changes; the HUD rebuilds only then. */
    var revision = 0L
        private set
    var stepChangedAt = 0L
        private set
    /** How many slots on the open menu carry the current click target's name; more than one is never guessed between. */
    internal var targetMatches = 0
        private set

    internal lateinit var startKey: KeyMapping
        private set
    internal lateinit var stopKey: KeyMapping
        private set
    internal lateinit var commandKey: KeyMapping
        private set
    private var screen = AssistScreen()
    private var screenDirty = true
    private var observedScreen: Any? = null
    private var ticks = 0
    private var filledSign = 0
    private var lastCommand: String? = null
    private var lastCommandAt = 0L
    private var lastClick: SlotClick? = null
    private var pendingPickUntil = 0L
    private var runStartedAt = 0L
    private val runPositions = linkedSetOf<String>()
    private val seenRealizations = hashSetOf<String>()

    val active get() = status != AssistStatus.OFF
    internal val onSkyBlock get() = simulatedSkyBlock || LocationAPI.isOnSkyBlock
    internal val product get() = mission?.target?.product
    internal val productName get() = mission?.target?.name
    /** The command the current step shows, if any; shared by the panel, chat ghost text and Auto Command. */
    internal val shownCommand get() = step?.takeIf { active && status != AssistStatus.PAUSED && it.kind in setOf(AssistKind.COMMAND, AssistKind.CLOSE) }?.command

    override fun init() {
        startKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Start Bazaar Assistant", GLFW.GLFW_KEY_RIGHT_BRACKET, KeyMapping.Category.MISC))
        stopKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Stop Bazaar Assistant", GLFW.GLFW_KEY_LEFT_BRACKET, KeyMapping.Category.MISC))
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
                    drawSignHelper(opened, graphics); BazaarAssistHud.drawOverScreen(opened, graphics, partial)
                }
                is AbstractContainerScreen<*> -> {
                    ScreenEvents.afterExtract(opened).register { _, graphics, _, _, partial -> BazaarAssistHud.drawOverScreen(opened, graphics, partial) }
                    // Start, stop and send keys also work inside menus, unless a text field (such as a search box) has focus.
                    ScreenKeyboardEvents.allowKeyPress(opened).register { current, key -> current.focused is EditBox || !menuKey(key) }
                    // Passive: remember which slot was clicked, so realized profit can rise from it. Nothing is clicked for you.
                    ScreenMouseEvents.beforeMouseClick(opened).register { current, _ ->
                        val accessor = current as BazaarAssistContainerAccessor
                        val slot = accessor.`skymyce$getHoveredSlot`() ?: return@register
                        lastClick = SlotClick(System.identityHashCode(current), accessor.`skymyce$getLeftPos`() + slot.x + 8,
                            accessor.`skymyce$getTopPos`() + slot.y, System.currentTimeMillis())
                    }
                    BazaarAssistHud.attachDrag(opened)
                }
            }
        }
    }

    override fun onDisable() = stop()

    // ---- Lifecycle ----

    /** Guide [opportunity]; called by the terminal's Guide button and by the start key. */
    fun begin(opportunity: BazaarOpportunity, name: String = bazaarGuideName(opportunity.product) ?: BazaarNames.name(opportunity.product)) =
        guide(AssistMission(opportunity.product, name, opportunity.quantity, opportunity.buyPrice, opportunity.sellPrice))

    internal fun guide(target: AssistMission) = launch(Mission(target, System.currentTimeMillis()), "Guiding ${target.name}")

    /** Follow an existing position, for example one waiting to be claimed or sold. */
    private fun follow(position: BazaarPosition) {
        val name = bazaarGuideName(position.product) ?: BazaarNames.name(position.product)
        val target = AssistMission(position.product, name, maxOf(position.remaining, position.acquired - position.sold, 1), null, null)
        launch(Mission(target, position.openedAt, position.id), "Following ${target.name}")
    }

    private fun launch(next: Mission, message: String) {
        if (!Settings.enabled) { feedback("Bazaar Assistant is turned off in its settings", AssistTone.WARNING); return }
        val now = System.currentTimeMillis()
        mission = next; pendingPickUntil = 0
        if (runStartedAt == 0L) runStartedAt = now
        next.positionId?.let { runPositions += it }
        source.ledger.realizations.filter { it.product == next.target.product }.mapTo(seenRealizations) { it.id }
        status = AssistStatus.ACTIVE; filledSign = 0; screenDirty = true
        say(message, AssistTone.INFO)
        update(now)
    }

    /** Start key: resume, follow a position that needs you, or take the best current recommendation. */
    fun start() {
        if (!Settings.enabled) { feedback("Bazaar Assistant is turned off in its settings", AssistTone.WARNING); return }
        mission?.takeIf { status != AssistStatus.DONE }?.let {
            status = AssistStatus.ACTIVE; say("Resumed", AssistTone.INFO); screenDirty = true; update(System.currentTimeMillis()); return
        }
        val ledger = source.ledger
        val waiting = ledger.positions.filter { it.pendingItems > 0 || it.stage == BazaarStage.READY }.maxByOrNull { it.lastAt }
        if (waiting != null) { follow(waiting); return }
        val best = source.opportunities.firstOrNull()
        if (best != null) { begin(best); return }
        source.startSession()
        pendingPickUntil = System.currentTimeMillis() + 60_000
        if (runStartedAt == 0L) runStartedAt = System.currentTimeMillis()
        status = AssistStatus.WAITING; mission = null; step = null; phase = null
        say("Loading recommendations…", AssistTone.INFO)
    }

    fun stop() {
        if (!active && mission == null) return
        lastRun = assistRunSummary(source.ledger.realizations, runPositions, runStartedAt, System.currentTimeMillis())
        lastRun?.let { run ->
            Utils.displayModMessage("Bazaar Assistant run: ${bazaarSignedCoins(run.profit)} from ${run.sales} sale${if (run.sales == 1) "" else "s"}" +
                " in ${assistDuration(run.millis)}" + (run.perHour?.let { " · ${bazaarCompactCoins(it)}/h" } ?: "") +
                (run.bestProduct?.takeIf { run.sales > 1 }?.let { " · best ${BazaarNames.name(it)}" } ?: ""))
        }
        mission = null; step = null; phase = null; warning = null; pendingPickUntil = 0
        status = AssistStatus.OFF; filledSign = 0; runStartedAt = 0; runPositions.clear(); seenRealizations.clear(); pops = emptyList()
        bump()
    }

    private fun say(text: String, tone: AssistTone, millis: Long = 4000) {
        val now = System.currentTimeMillis()
        notice = AssistNotice(text, tone, now, now + millis)
        bump()
    }

    /** Notices show in the panel; while it is hidden they go to chat instead, so a key press never seems ignored. */
    private fun feedback(text: String, tone: AssistTone) {
        if (active) say(text, tone)
        else if (MC.instance.player != null) Utils.displayModMessage((if (tone == AssistTone.INFO) "§7" else "§e") + text)
    }

    private fun bump() { revision++ }

    // ---- Keys ----

    private fun onTick() {
        while (startKey.consumeClick()) startFromKey()
        while (stopKey.consumeClick()) stopFromKey()
        while (commandKey.consumeClick()) sendCommand()
        val now = System.currentTimeMillis()
        notice?.takeIf { now > it.until }?.let { notice = null; bump() }
        if (pops.isNotEmpty() && pops.all { now - it.at > POP_MILLIS }) { pops = emptyList(); bump() }
        if (pendingPickUntil > 0) {
            val best = source.opportunities.firstOrNull()
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

    private fun startFromKey() { if (onSkyBlock) start() else feedback("Join SkyBlock to use the Bazaar Assistant", AssistTone.INFO) }
    private fun stopFromKey() { if (active) { stop(); feedback("Bazaar Assistant stopped", AssistTone.INFO) } }

    /** Handles the assistant's own keys inside a menu; true consumes the key. */
    private fun menuKey(key: KeyEvent): Boolean {
        if (!onSkyBlock) return false
        return when {
            !startKey.isUnbound && startKey.matches(key) -> { startFromKey(); true }
            !stopKey.isUnbound && stopKey.matches(key) -> { stopFromKey(); true }
            !commandKey.isUnbound && commandKey.matches(key) && Settings.autoCommand && shownCommand != null -> { say("Close the menu first", AssistTone.INFO); true }
            else -> false
        }
    }

    // ---- Events ----

    @Subscription fun containerOpened(@Suppress("UNUSED_PARAMETER") event: ContainerInitializedEvent) { screenDirty = true }
    @Subscription fun containerChanged(event: InventoryChangeEvent) { if (!event.isInPlayerInventory) screenDirty = true }
    @Subscription(ServerDisconnectEvent::class) fun disconnected() {
        if (mission != null) { status = AssistStatus.PAUSED; say("Disconnected; your flip resumes when you're back", AssistTone.WARNING) }
    }

    @Subscription(receiveCancelled = true)
    fun chat(event: ChatReceivedEvent.Pre) = chatLine(event.text.trim())

    internal fun chatLine(text: String) {
        val target = mission?.target ?: return
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

    // ---- Step ----

    private fun update(now: Long) {
        val current = mission ?: return
        if (screenDirty) { screen = snapshot(); screenDirty = false; observedScreen = MC.screen }
        if (!onSkyBlock) {
            if (status != AssistStatus.PAUSED) { status = AssistStatus.PAUSED; bump() }
            return
        }
        val ledger = source.ledger
        val position = current.positionId?.let { id -> ledger.positions.firstOrNull { it.id == id } }
            ?: ledger.positions.filter { it.product == current.target.product && it.openedAt >= current.startedAt - 10_000 }.singleOrNull()
        if (position != null && current.positionId != position.id) { mission = current.copy(positionId = position.id); runPositions += position.id }
        val live = source.freshness() == BazaarFreshness.FRESH
        val quote = source.quote(current.target.product)
        // Undercut the live best offer by 0.1 when typing the sell price; without live data keep the recommendation.
        val sellPrice = quote?.sellOfferPrice?.takeIf { live }?.let { (kotlin.math.ceil(it * 10 - 1e-7) - 1) / 10 } ?: current.target.sellPrice
        val target = current.target.copy(sellPrice = sellPrice)
        val nextPhase = assistPhase(position, ledger.orders)
        val nextStep = assistStep(target, nextPhase, screen, Settings.priceMode, position?.remaining?.takeIf { it > 0 } ?: target.quantity)
        val nextWarning = when {
            nextPhase != AssistPhase.PLACE_BUY && nextPhase != AssistPhase.PLACE_SELL -> null
            !source.trading -> "Start flipping for live prices before placing the order"
            !live -> "Market data is not live; prices may have moved"
            nextPhase == AssistPhase.PLACE_BUY && source.opportunities.none { it.product == target.product } -> "This flip no longer ranks; consider another"
            else -> null
        }
        val nextStatus = when {
            nextPhase == AssistPhase.DONE -> AssistStatus.DONE
            nextStep.kind == AssistKind.WAIT -> AssistStatus.WAITING
            nextStep.kind == AssistKind.CLICK && nextStep.slot == null && screen.kind == AssistScreenKind.CONTAINER -> AssistStatus.ERROR
            else -> AssistStatus.ACTIVE
        }
        val matches = nextStep.target?.let { target -> screen.slots.count { it.name.equals(target, true) } } ?: 0
        if (matches != targetMatches) { targetMatches = matches; bump() }
        if (nextStep != step || nextPhase != phase || nextWarning != warning || nextStatus != status) {
            if (nextStep.kind != step?.kind || nextStep.label != step?.label || nextStep.slot != step?.slot) stepChangedAt = now
            if (nextPhase == AssistPhase.DONE && phase != AssistPhase.DONE) say("Flip complete", AssistTone.SUCCESS)
            step = nextStep; phase = nextPhase; warning = nextWarning; status = nextStatus
            bump()
        }
        position?.let { realized(it.id, now) }
    }

    /** Realized results come from the existing ledger; each record pops once and close records merge. */
    private fun realized(positionId: String, now: Long) {
        val fresh = source.ledger.realizations.filter { it.positionId == positionId && it.netProfit != null && seenRealizations.add(it.id) }
        if (fresh.isEmpty() || !Settings.profitNotices) return
        val click = lastClick?.takeIf { now - it.at <= 4000 && it.screenId == System.identityHashCode(MC.screen) && MC.screen is AbstractContainerScreen<*> }
        pops = assistMergePop(pops.filter { now - it.at <= POP_MILLIS }, AssistPopEvent(fresh.sumOf { it.netProfit ?: 0.0 }, now, click?.x, click?.y))
        bump()
    }

    /** Realized profit of positions guided since the assistant started, and its hourly rate once meaningful. */
    internal fun runProfit(): AssistRunSummary? = if (runStartedAt == 0L) null
        else assistRunSummary(source.ledger.realizations, runPositions, runStartedAt, System.currentTimeMillis())

    /** Screen reads so far; they happen on screen changes and every half second, never per frame. */
    internal var snapshots = 0L
        private set

    private fun snapshot(): AssistScreen = also { snapshots++ }.let { when (val open = MC.screen) {
        null, is ChatScreen -> AssistScreen(AssistScreenKind.NONE)
        is AbstractSignEditScreen -> AssistScreen(AssistScreenKind.SIGN,
            signLines = (open as BazaarAssistSignAccessor).`skymyce$getMessages`()?.map { it.orEmpty() }.orEmpty(), id = System.identityHashCode(open))
        is AbstractContainerScreen<*> -> AssistScreen(AssistScreenKind.CONTAINER, open.title.string.replace(colorCode, ""),
            open.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }.take(90).map { slot ->
                AssistSlot(slot.index, slotName(slot.item), slot.item.getSkyBlockId()?.bazaarId?.uppercase())
            }, id = System.identityHashCode(open))
        else -> AssistScreen(AssistScreenKind.OTHER, id = System.identityHashCode(open))
    } }
    private val colorCode = Regex("§.")
    private fun slotName(stack: net.minecraft.world.item.ItemStack) = stack.hoverName.string.replace(colorCode, "").trim()

    /**
     * Highlight only the current container's own target, and only while that slot still shows what the step was
     * computed from; a menu that changed since the last read shows nothing rather than a guess.
     */
    internal fun highlightTarget(container: AbstractContainerScreen<*>): Int? {
        if (!Settings.enabled || status == AssistStatus.OFF || status == AssistStatus.PAUSED) return null
        val index = step?.takeIf { it.kind == AssistKind.CLICK }?.slot ?: return null
        if (screen.id != System.identityHashCode(container)) return null
        val expected = screen.slots.firstOrNull { it.index == index }?.name ?: return null
        val live = container.menu.slots.firstOrNull { it.index == index }?.item ?: return null
        return index.takeIf { !live.isEmpty && slotName(live) == expected }
    }

    // ---- Commands ----

    /** Auto Command: one key press sends the one command the current step shows, never while a menu is open. */
    private fun sendCommand() {
        if (!onSkyBlock) return
        if (!Settings.autoCommand) { feedback("Turn on Auto Command in the Bazaar terminal to use this key", AssistTone.WARNING); return }
        val command = shownCommand
        if (command == null) { feedback("No command to send for this step", AssistTone.INFO); return }
        if (MC.screen != null) { say("Close the menu first", AssistTone.INFO); return }
        val connection = MC.instance.connection ?: return
        val now = System.currentTimeMillis()
        if (command == lastCommand && now - lastCommandAt < 3000) { say("Already sent; waiting for the menu", AssistTone.INFO); return }
        lastCommand = command; lastCommandAt = now
        connection.sendCommand(command)
        say("Sent /$command", AssistTone.SUCCESS, 2500)
    }

    /** Tab completes the expected command only while the box is on its way to it; otherwise vanilla Tab runs. */
    private fun tabFill(chat: ChatScreen): Boolean {
        if (!Settings.tabFill || !onSkyBlock) return false
        val command = shownCommand ?: return false
        val input = (chat as IrcChatScreenAccessor).`skymyce$getInput`()
        assistChatGuide(input.value, command) ?: return false
        input.value = "/$command"
        input.moveCursorToEnd(false)
        if (Settings.tabFills < TAB_HINTS) { Settings.tabFills++; SkyMyce.config.save() }
        return true
    }

    private fun drawGhost(chat: ChatScreen, graphics: GuiGraphicsExtractor) {
        if (!Settings.ghostText || !onSkyBlock) return
        val command = shownCommand ?: return
        val input = (chat as IrcChatScreenAccessor).`skymyce$getInput`()
        val typed = input.value
        val guide = assistChatGuide(typed, command) ?: return
        val font = MC.instance.font
        val x = input.x
        val y = input.y // The chat box is unbordered, so its text sits at the top edge.
        // Our guidance takes the inline slot; vanilla's suggestion list stays open and keeps its keys.
        input.setSuggestion(null)
        guide.mismatchAt?.let { at ->
            val wrong = typed.substring(at)
            if (font.width(typed) > input.innerWidth) return
            graphics.text(font, wrong, x + font.width(typed.substring(0, at)), y, 0xFFFF6B6B.toInt(), false)
            return
        }
        val hint = if (Settings.tabFill && Settings.tabFills < TAB_HINTS) "  Tab" else ""
        val typedWidth = font.width(typed)
        if (typedWidth + font.width(guide.ghost + hint) > input.innerWidth) return
        graphics.text(font, guide.ghost, x + typedWidth, y, 0x80B8C2CC.toInt(), false)
        if (hint.isNotEmpty()) graphics.text(font, hint, x + typedWidth + font.width(guide.ghost), y, 0x80FFC857.toInt(), false)
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

    /** Auto Sign types the expected value once per sign, into an empty first line; the player still clicks Done. */
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
        say("Typed $value: check it, then click Done", AssistTone.INFO, 3000)
    }

    private fun drawSignHelper(signScreen: AbstractSignEditScreen, graphics: GuiGraphicsExtractor) {
        if (!Settings.signHelper) return
        val current = signStep(signScreen) ?: return
        val expected = current.value ?: return
        val accessor = signScreen as BazaarAssistSignAccessor
        val typed = accessor.`skymyce$getMessages`()?.firstOrNull().orEmpty()
        val font = MC.instance.font
        val center = signScreen.width / 2
        val what = when (current.sign) { AssistSign.SEARCH -> "Search"; AssistSign.AMOUNT -> "Amount"; else -> "Price" }
        graphics.centeredText(font, "Bazaar Assistant · $what: $expected", center, 10, 0xFFFFC857.toInt())
        val check = assistSignCheck(typed, expected)
        val (line, color) = when {
            accessor.`skymyce$getLine`() != 0 && check != AssistSignCheck.MATCH -> "Use the first line: press ↑ to go back" to 0xFFFF7A7A.toInt()
            check == AssistSignCheck.EMPTY -> "Type it on the first line" to 0xFFB8C2CC.toInt()
            check == AssistSignCheck.PARTIAL -> "Keep typing…" to 0xFFB8C2CC.toInt()
            check == AssistSignCheck.MATCH -> "$expected is right: click Done" to 0xFF7CE38B.toInt()
            else -> "You typed ${typed.trim().take(24)}; it should be $expected" to 0xFFFF7A7A.toInt()
        }
        graphics.centeredText(font, line, center, 22, color)
    }

    fun commandKeyName(): String? = if (::commandKey.isInitialized && !commandKey.isUnbound) commandKey.translatedKeyMessage.string else null
    fun startKeyName(): String? = if (::startKey.isInitialized && !startKey.isUnbound) startKey.translatedKeyMessage.string else null
    fun stopKeyName(): String? = if (::stopKey.isInitialized && !stopKey.isUnbound) stopKey.translatedKeyMessage.string else null

    internal const val POP_MILLIS = 3500L
    private const val TAB_HINTS = 3
}
