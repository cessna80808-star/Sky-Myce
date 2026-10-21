package me.mycellium.skymyce.features.garden.greenhouse

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.phys.AABB
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData

/**
 * Reads what occupies a crop spot as a stable fingerprint: `block:<id>`, `head:<texture>` for a player head (as a block,
 * an armor stand's helmet or a display item), `item:<SkyBlock or vanilla id>`, or `entity:<type>` when nothing more is
 * known. Some crops (Scourroot and others) are armor stands or display entities above an air block. Null means empty.
 */
internal object GreenhouseWorld {
    private val TEXTURE_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

    /** Vanilla crop blocks for the base crops; everything else is matched by item id or learned. */
    private val VANILLA = mapOf(
        "wheat" to "WHEAT_SEEDS", "potatoes" to "POTATO", "carrots" to "CARROT", "melon_stem" to "MELON_SEEDS",
        "attached_melon_stem" to "MELON_SEEDS", "pumpkin_stem" to "PUMPKIN_SEEDS", "attached_pumpkin_stem" to "PUMPKIN_SEEDS",
        "cocoa" to "COCOA_BEANS", "sugar_cane" to "SUGAR_CANE", "cactus" to "CACTUS", "nether_wart" to "NETHER_WART",
        "brown_mushroom" to "BROWN_MUSHROOM", "red_mushroom" to "RED_MUSHROOM", "fire" to "FIRE", "sunflower" to "SUNFLOWER",
    ).mapKeys { "block:${it.key}" }

    fun fingerprint(level: Level, pos: BlockPos): String? {
        val state = level.getBlockState(pos)
        if (state.`is`(Blocks.FIRE) || !state.isAir && !state.canBeReplaced()) {
            (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.let { textureKey(it.partialProfile()) }?.let { return "head:$it" }
            return "block:" + BuiltInRegistries.BLOCK.getKey(state.block).path
        }
        val entities = level.getEntitiesOfClass(Entity::class.java, AABB(pos)) { it is ArmorStand || it is Display }
        for (entity in entities) {
            val stack = when (entity) {
                is ArmorStand -> entity.getItemBySlot(EquipmentSlot.HEAD).takeUnless { it.isEmpty } ?: entity.getItemBySlot(EquipmentSlot.MAINHAND)
                is Display.ItemDisplay -> entity.itemRenderState()?.itemStack()
                is Display.BlockDisplay -> entity.blockRenderState()?.blockState()?.let { return "block:" + BuiltInRegistries.BLOCK.getKey(it.block).path }
                else -> null
            }
            itemKey(stack)?.let { return it }
        }
        return entities.firstOrNull()?.let { "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(it.type).path }
    }

    /** The plant a fingerprint shows: learned first, then SkyBlock item ids, then vanilla crop blocks. */
    fun plantFor(fingerprint: String, catalog: PlantCatalog, learned: Map<String, String>): String? = learned[fingerprint] ?: when {
        fingerprint.startsWith("item:") -> fingerprint.removePrefix("item:").let { id -> catalog[id]?.id ?: catalog.plants.firstOrNull { id in it.itemIds }?.id }
        else -> VANILLA[fingerprint]
    }

    private fun itemKey(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty) return null
        stack.getData(DataTypes.ID)?.let { return "item:$it" }
        stack.get(DataComponents.PROFILE)?.let { textureKey(it.partialProfile()) }?.let { return "head:$it" }
        return "item:" + BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase()
    }

    /** The skin file name from a head's texture property; the full value also carries a timestamp that varies. */
    private fun textureKey(profile: GameProfile): String? {
        val value = profile.properties()["textures"].firstOrNull()?.value ?: return null
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(value)) }.getOrNull() ?: return value.take(64)
        return TEXTURE_URL.find(decoded)?.groupValues?.get(1)?.substringAfterLast('/') ?: value.take(64)
    }
}
