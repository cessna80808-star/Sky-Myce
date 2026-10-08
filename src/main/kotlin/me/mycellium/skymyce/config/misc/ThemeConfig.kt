package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.hud.ThemeOverrides
import me.mycellium.skymyce.hud.ThemePreset
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.GuiQuality

object ThemeConfig : CategoryKt("Theme & Appearance") {
    var migratedDigestTheme by boolean(false) { condition = { false } }
    var themeVersion by int(0) { condition = { false } }
    var preset by enum(ThemePreset.DARK_GLASS) {
        name = TranslatableValue.literal("Theme preset")
        description = TranslatableValue.literal("Base palette inherited by menus and widgets. Overrides remain separate.")
    }
    var graphicsQuality by enum(GuiQuality.MEDIUM) {
        name = TranslatableValue.literal("GUI Graphics Quality")
        description = TranslatableValue.literal("Extremely Light, Light, Medium or High. Controls raster detail, shadows and ambient effects while keeping essential interaction feedback.")
    }
    var animationMode by enum(GuiMotion.MEDIUM) {
        name = TranslatableValue.literal("GUI Animations")
        description = TranslatableValue.literal("Light is fast and precise, Medium is fluid, and Heavy adds physical depth. Reduced and Off simplify motion for accessibility.")
    }
    var menuIntroPlayed by boolean(false) { condition = { false } }

    fun migrateDigestTheme() {
        if (!migratedDigestTheme) {
            if (primary == -1 || primary == 0x67CCF2) primary = DailyDigestConfig.accent.color
            if ((opacity == -1 || opacity == 90) && DailyDigestConfig.cardOpacity != 96) opacity = DailyDigestConfig.cardOpacity.coerceIn(20, 100)
            migratedDigestTheme = true
        }
        if (themeVersion < 1) {
            if (primary == 0x67CCF2) primary = -1
            if (secondary == 0xC2A2F5) secondary = -1
            if (opacity == 90) opacity = -1
            themeVersion = 1
        }
    }
    var primary by int(-1) {
        name = TranslatableValue.literal("Primary accent")
        description = TranslatableValue.literal("Main highlight used throughout Sky-Hawk menus and HUD panels.")
        range = -1..0xFFFFFF
    }
    var secondary by int(-1) {
        name = TranslatableValue.literal("Secondary accent")
        description = TranslatableValue.literal("Secondary highlight for special information and selection outlines.")
        range = -1..0xFFFFFF
    }
    var opacity by int(-1) {
        name = TranslatableValue.literal("Panel opacity")
        description = TranslatableValue.literal("Opacity of all Sky-Hawk panels and buttons, from transparent to solid. Text remains fully readable.")
        range = -1..100
        slider = true
    }
    var panel by int(-1) { range = -1..0xFFFFFF; condition = { false } }
    var card by int(-1) { range = -1..0xFFFFFF; condition = { false } }
    var text by int(-1) { range = -1..0xFFFFFF; condition = { false } }
    var muted by int(-1) { range = -1..0xFFFFFF; condition = { false } }
    var border by int(-1) { range = -1..0xFFFFFF; condition = { false } }
    var padding by int(-1) { range = -1..12; condition = { false } }
    var rounded by int(-1) { range = -1..1; condition = { false } }
    var shadow by int(-1) { range = -1..1; condition = { false } }
    var borders by int(-1) { range = -1..1; condition = { false } }

    fun overrides() = ThemeOverrides(primary.validColor(), secondary.validColor(), panel.validColor(), card.validColor(), text.validColor(),
        muted.validColor(), border.validColor(), opacity.takeIf { it in 20..100 }, rounded.flag(), padding.takeIf { it in 2..12 }, shadow.flag(), borders.flag())
    fun apply(value: ThemeOverrides) {
        primary = value.primary ?: -1; secondary = value.secondary ?: -1; panel = value.panel ?: -1; card = value.card ?: -1
        text = value.text ?: -1; muted = value.muted ?: -1; border = value.border ?: -1; opacity = value.opacity ?: -1
        padding = value.padding ?: -1; rounded = value.rounded.encoded(); shadow = value.shadow.encoded(); borders = value.borders.encoded()
    }
    private fun Int.validColor() = takeIf { it in 0..0xFFFFFF }
    private fun Int.flag() = when (this) { 0 -> false; 1 -> true; else -> null }
    private fun Boolean?.encoded() = when (this) { true -> 1; false -> 0; null -> -1 }
}
