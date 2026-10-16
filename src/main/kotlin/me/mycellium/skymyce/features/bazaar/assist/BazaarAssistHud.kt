package me.mycellium.skymyce.features.bazaar.assist

import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.VerticalAlignment
import me.mycellium.mixin.BazaarAssistContainerAccessor
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.economy.AssistPanelPlacement
import me.mycellium.skymyce.config.economy.BazaarAssistConfig as Settings
import me.mycellium.skymyce.features.bazaar.BazaarNames
import me.mycellium.skymyce.features.bazaar.bazaarCompactCoins
import me.mycellium.skymyce.features.bazaar.bazaarSignedCoins
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
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
    private var dragging = false

    val panel = Widget("bazaar_assistant", "Bazaar Assistant", 630, 60, 1f, Anchor.TOP_RIGHT, previewBuilder = { preview }) {
        if (!visible()) return@Widget null
        rebuild()
        content
    }

    val popups = Widget("bazaar_assistant_pops", "Bazaar Assistant Profit", 630, 40, 1f, Anchor.TOP_RIGHT, previewBuilder = { popPreview }) {
        // Pops anchored to a clicked slot rise from that slot instead; this widget shows the rest.
        val shown = BazaarAssist.pops.filter { it.x == null }
        if (!visible() || !Settings.profitNotices || shown.isEmpty()) return@Widget null
        val now = System.currentTimeMillis()
        val name = BazaarAssist.productName.orEmpty().take(24)
        popContent.updateTextLines(shown.map { "${popColor(it.amount)}${bazaarSignedCoins(it.amount)} profit §7· $name" },
            shown.map { fade(now - it.at, BazaarAssist.POP_MILLIS) })
    }

    /** Menus cover the HUD layer, so container and sign screens draw the panel again on top, never twice. */
    private fun visible(): Boolean {
        if (!isEnabled() || !Settings.enabled || !BazaarAssist.active || !BazaarAssist.onSkyBlock) return false
        val screen = MC.screen
        return overScreen || (screen !is AbstractContainerScreen<*> && screen !is AbstractSignEditScreen)
    }

    fun drawOverScreen(screen: Screen, graphics: GuiGraphicsExtractor, partialTicks: Float) {
        if (!BazaarAssist.active || MC.instance.options.hideGui || !BazaarAssist.onSkyBlock) return
        overScreen = true
        try {
            graphics.nextStratum()
            if (Settings.panelInMenus) besideMenu(screen) { panel.render(graphics, -1_000_000, -1_000_000, partialTicks) }
            popups.render(graphics, -1_000_000, -1_000_000, partialTicks)
            if (screen is AbstractContainerScreen<*>) drawSlotPops(graphics)
        } finally { overScreen = false }
    }

    /** The last beside-the-menu placement as x, y, width, height; read by the in-game test. */
    internal var lastBeside: IntArray? = null
        private set

    /** Beside the open menu, shrunk to fit if needed, unless the player placed the panel; null uses the saved position. */
    private fun besidePlacement(screen: Screen): Pair<AssistPlacement, Int>? {
        val menu = screen as? BazaarAssistContainerAccessor ?: return null
        if (Settings.panelPlacement != AssistPanelPlacement.BESIDE_MENU) return null
        val natural = kotlin.math.ceil(panel.width / panel.scale.coerceAtLeast(.1f)).toInt().coerceAtLeast(120)
        val fit = assistFitBesideMenu(screen.width, menu.`skymyce$getLeftPos`(), menu.`skymyce$getImageWidth`(), natural, panel.scale,
            // Never smaller than one screen pixel per font pixel, so the text stays readable.
            minScale = 1f / MC.instance.window.guiScale.coerceAtLeast(1)) ?: return null
        return fit to menu.`skymyce$getTopPos`()
    }

    /** Draw at the beside-the-menu placement; the saved position and scale are restored right after drawing. */
    private inline fun besideMenu(screen: Screen, draw: () -> Unit) {
        val placement = if (dragging) null else besidePlacement(screen)
        if (placement == null) { lastBeside = null; draw(); return }
        val (fit, top) = placement
        val savedX = panel.x; val savedY = panel.y; val savedAnchor = panel.anchor; val savedScale = panel.scale
        panel.anchor = Anchor.TOP_LEFT; panel.x = fit.x; panel.y = top; panel.scale = fit.scale
        try { draw(); lastBeside = intArrayOf(fit.x, top, panel.width, panel.height) }
        finally { panel.x = savedX; panel.y = savedY; panel.anchor = savedAnchor; panel.scale = savedScale }
    }

    /** Drag the panel inside menus; the drop position (and any fitted size) is saved and used from then on. */
    fun attachDrag(screen: AbstractContainerScreen<*>) {
        var offsetX = 0.0
        var offsetY = 0.0
        ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
            if (!panel.isRenderable || !Settings.panelInMenus || !BazaarAssist.active || click.button() != 0) return@register true
            val beside = besidePlacement(screen)
            val bounds = if (beside != null) intArrayOf(beside.first.x, beside.second, lastBeside?.get(2) ?: 0, lastBeside?.get(3) ?: 0)
                else intArrayOf(panel.renderX, panel.renderY, panel.width, panel.height)
            if (click.x < bounds[0] || click.x >= bounds[0] + bounds[2] || click.y < bounds[1] || click.y >= bounds[1] + bounds[3]) return@register true
            if (beside != null) panel.scale = beside.first.scale
            panel.anchor = Anchor.TOP_LEFT; panel.x = bounds[0]; panel.y = bounds[1]
            offsetX = click.x - bounds[0]; offsetY = click.y - bounds[1]
            dragging = true
            false
        }
        ScreenMouseEvents.allowMouseDrag(screen).register { _, click, _, _ ->
            if (!dragging) return@register true
            panel.x = (click.x - offsetX).toInt().coerceIn(0, (screen.width - panel.width).coerceAtLeast(0))
            panel.y = (click.y - offsetY).toInt().coerceIn(0, (screen.height - panel.height).coerceAtLeast(0))
            false
        }
        ScreenMouseEvents.allowMouseRelease(screen).register { _, _ ->
            if (!dragging) return@register true
            dragging = false
            Settings.panelPlacement = AssistPanelPlacement.SAVED
            me.mycellium.skymyce.SkyMyce.config.save(); WidgetManager.save()
            false
        }
    }

    /** Profit rising from the slot that was clicked to claim it. */
    private fun drawSlotPops(graphics: GuiGraphicsExtractor) {
        if (!Settings.profitNotices) return
        val now = System.currentTimeMillis()
        val font = MC.instance.font
        for (pop in BazaarAssist.pops) {
            val x = pop.x ?: continue
            val y = pop.y ?: continue
            val age = now - pop.at
            if (age !in 0..BazaarAssist.POP_MILLIS) continue
            val rise = if (assistAnimated()) (age / BazaarAssist.POP_MILLIS.toDouble() * 18).toInt() else 0
            val text = bazaarSignedCoins(pop.amount)
            val alpha = (fade(age, BazaarAssist.POP_MILLIS) * 255).toInt().coerceIn(8, 255) shl 24
            val big = assistPopTone(pop.amount) == AssistPopTone.BIG
            graphics.pose().pushMatrix()
            graphics.pose().translate(x.toFloat(), (y - 4 - rise).toFloat())
            if (big) graphics.pose().scale(2f, 2f)
            graphics.centeredText(font, text, 0, -8, alpha or popRgb(pop.amount))
            graphics.pose().popMatrix()
        }
    }

    private fun popRgb(amount: Double) = when (assistPopTone(amount)) {
        AssistPopTone.LOSS -> 0xFF6B6B; AssistPopTone.SMALL -> 0xC8D0D8; AssistPopTone.GAIN -> 0xFFC857; AssistPopTone.BIG -> 0x7CE38B
    }
    private fun popColor(amount: Double) = when (assistPopTone(amount)) {
        AssistPopTone.LOSS -> "§c"; AssistPopTone.SMALL -> "§7"; AssistPopTone.GAIN -> "§e"; AssistPopTone.BIG -> "§a"
    }

    private fun rebuild() {
        val now = System.currentTimeMillis()
        val run = BazaarAssist.runProfit()
        val counting = run != null && assistAnimated() && kotlin.math.abs(shownProfit - run.profit) > 1
        val fading = assistAnimated() && now - BazaarAssist.stepChangedAt < STEP_FADE
        if (builtRevision == BazaarAssist.revision && !counting && !fading && BazaarAssist.notice == null) return
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
        fun line(text: String, opacity: Double = 1.0) { assistWrap(text).forEach { result += it; opacities += opacity } }
        BazaarAssist.productName?.let { name -> line("§f${name.take(28)}${BazaarAssist.phase?.let { " §7· ${it.label}" }.orEmpty()}") }
        if (step != null) {
            // A new step fades in, so the change is noticed without a jarring jump.
            val appear = if (assistAnimated()) ((now - BazaarAssist.stepChangedAt) / STEP_FADE.toDouble()).coerceIn(.15, 1.0) else 1.0
            line("§e${step.instruction}", appear)
            if (step.kind == AssistKind.CLICK && step.slot == null && status == AssistStatus.ERROR)
                line(if (BazaarAssist.targetMatches > 1) "§c${step.target} appears ${BazaarAssist.targetMatches} times here; nothing is highlighted, so check which one"
                    else "§c${step.target ?: "That button"} isn't on this page; nothing is highlighted", appear)
            else if (step.hint.isNotEmpty()) line("§7${step.hint}", appear)
            BazaarAssist.shownCommand?.let { command ->
                val key = BazaarAssist.commandKeyName()
                line(when {
                    Settings.autoCommand && key != null -> "§7Press §f$key§7 to send /$command"
                    Settings.tabFill -> "§7Open chat and press §fTab§7 to fill /$command"
                    else -> "§7Type /$command in chat"
                }, appear)
            }
        } else if (status == AssistStatus.WAITING) line("§7Finding the best flip…")
        BazaarAssist.warning?.let { line("§c⚠ $it") }
        BazaarAssist.notice?.let { notice ->
            val color = when (notice.tone) { AssistTone.SUCCESS -> "§a"; AssistTone.WARNING -> "§e"; AssistTone.ERROR -> "§c"; AssistTone.INFO -> "§b" }
            line("$color${notice.text}", fade(now - notice.at, notice.until - notice.at))
        }
        if (run != null) {
            if (!assistAnimated()) shownProfit = run.profit
            else {
                // Count toward the new total over about half a second.
                val elapsed = (now - profitAt).coerceIn(0, 100) / 100.0
                shownProfit += (run.profit - shownProfit) * elapsed.coerceAtMost(1.0)
                if (kotlin.math.abs(run.profit - shownProfit) < 1) shownProfit = run.profit
            }
            profitAt = now
            line("§7This run ${popColor(run.profit)}${bazaarSignedCoins(shownProfit)}" +
                (run.perHour?.let { " §7· ${bazaarCompactCoins(it)}/h" } ?: " §7· ${run.sales} sale${if (run.sales == 1) "" else "s"}"))
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

    private const val STEP_FADE = 180L
}
