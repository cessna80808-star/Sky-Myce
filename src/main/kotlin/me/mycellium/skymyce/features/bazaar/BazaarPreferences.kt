package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.config.economy.BazaarConfig as C
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.WidgetConfig
import me.mycellium.skymyce.hud.widget.WidgetManager
import java.net.URI
import java.time.ZoneId
import kotlin.math.roundToInt

internal fun bazaarPreferences(): Map<String, String> = linkedMapOf(
    "risk" to C.risk.name, "minimumProfit" to C.minimumProfit.toString(), "minimumRoiBps" to C.minimumRoiBps.toString(),
    "minimumLiquidity" to C.minimumLiquidity.toString(), "maximumFillMinutes" to C.maximumFillMinutes.toString(),
    "minimumConfidence" to C.minimumConfidence.toString(), "maximumCapital" to C.maximumCapital.toString(),
    "concentration" to C.concentration.toString(), "sellTaxBps" to C.sellTaxBps.toString(), "relistThreshold" to C.relistThreshold.toString(),
    "inGameAlerts" to C.inGameAlerts.toString(), "discordAlerts" to C.discordAlerts.toString(), "dashboardAlerts" to C.dashboardAlerts.toString(),
    "alertProfit" to C.alertProfit.toString(), "alertCooldownSeconds" to C.alertCooldownSeconds.toString(),
    "inGameAlertProfit" to C.inGameAlertProfit.toString(), "discordAlertProfit" to C.discordAlertProfit.toString(),
    "dashboardAlertProfit" to C.dashboardAlertProfit.toString(), "webhookAlertProfit" to C.webhookAlertProfit.toString(),
    "inGameAlertCooldown" to C.inGameAlertCooldown.toString(), "discordAlertCooldown" to C.discordAlertCooldown.toString(),
    "dashboardAlertCooldown" to C.dashboardAlertCooldown.toString(), "webhookAlertCooldown" to C.webhookAlertCooldown.toString(),
    "inGameAlertSound" to C.inGameAlertSound.toString(), "dashboardAlertSound" to C.dashboardAlertSound.toString(),
    "marketRetentionDays" to C.marketRetentionDays.toString(), "timezone" to C.timezone, "settingsSync" to C.settingsSync.toString(),
) + bazaarHudPreferences(BazaarHud.widget.toConfig())

internal fun bazaarHudPreferences(layout: WidgetConfig): Map<String, String> = linkedMapOf(
    "hudX" to layout.x.toString(), "hudY" to layout.y.toString(), "hudScalePercent" to (layout.scale * 100).roundToInt().toString(),
    "hudAnchor" to layout.anchor.name, "hudVisible" to layout.visible.toString(),
)

internal fun changeBazaarPreference(key: String, value: String, saveHud: Boolean = true): Boolean {
    fun number(range: IntRange) = requireNotNull(value.toIntOrNull()).also { require(it in range) }
    fun flag() = requireNotNull(value.toBooleanStrictOrNull())
    if (key in setOf("hudX", "hudY", "hudScalePercent", "hudAnchor", "hudVisible")) {
        val widget = BazaarHud.widget
        val before = widget.toConfig()
        when (key) {
            "hudX" -> widget.x = number(-32768..32768)
            "hudY" -> widget.y = number(-32768..32768)
            "hudScalePercent" -> widget.scale = number(25..300) / 100f
            "hudAnchor" -> widget.anchor = Anchor.valueOf(value)
            "hudVisible" -> widget.visible = flag()
        }
        val changed = before != widget.toConfig()
        if (changed && saveHud) WidgetManager.save()
        return changed
    }
    when (key) {
        "risk" -> C.risk = BazaarRisk.valueOf(value)
        "minimumProfit" -> C.minimumProfit = number(0..100000000)
        "minimumRoiBps" -> C.minimumRoiBps = number(0..10000)
        "minimumLiquidity" -> C.minimumLiquidity = number(0..100000000)
        "maximumFillMinutes" -> C.maximumFillMinutes = number(0..10080)
        "minimumConfidence" -> C.minimumConfidence = number(0..100)
        "maximumCapital" -> C.maximumCapital = number(1..1000000000)
        "concentration" -> C.concentration = number(1..100)
        "sellTaxBps" -> C.sellTaxBps = number(0..2500)
        "relistThreshold" -> C.relistThreshold = number(1..100)
        "inGameAlerts" -> C.inGameAlerts = flag()
        "discordAlerts" -> C.discordAlerts = flag()
        "dashboardAlerts" -> C.dashboardAlerts = flag()
        "alertProfit" -> C.alertProfit = number(0..1000000000)
        "alertCooldownSeconds" -> C.alertCooldownSeconds = number(30..3600)
        "inGameAlertProfit" -> C.inGameAlertProfit = number(0..1000000000)
        "discordAlertProfit" -> C.discordAlertProfit = number(0..1000000000)
        "dashboardAlertProfit" -> C.dashboardAlertProfit = number(0..1000000000)
        "webhookAlertProfit" -> C.webhookAlertProfit = number(0..1000000000)
        "inGameAlertCooldown" -> C.inGameAlertCooldown = number(30..3600)
        "discordAlertCooldown" -> C.discordAlertCooldown = number(30..3600)
        "dashboardAlertCooldown" -> C.dashboardAlertCooldown = number(30..3600)
        "webhookAlertCooldown" -> C.webhookAlertCooldown = number(30..3600)
        "inGameAlertSound" -> C.inGameAlertSound = flag()
        "dashboardAlertSound" -> C.dashboardAlertSound = flag()
        "marketRetentionDays" -> C.marketRetentionDays = number(1..31)
        "timezone" -> { require(value.length <= 100 && value in ZoneId.getAvailableZoneIds()); C.timezone = value }
        else -> error("Unsupported Bazaar preference")
    }
    return false
}

/** Remote preferences are prevalidated; coalesce all real HUD edits into one house-layout save. */
internal fun changeBazaarPreferences(values: Map<String, String>) {
    var changedHud = false
    values.forEach { (key, value) -> if (changeBazaarPreference(key, value, saveHud = false)) changedHud = true }
    if (changedHud) WidgetManager.save()
}

internal fun bazaarFilters() = BazaarFilters(C.minimumProfit.toDouble(), C.minimumRoiBps / 10000.0, C.minimumLiquidity.toDouble(),
    C.maximumFillMinutes.takeIf { it > 0 }?.times(60.0), C.minimumConfidence / 100.0, C.maximumCapital.toDouble(), C.concentration / 100.0)
internal fun bazaarFees() = BazaarFees(C.sellTaxBps / 10000.0)

internal fun validBazaarWebhook(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host in setOf("discord.com", "discordapp.com") && uri.port == -1 && uri.userInfo == null &&
        uri.fragment == null && uri.rawQuery == null && uri.path.matches(Regex("/api(?:/v10)?/webhooks/[0-9]{15,22}/[A-Za-z0-9._-]{30,200}"))
}.getOrDefault(false)
