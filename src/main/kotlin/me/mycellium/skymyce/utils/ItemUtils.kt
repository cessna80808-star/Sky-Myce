package me.mycellium.skymyce.utils

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.AuctionType
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.BazaarType
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI

/** Empty market quotes are unavailable; a usable Bazaar quote avoids an unnecessary auction lookup. */
internal fun marketPriceOrNull(bazaarPrice: Double?, auctionPrice: () -> Double?): Double? =
    bazaarPrice?.takeIf { it.isFinite() && it > 0.0 }
        ?: auctionPrice()?.takeIf { it.isFinite() && it > 0.0 }

object ItemUtils {
    /** Display-only copy: preserve lore/upgrades, but use the base item when a custom model is unavailable. */
    fun displayStack(stack: net.minecraft.world.item.ItemStack): net.minecraft.world.item.ItemStack {
        if (stack.isEmpty) return stack
        val key = net.minecraft.core.component.DataComponents.ITEM_MODEL
        val model = stack.get(key) ?: return stack
        if (MC.instance.modelManager.getItemModel(model) !is net.minecraft.client.renderer.item.MissingItemModel) return stack
        val base = stack.item.defaultInstance.get(key) ?: return stack
        return stack.copy().apply { set(key, base) }
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
