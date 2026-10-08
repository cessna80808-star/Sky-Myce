package me.mycellium.skymyce.features.digest

import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.hud.themed
import io.wispforest.owo.ui.component.DropdownComponent
import net.minecraft.client.gui.screens.ChatScreen
import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.DailyDigestConfig
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Retained owo UI: rendering reads snapshots; refresh and storage belong to DailyDigest. */
class DailyDigestScreen(private var page: String? = null) : MenuScreen(Component.literal("Sky-Hawk Daily Digest"), MenuPage.DIGEST) {
    private var opened = false
    private var returningFromSettings = false
    private var previous = DigestView()
    private var lastSecond = -1L
    private var displayedDate = LocalDate.now()
    private var newsFilter = ""
    private var community = false
    private var search = ""
    private var activity = ""
    private var historyPage = 0
    private var builtLayout: DigestLayout? = null
    private val expanded = mutableSetOf<String>()
    private val cards = mutableMapOf<String, FlowLayout>()
    private val clocks = mutableListOf<Pair<String?, () -> Unit>>()
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var context: LabelComponent
    private lateinit var footer: LabelComponent
    private lateinit var refresh: ButtonComponent
    private val layout get() = DigestLayout.fit(menuWidth + 24, menuHeight + 24, DailyDigestConfig.compact).copy(width = menuWidth, height = menuHeight)
    private val accent get() = MenuTheme.ACCENT
    private val compactOverview get() = page == null && (DailyDigestConfig.compact || height < 300)
    private val mergedHeader get() = compactOverview && layout.width >= 400


    override fun init() {
        val restyle = returningFromSettings
        if (returningFromSettings) { returningFromSettings = false; DailyDigest.settingsChanged() }
        super.init()
        if (::results.isInitialized && (restyle || builtLayout != layout)) rebuild()
        if (!opened && !invalid) { opened = true; DailyDigest.screenOpened() }
    }

    override fun removed() {
        if (opened) { opened = false; DailyDigest.screenClosed() }
        super.removed()
    }

    override fun isPauseScreen() = false

    override fun buildContent(root: FlowLayout) {
        builtLayout = layout
        previous = DailyDigest.view()
        cards.clear()
        clocks.clear()
        root.surface(Surface.BLANK)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(UIContainers.verticalFlow(Sizing.fixed(layout.width), Sizing.fixed(layout.height)).apply {
            padding(Insets.of(layout.padding)); gap(layout.gap)
            surface(MenuTheme.panel())
            child(row().apply {
                child(label("SKY-HAWK  /  ${if (mergedHeader) "DIGEST" else pageTitle()}", accent).apply { horizontalSizing(Sizing.expand()) })
                if (mergedHeader) { child(refreshButton()); child(settingsButton()) }
                if (page != null) child(button("Back", 42) { navigate(null) }.tip("Return to the daily overview"))
                child(button("Close", 44) { onClose() })
            })
            context = label("${DATE.format(LocalDate.now())}  •  ${digestText(previous.context, 100)}", MenuTheme.MUTED)
            child(context)
            if (!mergedHeader) child(row().apply {
                child(refreshButton())
                child(settingsButton())
                if (page == "rng" && menuWidth >= 280) child(button("Clear local", 76) { confirmClear() })
            })
            if (page == "rng" && menuWidth < 280) child(button("Clear local", 76) { confirmClear() })
            if (page == "news") child(newsFilters())
            if (page == "rng") {
                child(row().apply {
                    child(button("Local history", 96) { if (community) { community = false; historyPage = 0; rebuildMenu(-1) } }
                        .apply { navigation = true; selected = { !community } })
                    child(button("Community", 96) { if (!community) { community = true; historyPage = 0; rebuildMenu(1) } }
                        .apply { navigation = true; selected = { community } }
                        .tip("Opt-in community reports are unverified. Enable receiving in Daily Digest settings."))
                })
                child(row().apply {
                    child(MenuTextBox(Sizing.expand()).apply {
                        setMaxLength(80); text(search); setHint(Component.literal("Search drop or player..."))
                        onChanged().subscribe { search = it; resetList() }
                    })
                    child(button(activity.ifEmpty { "All activities" }.take(17), 100) {
                        val options = listOf("") + previous.cards.firstOrNull { it.id == "rng" }?.entries.orEmpty()
                            .map { it.activity }.filter { it.isNotEmpty() }.distinct().sorted()
                        activity = options[(options.indexOf(activity) + 1) % options.size]
                        rebuild()
                    }.tip("Cycle activity filter: dungeons, slayer and other detected sources"))
                })
            }
            results = menuScroll(Sizing.fill(), Sizing.expand(), column()).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(accent)))
                scrollbarThiccness(2); padding(Insets.right(4)); scrollStep(20)
            }
            child(results)
            footer = label(footerText(previous), MenuTheme.MUTED)
            child(footer)
        })
        updateContent()
    }

    override fun tick() {
        super.tick()
        if (menuClosing) return
        if (!::refresh.isInitialized) return
        val state = DailyDigest.view()
        if (state.revision != previous.revision) {
            val old = previous
            previous = state
            setText(context, "${DATE.format(LocalDate.now())}  •  ${digestText(state.context, 100)}")
            setText(footer, footerText(state))
            if (page == null && old.cards.map { it.id } == state.cards.map { it.id }) {
                state.cards.forEach { card -> if (old.cards.firstOrNull { it.id == card.id } != card) updateCard(card) }
            } else if (page == null || old.cards.firstOrNull { it.id == page } != state.cards.firstOrNull { it.id == page }) {
                updateContent(animate = false)
            }
        }
        val busy = previous.cards.any { (page == null || it.id == page) && it.loading }
        refresh.active(!busy)
        val text = if (busy) "Refreshing" else "Refresh"
        if (refresh.message.string != text) refresh.message = Component.literal(text)
        val second = System.currentTimeMillis() / 1000
        if (lastSecond != second) {
            lastSecond = second
            val today = LocalDate.now()
            if (today != displayedDate) {
                displayedDate = today
                setText(context, "${DATE.format(today)}  •  ${digestText(previous.context, 100)}")
            }
            clocks.forEach { it.second() }
        }
    }

    private fun updateContent(animate: Boolean = true) {
        if (!::results.isInitialized) return
        if (animate) contentChanged()
        cards.clear()
        clocks.clear()
        results.child(column().apply {
            if (page == null) overview(this) else details(this, previous.cards.firstOrNull { it.id == page })
        })
    }

    private fun overview(target: FlowLayout) {
        val data = previous.cards
        if (data.isEmpty()) {
            target.child(empty("Your daily overview is getting ready", "Local data appears first. Network sources update independently."))
            return
        }
        data.chunked(layout.columns).forEach { group ->
            target.child(row().apply {
                gap(layout.gap)
                verticalAlignment(VerticalAlignment.TOP)
                group.forEach { state ->
                    val panel = card("digest/overview/${state.id}").apply { horizontalSizing(Sizing.fixed(layout.cardWidth)) }
                    panel.cursorStyle(CursorStyle.HAND)
                    panel.mouseDown().subscribe { click, _ ->
                        if (click.button() == 0) { navigate(state.id); true } else false
                    }
                    cards[state.id] = panel
                    child(panel)
                    populateCard(panel, state)
                }
            })
        }
    }

    private fun updateCard(state: DigestCardState) {
        cards[state.id]?.let { panel ->
            clocks.removeAll { it.first == state.id }
            panel.clearChildren()
            populateCard(panel, state)
        }
    }

    private fun populateCard(panel: FlowLayout, state: DigestCardState) {
        val nextEvent = if (state.id == "global") state.entries.filter { it.until != null }
            .minWithOrNull(compareBy<DigestEntry> { it.tone != DigestTone.POSITIVE }.thenBy { it.until }) else null
        if (compactOverview) { panel.padding(Insets.of(7)); panel.gap(5) }
        panel.surface(cardSurface().and(Surface { graphics, component ->
            graphics.fill(component.x(), component.y(), component.x() + 2, component.y() + component.height(),
                0xFF000000.toInt() or tone(state.tone))
        }))
        panel.child(button("${icon(state.id)}  ${digestText(state.title, 32)}  ›", 1) { navigate(state.id) }.apply {
            horizontalSizing(Sizing.fill()); if (compactOverview) verticalSizing(Sizing.fixed(18))
            tip("Open ${state.title}\n${state.summary}\n${state.status}")
        })
        val summary = if (state.id == "news" && state.entries.isNotEmpty()) {
            if (compactOverview) state.entries.first().title.let { if (it.length > 53) it.take(52).trimEnd() + "…" else it } else "Latest from Cowshed"
        } else if (compactOverview && state.id == "global" && nextEvent == null) state.summary + "\n" + digestText(state.status, 52) else state.summary
        panel.child(label(digestText(summary.ifBlank { "No information available yet." }, 240)))
        if (!compactOverview && state.id == "news" && state.entries.isNotEmpty()) state.entries.take(2).forEach { entry ->
            panel.child(label("• ${digestText(entry.title, 90)}", MenuTheme.MUTED))
        }
        if (nextEvent != null) panel.child(clock(tone(state.tone), nextEvent.until, state.id) {
            val title = digestText(nextEvent.title, 22).let { if (nextEvent.title.length > 22) "$it…" else it }
            "$title · ${digestTimeRemaining(nextEvent.until!!, System.currentTimeMillis()).removeSuffix(" remaining")}"
        }.tip("${nextEvent.title}\n${nextEvent.summary}\n${state.status}"))
        else if (!compactOverview) panel.child(label(digestText(if (state.loading) "Updating…" else state.status.ifBlank { "Open for details" }, 150), tone(state.tone)))
        if (state.id == "dailies" && state.entries.isNotEmpty()) panel.child(row().apply {
            gap(3)
            val count = state.entries.size.coerceAtMost(6)
            val segmentWidth = ((layout.cardWidth - (if (compactOverview) 14 else if (DailyDigestConfig.compact) 16 else 22) - (count - 1) * 3) / count).coerceAtLeast(1)
            state.entries.take(6).forEach { task -> child(UIContainers.verticalFlow(Sizing.fixed(segmentWidth), Sizing.fixed(3))
                .surface(Surface.flat(0xFF000000.toInt() or tone(task.tone))).tip("${task.title}: ${task.meta.ifBlank { task.summary }}")) }
        })
        if (!compactOverview) state.updatedAt?.let { updated -> panel.child(label("Updated ${digestRelativeTime(updated, System.currentTimeMillis())}", MenuTheme.MUTED)) }
    }

    private fun details(target: FlowLayout, state: DigestCardState?) {
        if (state == null) { target.child(empty("Waiting for information", "This source will appear when available.")); return }
        if (page != "rng") target.child(label(digestText(state.summary, 500)))
        if (state.status.isNotBlank()) target.child(label(digestText(state.status, 300), tone(state.tone)))
        state.updatedAt?.let { stamp -> target.child(clock(MenuTheme.MUTED) {
            "Last successful update ${digestRelativeTime(stamp, System.currentTimeMillis())}  •  ${timestamp(stamp)}"
        }) }
        if (page == "rng") {
            target.child(label(if (community) "Community-reported • unverified" else "Detected on this account and profile • stored locally", MenuTheme.PURPLE))
            if (community && !DailyDigestConfig.receiveRng) {
                target.child(empty("Community feed is off", "Enable receiving in Settings → Social & Sharing. Sharing your own drops is a separate option."))
                return
            }
        }
        val entries = when (page) {
            "rng" -> digestHistory(state.entries, community, activity, search)
            "news" -> state.entries.filter { newsFilter.isEmpty() || it.activity.contains(newsFilter, true) || it.meta.contains(newsFilter, true) }
            else -> state.entries
        }
        if (entries.isEmpty()) {
            target.child(empty(if (state.loading) "Loading updates…" else when (page) {
                "rng" -> "No matching RNG drops"
                "news" -> "No cached updates yet"
                else -> "Not detected yet"
            }, when (page) {
                "rng" -> "Only supported rare drops appear here. Ordinary loot and unrelated activity are never published."
                "news" -> "The dashboard remains available when the news service is offline. Try Refresh later."
                else -> "Visit the relevant activity or use a manual note where offered. Unknown never means complete."
            }))
            return
        }
        val perPage = if (page == "rng") 20 else 30
        val pages = ((entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        historyPage = historyPage.coerceIn(0, pages - 1)
        entries.drop(historyPage * perPage).take(perPage).forEach { entry -> target.child(entryCard(entry)) }
        if (pages > 1) target.child(row().apply {
            child(button("Previous", 70) { historyPage--; updateContent() }.apply { active(historyPage > 0) })
            child(label("${historyPage + 1} / $pages", MenuTheme.MUTED).apply { horizontalSizing(Sizing.expand()) })
            child(button("Next", 50) { historyPage++; updateContent() }.apply { active(historyPage + 1 < pages) })
        })
    }

    private fun entryCard(entry: DigestEntry) = card("digest/entry/${entry.id}").apply {
        child(label(digestText(entry.title, 300), tone(entry.tone)))
        if (entry.meta.isNotBlank()) child(label(digestText(entry.meta, 500), MenuTheme.MUTED))
        if (entry.summary.isNotBlank()) child(label(digestText(entry.summary, 1200)))
        entry.timestamp?.let { stamp -> child(clock(MenuTheme.MUTED) { "${timestamp(stamp)}  •  ${digestRelativeTime(stamp, System.currentTimeMillis())}" }) }
        entry.until?.let { until -> child(clock(tone(entry.tone), until) { digestTimeRemaining(until, System.currentTimeMillis()) }) }
        entry.slots.take(7).forEach { slot -> child(column().apply {
            padding(Insets.of(7)); gap(4); surface(MenuTheme.panel(true))
            child(label(digestText(slot.title, 200), tone(slot.tone)))
            if (slot.summary.isNotBlank()) child(label(digestText(slot.summary, 200), MenuTheme.MUTED))
            slot.until?.let { until -> child(clock(tone(slot.tone), until) { digestTimeRemaining(until, System.currentTimeMillis()) }) }
        }) }
        if (entry.detail.isNotBlank()) {
            if (page == "news") {
                val isExpanded = entry.id in expanded
                child(button(if (isExpanded) "Show less" else "Read update", 88) {
                    if (!expanded.add(entry.id)) expanded.remove(entry.id)
                    updateContent()
                })
                if (isExpanded) child(label(digestText(entry.detail, 12_000)))
            } else {
                tooltip(MC.font.split(Component.literal(digestText(entry.detail, 2000)), 240)
                    .map { net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent.create(it) })
            }
        }
        entry.manualId?.let { id -> child(button(if (entry.manualComplete) "Clear manual note" else "Mark done manually", 130) {
            DailyDigest.setManual(id, !entry.manualComplete)
        }.tip("A local note for this profile, clearly labelled as manual. It does not change the game or claim automatic verification.")) }
        digestLink(entry.url)?.let { uri -> child(button("Open source", 90) {
            navigateMenu(MenuConfirmScreen(this@DailyDigestScreen, "Open source in your browser?", uri.toString(), "Open browser") {
                net.minecraft.util.Util.getPlatform().openUri(uri)
            })
        }.tip("Open ${uri.host} in your browser after confirmation")) }
        if (page == "rng") child(MenuButton(Component.literal("Share…")) { anchor ->
            val report = digestShareText(entry)
            openMenuDropdown(anchor) { menu ->
                menu.button(Component.literal("Party chat…")) { navigateMenu(ChatScreen("/pc $report", false)) }
                menu.button(Component.literal("Guild chat…")) { navigateMenu(ChatScreen("/gc $report", false)) }
                menu.button(Component.literal("Copy report")) { MC.instance.keyboardHandler.clipboard = report; menu.parent()?.removeChild(menu) }
            }
        }.themed().apply { sizing(Sizing.fixed(66), Sizing.fixed(20)); tip("Choose Party, Guild or Copy. Chat opens a draft for you to review and send; nothing is sent automatically.") })
    }

    private fun newsFilters() = column().apply {
        listOf("" to "All", "game" to "Game updates", "alpha" to "Alpha updates").chunked(if (menuWidth < 280) 2 else 3).forEach { filters -> child(row().apply {
        filters.forEach { (value, title) ->
            child(button(title, if (value.isEmpty()) 34 else 96) {
                if (newsFilter != value) { newsFilter = value; historyPage = 0; rebuild() }
            }.apply { navigation = true; selected = { newsFilter == value } })
        }
        }) }
    }

    private fun confirmClear() {
        navigateMenu(MenuConfirmScreen(this, "Clear local RNG history?",
            "Remove the history saved for this account and profile. Already shared community reports are unaffected.", "Clear history") {
            DailyDigest.clearHistory()
        })
    }

    private fun refreshButton() = button("Refresh", if (mergedHeader) 54 else 64) { DailyDigest.refresh(page) }
        .tip("Refresh available sources asynchronously. Cached data stays visible and cooldowns prevent repeated requests.").also { refresh = it }

    private fun settingsButton() = button("Settings", if (mergedHeader) 54 else 64) {
        returningFromSettings = true
        navigateMenu(SettingsScreen.forFeature(this@DailyDigestScreen, "daily-digest"))
    }.tip("Utils → Progress → Daily Digest: automatic opening, news and RNG privacy")

    private fun navigate(next: String?) { page = next; historyPage = 0; rebuildMenu(if (next == null) -1 else 1) }
    private fun resetList() { historyPage = 0; updateContent(animate = false) }
    private fun rebuild() { rebuildMenu() }
    private fun pageTitle() = when (page) { "news" -> "DAILY NEWS"; "global" -> "GLOBAL STATUS"; "dailies" -> "PERSONAL DAILIES"; "rng" -> "RNG ACTIVITY"; else -> "DAILY DIGEST" }
    private fun footerText(view: DigestView) = digestText(view.status.ifBlank { "Local time • cached first • Tab to navigate • Esc to close" }, 200)
    private fun timestamp(value: Long) = TIME.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    private fun icon(id: String) = when (id) { "news" -> "≡"; "global" -> "◈"; "dailies" -> "✓"; else -> "✦" }
    private fun tone(value: DigestTone) = when (value) {
        DigestTone.POSITIVE -> MenuTheme.GREEN; DigestTone.WAITING -> MenuTheme.YELLOW; DigestTone.ATTENTION -> MenuTheme.RED
        DigestTone.INFO -> accent; DigestTone.SPECIAL -> MenuTheme.PURPLE; DigestTone.MUTED -> MenuTheme.MUTED
    }
    private fun clock(color: Int, until: Long? = null, owner: String? = null, value: () -> String): LabelComponent = label(value(), color).also { label ->
        var currentColor = color
        clocks += owner to {
            setText(label, value())
            val nextColor = if (until != null && until <= System.currentTimeMillis()) MenuTheme.GREEN else color
            if (currentColor != nextColor) { currentColor = nextColor; label.color(Color.ofRgb(nextColor)) }
        }
    }
    private fun <T : UIComponent> T.tip(text: String): T = apply {
        tooltip(MC.font.split(Component.literal(digestText(text, 2000)), minOf(240, (this@DailyDigestScreen.width - 32).coerceAtLeast(80)))
            .map { net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent.create(it) })
    }
    private fun setText(label: LabelComponent, value: String) { if (label.text().string != value) label.text(Component.literal(value)) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text)).apply {
        color(Color.ofRgb(color)); shadow(MenuTheme.SHADOW); horizontalSizing(Sizing.fill()); lineSpacing(3)
    }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(layout.gap) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); verticalAlignment(VerticalAlignment.CENTER) }
    private fun cardSurface(): Surface = MenuTheme.panel(true)
    private fun card(key: String) = MenuRevealCard(key, Sizing.fill(), Sizing.content()).apply {
        padding(Insets.of(if (DailyDigestConfig.compact) 8 else 11)); gap(7); surface(cardSurface())
    }
    private fun empty(title: String, body: String) = card("digest/empty/$page/$title").apply { child(label(title, MenuTheme.MUTED)); child(label(body, MenuTheme.MUTED)) }
    private fun button(text: String, width: Int, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply {
        sizing(Sizing.fixed(width), Sizing.fixed(20)); textShadow(false)
        themed()
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM")
        private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
    }
}
