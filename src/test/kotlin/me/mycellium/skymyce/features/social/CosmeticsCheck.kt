package me.mycellium.skymyce.features.social

import com.google.gson.JsonParser
import me.mycellium.skymyce.features.instances.dungeons.friends.relayPacketSizeAllowed
import me.mycellium.skymyce.features.instances.dungeons.friends.retainCosmeticsDuringOutage
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.Deflater
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.ClickEvent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.client.StringSplitter

fun checkCosmetics() {
    // The native component codec now references registered dialog types; production is already bootstrapped.
    net.minecraft.SharedConstants.tryDetectVersion()
    net.minecraft.server.Bootstrap.bootStrap()
    studioAsyncWorkChecks()
    cosmeticDraftChecks()
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
    // Local grace keeps only the same account/room's public appearance, without renewing its TTL.
    val relayIdentity = "wss://relay.example/websocket?room=friends|${profile.uuid}"
    val outage = CosmeticCache()
    outage.apply(profile, 1000)
    check(retainCosmeticsDuringOutage(true, true, relayIdentity, relayIdentity))
    check(outage.get(profile.uuid, 301000, touch = true) == profile)
    check(outage.get(profile.uuid, 601001, touch = true) == null)
    val boundaries = listOf(
        Triple(false, true, relayIdentity), // Denial or exhausted local grace.
        Triple(true, false, relayIdentity), // Logout or disabled relay.
        Triple(true, true, relayIdentity.replace("friends", "testing")),
        Triple(true, true, "wss://other.example/websocket?room=friends|${profile.uuid}"),
        Triple(true, true, "wss://relay.example/websocket?room=friends|${UUID(0, 9)}")
    )
    for ((allowed, active, requested) in boundaries) {
        outage.apply(profile, 1000)
        val retain = retainCosmeticsDuringOutage(allowed, active, relayIdentity, requested)
        check(!retain)
        if (!retain) outage.clear()
        check(outage.get(profile.uuid, 1001) == null)
    }
    check(!retainCosmeticsDuringOutage(true, true, "", relayIdentity))
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
    val studio = extended.deepCopy().apply {
        addProperty("nametagScale", 1.75); addProperty("capePreset", "forest"); add("capeAsset", com.google.gson.JsonNull.INSTANCE)
    }
    val live = requireNotNull(parseCosmeticProfile(studio))
    check(live.nametagScale == 1.75f && live.capePreset == "forest" && live.capeAsset == null)
    check(live.scaleX == .6f && live.scaleY == 1.5f && live.scaleZ == 2f && live.nameStyle!!.style.isBold)
    val noCape = requireNotNull(parseCosmeticProfile(studio.deepCopy().apply { addProperty("capePreset", "none") }))
    val bodyTexture = net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "body")
    val nativeTexture = net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "native_cape")
    val elytraTexture = net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "elytra")
    val originalSkin = net.minecraft.world.entity.player.PlayerSkin(
        net.minecraft.core.ClientAsset.ResourceTexture(bodyTexture, bodyTexture),
        net.minecraft.core.ClientAsset.ResourceTexture(nativeTexture, nativeTexture),
        net.minecraft.core.ClientAsset.ResourceTexture(elytraTexture, elytraTexture),
        net.minecraft.world.entity.player.PlayerModelType.SLIM, true)
    val hiddenCape = applyCosmeticCape(originalSkin, noCape, nativeTexture)
    check(noCape.capePreset == "none" && hiddenCape.cape() == null)
    check(hiddenCape.body() == originalSkin.body() && hiddenCape.elytra() == originalSkin.elytra() &&
        hiddenCape.model() == originalSkin.model() && hiddenCape.secure() == originalSkin.secure())
    check(applyCosmeticCape(originalSkin, live.copy(capePreset = null), null) === originalSkin)
    val customCape = applyCosmeticCape(originalSkin, live, bodyTexture)
    check(customCape.cape()?.id() == bodyTexture && customCape.cape()?.texturePath() == bodyTexture && customCape.elytra() == originalSkin.elytra())
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("capePreset", true) }) == null)
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("capePreset", "NONE") }) == null)
    for (value in listOf(.49, 2.01)) check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("nametagScale", value) }) == null)
    for (value in listOf(.5, 2.0)) check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("nametagScale", value) })?.nametagScale == value.toFloat())
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("nametagScale", "1.75") }) == null)
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("capePreset", "https://remote.example/cape.png") }) == null)
    check(parseCosmeticProfile(studio.deepCopy().apply { remove("capeAsset") }) == null)
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("discordId", "private") }) == null)
    val pristine = studio.deepCopy().apply {
        add("name", com.google.gson.JsonNull.INSTANCE); add("nameStyle", com.google.gson.JsonNull.INSTANCE)
        for (key in listOf("scale", "scaleX", "scaleY", "scaleZ", "nametagScale")) addProperty(key, 1)
        addProperty("revision", 0); addProperty("updatedAt", 0); add("capePreset", com.google.gson.JsonNull.INSTANCE)
    }
    check(parseCosmeticProfile(pristine) != null)
    check(parseCosmeticProfile(pristine.deepCopy().apply { addProperty("nametagScale", 1.1) }) == null)
    check(parseCosmeticProfile(pristine.deepCopy().apply { addProperty("capePreset", "forest") }) == null)
    val png = checkCapeValidation()
    localCosmeticsChecks(png)
    val descriptor = JsonParser.parseString("""{"id":"${"b".repeat(32)}","sha256":"${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))}","size":${png.size},"width":64,"height":32}""").asJsonObject
    val asset = parseCapeAsset(descriptor)
    capePreviewChecks(profile, asset, png)
    validateCapeDownload(png, asset)
    check(runCatching { validateCapeDownload(png, asset.copy(size = png.size + 1)) }.isFailure)
    check(runCatching { validateCapeDownload(png, asset.copy(sha256 = "0".repeat(64))) }.isFailure)
    val changedBytes = png.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    check(runCatching { validateCapeDownload(changedBytes, asset) }.isFailure)
    val changedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(changedBytes))
    check(runCatching { validateCapeDownload(changedBytes, asset.copy(sha256 = changedHash)) }.isFailure) // Correct hash cannot authorize malformed PNG.
    val uploaded = requireNotNull(parseCosmeticProfile(studio.deepCopy().apply {
        add("capePreset", com.google.gson.JsonNull.INSTANCE); add("capeAsset", descriptor)
    }))
    check(uploaded.capeAsset == asset && uploaded.capePreset == null && uploaded.nameStyle!!.style.isBold)
    check(parseCosmeticProfile(studio.deepCopy().apply { add("capeAsset", descriptor) }) == null) // Preset and upload are exclusive.
    check(parseCosmeticProfile(studio.deepCopy().apply { addProperty("capePreset", "none"); add("capeAsset", descriptor) }) == null)
    for (invalid in listOf(
        descriptor.deepCopy().apply { addProperty("width", 64.8) },
        descriptor.deepCopy().apply { addProperty("height", "32") },
        descriptor.deepCopy().apply { addProperty("size", png.size + .5) },
        descriptor.deepCopy().apply { addProperty("size", "${png.size}") },
        descriptor.deepCopy().apply { addProperty("id", "11111111111111111111111111111111".toBigInteger()) },
        descriptor.deepCopy().apply { addProperty("sha256", "A".repeat(64)) },
        descriptor.deepCopy().apply { addProperty("url", "https://remote.example/cape.png") },
        descriptor.deepCopy().apply { addProperty("size", 262145) }
    )) check(runCatching { parseCapeAsset(invalid) }.isFailure)
    check(cosmeticDraftOwnerCurrent(profile, profile.copy(nametagScale = 2f), "a".repeat(32)))
    check(!cosmeticDraftOwnerCurrent(profile, profile, "b".repeat(32))) // A refreshed identity cannot save an old preview.
    check(!cosmeticDraftOwnerCurrent(profile, profile.copy(uuid = UUID(0, 2)), "a".repeat(32)))
    check(!cosmeticDraftOwnerCurrent(profile, profile, profile.uuid.toString())) // HTTP owner IDs use canonical 32-hex UUIDs.
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
    val stable = CosmeticIdentities(3)
    stable.observe("Alice", profile.uuid, 1000, priority = true)
    repeat(20) { stable.observe("Alice", profile.uuid, 1001L + it) }
    stable.observe("PreviousName", profile.uuid, 1100) // Bulk data cannot override a priority identity.
    check(stable.resolved()[profile.uuid] == "Alice" && stable.size == 1)
    stable.observe("NewName", profile.uuid, 1200, priority = true)
    check(stable.resolved()[profile.uuid] == "NewName" && stable.size == 1)
    stable.observe("NewName", UUID(0, 44), 1300, priority = true)
    check(profile.uuid !in stable.resolved() && stable.resolved()[UUID(0, 44)] == "NewName")
    stable.observe("Lookup", null, 1400, priority = true)
    stable.complete(listOf("Lookup"), mapOf("lookup" to UUID(0, 55)), 1500)
    repeat(20) { stable.observe("Lookup", null, 1501L + it, priority = true) }
    check(stable.names(301499).isEmpty() && stable.names(301500) == listOf("Lookup")) // Re-observation preserves lookup TTL.
    stable.expire(901521)
    check(stable.size == 0)
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
    println("Cosmetic client checks passed: strict v1-v4 profiles, independent scales/style, cape PNG bounds, captured owners, snapshot/live ordering and bounded offline cache")
}

/** Construct synthetic PNGs so malformed chunks and decompression bounds exercise the actual client validator. */
private fun checkCapeValidation(): ByteArray {
    fun chunk(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
        val bytes = type.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(payload.size + 12).putInt(payload.size).put(bytes).put(payload)
            .putInt(CRC32().run { update(bytes); update(payload); value.toInt() }).array()
    }
    fun deflate(raw: ByteArray): ByteArray {
        val compressor = Deflater(); val output = ByteArrayOutputStream()
        try {
            compressor.setInput(raw); compressor.finish(); val buffer = ByteArray(1024)
            while (!compressor.finished()) output.write(buffer, 0, compressor.deflate(buffer))
            return output.toByteArray()
        } finally { compressor.end() }
    }
    fun header(width: Int = 64, depth: Int = 8, color: Int = 6, interlace: Int = 0) =
        ByteBuffer.allocate(13).putInt(width).putInt(32).put(depth.toByte()).put(color.toByte()).put(0.toByte()).put(0.toByte()).put(interlace.toByte()).array()
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    fun png(raw: ByteArray, h: ByteArray = header(), compressed: ByteArray = deflate(raw), before: ByteArray = byteArrayOf(), after: ByteArray = byteArrayOf()) =
        signature + chunk("IHDR", h) + before + chunk("IDAT", compressed) + after + chunk("IEND")
    val raw = ByteArray(32 * 257).apply { repeat(32) { this[it * 257] = (it % 5).toByte() } }
    val valid = png(raw)
    validateCapePng(valid)
    validateCapePng(png(ByteArray(32 * 193), header(color = 2))) // RGB also supported.
    validateCapePng(png(raw, before = chunk("tEXt", "note\u0000synthetic".toByteArray())))
    validateCapePng(signature + chunk("IHDR", header()) + chunk("IDAT") + chunk("IDAT", deflate(raw)) + chunk("IEND"))
    val corrupt = valid.copyOf().apply { this[lastIndex - 1] = (this[lastIndex - 1].toInt() xor 1).toByte() }
    val invalid = listOf(
        valid.copyOf(valid.size - 1), valid + byteArrayOf(0), ByteArray(262145), corrupt,
        valid.copyOf().apply { this[0] = 0 }, png(raw, header(width = 65)), png(raw, header(depth = 16)),
        png(raw, header(color = 3)), png(raw, header(interlace = 1)),
        png(raw.copyOf().apply { this[0] = 5 }), png(raw.copyOf(raw.size - 1)), png(raw + byteArrayOf(0)),
        png(ByteArray(262144)), png(raw, compressed = deflate(raw) + byteArrayOf(0)),
        signature + chunk("IHDR", header()) + chunk("IDAT", deflate(raw)),
        png(raw, before = chunk("ABCD")), png(raw, before = chunk("t3Xt")),
        png(raw, before = chunk("acTL", ByteArray(8))), png(raw, before = chunk("fcTL", ByteArray(26))),
        png(raw, after = chunk("fdAT", ByteArray(4))),
        signature + chunk("IHDR", header()) + chunk("IDAT") + chunk("tEXt") + chunk("IDAT", deflate(raw)) + chunk("IEND"),
        signature + chunk("IHDR", header()) + chunk("IDAT", deflate(raw)) + chunk("tEXt") + chunk("IDAT") + chunk("IEND")
    )
    invalid.forEachIndexed { index, bytes -> check(runCatching { validateCapePng(bytes) }.isFailure) { "Unsafe cape PNG case $index was accepted" } }
    return valid
}
