package me.mycellium.skymyce.config.misc

import com.google.gson.JsonObject
import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import com.teamresourceful.resourcefulconfigkt.api.ObjectKt
import me.mycellium.skymyce.community.SharingMode
import me.mycellium.skymyce.features.garden.greenhouse.PriceMode
import me.mycellium.skymyce.features.garden.greenhouse.ProfitMetric
import me.mycellium.skymyce.features.general.AutoRefill
import me.mycellium.skymyce.features.general.PartyCommandTypes
import me.mycellium.skymyce.features.general.pricehistory.PriceRange
import me.mycellium.skymyce.hud.ThemePalette
import me.mycellium.skymyce.hud.ThemePreset
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.GuiQuality
import org.lwjgl.glfw.GLFW

object GeneralConfig : CategoryKt("General") {
    init {
        category(DailyDigestConfig)
        category(GlobalChatConfig)
        category(PartyCommandsConfig)
        category(StashHelperConfig)
        category(LoadoutConfig)
        category(PriceHistoryConfig)
        category(GreenhouseConfig)
        category(StatusBarsConfig)
        category(NpcDialogueConfig)
        category(SignsConfig)
    }

    val clientInfo by boolean(false) {
        name = TranslatableValue.literal("Display Client Info")
        description = TranslatableValue.literal("Shows client info on screen")
    }

    val autoRefill by select<AutoRefill.Types> {
        name = TranslatableValue.literal("Auto Refill")
        description = TranslatableValue.literal("Automatically refill these items when it runs out")
    }

    val displayXp by boolean(true) {
        name = TranslatableValue.literal("Display Skill XP")
        description = TranslatableValue.literal("Display Skill XP on your screen")
    }

    val displayXpBar by boolean(true) {
        name = TranslatableValue.literal("Skill XP Progress Bar")
        description = TranslatableValue.literal("Show a bar under the skill XP display with your progress to the next level. Hypixel sends a percentage or current/needed XP; skills at max level show no bar")
    }

    val displayAbilityCooldown by boolean(true) {
        name = TranslatableValue.literal("Display Ability Cooldown")
        description = TranslatableValue.literal("Displays ability cooldown timers when you hold an item")
    }

    val displayAbilityNotification by boolean(false) {
        name = TranslatableValue.literal("Display Ability Notification")
        description = TranslatableValue.literal("Displays a notification when an ability's cooldown resets")
    }
}

object ThemeConfig : CategoryKt("Theme & Appearance") {
    var preset by enum(ThemePreset.SIGNATURE) {
        name = TranslatableValue.literal("Theme")
        description = TranslatableValue.literal("The palette and component style for every Sky-Myce menu and HUD widget.")
    }
    var graphicsQuality by enum(GuiQuality.AUTO) {
        name = TranslatableValue.literal("Graphics mode")
        description = TranslatableValue.literal("Extremely Light and Light keep Sky-Myce's menus plain and turn GUI animations off. Medium adds sharper menus, shadows, glow and animations; High is the fullest. Auto keeps Medium animations. " +
            "Auto (recommended) moves between these to hold 60 FPS, never above your frame limit; /sm graphics shows what it chose. HUD information is never reduced, and Minecraft's own settings are untouched.")
    }
    var menuIntroPlayed by boolean(false) { condition = { false } }

    private var pinnedMotion: GuiMotion? = null

    /** Not a setting: GUI animations follow the graphics mode; Auto keeps Medium so motion never switches mid-session. Checks may pin a mode. */
    var animationMode: GuiMotion
        get() = pinnedMotion ?: when (graphicsQuality) {
            GuiQuality.EXTREMELY_LIGHT, GuiQuality.LIGHT -> GuiMotion.OFF
            GuiQuality.HIGH -> GuiMotion.HEAVY
            else -> GuiMotion.MEDIUM
        }
        internal set(value) { pinnedMotion = value }

    /** An old file without a preset still used Dark glass; old custom colors move to the nearest preset. */
    fun restoreLegacyTheme(original: JsonObject) {
        preset = restoredThemePreset(original, preset)
    }
}

private val legacyThemeKeys = listOf("primary", "secondary", "panel", "card", "text", "muted", "border", "opacity", "padding", "rounded",
    "shadow", "borders", "animationMode", "autoTargetFps", "autoResponse", "autoLowest", "migratedDigestTheme", "themeVersion")

internal fun restoredThemePreset(original: JsonObject, loaded: ThemePreset): ThemePreset {
    val theme = original.get("Theme & Appearance")?.takeIf { it.isJsonObject }?.asJsonObject
    if (original.size() > 0 && theme?.has("preset") != true) return ThemePreset.DARK_GLASS
    fun color(key: String) = theme?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 0..0xFFFFFF }
    val base = loaded.palette
    val custom = base.copy(primary = color("primary") ?: base.primary, secondary = color("secondary") ?: base.secondary,
        panel = color("panel") ?: base.panel, card = color("card") ?: base.card, text = color("text") ?: base.text,
        muted = color("muted") ?: base.muted, border = color("border") ?: base.border)
    legacyThemeKeys.forEach { theme?.remove(it) }
    return if (custom == base) loaded else ThemePreset.entries.minBy { paletteDistance(it.palette, custom) }
}

/** Squared RGB distance over the colors a custom theme could change. */
internal fun paletteDistance(a: ThemePalette, b: ThemePalette): Long =
    listOf(a.primary to b.primary, a.secondary to b.secondary, a.panel to b.panel, a.card to b.card, a.text to b.text,
        a.muted to b.muted, a.border to b.border).sumOf { (x, y) ->
        (0..16 step 8).sumOf { shift -> ((x shr shift and 255) - (y shr shift and 255)).let { (it * it).toLong() } }
    }

object CommunitySharingConfig : CategoryKt("Community Sharing") {
    var mode by enum(SharingMode.ANONYMOUS) {
        name = TranslatableValue.literal("Community data sharing")
        description = TranslatableValue.literal("On: shares your dungeon stats, RNG drops, activity and leaderboard statistics (such as dungeon chest profit), with your Minecraft name on public reports. " +
            "On (anon): shares the same categories, but public reports are anonymous; switching from On removes or hides earlier named reports. " +
            "Off: shares nothing and removes or suppresses your earlier contributions. Local features, Discord chat and cosmetics work in every state.")
    }
    var syncedAccount by string("") { condition = { false } }
    var syncedPolicy by string("") { condition = { false } }
}

object CosmeticsConfig : CategoryKt("Cosmetics") {
    var names by boolean(true) {
        name = TranslatableValue.literal("Show Cosmetic Names")
        description = TranslatableValue.literal("Replace complete known usernames in rendered text on Hypixel. UUIDs, commands, messages and game data keep real identities.")
    }
    var originalTooltips by boolean(true) {
        name = TranslatableValue.literal("Keep original names in item tooltips")
        description = TranslatableValue.literal("Keep real usernames in tooltip text. Also preserves other hover tooltips for consistent identity inspection.")
    }
    var scaling by boolean(true) {
        name = TranslatableValue.literal("Show Sky-Hawk player scaling")
        description = TranslatableValue.literal("Render supported players at their server-stored visual scale (0.5–2.0). Collision, reach, movement and camera remain unchanged.")
    }
}

object DailyDigestConfig : CategoryKt("Daily Digest") {
    val automatic by boolean(true) {
        name = TranslatableValue.literal("Open daily digest automatically")
        description = TranslatableValue.literal("Once per local computer day, after SkyBlock and your profile have loaded")
    }
    val openingDelay by int(8) {
        name = TranslatableValue.literal("Opening delay")
        description = TranslatableValue.literal("Seconds to settle after entering SkyBlock; opening a menu postpones the digest")
        range = 3..30
        slider = true
    }
    var news by boolean(true) {
        name = TranslatableValue.literal("Daily news")
        description = TranslatableValue.literal("Fetch public Cowshed updates through the relay; cached updates remain available offline")
    }
    val receiveRng by boolean(true) {
        name = TranslatableValue.literal("Receive community RNG feed")
        description = TranslatableValue.literal("Show optional, unverified drop reports from other Sky-Hawk users")
    }
    val retainedRngEvents by int(100) {
        name = TranslatableValue.literal("Local RNG history size")
        description = TranslatableValue.literal("Maximum retained recent personal events; saved acquisition history remains available. Community reports are capped separately at 30.")
        range = 20..500
        slider = true
    }
    val compact by boolean(false) {
        name = TranslatableValue.literal("Compact spacing")
        description = TranslatableValue.literal("Reduce card padding while retaining readable, scrollable layouts")
    }

}

/** Fold the former normal-news switch into News once; stale fields cannot override later user choices. */
internal fun migrateDailyNewsConfig(original: JsonObject, enabled: Boolean): Boolean {
    val digest = original.get("General")?.takeIf { it.isJsonObject }?.asJsonObject
        ?.get("Daily Digest")?.takeIf { it.isJsonObject }?.asJsonObject ?: return enabled
    val game = digest.get("gameUpdates")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
    val migrated = enabled && game?.asBoolean != false
    digest.remove("gameUpdates")
    digest.remove("alphaUpdates")
    return migrated
}

object GlobalChatConfig : CategoryKt("Global SkyMyce Chat") {
    var enabled by boolean(false) {
        name = TranslatableValue.literal("Global chat")
        description = TranslatableValue.literal("Receive the shared Minecraft and Discord global channel. /sm irc selects it; /sm chat returns to normal chat. Separate from in-game Party IRC.")
    }
    var partyEnabled by boolean(false) {
        name = TranslatableValue.literal("Party IRC")
        description = TranslatableValue.literal("Minecraft-only chat with opted-in Sky-Myce users reporting the same complete current Hypixel party roster. Never forwarded to Discord. /sm party selects it; an incomplete roster stops routing.")
    }
    val mediaPreviews by boolean(true) {
        name = TranslatableValue.literal("Chat thumbnails")
        description = TranslatableValue.literal("Show image and GIF previews inside chat. Click a preview for the media viewer. Downloads use a bounded memory cache; nothing is saved to disk.")
    }
    val previewSize by int(100) {
        name = TranslatableValue.literal("Chat preview size (%)")
        description = TranslatableValue.literal("Resize image and GIF previews in chat. 100% is the normal size; previews always fit the chat width and show the whole image.")
        range = 50..200
        slider = true
    }
    val animateGifs by boolean(true) {
        name = TranslatableValue.literal("Animate GIFs")
        description = TranslatableValue.literal("Play GIFs in chat previews and the media viewer. The viewer also has a pause button.")
    }
    val mentionHighlight by boolean(true) {
        name = TranslatableValue.literal("Highlight mentions")
        description = TranslatableValue.literal("Highlight new messages that mention your linked account. Editing or reacting to a message does not repeat its notification.")
    }
    val mentionSounds by boolean(true) {
        name = TranslatableValue.literal("Mention sound")
        description = TranslatableValue.literal("Play one subtle ping for a new party or global message that mentions your account.")
    }
    val emojiInNormalChat by boolean(true) {
        name = TranslatableValue.literal("Emoji in Normal Chat")
        description = TranslatableValue.literal("Use the emoji picker and :shortcode: suggestions in normal chat too. Emoji sent to Hypixel become readable :names: (as many as fit in 256 characters), and :names: in received chat show as emoji for you. Sky-Myce IRC always sends real emoji.")
    }
}

object GreenhouseConfig : CategoryKt("Greenhouse Planner") {
    enum class Tier { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Ranks mutations by estimated profit, solves a full Greenhouse layout and shows it as ghost blocks for wrong soil and for the crop you hold. Visual only; use /sm greenhouse. Profit formula and planner idea credit: skymutations.eu by palmaner")
    }

    val priceMode by enum(PriceMode.SELL_OFFER) {
        name = TranslatableValue.literal("Mutation Price")
        description = TranslatableValue.literal("Sell Offer matches skymutations.eu; Instant Sell is the lower, immediate price. Ingredients are always priced at instant buy")
    }

    val profitMetric by enum(ProfitMetric.PER_HOUR) {
        name = TranslatableValue.literal("Profit Metric")
        description = TranslatableValue.literal("Sort the ranking by profit per hour or per growth cycle, or show it per day (per hour × 24)")
    }

    val maxTier by enum(Tier.LEGENDARY) {
        name = TranslatableValue.literal("Highest Unlocked Tier")
        description = TranslatableValue.literal("Only rank and plan mutations up to this rarity")
    }

    var upgradeLevel by int(0) {
        name = TranslatableValue.literal("Growth Speed Upgrade")
        description = TranslatableValue.literal("Your Desk's Greenhouse Growth Speed tier (0-9); shortens every growth stage. Read automatically when you open /desk and Greenhouse Upgrades")
        range = 0..9; slider = true
    }

    val uniqueCrops by int(0) {
        name = TranslatableValue.literal("Unique Crops Elsewhere")
        description = TranslatableValue.literal("Unique crop types count across all your Greenhouse plots (up to 10), so crops on your other saved plots are counted automatically and not planted twice. Raise it only for unique crops grown in plots the planner does not know")
        range = 0..10; slider = true
    }

    val gardenLevel by int(0) {
        name = TranslatableValue.literal("Garden Level")
        description = TranslatableValue.literal("0 reads it from the tab list in your Garden. Mutations needing a base crop your Garden level has not unlocked (Sunflower and Moonflower at 11, Wild Rose at 12) are left out")
        range = 0..15; slider = true
    }

    val blockWrongClicks by boolean(true) {
        name = TranslatableValue.literal("Block Wrong Clicks")
        description = TranslatableValue.literal("Do not plant a crop or place soil in a slot the plot's layout plans for something else; the click is not sent")
    }

    val alwaysShowSoilGhosts by boolean(true) {
        name = TranslatableValue.literal("Always Show Soil Ghosts")
        description = TranslatableValue.literal("Mark every slot with the wrong soil without holding that soil. Crop ghosts still appear only while you hold the matching crop")
    }

    val maxDisplayedRequirements by int(8) {
        name = TranslatableValue.literal("Missing Items Shown")
        description = TranslatableValue.literal("How many missing crops and soils the HUD lists; /sm greenhouse missing prints the full list")
        range = 0..30; slider = true
    }

    val discordReminders by boolean(false) {
        name = TranslatableValue.literal("Discord Reminders")
        description = TranslatableValue.literal("Sky-Myce's Discord bot DMs you when enough of a plot's mutations are ready, or when a growing one runs out of water. At most 4 a day, 2 hours apart. Shares your plots' mutation stages, water and timings with the Sky-Myce relay; needs Discord connected. Timings come from your Plant Diagnostics Tool readings")
    }

    val reminderReadyPercent by int(50) {
        name = TranslatableValue.literal("Ready Reminder At (%)")
        description = TranslatableValue.literal("Discord Reminders ping when this share of a plot's mutations is ready")
        range = 10..100; slider = true
    }

    val opacity by int(40) {
        name = TranslatableValue.literal("Ghost Block Opacity")
        range = 15..80; slider = true
    }

    val renderDistance by int(48) {
        name = TranslatableValue.literal("Max Render Distance")
        range = 8..128; slider = true
    }
}

object LoadoutConfig : CategoryKt("Loadout Keybinds") {
    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
    }

    val autoClose by boolean(true) {
        name = TranslatableValue.literal("Auto Close")
        description = TranslatableValue.literal("Automatically close the loadout menu after swapping")
    }

    val slot1 by key(GLFW.GLFW_KEY_4) {
        name = TranslatableValue("Slot 1")
    }
    val slot2 by key(GLFW.GLFW_KEY_5) {
        name = TranslatableValue("Slot 2")
    }
    val slot3 by key(GLFW.GLFW_KEY_6) {
        name = TranslatableValue("Slot 3")
    }
    val slot4 by key(GLFW.GLFW_KEY_R) {
        name = TranslatableValue("Slot 4")
    }
    val slot5 by key(GLFW.GLFW_KEY_T) {
        name = TranslatableValue("Slot 5")
    }
    val slot6 by key(GLFW.GLFW_KEY_Y) {
        name = TranslatableValue("Slot 6")
    }
    val slot7 by key(GLFW.GLFW_KEY_F) {
        name = TranslatableValue("Slot 7")
    }
    val slot8 by key(GLFW.GLFW_KEY_G) {
        name = TranslatableValue("Slot 8")
    }
    val slot9 by key(GLFW.GLFW_KEY_H) {
        name = TranslatableValue("Slot 9")
    }
    val slot10 by key(GLFW.GLFW_KEY_V) {
        name = TranslatableValue("Slot 10")
    }
    val slot11 by key(GLFW.GLFW_KEY_B) {
        name = TranslatableValue("Slot 11")
    }
    val slot12 by key(GLFW.GLFW_KEY_N) {
        name = TranslatableValue("Slot 12")
    }
}

object NpcDialogueConfig : CategoryKt("NPC Dialogue") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show [NPC] lines in a dialogue box above the hotbar, one at a time, with the speaker's name and portrait")
    }
    val hideChat by boolean(false) {
        name = TranslatableValue.literal("Hide From Chat")
        description = TranslatableValue.literal("Remove [NPC] lines from chat while the dialogue box shows them")
    }
    val optionKeys by boolean(true) {
        name = TranslatableValue.literal("Number Keys Choose Options")
        description = TranslatableValue.literal("When an NPC offers options, press 1–9 to choose one, the same as clicking it in chat. The keys still change your hotbar slot")
    }
    val hideOptions by boolean(false) {
        name = TranslatableValue.literal("Hide Option Line From Chat")
        description = TranslatableValue.literal("Remove the \"Select an option\" line from chat while the box shows the options. With this on, number keys are the way to choose")
    }
    val portrait by boolean(true) {
        name = TranslatableValue.literal("Show Portrait")
        description = TranslatableValue.literal("Draw the NPC beside its name when it is standing near you")
    }
    val inInventories by boolean(true) {
        name = TranslatableValue.literal("Show Over Menus")
        description = TranslatableValue.literal("Keep the box on screen, at the left, while a chest menu is open")
    }
    val wordsPerMinute by int(220) {
        name = TranslatableValue.literal("Reading Speed")
        description = TranslatableValue.literal("Words per minute used to decide how long each line stays")
        range = 80..600; slider = true
    }
    val minimumSeconds by int(2) {
        name = TranslatableValue.literal("Minimum Seconds Per Line")
        description = TranslatableValue.literal("No line disappears sooner than this")
        range = 1..10; slider = true
    }
}

object PartyCommandsConfig : CategoryKt("Party Commands") {
    init {
        separator {
            title = "Party Commands"
            description = "Adds useful party commands using the '!' prefix (e.g. !warp, !kick, !promote)"
        }
    }

    val enabledPartyCommands by select<PartyCommandTypes> {
        name = TranslatableValue.literal("Enabled Commands")
        description = TranslatableValue.literal("Choose which ! commands party members may use with your mod. Unselected commands are ignored.")
    }

    val partyCommandCooldown by double(0.5) {
        name = TranslatableValue.literal("Cooldown")
        description = TranslatableValue.literal("The cooldown (in seconds) between handling party commands")

        slider = true
        range = 0.0..10.0
    }

    var hypixelApiKey by string("") {
        name = TranslatableValue.literal("Hypixel API Key")
        description = TranslatableValue.literal("Optional fallback for dungeon friends and !pb when SkyBlocker / SkyBlockPv cannot provide stats")
    }
}

object PriceHistoryConfig : CategoryKt("Price History") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Press the key below while hovering an item in any menu to open its price history graph (Bazaar and auction items, data from Coflnet)")
    }

    val key by key(GLFW.GLFW_KEY_P) {
        name = TranslatableValue.literal("Price History Key")
        description = TranslatableValue.literal("Hover an item in a menu and press this key")
    }

    val defaultRange by enum(PriceRange.WEEK) {
        name = TranslatableValue.literal("Default Range")
        description = TranslatableValue.literal("The time range the graph opens with")
    }
}

object SignsConfig : CategoryKt("Signs") {
    /** Which signs Enter confirms. */
    enum class EnterConfirms(private val title: String) {
        OFF("Off"), MARKET("Auction House, Bazaar and bank"), PROMPTS("Every Hypixel input sign"), ALL("Every sign");
        override fun toString() = title
    }

    val enterConfirms by enum(EnterConfirms.MARKET) {
        name = TranslatableValue.literal("Enter Confirms Signs")
        description = TranslatableValue.literal("Press Enter to finish a sign, the same as clicking Done. Auction House, Bazaar and bank covers prices, bids, amounts and searches; every Hypixel input sign adds the rest of Hypixel's prompts; every sign includes signs you place. Elsewhere Enter moves to the next line as usual")
    }
}

object StashHelperConfig : CategoryKt("Stash Helper") {
    init {
        separator {
            title = "Stash Helper"
            description = "Hides useless items when in your stash"
        }
    }

    val stashHelper by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Use the configured value threshold to filter low-value stash items.")
    }

    val stashValueThreshold by double(0.1) {
        name = TranslatableValue.literal("Threshold")
        description = TranslatableValue.literal("Any item worth less than this value in millions will be hidden")

        slider = true
        range = 0.0..10.0
    }

    val blockUseless by boolean(true) {
        name = TranslatableValue.literal("Block Useless Clicks")
        description = TranslatableValue.literal("Blocks picking up useless items from your stash (Hold 'CTRL' to override)")
    }
}

object StatusBarsConfig : CategoryKt("Status Bars") {
    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show health, mana, defense, speed, vitality, respiration and experience as separate animated bars. Arrange, resize, recolour or hide each one with /sm bars or Open on this feature")
    }
    val useHearts by boolean(true) {
        name = TranslatableValue.literal("Read Vanilla Hearts")
        description = TranslatableValue.literal("Update health from your vanilla hearts every tick, using the action bar only for the maximum and absorption. Faster than the action bar, which refreshes about once a second")
    }
    val hideVanilla by boolean(true) {
        name = TranslatableValue.literal("Hide Vanilla Hearts")
        description = TranslatableValue.literal("Hide the vanilla hearts, armor and hunger rows on SkyBlock while the bars show, the air bubbles while the respiration bar is on and the experience bar and level while the experience bar is on. They return everywhere else")
    }
    val hideActionBar by boolean(true) {
        name = TranslatableValue.literal("Hide Action Bar Stats")
        description = TranslatableValue.literal("Remove health, defense, mana and vitality from the action bar while their bars show them. Other action bar text stays")
    }
    val animate by boolean(true) {
        name = TranslatableValue.literal("Animate")
        description = TranslatableValue.literal("Spring the bars to new values, trail and flash damage, sweep heals, roll numbers and beat at low health. Off draws exact values instantly")
    }
    val showMaximum by boolean(false) {
        name = TranslatableValue.literal("Show Maximum")
        description = TranslatableValue.literal("Write the maximum after the value, as 4,800/4,800. Off shows the value alone")
    }
    val splitExtra by boolean(false) {
        name = TranslatableValue.literal("Split Extra")
        description = TranslatableValue.literal("Show absorption and overflow mana after the value, as 4,425 +375. Off adds them into the value, as 4,800")
    }
    val thousandsSeparators by boolean(true) {
        name = TranslatableValue.literal("Thousands Separators")
        description = TranslatableValue.literal("Write numbers as 4,800. Off writes 4800")
    }

    /** Before 2.7.27 the bars were one panel; these seed the per-bar layout once. */
    val showMana by boolean(true) { condition = { false } }
    val showDefense by boolean(true) { condition = { false } }
}
