package me.mycellium.skymyce.ui

import com.google.gson.JsonObject
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
    check(edited.json().keySet() == setOf("version", "pins", "favorites", "recent")) { "A shortcut must never persist copies of config values" }
    check(MenuShortcutState.read(JsonObject().apply { addProperty("version", 1) }).pins.isEmpty()) { "An empty pin list must survive restart" }
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", 99) }) }.isFailure)
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", "1") }) }.isFailure)
    check(runCatching { MenuShortcutState.read(JsonObject().apply { addProperty("version", 1.5) }) }.isFailure)
    check(MenuShortcutState().copy(pins = emptyList()).copy(pins = defaultMenuPins).pins == defaultMenuPins)

    val folder = Files.createTempDirectory("skymyce-menu-check-")
    try {
        val file = AtomicJsonFile(folder.resolve("shortcuts.json"), 16384)
        val jobs = mutableListOf<() -> Unit>()
        val writer = StateWriter<MenuShortcutState>({ file.write(it.json()) }, { jobs.add(it) }, { throw it })
        writer.submit(clean); writer.submit(edited)
        check(jobs.size == 1)
        jobs.removeAt(0)()
        check(MenuShortcutState.read(file.read()) == edited)
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
    println("Menu shortcuts checks passed: bounded identities, pin order/removal/reset, favorites/recent, coalesced atomic restart and corrupt backup")
}
