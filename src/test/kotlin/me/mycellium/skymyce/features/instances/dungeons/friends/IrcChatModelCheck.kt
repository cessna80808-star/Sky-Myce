package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.UUID

private fun richMessage(id: String = "a".repeat(32), scope: String = "global", room: String = "global-chat"): JsonObject = JsonParser.parseString("""
    {"type":"irc_message","id":"$id","scope":"$scope","room":"$room","source":"minecraft","from":"Alice","text":"hello","timestamp":1,"mention":false,
     "model":1,"revision":0,"sourceId":"mc:a","sender":{"uuid":"${"b".repeat(32)}","name":"Alice","cosmeticName":"Blue Alice"},"media":[],"reactions":[]}
""").asJsonObject
private fun change(id: String = "a".repeat(32), scope: String = "global", room: String = "global-chat", revision: Int = 1, operation: String = "edit"): JsonObject = JsonParser.parseString("""
    {"type":"irc_update","id":"${"c".repeat(32)}","scope":"$scope","room":"$room","messageId":"$id","operation":"$operation","revision":$revision,"text":"changed"}
""").asJsonObject

fun checkIrcChatModel() {
    check(richIrcCapability(JsonParser.parseString("{\"richChatVersion\":1}").asJsonObject))
    for (value in listOf("null", "\"1\"", "true", "1.0", "2"))
        check(!richIrcCapability(JsonParser.parseString("{\"richChatVersion\":$value}").asJsonObject))
    val global = requireNotNull(parseGlobalChat(richMessage()))
    check(parseGlobalChat(richMessage(room = "testing")) == null)
    check(ircClientMessageError("room", "x".repeat(257)) != null && ircClientMessageError("global", "x".repeat(257)) == null)
    check(global.key == IrcKey("global", "global-chat", "a".repeat(32)) && global.model == 1 && global.sender?.name == "Alice")
    val self = UUID(0, 1); val peer = UUID(0, 2)
    val roster = requireNotNull(validatedIrcPartyRoster(true, listOf(peer, self), peer, self))
    check(roster == validatedIrcPartyRoster(true, listOf(self, peer), peer, self) && roster.id.length == 32)
    check(roster.id != validatedIrcPartyRoster(true, listOf(self, peer), self, self)?.id)
    check(validatedIrcPartyRoster(false, listOf(self, peer), peer, self) == null)
    check(validatedIrcPartyRoster(true, listOf(self, null), peer, self) == null)
    check(validatedIrcPartyRoster(true, listOf(self, peer, peer), peer, self) == null)
    check(validatedIrcPartyRoster(true, listOf(peer, UUID(0, 3)), peer, self) == null)
    check(validatedIrcPartyRoster(true, listOf(self, peer), UUID(0, 3), self) == null)
    check(validatedIrcPartyRoster(true, (1L..21).map { UUID(0, it) }, self, self) == null)
    val partyPacket = richMessage(scope = "party", room = roster.id)
    val party = requireNotNull(parsePartyIrc(partyPacket, roster.id))
    check(party.scope == "party" && party.key != global.key && parsePartyIrc(partyPacket, "e".repeat(32)) == null)
    partyPacket.addProperty("source", "discord"); check(parsePartyIrc(partyPacket, roster.id) == null)
    check(partyIrcCapability(JsonParser.parseString("""{"richChatVersion":1,"partyChat":true,"partyChatVersion":1}""").asJsonObject))
    check(!partyIrcCapability(JsonParser.parseString("""{"richChatVersion":1,"partyChat":true,"partyChatVersion":"1"}""").asJsonObject))
    check(!partyIrcCapability(JsonParser.parseString("""{"partyChat":true,"partyChatVersion":1}""").asJsonObject))
    val grant = JsonParser.parseString("""{"type":"irc_result","scope":"party","room":"${roster.id}","expiresAt":61000}""").asJsonObject
    check(ircPartyGrantMillis(grant, roster.id, 1000) == 55000L)
    grant.addProperty("expiresAt", 21000); check(ircPartyGrantMillis(grant, roster.id, 1000) == 15000L)
    check(ircPartyGrantMillis(grant, "f".repeat(32), 1000) == null)
    check(ircPartyGrantMillis(grant, roster.id, 21000) == null)
    grant.addProperty("expiresAt", "61000"); check(ircPartyGrantMillis(grant, roster.id, 1000) == null)
    grant.addProperty("expiresAt", 61448); check(ircPartyGrantMillis(grant, roster.id, 1000) == 55000L) // Measured Windows/Cloudflare clock lag.
    grant.addProperty("expiresAt", 11448); check(ircPartyGrantMillis(grant, roster.id, 1000) == 5448L) // Skew cannot extend a short lease.
    grant.addProperty("expiresAt", 66000); check(ircPartyGrantMillis(grant, roster.id, 1000) == 55000L)
    grant.addProperty("expiresAt", 66001); check(ircPartyGrantMillis(grant, roster.id, 1000) == null)
    grant.addProperty("expiresAt", 6000); check(ircPartyGrantMillis(grant, roster.id, 1000) == null)
    grant.addProperty("expiresAt", 6001); check(ircPartyGrantMillis(grant, roster.id, 1000) == 1L)
    val roomJson = richMessage(scope = "room", room = "testing")
    val room = requireNotNull(parseRoomIrc(roomJson, "testing"))
    check(room.key != global.key && parseRoomIrc(roomJson, "friends") == null && parseGlobalChat(roomJson) == null)
    for (field in listOf("model", "revision")) {
        val invalid = richMessage(); invalid.addProperty(field, "1"); check(parseGlobalChat(invalid) == null)
    }
    val reply = richMessage().apply { add("reply", JsonParser.parseString("""{"from":"Bob","messageId":"${"d".repeat(32)}","text":"quoted"}""")) }
    check(parseGlobalChat(reply)?.reply?.messageId == "d".repeat(32))
    reply.getAsJsonObject("reply").addProperty("messageId", "untrusted"); check(parseGlobalChat(reply) == null)
    val image = """{"id":"attachment:1","type":"image","url":"https://cdn.discordapp.com/attachments/1/2/image.png?ex=123&hm=abc","name":"image.png","width":80,"height":60}"""
    val attached = richMessage().apply { addProperty("text", ""); add("media", JsonParser.parseString("[$image]")) }
    val attachment = requireNotNull(parseGlobalChat(attached)).media.single()
    check(attachment.url.rawQuery == "ex=123&hm=abc" && attachment.width == 80)
    for (url in listOf("http://cdn.discordapp.com/a.png", "https://user:pass@cdn.discordapp.com/a.png", "https://cdn.discordapp.com:444/a.png", "https://cdn.discordapp.com/a.png#fragment")) {
        val invalid = attached.deepCopy(); invalid.getAsJsonArray("media")[0].asJsonObject.addProperty("url", url)
        check(parseGlobalChat(invalid) == null)
    }
    attached.add("media", JsonParser.parseString("[$image,$image]")); check(parseGlobalChat(attached) == null)
    attached.add("media", JsonParser.parseString("[]")); check(parseGlobalChat(attached) == null)
    val reactions = richMessage().apply { add("reactions", JsonParser.parseString("""[{"emoji":"👍","count":2,"mine":true}]""")) }
    check(parseGlobalChat(reactions)?.reactions == listOf(IrcReaction("👍", 2, true)))
    reactions.getAsJsonArray("reactions")[0].asJsonObject.addProperty("emoji", "unknown"); check(parseGlobalChat(reactions) == null)
    val state = IrcChatStore()
    check(state.boundary("account1|endpoint|lease|1"))
    check(state.accept(global, 1000) && !state.accept(global, 1001) && state.accept(room, 1000))
    check(state.manageable(global.key, "b".repeat(32), 1001) && !state.manageable(global.key, "e".repeat(32), 1001))
    val unknown = requireNotNull(parseIrcUpdate(change(id = "f".repeat(32))))
    check(state.apply(unknown, 1002) == null)
    val updated = requireNotNull(state.apply(requireNotNull(parseIrcUpdate(change())), 1002))
    check(updated.text == "changed" && state.get(room.key)?.text == "hello")
    check(state.apply(requireNotNull(parseIrcUpdate(change())), 1003) == null) // Replayed revision.
    val reacted = change(revision = 2, operation = "reaction").apply { remove("text"); add("reactions", JsonParser.parseString("""[{"emoji":"❤️","count":1,"mine":true}]""")) }
    check(state.apply(requireNotNull(parseIrcUpdate(reacted)), 1004)?.reactions?.single()?.mine == true)
    check(state.apply(requireNotNull(parseIrcUpdate(change(revision = 3, operation = "delete"))), 1005)?.let { it.deleted && it.media.isEmpty() && it.reactions.isEmpty() } == true)
    check(state.apply(requireNotNull(parseIrcUpdate(change(revision = 4))), 1006) == null) // Deleted IDs never resurrect.
    check(!state.manageable(global.key, "b".repeat(32), 1006))
    check(state.boundary("account2|endpoint|lease|1") && state.values().isEmpty())
    check(state.accept(global, 2000)); state.expire(2000 + IRC_MANAGEMENT_MILLIS + 1); check(state.values().isEmpty())
    for (index in 0..128) state.accept(global.copy(id = index.toString(16).padStart(32, '0')), 3000)
    check(state.values().size == 128 && state.get(global.copy(id = "0".repeat(32)).key) == null)
    val tag = global.key.tag
    check(tag != room.key.tag && tag.endsWith(global.id))
    for (scope in listOf("global", "room", "party")) {
        val action = IrcChatAction("media", scope, global.id, 3)
        check(parseIrcChatAction(action.command) == action)
        check(parseIrcChatAction(IrcChatAction("manage", scope, global.id).command)?.kind == "manage")
        val label = ircPrefix(scope); val colors = ircPrefixColors(label.length)
        check(colors.size == label.length && colors.first() == 0x44E4EF && colors.last() == 0xA46BFF && colors.distinct().size > 5)
    }
    for (action in listOf("/sm ircaction media global ${global.id} 4", "/sm ircaction media room ${global.id} -1", "/sm ircaction manage global ${global.id} 0", "/sm ircaction manage global bad", "/sm ircaction media other ${global.id} 0")) check(parseIrcChatAction(action) == null)
    check(ircThumbnailLayout(100, 320, 9) == IrcThumbnailLayout(96, 72, 8))
    check(ircThumbnailLayout(50, 320, 9) == IrcThumbnailLayout(48, 36, 4))
    check(ircThumbnailLayout(200, 320, 9) == IrcThumbnailLayout(192, 144, 16))
    check(ircThumbnailLayout(200, 40, 9) == IrcThumbnailLayout(40, 30, 4))
    check(ircThumbnailLayout(100, 40, 9).title("[Image 1]", 48, 0) == "[1]")
    check(ircThumbnailLayout(100, 320, 9).title("[Image 1]", 48, 0) == "[Image 1]")
    check(ircThumbnailLayout(0, 320, 9) == ircThumbnailLayout(50, 320, 9))
    check(ircThumbnailLayout(999, 320, 9) == ircThumbnailLayout(200, 320, 9))
    check(ircThumbnailLayout(100, 0, 0) == IrcThumbnailLayout(1, 1, 1))
    for (percent in 50..200) for (width in listOf(40, 73, 96, 192, 320)) for (lineHeight in 9..18) {
        val layout = ircThumbnailLayout(percent, width, lineHeight)
        check(layout.width in 1..width && layout.height > 0 && layout.rows > 0)
        check((layout.rows - 1) * lineHeight < layout.height && layout.rows * lineHeight >= layout.height)
    }
    check(ircMessageHeader(global, false).string == "Alice: ")
    check(ircMessageHeader(global.copy(source = "discord"), false).string == "@Alice: ")
    val replied = global.copy(reply = GlobalChatReply("Bob", "https://cdn.discordapp.com/attachments/1/2/clip.gif?signature=${"x".repeat(180)}"))
    val replyHeader = ircMessageHeader(replied, false)
    check(replyHeader.string == "Alice ↩ Bob: " && !replyHeader.string.contains("shortened"))
    check(replyHeader.siblings.first().style.hoverEvent is net.minecraft.network.chat.HoverEvent.ShowText)
    val names = me.mycellium.skymyce.features.social.CosmeticNameMap()
    names.update(mapOf("Alice" to net.minecraft.network.chat.Component.literal("✈ Alice ✈")))
    val displayed = StringBuilder()
    names.sequence(replyHeader.visualOrderText).accept { _, _, code -> displayed.appendCodePoint(code); true }
    check(displayed.toString() == "✈ Alice ✈ ↩ Bob: ")
    check(!IrcChatRendering.addingMessage())
    check(runCatching {
        IrcChatRendering.withTaggedMessage {
            check(IrcChatRendering.addingMessage())
            IrcChatRendering.withTaggedMessage { check(IrcChatRendering.addingMessage()) }
            check(IrcChatRendering.addingMessage())
            error("test insertion failure")
        }
    }.isFailure)
    check(!IrcChatRendering.addingMessage())
    check(relayPacketSizeAllowed(65536, "irc_update") && !relayPacketSizeAllowed(65537, "irc_update"))
    val mode = IrcMode(); mode.selectParty(); check(mode.selected && mode.party && !mode.global)
    mode.command("chat global"); check(mode.global && !mode.party); mode.selectParty(); check(mode.party && !mode.global)
    mode.selected = false; check(!mode.global && !mode.party)
    check(ircHttpsUrl("https://example.com/image.png") == URI("https://example.com/image.png")) // Unknown HTTPS remains a link; fetch has a narrower allowlist.
    val subscription = GlobalChatSubscription()
    val pending = requireNotNull(subscription.take(true, 0)); subscription.queued(pending, true)
    subscription.reset(); check(!subscription.current(pending) && !subscription.enabled)
    println("IRC model checks passed")
}
fun main() = checkIrcChatModel()
