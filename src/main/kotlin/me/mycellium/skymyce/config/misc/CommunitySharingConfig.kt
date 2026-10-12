package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object CommunitySharingConfig : CategoryKt("Community Sharing") {
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Share community statistics")
        description = TranslatableValue.literal("Contribute supported statistics to the community. Turning this off removes or suppresses prior contributions. Local features, Discord chat and cosmetics remain available.")
    }
    var dungeons by boolean(true) {
        name = TranslatableValue.literal("Dungeon statistics and profit")
        description = TranslatableValue.literal("Share dungeon levels, classes and completion times in the friend cache; controls future run and profit contributions. Local tracking remains available.")
    }
    var economy by boolean(true) {
        name = TranslatableValue.literal("Economy statistics")
        description = TranslatableValue.literal("Share available net worth, purse, bank and wardrobe totals in the friend cache. No inventory contents are uploaded. Local calculations remain available.")
    }
    var rng by boolean(true) {
        name = TranslatableValue.literal("RNG drops and achievements")
        description = TranslatableValue.literal("Share newly detected supported drops with the community feed. Turning this off removes previous shared reports; personal history and receiving the feed remain available.")
    }
    var activity by boolean(true) {
        name = TranslatableValue.literal("Activity and progress")
        description = TranslatableValue.literal("Allow supported community activity contributions. Local Daily Digest tasks remain local until a sharing feature is available; saved history is never uploaded automatically.")
    }
    var leaderboards by boolean(true) {
        name = TranslatableValue.literal("Leaderboard contributions")
        description = TranslatableValue.literal("Allow measured statistics in future community leaderboards when their category is also enabled. Estimates and unverified reports are never presented as verified scores.")
    }
    var publicName by boolean(false) {
        name = TranslatableValue.literal("Show name in public statistics")
        description = TranslatableValue.literal("Display your Minecraft name on RNG reports and future public statistics. Otherwise reports are anonymous. Turning this off removes earlier named feed reports; chat and cosmetics keep their own identities.")
    }
    var legacySharingMigrated by boolean(false) { condition = { false } }
    var syncedAccount by string("") { condition = { false } }
    var syncedPolicy by string("") { condition = { false } }

    fun migrateLegacy() {
        if (legacySharingMigrated) return
        if (!DailyDigestConfig.shareRng) rng = false
        publicName = DailyDigestConfig.publicRngName
        legacySharingMigrated = true
    }
}
