package me.mycellium.skymyce.hud

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.widget.parseWidgetConfig
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.ui.MenuCanvas
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

fun checkThemes() {
    check(ThemePreset.entries.map { it.name }.containsAll(listOf("DARK_GLASS", "MINIMAL", "LIGHT", "HIGH_CONTRAST")))
    check(ThemePreset.entries.size >= 7 && ThemeDocument().preset == ThemePreset.SIGNATURE)
    check(themeSelectorPresets.toSet() == ThemePreset.entries.toSet())
    for (width in 1..1200) {
        val columns = themeSelectorColumns(width)
        check(columns in 1..3 && (width - (columns - 1) * 6) / columns > 0)
    }
    check(me.mycellium.skymyce.config.misc.restoredThemePreset(JsonParser.parseString("{}").asJsonObject,
        ThemePreset.SIGNATURE) == ThemePreset.SIGNATURE)
    check(me.mycellium.skymyce.config.misc.restoredThemePreset(JsonParser.parseString("""{"General":{}}""").asJsonObject,
        ThemePreset.SIGNATURE) == ThemePreset.DARK_GLASS)
    for (preset in ThemePreset.entries) {
        val existing = JsonParser.parseString("""{"Theme & Appearance":{"preset":"${preset.name}","primary":123}}""").asJsonObject
        check(me.mycellium.skymyce.config.misc.restoredThemePreset(existing, preset) == preset)
        check(parseThemeDocument(exportThemeDocument(ThemeDocument(preset = preset))).preset == preset)
        val palette = preset.palette
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
    check(ThemePreset.entries.map { it.palette.style }.toSet().size == ThemePreset.entries.size)
    writeThemePreviewSheet()
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
    val originalConfig = JsonParser.parseString("""{"Theme & Appearance":{"primary":123,"unknown":true},"other":{"value":7}}""").asJsonObject
    val newConfig = JsonParser.parseString("""{"Theme & Appearance":{"primary":456,"themeVersion":1}}""").asJsonObject
    val merged = me.mycellium.skymyce.config.mergeConfigFields(originalConfig, newConfig)
    check(merged.getAsJsonObject("Theme & Appearance").get("unknown").asBoolean && merged.getAsJsonObject("other").get("value").asInt == 7)
    check(me.mycellium.skymyce.config.mergeConfigFields(merged, newConfig) == merged)
    val global = ThemeOverrides(primary = 0x123456, opacity = 72)
    val widget = ThemeOverrides(primary = 0x654321, rounded = false)
    check(resolveTheme(ThemePreset.LIGHT, global).primary == global.primary)
    check(resolveTheme(ThemePreset.LIGHT, global).text == ThemePreset.LIGHT.palette.text)
    check(resolveTheme(ThemePreset.LIGHT, global, widget).let { it.primary == widget.primary && it.opacity == 72 && !it.rounded })
    check(resolveTheme(ThemePreset.LIGHT, global.copy(primary = 0xFFFFFF), widget).primary == widget.primary)
    check(resolveTheme(ThemePreset.LIGHT, global.copy(primary = 0xFFFFFF), widget.copy(primary = null)).primary == 0xFFFFFF)
    val doc = ThemeDocument(preset = ThemePreset.HIGH_CONTRAST, overrides = widget)
    check(parseThemeDocument(exportThemeDocument(doc)) == doc)
    check(parseThemeDocument("""{"version":1}""").let { it.overrides == ThemeOverrides() && it.preset == ThemePreset.DARK_GLASS })
    listOf("{}", "{", """{"version":2}""", """{"version":"1"}""", """{"version":1,"overrides":{"opacity":0}}""",
        """{"version":1,"overrides":{"primary":-1}}""", """{"version":1,"overrides":{"primary":"123"}}""",
        """{"version":1,"overrides":{"padding":1}}""", """{"version":1,"overrides":{"shadow":"false"}}""",
        """{"version":1,"overrides":{"script":"run"}}""", " ".repeat(16385)).forEach { check(runCatching { parseThemeDocument(it) }.isFailure) }
    val old = JsonParser.parseString("""{"x":480,"y":290,"scale":1.5,"anchor":"BOTTOM_RIGHT","unknownPreference":true}""")
    val migrated = requireNotNull(parseWidgetConfig(old))
    check(migrated.x == 480 && migrated.y == 290 && migrated.scale == 1.5f && migrated.theme == null)
    val customized = migrated.copy(theme = widget)
    val restored = parseWidgetConfig(Gson().toJsonTree(customized))
    check(restored == customized && restored?.copy(theme = null) == migrated)
    val corruptedTheme = old.deepCopy().asJsonObject.apply { add("theme", JsonParser.parseString("""{"primary":99999999}""")) }
    check(parseWidgetConfig(corruptedTheme) == migrated)
    val previous = ThemeConfig.overrides(); val previousPreset = ThemeConfig.preset
    val previousVersion = ThemeConfig.themeVersion; val previousMigration = ThemeConfig.migratedDigestTheme
    val previousQuality = ThemeConfig.graphicsQuality; val previousMotion = ThemeConfig.animationMode
    try {
        ThemeConfig.preset = ThemePreset.SIGNATURE; ThemeConfig.apply(ThemeOverrides())
        ThemeConfig.restoreLegacyTheme(com.google.gson.JsonObject()); ThemeConfig.migrateDigestTheme()
        check(ThemeConfig.preset == ThemePreset.SIGNATURE && ThemeConfig.overrides() == ThemeOverrides())
        ThemeConfig.apply(global.copy(primary = 0x67CCF2, secondary = 0xC2A2F5, opacity = 90))
        ThemeConfig.restoreLegacyTheme(JsonParser.parseString("""{"General":{}}""").asJsonObject)
        ThemeConfig.migratedDigestTheme = false; ThemeConfig.themeVersion = 0; ThemeConfig.migrateDigestTheme()
        check(ThemeConfig.preset == ThemePreset.DARK_GLASS && ThemeConfig.primary == 0x67CCF2 &&
            ThemeConfig.secondary == 0xC2A2F5 && ThemeConfig.opacity == 90) { "Legacy migration reset explicit theme overrides" }
        val retained = ThemeConfig.overrides()
        ThemePreset.entries.forEach { preset ->
            ThemeConfig.preset = preset
            check(ThemeConfig.overrides() == retained && ThemeConfig.graphicsQuality == previousQuality &&
                ThemeConfig.animationMode == previousMotion) { "Changing presets reset unrelated settings" }
        }
    } finally {
        ThemeConfig.apply(previous); ThemeConfig.preset = previousPreset
        ThemeConfig.themeVersion = previousVersion; ThemeConfig.migratedDigestTheme = previousMigration
    }
    ThemeConfig.preset = ThemePreset.LIGHT; ThemeConfig.apply(global)
    val themed = themedHudText("§bTitle §fValue §aReady §cFailed")
    check(themed.string == "Title Value Ready Failed")
    check(themed.siblings.map { it.style.color?.value } == listOf(HudTheme.ACCENT, HudTheme.TEXT, HudTheme.GREEN, HudTheme.RED))
    check(HudTheme.TEXT == ThemePreset.LIGHT.palette.text)
    HudTheme.withWidget(widget) { check(HudTheme.ACCENT == widget.primary && HudTheme.OPACITY == 72) }
    check(HudTheme.ACCENT == global.primary)
    ThemeConfig.apply(previous); ThemeConfig.preset = previousPreset
    val dir = Files.createTempDirectory("sky-hawk-theme-check")
    try {
        val path = dir.resolve("theme.json"); val file = AtomicJsonFile(path)
        check(file.read() == null)
        Files.writeString(path, "broken")
        check(file.read() == null && file.damaged)
        file.write(JsonParser.parseString(exportThemeDocument(doc)))
        check(parseThemeDocument(Files.readString(path)) == doc)
        Files.list(dir).use { paths -> check(paths.anyMatch { it.fileName.toString().contains("corrupt-") }) }
    } finally { Files.list(dir).use { it.forEach(Files::delete) }; Files.delete(dir) }
    println("Theme checks passed: inheritance, resets, safe import, saved layout compatibility and corrupt-file recovery")
}

private fun writeThemePreviewSheet() {
    val canvas = MenuCanvas()
    try {
        canvas.begin()
        themeSelectorPresets.forEachIndexed { index, preset ->
            val x = 16.0 + index % 2 * 292; val y = 16.0 + index / 2 * 146
            canvas.fill(x, y, 276.0, 130.0, preset.palette.panel or -0x1000000)
            canvas.text(listOf(MenuCanvas.Run(preset.title, preset.palette.text or -0x1000000, bold = true)), x + 10, y + 8, 12.0)
            ThemePreview(preset.palette).paint(canvas, x + 10, y + 31, 256.0, 88.0)
        }
        val target = File("theme-presets.png")
        ImageIO.write(canvas.finish(600, 600, 1.5), "png", target)
        println("Theme selector samples: ${target.absolutePath}")
    } finally { canvas.close() }
}
