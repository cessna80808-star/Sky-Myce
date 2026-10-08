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
    val title get() = entry.options().title().toLocalizedString().ifBlank { element.id() }
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
    val groups = MenuCatalog(rows).findFeature(featureId)?.settings.orEmpty()
        .groupBy { it.path.substringBeforeLast('/', "Basics").substringAfterLast('/') }.toMutableMap()
    if (featureId == "party-finder") groups["Availability"] = emptyList()
    return groups.filter { (group, settings) -> query.isBlank() || group.contains(query, true) ||
        settings.any { "${it.title} ${it.tip}".contains(query, true) } || friendPreferenceSearch[group]?.contains(query, true) == true }
}

internal fun settingsFocusTarget(component: UIComponent): UIComponent? = when {
    component.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) -> component
    component is ParentUIComponent -> component.children().firstNotNullOfOrNull(::settingsFocusTarget)
    else -> null
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
}
internal fun settingsRow(): FlowLayout = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
    gap(5); verticalAlignment(VerticalAlignment.CENTER)
}
internal fun settingsCardColumns(width: Int) = if (width >= 280) 2 else 1
internal fun settingsDrawerWidth(width: Int) = minOf((width / 3).coerceIn(190, 240), width.coerceAtLeast(1))
internal fun settingsCardRow(columns: Int): FlowLayout =
    object : FlowLayout(Sizing.fill(), Sizing.content(), FlowLayout.Algorithm.HORIZONTAL) {
        override fun calculateChildSpace(space: Size): Size {
            val available = super.calculateChildSpace(space)
            // owo rounds both 50% widths up at odd widths; reserve that pixel before expansion.
            val width = available.width().coerceAtLeast(0)
            return Size.of(width - width % columns, available.height())
        }
    }.apply { gap(8); verticalAlignment(VerticalAlignment.CENTER) }

class SettingsScreen @JvmOverloads constructor(
    private val parent: Screen? = null,
    private var tab: SettingsTab = SettingsTab.GENERAL,
    dungeonFriend: String? = null,
    featureId: String? = null,
    settingPath: String? = null,
    private val onPinSetting: (String) -> Unit = { MenuShortcuts.togglePin(it) },
) : MenuScreen(Component.literal("Sky-Myce Settings")) {
    private val rows by lazy { settingRows(SkyMyce.config) }
    private val catalog by lazy { MenuCatalog(rows) }
    private var featureId = featureId ?: legacySettingsFeature(tab)
    internal override val canonicalFeatureId get() = featureId
    private val feature get() = catalog.findFeature(featureId)
    private var pendingSetting = settingPath
    private val controls = mutableMapOf<String, UIComponent>()
    private var renderedValues = emptyList<Any?>()
    private var renderedPins = -1L
    private var keyCapture: Pair<SettingRow, ButtonComponent>? = null
    private var changed = false
    private var query = ""
    private lateinit var body: FlowLayout
    private lateinit var heading: LabelComponent
    private lateinit var drawer: FlowLayout
    private lateinit var drawerClip: FlowLayout
    private lateinit var drawerHeading: FlowLayout
    private lateinit var drawerScroll: MenuScroll<FlowLayout>
    public override val menuParent get() = parent.takeIf { it is MenuScreen || it is WidgetEditorScreen }
    override val menuCategory get() = feature?.category ?: MenuCategory.SETTINGS
    override val settingsCategory get() = tab
    public override fun selectSettings(tab: SettingsTab) {
        val destination = legacySettingsFeature(tab)
        if (destination == featureId) return
        val direction = if (tab.ordinal < this.tab.ordinal) -1 else 1
        this.tab = tab; featureId = destination; query = ""; selectedGroup = null; editingDungeonFriend = null; friendDraft = null; keyCapture = null
        rebuildMenu(direction)
    }
    private var selectedGroup: String? = dungeonFriend?.let { "Dungeon Friends" }
        ?: settingPath?.takeUnless { this.featureId == "settings-appearance" }?.let {
            if (it == "Dungeons/Dungeon Friends/availability") "Availability" else settingGroupName(it)
        }
    private var editingDungeonFriend = dungeonFriend
    private var friendDraft: FriendPreferenceDraft? = null
    private class FriendPreferenceDraft(val group: String?, val friend: String?) {
        var floor = DungeonFriendsSettings.availability.floor
        var classes = if (group == "Availability") DungeonFriendsSettings.availability.classes
            else friend?.let { DungeonFriendsSettings.secondaryClasses[it.lowercase()] }.orEmpty()
        var pb = DungeonFriendsSettings.availability.maxPbMillis?.let(::formatDungeonTime).orEmpty()
        var template = DungeonFriendsSettings.messageTemplate
        var titles = DungeonFriendsSettings.titleNotifications
    }
    private lateinit var details: FlowLayout
    private val detailWidth get() = settingsDrawerWidth(menuWidth)
    private val controlWidth get() = (detailWidth - 28).coerceAtLeast(1)

    override fun init() {
        val previousBody = if (::body.isInitialized) body else null
        val drawerOffset = if (::drawerScroll.isInitialized) drawerScroll.scrollTarget else 0.0
        keyCapture = null
        SkyMyceCommands.syncSettingsKey()
        super.init()
        // Native screen reuse keeps old captions; refresh from the canonical entries on return.
        if (previousBody != null && !invalid && (renderedValues != currentValues() || renderedPins != MenuShortcuts.revision)) rebuildMenu()
        if (previousBody != null && ::drawerScroll.isInitialized) drawerScroll.scrollBy(drawerOffset, true, false)
    }
    override fun buildContent(root: FlowLayout) {
        renderedValues = currentValues(); renderedPins = MenuShortcuts.revision
        val inlineSearch = menuWidth >= 560
        val search = MenuTextBox(if (inlineSearch) Sizing.fixed(150) else Sizing.fill()).apply {
            setMaxLength(80); text(query); setHint(Component.literal("Search settings..."))
            tip("Search feature names and setting descriptions in this category")
            onChanged().subscribe { query = it; keyCapture = null; populate(animate = false) }
        }
        body = column()
        val main = UIContainers.verticalFlow(Sizing.expand(), Sizing.fill()).apply {
            padding(Insets.of(14)); gap(12)
            child(settingsRow().apply {
                gap(10)
                child(column().apply {
                    horizontalSizing(Sizing.expand()); gap(5)
                    child(label(feature?.breadcrumb ?: "Settings", MenuTheme.MUTED, 8f))
                    heading = label(feature?.title ?: "Settings", MenuTheme.TEXT, 17f)
                    child(heading)
                })
                if (inlineSearch) child(search)
            })
            if (feature?.category == MenuCategory.SETTINGS) child(button("Settings: ${feature?.title}", minOf(220, menuWidth - 28), "Choose a global settings section") { anchor ->
                menu(anchor) { choices -> catalog.features.filter { it.category == MenuCategory.SETTINGS }.forEach { section ->
                    choices.button(Component.literal(section.title)) {
                        featureId = section.id; query = ""; selectedGroup = null; pendingSetting = null; keyCapture = null
                        rebuildMenu()
                    }
                } }
            })
            if (!inlineSearch) child(search)
            child(menuScroll(Sizing.fill(), Sizing.expand(), body).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
            })
            child(settingsRow().apply {
                child(label(if (featureId == "party-finder") "Friend preferences use Save" else "Changes save on close", MenuTheme.MUTED, 8f).horizontalSizing(Sizing.expand()))
                child(button("Done", 42, "Save settings and return") { onClose() })
            })
        }
        drawerHeading = column().apply { padding(Insets.of(14)) }
        details = column().apply { padding(Insets.horizontal(14)); gap(0) }
        drawerScroll = menuScroll(Sizing.fill(), Sizing.expand(), details).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
        }
        drawer = UIContainers.verticalFlow(Sizing.fixed(detailWidth), Sizing.fill()).apply {
            surface(Surface { g, c ->
                MenuTheme.rounded(g, c.x(), c.y(), c.width(), c.height(), MenuTheme.SIDEBAR, 0)
                g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.height(), MenuTheme.alpha(MenuTheme.BORDER))
            })
            child(drawerHeading); child(drawerScroll)
        }
        drawerClip = UIContainers.horizontalFlow(Sizing.fixed(if (selectedGroup == null) 0 else detailWidth), Sizing.fill())
        drawerClip.mouseDown().subscribe { _, _ -> true }
        drawerClip.mouseScroll().subscribe { _, _, _ -> true }
        // On small windows the drawer overlays the cards rather than squeezing their controls out of view.
        if (menuWidth < 440) drawerClip.positioning(Positioning.absolute(menuWidth - detailWidth, 0))
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.expand()).apply {
            child(main); child(drawerClip)
        })
        populate()
    }

    private fun populate(animate: Boolean = true) {
        if (animate) contentChanged()
        controls.clear()
        body.clearChildren()
        if (featureId == "settings-appearance") {
            closeDetails(false)
            feature?.settings?.firstOrNull { it.element.id() == "animationMode" }?.let { setting ->
                if (query.isBlank() || "${setting.title} ${setting.tip}".contains(query, true)) body.child(editor(setting))
            }
            body.child(button("Customize shared theme", minOf(190, menuWidth - 28), "Presets, colors, opacity, spacing, preview and JSON import/export") {
                navigateMenu(ThemeEditorScreen(this))
            }.apply { verticalSizing(Sizing.fixed(38)) })
            body.child(label("Theme colors and animation apply throughout Sky-Myce. Reduced and Off simplify motion. Rendering quality lives in Performance.", MenuTheme.MUTED))
            return
        }
        if (featureId == "settings-hud") {
            closeDetails(false)
            body.child(label("Drag widgets to arrange them, scroll to scale, and right-click for their theme.", MenuTheme.MUTED))
            body.child(button("Open HUD editor", minOf(190, menuWidth - 28), "Edit widget layout, scale and appearance") { navigateMenu(WidgetEditorScreen(this)) })
            return
        }
        val groups = settingGroups(rows, featureId, query)
        if (selectedGroup !in groups) closeDetails(false)
        if (groups.isEmpty()) { body.child(label("No matching settings", MenuTheme.MUTED)); return }
        val columns = settingsCardColumns(menuWidth)
        groups.entries.chunked(columns).forEach { line -> body.child(settingsCardRow(columns).apply {
            line.forEach { (group, settings) ->
                val title = MenuButton(Component.literal(group)) {
                    selectedGroup = group; editingDungeonFriend = null; keyCapture = null; populateDetails(groups); openDetails()
                }.apply {
                    sizing(Sizing.expand(), Sizing.fill()); navigation = true; background = false
                    tip(if (group in friendPreferenceSearch) "Open $group preferences" else "Open all ${settings.size} $group settings")
                }
                val quickToggle = settings.firstOrNull { it.entry.type() == EntryType.BOOLEAN && it.element.id() in setOf("enabled", "automatic", "dungeonTracker") }
                    ?.let { toggle(it).apply { background = false } }
                val hoverMotion = MotionValue(0.0)
                val selectionMotion = MotionValue(if (selectedGroup == group) 1.0 else 0.0)
                child(MenuRevealCard("settings/$featureId/$group", Sizing.expand(100 / columns), Sizing.fixed(43), FlowLayout.Algorithm.HORIZONTAL).apply {
                    padding(Insets.of(4)); gap(2); verticalAlignment(VerticalAlignment.CENTER)
                    surface(Surface { g, c ->
                        val focused = title.isFocused || quickToggle?.isFocused == true
                        val hover = title.isHovered || quickToggle?.isHovered == true
                        hoverMotion.animateTo(if (hover) 1.0 else 0.0, 110, ThemeConfig.animationMode.profile.hoverEasing, ThemeConfig.animationMode)
                        selectionMotion.animateTo(if (selectedGroup == group || focused) 1.0 else 0.0, 150, MotionEasing.QUICK, ThemeConfig.animationMode)
                        val hovered = hoverMotion.sample().coerceIn(0.0, 1.0)
                        val selection = selectionMotion.sample().coerceIn(0.0, 1.0)
                        val border = MenuTheme.blend(MenuTheme.blend(MenuTheme.BORDER, MenuTheme.TEXT, (hovered * 18).toInt()), MenuTheme.ACCENT, (selection * 100).toInt())
                        MenuTheme.shadow(g, c.x(), c.y(), c.width(), c.height(), (.3 + hovered * .65) * ThemeConfig.animationMode.profile.shadowStrength)
                        MenuTheme.glow(g, c.x(), c.y(), c.width(), c.height(), (selection * .35 + hovered * .3) * ThemeConfig.animationMode.profile.glowStrength)
                        MenuTheme.box(g, c.x(), c.y(), c.width(), c.height(), MenuTheme.CARD, border)
                        MenuTheme.sheen(g, c.x(), c.y(), c.width(), c.height(), .45 + hovered * .2)
                        if (focused && !MenuTheme.BORDERS) g.drawRectOutline(c.x(), c.y(), c.width(), c.height(), MenuTheme.alpha(border))
                    })
                    child(title)
                    quickToggle?.let { child(it) }
                })
            }
            repeat(columns - line.size) { child(UIContainers.verticalFlow(Sizing.expand(100 / columns), Sizing.fixed(43))) }
        }) }
        if (selectedGroup != null && animate) { populateDetails(groups, animate = false); openDetails(false) }
    }

    private fun populateDetails(groups: Map<String, List<SettingRow>>, animate: Boolean = true) {
        if (animate) contentChanged()
        details.clearChildren()
        drawerHeading.clearChildren()
        drawerHeading.child(label(if (feature?.category == MenuCategory.SETTINGS) "GLOBAL SETTINGS" else "FEATURE SETTINGS", MenuTheme.MUTED, 8f))
        drawerHeading.child(settingsRow().apply {
            child(label(selectedGroup.orEmpty(), MenuTheme.TEXT, 12f).horizontalSizing(Sizing.expand()))
            child(button("Close", 42, "Close feature settings") { closeDetails() })
        })
        val settings = groups[selectedGroup].orEmpty()
        drawerHeading.child(label(if (selectedGroup in friendPreferenceSearch) "Save preferences below" else "${settings.size} options", MenuTheme.MUTED, 9f))
        if (settings.isNotEmpty() && editingDungeonFriend == null) drawerHeading.child(button(
            if (selectedGroup == "Dungeon Friends") "Reset scan setting" else "Reset feature",
            minOf(if (selectedGroup == "Dungeon Friends") 110 else 88, controlWidth),
            "Restore the default values for these scan or feature controls",
        ) {
            settings.forEach { it.entry.reset() }
            if (settings.any { it.path == "openConfigKey" }) SkyMyceCommands.updateSettingsKey()
            keyCapture = null; edited(); populateDetails(groups)
        })
        if (editingDungeonFriend == null) settings.forEach { setting ->
            details.child(editor(setting))
            details.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(1)).surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER))))
        }
        if (selectedGroup in friendPreferenceSearch) details.child(friendPreferences(selectedGroup == "Availability"))
        drawerScroll.scrollTo(0.0)
    }

    private fun friendPreferences(availabilityOnly: Boolean): FlowLayout = column().apply {
        padding(Insets.vertical(10)); gap(8)
        DungeonFriendsSettings.load()
        val friend = editingDungeonFriend
        val friendStats = friend?.let(DungeonFriendStatsCache::get)
        val draft = friendDraft?.takeIf { it.group == selectedGroup && it.friend == friend }
            ?: FriendPreferenceDraft(selectedGroup, friend).also { friendDraft = it }
        if (availabilityOnly) {
            child(label("Choose the floor and classes you offer when joining a party.", MenuTheme.MUTED))
            child(button("Floor: ${draft.floor.name}", detailWidth - 28, "Your available dungeon floor") { anchor ->
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
                    selected = { clazz in draft.classes }; sizing(Sizing.fixed((detailWidth - 33) / 2), Sizing.fixed(24))
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
                    toggled = { draft.titles }; sizing(Sizing.fixed(32), Sizing.fixed(22))
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

    private fun openDetails(animate: Boolean = true) {
        if (drawerClip.children().isEmpty()) drawerClip.child(drawer)
        val duration = drawerDuration(animate)
        if (duration == 0) drawerClip.horizontalSizing(Sizing.fixed(detailWidth))
        else drawerClip.horizontalSizing().animate(duration, Easing.CUBIC, Sizing.fixed(detailWidth)).forwards()
    }

    private fun closeDetails(animate: Boolean = true) {
        if (animate) friendDraft = null
        selectedGroup = null; keyCapture = null
        if (!::drawerClip.isInitialized) return
        val duration = drawerDuration(animate)
        if (duration == 0) { drawerClip.horizontalSizing(Sizing.fixed(0)); drawerClip.clearChildren() }
        else {
            val closing = drawerClip
            val animation = closing.horizontalSizing().animate(duration, Easing.CUBIC, Sizing.fixed(0))
            animation.finished().subscribe { _, _ -> if (selectedGroup == null && drawerClip === closing) closing.clearChildren() }
            animation.forwards()
        }
    }
    private fun drawerDuration(animate: Boolean) = if (!animate || ThemeConfig.animationMode == GuiMotion.OFF) 0
        else (ThemeConfig.animationMode.profile.layoutMillis * ThemeConfig.animationMode.profile.durationScale).roundToInt()

    private fun toggle(setting: SettingRow) = MenuButton(Component.literal(if (setting.entry.boolean) "On" else "Off")) { b ->
        if (setting.entry.setBoolean(!setting.entry.boolean)) edited()
        b.message = Component.literal(if (setting.entry.boolean) "On" else "Off")
    }.apply {
        toggled = { setting.entry.boolean }; sizing(Sizing.fixed(32), Sizing.fixed(22)); tip(setting.tip)
    }

    private fun editor(setting: SettingRow): FlowLayout = column().apply {
        gap(8); padding(Insets.vertical(10)); tip(setting.tip)
        val entry = setting.entry
        if (entry.type() == EntryType.BOOLEAN) {
            child(settingsRow().apply {
                child(label(setting.title).horizontalSizing(Sizing.expand()))
                child(toggle(setting))
            })
            child(pinControl(setting))
            return@apply
        }
        child(label(setting.title))
        when {
            entry.isArray -> child(button("Choose options (${entry.array.size} selected)", detailWidth - 28, setting.tip) { anchor ->
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
        "${feature?.breadcrumb} → ${setting.title}. Add or remove this canonical control from Quick Settings.") { anchor ->
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
        if (featureId != "settings-appearance") {
            drawerScroll.scrollTo(component)
            drawerScroll.scrollBy(0.0, true, false)
        }
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
        if (event.key == GLFW.GLFW_KEY_ESCAPE && selectedGroup != null) { closeDetails(); return true }
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
    override fun onClose() { keyCapture = null; closeMenuTo(parent) }
    private fun edited() {
        changed = true; PartyFinder.invalidateMatcher()
        heading.color(Color.ofRgb(MenuTheme.ACCENT))
    }
    private fun rebuild() { if (uiAdapter != null) rebuildMenu() }
    private fun keyName(key: Int) = if (key == GLFW.GLFW_KEY_UNKNOWN) "Unbound" else InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string
    private fun numberText(value: Double) = "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(6)
    private fun label(text: String, color: Int = MenuTheme.TEXT, size: Float = 10f) = MenuLabel(Component.literal(text)).apply {
        fontSize = size; color(Color.ofRgb(color)); shadow(MenuTheme.SHADOW); horizontalSizing(Sizing.fill())
    }
    private fun button(text: String, width: Int, tooltip: String, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).themed().apply {
        sizing(Sizing.fixed(width), Sizing.fixed(20)); tip(tooltip)
    }
    private fun <T : UIComponent> T.tip(text: String): T = wrappedTooltip(Component.literal(text))
    private fun menu(anchor: ButtonComponent, options: (DropdownComponent) -> Unit) = openMenuDropdown(anchor, options)
    companion object {
        private val SAVE_LOCK = Any()
        private fun settingGroupName(path: String) = path.substringBeforeLast('/', "Basics").substringAfterLast('/')
        fun forFeature(parent: Screen?, id: String) = SettingsScreen(parent, featureId = id)
        fun forSetting(parent: Screen?, path: String): SettingsScreen {
            val location = MenuCatalog.settingLocation(path)
            val id = location.featureId ?: MenuCatalog.definitions.first {
                it.category == MenuCategory.SETTINGS && it.subcategory == location.subcategory
            }.id
            return SettingsScreen(parent, featureId = id, settingPath = path)
        }
    }
    private fun currentValues() = feature?.settings.orEmpty().map { setting ->
        if (setting.entry.isArray) setting.entry.array.toList() else setting.entry.get()
    }
}
