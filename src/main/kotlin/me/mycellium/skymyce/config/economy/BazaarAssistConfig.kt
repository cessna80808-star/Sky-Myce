package me.mycellium.skymyce.config.economy

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.bazaar.assist.AssistPriceMode

enum class AssistHighlightShape(val label: String) { OUTLINE_FILL("Outline and fill"), OUTLINE("Outline"), FILL("Fill"), CORNERS("Corner brackets") }
enum class AssistPanelPlacement(val label: String) { BESIDE_MENU("Beside the open menu"), SAVED("Where I put it") }
enum class AssistPulse(val label: String) { ALWAYS("Always pulse"), ON_CHANGE("Pulse when the target changes"), NONE("No pulse") }

/** Property names are persisted config keys. Start, stop and send-command keys live in Minecraft's Controls. */
object BazaarAssistConfig : CategoryKt("Bazaar Assistant") {
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Guide a chosen flip step by step: what to run, where to click and what to type. You still do every action yourself")
    }
    var priceMode by enum(AssistPriceMode.MATCH_ORDER_BOOK) {
        name = TranslatableValue.literal("Price entry")
        description = TranslatableValue.literal("Match the order book uses Top Order +0.1 and Best Offer -0.1. Custom types the recommended price on a sign")
    }
    var ghostText by boolean(true) {
        name = TranslatableValue.literal("Command ghost text")
        description = TranslatableValue.literal("Show the rest of the expected command faintly while you type in chat")
    }
    var tabFill by boolean(true) {
        name = TranslatableValue.literal("Tab completes the command")
        description = TranslatableValue.literal("Press Tab in chat to fill in the shown command. Other commands keep normal Tab completion")
    }
    var signHelper by boolean(true) {
        name = TranslatableValue.literal("Sign helper")
        description = TranslatableValue.literal("Show the expected search, amount or price above Bazaar signs and check what you typed")
    }
    var menuButton by boolean(true) {
        name = TranslatableValue.literal("Sky-Myce button in Bazaar menus")
        description = TranslatableValue.literal("Adds a button to Hypixel's Bazaar menus that opens the Sky-Myce Bazaar terminal")
    }
    var panelInMenus by boolean(true) {
        name = TranslatableValue.literal("Show the panel over Bazaar menus")
    }
    var panelPlacement by enum(AssistPanelPlacement.BESIDE_MENU) {
        name = TranslatableValue.literal("Panel position in menus")
        description = TranslatableValue.literal("Beside the open menu, or where you put it. Dragging the panel inside a menu switches to where you put it")
    }
    var profitNotices by boolean(true) {
        name = TranslatableValue.literal("Profit pop-ups")
        description = TranslatableValue.literal("Briefly show realized profit when a guided sale is claimed")
    }
    var animations by boolean(true) {
        name = TranslatableValue.literal("Animations")
        description = TranslatableValue.literal("Gliding highlights, fades and counting numbers. Also off when menu animations are off")
    }
    var highlightColor by string("") {
        name = TranslatableValue.literal("Highlight color")
        description = TranslatableValue.literal("Hex such as #FFC857. Leave empty to use your theme accent")
    }
    var highlightShape by enum(AssistHighlightShape.OUTLINE_FILL) {
        name = TranslatableValue.literal("Highlight shape")
    }
    var highlightThickness by int(2) {
        name = TranslatableValue.literal("Highlight thickness")
        range = 1..4; slider = true
    }
    var highlightOpacity by int(80) {
        name = TranslatableValue.literal("Highlight opacity (%)")
        range = 20..100; slider = true
    }
    var highlightPulse by enum(AssistPulse.ON_CHANGE) {
        name = TranslatableValue.literal("Highlight pulse")
    }
    // Explained and confirmed from the Bazaar terminal before either can be turned on.
    var autoCommand by boolean(false) { condition = { false } }
    var autoSign by boolean(false) { condition = { false } }
    // How often Tab filled the command; the "Tab" hint disappears after the first few uses.
    var tabFills by int(0) { condition = { false } }

    fun resetAppearance() {
        enabled = true; priceMode = AssistPriceMode.MATCH_ORDER_BOOK; ghostText = true; tabFill = true; signHelper = true
        menuButton = true; panelInMenus = true; panelPlacement = AssistPanelPlacement.BESIDE_MENU; profitNotices = true; animations = true; highlightColor = ""; highlightShape = AssistHighlightShape.OUTLINE_FILL
        highlightThickness = 2; highlightOpacity = 80; highlightPulse = AssistPulse.ON_CHANGE; autoCommand = false; autoSign = false
    }
}

/** "#RRGGBB" or "RRGGBB" to RGB; anything else is null. */
internal fun parseAssistColor(value: String): Int? = value.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toInt(16)
