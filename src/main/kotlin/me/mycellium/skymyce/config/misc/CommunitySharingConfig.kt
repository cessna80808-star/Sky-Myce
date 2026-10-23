package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.community.SharingMode

object CommunitySharingConfig : CategoryKt("Community Sharing") {
    var mode by enum(SharingMode.ANONYMOUS) {
        name = TranslatableValue.literal("Community data sharing")
        description = TranslatableValue.literal("On: shares your dungeon stats, RNG drops, activity and leaderboard statistics (such as dungeon chest profit), with your Minecraft name on public reports. " +
            "On (anon): shares the same categories, but public reports are anonymous; switching from On removes or hides earlier named reports. " +
            "Off: shares nothing and removes or suppresses your earlier contributions. Local features, Discord chat and cosmetics work in every state.")
    }
    var syncedAccount by string("") { condition = { false } }
    var syncedPolicy by string("") { condition = { false } }
}
