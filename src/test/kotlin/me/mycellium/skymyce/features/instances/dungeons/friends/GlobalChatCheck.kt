package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import java.util.UUID
import java.net.URI
import java.util.concurrent.CompletableFuture
import com.mojang.brigadier.CommandDispatcher
import me.mycellium.skymyce.commands.relayChannelCommand
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

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
    for (invalid in listOf("", " ", "x".repeat(4097), "bad\u0000text", "bad\rtext", "§atext", "bad\u202etext", "bad\u2066text", "\ud800", "\udc00")) {
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
    for (invalid in listOf("", " ", "x".repeat(1025), "§ctext", "control\u0001", "bidi\u202e", "isolate\u2069", "\ud800")) {
        check(globalMessageError(invalid) != null)
    }
    check(globalMessageError("Hello 😀, @Player") == null)
    check(userMessageError("Party", "bidi\u202e") != null && userMessageError("Party", "isolate\u2066") != null)
    check("already sent" in globalDeliveryError("duplicate_text"))
    check("not configured" in globalDeliveryError("not_configured"))
    for (reason in listOf("receipt_timeout", "connection_lost")) check("unconfirmed" in globalDeliveryError(reason))

    val account = UUID.randomUUID()
    val endpoint = "wss://relay.example/websocket"
    val identity = "$endpoint|$account"
    check(globalRelaySessionCurrent(identity, "lease-a", endpoint, account, "lease-a"))
    check(!globalRelaySessionCurrent(identity, "lease-a", endpoint, account, "lease-b"))
    check(!globalRelaySessionCurrent(identity, "lease-a", "wss://other.example/websocket", account, "lease-a"))
    check(!globalRelaySessionCurrent(identity, "lease-a", endpoint, UUID.randomUUID(), "lease-a"))
    check(!globalRelaySessionCurrent(identity, null, endpoint, account, null))

    val subscription = GlobalChatSubscription()
    val firstSubscribe = requireNotNull(subscription.take(true, 0L))
    subscription.queued(firstSubscribe, true)
    check(subscription.enabled && subscription.take(true, 1L) == null)
    check(subscription.complete(firstSubscribe, "temporarily_unavailable", 2L))
    check(!subscription.enabled && subscription.take(true, 3L) == null)
    val unsubscribe = requireNotNull(subscription.take(false, 3L)) // Opt-out bypasses a failed opt-in's backoff.
    subscription.queued(unsubscribe, false)
    check(!subscription.complete(firstSubscribe, "not_authorized", 4L)) // Old receipt cannot undo the new preference.
    check(!subscription.enabled && subscription.take(false, 5L) == null)
    val subscribeAgain = requireNotNull(subscription.take(true, 6L))
    subscription.queued(subscribeAgain, true)
    check(subscription.complete(subscribeAgain, "not_authorized", 7L))
    val retry = requireNotNull(subscription.take(true, 5_000_000_008L))
    subscription.queued(retry, true)
    check(!subscription.complete(retry, "not_authorized", 5_000_000_009L)) // One notice for a continuing failure.
    subscription.reset() // Reconnect invalidates receipts and subscribes again, with no automatic message resend.
    check(!subscription.enabled && !subscription.complete(retry, null, 6_000_000_000L))
    check(subscription.take(true, 6_000_000_000L) != null)

    val history = IrcChatStore()
    val historyBoundary = ircHistoryBoundary(account, endpoint)
    check(history.boundary(historyBoundary) && history.accept(message, 1000))
    subscription.reset() // Authorization renewal and socket reconnect reset receipts, not account-scoped history.
    check(!history.boundary(ircHistoryBoundary(account, endpoint)) && history.get(message.key) == message)
    val partyMessage = message.copy(scope = "party", room = "a".repeat(32))
    check(history.accept(partyMessage, 1001))
    history.clearScope("party") // Leaving/changing the party still removes its private history.
    check(history.get(partyMessage.key) == null && history.get(message.key) == message)
    check(history.boundary(ircHistoryBoundary(account, "wss://other.example/websocket")) && history.values().isEmpty())
    check(history.accept(message, 1002))
    check(history.boundary(ircHistoryBoundary(UUID.randomUUID(), endpoint)) && history.values().isEmpty())
    check(history.accept(message, 1003))
    check(history.boundary("") && history.values().isEmpty()) // A deliberate world disconnect still clears history.

    check(partyInfoRequestDue(0, 0, 0))
    val receivedPartyInfoAt = 10_000L
    val partyInfoAllowedAt = receivedPartyInfoAt + PARTY_INFO_REQUEST_MILLIS
    for (commandAt in listOf(10_001L, 20_000L, 35_000L, 69_999L)) {
        check(!partyInfoRequestDue(commandAt, commandAt, partyInfoAllowedAt)) // Manual selection/polls cannot bypass the shared minute guard.
    }
    check(partyInfoRequestDue(partyInfoAllowedAt, 35_000L, partyInfoAllowedAt))
    check(!partyInfoRequestDue(partyInfoAllowedAt, partyInfoAllowedAt + 1, partyInfoAllowedAt))
    check(ircPartyRefreshMillis(55_000) == 27_500L)
    check(ircPartyRefreshMillis(15_000) == 7_500L)
    check(ircPartyRefreshMillis(1) == 5_000L)
    check(ircPartyRefreshMillis(60_000) == 30_000L)
    check("Join Hypixel" in ircPartyRosterError(false, true, 2, 1))
    check("2–20" in ircPartyRosterError(true, false, 1, null))
    check("complete" in ircPartyRosterError(true, true, 2, null))
    check("expired" in ircPartyRosterError(true, true, 2, 65_001))
    check("changed" in ircPartyRosterError(true, true, 2, 65_000))

    val receipt = CompletableFuture<JsonObject?>()
    var completions = 0
    receipt.thenAccept { check(it == null); completions++ }
    val requests = mutableMapOf("global-send" to (12_000L to receipt))
    expireRelayRequests(requests, 11_999L, cancelled = false)
    check(!receipt.isDone && requests.size == 1)
    expireRelayRequests(requests, 12_000L, cancelled = false)
    expireRelayRequests(requests, 12_001L, cancelled = false)
    check(receipt.isDone && requests.isEmpty() && completions == 1)

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
    val signedGif = "https://cdn.discordapp.com/attachments/1/2/animation.gif?signature=" + "a".repeat(600)
    val compact = globalChatLines("See ($signedGif), then reply.", 32)
    check(compact.flatten().joinToString("") { it.text } == "See ([GIF]), then reply.")
    check(compact.flatten().filter { it.url != null } == listOf(GlobalChatSpan("[GIF]", URI(signedGif))))
    check(compact.all { line -> line.sumOf { it.text.length } <= 32 })
    val longLink = "https://example.com/download?signature=" + "b".repeat(600)
    val longSpans = globalChatLines(longLink, 32).flatten()
    check(longSpans.joinToString("") { it.text } == longLink)
    check(longSpans.all { it.url == URI(longLink) }) // No shortened target can open a different resource.
    val atomicGif = globalChatLines("x".repeat(30) + signedGif, 32)
    check(atomicGif.size == 2 && atomicGif.last().single() == GlobalChatSpan("[GIF]", URI(signedGif)))
    check(globalChatLines(emoji, 163).flatten().joinToString("") { it.text } == emoji)
    check(globalChatLines(emoji, 163).flatten().all { !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
    check(globalChatLines("one\n\nsecond").map { line -> line.joinToString("") { it.text } } == listOf("one", "second"))
    val shortenedLinks = globalChatLines((1..100).joinToString("\n") { "line $it" }, 32)
    check(shortenedLinks.size == 32 && "message shortened" in shortenedLinks.last().single().text)
    check(globalChatLines("https://tenor.com/view/example", 32).flatten().none { it.text == "[GIF]" })

    val mode = IrcMode()
    for (exit in listOf("sm chat", "skymyce chat", "SM  CHAT")) check(relayChatExit.matches(exit))
    for (protected in listOf("sm chat Player", "sm irc", "sm help", "other chat", "sm chat text")) check(!relayChatExit.matches(protected))
    check(mode.chat("normal") { error("Normal Hypixel chat must not be intercepted") })
    check(!mode.command("chat sky") && mode.global)
    check(!mode.chat("global only even after failed delivery") { })
    check(mode.command("chat guild") && !mode.selected)
    check(!mode.command("chat irc") && mode.selected && mode.global && !mode.party)
    check(mode.command("chat p") && !mode.selected)
    mode.selectParty()
    check(mode.selected && mode.party && !mode.global)
    check(!mode.chat("unavailable party delivery") { })
    check(mode.party && mode.selected)
    check(mode.command("other command") && mode.party)
    check(mode.command("chat all") && !mode.selected && !mode.party && !mode.global)
    for (alias in listOf("chat irc", "chat sky", "chat skymyce", "chat global", " CHAT   IRC ")) {
        check(!mode.command(alias) && mode.selected && mode.global && !mode.party)
    }
    mode.account(account)
    check(mode.global) // Initial identity association does not undo an explicit selection.
    mode.account(account)
    check(mode.global) // Reconnect with the same identity keeps failed sends confined.
    mode.account(UUID.randomUUID())
    check(!mode.selected && !mode.global && !mode.party)
    mode.selectParty()
    mode.account(UUID.randomUUID())
    check(!mode.selected && !mode.global && !mode.party)

    val dispatcher = CommandDispatcher<FabricClientCommandSource>()
    val sent = mutableListOf<Pair<String, String>>()
    for (name in listOf("irc", "global")) dispatcher.register(relayChannelCommand(name,
        { mode.command("chat sky") }, { text -> if (globalMessageError(text) == null) sent += "global" to text }))
    dispatcher.register(relayChannelCommand("pc", mode::selectParty,
        { text -> if (userMessageError("Party", text) == null) sent += "party" to text }))
    fun command(text: String) { check(dispatcher.execute(text, null) == 1) }
    command("pc")
    command("irc hello  world 😀")
    check(mode.party && sent.single() == "global" to "hello  world 😀")
    command("irc")
    command("pc one-off party")
    check(mode.global && sent.last() == "party" to "one-off party")
    command("global alias message")
    check(mode.global && sent.last() == "global" to "alias message")
    val validSends = sent.size
    for (invalid in listOf("§ctext", "x".repeat(1025), "bidi\u202e", "control\u0001")) command("irc $invalid")
    for (invalid in listOf("§ctext", "x".repeat(257), "isolate\u2066")) command("pc $invalid")
    check(sent.size == validSends && mode.global)
    mode.selected = false
    command("irc one-off while in normal chat")
    check(!mode.selected && sent.last() == "global" to "one-off while in normal chat")
    println("Global chat checks passed: strict public payloads, replies, mention pings, lease/account boundaries, subscription recovery, receipt expiry, replay/toggles, Unicode wrapping, safe links and canonical command/mode isolation")
}

fun main() = checkGlobalChat()
