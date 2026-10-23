package me.mycellium.skymyce.features.social

import com.google.gson.JsonParser
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityScreen
import me.mycellium.skymyce.config.misc.CosmeticsConfig
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.network.chat.Component
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.roundToInt

/** One Save equips locally and, when connected, publishes the same appearance to the community. */
class CosmeticsScreen(private val link: Boolean = false, private val focusPath: String? = null) : MenuScreen(Component.literal("Cosmetics Studio"), MenuPage.COSMETICS) {
    override val menuCategory get() = MenuCategory.COSMETICS
    private val studio = CosmeticsStudio()
    private var draft = CosmeticProfile(MC.instance.user.profileId, null, 1f, 0, 0)
    private var nameJson: com.google.gson.JsonElement? = null
    private var seen = -1L
    private var loadedRevision = -1L
    private var localSeen = -1L
    private var localSubmitted: CosmeticProfile? = null
    private var minecraftOwner = MC.instance.user.profileId
    private var initializedDraftOwner: UUID? = null
    private var confirmDelete: String? = null
    private var rotation = 180f
    private var color: Int? = null
    private var bold = false
    private val edited = mutableSetOf<CosmeticDraftField>()
    private var inputError: String? = null
    private var populating = false
    private var dirtyPreferences = false
    private lateinit var preview: FlowLayout
    private lateinit var workspace: UIComponent
    private lateinit var controls: FlowLayout
    private lateinit var status: MenuLabel
    private lateinit var draftStatus: MenuLabel
    private lateinit var capeStatus: MenuLabel
    private lateinit var save: MenuButton
    private lateinit var upload: MenuButton
    private lateinit var delete: MenuButton
    private lateinit var connect: MenuButton
    private lateinit var cape: MenuButton
    private lateinit var nameInput: MenuTextBox
    private lateinit var colorInput: MenuTextBox
    private lateinit var jsonInput: MenuTextBox
    private lateinit var rotationSlider: MenuSlider
    private val sliders = mutableMapOf<String, MenuSlider>()
    override fun isPauseScreen() = false

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COSMETICS STUDIO", MenuTheme.ACCENT))
        status = label(studio.status, MenuTheme.MUTED); root.child(status)
        LocalCosmetics.observeOwner()
        initializeDraftOwner()
        draftStatus = label("Save works without Discord", MenuTheme.SECONDARY); root.child(draftStatus)
        val left = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(8) }
        preview = UIContainers.verticalFlow(Sizing.fill(), if (menuWidth < 500) Sizing.fixed(180) else Sizing.expand()).apply { surface(MenuTheme.panel()); padding(Insets.of(8)) }
        preview.child(label("LIVE PREVIEW", MenuTheme.SECONDARY))
        preview.child(label("${MC.instance.user.name} • actual skin", MenuTheme.MUTED))
        capeStatus = label(capeLabel(), MenuTheme.MUTED)
        if (MC.instance.player == null) preview.child(label("Join a world to preview your player model.", MenuTheme.MUTED))
        left.child(preview)
        left.child(capeStatus)
        rotationSlider = MenuSlider().apply { value(rotation / 360.0); onChanged().subscribe { rotation = (it * 360).toFloat() } }
        left.child(rotationSlider)
        left.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5)
            child(button("Front") { face(180f) }.horizontalSizing(Sizing.fill(48)))
            child(button("Back / cape") { face(0f) }.horizontalSizing(Sizing.fill(48)))
        })
        left.child(label("Visual only. Identity, movement and hitboxes stay the same.", MenuTheme.MUTED))
        controls = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(8)) }
        workspace = cosmeticsWorkspace(menuWidth, left, controls)
        root.child(workspace); populate(); studio.refresh()
    }
    private fun populate() {
        populating = true
        controls.clearChildren(); sliders.clear()
        controls.child(label("DISPLAY NAME", MenuTheme.SECONDARY))
        nameInput = MenuTextBox(Sizing.fill()).apply {
            setMaxLength(32); text(draft.name?.takeUnless { it == MC.instance.user.name }.orEmpty()); setHint(Component.literal("Blank uses your Minecraft name"))
            onChanged().subscribe { rename(it) }
        }; controls.child(nameInput)
        controls.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5); verticalAlignment(VerticalAlignment.CENTER)
            child(button("Cyan") { setColor(0x62D7F1) }.apply { selected = { color == 0x62D7F1 }; horizontalSizing(Sizing.fill(31)) })
            child(button("Violet") { setColor(0xB593F5) }.apply { selected = { color == 0xB593F5 }; horizontalSizing(Sizing.fill(31)) })
            child(button("Bold") { bold = !bold; rename(nameInput.value) }.apply { selected = { bold }; horizontalSizing(Sizing.fill(31)) })
        })
        controls.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5)
            colorInput = MenuTextBox(Sizing.expand()).apply {
                setMaxLength(7); text(color?.let { "#%06x".format(java.util.Locale.ROOT, it) }.orEmpty())
                setHint(Component.literal("#RRGGBB • blank is default"))
            }; child(colorInput)
            child(button("Apply color") { applyColor() }.horizontalSizing(Sizing.fixed(88)))
        })
        controls.child(button("Reset name and style") {
            color = null; bold = false; nameInput.text(""); colorInput.text(""); rename("")
        })
        controls.child(label("Color and bold also work with your real name. Blank color restores default.", MenuTheme.MUTED))
        controls.child(label("MINECRAFT NAME JSON", MenuTheme.SECONDARY))
        jsonInput = MenuTextBox(Sizing.fill()).apply {
            setMaxLength(8192); text(nameJson?.toString().orEmpty()); setHint(Component.literal("Paste text, an object, or a component array"))
        }; controls.child(jsonInput)
        controls.child(button("Apply name JSON") {
            val parsed = runCatching { if (jsonInput.value.isBlank()) null else JsonParser.parseString(jsonInput.value).let { it to parseCosmeticText(it) } }
            parsed.fold({ result ->
                nameJson = result?.first; draft = draft.copy(name = result?.second?.string, nameStyle = result?.second)
                color = draft.nameStyle?.style?.color?.value; bold = draft.nameStyle?.style?.isBold ?: false
                mark(CosmeticDraftField.NAME); populate()
            }, { inputError = it.message?.take(160) ?: "Invalid name JSON. Use inert text components, up to 32 visible characters." })
        })
        controls.child(label("Arrays preserve per-character colors. Events, fonts and commands are rejected.", MenuTheme.MUTED))
        controls.child(label("NAMETAG", MenuTheme.SECONDARY))
        scale("Nametag", .5f, 2f, CosmeticDraftField.NAMETAG, { draft.nametagScale }) { draft = draft.copy(nametagScale = it) }
        controls.child(label("BODY SCALE", MenuTheme.SECONDARY))
        scale("Width X", .1f, 3f, CosmeticDraftField.WIDTH, { draft.scaleX }) { draft = draft.copy(scaleX = it) }
        scale("Height Y", .1f, 3f, CosmeticDraftField.HEIGHT, { draft.scaleY }) { draft = draft.copy(scale = it, scaleY = it) }
        scale("Depth Z", .1f, 3f, CosmeticDraftField.DEPTH, { draft.scaleZ }) { draft = draft.copy(scaleZ = it) }
        controls.child(button("Reset body scale") {
            draft = draft.copy(scale = 1f, scaleX = 1f, scaleY = 1f, scaleZ = 1f)
            listOf(CosmeticDraftField.WIDTH, CosmeticDraftField.HEIGHT, CosmeticDraftField.DEPTH).forEach(::mark)
            refreshSliders()
        })
        controls.child(label("CAPE", MenuTheme.SECONDARY))
        cape = button(capeLabel()) { openMenuDropdown(cape) { popup ->
            popup.button(Component.literal("Minecraft cape")) { selectCape(null, null) }
            popup.button(Component.literal("None / Remove cape")) { selectCape("none", null) }
            CapeTextures.presets.forEach { (id, title) -> popup.button(Component.literal(title)) { selectCape(id, null) } }
            LocalCosmetics.assets.forEach { asset -> popup.button(Component.literal("Local PNG ${asset.id.take(8)}")) { selectCape(null, asset, true) } }
            studio.assets.forEach { asset -> popup.button(Component.literal("Community PNG ${asset.id.take(8)}")) { selectCape(null, asset) } }
        } }; controls.child(cape)
        controls.child(label("Pick a cape to preview it; Save keeps it. None removes the cape.", MenuTheme.MUTED))
        controls.child(label("64 × 32 RGB/RGBA PNG • 256 KiB • 8 local capes", MenuTheme.MUTED))
        val path = MenuTextBox(Sizing.fill()).apply { setMaxLength(2048); setHint(Component.literal("Path to cape.png, or Browse")) }; controls.child(path)
        upload = button("Browse PNG…") {
            val typed = path.value.trim()
            if (typed.isNotEmpty()) importCape(typed) else studio.chooseFile { path.text(it); importCape(it) }
        }; controls.child(upload)
        delete = button("Delete selected PNG from library") {
            val asset = draft.capeAsset ?: return@button
            val key = "${draft.localCape}:${asset.id}"
            if (confirmDelete != key) { confirmDelete = key; inputError = "Click Delete again to permanently remove this PNG. None only unequips it." }
            else { confirmDelete = null; inputError = null; if (draft.localCape) { localSubmitted = draft; LocalCosmetics.delete(asset) } else studio.delete(asset) }
        }.apply { intent = MenuButtonIntent.DESTRUCTIVE }; controls.child(delete)
        controls.child(label("LOCAL DISPLAY", MenuTheme.SECONDARY))
        preference("Show cosmetic names", { CosmeticsConfig.names }) { CosmeticsConfig.names = !CosmeticsConfig.names }
        preference("Show body scaling", { CosmeticsConfig.scaling }) { CosmeticsConfig.scaling = !CosmeticsConfig.scaling }
        preference("Original tooltip names", { CosmeticsConfig.originalTooltips }) { CosmeticsConfig.originalTooltips = !CosmeticsConfig.originalTooltips }
        save = button("Save") { saveAll() }.apply { intent = MenuButtonIntent.PRIMARY }; controls.child(save)
        controls.child(label("Saves here and, when Discord is connected, shares it with other Sky-Myce users.", MenuTheme.MUTED))
        controls.child(button("Discard draft changes") { adopt(LocalCosmetics.equipped() ?: studio.profile ?: CosmeticProfile(MC.instance.user.profileId, null, 1f, 0, 0)); populate() })
        connect = button("Connect Discord to share") { navigateMenu(CommunityScreen(this@CosmeticsScreen)) }; controls.child(connect)
        updateCapeLabel()
        populating = false
    }
    private fun rename(value: String) {
        if (populating) return
        val json = runCatching { cosmeticDraftName(value, MC.instance.user.name, color, bold) }.getOrElse { inputError = "Name contains unsupported characters; preview is unchanged."; return }
        val component = json?.let(::parseCosmeticText)
        nameJson = json; draft = draft.copy(name = component?.string, nameStyle = component)
        mark(CosmeticDraftField.NAME)
    }
    private fun setColor(value: Int?) {
        color = value; colorInput.text(value?.let { "#%06x".format(java.util.Locale.ROOT, it) }.orEmpty()); rename(nameInput.value)
    }
    private fun applyColor() {
        val parsed = runCatching { parseCosmeticColor(colorInput.value) }
        if (parsed.isFailure) { inputError = "Use six hex digits, such as #62D7F1, or leave blank for default."; return }
        setColor(parsed.getOrNull())
    }
    private fun mark(field: CosmeticDraftField) { if (!populating) { edited += field; inputError = null } }
    private fun scale(title: String, minimum: Float, maximum: Float, field: CosmeticDraftField, get: () -> Float, set: (Float) -> Unit) {
        val caption = label("$title  ${"%.2f".format(java.util.Locale.ROOT, get())}×")
        val slider = MenuSlider().apply {
            value(((get() - minimum) / (maximum - minimum)).toDouble())
            onChanged().subscribe { if (!populating) { set((minimum + (maximum - minimum) * it).toFloat()); mark(field) }; caption.text(Component.literal("$title  ${"%.2f".format(java.util.Locale.ROOT, get())}×")) }
        }; sliders[title] = slider; controls.child(caption); controls.child(slider)
    }
    private fun refreshSliders() {
        populating = true
        sliders.forEach { (title, slider) -> val value = when (title) { "Nametag" -> draft.nametagScale; "Width X" -> draft.scaleX; "Height Y" -> draft.scaleY; else -> draft.scaleZ }
            slider.value(((value - if (title == "Nametag") .5f else .1f) / if (title == "Nametag") 1.5f else 2.9f).toDouble()) }
        populating = false
    }
    private fun preference(title: String, get: () -> Boolean, action: () -> Unit) {
        controls.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5); verticalAlignment(VerticalAlignment.CENTER)
            child(label(title).horizontalSizing(Sizing.expand()))
            child(button(title) { action(); dirtyPreferences = true }.apply {
                toggled = get; sizing(Sizing.fixed(64), Sizing.fixed(22)); tooltip(Component.literal(title))
            })
        })
    }
    private fun capeLabel() = if (draft.capePreset == "none") "No cape" else
        draft.capePreset?.let(CapeTextures.presets::get) ?: draft.capeAsset?.let { "${if (draft.localCape) "Local" else "Community"} PNG ${it.id.take(8)}" } ?: "Minecraft cape"
    private fun face(angle: Float) { rotation = angle; rotationSlider.value(rotation / 360.0) }
    private fun selectCape(preset: String?, asset: CapeAsset?, local: Boolean = false) {
        draft = draft.copy(capePreset = preset, capeAsset = asset, localCape = local); mark(CosmeticDraftField.CAPE); confirmDelete = null
        updateCapeLabel(); face(0f)
    }
    private fun importCape(path: String) { inputError = null; localSubmitted = draft; LocalCosmetics.import(draft, path) }
    private fun saveAll() {
        inputError = null; localSubmitted = draft; LocalCosmetics.equip(draft)
        if (CommunityClient.connected && studio.profile != null && !studio.busy) publish()
    }
    private fun publish() {
        inputError = null
        val captured = draft; val json = nameJson?.deepCopy()
        if (captured.localCape) {
            val file = LocalCosmetics.localFile(captured) ?: run { inputError = "Local PNG is unavailable. Import it again."; return }
            studio.publishLocal(captured, json, file.toString())
        } else studio.save(captured, json)
    }
    private fun updateCapeLabel() { cape.message = Component.literal(capeLabel()); capeStatus.text(Component.literal("Preview: ${capeLabel()}")) }
    private fun adopt(profile: CosmeticProfile) {
        draft = profile; nameJson = profile.nameStyle?.let(::cosmeticTextJson); edited.clear(); inputError = null
        color = profile.nameStyle?.style?.color?.value; bold = profile.nameStyle?.style?.isBold ?: false
        loadedRevision = profile.revision
    }
    private fun initializeDraftOwner(): Boolean {
        val owner = MC.instance.user.profileId
        val initial = initialCosmeticDraft(initializedDraftOwner, owner, LocalCosmetics.equipped()) ?: return false
        initializedDraftOwner = owner; minecraftOwner = owner; localSubmitted = null; localSeen = -1
        adopt(initial); loadedRevision = -1
        return true
    }
    override fun tick() {
        super.tick(); if (!::status.isInitialized || menuClosing) return
        LocalCosmetics.observeOwner()
        if (initializeDraftOwner()) populate()
        if (studio.observeOwner()) { loadedRevision = -1; if (CommunityClient.connected) studio.refresh() }
        val online = CommunityClient.connected && !studio.busy
        save.active = LocalCosmetics.ready && !LocalCosmetics.busy && !studio.busy && inputError == null
        cape.active = LocalCosmetics.ready && !LocalCosmetics.busy
        upload.active = LocalCosmetics.ready && !LocalCosmetics.busy
        delete.active = draft.capeAsset != null && if (draft.localCape) !LocalCosmetics.busy else online
        connect.active = !CommunityClient.connected
        status.text(Component.literal(inputError ?: LocalCosmetics.status).withColor(if (inputError == null) MenuTheme.MUTED else MenuTheme.RED))
        draftStatus.text(Component.literal(when {
            studio.busy -> studio.status
            edited.isNotEmpty() -> "Unsaved changes • Save to keep them"
            LocalCosmetics.equipped() != null -> "Local appearance takes priority • ${studio.status}"
            else -> studio.status
        }).withColor(MenuTheme.SECONDARY))
        if (localSeen != LocalCosmetics.revision && !LocalCosmetics.busy) {
            localSeen = LocalCosmetics.revision
            val preferred = LocalCosmetics.equipped()
            if (preferred != null) {
                val preserve = localSubmitted?.let { submitted -> CosmeticDraftField.entries.filter { !cosmeticFieldMatches(draft, submitted, it) }.toSet() } ?: edited.toSet()
                val merged = mergeCosmeticDraft(preferred, draft, preserve); val shown = sameCosmeticFields(merged, draft)
                adopt(merged); edited += preserve; if (!shown) populate() else updateCapeLabel()
            } else if (edited.isEmpty() && LocalCosmetics.ready) { adopt(studio.profile ?: CosmeticProfile(minecraftOwner, null, 1f, 0, 0)); populate() }
            localSubmitted = null
        }
        if (seen == studio.revision) return
        seen = studio.revision
        studio.profile?.takeIf { it.revision != loadedRevision && LocalCosmetics.equipped() == null }?.let {
            edited.removeAll { field -> cosmeticFieldMatches(it, draft, field) }
            val merged = mergeCosmeticDraft(it, draft, edited); val shown = sameCosmeticFields(merged, draft)
            draft = merged; loadedRevision = it.revision
            if (CosmeticDraftField.NAME !in edited) {
                nameJson = it.nameStyle?.let(::cosmeticTextJson); color = it.nameStyle?.style?.color?.value; bold = it.nameStyle?.style?.isBold ?: false
            }
            if (!shown) populate(); updateCapeLabel()
        }
    }
    override fun extractNativeContent(g: GuiGraphicsExtractor, sx: Float, sy: Float, partialTicks: Float) {
        val player = MC.instance.player ?: return; if (!::preview.isInitialized) return
        val left = ((preview.x() + 8) * sx).roundToInt(); val top = ((preview.y() + 45) * sy).roundToInt()
        val right = ((preview.x() + preview.width() - 8) * sx).roundToInt(); val bottom = ((preview.y() + preview.height() - 8) * sy).roundToInt()
        val clipTop = maxOf(top - 15, (workspace.y() * sy).roundToInt())
        val clipBottom = minOf(bottom, ((workspace.y() + workspace.height()) * sy).roundToInt())
        if (clipBottom <= clipTop) return
        if (bottom - top < 50) return
        val state = MC.instance.entityRenderDispatcher.getPlayerRenderer(player).createRenderState(player, partialTicks)
        (state as CosmeticAvatarState).`skymyce$setCosmetics`(draft)
        state.`skymyce$setPreview`(true)
        state.skin = CapeTextures.applyPreview(CapeTextures.nativeSkin(player), draft); state.nameTag = null
        prepareCosmeticPreview(state)
        state.bodyRot = 0f; state.yRot = 0f; state.xRot = 0f; state.walkAnimationSpeed = 0f
        val scale = minOf((bottom - top) / 3.8f, (right - left) / 2.5f, (bottom - top - 15) / (player.bbHeight * draft.scaleY), (right - left) / (1.1f * draft.scaleX))
        g.enableScissor(left, clipTop, right, clipBottom)
        try { g.entity(state, scale, Vector3f(0f, .9f * draft.scaleY, 0f), Quaternionf().rotateZ(Math.PI.toFloat()).rotateY(Math.toRadians(rotation.toDouble()).toFloat()), null, left, top, right, bottom) }
        finally { g.disableScissor() }
        val tag = CosmeticNames.decorated(draft.nameStyle ?: Component.literal(MC.instance.user.name)); val tagScale = sy * draft.nametagScale
        g.enableScissor(left, clipTop, right, clipBottom); g.pose().pushMatrix()
        try {
            g.pose().translate((left + right) / 2f, top.toFloat()); g.pose().scale(tagScale)
            // Explicit cosmetic colors stay intact; the theme supplies the unstyled name color.
            val tagWidth = MC.font.width(tag)
            g.fill(-tagWidth / 2 - 3, -2, (tagWidth + 1) / 2 + 3, MC.font.lineHeight + 2, MenuTheme.alpha(MenuTheme.PANEL, 95))
            g.text(MC.font, tag, -tagWidth / 2, 0, MenuTheme.alpha(MenuTheme.TEXT), MenuTheme.SHADOW)
        }
        finally { g.pose().popMatrix(); g.disableScissor() }
    }
    override fun removed() { studio.close(); CapeTextures.clearPreviews(); if (dirtyPreferences) java.util.concurrent.CompletableFuture.runAsync { runCatching { SkyMyce.config.save() }.onFailure { SkyMyce.logger.warn("Could not save cosmetics display preferences", it) } }; super.removed() }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}

internal fun cosmeticsWorkspace(availableWidth: Int, preview: UIComponent, controls: FlowLayout): UIComponent =
    if (availableWidth < 500) menuScroll(Sizing.fill(), Sizing.expand(),
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(10); child(preview.sizing(Sizing.fill(), Sizing.content())); child(controls)
        }).apply { scrollStep(20); scrollbarThiccness(2) }
    else UIContainers.horizontalFlow(Sizing.fill(), Sizing.expand()).apply {
        gap(10)
        child(preview.sizing(Sizing.fixed((availableWidth * .34).toInt()), Sizing.fill()))
        child(menuScroll(Sizing.expand(), Sizing.fill(), controls).apply { scrollStep(20); scrollbarThiccness(2) })
    }

/** Whether every editable field matches, so the controls already show [a] and need no rebuild. */
internal fun sameCosmeticFields(a: CosmeticProfile, b: CosmeticProfile) = CosmeticDraftField.entries.all { cosmeticFieldMatches(a, b, it) }

/** Responsive rebuilds retain drafts; only a first load or actual Minecraft owner change seeds them. */
internal fun initialCosmeticDraft(initializedOwner: UUID?, currentOwner: UUID, equipped: CosmeticProfile?): CosmeticProfile? =
    when {
        initializedOwner == currentOwner -> null
        equipped?.uuid == currentOwner -> equipped
        else -> CosmeticProfile(currentOwner, null, 1f, 0, 0)
    }
