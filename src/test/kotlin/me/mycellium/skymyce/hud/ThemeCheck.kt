package me.mycellium.skymyce.hud

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.config.misc.restoredThemePreset
import me.mycellium.skymyce.hud.widget.parseWidgetConfig
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.GuiQuality

fun checkThemes() {
    check(ThemePreset.entries.map { it.name }.containsAll(listOf("DARK_GLASS", "MINIMAL", "LIGHT", "HIGH_CONTRAST")))
    check(ThemePreset.entries.size >= 7 && ThemePreset.SIGNATURE.toString() == "Signature")
    check(restoredThemePreset(JsonParser.parseString("{}").asJsonObject, ThemePreset.SIGNATURE) == ThemePreset.SIGNATURE)
    check(restoredThemePreset(JsonParser.parseString("""{"General":{}}""").asJsonObject, ThemePreset.SIGNATURE) == ThemePreset.DARK_GLASS)
    for (preset in ThemePreset.entries) {
        // A preset without custom colors is kept; a customized theme moves to the closest preset.
        check(restoredThemePreset(JsonParser.parseString("""{"Theme & Appearance":{"preset":"${preset.name}","primary":-1}}""").asJsonObject, preset) == preset)
        val palette = preset.palette
        val custom = JsonParser.parseString("""{"Theme & Appearance":{"preset":"SIGNATURE","primary":${palette.primary},"secondary":${palette.secondary},
            "panel":${palette.panel},"card":${palette.card},"text":${palette.text},"muted":${palette.muted},"border":${palette.border},"opacity":40}}""").asJsonObject
        check(restoredThemePreset(custom, ThemePreset.SIGNATURE) == preset) { "${preset.title} colors did not map back to their preset" }
        check(custom.getAsJsonObject("Theme & Appearance").keySet() == setOf("preset")) { "Legacy theme keys were kept" }
        for (surface in listOf(palette.panel, palette.card, palette.sidebar)) {
            check(themeContrast(palette.text, surface) >= 7.0) { "${preset.title} primary text contrast is too low" }
            check(themeContrast(palette.muted, surface) >= 4.5) { "${preset.title} secondary text contrast is too low" }
        }
        for (accent in listOf(palette.primary, palette.secondary)) {
            check(themeContrast(themeForeground(accent), accent) >= 4.5) { "${preset.title} on-accent controls are unreadable" }
        }
        for (semantic in listOf(palette.success, palette.warning, palette.error)) {
            check(themeContrast(semantic, palette.card) >= 4.5) { "${preset.title} semantic text contrast is too low" }
        }
    }
    // A slightly adjusted Light palette still lands on Light; unrelated legacy keys are ignored.
    val nudged = ThemePreset.LIGHT.palette.primary + 0x000101
    check(restoredThemePreset(JsonParser.parseString("""{"Theme & Appearance":{"preset":"DARK_GLASS","primary":$nudged,"panel":${ThemePreset.LIGHT.palette.panel},
        "card":16777215,"text":${ThemePreset.LIGHT.palette.text},"animationMode":"HEAVY","unknown":1}}""").asJsonObject, ThemePreset.DARK_GLASS) == ThemePreset.LIGHT)
    check(ThemePreset.entries.map { it.palette.style }.toSet().size == ThemePreset.entries.size)

    val translucent = 0x80A15CC3.toInt()
    check(hudOpacityColor(translucent, .5) == 0x40A15CC3) { "HUD fade lost the configured alpha or changed its RGB" }
    check(hudOpacityColor(translucent, 1.0) == translucent)
    check(hudOpacityColor(translucent, 5.0) == translucent)
    val fades = listOf(0.0, .125, .25, .5, .75, 1.0).map { hudOpacityColor(translucent, it) }
    check(fades.all { it and 0xFFFFFF == translucent and 0xFFFFFF })
    check(fades.map { it ushr 24 }.zipWithNext().all { (before, after) -> before <= after && after <= 128 })
    for (invalid in listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
        check(hudOpacityColor(translucent, invalid) == translucent and 0xFFFFFF) { "Invalid HUD fade became opaque" }
    }
    check(hudOpacityColor(0x00A15CC3, .5) == 0x00A15CC3) { "Transparent configured HUD color gained opacity" }
    val originalConfig = JsonParser.parseString("""{"Theme & Appearance":{"preset":"LIGHT","unknown":true},"other":{"value":7}}""").asJsonObject
    val newConfig = JsonParser.parseString("""{"Theme & Appearance":{"preset":"MINIMAL"}}""").asJsonObject
    val merged = me.mycellium.skymyce.config.mergeConfigFields(originalConfig, newConfig)
    check(merged.getAsJsonObject("Theme & Appearance").get("unknown").asBoolean && merged.getAsJsonObject("other").get("value").asInt == 7)
    check(me.mycellium.skymyce.config.mergeConfigFields(merged, newConfig) == merged)

    // Saved widget layouts with a retired per-widget theme still load; the theme is ignored.
    val old = JsonParser.parseString("""{"x":480,"y":290,"scale":1.5,"anchor":"BOTTOM_RIGHT","unknownPreference":true}""")
    val migrated = requireNotNull(parseWidgetConfig(old))
    check(migrated.x == 480 && migrated.y == 290 && migrated.scale == 1.5f)
    check(parseWidgetConfig(Gson().toJsonTree(migrated)) == migrated)
    val withTheme = old.deepCopy().asJsonObject.apply { add("theme", JsonParser.parseString("""{"primary":99999999}""")) }
    check(parseWidgetConfig(withTheme) == migrated)

    // Animations follow the graphics mode: off in the two lightest modes.
    val previousPreset = ThemeConfig.preset; val previousQuality = ThemeConfig.graphicsQuality
    try {
        for ((quality, motion) in listOf(GuiQuality.EXTREMELY_LIGHT to GuiMotion.OFF, GuiQuality.LIGHT to GuiMotion.OFF,
                GuiQuality.MEDIUM to GuiMotion.MEDIUM, GuiQuality.HIGH to GuiMotion.HEAVY, GuiQuality.AUTO to GuiMotion.MEDIUM)) {
            ThemeConfig.graphicsQuality = quality
            check(ThemeConfig.animationMode == motion) { "$quality must use $motion animations" }
        }
        ThemeConfig.graphicsQuality = GuiQuality.HIGH
        ThemePreset.entries.forEach { preset ->
            ThemeConfig.preset = preset
            check(ThemeConfig.graphicsQuality == GuiQuality.HIGH) { "Changing presets reset the graphics mode" }
        }
        ThemeConfig.preset = ThemePreset.LIGHT
        val themed = themedHudText("§bTitle §fValue §aReady §cFailed")
        check(themed.string == "Title Value Ready Failed")
        check(themed.siblings.map { it.style.color?.value } == listOf(HudTheme.ACCENT, HudTheme.TEXT, HudTheme.GREEN, HudTheme.RED))
        check(HudTheme.TEXT == ThemePreset.LIGHT.palette.text && HudTheme.ACCENT == ThemePreset.LIGHT.palette.primary)
    } finally { ThemeConfig.preset = previousPreset; ThemeConfig.graphicsQuality = previousQuality }
    println("Theme checks passed: preset contrast, nearest-preset migration, motion by graphics mode and saved layout compatibility")
}
