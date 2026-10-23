package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional

internal fun checkIrcEmojiNames() {
    val catalog = IrcEmojiCatalog(ircEmojiKeys)
    val code = catalog::shortcode
    check(catalog.all.size == ircEmojiKeys.size)

    // Unicode-derived names, Discord aliases, keycaps, flags, tag flags, ZWJ and skin tones.
    check(code("skull") == "💀" && code("fire") == "🔥" && code("joy") == "😂" && code("sob") == "😭")
    check(code("thumbs_up") == "👍" && code("thumbsup") == "👍" && code("+1") == "👍" && code("-1") == "👎")
    check(code("heart") == "❤️" && code("pray") == "🙏" && code("ok_hand") == "👌" && code("heart_with_ribbon") == "💝")
    check(code("flag_se") == "🇸🇪" && code("keycap_1") == "1️⃣" && code("one") == "1️⃣" && code("hash") == "#️⃣")
    check(code("flag_gbeng") == "🏴󠁧󠁢󠁥󠁮󠁧󠁿")
    check(catalog.key("1f468-200d-1f4bb")?.names?.first() == "man_personal_computer")
    check(catalog.name("thumbs_up_sign_tone3")?.key == "1f44d-1f3fd" && code("SKULL") == "💀")
    val validName = Regex("[a-z0-9_+\\-]{1,$IRC_SHORTCODE_MAX}")
    for (emoji in catalog.all) {
        check(emoji.names.isNotEmpty() && emoji.names.all(validName::matches)) { emoji.key }
        // The wire text is fully qualified Unicode and still paints as this one bundled glyph.
        val shown = ircReadableText(emoji.text).single()
        check(shown.emoji && shown.text == emoji.glyph && shown.original == emoji.text) { emoji.key }
        check(catalog.text(emoji.text) === emoji)
        val descriptive = checkNotNull(catalog.descriptiveName(emoji)) { emoji.key }
        check(catalog.name(descriptive) === emoji && catalog.exactGlyph(descriptive) == emoji.glyph) { emoji.key }
    }
    check(catalog.grid.none { it.toned } && catalog.grid.map { it.category }.zipWithNext().all { (a, b) -> a <= b })
    check(catalog.search("sku", 8).first().first.text == "💀" && catalog.search("skull", 8).first().second == "skull")
    check(catalog.search("thumbs", 20).none { it.first.toned } && catalog.search("thumbs_up_sign_tone", 20).all { it.first.toned })
    check(catalog.search("", 8).isEmpty() && catalog.search("zzzznotanemoji", 8).isEmpty())

    // Typing and sending in IRC: only complete known codes convert.
    check(ircReplaceShortcodes("gg :skull: :fire:", code) == "gg 💀 🔥")
    check(ircReplaceShortcodes("::fire::", code) == ":🔥:" && ircReplaceShortcodes(":fire::skull:", code) == "🔥💀")
    for (unchanged in listOf(":nope: stays", "at 12:30:45", "12:100:5", "a:fire:", ":fire:x", "https://example.com/:fire:",
        "www.example.com/:skull:", "<:skull:123456789012345678>", "<a:fire:123456789012345678>", "no colons", ":", "::"))
        check(ircReplaceShortcodes(unchanged, code) == unchanged) { unchanged }
    val typed = "hi :skull:"
    val (start, emoji) = checkNotNull(ircShortcodeBefore(typed, typed.length, code))
    check(start == 3 && emoji == "💀")
    val edit = ircReplaceRange(typed, start, typed.length, emoji)
    check(edit.text == "hi 💀" && edit.cursor == edit.text.length)
    val middle = "a :fire: b"
    val middleEdit = ircShortcodeBefore(middle, 8, code)!!.let { (at, text) -> ircReplaceRange(middle, at, 8, text) }
    check(middleEdit.text == "a 🔥 b" && middleEdit.cursor == 4 && middleEdit.text[middleEdit.cursor] == ' ')
    check(ircShortcodeBefore("12:30:", 6, code) == null && ircShortcodeBefore("x :nope:", 8, code) == null && ircShortcodeBefore("::", 2, code) == null)
    check(ircShortcodeQuery("hey :sku", 8) == (4 to "sku") && ircShortcodeQuery("hey :sku now", 8) == (4 to "sku"))
    check(ircShortcodeQuery("hey :s", 6) == null && ircShortcodeQuery("12:30", 5) == null && ircShortcodeQuery("hey :skull", 7) == null)
    check(ircShortcodeQuery("http://a.b/:sku", 15) == null && ircShortcodeQuery(":fire:", 6) == null)
    check(ircEmojiProse("hello") && ircEmojiProse("/pc :sk") && ircEmojiProse("/msg Name hi") && ircEmojiProse("/party chat hi"))
    check(!ircEmojiProse("/warp hub") && !ircEmojiProse("/party invite Name") && !ircEmojiProse("/p invite Name") && !ircFreeTextCommand("sm chat"))

    // Recents: newest first, unique, bounded; persisted shape validated.
    check(ircRecentEmoji(listOf("a", "b", "c"), "b") == listOf("b", "a", "c"))
    val many = (1..20).fold(emptyList<String>()) { recent, index -> ircRecentEmoji(recent, "e$index") }
    check(many.size == IRC_RECENT_EMOJI && many.first() == "e20" && many.last() == "e5")
    val saved = com.google.gson.JsonParser.parseString("""{"version":1,"recent":["💀","💀","🔥",""]}""")
    check(parseIrcRecentEmoji(saved) == listOf("💀", "🔥") && parseIrcRecentEmoji(null).isEmpty())
    check(runCatching { parseIrcRecentEmoji(com.google.gson.JsonParser.parseString("""{"version":2,"recent":[]}""")) }.isFailure)

    // Server chat: emoji become descriptive names, text-style symbols stay, and the 256-character limit holds.
    val server = catalog::serverShortcode
    fun send(text: String, limit: Int = SERVER_CHAT_LIMIT) = ircEmojiToShortcodes(text, limit, server)
    check(send("gg 💀") == "gg :skull:" && send("💝") == ":heart_with_ribbon:" && send("👍") == ":thumbs_up:")
    check(send("🇸🇪 1️⃣ #️⃣") == ":flag_se: :keycap_1: :keycap_hash:" && send("🔥🔥") == ":fire::fire:")
    for (sequence in listOf("👩🏽‍💻", "🏳️‍🌈", "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "❤️‍🔥", "👍🏿")) {
        val name = send(sequence)
        check(name.matches(Regex(":[a-z0-9_]+:")) && name == ":${catalog.descriptiveName(catalog.text(ircEmojiText(
            sequence.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { it.toString(16) }))!!)}:") { sequence }
    }
    for (unchanged in listOf("plain text", "© ™ ♥ →", "☀︎", "é ñ 中文", "")) check(send(unchanged) == unchanged) { unchanged }
    check(send("©️").startsWith(":copyright"))
    val full = "x".repeat(250) + "💀💀"
    check(send(full) == full) // Neither name fits: nothing is replaced and nothing is dropped.
    val partial = send("x".repeat(245) + "💀 💀")
    check(partial == "x".repeat(245) + ":skull: 💀" && partial.length <= SERVER_CHAT_LIMIT)
    check(send("x".repeat(300) + "💀") == "x".repeat(300) + "💀")
    check(send("💀".repeat(40), 100).let { it.length <= 100 && it.startsWith(":skull:") && it.endsWith("💀") })

    // Received server chat: only exact known names, never links or times.
    val glyph = catalog::exactGlyph
    val skull = catalog.text("💀")!!.glyph
    check(ircShortcodeRuns("gg :skull: 12:30:45 https://a.b/:fire: :nope: :Skull:", glyph) ==
        listOf("gg " to false, skull to true, " 12:30:45 https://a.b/:fire: :nope: :Skull:" to false))
    check(ircShortcodeRuns("12:30:45 and :nope:", glyph) == null && ircShortcodeRuns("no colons", glyph) == null)
    val click = ClickEvent.SuggestCommand("/msg Friend ")
    val received = Component.literal("[MVP+] Friend: ").append(Component.literal("gg :skull:").withStyle(Style.EMPTY.withColor(0xAAAAAA).withClickEvent(click)))
    val rendered = checkNotNull(ircShortcodeComponent(received, glyph))
    val parts = arrayListOf<Pair<String, Style>>()
    rendered.visit({ style, text -> parts += text to style; Optional.empty<Unit>() }, Style.EMPTY)
    check(parts.joinToString("") { it.first } == "[MVP+] Friend: gg $skull")
    val painted = parts.single { it.first == skull }.second
    check(painted.font == ircEmojiStyle.font && painted.clickEvent == click && painted.color?.value == 0xFFFFFF)
    check(parts.single { it.first == "gg " }.second.color?.value == 0xAAAAAA)
    check(ircShortcodeComponent(Component.literal("time 12:30:45"), glyph) == null)

    // Round trip: emoji -> :name: on send -> glyph for Sky-Myce readers, and -> emoji again in IRC.
    for (emojiText in listOf("💀", "🔥", "😂", "👍", "❤️", "💝", "🇸🇪", "1️⃣", "👩🏽‍💻", "🏳️‍🌈", "🙏", "😭")) {
        val sent = send("gg $emojiText")
        check(ircReplaceShortcodes(sent, code) == "gg $emojiText") { emojiText }
        check(ircShortcodeRuns(sent, glyph) == listOf("gg " to false, catalog.text(emojiText)!!.glyph to true)) { emojiText }
    }
}
