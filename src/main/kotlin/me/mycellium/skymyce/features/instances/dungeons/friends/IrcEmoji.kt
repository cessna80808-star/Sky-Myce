package me.mycellium.skymyce.features.instances.dungeons.friends

/** One bundled Twemoji glyph: [text] is the fully qualified Unicode sent on the wire, [glyph] only paints it. */
internal data class IrcEmoji(val key: String, val text: String, val glyph: String, val names: List<String>, val category: Int) {
    val toned get() = key.split('-').any { it.length == 5 && it.startsWith("1f3f") && it.last() in 'b'..'f' }
}

internal val IRC_EMOJI_CATEGORIES = listOf("Smileys", "People & more", "Nature & objects", "Travel", "Symbols", "Flags")

/** Common Discord names that differ from the Unicode character names; everything else is derived offline. */
private val ircEmojiAliases = mapOf(
    "1f44d" to listOf("thumbsup", "+1"), "1f44e" to listOf("thumbsdown", "-1"), "1f602" to listOf("joy"),
    "2764" to listOf("heart"), "1f62d" to listOf("sob"), "1f64f" to listOf("pray"), "1f604" to listOf("smile"),
    "1f603" to listOf("smiley"), "1f601" to listOf("grin"), "1f605" to listOf("sweat_smile"), "1f923" to listOf("rofl"),
    "1f609" to listOf("wink"), "1f60a" to listOf("blush"), "1f60d" to listOf("heart_eyes"), "1f44f" to listOf("clap"),
    "1f389" to listOf("tada"), "1f4af" to listOf("100"), "1f44b" to listOf("wave"), "1f622" to listOf("cry"),
    "1f621" to listOf("rage"), "1f60e" to listOf("sunglasses"), "1f4aa" to listOf("muscle"), "1f4a9" to listOf("poop"),
    "1f926" to listOf("facepalm"), "2b50" to listOf("star"), "2705" to listOf("white_check_mark"), "274c" to listOf("x"),
    "1f642" to listOf("slight_smile"), "1f97a" to listOf("pleading_face"), "2620" to listOf("skull_crossbones"),
    "1f62e" to listOf("open_mouth"), "1f644" to listOf("rolling_eyes"), "1f60f" to listOf("smirk"),
    "1f618" to listOf("kissing_heart"), "1f6a8" to listOf("rotating_light"), "1f4b0" to listOf("moneybag"), "1f48e" to listOf("gem"),
)
private val keycapNames = mapOf('#' to "hash", '*' to "asterisk") +
    "zero one two three four five six seven eight nine".split(' ').mapIndexed { digit, name -> '0' + digit to name }

private fun ircSnake(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** Names for one index key: hand aliases, then the joined Unicode component names, then a shorter Discord-like form. */
internal fun ircEmojiNames(key: String): List<String> {
    val points = key.split('-').map { it.toInt(16) }
    val names = arrayListOf<String>()
    ircEmojiAliases[key]?.let(names::addAll)
    when {
        points.size == 2 && points[1] == 0x20E3 -> {
            val base = points[0].toChar()
            names += "keycap_" + (if (base.isDigit()) base.toString() else keycapNames.getValue(base))
            names += keycapNames.getValue(base)
        }
        points.size == 2 && points.all { it in 0x1F1E6..0x1F1FF } ->
            names += "flag_" + points.joinToString("") { ('a' + (it - 0x1F1E6)).toString() }
        points.size > 2 && points[0] == 0x1F3F4 && points.last() == 0xE007F && points.drop(1).dropLast(1).all { it in 0xE0020..0xE007E } ->
            names += "flag_" + points.drop(1).dropLast(1).joinToString("") { (it - 0xE0000).toChar().toString() }
        else -> {
            val primary = points.filter { it != 0x200D }.joinToString("_") { point ->
                if (point in 0x1F3FB..0x1F3FF) "tone${point - 0x1F3FA}"
                else Character.getName(point)?.let(::ircSnake) ?: "u${point.toString(16)}"
            }
            names += primary
            // "THUMBS UP SIGN" and "LOUDLY CRYING FACE" are typed as thumbs_up and loudly_crying.
            for (suffix in listOf("_sign", "_face")) if (primary.endsWith(suffix) && primary.length > suffix.length + 2)
                names += primary.removeSuffix(suffix)
        }
    }
    return names.distinct()
}

/** Default-text characters get U+FE0F so Discord and other clients show the emoji, not the text glyph. */
internal fun ircEmojiText(key: String): String {
    val points = key.split('-').map { it.toInt(16) }
    val text = StringBuilder()
    for ((index, point) in points.withIndex()) {
        text.appendCodePoint(point)
        val next = points.getOrNull(index + 1)
        if (Character.isEmoji(point) && !Character.isEmojiPresentation(point) && point !in 0x1F3FB..0x1F3FF &&
            (next == null || next !in 0x1F3FB..0x1F3FF)) text.append('️')
    }
    return text.toString()
}

internal fun ircEmojiCategory(key: String): Int {
    val points = key.split('-').map { it.toInt(16) }
    val first = points[0]
    return when {
        first in 0x1F1E6..0x1F1FF || first == 0x1F3F4 && points.size > 1 || first == 0x1F3F3 && points.size > 1 -> 5
        first in 0x1F600..0x1F64F -> 0
        first in 0x1F900..0x1F9FF || first in 0x1FA70..0x1FAFF -> 1
        first in 0x1F300..0x1F5FF -> 2
        first in 0x1F680..0x1F6FF -> 3
        else -> 4
    }
}

/** Built once from the bundled index; searching and lookup never touch the font or render state. */
internal class IrcEmojiCatalog(keys: List<String>) {
    val all = keys.mapIndexed { index, key ->
        IrcEmoji(key, ircEmojiText(key), (0xE000 + index).toChar().toString(), ircEmojiNames(key), ircEmojiCategory(key))
    }
    private val byKey = all.associateBy { it.key }
    private val byName = HashMap<String, IrcEmoji>().apply {
        // Hand aliases, then derived primary names, win over shortened forms when two emoji would share a name.
        for (emoji in all) ircEmojiAliases[emoji.key].orEmpty().forEach { putIfAbsent(it, emoji) }
        for (emoji in all) putIfAbsent(emoji.names[ircEmojiAliases[emoji.key].orEmpty().size.coerceAtMost(emoji.names.lastIndex)], emoji)
        for (emoji in all) emoji.names.forEach { putIfAbsent(it, emoji) }
    }
    private val byText = all.associateBy { it.text }
    val grid = all.filter { !it.toned }.sortedBy { it.category }
    fun key(key: String) = byKey[key]
    fun text(text: String) = byText[text]
    fun name(name: String) = byName[name.lowercase()]
    fun shortcode(name: String) = name(name)?.text
    /** Received server chat: only exact, already lowercase names become glyphs. */
    fun exactGlyph(name: String) = byName[name]?.glyph

    /** The descriptive Unicode-derived name ("thumbs_up", not "+1") that reads back as this same emoji. */
    private val descriptive = all.associateWith { emoji ->
        val derived = emoji.names.drop(ircEmojiAliases[emoji.key].orEmpty().size)
        val primary = derived.firstOrNull()
        val preferred = listOfNotNull(primary?.removeSuffix("_sign")?.takeIf { it != primary }, primary) + emoji.names
        preferred.firstOrNull { byName[it] === emoji }
    }
    fun descriptiveName(emoji: IrcEmoji) = descriptive[emoji]

    /** `:name:` for one grapheme sent to a server; text-style symbols such as © or ♥ without U+FE0F stay as typed. */
    fun serverShortcode(cluster: String): String? {
        val points = cluster.codePoints().toArray()
        if (points.isEmpty() || 0xFE0E in points || points.size == 1 && !Character.isEmojiPresentation(points[0])) return null
        val emoji = byKey[points.filter { it != 0xFE0F }.joinToString("-") { it.toString(16) }] ?: return null
        return descriptive[emoji]?.let { ":$it:" }
    }

    /** Name matches ranked exact, prefix, word prefix, substring; skin-tone variants only when asked for. */
    fun search(query: String, limit: Int): List<Pair<IrcEmoji, String>> {
        val wanted = query.trim().lowercase().replace(' ', '_')
        if (wanted.isEmpty()) return emptyList()
        val tones = "tone" in wanted
        val ranked = arrayListOf<Triple<Int, Int, Pair<IrcEmoji, String>>>()
        for ((order, emoji) in all.withIndex()) {
            if (emoji.toned && !tones) continue
            var best: Pair<Int, String>? = null
            for (name in emoji.names) {
                val rank = when {
                    name == wanted -> 0
                    name.startsWith(wanted) -> 1
                    name.contains("_$wanted") -> 2
                    name.contains(wanted) -> 3
                    else -> continue
                }
                if (best == null || rank < best.first) best = rank to name
            }
            best?.let { ranked += Triple(it.first, order, emoji to it.second) }
        }
        return ranked.sortedWith(compareBy({ it.first }, { it.third.second.length }, { it.second })).take(limit).map { it.third }
    }
}

/** Joined ZWJ names such as kiss_woman_tone1_heavy_black_heart_kiss_mark_man_tone2 run long. */
internal const val IRC_SHORTCODE_MAX = 120
private fun shortcodeChar(char: Char) = char.isLetterOrDigit() && char.code < 128 || char == '_' || char == '+' || char == '-'
private fun wordChar(char: Char?) = char != null && char.isLetterOrDigit()

/** Links keep their literal colons; e.g. https://host/:fire: is never rewritten. */
private fun insideLink(text: String, index: Int): Boolean {
    val start = text.lastIndexOfAny(charArrayOf(' ', '\t', '\n'), index).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOfAny(charArrayOf(' ', '\t', '\n'), index).let { if (it < 0) text.length else it }
    val token = text.substring(start, end).lowercase()
    return "://" in token || token.startsWith("www.")
}

/** The emoji for a complete `:name:` occupying [start, end), or null if it is unknown or glued to a word, time or link. */
internal fun ircShortcodeAt(text: String, start: Int, end: Int, lookup: (String) -> String?): String? {
    if (start < 0 || end > text.length || end - start < 3 || text[start] != ':' || text[end - 1] != ':') return null
    val name = text.substring(start + 1, end - 1)
    if (name.length > IRC_SHORTCODE_MAX || !name.all(::shortcodeChar)) return null
    if (wordChar(text.getOrNull(start - 1)) || wordChar(text.getOrNull(end)) || insideLink(text, start)) return null
    return lookup(name)
}

/** Replace every known `:name:`; unknown codes, times like 12:30:45 and links stay exactly as typed. */
internal fun ircReplaceShortcodes(text: String, lookup: (String) -> String?): String =
    ircShortcodeRuns(text, lookup)?.joinToString("") { it.first } ?: text

internal data class IrcTextEdit(val text: String, val cursor: Int)

/** Replace [start, end) with [insert]; the cursor lands right after the inserted text. */
internal fun ircReplaceRange(text: String, start: Int, end: Int, insert: String) =
    IrcTextEdit(text.substring(0, start) + insert + text.substring(end), start + insert.length)

/** The `:name:` that ends at [cursor] (just typed closing colon) as its start index and emoji. */
internal fun ircShortcodeBefore(text: String, cursor: Int, lookup: (String) -> String?): Pair<Int, String>? {
    if (cursor < 3 || cursor > text.length || text[cursor - 1] != ':') return null
    var start = cursor - 2
    while (start >= 0 && cursor - start <= IRC_SHORTCODE_MAX + 2 && shortcodeChar(text[start])) start--
    if (start < 0 || text[start] != ':') return null
    return ircShortcodeAt(text, start, cursor, lookup)?.let { start to it }
}

/** The partial `:que` being typed at [cursor] (two or more name characters) as its colon index and query. */
internal fun ircShortcodeQuery(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor > text.length || cursor < text.length && shortcodeChar(text[cursor])) return null
    var start = cursor - 1
    while (start >= 0 && cursor - start <= IRC_SHORTCODE_MAX + 1 && shortcodeChar(text[start])) start--
    if (start < 0 || text[start] != ':' || cursor - start - 1 < 2) return null
    if (wordChar(text.getOrNull(start - 1)) || insideLink(text, start)) return null
    return start to text.substring(start + 1, cursor)
}

/** Most recent first, no duplicates, bounded. */
internal fun ircRecentEmoji(recent: List<String>, picked: String, limit: Int = IRC_RECENT_EMOJI) =
    (listOf(picked) + recent.filter { it != picked }).take(limit)
internal const val IRC_RECENT_EMOJI = 16

internal fun parseIrcRecentEmoji(json: com.google.gson.JsonElement?): List<String> {
    if (json == null) return emptyList()
    val root = json.asJsonObject
    require(root.get("version")?.asInt == 1)
    return root.getAsJsonArray("recent").map { it.asString }.filter { it.length in 1..64 }.distinct().take(IRC_RECENT_EMOJI)
}

/** Chat commands whose text after the command is free prose; anything else (e.g. /warp, /party invite) keeps literal arguments. */
private val freeTextCommands = setOf("pc", "pchat", "gc", "gchat", "ac", "achat", "oc", "ochat", "cc", "coopchat",
    "msg", "w", "whisper", "tell", "message", "r", "reply")
internal fun ircFreeTextCommand(command: String): Boolean {
    val parts = command.trimStart().split(' ', limit = 3)
    val first = parts[0].lowercase()
    return first in freeTextCommands || first in setOf("party", "p", "guild", "g") && parts.getOrNull(1)?.lowercase() == "chat"
}
/** Composer text where emoji input and :shortcode: completion apply: prose or a free-text chat command. */
internal fun ircEmojiProse(text: String) = !text.startsWith("/") || ircFreeTextCommand(text.substring(1))

/**
 * Server-bound chat: each known emoji becomes its descriptive `:name:`, left to right, while the whole message stays within
 * [limit] characters. Once a replacement would overflow, that emoji and every later one stay as typed, so the message
 * remains sendable and no text is ever dropped. Text already over [limit] is returned unchanged.
 */
internal fun ircEmojiToShortcodes(text: String, limit: Int, shortcode: (String) -> String?): String {
    val output = StringBuilder()
    var copied = 0
    for (match in ircGrapheme.findAll(text)) {
        val cluster = match.value
        if (cluster.all { it.code < 0x80 }) continue
        val replacement = shortcode(cluster) ?: continue
        val length = output.length + (match.range.first - copied) + replacement.length + (text.length - match.range.last - 1)
        if (length > limit) break
        output.append(text, copied, match.range.first).append(replacement)
        copied = match.range.last + 1
    }
    return if (copied == 0) text else output.append(text, copied, text.length).toString()
}

/** Received text split into plain runs and emoji glyphs for exact known `:name:` tokens; null when nothing matched. */
internal fun ircShortcodeRuns(text: String, glyph: (String) -> String?): List<Pair<String, Boolean>>? {
    if (':' !in text) return null
    val runs = arrayListOf<Pair<String, Boolean>>()
    var copied = 0
    var index = text.indexOf(':')
    while (index >= 0) {
        var end = index + 1
        while (end < text.length && end - index <= IRC_SHORTCODE_MAX + 1 && shortcodeChar(text[end])) end++
        val emoji = if (end < text.length && text[end] == ':') ircShortcodeAt(text, index, end + 1, glyph) else null
        if (emoji != null) {
            if (index > copied) runs += text.substring(copied, index) to false
            runs += emoji to true
            copied = end + 1
            index = text.indexOf(':', copied)
        } else index = text.indexOf(':', index + 1)
    }
    if (runs.isEmpty()) return null
    if (copied < text.length) runs += text.substring(copied) to false
    return runs
}
