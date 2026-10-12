package me.mycellium.skymyce.utils

import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped

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
