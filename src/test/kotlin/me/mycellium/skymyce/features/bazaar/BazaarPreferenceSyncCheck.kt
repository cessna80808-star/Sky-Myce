package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonObject

private fun rejectedSync(block: () -> Unit) = check(runCatching(block).isFailure)

private fun syntheticSyncPreferences(): Map<String, String> = linkedMapOf(
    "risk" to "BALANCED", "timezone" to "Europe/Stockholm", "hudAnchor" to "BOTTOM_RIGHT",
    "minimumProfit" to "1000", "minimumRoiBps" to "50", "minimumLiquidity" to "1000",
    "maximumFillMinutes" to "0", "minimumConfidence" to "35", "maximumCapital" to "10000000",
    "concentration" to "40", "sellTaxBps" to "125", "relistThreshold" to "6", "alertProfit" to "100000",
    "alertCooldownSeconds" to "300", "marketRetentionDays" to "7", "inGameAlerts" to "true",
    "discordAlerts" to "false", "dashboardAlerts" to "true", "inGameAlertSound" to "false",
    "dashboardAlertSound" to "false", "hudVisible" to "true", "inGameAlertProfit" to "0",
    "discordAlertProfit" to "0", "dashboardAlertProfit" to "0", "webhookAlertProfit" to "0",
    "inGameAlertCooldown" to "300", "discordAlertCooldown" to "300", "dashboardAlertCooldown" to "300",
    "webhookAlertCooldown" to "300", "hudX" to "-32768", "hudY" to "32768", "hudScalePercent" to "300",
)

fun main() {
    val values = syntheticSyncPreferences()
    val payload = bazaarPreferenceSyncPayload(values + mapOf("settingsSync" to "true", "webhookURL" to "private-token",
        "webhookEnabled" to "true", "history" to "private-history"))
    check(bazaarPreferenceSyncValues(payload) == values)
    check("private-token" !in payload.toString() && "private-history" !in payload.toString())
    listOf("settingsSync", "webhookURL", "webhookEnabled", "history").forEach { check(!payload.has(it)) }
    check(payload["hudX"].asJsonPrimitive.isNumber && payload["hudVisible"].asJsonPrimitive.isBoolean)
    check(payload["risk"].asJsonPrimitive.isString && payload["timezone"].asJsonPrimitive.isString)
    rejectedSync { bazaarPreferenceSyncPayload(values - "risk") }
    for (change in listOf<(JsonObject) -> Unit>(
        { it.addProperty("hudX", -32769) }, { it.addProperty("hudY", 32769) },
        { it.addProperty("hudScalePercent", 24) }, { it.addProperty("hudScalePercent", 301) },
        { it.addProperty("hudAnchor", "FLOATING") }, { it.addProperty("risk", "UNKNOWN") },
        { it.addProperty("discordAlertCooldown", 29) }, { it.addProperty("webhookAlertProfit", -1) },
        { it.addProperty("inGameAlertSound", "true") }, { it.addProperty("minimumProfit", "1000") },
        { it.addProperty("minimumProfit", 1.5) }, { it.addProperty("minimumProfit", Long.MAX_VALUE) },
        { it.addProperty("minimumConfidence", 101) }, { it.addProperty("timezone", "Not/AZone") },
        { it.addProperty("webhookURL", "secret") }, { it.remove("hudVisible") },
    )) {
        val invalid = payload.deepCopy().also(change)
        val before = invalid.deepCopy()
        rejectedSync { bazaarPreferenceSyncValues(invalid) }
        check(invalid == before) { "Validation mutated a partially invalid remote object" }
    }
    val minimumHud = bazaarPreferenceSyncPayload(values + mapOf("hudX" to "32768", "hudY" to "-32768", "hudScalePercent" to "25"))
    check(bazaarPreferenceSyncValues(minimumHud)["hudScalePercent"] == "25")
    check(bazaarPreferenceSyncValues(payload.deepCopy().apply { addProperty("risk", "balanced") })["risk"] == "BALANCED")

    val original = mapOf("profile" to "original")
    val edited = mapOf("profile" to "local edit")
    val remote = mapOf("profile" to "remote edit")
    check(bazaarPreferencePullDecision(original, original, null, null, 3, remote) == BazaarPreferencePull.APPLY_REMOTE)
    check(bazaarPreferencePullDecision(original, original, original, 2, 3, remote) == BazaarPreferencePull.APPLY_REMOTE)
    check(bazaarPreferencePullDecision(edited, edited, original, 2, 2, original) == BazaarPreferencePull.KEEP_LOCAL)
    check(bazaarPreferencePullDecision(edited, edited, original, 2, 3, remote) == BazaarPreferencePull.PAUSE)
    check(bazaarPreferencePullDecision(original, edited, original, 2, 2, original) == BazaarPreferencePull.KEEP_LOCAL)
    check(bazaarPreferencePullDecision(original, edited, original, 2, 3, remote) == BazaarPreferencePull.PAUSE)
    check(bazaarPreferencePullDecision(original, edited, null, null, 3, remote) == BazaarPreferencePull.PAUSE)
    check(bazaarPreferencePullDecision(edited, edited, original, 2, 3, edited) == BazaarPreferencePull.APPLY_REMOTE)
    for (local in listOf(original, edited)) for (version in listOf(0L, 4L)) {
        check(bazaarPreferencePullDecision(original, local, original, 2, version, null) == BazaarPreferencePull.DISABLE) {
            "An absent remote copy must never become an automatic upload, even when local edits are waiting"
        }
    }
    println("Bazaar preference sync checks passed: typed allowlist, channel/HUD bounds, private-field exclusion, atomic validation, concurrent edits, revision conflicts and remote opt-out")
}
