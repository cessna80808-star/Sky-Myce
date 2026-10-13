package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.ui.MenuCanvas
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
    val retainedGif = global.copy(media = listOf(IrcMedia("retained", "gif", URI("https://media.tenor.com/retained.gif"))))
    check(state.accept(retainedGif, 2000))
    val later = 3000 + IRC_MANAGEMENT_MILLIS + 1
    state.expire(later)
    state.retain(setOf(global.key.tag), later)
    check(state.get(global.key) == retainedGif && !state.manageable(global.key, "b".repeat(32), later))
    state.retain(emptySet(), later); check(state.values().isEmpty())
    for (index in 0..1024) state.accept(global.copy(id = index.toString(16).padStart(32, '0')), 3000)
    check(state.values().size == 1024 && state.get(global.copy(id = "0".repeat(32)).key) == null)
    val oldest = state.values().first()
    state.expire(later)
    state.retain(setOf(oldest.key.tag), later)
    check(state.values() == listOf(oldest) && !state.manageable(oldest.key, "b".repeat(32), later))
    val queued = global.copy(id = "e".repeat(32))
    check(state.accept(queued, later))
    state.retain(setOf(oldest.key.tag), later + 15_000)
    check(state.get(queued.key) == queued)
    state.retain(setOf(oldest.key.tag), later + 15_001)
    check(state.get(queued.key) == null && state.get(oldest.key) == oldest)
    val tag = global.key.tag
    check(tag != room.key.tag && tag.endsWith(global.id))
    for (scope in listOf("global", "room", "party")) {
        val action = IrcChatAction("media", scope, global.id, 3)
        check(parseIrcChatAction(action.command) == action)
        check(parseIrcChatAction(IrcChatAction("manage", scope, global.id).command)?.kind == "manage")
        for (kind in listOf("reply", "react")) {
            check(parseIrcChatAction(IrcChatAction(kind, scope, global.id).command) == IrcChatAction(kind, scope, global.id))
            check(parseIrcChatAction("/sm ircaction $kind $scope ${global.id} 0") == null)
        }
        for (manageable in listOf(false, true)) {
            val controls = ircMessageActions(global.copy(scope = scope), manageable)
            val kinds = if (manageable) listOf("reply", "react", "manage") else listOf("reply", "react")
            check(controls.string == if (manageable) " [↩] [+] [⋯]" else " [↩] [+]")
            check(controls.siblings.map { parseIrcChatAction((it.style.clickEvent as net.minecraft.network.chat.ClickEvent.RunCommand).command()) } ==
                kinds.map { IrcChatAction(it, scope, global.id) })
            check(controls.siblings.all { it.style.hoverEvent is net.minecraft.network.chat.HoverEvent.ShowText })
        }
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
    val mediaAction = IrcChatAction("media", "global", global.id, 0)
    for (row in 0..15) check(parseIrcChatAction(mediaAction.copy(previewRow = row).command) == mediaAction.copy(previewRow = row))
    for (row in listOf("-1", "16", "bad")) check(parseIrcChatAction(mediaAction.command + " $row") == null)
    check(parseIrcChatAction(IrcChatAction("reply", "global", global.id).command + " 0 0") == null)
    fun mediaRow(text: String, row: Int) = net.minecraft.network.chat.Component.literal(text).withStyle {
        it.withClickEvent(net.minecraft.network.chat.ClickEvent.RunCommand(mediaAction.copy(previewRow = row).command))
    }.visualOrderText
    val pose = org.joml.Matrix3x2f().scale(2f).translate(4f, 5f)
    val parameters = net.minecraft.client.gui.ActiveTextCollector.Parameters(pose)
    val captured = IrcThumbnailCollector(9, 0.0)
    // Chat traverses bottom-first. Empty rows must reserve space despite having no drawable glyphs.
    for (row in 7 downTo 0) captured.accept(net.minecraft.client.gui.TextAlignment.LEFT, 0, 20 + row * 9,
        parameters, mediaRow(if (row == 0) "[GIF 1]" else "\u00a0".repeat(24), row))
    val anchor = captured.anchors.getValue(mediaAction)
    check(anchor.top == 19 && anchor.visibleTop == 19 && anchor.visibleBottom == 91)
    check(anchor.rectangle(96) == net.minecraft.client.gui.navigation.ScreenRectangle(8, 48, 192, 144))
    val partial = IrcThumbnailCollector(9, 0.0)
    for (row in 0..2) partial.accept(net.minecraft.client.gui.TextAlignment.LEFT, 0, 20 + row * 9,
        parameters, mediaRow(if (row == 0) "[GIF 1]" else "\u00a0".repeat(24), row))
    check(partial.anchors.getValue(mediaAction).rectangle(96).height() == 54)
    val middle = IrcThumbnailCollector(9, 0.0)
    for (row in 4..7) middle.accept(net.minecraft.client.gui.TextAlignment.LEFT, 0, 20 + row * 9,
        parameters, mediaRow("\u00a0".repeat(24), row))
    val middleAnchor = middle.anchors.getValue(mediaAction)
    check(middleAnchor.top == anchor.top && middleAnchor.visibleTop == 55 && middleAnchor.visibleBottom == 91)
    check(middleAnchor.rectangle(96) == net.minecraft.client.gui.navigation.ScreenRectangle(8, 120, 192, 72))
    val clipped = anchor.copy(parameters = parameters.withScissor(net.minecraft.client.gui.navigation.ScreenRectangle(8, 48, 72, 22)))
    check(clipped.rectangle(96) == net.minecraft.client.gui.navigation.ScreenRectangle(8, 48, 72, 22))
    check(anchor.copy(parameters = parameters.withScissor(net.minecraft.client.gui.navigation.ScreenRectangle(300, 300, 100, 100))).rectangle(96) == net.minecraft.client.gui.navigation.ScreenRectangle.empty())
    captured.accept(net.minecraft.client.gui.TextAlignment.LEFT, 0, 1000, parameters, mediaRow("body [GIF 1]", 0))
    check(captured.anchors.getValue(mediaAction).visibleBottom == 91)
    check(ircMessageHeader(global, false).string == "Alice: ")
    for (emoji in IRC_REACTIONS) {
        val reaction = ircReactionText(IrcReaction(emoji, 12))
        check(reaction.string == ircReadableText(emoji).single().text + " 12")
        var imageGlyph = false
        reaction.visualOrderText.accept { _, style, codepoint ->
            if (codepoint in 0xE000..0xF8FF) imageGlyph = style.font == net.minecraft.network.chat.FontDescription.Resource(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("skymyce", "irc_emoji")) && style.color?.value == 0xFFFFFF
            true
        }
        check(imageGlyph)
    }
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
    val joined = "👩🏽‍💻 👨‍👩‍👧‍👦 🇸🇪 ❤️"
    val custom = "<a:party_blob:123456789012345678>"
    val unicode = richMessage().apply { addProperty("text", "$joined $custom") }
    check(parseGlobalChat(unicode)?.text == "$joined $custom")
    val readable = ircReadableText("Hello $joined $custom")
    check(readable.joinToString("") { it.original ?: it.text } == "Hello $joined $custom")
    check(readable.last().text == ":party_blob:" && readable.last().original == custom)
    check(readable.any { it.original == "👩🏽‍💻" } && readable.any { it.original == "👨‍👩‍👧‍👦" })
    check(readable.all { it.text.codePoints().noneMatch { point -> point in 0x1F000..0x1FAFF || point == 0x200D } })
    val flag = ircReadableText("🏳️‍⚧️").single()
    check(flag.emoji && flag.original == "🏳️‍⚧️" && flag.text.length == 1 && flag.text.single().code in 0xE000..0xF8FF)
    check(ircReadableText("🏳‍⚧").single().text == flag.text)
    check(ircReadableText("☀\uFE0E").single() == IrcReadableText("☀\uFE0E"))
    check(ircReadableText("©️ #️⃣ 1️⃣ 👩🏽‍💻 🇸🇪 ❤️").count { it.emoji } == 6)
    check(readable.filter { it.original != null && it.original != custom }.all { it.emoji && it.text.length == 1 })
    check(ircReadableText("Hello 123").single() == IrcReadableText("Hello 123"))
    val emojiFont = net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath("skymyce", "irc_emoji"))
    val styledFlag = net.minecraft.network.chat.Component.literal(flag.text).withStyle { it.withFont(emojiFont)
        .withClickEvent(net.minecraft.network.chat.ClickEvent.RunCommand("/sm irc-action react global ${global.id}"))
        .withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal("old actions")))
        .withInsertion("expired") }
    val passive = ircPassiveText(styledFlag)
    check(passive.string == flag.text)
    passive.visualOrderText.accept { _, style, _ ->
        check(style.font == emojiFont && style.clickEvent == null && style.hoverEvent == null && style.insertion == null); true
    }
    checkIrcEmojiFont()
    val unsigned = URI("https://cdn.discordapp.com/attachments/1/2/clip.gif")
    val signed = URI("https://media.discordapp.net/attachments/1/2/clip.gif?ex=123&is=2&hm=abc")
    check(ircMediaIdentity(unsigned) == ircMediaIdentity(signed))
    check(ircMediaIdentity(URI("HTTPS://MEDIA.DISCORDAPP.NET:443/attachments/1/2/clip.gif?ex=123&is=2&hm=abc")) == ircMediaIdentity(unsigned))
    check(ircMediaIdentity(URI("$unsigned?width=20")) != ircMediaIdentity(unsigned))
    check(ircMediaIdentity(URI("https://example.com/clip.gif?ex=123")) != ircMediaIdentity(URI("https://example.com/clip.gif?ex=456")))
    val resource = IrcMedia("clip", "gif", signed, sourceUrl = URI("https://tenor.com/view/clip"))
    val repeated = "caption $unsigned https://tenor.com/view/clip $signed"
    val previewBody = ircBodySpans(repeated, listOf(resource), previews = true)
    check(previewBody.none { it.url != null || it.mediaIndex != null } && "caption" in previewBody.joinToString("") { it.text })
    check(ircBodySpans(repeated, listOf(resource), previews = false).count { it.mediaIndex == 0 } == 1)
    check(ircBodySpans("https://example.com/unrelated.gif", listOf(resource), previews = true).single().url != null)
    val alias = richMessage().apply { add("media", JsonParser.parseString("""[{"id":"clip","type":"gif","url":"$signed","sourceUrl":"https://tenor.com/view/clip"}]""")) }
    check(parseGlobalChat(alias)?.media?.single()?.sourceUrl == URI("https://tenor.com/view/clip"))
    alias.getAsJsonArray("media")[0].asJsonObject.addProperty("sourceUrl", "javascript:bad")
    check(parseGlobalChat(alias) == null)
    val reactionRequests = IrcReactionRequests()
    check(reactionRequests.start(global.key, "👍", true, 1000))
    check(!reactionRequests.start(global.key, "👍", true, 1001) && !reactionRequests.start(global.key, "👍", false, 1001))
    reactionRequests.observe(global.copy(reactions = listOf(IrcReaction("👍", 1, true))))
    check(reactionRequests.start(global.key, "👍", false, 1002))
    reactionRequests.finish(global.key, "👍"); check(reactionRequests.start(global.key, "👍", false, 1003))
    reactionRequests.clear(); check(reactionRequests.start(global.key, "👍", true, 1004))
    check(reactionRequests.start(global.key, "👍", false, 16004)) // Expired receipt state cannot lock the UI forever.
    check(!reactionRequests.start(global.key, "not-an-emoji", true, 16005))
    for (width in listOf(80, 320, 854, 1920)) for (height in listOf(160, 480, 1080)) {
        val bounds = ircPickerBounds(width + 20, height + 20, width, height)
        check(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= width && bounds.y + bounds.height <= height - 28)
    }
    for (width in 1..246) {
        val bounds = IrcControlBounds(4, 7, width, 64)
        for (row in 0..1) for (x in bounds.x until bounds.x + bounds.width) {
            val index = requireNotNull(ircReactionAt(bounds, x, bounds.y + 20 + row * 20))
            check(index / 3 == row && ircReactionCell(bounds, index).contains(x, bounds.y + 20 + row * 20))
            check(IRC_REACTIONS.indices.count { ircReactionCell(bounds, it).contains(x, bounds.y + 20 + row * 20) } == 1)
        }
        for ((x, y) in listOf(bounds.x - 1 to bounds.y + 20, bounds.x + bounds.width to bounds.y + 20,
            bounds.x to bounds.y + 19, bounds.x to bounds.y + 60)) check(ircReactionAt(bounds, x, y) == null)
    }
    val reorder = IrcChatStore()
    val early = requireNotNull(parseIrcUpdate(change()))
    check(reorder.apply(early, 1000) == null && reorder.values().isEmpty())
    check(reorder.apply(early, 1001) == null)
    check(reorder.accept(global, 1002) && reorder.get(global.key)?.text == "changed" && reorder.values().size == 1)
    check(!reorder.accept(global, 1003))
    reorder.clear()
    check(reorder.apply(early, 1004) == null)
    reorder.boundary("new-account")
    check(reorder.accept(global, 1005) && reorder.get(global.key)?.text == "hello")
    reorder.clear()
    check(reorder.apply(early, 1006) == null)
    check(reorder.accept(global, 16007) && reorder.get(global.key)?.text == "hello")
    // Body, media and full reaction snapshots can fan out in a different order.
    for (beforeCreate in listOf(false, true)) {
        val domains = IrcChatStore()
        val original = global.copy(reply = GlobalChatReply("Bob", "Quoted message", "d".repeat(32)))
        val latestReactions = IrcUpdate(global.key, "reaction", 3, null, null, listOf(IrcReaction("🔥", 1, true)))
        val delayedBody = IrcUpdate(global.key, "edit", 1, "actual edited body", null, emptyList())
        val newerMedia = IrcUpdate(global.key, "edit", 2, null, listOf(attachment), emptyList())
        if (!beforeCreate) check(domains.accept(original, 1000))
        domains.apply(latestReactions, 1001); domains.apply(newerMedia, 1002); domains.apply(delayedBody, 1003)
        if (beforeCreate) check(domains.accept(original, 1004))
        val merged = requireNotNull(domains.get(global.key))
        check(merged.text == "actual edited body" && merged.media == listOf(attachment) && merged.reactions == latestReactions.reactions && merged.revision == 3)
        check(merged.key == original.key && merged.reply == original.reply) // Discord preview reparsing cannot change a reply's stable reference.
        check(domains.apply(delayedBody, 1005) == null && domains.apply(newerMedia, 1006) == null)
        check(domains.apply(IrcUpdate(global.key, "delete", 2, null, null, null), 1007)?.deleted == true)
        check(domains.apply(latestReactions.copy(revision = 4, reactionRevision = 4), 1008) == null)
    }
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
    val mediaHeading = "GIF · café 😀 · a long media title"
    for (size in listOf(8.0, 10.0, 12.0)) for (available in 0..260) {
        val shown = ircMediaTextEllipsis(mediaHeading, available.toDouble(), size)
        check(MenuCanvas.textWidth(shown, size = size) <= available + 1e-9) { "Media chrome text exceeded its measured width" }
        check(shown.codePoints().noneMatch { it in 0xD800..0xDFFF }) { "Media chrome truncation split a Unicode character" }
        if (MenuCanvas.textWidth(mediaHeading, size = size) <= available) check(shown == mediaHeading)
        else check(shown.isEmpty() || shown.endsWith("…"))
    }
    println("IRC model checks passed")
}

private fun checkIrcEmojiFont() {
    fun resource(path: String) = requireNotNull(IrcReadableText::class.java.getResourceAsStream(path))
    val index = resource("/assets/skymyce/font/irc_emoji_index.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
    val font = resource("/assets/skymyce/font/irc_emoji.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    val glyphs = arrayListOf<Int>()
    for (provider in font.getAsJsonArray("providers")) {
        val definition = provider.asJsonObject
        check(net.minecraft.client.gui.font.providers.BitmapProvider.Definition.CODEC.codec().parse(
            com.mojang.serialization.JsonOps.INSTANCE, definition).result().isPresent)
        check(definition.get("type").asString == "bitmap" && definition.get("ascent").asInt == 7 && definition.get("height").asInt == 8)
        val rows = definition.getAsJsonArray("chars").map { it.asString.codePoints().toArray().toList() }
        check(rows.all { it.size == 32 })
        val path = definition.get("file").asString.substringAfter("skymyce:")
        resource("/assets/skymyce/textures/$path").use { stream ->
            val image = requireNotNull(javax.imageio.ImageIO.read(stream))
            check(image.width == 1024 && image.height == rows.size * 32 && image.colorModel.hasAlpha())
        }
        glyphs.addAll(rows.flatten().filter { it != 0 })
    }
    check(index.size() in 3001..6400 && glyphs == (0xE000 until 0xE000 + index.size()).toList())
    for ((offset, key) in index.withIndex()) {
        val unicode = key.asString.split('-').joinToString("") { String(Character.toChars(it.toInt(16))) }
        val part = ircReadableText(unicode).single()
        check(part.emoji && part.text.single().code == 0xE000 + offset && part.original == unicode)
    }
}
fun main() = checkIrcChatModel()
