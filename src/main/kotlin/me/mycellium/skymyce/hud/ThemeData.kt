package me.mycellium.skymyce.hud

import kotlin.math.pow

/** Geometry and effects share the layout; presets only tune the supported paint treatments. */
data class ThemeStyle(val radius: Int = 5, val menuPadding: Int = 8,
    val sheen: Double = .35, val glow: Double = .15, val shadowTint: Int = 0x05080E,
    val shadowOpacity: Int = 18, val borderWidth: Double = 1.0,
    val hoverWeight: Int = 14, val selectionWeight: Int = 22,
    val focusWidth: Int = 2, val transitionMillis: Int = 150)

data class ThemePalette(val primary: Int = 0xBDA5EC, val secondary: Int = 0xDED0F9,
    val panel: Int = 0x191622, val card: Int = 0x272232, val text: Int = 0xF4F0FA,
    val muted: Int = 0xB9ACC8, val border: Int = 0x50465F, val opacity: Int = 94,
    val rounded: Boolean = true, val padding: Int = 5, val shadow: Boolean = false,
    val borders: Boolean = true, val success: Int = 0x87D6A2, val warning: Int = 0xE8BF71,
    val error: Int = 0xF18C8C, val sidebar: Int = panel, val style: ThemeStyle = ThemeStyle())

enum class ThemePreset(val title: String, val palette: ThemePalette, val description: String) {

    DARK_GLASS("Dark glass", ThemePalette(sidebar = 0x12101B, shadow = true,
        style = ThemeStyle(radius = 7, sheen = .5, glow = .12, shadowTint = 0x100A19)),
        "Soft violet glass, layered surfaces and gentle highlights."),
    MINIMAL("Minimal", ThemePalette(primary = 0x81D4E9, secondary = 0xB7BAC4, panel = 0x141618, card = 0x202326,
        text = 0xEFF2F5, muted = 0xA6AFB8, border = 0x41464C, opacity = 100, rounded = false, padding = 3, borders = false,
        sidebar = 0x111315, style = ThemeStyle(radius = 0, menuPadding = 6, sheen = 0.0, glow = 0.0,
            shadowOpacity = 0, hoverWeight = 10, selectionWeight = 18, transitionMillis = 110)),
        "Flat graphite, crisp edges and quiet cyan selection."),
    LIGHT("Light", ThemePalette(primary = 0x00627E, secondary = 0x67449E, panel = 0xEEF2F6, card = 0xFFFFFF,
        text = 0x17232D, muted = 0x495F70, border = 0x99ADBD, opacity = 100, shadow = true,
        success = 0x196938, warning = 0x805300, error = 0xAE2222, sidebar = 0xE2EAF1,
        style = ThemeStyle(radius = 4, sheen = 0.0, glow = 0.0, shadowTint = 0x607080,
            shadowOpacity = 8, hoverWeight = 8, selectionWeight = 14)),
        "Cool paper surfaces, dark ink and restrained depth."),
    HIGH_CONTRAST("High contrast", ThemePalette(primary = 0x67E8FF, secondary = 0xFFFF66, panel = 0, card = 0x101010,
        text = 0xFFFFFF, muted = 0xDEDEDE, border = 0xFFFFFF, opacity = 100, rounded = false, padding = 7,
        success = 0x66FF99, warning = 0xFFFF66, error = 0xFF8888,
        style = ThemeStyle(radius = 0, sheen = 0.0, glow = 0.0, shadowOpacity = 0,
            borderWidth = 2.0, hoverWeight = 18, selectionWeight = 28, focusWidth = 3, transitionMillis = 100)),
        "Solid black, bright text and substantial focus outlines."),
    SIGNATURE("Signature", ThemePalette(primary = 0x71D4EA, secondary = 0xB59BE6, panel = 0x141A24, card = 0x202A37,
        text = 0xF1F5FC, muted = 0xABBBCE, border = 0x445469, opacity = 98, shadow = true,
        sidebar = 0x10151E, style = ThemeStyle(sheen = .24, glow = .08)),
        "Technical cyan and violet, precise lines and neutral slate."),
    BOREALIS("Borealis", ThemePalette(primary = 0x75DED0, secondary = 0x9CC9F4, panel = 0x102334, card = 0x1C3548,
        text = 0xEAF7FC, muted = 0xA6C4D8, border = 0x416277, opacity = 96, padding = 6, shadow = true,
        success = 0x84DFC0, warning = 0xE6CA85, error = 0xF39BA2, sidebar = 0x0C1B2A,
        style = ThemeStyle(radius = 8, menuPadding = 9, sheen = .55, glow = .1,
            shadowTint = 0x061727, shadowOpacity = 22, hoverWeight = 12, selectionWeight = 20, transitionMillis = 180)),
        "Deep navy, soft corners and an icy atmospheric wash."),
    EMBERFORGE("Emberforge", ThemePalette(primary = 0xE7B077, secondary = 0xD99279, panel = 0x24201D, card = 0x34302B,
        text = 0xF8F0E5, muted = 0xC6B8A7, border = 0x746150, opacity = 100, padding = 4,
        success = 0xAAD294, warning = 0xF1C777, error = 0xF09A89, sidebar = 0x1B1816,
        style = ThemeStyle(radius = 2, menuPadding = 7, sheen = .12, glow = 0.0,
            shadowTint = 0x160E08, shadowOpacity = 12, hoverWeight = 10, selectionWeight = 24, transitionMillis = 130)),
        "Copper on warm graphite, firm corners and grounded contrast."),
    VERDANT("Verdant", ThemePalette(primary = 0x92D1AE, secondary = 0xC4D5A0, panel = 0x18251F, card = 0x25382E,
        text = 0xF0F7ED, muted = 0xB2C8B9, border = 0x4E6B59, opacity = 100, padding = 6,
        success = 0xA6D6A1, warning = 0xE8CD88, error = 0xEFA49A, sidebar = 0x111C16,
        style = ThemeStyle(radius = 6, menuPadding = 9, sheen = 0.0, glow = 0.0,
            shadowOpacity = 10, hoverWeight = 10, selectionWeight = 18, transitionMillis = 165)),
        "Matte forest and jade, generous spacing and clear separation.");

    override fun toString() = title
}

internal fun themeLuminance(rgb: Int): Double {
    fun channel(shift: Int): Double {
        val value = (rgb shr shift and 255) / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return channel(16) * .2126 + channel(8) * .7152 + channel(0) * .0722
}

internal fun themeContrast(first: Int, second: Int): Double {
    val a = themeLuminance(first); val b = themeLuminance(second)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

/** A selected accent fill needs its own foreground, especially in the retained light theme. */
private val darkForegroundLuminance = themeLuminance(0x101820)
fun themeForeground(background: Int): Int {
    val luminance = themeLuminance(background)
    val darkContrast = (maxOf(luminance, darkForegroundLuminance) + .05) / (minOf(luminance, darkForegroundLuminance) + .05)
    return if (1.05 / (luminance + .05) >= darkContrast) 0xFFFFFF else 0x101820
}

