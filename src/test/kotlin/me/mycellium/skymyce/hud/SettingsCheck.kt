package me.mycellium.skymyce.hud

import me.mycellium.skymyce.config.*
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.digest.DigestEntry
import me.mycellium.skymyce.features.digest.digestShareText

fun checkSettings() {
    val row = settingsRow().horizontalSizing(io.wispforest.owo.ui.core.Sizing.fixed(400)) as io.wispforest.owo.ui.container.FlowLayout
    val first = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(100), io.wispforest.owo.ui.core.Sizing.fixed(20))
    val second = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(100), io.wispforest.owo.ui.core.Sizing.fixed(20))
    row.children(listOf(first, second)); row.inflate(io.wispforest.owo.ui.core.Size.of(400, 240)); row.mount(null, 0, 0)
    check(row.children().size == 2 && second.x() == first.x() + 105 && second.y() == first.y())
    val build = Config.javaClass.methods.first { it.name.startsWith("build") && it.parameterCount == 1 }
    val raw = build.invoke(Config, null) as com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
    val delegate = com.teamresourceful.resourcefulconfig.common.loader.ParsedConfig(7, raw.id(), raw.info(), raw.elements(), raw.categories())
    var saves = 0
    val wrapped = SavingConfig(delegate) { saves++ }
    val rows = settingRows(wrapped) // Use the same saving wrapper as the live settings screen.
    check(rows.map { it.path } == settingRows(delegate).map { it.path }) { "Saving wrapper lost settings" }
    check(wrapped.version() == 7 && wrapped.id() == delegate.id() && wrapped.info() === delegate.info())
    check(wrapped.categories() === delegate.categories())
    wrapped.save(); check(saves == 1)
    check(rows.map { it.path }.containsAll(listOf("devMode", "gatherData", "openConfigKey")))
    check(rows.isNotEmpty() && rows.map { it.path }.distinct().size == rows.size)
    check(rows.all { it.title.isNotBlank() && it.tip.isNotBlank() })
    check(rows.map { settingsTab(it.path) }.toSet() == SettingsTab.entries.toSet())
    check(rows.none { it.path.endsWith("migratedDigestTheme") || it.path == "General/Daily Digest/accent" || it.path == "General/Daily Digest/cardOpacity" })
    check(settingsTab("General/Daily Digest/automatic") == SettingsTab.GENERAL)
    check(settingsTab("General/Daily Digest/shareRng") == SettingsTab.SOCIAL)
    check(settingsTab("Party Finder/matchPb") == SettingsTab.PARTY)
    check(settingsTab("Dungeons/Dungeon Tracker/dungeonTracker") == SettingsTab.COMBAT)
    check(settingsTab("Theme & Appearance/opacity") == SettingsTab.THEME)
    check(rows.any { it.path == "General/Party Commands/hypixelApiKey" }) // Existing storage path retained.
    val devMode = rows.single { it.path == "devMode" }
    check(devMode.title == "Dev Mode" && settingsTab(devMode.path) == SettingsTab.GENERAL)
    val previousDevMode = Config.devMode
    try {
        check(devMode.entry.setBoolean(!previousDevMode) && Config.devMode == !previousDevMode)
    } finally { devMode.entry.setBoolean(previousDevMode) }
    check(parseThemeHex("#67ccf2") == 0x67CCF2 && parseThemeHex("FFFFFF") == 0xFFFFFF)
    listOf("", "#", "#123", "#12345678", "#GGFFFF", "-1").forEach { check(parseThemeHex(it) == null) }
    val primary = ThemeConfig.primary
    ThemeConfig.primary = 0x123456
    check(HudTheme.ACCENT == 0x123456 && HudTheme.HUD_ACCENT == 0x123456)
    ThemeConfig.primary = primary
    check(HudTheme.alpha(0x123456, 0) == 0x123456 && HudTheme.alpha(0x123456, 100) == 0xFF123456.toInt())
    check(HudTheme.blend(0, 0xFFFFFF, 100) == 0xFFFFFF)
    val report = digestShareText(DigestEntry("id", "§aHandle\n/command", activity = "dungeons", community = true))
    check('§' !in report && '\n' !in report && report.endsWith("[unverified]"))
    check(digestShareText(DigestEntry("id", "x".repeat(500))).length <= 240)
    val longReport = digestShareText(DigestEntry("id", "x".repeat(500), community = true))
    check(longReport.length <= 240 && longReport.endsWith("[unverified]"))
    println("Settings checks passed: category coverage, config paths, tooltips, theme validation and safe share drafts")
}
