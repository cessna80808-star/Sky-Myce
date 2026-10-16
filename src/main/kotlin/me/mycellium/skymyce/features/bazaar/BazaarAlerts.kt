package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.config.economy.BazaarConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** A bounded local delivery gate; each channel has its own threshold, cooldown and duplicate state. */
internal class BazaarAlertGate {
    private val channels = hashMapOf<String, Long>()
    private val opportunities = linkedMapOf<String, Long>()

    fun allows(channel: String, fingerprint: String, now: Long, cooldownSeconds: Int, profit: Double, minimum: Double): Boolean {
        require(channel in setOf("game", "dashboard", "discord", "webhook", "webhook-report") && cooldownSeconds in 30..3600)
        if (!profit.isFinite() || !minimum.isFinite() || profit < minimum) return false
        val cooldown = cooldownSeconds * 1000L
        return channels[channel]?.let { now - it >= cooldown } != false &&
            opportunities["$channel/$fingerprint"]?.let { now - it >= cooldown } != false
    }

    fun delivered(channel: String, fingerprint: String, now: Long) {
        channels[channel] = now
        opportunities["$channel/$fingerprint"] = now
        while (opportunities.size > 128) opportunities.remove(opportunities.keys.first())
    }
}

/**
 * What an alert needs to know about the live session. Every channel re-checks [current] (same session, live
 * data) right before sending; the webhook also re-checks that it is still enabled with the same URL.
 */
internal data class BazaarAlertContext(val session: BazaarSession, val purse: Double?, val freshness: BazaarFreshness,
                                       val webhook: String?, val current: () -> Boolean, val webhookCurrent: (String) -> Boolean)

/**
 * Exceptional-flip notifications. Nothing is persisted or sent to a public channel, and every channel stops the
 * moment the flipping session ends or market data stops being live.
 */
internal class BazaarAlerts(private val webhook: BazaarWebhook = BazaarWebhook()) {
    private val gate = BazaarAlertGate()
    /** The latest dashboard alert; the dashboard shows each [serial] once. */
    var latest: String? = null
        private set
    var serial = 0L
        private set

    fun clear() { latest = null }

    fun consider(ranked: List<BazaarOpportunity>, context: BazaarAlertContext) {
        val best = ranked.firstOrNull() ?: return
        if (!exceptionalBazaarOpportunity(best, ranked, context.purse, true, context.freshness, BazaarConfig.alertProfit.toDouble())) return
        val now = System.currentTimeMillis()
        val key = "${best.product}:${best.buyPrice}:${best.sellPrice}"
        fun allows(channel: String, minimum: Int, cooldown: Int) = gate.allows(channel, key, now,
            maxOf(cooldown, BazaarConfig.alertCooldownSeconds), best.netProfit, maxOf(minimum, BazaarConfig.alertProfit).toDouble())
        val name = BazaarNames.name(best.product)
        val text = "Bazaar: $name could make ~${bazaarCompactCoins(best.netProfit)} (${percent(best.roi)} return). Estimate only; trade manually."

        if (BazaarConfig.inGameAlerts && allows("game", BazaarConfig.inGameAlertProfit, BazaarConfig.inGameAlertCooldown)) {
            MC.instance.gui.setOverlayMessage(Component.literal(text), false)
            if (BazaarConfig.inGameAlertSound) MC.instance.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f))
            gate.delivered("game", key, now)
        }
        if (BazaarConfig.dashboardAlerts && BazaarDashboard.running() && allows("dashboard", BazaarConfig.dashboardAlertProfit, BazaarConfig.dashboardAlertCooldown)) {
            latest = text; serial++
            gate.delivered("dashboard", key, now)
        }
        if (BazaarConfig.discordAlerts && allows("discord", BazaarConfig.discordAlertProfit, BazaarConfig.discordAlertCooldown)) {
            val fields = mapOf("id" to bazaarRandomId(), "epoch" to context.session.id, "product" to best.product, "netProfit" to best.netProfit,
                "roi" to best.roi, "confidence" to best.confidence, "capital" to best.capital, "availableCapital" to (context.purse ?: 0),
                "observedAt" to best.observedAt, "reason" to "High relative ranking, confidence and liquidity; fees estimated")
            if (DungeonFriendRelay.bazaarPacket("bazaar_alert", fields) { context.current() && BazaarConfig.discordAlerts }) gate.delivered("discord", key, now)
        }
        val url = context.webhook
        if (url != null && allows("webhook", BazaarConfig.webhookAlertProfit, BazaarConfig.webhookAlertCooldown)) {
            gate.delivered("webhook", key, now)
            Scheduling.schedule(0.seconds) { runCatching { webhook.send(url, text) { context.current() && context.webhookCurrent(url) } } }
        }
    }

    /** A manual, confirmed session report; it has its own cooldown and never resets exceptional-alert cooldowns. */
    fun sendReport(url: String, sessionId: String, message: String, current: () -> Boolean, done: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        if (!gate.allows("webhook-report", sessionId, now, 30, 0.0, 0.0)) { done(false); return }
        gate.delivered("webhook-report", sessionId, now)
        Scheduling.schedule(0.seconds) {
            val sent = runCatching { webhook.send(url, message, current) }.getOrDefault(false)
            MC.instance.execute { done(sent) }
        }
    }

    private fun percent(value: Double) = String.format(Locale.ROOT, "%.1f%%", value * 100)
}
