package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object LeapMenuConfig : CategoryKt("Leap Menu") {
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show four teammate cards in the Spirit Leap menu. Click a view or press 1–4 to leap.")
    }
    var povEnabled by boolean(true) {
        name = TranslatableValue.literal("Teammate POVs")
        description = TranslatableValue.literal("Approximate eye-level views of the locally loaded world; unavailable views stay blank.")
    }
    var partyPovSharing by boolean(false) {
        name = TranslatableValue.literal("Share party viewpoints")
        description = TranslatableValue.literal("Share your position and viewing direction with current teammates through Community. Both players must enable this.")
    }
    var framesPerSecond by int(6) {
        name = TranslatableValue.literal("POV frame limit")
        description = TranslatableValue.literal("Target maximum updates per teammate each second. Slow views update less often.")
        range = 2..60; slider = true
    }
}
