package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ItemComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import me.mycellium.skymyce.utils.MC
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Item art, including profile-textured skulls, is painted by our canvas. */
class MenuItem(stack: ItemStack) : ItemComponent(stack) {
    private var loaded = false
    private var art: BufferedImage? = null
    private var generation = 0

    override fun stack(stack: ItemStack): ItemComponent {
        generation++; loaded = false; art = null
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
        if (!loaded) {
            loaded = true
            if (stack.item == Items.PLAYER_HEAD) loadSkull(stack.get(DataComponents.PROFILE) ?: ResolvableProfile.Static.EMPTY)
            else {
                val id = BuiltInRegistries.ITEM.getKey(stack.item)
                val texture = Identifier.fromNamespaceAndPath(id.namespace, "textures/item/${id.path}.png")
                art = runCatching {
                    MC.instance.resourceManager.getResource(texture).orElse(null)?.open()?.use(ImageIO::read)
                }.getOrNull()
            }
        }
        art?.let { (g as MenuGraphics).picture(it, x, y, width, height) } ?: run {
            MenuTheme.box(g, x, y, width, height, MenuTheme.blend(MenuTheme.CARD, MenuTheme.ACCENT, 12))
            val initials = stack.hoverName.string.replace(Regex("\u00A7."), "").split(Regex("\\s+"))
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
        // Render-thread-only cache; item pages share immutable icons without retaining stacks/profiles.
        private val skullIcons = object : LinkedHashMap<Identifier, BufferedImage>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Identifier, BufferedImage>?) = size > 128
        }
    }
}
