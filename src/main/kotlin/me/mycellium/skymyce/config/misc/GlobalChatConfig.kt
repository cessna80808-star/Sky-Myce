package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object GlobalChatConfig : CategoryKt("Global SkyMyce Chat") {
    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Opt into the global SkyMyce and Discord channel in any world. Use /sm global <message> or /chat sky; normal chat stays unchanged.")
    }
    val mentionSounds by boolean(true) {
        name = TranslatableValue.literal("Mention Sound")
        description = TranslatableValue.literal("Play one subtle ping when a new global message mentions your linked account.")
    }
}
