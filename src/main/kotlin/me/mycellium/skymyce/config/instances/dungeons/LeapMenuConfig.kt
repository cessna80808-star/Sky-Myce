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
    // Replaces the old 6 fps "framesPerSecond" limit, so existing configs pick up the smoother default.
    var previewFramesPerSecond by int(30) {
        name = TranslatableValue.literal("POV frame rate")
        description = TranslatableValue.literal("Target updates per teammate each second. Previews slow down automatically if together they would take more than half the frame time.")
        range = 2..60; slider = true
    }
}
