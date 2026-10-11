package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import java.net.URI

internal data class GlobalChatReply(val from: String, val text: String?)
internal data class GlobalChatMessage(
    val id: String,
    val source: String,
    val from: String,
    val text: String,
    val timestamp: Long,
    val mention: Boolean,
    val reply: GlobalChatReply?,
)
internal data class GlobalChatDelivery(val message: GlobalChatMessage, val ping: Boolean)
internal data class GlobalChatSpan(val text: String, val url: URI? = null)

private val globalMessageId = Regex("[a-f0-9]{32}")
private val globalUrl = Regex("(?i)https?://[^\\s<>]+")
private fun safeGlobalText(text: String, maximum: Int, multiline: Boolean) = text.length in 1..maximum &&
    !text.isBlank() && text.none { it == '§' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' ||
        it.isISOControl() && !(multiline && it == '\n') } &&
    text.indices.all { index -> when {
        text[index].isHighSurrogate() -> index + 1 < text.length && text[index + 1].isLowSurrogate()
        text[index].isLowSurrogate() -> index > 0 && text[index - 1].isHighSurrogate()
        else -> true
    } }

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
    val id = string("id"); require(id.matches(globalMessageId))
    val source = string("source"); require(source in setOf("minecraft", "discord"))
    val from = string("from"); require(safeGlobalText(from, 80, false))
    val text = string("text"); require(safeGlobalText(text, 4096, true))
    val rawTime = json.get("timestamp")
    require(rawTime != null && rawTime.isJsonPrimitive && rawTime.asJsonPrimitive.isNumber &&
        rawTime.asString.matches(Regex("[0-9]{1,16}")))
    val timestamp = rawTime.asString.toLong(); require(timestamp in 0..9_007_199_254_740_991L)
    val rawMention = json.get("mention")
    require(rawMention != null && rawMention.isJsonPrimitive && rawMention.asJsonPrimitive.isBoolean)
    val reply = json.get("reply")?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonObject)
        val value = it.asJsonObject
        val author = string("from", value); require(safeGlobalText(author, 80, false))
        val preview = value.get("text")?.takeUnless { field -> field.isJsonNull }?.let { _ ->
            string("text", value).also { preview -> require(preview.length <= 512) }.takeUnless { preview -> preview.isBlank() }
                ?.also { preview -> require(safeGlobalText(preview, 512, true)) }
        }
        GlobalChatReply(author, preview)
    }
    GlobalChatMessage(id, source, from, text, timestamp, rawMention.asBoolean, reply)
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
    val enabled get() = queued == true
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
        if (reason == null) { lastError = null; return false }
        queued = null
        retryAfter = now + 5_000_000_000L
        val notify = desired == true && reason != lastError && reason !in setOf("receipt_timeout", "connection_lost")
        lastError = reason
        return notify
    }
}

/** Bounded word wrapping; never cuts a UTF-16 surrogate pair or floods chat with empty lines. */
internal fun splitGlobalChatText(text: String, maximum: Int = 256, maxLines: Int = 32): List<String> {
    require(maximum in 32..256 && maxLines in 1..32)
    val output = ArrayList<String>()
    val shortened = "… (message shortened)"
    for (paragraph in text.splitToSequence('\n')) {
        var start = 0
        while (start < paragraph.length) {
            if (output.size == maxLines) {
                val previous = output.last()
                var end = minOf(previous.length, maximum - shortened.length)
                if (end > 0 && previous[end - 1].isHighSurrogate()) end--
                output[output.lastIndex] = previous.substring(0, end) + shortened
                return output
            }
            var end = minOf(start + maximum, paragraph.length)
            if (end < paragraph.length) {
                if (paragraph[end - 1].isHighSurrogate()) end--
                val space = paragraph.lastIndexOf(' ', end - 1)
                if (space > start + maximum / 2) end = space
            }
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
