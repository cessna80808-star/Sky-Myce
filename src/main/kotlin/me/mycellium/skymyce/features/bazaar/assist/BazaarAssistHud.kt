package me.mycellium.skymyce.features.bazaar.assist

import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.VerticalAlignment
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.features.bazaar.BazaarNames
import me.mycellium.skymyce.features.bazaar.bazaarSignedCoins
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The assistant panel and profit pop-ups. Both are normal HUD widgets: movable, scalable and resettable in the HUD editor. */
object BazaarAssistHud : SkyMyceModule() {
    private val icon by lazy { UIComponents.item(ItemStack(Items.EMERALD)) }
    private val title by lazy { hudLabel() }
    private val lines by lazy { hudColumn() }
    private val content by lazy {
        hudPanel().apply {
            child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(4).apply {
                verticalAlignment(VerticalAlignment.CENTER)
                allowOverflow(true)
                children(listOf(icon, title))
            })
            child(lines)
        }
    }
    private val popContent by lazy { hudColumn() }
    private val preview by lazy {
        hudPanel().updateTextLines(listOf("§bBazaar Assistant §7· §aActive", "§fEnchanted Carrot §7· Place buy order",
            "§eClick Top Order +0.1", "§7Outbids the current best buy order by 0.1 coins", "§7This run §a+1.4M §7· 2.1M/h"))
    }
    private val popPreview by lazy { hudColumn().updateTextLines(listOf("§a+182K profit §7· Enchanted Carrot")) }

    private var overScreen = false
    private var builtRevision = -1L
    private var iconProduct: String? = null
    private var shownProfit = 0.0
    private var profitAt = 0L

    val panel = Widget("bazaar_assistant", "Bazaar Assistant", 10, 60, 1f, previewBuilder = { preview }) {
        if (!visible()) return@Widget null
        rebuild()
        content
    }

    val popups = Widget("bazaar_assistant_pops", "Bazaar Assistant Profit", 10, 40, 1f, previewBuilder = { popPreview }) {
        if (!visible() || !Settings.profitNotices || BazaarAssist.pops.isEmpty()) return@Widget null
        val now = System.currentTimeMillis()
        val name = BazaarAssist.productName.orEmpty().take(24)
        popContent.updateTextLines(BazaarAssist.pops.map { "§a${bazaarSignedCoins(it.amount)} profit §7· $name" },
            BazaarAssist.pops.map { fade(now - it.at, 3500) })
    }

    /** Menus cover the HUD layer, so container and sign screens draw the panel again on top, never twice. */
    private fun visible(): Boolean {
        if (!isEnabled() || !Settings.enabled || !BazaarAssist.active) return false
        val screen = MC.screen
        return overScreen || (screen !is AbstractContainerScreen<*> && screen !is AbstractSignEditScreen)
    }

    fun drawOverScreen(graphics: GuiGraphicsExtractor, partialTicks: Float) {
        if (!Settings.panelInMenus || !BazaarAssist.active || MC.instance.options.hideGui) return
        overScreen = true
        try {
            graphics.nextStratum()
            panel.render(graphics, -1_000_000, -1_000_000, partialTicks)
            popups.render(graphics, -1_000_000, -1_000_000, partialTicks)
        } finally { overScreen = false }
    }

    private fun rebuild() {
        val now = System.currentTimeMillis()
        val run = BazaarAssist.runProfit()
        val counting = run != null && assistAnimated() && kotlin.math.abs(shownProfit - run.first) > 1
        if (builtRevision == BazaarAssist.revision && !counting && BazaarAssist.notice == null) return
        builtRevision = BazaarAssist.revision
        val product = BazaarAssist.product
        if (product != iconProduct) {
            iconProduct = product
            icon.stack(product?.let(BazaarNames::icon) ?: ItemStack(Items.EMERALD))
        }
        val status = BazaarAssist.status
        val statusColor = when (status) {
            AssistStatus.ACTIVE -> "§a"; AssistStatus.WAITING -> "§e"; AssistStatus.DONE -> "§b"
            AssistStatus.ERROR -> "§c"; AssistStatus.PAUSED, AssistStatus.OFF -> "§7"
        }
        title.updateText("§bBazaar Assistant §7· $statusColor${status.label}")
        val step = BazaarAssist.step
        val result = mutableListOf<String>()
        val opacities = mutableListOf<Double>()
        fun line(text: String, opacity: Double = 1.0) { result += text; opacities += opacity }
        BazaarAssist.productName?.let { name -> line("§f${name.take(28)}${BazaarAssist.phase?.let { " §7· ${it.label}" }.orEmpty()}") }
        if (step != null) {
            line("§e${step.instruction.take(70)}")
            if (step.kind == AssistKind.CLICK && step.slot == null && status == AssistStatus.ERROR)
                line("§c${step.target ?: "That button"} isn't on this page; no slot is highlighted")
            else if (step.hint.isNotEmpty()) line("§7${step.hint.take(70)}")
            if (step.command != null && step.kind in setOf(AssistKind.COMMAND, AssistKind.CLOSE)) {
                val key = BazaarAssist.commandKeyName()
                line(when {
                    Settings.autoCommand && key != null -> "§7Press §f$key§7 to send /${step.command}"
                    Settings.tabFill -> "§7Open chat and press §fTab§7 to fill /${step.command}"
                    else -> "§7Type /${step.command} in chat"
                })
            }
        } else if (status == AssistStatus.WAITING) line("§7Finding the best flip…")
        BazaarAssist.warning?.let { line("§c⚠ ${it.take(70)}") }
        BazaarAssist.notice?.let { notice ->
            val color = when (notice.tone) { AssistTone.SUCCESS -> "§a"; AssistTone.WARNING -> "§e"; AssistTone.ERROR -> "§c"; AssistTone.INFO -> "§b" }
            line("$color${notice.text.take(70)}", fade(now - notice.at, notice.until - notice.at))
        }
        if (run != null) {
            if (!assistAnimated()) shownProfit = run.first
            else {
                // Count toward the new total over about half a second.
                val elapsed = (now - profitAt).coerceIn(0, 100) / 100.0
                shownProfit += (run.first - shownProfit) * (.25 * elapsed * 4).coerceAtMost(1.0)
                if (kotlin.math.abs(run.first - shownProfit) < 1) shownProfit = run.first
            }
            profitAt = now
            line("§7This run §a${bazaarSignedCoins(shownProfit)}${run.second?.let { " §7· ${bazaarSignedCoins(it).removePrefix("+")}/h" }.orEmpty()}")
        } else shownProfit = 0.0
        lines.updateTextLines(result, opacities)
    }

    /** Fade in quickly and out over the last 400 ms; steady without animations. */
    private fun fade(age: Long, life: Long): Double {
        if (!assistAnimated()) return 1.0
        val fadeIn = (age / 150.0).coerceIn(0.0, 1.0)
        val fadeOut = ((life - age) / 400.0).coerceIn(0.0, 1.0)
        return minOf(fadeIn, fadeOut).coerceAtLeast(.05)
    }
}
