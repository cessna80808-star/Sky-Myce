package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.instances.dungeons.DungeonCleanChat

object DungeonsConfig : CategoryKt("Dungeons") {
    init {
        category(DungeonTrackerConfig)
        category(DungeonSplitsConfig)
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
