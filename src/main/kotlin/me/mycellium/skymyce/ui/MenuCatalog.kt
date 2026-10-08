package me.mycellium.skymyce.ui

import com.teamresourceful.resourcefulconfig.api.types.options.Option
import me.mycellium.skymyce.config.SettingRow
import java.util.Locale

/** UI identities are independent of saved Resourceful Config category names and keys. */
enum class MenuCategory(val title: String, val description: String, val iconId: Int) {
    DUNGEONS("Dungeons", "Dungeon parties, routes, rewards and run history", 4),
    COMBAT("Combat", "Ability timers, equipment and instance helpers", 9),
    ECONOMY("Economy", "Auctions, item values, loans and player wealth", 5),
    COSMETICS("Cosmetics", "Player names, visual scaling and cosmetic selection", 6),
    UTILS("Utils", "Daily progress, social tools and general helpers", 1),
    SETTINGS("Settings", "Configure controls, graphics, HUD and appearance", 8),
}

data class MenuFeature(
    val id: String,
    val title: String,
    val description: String,
    val category: MenuCategory,
    val subcategory: String,
    val aliases: List<String> = emptyList(),
    val iconId: Int = category.iconId,
    val tool: String? = null,
    val settings: List<SettingRow> = emptyList(),
    val searchable: Boolean = true,
    val favoriteEligible: Boolean = true,
    val enabledPath: String? = null,
) {
    val breadcrumb get() = listOf(category.title, subcategory, title).distinct().joinToString(" → ")
}

data class MenuSettingLocation(val category: MenuCategory, val subcategory: String, val featureId: String? = null)

data class MenuSearchEntry(
    val id: String,
    val title: String,
    val description: String,
    val category: MenuCategory,
    val subcategory: String,
    val featureId: String,
    val settingPath: String? = null,
    val aliases: List<String> = emptyList(),
    val quickSettingEligible: Boolean = settingPath != null,
    val favoriteEligible: Boolean = true,
    val featureTitle: String? = null,
) {
    val breadcrumb get() = listOfNotNull(category.title, subcategory, featureTitle, title).distinct().joinToString(" → ")
}

/** One inventory supplies category cards, feature settings, search and canonical shortcuts. */
class MenuCatalog(rows: List<SettingRow>) {
    val features = definitions.map { feature -> feature.copy(settings = rows.filter { row ->
        val owner = settingLocation(row.path)
        if (owner.featureId == null) feature.category == MenuCategory.SETTINGS && owner.subcategory == feature.subcategory
        else owner.featureId == feature.id
    }) }
    private val byId = features.associateBy { it.id }
    private val byPath = features.flatMap { feature -> feature.settings.map { it.path to feature } }.toMap()
    private val entries by lazy { features.filter { it.searchable }.flatMap { feature ->
        listOf(MenuSearchEntry("feature:${feature.id}", feature.title, feature.description, feature.category,
            feature.subcategory, feature.id, aliases = feature.aliases)) + feature.settings.map { row ->
            MenuSearchEntry("setting:${row.path}", row.title, row.tip, feature.category, feature.subcategory, feature.id,
                row.path, feature.aliases + listOf(feature.title, row.path) +
                    if (row.entry.options().hasOption(Option.KEYBIND)) listOf("keybind", "keyboard", "controls") else emptyList(),
                quickSettingEligible = !row.path.endsWith("/hypixelApiKey"), featureTitle = feature.title)
        }
    } + extraSettings.filter { it.settingPath !in byPath } }

    fun findFeature(id: String) = byId[id]
    fun featureForPath(path: String) = byPath[path] ?: extraSettings.firstOrNull { it.settingPath == path }?.let { byId[it.featureId] }
    fun setting(path: String) = byPath[path]?.settings?.firstOrNull { it.path == path }
    fun settingEntry(path: String) = entries.firstOrNull { it.settingPath == path }
    fun search(query: String) = searchMenuEntries(entries, query)

    companion object {
        val definitions = listOf(
            MenuFeature("party-finder", "Party Finder", "Online dungeon friends, LFG availability, invites and party filters", MenuCategory.DUNGEONS, "Parties", listOf("party", "friends", "lfg", "availability", "dungeon-friends", "secondary classes"), tool = "party-finder", enabledPath = "Dungeons/Dungeon Friends/enabled"),
            MenuFeature("starred-routes", "Starred Clear Routing", "Wall-aware starred mob routes and the Blood/Wither exit", MenuCategory.DUNGEONS, "Routes", listOf("route", "path", "blood", "wither", "healer", "starred"), enabledPath = "Dungeons/Starred Clear Routing/enabled"),
            MenuFeature("dungeon-tracker", "Dungeon Tracker", "Run history, loot records and estimated dungeon rewards", MenuCategory.DUNGEONS, "Runs", listOf("loot", "profit", "bazaar", "history", "rng"), tool = "dungeon-tracker", enabledPath = "Dungeons/Dungeons Tracker/dungeonTracker"),
            MenuFeature("dungeon-chests", "Dungeon Chests", "Estimated chest profit, valuable loot and purchase/reroll protection", MenuCategory.DUNGEONS, "Rewards", listOf("profit", "kismet", "reroll", "bazaar", "prices", "chest")),
            MenuFeature("dungeon-chat", "Dungeon Chat Filter", "Choose which dungeon messages remain visible", MenuCategory.DUNGEONS, "Information", listOf("chat", "messages", "filter")),
            MenuFeature("dungeon-summary", "Dungeon Win Screen", "Show a summary overlay after a completed dungeon", MenuCategory.DUNGEONS, "Runs", listOf("overlay", "hud", "win"), enabledPath = "Dungeons/dungeonWinScreen"),
            MenuFeature("ability-cooldowns", "Ability Cooldowns", "Held-item ability timers and ready notifications", MenuCategory.COMBAT, "Timers", listOf("cooldown", "timer", "ability", "hud", "notification"), enabledPath = "General/displayAbilityCooldown"),
            MenuFeature("loadouts", "Loadout Keybinds", "Swap equipment loadouts with configured keys and menu safeguards", MenuCategory.COMBAT, "Gear", listOf("equipment", "keybind", "keyboard", "armor"), enabledPath = "General/Loadout Keybinds/enabled"),
            MenuFeature("wardrobe", "Wardrobe Keybinds", "Saved armor-slot keys and safeguards; this module is currently inactive", MenuCategory.COMBAT, "Gear", listOf("armor", "equipment", "wardrobe", "keybind")),
            MenuFeature("instance-helpers", "Instance Helpers", "Missing-player alerts and requeue behavior for dungeon and Kuudra parties", MenuCategory.COMBAT, "Instances", listOf("dungeons", "kuudra", "party", "requeue", "downtime")),
            MenuFeature("bal-notifier", "Bal Notifications", "Share Bal spawn and health milestones with your party", MenuCategory.COMBAT, "Enemies", listOf("bal", "boss", "mob", "mining", "health"), enabledPath = "Mining/balNotify"),
            MenuFeature("auction-browser", "Auction Browser", "Search cached auction listings, filters, bids and item lore", MenuCategory.ECONOMY, "Auctions", listOf("ah", "auction house", "market", "prices", "bin"), tool = "auction-browser"),
            MenuFeature("loans", "Loans & Lending", "Local trade history and the items lent to friends", MenuCategory.ECONOMY, "Loans", listOf("friends", "gear", "trade", "lending", "borrow"), tool = "loans"),
            MenuFeature("player-economy", "Player Wealth", "Compare cached friend networth, purse, bank and wardrobe estimates", MenuCategory.ECONOMY, "Players", listOf("friends", "player", "wealth", "networth", "compare"), tool = "player-economy"),
            MenuFeature("stash-helper", "Stash Value Filter", "Filter low-value stash items and prevent unwanted pickups", MenuCategory.ECONOMY, "Item Values", listOf("stash", "price", "coins", "value", "filter"), enabledPath = "General/Stash Helper/stashHelper"),
            MenuFeature("cosmetics", "Player Cosmetics", "Choose cosmetics and control rendered names and player scaling", MenuCategory.COSMETICS, "Player Appearance", listOf("appearance", "names", "scale", "scaling", "preview"), tool = "cosmetics"),
            MenuFeature("daily-digest", "Daily Digest", "Daily progress, public updates and optional community RNG reports", MenuCategory.UTILS, "Progress", listOf("news", "rng", "daily", "history", "rewards"), tool = "daily-digest"),
            MenuFeature("global-chat", "Global SkyMyce Chat", "Opt into global client/Discord chat and mention notifications", MenuCategory.UTILS, "Social", listOf("irc", "discord", "messages", "social", "global", "ping"), tool = "global-chat", enabledPath = "General/Global SkyMyce Chat/enabled"),
            MenuFeature("party-commands", "Party Commands", "Choose party commands, cooldowns and the optional stats API fallback", MenuCategory.UTILS, "Social", listOf("party", "pb", "downtime", "commands", "api")),
            MenuFeature("player-utilities", "Player Utilities", "Cached friend activity, locations and profile information", MenuCategory.UTILS, "Social", listOf("friends", "player", "profiles", "online", "social"), tool = "player-utilities"),
            MenuFeature("mining", "Crystal Hollows Helpers", "Lobby information and configurable nucleus warp timing", MenuCategory.UTILS, "Mining", listOf("mining", "crystal", "nucleus", "powder", "lobby", "hud")),
            MenuFeature("auto-refill", "Auto Refill", "Choose which consumable items are refilled when empty", MenuCategory.UTILS, "Inventory", listOf("refill", "items", "consumables", "inventory")),
            MenuFeature("information", "Information Overlays", "Display client details and skill XP progress", MenuCategory.UTILS, "Information", listOf("xp", "skill", "client", "hud", "overlay")),
            MenuFeature("settings-general", "General", "Client data collection and developer preferences", MenuCategory.SETTINGS, "General", listOf("client", "preferences", "developer", "data")),
            MenuFeature("settings-appearance", "Appearance", "Shared theme colors, opacity and animation personality", MenuCategory.SETTINGS, "Appearance", listOf("theme", "colors", "animation", "animations", "motion", "accessibility", "reduced motion"), tool = "theme-editor"),
            MenuFeature("settings-hud", "HUD", "Arrange widgets, visibility, scale and per-widget themes", MenuCategory.SETTINGS, "HUD", listOf("hud", "overlay", "layout", "widget", "scale", "visibility"), tool = "hud-editor"),
            MenuFeature("settings-performance", "Performance", "GUI rendering quality, raster detail, shadows and effects", MenuCategory.SETTINGS, "Performance", listOf("fps", "graphics", "quality", "rendering", "performance")),
            MenuFeature("settings-controls", "Controls", "Choose the key that opens the Sky-Myce menu", MenuCategory.SETTINGS, "Controls", listOf("keybind", "keyboard", "input", "controls")),
        )

        // These existing editors own controls outside the generic visible Resourceful rows.
        val themeColorTitles = linkedMapOf("primary" to "Primary accent", "secondary" to "Secondary accent",
            "panel" to "Panel background", "card" to "Card background", "text" to "Primary text",
            "muted" to "Secondary text", "border" to "Borders")
        val themeFlagTitles = linkedMapOf("rounded" to "Rounded corners", "shadow" to "Text shadow", "borders" to "Show borders")
        val themeNumberTitles = linkedMapOf("opacity" to "Panel opacity", "padding" to "Padding / compactness")
        val extraSettings: List<MenuSearchEntry> = buildList {
            fun addSetting(featureId: String, path: String, title: String, description: String, aliases: List<String>) {
                val feature = definitions.first { it.id == featureId }
                add(MenuSearchEntry("setting:$path", title, description, feature.category, feature.subcategory, feature.id,
                    path, feature.aliases + aliases, quickSettingEligible = false, featureTitle = feature.title))
            }
            addSetting("party-finder", "Dungeons/Dungeon Friends/availability", "Dungeon Availability",
                "Choose your available floor, classes and join PB limit", listOf("availability", "floor", "classes", "personal best", "pb limit"))
            addSetting("party-finder", "Dungeons/Dungeon Friends/messageTemplate", "LFG Message Template",
                "Customize the friend Message action using name, class and floor placeholders", listOf("message", "template", "lfg"))
            addSetting("party-finder", "Dungeons/Dungeon Friends/titleNotifications", "Title Notifications",
                "Show invitations and joining players as titles", listOf("notification", "titles", "invite"))
            for ((key, title) in themeColorTitles.filterKeys { it !in setOf("primary", "secondary") }) {
                addSetting("settings-appearance", "Theme & Appearance/$key", title,
                    "Choose an inherited theme color or an RGB override", listOf("color", "palette", "rgb", "red", "green", "blue", key))
            }
            for ((key, title) in themeFlagTitles) addSetting("settings-appearance", "Theme & Appearance/$key", title,
                "Choose inherited, enabled or disabled in the shared theme editor", listOf("visual", "theme", key))
            addSetting("settings-appearance", "Theme & Appearance/padding", themeNumberTitles.getValue("padding"),
                "Adjust inherited panel spacing or an explicit padding override", listOf("padding", "spacing", "compact", "density"))
        }

        fun settingLocation(path: String): MenuSettingLocation {
            val key = path.substringAfterLast('/')
            val feature = when {
                path.startsWith("Party Finder/") || path.startsWith("Dungeons/Dungeon Friends/") -> "party-finder"
                path.startsWith("Dungeons/Starred Clear Routing/") -> "starred-routes"
                path.startsWith("Dungeons/Dungeons Tracker/") -> "dungeon-tracker"
                path.startsWith("Dungeons/") && key == "dungeonWinScreen" -> "dungeon-summary"
                path.startsWith("Dungeons/") && key == "dungeonMessageFilter" -> "dungeon-chat"
                path.startsWith("Dungeons/") -> "dungeon-chests"
                path.startsWith("Instances/") -> "instance-helpers"
                path.startsWith("Cosmetics/") -> "cosmetics"
                path.startsWith("Mining/") && key == "balNotify" -> "bal-notifier"
                path.startsWith("Mining/") -> "mining"
                path.startsWith("General/Daily Digest/") -> "daily-digest"
                path.startsWith("General/Global SkyMyce Chat/") -> "global-chat"
                path.startsWith("General/Party Commands/") -> "party-commands"
                path.startsWith("General/Stash Helper/") -> "stash-helper"
                path.startsWith("General/Loadout Keybinds/") -> "loadouts"
                path.startsWith("General/Wardrobe Keybinds/") -> "wardrobe"
                path == "General/autoRefill" -> "auto-refill"
                path == "General/displayAbilityCooldown" || path == "General/displayAbilityNotification" -> "ability-cooldowns"
                path == "General/clientInfo" || path == "General/displayXp" -> "information"
                else -> null
            }
            if (feature != null) return definitions.first { it.id == feature }.let {
                MenuSettingLocation(it.category, it.subcategory, it.id)
            }
            val section = when {
                path == "Theme & Appearance/graphicsQuality" -> "Performance"
                path.startsWith("Theme & Appearance/") -> "Appearance"
                path == "openConfigKey" -> "Controls"
                else -> "General"
            }
            return MenuSettingLocation(MenuCategory.SETTINGS, section)
        }
    }
}

fun menuSettingLocation(path: String) = MenuCatalog.settingLocation(path)

/** Small metadata aliases supplement real titles/descriptions; all query words must match. */
fun searchMenuEntries(entries: List<MenuSearchEntry>, query: String): List<MenuSearchEntry> {
    val tokens = Regex("[a-z0-9]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
    return entries.filter { entry ->
        val text = (listOf(entry.title, entry.description, entry.category.title, entry.subcategory) + entry.aliases)
            .joinToString(" ").lowercase(Locale.ROOT)
        val words = Regex("[a-z0-9]+").findAll(text).map { it.value }.toList()
        tokens.all { token -> if (token.length <= 2) token in words else words.any { it.contains(token) } }
    }.sortedWith(compareBy<MenuSearchEntry> { it.category.ordinal }.thenBy { it.subcategory }
        .thenBy { it.settingPath != null }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
}
