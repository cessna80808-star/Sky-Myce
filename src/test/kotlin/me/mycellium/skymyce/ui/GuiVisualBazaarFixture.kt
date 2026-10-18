package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.features.bazaar.*
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture

/**
 * In-memory presentation data; exercising the terminal cannot modify a real ledger or send a trade. The scripted
 * walk ends on [finalTab], which is what the screenshot shows.
 */
internal class GuiVisualBazaarFixture(private val finalTab: String = "flips", private val scroll: Double = 0.0) : AutoCloseable {
    private val previous = BazaarUi.bridge
    private val calls = arrayListOf<String>()
    private val actionFields = arrayListOf<Pair<String, Map<String, String>>>()
    private val at = 1_792_147_200_000L
    private val synced = System.currentTimeMillis() - 120_000
    private val trade = BazaarUiTrade("fixture-sale", "ENCHANTED_CARROT", at + 60000, 320, 12480.0, 64000.0, 1520.0, true, "OBSERVED")
    // Needs you: a sell offer relisted past the threshold, and bought items waiting to be claimed.
    private val position = BazaarPosition("fixture-position", "ENCHANTED_CARROT", 640, 320, 320, 64000.0, 12480.0, 7,
        at, at + 60000, BazaarStage.SELLING, false, "fixture-session", 0, 320)
    private val packrat = BazaarPosition("fixture-packrat", "SHARD_PACKRAT", 24, 0, 24, 33840.0, 0.0, 0, at - 600_000, at + 30000, BazaarStage.COLLECT, false, null, 24, 0)
    // Filling: a sell offer two thirds sold and an untouched buy order, as Manage Orders shows them.
    private val cactus = BazaarPosition("fixture-cactus", "ENCHANTED_CACTUS_GREEN", 647, 433, 214, 401_980.0, 0.0, 0, at - 10_800_000, at, BazaarStage.SELLING, false, null, 0, 214)
    private val chocobun = BazaarPosition("fixture-chocobun", "SHARD_CHOCOBUN", 0, 0, 0, null, 0.0, 0, at - 300_000, at - 300_000, BazaarStage.BUYING, false, null, 0, 0)
    // Held: a reconstructed stack with no offer. Stale: a buy order the last Manage Orders read did not show.
    private val gunpowder = BazaarPosition("fixture-gunpowder", "ENCHANTED_GUNPOWDER", 54, 0, 54, 294_310.0, 0.0, 0, at - 7_200_000, at - 3_600_000, BazaarStage.WAITING, true, null, 0, 0)
    private val pearl = BazaarPosition("fixture-pearl", "HEAVY_PEARL", 0, 0, 0, null, 0.0, 0, at - 86_400_000, at - 86_400_000, BazaarStage.BUYING, true, null, 0, 0)
    private var state = BazaarUiState(revision = 1, active = true, status = "Flipping on Hub", marketStatus = "Offline GUI fixture",
        source = "Offline GUI fixture", observedAt = System.currentTimeMillis() - 12_000, retrievedAt = System.currentTimeMillis() - 12_000,
        freshness = BazaarFreshness.FRESH, island = "Hub", purse = 2500000.0, committed = 125000.0,
        inventoryCost = 64000.0, inventoryValue = 76480.0, pendingCollection = 0.0, wizardDone = true,
        opportunities = listOf(
            BazaarOpportunity("ENCHANTED_CARROT", "Enchanted Carrot", 320, 200.1, 242.4, 13536.0, 969.6, 12566.4, .196, 64032.0, 45.0, 180.0,
                12000000.0, .78, 80.0, "Offline GUI fixture", at, listOf("Estimated top-of-book prices; queue position and fills are not guaranteed"), .015, .028),
            BazaarOpportunity("ENCHANTED_SUGAR", "Enchanted Sugar", 1500, 41.2, 47.9, 10050.0, 898.1, 9151.9, .148, 61800.0, 120.0, 900.0,
                4000000.0, .66, 60.0, "Offline GUI fixture", at, listOf("Sparse price history"), -.02, .05)),
        positions = listOf(position, packrat, cactus, chocobun, gunpowder, pearl),
        orders = listOf(
            BazaarOrder("fixture-order", "fixture-position", "ENCHANTED_CARROT", false, 640, 320, 320, 242.5, BazaarOrderStatus.OPEN, at, at + 60000, false),
            BazaarOrder("fixture-packrat-order", "fixture-packrat", "SHARD_PACKRAT", true, 24, 24, 0, 1410.0, BazaarOrderStatus.FILLED, at - 600_000, at + 30000, false),
            BazaarOrder("fixture-cactus-order", "fixture-cactus", "ENCHANTED_CACTUS_GREEN", false, 647, 433, 433, 1487.9, BazaarOrderStatus.OPEN, at - 10_800_000, at, false),
            BazaarOrder("fixture-chocobun-order", "fixture-chocobun", "SHARD_CHOCOBUN", true, 17, 0, 0, 1410.0, BazaarOrderStatus.OPEN, at - 300_000, at - 300_000, false),
            BazaarOrder("fixture-pearl-order", "fixture-pearl", "HEAVY_PEARL", true, 30, 0, 0, 279_189.0, BazaarOrderStatus.OPEN, at - 86_400_000, at - 86_400_000, true)),
        nextSteps = mapOf(position.id to BazaarUiStep("Relisted 7 times: compare waiting with selling instantly", true),
            packrat.id to BazaarUiStep("Claim 24 Packrat Shard from Manage Orders", true),
            cactus.id to BazaarUiStep("Sell offer open · 214 left to sell", false),
            chocobun.id to BazaarUiStep("Buy order open · 0/17 filled", false),
            gunpowder.id to BazaarUiStep("Holding 54 · no offer placed yet", false),
            pearl.id to BazaarUiStep("Buy order open · 0/30 filled", false)),
        guidance = "Enchanted Cactus Green · Sell offer open · 214 left to sell",
        history = listOf(trade), sessions = listOf(BazaarUiSession("fixture-session", "Hub", at, at + 120000, 12480.0, 1,
            listOf(BazaarUiPoint(at, 0.0), BazaarUiPoint(at + 60000, 12480.0)), listOf(trade))),
        pending = listOf(BazaarUiPending("fixture-pending", "Private raw receipt: ambiguous item and order"),
            BazaarUiPending("fixture-pending-order", "Private raw order details", kind = "BUY_ORDER", product = "ENCHANTED_CARROT", quantity = 64, unitPrice = 200.0)),
        awaiting = 1,
        stats = BazaarStats(12480.0, 12480.0, 64000.0, 76480.0, 1, 1, .195, 374400.0, 120000, 0),
        settings = mapOf("risk" to "BALANCED", "relistThreshold" to "6", "inGameAlerts" to "true", "discordAlerts" to "false", "dashboardAlerts" to "true",
            "webhookEnabled" to "false", "webhookConfigured" to "false", "settingsSync" to "false"),
        relistAdvice = mapOf(position.id to BazaarRelistAdvice(true, "Leave it / keep waiting", 76200.0, 75100.0, 11100.0, 64000.0, 64000.0, emptyList())),
        priceHistory = mapOf("ENCHANTED_CARROT" to listOf(BazaarUiPoint(at - 120000, 238.0), BazaarUiPoint(at - 60000, 240.0), BazaarUiPoint(at, 242.5))),
        names = mapOf("ENCHANTED_CARROT" to "Enchanted Carrot", "ENCHANTED_SUGAR" to "Enchanted Sugar", "ENCHANTED_CACTUS_GREEN" to "Enchanted Cactus Green",
            "SHARD_CHOCOBUN" to "Chocobun Shard", "SHARD_PACKRAT" to "Packrat Shard", "ENCHANTED_GUNPOWDER" to "Enchanted Gunpowder", "HEAVY_PEARL" to "Heavy Pearl"),
        ordersSyncedAt = synced, ordersSyncNote = "Read 4 orders · 1 by co-op members ignored",
        orderSeenAt = listOf("fixture-order", "fixture-packrat-order", "fixture-cactus-order", "fixture-chocobun-order").associateWith { synced })
    private var stage = 0
    private var ticks = 0
    private var syncedFromToolbar = false

    init {
        check(Minecraft.getInstance().level == null && Minecraft.getInstance().connection == null)
        for ((product, expected) in mapOf("ENCHANTED_CARROT" to "Enchanted Carrot", "ESSENCE_UNDEAD" to "Undead Essence",
            "SHARD_WIKI_TIKI" to "Wiki Tiki Shard", "ENCHANTMENT_ULTIMATE_WISE_1" to "Ultimate Wise")) {
            val name = bazaarGuideName(product)
            check(name?.contains(expected) == true && bazaarGuideCommand(name, 320) == "bz $name" && bazaarProductId(name) == product) {
                "Guide could not resolve the native Bazaar search name for $product: $name"
            }
        }
        BazaarUi.bridge = object : BazaarUiBridge {
            override fun snapshot() = state
            override fun action(name: String, fields: Map<String, String>): CompletableFuture<BazaarUiResult> {
                calls += name
                actionFields += name to fields.toMap()
                if (name == "toggle") state = state.copy(revision = state.revision + 1, active = !state.active)
                if (name == "confirm" && fields["ignore"] == "true") state = state.copy(revision = state.revision + 1,
                    pending = state.pending.filterNot { it.id == fields["id"] })
                return CompletableFuture.completedFuture(BazaarUiResult("Local fixture action: $name"))
            }
        }
    }

    fun screen() = BazaarScreen()

    fun advance(client: Minecraft): Boolean {
        if (++ticks < 8) return false
        val screen = client.screen as BazaarScreen
        val field = BaseOwoScreen::class.java.getDeclaredField("uiAdapter").apply { isAccessible = true }
        val root = (field.get(screen) as OwoUIAdapter<*>).rootComponent
        val components = arrayListOf<UIComponent>().apply { root.collectDescendants(this) }
        fun activate(control: UIComponent) {
            checkNotNull(root.focusHandler()).focus(control, UIComponent.FocusSource.KEYBOARD_CYCLE)
            screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
        }
        fun press(id: String) = activate(checkNotNull(components.singleOrNull { it.id() == id }) { "Missing control $id" })
        fun pressButton(title: String) = activate(components.filterIsInstance<MenuButton>().first { it.message.string == title })
        fun chooseRepairKind(title: String) {
            press("bazaar-repair-kind-fixture-pending")
            val opened = arrayListOf<UIComponent>().apply { root.collectDescendants(this) }
            activate(opened.filterIsInstance<MenuLabel>().single { it.text().string == title && it.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) })
        }
        fun repairInput(key: String) = components.filterIsInstance<MenuTextBox>().single { it.id() == "bazaar-repair-fixture-pending-$key" }
        fun checkDraft() {
            check(repairInput("product").value == "Enchanted Carrot" && repairInput("quantity").value == "320" && repairInput("coins").value == "64000") {
                "A review draft was lost when its classification, disclosure or snapshot changed"
            }
        }
        fun labels() = components.filterIsInstance<MenuLabel>().map { it.text().string }
        fun buttons() = components.filterIsInstance<MenuButton>().map { it.message.string }
        fun reviewCollapsed() = components.none { it.id()?.startsWith("bazaar-fix-") == true }
        when (stage++) {
            0 -> {
                check(labels().any { it == "Enchanted Carrot" } && labels().any { it == "Enchanted Sugar" })
                check(labels().none { it == "ENCHANTED_CARROT" || it.startsWith("Gross spread") }) { "Collapsed flip cards expose raw IDs or secondary analysis" }
                check(labels().any { it.startsWith("Market live") }) { "Market freshness line is missing" }
                check(labels().any { it == "NEXT STEP · FILLING" } && labels().any { it == "433/647 sold" }) { "Next step banner lacks its group and fill" }
                pressButton("Guide this flip")
            }
            1 -> {
                check(calls == listOf("guide") && actionFields.last() == ("guide" to mapOf("product" to "ENCHANTED_CARROT"))) {
                    "Guide must submit only its selected product through the bridge"
                }
                pressButton("Details")
            }
            2 -> {
                check(labels().any { it.startsWith("Gross spread") } && labels().any { it == "Sell price history" })
                press("bazaar-tab-positions")
            }
            3 -> {
                check(labels().any { it.startsWith("Relist check:") }) { "Seventh-relist advice was missing" }
                check(labels().any { it == "Where your coins are" } && "2 receipts need review" in buttons())
                check(labels().any { it.startsWith("→ Relisted 7 times") }) { "Next step was missing" }
                check(labels().any { it == "NEEDS YOU · 2" } && labels().any { it == "FILLING · 2" } && labels().any { it == "HELD · 1" }) {
                    "Positions are not grouped by what the player must do: ${labels()}"
                }
                check(labels().any { it.startsWith("Synced 2m ago · Read 4 orders") }) { "Sync status is missing next to Sync orders" }
                check(labels().any { it == "433/647" } && labels().any { it == "0/17" } && labels().any { it == "24" } && labels().any { it == "54" })
                check(labels().none { it == "Heavy Pearl" } && "Possibly stale · 1 position not in your last sync" in buttons()) {
                    "An order missing from the last sync must sit in the collapsed stale group"
                }
                check(components.none { it.id()?.startsWith("bazaar-repair-") == true } && labels().none { it.startsWith("Private raw") } && reviewCollapsed()) {
                    "Review must start as one collapsed summary row"
                }
                if (!syncedFromToolbar) { syncedFromToolbar = true; press("bazaar-open-orders"); stage-- }
                else {
                    check(actionFields.last() == ("open-orders" to emptyMap<String, String>()))
                    press("bazaar-review-toggle")
                }
            }
            4 -> { check(!reviewCollapsed() && components.none { it.id()?.startsWith("bazaar-repair-") == true }); press("bazaar-fix-fixture-pending") }
            5 -> { check(components.none { it.id()?.startsWith("bazaar-repair-fixture-pending-") == true }); chooseRepairKind("Bought items") }
            6 -> {
                check(labels().any { it == "Total paid (coins)" } && labels().none { it == "Known tax or fee (optional)" })
                repairInput("product").value = "Enchanted Carrot"; repairInput("quantity").value = "320"; repairInput("coins").value = "64000"
                state = state.copy(revision = state.revision + 1)
            }
            7 -> { checkDraft(); chooseRepairKind("Sold items") }
            8 -> { checkDraft(); check(labels().any { it == "Coins received after tax" }); press("bazaar-fix-fixture-pending") }
            9 -> { check(components.none { it.id()?.startsWith("bazaar-repair-") == true }); press("bazaar-fix-fixture-pending") }
            10 -> { checkDraft(); press("bazaar-dismiss-fixture-pending") }
            11 -> {
                check(actionFields.last() == ("confirm" to mapOf("id" to "fixture-pending", "ignore" to "true")))
                check("1 receipt needs review · hide" in buttons())
                press("bazaar-open-orders-fixture-pending-order")
            }
            12 -> { check(actionFields.last() == ("open-orders" to emptyMap<String, String>())); press("bazaar-review-toggle") }
            13 -> { check(reviewCollapsed() && "1 receipt needs review" in buttons()); press("bazaar-stale-toggle") }
            14 -> {
                check(labels().any { it == "Heavy Pearl" } && labels().any { it == "→ Buy order open · 0/30 filled" }) { "Stale group did not unfold" }
                press("bazaar-stale-toggle")
            }
            15 -> { check(labels().none { it == "Heavy Pearl" }); press("bazaar-open-hypixel") }
            16 -> { check(calls.last() == "open-bazaar"); press("bazaar-tab-history") }
            17 -> {
                check(labels().any { it == "Counts toward leaderboards" } && labels().any { it == "Realized profit" })
                press("bazaar-tab-replays")
            }
            18 -> {
                check(labels().any { it == "Hub" })
                state = state.copy(revision = state.revision + 1, sessions = emptyList(), publications = listOf(
                    BazaarUiPublication("fixture-publication", "recovered", "https://example.invalid/fixture-replay", "unlisted")))
            }
            19 -> {
                check(labels().any { it == "Published replay" } && labels().none { it == "Hub" }) {
                    "Deleting local replay history hid independent published-replay management"
                }
                check("Revoke link" in buttons() && "Delete" in buttons())
                press("bazaar-tab-more")
            }
            20 -> {
                check(labels().any { it == "Personal Discord webhook" } && labels().any { it == "Your data" } && components.any { it.id() == "bazaar-settings" })
                check(labels().any { it == "Bazaar Assistant" } && components.any { it.id() == "bazaar-assist-start" } &&
                    components.any { it.id() == "bazaar-assist-auto-command" } && components.any { it.id() == "bazaar-assist-auto-sign" }) {
                    "The Bazaar Assistant card is missing its start and opt-in controls"
                }
                check(!me.mycellium.skymyce.config.economy.BazaarAssistConfig.autoCommand && !me.mycellium.skymyce.config.economy.BazaarAssistConfig.autoSign) {
                    "Auto Command and Auto Sign must be off by default"
                }
                press("bazaar-toggle")
            }
            21 -> {
                check(!state.active && calls == listOf("guide", "open-orders", "confirm", "open-orders", "open-bazaar", "toggle"))
                check(components.filterIsInstance<MenuButton>().single { it.id() == "bazaar-toggle" }.message.string == "Start flipping")
                state = state.copy(revision = state.revision + 1, active = true)
                press("bazaar-tab-$finalTab")
            }
            22 -> {
                check(components.filterIsInstance<MenuButton>().all { it.width > 0 })
                check(client.level == null && client.connection == null && calls.none { it in setOf("publish", "delete-history", "webhook") })
                // Scenes that show the lower groups scroll the list once the final tab has rendered.
                if (scroll > 0) (components.single { it.id() == "bazaar-results" } as MenuScroll<*>).scrollBy(scroll, true, false)
            }
            // Let action toasts fade and the last state reach a rendered frame before the screenshot.
            in 23..125 -> Unit
            else -> return true
        }
        return false
    }

    override fun close() { BazaarUi.bridge = previous }
}
