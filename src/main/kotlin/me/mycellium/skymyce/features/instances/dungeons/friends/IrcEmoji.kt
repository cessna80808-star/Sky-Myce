package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Optional
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.ui.AutoGraphics
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.utils.Scheduling

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

/** Hypixel's chat limit; vanilla's chat box already enforces it, so emoji names may only fill the remaining room. */
internal const val SERVER_CHAT_LIMIT = 256

/** Received server chat with exact `:name:` tokens painted as emoji; null leaves the original component untouched. */
internal fun ircShortcodeComponent(component: Component, glyph: (String) -> String?): Component? {
    val output = Component.empty()
    var changed = false
    component.visit({ style: Style, text: String ->
        val runs = ircShortcodeRuns(text, glyph)
        if (runs == null) output.append(Component.literal(text).withStyle(style))
        else {
            changed = true
            // Click and hover events stay on the glyph; only the font and tint change so the emoji keeps its colours.
            for ((run, emoji) in runs) output.append(Component.literal(run).withStyle(if (emoji) style.withFont(ircEmojiStyle.font)
                .withColor(0xFFFFFF).withBold(false).withItalic(false).withObfuscated(false) else style))
        }
        Optional.empty<Unit>()
    }, Style.EMPTY)
    return if (changed) output else null
}

/** Shared emoji names and recents; normal server chat sends readable `:names:` and shows received ones as emoji. */
object IrcEmojiChat : SkyMyceModule() {
    @Volatile internal var catalog: IrcEmojiCatalog? = null
        private set
    /** Fully qualified emoji text, newest first. */
    internal var recent = emptyList<String>()
        private set
    internal var revision = 0
        private set
    private var loaded = false
    private var edited = false
    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("irc_emoji_recent.json"), 4096) }
    private val writer by lazy {
        StateWriter<List<String>>({ value ->
            file.write(JsonObject().apply { addProperty("version", 1); add("recent", JsonArray().apply { value.forEach(::add) }) })
        }, { work -> Scheduling.schedule(500.milliseconds) { work() } },
            { SkyMyce.logger.warn("Could not save recent emoji; previous file retained") }).also { writer ->
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Recent-Emoji"))
        }
    }

    override fun init() {
        // Character names for ~4000 sequences: built once, off the render thread.
        Scheduling.schedule(0.milliseconds) {
            val built = runCatching { IrcEmojiCatalog(ircEmojiKeys) }
                .onFailure { SkyMyce.logger.warn("Emoji names unavailable; :shortcodes: and the emoji picker are disabled", it) }.getOrNull()
            val saved = runCatching { parseIrcRecentEmoji(file.read()) }.getOrElse { file.preserveOriginal(); emptyList() }
            MC.instance.execute {
                catalog = built
                if (!edited) recent = saved.filter { built?.text(it) != null }
                loaded = true
                revision++
                if (edited) writer.submit(recent)
            }
        }
        // IRC and relay messages are consumed before these modify hooks, so they keep real Unicode.
        ClientSendMessageEvents.MODIFY_CHAT.register { serverText(it, SERVER_CHAT_LIMIT) }
        ClientSendMessageEvents.MODIFY_COMMAND.register { command ->
            // The command arrives without "/", which still counts toward the chat box limit.
            if (ircFreeTextCommand(command)) serverText(command, SERVER_CHAT_LIMIT - 1) else command
        }
    }

    internal fun pick(text: String) {
        recent = ircRecentEmoji(recent, text)
        revision++
        edited = true
        if (loaded) writer.submit(recent)
    }

    /** IRC keeps real Unicode: pasted or untouched known `:names:` become the emoji when sent. */
    internal fun ircText(text: String): String = catalog?.let { ircReplaceShortcodes(text, it::shortcode) } ?: text

    private fun serverText(text: String, limit: Int): String {
        val names = catalog ?: return text
        return if (GlobalChatConfig.emojiInNormalChat) ircEmojiToShortcodes(text, limit, names::serverShortcode) else text
    }

    @Subscription
    fun onChat(event: ChatReceivedEvent.Post) {
        // IRC history is added through the same chat path but already paints real emoji itself.
        if (!GlobalChatConfig.emojiInNormalChat || IrcChatRendering.addingMessage() || ':' !in event.text) return
        val names = catalog ?: return
        ircShortcodeComponent(event.component, names::exactGlyph)?.let { event.component = it }
    }
}

internal val ircEmojiStyle = Style.EMPTY.withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath("skymyce", "irc_emoji")))
    .withColor(0xFFFFFF).withBold(false).withItalic(false)

/** Raw UTF-16 indices remain owned by EditBox; one joined emoji occupies one displayed glyph. */
class IrcEmojiInputLayout internal constructor(val value: String) {
    private data class Run(val start: Int, val end: Int, val shown: String, val emoji: Boolean)
    private val runs = buildList {
        var start = 0
        for (part in ircReadableText(value)) {
            val raw = part.original ?: part.text
            add(Run(start, start + raw.length, if (part.emoji) part.text else raw, part.emoji))
            start += raw.length
        }
    }
    val hasEmoji = runs.any { it.emoji }

    private fun boundary(index: Int): Int {
        val safe = index.coerceIn(0, value.length)
        return if (safe > 0 && safe < value.length && value[safe].isLowSurrogate() && value[safe - 1].isHighSurrogate()) safe - 1 else safe
    }

    fun floor(index: Int): Int {
        val safe = boundary(index)
        return runs.firstOrNull { it.emoji && safe in it.start + 1 until it.end }?.start ?: safe
    }

    fun next(index: Int): Int {
        val safe = boundary(index)
        if (safe == value.length) return safe
        return runs.firstOrNull { it.emoji && safe in it.start until it.end }?.end ?: value.offsetByCodePoints(safe, 1)
    }

    internal fun position(index: Int, measure: (String, Boolean) -> Int): Int {
        val safe = boundary(index)
        var width = 0
        for (run in runs) {
            if (safe <= run.start) break
            val full = measure(run.shown, run.emoji)
            if (safe >= run.end) width += full
            else {
                width += if (run.emoji) (full * value.codePointCount(run.start, safe).toDouble() / value.codePointCount(run.start, run.end)).roundToInt()
                    else measure(value.substring(run.start, safe), false)
                break
            }
        }
        return width
    }

    internal fun index(offset: Int, measure: (String, Boolean) -> Int): Int {
        if (offset <= 0) return 0
        var left = 0
        for (run in runs) {
            val right = left + measure(run.shown, run.emoji)
            if (offset < right) {
                if (run.emoji) return if (offset * 2 < left + right) run.start else run.end
                var raw = run.start
                var previous = left
                while (raw < run.end) {
                    val end = value.offsetByCodePoints(raw, 1)
                    val next = left + measure(value.substring(run.start, end), false)
                    if (offset * 2 < previous + next) return raw
                    previous = next; raw = end
                }
                return run.end
            }
            left = right
        }
        return value.length
    }

    internal fun scroll(index: Int, current: Int, available: Int, measure: (String, Boolean) -> Int): Int {
        val safe = boundary(index)
        var start = floor(minOf(current, safe))
        val width = available.coerceAtLeast(1)
        val caret = position(safe, measure)
        while (caret - position(start, measure) > width) {
            val next = next(start)
            if (next <= start || next > safe) break
            start = next
        }
        val end = position(value.length, measure)
        while (start > 0) {
            val previous = floor(value.offsetByCodePoints(start, -1))
            if (end - position(previous, measure) > width) break
            start = previous
        }
        return start
    }

    fun position(index: Int, font: Font): Int = position(index) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, ircEmojiStyle)) else font.width(text)
    }

    fun index(offset: Int, font: Font): Int = index(offset) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, ircEmojiStyle)) else font.width(text)
    }

    fun scroll(index: Int, current: Int, available: Int, font: Font): Int = scroll(index, current, available) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, ircEmojiStyle)) else font.width(text)
    }

    fun display(source: FormattedCharSequence): FormattedCharSequence {
        val text = StringBuilder()
        val styles = arrayListOf<Style>()
        source.accept { _, style, code ->
            text.appendCodePoint(code); repeat(Character.charCount(code)) { styles.add(style) }; true
        }
        if (text.toString() != value) return display(FormattedCharSequence.forward(value, Style.EMPTY))
        return FormattedCharSequence { sink ->
            var complete = true
            for (run in runs) {
                if (!complete) break
                if (run.emoji) complete = sink.accept(run.start, ircEmojiStyle, run.shown[0].code)
                else {
                    var index = run.start
                    while (complete && index < run.end) {
                        val code = value.codePointAt(index)
                        complete = sink.accept(index, styles[index], code)
                        index += Character.charCount(code)
                    }
                }
            }
            complete
        }
    }
}

object IrcEmojiInput {
    private var current = IrcEmojiInputLayout("")
    @JvmStatic fun layout(value: String): IrcEmojiInputLayout {
        if (current.value != value) current = IrcEmojiInputLayout(value)
        return current
    }
}

/** Emoji button, popup grid and `:shortcode:` suggestions inside the native chat screen; the chat box keeps real text. */
internal object IrcEmojiPicker {
    private const val COLUMNS = 9
    private const val CELL = 20
    private const val PAD = 5
    private const val HEADER = 12
    private const val FIELD = 14
    private const val TABS = 18
    private const val GRID_ROWS = 6
    private const val WIDTH = PAD * 2 + COLUMNS * CELL
    private const val GRID_TOP = PAD + FIELD + 4 + TABS + 2
    private const val HEIGHT = GRID_TOP + GRID_ROWS * CELL + PAD
    private const val SUGGESTIONS = 8
    private const val INPUT_LIMIT = 256 // ChatScreen's input maximum; never insert half of a joined emoji.

    private class Row(val top: Int, val header: String?, val items: List<IrcEmoji>)
    private class Tab(val name: String, val glyph: String, val top: Int)

    private var open = false
    private var searchFocused = false
    private var scroll = 0
    private var rows = emptyList<Row>()
    private var tabs = emptyList<Tab>()
    private var contentHeight = 0
    private var results = 0
    private var built: Triple<IrcEmojiCatalog, String, Int>? = null
    private var openRevision = 0 // Recents stay put while open, so repeated clicks keep hitting the same cell.
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    private val search by lazy {
        EditBox(MC.instance.font, 0, 0, WIDTH - PAD * 2 - 8, 10, Component.literal("Search emoji")).apply {
            setBordered(false); setMaxLength(48); setHint(Component.literal("Search emoji"))
            setResponder { scroll = 0 }
        }
    }
    private val buttonGlyph by lazy { ircReadableText("🙂").first().text }

    private var lastValue: String? = null
    private var lastCursor = -1
    private var suggestionKey: String? = null
    private var dismissed: String? = null
    private var suggestionStart = 0
    private var suggestionWidth = 0
    private var suggestions = emptyList<Pair<IrcEmoji, String>>()
    private var selected = 0

    /** IRC always; normal chat only while its toggle is on. Otherwise the chat screen is left exactly as vanilla. */
    private fun active() = RelayMessages.selectedIrcComposer() || GlobalChatConfig.emojiInNormalChat
    private fun input(screen: ChatScreen) = (screen as IrcChatScreenAccessor).`skymyce$getInput`()
    private fun buttonBounds(screen: ChatScreen) = IrcControlBounds(screen.width - 15, screen.height - 14, 13, 12)
    private fun bounds(screen: ChatScreen): IrcControlBounds {
        val width = minOf(WIDTH, screen.width - 4)
        val height = minOf(HEIGHT, screen.height - 18).coerceAtLeast(GRID_TOP + CELL + PAD)
        return IrcControlBounds(screen.width - 2 - width, screen.height - 16 - height, width, height)
    }

    /** The button sits inside vanilla's input background; the field only gives up its rightmost 14 pixels. */
    fun layout(screen: ChatScreen) {
        val input = input(screen)
        val wide = screen.width - 4
        if (active()) { if (input.width != wide - 14) input.width = wide - 14 }
        else if (input.width == wide - 14) input.width = wide
    }

    fun clear() {
        open = false; searchFocused = false; scroll = 0
        search.value = ""; search.isFocused = false
        lastValue = null; suggestionKey = null; dismissed = null; suggestions = emptyList()
        canvas.close(); texture.close()
    }
    private fun close() { open = false; searchFocused = false; search.isFocused = false; canvas.close(); texture.close() }

    private fun rebuild(catalog: IrcEmojiCatalog) {
        val query = search.value.trim()
        val key = Triple(catalog, query, openRevision)
        if (key == built) return
        built = key
        val sections = arrayListOf<Pair<String, List<IrcEmoji>>>()
        if (query.isEmpty()) {
            IrcEmojiChat.recent.mapNotNull(catalog::text).takeIf { it.isNotEmpty() }?.let { sections += "Recent" to it }
            val grouped = catalog.grid.groupBy { it.category }
            IRC_EMOJI_CATEGORIES.forEachIndexed { index, name -> grouped[index]?.let { sections += name to it } }
        } else sections += "Results" to catalog.search(query, 360).map { it.first }
        results = sections.sumOf { it.second.size }
        val nextRows = arrayListOf<Row>()
        val nextTabs = arrayListOf<Tab>()
        var top = 0
        for ((name, items) in sections) {
            nextTabs += Tab(name, items.first().glyph, top)
            nextRows += Row(top, name, emptyList()); top += HEADER
            for (chunk in items.chunked(COLUMNS)) { nextRows += Row(top, null, chunk); top += CELL }
        }
        rows = nextRows; tabs = if (query.isEmpty()) nextTabs else emptyList(); contentHeight = top
    }

    private fun maxScroll(bounds: IrcControlBounds) = (contentHeight - (bounds.height - GRID_TOP - PAD)).coerceAtLeast(0)
    private fun cellAt(bounds: IrcControlBounds, x: Int, y: Int): IrcEmoji? {
        val localX = x - bounds.x - PAD
        val localY = y - bounds.y - GRID_TOP
        if (localX < 0 || localX >= COLUMNS * CELL || localY < 0 || localY >= bounds.height - GRID_TOP - PAD) return null
        val content = localY + scroll
        val row = rows.lastOrNull { it.top <= content } ?: return null
        return if (row.header == null && content < row.top + CELL) row.items.getOrNull(localX / CELL) else null
    }
    private fun tabAt(bounds: IrcControlBounds, x: Int, y: Int): Int? {
        val top = bounds.y + PAD + FIELD + 4
        if (y < top || y >= top + TABS || x < bounds.x + PAD) return null
        return ((x - bounds.x - PAD) / CELL).takeIf { it in tabs.indices }
    }

    private fun insert(input: EditBox, emoji: IrcEmoji) {
        if (input.value.length - input.highlighted.length + emoji.text.length > INPUT_LIMIT) return
        input.insertText(emoji.text)
        IrcEmojiChat.pick(emoji.text)
    }
    private fun replace(input: EditBox, start: Int, end: Int, emoji: IrcEmoji) {
        if (input.value.length - (end - start) + emoji.text.length > INPUT_LIMIT) return
        val edit = ircReplaceRange(input.value, start, end, emoji.text)
        input.value = edit.text
        input.moveCursorTo(edit.cursor, false) // After the emoji, not at the end of the message.
        IrcEmojiChat.pick(emoji.text)
    }

    private fun refreshSuggestions(input: EditBox) {
        val value = input.value
        val cursor = input.cursorPosition
        if (value === lastValue && cursor == lastCursor) return
        lastValue = value; lastCursor = cursor
        val catalog = IrcEmojiChat.catalog
        val query = if (catalog != null && ircEmojiProse(value)) ircShortcodeQuery(value, cursor) else null
        val key = query?.let { "${it.first}:${it.second}" }
        if (key == suggestionKey) return
        suggestionKey = key; selected = 0
        if (key != dismissed) dismissed = null
        suggestions = if (query == null || catalog == null) emptyList() else catalog.search(query.second, SUGGESTIONS)
        suggestionStart = query?.first ?: 0
        val font = MC.instance.font
        suggestionWidth = (suggestions.maxOfOrNull { font.width(":${it.second}:") } ?: 0) + 18
    }
    private fun suggesting() = suggestions.isNotEmpty() && dismissed != suggestionKey && !(open && searchFocused)
    private fun suggestionBounds(screen: ChatScreen, input: EditBox): IrcControlBounds {
        val height = suggestions.size * 12
        val x = (input.getScreenX(suggestionStart) - 1).coerceIn(2, (screen.width - suggestionWidth - 2).coerceAtLeast(2))
        return IrcControlBounds(x, screen.height - 14 - height, suggestionWidth, height)
    }
    private fun accept(input: EditBox, index: Int) {
        val emoji = suggestions.getOrNull(index)?.first ?: return
        replace(input, suggestionStart, input.cursorPosition, emoji)
    }

    fun hasInteractionAt(screen: ChatScreen, x: Int, y: Int): Boolean = active() && (buttonBounds(screen).contains(x, y) ||
        open && bounds(screen).contains(x, y) || suggesting() && suggestionBounds(screen, input(screen)).contains(x, y))

    /** True when the click belongs to the emoji controls and must not reach vanilla chat. */
    fun click(screen: ChatScreen, x: Int, y: Int): Boolean {
        if (!active()) return false
        val input = input(screen)
        if (suggesting()) {
            val list = suggestionBounds(screen, input)
            if (list.contains(x, y)) { accept(input, (y - list.y) / 12); return true }
        }
        if (buttonBounds(screen).contains(x, y)) {
            if (open) close() else { open = true; searchFocused = true; search.isFocused = true; openRevision = IrcEmojiChat.revision }
            return true
        }
        if (!open) return false
        val bounds = bounds(screen)
        if (!bounds.contains(x, y)) {
            // Clicking the chat field returns typing to chat and keeps the picker for more picks.
            if (y >= screen.height - 14) { searchFocused = false; search.isFocused = false } else close()
            return false
        }
        if (y < bounds.y + PAD + FIELD + 2) { searchFocused = true; search.isFocused = true; return true }
        tabAt(bounds, x, y)?.let { scroll = tabs[it].top.coerceAtMost(maxScroll(bounds)); return true }
        cellAt(bounds, x, y)?.let { insert(input, it) }
        return true
    }

    fun scroll(screen: ChatScreen, x: Int, y: Int, amount: Double): Boolean {
        if (!open || !active()) return false
        val bounds = bounds(screen)
        if (!bounds.contains(x, y)) return false
        scroll = (scroll - (amount * CELL).toInt()).coerceIn(0, maxScroll(bounds))
        return true
    }

    /** True when the key was used by the suggestions or the picker search. */
    fun key(screen: ChatScreen, event: KeyEvent): Boolean {
        if (!active()) return false
        val input = input(screen)
        refreshSuggestions(input)
        if (suggesting()) when (event.key()) {
            GLFW.GLFW_KEY_TAB -> { accept(input, selected); return true }
            GLFW.GLFW_KEY_UP -> { selected = (selected - 1 + suggestions.size) % suggestions.size; return true }
            GLFW.GLFW_KEY_DOWN -> { selected = (selected + 1) % suggestions.size; return true }
            GLFW.GLFW_KEY_ESCAPE -> { dismissed = suggestionKey; return true }
        }
        if (!open) return false
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        if (!searchFocused) return false
        when (event.key()) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                // An empty search lets Enter send the message as usual.
                if (search.value.isBlank()) { close(); return false }
                IrcEmojiChat.catalog?.let { rebuild(it) }
                rows.firstOrNull { it.header == null }?.items?.firstOrNull()?.let { insert(input, it) }
                return true
            }
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> return true
        }
        search.keyPressed(event)
        return true
    }

    fun charTyped(event: CharacterEvent): Boolean {
        if (!open || !searchFocused || !active()) return false
        search.charTyped(event)
        return true
    }

    /** Typing the closing colon of a known `:name:` turns it into the emoji, like Discord. */
    fun afterCharTyped(screen: ChatScreen, event: CharacterEvent) {
        if (event.codepoint() != ':'.code || !active()) return
        val catalog = IrcEmojiChat.catalog ?: return
        val input = input(screen)
        if (!ircEmojiProse(input.value)) return
        val cursor = input.cursorPosition
        val (start, text) = ircShortcodeBefore(input.value, cursor, catalog::shortcode) ?: return
        catalog.text(text)?.let { replace(input, start, cursor, it) }
    }

    private fun glyph(output: GuiGraphicsExtractor, glyph: String, x: Int, y: Int, scale: Float) {
        output.pose().pushMatrix()
        output.pose().translate(x.toFloat(), y.toFloat())
        output.pose().scale(scale, scale)
        output.text(MC.instance.font, FormattedCharSequence.forward(glyph, ircEmojiStyle), 0, 0, -1, false)
        output.pose().popMatrix()
    }

    fun render(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, vanillaSuggestions: Boolean) {
        layout(screen)
        if (!active()) { if (open || suggestions.isNotEmpty()) clear(); return }
        val font = MC.instance.font
        val input = input(screen)
        output.nextStratum()
        val button = buttonBounds(screen)
        if (open || button.contains(mouseX, mouseY)) output.fill(button.x, button.y, button.x + button.width, button.y + button.height, 0x40FFFFFF)
        glyph(output, buttonGlyph, button.x + 2, button.y + 2, 1f)
        if (button.contains(mouseX, mouseY) && !open) output.setTooltipForNextFrame(Component.literal("Emoji"), mouseX, mouseY)

        refreshSuggestions(input)
        if (!vanillaSuggestions && suggesting()) {
            val list = suggestionBounds(screen, input)
            output.fill(list.x, list.y, list.x + list.width, list.y + list.height, 0xD0000000.toInt())
            suggestions.forEachIndexed { index, (emoji, name) ->
                val y = list.y + index * 12
                if (list.contains(mouseX, mouseY) && (mouseY - list.y) / 12 == index) selected = index
                glyph(output, emoji.glyph, list.x + 2, y + 2, 1f)
                output.text(font, ":$name:", list.x + 14, y + 2, if (index == selected) 0xFFFFFF00.toInt() else 0xFFAAAAAA.toInt(), true)
            }
        }
        if (open) renderPopup(screen, output, mouseX, mouseY, delta)
    }

    private fun renderPopup(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val catalog = IrcEmojiChat.catalog
        val bounds = bounds(screen)
        val font = MC.instance.font
        catalog?.let(::rebuild)
        val gridHeight = bounds.height - GRID_TOP - PAD
        scroll = scroll.coerceIn(0, maxScroll(bounds))
        val visible = rows.filter { it.top + CELL > scroll && it.top < scroll + gridHeight }
        val hovered = cellAt(bounds, mouseX, mouseY)
        val hoveredTab = tabAt(bounds, mouseX, mouseY)
        val currentTab = tabs.indexOfLast { it.top <= scroll + 1 }

        if (texture.prepareFrame()) canvas.close()
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX - bounds.x, mouseY - bounds.y, bounds.width, bounds.height)
        MenuTheme.box(graphics, 0, 0, bounds.width, bounds.height, MenuTheme.SIDEBAR)
        MenuTheme.drawField(graphics, PAD, PAD, bounds.width - PAD * 2, FIELD, searchFocused)
        val tabTop = PAD + FIELD + 4
        if (catalog == null) graphics.text(font, "Loading emoji…", PAD + 2, tabTop + 5, MenuTheme.alpha(MenuTheme.MUTED), false)
        else if (tabs.isEmpty()) graphics.text(font, if (results == 0) "No emoji found" else "$results found", PAD + 2, tabTop + 5, MenuTheme.alpha(MenuTheme.MUTED), false)
        tabs.forEachIndexed { index, _ ->
            if (index == currentTab || index == hoveredTab)
                MenuTheme.rounded(graphics, PAD + index * CELL, tabTop, CELL - 2, TABS, if (index == currentTab) MenuTheme.CARD else MenuTheme.blend(MenuTheme.SIDEBAR, MenuTheme.CARD, 50), 3)
        }
        graphics.enableScissor(0, GRID_TOP, bounds.width, GRID_TOP + gridHeight)
        for (row in visible) {
            val y = GRID_TOP + row.top - scroll
            if (row.header != null) graphics.text(font, row.header, PAD + 2, y + 2, MenuTheme.alpha(MenuTheme.MUTED), false)
            else row.items.forEachIndexed { column, emoji ->
                if (emoji === hovered) MenuTheme.rounded(graphics, PAD + column * CELL, y, CELL, CELL, MenuTheme.CARD, 3)
            }
        }
        graphics.disableScissor()
        output.nextStratum()
        output.pose().pushMatrix()
        output.pose().translate(bounds.x.toFloat(), bounds.y.toFloat())
        val scale = minOf(AutoGraphics.quality.rasterScale, 2560.0 / screen.width.coerceAtLeast(1), 1440.0 / screen.height.coerceAtLeast(1))
        texture.render(output, canvas, bounds.width, bounds.height, scale)
        output.pose().popMatrix()

        output.nextStratum()
        search.x = bounds.x + PAD + 4
        search.y = bounds.y + PAD + 3
        search.width = bounds.width - PAD * 2 - 8
        search.setTextColor(MenuTheme.alpha(MenuTheme.TEXT))
        search.extractRenderState(output, mouseX, mouseY, delta)
        tabs.forEachIndexed { index, tab -> glyph(output, tab.glyph, bounds.x + PAD + index * CELL + 1, bounds.y + tabTop + 1, 2f) }
        output.enableScissor(bounds.x, bounds.y + GRID_TOP, bounds.x + bounds.width, bounds.y + GRID_TOP + gridHeight)
        for (row in visible) if (row.header == null) {
            val y = bounds.y + GRID_TOP + row.top - scroll
            row.items.forEachIndexed { column, emoji -> glyph(output, emoji.glyph, bounds.x + PAD + column * CELL + 2, y + 2, 2f) }
        }
        output.disableScissor()
        hovered?.let { emoji -> catalog?.descriptiveName(emoji)?.let { output.setTooltipForNextFrame(Component.literal(":$it:"), mouseX, mouseY) } }
        hoveredTab?.let { output.setTooltipForNextFrame(Component.literal(tabs[it].name), mouseX, mouseY) }
    }
}
