package me.mycellium.skymyce.ui

import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.config.settingGroups
import me.mycellium.skymyce.config.settingNumber
import me.mycellium.skymyce.config.settingRows
import java.lang.management.ManagementFactory
import java.util.Locale

@Volatile private var menuSearchSink = emptyList<MenuSearchEntry>()

private fun measureMenuSearch(catalog: MenuCatalog) {
    val queries = listOf("f", "fortune", "party", "animations", "blood", "economy friends")
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    bean?.takeIf { it.isThreadAllocatedMemorySupported }?.isThreadAllocatedMemoryEnabled = true
    repeat(60) { queries.forEach { menuSearchSink = catalog.search(it) } }
    val id = Thread.currentThread().threadId()
    val bytes = bean?.getThreadAllocatedBytes(id) ?: 0L
    val start = System.nanoTime()
    repeat(100) { queries.forEach { menuSearchSink = catalog.search(it) } }
    val nanos = System.nanoTime() - start
    val allocated = (bean?.getThreadAllocatedBytes(id) ?: bytes) - bytes
    println("Menu catalog search: ${catalog.search("").size} entries, 600 warmed queries: ${nanos / 600 / 1000.0}us/query ${allocated / 600}B/query")
}

/** Check the real config inventory so moved UI controls cannot silently vanish or duplicate. */
fun main() {
    val build = Config.javaClass.methods.first { it.name.startsWith("build") && it.parameterCount == 1 }
    val rows = settingRows(build.invoke(Config, null) as ResourcefulConfig)
    val catalog = MenuCatalog(rows)
    measureMenuSearch(catalog)
    val indexedEntries = catalog.search("")
    check(catalog.search("") === indexedEntries) { "Blank search rebuilt the sorted inventory" }
    fun originalSearch(query: String): List<MenuSearchEntry> {
        val tokens = Regex("[a-z0-9]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        return indexedEntries.filter { entry ->
            val text = (listOf(entry.title, entry.description, entry.category.title, entry.subcategory) + entry.aliases)
                .joinToString(" ").lowercase(Locale.ROOT)
            val words = Regex("[a-z0-9]+").findAll(text).map { it.value }.toList()
            tokens.all { token -> words.any { menuSearchWordMatches(it, token) } }
        }.sortedWith(compareBy<MenuSearchEntry> { it.category.ordinal }.thenBy { it.subcategory }
            .thenBy { it.settingPath != null }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
    for (query in listOf("", "f", "fortune", "party", "dungeom", "animations", "blood", "economy friends", "AH", "reduced motion", "!!", "missing xyz")) {
        check(catalog.search(query) == originalSearch(query)) { "Cached search changed matching/order for '$query'" }
    }
    val sourceEntries = indexedEntries.toMutableList()
    val isolatedIndex = MenuSearchIndex(sourceEntries)
    sourceEntries.clear()
    check(isolatedIndex.search("blood") == catalog.search("blood")) { "Search index retained a mutable caller inventory" }
    check(MenuSearchIndex(emptyList()).search("anything").isEmpty())
    val camera = checkNotNull(catalog.findFeature("healer-crush-camera"))
    check(camera.title == "Crush Camera")
    check(camera.enabledPath == "Dungeons/Healer Crush Camera/enabled")
    val leap = checkNotNull(catalog.findFeature("leap-menu"))
    check(leap.settings.map { it.path.substringAfterLast('/') }.toSet() == setOf("enabled", "povEnabled", "partyPovSharing", "previewFramesPerSecond"))
    check(!checkNotNull(catalog.setting("Dungeons/Leap Menu/partyPovSharing")).entry.boolean) { "Party POV sharing must require explicit opt-in" }
    val cameraGroups = settingGroups(rows, camera.id)
    check(cameraGroups.keys == setOf("Automatic activation", "Appearance"))
    for (key in listOf("enabled", "healer", "mage", "berserk", "archer", "tank"))
        check(cameraGroups.getValue("Automatic activation").any { it.path == "Dungeons/Healer Crush Camera/$key" })
    val irc = checkNotNull(catalog.findFeature("global-chat"))
    check(irc.title == "IRC Chat" && "/sm party" in irc.description && "/sm irc" in irc.description)
    for (key in listOf("enabled", "partyEnabled", "mediaPreviews", "previewSize", "animateGifs", "mentionHighlight", "mentionSounds"))
        check(irc.settings.any { it.path == "General/Global SkyMyce Chat/$key" })
    val previewSize = checkNotNull(catalog.setting("General/Global SkyMyce Chat/previewSize")).entry
    check(previewSize.int == 100 && previewSize.options().hasOption(com.teamresourceful.resourcefulconfig.api.types.options.Option.SLIDER))
    val previewRange = checkNotNull(previewSize.options().getOption(com.teamresourceful.resourcefulconfig.api.types.options.Option.RANGE))
    check(previewRange.min == 50.0 && previewRange.max == 200.0)
    check(catalog.search("gif").any { it.featureId == irc.id })
    check(catalog.search("global skymyce chat").any { it.featureId == irc.id })
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
    check(globalPaths.all { it in setOf("openConfigKey", "gatherData", "devMode") || it.startsWith("Theme & Appearance/") || it.startsWith("Community Sharing/") })
    for (key in listOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName")) {
        check(catalog.featureForPath("Community Sharing/$key")?.id == "community")
    }
    check(catalog.featureForPath("Theme & Appearance/graphicsQuality")!!.id == "settings-performance")
    check(catalog.featureForPath("Theme & Appearance/animationMode")!!.id == "settings-appearance")
    check(catalog.featureForPath("openConfigKey")!!.id == "settings-controls")
    check(catalog.search("fortune").any { it.featureId == "mining" && it.settingPath != null })
    check(catalog.search("f").isNotEmpty())
    check(catalog.search("dungeom").any { it.category == MenuCategory.DUNGEONS })
    check(menuSearchWordMatches("fortune", "fortne"))
    check(menuSearchWordMatches("fortune", "fortunne"))
    check(!menuSearchWordMatches("fortune", "fxrtxne"))
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
    check(opacity.breadcrumb == "Dungeons → Routes → Dungeon Routing → Line opacity")
    val onlySetting = menuSearchFeatureGroups(catalog, listOf(opacity))
    check(onlySetting.single().first.id == opacity.featureId && onlySetting.single().second == listOf(opacity)) {
        "Settings-only search lost its owning feature context"
    }
    val routingEntry = indexedEntries.single { it.featureId == opacity.featureId && it.settingPath == null }
    val cameraEntry = indexedEntries.single { it.featureId == camera.id && it.settingPath == null }
    val heightEntry = checkNotNull(catalog.settingEntry("Dungeons/Starred Clear Routing/lineHeight"))
    val mixedMatches = listOf(opacity, cameraEntry, routingEntry, heightEntry)
    val groupedMatches = menuSearchFeatureGroups(catalog, mixedMatches)
    check(groupedMatches.map { it.first.id } == listOf(opacity.featureId, camera.id)) {
        "Search owner cards duplicated or changed first-match order"
    }
    check(groupedMatches.first().second == listOf(opacity, heightEntry) && groupedMatches.last().second.isEmpty()) {
        "Search showed unmatched settings or changed matching child order"
    }
    for (limit in 0..mixedMatches.size) {
        val page = mixedMatches.take(limit)
        val groups = menuSearchFeatureGroups(catalog, page)
        check(groups.flatMap { it.second }.toSet() == page.filter { it.settingPath != null }.toSet()) {
            "Search owner context changed the paged matching settings"
        }
    }
    val routing = checkNotNull(catalog.findFeature("starred-routes"))
    check(routing.title == "Dungeon Routing" && routing.enabledPath == "Dungeons/Starred Clear Routing/enabled")
    val routingGroups = settingGroups(rows, routing.id)
    val expectedRoutingGroups = linkedMapOf(
        "Route" to listOf("enabled", "routeThroughMobs", "routeThroughStarredMinibosses", "showRoute"),
        "Mob markers" to listOf("showTargets", "showNumbers", "showCleared"),
        "Appearance" to listOf("thickness", "opacity", "lineHeight"),
        "Advanced" to listOf("showHealer", "automaticRecalculation", "debug"),
    )
    check(routingGroups.keys.toList() == expectedRoutingGroups.keys.toList())
    check(routingGroups.mapValues { (_, settings) -> settings.map { it.element.id() } } == expectedRoutingGroups)
    check(routingGroups.values.flatten().map { it.path }.sorted() == routing.settings.map { it.path }.sorted()) {
        "Grouping dungeon routing must retain every saved control exactly once"
    }
    check(settingGroups(rows, routing.id, "Developer diagnostics").keys.toList() == listOf("Advanced"))
    check(catalog.search("starred clear routing").any { it.featureId == routing.id && it.settingPath == null })
    check(catalog.search("straight blood").any { it.settingPath == "Dungeons/Starred Clear Routing/routeThroughMobs" })
    val routeMode = checkNotNull(catalog.setting("Dungeons/Starred Clear Routing/routeThroughMobs"))
    check(routeMode.title == "Route through starred mobs" && routeMode.entry.boolean)
    val minibossMode = checkNotNull(catalog.setting("Dungeons/Starred Clear Routing/routeThroughStarredMinibosses"))
    check(minibossMode.title == "Route through starred minibosses" && minibossMode.entry.boolean)
    check(catalog.search("starred minibosses").any { it.settingPath == minibossMode.path })
    val booleanDefaults = mapOf("enabled" to true, "routeThroughMobs" to true, "routeThroughStarredMinibosses" to true, "showTargets" to true,
        "showRoute" to true, "showNumbers" to true, "showCleared" to false, "showHealer" to true,
        "automaticRecalculation" to true, "debug" to false)
    booleanDefaults.forEach { (key, expected) -> check(catalog.setting("Dungeons/Starred Clear Routing/$key")!!.entry.boolean == expected) }
    check(catalog.setting("Dungeons/Starred Clear Routing/thickness")!!.entry.int == 2)
    check(catalog.setting("Dungeons/Starred Clear Routing/opacity")!!.entry.int == 85)
    val lineHeight = checkNotNull(catalog.setting("Dungeons/Starred Clear Routing/lineHeight"))
    check(lineHeight.title == "Line height" && lineHeight.entry.double == 1.2)
    val heightRange = checkNotNull(lineHeight.entry.options().getOption(com.teamresourceful.resourcefulconfig.api.types.options.Option.RANGE))
    check(heightRange.min == 0.0 && heightRange.max == 4.0)
    check(lineHeight.entry.options().hasOption(com.teamresourceful.resourcefulconfig.api.types.options.Option.SLIDER))
    check(settingNumber(lineHeight.entry, "0") == 0.0 && settingNumber(lineHeight.entry, "4") == 4.0)
    check(settingNumber(lineHeight.entry, "-0.1") == null && settingNumber(lineHeight.entry, "4.1") == null)
    check(catalog.search("line height").any { it.settingPath == lineHeight.path })
    val mobMarkers = routingGroups.getValue("Mob markers")
    val markerValues = mobMarkers.map { it.entry.boolean }
    try {
        check(routeMode.entry.setBoolean(false) && !routeMode.entry.boolean)
        check(minibossMode.entry.boolean) { "Regular mob routing must leave the independent starred miniboss toggle enabled" }
        check(minibossMode.entry.setBoolean(false) && !minibossMode.entry.boolean)
        check(mobMarkers.map { it.entry.boolean } == markerValues) { "Routing selections must keep independent mob-marker settings" }
    } finally { routeMode.entry.setBoolean(true); minibossMode.entry.setBoolean(true) }
    check(catalog.setting("Dungeons/Starred Clear Routing/showNumbers")!!.title == "Show mob labels")
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
    check(catalog.search("economy friends").all { it.category == MenuCategory.ECONOMY || it.settingPath == "Community Sharing/economy" })
    println("Menu catalog checks passed: six categories, ${catalog.features.size} canonical features, ${rows.size} settings and searchable aliases")
}
