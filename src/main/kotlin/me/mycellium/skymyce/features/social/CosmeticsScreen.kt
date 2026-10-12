package me.mycellium.skymyce.features.social

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import net.minecraft.network.chat.FormattedText
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Optional
import kotlin.math.roundToInt

/** Preview paints a separate render state; Save alone changes the public profile. */
class CosmeticsScreen(private val link: Boolean = false, private val focusPath: String? = null) : MenuScreen(Component.literal("Cosmetics Studio"), MenuPage.COSMETICS) {
    override val menuCategory get() = MenuCategory.COSMETICS
    private val studio = CosmeticsStudio()
    private var draft = CosmeticProfile(MC.instance.user.profileId, null, 1f, 0, 0)
    private var nameJson: com.google.gson.JsonElement? = null
    private var seen = -1L
    private var loadedRevision = -1L
    private var rotation = 180f
    private var color = MenuTheme.ACCENT
    private var bold = false
    private var dirtyPreferences = false
    private lateinit var preview: FlowLayout
    private lateinit var controls: FlowLayout
    private lateinit var status: MenuLabel
    private lateinit var save: MenuButton
    private lateinit var cape: MenuButton
    private lateinit var nameInput: MenuTextBox
    private lateinit var rotationSlider: MenuSlider
    private val sliders = mutableMapOf<String, MenuSlider>()
    override fun isPauseScreen() = false

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COSMETICS STUDIO", MenuTheme.ACCENT))
        status = label(studio.status, MenuTheme.MUTED); root.child(status)
        val row = UIContainers.horizontalFlow(Sizing.fill(), Sizing.expand()).apply { gap(10) }
        val left = UIContainers.verticalFlow(Sizing.fixed((menuWidth * .34).toInt().coerceAtLeast(135)), Sizing.fill()).apply { gap(8) }
        preview = UIContainers.verticalFlow(Sizing.fill(), Sizing.expand()).apply { surface(MenuTheme.panel()); padding(Insets.of(8)) }
        preview.child(label("LIVE PREVIEW", MenuTheme.SECONDARY))
        preview.child(label("${MC.instance.user.name} • actual skin", MenuTheme.MUTED))
        if (MC.instance.player == null) preview.child(label("Join a world to preview your player model.", MenuTheme.MUTED))
        left.child(preview)
        left.child(label("Rotate to inspect your cape", MenuTheme.MUTED))
        rotationSlider = MenuSlider().apply { value(rotation / 360.0); onChanged().subscribe { rotation = (it * 360).toFloat() } }
        left.child(rotationSlider)
        left.child(button("Front / back") {
            rotation = if (rotation < 90 || rotation > 270) 180f else 0f
            rotationSlider.value(rotation / 360.0)
        })
        left.child(label("Preview only. Gameplay identity, movement and hitboxes stay the same.", MenuTheme.MUTED))
        controls = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(8)) }
        row.child(left); row.child(menuScroll(Sizing.expand(), Sizing.fill(), controls).apply { scrollStep(20); scrollbarThiccness(2) })
        root.child(row); populate(); studio.refresh()
    }
    private fun populate() {
        controls.clearChildren(); sliders.clear()
        controls.child(label("DISPLAY NAME", MenuTheme.SECONDARY))
        nameInput = MenuTextBox(Sizing.fill()).apply {
            setMaxLength(32); text(draft.name.orEmpty()); setHint(Component.literal("Blank uses real Minecraft name"))
            onChanged().subscribe { rename(it) }
        }; controls.child(nameInput)
        controls.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5); verticalAlignment(VerticalAlignment.CENTER)
            child(button("Cyan") { color = 0x62D7F1; rename(nameInput.value) }.horizontalSizing(Sizing.fill(31)))
            child(button("Violet") { color = 0xB593F5; rename(nameInput.value) }.horizontalSizing(Sizing.fill(31)))
            child(button("Bold") { bold = !bold; rename(nameInput.value) }.apply { selected = { bold }; horizontalSizing(Sizing.fill(31)) })
        })
        controls.child(label("NAMETAG", MenuTheme.SECONDARY))
        scale("Nametag", .5f, 2f, { draft.nametagScale }) { draft = draft.copy(nametagScale = it) }
        controls.child(label("BODY SCALE", MenuTheme.SECONDARY))
        scale("Width X", .1f, 3f, { draft.scaleX }) { draft = draft.copy(scaleX = it) }
        scale("Height Y", .1f, 3f, { draft.scaleY }) { draft = draft.copy(scale = it, scaleY = it) }
        scale("Depth Z", .1f, 3f, { draft.scaleZ }) { draft = draft.copy(scaleZ = it) }
        controls.child(button("Reset body scale") { draft = draft.copy(scale = 1f, scaleX = 1f, scaleY = 1f, scaleZ = 1f); refreshSliders() })
        controls.child(label("CAPE", MenuTheme.SECONDARY))
        cape = button(capeLabel()) { openMenuDropdown(cape) { popup ->
            popup.button(Component.literal("Minecraft cape")) { draft = draft.copy(capePreset = null, capeAsset = null); updateCapeLabel() }
            popup.button(Component.literal("No cape")) { draft = draft.copy(capePreset = "none", capeAsset = null); updateCapeLabel() }
            CapeTextures.presets.forEach { (id, title) -> popup.button(Component.literal(title)) { draft = draft.copy(capePreset = id, capeAsset = null); updateCapeLabel() } }
            studio.assets.forEach { asset -> popup.button(Component.literal("Upload ${asset.id.take(8)}")) { draft = draft.copy(capePreset = null, capeAsset = asset); updateCapeLabel() } }
        } }; controls.child(cape)
        controls.child(label("64 × 32 RGB/RGBA PNG • 256 KiB • 8 uploads", MenuTheme.MUTED))
        val path = MenuTextBox(Sizing.fill()).apply { setMaxLength(2048); setHint(Component.literal("Local path to cape.png")) }; controls.child(path)
        controls.child(button("Browse PNG…") { studio.chooseFile { path.text(it) } })
        controls.child(button("Upload PNG from this path") { studio.upload(path.value) })
        controls.child(button("Delete selected upload") { draft.capeAsset?.let(studio::delete) })
        controls.child(label("LOCAL DISPLAY", MenuTheme.SECONDARY))
        preference("Show cosmetic names", { CosmeticsConfig.names }) { CosmeticsConfig.names = !CosmeticsConfig.names }
        preference("Show body scaling", { CosmeticsConfig.scaling }) { CosmeticsConfig.scaling = !CosmeticsConfig.scaling }
        preference("Original tooltip names", { CosmeticsConfig.originalTooltips }) { CosmeticsConfig.originalTooltips = !CosmeticsConfig.originalTooltips }
        save = button("Save public cosmetics") { studio.save(draft, nameJson) }; controls.child(save)
        controls.child(button("Refresh saved profile") { studio.refresh() })
        controls.child(button("Connect Discord") { navigateMenu(CommunityScreen()) })
    }
    private fun rename(value: String) {
        val json = value.takeIf { it.isNotBlank() }?.let { JsonObject().apply { addProperty("text", it); addProperty("color", "#%06x".format(color)); addProperty("bold", bold) } }
        val component = runCatching { json?.let(::parseCosmeticText) }.getOrElse { status.text(Component.literal("Name contains unsupported characters").withColor(MenuTheme.RED)); return }
        nameJson = json; draft = draft.copy(name = component?.string, nameStyle = component)
    }
    private fun scale(title: String, minimum: Float, maximum: Float, get: () -> Float, set: (Float) -> Unit) {
        val caption = label("$title  ${"%.2f".format(java.util.Locale.ROOT, get())}×")
        val slider = MenuSlider().apply {
            value(((get() - minimum) / (maximum - minimum)).toDouble())
            onChanged().subscribe { set((minimum + (maximum - minimum) * it).toFloat()); caption.text(Component.literal("$title  ${"%.2f".format(java.util.Locale.ROOT, get())}×")) }
        }; sliders[title] = slider; controls.child(caption); controls.child(slider)
    }
    private fun refreshSliders() {
        sliders.forEach { (title, slider) -> val value = when (title) { "Nametag" -> draft.nametagScale; "Width X" -> draft.scaleX; "Height Y" -> draft.scaleY; else -> draft.scaleZ }
            slider.value(((value - if (title == "Nametag") .5f else .1f) / if (title == "Nametag") 1.5f else 2.9f).toDouble()) }
    }
    private fun preference(title: String, get: () -> Boolean, action: () -> Unit) {
        controls.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5); verticalAlignment(VerticalAlignment.CENTER)
            child(label(title).horizontalSizing(Sizing.expand()))
            child(button(title) { action(); dirtyPreferences = true }.apply {
                toggled = get; sizing(Sizing.fixed(28), Sizing.fixed(22)); tooltip(Component.literal(title))
            })
        })
    }
    private fun capeLabel() = if (draft.capePreset == "none") "No cape" else
        draft.capePreset?.let(CapeTextures.presets::get) ?: draft.capeAsset?.let { "Upload ${it.id.take(8)}" } ?: "Minecraft cape"
    private fun updateCapeLabel() { cape.message = Component.literal(capeLabel()) }
    override fun tick() {
        super.tick(); if (!::status.isInitialized || menuClosing) return
        if (studio.observeOwner()) { draft = CosmeticProfile(MC.instance.user.profileId, null, 1f, 0, 0); nameJson = null; loadedRevision = -1; populate(); if (CommunityClient.connected) studio.refresh() }
        save.active = CommunityClient.connected && studio.profile != null && !studio.busy
        if (seen == studio.revision) return
        seen = studio.revision; status.text(Component.literal(studio.status).withColor(MenuTheme.MUTED))
        studio.profile?.takeIf { it.revision != loadedRevision }?.let {
            loadedRevision = it.revision; draft = it; nameJson = it.nameStyle?.let(::cosmeticTextJson)
            color = it.nameStyle?.style?.color?.value ?: MenuTheme.ACCENT; bold = it.nameStyle?.style?.isBold ?: false
            populate()
        }
    }
    override fun extractNativeContent(g: GuiGraphicsExtractor, sx: Float, sy: Float, partialTicks: Float) {
        val player = MC.instance.player ?: return; if (!::preview.isInitialized) return
        val left = ((preview.x() + 8) * sx).roundToInt(); val top = ((preview.y() + 45) * sy).roundToInt()
        val right = ((preview.x() + preview.width() - 8) * sx).roundToInt(); val bottom = ((preview.y() + preview.height() - 8) * sy).roundToInt()
        if (bottom - top < 50) return
        val state = MC.instance.entityRenderDispatcher.getPlayerRenderer(player).createRenderState(player, partialTicks)
        (state as CosmeticAvatarState).`skymyce$setCosmetics`(draft)
        state.`skymyce$setPreview`(true)
        state.skin = CapeTextures.applyPreview(player.skin, draft); state.nameTag = null; state.showCape = true
        state.bodyRot = 0f; state.yRot = 0f; state.xRot = 0f; state.walkAnimationSpeed = 0f
        val scale = minOf((bottom - top) / 3.8f, (right - left) / 2.5f, (bottom - top - 15) / (player.bbHeight * draft.scaleY), (right - left) / (1.1f * draft.scaleX))
        g.entity(state, scale, Vector3f(0f, .9f * draft.scaleY, 0f), Quaternionf().rotateZ(Math.PI.toFloat()).rotateY(Math.toRadians(rotation.toDouble()).toFloat()), null, left, top, right, bottom)
        val tag = draft.nameStyle ?: Component.literal(MC.instance.user.name); val tagScale = sy * draft.nametagScale
        g.enableScissor(left, top - 15, right, bottom); g.pose().pushMatrix()
        try { g.pose().translate((left + right) / 2f, top.toFloat()); g.pose().scale(tagScale); g.text(MC.font, tag, -MC.font.width(tag) / 2, 0, 0xffffffff.toInt(), true) }
        finally { g.pose().popMatrix(); g.disableScissor() }
    }
    override fun removed() { studio.close(); CapeTextures.clearPreviews(); if (dirtyPreferences) java.util.concurrent.CompletableFuture.runAsync { runCatching { SkyMyce.config.save() } }; super.removed() }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}

/** Preserve the inert styled name when only body/cape settings change. */
fun cosmeticTextJson(component: Component): JsonObject {
    val parts = JsonArray()
    component.visit(FormattedText.StyledContentConsumer<Unit> { style, value ->
        if (value.isNotEmpty()) parts.add(JsonObject().apply {
            addProperty("text", value); style.color?.let { addProperty("color", "#%06x".format(it.value)) }
            addProperty("bold", style.isBold); addProperty("italic", style.isItalic); addProperty("underlined", style.isUnderlined); addProperty("strikethrough", style.isStrikethrough)
        }); Optional.empty()
    }, net.minecraft.network.chat.Style.EMPTY)
    return JsonObject().apply { addProperty("text", ""); add("extra", parts) }
}
