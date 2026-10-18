package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object SignsConfig : CategoryKt("Signs") {
    /** Which signs Enter confirms. */
    enum class EnterConfirms(private val title: String) {
        OFF("Off"), MARKET("Auction House, Bazaar and bank"), PROMPTS("Every Hypixel input sign"), ALL("Every sign");
        override fun toString() = title
    }

    val enterConfirms by enum(EnterConfirms.MARKET) {
        name = TranslatableValue.literal("Enter Confirms Signs")
        description = TranslatableValue.literal("Press Enter to finish a sign, the same as clicking Done. Auction House, Bazaar and bank covers prices, bids, amounts and searches; every Hypixel input sign adds the rest of Hypixel's prompts; every sign includes signs you place. Elsewhere Enter moves to the next line as usual")
    }
}
