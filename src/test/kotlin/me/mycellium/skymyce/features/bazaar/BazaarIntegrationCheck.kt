package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.math.abs
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

private const val INTEGRATION_NOW = 1_800_000_000_000L
private val integrationContext = BazaarContext("abcdefab-cdef-abcd-efab-cdefabcdefab", "synthetic-profile")
private fun nearIntegration(actual: Double?, expected: Double) = check(actual != null && abs(actual - expected) < .000001) { "$actual != $expected" }
private fun rejectIntegration(action: () -> Unit) = check(runCatching(action).isFailure)
private fun integrationEvent(id: String, kind: BazaarEventKind, quantity: Long, coins: Double?, at: Long,
    price: Double? = null, orderId: String? = null, cumulative: Long? = null) =
    BazaarEvent(id, integrationContext, "WHEAT", kind, at, quantity, price, coins, orderId = orderId,
        positionId = "position-integration", sessionId = "session-integration", cumulativeFilled = cumulative)

fun main() {
    checkBazaarProviderIntegration()
    checkBazaarLiveMarketBody()
    checkBazaarElectionContext()
    checkBazaarProviderRetry()
    checkBazaarOutboundReplay()
    checkBazaarCompletion()
    checkBazaarAlertAndWebhook()
    checkBazaarAlertGate()
    checkBazaarGuideCommand()
    checkBazaarCorrections()
    checkBazaarManualOnlyBytecode()
    println("Bazaar integration checks passed: raw bid/ask semantics, stale/invalid/depth handling, complete replay totals/redaction/bucket timing, final-position completion, alerts, webhook boundaries and no gameplay execution")
}

private fun rawProduct(id: String) = """{"product_id":"$id","sell_summary":[{"pricePerUnit":4.2,"amount":100},{"pricePerUnit":3.8,"amount":500}],"buy_summary":[{"pricePerUnit":4.8,"amount":300},{"pricePerUnit":5.0,"amount":700}],"quick_status":{"buyMovingWeek":999999,"sellMovingWeek":1111111,"buyOrders":7,"sellOrders":11}}"""
private fun rawMarket() = JsonParser.parseString("""{"success":true,"lastUpdated":$INTEGRATION_NOW,"products":{"WHEAT":${rawProduct("WHEAT")},"DIAMOND":${rawProduct("DIAMOND")},"UNUSUAL_SHARD":${rawProduct("UNUSUAL_SHARD")}}}""").asJsonObject

private fun checkBazaarLiveMarketBody() {
    // Unmodified public Hypixel response captured 2026-10-16; compressed only to keep the fixture small.
    val resource = requireNotNull(object {}.javaClass.getResourceAsStream("/bazaar/hypixel-bazaar-2026-10-16.json.gz"))
    var closed = false
    val json = readBazaarMarketBody(object : FilterInputStream(GZIPInputStream(resource)) {
        override fun close() { closed = true; super.close() }
    })
    check(closed && json.getAsJsonObject("products").size() == 2201)
    val now = json["lastUpdated"].asLong + 15_000
    val parsed = parseBazaarMarket(json, now)
    check(parsed.products.size == 2201 && bazaarFreshness(parsed, now) == BazaarFreshness.FRESH)
    check(parsed.products.any { it.id == "WHEAT" && it.buyOrderPrice != null && it.sellOfferPrice != null })
    val filters = BazaarFilters(0.0, 0.0, 0.0, minConfidence = 0.0, maxCapital = 10_000.0, maxConcentration = 1.0)
    check(scanBazaar(parsed, 10_000.0, filters = filters, now = now).isNotEmpty())
    fun rejectBody(input: InputStream) {
        var released = false
        val tracked = object : FilterInputStream(input) { override fun close() { released = true; super.close() } }
        rejectIntegration { readBazaarMarketBody(tracked) }
        check(released) { "A rejected Bazaar body leaked its response stream" }
    }
    rejectBody(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1) { ' '.code.toByte() }))
    rejectBody(ByteArrayInputStream("not JSON".toByteArray()))
    rejectBody(ByteArrayInputStream("[]".toByteArray()))
    rejectBody(object : InputStream() { override fun read(): Int = throw java.io.IOException("Synthetic body failure") })
}

private fun checkBazaarElectionContext() {
    val observedAt = INTEGRATION_NOW - 30_000
    val fresh = JsonParser.parseString("""{"success":true,"lastUpdated":$observedAt,"mayor":{"name":"Diana","perks":[{"name":"Mythological Ritual"}],"minister":{"name":"Paul"}}}""").asJsonObject
    val parsed = requireNotNull(parseBazaarElection(fresh, INTEGRATION_NOW))
    check(parsed.observedAt == observedAt && parsed.observedAt != INTEGRATION_NOW) { "Retrieving cached election data must not invent a fresh observation" }
    check(parsed.context == "Diana · Mythological Ritual · Minister: Paul")
    check(parseBazaarElection(fresh.deepCopy().apply { addProperty("lastUpdated", INTEGRATION_NOW - 3_600_000) }, INTEGRATION_NOW) != null)
    for (invalid in listOf(fresh.deepCopy().apply { remove("lastUpdated") },
        fresh.deepCopy().apply { addProperty("lastUpdated", INTEGRATION_NOW - 3_600_001) },
        fresh.deepCopy().apply { addProperty("lastUpdated", INTEGRATION_NOW + 1) },
        fresh.deepCopy().apply { addProperty("lastUpdated", 0) },
        fresh.deepCopy().apply { addProperty("lastUpdated", observedAt + .5) },
        fresh.deepCopy().apply { addProperty("lastUpdated", observedAt.toString()) },
        fresh.deepCopy().apply { addProperty("success", false) }, fresh.deepCopy().apply { remove("mayor") })) {
        check(parseBazaarElection(invalid, INTEGRATION_NOW) == null) { "Malformed, future or stale election data must remain unknown" }
    }
    check(parseBazaarElection(fresh, INTEGRATION_NOW + 3_600_000) == null) { "A once-valid context still expires by provider age" }
}

private fun checkBazaarProviderRetry() {
    check(parseBazaarRetryAt("900", INTEGRATION_NOW) == INTEGRATION_NOW + 900_000)
    val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(INTEGRATION_NOW + 900_000).atZone(java.time.ZoneOffset.UTC))
    check(parseBazaarRetryAt(date, INTEGRATION_NOW) == INTEGRATION_NOW + 900_000)
    check(parseBazaarRetryAt("  900  ", INTEGRATION_NOW) == INTEGRATION_NOW + 900_000)
    for (invalid in listOf(null, "", "not a retry deadline", "-1", "+1", "1.5", "Thu, 1 Jan 1970 00:00:00 GMT"))
        check(parseBazaarRetryAt(invalid, INTEGRATION_NOW) == null)
    check(parseBazaarRetryAt("999999999999999999999999999999999999", INTEGRATION_NOW) == Long.MAX_VALUE)
    check(parseBazaarRetryAt("9223372036854775807", INTEGRATION_NOW) == Long.MAX_VALUE)
    check(parseBazaarRetryAt("2", Long.MAX_VALUE - 1000) == Long.MAX_VALUE)
    check(parseBazaarRetryAt("0", INTEGRATION_NOW) == INTEGRATION_NOW)
    check(BazaarMarketFailure(true, INTEGRATION_NOW + 900_000).retryAt == INTEGRATION_NOW + 900_000)
}

private fun checkBazaarProviderIntegration() {
    val parsed = parseBazaarMarket(rawMarket(), INTEGRATION_NOW)
    check(parsed.products.map { it.id }.toSet() == setOf("WHEAT", "DIAMOND", "UNUSUAL_SHARD"))
    val wheat = parsed.products.first { it.id == "WHEAT" }
    nearIntegration(wheat.buyOrderPrice, 4.2); nearIntegration(wheat.sellOfferPrice, 4.8)
    nearIntegration(wheat.buyVolume, 600.0); nearIntegration(wheat.sellVolume, 1000.0)
    nearIntegration(wheat.buyMovingWeek, 1111111.0); nearIntegration(wheat.sellMovingWeek, 999999.0)
    check(wheat.buyOrders == 11 && wheat.sellOrders == 7)
    val filters = BazaarFilters(0.0, 0.0, 0.0, minConfidence = 0.0, maxCapital = 1000.0, maxConcentration = 1.0)
    val candidates = scanBazaar(parsed, 1000.0, filters = filters, now = INTEGRATION_NOW)
    check(candidates.map { it.product }.toSet() == parsed.products.map { it.id }.toSet())
    check(candidates.all { it.capital <= 1000.0 && it.buyPrice < it.sellPrice })
    check(scanBazaar(parsed, null, filters = filters, now = INTEGRATION_NOW).isEmpty())
    check(scanBazaar(parsed, 0.0, filters = filters, now = INTEGRATION_NOW).isEmpty())
    val stale = parseBazaarMarket(rawMarket().apply { addProperty("lastUpdated", INTEGRATION_NOW - 300001) }, INTEGRATION_NOW)
    check(bazaarFreshness(stale, INTEGRATION_NOW) == BazaarFreshness.STALE && scanBazaar(stale, 1000.0, filters = filters, now = INTEGRATION_NOW).isEmpty())
    rejectIntegration { parseBazaarMarket(rawMarket().apply { addProperty("lastUpdated", INTEGRATION_NOW + 10001) }, INTEGRATION_NOW) }
    rejectIntegration { parseBazaarMarket(rawMarket().apply { addProperty("success", false) }, INTEGRATION_NOW) }
    rejectIntegration { parseBazaarMarket(rawMarket().apply {
        getAsJsonObject("products").getAsJsonObject("WHEAT").getAsJsonArray("sell_summary")[0].asJsonObject.addProperty("amount", -1)
    }, INTEGRATION_NOW) }
    rejectIntegration { parseBazaarMarket(rawMarket().apply {
        getAsJsonObject("products").getAsJsonObject("WHEAT").addProperty("product_id", "DIAMOND")
    }, INTEGRATION_NOW) }
    val missing = parseBazaarMarket(rawMarket().apply { getAsJsonObject("products").getAsJsonObject("WHEAT").remove("sell_summary") }, INTEGRATION_NOW)
    check(missing.products.first { it.id == "WHEAT" }.buyOrderPrice == null)
    check(scanBazaar(missing, 1000.0, filters = filters, now = INTEGRATION_NOW).none { it.product == "WHEAT" })
    val normalized = JsonParser.parseString("""{"ok":true,"version":1,"source":"Synthetic shared source","observedAt":$INTEGRATION_NOW,"retrievedAt":$INTEGRATION_NOW,"products":[{"id":"WHEAT","buyPrice":4.2,"sellPrice":4.8,"buyDepth":[{"price":4.2,"amount":1000}],"sellDepth":[{"price":4.8,"amount":1000}],"buyMovingWeek":1111111,"sellMovingWeek":999999,"buyOrders":11,"sellOrders":7}]}""").asJsonObject
    nearIntegration(parseBazaarMarket(normalized, INTEGRATION_NOW, normalized = true).products.single().buyOrderPrice, 4.2)
    rejectIntegration { parseBazaarMarket(normalized.deepCopy().apply { getAsJsonArray("products").add(getAsJsonArray("products")[0].deepCopy()) }, INTEGRATION_NOW, normalized = true) }
    rejectIntegration { parseBazaarMarket(normalized.deepCopy().apply { addProperty("version", 2) }, INTEGRATION_NOW, normalized = true) }
}

private fun replayArchive(): BazaarArchive {
    val start = INTEGRATION_NOW - 60_000
    var archive = BazaarArchive(sessions = listOf(BazaarSession("session-integration", integrationContext, start, INTEGRATION_NOW,
        "synthetic-island")))
    archive = recordBazaarEvent(archive, integrationEvent("buy", BazaarEventKind.BUY_FILL, 300, 300.0, start + 1, price = 1.0))
    repeat(300) { index -> archive = recordBazaarEvent(archive, integrationEvent("sale-$index", BazaarEventKind.SELL_FILL,
        1, 2.0, start + 1000 + index * 100, price = 2.1)) }
    return archive
}

private fun checkBazaarOutboundReplay() {
    val archive = replayArchive()
    val full = bazaarPublicationPayload(archive, "session-integration", BazaarReplayOptions(true, false, false, false, false), listed = false)
    nearIntegration(full["realizedProfit"].asDouble, 300.0)
    nearIntegration(full["cost"].asDouble, 300.0)
    check(full.getAsJsonArray("timeline").size() == 256 && full["completedFlips"].asInt == 1)
    val summary = bazaarPublicationPayload(archive, "session-integration", BazaarReplayOptions(false, false, false), listed = false)
    check(summary["mode"].asString == "summary" && summary.getAsJsonArray("timeline").isEmpty)
    check(summary.getAsJsonArray("progress").size() in 1..32)
    nearIntegration(summary.getAsJsonArray("progress").sumOf { it.asJsonObject["profit"].asDouble }, 300.0)
    val ledger = reconcileBazaar(archive, integrationContext)
    val session = archive.sessions.single()
    val duration = checkNotNull(session.endedAt) - session.startedAt
    val recordsByBucket = ledger.realizations.groupBy { (((it.at - session.startedAt) * 32) / duration).toInt().coerceAtMost(31) }
    summary.getAsJsonArray("progress").forEach { value ->
        val point = value.asJsonObject
        val at = point["at"].asLong
        val bucket = ((at * 32 / duration) - 1).toInt()
        val values = requireNotNull(recordsByBucket[bucket])
        check(at >= values.maxOf { it.at - session.startedAt }) { "Replay profit appeared before its observed sale" }
    }
    for (flags in 0..31) {
        val options = BazaarReplayOptions(detail = flags and 1 != 0, hideItems = flags and 2 != 0,
            hideTransactions = flags and 4 != 0, hideValues = flags and 8 != 0, hideIdentity = flags and 16 != 0)
        val payload = bazaarPublicationPayload(archive, "session-integration", options, listed = false)
        val body = Gson().toJson(payload)
        check(payload["visibility"].asString == "unlisted")
        listOf(integrationContext.account, integrationContext.profile, "position-integration", "session-integration", "sale-", "sourceEventId", "fingerprint").forEach { check(it !in body) }
        check(payload.getAsJsonObject("redact")["identity"].asBoolean == options.hideIdentity)
        if (options.hideItems) check("WHEAT" !in body)
        if (options.hideTransactions || !options.detail) check(payload.getAsJsonArray("timeline").isEmpty)
        if (options.hideValues) {
            nearIntegration(payload["realizedProfit"].asDouble, 0.0); nearIntegration(payload["cost"].asDouble, 0.0)
            check(payload.getAsJsonArray("progress").isEmpty)
            payload.getAsJsonArray("timeline").forEach { row ->
                listOf("profit", "quantity", "fees", "buyPrice", "sellPrice").forEach { check(!row.asJsonObject.has(it)) }
            }
        }
    }
    check(bazaarPublicationPayload(archive, "session-integration", BazaarReplayOptions(), listed = true)["visibility"].asString == "community")
}

private fun checkBazaarCompletion() {
    val full = reconcileBazaar(replayArchive(), integrationContext)
    check(full.realizations.count { bazaarFinalRealization(full, it) } == 1)
    check(bazaarFinalRealization(full, full.realizations.last()))
    val durations = bazaarDurationContributions(full)
    check(durations.values.sum() == bazaarStats(full).durationMillis)
    check(durations.values.sum() < full.realizations.sumOf { it.durationMillis ?: 0L })
    val repeatedHoldingTime = full.copy(realizations = full.realizations.take(3).mapIndexed { index, record ->
        record.copy(durationMillis = if (index == 0) 1000L else 1500L)
    })
    val allocated = bazaarDurationContributions(repeatedHoldingTime).values.toList()
    check(allocated == listOf(1000L, 500L, 0L)) { "A partial realization can add zero new holding time" }
    check(allocated.sum() == bazaarStats(repeatedHoldingTime).durationMillis)
    var archive = BazaarArchive(sessions = listOf(BazaarSession("session-integration", integrationContext, INTEGRATION_NOW - 1000, INTEGRATION_NOW + 1000, "synthetic")))
    listOf(integrationEvent("partial-order", BazaarEventKind.BUY_ORDER, 100, null, INTEGRATION_NOW - 10, 1.0, "buy-order"),
        integrationEvent("partial-buy", BazaarEventKind.BUY_FILL, 50, 50.0, INTEGRATION_NOW - 9, 1.0, "buy-order", 50),
        integrationEvent("partial-collect", BazaarEventKind.BUY_COLLECT, 50, null, INTEGRATION_NOW - 8, orderId = "buy-order"),
        integrationEvent("partial-sale", BazaarEventKind.SELL_FILL, 50, 100.0, INTEGRATION_NOW - 8, 2.1)).forEach { archive = recordBazaarEvent(archive, it) }
    val before = reconcileBazaar(archive, integrationContext)
    check(before.positions.single().stage == BazaarStage.BUYING)
    check(!bazaarFinalRealization(before, before.realizations.single())) { "An outstanding buy commitment has not closed its position" }
    check(bazaarPublicationPayload(archive, "session-integration", BazaarReplayOptions(), listed = false)["completedFlips"].asInt == 0)
    archive = recordBazaarEvent(archive, integrationEvent("cancel-rest", BazaarEventKind.CANCEL, 0, null, INTEGRATION_NOW - 7, orderId = "buy-order"))
    val after = reconcileBazaar(archive, integrationContext)
    check(after.positions.single().stage == BazaarStage.CLOSED && bazaarFinalRealization(after, after.realizations.single()))
    check(bazaarPublicationPayload(archive, "session-integration", BazaarReplayOptions(), listed = false)["completedFlips"].asInt == 1)
    nearIntegration(bazaarStats(before).realizedProfit, bazaarStats(after).realizedProfit)
}

private fun checkBazaarAlertAndWebhook() {
    val base = BazaarOpportunity("WHEAT", "Wheat", 100, 1.0, 2.0, 100.0, 2.0, 98.0, .98, 100.0,
        10.0, 20.0, 100000.0, .9, 100.0, "Synthetic source", INTEGRATION_NOW, emptyList(), null, null)
    val ranked = listOf(base, base.copy(score = 10.0), base.copy(score = 20.0))
    check(exceptionalBazaarOpportunity(base, ranked, 1000.0, true, BazaarFreshness.FRESH, 50.0))
    for (freshness in BazaarFreshness.entries.filter { it != BazaarFreshness.FRESH })
        check(!exceptionalBazaarOpportunity(base, ranked, 1000.0, true, freshness, 50.0))
    check(!exceptionalBazaarOpportunity(base, ranked, 1000.0, false, BazaarFreshness.FRESH, 50.0))
    check(!exceptionalBazaarOpportunity(base, ranked, 99.0, true, BazaarFreshness.FRESH, 50.0))
    check(!exceptionalBazaarOpportunity(base, ranked, null, true, BazaarFreshness.FRESH, 50.0))
    check(!exceptionalBazaarOpportunity(base.copy(confidence = .64), ranked, 1000.0, true, BazaarFreshness.FRESH, 50.0))
    check(!exceptionalBazaarOpportunity(base.copy(score = 11.0), ranked, 1000.0, true, BazaarFreshness.FRESH, 50.0)) { "Alerts need a clear lead over the typical candidate" }
    val url = "https://discord.com/api/webhooks/123456789012345678/${"a".repeat(50)}"
    check(validBazaarWebhook(url) && validBazaarWebhook(url.replace("/api/", "/api/v10/")))
    listOf(url.replace("https:", "http:"), url.replace("discord.com", "127.0.0.1"), url.replace("discord.com", "localhost"),
        url.replace("discord.com", "discord.com.evil.example"), url.replace("discord.com", "user:password@discord.com"),
        "$url?redirect=https://localhost", "$url#fragment", url.replace("discord.com", "discord.com:443")).forEach { check(!validBazaarWebhook(it)) }
    val report = bazaarWebhookReport(90000, -1250.5, 2)
    check(report.lines().size == 5 && report.contains("Duration: 0h 1m 30s") && report.contains("Realized net profit: -1,250.50 coins") && report.contains("Completed flips: 2"))
    check(report.length <= 1800 && listOf("@", "https://", integrationContext.account, integrationContext.profile, "WHEAT", "fingerprint", "orderId").none { it in report }) {
        "Explicit webhook reports may contain only the three selected summary fields and static explanatory text"
    }
    rejectIntegration { bazaarWebhookReport(0, 1.0, 1) }
    rejectIntegration { bazaarWebhookReport(1000, Double.NaN, 1) }
    rejectIntegration { bazaarWebhookReport(1000, Double.POSITIVE_INFINITY, 1) }
    rejectIntegration { bazaarWebhookReport(1000, 1.0, -1) }
}

private fun checkBazaarGuideCommand() {
    for ((name, expected) in listOf("Wheat" to "Wheat", "  Enchanted Carrot  " to "Enchanted Carrot",
        "Jacob's Ticket" to "Jacob's Ticket", "Wisp's Ice-Flavored Water I" to "Wisp's Ice-Flavored Water I",
        "Ritual Residue (Tier I)" to "Ritual Residue (Tier I)", "Enchanted Wheat 🌾" to "Enchanted Wheat 🌾",
        "Enchanted  Carrot" to "Enchanted  Carrot", "A".repeat(120) to "A".repeat(120))) {
        for (quantity in listOf(1L, 320L, 1_000_000L))
            check(bazaarGuideCommand(name, quantity) == "bz $expected") { "Guide changed the selected Bazaar search name or included trade execution arguments" }
    }
    for (quantity in listOf(Long.MIN_VALUE, -1L, 0L, 1_000_001L, Long.MAX_VALUE))
        check(bazaarGuideCommand("Wheat", quantity) == null)
    for (name in listOf("", "   ", "☘", "A".repeat(121), "Wheat\nparty warp", "\tWheat", "Wheat\r", "Wheat\u0000",
        "Wheat\u007F", "Wheat\u0085", "/bz Wheat", "Wheat/party", "Wheat\\party", "Wheat\u2028party", "Wheat\u2029party",
        "Wheat\uD800", "Wheat\uDC00", "Wheat\uDC00\uD800")) {
        check(bazaarGuideCommand(name, 1) == null) { "Guide accepted an empty, oversized or malformed search name" }
    }
}

private fun checkBazaarCorrections() {
    val observation = integrationEvent("ambiguous-receipt", BazaarEventKind.OBSERVATION, 0, null, INTEGRATION_NOW - 50)
        .copy(product = "BAZAAR", orderId = "uncertain-order", positionId = "uncertain-position", cumulativeFilled = 50)
    fun correction(fields: Map<String, String>, product: String? = "WHEAT", original: BazaarEvent = observation, id: String = "personal-buy") =
        bazaarCorrection(original, fields, product, id, INTEGRATION_NOW)
    fun rejected(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message?.contains(message, ignoreCase = true) == true) { "Expected an actionable '$message' validation error, got $failure" }
    }
    rejected("Choose what happened") { correction(emptyMap()) }
    rejected("Choose the Bazaar item") { correction(mapOf("kind" to "BUY_FILL", "quantity" to "10", "coins" to "100"), product = null) }
    rejected("Choose the Bazaar item") { correction(mapOf("kind" to "BUY_FILL", "quantity" to "10", "coins" to "100"), product = null, original = observation.copy(product = "UNKNOWN")) }
    rejected("recognized Bazaar item") { correction(mapOf("kind" to "BUY_FILL", "product" to "Unknown item"), product = null) }
    rejected("Enter a quantity") { correction(mapOf("kind" to "BUY_FILL", "coins" to "100")) }
    rejected("actual total coins paid") { correction(mapOf("kind" to "BUY_FILL", "quantity" to "10")) }
    rejected("actual NET coins received") { correction(mapOf("kind" to "SELL_FILL", "quantity" to "10")) }
    rejected("positive price per item") { correction(mapOf("kind" to "BUY_ORDER", "quantity" to "10")) }
    rejected("positive price per item") { correction(mapOf("kind" to "SELL_ORDER", "quantity" to "10", "unitPrice" to "0")) }
    val buyFields = mapOf("kind" to "BUY_FILL", "quantity" to "10", "coins" to "100", "unitPrice" to "10")
    for (value in listOf("not money", "NaN", "Infinity", "-1", "1000000000000001"))
        rejected("Actual total acquisition cost must be") { correction(buyFields + ("coins" to value)) }
    for (value in listOf("1.5", "-1", "1e3", "quantity"))
        rejected("whole number") { correction(buyFields + ("quantity" to value)) }
    for (value in listOf("0", "1000000001", "999999999999999999999999999"))
        rejected("quantity") { correction(buyFields + ("quantity" to value)) }
    val buy = correction(buyFields)
    check(buy.kind == BazaarEventKind.BUY_FILL && buy.evidence == BazaarEvidence.MANUAL && buy.quantity == 10L && buy.coins == 100.0)
    check(buy.at == observation.at && buy.product == "WHEAT" && buy.orderId == null && buy.positionId == null && buy.cumulativeFilled == null && buy.replacesId == null)
    val sell = correction(mapOf("kind" to "SELL_FILL", "quantity" to "10", "coins" to "125", "unitPrice" to "13"),
        original = observation.copy(id = "ambiguous-sale", at = INTEGRATION_NOW - 20), id = "personal-sale")
    var archive = recordBazaarEvent(BazaarArchive(), buy)
    archive = recordBazaarEvent(archive, buy)
    archive = recordBazaarEvent(archive, sell)
    val ledger = reconcileBazaar(archive, integrationContext)
    check(archive.events.size == 2 && ledger.realizations.single().evidence == BazaarEvidence.MANUAL)
    nearIntegration(bazaarStats(ledger).realizedProfit, 25.0); nearIntegration(bazaarStats(ledger).eligibleProfit, 0.0)
    val order = correction(mapOf("kind" to "BUY_ORDER", "quantity" to "20", "unitPrice" to "5", "coins" to "100"), id = "personal-order")
    check(order.orderId == "personal-order" && order.positionId == null && order.coins == null && order.cumulativeFilled == null)
    val orderLedger = reconcileBazaar(recordBazaarEvent(BazaarArchive(), order), integrationContext)
    check(orderLedger.orders.single().filled == 0L && orderLedger.realizations.isEmpty() && orderLedger.positions.single().acquired == 0L)
    val observedSale = sell.copy(id = "observed-sale", evidence = BazaarEvidence.OBSERVED)
    val repaired = correction(mapOf("coins" to "120", "quantity" to "  "), original = observedSale, id = "repair")
    check(repaired.kind == BazaarEventKind.CORRECTION && repaired.replacesId == observedSale.id && repaired.quantity == observedSale.quantity && repaired.coins == 120.0)
    rejected("type is fixed") { correction(mapOf("kind" to "BUY_FILL"), original = observedSale) }
    rejected("item is fixed") { correction(mapOf("coins" to "120"), product = "DIAMOND", original = observedSale) }
    rejected("NET sale proceeds exceed") { correction(mapOf("coins" to "140"), original = observedSale) }
    rejected("cumulative fill quantity is fixed") { correction(mapOf("quantity" to "9"), original = buy.copy(orderId = "buy-order", cumulativeFilled = 10)) }
    for (kind in listOf(BazaarEventKind.BUY_COLLECT, BazaarEventKind.COINS_COLLECT, BazaarEventKind.CANCEL, BazaarEventKind.RELIST))
        rejected("cannot be repaired as a trade") { correction(emptyMap(), original = observation.copy(kind = kind)) }
    val grossSale = observedSale.copy(id = "gross-sale", quantity = 10, unitPrice = 2.0, coins = 19.0)
    val before = recordBazaarEvent(BazaarArchive(), grossSale)
    rejectIntegration { recordBazaarEvent(before, grossSale.copy(id = "impossible-net-correction", kind = BazaarEventKind.CORRECTION,
        coins = 30.0, evidence = BazaarEvidence.MANUAL, replacesId = grossSale.id)) }
    check(before.events == listOf(grossSale)) { "An invalid effective correction must leave the private journal unchanged" }
}

private fun checkBazaarManualOnlyBytecode() {
    var methods = 0
    val dispatches = mutableMapOf<String, Int>()
    var openOrdersLiteral = false
    var guideValidated = false
    for (resource in listOf("BazaarTrading", "BazaarGuideKt", "BazaarCorrectionKt")) resourceReader("/me/mycellium/skymyce/features/bazaar/$resource.class").accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            methods++
            val method = name
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitLdcInsn(value: Any?) {
                    if (resource == "BazaarTrading" && method == "openBazaarOrders" && value == "managebazaarorders") openOrdersLiteral = true
                }
                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                    if (resource == "BazaarTrading" && method == "openGuidedBazaar" && owner == "me/mycellium/skymyce/features/bazaar/BazaarGuideKt" && name == "bazaarGuideCommand") guideValidated = true
                    check(!owner.startsWith("net/minecraft/network/protocol/game/")) { "Bazaar constructs a gameplay packet" }
                    check(!(owner in setOf("net/minecraft/network/Connection", "net/minecraft/client/multiplayer/ClientPacketListener") &&
                        name in setOf("send", "sendChat", "sendCommand", "sendUnsignedCommand"))) { "Bazaar sends a gameplay/chat action" }
                    check(!(owner == "net/minecraft/client/multiplayer/MultiPlayerGameMode" && name in setOf("handleContainerInput", "handleInventoryMouseClick", "useItem", "useItemOn", "attack", "interact", "interactAt")))
                    check(!(owner == "net/minecraft/client/KeyMapping" && name in setOf("click", "setDown", "set"))) { "Bazaar simulates player input" }
                    check(!owner.startsWith("java/awt/Robot"))
                    if (owner == "me/mycellium/skymyce/utils/PlayerUtils" && name in setOf("sendCommand", "sendCommand\$default", "sendMessage", "sendMessage\$default", "sendModMessage", "sendModMessage\$default")) {
                        check(resource == "BazaarTrading" && method in setOf("openGuidedBazaar", "openBazaarOrders") && name in setOf("sendCommand", "sendCommand\$default")) {
                            "Only an explicit Guide or Open orders selection may open a Bazaar menu; every trade remains manual"
                        }
                        dispatches[method] = (dispatches[method] ?: 0) + 1
                    }
                }
            }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    check(methods > 20 && dispatches == mapOf("openGuidedBazaar" to 1, "openBazaarOrders" to 1)) { "Guide and Open orders must each have one isolated dispatch" }
    check(openOrdersLiteral && guideValidated) { "Manual menu opening must use literal managebazaarorders or the validated Guide search" }
    var redirectsNever = false
    resourceReader("/me/mycellium/skymyce/features/bazaar/BazaarWebhook.class").accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                if (owner == "java/net/http/HttpClient\$Redirect" && name == "NEVER") redirectsNever = true
            }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    check(redirectsNever) { "Personal webhook delivery must never follow redirects" }
}

private fun checkBazaarAlertGate() {
    val gate = BazaarAlertGate()
    val at = INTEGRATION_NOW
    check(!gate.allows("game", "same-opportunity", at, 60, 100.0, 150.0))
    check(gate.allows("dashboard", "same-opportunity", at, 30, 100.0, 50.0))
    check(gate.allows("dashboard", "same-opportunity", at, 30, 100.0, 50.0)) { "A check must not consume a delivery" }
    check(gate.allows("game", "same-opportunity", at, 60, 200.0, 150.0))
    gate.delivered("game", "same-opportunity", at)
    check(!gate.allows("game", "same-opportunity", at + 59_999, 60, 200.0, 150.0))
    check(!gate.allows("game", "another-opportunity", at + 59_999, 60, 200.0, 150.0))
    check(gate.allows("game", "same-opportunity", at + 60_000, 60, 200.0, 150.0))
    check(gate.allows("discord", "same-opportunity", at + 1000, 120, 200.0, 150.0))
    gate.delivered("discord", "same-opportunity", at + 1000)
    check(!gate.allows("discord", "same-opportunity", at + 120_999, 120, 200.0, 150.0))
    check(gate.allows("discord", "same-opportunity", at + 121_000, 120, 200.0, 150.0))
    check(gate.allows("webhook", "same-opportunity", at + 2000, 300, 200.0, 150.0))
    gate.delivered("webhook", "same-opportunity", at + 2000)
    check(!gate.allows("webhook", "same-opportunity", at + 301_999, 300, 200.0, 150.0))
    check(gate.allows("webhook", "same-opportunity", at + 302_000, 300, 200.0, 150.0))
    check(gate.allows("webhook-report", "manual-session", at + 2000, 30, 0.0, 0.0))
    gate.delivered("webhook-report", "manual-session", at + 2000)
    check(!gate.allows("webhook-report", "manual-session", at + 31_999, 30, 0.0, 0.0))
    check(gate.allows("webhook-report", "manual-session", at + 32_000, 30, 0.0, 0.0))
    check(!gate.allows("webhook", "same-opportunity", at + 32_000, 300, 200.0, 150.0)) { "Manual reports must not reset exceptional webhook cooldowns" }
    check(gate.allows("dashboard", "same-opportunity", at + 2000, 30, 200.0, 150.0))
    gate.delivered("dashboard", "same-opportunity", at + 2000)
    check(!gate.allows("dashboard", "same-opportunity", at + 31_999, 30, 200.0, 150.0))
    check(gate.allows("dashboard", "same-opportunity", at + 32_000, 30, 200.0, 150.0))
    check(!gate.allows("game", "clock-rollback", at - 1, 60, 200.0, 150.0))
    check(!gate.allows("game", "nan", at + 60_000, 60, Double.NaN, 150.0))
    check(!gate.allows("game", "infinity", at + 60_000, 60, Double.POSITIVE_INFINITY, 150.0))
    check(!gate.allows("game", "invalid-threshold", at + 60_000, 60, 200.0, Double.NaN))
    rejectIntegration { gate.allows("public-feed", "invalid", at, 60, 200.0, 150.0) }
    rejectIntegration { gate.allows("game", "invalid", at, 29, 200.0, 150.0) }
    rejectIntegration { gate.allows("game", "invalid", at, 3601, 200.0, 150.0) }
    val bounded = BazaarAlertGate()
    repeat(140) { index ->
        val now = at + index * 30_000L
        check(bounded.allows("game", "opportunity-$index", now, 30, 200.0, 150.0))
        bounded.delivered("game", "opportunity-$index", now)
    }
    val field = BazaarAlertGate::class.java.getDeclaredField("opportunities").apply { isAccessible = true }
    check((field.get(bounded) as Map<*, *>).size <= 128) { "Notification deduplication must stay bounded" }
    check(bounded.allows("game", "opportunity-139", at + 140 * 30_000L, 30, 200.0, 150.0))
}

private fun resourceReader(path: String) = requireNotNull(object {}.javaClass.getResourceAsStream(path)).use(::ClassReader)
