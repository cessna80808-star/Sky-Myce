package me.mycellium.skymyce.ui

import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.utils.ItemUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId

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
