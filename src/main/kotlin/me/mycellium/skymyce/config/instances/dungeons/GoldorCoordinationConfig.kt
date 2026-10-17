package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object GoldorCoordinationConfig : CategoryKt("Goldor Coordination") {
    /** Saved by name. Each maps to a profile id in `GoldorProfiles`. */
    enum class Profile(val title: String, val id: String) {
        SKY_MYCE("Sky-Myce ADG", "skymyce-adg"), ADG_2026("ADG 2026 (DTM preset)", "adg-2026");
        override fun toString() = title
    }

    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Track F7/M7 phase 3 terminals, devices, levers and early-enter routes, and recommend stacks and pre-leaps. Never clicks, moves or leaps for you")
    }

    val profile by enum(Profile.SKY_MYCE) {
        name = TranslatableValue.literal("Strategy")
        description = TranslatableValue.literal("Sky-Myce ADG: Berserker S2 T4 then T3. ADG 2026: the DungeonTerminalMap preset, Berserker S2 3 and 5. Both use MEE2, HEE3 and MEE4 Core")
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

    val backgroundOpacity by int(60) {
        name = TranslatableValue.literal("Background Opacity")
        description = TranslatableValue.literal("How visible the map's background is")
        range = 0..100; slider = true
    }
}
