package me.mycellium.skymyce.config.instances.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.AuctionType
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.BazaarType
import me.mycellium.skymyce.features.instances.dungeons.DungeonCleanChat
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonAvailability
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.FRIEND_FLOORS
import me.mycellium.skymyce.features.instances.dungeons.friends.SavedDungeonParty
import me.mycellium.skymyce.features.instances.dungeons.friends.currentRelayUrl
import me.mycellium.skymyce.features.instances.dungeons.friends.parseDungeonClass
import me.mycellium.skymyce.features.instances.dungeons.friends.relayUri
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitComparison
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitWindow
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass

object DungeonsConfig : CategoryKt("Dungeons") {
    init {
        category(DungeonTrackerConfig)
        category(DungeonSplitsConfig)
        category(GoldorCoordinationConfig)
        category(DungeonFriendsConfig)
        category(StarredClearRoutingConfig)
        category(HealerCrushCameraConfig)
        category(LeapMenuConfig)
    }

    val valuableItemThreshold by int(15) {
        name = TranslatableValue.literal("Valuable Item Threshold")
        description = TranslatableValue.literal("Any item with a value above this value in millions will be determined as valuable")

        slider = true
        range = 0..100
    }


    val dungeonWinScreen by boolean(true) {
        name = TranslatableValue.literal("Dungeon Win Screen")
        description = TranslatableValue.literal("Displays an overlay when a dungeon is ended")
    }

    val dungeonWinScreenDetails by boolean(true) {
        name = TranslatableValue.literal("Win Screen Details")
        description = TranslatableValue.literal("Add your splits time against your PB, average or worst (Dungeon Splits settings), secrets, crypts, deaths, damage, kills, healing and experience gained to the win screen")
    }

    val dungeonMessageFilter by select(DungeonCleanChat.DungeonFilter.MISCELLANEOUS) {
        name = TranslatableValue.literal("Dungeon Message Filter")
        description = TranslatableValue.literal("Filters dungeon chat messages by category")
    }

    val safeReroll by boolean(true) {
        name = TranslatableValue.literal("Safe Kismet Reroll")
        description = TranslatableValue.literal("Blocks a chest reroll if it has an item thats worth above the threshold (Hold 'CTRL' to override)")
    }

    val safeRerollThreshold by int(5) {
        name = TranslatableValue.literal("Safe Kismet Reroll Threshold")
        description = TranslatableValue.literal("The kismet threshold for blocking kismets")

        slider = true
        range = 0..100
    }

    val dungeonChestProfit by boolean(true) {
        name = TranslatableValue.literal("Dungeon Chest Profit")
        description = TranslatableValue.literal("Shows the profit and value of all items while in a dungeon chest")
    }

    val dungeonChestProfitProtection by boolean(true) {
        name = TranslatableValue.literal("Dungeon Chest Profit Protection")
        description = TranslatableValue.literal("Blocks opening a dungeon reward chest when its estimated rewards are below the opening cost (hold CTRL to override)")
    }

    val negativeProfitThreshold by double(0.0) {
        name = TranslatableValue.literal("Negative Profit Threshold")
        description = TranslatableValue.literal("Block only when the estimated loss is greater than this many coins; zero blocks every negative-profit chest")

        range = 0.0..1_000_000_000_000.0
    }

    enum class BazaarType {
        INSTANT_BUY,
        INSTANT_SELL
    }

    enum class AuctionType {
        LOWEST,
        HIGHEST,
        MEAN,
        MEDIAN
    }
}

object DungeonFriendsConfig : CategoryKt("Dungeon Friends") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Scan Dungeon Friends")
        description = TranslatableValue.literal("Load a missing friend cache on joining; only Party Finder refreshes online status, at most once per minute on opening")
    }

    init {
        button {
            title = "Dungeon Friend & LFG Assistant"
            text = "Open"
            description = "Online dungeon friends, floor stats and quick invites (/skymyce pf)"
            onClick { MC.instance.execute { navigateMenu(DungeonFriendsScreen()) } }
        }
    }
}

/** User-entered friend preferences live alongside the existing dungeon tracker data. */
object DungeonFriendsSettings {
    private val file = SkyMyce.configPath.resolve("dungeon_friends.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    var messageTemplate = "Hey {name}, want to play {class} on {floor}?"
        private set
    var secondaryClasses: Map<String, Set<DungeonClass>> = emptyMap()
        private set
    var availability = DungeonAvailability()
        private set
    var titleNotifications = false
        private set
    var lastParty: SavedDungeonParty? = null
        private set
    var relayUrl = currentRelayUrl(null)
        private set
    var error: String? = null
        private set
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        if (!Files.exists(file)) return
        try {
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            val template = json.get("messageTemplate")?.asString ?: messageTemplate
            require(template.isNotBlank() && template.none { it < ' ' })
            val secondary = json.getAsJsonObject("secondaryClasses")?.entrySet()?.associate { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
                name.lowercase() to value.asJsonArray.map { parseDungeonClass(it.asString) ?: error("Invalid class") }.toSet()
            }.orEmpty()
            val available = json.get("availability")?.takeUnless { it.isJsonNull }
                ?.let { gson.fromJson(it, DungeonAvailability::class.java) } ?: DungeonAvailability()
            require(available.floor in FRIEND_FLOORS && available.classes.all { it in DungeonClass.entries })
            require(available.maxPbMillis == null || available.maxPbMillis in 1..59999999)
            val relay = currentRelayUrl(json.get("relayUrl")?.asString)
            require(relay.isBlank() || relayUri(relay) != null)
            val savedParty = json.get("lastParty")?.takeUnless { it.isJsonNull }
                ?.let { gson.fromJson(it, SavedDungeonParty::class.java) }
            savedParty?.let { saved ->
                require(saved.leader.matches(Regex("[A-Za-z0-9_]{1,16}")))
                require(saved.members.size in 1..5 && saved.members.all { it.matches(Regex("[a-z0-9_]{1,16}")) })
                require(saved.leader.lowercase() in saved.members)
                require(saved.classes.keys.all { it in saved.members } && saved.classes.values.all { it in DungeonClass.entries })
                require(saved.floor == null || saved.floor in FRIEND_FLOORS)
                require(saved.savedAt >= 0)
            }
            messageTemplate = template
            secondaryClasses = secondary
            availability = available
            titleNotifications = json.get("titleNotifications")?.asBoolean ?: false
            lastParty = savedParty
            relayUrl = relay.trim()
        } catch (failure: Exception) {
            error = "Could not read dungeon_friends.json. Repair or rename it before saving."
            SkyMyce.logger.warn("Could not read dungeon friend preferences; original file preserved", failure)
        }
    }

    fun save(
        template: String = messageTemplate,
        classes: Map<String, Set<DungeonClass>> = secondaryClasses,
        available: DungeonAvailability = availability,
        savedParty: SavedDungeonParty? = lastParty,
        relay: String = relayUrl,
        titles: Boolean = titleNotifications,
    ): Boolean {
        load()
        if (error != null) return false
        if (template.isBlank() || template.any { it < ' ' || it == '§' } || template.length > 220) return false
        if (available.floor !in FRIEND_FLOORS) return false
        if (available.maxPbMillis != null && available.maxPbMillis !in 1..59999999) return false
        if (relay.isNotBlank() && relayUri(relay) == null) return false
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "dungeon_friends", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { writer ->
                    gson.toJson(mapOf("messageTemplate" to template, "secondaryClasses" to classes,
                        "availability" to available, "lastParty" to savedParty, "relayUrl" to relay.trim(), "titleNotifications" to titles), writer)
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            messageTemplate = template
            secondaryClasses = classes
            availability = available
            titleNotifications = titles
            lastParty = savedParty
            relayUrl = relay.trim()
            return true
        } catch (failure: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend preferences", failure)
            return false
        }
    }
}

object DungeonSplitsConfig : CategoryKt("Dungeon Splits") {
    /** Saved by name; SECTION_PB and NONE predate the average and worst comparisons. */
    enum class Comparison(val title: String, val target: SplitComparison?) {
        SECTION_PB("Best", SplitComparison.BEST), AVERAGE("Average", SplitComparison.AVERAGE), WORST("Worst", SplitComparison.WORST),
        NONE("Times only", null);
        override fun toString() = title
    }

    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Record dungeon splits from in-game events and save each run's history locally")
    }

    init {
        button {
            title = "Dungeon Splits Statistics"
            text = "Open"
            description = "Personal bests, theoretical best and averages per floor (/skymyce splits)"
            onClick { MC.instance.execute { navigateMenu(DungeonSplitsScreen()) } }
        }
    }

    val hud by boolean(true) {
        name = TranslatableValue.literal("Splits HUD")
        description = TranslatableValue.literal("Show the current run's splits during a dungeon. Move it in the HUD editor")
    }

    val comparison by enum(Comparison.SECTION_PB) {
        name = TranslatableValue.literal("HUD Comparison")
        description = TranslatableValue.literal("Compare each finished section with your best, average or worst time for that section, or show times only")
    }

    val averageWindow by enum(SplitWindow.ALL) {
        name = TranslatableValue.literal("Average Over")
        description = TranslatableValue.literal("Which recent runs the HUD's Average comparison uses. Best and worst always use every saved run")
    }

    val showUpcoming by boolean(true) {
        name = TranslatableValue.literal("Show Upcoming Splits")
        description = TranslatableValue.literal("List splits that have not happened yet in the HUD")
    }

    val showTotal by boolean(true) {
        name = TranslatableValue.literal("Show Run Total")
        description = TranslatableValue.literal("Show the run's elapsed time and the floor's actual and theoretical best in the HUD")
    }

    val pace by boolean(false) {
        name = TranslatableValue.literal("Pace Timer")
        description = TranslatableValue.literal("Show an estimated finish time in the HUD: your time so far plus each remaining split's average over your last 10 runs. The split you are on counts at least its time so far")
    }

    val chatSummary by boolean(true) {
        name = TranslatableValue.literal("Run Time in Chat")
        description = TranslatableValue.literal("When a run ends, show its real time, server time and the time lost to lag in chat")
    }

    val showServerTime by boolean(true) {
        name = TranslatableValue.literal("Show Both Clocks")
        description = TranslatableValue.literal("Show each split's time on the other clock in brackets, and the time lost to lag. Server time counts server ticks, so lag does not add to it")
    }

    var bestsClock by enum(SplitClock.REAL) {
        name = TranslatableValue.literal("Timer")
        description = TranslatableValue.literal("Which time the HUD shows and compares: real time, which includes lag, or server (tick) time, which leaves it out. The \"Toggle splits timer\" key in Controls switches it during a run")
    }

    val backgroundOpacity by int(100) {
        name = TranslatableValue.literal("Background Opacity")
        description = TranslatableValue.literal("How visible the splits HUD's background is: 100% follows your theme's panel opacity, 0% leaves only the text")
        range = 0..100; slider = true
    }

    val precise by boolean(true) {
        name = TranslatableValue.literal("Hundredths")
        description = TranslatableValue.literal("Show split times to a hundredth of a second")
    }
}

object DungeonTrackerConfig : CategoryKt("Dungeons Tracker") {
    val dungeonTracker by boolean(true) {
        name = TranslatableValue.literal("Enabled")
    }

    init {
        button {
            title = "Dungeon Tracker Screen"
            text = "Open"
            description = "Opens a detailed summary of your dungeon runs (/skymyce dungeon)"

            onClick {
                MC.instance.execute {
                    navigateMenu(DungeonScreen())
                }
            }
        }
    }

    val dungeonTrackerWidget by boolean(true) {
        name = TranslatableValue.literal("Dungeon Tracker Widget")
        description = TranslatableValue.literal("Show a dungeon tracker summary widget in the dungeon hub or after a run is completed")
    }

    val targetLevel by int(50) {
        name = TranslatableValue.literal("Target Level")
        description = TranslatableValue.literal("Catacombs level to work toward. The widget and tracker show the experience left and estimated runs to reach it (levels past 50 take 200M each)")
        range = 1..200
    }

    val showTarget by boolean(true) {
        name = TranslatableValue.literal("Show Target Level")
        description = TranslatableValue.literal("Show progress toward the target level in the Dungeon Tracker widget and screen. Needs your profile's Catacombs experience from Skyblocker, SkyBlockPV or a Hypixel API key")
    }

    var levelsTarget by int(50) {
        name = TranslatableValue.literal("Levels Page Target")
        description = TranslatableValue.literal("The level the tracker's Levels page counts toward for Catacombs and every class. 0 means each row's next level. Also changes from the page itself")
        range = 0..200
    }

    var levelsMetric by enum(me.mycellium.skymyce.features.instances.dungeons.tracker.LevelMetric.RUNS) {
        name = TranslatableValue.literal("Levels Page Shows")
        description = TranslatableValue.literal("What the Levels page shows beside each bar: estimated runs needed, experience needed, or percent of the target")
    }

    val bazaarPriceType by enum(BazaarType.INSTANT_BUY) {
        name = TranslatableValue.literal("Bazaar Price Type")
        description = TranslatableValue.literal("Choose which bazaar price metric should be used when tracking")
    }

    val auctionPriceType by enum(AuctionType.LOWEST) {
        name = TranslatableValue.literal("Auction Price Type")
        description = TranslatableValue.literal("Choose which auction house price metric should be used when tracking")
    }
}

object GoldorCoordinationConfig : CategoryKt("Goldor Coordination") {
    /** Saved by name. Each maps to a profile id in `GoldorProfiles`. */
    enum class Profile(val title: String, val id: String) {
        GOLD_STANDARD("Gold standard (MEE2)", "gold-standard"), GOLD_STANDARD_AEE2("Gold standard (AEE2)", "gold-standard-aee2"),
        SKY_MYCE("Sky-Myce ADG", "skymyce-adg"), ADG_2026("ADG 2026 (DTM preset)", "adg-2026");
        override fun toString() = title
    }

    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Track F7/M7 phase 3 terminals, devices, levers and early-enter routes, and recommend stacks and pre-leaps. Never clicks, moves or leaps for you")
    }

    var profile by enum(Profile.GOLD_STANDARD) {
        name = TranslatableValue.literal("Strategy")
        description = TranslatableValue.literal("Gold standard: the class-by-class baseline with step guides, Mage early-entering S2. Choose AEE2 if your party has Archer early-enter instead. Sky-Myce ADG and ADG 2026 are the older assignment-only profiles")
    }

    /** Set once the old default (Sky-Myce ADG) has been moved to the gold standard. */
    var migratedGoldStandard by boolean(false) { condition = { false } }

    val learnRoles by boolean(true) {
        name = TranslatableValue.literal("Learn Roles")
        description = TranslatableValue.literal("Record which terminals, levers and devices each class actually completes in your runs. Observations only: they never change the strategy unless Apply Learned Roles is on. /sm goldor learned shows them next to the strategy")
    }

    val applyLearned by boolean(false) {
        name = TranslatableValue.literal("Apply Learned Roles")
        description = TranslatableValue.literal("Experimental. Replace the strategy's assignments with what you have learned once a class has done the same objective in most of 3 or more runs. Leaps, early-enters and the step guide stay as the strategy says")
    }

    val map by boolean(true) {
        name = TranslatableValue.literal("Terminal Map")
        description = TranslatableValue.literal("Show the section map with assignments, puzzle types and progress. Move and scale it in the HUD editor")
    }

    val recommendations by boolean(true) {
        name = TranslatableValue.literal("Recommendations")
        description = TranslatableValue.literal("Show your next action, its reason and the pre-leap timing beside the map. Tracking continues when hidden")
    }

    val sendMessages by boolean(true) {
        name = TranslatableValue.literal("Share In Party Chat")
        description = TranslatableValue.literal("Send short state updates (e.g. smt1:4:t24m0) to party chat when your puzzle, terminal, lever or route changes, so other Sky-Myce users can plan. Never uses the Sky-Myce backend")
    }

    val receiveMessages by boolean(true) {
        name = TranslatableValue.literal("Use Party Updates")
        description = TranslatableValue.literal("Read other Sky-Myce users' party chat updates. When off, only what this client sees is used")
    }

    val hideMessages by boolean(true) {
        name = TranslatableValue.literal("Hide Coordination Lines")
        description = TranslatableValue.literal("Hide smt1 lines from your chat. Players without Sky-Myce still see them")
    }

    val leapAlert by boolean(true) {
        name = TranslatableValue.literal("Leap Alert")
        description = TranslatableValue.literal("Show a title when your recommended leap window opens")
    }

    val reactionTime by int(1000) {
        name = TranslatableValue.literal("Reaction Time (ms)")
        description = TranslatableValue.literal("How much earlier leap calls show, so you have time to see them and leap. 0 calls the exact moment")
        range = 0..2000; slider = true
    }

    val stackAlert by boolean(true) {
        name = TranslatableValue.literal("Stack Alert")
        description = TranslatableValue.literal("Show a title when your next job is stacking with a teammate")
    }

    /**
     * Moves players on the previous default strategy to the gold standard, once. A fresh config already defaults to it.
     * Sky-Myce ADG stays available in the Strategy setting.
     */
    fun migrateStrategy() {
        if (migratedGoldStandard) return
        if (profile == Profile.SKY_MYCE) profile = Profile.GOLD_STANDARD
        migratedGoldStandard = true
    }

    val backgroundOpacity by int(60) {
        name = TranslatableValue.literal("Background Opacity")
        description = TranslatableValue.literal("How visible the map's background is")
        range = 0..100; slider = true
    }
}

/** F7 and M7 share one presentation; captured viewpoints are stored separately. */
object HealerCrushCameraConfig : CategoryKt("Healer Crush Camera") {
    // Keep the saved category ID so existing presentation settings survive the rename.
    override val name = TranslatableValue.literal("Crush Camera")
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show the saved crush viewpoints from 26 seconds into Storm's F7/M7 phase until Storm dies. Switch to yellow one second after the first crush, or at 38 seconds if its notice is missed. Choose the classes below.")
    }
    var healer by boolean(true) {
        name = TranslatableValue.literal("Healer")
        description = TranslatableValue.literal("Automatically show the Crush Camera when playing Healer.")
    }
    var mage by boolean(false) {
        name = TranslatableValue.literal("Mage")
        description = TranslatableValue.literal("Automatically show the Crush Camera when playing Mage.")
    }
    var berserk by boolean(false) {
        name = TranslatableValue.literal("Berserk")
        description = TranslatableValue.literal("Automatically show the Crush Camera when playing Berserk.")
    }
    var archer by boolean(false) {
        name = TranslatableValue.literal("Archer")
        description = TranslatableValue.literal("Automatically show the Crush Camera when playing Archer.")
    }
    var tank by boolean(false) {
        name = TranslatableValue.literal("Tank")
        description = TranslatableValue.literal("Automatically show the Crush Camera when playing Tank.")
    }

    fun classEnabled(clazz: DungeonClass?) = when (clazz) {
        DungeonClass.HEALER -> healer
        DungeonClass.MAGE -> mage
        DungeonClass.BERSERKER -> berserk
        DungeonClass.ARCHER -> archer
        DungeonClass.TANK -> tank
        null -> false
    }

    var width by int(160) {
        name = TranslatableValue.literal("Window width")
        description = TranslatableValue.literal("HUD width in Minecraft GUI pixels. Drag the lower-right corner in the HUD editor to resize.")
        range = 96..400; slider = true
    }
    var height by int(90) {
        name = TranslatableValue.literal("Window height")
        description = TranslatableValue.literal("HUD height in Minecraft GUI pixels when the aspect ratio is Free.")
        range = 64..300; slider = true
    }
    var aspect by enum(CameraAspect.WIDE) {
        name = TranslatableValue.literal("Aspect ratio")
        description = TranslatableValue.literal("16:9, 4:3, or an independently resizable window.")
    }
    var fieldOfView by double(65.0) {
        name = TranslatableValue.literal("Field of view")
        description = TranslatableValue.literal("Lower values zoom in. This does not change the player's main camera.")
        range = 20.0..110.0; slider = true
    }
    var yawOffset by double(0.0) {
        name = TranslatableValue.literal("Yaw adjustment")
        description = TranslatableValue.literal("Horizontal angle relative to the saved viewpoint.")
        range = -180.0..180.0; slider = true
    }
    var pitchOffset by double(0.0) {
        name = TranslatableValue.literal("Pitch adjustment")
        description = TranslatableValue.literal("Vertical angle relative to the saved viewpoint.")
        range = -60.0..60.0; slider = true
    }
    var offsetX by double(0.0) {
        name = TranslatableValue.literal("World X offset")
        range = -32.0..32.0; slider = true
    }
    var offsetY by double(0.0) {
        name = TranslatableValue.literal("Height offset")
        range = -32.0..32.0; slider = true
    }
    var offsetZ by double(0.0) {
        name = TranslatableValue.literal("World Z offset")
        range = -32.0..32.0; slider = true
    }
    var opacity by int(100) {
        name = TranslatableValue.literal("View opacity")
        range = 20..100; slider = true
    }
    var backgroundOpacity by int(75) {
        name = TranslatableValue.literal("Background opacity")
        range = 0..100; slider = true
    }
    var border by boolean(true) {
        name = TranslatableValue.literal("Show border")
    }
    var stageLabel by boolean(true) {
        name = TranslatableValue.literal("Show stage label")
    }
    var framesPerSecond by int(15) {
        name = TranslatableValue.literal("Camera frame limit")
        description = TranslatableValue.literal("Limit secondary rendering work independently from the main view.")
        range = 5..30; slider = true
    }
    var renderDistance by int(3) {
        name = TranslatableValue.literal("Camera render distance")
        description = TranslatableValue.literal("Nearby loaded chunks only; the camera never requests server chunks.")
        range = 2..4; slider = true
    }
}

enum class CameraAspect(val label: String, val ratio: Double?) {
    WIDE("16:9", 16.0 / 9.0), CLASSIC("4:3", 4.0 / 3.0), FREE("Free", null)
}

object LeapMenuConfig : CategoryKt("Leap Menu") {
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show four teammate cards in the Spirit Leap menu. Click a view or press 1–4 to leap.")
    }
    var povEnabled by boolean(true) {
        name = TranslatableValue.literal("Teammate POVs")
        description = TranslatableValue.literal("Approximate eye-level views of the locally loaded world; unavailable views stay blank.")
    }
    var partyPovSharing by boolean(false) {
        name = TranslatableValue.literal("Share party viewpoints")
        description = TranslatableValue.literal("Share your position and viewing direction with current teammates through Community. Both players must enable this.")
    }
    // Replaces the old 6 fps "framesPerSecond" limit, so existing configs pick up the smoother default.
    var previewFramesPerSecond by int(30) {
        name = TranslatableValue.literal("POV frame rate")
        description = TranslatableValue.literal("Target updates per teammate each second. Previews slow down automatically if together they would take more than half the frame time.")
        range = 2..60; slider = true
    }
}

object PartyFinderConfig : CategoryKt("Party Finder") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Dim incompatible parties")
        description = TranslatableValue.literal("Shade Party Finder slots that fail your class or PB criteria. Items stay visible and clickable; nothing joins automatically.")
    }
    val matchClass by boolean(true) {
        name = TranslatableValue.literal("Match open classes")
        description = TranslatableValue.literal("Use explicit open-class lore when present, otherwise classes not occupied by listed members. Unknown class information stays visible.")
    }
    val classes by select<DungeonClass> {
        name = TranslatableValue.literal("Classes I play")
        description = TranslatableValue.literal("Any selected class may match. Leave empty to use your current detected dungeon class.")
    }
    val matchPb by boolean(true) {
        name = TranslatableValue.literal("Match note PB requirements")
        description = TranslatableValue.literal("Compare note times with your cached API PB for that floor. Missing or loading PBs do not satisfy a requirement. Notes without a valid time are unrestricted.")
    }
}

object StarredClearRoutingConfig : CategoryKt("Starred Clear Routing") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show dungeon navigation until Blood opens. Mages keep the Blood doorway highlighted throughout the run, except while inside Blood.")
    }
    val bloodRushClassesOnly by boolean(true) {
        name = TranslatableValue.literal("Only for Blood-rush classes")
        description = TranslatableValue.literal("Route only when you play Mage, Archer or Healer, the classes that rush Blood. Berserker and Tank see no route. Turn off to route on every class.")
    }
    val routeThroughMobs by boolean(true) {
        name = TranslatableValue.literal("Route through starred mobs")
        description = TranslatableValue.literal("Visit regular starred mobs on the way to Blood. Starred minibosses have their own toggle. Turn both off to go straight toward Blood; Show Blood guidance under Advanced must be on. Mob markers are separate.")
    }
    val routeThroughStarredMinibosses by boolean(true) {
        name = TranslatableValue.literal("Route through starred minibosses")
        description = TranslatableValue.literal("Visit minibosses with an actual star in their label, such as Shadow Assassins or Lost Adventurers. Works independently of Route through starred mobs. Minibosses without a star are skipped.")
    }
    val showTargets by boolean(true) {
        name = TranslatableValue.literal("Show starred mobs")
        description = TranslatableValue.literal("Highlight starred mobs independently of whether the route visits them.")
    }
    val showRoute by boolean(true) {
        name = TranslatableValue.literal("Show route line")
        description = TranslatableValue.literal("Draw the path, including clear shortcuts across gaps for teleports. Blood sections also need Show Blood guidance under Advanced. Solid lines are checked guidance; dashed amber lines are planned crossings or unchecked guidance.")
    }
    val showNumbers by boolean(true) {
        name = TranslatableValue.literal("Show mob labels")
        description = TranslatableValue.literal("Show mob status and distance, plus visit numbers when routing through mobs.")
    }
    val showCleared by boolean(false) {
        name = TranslatableValue.literal("Show cleared mobs")
        description = TranslatableValue.literal("Keep markers for starred mobs that have been cleared.")
    }
    val showHealer by boolean(true) {
        name = TranslatableValue.literal("Show Blood guidance")
        description = TranslatableValue.literal("Include the route toward Blood and the Mage's Blood-door highlight before and after opening, except while inside Blood. Turn off to show only the route between selected starred mobs and minibosses.")
    }
    val automaticRecalculation by boolean(true) {
        name = TranslatableValue.literal("Recalculate off route")
        description = TranslatableValue.literal("Find a new path when you leave the current one. Rooms, mobs, obstacles and teleports always update the route.")
    }
    val thickness by int(2) {
        name = TranslatableValue.literal("Line width")
        description = TranslatableValue.literal("Thickness of the route line.")
        range = 1..6; slider = true
    }
    val opacity by int(85) {
        name = TranslatableValue.literal("Line opacity")
        description = TranslatableValue.literal("Visibility of route lines and markers.")
        range = 10..100; slider = true
    }
    val lineHeight by double(1.2) {
        name = TranslatableValue.literal("Line height")
        description = TranslatableValue.literal("Height of the route line above the floor, from 0 to 4 blocks. Changes its appearance without changing the path.")
        range = 0.0..4.0; slider = true
    }
    val debug by boolean(false) {
        name = TranslatableValue.literal("Developer diagnostics")
        description = TranslatableValue.literal("Record route planning timing and include diagnostics in /sm route status. Requires Developer Mode.")
    }
}
