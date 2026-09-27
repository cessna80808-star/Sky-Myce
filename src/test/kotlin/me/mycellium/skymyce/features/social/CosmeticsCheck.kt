package me.mycellium.skymyce.features.social

import com.google.gson.JsonParser
import me.mycellium.skymyce.features.instances.dungeons.friends.relayPacketSizeAllowed
import java.util.UUID
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.ClickEvent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.client.StringSplitter

fun checkCosmetics() {
    fun parse(text: String) = parseCosmeticProfile(JsonParser.parseString(text).asJsonObject)
    val raw = """{"uuid":"${"a".repeat(32)}","name":"Hawk","scale":1.5,"revision":1,"updatedAt":1700000000000}"""
    val profile = requireNotNull(parse(raw))
    check(profile.name == "Hawk" && profile.scale == 1.5f)
    check(parse(raw.replace("Hawk", "✦ Hawk • ☠"))?.name == "✦ Hawk • ☠")
    listOf(raw.replace("1.5", "3.01"), raw.replace("1.5", "0.09"), raw.replace("1.5", "\"NaN\""),
        raw.replace("\"Hawk\"", "123"), raw.replace("Hawk", "§aHawk"), raw.replace("Hawk", "a\\nb"),
        raw.replace("Hawk", "x".repeat(33)), raw.replace("Hawk", "<click:run>"), raw.replace("Hawk", "@everyone"),
        raw.replace("\"revision\":1", "\"revision\":-1"), raw.replace("\"revision\":1", "\"revision\":1.5"),
        raw.dropLast(1) + ",\"discord\":\"private\"}").forEach { check(parse(it) == null) }
    val cache = CosmeticCache(2)
    check(cache.apply(profile, 1000))
    val reset = profile.copy(name = null, scale = 1f, revision = 3, updatedAt = profile.updatedAt + 100)
    check(cache.apply(reset, 1100))
    check(!cache.apply(profile.copy(revision = 2), 1200)) // Late snapshot cannot undo a live reset.
    check(!cache.apply(profile.copy(revision = 3), 1200)) // Same revision must not change payload.
    check(cache.get(profile.uuid, 1300) == reset)
    check(cache.get(profile.uuid, 601101) == null) // Bounded outage rendering, without network work in a hook.
    check(cache.apply(reset, 700000) && cache.get(profile.uuid, 700001) == reset) // Reconnect reads can refresh identical revisions.
    cache.apply(profile.copy(uuid = UUID(0, 2)), 700002); cache.apply(profile.copy(uuid = UUID(0, 3)), 700003)
    check(cache.size == 2 && cache.get(profile.uuid, 700004) == null)
    cache.clear(); check(cache.size == 0)
    // F5 keeps reading the local profile while cross-lobby discovery fills the bounded cache.
    val rendered = CosmeticCache()
    rendered.apply(profile, 1000)
    repeat(511) { rendered.apply(profile.copy(uuid = UUID(0, it.toLong() + 1)), 1001) }
    repeat(40) { frame ->
        check(rendered.get(profile.uuid, 2000 + frame.toLong(), touch = true)?.scaleY == 1.5f)
        repeat(16) { rendered.apply(profile.copy(uuid = UUID(1, frame * 16L + it)), 2000 + frame.toLong()) }
        check(rendered.size == 512 && rendered.get(profile.uuid, 2100 + frame.toLong()) == profile)
    }
    check(rendered.get(profile.uuid, 601000, touch = true) == profile)
    check(rendered.get(profile.uuid, 601001, touch = true) == null) // Rendering/reconnection cannot renew offline TTL.
    check(rendered.apply(reset, 601002) && rendered.get(profile.uuid, 601003, touch = true) == reset)
    check(!rendered.apply(profile, 601004)) // A real reset still wins over a delayed pre-reset snapshot.
    rendered.clear(); check(rendered.get(profile.uuid, 601005, touch = true) == null) // Account/logout clears still apply.
    check(parse(raw.replace("\"Hawk\"", "null").replace("1.5", "1").replace("\"revision\":1", "\"revision\":0").replace("1700000000000", "0")) != null)
    check(parse(raw.replace("1.5", "0.1"))?.scale == .1f)
    check(parse(raw.replace("1.5", "3.0"))?.scale == 3f)
    val extended = JsonParser.parseString(raw).asJsonObject.apply {
        add("nameStyle", JsonParser.parseString("""{"text":"Hawk","bold":true,"color":"gold"}"""))
        addProperty("scaleX", .6); addProperty("scaleY", 1.5); addProperty("scaleZ", 2)
    }
    val axes = requireNotNull(parseCosmeticProfile(extended))
    check(axes.scaleX == .6f && axes.scaleY == 1.5f && axes.scaleZ == 2f)
    check(axes.nameStyle!!.style.isBold)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleX", 3.01) }) == null)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleX", .1) })?.scaleX == .1f)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleZ", 3) })?.scaleZ == 3f)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleY", "1.5") }) == null)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("name", "Wrong") }) == null)
    for (unsafe in listOf("""{"text":"Hawk","clickEvent":{"action":"run_command","command":"/op"}}""", """{"translate":"anything"}""", """{"text":"Hawk","font":"remote:font"}""", """{"text":"Hawk","bold":"true"}""")) {
        check(runCatching { parseCosmeticText(JsonParser.parseString(unsafe)) }.isFailure)
    }
    fun text(sequence: FormattedCharSequence) = buildString { sequence.accept { _, _, code -> appendCodePoint(code); true } }
    val names = CosmeticNameMap()
    val replacement = parseCosmeticText(JsonParser.parseString("""{"text":"Sky","bold":true,"extra":[{"text":" Hawk","color":"#67ccf2"}]}"""))
    check(parseCosmeticText(JsonParser.parseString("""{"text":"✦ Sky","extra":[{"text":" Hawk ☠"}]}""")).string == "✦ Sky Hawk ☠")
    check(names.update(mapOf("Alice" to replacement, "Bob" to Component.literal("Alice"))))
    check(!names.update(mapOf("Alice" to replacement, "Bob" to Component.literal("Alice"))))
    val input = Component.literal("[MVP] Al").append(Component.literal("ice").withStyle(Style.EMPTY.withItalic(true))).append(" / Bob / Alice123 / xAlice / Alice_x")
    val result = names.sequence(input.visualOrderText)
    check(text(result) == "[MVP] Sky Hawk / Alice / Alice123 / xAlice / Alice_x")
    check(names.sequence(result) === result) // No recursive replacement inside another cosmetic name.
    check(input.string == "[MVP] Alice / Bob / Alice123 / xAlice / Alice_x")
    check(text(names.string("§aALICE, Alice.")) == "Sky Hawk, Sky Hawk.")
    val action = ClickEvent.SuggestCommand("/party Alice")
    val clickable = names.sequence(Component.literal("Alice").withStyle { it.withClickEvent(action) }.visualOrderText)
    clickable.accept { _, style, _ -> check(style.clickEvent == action); true }
    val splitter = StringSplitter { _, style -> if (style.isBold) 2f else 1f }
    check(splitter.stringWidth(names.string("Alice")) == 16f) // Styled displayed width, not five real-name characters.
    check(text(names.string("Unknown")) == "Unknown")
    names.update(emptyMap()); check(text(names.string("Alice")) == "Alice")
    CosmeticNames.enabled = true; CosmeticNames.update(mapOf("Alice" to replacement))
    check(CosmeticNames.display(CosmeticNames.original(Component.literal("Alice"))).string == "Alice")
    val displayed = CosmeticNames.display(Component.literal("Alice").visualOrderText)
    check(text(displayed) == "Sky Hawk")
    CosmeticNames.enterOriginal()
    try { check(text(CosmeticNames.display(displayed)) == "Alice") } finally { CosmeticNames.exitOriginal() }
    CosmeticNames.enabled = false
    check(text(CosmeticNames.display(displayed)) == "Alice")
    CosmeticNames.update(emptyMap())
    // A sender absent from the tab roster resolves through the relay and works in chat and menu labels.
    val remote = CosmeticIdentities()
    remote.observe("Alice", null, 1000, priority = true)
    check(remote.uuids().isEmpty() && remote.names(1000) == listOf("Alice"))
    fun lookup(records: String = raw, identities: String? = null) = JsonParser.parseString(
        "{\"records\":[$records]" + (identities?.let { ",\"identities\":[$it]" } ?: "") + "}").asJsonObject
    val resolvedIdentity = """{"uuid":"${"a".repeat(32)}","name":"Alice"}"""
    val resolved = requireNotNull(parseCosmeticsLookup(lookup(identities = resolvedIdentity), emptyList(), listOf("alice")))
    remote.complete(listOf("Alice"), resolved.identities, 1100)
    val remoteCache = CosmeticCache()
    resolved.records.forEach { remoteCache.apply(it, 1100) }
    val remoteNames = CosmeticNameMap()
    remoteNames.update(remote.resolved().mapNotNull { (uuid, name) -> remoteCache.get(uuid, 1100)?.nameStyle?.let { name to it } }.toMap())
    check(text(remoteNames.string("Party > Alice: hi")) == "Party > Hawk: hi")
    check(text(remoteNames.string("1. Alice • Profile")) == "1. Hawk • Profile")
    check(remote.names(301099).isEmpty() && remote.names(301100) == listOf("Alice"))
    remote.complete(listOf("Alice"), emptyMap(), 301100)
    check(remote.resolved().isEmpty()) // A name no longer resolved by the server loses its old UUID.
    remote.observe("Alice", null, 302000, priority = true)
    check(remote.names(302000).isEmpty()) // Repeated chat and menu observations cannot bypass a negative cache.
    remote.reconnect(); check(remote.names(302001) == listOf("Alice"))
    remote.expire(902001); check(remote.size == 0)

    val retained = CosmeticIdentities(2)
    retained.observe("Alice", profile.uuid, 1000)
    retained.expire(301000); check(retained.uuids() == listOf(profile.uuid)) // Refresh after leaving the lobby.
    retained.observe("Bob", UUID(0, 2), 301000)
    retained.observe("MenuSearch", null, 301001, priority = true)
    check(retained.size == 2 && "MenuSearch" in retained.names(301001))
    retained.observe("invalid name", null, 301002); check(retained.size == 2)
    retained.expire(901002); check(retained.size == 0)
    val crowded = CosmeticIdentities()
    repeat(700) { crowded.observe("Cached$it", null, 1000) }
    crowded.observe("GuildSender", null, 1100, priority = true)
    repeat(700) { crowded.observe("Cached$it", null, 1200) }
    check(crowded.size == 512 && crowded.names(1200).first() == "GuildSender")
    crowded.complete(listOf("GuildSender"), mapOf("guildsender" to profile.uuid), 1300)
    check(crowded.resolved()[profile.uuid] == "GuildSender")
    crowded.clear(); check(crowded.size == 0 && crowded.resolved().isEmpty())

    check(parseCosmeticsLookup(lookup(), listOf(profile.uuid), emptyList()) != null) // Old UUID-only relay response.
    check(parseCosmeticsLookup(lookup(identities = resolvedIdentity), listOf(profile.uuid), listOf("Alice")) != null) // Deduplicated UUID/name union.
    check(parseCosmeticsLookup(lookup("", ""), emptyList(), listOf("Unknown"))?.records?.isEmpty() == true)
    check(parseCosmeticsLookup(lookup(identities = resolvedIdentity), emptyList(), listOf("Mallory")) == null)
    check(parseCosmeticsLookup(lookup(), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup(identities = resolvedIdentity.replace("Alice", "Alice other")), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup(identities = "$resolvedIdentity,$resolvedIdentity"), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup(identities = resolvedIdentity.replace("a".repeat(32), "b".repeat(32))), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup("$raw,$raw", resolvedIdentity), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup("", resolvedIdentity), emptyList(), listOf("Alice")) == null)
    check(parseCosmeticsLookup(lookup(identities = resolvedIdentity), listOf(profile.uuid), emptyList()) == null)
    // Sixteen valid styled profiles exceed the ordinary relay-frame limit without exceeding the batch cap.
    val styled = extended.deepCopy().apply {
        addProperty("name", "H" + "a".repeat(15))
        add("nameStyle", JsonParser.parseString("""{"text":"H","extra":[${List(15) { """{"text":"a","color":"#67ccf2","bold":true,"italic":false,"underlined":true}""" }.joinToString(",") }]}"""))
    }
    val batch = lookup(List(16) { index -> styled.deepCopy().apply { addProperty("uuid", (index + 1).toString(16).padStart(32, '0')) }.toString() }.joinToString(","))
        .apply { addProperty("type", "cosmetics_result") }
    val batchIds = (1..16).map { UUID(0, it.toLong()) }
    check(parseCosmeticsLookup(batch, batchIds, emptyList())?.records?.size == 16)
    check(batch.toString().length in 8193..65536)
    check(relayPacketSizeAllowed(batch.toString().length, "cosmetics_result"))
    check(!relayPacketSizeAllowed(batch.toString().length, "message"))
    check(!relayPacketSizeAllowed(65537, "cosmetics_result"))
    check(cosmeticLookupCurrent(1, 1, profile.uuid, profile.uuid, profile.uuid, true))
    check(!cosmeticLookupCurrent(2, 1, profile.uuid, profile.uuid, profile.uuid, true))
    check(!cosmeticLookupCurrent(1, 1, profile.uuid, profile.uuid, UUID(0, 2), true))
    check(!cosmeticLookupCurrent(1, 1, null, profile.uuid, profile.uuid, true))
    check(!cosmeticLookupCurrent(1, 1, profile.uuid, profile.uuid, profile.uuid, false))
    println("Cosmetic client checks passed: strict public payloads, scale/name limits, snapshot/live ordering, reset tombstones, reconnect freshness, rendered-player retention and bounded offline cache")
}
