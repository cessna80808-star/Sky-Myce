package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

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
