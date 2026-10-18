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
    private val searchIndex by lazy { MenuSearchIndex(entries) }

    fun findFeature(id: String) = byId[id]
    fun featureForPath(path: String) = byPath[path] ?: extraSettings.firstOrNull { it.settingPath == path }?.let { byId[it.featureId] }
    fun setting(path: String) = byPath[path]?.settings?.firstOrNull { it.path == path }
    fun settingEntry(path: String) = entries.firstOrNull { it.settingPath == path }
    fun search(query: String) = searchIndex.search(query)

    companion object {
        val definitions = listOf(
            MenuFeature("community", "Discord & Community", "Connect Discord, control statistics sharing and choose updates", MenuCategory.SETTINGS, "Community", listOf("discord", "connect", "privacy", "sharing", "updates", "account"), tool = "community"),
            MenuFeature("leaderboards", "Community Leaderboards", "Compare consenting participants with source labels and UTC periods", MenuCategory.UTILS, "Progress", listOf("rank", "ranking", "economy", "dungeons", "rng", "weekly", "monthly"), tool = "leaderboards"),
            MenuFeature("rng-journal", "RNG Journal", "Search preserved personal acquisitions, dates, counts and measured history", MenuCategory.UTILS, "Progress", listOf("rare", "drops", "history", "journal", "rng", "weekly", "monthly"), tool = "rng-journal"),
            MenuFeature("rng-test", "RNG Drop Tester", "Administrator-only parser checks and isolated mock feed delivery", MenuCategory.UTILS, "Administration", listOf("rng", "test", "admin", "mock", "dry-run", "fixtures"), tool = "rng-test"),
            MenuFeature("party-finder", "Party Finder", "Online dungeon friends, LFG availability, invites and party filters", MenuCategory.DUNGEONS, "Parties", listOf("party", "friends", "lfg", "availability", "dungeon-friends", "secondary classes"), tool = "party-finder", enabledPath = "Dungeons/Dungeon Friends/enabled"),
            MenuFeature("starred-routes", "Dungeon Routing", "Follow doors toward Blood, optionally visiting starred mobs", MenuCategory.DUNGEONS, "Routes", listOf("route", "path", "blood", "wither", "healer", "starred", "mobs", "fairy", "straight", "starred clear routing"), enabledPath = "Dungeons/Starred Clear Routing/enabled"),
            MenuFeature("dungeon-tracker", "Dungeon Tracker", "Run history, loot records and estimated dungeon rewards", MenuCategory.DUNGEONS, "Runs", listOf("loot", "profit", "bazaar", "history", "rng"), tool = "dungeon-tracker", enabledPath = "Dungeons/Dungeons Tracker/dungeonTracker"),
            MenuFeature("dungeon-splits", "Dungeon Splits", "Live run splits, personal bests, best possible time and averages per floor", MenuCategory.DUNGEONS, "Runs", listOf("splits", "timer", "pb", "personal best", "theoretical", "average", "300", "terminals", "necron", "relics"), tool = "dungeon-splits", enabledPath = "Dungeons/Dungeon Splits/enabled"),
            MenuFeature("goldor-coordination", "Goldor Coordination", "F7/M7 terminal map, ADG role routes, stacks and timed pre-leaps, shared in party chat", MenuCategory.DUNGEONS, "Boss", listOf("terminals", "terminal map", "adg", "goldor", "p3", "pre-leap", "ee2", "ee3", "core", "mee2", "hee3", "leap", "stack", "levers", "devices"), enabledPath = "Dungeons/Goldor Coordination/enabled"),
            MenuFeature("healer-crush-camera", "Crush Camera", "Class-specific fixed views from 26 seconds into Storm until Storm dies", MenuCategory.DUNGEONS, "Boss", listOf("camera", "healer", "crush", "purple", "yellow", "storm", "f7", "m7", "preview"), tool = "healer-crush-camera", enabledPath = "Dungeons/Healer Crush Camera/enabled"),
            MenuFeature("leap-menu", "Spirit Leap Menu", "Choose a teammate with four local gameplay previews", MenuCategory.DUNGEONS, "Parties", listOf("spirit", "leap", "teleport", "pov", "camera", "teammates"), enabledPath = "Dungeons/Leap Menu/enabled"),
            MenuFeature("dungeon-chests", "Dungeon Chests", "Estimated chest profit, valuable loot and purchase/reroll protection", MenuCategory.DUNGEONS, "Rewards", listOf("profit", "kismet", "reroll", "bazaar", "prices", "chest")),
            MenuFeature("dungeon-chat", "Dungeon Chat Filter", "Choose which dungeon messages remain visible", MenuCategory.DUNGEONS, "Information", listOf("chat", "messages", "filter")),
            MenuFeature("dungeon-summary", "Dungeon Win Screen", "Show a summary overlay after a completed dungeon", MenuCategory.DUNGEONS, "Runs", listOf("overlay", "hud", "win"), enabledPath = "Dungeons/dungeonWinScreen"),
            MenuFeature("status-bars", "Status Bars", "Separate animated health, mana, defense, speed, vitality and respiration bars you can arrange around the hotbar", MenuCategory.COMBAT, "Player", listOf("health", "mana", "defense", "ehp", "hearts", "bar", "skyblocker", "hud", "absorption", "overflow", "speed", "vitality", "respiration", "air", "fancy bars"), tool = "status-bars", enabledPath = "General/Status Bars/enabled"),
            MenuFeature("ability-cooldowns", "Ability Cooldowns", "Held-item ability timers and ready notifications", MenuCategory.COMBAT, "Timers", listOf("cooldown", "timer", "ability", "hud", "notification"), enabledPath = "General/displayAbilityCooldown"),
            MenuFeature("loadouts", "Loadout Keybinds", "Swap equipment loadouts with configured keys and menu safeguards", MenuCategory.COMBAT, "Gear", listOf("equipment", "keybind", "keyboard", "armor"), enabledPath = "General/Loadout Keybinds/enabled"),
            MenuFeature("wardrobe", "Wardrobe Keybinds", "Saved armor-slot keys and safeguards; this module is currently inactive", MenuCategory.COMBAT, "Gear", listOf("armor", "equipment", "wardrobe", "keybind")),
            MenuFeature("instance-helpers", "Instance Helpers", "Missing-player alerts and requeue behavior for dungeon and Kuudra parties", MenuCategory.COMBAT, "Instances", listOf("dungeons", "kuudra", "party", "requeue", "downtime")),
            MenuFeature("bal-notifier", "Bal Notifications", "Share Bal spawn and health milestones with your party", MenuCategory.COMBAT, "Enemies", listOf("bal", "boss", "mob", "mining", "health"), enabledPath = "Mining/balNotify"),
            MenuFeature("auction-browser", "Auction Browser", "Search cached auction listings, filters, bids and item lore", MenuCategory.ECONOMY, "Auctions", listOf("ah", "auction house", "market", "prices", "bin"), tool = "auction-browser"),
            MenuFeature("bazaar-flipping", "Bazaar Trading", "Manual order flips, private realized-profit history, forecasts and session replays", MenuCategory.ECONOMY, "Bazaar", listOf("bazaar", "flip", "trading", "orders", "portfolio", "profit", "replay", "dashboard"), tool = "bazaar-flipping"),
            MenuFeature("loans", "Loans & Lending", "Local trade history and the items lent to friends", MenuCategory.ECONOMY, "Loans", listOf("friends", "gear", "trade", "lending", "borrow"), tool = "loans"),
            MenuFeature("player-economy", "Player Wealth", "Compare cached friend networth, purse, bank and wardrobe estimates", MenuCategory.ECONOMY, "Players", listOf("friends", "player", "wealth", "networth", "compare"), tool = "player-economy"),
            MenuFeature("price-history", "Price History", "Hover an item in a menu and press P for its Bazaar or auction price graph", MenuCategory.ECONOMY, "Item Values", listOf("price", "graph", "chart", "history", "neu", "bazaar", "auction", "bin", "coflnet"), enabledPath = "General/Price History/enabled"),
            MenuFeature("stash-helper", "Stash Value Filter", "Filter low-value stash items and prevent unwanted pickups", MenuCategory.ECONOMY, "Item Values", listOf("stash", "price", "coins", "value", "filter"), enabledPath = "General/Stash Helper/stashHelper"),
            MenuFeature("cosmetics", "Player Cosmetics", "Choose cosmetics and control rendered names and player scaling", MenuCategory.COSMETICS, "Player Appearance", listOf("appearance", "names", "scale", "scaling", "preview"), tool = "cosmetics"),
            MenuFeature("daily-digest", "Daily Digest", "Daily progress, public updates and optional community RNG reports", MenuCategory.UTILS, "Progress", listOf("news", "rng", "daily", "history", "rewards"), tool = "daily-digest"),
            MenuFeature("global-chat", "IRC Chat", "Global Minecraft/Discord chat and Minecraft-only Party IRC. /sm irc or /sm party", MenuCategory.UTILS, "Social", listOf("irc", "discord", "messages", "social", "global", "party", "media", "gif", "replies", "reactions", "ping", "global skymyce chat", "chat sky"), tool = "global-chat", enabledPath = "General/Global SkyMyce Chat/enabled"),
            MenuFeature("party-commands", "Party Commands", "Choose party commands, cooldowns and the optional stats API fallback", MenuCategory.UTILS, "Social", listOf("party", "pb", "downtime", "commands", "api")),
            MenuFeature("player-utilities", "Player Utilities", "Cached friend activity, locations and profile information", MenuCategory.UTILS, "Social", listOf("friends", "player", "profiles", "online", "social"), tool = "player-utilities"),
            MenuFeature("mining", "Crystal Hollows Helpers", "Lobby information and configurable nucleus warp timing", MenuCategory.UTILS, "Mining", listOf("mining", "fortune", "crystal", "nucleus", "powder", "lobby", "hud")),
            MenuFeature("auto-refill", "Auto Refill", "Choose which consumable items are refilled when empty", MenuCategory.UTILS, "Inventory", listOf("refill", "items", "consumables", "inventory")),
            MenuFeature("npc-dialogue", "NPC Dialogue", "Show NPC lines in a dialogue box with the speaker's portrait, and pick options with number keys", MenuCategory.UTILS, "Information", listOf("npc", "dialogue", "chat", "skycubed", "quest", "options", "talk"), enabledPath = "General/NPC Dialogue/enabled"),
            MenuFeature("sign-confirm", "Sign Enter", "Press Enter to finish Auction House, Bazaar and other signs", MenuCategory.UTILS, "Inventory", listOf("sign", "enter", "confirm", "ah", "bazaar", "price", "bid", "amount", "done")),
            MenuFeature("information", "Information Overlays", "Display client details and skill XP progress", MenuCategory.UTILS, "Information", listOf("xp", "skill", "client", "hud", "overlay")),
            MenuFeature("settings-general", "General", "Client data collection and developer preferences", MenuCategory.SETTINGS, "General", listOf("client", "preferences", "developer", "data")),
            MenuFeature("settings-appearance", "Appearance", "Shared theme colors, opacity and animation personality", MenuCategory.SETTINGS, "Appearance", listOf("theme", "colors", "animation", "animations", "motion", "accessibility", "reduced motion"), tool = "theme-editor"),
            MenuFeature("settings-hud", "HUD", "Arrange widgets, visibility, scale and per-widget themes", MenuCategory.SETTINGS, "HUD", listOf("hud", "overlay", "layout", "widget", "scale", "visibility"), tool = "hud-editor"),
            MenuFeature("settings-performance", "Performance", "GUI rendering quality, raster detail, shadows and effects", MenuCategory.SETTINGS, "Performance", listOf("fps", "graphics", "quality", "rendering", "performance")),
            MenuFeature("settings-controls", "Controls", "Choose the key that opens the Sky-Myce menu", MenuCategory.SETTINGS, "Controls", listOf("keybind", "keyboard", "input", "controls")),
        )

        val routingSettingGroups = linkedMapOf(
            "Route" to listOf("enabled", "bloodRushClassesOnly", "routeThroughMobs", "routeThroughStarredMinibosses", "showRoute"),
            "Mob markers" to listOf("showTargets", "showNumbers", "showCleared"),
            "Appearance" to listOf("thickness", "opacity", "lineHeight"),
            "Advanced" to listOf("showHealer", "automaticRecalculation", "debug"),
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
                path.startsWith("Bazaar Flipping/") || path.startsWith("Bazaar Assistant/") -> "bazaar-flipping"
                path.startsWith("Community Sharing/") -> "community"
                path.startsWith("Party Finder/") || path.startsWith("Dungeons/Dungeon Friends/") -> "party-finder"
                path.startsWith("Dungeons/Starred Clear Routing/") -> "starred-routes"
                path.startsWith("Dungeons/Healer Crush Camera/") -> "healer-crush-camera"
                path.startsWith("Dungeons/Leap Menu/") -> "leap-menu"
                path.startsWith("Dungeons/Dungeons Tracker/") -> "dungeon-tracker"
                path.startsWith("Dungeons/Dungeon Splits/") -> "dungeon-splits"
                path.startsWith("Dungeons/Goldor Coordination/") -> "goldor-coordination"
                path.startsWith("Dungeons/") && key.startsWith("dungeonWinScreen") -> "dungeon-summary"
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
                path.startsWith("General/Price History/") -> "price-history"
                path.startsWith("General/Status Bars/") -> "status-bars"
                path.startsWith("General/NPC Dialogue/") -> "npc-dialogue"
                path.startsWith("General/Signs/") -> "sign-confirm"
                path.startsWith("General/Loadout Keybinds/") -> "loadouts"
                path.startsWith("General/Wardrobe Keybinds/") -> "wardrobe"
                path == "General/autoRefill" -> "auto-refill"
                path == "General/displayAbilityCooldown" || path == "General/displayAbilityNotification" -> "ability-cooldowns"
                path == "General/clientInfo" || path == "General/displayXp" || path == "General/displayXpBar" -> "information"
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
fun searchMenuEntries(entries: List<MenuSearchEntry>, query: String) = MenuSearchIndex(entries).search(query)

/** The inventory is immutable; normalize and sort it once instead of rebuilding every setting on each keystroke. */
internal class MenuSearchIndex(entries: List<MenuSearchEntry>) {
    private val ordered = entries.sortedWith(compareBy<MenuSearchEntry> { it.category.ordinal }.thenBy { it.subcategory }
        .thenBy { it.settingPath != null }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    private val words = ordered.map { entry ->
        wordPattern.findAll((listOf(entry.title, entry.description, entry.category.title, entry.subcategory) + entry.aliases)
            .joinToString(" ").lowercase(Locale.ROOT)).map { it.value }.toSet()
    }

    fun search(query: String): List<MenuSearchEntry> {
        val tokens = wordPattern.findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (tokens.isEmpty()) return ordered
        return ordered.filterIndexed { index, _ -> tokens.all { token -> words[index].any { menuSearchWordMatches(it, token) } } }
    }

    companion object { private val wordPattern = Regex("[a-z0-9]+") }
}

/** Prefixes work from the first keystroke; longer words tolerate one insertion, deletion or substitution. */
internal fun menuSearchWordMatches(word: String, token: String): Boolean {
    if (token.length <= 2) return word.startsWith(token)
    if (word.contains(token)) return true
    if (token.length < 4 || kotlin.math.abs(word.length - token.length) > 1) return false
    var left = 0
    var right = 0
    var edits = 0
    while (left < word.length && right < token.length) {
        if (word[left] == token[right]) { left++; right++; continue }
        if (++edits > 1) return false
        when {
            word.length > token.length -> left++
            word.length < token.length -> right++
            else -> { left++; right++ }
        }
    }
    return edits + (word.length - left) + (token.length - right) <= 1
}
