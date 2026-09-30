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
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.hud.*
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.math.roundToInt
import kotlin.time.Duration

enum class SettingsTab(val title: String) {
    GENERAL("General"), PARTY("Party Finder"), COMBAT("Dungeons & Combat"), SOCIAL("Social & Sharing"), THEME("Theme & Appearance")
}

/** Navigation only: the persisted Resourceful Config paths and existing consumers stay intact. */
fun settingsTab(path: String): SettingsTab = when {
    path.startsWith("Theme & Appearance/") -> SettingsTab.THEME
    path.startsWith("Party Finder/") -> SettingsTab.PARTY
    path.contains("/Daily Digest/") && path.substringAfterLast('/') in setOf("receiveRng", "shareRng", "publicRngName", "retainedRngEvents") -> SettingsTab.SOCIAL
    path.contains("Dungeon Friends/") || path.contains("Party Commands/") || path.startsWith("Cosmetics/") -> SettingsTab.SOCIAL
    path.startsWith("Dungeons/") || path.startsWith("Instances/") || path.contains("Loadout Keybinds/") ||
        path.contains("Wardrobe Keybinds/") || path.contains("Wardrobe/") || path.substringAfterLast('/').startsWith("displayAbility") -> SettingsTab.COMBAT
    else -> SettingsTab.GENERAL
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

fun settingGroups(rows: List<SettingRow>, tab: SettingsTab, query: String = ""): Map<String, List<SettingRow>> = rows
    .filter { settingsTab(it.path) == tab }
    .groupBy { it.path.substringBeforeLast('/', "Basics").substringAfterLast('/') }
    .filter { (group, settings) -> query.isBlank() || group.contains(query, true) || settings.any { "${it.title} ${it.tip}".contains(query, true) } }

fun parseThemeHex(value: String): Int? = value.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toInt(16)
internal fun settingsRow(): FlowLayout = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
    gap(5); verticalAlignment(VerticalAlignment.CENTER)
}
internal fun settingsCardColumns(width: Int) = if (width >= 280) 2 else 1
internal fun settingsDrawerWidth(width: Int) = minOf((width / 3).coerceIn(190, 240), width.coerceAtLeast(1))

class SettingsScreen(private val parent: Screen? = null, private var tab: SettingsTab = SettingsTab.GENERAL) :
    MenuScreen(Component.literal("Sky-Myce Settings")) {
    private val rows by lazy { settingRows(SkyMyce.config) }
    private var keyCapture: Pair<SettingRow, ButtonComponent>? = null
    private var changed = false
    private var query = ""
    private lateinit var body: FlowLayout
    private lateinit var heading: LabelComponent
    private lateinit var drawer: FlowLayout
    private lateinit var drawerClip: FlowLayout
    private lateinit var drawerHeading: FlowLayout
    private lateinit var drawerScroll: ScrollContainer<FlowLayout>
    public override val menuParent get() = parent.takeIf { it is MenuScreen || it === WidgetEditorScreen }
    override val settingsCategory get() = tab
    override fun selectSettings(tab: SettingsTab) {
        this.tab = tab; query = ""; selectedGroup = null; keyCapture = null; rebuild()
    }
    private var selectedGroup: String? = null
    private lateinit var details: FlowLayout
    private val detailWidth get() = settingsDrawerWidth(menuWidth)

    override fun init() { SkyMyceCommands.syncSettingsKey(); super.init() }
    override fun buildContent(root: FlowLayout) {
        val inlineSearch = menuWidth >= 560
        val search = MenuTextBox(if (inlineSearch) Sizing.fixed(150) else Sizing.fill()).apply {
            setMaxLength(80); text(query); setHint(Component.literal("Search settings..."))
            tip("Search feature names and setting descriptions in this category")
            onChanged().subscribe { query = it; keyCapture = null; populate() }
        }
        body = column()
        val main = UIContainers.verticalFlow(Sizing.expand(), Sizing.fill()).apply {
            padding(Insets.of(14)); gap(12)
            child(settingsRow().apply {
                gap(10)
                child(column().apply {
                    horizontalSizing(Sizing.expand()); gap(5)
                    child(label("CATEGORY", MenuTheme.MUTED, 8f))
                    heading = label(tab.title, MenuTheme.TEXT, 17f)
                    child(heading)
                })
                if (inlineSearch) child(search)
            })
            if (!inlineSearch) child(search)
            child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), body).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(MenuTheme.BORDER))); scrollbarThiccness(2)
            })
            child(settingsRow().apply {
                child(label("Changes save on close", MenuTheme.MUTED, 8f).horizontalSizing(Sizing.expand()))
                child(button("Done", 42, "Save settings and return") { onClose() })
            })
        }
        drawerHeading = column().apply { padding(Insets.of(14)) }
        details = column().apply { padding(Insets.horizontal(14)); gap(0) }
        drawerScroll = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), details).apply {
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

    private fun populate() {
        body.clearChildren()
        details.clearChildren()
        if (tab == SettingsTab.THEME) {
            closeDetails(false)
            body.child(label("Shared colors, spacing and widget appearance", MenuTheme.MUTED))
            body.child(button("Customize shared theme", minOf(190, menuWidth - 28), "Presets, colors, opacity, spacing, preview and JSON import/export") {
                MC.instance.setScreen(ThemeEditorScreen(this))
            }.apply { verticalSizing(Sizing.fixed(38)) })
            body.child(button("Per-widget themes", minOf(190, menuWidth - 28), "Open the HUD editor and right-click a widget to customize its theme") {
                MC.instance.setScreen(WidgetEditorScreen)
            }.apply { verticalSizing(Sizing.fixed(38)) })
            body.child(label("Presets and global overrides apply to your HUD. Widgets can inherit them or use their own overrides.", MenuTheme.MUTED))
            return
        }
        val groups = settingGroups(rows, tab, query)
        if (selectedGroup !in groups) closeDetails(false)
        if (groups.isEmpty()) { body.child(label("No matching settings", MenuTheme.MUTED)); return }
        val columns = settingsCardColumns(menuWidth)
        groups.entries.chunked(columns).forEach { line -> body.child(settingsRow().apply {
            gap(8)
            line.forEach { (group, settings) -> child(UIContainers.horizontalFlow(Sizing.expand(), Sizing.fixed(43)).apply {
                padding(Insets.of(4)); gap(2); verticalAlignment(VerticalAlignment.CENTER)
                surface(Surface { g, c -> MenuTheme.box(g, c.x(), c.y(), c.width(), c.height(), MenuTheme.CARD,
                    if (selectedGroup == group) MenuTheme.ACCENT else MenuTheme.BORDER) })
                child(MenuButton(Component.literal(group)) {
                    selectedGroup = group; keyCapture = null; populateDetails(groups); openDetails()
                }.apply {
                    sizing(Sizing.expand(), Sizing.fill()); navigation = true
                    tip("Open all ${settings.size} $group settings")
                })
                settings.firstOrNull { it.entry.type() == EntryType.BOOLEAN && it.element.id() in setOf("enabled", "automatic", "dungeonTracker") }?.let { setting ->
                    child(toggle(setting))
                }
            }) }
            repeat(columns - line.size) { child(UIContainers.verticalFlow(Sizing.expand(), Sizing.fixed(43))) }
        }) }
        if (selectedGroup != null) { populateDetails(groups); openDetails(false) }
    }

    private fun populateDetails(groups: Map<String, List<SettingRow>>) {
        details.clearChildren()
        drawerHeading.clearChildren()
        drawerHeading.child(label("FEATURE SETTINGS", MenuTheme.MUTED, 8f))
        drawerHeading.child(settingsRow().apply {
            child(label(selectedGroup.orEmpty(), MenuTheme.TEXT, 12f).horizontalSizing(Sizing.expand()))
            child(button("Close", 42, "Close feature settings") { closeDetails() })
        })
        val settings = groups[selectedGroup].orEmpty()
        drawerHeading.child(label("${settings.size} options", MenuTheme.MUTED, 9f))
        drawerHeading.child(button("Reset feature", 88, "Restore the default values for this feature") {
            settings.forEach { it.entry.reset() }
            if (settings.any { it.path == "openConfigKey" }) SkyMyceCommands.updateSettingsKey()
            keyCapture = null; edited(); populateDetails(groups)
        })
        settings.forEach { setting ->
            details.child(editor(setting))
            details.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(1)).surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER))))
        }
        drawerScroll.scrollTo(0.0)
    }

    private fun openDetails(animate: Boolean = true) {
        if (drawerClip.children().isEmpty()) drawerClip.child(drawer)
        drawerClip.horizontalSizing().animate(if (animate) 200 else 1, Easing.CUBIC, Sizing.fixed(detailWidth)).forwards()
        if (!animate) drawerClip.horizontalSizing(Sizing.fixed(detailWidth))
    }

    private fun closeDetails(animate: Boolean = true) {
        selectedGroup = null; keyCapture = null
        if (!::drawerClip.isInitialized) return
        val animation = drawerClip.horizontalSizing().animate(if (animate) 200 else 1, Easing.CUBIC, Sizing.fixed(0))
        if (animate) animation.finished().subscribe { _, _ -> if (selectedGroup == null) drawerClip.clearChildren() }
        else { drawerClip.horizontalSizing(Sizing.fixed(0)); drawerClip.clearChildren() }
        animation.forwards()
    }

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
            entry.options().hasOption(Option.KEYBIND) -> child(button(keyName(entry.int), 130, setting.tip) { b ->
                keyCapture?.let { it.second.message = Component.literal(keyName(it.first.entry.int)) }
                keyCapture = setting to b; b.message = Component.literal("Press a key…")
            })
            entry.type() == EntryType.ENUM -> child(button(entry.enum.toString(), 150, setting.tip) { b ->
                menu(b) { dropdown -> entry.objectType().enumConstants.filterIsInstance<Enum<*>>().forEach { option ->
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
                if (secret) child(button("Clear key", 72, "Remove the saved optional API key") { if (entry.setString("")) { edited(); populate() } })
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
                }
            }
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
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
            DailyDigest.settingsChanged()
        }
        super.removed()
    }
    override fun onClose() { MC.instance.setScreen(parent) }
    private fun edited() {
        changed = true; PartyFinder.invalidateMatcher()
        heading.color(Color.ofRgb(MenuTheme.ACCENT))
    }
    private fun rebuild() { if (uiAdapter == null) return; uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount() }
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
    private fun menu(anchor: ButtonComponent, options: (DropdownComponent) -> Unit) = DropdownComponent.openContextMenu(this, uiAdapter.rootComponent,
        { root, dropdown -> root.child(dropdown) }, anchor.x.toDouble(), (anchor.y + anchor.height).toDouble(), {
            it.surface(MenuTheme.panel()); options(it)
        })
    companion object { private val SAVE_LOCK = Any() }
}
