package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitComparison
import me.mycellium.skymyce.features.instances.dungeons.splits.SplitWindow
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC

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
