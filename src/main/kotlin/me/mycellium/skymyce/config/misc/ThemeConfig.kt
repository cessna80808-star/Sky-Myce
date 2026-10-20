package me.mycellium.skymyce.config.misc

import com.google.gson.JsonObject
import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.hud.ThemePalette
import me.mycellium.skymyce.hud.ThemePreset
import me.mycellium.skymyce.ui.GuiMotion
import me.mycellium.skymyce.ui.GuiQuality

object ThemeConfig : CategoryKt("Theme & Appearance") {
    var preset by enum(ThemePreset.SIGNATURE) {
        name = TranslatableValue.literal("Theme")
        description = TranslatableValue.literal("The palette and component style for every Sky-Myce menu and HUD widget.")
    }
    var graphicsQuality by enum(GuiQuality.AUTO) {
        name = TranslatableValue.literal("Graphics mode")
        description = TranslatableValue.literal("Extremely Light and Light keep Sky-Myce's menus plain and turn GUI animations off. Medium adds sharper menus, shadows, glow and animations; High is the fullest. Auto keeps Medium animations. " +
            "Auto (recommended) moves between these to hold 60 FPS, never above your frame limit; /sm graphics shows what it chose. HUD information is never reduced, and Minecraft's own settings are untouched.")
    }
    var menuIntroPlayed by boolean(false) { condition = { false } }

    private var pinnedMotion: GuiMotion? = null

    /** Not a setting: GUI animations follow the graphics mode; Auto keeps Medium so motion never switches mid-session. Checks may pin a mode. */
    var animationMode: GuiMotion
        get() = pinnedMotion ?: when (graphicsQuality) {
            GuiQuality.EXTREMELY_LIGHT, GuiQuality.LIGHT -> GuiMotion.OFF
            GuiQuality.HIGH -> GuiMotion.HEAVY
            else -> GuiMotion.MEDIUM
        }
        internal set(value) { pinnedMotion = value }

    /** An old file without a preset still used Dark glass; old custom colors move to the nearest preset. */
    fun restoreLegacyTheme(original: JsonObject) {
        preset = restoredThemePreset(original, preset)
    }
}

private val legacyThemeKeys = listOf("primary", "secondary", "panel", "card", "text", "muted", "border", "opacity", "padding", "rounded",
    "shadow", "borders", "animationMode", "autoTargetFps", "autoResponse", "autoLowest", "migratedDigestTheme", "themeVersion")

internal fun restoredThemePreset(original: JsonObject, loaded: ThemePreset): ThemePreset {
    val theme = original.get("Theme & Appearance")?.takeIf { it.isJsonObject }?.asJsonObject
    if (original.size() > 0 && theme?.has("preset") != true) return ThemePreset.DARK_GLASS
    fun color(key: String) = theme?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 0..0xFFFFFF }
    val base = loaded.palette
    val custom = base.copy(primary = color("primary") ?: base.primary, secondary = color("secondary") ?: base.secondary,
        panel = color("panel") ?: base.panel, card = color("card") ?: base.card, text = color("text") ?: base.text,
        muted = color("muted") ?: base.muted, border = color("border") ?: base.border)
    legacyThemeKeys.forEach { theme?.remove(it) }
    return if (custom == base) loaded else ThemePreset.entries.minBy { paletteDistance(it.palette, custom) }
}

/** Squared RGB distance over the colors a custom theme could change. */
internal fun paletteDistance(a: ThemePalette, b: ThemePalette): Long =
    listOf(a.primary to b.primary, a.secondary to b.secondary, a.panel to b.panel, a.card to b.card, a.text to b.text,
        a.muted to b.muted, a.border to b.border).sumOf { (x, y) ->
        (0..16 step 8).sumOf { shift -> ((x shr shift and 255) - (y shr shift and 255)).let { (it * it).toLong() } }
    }
