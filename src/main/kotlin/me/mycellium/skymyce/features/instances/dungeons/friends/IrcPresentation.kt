package me.mycellium.skymyce.features.instances.dungeons.friends

import java.net.URI
import com.google.gson.JsonParser

internal val ircGrapheme = Regex("\\X")
private val customIrcEmoji = Regex("<a?:([A-Za-z0-9_]{1,32}):([0-9]{17,20})>")
private val discordAttachmentPath = Regex("/attachments/[0-9]{1,20}/[0-9]{1,20}/[^/]+")
internal val IRC_REACTION_NAMES = listOf("Like", "Love", "Laugh", "Celebrate", "Eyes", "Fire")
internal data class IrcReadableText(val text: String, val original: String? = null, val emoji: Boolean = false)

/** Twemoji code point keys (FE0F removed) in glyph order: key N paints as U+E000 + N. */
internal val ircEmojiKeys: List<String> by lazy {
    // Kept outside font/ so Minecraft's font loader does not parse it as a font definition.
    val resource = "/assets/skymyce/irc/emoji_index.json"
    requireNotNull(IrcReadableText::class.java.getResourceAsStream(resource)).bufferedReader().use { reader ->
        JsonParser.parseReader(reader).asJsonArray.map { it.asString }
    }
}
private val ircEmojiGlyphs by lazy { ircEmojiKeys.withIndex().associate { (index, key) -> key to (0xE000 + index).toChar().toString() } }

/** One bundled bitmap glyph per joined emoji; only presentation changes, never the wire text. */
internal fun ircReadableText(text: String): List<IrcReadableText> {
    val output = arrayListOf<IrcReadableText>()
    fun unicode(value: String) {
        for (match in ircGrapheme.findAll(value)) {
            val cluster = match.value
            if (cluster.all { it.code < 128 }) { output.add(IrcReadableText(cluster)); continue }
            val points = cluster.codePoints().toArray()
            val key = points.filter { it != 0xFE0F }.joinToString("-") { it.toString(16) }
            val glyph = if (0xFE0E in points) null else ircEmojiGlyphs[key]
            if (glyph != null) output.add(IrcReadableText(glyph, cluster, emoji = true))
            else if (0xFE0E in points || points.none { it in 0x1F000..0x1FAFF || it == 0x200D || it == 0xFE0F }) output.add(IrcReadableText(cluster))
            else {
                output.add(IrcReadableText("[emoji]", cluster))
            }
        }
    }
    var start = 0
    for (match in customIrcEmoji.findAll(text)) {
        unicode(text.substring(start, match.range.first))
        output.add(IrcReadableText(":${match.groupValues[1]}:", match.value))
        start = match.range.last + 1
    }
    unicode(text.substring(start))
    val merged = arrayListOf<IrcReadableText>()
    for (part in output) {
        if (part.original == null && merged.lastOrNull()?.original == null && merged.isNotEmpty())
            merged[merged.lastIndex] = IrcReadableText(merged.last().text + part.text)
        else merged.add(part)
    }
    return merged
}

/** Truncate/wrap at an extended grapheme boundary, including skin tones, flags and ZWJ families. */
internal fun ircGraphemeEnd(text: String, start: Int, limit: Int): Int {
    if (limit >= text.length) return text.length
    var end = start
    val matcher = ircGrapheme.toPattern().matcher(text).region(start, text.length)
    while (matcher.find() && matcher.end() <= limit) end = matcher.end()
    return end
}

/** Identity ignores only Discord attachment signatures; fetch/click always retains the original URI. */
internal fun ircMediaIdentity(uri: URI): String {
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase() ?: return uri.toString()
    if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return uri.toString()
    val port = if (uri.port == -1 || scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80) "" else ":${uri.port}"
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    if (scheme != "https" || host !in setOf("cdn.discordapp.com", "media.discordapp.net") || port.isNotEmpty() ||
        !path.matches(discordAttachmentPath))
        return "$scheme://$host$port$path" + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    val query = uri.rawQuery?.split('&')?.filterNot { it.substringBefore('=') in setOf("ex", "is", "hm") }?.joinToString("&")?.takeIf { it.isNotEmpty() }
    return "https://cdn.discordapp.com$path" + (query?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
}

internal data class IrcPresentationSpan(val text: String, val url: URI? = null, val mediaIndex: Int? = null)
/** An explicit media resource or its Gateway-attested GIF page has one visible representation. */
internal fun ircBodySpans(text: String, media: List<IrcMedia>, previews: Boolean): List<IrcPresentationSpan> {
    val seen = hashSetOf<Int>()
    return globalChatSpans(text).mapNotNull { span ->
        val index = media.indexOfFirst { span.url != null && (ircMediaIdentity(it.url) == ircMediaIdentity(span.url) ||
            it.sourceUrl?.let(::ircMediaIdentity) == ircMediaIdentity(span.url)) }
        if (index < 0) IrcPresentationSpan(span.text, span.url)
        else if (previews || !seen.add(index)) null
        else IrcPresentationSpan("[${media[index].type.uppercase()}]", span.url, index)
    }
}

/** One in-flight desired toggle per message/emoji. Only authoritative broadcasts change displayed counts. */
internal class IrcReactionRequests {
    private data class Pending(val active: Boolean, val expires: Long)
    private val pending = linkedMapOf<Pair<IrcKey, String>, Pending>()
    fun start(key: IrcKey, emoji: String, active: Boolean, now: Long): Boolean {
        pending.entries.removeIf { now >= it.value.expires }
        if (emoji !in IRC_REACTIONS || (key to emoji) in pending || pending.size >= 64) return false
        pending[key to emoji] = Pending(active, now + 15_000)
        return true
    }
    fun finish(key: IrcKey, emoji: String) { pending.remove(key to emoji) }
    fun observe(message: GlobalChatMessage) {
        pending.entries.removeIf { (key, value) -> key.first == message.key &&
            (message.deleted || message.reactions.any { it.emoji == key.second && it.mine } == value.active) }
    }
    fun clear() = pending.clear()
}
