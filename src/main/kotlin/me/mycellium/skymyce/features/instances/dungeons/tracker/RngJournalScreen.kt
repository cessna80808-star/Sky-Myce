package me.mycellium.skymyce.features.instances.dungeons.tracker

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.community.*
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Permanent local archive; opening this page needs neither a relay connection nor Discord. */
class RngJournalScreen : MenuScreen(Component.literal("Sky-Myce RNG Journal"), MenuPage.JOURNAL) {
    private var filter = AcquisitionRepository.journalPreferences
    private var view = JournalView()
    private var seenRevision = -1L
    private var seenContext: AcquisitionContext? = null
    @Volatile private var generation = 0L
    private var requested = -1L
    @Volatile private var closed = false
    private var edited = false
    private var page = 0
    private var charts = false
    private var expanded: String? = null
    private var lastDay = LocalDate.now(ZoneOffset.UTC)
    private var periodCounts = emptyMap<JournalPeriod, Long>()
    private var comparison: JournalCommunityComparison? = null
    private var comparisonLocalCount = 0L
    private var comparisonStatus = "Connect Discord to compare shared observations. Personal history works offline."
    private var comparisonKey = ""
    private var nextComparison = 0L
    @Volatile private var comparisonEpoch = 0L
    private companion object {
        val localWorker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce local journal").apply { isDaemon = true }
        })
        val comparisonWorker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce journal comparison").apply { isDaemon = true }
        })
    }
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var status: MenuLabel
    private lateinit var previousPage: MenuButton
    private lateinit var nextPage: MenuButton
    private lateinit var pageLabel: MenuLabel
    override fun isPauseScreen() = false
    override fun init() { closed = false; comparisonKey = ""; super.init() }
    override fun removed() { closed = true; generation++; localWorker.cancel(this); comparisonEpoch++; comparisonWorker.cancel(this); super.removed() }

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK)
        root.child(column().apply {
            id("journal-panel")
            sizing(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight)); padding(Insets.of(8)); surface(MenuTheme.panel())
            child(row().apply {
                child(label("RNG Journal", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()); fontSize = 12f })
                listOf("History" to false, "Trends" to true).forEach { (title, trends) ->
                    child(button(title, 48) {
                        charts = trends; comparisonKey = ""; comparisonEpoch++; comparisonWorker.cancel(this@RngJournalScreen); comparison = null
                        page = 0; expanded = null; renderResults()
                    }.apply { navigation = true; centeredNavigation = true; selected = { charts == trends } })
                }
            })
            child(MenuTextBox(Sizing.fill()).apply {
                id("journal-search")
                setMaxLength(100); text(this@RngJournalScreen.filter.query); setHint(Component.literal("Search drops or players…"))
                onChanged().subscribe { change(this@RngJournalScreen.filter.copy(query = it)) }
            })
            val controls = listOf(
                button(filter.period.title, 95) { b -> openMenuDropdown(b) { d -> JournalPeriod.entries.forEach { period ->
                    d.button(Component.literal(period.title)) { change(filter.copy(period = period)); rebuildMenu() }
                } } },
                button(scopeTitle(filter.scope), 100) { b -> openMenuDropdown(b) { d ->
                    TrackerScope.entries.forEach { scope -> d.button(Component.literal(scopeTitle(scope))) {
                        change(filter.copy(scope = scope, includeLegacy = filter.includeLegacy || scope == TrackerScope.UNSCOPED_LEGACY)); rebuildMenu()
                    } }
                } },
                button(filter.activity?.replaceFirstChar(Char::uppercase) ?: "All activities", 96) { b -> openMenuDropdown(b) { d ->
                    (listOf<String?>(null) + DigestRngParser.catalog.values.map { it.activity }.distinct()).forEach { activity ->
                        d.button(Component.literal(activity?.replaceFirstChar(Char::uppercase) ?: "All activities")) { change(filter.copy(activity = activity)); rebuildMenu() }
                    }
                } },
                button(filter.floor ?: "All floors", 72) { b -> openMenuDropdown(b) { d ->
                    (listOf<String?>(null) + (1..7).flatMap { listOf("F$it", "M$it") }).forEach { floor ->
                        d.button(Component.literal(floor ?: "All floors")) { change(filter.copy(floor = floor)); rebuildMenu() }
                    }
                } },
                button(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓", 72) { change(filter.copy(oldestFirst = !filter.oldestFirst)); rebuildMenu() },
                button(if (filter.includeLegacy) "Imported ✓" else "Imported off", 78) { change(filter.copy(includeLegacy = !filter.includeLegacy)); rebuildMenu() }
                    .wrappedTooltip(Component.literal("Include older saved reports"))
            )
            var line = row().apply { id("journal-controls-0") }
            var used = 0
            controls.forEachIndexed { index, control ->
                val width = control.horizontalSizing().get().value
                if (used > 0 && used + 4 + width > menuWidth - 16) {
                    child(line); line = row().apply { id("journal-controls-$index") }; used = 0
                }
                line.child(control); used += width + if (used == 0) 0 else 4
            }
            child(line)
            if (filter.period == JournalPeriod.CUSTOM) child(row().apply { child(dateField(true)); child(dateField(false)) })
            status = label("Loading local archive…", MenuTheme.MUTED).apply { id("journal-status"); fontSize = 9f }; child(status)
            results = menuScroll(Sizing.fill(), Sizing.expand(), column()).apply { id("journal-results"); scrollStep(24); scrollbarThiccness(2) }; child(results)
            child(row().apply {
                previousPage = button("‹", 28) { page = (page - 1).coerceAtLeast(0); expanded = null; renderResults() }.apply { id("journal-previous") }; child(previousPage)
                pageLabel = label("").apply { id("journal-page"); horizontalSizing(Sizing.expand()); horizontalTextAlignment(HorizontalAlignment.CENTER) }; child(pageLabel)
                nextPage = button("›", 28) { page++; expanded = null; renderResults() }.apply { id("journal-next") }; child(nextPage)
                child(button("Clear filters", 78) {
                    change(filter.copy(period = JournalPeriod.ALL_TIME, query = "", activity = null, floor = null, from = "", until = "")); rebuildMenu()
                }.apply { id("journal-clear") }.wrappedTooltip(Component.literal("Keep saved drops and selected scope")))
            })
        })
        renderResults()
    }
    private fun dateField(start: Boolean) = MenuTextBox(Sizing.fill(48)).apply {
        setMaxLength(10); text(if (start) this@RngJournalScreen.filter.from else this@RngJournalScreen.filter.until)
        setHint(Component.literal(if (start) "From YYYY-MM-DD" else "To YYYY-MM-DD"))
        onChanged().subscribe { value ->
            if (value.isEmpty() || runCatching { LocalDate.parse(value) }.isSuccess) {
                val next = if (start) this@RngJournalScreen.filter.copy(from = value) else this@RngJournalScreen.filter.copy(until = value)
                if (next.from.isEmpty() || next.until.isEmpty() || next.from <= next.until) change(next)
                else status.text(Component.literal("Start date must not follow end date").withColor(MenuTheme.YELLOW))
            } else status.text(Component.literal("Use a valid UTC YYYY-MM-DD date").withColor(MenuTheme.YELLOW))
        }
    }
    private fun change(next: JournalFilter) {
        filter = next; edited = true; page = 0; expanded = null; generation++; comparisonKey = ""; comparisonEpoch++
        comparisonWorker.cancel(this); comparison = null; AcquisitionRepository.rememberJournal(next)
    }
    override fun tick() {
        super.tick()
        if (menuClosing || !::results.isInitialized) return
        val current = AcquisitionRepository.currentContext()
        val day = LocalDate.now(ZoneOffset.UTC)
        if (!edited && filter != AcquisitionRepository.journalPreferences) { filter = AcquisitionRepository.journalPreferences; generation++; rebuildMenu() }
        if (seenRevision != AcquisitionRepository.revision || seenContext != current || lastDay != day) {
            seenRevision = AcquisitionRepository.revision; seenContext = current; lastDay = day; generation++
        }
        if (charts) updateComparison()
        if (requested == generation) return
        val token = generation; requested = token
        val archive = AcquisitionRepository.state; val query = filter; val now = System.currentTimeMillis()
        localWorker.submit(this, { !closed && token == generation }, {
            rngJournal(archive, query, current, now) to journalPeriodCounts(archive, query, current, now)
        }) { computed ->
            MC.instance.execute {
                if (closed || menuClosing || token != generation) return@execute
                computed.onSuccess { (journal, periods) -> view = journal; periodCounts = periods; renderResults() }
                    .onFailure { status.text(Component.literal("Local journal could not be queried").withColor(MenuTheme.RED)) }
            }
        }
    }
    private fun renderResults() {
        if (!::results.isInitialized) return
        val focus = uiAdapter.rootComponent.focusHandler()
        val focusedId = focus?.focused()?.id()?.takeIf { it.startsWith("journal-details-toggle-") }
        val pages = ((view.events.size + 19) / 20).coerceAtLeast(1); page = page.coerceIn(0, pages - 1)
        val warning = AcquisitionRepository.status.takeUnless { it == "Confirmed tracking • historical profit preserved separately" }
        status.text(Component.literal(listOfNotNull("${view.events.size} entries", warning).joinToString(" · ")).withColor(MenuTheme.MUTED))
        previousPage.active(page > 0); nextPage.active(page + 1 < pages)
        pageLabel.text(Component.literal("${page + 1} / $pages").withColor(MenuTheme.MUTED))
        val content = column().apply {
            if (charts) trends(this) else {
                if (view.events.isEmpty()) child(label(if (AcquisitionRepository.currentContext() == null && filter.scope == TrackerScope.CURRENT_PROFILE)
                    "Choose All profiles to view saved drops offline." else "No drops match these filters.", MenuTheme.MUTED))
                var day = ""
                view.events.drop(page * 20).take(20).forEach { event ->
                    val time = Instant.ofEpochMilli(event.occurredAt).atZone(ZoneOffset.UTC)
                    val date = time.toLocalDate().toString()
                    if (date != day) {
                        day = date
                        child(label("$date UTC", MenuTheme.SECONDARY).apply { fontSize = 9f; margins(Insets.top(5)) })
                    }
                    child(column().apply {
                        id("journal-event-${event.id}"); gap(2); surface(MenuTheme.panel(true)); padding(Insets.of(4))
                        val wide = menuWidth >= 560
                        val context = listOfNotNull(time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            event.floor ?: event.activity.replaceFirstChar(Char::uppercase),
                            event.chest?.lowercase(java.util.Locale.ROOT)?.replaceFirstChar(Char::uppercase),
                            if (event.origin != "confirmed") "Imported" else null)
                        val metadata = label(context.joinToString(" · "), MenuTheme.MUTED).apply {
                            fontSize = 9f; horizontalSizing(if (wide) Sizing.fixed(145) else Sizing.expand())
                        }
                        val detailsButton = button(if (expanded == event.id) "Hide" else "Details", 44) {
                            expanded = event.id.takeUnless { expanded == it }; renderResults()
                        }.apply { id("journal-details-toggle-${event.id}"); verticalSizing(Sizing.fixed(18)) }
                        child(row().apply {
                            child(label("${event.displayName ?: DigestRngCatalog.name(event.item)} ×${event.quantity}", MenuTheme.ACCENT)
                                .apply { horizontalSizing(Sizing.expand()) })
                            if (wide) child(metadata)
                            child(label(event.value?.let { "${money(it)} est." } ?: "Unpriced", MenuTheme.SECONDARY).apply {
                                horizontalSizing(Sizing.fixed(76)); horizontalTextAlignment(HorizontalAlignment.RIGHT)
                                wrappedTooltip(Component.literal("Recorded loot estimate"))
                            })
                            if (wide) child(detailsButton)
                        })
                        if (!wide) child(row().apply { child(metadata); child(detailsButton) })
                        if (expanded == event.id) child(details(event))
                    })
                }
            }
        }
        results.child(content)
        if (focusedId != null) {
            val children = arrayListOf<UIComponent>()
            content.collectDescendants(children)
            children.firstOrNull { it.id() == focusedId }?.let { focus.focus(it, UIComponent.FocusSource.KEYBOARD_CYCLE) }
        }
    }
    private fun details(event: AcquisitionEvent) = column().apply {
        id("journal-details-${event.id}"); gap(3); padding(Insets.top(5))
        val lines = listOfNotNull(
            "Recorded: ${timestamp(event.occurredAt)}",
            "${event.player ?: "Player not recorded"} · ${event.context?.profileName ?: "Unscoped"}",
            "Recorded estimate: ${money(event.value)} · Chest cost: ${money(event.chestCost)}" +
                (event.rerollCost?.let { " · Rerolls: ${money(it)}" } ?: ""),
            "Item: ${event.item} · Rarity: ${event.rarity ?: "Unknown"}",
            event.valuationAt?.let { "Estimate evaluated: ${timestamp(it)} · Market cache age unknown" } ?: "Valuation date not saved",
            event.run?.let { run -> "Run: ${run.take(8)}" + (view.runCounts[run]?.let { " · $it observed drops" } ?: "") },
            if (event.origin == "confirmed") "Source: Local confirmation · Community publication unverified"
                else "Source: ${event.origin} · Imported report"
        )
        lines.forEach { child(label(it, MenuTheme.MUTED).apply { fontSize = 9f }) }
    }
    private fun timestamp(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
    private fun trends(content: FlowLayout) {
        content.child(label("Observed drop counts · selected scope and period", MenuTheme.SECONDARY))
        content.child(label("Same scope and filters · UTC period comparison", MenuTheme.SECONDARY))
        if (filter.scope == TrackerScope.CURRENT_PROFILE && AcquisitionRepository.currentContext() == null)
            content.child(label("Current profile unavailable. Choose All local profiles to compare saved personal observations.", MenuTheme.MUTED))
        else listOf(JournalPeriod.ALL_TIME, JournalPeriod.WEEK, JournalPeriod.MONTH).forEach { period ->
            content.child(label("${period.title}: ${periodCounts[period] ?: 0} confirmed observed drops", MenuTheme.MUTED))
        }
        content.child(label("${view.counts.values.sum()} drops across ${view.observedRuns} recorded completed dungeon runs. ${view.legacyReports} legacy reports excluded from graphs. This is observed coverage, not game drop probability.", MenuTheme.MUTED))
        content.child(label("Known acquisition-time estimate: ${money(view.knownValue)} · ${view.unpriced} unpriced reports. Missing values are not zero.", MenuTheme.MUTED))
        if (view.bars.isEmpty()) content.child(label("No confirmed observations to graph for this period.", MenuTheme.MUTED))
        else {
            content.child(label("Latest ${view.bars.size} observed UTC days (days without observations omitted)", MenuTheme.MUTED))
            val maximum = view.bars.maxOf { it.count }.coerceAtLeast(1)
            view.bars.forEach { bar -> content.child(column().apply {
                child(label("${bar.date}: ${bar.count}", MenuTheme.MUTED))
                child(UIComponents.box(Sizing.fixed(((menuWidth - 40).coerceAtLeast(1) * bar.count / maximum).toInt().coerceAtLeast(1)), Sizing.fixed(5)).color(Color.ofRgb(MenuTheme.ACCENT)))
            }) }
        }
        content.child(label("Distribution by supported item", MenuTheme.SECONDARY))
        view.counts.entries.sortedByDescending { it.value }.forEach { (item, count) -> content.child(label("${DigestRngCatalog.name(item)}: $count", MenuTheme.MUTED)) }
        content.child(label("Observed rarity distribution", MenuTheme.SECONDARY))
        if (view.rarityCounts.isEmpty()) content.child(label("No recorded rarity samples. Historical metadata is not inferred.", MenuTheme.MUTED))
        val largestRarity = view.rarityCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1L
        view.rarityCounts.entries.sortedByDescending { it.value }.forEach { (rarity, count) ->
            content.child(label("${if (rarity == "UNKNOWN") "Unknown / not recorded" else rarity.replace('_', ' ')}: $count drops", MenuTheme.MUTED))
            content.child(UIComponents.box(Sizing.fixed(((menuWidth - 40).coerceAtLeast(1) * count / largestRarity).toInt().coerceAtLeast(1)), Sizing.fixed(5)).color(Color.ofRgb(MenuTheme.ACCENT)))
        }
        content.child(label("${view.runCounts.size} runs linked to a recorded drop. Unlinked reports are retained without guessing a run.", MenuTheme.MUTED))
        content.child(label("Latest ${view.runComparisons.size} linked runs · actual recorded coverage", MenuTheme.SECONDARY))
        view.runComparisons.forEach { run -> content.child(label(
            "${run.floor ?: "Unknown floor"} · ${run.run.take(8)} · ${run.dropCount} drops · ${run.duration?.let { "${it / 1000}s recorded duration" } ?: "duration unknown"} · " +
                "known value ${money(run.knownValue)} (${run.unpricedReports} unpriced) · estimated chest profit ${money(run.estimatedProfit)}", MenuTheme.MUTED)) }
        communityComparison(content)
    }
    private fun updateComparison() {
        val period = journalCommunityPeriod(filter)
        val token = CommunityClient.sessionToken; val identity = CommunityClient.identity?.deepCopy()
        val ownerGeneration = CommunityClient.sessionGeneration
        val minecraftOwner = MC.instance.user.profileId.toString().replace("-", "")
        val key = "$period/$ownerGeneration/$minecraftOwner/${identity?.get("uuid")}/$token/${AcquisitionRepository.revision}"
        if (key == comparisonKey && System.currentTimeMillis() < nextComparison) return
        comparisonKey = key; nextComparison = Long.MAX_VALUE; comparison = null
        val boundary = ++comparisonEpoch; comparisonWorker.cancel(this)
        if (period == null || !CommunityClient.connected || token == null || identity == null) {
            comparisonStatus = if (period == null) "Comparison requires an all-time/week/month period, all activities/floors, no search and no legacy records."
                else "Connect Discord to compare shared observations. Personal history works offline."
            renderResults(); return
        }
        val owner = identity["uuid"]?.asString
        if (owner?.matches(Regex("[a-f0-9]{32}")) != true || owner != minecraftOwner) {
            comparisonStatus = "Connected Minecraft identity unavailable. Reconnect the selected account."; renderResults(); return
        }
        comparisonStatus = "Loading consent-filtered community comparison…"; renderResults()
        val archive = AcquisitionRepository.state
        fun currentOwner() = !closed && charts && CommunityClient.connected && owner == MC.instance.user.profileId.toString().replace("-", "") &&
            boundary == comparisonEpoch && token == CommunityClient.sessionToken &&
            ownerGeneration == CommunityClient.sessionGeneration && identity == CommunityClient.identity
        comparisonWorker.submit(this, ::currentOwner, {
            val data = parseJournalCommunityComparison(CommunityClient.exchange("/community/journal-comparison?period=$period", null, "GET", token), period)
            data to journalComparableLocalCount(archive, owner, data)
        }) { loaded -> MC.instance.execute {
            if (!currentOwner() || menuClosing) return@execute
            nextComparison = System.currentTimeMillis() + 60_000
            loaded.fold({ (data, local) -> comparison = data; comparisonLocalCount = local; comparisonStatus = "Same supported-drop metric and UTC window; sample coverage differs." },
                { comparisonStatus = "Community comparison unavailable. Personal counts remain available; retry in one minute." })
            renderResults()
        } }
    }
    private fun communityComparison(content: FlowLayout) {
        content.child(label("Community comparison · current Minecraft account", MenuTheme.SECONDARY))
        content.child(label(comparisonStatus, MenuTheme.MUTED))
        val data = comparison ?: return
        content.child(label("All profiles and servers for this Minecraft account: $comparisonLocalCount locally confirmed acquisition reports · ${data.ownSharedCount?.toString() ?: "unavailable / sharing disabled"} accepted shared reports", MenuTheme.MUTED))
        content.child(label("${data.participants} contributing Minecraft identities · ${data.totalDrops} accepted shared reports · mean ${data.mean?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "unavailable"} per contributing identity. One acquisition counts once even if its item quantity exceeds one.", MenuTheme.MUTED))
        content.child(label("Community updated ${if (data.updatedAt > 0) Instant.ofEpochMilli(data.updatedAt).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")) else "no samples"}. Local history may predate sharing; community omissions are not missing personal acquisitions.", MenuTheme.MUTED))
        content.child(label("Source: ${data.source} · ${data.coverage}. Observed frequency is not game drop probability.", MenuTheme.MUTED))
    }
    private fun money(value: Double?) = value?.let(NumberUtils::condense) ?: "Unknown"
    private fun scopeTitle(scope: TrackerScope) = when (scope) {
        TrackerScope.CURRENT_PROFILE -> "Current profile"
        TrackerScope.ALL_LOCAL_PROFILES -> "All profiles"
        TrackerScope.UNSCOPED_LEGACY -> "Unscoped imports"
    }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, action: (io.wispforest.owo.ui.component.ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).apply { sizing(Sizing.fixed(width), Sizing.fixed(22)) }
}
