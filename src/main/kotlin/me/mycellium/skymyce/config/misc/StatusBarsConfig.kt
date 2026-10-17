package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object StatusBarsConfig : CategoryKt("Status Bars") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Replace SkyBlock's health, mana and defense readouts with animated bars: a damage trail on hits, a gold absorption layer, overflow mana and effective health. Move it in the HUD editor")
    }
    val hideVanilla by boolean(true) {
        name = TranslatableValue.literal("Hide Vanilla Hearts")
        description = TranslatableValue.literal("Hide the vanilla hearts, armor and hunger rows on SkyBlock while the bars show. They return everywhere else")
    }
    val hideActionBar by boolean(true) {
        name = TranslatableValue.literal("Hide Action Bar Stats")
        description = TranslatableValue.literal("Remove health, defense and mana from the action bar while the bars show them. Other action bar text stays")
    }
    val showMana by boolean(true) {
        name = TranslatableValue.literal("Show Mana")
        description = TranslatableValue.literal("Show the mana bar, with overflow mana in cyan")
    }
    val showDefense by boolean(true) {
        name = TranslatableValue.literal("Show Defense")
        description = TranslatableValue.literal("Show defense and effective health (health × (1 + defense ÷ 100)) beside the health")
    }
    val animate by boolean(true) {
        name = TranslatableValue.literal("Animate")
        description = TranslatableValue.literal("Ease the bars to new values, trail damage and pulse at low health. Off draws exact values instantly")
    }
}
