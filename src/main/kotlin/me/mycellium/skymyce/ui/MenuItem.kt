package me.mycellium.skymyce.ui

import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import io.wispforest.owo.ui.component.ItemComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.UIComponent
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt
import me.mycellium.mixin.MenuItemLayersAccessor
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.ItemUtils
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.item.MissingItemModel
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling

private fun menuItemModelStack(stack: ItemStack): ItemStack {
    val model = stack.get(DataComponents.ITEM_MODEL) ?: return stack
    if (MC.instance.modelManager.getItemModel(model) !is MissingItemModel) return stack
    val base = stack.prototype.get(DataComponents.ITEM_MODEL) ?: return stack
    return stack.copy().apply { set(DataComponents.ITEM_MODEL, base) }
}

/** Minecraft owns model selection, including pack overrides and composite item layers. */
internal fun menuItemModelTextures(stack: ItemStack): List<Pair<TextureAtlasSprite, Int>> {
    if (stack.isEmpty) return emptyList()
    val state = ItemStackRenderState()
    val mc = MC.instance
    mc.itemModelResolver.updateForTopItem(state, menuItemModelStack(stack), ItemDisplayContext.GUI, mc.level, mc.player, 0)
    val layers = state as MenuItemLayersAccessor
    return (0 until layers.`skymyce$activeLayerCount`()).flatMap { index ->
        val layer = layers.`skymyce$layers`()[index]
        layer.prepareQuadList().map { quad ->
            val material = quad.materialInfo()
            val tint = layer.tintLayers().let { if (material.tintIndex() in 0 until it.size) it.getInt(material.tintIndex()) else -1 }
            material.sprite() to tint
        }.distinct()
    }
}

/** Item art, including profile-textured skulls, is painted by our canvas. */
class MenuItem(stack: ItemStack) : ItemComponent(stack) {
    private var loaded = false
    private var art: BufferedImage? = null
    private var modelArt: List<BufferedImage> = emptyList()
    private var loadedModel: Any? = null
    private var loadedTextures: List<Pair<TextureAtlasSprite, Int>> = emptyList()
    private var generation = 0

    override fun stack(stack: ItemStack): ItemComponent {
        generation++; loaded = false; art = null; modelArt = emptyList()
        return super.stack(stack)
    }

    private fun loadSkull(profile: ResolvableProfile) {
        val request = generation
        val mc = MC.instance
        mc.playerSkinRenderCache().lookup(profile).handle { resolved, _ ->
            mc.execute {
                if (request != generation) return@execute
                val skin = resolved?.orElse(null)?.playerSkin() ?: DefaultPlayerSkin.get(profile.partialProfile()).with(profile.skinPatch())
                val texture = skin.body().texturePath()
                skullIcons[texture]?.let { art = it; return@execute }
                // Vanilla owns this texture. Copy its straight ARGB pixels; never close the shared image.
                val copied = runCatching {
                    val pixels = (mc.textureManager.getTexture(texture) as? DynamicTexture)?.pixels
                    if (pixels == null || pixels.isClosed || pixels.width !in 1..1024 || pixels.height !in 1..1024) null
                    else BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB).apply {
                        setRGB(0, 0, width, height, pixels.getPixels(), 0, width)
                    }
                }.getOrNull()
                Scheduling.async {
                    // Built-in/default skins are resource-backed rather than downloaded DynamicTextures.
                    val icon = runCatching {
                        val image = copied ?: mc.resourceManager.getResource(texture).orElse(null)?.open()?.use(ImageIO::read)
                        image?.let(::menuSkullArt)
                    }.getOrNull()
                    mc.execute {
                        if (request == generation) {
                            art = icon
                            if (icon != null) skullIcons[texture] = icon
                        }
                    }
                }
            }
        }
    }

    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (stack.isEmpty) return
        val displayed = menuItemModelStack(stack)
        val model = displayed.get(DataComponents.ITEM_MODEL)?.let(MC.instance.modelManager::getItemModel)
        val textures = runCatching { menuItemModelTextures(displayed) }.getOrDefault(emptyList())
        if (!loaded || model !== loadedModel || textures != loadedTextures) {
            generation++; art = null
            loaded = true
            loadedModel = model; loadedTextures = textures
            modelArt = textures.mapNotNull { (sprite, tint) ->
                val key = sprite to tint
                itemIcons[key] ?: runCatching {
                    val contents = sprite.contents()
                    val id = contents.name()
                    val texture = Identifier.fromNamespaceAndPath(id.namespace, "textures/${id.path}.png")
                    MC.instance.resourceManager.getResource(texture).orElse(null)?.open()?.use(ImageIO::read)?.let { source ->
                        // shortcut: menu icons are static; use a sprite frame until animated previews are requested.
                        val frame = if (contents.isAnimated) contents.getUniqueFrames().firstOrNull() ?: 0 else 0
                        menuItemTextureArt(source, contents.width(), contents.height(), frame, tint)
                    }
                }.getOrNull()?.also { itemIcons[key] = it }
            }
            if (modelArt.isEmpty() && (stack.item == Items.PLAYER_HEAD || stack.get(DataComponents.PROFILE) != null))
                loadSkull(stack.get(DataComponents.PROFILE) ?: ResolvableProfile.Static.EMPTY)
            else if (modelArt.isEmpty()) {
                val id = BuiltInRegistries.ITEM.getKey(stack.item)
                val texture = Identifier.fromNamespaceAndPath(id.namespace, "textures/item/${id.path}.png")
                art = runCatching {
                    MC.instance.resourceManager.getResource(texture).orElse(null)?.open()?.use(ImageIO::read)
                }.getOrNull()
            }
        }
        if (modelArt.isNotEmpty()) modelArt.forEach { (g as MenuGraphics).picture(it, x, y, width, height) }
        else art?.let { (g as MenuGraphics).picture(it, x, y, width, height) } ?: run {
            MenuTheme.box(g, x, y, width, height, MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 12))
            val initials = stack.hoverName.string.replace(formatting, "").split(whitespace)
                .filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1).uppercase() }
                .ifEmpty { "?" }
            val left = x + ((width - MenuCanvas.textWidth(initials, true)) / 2).toInt()
            g.text(MC.font, net.minecraft.network.chat.Component.literal(initials).withStyle { it.withBold(true) },
                left, y + (height - 10) / 2, MenuTheme.alpha(MenuTheme.ACCENT), false)
        }
        if (showOverlay && stack.count > 1) {
            val count = stack.count.toString()
            g.text(MC.font, count, x + width - MenuCanvas.textWidth(count).toInt(), y + height - 10,
                MenuTheme.alpha(MenuTheme.TEXT), false)
        }
    }

    companion object {
        private val formatting = Regex("§.")
        private val whitespace = Regex("\\s+")
        // Render-thread-only cache; item pages share immutable icons without retaining stacks/profiles.
        private val skullIcons = object : LinkedHashMap<Identifier, BufferedImage>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Identifier, BufferedImage>?) = size > 128
        }
        private val itemIcons = object : LinkedHashMap<Pair<TextureAtlasSprite, Int>, BufferedImage>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TextureAtlasSprite, Int>, BufferedImage>?) = size > 128
        }
    }
}

/** A static icon uses a real sprite frame, with the tint selected by Minecraft. */
internal fun menuItemTextureArt(source: BufferedImage, width: Int, height: Int, frame: Int, tint: Int): BufferedImage? {
    if (width !in 1..2048 || height !in 1..2048 || source.width % width != 0 || source.height % height != 0) return null
    val columns = source.width / width
    val rows = source.height / height
    if (frame < 0 || frame >= columns * rows) return null
    val left = frame % columns * width
    val top = frame / columns * height
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) {
            val color = source.getRGB(left + x, top + y)
            var tinted = 0
            for (shift in 0..24 step 8)
                tinted = tinted or (((color ushr shift and 255) * (tint ushr shift and 255) / 255) shl shift)
            setRGB(x, y, tinted)
        }
    }
}

/** Skin UVs and the skull item's 30°/45° GUI pose, baked without Minecraft rendering. */
internal fun menuSkullArt(skin: BufferedImage): BufferedImage? {
    if (skin.width < 64 || skin.width % 64 != 0) return null
    val factor = skin.width / 64
    if (skin.height != 32 * factor && skin.height != 64 * factor) return null
    val result = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    val scale = 56.0 / (8.5 * (sqrt(3.0) / 2 + 1 / sqrt(2.0)))
    val horizontal = scale / sqrt(2.0)
    val slope = horizontal / 2
    val vertical = scale * sqrt(3.0) / 2
    fun face(u: Int, v: Int, light: Double, transform: AffineTransform) {
        val size = 8 * factor
        val pixels = skin.getRGB(u * factor, v * factor, size, size, null, 0, size)
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = (color and 0xFF000000.toInt()) or
                (((color ushr 16 and 255) * light).roundToInt() shl 16) or
                (((color ushr 8 and 255) * light).roundToInt() shl 8) or
                ((color and 255) * light).roundToInt()
        }
        val face = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        face.setRGB(0, 0, size, size, pixels, 0, size)
        graphics.drawImage(face, transform, null)
    }
    for ((offset, radius) in listOf(0 to 4.0, 32 to 4.25)) {
        val edge = radius * 2 / (8 * factor)
        val left = 32 - radius * 2 * horizontal
        val top = 32 - radius * vertical
        // The player's right face is on screen-left; the front face remains unmirrored on screen-right.
        face(offset + 8, 0, 1.0, AffineTransform(edge * horizontal, -edge * slope,
            edge * horizontal, edge * slope, left, top))
        face(offset, 8, .72, AffineTransform(edge * horizontal, edge * slope,
            0.0, edge * vertical, left, top))
        face(offset + 8, 8, .9, AffineTransform(edge * horizontal, -edge * slope,
            0.0, edge * vertical, 32.0, top + radius * 2 * slope))
    }
    graphics.dispose()
    return result
}

/** A stored item key: a plain SkyBlock id (`NECRON_HANDLE`) or a typed one (`pet:…`). Null when it cannot be read. */
fun skyBlockItemId(item: String): SkyBlockId? = runCatching {
    if (':' in item) SkyBlockId.CODEC.parse(JsonOps.INSTANCE, JsonPrimitive(item.lowercase(java.util.Locale.ROOT))).result().orElse(null)
    else SkyBlockId.item(item)
}.getOrNull()

/**
 * The item as Hypixel shows it: the repository definition with its model and skull texture, so resource packs and
 * profile-textured heads render like in game. Falls back to paper with an explanatory tooltip.
 */
fun skyBlockItemIcon(item: String): UIComponent {
    val stack = runCatching { skyBlockItemId(item)?.let(ItemUtils::displayItem) }.getOrNull()?.takeUnless(ItemStack::isEmpty)
    val display = stack ?: runCatching { Items.PAPER.defaultInstance }.getOrDefault(ItemStack.EMPTY)
    return MenuItem(ItemUtils.displayStack(display)).apply {
        if (stack != null) setTooltipFromStack(true) else wrappedTooltip(Component.literal("$item • item definition unavailable"))
    }
}
