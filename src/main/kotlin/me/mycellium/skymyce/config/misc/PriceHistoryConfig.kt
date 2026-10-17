package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.general.pricehistory.PriceRange
import org.lwjgl.glfw.GLFW

object PriceHistoryConfig : CategoryKt("Price History") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Press the key below while hovering an item in any menu to open its price history graph (Bazaar and auction items, data from Coflnet)")
    }

    val key by key(GLFW.GLFW_KEY_P) {
        name = TranslatableValue.literal("Price History Key")
        description = TranslatableValue.literal("Hover an item in a menu and press this key")
    }

    val defaultRange by enum(PriceRange.WEEK) {
        name = TranslatableValue.literal("Default Range")
        description = TranslatableValue.literal("The time range the graph opens with")
    }
}
