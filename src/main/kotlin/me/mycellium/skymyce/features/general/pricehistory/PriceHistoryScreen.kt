package me.mycellium.skymyce.features.general.pricehistory

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.StringUtil
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hourFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val axisTimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val axisDayFormat = DateTimeFormatter.ofPattern("MMM d")
private val axisMonthFormat = DateTimeFormatter.ofPattern("MMM yyyy")

/** One item's price graph over a chosen range; closing returns to the menu it was opened from. */
class PriceHistoryScreen(private val parent: Screen, private val tag: String, private val itemName: String,
                         private val market: PriceMarket, startRange: PriceRange) : MenuScreen(Component.literal("Price History")) {
    override val menuCategory get() = MenuCategory.ECONOMY
    override val menuParent get() = parent
    private var range = startRange
    private var shown: Result<PriceHistory>? = null
    private lateinit var body: FlowLayout
    private lateinit var chart: PriceChart
    private lateinit var status: MenuLabel
    private val rangeButtons = ArrayList<MenuButton>()

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(6)
        root.child(row().apply {
            child(label("PRICE HISTORY", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.content()) })
            child(label(itemName, MenuTheme.TEXT).apply { horizontalSizing(Sizing.expand()) })
            child(button("Search", 52) {
                // A pet's title carries its level; the server searches by the pet's name. Hypixel kicks for "illegal
                // characters" in a command, so formatting codes and symbols (Blazetekk™ Ham Radio) are left out.
                val name = StringUtil.stripColor(itemName).replace(Regex("^\\[Lvl \\d+] "), "")
                    .filter { it.code in 32..126 }.replace(Regex(" {2,}"), " ").trim()
                if (LocationAPI.isOnSkyBlock) sendCommand(if (market == PriceMarket.BAZAAR) "bz $name" else "ahs $name")
            }.apply { active(LocationAPI.isOnSkyBlock) }
                .wrappedTooltip(Component.literal(if (market == PriceMarket.BAZAAR) "Opens the Bazaar search for this item" else "Opens the Auction House search for this item")))
            child(button("Close", 46) { onClose() })
        })
        root.child(row().apply {
            rangeButtons.clear()
            PriceRange.entries.forEach { option ->
                child(button(option.title, 62) { range = option; shown = null; update() }.also {
                    it.selected = { range == option }; rangeButtons += it
                })
            }
            status = label("", MenuTheme.MUTED).apply { horizontalSizing(Sizing.expand()) }
            child(status)
        })
        body = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(4) }
        root.child(body)
        chart = PriceChart(market)
        root.child(chart)
        root.child(label("${if (market == PriceMarket.BAZAAR) "Bazaar buy and sell prices" else "Prices of sold auctions"} · data from Coflnet (sky.coflnet.com) · $tag",
            MenuTheme.MUTED).apply { fontSize = 8f })
        shown = null
        update()
    }

    override fun tick() {
        super.tick()
        if (!menuClosing && ::body.isInitialized && shown == null) update()
    }

    private fun update() {
        val result = PriceHistoryFeature.history(tag, market, range) {}
        if (result == null) { status.text(Component.literal("Loading ${range.title}…").withColor(MenuTheme.MUTED)); chart.history = null; return }
        shown = result
        body.clearChildren()
        val history = result.getOrNull()
        chart.history = history?.let { it.copy(samples = downsample(it.samples, 360, market)) }
        if (history == null) {
            status.text(Component.literal(result.exceptionOrNull()?.message ?: "Price history unavailable").withColor(MenuTheme.YELLOW))
            return
        }
        val first = history.samples.first().at; val last = history.samples.last().at
        val format = if (range == PriceRange.DAY || range == PriceRange.WEEK) hourFormat else dayFormat
        status.text(Component.literal("${format.format(local(first))} – ${format.format(local(last))} · ${history.samples.size} points")
            .withColor(MenuTheme.MUTED))
        body.child(row().apply {
            history.firstStats?.let { child(card(market.first, it, PriceChart.FIRST)) }
            history.secondStats?.let { child(card(market.second, it, PriceChart.SECOND)) }
            if (market == PriceMarket.BAZAAR && history.firstStats != null && history.secondStats != null) {
                val spread = history.firstStats.latest - history.secondStats.latest
                child(summary("Spread", formatCoins(spread), "%.1f%% of the sell price".format(java.util.Locale.ROOT, spread / history.secondStats.latest * 100)))
            }
            if (market == PriceMarket.AUCTION) child(summary("Sales", "%,d".format(java.util.Locale.ROOT, history.sales), "in ${range.title.lowercase()}"))
        })
    }

    private fun local(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())

    private fun card(title: String, stats: SeriesStats, color: Int) = UIContainers.verticalFlow(Sizing.fill(32), Sizing.content()).apply {
        gap(2); padding(Insets.of(5)); surface(MenuTheme.panel(true))
        child(label(title, color))
        child(MenuLabel(Component.literal(formatCoins(stats.latest)).withColor(MenuTheme.TEXT)).apply { fontSize = 14f; horizontalSizing(Sizing.fill()) })
        val change = stats.change
        child(MenuLabel(Component.literal("%+.1f%%".format(java.util.Locale.ROOT, change)).withColor(if (change >= 0) MenuTheme.GREEN else MenuTheme.RED)
            .append(Component.literal(" · low ${formatCoins(stats.low)} · high ${formatCoins(stats.high)} · avg ${formatCoins(stats.mean)}").withColor(MenuTheme.MUTED)))
            .apply { fontSize = 8f; horizontalSizing(Sizing.fill()) })
    }

    private fun summary(title: String, value: String, caption: String) = UIContainers.verticalFlow(Sizing.fill(32), Sizing.content()).apply {
        gap(2); padding(Insets.of(5)); surface(MenuTheme.panel(true))
        child(label(title, MenuTheme.MUTED))
        child(MenuLabel(Component.literal(value).withColor(MenuTheme.TEXT)).apply { fontSize = 14f; horizontalSizing(Sizing.fill()) })
        child(label(caption, MenuTheme.MUTED).apply { fontSize = 8f })
    }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, action: () -> Unit) =
        MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}

/** Canvas text needs an opaque ARGB colour; theme colours are plain RGB. */
private fun run(text: String, color: Int) = MenuCanvas.Run(text, MenuTheme.alpha(color))

/** Two price lines with a price axis, date labels and a crosshair that reads out the hovered point. */
internal class PriceChart(private val market: PriceMarket) : BaseUIComponent() {
    companion object {
        val FIRST get() = MenuTheme.YELLOW
        val SECOND get() = MenuTheme.ACCENT
    }
    var history: PriceHistory? = null

    init { sizing(Sizing.fill(), Sizing.expand()) }

    override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val canvas = (graphics as MenuGraphics).canvas
        canvas.roundedRect(x().toDouble(), y().toDouble(), width().toDouble(), height().toDouble(), 4.0, MenuTheme.alpha(MenuTheme.BORDER, 18))
        val samples = history?.samples ?: return
        val values = samples.flatMap { listOfNotNull(it.first, it.second) }
        if (samples.size < 2 || values.isEmpty()) return
        val axisWidth = 42.0; val bottom = 14.0; val top = 8.0
        val left = x() + axisWidth; val right = x() + width() - 8.0
        val plotTop = y() + top; val plotBottom = y() + height() - bottom
        if (right - left < 20 || plotBottom - plotTop < 20) return
        val low = values.min(); val high = values.max()
        val pad = ((high - low) * .08).coerceAtLeast(high * .01).coerceAtLeast(.1)
        val min = (low - pad).coerceAtLeast(0.0); val max = high + pad
        val start = samples.first().at; val span = (samples.last().at - start).coerceAtLeast(1)
        fun px(at: Long) = left + (at - start).toDouble() / span * (right - left)
        fun py(value: Double) = plotBottom - (value - min) / (max - min) * (plotBottom - plotTop)
        // Price grid with labels on the left.
        for (step in 0..4) {
            val value = min + (max - min) * step / 4
            val lineY = py(value)
            canvas.line(left, lineY, right, lineY, 1.0, MenuTheme.alpha(MenuTheme.BORDER, if (step == 0) 70 else 30))
            canvas.text(listOf(run(formatCoins(value), MenuTheme.MUTED)), x() + 4.0, lineY - 4, 8.0)
        }
        // Date labels under the plot.
        val dates = if (span <= 2 * 86_400_000L) axisTimeFormat else if (span <= 120 * 86_400_000L) axisDayFormat else axisMonthFormat
        for (step in 0..4) {
            val at = start + span * step / 4
            val text = dates.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
            val width = MenuCanvas.textWidth(text, size = 8.0)
            canvas.text(listOf(run(text, MenuTheme.MUTED)), (px(at) - width / 2).coerceIn(left, right - width), plotBottom + 3, 8.0)
        }
        fun series(value: (PriceSample) -> Double?, color: Int) {
            var previous: Pair<Double, Double>? = null
            for (sample in samples) {
                val v = value(sample) ?: continue
                val point = px(sample.at) to py(v)
                previous?.let { canvas.line(it.first, it.second, point.first, point.second, 1.5, MenuTheme.alpha(color)) }
                previous = point
            }
        }
        series({ it.second }, SECOND)
        series({ it.first }, FIRST)
        // Legend.
        var legendX = left + 4
        for ((name, color) in listOf(market.first to FIRST, market.second to SECOND)) {
            canvas.fill(legendX, plotTop + 3, 6.0, 6.0, MenuTheme.alpha(color))
            canvas.text(listOf(run(name, MenuTheme.TEXT)), legendX + 9, plotTop + 2, 8.0)
            legendX += 16 + MenuCanvas.textWidth(name, size = 8.0)
        }
        if (mouseX < left || mouseX > right || mouseY < plotTop || mouseY > plotBottom) return
        val hovered = samples.minBy { kotlin.math.abs(px(it.at) - mouseX) }
        val hoverX = px(hovered.at)
        canvas.line(hoverX, plotTop, hoverX, plotBottom, 1.0, MenuTheme.alpha(MenuTheme.TEXT, 35))
        hovered.first?.let { canvas.roundedRect(hoverX - 2, py(it) - 2, 4.0, 4.0, 2.0, MenuTheme.alpha(FIRST)) }
        hovered.second?.let { canvas.roundedRect(hoverX - 2, py(it) - 2, 4.0, 4.0, 2.0, MenuTheme.alpha(SECOND)) }
        val lines = listOfNotNull(
            run((if (span <= 14 * 86_400_000L) hourFormat else dayFormat).format(Instant.ofEpochMilli(hovered.at).atZone(ZoneId.systemDefault())), MenuTheme.MUTED),
            hovered.first?.let { run("${market.first}: ${"%,.1f".format(java.util.Locale.ROOT, it)}", FIRST) },
            hovered.second?.let { run("${market.second}: ${"%,.1f".format(java.util.Locale.ROOT, it)}", SECOND) },
            hovered.volume.takeIf { market == PriceMarket.AUCTION }?.let { run("Sales: $it", MenuTheme.TEXT) },
        )
        val boxWidth = lines.maxOf { MenuCanvas.textWidth(it.text, size = 8.0) } + 10
        val boxHeight = lines.size * 10.0 + 6
        val boxX = if (hoverX + 8 + boxWidth > right) hoverX - 8 - boxWidth else hoverX + 8
        val boxY = (mouseY - boxHeight / 2).coerceIn(plotTop, plotBottom - boxHeight)
        canvas.roundedRect(boxX, boxY, boxWidth, boxHeight, 3.0, MenuTheme.alpha(0x101318, 92))
        lines.forEachIndexed { index, line -> canvas.text(listOf(line), boxX + 5, boxY + 3 + index * 10.0, 8.0) }
    }
}
