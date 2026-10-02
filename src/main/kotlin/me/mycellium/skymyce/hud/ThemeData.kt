package me.mycellium.skymyce.hud

import com.google.gson.Gson
import com.google.gson.JsonParser

data class ThemePalette(val primary: Int = 0xB698EF, val secondary: Int = 0xD2BAFF,
    val panel: Int = 0x191522, val card: Int = 0x251F32, val text: Int = 0xF3EEF9,
    val muted: Int = 0xAF9FC0, val border: Int = 0x443752, val opacity: Int = 90,
    val rounded: Boolean = true, val padding: Int = 5, val shadow: Boolean = false,
    val borders: Boolean = true, val success: Int = 0x87D6A2, val warning: Int = 0xE8BF71,
    val error: Int = 0xF18C8C)

enum class ThemePreset(val title: String, val palette: ThemePalette) {
    DARK_GLASS("Dark glass", ThemePalette()),
    MINIMAL("Minimal", ThemePalette(primary = 0x67CCF2, secondary = 0xC2A2F5, panel = 0x111111, card = 0x181818,
        text = 0xEDF3F7, muted = 0x91A2AF, border = 0x333333, opacity = 85, rounded = false, padding = 3, borders = false)),
    LIGHT("Light", ThemePalette(primary = 0x006786, secondary = 0x663AA0, panel = 0xF0F3F7, card = 0xFFFFFF,
        text = 0x17212A, muted = 0x485B6B, border = 0x8C9AA5, opacity = 98, success = 0x196938, warning = 0x805300, error = 0xAE2222)),
    HIGH_CONTRAST("High contrast", ThemePalette(primary = 0x67E8FF, secondary = 0xFFFF66, panel = 0, card = 0x101010,
        text = 0xFFFFFF, muted = 0xDEDEDE, border = 0xFFFFFF, opacity = 100, rounded = false, padding = 7,
        success = 0x66FF99, warning = 0xFFFF66, error = 0xFF8888)),
}

/** Null means inherit, even when the inherited value happens to equal an earlier override. */
data class ThemeOverrides(val primary: Int? = null, val secondary: Int? = null,
    val panel: Int? = null, val card: Int? = null, val text: Int? = null, val muted: Int? = null,
    val border: Int? = null, val opacity: Int? = null, val rounded: Boolean? = null,
    val padding: Int? = null, val shadow: Boolean? = null, val borders: Boolean? = null) {
    fun apply(base: ThemePalette) = base.copy(primary = primary ?: base.primary, secondary = secondary ?: base.secondary,
        panel = panel ?: base.panel, card = card ?: base.card, text = text ?: base.text, muted = muted ?: base.muted,
        border = border ?: base.border, opacity = opacity ?: base.opacity, rounded = rounded ?: base.rounded,
        padding = padding ?: base.padding, shadow = shadow ?: base.shadow, borders = borders ?: base.borders)
}

data class ThemeDocument(val version: Int = 1, val preset: ThemePreset = ThemePreset.DARK_GLASS, val overrides: ThemeOverrides = ThemeOverrides())
fun resolveTheme(preset: ThemePreset, global: ThemeOverrides, widget: ThemeOverrides? = null): ThemePalette =
    (widget ?: ThemeOverrides()).apply(global.apply(preset.palette))

/** Data only. Strict fields, scalar types and limits; no resource paths or executable components. */
fun parseThemeDocument(json: String): ThemeDocument {
    require(json.length <= 16384 && json.toByteArray(Charsets.UTF_8).size <= 16384) { "Theme exceeds 16 KiB" }
    val root = JsonParser.parseString(json).asJsonObject
    require(root.keySet().all { it in setOf("version", "preset", "overrides") } && root.get("version")?.asJsonPrimitive?.isNumber == true && root.get("version").asString == "1") { "Unsupported theme format" }
    val preset = ThemePreset.valueOf(root.get("preset")?.asString ?: "DARK_GLASS")
    val fields = root.getAsJsonObject("overrides") ?: com.google.gson.JsonObject()
    val colors = setOf("primary", "secondary", "panel", "card", "text", "muted", "border")
    require(fields.keySet().all { it in colors || it in setOf("opacity", "padding", "rounded", "shadow", "borders") }) { "Unknown theme field" }
    fun number(name: String, range: IntRange): Int? = fields.get(name)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,8}"))) { "Invalid $name" }
        it.asInt.also { value -> require(value in range) { "Invalid $name range" } }
    }
    fun flag(name: String): Boolean? = fields.get(name)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Invalid $name" }; it.asBoolean
    }
    return ThemeDocument(preset = preset, overrides = ThemeOverrides(number("primary", 0..0xFFFFFF), number("secondary", 0..0xFFFFFF),
        number("panel", 0..0xFFFFFF), number("card", 0..0xFFFFFF), number("text", 0..0xFFFFFF), number("muted", 0..0xFFFFFF),
        number("border", 0..0xFFFFFF), number("opacity", 20..100), flag("rounded"), number("padding", 2..12), flag("shadow"), flag("borders")))
}
fun exportThemeDocument(theme: ThemeDocument): String = Gson().toJson(theme)
