package me.mycellium.skymyce.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.teamresourceful.resourcefulconfig.api.types.options.EntryType
import com.teamresourceful.resourcefulconfig.api.types.options.Option
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.SettingRow
import me.mycellium.skymyce.config.notifySettingsChanged
import me.mycellium.skymyce.config.settingRows
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.utils.Scheduling

internal enum class MenuDirectoryView(val title: String, val description: String) {
    SEARCH("Search", "Find a feature or setting by name. Results show its category and open the original control."),
    QUICK("Quick Settings", "Your pinned controls. Pin from search; use Full settings to open their original page."),
    FAVORITES("Favorites", "Features and settings you saved with the star button."),
    RECENT("Recent", "Return directly to features you recently opened."),
}

/** Category browsing and shortcuts are views over the same inventory, not separate destinations. */
internal class MenuDirectoryScreen(private val category: MenuCategory? = null,
                                   private var view: MenuDirectoryView = MenuDirectoryView.SEARCH,
                                   private val parent: Screen? = null,
                                   private var section: String? = null) :
    MenuScreen(Component.literal(category?.title ?: view.title)) {
    override val menuCategory get() = category
    override val menuParent get() = menuBackScreen(menuBackTarget(category, categoryRoot = true))
    private val catalog get() = MenuDirectory.catalog
    private var query = ""
    private var filter = 0
    private var limit = 50
    private lateinit var heading: MenuLabel
    private lateinit var description: MenuLabel
    private lateinit var viewContent: MenuSwap
    private var viewButton: ButtonComponent? = null
    private lateinit var results: FlowLayout
    private lateinit var search: MenuTextBox
    private var observedRevision = -1L
    private var quickValues = emptyList<Any?>()
    private var focusSearch = category == null && view == MenuDirectoryView.SEARCH

    override fun init() {
        val returning = ::results.isInitialized
        super.init()
        if (returning) populate()
    }

    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(14, 14, 18, 18)); root.gap(9)
        root.child(row().apply {
            heading = label(category?.title ?: view.title, MenuTheme.TEXT, 16f)
            if (category == null) heading.verticalSizing(Sizing.fixed(MenuDirectoryView.entries.maxOf {
                val lines = menuTextLines(Component.literal(it.title), (menuWidth - 36 - 48 - 6).coerceAtLeast(1), heading.fontSize.toDouble()).size
                lines * heading.lineHeight() + (lines - 1).coerceAtLeast(0) * heading.lineSpacing()
            }))
            child(heading.horizontalSizing(Sizing.expand()))
            child(button("Back", 48) { onClose() })
        })
        description = label(category?.description ?: view.description, MenuTheme.MUTED)
        if (category == null) description.verticalSizing(Sizing.fixed(MenuDirectoryView.entries.maxOf {
            val lines = menuTextLines(Component.literal(it.description), (menuWidth - 36).coerceAtLeast(1)).size
            lines * description.lineHeight() + (lines - 1).coerceAtLeast(0) * description.lineSpacing()
        }))
        root.child(description.horizontalSizing(Sizing.fill()))
        viewButton = null
        if (category == null) {
            if (menuWidth < 350) root.child(button(view.title, minOf(150, menuWidth - 36)) { anchor ->
                openMenuDropdown(anchor) { dropdown -> MenuDirectoryView.entries.forEach { tab -> dropdown.button(Component.literal(tab.title)) {
                    selectView(tab)
                } } }
            }.also { viewButton = it }) else MenuDirectoryView.entries.chunked(if (menuWidth < 390) 2 else 4).forEach { tabs -> root.child(row().apply {
                tabs.forEach { tab -> child(button(tab.title, if (tab == MenuDirectoryView.QUICK) 100 else 70) {
                    selectView(tab)
                }.apply { navigation = true; selected = { view == tab } }) }
            }) }
            viewContent = MenuSwap("directory/views", Sizing.fill(), Sizing.expand()).apply {
                gap(9); margins(Insets.of(-9, 0, -18, -18)); padding(Insets.of(9, 0, 18, 18))
            }
            root.child(viewContent)
            buildViewContent(viewContent)
        } else buildViewContent(root)
    }

    private fun selectView(next: MenuDirectoryView) {
        if (view == next || category != null) return
        viewContent.change()
        view = next; query = ""; limit = 50; focusSearch = next == MenuDirectoryView.SEARCH
        heading.text(Component.literal(next.title)); description.text(Component.literal(next.description))
        viewButton?.message = Component.literal(next.title)
        viewContent.clearChildren()
        buildViewContent(viewContent)
    }

    private fun buildViewContent(root: FlowLayout) {
        if (category != null || view == MenuDirectoryView.SEARCH) {
            root.child(row().apply {
                search = MenuTextBox(Sizing.expand()).apply {
                    setMaxLength(100); text(query); setHint(Component.literal(if (category == null) "Try ah, party, fps, animations…" else "Search this category…"))
                    onChanged().subscribe { query = it; limit = 50; populate() }
                }
                child(search)
                if (category != null) child(button(listOf("All", "Enabled", "Disabled")[filter], 66) { anchor ->
                    openMenuDropdown(anchor) { dropdown -> listOf("All features", "Enabled", "Disabled").forEachIndexed { index, text ->
                        dropdown.button(Component.literal(text)) { filter = index; anchor.message = Component.literal(listOf("All", "Enabled", "Disabled")[filter]); populate() }
                    } }
                })
            })
            if (category != null) root.child(button(section ?: "All sections", minOf(150, menuWidth - 36)) { anchor ->
                openMenuDropdown(anchor) { dropdown ->
                    (listOf<String?>(null) + catalog.features.filter { it.category == category }.map { it.subcategory }.distinct()).forEach { next ->
                        dropdown.button(Component.literal(next ?: "All sections")) {
                            section = next; anchor.message = Component.literal(next ?: "All sections"); limit = 50; populate()
                        }
                    }
                }
            })
        }
        // Keep the gutters outside the header-aligned cards, while the wider viewport clips scrolling.
        results = column().apply { gap(8); padding(Insets.of(18, 26, 18, 22)) }
        root.child(menuDirectoryScroll(results))
        populate()
    }

    private fun populate() {
        if (!::results.isInitialized) return
        results.clearChildren()
        observedRevision = MenuShortcuts.revision
        when {
            category != null && query.isBlank() -> showFeatures(catalog.features.filter { it.category == category && matchesFilter(it) })
            category != null || view == MenuDirectoryView.SEARCH -> showSearch()
            view == MenuDirectoryView.QUICK -> showQuick()
            else -> {
                val ids = if (view == MenuDirectoryView.FAVORITES) MenuShortcuts.favorites else MenuShortcuts.recent
                if (ids.isEmpty()) results.child(label(if (view == MenuDirectoryView.FAVORITES)
                    "Favorite a feature or setting from a card or search result." else "Opened features appear here.", MenuTheme.MUTED))
                ids.forEach { id ->
                    if (id.startsWith("setting:")) catalog.settingEntry(id.removePrefix("setting:"))?.let { results.child(settingCard(it)) }
                    else catalog.findFeature(id)?.let { results.child(featureCard(it, true)) }
                }
            }
        }
    }

    private fun matchesFilter(feature: MenuFeature) = (section == null || section == feature.subcategory) && when (filter) {
        1 -> featureEnabled(feature) == true
        2 -> featureEnabled(feature) == false
        else -> true
    }
    private fun showFeatures(features: List<MenuFeature>) {
        if (features.isEmpty()) results.child(label("No features match this filter.", MenuTheme.MUTED))
        val available = menuDirectoryContentWidth(menuWidth)
        val columns = menuFeatureColumns(available)
        val cardWidth = ((available - (columns - 1) * 8) / columns).coerceAtLeast(1)
        features.groupBy { it.subcategory }.forEach { (section, items) ->
            results.child(label(section, MenuTheme.ACCENT, 11f))
            items.chunked(columns).forEach { group -> results.child(row().apply {
                gap(8); verticalAlignment(VerticalAlignment.TOP)
                group.forEach { child(featureCard(it, available = cardWidth - 20).horizontalSizing(Sizing.fixed(cardWidth))) }
            }) }
        }
    }
    private fun showSearch() {
        if (category == null && query.isBlank()) {
            results.child(label("Type to search every category, or choose a category from the icon rail.", MenuTheme.MUTED).horizontalSizing(Sizing.fill()))
            results.child(actions(listOf("Dungeon routes", "Chest profit", "Animations").map { example ->
                button(example, 110) { search.text(example); focusSearch = true }
            }))
            return
        }
        val entries = catalog.search(query).filter { entry ->
            (category == null || entry.category == category) && (query.isNotBlank() || entry.settingPath == null) &&
                (category == null || catalog.findFeature(entry.featureId)?.let(::matchesFilter) == true)
        }
        results.child(label("${entries.size} ${if (query.isBlank()) "features" else "results"}", MenuTheme.MUTED))
        if (entries.isEmpty()) results.child(label("No matches. Try a feature name, description or alias such as ah, hud or party.", MenuTheme.MUTED).horizontalSizing(Sizing.fill()))
        entries.take(limit).groupBy { it.category }.forEach { (group, items) ->
            results.child(label(group.title, MenuTheme.ACCENT, 11f))
            menuSearchFeatureGroups(catalog, items).forEach { (feature, settings) ->
                results.child(featureCard(feature, true))
                settings.forEach { results.child(settingCard(it, indent = 14)) }
            }
        }
        if (entries.size > limit) results.child(button("Show more (${entries.size - limit})", 150) { limit += 50; populate() })
    }

    private fun settingCard(entry: MenuSearchEntry, indent: Int = 0) = card("search/${entry.id}").apply {
        margins(Insets.left(indent))
        val path = checkNotNull(entry.settingPath)
        child(row().apply {
            child(label(entry.title, MenuTheme.TEXT, 11f).horizontalSizing(Sizing.expand()))
            if (entry.favoriteEligible) child(favoriteButton(entry.id, entry.title))
        })
        child(label(entry.breadcrumb, MenuTheme.ACCENT, 9f).horizontalSizing(Sizing.fill()))
        child(label(entry.description, MenuTheme.MUTED, 9f).horizontalSizing(Sizing.fill()))
        val controls = mutableListOf(button("Open setting", 94) { openMenuSetting(path) })
        if (entry.quickSettingEligible && MenuShortcuts.eligible(path)) controls += pinButton(path)
        child(actions(controls, menuDirectoryContentWidth(menuWidth) - 20 - indent))
    }
    private fun favoriteButton(id: String, title: String) = button(if (MenuShortcuts.isFavorite(id)) "★" else "☆", 26) {
        if (!MenuShortcuts.toggleFavorite(id)) showMenuToast("Favorites is full (32 shortcuts)", MenuToastKind.INFO)
        populate()
    }.apply { tooltip(Component.literal("Favorite $title")); selected = { MenuShortcuts.isFavorite(id) } }

    private fun featureCard(feature: MenuFeature, breadcrumb: Boolean = false, available: Int = menuDirectoryContentWidth(menuWidth) - 20) = card("feature/${feature.id}").apply {
        child(row().apply {
            child(label(feature.title, MenuTheme.TEXT, 12f).horizontalSizing(Sizing.expand()))
            if (feature.favoriteEligible) child(favoriteButton(feature.id, feature.title))
        })
        if (breadcrumb) child(label(feature.breadcrumb, MenuTheme.ACCENT, 9f).horizontalSizing(Sizing.fill()))
        child(label(feature.description, MenuTheme.MUTED).horizontalSizing(Sizing.fill()))
        val controls = mutableListOf(button(if (feature.tool == null) "Configure" else "Open", 76) { openMenuFeature(feature.id) })
        if (feature.settings.isNotEmpty() && feature.tool != null && feature.tool != "cosmetics")
            controls += button("Configure", 76) { openMenuFeature(feature.id, configure = true) }
        enabledSetting(feature)?.takeUnless { feature.id == "community" }?.let { setting ->
            controls += button(if (setting.entry.boolean) "On" else "Off", 64) {
                if (setting.entry.setBoolean(!setting.entry.boolean)) quickChanged(setting.path)
                populate()
            }.apply { toggled = { setting.entry.boolean }; tooltip(Component.literal("Enable ${feature.title}")) }
        }
        child(actions(controls, available))
        child(row().apply {
            featureEnabled(feature)?.takeUnless { feature.id == "community" }?.let { child(label(if (it) "Enabled" else "Disabled", if (it) MenuTheme.ACCENT else MenuTheme.MUTED)) }
            if (feature.settings.isNotEmpty()) child(label("${feature.settings.size} options", MenuTheme.MUTED, 9f))
        })
    }

    private fun showQuick() {
        results.child(actions(listOf(
            button("Add controls", 98) { selectView(MenuDirectoryView.SEARCH) },
            button("Reset defaults", 105) { MenuShortcuts.resetPins(); populate() }), menuDirectoryContentWidth(menuWidth)))
        results.child(label("${MenuShortcuts.pins.size}/8 pinned • values come from their original controls", MenuTheme.MUTED).horizontalSizing(Sizing.fill()))
        MenuShortcuts.pins.forEachIndexed { index, path ->
            val setting = catalog.setting(path) ?: return@forEachIndexed
            val owner = catalog.featureForPath(path) ?: return@forEachIndexed
            results.child(card("quick/$path").apply {
                child((if (menuWidth < 350) column() else row()).apply {
                    gap(6)
                    child(label(setting.title, MenuTheme.TEXT, 11f).horizontalSizing(if (menuWidth < 350) Sizing.fill() else Sizing.expand()))
                    child(quickControl(setting))
                })
                child(label("${owner.breadcrumb} → ${setting.title}", MenuTheme.ACCENT, 9f).horizontalSizing(Sizing.fill()))
                child(actions(listOf(
                    button("Full settings", 90) { openMenuSetting(path) },
                    button("↑", 24) { MenuShortcuts.move(path, -1); populate() }.apply { active(index > 0); tooltip(Component.literal("Move up")) },
                    button("↓", 24) { MenuShortcuts.move(path, 1); populate() }.apply { active(index < MenuShortcuts.pins.lastIndex); tooltip(Component.literal("Move down")) },
                    button("Remove", 60) { MenuShortcuts.togglePin(path); populate() })))
            })
        }
        quickValues = MenuShortcuts.pins.map { catalog.setting(it)?.entry?.get() }
        if (MenuShortcuts.pins.isEmpty()) results.child(label("Pin a setting from search or its original settings page.", MenuTheme.MUTED))
    }
    private fun quickControl(setting: SettingRow): ButtonComponent {
        val entry = setting.entry
        return when (entry.type()) {
            EntryType.BOOLEAN -> button(if (entry.boolean) "On" else "Off", 64) { anchor ->
                if (entry.setBoolean(!entry.boolean)) quickChanged(setting.path)
                anchor.message = Component.literal(if (entry.boolean) "On" else "Off")
                quickValues = MenuShortcuts.pins.map { catalog.setting(it)?.entry?.get() }
            }.apply { toggled = { entry.boolean } }
            EntryType.ENUM -> button(entry.enum.toString(), 126) { anchor ->
                val options = entry.objectType().enumConstants.filterIsInstance<Enum<*>>()
                openMenuDropdown(anchor) { dropdown -> options.forEach { option -> dropdown.button(Component.literal(option.toString())) {
                    if (entry.setEnum(option)) quickChanged(setting.path)
                    anchor.message = Component.literal(entry.enum.toString())
                    quickValues = MenuShortcuts.pins.map { catalog.setting(it)?.entry?.get() }
                } } }
            }
            else -> button("Edit: ${entry.get()}", 120) { openMenuSetting(setting.path) }
        }.apply {
            horizontalSizing(Sizing.fixed(horizontalSizing().get().value.coerceAtMost((menuDirectoryContentWidth(menuWidth) - 20).coerceAtLeast(1))))
            tooltip(Component.literal(setting.tip))
        }
    }
    private fun quickChanged(path: String) {
        SkyMyce.config.save(); notifySettingsChanged()
        if (path == "Theme & Appearance/preset") { me.mycellium.skymyce.hud.HudTheme.changed(); rebuildMenu() }
    }
    private fun pinButton(path: String) = button(if (MenuShortcuts.isPinned(path)) "Unpin" else "Pin", 54) {
        if (!MenuShortcuts.togglePin(path)) showMenuToast("Quick Settings is full (8 controls)", MenuToastKind.INFO)
        populate()
    }

    override fun tick() {
        super.tick()
        if (menuClosing) return
        if (focusSearch && (category != null || view == MenuDirectoryView.SEARCH) && ::search.isInitialized) {
            search.focusHandler()?.focus(search, UIComponent.FocusSource.KEYBOARD_CYCLE); focusSearch = false
        }
        if (observedRevision != MenuShortcuts.revision) populate()
        if (view == MenuDirectoryView.QUICK && category == null && menuQuickValuesChanged(MenuShortcuts.pins, quickValues) {
            catalog.setting(it)?.entry?.get()
        }) populate()
    }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content())
    private fun actions(controls: List<ButtonComponent>, available: Int = menuDirectoryContentWidth(menuWidth) - 20) = column().apply {
        gap(6)
        val width = available.coerceAtLeast(1)
        val sizes = controls.map { it.horizontalSizing().get().value.coerceAtMost(width) }
        menuActionRows(sizes, width).forEach { indices -> child(row().apply {
            indices.forEach { index -> child(controls[index].apply { horizontalSizing(Sizing.fixed(sizes[index])) }) }
        }) }
    }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); verticalAlignment(VerticalAlignment.CENTER) }
    private fun card(key: String) = MenuRevealCard("directory/$key", Sizing.fill(), Sizing.content()).apply { surface(MenuTheme.panel(true)); padding(Insets.of(10)); gap(5) }
    private fun label(text: String, color: Int = MenuTheme.TEXT, size: Float = 10f) = MenuLabel(Component.literal(text)).apply { color(Color.ofRgb(color)); fontSize = size }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).apply { sizing(Sizing.fixed(width), Sizing.fixed(22)) }
}

internal fun menuFeatureColumns(available: Int) = if (available >= 580) 2 else 1

/** Widen into the outer padding so motion gutters do not indent cards beyond the page header. */
internal fun menuDirectoryScroll(results: FlowLayout) = menuScroll(Sizing.fill(), Sizing.expand(), results).apply {
    margins(Insets.horizontal(-18))
}

/** Outer page padding and four pixels for the scrollbar share the same card/action width. */
internal fun menuDirectoryContentWidth(menuWidth: Int) = (menuWidth - 36 - 4).coerceAtLeast(1)

/** Include each matched setting's owning feature once, retaining first-owner and child match order. */
internal fun menuSearchFeatureGroups(catalog: MenuCatalog, matches: List<MenuSearchEntry>): List<Pair<MenuFeature, List<MenuSearchEntry>>> =
    matches.groupBy { it.featureId }.mapNotNull { (id, entries) ->
        catalog.findFeature(id)?.let { feature -> feature to entries.filter { it.settingPath != null } }
    }

/** Read at most eight pinned values without constructing a replacement list every idle tick. */
internal inline fun menuQuickValuesChanged(paths: List<String>, values: List<Any?>, value: (String) -> Any?): Boolean {
    if (paths.size != values.size) return true
    for (index in paths.indices) if (value(paths[index]) != values[index]) return true
    return false
}

/** Wrap actions before mounting so the same controls remain reachable in narrow viewports. */
internal fun menuActionRows(widths: List<Int>, available: Int, gap: Int = 6): List<List<Int>> = buildList {
    var line = mutableListOf<Int>(); var used = 0
    widths.forEachIndexed { index, size ->
        val width = size.coerceIn(1, available.coerceAtLeast(1))
        if (line.isNotEmpty() && used + gap + width > available) { add(line); line = mutableListOf(); used = 0 }
        used += width + if (line.isEmpty()) 0 else gap; line.add(index)
    }
    if (line.isNotEmpty()) add(line)
}

internal fun enabledSetting(feature: MenuFeature): SettingRow? {
    val row = feature.enabledPath?.let { path -> feature.settings.firstOrNull { it.path == path } }
        ?: feature.settings.firstOrNull { it.element.id() == "enabled" && it.entry.type() == EntryType.BOOLEAN }
        ?: return null
    return row
}

internal fun featureEnabled(feature: MenuFeature): Boolean? = enabledSetting(feature)?.entry?.boolean

/** UI identities are independent of saved Resourceful Config category names and keys. */
enum class MenuCategory(val title: String, val description: String, val iconId: Int) {
    DUNGEONS("Dungeons", "Dungeon parties, routes, rewards and run history", 4),
    COMBAT("Combat", "Ability timers, equipment and instance helpers", 9),
    ECONOMY("Economy", "Auctions, the Garden and item values", 5),
    COSMETICS("Cosmetics", "Player names, visual scaling and cosmetic selection", 6),
    UTILS("Utils", "Daily progress, social tools and general helpers", 1),
    SETTINGS("Settings", "Configure controls, graphics, HUD and appearance", 8),
}

data class MenuFeature(
    val id: String,
    val title: String,
    val description: String,
    val category: MenuCategory,
    val subcategory: String,
    val aliases: List<String> = emptyList(),
    val iconId: Int = category.iconId,
    val tool: String? = null,
    val settings: List<SettingRow> = emptyList(),
    val searchable: Boolean = true,
    val favoriteEligible: Boolean = true,
    val enabledPath: String? = null,
) {
    val breadcrumb get() = listOf(category.title, subcategory, title).distinct().joinToString(" → ")
}

data class MenuSettingLocation(val category: MenuCategory, val subcategory: String, val featureId: String? = null)

data class MenuSearchEntry(
    val id: String,
    val title: String,
    val description: String,
    val category: MenuCategory,
    val subcategory: String,
    val featureId: String,
    val settingPath: String? = null,
    val aliases: List<String> = emptyList(),
    val quickSettingEligible: Boolean = settingPath != null,
    val favoriteEligible: Boolean = true,
    val featureTitle: String? = null,
) {
    val breadcrumb get() = listOfNotNull(category.title, subcategory, featureTitle, title).distinct().joinToString(" → ")
}

/** One inventory supplies category cards, feature settings, search and canonical shortcuts. */
class MenuCatalog(rows: List<SettingRow>) {
    val features = definitions.map { feature -> feature.copy(settings = rows.filter { row ->
        val owner = settingLocation(row.path)
        if (owner.featureId == null) feature.category == MenuCategory.SETTINGS && owner.subcategory == feature.subcategory
        else owner.featureId == feature.id
    }) }
    private val byId = features.associateBy { it.id }
    private val byPath = features.flatMap { feature -> feature.settings.map { it.path to feature } }.toMap()
    private val entries by lazy { features.filter { it.searchable }.flatMap { feature ->
        listOf(MenuSearchEntry("feature:${feature.id}", feature.title, feature.description, feature.category,
            feature.subcategory, feature.id, aliases = feature.aliases)) + feature.settings.map { row ->
            MenuSearchEntry("setting:${row.path}", row.title, row.tip, feature.category, feature.subcategory, feature.id,
                row.path, feature.aliases + listOf(feature.title, row.path) +
                    if (row.entry.options().hasOption(Option.KEYBIND)) listOf("keybind", "keyboard", "controls") else emptyList(),
                quickSettingEligible = !row.path.endsWith("/hypixelApiKey"), featureTitle = feature.title)
        }
    } + extraSettings.filter { it.settingPath !in byPath } }
    private val searchIndex by lazy { MenuSearchIndex(entries) }

    fun findFeature(id: String) = byId[id]
    fun featureForPath(path: String) = byPath[path] ?: extraSettings.firstOrNull { it.settingPath == path }?.let { byId[it.featureId] }
    fun setting(path: String) = byPath[path]?.settings?.firstOrNull { it.path == path }
    fun settingEntry(path: String) = entries.firstOrNull { it.settingPath == path }
    fun search(query: String) = searchIndex.search(query)

    companion object {
        val definitions = listOf(
            MenuFeature("community", "Discord & Community", "Connect Discord, choose community data sharing (On, On (anon) or Off) and updates", MenuCategory.SETTINGS, "Community", listOf("discord", "connect", "privacy", "sharing", "updates", "account"), tool = "community"),
            MenuFeature("leaderboards", "Community Leaderboards", "Compare consenting participants with source labels and UTC periods", MenuCategory.UTILS, "Progress", listOf("rank", "ranking", "economy", "dungeons", "rng", "weekly", "monthly"), tool = "leaderboards"),
            MenuFeature("rng-journal", "RNG Journal", "Search preserved personal acquisitions, dates, counts and measured history", MenuCategory.UTILS, "Progress", listOf("rare", "drops", "history", "journal", "rng", "weekly", "monthly"), tool = "rng-journal"),
            MenuFeature("party-finder", "Party Finder", "Online dungeon friends, LFG availability, invites and party filters", MenuCategory.DUNGEONS, "Parties", listOf("party", "friends", "lfg", "availability", "dungeon-friends", "secondary classes"), tool = "party-finder", enabledPath = "Dungeons/Dungeon Friends/enabled"),
            MenuFeature("starred-routes", "Dungeon Routing", "Follow doors toward Blood, optionally visiting starred mobs", MenuCategory.DUNGEONS, "Routes", listOf("route", "path", "blood", "wither", "healer", "starred", "mobs", "fairy", "straight", "starred clear routing"), enabledPath = "Dungeons/Starred Clear Routing/enabled"),
            MenuFeature("dungeon-tracker", "Dungeon Tracker", "Run history, loot records and estimated dungeon rewards", MenuCategory.DUNGEONS, "Runs", listOf("loot", "profit", "bazaar", "history", "rng"), tool = "dungeon-tracker", enabledPath = "Dungeons/Dungeons Tracker/dungeonTracker"),
            MenuFeature("dungeon-splits", "Dungeon Splits", "Live run splits, personal bests, best possible time and averages per floor", MenuCategory.DUNGEONS, "Runs", listOf("splits", "timer", "pb", "personal best", "theoretical", "average", "300", "terminals", "necron", "relics"), tool = "dungeon-splits", enabledPath = "Dungeons/Dungeon Splits/enabled"),
            MenuFeature("goldor-coordination", "Goldor Coordination", "F7/M7 terminal map, ADG role routes, stacks and timed pre-leaps, shared in party chat", MenuCategory.DUNGEONS, "Boss", listOf("terminals", "terminal map", "adg", "goldor", "p3", "pre-leap", "ee2", "ee3", "core", "mee2", "hee3", "leap", "stack", "levers", "devices"), enabledPath = "Dungeons/Goldor Coordination/enabled"),
            MenuFeature("healer-crush-camera", "Crush Camera", "Class-specific fixed views from 26 seconds into Storm until Storm dies", MenuCategory.DUNGEONS, "Boss", listOf("camera", "healer", "crush", "purple", "yellow", "storm", "f7", "m7", "preview"), tool = "healer-crush-camera", enabledPath = "Dungeons/Healer Crush Camera/enabled"),
            MenuFeature("leap-menu", "Spirit Leap Menu", "Choose a teammate with four local gameplay previews", MenuCategory.DUNGEONS, "Parties", listOf("spirit", "leap", "teleport", "pov", "camera", "teammates"), enabledPath = "Dungeons/Leap Menu/enabled"),
            MenuFeature("dungeon-chests", "Dungeon Chests", "Estimated chest profit, valuable loot and purchase/reroll protection", MenuCategory.DUNGEONS, "Rewards", listOf("profit", "kismet", "reroll", "bazaar", "prices", "chest")),
            MenuFeature("dungeon-chat", "Dungeon Chat Filter", "Choose which dungeon messages remain visible", MenuCategory.DUNGEONS, "Information", listOf("chat", "messages", "filter")),
            MenuFeature("dungeon-summary", "Dungeon Win Screen", "Show a summary overlay after a completed dungeon", MenuCategory.DUNGEONS, "Runs", listOf("overlay", "hud", "win"), enabledPath = "Dungeons/dungeonWinScreen"),
            MenuFeature("status-bars", "Status Bars", "Separate animated health, mana, defense, speed, vitality, respiration and experience bars you can arrange around the hotbar", MenuCategory.COMBAT, "Player", listOf("health", "mana", "defense", "ehp", "hearts", "bar", "skyblocker", "hud", "absorption", "overflow", "speed", "vitality", "respiration", "air", "experience", "xp", "level", "fancy bars"), tool = "status-bars", enabledPath = "General/Status Bars/enabled"),
            MenuFeature("ability-cooldowns", "Ability Cooldowns", "Held-item ability timers and ready notifications", MenuCategory.COMBAT, "Timers", listOf("cooldown", "timer", "ability", "hud", "notification"), enabledPath = "General/displayAbilityCooldown"),
            MenuFeature("loadouts", "Loadout Keybinds", "Swap equipment loadouts with configured keys and menu safeguards", MenuCategory.COMBAT, "Gear", listOf("equipment", "keybind", "keyboard", "armor"), enabledPath = "General/Loadout Keybinds/enabled"),
            MenuFeature("instance-helpers", "Instance Helpers", "Missing-player alerts and requeue behavior for dungeon and Kuudra parties", MenuCategory.COMBAT, "Instances", listOf("dungeons", "kuudra", "party", "requeue", "downtime")),
            MenuFeature("bal-notifier", "Bal Notifications", "Share Bal spawn and health milestones with your party", MenuCategory.COMBAT, "Enemies", listOf("bal", "boss", "mob", "mining", "health"), enabledPath = "Mining/balNotify"),
            MenuFeature("auction-browser", "Auction Browser", "Search cached auction listings, filters, bids and item lore", MenuCategory.ECONOMY, "Auctions", listOf("ah", "auction house", "market", "prices", "bin"), tool = "auction-browser"),
            MenuFeature("price-history", "Price History", "Hover an item in a menu and press P for its Bazaar or auction price graph", MenuCategory.ECONOMY, "Item Values", listOf("price", "graph", "chart", "history", "neu", "bazaar", "auction", "bin", "coflnet"), enabledPath = "General/Price History/enabled"),
            MenuFeature("greenhouse-planner", "Greenhouse Planner", "Your greenhouses, profit-ranked mutations per plot, layouts, growth timers, harvest profit and ghost blocks. /sm greenhouse help", MenuCategory.ECONOMY, "Garden", listOf("garden", "greenhouse", "mutation", "crops", "farming", "skymutations", "layout", "ghost", "harvest", "profit history"), tool = "greenhouse-planner", enabledPath = "General/Greenhouse Planner/enabled"),
            MenuFeature("stash-helper", "Stash Value Filter", "Filter low-value stash items and prevent unwanted pickups", MenuCategory.ECONOMY, "Item Values", listOf("stash", "price", "coins", "value", "filter"), enabledPath = "General/Stash Helper/stashHelper"),
            MenuFeature("cosmetics", "Player Cosmetics", "Choose cosmetics and control rendered names and player scaling", MenuCategory.COSMETICS, "Player Appearance", listOf("appearance", "names", "scale", "scaling", "preview"), tool = "cosmetics"),
            MenuFeature("daily-digest", "Daily Digest", "Daily progress, public updates and optional community RNG reports", MenuCategory.UTILS, "Progress", listOf("news", "rng", "daily", "history", "rewards"), tool = "daily-digest"),
            MenuFeature("global-chat", "IRC Chat", "Global Minecraft/Discord chat and Minecraft-only Party IRC. /sm irc or /sm party", MenuCategory.UTILS, "Social", listOf("irc", "discord", "messages", "social", "global", "party", "media", "gif", "replies", "reactions", "ping", "global skymyce chat", "chat sky"), tool = "global-chat", enabledPath = "General/Global SkyMyce Chat/enabled"),
            MenuFeature("party-commands", "Party Commands", "Choose party commands, cooldowns and the optional stats API fallback", MenuCategory.UTILS, "Social", listOf("party", "pb", "downtime", "commands", "api")),
            MenuFeature("player-utilities", "Player Utilities", "Cached friend activity, locations and profile information", MenuCategory.UTILS, "Social", listOf("friends", "player", "profiles", "online", "social"), tool = "player-utilities"),
            MenuFeature("mining", "Crystal Hollows Helpers", "Lobby information and configurable nucleus warp timing", MenuCategory.UTILS, "Mining", listOf("mining", "fortune", "crystal", "nucleus", "powder", "lobby", "hud")),
            MenuFeature("auto-refill", "Auto Refill", "Choose which consumable items are refilled when empty", MenuCategory.UTILS, "Inventory", listOf("refill", "items", "consumables", "inventory")),
            MenuFeature("npc-dialogue", "NPC Dialogue", "Show NPC lines in a dialogue box with the speaker's portrait, and pick options with number keys", MenuCategory.UTILS, "Information", listOf("npc", "dialogue", "chat", "skycubed", "quest", "options", "talk"), enabledPath = "General/NPC Dialogue/enabled"),
            MenuFeature("sign-confirm", "Sign Enter", "Press Enter to finish Auction House, Bazaar and other signs", MenuCategory.UTILS, "Inventory", listOf("sign", "enter", "confirm", "ah", "bazaar", "price", "bid", "amount", "done")),
            MenuFeature("information", "Information Overlays", "Display client details and skill XP progress", MenuCategory.UTILS, "Information", listOf("xp", "skill", "client", "hud", "overlay")),
            MenuFeature("settings-appearance", "Appearance", "Theme preset and graphics mode; the mode also sets GUI animations", MenuCategory.SETTINGS, "Appearance", listOf("theme", "colors", "preset", "animation", "animations", "motion", "accessibility", "reduced motion", "fps", "graphics", "quality", "rendering", "performance")),
            MenuFeature("settings-hud", "HUD", "Arrange widgets, visibility and scale", MenuCategory.SETTINGS, "HUD", listOf("hud", "overlay", "layout", "widget", "scale", "visibility"), tool = "hud-editor"),
            MenuFeature("settings-controls", "Controls", "Choose the key that opens the Sky-Myce menu", MenuCategory.SETTINGS, "Controls", listOf("keybind", "keyboard", "input", "controls")),
        )

        val routingSettingGroups = linkedMapOf(
            "Route" to listOf("enabled", "bloodRushClassesOnly", "routeThroughMobs", "routeThroughStarredMinibosses", "showRoute"),
            "Mob markers" to listOf("showTargets", "showNumbers", "showCleared"),
            "Appearance" to listOf("thickness", "opacity", "lineHeight"),
            "Advanced" to listOf("showHealer", "automaticRecalculation", "debug"),
        )
        val extraSettings: List<MenuSearchEntry> = buildList {
            fun addSetting(featureId: String, path: String, title: String, description: String, aliases: List<String>) {
                val feature = definitions.first { it.id == featureId }
                add(MenuSearchEntry("setting:$path", title, description, feature.category, feature.subcategory, feature.id,
                    path, feature.aliases + aliases, quickSettingEligible = false, featureTitle = feature.title))
            }
            addSetting("party-finder", "Dungeons/Dungeon Friends/availability", "Dungeon Availability",
                "Choose your available floor, classes and join PB limit", listOf("availability", "floor", "classes", "personal best", "pb limit"))
            addSetting("party-finder", "Dungeons/Dungeon Friends/messageTemplate", "LFG Message Template",
                "Customize the friend Message action using name, class and floor placeholders", listOf("message", "template", "lfg"))
            addSetting("party-finder", "Dungeons/Dungeon Friends/titleNotifications", "Title Notifications",
                "Show invitations and joining players as titles", listOf("notification", "titles", "invite"))
        }

        fun settingLocation(path: String): MenuSettingLocation {
            val key = path.substringAfterLast('/')
            val feature = when {
                path.startsWith("Community Sharing/") -> "community"
                path.startsWith("Party Finder/") || path.startsWith("Dungeons/Dungeon Friends/") -> "party-finder"
                path.startsWith("Dungeons/Starred Clear Routing/") -> "starred-routes"
                path.startsWith("Dungeons/Healer Crush Camera/") -> "healer-crush-camera"
                path.startsWith("Dungeons/Leap Menu/") -> "leap-menu"
                path.startsWith("Dungeons/Dungeons Tracker/") -> "dungeon-tracker"
                path.startsWith("Dungeons/Dungeon Splits/") -> "dungeon-splits"
                path.startsWith("Dungeons/Goldor Coordination/") -> "goldor-coordination"
                path.startsWith("Dungeons/") && key.startsWith("dungeonWinScreen") -> "dungeon-summary"
                path.startsWith("Dungeons/") && key == "dungeonMessageFilter" -> "dungeon-chat"
                path.startsWith("Dungeons/") -> "dungeon-chests"
                path.startsWith("Instances/") -> "instance-helpers"
                path.startsWith("Cosmetics/") -> "cosmetics"
                path.startsWith("Mining/") && key == "balNotify" -> "bal-notifier"
                path.startsWith("Mining/") -> "mining"
                path.startsWith("General/Daily Digest/") -> "daily-digest"
                path.startsWith("General/Global SkyMyce Chat/") -> "global-chat"
                path.startsWith("General/Party Commands/") -> "party-commands"
                path.startsWith("General/Stash Helper/") -> "stash-helper"
                path.startsWith("General/Price History/") -> "price-history"
                path.startsWith("General/Greenhouse Planner/") -> "greenhouse-planner"
                path.startsWith("General/Status Bars/") -> "status-bars"
                path.startsWith("General/NPC Dialogue/") -> "npc-dialogue"
                path.startsWith("General/Signs/") -> "sign-confirm"
                path.startsWith("General/Loadout Keybinds/") -> "loadouts"
                path == "General/autoRefill" -> "auto-refill"
                path == "General/displayAbilityCooldown" || path == "General/displayAbilityNotification" -> "ability-cooldowns"
                path == "General/clientInfo" || path == "General/displayXp" || path == "General/displayXpBar" -> "information"
                else -> null
            }
            if (feature != null) return definitions.first { it.id == feature }.let {
                MenuSettingLocation(it.category, it.subcategory, it.id)
            }
            val section = when {
                path.startsWith("Theme & Appearance/") -> "Appearance"
                else -> "Controls"
            }
            return MenuSettingLocation(MenuCategory.SETTINGS, section)
        }
    }
}

/**
 * Small metadata aliases supplement real titles/descriptions; all query words must match.
 * The inventory is immutable; normalize and sort it once instead of rebuilding every setting on each keystroke.
 */
internal class MenuSearchIndex(entries: List<MenuSearchEntry>) {
    private val ordered = entries.sortedWith(compareBy<MenuSearchEntry> { it.category.ordinal }.thenBy { it.subcategory }
        .thenBy { it.settingPath != null }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    private val words = ordered.map { entry ->
        wordPattern.findAll((listOf(entry.title, entry.description, entry.category.title, entry.subcategory) + entry.aliases)
            .joinToString(" ").lowercase(Locale.ROOT)).map { it.value }.toSet()
    }

    fun search(query: String): List<MenuSearchEntry> {
        val tokens = wordPattern.findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (tokens.isEmpty()) return ordered
        return ordered.filterIndexed { index, _ -> tokens.all { token -> words[index].any { menuSearchWordMatches(it, token) } } }
    }

    companion object { private val wordPattern = Regex("[a-z0-9]+") }
}

/** Prefixes work from the first keystroke; longer words tolerate one insertion, deletion or substitution. */
internal fun menuSearchWordMatches(word: String, token: String): Boolean {
    if (token.length <= 2) return word.startsWith(token)
    if (word.contains(token)) return true
    if (token.length < 4 || kotlin.math.abs(word.length - token.length) > 1) return false
    var left = 0
    var right = 0
    var edits = 0
    while (left < word.length && right < token.length) {
        if (word[left] == token[right]) { left++; right++; continue }
        if (++edits > 1) return false
        when {
            word.length > token.length -> left++
            word.length < token.length -> right++
            else -> { left++; right++ }
        }
    }
    return edits + (word.length - left) + (token.length - right) <= 1
}

object MenuDirectory {
    val catalog by lazy { MenuCatalog(settingRows(SkyMyce.config)) }
}

internal val defaultMenuPins = listOf("Theme & Appearance/preset", "Theme & Appearance/graphicsQuality",
    "General/displayAbilityCooldown")
internal val defaultMenuHomeTools = listOf("party-finder", "dungeon-tracker", "auction-browser", "player-utilities", "cosmetics", "daily-digest")

internal fun canUseMenuHomeTool(feature: MenuFeature) = feature.tool != null && feature.favoriteEligible
private fun validMenuHomeTool(id: String) = MenuCatalog.definitions.any { it.id == id && canUseMenuHomeTool(it) }
private fun normalizedMenuHomeTools(ids: List<String>) =
    (ids.asSequence() + defaultMenuHomeTools.asSequence() + MenuCatalog.definitions.asSequence().map { it.id })
        .filter(::validMenuHomeTool).distinct().take(6).toList()

/** Shortcuts contain identities only. Values always come from the existing config entries. */
internal data class MenuShortcutState(val pins: List<String> = defaultMenuPins,
                                      val favorites: List<String> = emptyList(), val recent: List<String> = emptyList(),
                                      val homeTools: List<String> = defaultMenuHomeTools) {
    fun normalized(validPin: (String) -> Boolean, validFavorite: (String) -> Boolean, validFeature: (String) -> Boolean = validFavorite) = copy(
        pins = pins.distinct().filter(validPin).take(8), favorites = favorites.distinct().filter(validFavorite).take(32),
        recent = recent.distinct().filter(validFeature).take(12), homeTools = normalizedMenuHomeTools(homeTools))
    fun pin(path: String) = if (path in pins || pins.size >= 8) this else copy(pins = pins + path)
    fun unpin(path: String) = copy(pins = pins - path)
    fun move(path: String, direction: Int): MenuShortcutState {
        val index = pins.indexOf(path); val target = index + direction.coerceIn(-1, 1)
        if (index < 0 || target !in pins.indices) return this
        return copy(pins = pins.toMutableList().apply { val value = removeAt(index); add(target, value) })
    }
    fun favorite(id: String) = if (id in favorites) copy(favorites = favorites - id)
        else if (favorites.size < 32) copy(favorites = favorites + id) else this
    fun visit(id: String) = copy(recent = (listOf(id) + recent.filter { it != id }).take(12))
    fun replaceHomeTool(index: Int, id: String): MenuShortcutState {
        if (index !in 0..5 || index !in homeTools.indices || !validMenuHomeTool(id) || homeTools[index] == id) return this
        val previous = homeTools[index]
        val existing = homeTools.indexOf(id)
        return copy(homeTools = homeTools.toMutableList().apply {
            this[index] = id
            if (existing >= 0) this[existing] = previous
        })
    }
    fun json() = JsonObject().apply {
        addProperty("version", 1)
        fun array(values: List<String>) = JsonArray().apply { values.forEach(::add) }
        add("pins", array(pins)); add("favorites", array(favorites)); add("recent", array(recent)); add("homeTools", array(homeTools))
    }
    companion object {
        fun read(json: JsonElement?): MenuShortcutState {
            if (json == null) return MenuShortcutState()
            val obj = json.asJsonObject
            require(obj["version"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" } == true) { "Unsupported menu shortcut format" }
            fun strings(key: String): List<String> {
                val values = obj[key] ?: return emptyList()
                require(values.isJsonArray && values.asJsonArray.size() <= 128)
                return values.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { s -> require(s.length <= 256) } }
            }
            return MenuShortcutState(strings("pins"), strings("favorites"), strings("recent"),
                if (obj.has("homeTools")) strings("homeTools") else defaultMenuHomeTools)
        }
    }
}

internal fun canPinMenuSetting(row: SettingRow) = !row.entry.isArray && row.entry.type() in
    listOf(EntryType.BOOLEAN, EntryType.ENUM, EntryType.INTEGER, EntryType.DOUBLE)

object MenuShortcuts {
    private var state = MenuShortcutState()
    private var writer: StateWriter<MenuShortcutState>? = null
    var revision = 0L
        private set
    val pins get() = state.pins
    val favorites get() = state.favorites
    val recent get() = state.recent
    val homeTools get() = state.homeTools

    /** Startup only; the bounded read precedes any menu callbacks, saves run on the scheduler. */
    fun initialize() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("menu-shortcuts.json"), 16384)
        var recoverable = true
        state = runCatching { MenuShortcutState.read(file.read()) }.getOrElse {
            runCatching { file.preserveOriginal("unreadable-${System.currentTimeMillis()}") }.onFailure { recoverable = false }
            SkyMyce.logger.warn("Menu shortcuts were unreadable; using defaults and retaining the original")
            MenuShortcutState()
        }.normalized(::eligible, ::validFavorite, ::validFeature)
        if (file.damaged) SkyMyce.logger.warn("Damaged menu shortcuts; defaults restored and the original will be backed up on save")
        if (!recoverable) SkyMyce.logger.warn("Menu shortcut backup unavailable; shortcut writes paused")
        writer = StateWriter({ if (recoverable) file.write(it.json()) }, { work -> Scheduling.schedule(100.milliseconds) { work() } }, {
            SkyMyce.logger.warn("Could not save menu shortcuts; previous file retained", it)
        })
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer?.flush() } }, "SkyMyce-Menu-Save"))
        revision++
    }
    fun eligible(path: String) = MenuDirectory.catalog.setting(path)?.let(::canPinMenuSetting) == true
    private fun validFeature(id: String) = MenuDirectory.catalog.findFeature(id)?.favoriteEligible == true
    private fun validFavorite(id: String) = if (id.startsWith("setting:")) MenuDirectory.catalog.settingEntry(id.removePrefix("setting:"))?.favoriteEligible == true else validFeature(id)
    fun isPinned(path: String) = path in state.pins
    fun isFavorite(id: String) = id in state.favorites
    fun togglePin(path: String): Boolean {
        if (!eligible(path)) return false
        return update(if (isPinned(path)) state.unpin(path) else state.pin(path))
    }
    fun move(path: String, direction: Int) = update(state.move(path, direction))
    fun resetPins() = update(state.copy(pins = defaultMenuPins.filter(::eligible)))
    fun replaceHomeTool(index: Int, id: String) = update(state.replaceHomeTool(index, id))
    fun resetHomeTools() = update(state.copy(homeTools = normalizedMenuHomeTools(defaultMenuHomeTools)))
    fun toggleFavorite(id: String) = validFavorite(id) && update(state.favorite(id))
    fun visit(id: String) { if (validFeature(id)) update(state.visit(id)) }
    private fun update(next: MenuShortcutState): Boolean {
        if (next == state) return false
        state = next; revision++; writer?.submit(next); return true
    }
}

/** Home choices reference the existing tool inventory and never duplicate a tool's controls. */
internal class MenuHomeShortcutsScreen : MenuScreen(Component.literal("Customize Home")) {
    override val menuParent get() = MenuHomeScreen()

    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(14)); root.gap(9)
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Customize Home", MenuTheme.TEXT, 16f).horizontalSizing(Sizing.expand()))
            child(MenuButton(Component.literal("Back")) { onClose() }.apply { sizing(Sizing.fixed(48), Sizing.fixed(24)) })
        })
        root.child(label("Choose the six tools that Home opens. Choosing a tool already on Home swaps its slots.", MenuTheme.MUTED))
        val choices = MenuDirectory.catalog.features.filter(::canUseMenuHomeTool)
        val slots = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(9); padding(Insets.right(4)) }
        MenuShortcuts.homeTools.forEachIndexed { index, id ->
            val feature = checkNotNull(MenuDirectory.catalog.findFeature(id))
            slots.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(5); padding(Insets.of(10)); surface(MenuTheme.panel(true))
                child(label("Tool ${index + 1} · ${feature.category.title}", MenuTheme.ACCENT, 9f))
                child(MenuButton(Component.literal(feature.title)) { anchor ->
                    openMenuDropdown(anchor) { dropdown -> choices.forEach { choice ->
                        dropdown.button(Component.literal(choice.title)) {
                            if (MenuShortcuts.replaceHomeTool(index, choice.id)) rebuildMenu()
                        }
                    } }
                }.apply { sizing(Sizing.fill(), Sizing.fixed(26)); tooltip(Component.literal("Choose the tool for Home slot ${index + 1}")) })
                child(label(feature.description, MenuTheme.MUTED, 9f))
            })
        }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), slots))
        root.child(MenuButton(Component.literal("Reset defaults")) {
            if (MenuShortcuts.resetHomeTools()) rebuildMenu()
        }.apply { sizing(Sizing.fixed(112), Sizing.fixed(24)) })
    }

    private fun label(text: String, color: Int, size: Float = 10f) = MenuLabel(Component.literal(text)).apply {
        color(Color.ofRgb(color)); fontSize = size; horizontalSizing(Sizing.fill())
    }
}
