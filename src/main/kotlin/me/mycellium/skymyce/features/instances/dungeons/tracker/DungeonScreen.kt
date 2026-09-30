package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.*
import io.wispforest.owo.ui.container.*
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/** View filters never write DungeonTracker.currentFloor; the detector alone owns recording context. */
class DungeonScreen : MenuScreen(Component.literal("Sky-Hawk Dungeon Tracker"), MenuPage.DUNGEONS) {
    private var filter = AcquisitionRepository.preferences
    private var view = TrackerView()
    private var page = 0
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
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var status: LabelComponent
    private lateinit var pageLabel: LabelComponent
    private val panelWidth get() = menuWidth
    private val bodyWidth get() = panelWidth - 24
    private val history get() = filter.page == TrackerPage.TIMELINE || filter.page == TrackerPage.MUSEUM
    override fun isPauseScreen() = false
    override fun init() { closed = false; super.init() }
    override fun removed() { closed = true; generation++; super.removed() }
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(column().apply {
            sizing(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)); padding(Insets.of(8)); surface(MenuTheme.panel())
            child(row().apply {
                child(label("SKY-HAWK / DUNGEONS", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
                child(button("Close", 42) { onClose() })
            })
            val perRow = if (panelWidth < 420) 2 else 4
            if (!more || height >= 340) TrackerPage.entries.chunked(perRow).forEach { tabs -> child(row().apply { tabs.forEach { tab ->
                child(button(tab.title, (bodyWidth - (perRow - 1) * 4) / perRow) { change(filter.copy(page = tab)); rebuild() }
                    .apply { if (filter.page == tab) message = message.copy().withColor(MenuTheme.ACCENT) }
                    .wrappedTooltip(Component.literal(when (tab) { TrackerPage.OVERVIEW -> "Tracked runs, time, costs, values and XP"; TrackerPage.LOOT -> "Observed loot in your tracked sample, with real filters and sorting"; TrackerPage.TIMELINE -> "Your dated acquisitions; legacy source labels preserve uncertainty"; TrackerPage.MUSEUM -> "Your collection of catalogued rare drops. This is not Hypixel's donation Museum." })))
            } }) }
            child(row().apply {
                child(button(filter.floor ?: "All tracked floors", minOf(113, bodyWidth - 84)) { b -> menu(b) { d ->
                    d.button(Component.literal("All tracked floors")) { change(filter.copy(floor = null)); rebuild() }
                    DungeonFloor.entries.forEach { floor -> d.button(Component.literal(floor.name)) { change(filter.copy(floor = floor.name, mode = null)); rebuild() } }
                } }.wrappedTooltip(Component.literal("Select a viewing floor. This never changes an ongoing run's recording floor.")))
                child(button(when (filter.mode) { "F" -> "Normal"; "M" -> "Master"; else -> "Both modes" }, 80) {
                    change(filter.copy(mode = when (filter.mode) { null -> "F"; "F" -> "M"; else -> null }, floor = null)); rebuild()
                })
            })
            child(row().apply {
                child(button(if (more) "Filters −" else "Filters +", 68) { more = !more; rebuild() })
                child(button("Clear all", 65) { change(TrackerFilter(page = filter.page)); rebuild() })
                if (filter.page == TrackerPage.TIMELINE && bodyWidth >= 220) child(button(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓", 75) { change(filter.copy(oldestFirst = !filter.oldestFirst)); rebuild() })
            })
            if (filter.page == TrackerPage.TIMELINE && bodyWidth < 220) child(button(if (filter.oldestFirst) "Oldest ↑" else "Newest ↓", 75) { change(filter.copy(oldestFirst = !filter.oldestFirst)); rebuild() })
            if (filter.page != TrackerPage.OVERVIEW && (!more || height >= 340)) child(MenuTextBox(Sizing.fill()).apply {
                setMaxLength(100); text(this@DungeonScreen.filter.query); setHint(Component.literal("Search item name or ID…"))
                onChanged().subscribe { change(this@DungeonScreen.filter.copy(query = it)) }
            })
            if (more && height >= 340) child(UIContainers.verticalScroll(Sizing.fill(), Sizing.fixed(if (height < 400) 58 else 100), filters()).apply { scrollStep(20); scrollbarThiccness(2) })
            status = label("Loading saved records…", MenuTheme.MUTED); child(status)
            results = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), column()).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.ACCENT))); scrollbarThiccness(2); scrollStep(24)
            }; child(results)
            child(row().apply {
                child(button("‹", 28) { page = (page - 1).coerceAtLeast(0); renderView() })
                pageLabel = label("").apply { horizontalSizing(Sizing.expand()) }; child(pageLabel)
                child(button("›", 28) { page++; renderView() })
            })
        })
        renderView()
    }

    private fun filters() = column().apply {
        child(button("Scope: ${filter.scope.name.replace('_', ' ')}", (bodyWidth - 8).coerceAtMost(280)) { b -> menu(b) { d ->
            TrackerScope.entries.forEach { scope -> d.button(Component.literal(scope.name.replace('_', ' '))) { change(filter.copy(scope = scope, legacyTotals = false)); rebuild() } }
        } }.wrappedTooltip(Component.literal("Current profile uses account UUID and the available profile ID. All local profiles and unscoped legacy records are explicit separate views. Offline: choose All local profiles.")))
        if (!history) child(button(if (filter.legacyTotals && !filter.needsRecords) "Data: All-time totals" else "Data: Timestamped records", (bodyWidth - 8).coerceAtMost(280)) {
            change(filter.copy(legacyTotals = !filter.legacyTotals, from = "", until = "", session = null)); rebuild()
        }.wrappedTooltip(Component.literal("Legacy totals include all locally tracked accounts and cannot be filtered by date, session or profile. New timestamped records can.")))
        child(button(filter.chest ?: "All chest types", 145) { b -> menu(b) { d ->
            (listOf<String?>(null) + DungeonChest.entries.map { it.name }).forEach { chest -> d.button(Component.literal(chest ?: "All chest types")) { change(filter.copy(chest = chest)); rebuild() } }
        } })
        child(row().apply {
            child(dateField(true)); child(dateField(false))
        })
        child(button(filter.session?.let { "Session ${it.take(8)}" } ?: "All sessions", 145) { b -> menu(b) { d ->
            d.button(Component.literal("All sessions")) { change(filter.copy(session = null)); rebuild() }
            AcquisitionRepository.state.runs.asReversed().asSequence().filter { filter.scopeMatches(it.context, seenContext) }.map { it.session }.distinct().take(20).forEach { session ->
                d.button(Component.literal(session.take(8))) { change(filter.copy(session = session, legacyTotals = false)); rebuild() }
            }
        } }.wrappedTooltip(Component.literal("Recent local sessions. A new session starts on account/profile change or disconnect. Legacy aggregates have no sessions.")))
        if (filter.page != TrackerPage.OVERVIEW) child(row().apply {
            child(button(if (filter.rareOnly) "RNG only ✓" else "RNG only", 86) { change(filter.copy(rareOnly = !filter.rareOnly)); rebuild() }.wrappedTooltip(Component.literal("Only maintained rare-item catalog IDs; market price alone does not define RNG.")))
            child(button(if (filter.valuableOnly) "1m+ ✓" else "1m+ value", 86) { change(filter.copy(valuableOnly = !filter.valuableOnly)); rebuild() }.wrappedTooltip(Component.literal("Only recorded values of at least one million coins. Unknown historical values do not qualify.")))
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
        wrappedTooltip(Component.literal("Inclusive local calendar date. Date filters require timestamped records; old aggregate totals stay available separately."))
        onChanged().subscribe { value ->
            if (value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess) {
                val next = if (start) this@DungeonScreen.filter.copy(from = value, legacyTotals = false) else this@DungeonScreen.filter.copy(until = value, legacyTotals = false)
                if (next.from.isEmpty() || next.until.isEmpty() || next.from <= next.until) change(next)
                else status.text(Component.literal("Start date must not follow end date").withColor(MenuTheme.RED))
            } else status.text(Component.literal("Use a valid YYYY-MM-DD date").withColor(MenuTheme.YELLOW))
        }
    }
    private fun change(next: TrackerFilter) { preferencesEdited = true; filter = next; page = 0; generation++; changedAt = System.currentTimeMillis(); AcquisitionRepository.remember(filter) }
    override fun tick() {
        super.tick()
        if (!::results.isInitialized) return
        val context = AcquisitionRepository.currentContext()
        if (!preferencesEdited && filter != AcquisitionRepository.preferences) {
            filter = AcquisitionRepository.preferences; generation++; rebuild()
        }
        if (seenArchive != AcquisitionRepository.revision || seenLegacy != DungeonTracker.revision || seenContext != context) {
            seenArchive = AcquisitionRepository.revision; seenLegacy = DungeonTracker.revision; seenContext = context; generation++
        }
        if (seenTheme != HudTheme.revision) { seenTheme = HudTheme.revision; rebuild() }
        if (!busy && requested != generation && System.currentTimeMillis() - changedAt >= 120) {
            val token = generation; requested = token; busy = true
            val archive = AcquisitionRepository.state; val legacy = legacyTrackerView(); val query = filter
            Scheduling.schedule(0.milliseconds) {
                val computed = runCatching { trackerView(archive, legacy, query, context) }
                MC.instance.execute {
                    busy = false
                    if (!closed && token == generation) computed.fold({ view = it; renderView() }, { status.text(Component.literal("Some saved records could not be displayed").withColor(MenuTheme.RED)) })
                }
            }
        }
    }
    private fun renderView() {
        val filters = listOfNotNull(filter.floor, filter.mode?.let { if (it == "M") "Master" else "Normal" }, filter.chest,
            filter.from.takeIf(String::isNotEmpty)?.let { "From $it" }, filter.until.takeIf(String::isNotEmpty)?.let { "To $it" },
            filter.session?.let { "Session ${it.take(8)}" }, filter.query.takeIf(String::isNotBlank)?.let { "Search: ${it.take(25)}" },
            if (filter.rareOnly) "RNG" else null, if (filter.valuableOnly) "1m+" else null)
        val source = if (!history && view.usingLegacy) "All-time totals • unscoped" else filter.scope.name.replace('_', ' ')
        status.text(Component.literal("$source${if (filters.isEmpty()) " • no filters" else " • ${filters.joinToString(" / ")}"}").withColor(MenuTheme.MUTED))
        if (more && height < 340) {
            status.text(Component.literal("Filters • changes apply immediately").withColor(MenuTheme.MUTED))
            results.child(filters()); pageLabel.text(Component.literal("Collapse filters for results").withColor(MenuTheme.MUTED)); return
        }
        val content = column()
        when (filter.page) {
            TrackerPage.OVERVIEW -> overview(content)
            TrackerPage.LOOT -> loot(content)
            TrackerPage.TIMELINE -> timeline(content)
            TrackerPage.MUSEUM -> museum(content)
        }
        results.child(content)
    }
    private fun overview(content: FlowLayout) {
        pageLabel.text(Component.literal("Overview").withColor(MenuTheme.MUTED))
        val s = view.summary
        val values = listOf("Runs" to s.runs.toString(), "Tracked time${if (s.timedRuns < s.runs) " (partial)" else ""}" to
            (if (s.timedRuns == 0L && s.runs > 0) "Unknown" else duration(s.time)), "Average run" to (s.average?.toLong()?.let(::duration) ?: "Unknown"),
            "Net profit${if (s.incompleteValues) " (partial)" else ""}" to money(s.net), "Gross value${if (s.incompleteValues) " (known only)" else ""}" to money(s.gross),
            "Chest costs" to money(s.chestCost), "Reroll costs" to money(s.rerollCost), "Catacombs XP" to money(s.cataXp), "Chests / rerolls" to "${s.chests} / ${s.rerolls}")
        val columns = (bodyWidth / 180).coerceIn(1, 3)
        values.chunked(columns).forEach { group -> content.child(row().apply { group.forEach { (name, value) ->
            child(column().apply { horizontalSizing(Sizing.fixed((bodyWidth - (columns - 1) * 4) / columns)); surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
                child(label(name, MenuTheme.MUTED)); child(label(value, MenuTheme.ACCENT))
            })
        } }) }
        s.classXp.forEach { (name, xp) -> content.child(label("$name XP: ${money(xp)}", MenuTheme.MUTED)) }
        if (s.timedRuns < s.runs) content.child(label("Time known for ${s.timedRuns}/${s.runs} completed runs. Average uses only timed runs; missing historical durations are not estimated.", MenuTheme.YELLOW))
        content.child(label(if (view.usingLegacy) "Legacy totals preserve the original save. Account, date, session and per-acquisition prices were not recorded. Average uses aggregate time/runs; observed loot rates are unavailable." else
            "Only confirmed inventory receipts appear as chest acquisitions. Average uses completed runs with known duration. Values are estimates recorded at acquisition; unknown values remain unknown. Reroll costs cannot be split by chest type.", MenuTheme.MUTED))
        content.child(label(AcquisitionRepository.status, MenuTheme.MUTED)); content.child(label(DungeonTracker.storageStatus, MenuTheme.MUTED))
    }
    private fun loot(content: FlowLayout) {
        val visible = slice(view.loot)
        content.child(label("Item  /  Count  /  Recorded value  /  Observed rate", MenuTheme.SECONDARY))
        if (visible.isEmpty()) content.child(label("No loot matches these filters. All-time totals remain available under Filters.", MenuTheme.MUTED))
        visible.forEach { item -> content.child(column().apply {
            surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
            child(row().apply { child(icon(item.item)); child(label(itemName(item.item)).apply { horizontalSizing(Sizing.expand()) }) })
            child(row().apply {
                child(label("× ${item.count}").horizontalSizing(Sizing.fill(25)))
                child(label(money(item.value), MenuTheme.GREEN).horizontalSizing(Sizing.fill(35)))
                child(label(item.observedRate?.let { "%.2f%%".format(it * 100) } ?: "Unknown", MenuTheme.SECONDARY).horizontalSizing(Sizing.expand())
                    .wrappedTooltip(Component.literal(if (item.observedRate == null) "Legacy aggregates do not record which chests contained this item. No theoretical chance is inferred." else
                        "Observed in ${item.acquiredChests} of ${item.sampledChests} confirmed chests in this tracked sample. This is not an official drop chance.")))
            })
        }) }
    }
    private fun timeline(content: FlowLayout) {
        val visible = slice(view.events)
        if (visible.isEmpty()) content.child(label("No acquisitions match. Offline, choose All local profiles; older tracker events are under Unscoped legacy.", MenuTheme.MUTED))
        var date = ""
        visible.forEach { event ->
            val local = Instant.ofEpochMilli(event.occurredAt).atZone(ZoneId.systemDefault())
            if (date != local.toLocalDate().toString()) { date = local.toLocalDate().toString(); content.child(label(date, MenuTheme.SECONDARY)) }
            content.child(column().apply {
                surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
                child(row().apply { child(icon(event.item)); child(label("${itemName(event.item)} ×${event.quantity}", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) }) })
                child(label("${local.format(DateTimeFormatter.ofPattern("HH:mm:ss"))} • ${event.floor ?: event.activity} • ${event.chest ?: "Chest unknown"}", MenuTheme.MUTED))
                child(label("${event.player ?: "Player not recorded"} • ${event.context?.profileName ?: "Unscoped legacy"} • ${event.origin}", MenuTheme.MUTED))
                child(label("Recorded value: ${money(event.value)} • Chest cost: ${money(event.chestCost)}", MenuTheme.MUTED))
                if (event.rerollCost != null) child(label("Reroll estimate: ${money(event.rerollCost)}", MenuTheme.MUTED))
                if (event.run != null) child(label("Linked run ${event.run.take(8)}", MenuTheme.MUTED))
                if (event.origin.startsWith("legacy")) child(label("Legacy report • no matching receipt to reconcile across old sources", MenuTheme.YELLOW))
            })
        }
    }
    private fun museum(content: FlowLayout) {
        val grouped = view.events.filter { it.item in DigestRngParser.catalog }.groupBy { it.item }
        val catalog = DigestRngParser.catalog.filter { (item, entry) -> (filter.activity == null || entry.activity == filter.activity) &&
            (filter.query.isBlank() || "$item ${entry.name}".contains(filter.query, true)) && (!filter.valuableOnly || item in grouped) }
            .keys.sortedWith(compareByDescending<String> { AcquisitionRepository.pinned(it, seenContext) }.thenBy { itemName(it) })
        content.child(label("Personal rare-drop collection • not Hypixel's donation Museum", MenuTheme.MUTED))
        val columns = (bodyWidth / 260).coerceIn(1, 3)
        slice(catalog).chunked(columns).forEach { group -> content.child(row().apply { group.forEach { item ->
            val events = grouped[item].orEmpty()
            child(column().apply {
                horizontalSizing(Sizing.fixed((bodyWidth - (columns - 1) * 4) / columns))
                surface(MenuTheme.panel(true)); padding(Insets.of(MenuTheme.PADDING))
                child(row().apply { child(icon(item)); child(label(itemName(item), if (events.isEmpty()) MenuTheme.MUTED else MenuTheme.SECONDARY).apply { horizontalSizing(Sizing.expand()) }) })
                child(label(if (events.isEmpty()) "Not recorded" else "Obtained: ${events.sumOf { it.quantity }} • ${events.mapNotNull { it.floor }.distinct().joinToString().ifEmpty { events.first().activity }}", MenuTheme.MUTED))
                if (events.isNotEmpty()) child(label("First: ${date(events.minOf { it.occurredAt })} • Latest: ${date(events.maxOf { it.occurredAt })}", MenuTheme.MUTED))
                child(row().apply {
                    child(button("History", 68) { change(filter.copy(page = TrackerPage.TIMELINE, query = item, valuableOnly = false)); rebuild() }.apply { active(events.isNotEmpty()) })
                    child(button(if (AcquisitionRepository.pinned(item, seenContext)) "Unpin" else "Pin", 55) { AcquisitionRepository.pin(item, seenContext); renderView() }
                        .wrappedTooltip(Component.literal("Pins and personal collection entries are permanent local records, independent of community-feed retention.")))
                })
            })
        } }) }
    }
    private fun <T> slice(values: List<T>): List<T> {
        val pages = ((values.size + 19) / 20).coerceAtLeast(1); page = page.coerceIn(0, pages - 1)
        pageLabel.text(Component.literal("${page + 1} / $pages • ${values.size} entries").withColor(MenuTheme.MUTED))
        return values.drop(page * 20).take(20)
    }
    private fun itemName(item: String) = DigestRngParser.catalog[item]?.name ?: item.substringAfter(':').replace('_', ' ').take(100)
    private fun icon(item: String): UIComponent {
        val stack = runCatching { if (':' in item) SkyBlockId.CODEC.parse(JsonOps.INSTANCE, JsonPrimitive(item.lowercase())).result().orElse(null)?.toItem() else SkyBlockId.item(item).toItem() }.getOrNull()
        val display = stack?.takeUnless { it.isEmpty } ?: runCatching { Items.PAPER.defaultInstance }.getOrDefault(net.minecraft.world.item.ItemStack.EMPTY)
        return MenuItem(me.mycellium.skymyce.utils.ItemUtils.displayStack(display)).apply {
            if (stack != null && !stack.isEmpty) setTooltipFromStack(true) else wrappedTooltip(Component.literal("$item • item definition unavailable"))
        }
    }
    private fun money(value: Double?) = value?.takeIf(Double::isFinite)?.let(NumberUtils::condense) ?: "Unknown"
    private fun duration(value: Long) = "${value / 3600000}h ${value / 60000 % 60}m ${value / 1000 % 60}s"
    private fun date(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun menu(button: ButtonComponent, options: (DropdownComponent) -> Unit) = openMenuDropdown(button, options)
    private fun rebuild() { uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount() }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(4); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).themed().apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}
