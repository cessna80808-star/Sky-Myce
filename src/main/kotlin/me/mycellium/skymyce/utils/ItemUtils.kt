package me.mycellium.skymyce.utils

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.AuctionType
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.BazaarType
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockItemsRepo
import tech.thatgravyboat.skyblockapi.utils.json.Json.toData
import java.util.Locale

/** Empty market quotes are unavailable; a usable Bazaar quote avoids an unnecessary auction lookup. */
internal fun marketPriceOrNull(bazaarPrice: Double?, auctionPrice: () -> Double?): Double? =
    bazaarPrice?.takeIf { it.isFinite() && it > 0.0 }
        ?: auctionPrice()?.takeIf { it.isFinite() && it > 0.0 }

object ItemUtils {
    /** Keep model identity intact; missing packs are handled by the display renderer. */
    fun displayStack(stack: net.minecraft.world.item.ItemStack) = stack

    /** Display only: the SDK recreates templates using a global holder that can lack default components. */
    fun displayItem(id: SkyBlockId): ItemStack {
        val template = if (id.isItem) SkyBlockItemsRepo.get(id.cleanId.uppercase(Locale.ROOT))?.toData(ItemStackTemplate.CODEC) else null
        if (template == null) return id.toItem()
        val holder = template.item()
        if (holder.areComponentsBound()) return template.create()
        val item = holder.value()
        val defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS)
            .set(DataComponents.ITEM_NAME, Component.translatable(item.descriptionId))
            .set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(item)).build()
        return ItemStack(Holder.direct(item, defaults), template.count(), template.components())
    }

    fun SkyBlockId.getPrice(bazaar: BazaarType, auction: AuctionType): Double {
        return getPriceOrNull(bazaar, auction) ?: 0.0
    }

    fun SkyBlockId.getPriceOrNull(bazaar: BazaarType, auction: AuctionType): Double? {
        return try {
            val bazaarProduct = BazaarAPI.getProduct(this.bazaarId)
            val bazaarPrice = bazaarProduct?.let {
                when (bazaar) {
                    BazaarType.INSTANT_BUY -> bazaarProduct.buyPrice
                    BazaarType.INSTANT_SELL -> bazaarProduct.sellPrice
                }
            }
            marketPriceOrNull(bazaarPrice) {
                val auctionProduct = LowestBinAPI.getPrice(this.bazaarId) ?: return@marketPriceOrNull null
                when (auction) {
                    AuctionType.LOWEST -> auctionProduct.lowest
                    AuctionType.HIGHEST -> auctionProduct.highest
                    AuctionType.MEAN -> auctionProduct.mean
                    AuctionType.MEDIAN -> auctionProduct.median
                }.toDouble()
            }
        } catch (exception: Exception) {
            SkyMyce.logger.warn("Could not retrieve a SkyBlock item price for {}", this.bazaarId, exception)
            null
        }
    }
}
