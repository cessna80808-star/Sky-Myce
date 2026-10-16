package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlin.math.abs

/** Numeric summaries have no path for item names, identities, order IDs or private journal details. */
internal fun bazaarWebhookReport(durationMs: Long, realizedProfit: Double, completedFlips: Int): String {
    require(durationMs in 1..31 * 86400000L && realizedProfit.isFinite() && abs(realizedProfit) <= 1e15 && completedFlips in 0..10000)
    val duration = "${durationMs / 3600000}h ${durationMs / 60000 % 60}m ${durationMs / 1000 % 60}s"
    return "Sky-Myce Bazaar · private session report\nDuration: $duration\nRealized net profit: ${String.format(Locale.ROOT, "%,.2f", realizedProfit)} coins\nCompleted flips: $completedFlips\nClient-observed personal summary. No transaction details included."
}

/** The URL never leaves this direct HTTPS request, local settings, or the user's explicit configuration field. */
internal class BazaarWebhook {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun send(url: String, message: String, current: () -> Boolean): Boolean {
        require(validBazaarWebhook(url) && message.length <= 1800)
        val json = JsonObject().apply {
            addProperty("content", message)
            add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
        }
        if (!current()) return false
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().close()
        return response.statusCode() in 200..299
    }
}
