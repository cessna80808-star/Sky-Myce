package me.mycellium.skymyce.ui

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import java.nio.file.Files

fun main() {
    val validPins = (0..10).map { "setting/$it" }.toSet()
    val validFeatures = (0..40).map { "feature/$it" }.toSet()
    val original = MenuShortcutState(listOf("gone") + validPins.toList() + listOf("setting/1"), validFeatures.toList(), validFeatures.toList())
    val clean = original.normalized({ it in validPins }, { it in validFeatures })
    check(clean.pins == validPins.take(8) && clean.favorites.size == 32 && clean.recent.size == 12)
    check(clean.pin("setting/9") == clean)
    val edited = clean.unpin("setting/1").pin("setting/9").move("setting/9", -1)
    check(edited.pins == listOf("setting/0", "setting/2", "setting/3", "setting/4", "setting/5", "setting/6", "setting/9", "setting/7"))
    check(edited.move("setting/0", -1) == edited && edited.move("missing", 1) == edited)
    check(clean.favorite("feature/40") == clean && clean.favorite("feature/1").favorite("feature/1").favorites.last() == "feature/1")
    var recent = MenuShortcutState(pins = emptyList())
    (0..30).forEach { recent = recent.visit("feature/$it") }
    recent = recent.visit("feature/25")
    check(recent.recent.size == 12 && recent.recent.first() == "feature/25" && recent.recent.toSet().size == 12)
    check(MenuShortcutState.read(edited.json()) == edited)
    val favoriteSetting = "setting:Theme & Appearance/animationMode"
    val withSetting = edited.favorite("feature/0").favorite(favoriteSetting)
    check(MenuShortcutState.read(withSetting.json()) == withSetting)
    check(withSetting.normalized({ it in validPins }, { it == favoriteSetting || it in validFeatures }, { it in validFeatures }).favorites.contains(favoriteSetting))
    check(edited.json().keySet() == setOf("version", "pins", "favorites", "recent", "homeTools")) { "A shortcut must never persist copies of config values" }
    check(MenuShortcutState.read(JsonObject().apply { addProperty("version", 1) }).pins.isEmpty()) { "An empty pin list must survive restart" }
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", 99) }) }.isFailure)
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", "1") }) }.isFailure)
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", 1.5) }) }.isFailure)
    check(MenuShortcutState().copy(pins = emptyList()).copy(pins = defaultMenuPins).pins == defaultMenuPins)

    val tools = MenuCatalog.definitions.filter(::canUseMenuHomeTool)
    check(defaultMenuHomeTools == listOf("party-finder", "dungeon-tracker", "auction-browser", "loans", "cosmetics", "daily-digest"))
    check(defaultMenuHomeTools.all { id -> tools.any { it.id == id } } && defaultMenuHomeTools.distinct().size == 6)
    check(!canUseMenuHomeTool(MenuCatalog.definitions.first { it.id == "rng-test" }))
    check(!canUseMenuHomeTool(tools.first().copy(favoriteEligible = false)))
    val legacy = withSetting.json().apply { remove("homeTools") }
    val migrated = MenuShortcutState.read(legacy)
    check(migrated == withSetting && migrated.homeTools == defaultMenuHomeTools) { "Old shortcut files must preserve all existing lists and gain default Home tools" }
    val custom = withSetting.copy(homeTools = listOf("missing", "rng-test", "starred-routes", "theme-editor", "loans", "loans", "community"))
        .normalized({ it in validPins }, { it == favoriteSetting || it in validFeatures }, { it in validFeatures })
    check(custom.homeTools == listOf("loans", "community", "party-finder", "dungeon-tracker", "auction-browser", "cosmetics"))
    check(custom.pins == withSetting.pins && custom.favorites == withSetting.favorites && custom.recent == withSetting.recent)
    check(MenuShortcutState(homeTools = emptyList()).normalized({ false }, { false }).homeTools == defaultMenuHomeTools)
    val allTools = tools.map { it.id }
    check(MenuShortcutState(homeTools = allTools + allTools).normalized({ false }, { false }).homeTools == allTools.take(6))
    val swapped = migrated.replaceHomeTool(0, "daily-digest")
    check(swapped.homeTools == listOf("daily-digest", "dungeon-tracker", "auction-browser", "loans", "cosmetics", "party-finder"))
    check(swapped.homeTools.distinct().size == 6 && swapped.pins == migrated.pins && swapped.favorites == migrated.favorites && swapped.recent == migrated.recent)
    val homeEdited = edited.replaceHomeTool(2, "community")
    check(homeEdited.homeTools == listOf("party-finder", "dungeon-tracker", "community", "loans", "cosmetics", "daily-digest"))
    check(homeEdited.replaceHomeTool(2, "community") == homeEdited)
    for (invalid in listOf("missing", "rng-test", "ability-cooldowns", "hud-editor")) check(homeEdited.replaceHomeTool(0, invalid) == homeEdited)
    check(homeEdited.replaceHomeTool(-1, "community") == homeEdited && homeEdited.replaceHomeTool(6, "community") == homeEdited)
    check(MenuShortcutState.read(homeEdited.json()) == homeEdited)
    check(homeEdited.copy(homeTools = defaultMenuHomeTools) == edited)
    fun homeJson(values: JsonArray) = edited.json().apply { add("homeTools", values) }
    check(runCatching { MenuShortcutState.read(homeJson(JsonArray().apply { repeat(129) { add("loans") } })) }.isFailure)
    check(runCatching { MenuShortcutState.read(homeJson(JsonArray().apply { add("x".repeat(257)) })) }.isFailure)
    check(runCatching { MenuShortcutState.read(homeJson(JsonArray().apply { add(1) })) }.isFailure)
    check(runCatching { MenuShortcutState.read(edited.json().apply { addProperty("homeTools", "loans") }) }.isFailure)

    val folder = Files.createTempDirectory("skymyce-menu-check-")
    try {
        val file = AtomicJsonFile(folder.resolve("shortcuts.json"), 16384)
        val jobs = mutableListOf<() -> Unit>()
        val writer = StateWriter<MenuShortcutState>({ file.write(it.json()) }, { jobs.add(it) }, { throw it })
        writer.submit(clean); writer.submit(homeEdited)
        check(jobs.size == 1)
        jobs.removeAt(0)()
        check(MenuShortcutState.read(file.read()) == homeEdited)
        writer.submit(recent); writer.flush()
        check(MenuShortcutState.read(AtomicJsonFile(file.path).read()) == recent)
        Files.writeString(file.path, "{broken")
        val damaged = AtomicJsonFile(file.path, 16384)
        check(damaged.read() == null && damaged.damaged)
        damaged.write(edited.json())
        check(MenuShortcutState.read(damaged.read()) == edited)
        Files.list(folder).use { paths -> check(paths.anyMatch { it.fileName.toString().contains(".corrupt-") }) }
    } finally {
        Files.list(folder).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(folder)
    }
    println("Menu shortcuts checks passed: bounded identities, pin order/removal/reset, favorites/recent, Home tools/migration/swap, coalesced atomic restart and corrupt backup")
}
