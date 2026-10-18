package me.mycellium.skymyce.config.economy

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.bazaar.BazaarRisk
import org.lwjgl.glfw.GLFW

/** Property names are persisted config and synchronized-preference keys; rename only with a migration. */
object BazaarConfig : CategoryKt("Bazaar Flipping") {
    var risk by enum(BazaarRisk.BALANCED) {
        name = TranslatableValue.literal("Risk profile")
        description = TranslatableValue.literal("Conservative favours steady, liquid items. Aggressive accepts slower fills and more volatility for larger returns")
    }
    var minimumProfit by int(1000) {
        name = TranslatableValue.literal("Minimum profit per flip")
        description = TranslatableValue.literal("Estimated coins after tax")
        range = 0..100000000
    }
    var minimumRoiBps by int(50) {
        name = TranslatableValue.literal("Minimum return (basis points)")
        description = TranslatableValue.literal("100 = 1% of the coins you put in")
        range = 0..10000
    }
    var minimumLiquidity by int(1000) {
        name = TranslatableValue.literal("Minimum weekly volume")
        description = TranslatableValue.literal("Items traded per week on the quieter side of the market")
        range = 0..100000000
    }
    var maximumFillMinutes by int(0) {
        name = TranslatableValue.literal("Longest acceptable fill (minutes)")
        description = TranslatableValue.literal("0 allows slow flips. Fill times are rough estimates")
        range = 0..10080
    }
    var minimumConfidence by int(35) {
        name = TranslatableValue.literal("Minimum confidence (%)")
        range = 0..100
    }
    var maximumCapital by int(10000000) {
        name = TranslatableValue.literal("Most coins per flip")
        description = TranslatableValue.literal("Suggestions also never exceed your purse")
        range = 1..1000000000
    }
    var concentration by int(40) {
        name = TranslatableValue.literal("Most capital in one item (%)")
        description = TranslatableValue.literal("Share of your purse plus open positions")
        range = 1..100
    }
    var sellTaxBps by int(125) {
        name = TranslatableValue.literal("Bazaar tax (basis points)")
        description = TranslatableValue.literal("Used for estimates only: 125 = 1.25%. Realized profit always uses the coins you actually received")
        range = 0..2500
    }
    var relistThreshold by int(6) {
        name = TranslatableValue.literal("Relist warning after")
        description = TranslatableValue.literal("Suggest waiting or selling instantly once a position has been relisted more than this many times")
        range = 1..100
    }
    var toggleKey by key(GLFW.GLFW_KEY_UNKNOWN) {
        name = TranslatableValue.literal("Start/stop flipping key")
    }
    var cycleKey by key(GLFW.GLFW_KEY_UNKNOWN) {
        name = TranslatableValue.literal("Next position key")
        description = TranslatableValue.literal("Cycle the positions shown in the Bazaar HUD")
    }
    var alertProfit by int(100000) {
        name = TranslatableValue.literal("Alert minimum profit")
        description = TranslatableValue.literal("Exceptional-flip alerts only fire while flipping, with live data, for flips you can afford")
        range = 0..1000000000
    }
    var alertCooldownSeconds by int(300) {
        name = TranslatableValue.literal("Alert cooldown (seconds)")
        description = TranslatableValue.literal("Shortest gap between alerts on any channel. Channel cooldowns below can only lengthen it")
        range = 30..3600
    }
    var inGameAlerts by boolean(true) {
        name = TranslatableValue.literal("In-game alerts")
    }
    var inGameAlertSound by boolean(false) {
        name = TranslatableValue.literal("In-game alert sound")
    }
    var dashboardAlerts by boolean(true) {
        name = TranslatableValue.literal("Dashboard alerts")
        description = TranslatableValue.literal("Shown while the local browser dashboard is open")
    }
    var dashboardAlertSound by boolean(false) {
        name = TranslatableValue.literal("Dashboard alert sound")
    }
    var discordAlerts by boolean(false) {
        name = TranslatableValue.literal("Discord DM alerts")
        description = TranslatableValue.literal("Private messages from the Sky-Myce bot while you are online and flipping. Never posted publicly")
    }
    var inGameAlertProfit by int(0) {
        name = TranslatableValue.literal("In-game minimum profit")
        description = TranslatableValue.literal("0 uses the alert minimum")
        range = 0..1000000000
    }
    var dashboardAlertProfit by int(0) {
        name = TranslatableValue.literal("Dashboard minimum profit")
        description = TranslatableValue.literal("0 uses the alert minimum")
        range = 0..1000000000
    }
    var discordAlertProfit by int(0) {
        name = TranslatableValue.literal("Discord DM minimum profit")
        description = TranslatableValue.literal("0 uses the alert minimum")
        range = 0..1000000000
    }
    var webhookAlertProfit by int(0) {
        name = TranslatableValue.literal("Webhook minimum profit")
        description = TranslatableValue.literal("0 uses the alert minimum")
        range = 0..1000000000
    }
    var inGameAlertCooldown by int(300) {
        name = TranslatableValue.literal("In-game cooldown (seconds)")
        range = 30..3600
    }
    var dashboardAlertCooldown by int(300) {
        name = TranslatableValue.literal("Dashboard cooldown (seconds)")
        range = 30..3600
    }
    var discordAlertCooldown by int(300) {
        name = TranslatableValue.literal("Discord DM cooldown (seconds)")
        range = 30..3600
    }
    var webhookAlertCooldown by int(300) {
        name = TranslatableValue.literal("Webhook cooldown (seconds)")
        range = 30..3600
    }
    var timezone by string(java.time.ZoneId.systemDefault().id) {
        name = TranslatableValue.literal("Leaderboard timezone")
        description = TranslatableValue.literal("IANA name such as Europe/Stockholm. Weeks start Sunday at local midnight. Changes apply to future sales only")
    }
    var marketRetentionDays by int(7) {
        name = TranslatableValue.literal("Market cache days")
        description = TranslatableValue.literal("How long recent market snapshots are kept locally. Daily price summaries and your trading history are kept separately")
        range = 1..31
    }
    var settingsSync by boolean(false) {
        name = TranslatableValue.literal("Sync preferences to my account")
        description = TranslatableValue.literal("Optional. Copies these settings and the HUD layout to your linked account. Trades, replays and webhooks stay on this device")
        condition = { false }
    }
    var wizardDone by boolean(false) { condition = { false } }
}
