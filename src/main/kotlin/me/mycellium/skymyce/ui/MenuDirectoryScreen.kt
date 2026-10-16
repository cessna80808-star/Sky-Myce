package me.mycellium.skymyce.ui

import com.teamresourceful.resourcefulconfig.api.types.options.EntryType
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.SettingRow
import me.mycellium.skymyce.config.notifySettingsChanged
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

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
                val options = if (setting.path == "Theme & Appearance/animationMode") GuiMotion.choices else entry.objectType().enumConstants.filterIsInstance<Enum<*>>()
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
