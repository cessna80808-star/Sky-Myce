package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget

/** Shows the most actionable open position while flipping; the keybind or /sm bazaar cycle steps through the rest. */
object BazaarHud : SkyMyceModule() {
    private val content by lazy { hudPanel() }
    private val preview by lazy {
        hudPanel().updateTextLines(listOf("§bBazaar · Enchanted Carrot §71/3", "§fSell offer open · 320 left to sell", "§7Cost 64K · relisted 2×",
            "§eClaim the coins for 320 sold from Manage Orders"))
    }
    private var selected = 0

    fun cycle() { selected++ }

    val widget = Widget("bazaar_flipping", "Bazaar Active Flip", 10, 135, 1f, previewBuilder = { preview }) {
        val state = BazaarTrading.snapshot()
        if (!isEnabled() || !state.active || !state.freshness.scannable) return@Widget null
        content.updateTextLines(bazaarHudLines(state, selected))
    }
}

internal fun bazaarHudLines(state: BazaarUiState, selected: Int): List<String> {
    val positions = bazaarPositionPriority(state)
    if (positions.isEmpty()) {
        val best = state.opportunities.firstOrNull()
        return listOfNotNull(
            "§bBazaar · flipping on ${state.island ?: "SkyBlock"}",
            best?.let { "§fTop flip: ${state.name(it.product).take(28)} §a${bazaarSignedCoins(it.netProfit)}" } ?: "§7No flips match your filters right now",
            state.guidance?.let { "§e${it.take(70)}" },
        )
    }
    val index = Math.floorMod(selected, positions.size)
    val position = positions[index]
    val step = state.nextSteps[position.id]
    val counter = if (positions.size > 1) " §7${index + 1}/${positions.size}" else ""
    val held = when (position.stage) {
        BazaarStage.BUYING -> "buying"
        BazaarStage.SELLING -> "${position.listed} left to sell"
        else -> "${position.remaining} held"
    }
    return listOfNotNull(
        "§bBazaar · ${state.name(position.product).take(28)}$counter",
        "§f${position.stage.label} · $held",
        "§7Cost ${bazaarCompactCoins(position.costBasis)}${if (position.relistCount > 0) " · relisted ${position.relistCount}×" else ""}" +
            if (position.realizedProfit != 0.0) " · ${bazaarSignedCoins(position.realizedProfit)}" else "",
        step?.takeIf { it.actionable }?.let { "§e${it.text.take(70)}" },
        "§7Needs a check: open Manage Orders".takeIf { position.uncertain && step?.actionable != true },
    )
}
