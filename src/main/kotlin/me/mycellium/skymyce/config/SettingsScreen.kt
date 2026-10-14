package me.mycellium.skymyce.config

import com.mojang.blaze3d.platform.InputConstants
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import com.teamresourceful.resourcefulconfig.api.types.elements.ResourcefulConfigEntryElement
import com.teamresourceful.resourcefulconfig.api.types.entries.ResourcefulConfigValueEntry
import com.teamresourceful.resourcefulconfig.api.types.options.EntryType
import com.teamresourceful.resourcefulconfig.api.types.options.Option
import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.*
import io.wispforest.owo.ui.container.*
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.commands.SkyMyceCommands
import me.mycellium.skymyce.config.instances.dungeons.PartyFinder
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.*
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.hud.*
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import kotlin.math.roundToInt
import kotlin.time.Duration

enum class SettingsTab(val title: String) {
    GENERAL("General"), PARTY("Party Finder"), COMBAT("Dungeons & Combat"), SOCIAL("Social & Sharing"), THEME("Theme & Appearance")
}

/** Compatibility labels for old contextual callers, derived from the canonical menu inventory. */
fun settingsTab(path: String): SettingsTab = MenuCatalog.settingLocation(path).let { location -> when {
    location.featureId == "party-finder" -> SettingsTab.PARTY
    location.featureId == "daily-digest" -> SettingsTab.SOCIAL
    location.subcategory == "Appearance" && location.category == MenuCategory.SETTINGS -> SettingsTab.THEME
    location.category == MenuCategory.DUNGEONS || location.category == MenuCategory.COMBAT -> SettingsTab.COMBAT
    else -> SettingsTab.GENERAL
} }

private fun legacySettingsFeature(tab: SettingsTab) = when (tab) {
    SettingsTab.GENERAL -> "settings-general"
    SettingsTab.PARTY -> "party-finder"
    SettingsTab.COMBAT -> "dungeon-chests"
    SettingsTab.SOCIAL -> "daily-digest"
    SettingsTab.THEME -> "settings-appearance"
}

data class SettingRow(val path: String, val element: ResourcefulConfigEntryElement) {
    val entry get() = element.entry() as ResourcefulConfigValueEntry
    val title get() = if (path.startsWith("Theme & Appearance/"))
        MenuCatalog.themeColorTitles[element.id()] ?: MenuCatalog.themeFlagTitles[element.id()] ?: MenuCatalog.themeNumberTitles[element.id()]
            ?: entry.options().title().toLocalizedString().ifBlank { element.id() }
        else entry.options().title().toLocalizedString().ifBlank { element.id() }
    val tip get() = entry.options().comment().toLocalizedString().ifBlank {
        when {
            entry.options().hasOption(Option.KEYBIND) -> "Choose the keyboard key for $title. Escape cancels; Backspace clears it."
            element.id() == "enabled" -> "Enable or disable ${path.substringBeforeLast('/').substringAfterLast('/')} features."
            else -> "Configure ${title.lowercase()} for ${path.substringBeforeLast('/').ifEmpty { "Sky-Hawk" }}."
        }
    }
}

fun settingRows(config: ResourcefulConfig, prefix: String = ""): List<SettingRow> = buildList {
    config.elements().filterIsInstance<ResourcefulConfigEntryElement>().filter { !it.isHidden && it.entry() is ResourcefulConfigValueEntry }
        .forEach { add(SettingRow(prefix + it.id(), it)) }
    config.categories().forEach { (id, child) -> addAll(settingRows(child, "$prefix$id/")) }
}

private val friendPreferenceSearch = mapOf(
    "Availability" to "Available floor classes join PB limit personal best",
    "Dungeon Friends" to "LFG settings message template title notifications reconnect secondary classes",
)

fun settingGroups(rows: List<SettingRow>, tab: SettingsTab, query: String = "") = settingGroups(rows, legacySettingsFeature(tab), query)

/** The shared catalog assigns the UI owner; paths still identify the persisted entries. */
fun settingGroups(rows: List<SettingRow>, featureId: String, query: String = ""): Map<String, List<SettingRow>> {
    val settings = MenuCatalog(rows).findFeature(featureId)?.settings.orEmpty()
    val groups = if (featureId == "starred-routes") MenuCatalog.routingSettingGroups.mapValues { (_, keys) ->
        keys.mapNotNull { key -> settings.firstOrNull { it.element.id() == key } }
    }.filterValues { it.isNotEmpty() }.toMutableMap()
    else if (featureId == "healer-crush-camera") settings.groupBy {
        if (it.element.id() in setOf("enabled", "healer", "mage", "berserk", "archer", "tank")) "Automatic activation" else "Appearance"
    }.toMutableMap()
    else settings.groupBy { it.path.substringBeforeLast('/', "Basics").substringAfterLast('/') }.toMutableMap()
    if (featureId == "party-finder") groups["Availability"] = emptyList()
    return groups.filter { (group, settings) -> query.isBlank() || group.contains(query, true) ||
        settings.any { "${it.title} ${it.tip}".contains(query, true) } || friendPreferenceSearch[group]?.contains(query, true) == true }
}

internal fun settingsFocusTarget(component: UIComponent): UIComponent? = when {
    component.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) -> component
    component is ParentUIComponent -> component.children().firstNotNullOfOrNull(::settingsFocusTarget)
    else -> null
}

/** Only a direct setting/friend link requests keyboard focus; opening a feature does not. */
internal fun settingsFocusPath(settingPath: String?, dungeonFriend: String?): String? =
    settingPath ?: dungeonFriend?.let { "friend/$it" }

internal fun settingsScroll(body: FlowLayout): MenuScroll<FlowLayout> {
    body.padding(Insets.of(18, 26, 18, 22))
    return menuDirectoryScroll(body).apply {
        scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2); scrollStep(32)
    }
}

/** Header and cards share an inset while the stationary viewport includes their motion gutters. */
internal fun settingsPage(search: UIComponent, heading: UIComponent, scroll: UIComponent, footer: UIComponent): FlowLayout =
    UIContainers.verticalFlow(Sizing.fill(), Sizing.expand()).apply {
        padding(Insets.of(14, 14, 18, 18)); gap(8)
        child(search); child(heading); child(scroll); child(footer)
    }

internal fun settingNumber(entry: ResourcefulConfigValueEntry, value: String): Number? {
    val number = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val range = entry.options().getOption(Option.RANGE)
    if (range != null && number !in range.min..range.max) return null
    return if (entry.type() == EntryType.INTEGER) number.takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt() else number
}

fun parseThemeHex(value: String): Int? = value.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toInt(16)
/** Both original editors and canonical quick controls commit through the same feature hooks. */
fun notifySettingsChanged() {
    PartyFinder.invalidateMatcher()
    DailyDigest.settingsChanged()
    me.mycellium.skymyce.community.CommunitySharing.settingsChanged()
}
internal fun settingsRow(): FlowLayout = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
    gap(5); verticalAlignment(VerticalAlignment.CENTER)
}

internal fun settingsSectionHeader(title: UIComponent, action: UIComponent?, availableWidth: Int): FlowLayout {
    val compact = availableWidth < 240
    return (if (compact) UIContainers.verticalFlow(Sizing.fill(), Sizing.content()) else settingsRow()).apply {
        gap(8); padding(Insets.of(10)); surface(MenuTheme.panel(true))
        child(title.horizontalSizing(if (compact || action == null) Sizing.fill() else Sizing.expand()))
        action?.let { child(it) }
    }
}
/** Only the theme editor's public overrides are exposed from hidden config entries. */
internal fun commandCenterRows(config: ResourcefulConfig): List<SettingRow> = settingRows(config) +
    config.categories()["Theme & Appearance"]?.elements().orEmpty()
        .filterIsInstance<ResourcefulConfigEntryElement>()
        .filter { it.isHidden && it.id() in MenuCatalog.themeColorTitles.keys + MenuCatalog.themeFlagTitles.keys + MenuCatalog.themeNumberTitles.keys }
        .map { SettingRow("Theme & Appearance/${it.id()}", it) }

internal fun commandCenterSettings(catalog: MenuCatalog, rows: List<SettingRow>, category: MenuCategory, query: String, featureId: String? = null): List<SettingRow> {
    if (query.isBlank()) return rows.filter {
        val owner = catalog.featureForPath(it.path)
        if (featureId != null) owner?.id == featureId else MenuCatalog.settingLocation(it.path).category == category
    }
    val matches = catalog.search(query).mapNotNull { it.settingPath }.toSet()
    return rows.filter { it.path in matches }
}

class SettingsScreen @JvmOverloads constructor(
    private val parent: Screen? = null,
    private var tab: SettingsTab = SettingsTab.GENERAL,
    dungeonFriend: String? = null,
    featureId: String? = null,
    settingPath: String? = null,
    private val onPinSetting: (String) -> Unit = { MenuShortcuts.togglePin(it) },
) : MenuScreen(Component.literal("Sky-Myce Settings")) {
    private var featureScoped = featureId != null || settingPath != null || dungeonFriend != null
    private val rows by lazy { settingRows(SkyMyce.config) }
    private val commandRows by lazy { commandCenterRows(SkyMyce.config) }
    private val catalog by lazy { MenuCatalog(rows) }
    private var featureId = featureId ?: legacySettingsFeature(tab)
    internal override val canonicalFeatureId get() = featureId
    private var pendingSetting = settingsFocusPath(settingPath, dungeonFriend)
    private val controls = mutableMapOf<String, UIComponent>()
    private var renderedValues = emptyList<Any?>()
    private var renderedPins = -1L
    private var keyCapture: Pair<SettingRow, ButtonComponent>? = null
    private var changed = false
    private var query = ""
    private lateinit var body: FlowLayout
    private lateinit var heading: LabelComponent
    private lateinit var summary: LabelComponent
    private lateinit var contentScroll: MenuScroll<FlowLayout>
    private lateinit var search: MenuTextBox
    private var category = MenuCatalog.definitions.first { it.id == this.featureId }.category
    public override val menuParent get() = parent ?: super.menuParent
    override val menuCategory get() = category
    override val settingsCategory get() = tab
    public override fun selectSettings(tab: SettingsTab) {
        val destination = legacySettingsFeature(tab)
        if (destination == featureId) return
        val direction = if (tab.ordinal < this.tab.ordinal) -1 else 1
        this.tab = tab; featureId = destination; featureScoped = true; category = catalog.findFeature(destination)!!.category; query = ""; editingDungeonFriend = null; friendDraft = null; availabilityDraft = null; keyCapture = null
        pendingSetting = null
        rebuildMenu(direction)
    }
    private var editingDungeonFriend = dungeonFriend
    private var friendDraft: FriendPreferenceDraft? = null
    private var availabilityDraft: FriendPreferenceDraft? = null
    private var routingAdvanced = false
    private class FriendPreferenceDraft(val group: String?, val friend: String?) {
        var floor = DungeonFriendsSettings.availability.floor
        var classes = if (group == "Availability") DungeonFriendsSettings.availability.classes
            else friend?.let { DungeonFriendsSettings.secondaryClasses[it.lowercase()] }.orEmpty()
        var pb = DungeonFriendsSettings.availability.maxPbMillis?.let(::formatDungeonTime).orEmpty()
        var template = DungeonFriendsSettings.messageTemplate
        var titles = DungeonFriendsSettings.titleNotifications
    }
    private val detailWidth get() = menuDirectoryContentWidth(menuWidth)
    private val controlWidth get() = (detailWidth - 20).coerceAtLeast(1)

    override fun init() {
        val previousBody = if (::body.isInitialized) body else null
        val scrollOffset = if (::contentScroll.isInitialized) contentScroll.scrollTarget else 0.0
        keyCapture = null
        SkyMyceCommands.syncSettingsKey()
        super.init()
        // Native screen reuse keeps old captions; refresh from the canonical entries on return.
        if (previousBody != null && !invalid && (renderedValues != currentValues() || renderedPins != MenuShortcuts.revision)) rebuildMenu()
        if (previousBody != null && ::contentScroll.isInitialized) contentScroll.scrollBy(scrollOffset, true, false)
    }
    override fun buildContent(root: FlowLayout) {
        renderedValues = currentValues(); renderedPins = MenuShortcuts.revision
        body = column()
        search = MenuTextBox(Sizing.fill()).apply {
            setMaxLength(80); text(query); setHint(Component.literal("Search all settings…"))
            tip("Search names, descriptions and aliases across every category. Ctrl+F focuses search.")
            onChanged().subscribe { query = it; keyCapture = null; closeMenuDropdown(); populate(animate = false) }
        }
        heading = label(category.title, MenuTheme.TEXT, 17f)
        summary = label("", MenuTheme.MUTED, 9f)
        contentScroll = settingsScroll(body)
        root.child(settingsPage(search, column().apply { gap(4); child(heading); child(summary) }, contentScroll, settingsRow().apply {
            child(label("Changes save on close • Friend preferences use Save", MenuTheme.MUTED, 8f).horizontalSizing(Sizing.expand()))
            child(button("Done", 42, "Save settings and return") { onClose() })
        }))
        populate(animate = false)
    }

    private fun populate(animate: Boolean = true) {
        if (animate) contentChanged()
        controls.clear(); body.clearChildren()
        heading.text(Component.literal(if (query.isBlank()) {
            if (featureScoped) catalog.findFeature(featureId)?.breadcrumb ?: category.title else category.title
        } else "Search results"))
        val matches = commandCenterSettings(catalog, commandRows, category, query, featureId.takeIf { featureScoped })
        val results = if (query.isBlank()) MenuCatalog.extraSettings.filter {
            if (featureScoped) it.featureId == featureId else it.category == category
        } else catalog.search(query)
        val extraPaths = results.mapNotNull { it.settingPath }.toSet()
        val count = (matches.map { it.path } + extraPaths).distinct().size
        summary.text(Component.literal(if (query.isBlank()) {
            "${count} settings • ${if (featureScoped) "Pin frequent controls to Quick Settings" else category.description}"
        } else "${count} matching controls across all categories"))
        catalog.features.forEach { owner ->
            val settings = matches.filter { MenuCatalog.settingLocation(it.path).let { location ->
                location.featureId == owner.id || location.featureId == null && owner.category == MenuCategory.SETTINGS && location.subcategory == owner.subcategory
            } }
            val availability = owner.id == "party-finder" && "Dungeons/Dungeon Friends/availability" in extraPaths
            val preferences = owner.id == "party-finder" && extraPaths.any { it in setOf("Dungeons/Dungeon Friends/messageTemplate", "Dungeons/Dungeon Friends/titleNotifications") }
            val hud = owner.id == "settings-hud" && (query.isBlank() && category == MenuCategory.SETTINGS && (!featureScoped || featureId == owner.id) || query.isNotBlank() && results.any { it.featureId == owner.id })
            if (settings.isEmpty() && !availability && !preferences && !hud) return@forEach
            val reset = if (settings.isEmpty()) null else button("Reset section", 88, "Restore defaults for these displayed settings") {
                settings.forEach { it.entry.reset() }
                if (settings.any { it.path == "openConfigKey" }) SkyMyceCommands.updateSettingsKey()
                keyCapture = null; edited(); populate()
            }
            body.child(settingsSectionHeader(column().apply {
                gap(4)
                child(label(owner.title, MenuTheme.TEXT, 12f))
                child(label(if (query.isBlank()) owner.description else owner.breadcrumb, MenuTheme.MUTED, 9f))
            }, reset, detailWidth))
            if (owner.id == "settings-appearance") body.child(button("Browse themes & customize", minOf(210, controlWidth), "Choose a theme, preview its controls, or customize and transfer its palette") {
                navigateMenu(ThemeEditorScreen(this))
            }.apply { intent = MenuButtonIntent.PRIMARY })
            if (owner.id == "starred-routes") {
                settingGroups(settings, owner.id).forEach { (group, entries) ->
                    if (group == "Advanced") {
                        if (entries.any { it.path == pendingSetting }) routingAdvanced = true
                        body.child(MenuCollapsible(Sizing.fill(), Sizing.content(), Component.literal(group),
                            routingAdvanced || query.isNotBlank()).apply {
                            onToggled().subscribe { routingAdvanced = it }
                            entries.forEach { child(editor(it)) }
                        })
                    } else {
                        body.child(label(group, MenuTheme.MUTED, 10f))
                        entries.forEach { body.child(editor(it)) }
                    }
                }
            } else settings.forEach { setting -> body.child(editor(setting)) }
            if (availability && editingDungeonFriend == null) body.child(friendPreferences(true))
            if (preferences || owner.id == "party-finder" && editingDungeonFriend != null) body.child(friendPreferences(false).also {
                editingDungeonFriend?.let { name -> controls["friend/$name"] = it }
            })
            if (hud) body.child(button("Open HUD editor", minOf(190, controlWidth), "Arrange widget positions, scale and appearance") { navigateMenu(WidgetEditorScreen(this)) })
        }
        if (body.children().isEmpty()) body.child(column().apply {
            gap(7); padding(Insets.of(14)); surface(MenuTheme.panel(true))
            child(label("No matching settings", MenuTheme.TEXT, 12f))
            child(label("Try a feature name, setting name or a shorter search. Search includes every category.", MenuTheme.MUTED, 9f))
            child(button("Clear search", minOf(100, controlWidth), "Show this category's settings") { search.text("") })
        })
        contentScroll.scrollTo(0.0)
    }

    private fun friendPreferences(availabilityOnly: Boolean): FlowLayout = column().apply {
        padding(Insets.vertical(10)); gap(8)
        DungeonFriendsSettings.load()
        val friend = editingDungeonFriend
        val friendStats = friend?.let(DungeonFriendStatsCache::get)
        val draft = (if (availabilityOnly) availabilityDraft else friendDraft)?.takeIf { it.group == (if (availabilityOnly) "Availability" else "Dungeon Friends") && it.friend == friend }
            ?: FriendPreferenceDraft(if (availabilityOnly) "Availability" else "Dungeon Friends", friend).also { if (availabilityOnly) availabilityDraft = it else friendDraft = it }
        if (availabilityOnly) {
            child(label("Choose the floor and classes you offer when joining a party.", MenuTheme.MUTED))
            child(button("Floor: ${draft.floor.name}", detailWidth, "Your available dungeon floor") { anchor ->
                menu(anchor) { dropdown -> FRIEND_FLOORS.forEach { option ->
                    dropdown.button(MenuTheme.text(Component.literal(option.name))) {
                        draft.floor = option; anchor.message = Component.literal("Floor: ${draft.floor.name}"); it.parent()?.removeChild(it)
                    }
                } }
            }.also { controls["Dungeons/Dungeon Friends/availability"] = it })
        }
        if (availabilityOnly || friend != null) {
            if (friend != null) child(label("Secondary classes: $friend", MenuTheme.ACCENT))
            DungeonClass.entries.chunked(2).forEach { options -> child(settingsRow().apply {
                options.forEach { clazz -> child(MenuButton(Component.literal(clazz.displayName)) {
                    draft.classes = if (clazz in draft.classes) draft.classes - clazz else draft.classes + clazz
                }.apply {
                    selected = { clazz in draft.classes }; sizing(Sizing.fixed(((detailWidth - 5) / 2).coerceAtLeast(1)), Sizing.fixed(24))
                    tip(if (availabilityOnly) "Offer ${clazz.displayName} when joining" else
                        "Include ${clazz.displayName} in $friend's class matching. Level: ${friendStats?.classes?.get(clazz) ?: "Unknown"}")
                }) }
            }) }
        }
        if (availabilityOnly) {
            child(label("Join PB limit"))
            child(MenuTextBox(Sizing.fill()).apply {
                setMaxLength(10); text(draft.pb); setHint(Component.literal("No limit (m:ss)"))
                tip("Your requirement for players using Join. Leave blank for no limit; invitations bypass it.")
                onChanged().subscribe { draft.pb = it }
            })
            child(label("Clear every class to stop offering availability.", MenuTheme.MUTED, 9f))
        } else if (friend == null) {
            child(settingsRow().apply {
                child(label("Title notifications").horizontalSizing(Sizing.expand()))
                child(MenuButton(Component.literal("Titles")) { draft.titles = !draft.titles }.apply {
                    toggled = { draft.titles }; sizing(Sizing.fixed(64), Sizing.fixed(22))
                    tip("Show invitations and joining players as titles")
                }.also { controls["Dungeons/Dungeon Friends/titleNotifications"] = it })
            })
            child(label("LFG message template"))
            child(label("Use {name}, {class} and {floor}.", MenuTheme.MUTED, 9f))
            child(MenuTextBox(Sizing.fill()).apply {
                setMaxLength(220); text(draft.template); onChanged().subscribe { draft.template = it }
                tip("Message sent by the Message action")
            }.also { controls["Dungeons/Dungeon Friends/messageTemplate"] = it })
            child(button("Reconnect", minOf(88, controlWidth), "Reconnect the friend service: ${DungeonFriendRelay.status}") { DungeonFriendRelay.reconnect() })
        }
        val feedback = label(DungeonFriendsSettings.error.orEmpty(), MenuTheme.MUTED, 9f)
        child(feedback)
        child((if (controlWidth >= 121) settingsRow() else column()).apply {
            child(button("Save", 58, "Apply these friend preferences") {
                feedback.color(Color.ofRgb(MenuTheme.RED))
                val limit = parsePbLimit(draft.pb)
                if (availabilityOnly && draft.pb.isNotBlank() && limit == null) {
                    feedback.text(Component.literal("Use m:ss (for example 5:30), or leave the limit blank.")); return@button
                }
                val available = if (availabilityOnly) DungeonAvailability(draft.floor, draft.classes, limit) else DungeonFriendsSettings.availability
                val secondary = DungeonFriendsSettings.secondaryClasses.toMutableMap()
                friend?.lowercase()?.let { name -> if (draft.classes.isEmpty()) secondary.remove(name) else secondary[name] = draft.classes }
                val availabilityChanged = available != DungeonFriendsSettings.availability
                if (DungeonFriendsSettings.save(
                    template = if (!availabilityOnly && friend == null) draft.template else DungeonFriendsSettings.messageTemplate,
                    classes = secondary, available = available,
                    titles = if (!availabilityOnly && friend == null) draft.titles else DungeonFriendsSettings.titleNotifications,
                )) {
                    if (availabilityChanged) DungeonFriends.joining.clear()
                    feedback.text(Component.literal("Saved")); feedback.color(Color.ofRgb(MenuTheme.ACCENT))
                    showMenuToast("Friend preferences saved", MenuToastKind.SUCCESS)
                } else {
                    feedback.text(Component.literal(DungeonFriendsSettings.error ?: "Use a nonempty message of up to 220 characters."))
                    showMenuToast("Friend preferences could not be saved", MenuToastKind.ERROR)
                }
            })
            child(button("Cancel", 58, "Discard unapplied preferences and close") { closeDetails() })
        })
    }

    private fun closeDetails(animate: Boolean = true) {
        friendDraft = null; availabilityDraft = null; editingDungeonFriend = null; keyCapture = null
        populate(animate)
    }

    private fun toggle(setting: SettingRow) = MenuButton(Component.literal(if (setting.entry.boolean) "On" else "Off")) { b ->
        if (setting.entry.setBoolean(!setting.entry.boolean)) edited()
        b.message = Component.literal(if (setting.entry.boolean) "On" else "Off")
    }.apply {
        toggled = { setting.entry.boolean }; sizing(Sizing.fixed(64), Sizing.fixed(22)); tip(setting.tip)
    }

    private fun editor(setting: SettingRow): FlowLayout = MenuRevealCard("settings/${setting.path}", Sizing.fill(), Sizing.content()).apply {
        gap(7); padding(Insets.of(10)); surface(MenuTheme.panel(true)); tip(setting.tip)
        val entry = setting.entry
        val themeKey = setting.element.id().takeIf { setting.path.startsWith("Theme & Appearance/") }
        if (themeKey in MenuCatalog.themeColorTitles) {
            child(label(setting.title, size = 11f))
            child(label(setting.tip, MenuTheme.MUTED, 9f))
            val feedback = label("", MenuTheme.RED, 8f)
            child(settingsRow().apply {
                val hex = MenuTextBox(Sizing.fixed(minOf(100, (controlWidth - 63).coerceAtLeast(1)))).apply {
                    setMaxLength(7); text(if (entry.int < 0) "" else "#%06X".format(entry.int)); setHint(Component.literal("Inherited")); tip("Six RGB hex digits. Inherit restores the preset color.")
                    onChanged().subscribe { value ->
                        val color = parseThemeHex(value)
                        if (color != null && entry.setInt(color)) { edited(); feedback.text(Component.empty()) }
                        else feedback.text(Component.literal("Use six RGB hex digits"))
                    }
                }
                child(hex)
                child(button("Inherit", 58, "Use the preset color") { entry.setInt(-1); edited(); hex.text(""); feedback.text(Component.empty()) })
            })
            child(feedback)
            if (canPinMenuSetting(setting)) child(pinControl(setting))
            return@apply
        }
        if (themeKey in MenuCatalog.themeFlagTitles) {
            child(settingsRow().apply {
                child(label(setting.title).horizontalSizing(Sizing.expand()))
                fun caption() = when (entry.int) { 0 -> "Off"; 1 -> "On"; else -> "Inherited" }
                child(button(caption(), 80, "Cycle inherited, enabled and disabled") { anchor ->
                    if (entry.setInt(when (entry.int) { -1 -> 1; 1 -> 0; else -> -1 })) { edited() }
                    anchor.message = Component.literal(caption())
                })
            })
            child(label(setting.tip, MenuTheme.MUTED, 9f))
            return@apply
        }
        if (themeKey in MenuCatalog.themeNumberTitles) {
            val min = if (themeKey == "opacity") 20 else 2
            val max = if (themeKey == "opacity") 100 else 12
            child(label(setting.title, size = 11f))
            child(label(setting.tip, MenuTheme.MUTED, 9f))
            val valueLabel = label(if (entry.int < 0) "Inherited" else entry.int.toString()); child(valueLabel)
            val resolved = resolveTheme(ThemeConfig.preset, ThemeConfig.overrides())
            child(MenuSlider().apply {
                value(((if (themeKey == "opacity") resolved.opacity else resolved.padding) - min).toDouble() / (max - min)); scrollStep(1.0 / (max - min)); tip(setting.tip)
                onChanged().subscribe { amount ->
                    if (entry.setInt((min + amount * (max - min)).roundToInt())) { edited(); valueLabel.text(Component.literal(entry.int.toString())) }
                }
            })
            child(button("Inherit", 58, "Restore preset ${setting.title.lowercase()}") { entry.setInt(-1); edited(); populate() })
            if (canPinMenuSetting(setting)) child(pinControl(setting))
            return@apply
        }
        if (entry.type() == EntryType.BOOLEAN) {
            child(settingsRow().apply {
                child(label(setting.title).horizontalSizing(Sizing.expand()))
                child(toggle(setting))
            })
            child(label(setting.tip, MenuTheme.MUTED, 9f))
            if (setting.path == "Dungeons/Starred Clear Routing/routeThroughMobs") {
                child(label("Off: route directly toward Blood.", MenuTheme.MUTED, 8f))
                child(label("Mob markers are separate.", MenuTheme.MUTED, 8f))
            }
            child(pinControl(setting))
            return@apply
        }
        child(label(setting.title, size = 11f))
        child(label(setting.tip, MenuTheme.MUTED, 9f))
        when {
            entry.isArray -> child(button("Choose options (${entry.array.size} selected)", controlWidth, setting.tip) { anchor ->
                menu(anchor) { dropdown -> entry.objectType().enumConstants?.forEach { option ->
                    dropdown.checkbox(MenuTheme.text(Component.literal(option.toString())), option in entry.array) { selected ->
                        val values = entry.array.toMutableList()
                        if (selected && option !in values) values.add(option) else if (!selected) values.remove(option)
                        if (entry.setArray(values.toTypedArray())) edited()
                        anchor.message = Component.literal("Choose options (${entry.array.size} selected)")
                    }
                } }
            })
            entry.options().hasOption(Option.KEYBIND) -> child(button(keyName(entry.int), minOf(130, controlWidth), setting.tip) { b ->
                keyCapture?.let { it.second.message = Component.literal(keyName(it.first.entry.int)) }
                keyCapture = setting to b; b.message = Component.literal("Press a key…")
            })
            entry.type() == EntryType.ENUM -> child(button(entry.enum.toString(), minOf(150, controlWidth), setting.tip) { b ->
                val values = if (setting.path == "Theme & Appearance/animationMode") GuiMotion.choices
                    else entry.objectType().enumConstants.filterIsInstance<Enum<*>>()
                menu(b) { dropdown -> values.forEach { option ->
                    dropdown.button(MenuTheme.text(Component.literal(option.toString()))) {
                        if (entry.setEnum(option)) edited()
                        b.message = Component.literal(entry.enum.toString()); it.parent()?.removeChild(it)
                    }
                } }
            })
            entry.type() == EntryType.STRING -> {
                val secret = setting.element.id().contains("key", true)
                child(MenuTextBox(Sizing.fill()).apply {
                    setMaxLength(256); text(if (secret) "" else entry.string)
                    if (secret) setHint(Component.literal(if (entry.string.isBlank()) "No key configured" else "Key saved • enter replacement"))
                    tip(setting.tip)
                    onChanged().subscribe { if ((!secret || it.isNotBlank()) && entry.setString(it)) edited() }
                })
                if (secret) child(button("Clear key", minOf(72, controlWidth), "Remove the saved optional API key") { if (entry.setString("")) { edited(); populate() } })
            }
            else -> {
                val range = entry.options().getOption(Option.RANGE)
                if (range != null && entry.options().hasOption(Option.SLIDER)) {
                    val number = (entry.get() as Number).toDouble()
                    val valueLabel = label(numberText(number))
                    child(valueLabel)
                    child(MenuSlider().apply {
                        value((number - range.min) / (range.max - range.min)); tip(setting.tip)
                        onChanged().subscribe { amount ->
                            val n = range.min + amount * (range.max - range.min)
                            val ok = if (entry.type() == EntryType.INTEGER) entry.setInt(n.roundToInt()) else entry.setDouble(n)
                            if (ok) { edited(); valueLabel.text(Component.literal(numberText((entry.get() as Number).toDouble()))) }
                        }
                    })
                } else {
                    val feedback = label("", MenuTheme.RED, 8f)
                    child(MenuTextBox(Sizing.fill()).apply {
                        setMaxLength(32); text(entry.get().toString()); tip(setting.tip)
                        onChanged().subscribe { value ->
                            val parsed = settingNumber(entry, value)
                            if (parsed == null) feedback.text(Component.literal(if (range == null) "Enter a valid number" else "Use a number from ${range.min} to ${range.max}"))
                            else {
                                val accepted = if (entry.type() == EntryType.INTEGER) entry.setInt(parsed.toInt()) else entry.setDouble(parsed.toDouble())
                                if (accepted) { edited(); feedback.text(Component.empty()) }
                            }
                        }
                    })
                    child(feedback)
                }
            }
        }
        if (canPinMenuSetting(setting)) child(pinControl(setting))
    }.also { controls[setting.path] = it }

    private fun pinControl(setting: SettingRow) = button(if (MenuShortcuts.isPinned(setting.path)) "Unpin" else "Pin", 58,
        "${catalog.featureForPath(setting.path)?.breadcrumb} → ${setting.title}. Add or remove this canonical control from Quick Settings.") { anchor ->
        val wasPinned = MenuShortcuts.isPinned(setting.path)
        onPinSetting(setting.path)
        val pinned = MenuShortcuts.isPinned(setting.path)
        anchor.message = Component.literal(if (pinned) "Unpin" else "Pin")
        if (!wasPinned && !pinned) showMenuToast("Quick Settings is full (8 controls)", MenuToastKind.INFO)
    }

    override fun tick() {
        super.tick()
        val path = pendingSetting ?: return
        val component = controls[path] ?: return
        if (component.parent() == null) return
        val handler = component.focusHandler() ?: return
        contentScroll.scrollTo(component)
        contentScroll.scrollBy(0.0, true, false)
        handler.focus(settingsFocusTarget(component), UIComponent.FocusSource.KEYBOARD_CYCLE)
        pendingSetting = null
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (menuClosing) return true
        if (event.isEscape && closeMenuDropdown()) return true
        keyCapture?.let { (setting, button) ->
            if (event.key != GLFW.GLFW_KEY_ESCAPE && setting.entry.setInt(if (event.key == GLFW.GLFW_KEY_BACKSPACE) GLFW.GLFW_KEY_UNKNOWN else event.key)) edited()
            if (setting.path == "openConfigKey") SkyMyceCommands.updateSettingsKey()
            button.message = Component.literal(keyName(setting.entry.int)); keyCapture = null; return true
        }
        if (event.key == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            search.focusHandler()?.focus(search, UIComponent.FocusSource.KEYBOARD_CYCLE); return true
        }
        return super.keyPressed(event)
    }
    override fun removed() {
        if (changed) {
            changed = false
            Scheduling.schedule(Duration.ZERO) {
                synchronized(SAVE_LOCK) {
                    runCatching {
                        SkyMyce.config.save()
                        // Persist the native Controls mapping alongside the existing config key.
                        MC.instance.options.save()
                    }.onFailure { SkyMyce.logger.warn("Could not save Sky-Hawk settings") }
                }
            }
            notifySettingsChanged()
        }
        super.removed()
    }
    override fun onClose() { keyCapture = null; closeMenuTo(menuParent) }
    private fun edited() {
        changed = true; PartyFinder.invalidateMatcher()
        heading.color(Color.ofRgb(MenuTheme.ACCENT))
        HudTheme.changed()
    }
    private fun keyName(key: Int) = if (key == GLFW.GLFW_KEY_UNKNOWN) "Unbound" else InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string
    private fun numberText(value: Double) = "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(6)
    private fun label(text: String, color: Int = MenuTheme.TEXT, size: Float = 10f) = MenuLabel(Component.literal(text)).apply {
        fontSize = size; color(Color.ofRgb(color)); shadow(MenuTheme.SHADOW); horizontalSizing(Sizing.fill())
    }
    private fun button(text: String, width: Int, tooltip: String, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).apply {
        themed(); sizing(Sizing.fixed(width), Sizing.fixed(20)); tip(tooltip)
    }
    private fun <T : UIComponent> T.tip(text: String): T = wrappedTooltip(Component.literal(text))
    private fun menu(anchor: ButtonComponent, options: (DropdownComponent) -> Unit) = openMenuDropdown(anchor, options)
    companion object {
        private val SAVE_LOCK = Any()
        fun forFeature(parent: Screen?, id: String) = SettingsScreen(parent, featureId = id)
        fun forSetting(parent: Screen?, path: String): SettingsScreen {
            val location = MenuCatalog.settingLocation(path)
            val id = location.featureId ?: MenuCatalog.definitions.first {
                it.category == MenuCategory.SETTINGS && it.subcategory == location.subcategory
            }.id
            return SettingsScreen(parent, featureId = id, settingPath = path)
        }
    }
    private fun currentValues() = commandRows.map { setting ->
        if (setting.entry.isArray) setting.entry.array.toList() else setting.entry.get()
    }
}
