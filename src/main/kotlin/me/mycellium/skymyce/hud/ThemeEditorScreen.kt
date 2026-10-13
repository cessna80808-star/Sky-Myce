package me.mycellium.skymyce.hud

import com.google.gson.Gson
import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.*
import io.wispforest.owo.ui.container.*
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.settingsFocusTarget
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt

/** Snapshot current values whenever an existing editor returns from search or another menu. */
internal fun currentThemeEditorDocument(widget: Widget? = null) = ThemeDocument(preset = ThemeConfig.preset,
    overrides = if (widget == null) ThemeConfig.overrides() else widget.theme ?: ThemeOverrides())

/** One editor for the shared palette and nullable widget overrides. Clipboard imports are data only. */
class ThemeEditorScreen(private val parent: Screen?, private val widget: Widget? = null, focusKey: String? = null) :
    MenuScreen(Component.literal("Sky-Hawk Theme")) {
    override val menuCategory get() = MenuCategory.SETTINGS
    override val menuParent get() = if (parent is SettingsScreen) parent.menuParent else parent
    override val settingsCategory get() = SettingsTab.THEME
    override fun selectSettings(tab: SettingsTab) {
        val settings = (parent as? SettingsScreen) ?: SettingsScreen(parent, tab)
        settings.selectSettings(tab)
        navigateMenu(settings)
    }
    private var draft = currentThemeEditorDocument(widget)
    private lateinit var preview: FlowLayout
    private lateinit var controlsScroll: MenuScroll<FlowLayout>
    private lateinit var notice: LabelComponent
    private val states = mutableMapOf<String, LabelComponent>()
    private val expandedColors = mutableSetOf<String>().apply { focusKey?.let(::add) }
    private var pendingFocus = focusKey
    private val controlsByKey = mutableMapOf<String, UIComponent>()
    private val targetsByKey = mutableMapOf<String, UIComponent>()
    private var renderedDocument: ThemeDocument? = null
    private var renderedPins = -1L
    private var dirty = false
    override fun isPauseScreen() = false
    override fun init() {
        val previousControls = if (::controlsScroll.isInitialized) controlsScroll else null
        val offset = previousControls?.scrollTarget ?: 0.0
        // Palette edits apply immediately, so there is no unapplied document to retain here.
        draft = currentThemeEditorDocument(widget)
        super.init()
        if (previousControls != null && !invalid && (renderedDocument != draft || renderedPins != MenuShortcuts.revision)) rebuildMenu()
        if (previousControls != null && ::controlsScroll.isInitialized) controlsScroll.scrollBy(offset, true, false)
    }
    override fun buildContent(root: FlowLayout) {
        renderedDocument = draft; renderedPins = MenuShortcuts.revision
        states.clear()
        controlsByKey.clear()
        targetsByKey.clear()
        root.surface(Surface.BLANK).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(column().apply {
            sizing(Sizing.fixed(menuWidth), Sizing.fixed(menuHeight))
            padding(Insets.of(8)); surface(MenuTheme.panel())
            child(row().apply {
                child(label(if (widget == null) "SHARED THEME" else "WIDGET / ${widget.title}", MenuTheme.ACCENT).apply { horizontalSizing(Sizing.expand()) })
                child(button("Done", 48) { onClose() })
            })
            preview = column(); child(preview)
            val controls = column()
            controlsScroll = menuScroll(Sizing.fill(), Sizing.expand(), controls).apply { scrollbarThiccness(2); scrollStep(20) }
            child(controlsScroll)
            if (widget == null) controls.child(button("Preset: ${draft.preset.title}", 190) { b ->
                openMenuDropdown(b) { d ->
                    ThemePreset.entries.forEach { preset -> d.button(Component.literal(preset.title)) {
                        draft = draft.copy(preset = preset); applyDraft(); rebuild()
                    } }
                }
            }.wrappedTooltip(Component.literal("Change the inherited base palette. Existing overrides stay in place; Reset removes them."))
                .also { controlsByKey["preset"] = it })
            pinControl("preset")?.let { controls.child(it) }
            controls.child(row().apply {
                child(button("Reset", 55) { draft = draft.copy(overrides = ThemeOverrides()); applyDraft(); rebuild() }
                    .wrappedTooltip(Component.literal(if (widget == null) "Reset all global overrides to the selected preset." else "Use the global theme for every widget value. Layout and scale stay unchanged.")))
                child(button("Export", 55) { MC.instance.keyboardHandler.clipboard = exportThemeDocument(draft); notice.text(Component.literal("Theme JSON copied to clipboard")); showMenuToast("Theme copied to clipboard", MenuToastKind.SUCCESS) }
                    .wrappedTooltip(Component.literal("Copy versioned theme JSON. No positions, identities or secrets are included.")))
                child(button("Import", 55) { importClipboard() }.wrappedTooltip(Component.literal("Import theme JSON from the clipboard, limited to 16 KiB. Invalid data changes nothing.")))
            })
            controls.child(label("Values marked Inherited follow ${if (widget == null) "the preset" else "your global theme"}." , MenuTheme.MUTED))
            for ((key, name) in MenuCatalog.themeColorTitles) controls.child(colorControl(key, name))
            for ((key, name) in MenuCatalog.themeNumberTitles) {
                val range = if (key == "opacity") 20..100 else 2..12
                controls.child(numberControl(key, name, range.first, range.last))
            }
            for ((key, name) in MenuCatalog.themeFlagTitles) controls.child(row().apply {
                child(label(name).apply { horizontalSizing(Sizing.expand()) })
                child(button(flagLabel(key), 105) { b ->
                    val current = Gson().toJsonTree(draft.overrides).asJsonObject.get(key)?.asBoolean
                    setField(key, when (current) { null -> true; true -> false; false -> null }); b.message = Component.literal(flagLabel(key))
                }.wrappedTooltip(Component.literal("Cycle inherited, enabled and disabled. Uses the renderer's supported pixel-rounded corners."))
                    .also { targetsByKey[key] = it })
            }.also { controlsByKey[key] = it })
            notice = label("Live preview • changes save when this screen closes", MenuTheme.MUTED); child(notice)
        })
        updatePreview()
    }
    private fun resolved() = resolveTheme(draft.preset, if (widget == null) draft.overrides else ThemeConfig.overrides(), if (widget == null) null else draft.overrides)
    private fun colorControl(key: String, name: String) = MenuCollapsible(Sizing.fill(), Sizing.content(), Component.literal(name).withColor(MenuTheme.TEXT), key in expandedColors).apply {
        onToggled().subscribe { expanded -> if (expanded) expandedColors.add(key) else expandedColors.remove(key) }
        wrappedTooltip(Component.literal("Choose a color override or reset to inheritance. Status colors remain independent."))
        val resolved = Gson().toJsonTree(resolved()).asJsonObject.get(key).asInt
        val hex = MenuTextBox(Sizing.fixed(100)).apply { setMaxLength(7); text("#%06X".format(resolved)); wrappedTooltip(Component.literal("Six hexadecimal RGB digits, for example #67CCF2.")) }
        targetsByKey[key] = hex
        val picker = MenuColorPicker().apply { sizing(Sizing.fill(), Sizing.fixed(64)); selectedColor(Color.ofRgb(resolved)) }
        val sliders = mutableListOf<MenuSlider>()
        var changing = false
        fun update(value: Int) {
            if (changing) return
            changing = true; setField(key, value); hex.text("#%06X".format(value)); picker.selectedColor(Color.ofRgb(value))
            sliders.forEachIndexed { i, s -> s.value((value shr (16 - i * 8) and 255) / 255.0) }; changing = false
        }
        child(row().apply {
            child(hex); child(button("Inherit", 60) { setField(key, null); rebuild() })
            val state = label(fieldState(key), MenuTheme.MUTED).apply { horizontalSizing(Sizing.expand()) }; states[key] = state; child(state)
        })
        child(picker)
        listOf("Red", "Green", "Blue").forEachIndexed { i, name ->
            child(label(name, MenuTheme.MUTED))
            val slider = MenuSlider().apply {
                value((resolved shr (16 - i * 8) and 255) / 255.0); scrollStep(1.0 / 255)
                wrappedTooltip(Component.literal("$name channel, 0–255. Arrow keys adjust the focused slider."))
                onChanged().subscribe { value -> if (!changing) {
                    val color = Gson().toJsonTree(resolved()).asJsonObject.get(key).asInt
                    update((color and (255 shl (16 - i * 8)).inv()) or ((value * 255).roundToInt() shl (16 - i * 8)))
                } }
            }
            sliders += slider; child(slider)
        }
        hex.onChanged().subscribe { me.mycellium.skymyce.config.parseThemeHex(it)?.let(::update) }
        picker.onChanged().subscribe { update(it.rgb()) }
        pinControl(key)?.let { child(it) }
    }.also { controlsByKey[key] = it }
    private fun numberControl(key: String, title: String, min: Int, max: Int) = column().apply {
        child(row().apply {
            val state = label("$title • ${fieldState(key)}", MenuTheme.MUTED).apply { horizontalSizing(Sizing.expand()) }; states[key] = state; child(state)
            child(button("Inherit", 60) { setField(key, null); rebuild() })
        })
        child(MenuSlider().apply {
            val current = Gson().toJsonTree(resolved()).asJsonObject.get(key).asInt
            value((current - min).toDouble() / (max - min)); scrollStep(1.0 / (max - min))
            wrappedTooltip(Component.literal("$title, $min–$max. An explicit override stays fixed when the inherited theme changes."))
            onChanged().subscribe { setField(key, (min + it * (max - min)).roundToInt()) }
        }.also { targetsByKey[key] = it })
        pinControl(key)?.let { child(it) }
    }.also { controlsByKey[key] = it }

    private fun pinControl(key: String): ButtonComponent? {
        val path = "Theme & Appearance/$key"
        if (widget != null || !MenuShortcuts.eligible(path)) return null
        val title = MenuCatalog.themeColorTitles[key] ?: MenuCatalog.themeNumberTitles[key] ?: MenuCatalog.themeFlagTitles[key] ?: "Theme preset"
        return button(if (MenuShortcuts.isPinned(path)) "Unpin" else "Pin", 44) { anchor ->
            val wasPinned = MenuShortcuts.isPinned(path)
            MenuShortcuts.togglePin(path)
            val pinned = MenuShortcuts.isPinned(path)
            anchor.message = Component.literal(if (pinned) "Unpin" else "Pin")
            if (!wasPinned && !pinned) showMenuToast("Quick Settings is full (8 controls)", MenuToastKind.INFO)
        }.wrappedTooltip(Component.literal("Settings → Appearance → $title. Add or remove this canonical control from Quick Settings."))
    }
    override fun tick() {
        super.tick()
        val key = pendingFocus ?: return
        val control = controlsByKey[key] ?: return
        if (control.parent() == null) return
        val handler = control.focusHandler() ?: return
        controlsScroll.scrollTo(control); controlsScroll.scrollBy(0.0, true, false)
        handler.focus(targetsByKey[key] ?: settingsFocusTarget(control), UIComponent.FocusSource.KEYBOARD_CYCLE)
        pendingFocus = null
    }
    private fun setField(key: String, value: Any?) {
        val json = Gson().toJsonTree(draft).asJsonObject
        if (value == null) json.getAsJsonObject("overrides").remove(key) else json.getAsJsonObject("overrides").add(key, Gson().toJsonTree(value))
        draft = parseThemeDocument(json.toString()); applyDraft(); states[key]?.text(Component.literal("$key • ${fieldState(key)}").withColor(MenuTheme.MUTED))
    }
    private fun fieldState(key: String) = Gson().toJsonTree(draft.overrides).asJsonObject.get(key)?.let { "Override: ${it.asString}" } ?: "Inherited"
    private fun flagLabel(key: String) = Gson().toJsonTree(draft.overrides).asJsonObject.get(key)?.let { if (it.asBoolean) "On" else "Off" } ?: "Inherited"
    private fun applyDraft() {
        if (widget == null) { ThemeConfig.preset = draft.preset; ThemeConfig.apply(draft.overrides) }
        else widget.theme = draft.overrides.takeUnless { it == ThemeOverrides() }
        dirty = true; HudTheme.changed(); if (::preview.isInitialized) updatePreview()
    }
    private fun updatePreview() {
        preview.clearChildren()
        val palette = resolved()
        preview.child(column().apply {
            padding(Insets.of(palette.padding))
            surface(Surface { g, c -> HudTheme.withWidget(if (widget == null) null else draft.overrides) {
                HudTheme.rounded(g, c.x(), c.y(), c.width(), c.height(), HudTheme.alpha(palette.card, palette.opacity))
                if (palette.borders) g.outline(c.x(), c.y(), c.width(), c.height(), MenuTheme.alpha(palette.border, 100))
            } })
            child(label("${if (widget == null) draft.preset.title else widget.title} • Live preview", palette.primary).shadow(palette.shadow))
            child(label("Primary text  /  12 tracked runs", palette.text).shadow(palette.shadow))
            child(label("Secondary text • inherited values update together", palette.muted).shadow(palette.shadow))
            child(label("✓ Ready", palette.success).apply {
                text(Component.literal("✓ Ready").withColor(palette.success)
                    .append(Component.literal("  •  ! Attention").withColor(palette.warning)))
            })
        })
    }
    private fun importClipboard() {
        val parsed = runCatching { parseThemeDocument(MC.instance.keyboardHandler.clipboard) }.getOrNull()
        if (parsed == null) { notice.text(Component.literal("Invalid theme • nothing changed").withColor(MenuTheme.RED)); showMenuToast("Theme import could not be read", MenuToastKind.ERROR); return }
        // A widget import uses only overrides; selecting a global preset is deliberately a global action.
        draft = if (widget == null) parsed else parsed.copy(preset = ThemeConfig.preset)
        applyDraft(); rebuild(); notice.text(Component.literal("Theme imported • review the preview before closing"))
        showMenuToast("Theme imported", MenuToastKind.SUCCESS)
    }
    override fun removed() {
        if (dirty) {
            if (widget != null) WidgetManager.save() else Scheduling.schedule(0.milliseconds) {
                runCatching { SkyMyce.config.save() }.onFailure { SkyMyce.logger.warn("Could not save shared theme") }
            }
            dirty = false
        }
        super.removed()
    }
    override fun onClose() { closeMenuTo(menuParent) }
    private fun rebuild() {
        val offset = controlsScroll.scrollTarget
        rebuildMenu()
        controlsScroll.scrollBy(offset, true, false)
    }
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(5); verticalAlignment(VerticalAlignment.CENTER) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()); shadow(MenuTheme.SHADOW) }
    private fun button(text: String, width: Int, action: (ButtonComponent) -> Unit) = MenuButton(Component.literal(text), action).themed().apply { sizing(Sizing.fixed(width), Sizing.fixed(20)) }
}
