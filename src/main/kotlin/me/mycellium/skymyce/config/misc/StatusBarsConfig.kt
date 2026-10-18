package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object StatusBarsConfig : CategoryKt("Status Bars") {
    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show health, mana, defense, speed, vitality and respiration as separate animated bars. Arrange, resize, recolour or hide each one with /sm bars or Open on this feature")
    }
    val useHearts by boolean(true) {
        name = TranslatableValue.literal("Read Vanilla Hearts")
        description = TranslatableValue.literal("Update health from your vanilla hearts every tick, using the action bar only for the maximum and absorption. Faster than the action bar, which refreshes about once a second")
    }
    val hideVanilla by boolean(true) {
        name = TranslatableValue.literal("Hide Vanilla Hearts")
        description = TranslatableValue.literal("Hide the vanilla hearts, armor and hunger rows on SkyBlock while the bars show, and the air bubbles while the respiration bar is on. They return everywhere else")
    }
    val hideActionBar by boolean(true) {
        name = TranslatableValue.literal("Hide Action Bar Stats")
        description = TranslatableValue.literal("Remove health, defense, mana and vitality from the action bar while their bars show them. Other action bar text stays")
    }
    val animate by boolean(true) {
        name = TranslatableValue.literal("Animate")
        description = TranslatableValue.literal("Spring the bars to new values, trail and flash damage, sweep heals, roll numbers and beat at low health. Off draws exact values instantly")
    }

    /** Before 2.7.27 the bars were one panel; these seed the per-bar layout once. */
    val showMana by boolean(true) { condition = { false } }
    val showDefense by boolean(true) { condition = { false } }
}
