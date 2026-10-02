package me.mycellium.skymyce.features.general

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.features.general.auction.AuctionPreferences
import me.mycellium.skymyce.features.general.auction.AuctionSearch
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import me.mycellium.skymyce.features.general.auction.AUCTION_RARITIES
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

/** Retained cards, bounded visible item decoding and background filtering. No render-thread I/O. */
class AuctionHouseScreen : MenuScreen(Component.literal("Sky-Hawk Auction House"), MenuPage.AUCTIONS) {
    private var search = AuctionPreferences.value.search
    private var expanded = false
    private var selected: AuctionAPI.ActiveAuction? = null
    private var matches = emptyList<AuctionAPI.ActiveAuction>()
    private var snapshotVersion = -1L
    private var preferenceVersion = AuctionPreferences.revision
    private var queryRevision = 0L
    private var requestedRevision = -1L
    private var filterBusy = false
    private var changedAt = 0L
    private var closed = false
    private var cardsRevision = 0L
    private var lastSecond = -1L
    private lateinit var scroll: ScrollContainer<FlowLayout>
    private lateinit var status: LabelComponent
    private lateinit var count: LabelComponent
    private lateinit var pages: LabelComponent
    private val clocks = mutableListOf<Pair<LabelComponent, AuctionAPI.ActiveAuction>>()
    private val panelWidth get() = menuWidth
    private val contentWidth get() = panelWidth - 28

    override fun isPauseScreen() = false
    override fun init() { closed = false; super.init(); AuctionAPI.beginBrowserSession() }
    override fun removed() { closed = true; cardsRevision++; AuctionAPI.stopBrowserSession(); super.removed() }

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(column(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)).apply {
            surface(MenuTheme.panel()); padding(Insets.of(8)); gap(5)
            child(row().apply {
                child(label("SKY-HAWK / AUCTIONS", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
                child(button("Close", 42) { onClose() })
            })
            child(row().apply {
                child(MenuTextBox(Sizing.expand()).apply {
                    setMaxLength(160); text(search.query); setHint(Component.literal("Search name, enchantment or lore…"))
                    wrappedTooltip(Component.literal("Search the item name and available auction lore. All words must match."))
                    onChanged().subscribe { change(search.withQuery(it)) }
                })
                child(button(if (expanded) "Filters −" else "Filters +", 65) { expanded = !expanded; rebuild() })
            })
            child(row().apply {
                child(button("Refresh", 59) { AuctionAPI.beginBrowserSession(true) }.wrappedTooltip(Component.literal("Refresh API pages in the background. At most once a minute.")))
                child(button("Favorites", 65) { b -> menu(b) { drop ->
                    drop.button(Component.literal("Save current search (${AuctionPreferences.value.favorites.size}/8)")) {
                        AuctionPreferences.update(search, (AuctionPreferences.value.favorites + search).takeLast(8)); preferenceVersion = AuctionPreferences.revision
                    }
                    AuctionPreferences.value.favorites.forEachIndexed { i, saved ->
                        drop.button(Component.literal("${i + 1}. ${saved.query.ifBlank { saved.category ?: saved.listingType.label }.take(32)}")) { change(saved); rebuild() }
                    }
                    drop.button(Component.literal("Clear favorites")) { AuctionPreferences.update(search, emptyList()); preferenceVersion = AuctionPreferences.revision }
                } }.wrappedTooltip(Component.literal("Keep up to eight searches, including filters and sort order.")))
                child(button("Reset", 45) { change(AuctionSearch()); rebuild() })
            })
            if (expanded && height >= 340) child(menuScroll(Sizing.fill(), Sizing.fixed(76), filters()).apply { scrollStep(20) })
            status = label("", MenuTheme.MUTED); child(status)
            count = label("Searching cached auctions…", MenuTheme.MUTED); child(count)
            scroll = menuScroll(Sizing.fill(), Sizing.expand(), column()).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.ACCENT))); scrollbarThiccness(2); scrollStep(30)
            }
            child(scroll)
            child(row().apply {
                child(button("‹", 28) { changePage(-1) }); pages = label("").apply { horizontalSizing(Sizing.expand()) }; child(pages)
                child(button("›", 28) { changePage(1) })
                if (selected != null) child(button("Back to results", 100) { selected = null; rebuild() })
            })
        })
        renderResults(); updateStatus()
    }

    private fun filters() = column().apply {
        val controls = listOf(
            button(search.category ?: "All categories", 108) { b -> menu(b) { d ->
                (listOf<String?>(null) + listOf("weapon", "armor", "accessories", "consumables", "blocks", "misc")).forEach { category ->
                    d.button(Component.literal(category ?: "All categories")) { change(search.withCategory(category)); rebuild() }
                }
            } }.wrappedTooltip(Component.literal("Filter using Hypixel's item category.")),
            button(search.rarity ?: "All rarities", 108) { b -> menu(b) { d ->
                (listOf<String?>(null) + AUCTION_RARITIES).forEach { rarity ->
                    d.button(Component.literal(rarity ?: "All rarities")) { change(search.withRarity(rarity)); rebuild() }
                }
            } },
            button(search.listingType.label, 108) { b -> menu(b) { d -> AuctionSearch.ListingType.entries.forEach { type ->
                d.button(Component.literal(type.label)) { change(search.withListingType(type)); rebuild() }
            } } }.wrappedTooltip(Component.literal("BIN is a purchase price. Auctions show their current or starting bid.")),
            button(search.sort.label, 108) { b -> menu(b) { d -> AuctionSearch.Sort.entries.forEach { sort ->
                d.button(Component.literal(sort.label)) { change(search.withSort(sort)); rebuild() }
            } } },
        )
        controls.chunked((contentWidth / 112).coerceAtLeast(1)).forEach { child(row().apply { it.forEach(::child) }) }
        child(row().apply {
            child(priceField(true)); child(priceField(false))
        })
    }

    private fun priceField(minimum: Boolean) = MenuTextBox(Sizing.fill(48)).apply {
        setMaxLength(16); text((if (minimum) search.minPrice else search.maxPrice)?.toString().orEmpty())
        setHint(Component.literal(if (minimum) "Minimum coins" else "Maximum coins"))
        wrappedTooltip(Component.literal("Whole coins. Leave blank for no limit. Bid filters use the displayed bid, not a guaranteed purchase price."))
        onChanged().subscribe { input ->
            val value = input.toLongOrNull()?.takeIf { it in 0..9007199254740991L }
            if (input.isBlank() || value != null) change(if (minimum) search.copy(minPrice = value, page = 0) else search.copy(maxPrice = value, page = 0))
            else count.text(Component.literal("Enter a valid non-negative whole coin amount").withColor(MenuTheme.RED))
        }
    }

    private fun change(value: AuctionSearch) {
        search = value; selected = null; queryRevision++; changedAt = System.currentTimeMillis()
        AuctionPreferences.update(search); preferenceVersion = AuctionPreferences.revision
    }

    override fun tick() {
        super.tick()
        if (!::scroll.isInitialized) return
        if (preferenceVersion != AuctionPreferences.revision) {
            preferenceVersion = AuctionPreferences.revision; search = AuctionPreferences.value.search; queryRevision++; rebuild()
        }
        val snapshot = AuctionAPI.index.view
        val now = System.currentTimeMillis()
        if (snapshotVersion != snapshot.version) { snapshotVersion = snapshot.version; queryRevision++; updateStatus() }
        if (!filterBusy && requestedRevision != queryRevision && now - changedAt >= 150) {
            filterBusy = true
            val revision = queryRevision; val query = search; requestedRevision = revision
            Scheduling.schedule(0.milliseconds) {
                val found = query.results(snapshot.auctions)
                MC.instance.execute {
                    filterBusy = false
                    if (!closed && revision == queryRevision) {
                        val changed = matches != found
                        matches = found; renderResults(animate = changed)
                    }
                }
            }
        }
        if (now / 1000 != lastSecond) {
            lastSecond = now / 1000
            clocks.forEach { (label, auction) -> label.text(Component.literal(remaining(auction)).withColor(if (auction.expired) MenuTheme.RED else MenuTheme.MUTED)) }
            // Expired listings are removed off-thread, without rebuilding the list every second.
            if (lastSecond % 15 == 0L) queryRevision++
        }
    }

    private fun updateStatus() {
        val view = AuctionAPI.index.view
        status.text(Component.literal("${if (view.stale) "Stale • " else ""}${view.coverage}\n${view.message}").withColor(if (view.stale) MenuTheme.YELLOW else MenuTheme.MUTED))
    }

    private fun renderResults(direction: Int = 1, animate: Boolean = true) {
        if (animate) contentChanged(direction)
        cardsRevision++; clocks.clear()
        val pageCount = ((matches.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        search = search.copy(page = search.page.coerceIn(0, pageCount - 1))
        count.text(Component.literal("${matches.size} matches in ${AuctionAPI.index.view.auctions.size} indexed listings").withColor(MenuTheme.MUTED))
        pages.text(Component.literal("${search.page + 1} / $pageCount").withColor(MenuTheme.TEXT))
        if (expanded && height < 340) {
            scroll.child(filters()); pages.text(Component.literal("Collapse filters for results").withColor(MenuTheme.MUTED)); return
        }
        val content = column()
        val detail = selected
        if (detail != null) content.child(details(detail))
        else if (matches.isEmpty()) content.child(label(if (AuctionAPI.index.view.loading) "No matches yet • other API pages are still loading." else "No active matches. Adjust filters or refresh.", MenuTheme.MUTED))
        else {
            val columns = (contentWidth / 230).coerceIn(1, 3)
            val cardWidth = (contentWidth - (columns - 1) * 6) / columns
            matches.drop(search.page * PAGE_SIZE).take(PAGE_SIZE).chunked(columns).forEach { auctions ->
                content.child(row().apply { auctions.forEach { child(card(it, cardWidth)) } })
            }
        }
        scroll.child(content)
    }

    private fun card(auction: AuctionAPI.ActiveAuction, cardWidth: Int) = column(Sizing.fixed(cardWidth)).apply {
        surface(MenuTheme.panel(true)); padding(Insets.of(7)); gap(4)
        child(row().apply { child(icon(auction)); child(label(auction.name).apply { horizontalSizing(Sizing.expand()); wrappedTooltip(Component.literal(auction.name)) }) })
        child(label("${auction.rarity.replace('_', ' ')} • ${if (auction.isBin) "BIN" else "AUCTION"}", MenuTheme.MUTED))
        child(label("${auction.priceLabel}: ${NumberUtils.condense(auction.price)}", MenuTheme.GREEN))
        child(clock(auction))
        child(button("Inspect listing", cardWidth - 14) { selected = auction; rebuild() }.wrappedTooltip(Component.literal("Inspect seller, lore and available item data before opening Hypixel's listing.")))
    }

    private fun details(auction: AuctionAPI.ActiveAuction) = column().apply {
        surface(MenuTheme.panel(true)); padding(Insets.of(8)); gap(6)
        child(row().apply { child(icon(auction)); child(label(auction.name, MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) }) })
        child(label("${auction.priceLabel}: ${NumberUtils.condense(auction.price)} coins", MenuTheme.GREEN))
        child(clock(auction))
        child(label("Seller UUID: ${auction.auctioneer}", MenuTheme.MUTED))
        child(label("${auction.category} • ${auction.rarity}"))
        child(label(auction.lore.ifBlank { "Item lore unavailable." }))
        child(label("Hover the icon for available enchantments and upgrades. Seller identity is shown as its API UUID.", MenuTheme.MUTED))
        child(button("Open in Hypixel", 130) {
            if (LocationAPI.isOnSkyBlock && !auction.expired) sendCommand("viewauction ${auction.uuid}", false)
        }.apply { active(LocationAPI.isOnSkyBlock && !auction.expired) }.wrappedTooltip(Component.literal("Opens the server's normal listing. Purchases and bids still require Hypixel's confirmation.")))
    }

    private fun icon(auction: AuctionAPI.ActiveAuction): FlowLayout = column(Sizing.fixed(32)).apply {
        verticalSizing(Sizing.fixed(32))
        surface(MenuTheme.tintedPanel { MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 12) })
        padding(Insets.of(2))
        fun item(stack: net.minecraft.world.item.ItemStack) = MenuItem(me.mycellium.skymyce.utils.ItemUtils.displayStack(stack)).apply { sizing(Sizing.fixed(28), Sizing.fixed(28)) }
        val fallback = runCatching { Items.PAPER.defaultInstance }.getOrDefault(net.minecraft.world.item.ItemStack.EMPTY)
        child(item(fallback).wrappedTooltip(Component.literal("Loading item data…")))
        val revision = cardsRevision
        AuctionAPI.item(auction) { stack ->
            if (!closed && revision == cardsRevision) {
                clearChildren()
                child(item(if (stack.isEmpty) fallback else stack).apply {
                    if (!stack.isEmpty) setTooltipFromStack(true) else wrappedTooltip(Component.literal("Item definition unavailable; listing metadata is still usable."))
                })
            }
        }
        if (auction.encodedItem == null) { clearChildren(); child(item(fallback).wrappedTooltip(Component.literal("Item definition unavailable"))) }
    }

    private fun clock(auction: AuctionAPI.ActiveAuction) = label(remaining(auction), MenuTheme.MUTED).also { clocks += it to auction }
    private fun remaining(auction: AuctionAPI.ActiveAuction): String {
        val seconds = (auction.remaining / 1000).coerceAtLeast(0)
        return if (seconds == 0L) "Expired" else "Ends in ${seconds / 3600}h ${seconds / 60 % 60}m ${seconds % 60}s"
    }
    private fun changePage(delta: Int) { if (selected == null) { search = search.copy(page = (search.page + delta).coerceAtLeast(0)); renderResults(delta) } }
    private fun rebuild() { rebuildMenu() }
    private fun menu(button: ButtonComponent, entries: (DropdownComponent) -> Unit) = openMenuDropdown(button, entries)
    private fun column(w: Sizing = Sizing.fill(), h: Sizing = Sizing.content()) = UIContainers.verticalFlow(w, h).apply { gap(5) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).themed().apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
    companion object { private const val PAGE_SIZE = 24 }
}
