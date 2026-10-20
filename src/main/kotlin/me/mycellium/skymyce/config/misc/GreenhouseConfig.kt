package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.garden.greenhouse.PriceMode
import me.mycellium.skymyce.features.garden.greenhouse.ProfitMetric

object GreenhouseConfig : CategoryKt("Greenhouse Planner") {
    enum class Tier { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    val enabled by boolean(false) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Ranks mutations by estimated profit, solves a full Greenhouse layout and shows it as ghost blocks while you hold a crop or soil. Visual only; use /sm greenhouse. Profit formula and planner idea credit: skymutations.eu by palmaner")
    }

    val priceMode by enum(PriceMode.SELL_OFFER) {
        name = TranslatableValue.literal("Mutation Price")
        description = TranslatableValue.literal("Sell Offer matches skymutations.eu; Instant Sell is the lower, immediate price. Ingredients are always priced at instant buy")
    }

    val profitMetric by enum(ProfitMetric.PER_HOUR) {
        name = TranslatableValue.literal("Profit Metric")
        description = TranslatableValue.literal("Sort the ranking by profit per hour or per growth cycle")
    }

    val maxTier by enum(Tier.LEGENDARY) {
        name = TranslatableValue.literal("Highest Unlocked Tier")
        description = TranslatableValue.literal("Only rank and plan mutations up to this rarity")
    }

    val upgradeLevel by int(0) {
        name = TranslatableValue.literal("Growth Speed Upgrade")
        description = TranslatableValue.literal("Your Greenhouse growth speed upgrade level (0-9); shortens every growth stage")
        range = 0..9; slider = true
    }

    val uniqueCrops by int(0) {
        name = TranslatableValue.literal("Unique Crops Elsewhere")
        description = TranslatableValue.literal("Unique base crop types you already grow in other Greenhouse plots; the planner adds its own on top")
        range = 0..12; slider = true
    }

    val opacity by int(40) {
        name = TranslatableValue.literal("Ghost Block Opacity")
        range = 15..80; slider = true
    }

    val renderDistance by int(48) {
        name = TranslatableValue.literal("Max Render Distance")
        range = 8..128; slider = true
    }
}
