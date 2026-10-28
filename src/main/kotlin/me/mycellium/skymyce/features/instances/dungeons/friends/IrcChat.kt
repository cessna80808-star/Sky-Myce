package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal data class IrcMedia(val id: String, val type: String, val url: URI, val thumbnailUrl: URI? = null,
    val name: String? = null, val mime: String? = null, val bytes: Long? = null, val width: Int? = null, val height: Int? = null,
    val sourceUrl: URI? = null)
internal data class IrcSender(val name: String, val uuid: String? = null, val cosmeticName: String? = null,
    val discordId: String? = null, val avatarUrl: URI? = null)
internal data class IrcReaction(val emoji: String, val count: Int, val mine: Boolean = false)
internal data class IrcKey(val scope: String, val room: String, val id: String) {
    val tag get() = "skymyce:irc:$scope:$room:$id"
}
internal val IRC_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "👀", "🔥")
internal const val IRC_MANAGEMENT_MILLIS = 15 * 60 * 1000L
internal fun ircClientMessageError(scope: String, text: String): String? = if (scope in setOf("global", "party")) globalMessageError(text)
    else userMessageError("Room", text)?.replace("Messages", "Room IRC messages")
internal fun ircOperationError(reason: String): String = when (reason) {
    "not_authorized", "not_owner", "forbidden" -> "Only the author can edit or delete this message."
    "message_expired", "not_found", "expired" -> "This message is no longer available for controls."
    "rate_limited" -> "Message controls are rate limited. Wait briefly and retry."
    "invalid_message", "invalid_operation", "invalid_emoji" -> "The relay could not accept this message change."
    "receipt_timeout", "connection_lost" -> "The message change is unconfirmed. It was not resent; check chat before retrying."
    else -> "The relay could not apply this message change."
}
private val ircId = Regex("[a-f0-9]{32}")
private val ircRoom = Regex("[a-z0-9_-]{1,32}")
private val ircDigits = Regex("[0-9]{1,16}")

internal fun richIrcCapability(json: JsonObject): Boolean = json.get("richChatVersion")?.let {
    it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
} == true
internal fun partyIrcCapability(json: JsonObject): Boolean = richIrcCapability(json) && json.get("partyChat")?.let {
    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
} == true && json.get("partyChatVersion")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" } == true

internal fun ircPrefix(scope: String) = when (scope) { "global" -> "Sky-Myce Global:"; "party" -> "Sky-Myce Party:"; else -> "Sky-Myce IRC:" }
internal fun ircPrefixColors(length: Int): List<Int> {
    val stops = intArrayOf(0x44E4EF, 0x4A8EFF, 0xA46BFF)
    return List(length.coerceIn(0, 32)) { index ->
        val position = if (length <= 1) 0.0 else index.toDouble() / (length - 1) * 2
        val segment = position.toInt().coerceAtMost(1)
        val fraction = (position - segment).coerceIn(0.0, 1.0)
        var color = 0
        for (shift in listOf(16, 8, 0)) {
            val start = stops[segment] shr shift and 255
            val end = stops[segment + 1] shr shift and 255
            color = color or ((start + (end - start) * fraction).toInt() shl shift)
        }
        color
    }
}

internal data class IrcChatAction(val kind: String, val scope: String, val id: String, val mediaIndex: Int? = null,
    val previewRow: Int? = null) {
    val command get() = "/sm ircaction $kind $scope $id${mediaIndex?.let { " $it" }.orEmpty()}${previewRow?.let { " $it" }.orEmpty()}"
}
internal fun parseIrcChatAction(command: String): IrcChatAction? {
    val parts = command.split(' ')
    if (parts.size !in 5..7 || parts[0] != "/sm" || parts[1] != "ircaction" || parts[2] !in setOf("media", "manage", "reply", "react") ||
        parts[3] !in setOf("global", "room", "party") || !parts[4].matches(ircId)) return null
    val index = parts.getOrNull(5)?.toIntOrNull()
    val row = parts.getOrNull(6)?.toIntOrNull()
    if (parts[2] == "media" && (parts.size !in 6..7 || index !in 0..3 || parts.size == 7 && row !in 0..15) ||
        parts[2] != "media" && parts.size != 5) return null
    return IrcChatAction(parts[2], parts[3], parts[4], index, row)
}

internal fun ircText(value: String, maximum: Int, multiline: Boolean = false): Boolean = value.length <= maximum &&
    value.none { it == '§' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it.isISOControl() && !(multiline && it == '\n') } &&
    value.indices.all { when {
        value[it].isHighSurrogate() -> it + 1 < value.length && value[it + 1].isLowSurrogate()
        value[it].isLowSurrogate() -> it > 0 && value[it - 1].isHighSurrogate()
        else -> true
    } }

private fun JsonObject.ircString(key: String): String {
    val value = get(key); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return value.asString
}
private fun JsonObject.optionalString(key: String, maximum: Int): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    ircString(key).also { value -> require(ircText(value, maximum) && value.isNotBlank()) }
}
private fun JsonObject.ircInteger(key: String, maximum: Long): Long {
    val value = get(key); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(ircDigits))
    return value.asLong.also { require(it in 0..maximum) }
}
private fun JsonObject.optionalInteger(key: String, maximum: Long): Long? = get(key)?.takeUnless { it.isJsonNull }?.let { ircInteger(key, maximum) }
internal fun ircHttpsUrl(value: String): URI? = safeGlobalUrl(value)?.takeIf {
    it.scheme.equals("https", true) && it.port in listOf(-1, 443) && it.rawFragment == null && value.length <= 2048
}

internal fun parseIrcMedia(json: JsonObject): List<IrcMedia> {
    val raw = json.get("media") ?: return emptyList()
    require(raw.isJsonArray && raw.asJsonArray.size() <= 4)
    return raw.asJsonArray.map {
        require(it.isJsonObject)
        val item = it.asJsonObject
        val id = item.ircString("id"); require(id.matches(Regex("[A-Za-z0-9_:.=-]{1,80}")))
        val type = item.ircString("type"); require(type in setOf("image", "gif", "video"))
        val url = requireNotNull(ircHttpsUrl(item.ircString("url")))
        val thumbnail = item.optionalString("thumbnailUrl", 2048)?.let { value -> requireNotNull(ircHttpsUrl(value)) }
        IrcMedia(id, type, url, thumbnail, item.optionalString("name", 120), item.optionalString("mime", 80),
            item.optionalInteger("bytes", 1_073_741_824), item.optionalInteger("width", 65535)?.toInt()?.also { value -> require(value > 0) },
            item.optionalInteger("height", 65535)?.toInt()?.also { value -> require(value > 0) },
            item.optionalString("sourceUrl", 2048)?.let { value -> requireNotNull(ircHttpsUrl(value)) })
    }.also { require(it.map(IrcMedia::id).distinct().size == it.size) }
}

internal fun parseIrcReactions(json: JsonObject): List<IrcReaction> {
    val raw = json.get("reactions") ?: return emptyList()
    require(raw.isJsonArray && raw.asJsonArray.size() <= IRC_REACTIONS.size)
    return raw.asJsonArray.map {
        require(it.isJsonObject)
        val value = it.asJsonObject
        val emoji = value.ircString("emoji"); require(emoji in IRC_REACTIONS)
        val mine = value.get("mine")?.let { field -> require(field.isJsonPrimitive && field.asJsonPrimitive.isBoolean); field.asBoolean } ?: false
        IrcReaction(emoji, value.ircInteger("count", 100000).toInt(), mine)
    }.also { require(it.map(IrcReaction::emoji).distinct().size == it.size) }
}

private fun parseIrcSender(json: JsonObject): IrcSender? {
    val raw = json.get("sender") ?: return null
    require(raw.isJsonObject)
    val value = raw.asJsonObject
    val name = value.ircString("name"); require(name.isNotBlank() && ircText(name, 80))
    val uuid = value.optionalString("uuid", 32)?.also { require(it.matches(ircId)) }
    val discord = value.optionalString("discordId", 20)?.also { require(it.matches(Regex("[0-9]{17,20}"))) }
    return IrcSender(name, uuid, value.optionalString("cosmeticName", 80), discord,
        value.optionalString("avatarUrl", 2048)?.let { requireNotNull(ircHttpsUrl(it)) })
}

internal fun enrichIrcMessage(json: JsonObject, base: GlobalChatMessage): GlobalChatMessage {
    val raw = json.get("model") ?: return base
    require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber && raw.asString == "1")
    val source = json.optionalString("sourceId", 80)?.also { require(it.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) }
    val media = parseIrcMedia(json)
    require(base.text.isNotBlank() || media.isNotEmpty())
    return base.copy(model = 1, sourceId = source, media = media, sender = parseIrcSender(json),
        revision = json.ircInteger("revision", Int.MAX_VALUE.toLong()).toInt(), reactions = parseIrcReactions(json))
}

internal fun parseRoomIrc(json: JsonObject, room: String): GlobalChatMessage? = runCatching {
    require(json.ircString("type") == "irc_message" && room.matches(ircRoom) && json.ircString("room") == room)
    require(json.get("scope")?.asString in setOf(null, "room"))
    val id = json.ircString("id"); require(id.matches(ircId))
    val source = json.ircString("source"); require(source in setOf("minecraft", "discord"))
    val from = json.ircString("from"); require(from.isNotBlank() && ircText(from, 80))
    val text = json.ircString("text"); require(ircText(text, if (json.has("model")) 4096 else 256, true))
    require(text.isNotBlank() || json.get("model")?.asInt == 1)
    val timestamp = json.optionalInteger("timestamp", 9_007_199_254_740_991L) ?: 0L
    val mention = json.get("mention")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean } ?: false
    val reply = parseIrcReply(json)
    enrichIrcMessage(json, GlobalChatMessage(id, source, from, text, timestamp, mention, reply, scope = "room", room = room))
}.getOrNull()

internal fun parsePartyIrc(json: JsonObject, partyId: String): GlobalChatMessage? = runCatching {
    require(partyId.matches(ircId) && json.ircString("scope") == "party" && json.ircString("room") == partyId &&
        json.ircString("source") == "minecraft" && json.get("model")?.asInt == 1)
    val roomPacket = json.deepCopy().apply { addProperty("scope", "room") }
    requireNotNull(parseRoomIrc(roomPacket, partyId)).copy(scope = "party")
}.getOrNull()

internal fun parseIrcReply(json: JsonObject): GlobalChatReply? = json.get("reply")?.takeUnless { it.isJsonNull }?.let {
    require(it.isJsonObject)
    val value = it.asJsonObject
    val from = value.ircString("from"); require(from.isNotBlank() && ircText(from, 80))
    val text = value.get("text")?.takeUnless { field -> field.isJsonNull }?.let { _ ->
        value.ircString("text").also { preview -> require(ircText(preview, 512, true)) }.takeIf(String::isNotBlank)
    }
    val id = value.optionalString("messageId", 32)?.also { require(it.matches(ircId)) }
    GlobalChatReply(from, text, id)
}

internal data class IrcUpdate(val key: IrcKey, val operation: String, val revision: Int,
    val text: String?, val media: List<IrcMedia>?, val reactions: List<IrcReaction>?,
    val textRevision: Int = revision, val mediaRevision: Int = revision, val reactionRevision: Int = revision)
internal fun parseIrcUpdate(json: JsonObject): IrcUpdate? = runCatching {
    require(json.ircString("type") == "irc_update" && json.ircString("id").matches(ircId))
    val scope = json.ircString("scope"); require(scope in setOf("global", "room", "party"))
    val room = json.ircString("room"); require(room.matches(ircRoom) && (scope != "global" || room == "global-chat"))
    if (scope == "party") require(room.matches(ircId))
    val id = json.ircString("messageId"); require(id.matches(ircId))
    val operation = json.ircString("operation"); require(operation in setOf("edit", "delete", "reaction"))
    val text = json.get("text")?.let { json.ircString("text").also { require(ircText(it, 4096, true)) } }
    val media = if (json.has("media")) parseIrcMedia(json) else null
    val reactions = if (json.has("reactions")) parseIrcReactions(json) else null
    require(operation != "edit" || text != null || media != null)
    require(operation != "reaction" || reactions != null)
    IrcUpdate(IrcKey(scope, room, id), operation, json.ircInteger("revision", Int.MAX_VALUE.toLong()).toInt(), text, media, reactions)
}.getOrNull()

/** Current-session metadata only; updates can never invent or resurrect a message. */
internal class IrcChatStore {
    private data class Entry(val message: GlobalChatMessage, val received: Long,
        val textRevision: Int = message.revision, val mediaRevision: Int = message.revision,
        val reactionRevision: Int = if (message.reactions.isEmpty()) -1 else message.revision)
    private val messages = linkedMapOf<IrcKey, Entry>()
    private data class Pending(var update: IrcUpdate, val received: Long)
    private val pending = linkedMapOf<IrcKey, Pending>()
    private var boundary = ""
    /** Counts changes, so [hasMedia] (asked every frame by chat rendering) is recomputed only when messages change. */
    private var mutations = 0
    private var mediaCheckedAt = -1
    private var anyMedia = false
    /** Whether any stored message has an attachment; expired entries may leave it true until the next change. */
    fun hasMedia(): Boolean {
        if (mediaCheckedAt != mutations) { anyMedia = messages.values.any { !it.message.deleted && it.message.media.isNotEmpty() }; mediaCheckedAt = mutations }
        return anyMedia
    }
    fun boundary(value: String): Boolean {
        if (boundary == value) return false
        boundary = value; messages.clear(); pending.clear(); mutations++; return true
    }
    fun clear() { messages.clear(); pending.clear(); mutations++ }
    fun clearPending() = pending.clear()
    fun clearScope(scope: String) { messages.keys.removeIf { it.scope == scope }; pending.keys.removeIf { it.scope == scope }; mutations++ }
    fun get(key: IrcKey): GlobalChatMessage? = messages[key]?.message
    val isEmpty get() = messages.isEmpty()
    val revision get() = mutations
    fun values(): List<GlobalChatMessage> = messages.values.map(Entry::message)
    fun accept(message: GlobalChatMessage, now: Long): Boolean {
        expire(now)
        val key = message.key
        if (key in messages) return false
        messages[key] = Entry(message, now); mutations++
        pending.remove(key)?.update?.let { apply(it, now) }
        while (messages.size > 1024) messages.remove(messages.keys.first())
        expire(now)
        return true
    }
    fun apply(update: IrcUpdate, now: Long): GlobalChatMessage? {
        expire(now)
        val entry = messages[update.key] ?: run {
            // Updates can win the initial fan-out's authorization await. They cannot become
            // visible until their canonical initial message arrives in this same account boundary.
            val waiting = pending[update.key]
            if (waiting == null) pending[update.key] = Pending(update, now)
            else if (waiting.update.operation != "delete") {
                val previous = waiting.update
                val later = update.revision > previous.revision
                val text = update.text != null && (previous.text == null || update.textRevision > previous.textRevision)
                val media = update.media != null && (previous.media == null || update.mediaRevision > previous.mediaRevision)
                val reactions = update.reactions != null && (previous.reactions == null || update.reactionRevision > previous.reactionRevision)
                waiting.update = (if (later || update.operation == "delete") update else previous).copy(
                    revision = maxOf(previous.revision, update.revision),
                    text = if (text) update.text else previous.text, textRevision = if (text) update.textRevision else previous.textRevision,
                    media = if (media) update.media else previous.media, mediaRevision = if (media) update.mediaRevision else previous.mediaRevision,
                    reactions = if (reactions) update.reactions else previous.reactions, reactionRevision = if (reactions) update.reactionRevision else previous.reactionRevision)
            }
            while (pending.size > 32) pending.remove(pending.keys.first())
            return null
        }
        val previous = entry.message
        if (previous.deleted || previous.model != 1) return null
        val text = update.text != null && update.textRevision > entry.textRevision
        val media = update.media != null && update.mediaRevision > entry.mediaRevision
        val reactions = update.reactions != null && update.reactionRevision > entry.reactionRevision
        if (update.operation != "delete" && !text && !media && !reactions) return null
        val revision = maxOf(previous.revision, update.revision)
        val next = if (update.operation == "delete") previous.copy(deleted = true, text = "Message deleted", media = emptyList(), reactions = emptyList(), revision = revision)
        else previous.copy(text = if (text) update.text else previous.text, media = if (media) update.media else previous.media,
            reactions = if (reactions) update.reactions else previous.reactions, revision = revision)
        messages[update.key] = entry.copy(message = next, textRevision = if (text) update.textRevision else entry.textRevision,
            mediaRevision = if (media) update.mediaRevision else entry.mediaRevision, reactionRevision = if (reactions) update.reactionRevision else entry.reactionRevision)
        mutations++
        return next
    }
    fun manageable(key: IrcKey, uuid: String, now: Long): Boolean = messages[key]?.let {
        !it.message.deleted && it.message.model == 1 && it.message.source == "minecraft" && it.message.sender?.uuid == uuid && now - it.received in 0..IRC_MANAGEMENT_MILLIS
    } == true
    fun expire(now: Long) {
        pending.entries.removeIf { now - it.value.received !in 0..15_000 }
    }
    fun retain(nativeTags: Set<String>, now: Long) {
        // A chat mod may queue the initial insertion while the world finishes loading.
        messages.entries.removeIf { it.key.tag !in nativeTags && now - it.value.received !in 0..15_000 }
    }
}

/** A disconnected private channel stays selected: failed sends never become public chat. */
internal class IrcMode {
    private var account: java.util.UUID? = null

    fun account(current: java.util.UUID) {
        if (account != null && account != current) selected = false
        account = current
    }
    var selected = false
        set(value) { field = value; if (!value) { global = false; party = false } }
    var global = false
        private set
    var party = false
        private set
    fun selectParty() { selected = true; global = false; party = true }

    fun command(command: String): Boolean {
        val normalized = command.trim().lowercase().split(spaces)
        if (normalized.size == 2 && normalized[0] == "chat" && normalized[1] in setOf("irc", "sky", "skymyce", "global")) {
            selected = true
            global = true
            party = false
            return false
        }
        if (normalized.size == 2 && normalized[0] == "chat" &&
            normalized[1] in setOf("p", "party", "a", "all", "g", "guild", "o", "officer", "c", "co", "coop", "co-op")) selected = false
        return true
    }
    fun chat(text: String, send: (String) -> Unit): Boolean {
        if (!selected) return true
        send(text)
        return false
    }
    fun composer(text: String, localFeedback: Boolean, send: (String) -> Unit): Boolean {
        if (!selected || text.startsWith("/")) return true
        if (!localFeedback && text.isNotBlank()) send(text)
        return false
    }

    private companion object { val spaces = Regex("\\s+") }
}

internal const val PARTY_INFO_REQUEST_MILLIS = 60_000L
internal fun partyInfoRequestDue(now: Long, requestedAt: Long, allowedAt: Long) = now >= requestedAt && now >= allowedAt
internal fun ircPartyRefreshMillis(grantMillis: Long) = (grantMillis / 2).coerceIn(5_000L, 30_000L)
internal fun ircPartyRosterError(onHypixel: Boolean, inParty: Boolean, memberCount: Int, rosterAgeMillis: Long?): String = when {
    !onHypixel -> "Join Hypixel to use Party IRC."
    !inParty || memberCount !in 2..20 -> "Party IRC needs a Hypixel party with 2–20 players."
    rosterAgeMillis == null -> "Waiting for Hypixel's complete party roster."
    rosterAgeMillis !in 0..65_000L -> "Refreshing Hypixel's expired party roster."
    else -> "Hypixel's party roster is incomplete or changed; waiting for a fresh update."
}

/** Shared identifier describes mutual roster consent. It is not a server attestation of Hypixel membership. */
internal data class IrcPartyRoster(val members: List<String>, val leader: String) {
    val id: String = MessageDigest.getInstance("SHA-256")
        .digest(("sky-myce-party-v1\n$leader\n${members.joinToString("\n")}").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
}
internal fun validatedIrcPartyRoster(inParty: Boolean, members: List<UUID?>, leader: UUID?, self: UUID): IrcPartyRoster? {
    if (!inParty || members.size !in 2..20 || leader == null || members.any { it == null || it == UUID(0, 0) } ||
        members.distinct().size != members.size || self !in members || leader !in members) return null
    return IrcPartyRoster(members.map { it.toString().replace("-", "") }.sorted(), leader.toString().replace("-", ""))
}

internal fun ircPartyGrantMillis(response: JsonObject, partyId: String?, now: Long): Long? = runCatching {
    require(partyId != null && response.get("type")?.asString == "irc_result" && response.get("scope")?.asString == "party" &&
        response.get("room")?.asString == partyId && !response.has("error"))
    val expires = response.get("expiresAt")
    require(expires != null && expires.isJsonPrimitive && expires.asJsonPrimitive.isNumber && expires.asString.matches(Regex("[0-9]{1,16}")))
    val remaining = expires.asLong - now
    // Subtract the clock-skew allowance so even a short server lease keeps a conservative local deadline.
    require(remaining in 5_001..65_000)
    (remaining - 5_000).coerceAtMost(55_000)
}.getOrNull()

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

internal data class GlobalChatReply(val from: String, val text: String?, val messageId: String? = null)
internal data class GlobalChatMessage(
    val id: String,
    val source: String,
    val from: String,
    val text: String,
    val timestamp: Long,
    val mention: Boolean,
    val reply: GlobalChatReply?,
    val scope: String = "global",
    val room: String = "global-chat",
    val model: Int = 0,
    val sourceId: String? = null,
    val media: List<IrcMedia> = emptyList(),
    val sender: IrcSender? = null,
    val revision: Int = 0,
    val reactions: List<IrcReaction> = emptyList(),
    val deleted: Boolean = false,
) { val key get() = IrcKey(scope, room, id) }
internal data class GlobalChatDelivery(val message: GlobalChatMessage, val ping: Boolean)
internal data class GlobalChatSpan(val text: String, val url: URI? = null)

private val globalMessageId = Regex("[a-f0-9]{32}")
private val globalUrl = Regex("(?i)https?://[^\\s<>]+")
private fun safeGlobalText(text: String, maximum: Int, multiline: Boolean) = !text.isBlank() && ircText(text, maximum, multiline)

internal fun globalMessageError(text: String): String? = when {
    text.isBlank() || text.length > 1024 -> "Global messages must contain 1–1024 characters."
    !safeGlobalText(text, 1024, false) -> "Global messages cannot contain control or formatting codes."
    else -> null
}

internal fun globalDeliveryError(reason: String?): String = when (reason) {
    "rate_limited" -> "Global chat rate limit reached. Wait briefly and retry."
    "duplicate_message", "duplicate_text", "duplicate" -> "That global message was already sent."
    "invalid_message" -> "Global chat could not accept that message."
    "not_configured", "global_chat_disabled", "irc_disabled" -> "Global Discord chat is not configured on this relay."
    "not_authorized" -> "Global chat authorization failed. Reconnect and try again."
    "not_subscribed" -> "Global chat is reconnecting. Wait a moment and retry."
    "temporarily_unavailable" -> "Global chat is temporarily unavailable. Wait briefly and retry."
    "receipt_timeout", "connection_lost" -> "Global message delivery is unconfirmed. Check chat before retrying; it was not resent automatically."
    else -> "Global chat could not deliver the message. Nothing was sent to normal chat."
}

/** Reads only public presentation fields. Private account/message metadata never becomes chat text. */
internal fun parseGlobalChat(json: JsonObject): GlobalChatMessage? = runCatching {
    fun string(key: String, value: JsonObject = json): String {
        val field = value.get(key)
        require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString)
        return field.asString
    }
    require(string("type") == "irc_message" && string("scope") == "global")
    if (json.has("model")) require(string("room") == "global-chat")
    val id = string("id"); require(id.matches(globalMessageId))
    val source = string("source"); require(source in setOf("minecraft", "discord"))
    val from = string("from"); require(safeGlobalText(from, 80, false))
    val text = string("text"); require(if (json.get("model")?.asInt == 1) ircText(text, 4096, true) else safeGlobalText(text, 4096, true))
    val rawTime = json.get("timestamp")
    require(rawTime != null && rawTime.isJsonPrimitive && rawTime.asJsonPrimitive.isNumber &&
        rawTime.asString.matches(Regex("[0-9]{1,16}")))
    val timestamp = rawTime.asString.toLong(); require(timestamp in 0..9_007_199_254_740_991L)
    val rawMention = json.get("mention")
    require(rawMention != null && rawMention.isJsonPrimitive && rawMention.asJsonPrimitive.isBoolean)
    enrichIrcMessage(json, GlobalChatMessage(id, source, from, text, timestamp, rawMention.asBoolean, parseIrcReply(json)))
}.getOrNull()

/** Account boundary and replay protection survive relay reconnects, but never cross accounts. */
internal class GlobalChatState {
    private var account = ""
    private val seen = linkedSetOf<String>()
    fun account(key: String) {
        if (account == key) return
        account = key
        seen.clear()
    }
    fun receive(
        json: JsonObject, deliveredTo: String, currentAccount: String,
        enabled: Boolean, authorized: Boolean, mentionSounds: Boolean,
    ): GlobalChatDelivery? {
        account(currentAccount)
        if (currentAccount.isBlank() || deliveredTo != currentAccount || !authorized) return null
        val message = parseGlobalChat(json) ?: return null
        if (!seen.add(message.id)) return null
        while (seen.size > 512) seen.remove(seen.first())
        if (!enabled) return null
        return GlobalChatDelivery(message, message.mention && mentionSounds)
    }
}

/** Changes opt-in immediately; failed receipts retry gently and cannot undo a later preference. */
internal class GlobalChatSubscription {
    private var desired: Boolean? = null
    private var queued: Boolean? = null
    private var revision = 0L
    private var retryAfter = 0L
    private var lastError: String? = null
    private var confirmedRevision = -1L
    val enabled get() = queued == true
    /** The relay authorizes subscriptions asynchronously; sends must wait for its receipt or they overtake it. */
    val confirmed get() = enabled && confirmedRevision == revision
    fun current(token: Long) = token == revision
    fun reset() {
        desired = null; queued = null; revision++; retryAfter = 0L; lastError = null
    }
    fun take(enabled: Boolean, now: Long): Long? {
        if (desired != enabled) { desired = enabled; retryAfter = 0L; lastError = null }
        if (queued == enabled || now < retryAfter) return null
        return ++revision
    }
    fun queued(token: Long, enabled: Boolean) { if (token == revision) queued = enabled }
    fun complete(token: Long, reason: String?, now: Long): Boolean {
        if (token != revision) return false
        if (reason == null) { lastError = null; confirmedRevision = token; return false }
        queued = null
        retryAfter = now + 5_000_000_000L
        val notify = desired == true && reason != lastError && reason !in setOf("receipt_timeout", "connection_lost")
        lastError = reason
        return notify
    }
}

/**
 * One IRC message waiting for a subscription receipt, released once confirmed or reported after 12 s.
 * A send the relay rejected as not_subscribed was never enqueued, so it may wait for one resubscription.
 */
internal class IrcHeldSend {
    class Held(val scope: String, val text: String, val expiresAt: Long, val retried: Boolean)
    var held: Held? = null
        private set
    fun hold(scope: String, text: String, now: Long, retried: Boolean = false): Boolean {
        if (held != null) return false
        held = Held(scope, text, now + 12_000_000_000L, retried); return true
    }
    fun rejected(scope: String, text: String, reason: String?, retried: Boolean, now: Long) =
        reason == "not_subscribed" && !retried && hold(scope, text, now, true)
    fun take(scope: String): Held? = held?.takeIf { it.scope == scope }?.also { held = null }
    fun expired(now: Long): Held? = held?.takeIf { now >= it.expiresAt }?.also { held = null }
    fun clear() { held = null }
}

/** Bounded word wrapping; never cuts an extended grapheme or floods chat with empty lines. */
internal fun splitGlobalChatText(text: String, maximum: Int = 256, maxLines: Int = 32): List<String> {
    require(maximum in 32..256 && maxLines in 1..32)
    val output = ArrayList<String>()
    val shortened = "… (message shortened)"
    for (paragraph in text.splitToSequence('\n')) {
        var start = 0
        while (start < paragraph.length) {
            if (output.size == maxLines) {
                val previous = output.last()
                val end = ircGraphemeEnd(previous, 0, minOf(previous.length, maximum - shortened.length))
                output[output.lastIndex] = previous.substring(0, end) + shortened
                return output
            }
            var end = minOf(start + maximum, paragraph.length)
            if (end < paragraph.length) {
                end = ircGraphemeEnd(paragraph, start, end)
                val space = paragraph.lastIndexOf(' ', end - 1)
                if (space > start + maximum / 2) end = space
            }
            if (end == start) end = Regex("\\X").find(paragraph, start)!!.range.last + 1
            output.add(paragraph.substring(start, end))
            start = end
            if (start < paragraph.length && paragraph[start] == ' ') start++
        }
    }
    return output
}

internal fun safeGlobalUrl(text: String): URI? = runCatching {
    require(text.length in 1..2048 && text.none { it.isISOControl() || it.isWhitespace() })
    URI(text).also {
        require(it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.port in -1..65535 && it.port != 0)
    }
}.getOrNull()

/** Whitelist full original URLs when wrapping so a cut URL never becomes a different clickable URL. */
internal fun globalChatSpans(text: String, fullUrls: Set<String>? = null): List<GlobalChatSpan> {
    val output = ArrayList<GlobalChatSpan>()
    var start = 0
    for (match in globalUrl.findAll(text)) {
        var candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?')
        for ((closing, opening) in listOf(')' to '(', ']' to '[', '}' to '{')) {
            while (candidate.lastOrNull() == closing && candidate.count { it == closing } > candidate.count { it == opening }) {
                candidate = candidate.dropLast(1)
            }
        }
        val uri = safeGlobalUrl(candidate)?.takeIf { fullUrls == null || candidate in fullUrls }
        if (uri == null) continue
        if (match.range.first > start) output.add(GlobalChatSpan(text.substring(start, match.range.first)))
        output.add(GlobalChatSpan(candidate, uri))
        start = match.range.first + candidate.length
    }
    if (start < text.length) output.add(GlobalChatSpan(text.substring(start)))
    return output
}

/** Wrap presentation spans after parsing URLs, retaining each original signed click target. */
internal fun globalChatLines(text: String, maximum: Int = 256, maxLines: Int = 32): List<List<GlobalChatSpan>> {
    require(maximum in 32..256 && maxLines in 1..32)
    val output = ArrayList<List<GlobalChatSpan>>()
    val line = ArrayList<GlobalChatSpan>()
    var length = 0
    fun flush() {
        if (line.isNotEmpty()) { output.add(line.toList()); line.clear(); length = 0 }
    }
    fun shortened(): List<List<GlobalChatSpan>> {
        val suffix = "… (message shortened)"
        val previous = output.last().joinToString("") { it.text }
        val end = ircGraphemeEnd(previous, 0, minOf(previous.length, maximum - suffix.length))
        output[output.lastIndex] = listOf(GlobalChatSpan(previous.substring(0, end) + suffix))
        return output
    }
    for (original in globalChatSpans(text)) {
        val gif = original.url?.let(::trustedIrcGifUrl) == true
        val span = if (gif) original.copy(text = "[GIF]") else original
        if (gif && length + span.text.length > maximum) flush()
        var start = 0
        while (start < span.text.length) {
            if (span.text[start] == '\n') { flush(); start++; continue }
            if (length >= maximum) flush()
            if (output.size == maxLines) return shortened()
            val paragraphEnd = span.text.indexOf('\n', start).takeIf { it >= 0 } ?: span.text.length
            val available = maximum - length
            var end = minOf(start + available, paragraphEnd)
            if (end < paragraphEnd) {
                end = ircGraphemeEnd(span.text, start, end)
                if (span.url == null) {
                    val space = span.text.lastIndexOf(' ', end - 1)
                    if (space > start + available / 2) end = space
                }
            }
            if (end == start) {
                if (length > 0) { flush(); continue }
                end = Regex("\\X").find(span.text, start)!!.range.last + 1
            }
            line.add(GlobalChatSpan(span.text.substring(start, end), span.url))
            length += end - start
            start = end
            if (start < paragraphEnd) {
                flush()
                if (span.text[start] == ' ') start++
            }
        }
    }
    flush()
    return output
}
