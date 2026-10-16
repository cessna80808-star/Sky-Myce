package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** What a published replay may contain. Hidden parts are left out of the upload itself. */
data class BazaarReplayOptions(val detail: Boolean = false, val hideItems: Boolean = true, val hideTransactions: Boolean = true,
                               val hideValues: Boolean = false, val hideIdentity: Boolean = true)

internal fun bazaarFinalRealization(ledger: BazaarLedger, record: BazaarRealization) =
    ledger.positions.any { it.id == record.positionId && it.stage == BazaarStage.CLOSED && it.acquired > 0 } &&
        ledger.realizations.lastOrNull { it.positionId == record.positionId }?.id == record.id

/** Add only new observed holding time; partial realizations must not multiply the same position's hours. */
internal fun bazaarDurationContributions(ledger: BazaarLedger): Map<String, Long> {
    val durations = hashMapOf<String, Long>()
    return ledger.realizations.associate { record ->
        val previous = durations[record.positionId] ?: 0L
        val current = maxOf(previous, record.durationMillis ?: 0L)
        durations[record.positionId] = current
        record.id to (current - previous)
    }
}

/** The outbound DTO itself is redacted; neither raw journal events nor hidden exact values are uploaded. */
internal fun bazaarPublicationPayload(archive: BazaarArchive, sessionId: String, options: BazaarReplayOptions, listed: Boolean): JsonObject {
    val session = requireNotNull(archive.sessions.find { it.id == sessionId })
    val ended = requireNotNull(session.endedAt)
    val duration = (ended - session.startedAt).coerceAtLeast(1)
    require(duration <= 86_400_000) { "A public replay supports one day; the full private session is retained" }
    val ledger = reconcileBazaar(archive, session.context)
    val records = ledger.realizations.filter { it.sessionId == sessionId }
    return JsonObject().apply {
        addProperty("mode", if (options.detail) "full" else "summary")
        addProperty("visibility", if (listed) "community" else "unlisted")
        addProperty("durationMs", duration)
        addProperty("completedFlips", ledger.positions.count { it.sessionId == sessionId && it.stage == BazaarStage.CLOSED && it.acquired > 0 })
        addProperty("realizedProfit", if (options.hideValues) 0 else records.sumOf { it.netProfit ?: 0.0 })
        addProperty("cost", if (options.hideValues) 0 else records.sumOf { it.cost ?: 0.0 })
        add("redact", JsonObject().apply {
            addProperty("items", options.hideItems); addProperty("transactions", options.hideTransactions)
            addProperty("values", options.hideValues); addProperty("identity", options.hideIdentity)
        })
        add("progress", JsonArray().apply {
            if (!options.hideValues) {
                val buckets = records.groupBy { (((it.at - session.startedAt).coerceIn(0, duration) * 32) / duration).toInt().coerceAtMost(31) }
                for ((bucket, values) in buckets.toSortedMap()) add(JsonObject().apply {
                    addProperty("at", (duration * (bucket + 1) / 32).coerceAtMost(duration))
                    addProperty("profit", values.sumOf { it.netProfit ?: 0.0 })
                })
            }
        })
        add("timeline", JsonArray().apply {
            if (options.detail && !options.hideTransactions) records.takeLast(256).forEach { row -> add(JsonObject().apply {
                addProperty("at", (row.at - session.startedAt).coerceIn(0, duration))
                if (!options.hideItems) addProperty("item", row.product)
                if (!options.hideValues) {
                    row.netProfit?.let { addProperty("profit", it) }; addProperty("quantity", row.quantity)
                    row.fees?.let { addProperty("fees", it) }
                    row.cost?.takeIf { row.quantity > 0 }?.let { addProperty("buyPrice", it / row.quantity) }
                    row.gross?.takeIf { row.quantity > 0 }?.let { addProperty("sellPrice", it / row.quantity) }
                }
            }) }
        })
    }
}
