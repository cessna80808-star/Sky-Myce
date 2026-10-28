package me.mycellium.skymyce.utils

import java.util.Locale
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
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockItemsRepo
import tech.thatgravyboat.skyblockapi.utils.json.Json.toData
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped

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

/** Immutable item presentation only. No lore, NBT, inventory or remote catalog is persisted. */
data class ObservedItemMetadata(val rarity: String? = null, val displayName: String? = null)
// Read enum declarations without constructing the API's client-dependent text/regex helpers during archive validation.
private val observedRarities = SkyBlockRarity::class.java.declaredFields.filter { it.isEnumConstant }.mapTo(hashSetOf()) { it.name }

/** Match the relay's Unicode plain-name contract; code points preserve valid supplementary characters. */
private fun safeObservedItemName(value: String) = value.codePoints().noneMatch { point ->
    when (Character.getType(point)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt() -> true
        else -> point == 0x2028 || point == 0x2029 || point == '§'.code
    }
}

fun validObservedItemMetadata(rarity: String?, displayName: String?) =
    (rarity == null || rarity in observedRarities) &&
        (displayName == null || displayName.length in 1..120 && displayName == displayName.trim() &&
            safeObservedItemName(displayName))

internal fun observedItemMetadata(rarity: String?, displayName: String?): ObservedItemMetadata? {
    val tier = rarity?.takeIf { it in observedRarities }
    val name = displayName?.takeIf(::safeObservedItemName)?.trim()?.takeIf { validObservedItemMetadata(null, it) }
    return if (tier == null && name == null) null else ObservedItemMetadata(tier, name)
}

/** RARITY is the pinned API's actual lore-derived tier, including the observed recombobulated tier. */
fun observedItemMetadata(stack: ItemStack): ObservedItemMetadata? = if (stack.isEmpty) null else
    observedItemMetadata(runCatching { stack.getData(DataTypes.RARITY)?.name }.getOrNull(),
        runCatching { stack.hoverName.stripped }.getOrNull())

/** Multiple same-ID stacks can carry different upgrades/names. Keep only unanimously observed fields. */
fun consistentItemMetadata(values: List<ObservedItemMetadata?>): ObservedItemMetadata? {
    if (values.isEmpty()) return null
    val rarities = values.map { it?.rarity }.distinct()
    val names = values.map { it?.displayName }.distinct()
    return observedItemMetadata(rarities.singleOrNull(), names.singleOrNull())
}
