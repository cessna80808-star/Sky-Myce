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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData

/**
 * Reads what occupies a crop spot as a stable fingerprint: `block:<id>@<soil block>`, `head:<texture>` for a player head
 * (as a block, an armor stand's helmet or a display item), `item:<SkyBlock or vanilla id>`, or `entity:<type>` when
 * nothing more is known. Mutations are mostly player heads using their item's texture, but some reuse a vanilla crop
 * block on a different soil (Ashwreath is wheat on soul sand), so block fingerprints carry the soil. Null means empty.
 */
internal object GreenhouseWorld {
    private val TEXTURE_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

    /** Mutations seen in game as a vanilla crop block on their own soil; checked before [VANILLA]. */
    private val ON_SOIL = mapOf("block:wheat@soul_sand" to "ASHWREATH")

    /** Vanilla crop blocks for the base crops, on any soil; everything else is matched by head texture, item id or learned. */
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
            return blockKey(level, pos, state)
        }
        val entities = level.getEntitiesOfClass(Entity::class.java, AABB(pos)) { it is ArmorStand || it is Display }
        for (entity in entities) {
            val stack = when (entity) {
                is ArmorStand -> EquipmentSlot.entries.firstNotNullOfOrNull { slot -> entity.getItemBySlot(slot).takeUnless { it.isEmpty } }
                is Display.ItemDisplay -> entity.itemRenderState()?.itemStack()
                is Display.BlockDisplay -> entity.blockRenderState()?.blockState()?.let { return blockKey(level, pos, it) }
                else -> null
            }
            itemKey(stack)?.let { return it }
        }
        return entities.firstOrNull()?.let { "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(it.type).path }
    }

    private fun blockKey(level: Level, pos: BlockPos, state: BlockState) =
        "block:" + BuiltInRegistries.BLOCK.getKey(state.block).path + "@" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).block).path

    /** What is at a spot, for the log when a look is not recognised: entity types, names, flags and equipment. */
    fun describe(level: Level, pos: BlockPos): String = level.getEntitiesOfClass(Entity::class.java, AABB(pos).inflate(0.5)) { it is ArmorStand || it is Display }
        .take(4).joinToString("; ") { entity ->
            val equipment = (entity as? ArmorStand)?.let { stand -> EquipmentSlot.entries.mapNotNull { slot -> stand.getItemBySlot(slot).takeUnless { it.isEmpty }?.let { "$slot=${itemKey(it)}" } } }
            "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path} at ${"%.2f".format(entity.y - pos.y)} name=${entity.customName?.string} invisible=${entity.isInvisible}" +
                ((entity as? ArmorStand)?.let { " marker=${it.isMarker} small=${it.isSmall} equipment=$equipment" } ?: "")
        }.ifEmpty { "block " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path }

    /** The plant a fingerprint shows: learned first, then bundled head textures, SkyBlock item ids and vanilla crop blocks. */
    fun plantFor(fingerprint: String, catalog: PlantCatalog, learned: Map<String, String>): String? = learned[fingerprint] ?: when {
        fingerprint.startsWith("head:") -> catalog.byHead[fingerprint.removePrefix("head:")]?.id
        fingerprint.startsWith("item:") -> fingerprint.removePrefix("item:").let { id -> catalog[id]?.id ?: catalog.plants.firstOrNull { id in it.itemIds }?.id }
        else -> ON_SOIL[fingerprint] ?: VANILLA[fingerprint.substringBefore('@')]
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
