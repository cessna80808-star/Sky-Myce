package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject

private fun globalPacket(number: Int = 1, text: String = "Hello", mention: Boolean = false): JsonObject = JsonObject().apply {
    addProperty("type", "irc_message")
    addProperty("scope", "global")
    addProperty("id", number.toString(16).padStart(32, '0'))
    addProperty("source", "discord")
    addProperty("from", "Example User")
    addProperty("text", text)
    addProperty("timestamp", 1_800_000_000_000L)
    addProperty("mention", mention)
}

fun checkGlobalChat() {
    val packet = globalPacket(text = "Update\nEmbed title: Something new\nhttps://example.com/update", mention = true).apply {
        add("reply", JsonObject().apply { addProperty("from", "MinecraftUser"); addProperty("text", "Anyone running F7?") })
        addProperty("discordId", "private-metadata") // Public rendering never incorporates extra fields.
    }
    val message = requireNotNull(parseGlobalChat(packet))
    check(message.reply == GlobalChatReply("MinecraftUser", "Anyone running F7?"))
    for (preview in listOf(null, "", "  \n ")) {
        val emptyReply = globalPacket().apply {
            add("reply", JsonObject().apply { addProperty("from", "Uncached Discord User"); if (preview != null) addProperty("text", preview) })
        }
        check(parseGlobalChat(emptyReply)?.reply == GlobalChatReply("Uncached Discord User", null))
    }
    check(parseGlobalChat(globalPacket().apply {
        add("reply", JsonObject().apply { addProperty("from", "person"); addProperty("text", false) })
    }) == null)
    check(message.mention && "private-metadata" !in message.toString())
    check(parseGlobalChat(globalPacket(text = "😀".repeat(2048)))?.text?.length == 4096)
    for (invalid in listOf("", " ", "x".repeat(4097), "bad\u0000text", "bad\rtext", "§atext", "\ud800", "\udc00")) {
        check(parseGlobalChat(globalPacket(text = invalid)) == null)
    }
    for ((key, bad) in listOf("type" to "other", "scope" to "room", "source" to "unknown", "id" to "internal-id",
        "from" to "name\nforged prefix", "from" to "§cunsafe")) {
        check(parseGlobalChat(globalPacket().apply { addProperty(key, bad) }) == null)
    }
    check(parseGlobalChat(globalPacket().apply { addProperty("mention", "true") }) == null)
    check(parseGlobalChat(globalPacket().apply { addProperty("timestamp", "1800000000000") }) == null)
    check(parseGlobalChat(globalPacket().apply { addProperty("timestamp", -1) }) == null)
    check(parseGlobalChat(globalPacket().apply { addProperty("timestamp", 1.5) }) == null)
    check(parseGlobalChat(globalPacket().apply { add("reply", JsonObject().apply { addProperty("from", "person"); addProperty("text", "x".repeat(513)) }) }) == null)
    check(parseGlobalChat(globalPacket().apply { add("reply", JsonObject().apply { addProperty("from", "person"); addProperty("text", " ".repeat(513)) }) }) == null)
    check(globalMessageError("x".repeat(1024)) == null && globalMessageError("x".repeat(1025)) != null)
    check(globalMessageError("normal conversation about politics or profanity") == null)
    check(globalMessageError("invalid\nMinecraft input") != null)

    val state = GlobalChatState()
    fun receive(value: JsonObject, enabled: Boolean = true, authorized: Boolean = true, sound: Boolean = true,
                delivered: String = "account-A", current: String = "account-A") =
        state.receive(value, delivered, current, enabled, authorized, sound)
    check(receive(packet, authorized = false) == null)
    check(receive(packet)?.ping == true)
    state.account("account-A") // Relay reconnect retains the account's replay window.
    check(receive(packet) == null)
    check(receive(globalPacket(2, "@OtherUser @OtherUser", false))?.ping == false)
    check(receive(globalPacket(3, "@CurrentUser @CurrentUser", true))?.ping == true)
    check(receive(globalPacket(3, "@CurrentUser @CurrentUser", true)) == null)
    check(receive(globalPacket(4, mention = true), sound = false)?.ping == false)
    check(receive(globalPacket(5, mention = true), enabled = false) == null)
    check(receive(globalPacket(5, mention = true)) == null) // Re-enabling never plays a queued duplicate.
    check(receive(globalPacket(6), enabled = true) != null)
    check(receive(globalPacket(7, mention = true), delivered = "account-A", current = "account-B") == null)
    check(receive(globalPacket(7, mention = true), delivered = "account-B", current = "account-B")?.ping == true)
    check(receive(globalPacket(8).apply { addProperty("text", "§bad") }, delivered = "account-B", current = "account-B") == null)
    check(receive(globalPacket(8), delivered = "account-B", current = "account-B") != null)

    val emoji = "😀".repeat(2048)
    val split = splitGlobalChatText(emoji, 163)
    check(split.size <= 32 && split.all { it.length <= 163 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    check(split.joinToString("") == emoji)
    val wrapped = splitGlobalChatText("alpha beta ".repeat(300), 163)
    check(wrapped.all { it.length <= 163 } && wrapped.size <= 32)
    check(splitGlobalChatText("one\n\nsecond") == listOf("one", "second"))
    val truncated = splitGlobalChatText((1..100).joinToString("\n") { "line $it" })
    check(truncated.size == 32 && "message shortened" in truncated.last() && truncated.all { it.length <= 256 })
    check(relayPacketSizeAllowed(globalPacket(text = "\\".repeat(4096)).toString().length, "irc_message"))
    check(!relayPacketSizeAllowed(65_537, "irc_message"))
    check(relayActive(inWorld = true, globalEnabled = true, friendsActive = false, connected = false)) // Global chat outside Hypixel/Party Finder.
    check(relayActive(inWorld = true, globalEnabled = false, friendsActive = false, connected = true)) // Disabling receiving keeps authorization.
    check(!relayActive(inWorld = true, globalEnabled = false, friendsActive = false, connected = false)) // Opt-out never starts a socket.
    check(!relayActive(inWorld = false, globalEnabled = true, friendsActive = true, connected = true)) // Leaving the world closes it.

    check(safeGlobalUrl("https://example.com/path?q=hello#section") != null)
    for (url in listOf("javascript:alert(1)", "file:///private", "mailto:someone@example.com", "https://user:password@example.com/",
        "https:///missing-host", "https://example.com:0/", "https://example.com:99999/", "https://example.com/\nunsafe")) {
        check(safeGlobalUrl(url) == null)
    }
    val linkedText = "See (https://example.com/page), then https://example.com/wiki/Foo_(Bar)."
    val links = globalChatSpans(linkedText)
    check(links.joinToString("") { it.text } == linkedText)
    check(links.mapNotNull { it.url?.toString() } == listOf("https://example.com/page", "https://example.com/wiki/Foo_(Bar)"))
    check(globalChatSpans("https://example.com/cut", setOf("https://example.com/cut/full-path")).none { it.url != null })

    val mode = IrcMode()
    check(mode.chat("normal") { error("Normal Hypixel chat must not be intercepted") })
    check(!mode.command("chat sky") && mode.global)
    check(!mode.chat("global only even after failed delivery") { })
    check(mode.command("chat guild") && !mode.selected)
    check(!mode.command("chat irc") && mode.selected && !mode.global)
    check(mode.command("chat p") && !mode.selected)
    println("Global chat checks passed: strict public payloads, replies, personalized mention pings, account boundaries, replay/toggles, bounded Unicode wrapping and safe clickable links")
}

fun main() = checkGlobalChat()
