package me.mycellium.skymyce.features.social

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.UUID
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FormattedCharSequence
import java.util.Optional

internal fun localCosmeticsChecks(png: ByteArray) {
    val root = Files.createTempDirectory("skymyce-local-cosmetics-check")
    try {
        val owner = UUID(0, 9); val repo = LocalCosmeticsRepository(root, owner)
        val initial = CosmeticProfile(owner, null, 1f, 0, 0)
        var state = repo.read(); check(state.profile == null && state.assets.isEmpty())
        state = repo.equip(state, initial.copy(capePreset = "forest"))
        check(LocalCosmeticsRepository(root, owner).read().profile?.capePreset == "forest") // Reopen / restart.
        val metadataPath = root.resolve(owner.toString().replace("-", "")).resolve("appearance.json")
        val persisted = JsonParser.parseString(Files.readString(metadataPath)).asJsonObject
        val persistedProfile = persisted.getAsJsonObject("profile")
        check(listOf("name", "nameStyle", "capeAsset").all { persistedProfile.has(it) && persistedProfile[it].isJsonNull })
        // Repair only the known null omission of early local v1 builds, without weakening the shared parser.
        val early = persisted.deepCopy().apply { getAsJsonObject("profile").apply { remove("name"); remove("nameStyle"); remove("capeAsset") } }
        Files.writeString(metadataPath, early.toString())
        check(LocalCosmeticsRepository(root, owner).read().profile?.capePreset == "forest")
        val invalidStored = early.deepCopy().apply { getAsJsonObject("profile").remove("scaleX") }
        Files.writeString(metadataPath, invalidStored.toString())
        check(runCatching { LocalCosmeticsRepository(root, owner).read() }.isFailure) // Missing required axes are never defaulted.
        Files.writeString(metadataPath, persisted.toString())
        check(LocalCosmeticsRepository(root, UUID(0, 10)).read().profile == null) // Offline Minecraft identities remain separate.
        val source = root.resolve("input.png"); Files.write(source, png)
        state = repo.import(state, initial.copy(scaleX = .8f, scaleY = 1.4f, scaleZ = 1.6f, scale = 1.4f), source)
        val uploaded = requireNotNull(state.profile); val asset = requireNotNull(uploaded.capeAsset)
        check(uploaded.localCape && uploaded.capePreset == null && state.assets.size == 1)
        check(repo.bytes(asset).contentEquals(png))
        Files.delete(source) // Library owns a copy; the original download is no longer required.
        val restarted = LocalCosmeticsRepository(root, owner).read()
        check(restarted.profile?.localCape == true && restarted.profile?.scaleY == 1.4f && restarted.assets == state.assets)
        state = repo.equip(state, uploaded.copy(capePreset = "none", capeAsset = null, localCape = false))
        check(repo.read().profile?.capePreset == "none" && repo.read().assets == listOf(asset)) // Unequip preserves reusable upload.
        state = repo.equip(state, uploaded)
        val invalid = root.resolve("bad.png"); Files.write(invalid, byteArrayOf(1, 2, 3))
        check(runCatching { repo.import(state, uploaded, invalid) }.isFailure)
        check(repo.read().profile?.capeAsset == asset && repo.read().assets.size == 1) // Bad import cannot reset equipped state.
        check(runCatching { repo.equip(state, uploaded.copy(uuid = UUID(0, 10))) }.isFailure)
        check(runCatching { repo.equip(state, uploaded.copy(capeAsset = asset.copy(id = "f".repeat(32)))) }.isFailure)
        state = repo.delete(state, asset)
        check(repo.read().profile?.capePreset == "none" && repo.read().assets.isEmpty())
        check(runCatching { repo.bytes(asset) }.isFailure) // Explicit deletion removes the managed file.
        state = repo.equip(state, initial.copy(capePreset = "sunset"))
        state = repo.useShared(state); check(state.profile == null && repo.read().profile == null)
        val sharedStored = JsonParser.parseString(Files.readString(metadataPath)).asJsonObject
        check(sharedStored.has("profile") && sharedStored["profile"].isJsonNull) // Explicit local override removal persists too.
        Files.writeString(metadataPath, sharedStored.deepCopy().apply { remove("profile") }.toString())
        check(repo.read().profile == null) // Early disabled preferences also survive their omitted null field.

        // Per-character and nested arrays retain Minecraft style inheritance through its native codec.
        val gradient = JsonParser.parseString("""[{"text":"A","color":"#112233","bold":true},[{"text":"l","color":"#223344"},"i"],{"text":"","extra":[{"text":"c","color":"#334455"},{"text":"e","color":"#445566"}]}]""")
        val component = parseCosmeticText(gradient)
        fun characters(value: Component): List<Pair<String, Style>> {
            val result = mutableListOf<Pair<String, Style>>()
            value.visit(FormattedText.StyledContentConsumer<Unit> { style, text -> if (text.isNotEmpty()) result += text to style; Optional.empty() }, Style.EMPTY)
            return result
        }
        val styles = characters(component)
        check(component.string == "Alice" && styles.map { it.first }.joinToString("") == "Alice")
        check(styles.map { it.second.color?.value } == listOf(0x112233, 0x223344, 0x223344, 0x334455, 0x445566))
        check(styles.all { it.second.isBold })
        val nativeJson = cosmeticTextJson(component)
        check(characters(parseCosmeticText(nativeJson)) == styles)
        state = repo.equip(state, initial.copy(name = component.string, nameStyle = component))
        check(characters(requireNotNull(repo.read().profile?.nameStyle)) == styles)
        for (raw in listOf("[]", "[true]", "{\"text\":\"Alice\",\"clickEvent\":{\"action\":\"run_command\",\"command\":\"/op\"}}", "[\"\",{\"translate\":\"secret\"}]", "\"${"x".repeat(33)}\""))
            check(runCatching { parseCosmeticText(JsonParser.parseString(raw)) }.isFailure)
        check(parseCosmeticText(JsonParser.parseString("\"Alice\"")).string == "Alice")
        var nested: com.google.gson.JsonElement = com.google.gson.JsonPrimitive("Alice")
        repeat(10) { nested = com.google.gson.JsonArray().apply { add(nested) } }
        check(runCatching { parseCosmeticText(nested) }.isFailure)

        // Actual Avatar hook supplies a marked cosmetic; the Font hook must not replace Alice inside it again.
        fun text(sequence: FormattedCharSequence) = StringBuilder().also { out -> sequence.accept { _, _, code -> out.appendCodePoint(code); true } }.toString()
        val selected = Component.literal("The big Alice").withColor(0x62d7f1)
        CosmeticNames.enabled = true; CosmeticNames.update(mapOf("Alice" to selected))
        val avatarName = CosmeticNames.decorated(selected)
        check(text(CosmeticNames.display(avatarName.visualOrderText)) == "The big Alice")
        val displayed = CosmeticNames.display(Component.literal("Alice") as FormattedText)
        check(displayed.string == "The big Alice" && CosmeticNames.display(displayed).string == "The big Alice")
        check(text(CosmeticNames.display((displayed as Component).visualOrderText)) == "The big Alice")
        CosmeticNames.enabled = false; CosmeticNames.update(emptyMap())
    } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
}
