package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object GlobalChatConfig : CategoryKt("Global SkyMyce Chat") {
    var enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Receive global Sky-Myce and Discord chat in any world. /sm irc selects this mode; /sm irc <message> sends once. /sm chat returns to normal chat. An explicit IRC command enables this preference.")
    }
    val mentionSounds by boolean(true) {
        name = TranslatableValue.literal("Mention Sound")
        description = TranslatableValue.literal("Play one subtle ping when a new global message mentions your linked account.")
    }
}
