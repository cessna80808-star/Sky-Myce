package me.mycellium.skymyce.config.misc

import com.google.gson.JsonObject
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
    var news by boolean(true) {
        name = TranslatableValue.literal("Daily news")
        description = TranslatableValue.literal("Fetch public Cowshed updates through the relay; cached updates remain available offline")
    }
    val receiveRng by boolean(true) {
        name = TranslatableValue.literal("Receive community RNG feed")
        description = TranslatableValue.literal("Show optional, unverified drop reports from other Sky-Hawk users")
    }
    val retainedRngEvents by int(100) {
        name = TranslatableValue.literal("Local RNG history size")
        description = TranslatableValue.literal("Maximum retained recent personal events; saved acquisition history remains available. Community reports are capped separately at 30.")
        range = 20..500
        slider = true
    }
    val compact by boolean(false) {
        name = TranslatableValue.literal("Compact spacing")
        description = TranslatableValue.literal("Reduce card padding while retaining readable, scrollable layouts")
    }

}

/** Fold the former normal-news switch into News once; stale fields cannot override later user choices. */
internal fun migrateDailyNewsConfig(original: JsonObject, enabled: Boolean): Boolean {
    val digest = original.get("General")?.takeIf { it.isJsonObject }?.asJsonObject
        ?.get("Daily Digest")?.takeIf { it.isJsonObject }?.asJsonObject ?: return enabled
    val game = digest.get("gameUpdates")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
    val migrated = enabled && game?.asBoolean != false
    digest.remove("gameUpdates")
    digest.remove("alphaUpdates")
    return migrated
}
