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
internal class GuiVisualBazaarFixture(private val finalTab: String = "flips") : AutoCloseable {
    private val previous = BazaarUi.bridge
    private val calls = arrayListOf<String>()
    private val actionFields = arrayListOf<Pair<String, Map<String, String>>>()
    private val at = 1_792_147_200_000L
    private val trade = BazaarUiTrade("fixture-sale", "ENCHANTED_CARROT", at + 60000, 320, 12480.0, 64000.0, 1520.0, true, "OBSERVED")
    private val position = BazaarPosition("fixture-position", "ENCHANTED_CARROT", 640, 320, 320, 64000.0, 12480.0, 7,
        at, at + 60000, BazaarStage.SELLING, false, "fixture-session", 0, 320)
    private var state = BazaarUiState(revision = 1, active = true, status = "Flipping on Hub", marketStatus = "Offline GUI fixture",
        source = "Offline GUI fixture", observedAt = System.currentTimeMillis() - 12_000, retrievedAt = System.currentTimeMillis() - 12_000,
        freshness = BazaarFreshness.FRESH, island = "Hub", purse = 2500000.0, committed = 125000.0,
        inventoryCost = 64000.0, inventoryValue = 76480.0, pendingCollection = 0.0, wizardDone = true,
        opportunities = listOf(
            BazaarOpportunity("ENCHANTED_CARROT", "Enchanted Carrot", 320, 200.1, 242.4, 13536.0, 969.6, 12566.4, .196, 64032.0, 45.0, 180.0,
                12000000.0, .78, 80.0, "Offline GUI fixture", at, listOf("Estimated top-of-book prices; queue position and fills are not guaranteed"), .015, .028),
            BazaarOpportunity("ENCHANTED_SUGAR", "Enchanted Sugar", 1500, 41.2, 47.9, 10050.0, 898.1, 9151.9, .148, 61800.0, 120.0, 900.0,
                4000000.0, .66, 60.0, "Offline GUI fixture", at, listOf("Sparse price history"), -.02, .05)),
        positions = listOf(position),
        orders = listOf(BazaarOrder("fixture-order", "fixture-position", "ENCHANTED_CARROT", false, 640, 320, 320, 242.5, BazaarOrderStatus.OPEN, at, at + 60000, false)),
        nextSteps = mapOf(position.id to BazaarUiStep("Relisted 7 times: compare waiting with selling instantly", true)),
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
        names = mapOf("ENCHANTED_CARROT" to "Enchanted Carrot", "ENCHANTED_SUGAR" to "Enchanted Sugar"))
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
        when (stage++) {
            0 -> {
                check(labels().any { it == "Enchanted Carrot" } && labels().any { it == "Enchanted Sugar" })
                check(labels().none { it == "ENCHANTED_CARROT" || it.startsWith("Gross spread") }) { "Collapsed flip cards expose raw IDs or secondary analysis" }
                check(labels().any { it.startsWith("Market live") }) { "Market freshness line is missing" }
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
                check(labels().any { it == "Where your coins are" } && labels().any { it == "Needs your review (2)" })
                check(labels().any { it.startsWith("→ Relisted 7 times") }) { "Next step was missing" }
                check(components.none { it.id()?.startsWith("bazaar-repair-") == true } && labels().none { it.startsWith("Private raw") }) {
                    "Review cards must start compact with their repair form and raw receipt collapsed"
                }
                if (!syncedFromToolbar) { syncedFromToolbar = true; press("bazaar-open-orders"); stage-- }
                else {
                    check(actionFields.last() == ("open-orders" to emptyMap<String, String>()))
                    press("bazaar-fix-fixture-pending")
                }
            }
            4 -> { check(components.none { it.id()?.startsWith("bazaar-repair-fixture-pending-") == true }); chooseRepairKind("Bought items") }
            5 -> {
                check(labels().any { it == "Total paid (coins)" } && labels().none { it == "Known tax or fee (optional)" })
                repairInput("product").value = "Enchanted Carrot"; repairInput("quantity").value = "320"; repairInput("coins").value = "64000"
                state = state.copy(revision = state.revision + 1)
            }
            6 -> { checkDraft(); chooseRepairKind("Sold items") }
            7 -> { checkDraft(); check(labels().any { it == "Coins received after tax" }); press("bazaar-fix-fixture-pending") }
            8 -> { check(components.none { it.id()?.startsWith("bazaar-repair-") == true }); press("bazaar-fix-fixture-pending") }
            9 -> { checkDraft(); press("bazaar-dismiss-fixture-pending") }
            10 -> {
                check(actionFields.last() == ("confirm" to mapOf("id" to "fixture-pending", "ignore" to "true")))
                check(labels().any { it == "Needs your review (1)" })
                press("bazaar-open-orders-fixture-pending-order")
            }
            11 -> { check(actionFields.last() == ("open-orders" to emptyMap<String, String>())); press("bazaar-tab-history") }
            12 -> {
                check(labels().any { it == "Counts toward leaderboards" } && labels().any { it == "Realized profit" })
                press("bazaar-tab-replays")
            }
            13 -> {
                check(labels().any { it == "Hub" })
                state = state.copy(revision = state.revision + 1, sessions = emptyList(), publications = listOf(
                    BazaarUiPublication("fixture-publication", "recovered", "https://example.invalid/fixture-replay", "unlisted")))
            }
            14 -> {
                check(labels().any { it == "Published replay" } && labels().none { it == "Hub" }) {
                    "Deleting local replay history hid independent published-replay management"
                }
                val buttons = components.filterIsInstance<MenuButton>().map { it.message.string }
                check("Revoke link" in buttons && "Delete" in buttons)
                press("bazaar-tab-more")
            }
            15 -> {
                check(labels().any { it == "Personal Discord webhook" } && labels().any { it == "Your data" } && components.any { it.id() == "bazaar-settings" })
                press("bazaar-toggle")
            }
            16 -> {
                check(!state.active && calls == listOf("guide", "open-orders", "confirm", "open-orders", "toggle"))
                check(components.filterIsInstance<MenuButton>().single { it.id() == "bazaar-toggle" }.message.string == "Start flipping")
                state = state.copy(revision = state.revision + 1, active = true)
                press("bazaar-tab-$finalTab")
            }
            17 -> {
                check(components.filterIsInstance<MenuButton>().all { it.width > 0 })
                check(client.level == null && client.connection == null && calls.none { it in setOf("publish", "delete-history", "webhook") })
            }
            // Let action toasts fade and the last state reach a rendered frame before the screenshot.
            in 18..120 -> Unit
            else -> return true
        }
        return false
    }

    override fun close() { BazaarUi.bridge = previous }
}
