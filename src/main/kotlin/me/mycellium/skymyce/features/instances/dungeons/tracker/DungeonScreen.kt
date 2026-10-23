package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.*
import io.wispforest.owo.ui.container.*
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/** View filters never write DungeonTracker.currentFloor; the detector alone owns recording context. */
class DungeonScreen : MenuScreen(Component.literal("Sky-Hawk Dungeon Tracker"), MenuPage.DUNGEONS) {
    private var filter = AcquisitionRepository.preferences
    private var itemHistoryReturn: TrackerFilter? = null
    private var view = TrackerView()
    private var page = 0
    private var timelineLimit = 100
    private var more = false
    private var closed = false
    private var generation = 0L
    private var requested = -1L
    private var busy = false
    private var changedAt = 0L
    private var seenArchive = -1L
    private var seenLegacy = -1L
    private var seenContext: AcquisitionContext? = null
    private var seenTheme = HudTheme.revision
    private var preferencesEdited = false
    private var nextMarketRefresh = 0L
    private lateinit var resultSwap: MenuSwap
    private lateinit var results: MenuScroll<FlowLayout>
    private lateinit var status: LabelComponent
    private lateinit var pageLabel: LabelComponent
    private lateinit var pagination: FlowLayout
    private lateinit var previousPage: ButtonComponent
    private lateinit var nextPage: ButtonComponent
    private lateinit var tabs: FlowLayout
    private lateinit var closeButton: ButtonComponent
    private var tabRows = emptyList<FlowLayout>()
    private lateinit var floorButton: ButtonComponent
    private lateinit var modeButton: ButtonComponent
    private lateinit var historyButton: ButtonComponent
    private lateinit var historyHost: FlowLayout
    private lateinit var moreButton: ButtonComponent
    private lateinit var filterActions: FlowLayout
    private lateinit var narrowActions: FlowLayout
    private lateinit var oldestButton: ButtonComponent
    private lateinit var searchHost: FlowLayout
    private lateinit var search: MenuTextBox
    private lateinit var filterHost: FlowLayout
    private val panelWidth get() = menuWidth
    private val bodyWidth get() = panelWidth - 24
    private val history get() = filter.page == TrackerPage.TIMELINE || filter.page == TrackerPage.MUSEUM
    override fun isPauseScreen() = false
    override fun init() { closed = false; super.init() }
    override fun removed() { closed = true; generation++; super.removed() }
    override fun onClose() {
        val previous = itemHistoryReturn
        if (previous == null) { super.onClose(); return }
        itemHistoryReturn = null; change(previous); rebuild(resetScroll = true)
    }
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(column().apply {
            sizing(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)); padding(Insets.of(8)); surface(MenuTheme.panel())
            child(row().apply {
                child(label("SKY-HAWK / DUNGEONS", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
                closeButton = button("Close", 42) { onClose() }; child(closeButton)
            })
            val perRow = if (panelWidth < 420) 3 else 5
            tabRows = TrackerPage.entries.chunked(perRow).map { pages -> row().apply { pages.forEach { tab ->
                child(button(tab.title, (bodyWidth - (perRow - 1) * 4) / perRow) {
                    if (filter.page == tab) return@button
                    val previous = itemHistoryReturn ?: filter
                    itemHistoryReturn = null; change(previous.copy(page = tab)); rebuild(resetScroll = true)
                }
                    .apply { navigation = true; selected = { filter.page == tab } }
                    .wrappedTooltip(Component.literal(when (tab) { TrackerPage.OVERVIEW -> "Runs, profit and time"; TrackerPage.LOOT -> "Your saved loot"; TrackerPage.TIMELINE -> "Drops by date"; TrackerPage.MUSEUM -> "Your rare-drop collection"; TrackerPage.LEVELS -> "Runs and experience to a target level for Catacombs and each class" })))
            } } }
            tabs = column(); child(tabs)
            child(row().apply {
                floorButton = button(filter.floor ?: "All tracked floors", minOf(113, bodyWidth - 84)) { b -> menu(b) { d ->
                    d.button(Component.literal("All tracked floors")) { change(filter.copy(floor = null)); rebuild() }
                    DungeonFloor.entries.forEach { floor -> d.button(Component.literal(floor.name)) { change(filter.copy(floor = floor.name, mode = null)); rebuild() } }
                } }.wrappedTooltip(Component.literal("Filter by floor")); child(floorButton)
                modeButton = button(when (filter.mode) { "F" -> "Normal"; "M" -> "Master"; else -> "Both modes" }, 80) {
                    change(filter.copy(mode = when (filter.mode) { null -> "F"; "F" -> "M"; else -> null }, floor = null)); rebuild()
                }; child(modeButton)
            })
            historyHost = row().apply {
                historyButton = button(if (history) { if (filter.legacyTotals) "Hide historical reports" else "Show all saved reports" }
                    else if (filter.legacyTotals && !filter.needsRecords) "Show new tracking" else "Show historical totals", minOf(180, bodyWidth)) {
                    change(filter.copy(legacyTotals = !filter.legacyTotals, from = "", until = "", session = null,
                        scope = if (history && !filter.legacyTotals) TrackerScope.ALL_LOCAL_PROFILES else filter.scope)); rebuild()
                }.wrappedTooltip(Component.literal("Include older saved reports"))
            }; child(historyHost)
            filterActions = row().apply {
                moreButton = button(if (more) "Filters −" else "Filters +", 68) { more = !more; rebuild() }; child(moreButton)
                child(button("Clear filters", 80) { change(filter.clearConstraints()); rebuild() }
                    .wrappedTooltip(Component.literal("Reset filters. Saved loot stays.")))
            }; child(filterActions)
            oldestButton = button(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓", 75) { change(filter.copy(oldestFirst = !filter.oldestFirst)); rebuild() }
            search = MenuTextBox(Sizing.fill()).apply {
                setMaxLength(100); text(this@DungeonScreen.filter.query); setHint(Component.literal("Search item name or ID…"))
                onChanged().subscribe { change(this@DungeonScreen.filter.copy(query = it)) }
            }
            resultSwap = MenuSwap("dungeon-tracker-results", Sizing.fill(), Sizing.expand()).apply {
                gap(5)
                narrowActions = column(); child(narrowActions)
                searchHost = column(); child(searchHost)
                filterHost = column(); child(filterHost)
                status = label("Loading saved records…", MenuTheme.MUTED); child(status)
                results = menuScroll(Sizing.fill(), Sizing.expand(), column()).apply {
                    scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.ACCENT))); scrollbarThiccness(2); scrollStep(24)
                }; child(results)
            }; child(resultSwap)
            pagination = row().apply {
                id("tracker-pagination")
                previousPage = button("‹", 28) { page = (page - 1).coerceAtLeast(0); renderView() }
                pageLabel = label("").apply { horizontalSizing(Sizing.expand()) }; child(pageLabel)
                nextPage = button("›", 28) { page++; renderView() }
            }; child(pagination)
        })
        refreshControls()
        renderView(animate = false)
    }

    private fun refreshControls() {
        closeButton.message = Component.literal(if (itemHistoryReturn == null) "Close" else "Back to Museum")
        closeButton.horizontalSizing(Sizing.fixed(if (itemHistoryReturn == null) 42 else 95))
        floorButton.message = Component.literal(filter.floor ?: "All tracked floors")
        modeButton.message = Component.literal(when (filter.mode) { "F" -> "Normal"; "M" -> "Master"; else -> "Both modes" })
        historyButton.message = Component.literal(if (history) { if (filter.legacyTotals) "Hide historical reports" else "Show all saved reports" }
            else if (filter.legacyTotals && !filter.needsRecords) "Show new tracking" else "Show historical totals")
        historyHost.clearChildren()
        if (history && filter.scope == TrackerScope.CURRENT_PROFILE) historyHost.child(historyButton)
        moreButton.message = Component.literal(if (more) "Filters −" else "Filters +")
        oldestButton.message = Component.literal(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓")
        if (more && height < 340) tabs.clearChildren()
        else if (tabs.children().isEmpty()) tabRows.forEach { tabs.child(it) }
        val sortHost = if (bodyWidth >= 235) filterActions else narrowActions
        if (filter.page == TrackerPage.TIMELINE) {
            if (oldestButton.parent() !== sortHost) sortHost.child(oldestButton)
        } else oldestButton.parent()?.removeChild(oldestButton)
        if (search.value != filter.query) search.text(filter.query)
        if (filter.page != TrackerPage.OVERVIEW && filter.page != TrackerPage.LEVELS && (!more || height >= 340)) {
            if (search.parent() !== searchHost) searchHost.child(search)
        } else searchHost.clearChildren()
        filterHost.clearChildren()
        if (more && height >= 340) filterHost.child(menuScroll(Sizing.fill(), Sizing.fixed(if (height < 400) 58 else 100), filters()).apply { scrollStep(20); scrollbarThiccness(2) })
    }

    private fun filters() = column().apply {
        child(button("Scope: ${filter.scope.name.replace('_', ' ')}", (bodyWidth - 8).coerceAtMost(280)) { b -> menu(b) { d ->
            TrackerScope.entries.forEach { scope -> d.button(Component.literal(scope.name.replace('_', ' '))) { change(filter.copy(scope = scope, legacyTotals = filter.legacyTotals || scope == TrackerScope.UNSCOPED_LEGACY)); rebuild() } }
        } }.wrappedTooltip(Component.literal("All local profiles includes saved totals and works offline.")))
        child(button(filter.chest ?: "All chest types", 145) { b -> menu(b) { d ->
            (listOf<String?>(null) + DungeonChest.entries.map { it.name }).forEach { chest -> d.button(Component.literal(chest ?: "All chest types")) { change(filter.copy(chest = chest)); rebuild() } }
        } })
        child(row().apply {
            child(dateField(true)); child(dateField(false))
        })
        child(button(filter.session?.let { "Session ${it.take(8)}" } ?: "All sessions", 145) { b -> menu(b) { d ->
            d.button(Component.literal("All sessions")) { change(filter.copy(session = null)); rebuild() }
            AcquisitionRepository.state.runs.asReversed().asSequence().filter { it.trackingVersion == 2 && filter.scopeMatches(it.context, seenContext) }.map { it.session }.distinct().take(20).forEach { session ->
                d.button(Component.literal(session.take(8))) { change(filter.copy(session = session, legacyTotals = false)); rebuild() }
            }
        } }.wrappedTooltip(Component.literal("Filter by play session")))
        if (filter.page != TrackerPage.OVERVIEW) child(row().apply {
            child(button(if (filter.rareOnly) "RNG only ✓" else "RNG only", 86) { change(filter.copy(rareOnly = !filter.rareOnly)); rebuild() }.wrappedTooltip(Component.literal("Show catalogued rare drops")))
            child(button(if (filter.valuableOnly) "1m+ ✓" else "1m+ value", 86) { change(filter.copy(valuableOnly = !filter.valuableOnly)); rebuild() }
                .wrappedTooltip(Component.literal(if (filter.page == TrackerPage.LOOT)
                    "Market value of at least 1M coins"
                    else "Recorded value of at least 1M coins")))
        })
        if (filter.page == TrackerPage.LOOT) child(row().apply {
            child(button("Sort: ${filter.sort.name.replace('_', ' ')}", minOf(140, bodyWidth - 64)) { change(filter.copy(sort = LootOrder.entries[(filter.sort.ordinal + 1) % LootOrder.entries.size])); rebuild() })
            child(button(if (filter.ascending) "↑ Asc" else "↓ Desc", 60) { change(filter.copy(ascending = !filter.ascending)); rebuild() })
        })
        if (history) child(button(filter.activity ?: "All activities", 145) { b -> menu(b) { d ->
            (listOf<String?>(null) + DigestRngParser.catalog.values.map { it.activity }.distinct()).forEach { activity ->
                d.button(Component.literal(activity ?: "All activities")) { change(filter.copy(activity = activity)); rebuild() }
            }
        } })
    }
    private fun dateField(start: Boolean) = MenuTextBox(Sizing.fill(48)).apply {
        setMaxLength(10); text(if (start) this@DungeonScreen.filter.from else this@DungeonScreen.filter.until); setHint(Component.literal(if (start) "From YYYY-MM-DD" else "To YYYY-MM-DD"))
        wrappedTooltip(Component.literal("Local date, inclusive. Dated records only."))
        onChanged().subscribe { value ->
            if (value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess) {
                val next = if (start) this@DungeonScreen.filter.copy(from = value, legacyTotals = false) else this@DungeonScreen.filter.copy(until = value, legacyTotals = false)
                if (next.from.isEmpty() || next.until.isEmpty() || next.from <= next.until) change(next)
                else status.text(Component.literal("Start date must not follow end date").withColor(MenuTheme.RED))
            } else status.text(Component.literal("Use a valid YYYY-MM-DD date").withColor(MenuTheme.YELLOW))
        }
    }
    private fun change(next: TrackerFilter) {
        if (next == filter) return
        preferencesEdited = true; filter = next; page = 0; timelineLimit = 100; generation++; changedAt = System.currentTimeMillis()
        // An item-history detour must not replace the collection's saved view, including on category exit.
        AcquisitionRepository.remember(itemHistoryReturn ?: filter)
    }
    override fun tick() {
        super.tick()
        if (menuClosing) return
        if (!::results.isInitialized) return
        val context = AcquisitionRepository.currentContext()
        if (!preferencesEdited && filter != AcquisitionRepository.preferences) {
            filter = AcquisitionRepository.preferences; generation++; rebuild()
        }
        if (seenArchive != AcquisitionRepository.revision || seenLegacy != DungeonTracker.revision || seenContext != context) {
            seenArchive = AcquisitionRepository.revision; seenLegacy = DungeonTracker.revision; seenContext = context; generation++
        }
        if (seenTheme != HudTheme.revision) { seenTheme = HudTheme.revision; rebuildMenu() }
        if (filter.page in listOf(TrackerPage.OVERVIEW, TrackerPage.LOOT) && System.currentTimeMillis() >= nextMarketRefresh) {
            nextMarketRefresh = System.currentTimeMillis() + 10_000; generation++
        }
        if (!busy && requested != generation && System.currentTimeMillis() - changedAt >= 120) {
            val token = generation; requested = token; busy = true
            val archive = AcquisitionRepository.state; val legacy = legacyTrackerView(); val query = filter
            val bazaar = DungeonTrackerConfig.bazaarPriceType; val auction = DungeonTrackerConfig.auctionPriceType
            Scheduling.schedule(0.milliseconds) {
                val computed = runCatching {
                    val marketView = query.page in listOf(TrackerPage.OVERVIEW, TrackerPage.LOOT)
                    val selectedFilter = when (query.page) {
                        TrackerPage.OVERVIEW -> query.copy(query = "", rareOnly = false, valuableOnly = false)
                        TrackerPage.LOOT -> query.copy(valuableOnly = false)
                        else -> query
                    }
                    val selected = trackerView(archive, legacy, selectedFilter, context)
                    if (!marketView) selected else {
                        val pricedLoot = marketValuedLoot(selected.loot) { cachedLootUnitPrice(it, bazaar, auction) }
                        selected.copy(summary = if (query.page == TrackerPage.OVERVIEW) marketValuedTrackerSummary(selected.summary, pricedLoot) else selected.summary,
                            loot = sortedLoot(pricedLoot, selectedFilter.copy(valuableOnly = query.page == TrackerPage.LOOT && query.valuableOnly)))
                    }
                }
                MC.instance.execute {
                    busy = false
                    if (!closed && !menuClosing && token == generation) computed.fold({ view = it; renderView(animate = false) }, { status.text(Component.literal("Some saved records could not be displayed").withColor(MenuTheme.RED)) })
                }
            }
        }
    }
    private fun renderView(animate: Boolean = true) {
        if (animate) resultSwap.change()
        val filters = listOfNotNull(filter.floor, filter.mode?.let { if (it == "M") "Master" else "Normal" }, filter.chest,
            filter.from.takeIf(String::isNotEmpty)?.let { "From $it" }, filter.until.takeIf(String::isNotEmpty)?.let { "To $it" },
            filter.session?.let { "Session ${it.take(8)}" }, filter.query.takeIf { it.isNotBlank() && filter.page != TrackerPage.OVERVIEW }?.let { "Search: ${it.take(25)}" },
            if (filter.rareOnly && filter.page != TrackerPage.OVERVIEW) "RNG" else null,
            if (filter.valuableOnly && filter.page != TrackerPage.OVERVIEW) "1m+" else null)
        val source = if (filter.page == TrackerPage.OVERVIEW) when (filter.scope) {
            TrackerScope.ALL_LOCAL_PROFILES -> "All saved runs"
            TrackerScope.CURRENT_PROFILE -> "Current profile"
            TrackerScope.UNSCOPED_LEGACY -> "Saved totals"
        } else if (filter.page == TrackerPage.LOOT) when (filter.scope) {
            TrackerScope.ALL_LOCAL_PROFILES -> if (filter.needsRecords) "Saved loot for these dates or session" else "All saved loot"
            TrackerScope.CURRENT_PROFILE -> "Current profile loot"
            TrackerScope.UNSCOPED_LEGACY -> "Saved totals"
        } else when (filter.scope) {
            TrackerScope.ALL_LOCAL_PROFILES -> "All saved drops"
            TrackerScope.CURRENT_PROFILE -> "Current profile drops"
            TrackerScope.UNSCOPED_LEGACY -> "Older saved drops"
        }
        status.text(Component.literal("$source${if (filters.isEmpty()) " • no filters" else " • ${filters.joinToString(" / ")}"}").withColor(MenuTheme.MUTED))
        pagination.clearChildren()
        if (more && height < 340) {
            status.text(Component.literal("Filters • changes apply immediately").withColor(MenuTheme.MUTED))
            results.child(filters()); return
        }
        val content = column()
        when (filter.page) {
            TrackerPage.OVERVIEW -> overview(content)
            TrackerPage.LOOT -> loot(content)
            TrackerPage.TIMELINE -> timeline(content)
            TrackerPage.MUSEUM -> museum(content)
            TrackerPage.LEVELS -> levels(content)
        }
        results.child(content)
    }
    private fun overview(content: FlowLayout) {
        val s = view.summary
        val columns = (bodyWidth / 180).coerceIn(1, 2)
        val cardWidth = (bodyWidth - (columns - 1) * 6) / columns
        fun stat(name: String, value: String, primary: Boolean = false,
                 color: Int = MenuTheme.ACCENT, hint: String? = null) = column().apply {
            sizing(Sizing.fixed(cardWidth), Sizing.fixed(if (primary) 64 else 54))
            surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING)); gap(4)
            child(label(name, MenuTheme.MUTED))
            child(label(value, color).apply { fontSize = if (primary) 21f else 16f; text(text().copy().withStyle { it.withBold(true) }) })
            if (!hint.isNullOrBlank()) wrappedTooltip(Component.literal(hint))
        }
        val cards = listOf(
            stat("Runs", s.runs.toString(), primary = true),
            stat("Profit (estimated)", money(s.knownNet),
                primary = true, color = if (s.knownNet < 0) MenuTheme.RED else MenuTheme.GREEN,
                hint = "Loot value: ${money(s.gross)}${if (s.incompleteValues) " (partial)" else ""}\n" +
                    "Chest costs: ${money(s.chestCost)}${if (s.incompleteCosts) " (partial)" else ""}\n" +
                    "Rerolls: ${money(s.knownRerollCost)}${if (s.rerollCost == null) " (partial)" else ""}"),
            stat("Average run", s.average?.toLong()?.let(::duration) ?: "—"),
            stat("Tracked time", if (s.timedRuns == 0L && s.runs > 0) "—" else duration(s.time)),
            stat("Chests / rerolls", "${s.chests} / ${s.rerolls}"),
            stat("Catacombs XP", money(s.cataXp), hint = s.classXp.entries.joinToString("\n") { (name, xp) -> "$name: ${money(xp)}" })
        ) + listOfNotNull(DungeonTrackerConfig.showTarget.takeIf { it }?.let {
            val target = DungeonTrackerConfig.targetLevel
            val xpPerRun = trackedXpPerRun(s)
            val progress = DungeonTargetLevel.progress(xpPerRun)
            when {
                progress == null -> stat("Target: Cata $target", "—", color = MenuTheme.MUTED,
                    hint = "Your Catacombs experience is ${DungeonTargetLevel.unavailableReason}. Change the target in Dungeons Tracker settings.")
                progress.remaining <= 0 -> stat("Target: Cata $target", "Reached", color = MenuTheme.GREEN, hint = "You are Catacombs ${progress.level}.")
                else -> stat("Target: Cata $target", "${money(progress.remaining)} XP left", hint = listOfNotNull(
                    "Catacombs ${progress.level} (%.1f%% to ${progress.level + 1}) · ${money(progress.xp)} XP".format(progress.nextLevelProgress * 100),
                    "%.1f%% of the way to $target".format(progress.progress * 100),
                    progress.runs?.let { "About $it more runs at this view's average of ${money(xpPerRun!!)} XP per run (estimated)" },
                    "Profile experience plus tracked runs since it was read. Change the target in Dungeons Tracker settings.",
                ).joinToString("\n"))
            }
        })
        cards.chunked(columns).forEach { group -> content.child(row().apply {
            gap(6); verticalAlignment(VerticalAlignment.TOP); group.forEach { child(it) }
        }) }
        if (AcquisitionRepository.status.contains("paused") || AcquisitionRepository.status.contains("failed") || AcquisitionRepository.status.contains("capacity"))
            content.child(label(AcquisitionRepository.status, MenuTheme.YELLOW))
        if (DungeonTracker.storageStatus.contains("unreadable")) content.child(label(DungeonTracker.storageStatus, MenuTheme.YELLOW))
    }
    /** Catacombs and class levels toward one target, with runs, experience or percent beside each bar. */
    private fun levels(content: FlowLayout) {
        val choice = DungeonTrackerConfig.levelsTarget
        val metric = DungeonTrackerConfig.levelsMetric
        content.child(row().apply {
            child(button(if (choice <= 0) "Target: next level" else "Target: $choice", 118) { b -> menu(b) { d ->
                LEVEL_TARGETS.forEach { level -> d.button(Component.literal(if (level <= 0) "Next level" else "Level $level")) {
                    DungeonTrackerConfig.levelsTarget = level; me.mycellium.skymyce.SkyMyce.config.save(); rebuild()
                } }
            } }.wrappedTooltip(Component.literal("The level every bar counts toward. Any level up to 200 can be set in Dungeons Tracker settings.")))
            LevelMetric.entries.forEach { option ->
                child(button(option.title, ((bodyWidth - 126) / 3 - 4).coerceIn(48, 84)) {
                    DungeonTrackerConfig.levelsMetric = option; me.mycellium.skymyce.SkyMyce.config.save(); rebuild()
                }.apply { navigation = true; selected = { DungeonTrackerConfig.levelsMetric == option } })
            }
        })
        val cata = DungeonTargetLevel.experience()
        val classes = DungeonTargetLevel.classExperience()
        if (cata == null) {
            content.child(label("Your levels are ${DungeonTargetLevel.unavailableReason}.", MenuTheme.MUTED))
            return
        }
        // Rates follow this view's floor and mode filters, so "Runs to 50" means runs of the floor you are looking at.
        val runs = AcquisitionRepository.state.runs.filter { run -> run.trackingVersion == 2 &&
            (filter.floor == null || run.floor == filter.floor) && (filter.mode == null || run.floor.startsWith(filter.mode!!)) }
        val context = AcquisitionRepository.currentContext()
        val cataRate = runs.filter { context == null || context.matches(it.context) }.mapNotNull { it.cataXp?.takeIf { xp -> xp.isFinite() && xp > 0 } }
            .takeIf { it.isNotEmpty() }?.average() ?: trackedXpPerRun(view.summary)
        val cataRow = levelRow("Catacombs", null, cata, levelTarget(choice, cata), cataRate)
        content.child(column().apply {
            surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
            child(LevelBars("CATACOMBS", listOf(cataRow), metric))
        })
        if (classes == null) {
            content.child(label("Class levels load with your next profile reading.", MenuTheme.MUTED))
        } else {
            val rows = classLevelRows(classes, choice, classXpPerRun(runs, context), cataRate)
            val selected = me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache.get(MC.instance.user.name)?.selectedClass
            content.child(column().apply {
                surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
                child(LevelBars("CLASSES", rows, metric, selected))
            })
            if (metric == LevelMetric.RUNS && rows.any { it.runs == null && it.remaining > 0 })
                content.child(label("— needs a tracked run that granted experience; switch to XP needed meanwhile.", MenuTheme.MUTED))
            else if (metric == LevelMetric.RUNS && rows.any { it.estimatedRate && it.remaining > 0 })
                content.child(label("~ uses your Catacombs average for classes with no tracked runs.", MenuTheme.MUTED))
        }
        content.child(label(if (metric == LevelMetric.RUNS)
            "Estimated from your tracked average per run${filter.floor?.let { " on $it" } ?: ""}. Profile experience plus runs since it was read."
            else "Profile experience plus tracked runs since it was read.", MenuTheme.MUTED))
    }
    private fun loot(content: FlowLayout) {
        val visible = slice(view.loot)
        content.child(label("Item  /  Count  /  Market value (estimated)  /  Personal rate", MenuTheme.SECONDARY))
        val samples = visible.mapNotNull { it.rateSample }.distinct()
        samples.singleOrNull()?.let { sample -> content.child(label(
            "Rate sample: ${sample.completedRuns} ${sample.floor} runs • ${date(sample.from)} – ${date(sample.until - 1)}", MenuTheme.MUTED)
            .wrappedTooltip(Component.literal("Older dated sample; Overview counts your full run history."))) }
        if (samples.size > 1) content.child(label("Rates use separate dated samples", MenuTheme.MUTED))
        if (visible.isEmpty()) content.child(label(if (filter.scope == TrackerScope.CURRENT_PROFILE && seenContext == null)
            "Choose All local profiles to see saved loot offline."
            else "No saved loot matches these filters.", MenuTheme.MUTED))
        if (filter.needsRecords && filter.scope == TrackerScope.ALL_LOCAL_PROFILES)
            content.child(label("Dated records only", MenuTheme.MUTED))
        visible.forEach { item -> content.child(column().apply {
            surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
            child(row().apply { child(icon(item.item)); child(label(itemName(item.item)).apply { horizontalSizing(Sizing.expand()) }) })
            child(row().apply {
                child(label("× ${item.count}").horizontalSizing(Sizing.fill(25)))
                child(label(money(item.value), MenuTheme.GREEN).horizontalSizing(Sizing.fill(35))
                    .wrappedTooltip(Component.literal("${money(item.value?.div(item.count.toDouble()))} each × ${item.count}\nCached market estimate")))
                child(label(item.observedRate?.let { "%.2f%%".format(it * 100) } ?: "—", MenuTheme.SECONDARY).horizontalSizing(Sizing.expand())
                    .wrappedTooltip(Component.literal(item.rateSample?.let { sample ->
                        "Drops: ${sample.itemCounts[item.item]} / ${sample.completedRuns} ${sample.floor} runs\nDated sample, per run"
                    } ?: if (item.observedRate == null) "Not enough recorded runs or chests" else
                        "${item.acquiredChests} drops / ${item.sampledChests} recorded chests")))
            })
        }) }
    }
    private fun timeline(content: FlowLayout) {
        val visible = view.events.take(timelineLimit)
        if (visible.isEmpty() && view.undatedLoot.isEmpty()) content.child(label("No drops match these filters.", MenuTheme.MUTED))
        var date = ""
        visible.forEach { event ->
            val local = Instant.ofEpochMilli(event.occurredAt).atZone(ZoneId.systemDefault())
            if (date != local.toLocalDate().toString()) {
                date = local.toLocalDate().toString()
                content.child(label(date, MenuTheme.SECONDARY).apply { margins(Insets.top(8)); fontSize = 11f })
            }
            content.child(column().apply {
                id("timeline-event-${event.id}"); gap(2); padding(Insets.vertical(3))
                child(row().apply {
                    child(label(local.format(DateTimeFormatter.ofPattern("HH:mm")), MenuTheme.MUTED).horizontalSizing(Sizing.fixed(34)))
                    child(icon(event.item))
                    child(label("${itemName(event.item)} ×${event.quantity}", MenuTheme.TEXT).horizontalSizing(Sizing.expand()))
                })
                val context = listOfNotNull(event.floor ?: event.activity, event.chest?.lowercase()?.replaceFirstChar(Char::uppercase),
                    event.value?.let { "${money(it)} est." }, if (event.origin.startsWith("legacy")) "Imported" else null)
                child(label(context.joinToString(" • "), MenuTheme.MUTED).apply { margins(Insets.left(38)); fontSize = 9f })
                wrappedTooltip(Component.literal(listOfNotNull(
                    "Value: ${money(event.value)} • Chest: ${money(event.chestCost)}",
                    event.rerollCost?.let { "Reroll: ${money(it)}" },
                    listOfNotNull(event.player, event.context?.profileName).joinToString(" • ").takeIf(String::isNotBlank)
                ).joinToString("\n")))
            })
        }
        if (view.undatedLoot.isNotEmpty()) {
            content.child(label("Date not saved", MenuTheme.SECONDARY).apply { margins(Insets.top(8)); fontSize = 11f })
            view.undatedLoot.entries.sortedBy { itemName(it.key) }.forEach { (item, count) ->
                content.child(row().apply {
                    id("timeline-undated-$item"); padding(Insets.vertical(3))
                    child(label("", MenuTheme.MUTED).horizontalSizing(Sizing.fixed(34)))
                    child(icon(item)); child(label("${itemName(item)} ×$count", MenuTheme.TEXT).horizontalSizing(Sizing.expand()))
                })
            }
        }
        if (view.events.size > visible.size) content.child(button("Load ${if (filter.oldestFirst) "newer" else "older"} (${view.events.size - visible.size})", minOf(150, bodyWidth)) {
            timelineLimit += 100; renderView(animate = false)
        })
    }
    private fun museum(content: FlowLayout) {
        val grouped = view.events.filter { it.item in DigestRngParser.catalog }.groupBy { it.item }
        val counts = view.loot.associate { it.item to it.count }
        fun obtained(item: String) = counts[item] ?: grouped[item].orEmpty().sumOf { it.quantity.toLong() }
        val catalog = DigestRngParser.catalog.filter { (item, entry) -> (filter.activity == null || entry.activity == filter.activity) &&
            (filter.query.isBlank() || "$item ${entry.name}".contains(filter.query, true)) && (!filter.valuableOnly || item in grouped) &&
            (filter.floor == null && filter.mode == null || obtained(item) > 0) }
            .keys.sortedWith(compareByDescending<String> { AcquisitionRepository.pinned(it, seenContext) }
                .thenByDescending { obtained(it) > 0 }.thenBy { itemName(it) })
        if (catalog.isEmpty()) content.child(label("No rare drops match these filters.", MenuTheme.MUTED))
        val columns = (bodyWidth / 260).coerceIn(1, 3)
        val cardWidth = (bodyWidth - (columns - 1) * 8) / columns
        catalog.chunked(columns).forEach { group -> content.child(row().apply {
            gap(8); verticalAlignment(VerticalAlignment.TOP)
            group.forEach { item ->
            val events = grouped[item].orEmpty()
            val undated = view.undatedLoot[item] ?: 0L
            val dated = obtained(item) - undated
            child(column().apply {
                id("museum-card-$item"); sizing(Sizing.fixed(cardWidth), Sizing.fixed(102)); gap(6)
                surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
                child(row().apply {
                    child(icon(item)); child(label(itemName(item), if (obtained(item) == 0L) MenuTheme.MUTED else MenuTheme.TEXT).apply { horizontalSizing(Sizing.expand()) })
                })
                child(label(if (obtained(item) == 0L) "Not collected" else "× ${obtained(item)} collected", if (obtained(item) == 0L) MenuTheme.MUTED else MenuTheme.ACCENT)
                    .apply { fontSize = 13f })
                child(label(if (undated > 0) "$dated dated • $undated without dates" else if (events.isEmpty()) "No dated history" else "Last recorded ${date(events.maxOf { it.occurredAt })}", MenuTheme.MUTED)
                    .apply { fontSize = 9f }
                    .wrappedTooltip(Component.literal(if (events.isEmpty()) "Older totals have no dates" else
                        "${events.sumOf { it.quantity }} dated drops • ${date(events.minOf { it.occurredAt })} – ${date(events.maxOf { it.occurredAt })}")))
                child(row().apply {
                    child(button("History", 68) {
                        itemHistoryReturn = filter
                        change(filter.copy(page = TrackerPage.TIMELINE, query = item, valuableOnly = false)); rebuild(resetScroll = true)
                    }.apply { active(undated > 0 || events.isNotEmpty()) })
                    child(button(if (AcquisitionRepository.pinned(item, seenContext)) "Unpin" else "Pin", 55) { AcquisitionRepository.pin(item, seenContext); renderView() }
                        .wrappedTooltip(Component.literal("Keep this item at the top")))
                })
            })
        } }) }
    }
    private fun <T> slice(values: List<T>): List<T> {
        val pages = ((values.size + 19) / 20).coerceAtLeast(1); page = page.coerceIn(0, pages - 1)
        if (pages > 1) {
            pagination.child(previousPage); pagination.child(pageLabel); pagination.child(nextPage)
            pageLabel.text(Component.literal("${page + 1} / $pages • ${values.size} entries").withColor(MenuTheme.MUTED))
            previousPage.active(page > 0); nextPage.active(page < pages - 1)
        }
        return values.drop(page * 20).take(20)
    }
    private fun itemName(item: String) = DigestRngParser.catalog[item]?.name ?: item.substringAfter(':').replace('_', ' ').take(100)
    private fun icon(item: String): UIComponent = skyBlockItemIcon(item)
    private fun money(value: Double?) = value?.takeIf(Double::isFinite)?.let(NumberUtils::condense) ?: "Unknown"
    private fun duration(value: Long) = buildList {
        if (value >= 3600000) add("${value / 3600000}h")
        if (value >= 60000) add("${value / 60000 % 60}m")
        add("${value / 1000 % 60}s")
    }.joinToString(" ")
    private fun date(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun menu(button: ButtonComponent, options: (DropdownComponent) -> Unit) = openMenuDropdown(button, options)
    private fun rebuild(resetScroll: Boolean = false) {
        resultSwap.change()
        if (resetScroll) results.scrollBy(-results.scrollTarget, true, false)
        refreshControls(); renderView(animate = false)
    }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).apply { themed(); sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}
