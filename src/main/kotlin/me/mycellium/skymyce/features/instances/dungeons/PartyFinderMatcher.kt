package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.config.instances.dungeons.PartyListing
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass

/** Whole time tokens only: minutes, m:ss, compact mss/mmss, or minutes with m/min. */
private val PARTY_PB_PATTERN = Regex("""(?<![\w:./+\-])(?:([0-9]{1,2}):([0-5][0-9])|([0-9]{1,2})([0-5][0-9])|([0-9]{1,2})\s*(?:min|m)|([0-9]{1,2}))(?![\w:./+\-])""", RegexOption.IGNORE_CASE)
private val NON_TIME_BEFORE = Regex("""(?:cata(?:combs)?|class|level|lvl|floor|f|m|mp|secrets|runs)\s*[:=]?\s*$""", RegexOption.IGNORE_CASE)
private val NON_TIME_AFTER = Regex("""^\s*(?:cata|level|lvl|mp|secrets|runs|comps|players|tanks?|healers?|mages?|archers?|berserks?|berserkers?)\b""", RegexOption.IGNORE_CASE)
private val TIME_LABEL = Regex("""\b(?:sub|pb)\s*[:<=>]?\s*$""", RegexOption.IGNORE_CASE)
private val TOUCHING_LABEL = Regex("""\b(sub|pb)(?=\d)""", RegexOption.IGNORE_CASE)
private val FORMATTING = Regex("§.")
private val OPEN_CLASSES_HEADER = Regex("""^(?:Open|Available|Needed) Classes?:""", RegexOption.IGNORE_CASE)

fun parsePartyPbSeconds(note: String?): Int? {
    val text = note?.take(500)?.replace(FORMATTING, "") ?: return null
    // A labelled prefix can touch its number, e.g. "sub5" or "pb510".
    val normalized = text.replace(TOUCHING_LABEL, "$1 ")
    return PARTY_PB_PATTERN.findAll(normalized).mapNotNull { match ->
        val before = normalized.take(match.range.first)
        if (NON_TIME_BEFORE.containsMatchIn(before) ||
            (match.groupValues[6].isNotEmpty() && !TIME_LABEL.containsMatchIn(before) &&
                NON_TIME_AFTER.containsMatchIn(normalized.substring(match.range.last + 1)))) return@mapNotNull null
        val groups = match.groupValues
        val seconds = when {
            groups[1].isNotEmpty() -> groups[1].toInt() * 60 + groups[2].toInt()
            groups[3].isNotEmpty() -> groups[3].toInt() * 60 + groups[4].toInt()
            else -> (groups[5].ifEmpty { groups[6] }).toInt() * 60
        }
        seconds.takeIf { it > 0 }
    }.minOrNull()
}

/** Null means the lore did not establish class availability. Empty means explicitly no slots. */
fun partyOpenClasses(lines: List<String>, occupied: Collection<DungeonClass>): Set<DungeonClass>? {
    val clean = lines.map { it.replace(FORMATTING, "").trim() }
    val explicitIndex = clean.indexOfFirst { OPEN_CLASSES_HEADER.containsMatchIn(it) }
    if (explicitIndex >= 0) {
        val value = clean[explicitIndex].substringAfter(':').trim().ifEmpty {
            clean.drop(explicitIndex + 1).takeWhile { it.isNotEmpty() && ':' !in it }.take(5).joinToString(" ")
        }
        if (value.equals("none", true) || value.equals("full", true)) return emptySet()
        if (value.equals("any", true) || value.equals("all", true)) return DungeonClass.entries.toSet()
        val parsed = DungeonClass.entries.filter { clazz ->
            val name = if (clazz == DungeonClass.BERSERKER) "berserk(?:er)?" else clazz.name
            Regex("""\b$name\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)
        }.toSet()
        return parsed.takeIf { it.isNotEmpty() }
    }
    return occupied.takeIf { it.isNotEmpty() }?.let { DungeonClass.entries.toSet() - it.toSet() }
}

/** PBs remain milliseconds until comparison, so 5:10.001 cannot pass a 5:10 limit. */
fun partyMatches(listing: PartyListing, classes: Set<DungeonClass>, pbMillis: Long?, checkClass: Boolean, checkPb: Boolean): Boolean {
    if (checkClass && classes.isNotEmpty() && listing.openClasses?.none { it in classes } == true) return false
    val requirement = if (checkPb) parsePartyPbSeconds(listing.note) else null
    return requirement == null || (pbMillis != null && pbMillis > 0 && pbMillis <= requirement.toLong() * 1000)
}
