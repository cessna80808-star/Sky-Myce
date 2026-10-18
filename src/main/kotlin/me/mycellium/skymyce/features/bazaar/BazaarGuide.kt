package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.utils.ItemUtils
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.remote.api.SimpleItemAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockAttributesRepo
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockEnchantmentsRepo
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockItemsRepo
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockRunesRepo
import tech.thatgravyboat.skyblockapi.utils.extentions.stripColor
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
import java.util.Locale

/** Exact display-name lookup; never drop words or guess an item from a partial search. */
internal fun bazaarProductId(displayName: String): String? = runCatching {
    val name = displayName.stripColor().trim()
    if (bazaarGuideCommand(name, 1) == null) return@runCatching null
    SimpleItemAPI.findIdByName(name)?.bazaarId?.uppercase(Locale.ROOT)?.takeIf(::validBazaarProductId)
}.getOrNull()

/** Resolve only on the client thread; the SDK owns repository loading. */
internal fun bazaarGuideName(product: String): String? = runCatching {
    val id = BazaarNames.itemId(product) ?: return@runCatching null
    val name = when {
        id.isEnchantment -> {
            val enchantment = SkyBlockEnchantmentsRepo.get(id.cleanId.substringBeforeLast(':')) ?: return@runCatching null
            val level = id.cleanId.substringAfterLast(':').toIntOrNull() ?: return@runCatching null
            val literal = enchantment.levels()[level]?.literalLevel() ?: return@runCatching null
            "${enchantment.name()} $literal"
        }
        id.isAttribute -> SkyBlockAttributesRepo.get(id.cleanId)?.shardName()
        id.isRune -> {
            val level = id.cleanId.substringAfterLast(':').toIntOrNull() ?: return@runCatching null
            SkyBlockRunesRepo.getTier(id.cleanId.substringBeforeLast(':'), level)?.name()
        }
        id.isItem -> {
            SkyBlockItemsRepo.get(id.cleanId.uppercase(Locale.ROOT)) ?: return@runCatching null
            ItemUtils.displayItem(id).hoverName.stripped
        }
        else -> null
    } ?: return@runCatching null
    name.stripColor().trim().takeIf { bazaarGuideCommand(it, 1) != null }
}.getOrNull()

/** A fixed Bazaar search only; quantities are copied separately and never become command arguments. */
internal fun bazaarGuideCommand(itemName: String, quantity: Long): String? {
    if (quantity !in 1L..1_000_000L) return null
    var offset = 0
    while (offset < itemName.length) {
        val character = itemName[offset]
        if (character.isHighSurrogate() && (offset + 1 >= itemName.length || !itemName[offset + 1].isLowSurrogate())) return null
        if (character.isLowSurrogate()) return null
        val point = itemName.codePointAt(offset)
        if (point == '/'.code || point == '\\'.code) return null
        when (Character.getType(point)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> return null
        }
        offset += Character.charCount(point)
    }
    val name = itemName.trim()
    if (name.length !in 1..120 || name.codePoints().noneMatch { Character.isLetterOrDigit(it) }) return null
    return "bz $name"
}

/**
 * In-game names and icons for Bazaar product IDs, on the client thread. Successful lookups are cached; misses
 * are retried because the item repository may still be loading.
 */
internal object BazaarNames {
    private var index = emptyMap<String, SkyBlockId>()
    private var indexedSize = -1
    private val names = HashMap<String, String>()
    private val icons = HashMap<String, ItemStack>()

    fun itemId(product: String): SkyBlockId? {
        val ids = SimpleItemAPI.getAllIds()
        if (ids.size != indexedSize) {
            index = ids.associateBy { it.bazaarId.uppercase(Locale.ROOT) }
            indexedSize = ids.size
        }
        return index[product.uppercase(Locale.ROOT)]
    }

    private val misses = HashMap<String, Long>()

    fun name(product: String): String {
        names[product]?.let { return it }
        val now = System.currentTimeMillis()
        if ((misses[product] ?: 0L) > now) return bazaarFallbackName(product)
        val found = bazaarGuideName(product)
        if (found == null) {
            if (misses.size >= 4096) misses.clear()
            misses[product] = now + 60_000
            return bazaarFallbackName(product)
        }
        if (names.size < 4096) names[product] = found
        misses.remove(product)
        return found
    }

    fun names(products: Iterable<String>): Map<String, String> = products.distinct().associateWith(::name)

    fun icon(product: String): ItemStack? = icons[product] ?: runCatching {
        itemId(product)?.let(ItemUtils::displayItem)?.takeUnless { it.isEmpty }
    }.getOrNull()?.also { if (icons.size < 512) icons[product] = it }
}
