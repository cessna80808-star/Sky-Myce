package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.ItemComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import me.mycellium.skymyce.utils.MC
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Flat item art is painted by our canvas; model-only items keep their name, lore and tooltip. */
class MenuItem(stack: ItemStack) : ItemComponent(stack) {
    private var loaded = false
    private var art: BufferedImage? = null

    override fun stack(stack: ItemStack): ItemComponent {
        loaded = false; art = null
        return super.stack(stack)
    }

    override fun draw(g: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        if (stack.isEmpty) return
        if (!loaded) {
            loaded = true
            val id = BuiltInRegistries.ITEM.getKey(stack.item)
            val texture = Identifier.fromNamespaceAndPath(id.namespace, "textures/item/${id.path}.png")
            art = runCatching {
                MC.instance.resourceManager.getResource(texture).orElse(null)?.open()?.use(ImageIO::read)
            }.getOrNull()
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
}
