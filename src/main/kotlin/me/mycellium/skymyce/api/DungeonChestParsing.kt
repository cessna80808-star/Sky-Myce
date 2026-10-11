package me.mycellium.skymyce.api

private val chestFormatting = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
private val chestSpaces = Regex("[\\s\\u00a0]+")
private val chestCoins = Regex("(?:Cost: )?([0-9]{1,3}(?:,[0-9]{3})*|[0-9]+) Coins")
private val chestQuantity = Regex("[A-Za-z ]+ (?:Essence|Shard)(?: x([0-9]{1,3}(?:,[0-9]{3})*|[0-9]+))?")
private val chestEssence = Regex("(Wither|Undead|Dragon|Spider|Ice|Gold|Diamond|Crimson) Essence(?: x[0-9,]+)?")

/** Bounded aliases, rather than substring matching against unrelated inventories. */
internal fun normalizeChestText(value: String): String? {
    if (value.length > 256) return null
    val text = chestFormatting.replace(value, "")
    if (text.any { it.isISOControl() && !it.isWhitespace() } || '§' in text) return null
    return chestSpaces.replace(text, " ").trim()
}

internal fun dungeonChestControl(name: String): String? = normalizeChestText(name)?.takeIf {
    it == "Open Reward Chest" || it == "Reroll Chest"
}

internal fun dungeonChestCost(lore: List<String>): Long? {
    if (lore.size > 64) return null
    val values = lore.mapNotNull { raw ->
        val line = normalizeChestText(raw) ?: return null
        if (line.endsWith(" Coins") && chestCoins.matchEntire(line) == null) return null
        when (line) {
            "FREE", "Cost: FREE" -> 0L
            else -> chestCoins.matchEntire(line)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        }
    }.distinct()
    return values.singleOrNull()?.takeIf { it in 0..1_000_000_000_000L }
}

internal fun dungeonRewardQuantity(name: String, stackCount: Int): Int? {
    val text = normalizeChestText(name) ?: return null
    val match = chestQuantity.matchEntire(text)
    if (match == null && Regex("[A-Za-z ]+ (?:Essence|Shard)(?: .*)?").matches(text)) return null
    val quantity = if (match == null) stackCount else if (match.groupValues[1].isEmpty()) 1
        else match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
    return quantity.takeIf { it in 1..100000 }
}

internal fun dungeonEssenceId(name: String): String? = normalizeChestText(name)?.let {
    chestEssence.matchEntire(it)?.groupValues?.get(1)?.uppercase()?.let { type -> "ESSENCE_$type" }
}

internal fun dungeonRewardHeader(text: String): DungeonChest? = normalizeChestText(text)?.let { value ->
    DungeonChest.entries.firstOrNull { value == "${it.name} CHEST REWARDS" }
}
