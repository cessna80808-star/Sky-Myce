package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC

object DungeonSplitsConfig : CategoryKt("Dungeon Splits") {
    enum class Comparison { SECTION_PB, NONE }

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
        description = TranslatableValue.literal("Compare each finished section with your best time for that section, or show times only")
    }

    val showUpcoming by boolean(true) {
        name = TranslatableValue.literal("Show Upcoming Splits")
        description = TranslatableValue.literal("List splits that have not happened yet in the HUD")
    }

    val showTotal by boolean(true) {
        name = TranslatableValue.literal("Show Run Total")
        description = TranslatableValue.literal("Show the run's elapsed time and the floor's actual and theoretical best in the HUD")
    }

    val showServerTime by boolean(true) {
        name = TranslatableValue.literal("Show Server Time")
        description = TranslatableValue.literal("Show each split's server time in brackets after its real time, and the time lost to lag. Server time counts server ticks, so lag does not add to it")
    }

    val bestsClock by enum(SplitClock.REAL) {
        name = TranslatableValue.literal("Compare On")
        description = TranslatableValue.literal("Which time the HUD compares with your bests: real time, which includes lag, or server time, which leaves it out")
    }

    val precise by boolean(true) {
        name = TranslatableValue.literal("Hundredths")
        description = TranslatableValue.literal("Show split times to a hundredth of a second")
    }
}
