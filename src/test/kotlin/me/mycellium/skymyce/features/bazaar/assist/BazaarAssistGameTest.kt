package me.mycellium.skymyce.features.bazaar.assist

import com.mojang.blaze3d.platform.InputConstants
import me.mycellium.mixin.BazaarAssistContainerAccessor
import me.mycellium.mixin.BazaarAssistSignAccessor
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.config.economy.AssistPanelPlacement
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.bazaar.*
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.ui.GuiMotion
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.inventory.SignEditScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Records stand in for Hypixel's order tracking, so the real assistant can be driven through a whole flip offline. */
internal class FakeAssistSource : AssistSource {
    @Volatile override var ledger = BazaarLedger()
    @Volatile override var opportunities = emptyList<BazaarOpportunity>()
    @Volatile override var trading = true
    @Volatile var fresh = BazaarFreshness.FRESH
    override fun quote(product: String): BazaarProduct? = null
    override fun freshness() = fresh
    override fun startSession() = Unit
}

/**
 * Drives the real Bazaar Assistant inside a running client: a local flat world supplies a player, chat, HUD,
 * keybinds and a server connection; Bazaar menus and signs are opened client-side with Hypixel's titles and
 * button names. Run with `./gradlew runBazaarAssistGameTest`; screenshots and report.txt land in its run directory.
 */
class BazaarAssistGameTest : FabricClientGameTest {
    private val source = FakeAssistSource()
    private val sent = CopyOnWriteArrayList<String>()
    private val report = mutableListOf<String>()
    private lateinit var context: ClientGameTestContext
    private var menuId = 100
    private var shot = 0
    private val carrot = BazaarOpportunity("ENCHANTED_CARROT", "Enchanted Carrot", 640, 152.3, 171.0, 18.7, 3.5, 9_400.0, .09, 97_472.0,
        600.0, 1_800.0, .8, .7, .9, "game-test", System.currentTimeMillis(), emptyList(), null, null)

    override fun runTest(context: ClientGameTestContext) {
        this.context = context
        ClientSendMessageEvents.COMMAND.register { sent += it }
        on {
            BazaarAssist.source = source; BazaarAssist.simulatedSkyBlock = true
            Settings.resetAppearance(); Settings.tabFills = 0
            ThemeConfig.animationMode = GuiMotion.MEDIUM
        }
        context.worldBuilder().create().use { world ->
            world.clientLevel.waitForChunksRender()
            startAndStop()
            chat()
            autoCommand()
            menus()
            signs()
            lifecycle()
            hud()
            performance()
        }
        on { BazaarAssist.source = TradingAssistSource; BazaarAssist.simulatedSkyBlock = false }
        val path = context.computeOnClient<java.nio.file.Path, RuntimeException> { it.gameDirectory.toPath().resolve("bazaar-assist-report.txt") }
        Files.write(path, report)
        println("Bazaar Assistant game test passed (${report.size} checks); report: $path")
    }

    // ---- Scenarios ----

    private fun startAndStop() {
        source.opportunities = listOf(carrot)
        context.waitTicks(2)
        pass("idle: assistant off and panel hidden before start", on { !BazaarAssist.active && !BazaarAssistHud.panel.isRenderable })
        screenshot("idle")
        context.input.pressKey(BazaarAssist.startKey)
        context.waitTicks(2)
        pass("start key ] begins the top recommendation", on {
            BazaarAssist.active && BazaarAssist.product == "ENCHANTED_CARROT" && BazaarAssist.step?.command == "bz Enchanted Carrot"
        })
        pass("panel renders in the HUD layer", on { BazaarAssistHud.panel.isRenderable })
        screenshot("started")
        context.input.pressKey(BazaarAssist.stopKey)
        context.waitTicks(1)
        pass("stop key [ stops and hides the panel", on { !BazaarAssist.active })
        context.waitTicks(1)
        pass("panel hidden after stop", on { !BazaarAssistHud.panel.isRenderable })
        context.input.pressKey(BazaarAssist.startKey)
        context.waitTicks(2)
        pass("start key resumes", on { BazaarAssist.active && BazaarAssist.phase == AssistPhase.PLACE_BUY })
    }

    private fun chat() {
        on { it.setScreen(ChatScreen("", false)) }
        context.waitTicks(1)
        context.input.typeChars("/b")
        context.waitTicks(1)
        screenshot("chat-ghost")
        context.input.pressKey(GLFW.GLFW_KEY_TAB)
        pass("Tab fills the expected command", chatValue() == "/bz Enchanted Carrot", chatValue())
        setChat("")
        context.input.typeChars("/ba")
        context.input.pressKey(GLFW.GLFW_KEY_TAB)
        pass("Tab on another command keeps vanilla completion", chatValue() != "/bz Enchanted Carrot" && chatValue().startsWith("/ba"), chatValue())
        setChat("")
        context.input.typeChars("/bz Enchanted Potato")
        context.waitTicks(1)
        screenshot("chat-mismatch")
        context.input.pressKey(GLFW.GLFW_KEY_TAB)
        pass("Tab corrects a wrong item after the right command", chatValue() == "/bz Enchanted Carrot", chatValue())
        pass("Tab use counted for the hint", on { Settings.tabFills == 2 })
        on { it.setScreen(null) }
        context.waitTicks(1)
        pass("nothing was sent from chat", sent.isEmpty(), sent.toString())
    }

    private fun autoCommand() {
        on { BazaarAssist.commandKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K)); KeyMapping.resetMapping() }
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("Auto Command off: the key sends nothing", sent.isEmpty() && on { BazaarAssist.notice?.text?.contains("Turn on Auto Command") == true })
        on { Settings.autoCommand = true }
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("Auto Command on: one key press sends the shown command once", sent.toList() == listOf("bz Enchanted Carrot"), sent.toString())
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("repeat press within 3 s is not resent", sent.size == 1 && on { BazaarAssist.notice?.text?.startsWith("Already sent") == true })
        on { it.setScreen(InventoryScreen(it.player!!)) }
        context.waitTicks(2)
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("never sends while a menu is open", sent.size == 1)
        on { it.setScreen(null) }
        Thread.sleep(3_100)
        context.waitTicks(2)
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("sends again after the cooldown", sent.size == 2, sent.toString())
        on { Settings.autoCommand = false }
    }

    private fun menus() {
        expectHighlight("search results", "Bazaar ➜ \"Enchanted Carrot\"", 12,
            11 to "Carrot", 12 to "Enchanted Carrot", 13 to "Enchanted Golden Carrot", 49 to "Go Back")
        expectHighlight("product page", "Farming ➜ Enchanted Carrot", 10, 10 to "Create Buy Order", 11 to "Create Sell Offer", 13 to "Enchanted Carrot")
        expectHighlight("amount menu", "How many do you want?", 16, 10 to "Buy a stack!", 12 to "Buy a big stack!", 16 to "Custom Amount")
        val price = arrayOf(10 to "Same as Top Order", 12 to "Top Order +0.1", 14 to "5% of Spread", 16 to "Custom Price")
        expectHighlight("price menu (match the book)", "How much do you want to pay?", 12, *price)
        on { Settings.priceMode = AssistPriceMode.CUSTOM }
        context.waitTicks(1)
        screenshot("price-menu-glide")
        context.waitTicks(4)
        pass("price menu (custom price) moves the highlight", on { highlight() == 16 })
        screenshot("price-menu-custom")
        on { Settings.priceMode = AssistPriceMode.MATCH_ORDER_BOOK }
        context.waitTicks(2)
        expectHighlight("confirm buy order", "Confirm Buy Order", 13, 13 to "Buy Order", 11 to "Enchanted Carrot")

        expectHighlight("ambiguous buttons are never guessed", "How many do you want?", null, 10 to "Custom Amount", 16 to "Custom Amount")
        pass("ambiguity shows as needing attention", on { BazaarAssist.status == AssistStatus.ERROR && BazaarAssist.targetMatches == 2 })
        expectHighlight("missing button shows nothing", "How many do you want?", null, 10 to "Buy a stack!")
        expectHighlight("another product's page", "Farming ➜ Enchanted Potato", null, 10 to "Create Buy Order")
        pass("another product's page asks to go back", on { BazaarAssist.step?.kind == AssistKind.CLOSE })
        expectHighlight("unrelated chest", "Large Chest", null, 13 to "Buy Order")

        // A slot renamed under the highlight hides it at once, before the next read.
        openMenu("How much do you want to pay?", *price)
        context.waitTicks(2)
        pass("stale slot: highlight hides immediately when its item changes", on {
            val before = highlight()
            container().setItem(12, named("Top Order +0.2"))
            before == 12 && highlight() == null
        })
        context.waitTicks(12)
        pass("stale slot: the next read finds no target", on { highlight() == null })
        // Buttons that move are followed after the next read, gliding from the old slot.
        openMenu("How much do you want to pay?", *price)
        context.waitTicks(2)
        on { val c = container(); val a = c.getItem(12); c.setItem(12, c.getItem(14)); c.setItem(14, a) }
        context.waitTicks(11)
        pass("moved button: highlight follows within half a second", on { highlight() == 14 })
        screenshot("moved-button")

        on { it.setScreen(InventoryScreen(it.player!!)) }
        context.waitTicks(2)
        pass("player inventory: asks to close it, no highlight", on { BazaarAssist.step?.kind == AssistKind.CLOSE })

        // Assistant keys inside a menu.
        openMenu("Farming ➜ Enchanted Carrot", 10 to "Create Buy Order")
        context.waitTicks(2)
        context.input.pressKey(GLFW.GLFW_KEY_LEFT_BRACKET)
        context.waitTicks(1)
        pass("stop key works inside a menu", on { !BazaarAssist.active && highlight() == null })
        context.input.pressKey(GLFW.GLFW_KEY_RIGHT_BRACKET)
        context.waitTicks(2)
        pass("start key works inside a menu", on { BazaarAssist.active && highlight() == 10 })
        on { it.setScreen(null) }
        context.waitTicks(1)
    }

    private fun signs() {
        openSign("", "^^^^^^^^^^^^^^^", "Enter amount", "to order")
        context.waitTicks(2)
        pass("Auto Sign off: the sign stays empty", signLine() == "")
        screenshot("sign-empty")
        context.input.typeChars("64")
        context.waitTicks(1)
        screenshot("sign-partial")
        context.input.typeChars("0")
        context.waitTicks(1)
        pass("typed amount is read back", signLine() == "640")
        screenshot("sign-match")
        context.input.pressKey(GLFW.GLFW_KEY_ENTER)
        context.waitTicks(1)
        val afterEnter = on { (it.screen as? AbstractSignEditScreen)?.let { s -> (s as BazaarAssistSignAccessor).`skymyce$getLine`() } }
        pass("Enter moves to the next line instead of submitting (instructions say Done)", afterEnter == 1, "line=$afterEnter")
        screenshot("sign-wrong-line")
        closeScreen()

        on { Settings.autoSign = true }
        openSign("", "^^^^^^^^^^^^^^^", "Enter amount", "to order")
        context.waitTicks(2)
        pass("Auto Sign types the amount", signLine() == "640", signLine())
        pass("Auto Sign does not submit or close", on { it.screen is SignEditScreen })
        screenshot("sign-auto")
        on { val f = (it.screen as BazaarAssistSignAccessor).`skymyce$getSignField`(); f.selectAll(); f.insertText("") }
        context.waitTicks(5)
        pass("a cleared sign is not refilled", signLine() == "")
        closeScreen()
        openSign("", "^^^^^^^^^^^^^^^", "Enter amount", "to order")
        context.waitTicks(2)
        pass("a newly opened sign is filled once", signLine() == "640")
        closeScreen()
        openSign("", "^^^^^^^^^^^^^^^", "", "Enter query")
        context.waitTicks(2)
        pass("search sign gets the item name", signLine() == "Enchanted Carrot", signLine())
        closeScreen()
        on { Settings.priceMode = AssistPriceMode.CUSTOM }
        openSign("", "^^^^^^^^^^^^^^^", "Enter price", "big nerd")
        context.waitTicks(2)
        pass("price sign gets the recommended price", signLine() == "152.3", signLine())
        closeScreen()
        on { Settings.priceMode = AssistPriceMode.MATCH_ORDER_BOOK }
        openSign("Hello", "", "", "")
        context.waitTicks(2)
        pass("unrelated signs are never typed into", signLine() == "Hello")
        closeScreen()
        on { Settings.autoSign = false }
        pass("signs never sent a command", sent.size == 2)
    }

    private fun lifecycle() {
        val now = System.currentTimeMillis()
        fun position(stage: BazaarStage, pending: Long = 0, remaining: Long = 0, listed: Long = 0, profit: Double = 0.0) =
            BazaarPosition("game-pos", "ENCHANTED_CARROT", 640, 640 - remaining, remaining, 97_472.0, profit, 0, now, now, stage, false, "s", pending, listed)
        fun order(buy: Boolean, filled: Long, collected: Long, status: BazaarOrderStatus) =
            BazaarOrder(if (buy) "game-buy" else "game-sell", "game-pos", "ENCHANTED_CARROT", buy, 640, filled, collected, 1.0, status, now, now, false)
        fun realization(id: String, profit: Double) = BazaarRealization(id, "ENCHANTED_CARROT", "game-pos", System.currentTimeMillis(), 320, null, null,
            null, null, profit, true, BazaarEvidence.OBSERVED, null, null, "fp-$id")

        source.ledger = BazaarLedger(listOf(position(BazaarStage.BUYING)), listOf(order(true, 0, 0, BazaarOrderStatus.OPEN)))
        context.waitTicks(2)
        pass("buy order placed: waiting, no command", on { BazaarAssist.phase == AssistPhase.WAIT_BUY && BazaarAssist.status == AssistStatus.WAITING && BazaarAssist.shownCommand == null })
        screenshot("waiting-buy")
        on { it.setScreen(ChatScreen("", false)) }
        context.waitTicks(1)
        context.input.typeChars("/")
        context.input.pressKey(GLFW.GLFW_KEY_TAB)
        pass("no command step: Tab is vanilla", chatValue() != "/bz Enchanted Carrot", chatValue())
        closeScreen()
        on { Settings.autoCommand = true }
        context.input.pressKey(BazaarAssist.commandKey)
        context.waitTicks(1)
        pass("no command step: the send key sends nothing", sent.size == 2)
        on { Settings.autoCommand = false }

        source.ledger = BazaarLedger(listOf(position(BazaarStage.COLLECT, pending = 640)), listOf(order(true, 640, 0, BazaarOrderStatus.FILLED)))
        context.waitTicks(2)
        pass("filled: claim items starts with /bz", on { BazaarAssist.phase == AssistPhase.CLAIM_ITEMS && BazaarAssist.shownCommand == "bz" })
        openMenu("Bazaar Orders", 10 to "BUY Enchanted Carrot", 11 to "SELL Enchanted Potato")
        context.waitTicks(2)
        pass("orders menu highlights the buy order", on { highlight() == 10 })
        screenshot("claim-items")
        // The order is claimed while the menu stays open: the step changes underneath it.
        source.ledger = BazaarLedger(listOf(position(BazaarStage.READY, remaining = 640)), listOf(order(true, 640, 640, BazaarOrderStatus.FILLED)))
        context.waitTicks(2)
        pass("step changes while a menu is open; old highlight removed", on { BazaarAssist.phase == AssistPhase.PLACE_SELL && highlight() == null })
        openMenu("Farming ➜ Enchanted Carrot", 10 to "Create Buy Order", 11 to "Create Sell Offer")
        context.waitTicks(2)
        pass("sell: product page highlights Create Sell Offer", on { highlight() == 11 })
        source.fresh = BazaarFreshness.STALE
        context.waitTicks(2)
        pass("stale market data warns before listing", on { BazaarAssist.warning?.contains("not live") == true })
        screenshot("stale-market")
        source.fresh = BazaarFreshness.FRESH
        expectHighlight("sell price menu", "At what price are you selling?", 10, 10 to "Best Offer -0.1", 16 to "Custom Price")

        source.ledger = BazaarLedger(listOf(position(BazaarStage.SELLING, remaining = 640, listed = 640)),
            listOf(order(true, 640, 640, BazaarOrderStatus.FILLED), order(false, 0, 0, BazaarOrderStatus.OPEN)))
        context.waitTicks(2)
        pass("sell offer listed: waiting", on { BazaarAssist.phase == AssistPhase.WAIT_SELL })

        val sold = listOf(position(BazaarStage.SELLING, remaining = 0))
        val orders = listOf(order(true, 640, 640, BazaarOrderStatus.FILLED), order(false, 640, 0, BazaarOrderStatus.FILLED))
        source.ledger = BazaarLedger(sold, orders)
        openMenu("Bazaar Orders", 10 to "BUY Enchanted Potato", 11 to "SELL Enchanted Carrot")
        context.waitTicks(2)
        pass("sold: orders menu highlights the sell offer", on { BazaarAssist.phase == AssistPhase.CLAIM_COINS && highlight() == 11 })
        clickSlot(11)
        source.ledger = BazaarLedger(sold, orders, listOf(realization("r1", 120_000.0), realization("r2", 30_000.0)))
        context.waitTicks(2)
        pass("claimed coins pop once, merged, at the clicked slot", on {
            BazaarAssist.pops.size == 1 && BazaarAssist.pops.single().amount == 150_000.0 && BazaarAssist.pops.single().x != null
        }, on { BazaarAssist.pops.toString() })
        screenshot("profit-pop-slot")
        source.ledger = BazaarLedger(sold, orders, listOf(realization("r1", 120_000.0), realization("r2", 30_000.0)))
        context.waitTicks(2)
        pass("the same records never pop twice", on { BazaarAssist.pops.size == 1 })
        closeScreen()
        Thread.sleep(1_300)
        source.ledger = BazaarLedger(sold, orders, listOf(realization("r1", 120_000.0), realization("r2", 30_000.0), realization("r3", 1_250_000.0)))
        context.waitTicks(2)
        pass("a later sale pops separately in the HUD", on { BazaarAssist.pops.size == 2 && BazaarAssist.pops.last().x == null })
        screenshot("profit-pop-hud")
        pass("run profit comes from the same records", on { BazaarAssist.runProfit()?.profit == 1_400_000.0 }, on { BazaarAssist.runProfit().toString() })

        source.ledger = BazaarLedger(listOf(position(BazaarStage.CLOSED, profit = 1_400_000.0)),
            listOf(order(true, 640, 640, BazaarOrderStatus.FILLED), order(false, 640, 640, BazaarOrderStatus.FILLED)),
            listOf(realization("r1", 120_000.0), realization("r2", 30_000.0), realization("r3", 1_250_000.0)))
        context.waitTicks(2)
        pass("closed position: flip complete", on { BazaarAssist.status == AssistStatus.DONE })
        screenshot("flip-complete")
        context.input.pressKey(BazaarAssist.stopKey)
        context.waitTicks(2)
        pass("stopping summarises the run", on { BazaarAssist.lastRun?.profit == 1_400_000.0 && BazaarAssist.lastRun?.sales == 3 })
        on { it.setScreen(ChatScreen("", false)) }
        context.waitTicks(1)
        screenshot("run-summary-chat")
        closeScreen()
        source.ledger = BazaarLedger()
    }

    private fun hud() {
        context.input.pressKey(BazaarAssist.startKey)
        context.waitTicks(2)
        openMenu("Farming ➜ Enchanted Carrot", 10 to "Create Buy Order", 11 to "Create Sell Offer", 13 to "Enchanted Carrot")
        for (scale in listOf(1, 2, 3, 4)) {
            on { it.options.guiScale().set(scale); it.resizeGui() }
            context.waitTicks(3)
            val placed = on {
                val menu = it.screen as BazaarAssistContainerAccessor
                val left = menu.`skymyce$getLeftPos`(); val right = left + menu.`skymyce$getImageWidth`()
                BazaarAssistHud.lastBeside?.let { b -> (b[0] >= right || b[0] + b[2] <= left) to "x=${b[0]} w=${b[2]} menu=$left..$right screen=${it.screen!!.width}" }
            }
            if (scale <= 3) pass("GUI scale $scale: panel sits beside the menu without covering it", placed?.first == true, placed?.second ?: "saved position")
            else report += "INFO GUI scale $scale on 1280x720: " + (placed?.second ?: "too narrow even at half size; the saved position is used")
            screenshot("hud-scale-$scale")
        }
        on { it.options.guiScale().set(2); it.resizeGui() }
        context.waitTicks(2)
        val (startX, startY, scale) = on {
            val beside = checkNotNull(BazaarAssistHud.lastBeside) { "panel was not beside the menu at GUI scale 2" }
            Triple(beside[0] + 10, beside[1] + 6, it.window.guiScale.toDouble())
        }
        context.input.setCursorPos(startX * scale, startY * scale)
        context.waitTicks(1)
        context.input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        context.waitTicks(1)
        context.input.moveCursor(-40 * scale, 60 * scale)
        context.waitTicks(1)
        context.input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        context.waitTicks(2)
        val dropped = on { BazaarAssistHud.panel.x to BazaarAssistHud.panel.y }
        pass("dragging inside a menu moves the panel and keeps it there", on { Settings.panelPlacement == AssistPanelPlacement.SAVED } &&
            dropped == (startX - 10 - 40) to (startY - 6 + 60), "at $dropped")
        screenshot("hud-dragged")
        Thread.sleep(1_200)
        context.waitTicks(1)
        val saved = on { String(Files.readAllBytes(WidgetManager.saveFile.toPath())) }
        pass("panel position is saved to widgets.json", saved.contains("bazaar_assistant") && saved.contains("\"x\": ${dropped.first}") ||
            saved.contains("\"x\":${dropped.first}"), saved.substringAfter("bazaar_assistant").take(80))
        closeScreen()
        context.waitTicks(1)
        // Outside menus the widget may shift to stay fully on screen; reopening a menu must keep the saved spot.
        val outside = on { BazaarAssistHud.panel.x to BazaarAssistHud.panel.y }
        openMenu("Farming ➜ Enchanted Carrot", 10 to "Create Buy Order")
        context.waitTicks(2)
        screenshot("hud-reopened")
        pass("a reopened menu keeps the dragged position", on {
            val menu = it.screen as BazaarAssistContainerAccessor
            (BazaarAssistHud.panel.x to BazaarAssistHud.panel.y) == outside && BazaarAssistHud.lastBeside == null &&
                menu.`skymyce$getLeftPos`() >= 0 && kotlin.math.abs(outside.first - dropped.first) < 160
        }, "dropped $dropped, outside $outside")
        on { Settings.resetAppearance(); BazaarAssistHud.panel.defaultLayout.copy().applyConfig(BazaarAssistHud.panel) }
        pass("reset restores beside-the-menu placement", on { Settings.panelPlacement == AssistPanelPlacement.BESIDE_MENU && BazaarAssistHud.panel.x == BazaarAssistHud.panel.defaultLayout.x && BazaarAssistHud.panel.anchor == BazaarAssistHud.panel.defaultLayout.anchor })
        closeScreen()
        context.input.resizeWindow(854, 480)
        context.waitTicks(3)
        screenshot("hud-small-window")
        context.input.resizeWindow(1280, 720)
        context.waitTicks(3)
        on { it.setScreen(WidgetEditorScreen()) }
        context.waitTicks(3)
        screenshot("hud-editor")
        closeScreen()
    }

    private fun performance() {
        openMenu("Farming ➜ Enchanted Carrot", 10 to "Create Buy Order")
        context.waitTicks(2)
        val before = on { BazaarAssist.snapshots }
        context.waitTicks(100)
        val reads = on { BazaarAssist.snapshots } - before
        pass("static menu is read on a timer, not per frame", reads in 8..12, "$reads reads in 100 ticks")
        closeScreen()
        openMenu("How much do you want to pay?", 12 to "Top Order +0.1")
        context.waitTicks(2)
        on { Settings.animations = false }
        pass("animations off disables motion", on { !assistAnimated() })
        screenshot("animations-off")
        on { Settings.animations = true; ThemeConfig.animationMode = GuiMotion.REDUCED }
        pass("reduced menu motion also disables assistant motion", on { !assistAnimated() })
        on { ThemeConfig.animationMode = GuiMotion.MEDIUM }
        context.input.pressKey(GLFW.GLFW_KEY_LEFT_BRACKET)
        context.waitTicks(1)
        val idle = on { BazaarAssist.snapshots }
        context.waitTicks(100)
        pass("no screen reads while stopped", on { BazaarAssist.snapshots } == idle)
        closeScreen()
    }

    // ---- Helpers ----

    private fun <T> on(block: (Minecraft) -> T): T = context.computeOnClient<T, RuntimeException> { block(it) }

    private fun pass(name: String, ok: Boolean, detail: String = "") {
        val line = (if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) " ($detail)" else ""
        report += line
        println("[Bazaar Assistant game test] $line")
        check(ok) { line }
    }

    private fun screenshot(name: String) {
        context.takeScreenshot("bazaar-assist-%02d-%s".format(++shot, name))
        report += "SHOT bazaar-assist-%02d-%s".format(shot, name)
    }

    private fun named(name: String) = ItemStack(Items.PAPER).apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) }

    private fun openMenu(title: String, vararg slots: Pair<Int, String>) = on {
        val player = it.player!!
        val container = SimpleContainer(54)
        slots.forEach { (index, name) -> container.setItem(index, named(name)) }
        it.setScreen(ContainerScreen(ChestMenu.sixRows(++menuId, player.inventory, container), player.inventory, Component.literal(title)))
    }

    private fun container() = (Minecraft.getInstance().screen as ContainerScreen).menu.container
    private fun highlight() = (Minecraft.getInstance().screen as? ContainerScreen)?.let { BazaarAssist.highlightTarget(it) }

    private fun expectHighlight(name: String, title: String, expected: Int?, vararg slots: Pair<Int, String>) {
        openMenu(title, *slots)
        context.waitTicks(2)
        pass("$name: ${expected?.let { "highlights slot $it" } ?: "no highlight"}", on { highlight() == expected }, "got ${on { highlight() }}")
        screenshot(name.substringBefore(" (").replace(' ', '-').replace("'", ""))
    }

    private fun clickSlot(index: Int) {
        val (x, y) = on {
            val screen = it.screen as ContainerScreen
            val menu = screen as BazaarAssistContainerAccessor
            val slot = screen.menu.slots.first { s -> s.index == index }
            val scale = it.window.guiScale.toDouble()
            (menu.`skymyce$getLeftPos`() + slot.x + 8) * scale to (menu.`skymyce$getTopPos`() + slot.y + 8) * scale
        }
        context.input.setCursorPos(x, y)
        context.waitTicks(1)
        context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
    }

    private fun openSign(vararg lines: String) = on {
        // Like Hypixel's prompts: a sign right next to the player, editable by them.
        val player = it.player!!
        val sign = SignBlockEntity(player.blockPosition().above(), Blocks.OAK_SIGN.defaultBlockState())
        sign.setLevel(it.level!!)
        sign.setAllowedPlayerEditor(player.uuid)
        var text = SignText()
        lines.forEachIndexed { index, line -> text = text.setMessage(index, Component.literal(line)) }
        sign.setText(text, true)
        it.setScreen(SignEditScreen(sign, true, false))
    }

    private fun signLine() = on { (it.screen as? AbstractSignEditScreen)?.let { s -> (s as BazaarAssistSignAccessor).`skymyce$getMessages`()[0] } ?: "<no sign>" }
    private fun chatValue() = on { ((it.screen as ChatScreen) as IrcChatScreenAccessor).`skymyce$getInput`().value }
    private fun setChat(value: String) = on { ((it.screen as ChatScreen) as IrcChatScreenAccessor).`skymyce$getInput`().value = value }
    private fun closeScreen() { on { it.setScreen(null) }; context.waitTicks(1) }
}
