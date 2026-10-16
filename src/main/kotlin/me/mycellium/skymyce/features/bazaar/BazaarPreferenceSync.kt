package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonObject
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.config.economy.BazaarConfig
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CompletableFuture

private val syncFlags = setOf("inGameAlerts", "discordAlerts", "dashboardAlerts", "inGameAlertSound", "dashboardAlertSound", "hudVisible")
private val syncNumbers = linkedMapOf(
    "minimumProfit" to 0..100_000_000, "minimumRoiBps" to 0..10_000, "minimumLiquidity" to 0..100_000_000,
    "maximumFillMinutes" to 0..10_080, "minimumConfidence" to 0..100, "maximumCapital" to 1..1_000_000_000,
    "concentration" to 1..100, "sellTaxBps" to 0..2500, "relistThreshold" to 1..100,
    "alertProfit" to 0..1_000_000_000, "alertCooldownSeconds" to 30..3600, "marketRetentionDays" to 1..31,
    "inGameAlertProfit" to 0..1_000_000_000, "discordAlertProfit" to 0..1_000_000_000,
    "dashboardAlertProfit" to 0..1_000_000_000, "webhookAlertProfit" to 0..1_000_000_000,
    "inGameAlertCooldown" to 30..3600, "discordAlertCooldown" to 30..3600,
    "dashboardAlertCooldown" to 30..3600, "webhookAlertCooldown" to 30..3600,
    "hudX" to -32768..32768, "hudY" to -32768..32768, "hudScalePercent" to 25..300,
)
private val syncAnchors = setOf("TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT")
private val syncKeys = syncNumbers.keys + syncFlags + setOf("risk", "timezone", "hudAnchor")

/** A strict version-one preference projection; local secrets, journal data and the opt-in flag cannot enter it. */
internal fun bazaarPreferenceSyncPayload(values: Map<String, String>): JsonObject = JsonObject().apply {
    require(values.keys.containsAll(syncKeys))
    for (key in syncKeys) {
        val value = requireNotNull(values[key])
        when (key) {
            in syncFlags -> addProperty(key, value.toBooleanStrict())
            in syncNumbers -> addProperty(key, value.toInt())
            else -> addProperty(key, value)
        }
    }
    bazaarPreferenceSyncValues(this)
}

/** Validate the whole remote object before changing any Resourceful Config value. */
internal fun bazaarPreferenceSyncValues(json: JsonObject): Map<String, String> {
    require(json.keySet() == syncKeys) { "Unsupported synchronized preferences" }
    return syncKeys.associateWith { key ->
        val value = json[key].also { require(it.isJsonPrimitive) }.asJsonPrimitive
        when (key) {
            in syncFlags -> { require(value.isBoolean); value.asBoolean.toString() }
            in syncNumbers -> {
                require(value.isNumber)
                value.asBigDecimal.intValueExact().also { require(it in requireNotNull(syncNumbers[key])) }.toString()
            }
            "risk" -> { require(value.isString); BazaarRisk.valueOf(value.asString.uppercase(Locale.ROOT)).name }
            "hudAnchor" -> { require(value.isString && value.asString in syncAnchors); value.asString }
            else -> { require(value.isString && value.asString.length <= 100 && value.asString in ZoneId.getAvailableZoneIds()); value.asString }
        }
    }
}

internal enum class BazaarPreferencePull { APPLY_REMOTE, KEEP_LOCAL, PAUSE, DISABLE }

internal fun bazaarPreferencePullDecision(captured: Map<String, String>, current: Map<String, String>,
    baseline: Map<String, String>?, knownRevision: Long?, remoteRevision: Long, remote: Map<String, String>?): BazaarPreferencePull = when {
    remote == null -> BazaarPreferencePull.DISABLE
    remote == current -> BazaarPreferencePull.APPLY_REMOTE
    captured != current -> if (baseline == remote && knownRevision == remoteRevision) BazaarPreferencePull.KEEP_LOCAL else BazaarPreferencePull.PAUSE
    baseline != null && captured != baseline && captured != remote ->
        if (knownRevision == remoteRevision) BazaarPreferencePull.KEEP_LOCAL else BazaarPreferencePull.PAUSE
    else -> BazaarPreferencePull.APPLY_REMOTE
}

/** One tick-driven coordinator; network callbacks are already marshalled to the client thread by BazaarCommunity. */
internal class BazaarPreferenceSync(private val community: BazaarCommunity, private val persist: () -> Unit,
    private val changed: () -> Unit) {
    var status: String = "Preference sync is off"; private set
    private data class Lease(val account: String, val uuid: String, val generation: Long)
    private var lease: Lease? = null
    private var intent = 0L
    private var busy = false
    private var paused = false
    private var revision: Long? = null
    private var baseline: Map<String, String>? = null
    private var noticed: Map<String, String> = emptyMap()
    private var changedAt = 0L
    private var readNeeded = true
    private var nextReadAt = 0L
    private var retryAt = 0L
    private var disableRequested = false
    private var explicit: CompletableFuture<BazaarUiResult>? = null

    private fun local() = bazaarPreferences().filterKeys { it in syncKeys }
    private fun currentLease(): Lease? = runCatching {
        if (!CommunityClient.connected) return null
        Lease(requireNotNull(CommunityClient.identity?.get("accountId")?.asString),
            requireNotNull(CommunityClient.identity?.get("uuid")?.asString), CommunityClient.sessionGeneration)
    }.getOrNull()
    private fun report(message: String) { if (status != message) { status = message; changed() } }
    private fun cancelExplicit() { explicit?.complete(BazaarUiResult("Preference request canceled; local values retained")); explicit = null }
    private fun finish(message: String) { report(message); explicit?.complete(BazaarUiResult(message)); explicit = null }

    fun tick(now: Long) {
        if (!community.loaded) return
        val current = currentLease()
        if (current != lease) {
            intent++; cancelExplicit(); readNeeded = true
            lease = current
        }
        if (disableRequested || !BazaarConfig.settingsSync && community.state.preferenceSyncAccount != null && community.state.preferenceDeleteAccount == null) {
            val owner = if (disableRequested) current?.account ?: community.state.preferenceSyncAccount else community.state.preferenceSyncAccount
            if (owner != null) community.pendingPreferenceDeletion(owner)
            disableRequested = false
        }
        if (community.state.preferenceDeleteAccount != null) {
            if (BazaarConfig.settingsSync) { BazaarConfig.settingsSync = false; persist(); intent++; cancelExplicit() }
            if (!busy && now >= retryAt) deleteRemote(current)
            return
        }
        if (!BazaarConfig.settingsSync || busy) return
        if (current == null) { report("Preference sync waits for your connected account; local edits stay private"); return }
        if (community.state.preferenceSyncAccount?.let { it != current.account } == true) {
            report("Preference sync paused for a different account; explicitly enable it for this account"); return
        }
        val values = local()
        if (values != noticed) { noticed = values; changedAt = now }
        if (now < retryAt) return
        if (readNeeded || now >= nextReadAt) {
            if (now >= nextReadAt) pull(current, now)
        } else if (!paused && revision != null && values != baseline && now - changedAt >= 1000) push(current, values, requireNotNull(revision), ++intent)
    }

    fun setEnabled(enabled: Boolean): CompletableFuture<BazaarUiResult> {
        if (!enabled) {
            intent++; cancelExplicit(); paused = false; BazaarConfig.settingsSync = false; persist()
            disableRequested = true; report("Preference sync disabled locally; server removal pending")
            tick(System.currentTimeMillis())
            return CompletableFuture.completedFuture(BazaarUiResult(status))
        }
        if (!community.loaded) return CompletableFuture.completedFuture(BazaarUiResult("Private preferences are still loading; local values retained"))
        if (busy || community.state.preferenceDeleteAccount != null) return CompletableFuture.completedFuture(BazaarUiResult("Finish the pending preference request or server-copy removal before enabling sync"))
        val owner = currentLease() ?: return CompletableFuture.completedFuture(BazaarUiResult("Connect Discord to enable optional preference sync"))
        val values = local()
        runCatching { bazaarPreferenceSyncPayload(values) }.getOrElse {
            return CompletableFuture.completedFuture(BazaarUiResult("Local preferences could not be validated; nothing uploaded"))
        }
        lease = owner; paused = false; val guard = ++intent
        val result = CompletableFuture<BazaarUiResult>(); explicit = result
        report("Reviewing the current preference revision before enabling sync")
        request(owner, guard, "GET", {}, { body ->
            if (local() != values) { paused = true; finish("Local preferences changed; review and enable again before uploading"); return@request }
            push(owner, values, responseRevision(body), guard, enabling = true)
        }, { finish("Preference sync could not be enabled; local values retained") })
        return result
    }

    private fun pull(owner: Lease, now: Long) {
        val captured = local(); val guard = ++intent; nextReadAt = now + 60000
        report("Checking synchronized Bazaar preferences")
        request(owner, guard, "GET", {}, { body ->
            if (!BazaarConfig.settingsSync) return@request
            val remoteRevision = responseRevision(body)
            val remote = body["preferences"].let { require(it != null); if (it.isJsonNull) null else bazaarPreferenceSyncValues(it.asJsonObject) }
            when (bazaarPreferencePullDecision(captured, local(), baseline, revision, remoteRevision, remote)) {
                BazaarPreferencePull.DISABLE -> {
                    BazaarConfig.settingsSync = false; persist(); revision = null; baseline = null; readNeeded = true
                    community.preferenceSyncAccount(null)
                    report("Preference sync was disabled remotely; local preferences retained")
                }
                BazaarPreferencePull.PAUSE -> { paused = true; report("Preferences changed locally and remotely; automatic sync paused for manual review") }
                BazaarPreferencePull.KEEP_LOCAL -> { revision = remoteRevision; readNeeded = false; paused = false; report("Local edits retained; waiting to synchronize them") }
                BazaarPreferencePull.APPLY_REMOTE -> {
                    val before = local(); val values = requireNotNull(remote)
                    try { changeBazaarPreferences(values) }
                    catch (problem: Exception) { changeBazaarPreferences(before); throw problem }
                    baseline = local(); noticed = requireNotNull(baseline); revision = remoteRevision; readNeeded = false; paused = false
                    community.preferenceSyncAccount(owner.account)
                    if (before != baseline) persist()
                    report("Bazaar preferences synchronized; private records remain local")
                }
            }
        }, { problem ->
            retryAt = System.currentTimeMillis() + 60000
            if (problem is IllegalArgumentException || problem is IllegalStateException) { paused = true; report("Remote preferences are invalid; local values retained and sync paused") }
            else report("Preference download unavailable; local values retained")
        })
    }

    private fun push(owner: Lease, values: Map<String, String>, expected: Long, guard: Long, enabling: Boolean = false) {
        val payload = runCatching { bazaarPreferenceSyncPayload(values) }.getOrElse {
            paused = true; finish("Preferences failed validation; automatic upload paused"); return
        }
        request(owner, guard, "POST", { body ->
            body.addProperty("syncEnabled", true); body.addProperty("revision", expected); body.add("preferences", payload)
        }, { body ->
            val received = responseRevision(body)
            require(received == expected + 1 && bazaarPreferenceSyncValues(body.getAsJsonObject("preferences")) == values)
            if (!enabling && !BazaarConfig.settingsSync) {
                community.pendingPreferenceDeletion(owner.account)
                report("Preference sync is off locally; acknowledged server copy queued for removal")
                return@request
            }
            revision = received; baseline = values; readNeeded = false; nextReadAt = System.currentTimeMillis() + 60000
            BazaarConfig.settingsSync = true; community.preferenceSyncAccount(owner.account); persist()
            finish("Only Bazaar preferences synchronized; journals, replays and webhooks stay local")
        }, { problem ->
            val code = generateSequence(problem) { it.cause }.filterIsInstance<CommunityFailure>().firstOrNull()?.code
            if (code == "invalid_settings_or_revision" || problem is IllegalArgumentException || problem is IllegalStateException) {
                paused = true; finish("Preferences changed remotely or failed validation; automatic writes paused for manual review")
            } else {
                retryAt = System.currentTimeMillis() + 60000; readNeeded = true
                finish(if (BazaarConfig.settingsSync) "Preference upload unavailable; retry will recheck remote consent"
                    else "Enable outcome unconfirmed; local sync remains off. Retry enable or disable to review the server copy")
            }
        })
    }

    private fun deleteRemote(current: Lease?) {
        val account = community.state.preferenceDeleteAccount ?: return
        if (current?.account != account) { report("Sync is off locally; reconnect the original account to remove its server copy"); return }
        val guard = ++intent
        request(current, guard, "POST", { it.addProperty("syncEnabled", false) }, {
            community.pendingPreferenceDeletion(null)
            if (community.state.preferenceSyncAccount == account) community.preferenceSyncAccount(null)
            revision = null; baseline = null; report("Preference sync disabled; server copy removed")
        }, {
            retryAt = System.currentTimeMillis() + 60000
            report("Sync is off locally; server removal will retry only for the same account")
        })
    }

    private fun responseRevision(body: JsonObject): Long {
        require(body["version"]?.asInt == 1)
        val value = requireNotNull(body["revision"]).asJsonPrimitive
        require(value.isNumber)
        return value.asBigDecimal.longValueExact().also { require(it in 0..9_007_199_254_740_991L) }
    }

    private fun request(owner: Lease, guard: Long, method: String, body: (JsonObject) -> Unit,
        success: (JsonObject) -> Unit, failure: (Throwable) -> Unit) {
        check(!busy); busy = true
        community.request("/community/bazaar/settings", body, method).whenComplete { response, problem ->
            busy = false
            if (guard != intent || currentLease() != owner) { if (guard == intent) cancelExplicit(); return@whenComplete }
            if (problem != null) failure(problem)
            else runCatching { success(requireNotNull(response)) }.onFailure { failure(IllegalArgumentException("Invalid synchronized preferences", it)) }
        }
    }
}
