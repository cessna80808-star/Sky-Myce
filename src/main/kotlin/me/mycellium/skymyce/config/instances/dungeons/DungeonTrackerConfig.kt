package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.AuctionType
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig.BazaarType
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC

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

    val bazaarPriceType by enum(BazaarType.INSTANT_BUY) {
        name = TranslatableValue.literal("Bazaar Price Type")
        description = TranslatableValue.literal("Choose which bazaar price metric should be used when tracking")
    }

    val auctionPriceType by enum(AuctionType.LOWEST) {
        name = TranslatableValue.literal("Auction Price Type")
        description = TranslatableValue.literal("Choose which auction house price metric should be used when tracking")
    }
}