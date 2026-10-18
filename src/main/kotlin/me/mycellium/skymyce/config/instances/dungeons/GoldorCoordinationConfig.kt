package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

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
