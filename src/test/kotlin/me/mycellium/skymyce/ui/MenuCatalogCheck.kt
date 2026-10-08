package me.mycellium.skymyce.ui

import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.config.settingRows

/** Check the real config inventory so moved UI controls cannot silently vanish or duplicate. */
fun main() {
    val build = Config.javaClass.methods.first { it.name.startsWith("build") && it.parameterCount == 1 }
    val rows = settingRows(build.invoke(Config, null) as ResourcefulConfig)
    val catalog = MenuCatalog(rows)
    check(MenuCategory.entries.map { it.title } == listOf("Dungeons", "Combat", "Economy", "Cosmetics", "Utils", "Settings"))
    check(catalog.features.map { it.id }.distinct().size == catalog.features.size)
    MenuPage.entries.forEach { check(catalog.findFeature(it.featureId) != null) }
    check(catalog.features.map { it.category }.toSet() == MenuCategory.entries.toSet())
    catalog.features.filter { it.enabledPath != null }.forEach { feature ->
        val enabled = checkNotNull(catalog.setting(feature.enabledPath!!))
        check(enabled.entry.type() == com.teamresourceful.resourcefulconfig.api.types.options.EntryType.BOOLEAN)
        check(catalog.featureForPath(enabled.path)!!.id == feature.id)
    }
    val assigned = catalog.features.flatMap { it.settings }.map { it.path }
    check(assigned.size == rows.size && assigned.toSet() == rows.map { it.path }.toSet()) {
        "A visible saved setting lost its home or appears under multiple features"
    }
    rows.forEach { row ->
        val feature = checkNotNull(catalog.featureForPath(row.path))
        check(catalog.setting(row.path) === row)
        check(feature.category == MenuCatalog.settingLocation(row.path).category)
        check(catalog.search(row.title).any { it.settingPath == row.path }) { "Setting ${row.path} cannot be found by its name" }
    }
    check(catalog.findFeature("party-finder")!!.settings.any { it.path.startsWith("Party Finder/") })
    check(catalog.findFeature("party-finder")!!.settings.any { it.path.startsWith("Dungeons/Dungeon Friends/") })
    check(catalog.features.none { it.id == "friends" || it.id == "dungeon-friends" })
    check(MenuCatalog.settingLocation("General/displayAbilityCooldown").featureId == "ability-cooldowns")
    check(MenuCatalog.settingLocation("Mining/balNotify").category == MenuCategory.COMBAT)
    check(MenuCatalog.settingLocation("Dungeons/dungeonChestProfit").category == MenuCategory.DUNGEONS)
    check(MenuCatalog.settingLocation("General/Stash Helper/stashHelper").category == MenuCategory.ECONOMY)
    val globalPaths = catalog.features.filter { it.category == MenuCategory.SETTINGS }.flatMap { it.settings }.map { it.path }.toSet()
    check(globalPaths.all { it in setOf("openConfigKey", "gatherData", "devMode") || it.startsWith("Theme & Appearance/") })
    check(catalog.featureForPath("Theme & Appearance/graphicsQuality")!!.id == "settings-performance")
    check(catalog.featureForPath("Theme & Appearance/animationMode")!!.id == "settings-appearance")
    check(catalog.featureForPath("openConfigKey")!!.id == "settings-controls")
    val friends = catalog.search("friends")
    check(friends.map { it.category }.toSet().containsAll(listOf(MenuCategory.DUNGEONS, MenuCategory.ECONOMY, MenuCategory.UTILS)))
    check(friends.map { it.category.ordinal } == friends.map { it.category.ordinal }.sorted())
    check(catalog.search("ah").any { it.featureId == "auction-browser" })
    check(catalog.search("fps").any { it.settingPath == "Theme & Appearance/graphicsQuality" })
    check(catalog.search("animations").any { it.settingPath == "Theme & Appearance/animationMode" })
    check(catalog.search("scale").any { it.featureId == "settings-hud" })
    check(catalog.search("keybind").any { it.settingPath == "openConfigKey" })
    check(catalog.search("reduced motion").any { it.settingPath == "Theme & Appearance/animationMode" })
    val opacity = catalog.search("opacity").first { it.settingPath == "Dungeons/Starred Clear Routing/opacity" }
    check(opacity.breadcrumb == "Dungeons → Routes → Starred Clear Routing → Path Opacity")
    check(catalog.search("no matching feature 985713").isEmpty())
    check(catalog.search("AH").map { it.id } == catalog.search("ah").map { it.id })
    check(catalog.search("").map { it.id }.distinct().size == rows.size + catalog.features.size + MenuCatalog.extraSettings.size)
    check(catalog.search("message template").any { it.settingPath == "Dungeons/Dungeon Friends/messageTemplate" })
    check(catalog.search("pb limit").any { it.settingPath == "Dungeons/Dungeon Friends/availability" })
    check(catalog.search("text shadow").any { it.settingPath == "Theme & Appearance/shadow" })
    MenuCatalog.extraSettings.forEach { entry ->
        check(catalog.settingEntry(entry.settingPath!!) == entry && !entry.quickSettingEligible)
        check(catalog.featureForPath(entry.settingPath)!!.id == entry.featureId)
    }
    check(catalog.search("API fallback").none { it.settingPath?.endsWith("/hypixelApiKey") == true && it.quickSettingEligible })
    // Category terms can narrow a natural query without adding another feature home.
    check(catalog.search("economy friends").all { it.category == MenuCategory.ECONOMY })
    println("Menu catalog checks passed: six categories, ${catalog.features.size} canonical features, ${rows.size} settings and searchable aliases")
}
