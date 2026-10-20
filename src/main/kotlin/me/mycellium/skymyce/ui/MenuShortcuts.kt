package me.mycellium.skymyce.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.teamresourceful.resourcefulconfig.api.types.options.EntryType
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.SettingRow
import me.mycellium.skymyce.config.settingRows
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

object MenuDirectory {
    val catalog by lazy { MenuCatalog(settingRows(SkyMyce.config)) }
}

internal val defaultMenuPins = listOf("Theme & Appearance/animationMode", "Theme & Appearance/graphicsQuality",
    "General/displayAbilityCooldown")
internal val defaultMenuHomeTools = listOf("party-finder", "dungeon-tracker", "auction-browser", "player-utilities", "cosmetics", "daily-digest")

internal fun canUseMenuHomeTool(feature: MenuFeature) = feature.tool != null && feature.favoriteEligible
private fun validMenuHomeTool(id: String) = MenuCatalog.definitions.any { it.id == id && canUseMenuHomeTool(it) }
private fun normalizedMenuHomeTools(ids: List<String>) =
    (ids.asSequence() + defaultMenuHomeTools.asSequence() + MenuCatalog.definitions.asSequence().map { it.id })
        .filter(::validMenuHomeTool).distinct().take(6).toList()

/** Shortcuts contain identities only. Values always come from the existing config entries. */
internal data class MenuShortcutState(val pins: List<String> = defaultMenuPins,
                                      val favorites: List<String> = emptyList(), val recent: List<String> = emptyList(),
                                      val homeTools: List<String> = defaultMenuHomeTools) {
    fun normalized(validPin: (String) -> Boolean, validFavorite: (String) -> Boolean, validFeature: (String) -> Boolean = validFavorite) = copy(
        pins = pins.distinct().filter(validPin).take(8), favorites = favorites.distinct().filter(validFavorite).take(32),
        recent = recent.distinct().filter(validFeature).take(12), homeTools = normalizedMenuHomeTools(homeTools))
    fun pin(path: String) = if (path in pins || pins.size >= 8) this else copy(pins = pins + path)
    fun unpin(path: String) = copy(pins = pins - path)
    fun move(path: String, direction: Int): MenuShortcutState {
        val index = pins.indexOf(path); val target = index + direction.coerceIn(-1, 1)
        if (index < 0 || target !in pins.indices) return this
        return copy(pins = pins.toMutableList().apply { val value = removeAt(index); add(target, value) })
    }
    fun favorite(id: String) = if (id in favorites) copy(favorites = favorites - id)
        else if (favorites.size < 32) copy(favorites = favorites + id) else this
    fun visit(id: String) = copy(recent = (listOf(id) + recent.filter { it != id }).take(12))
    fun replaceHomeTool(index: Int, id: String): MenuShortcutState {
        if (index !in 0..5 || index !in homeTools.indices || !validMenuHomeTool(id) || homeTools[index] == id) return this
        val previous = homeTools[index]
        val existing = homeTools.indexOf(id)
        return copy(homeTools = homeTools.toMutableList().apply {
            this[index] = id
            if (existing >= 0) this[existing] = previous
        })
    }
    fun json() = JsonObject().apply {
        addProperty("version", 1)
        fun array(values: List<String>) = JsonArray().apply { values.forEach(::add) }
        add("pins", array(pins)); add("favorites", array(favorites)); add("recent", array(recent)); add("homeTools", array(homeTools))
    }
    companion object {
        fun read(json: JsonElement?): MenuShortcutState {
            if (json == null) return MenuShortcutState()
            val obj = json.asJsonObject
            require(obj["version"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" } == true) { "Unsupported menu shortcut format" }
            fun strings(key: String): List<String> {
                val values = obj[key] ?: return emptyList()
                require(values.isJsonArray && values.asJsonArray.size() <= 128)
                return values.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { s -> require(s.length <= 256) } }
            }
            return MenuShortcutState(strings("pins"), strings("favorites"), strings("recent"),
                if (obj.has("homeTools")) strings("homeTools") else defaultMenuHomeTools)
        }
    }
}

internal fun canPinMenuSetting(row: SettingRow) = !row.entry.isArray && row.entry.type() in
    listOf(EntryType.BOOLEAN, EntryType.ENUM, EntryType.INTEGER, EntryType.DOUBLE)

object MenuShortcuts {
    private var state = MenuShortcutState()
    private var writer: StateWriter<MenuShortcutState>? = null
    var revision = 0L
        private set
    val pins get() = state.pins
    val favorites get() = state.favorites
    val recent get() = state.recent
    val homeTools get() = state.homeTools

    /** Startup only; the bounded read precedes any menu callbacks, saves run on the scheduler. */
    fun initialize() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("menu-shortcuts.json"), 16384)
        var recoverable = true
        state = runCatching { MenuShortcutState.read(file.read()) }.getOrElse {
            runCatching { file.preserveOriginal("unreadable-${System.currentTimeMillis()}") }.onFailure { recoverable = false }
            SkyMyce.logger.warn("Menu shortcuts were unreadable; using defaults and retaining the original")
            MenuShortcutState()
        }.normalized(::eligible, ::validFavorite, ::validFeature)
        if (file.damaged) SkyMyce.logger.warn("Damaged menu shortcuts; defaults restored and the original will be backed up on save")
        if (!recoverable) SkyMyce.logger.warn("Menu shortcut backup unavailable; shortcut writes paused")
        writer = StateWriter({ if (recoverable) file.write(it.json()) }, { work -> Scheduling.schedule(100.milliseconds) { work() } }, {
            SkyMyce.logger.warn("Could not save menu shortcuts; previous file retained", it)
        })
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer?.flush() } }, "SkyMyce-Menu-Save"))
        revision++
    }
    fun eligible(path: String) = MenuDirectory.catalog.setting(path)?.let(::canPinMenuSetting) == true
    private fun validFeature(id: String) = MenuDirectory.catalog.findFeature(id)?.favoriteEligible == true
    private fun validFavorite(id: String) = if (id.startsWith("setting:")) MenuDirectory.catalog.settingEntry(id.removePrefix("setting:"))?.favoriteEligible == true else validFeature(id)
    fun isPinned(path: String) = path in state.pins
    fun isFavorite(id: String) = id in state.favorites
    fun togglePin(path: String): Boolean {
        if (!eligible(path)) return false
        return update(if (isPinned(path)) state.unpin(path) else state.pin(path))
    }
    fun move(path: String, direction: Int) = update(state.move(path, direction))
    fun resetPins() = update(state.copy(pins = defaultMenuPins.filter(::eligible)))
    fun replaceHomeTool(index: Int, id: String) = update(state.replaceHomeTool(index, id))
    fun resetHomeTools() = update(state.copy(homeTools = normalizedMenuHomeTools(defaultMenuHomeTools)))
    fun toggleFavorite(id: String) = validFavorite(id) && update(state.favorite(id))
    fun visit(id: String) { if (validFeature(id)) update(state.visit(id)) }
    private fun update(next: MenuShortcutState): Boolean {
        if (next == state) return false
        state = next; revision++; writer?.submit(next); return true
    }
}
