package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import java.net.URI

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
