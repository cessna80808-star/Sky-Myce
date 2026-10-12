package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object DailyDigestConfig : CategoryKt("Daily Digest") {
    val automatic by boolean(true) {
        name = TranslatableValue.literal("Open daily digest automatically")
        description = TranslatableValue.literal("Once per local computer day, after SkyBlock and your profile have loaded")
    }
    val openingDelay by int(8) {
        name = TranslatableValue.literal("Opening delay")
        description = TranslatableValue.literal("Seconds to settle after entering SkyBlock; opening a menu postpones the digest")
        range = 3..30
        slider = true
    }
    val news by boolean(true) {
        name = TranslatableValue.literal("Daily news")
        description = TranslatableValue.literal("Fetch public Cowshed updates through the relay; cached updates remain available offline")
    }
    val gameUpdates by boolean(true) { name = TranslatableValue.literal("Game updates"); description = TranslatableValue.literal("Include Cowshed updates for the live SkyBlock server.") }
    val alphaUpdates by boolean(true) { name = TranslatableValue.literal("Alpha updates"); description = TranslatableValue.literal("Include Cowshed announcements for the Alpha test server.") }
    val receiveRng by boolean(true) {
        name = TranslatableValue.literal("Receive community RNG feed")
        description = TranslatableValue.literal("Show optional, unverified drop reports from other Sky-Hawk users")
    }
    val shareRng by boolean(true) {
        condition = { false } // Preserved and migrated to the canonical Community Sharing controls.
        name = TranslatableValue.literal("Share personal RNG drops")
        description = TranslatableValue.literal("Send only supported drop, activity, time and an optional public name through the authenticated relay. No chat or inventory is sent.")
    }
    val publicRngName by boolean(false) {
        condition = { false }
        name = TranslatableValue.literal("Show public RNG name")
        description = TranslatableValue.literal("Attach your Minecraft name to shared drop reports; otherwise reports appear anonymous")
    }
    val retainedRngEvents by int(100) {
        name = TranslatableValue.literal("Local RNG history size")
        description = TranslatableValue.literal("Maximum retained personal events; community reports are capped separately at 30. Clear personal history from the RNG submenu.")
        range = 20..500
        slider = true
    }
    val accent by enum(Accent.CYAN) { condition = { false } } // Legacy values migrate to the shared theme.
    val cardOpacity by int(96) {
        name = TranslatableValue.literal("Card opacity")
        condition = { false }
        range = 75..100
        slider = true
    }
    val compact by boolean(false) {
        name = TranslatableValue.literal("Compact spacing")
        description = TranslatableValue.literal("Reduce card padding while retaining readable, scrollable layouts")
    }

    enum class Accent(val color: Int) { CYAN(0x67CCF2), PURPLE(0xC2A2F5), GOLD(0xE8BF71), GREEN(0x87D6A2) }
}
