package me.mycellium.skymyce.features.digest.testing

import com.google.gson.JsonObject
import me.mycellium.skymyce.features.digest.DigestDungeonAcquisition
import me.mycellium.skymyce.features.digest.DigestRngParser
import me.mycellium.skymyce.features.digest.confirmedRng
import me.mycellium.skymyce.features.digest.normalizeRngText
import me.mycellium.skymyce.features.instances.dungeons.tracker.*

data class LocalRngTest(val normalized: String?, val item: String?, val activity: String?, val confirmed: Boolean,
    val journalRecords: Int, val duplicateSuppressed: Boolean, val notes: List<String>)

/** No singleton modules, callbacks, repositories, filesystem or network: disposable production parser objects only. */
fun localRngTest(fixture: String, now: Long, overrideText: String? = null): LocalRngTest {
    require(fixture in listOf("handle", "warden-heart", "duplicate", "malformed") && now > 0)
    val text = overrideText?.takeIf(String::isNotBlank) ?: when (fixture) {
        "warden-heart" -> "§dCRAZY RARE DROP! §r(Warden Heart) (+150% Magic Find)"
        "malformed" -> "[Guild] Someone: RARE REWARD! TestPlayer found a Necron's Handle in their Bedrock Chest!"
        else -> "§dRARE REWARD! §r[MVP+] TestPlayer found a Necron’s Handle in their Bedrock Chest!"
    }
    val location = if (fixture == "warden-heart") "HUB" else "THE_CATACOMBS"
    val normalized = normalizeRngText(text)
    val parsed = DigestRngParser.detect(text, "TestPlayer", location, now)
        ?: return LocalRngTest(normalized, null, null, false, 0, false, listOf("Actual chat parser rejected this text/location/player context. No acquisition was created."))
    val detector = DigestDungeonAcquisition().apply { seed(emptyMap()) }
    val confirmed = if (parsed.activity == "dungeon") {
        check(detector.announce(parsed, now) == null)
        // A fresh synthetic inventory receipt, rather than an announcement alone, confirms the fixture.
        detector.inventory(mapOf(parsed.item to 1), now + 1, "rng-test-receipt").singleOrNull()?.let(::confirmedRng)
    } else detector.annotateSlayer(parsed, now)
    if (confirmed == null) return LocalRngTest(normalized, parsed.item, parsed.activity, false, 0, false, listOf("Waiting for a matching test inventory receipt."))
    val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "TEST")
    val event = AcquisitionEvent(confirmed.id, confirmed.item, confirmed.occurredAt, confirmed.activity, context,
        floor = if (confirmed.activity == "dungeon") "F7" else null, player = "TestPlayer")
    val first = mergeAcquisition(AcquisitionArchive(), event)
    val retry = mergeAcquisition(first, event)
    val journal = rngJournal(retry, JournalFilter(), context, now + 2)
    return LocalRngTest(normalized, parsed.item, parsed.activity, true, journal.events.size,
        first.events.size == 1 && retry.events.size == 1, listOf("Production parser and acquisition confirmation accepted the fixture.",
            "Production merge/journal logic kept ${journal.events.size} temporary record after the exact receipt was retried.",
            "This archive exists only in memory. Your personal journal, tracker and shared feed were untouched."))
}

data class RngTestReply(val mode: String, val cases: List<String>, val mock: String?, val embedTitle: String?)

/** Reject a cached/client role claim or a response that permits any production write. */
fun parseRngTestReply(json: JsonObject, expectedMode: String): RngTestReply {
    fun flag(value: com.google.gson.JsonElement?, expected: Boolean) = value?.isJsonPrimitive == true &&
        value.asJsonPrimitive.isBoolean && value.asBoolean == expected
    require(flag(json["ok"], true) && flag(json["test"], true) && flag(json["authorized"], true))
    require(json["version"]?.asJsonPrimitive?.let { it.isNumber && it.asString == "1" } == true)
    require(expectedMode in listOf("dry-run", "mock-delivery") && json["mode"]?.asString == expectedMode)
    val writes = json.getAsJsonObject("writes")
    require(writes.keySet() == setOf("personal", "acquisitions", "shared", "leaderboards", "productionDiscord") && writes.entrySet().all { flag(it.value, false) })
    fun safeText(value: com.google.gson.JsonElement?, maximum: Int) = value?.asJsonPrimitive?.let {
        require(it.isString); it.asString.also { text -> require(text.length in 1..maximum && text.none(Char::isISOControl)) }
    } ?: error("Missing test text")
    val samples = json.getAsJsonArray("cases").also { require(it.size() in 1..4) }.map { raw ->
        val row = raw.asJsonObject
        val status = safeText(row["status"], 20).also { require(it in listOf("accepted", "duplicate", "invalid", "rate_limited")) }
        "${safeText(row["label"], 100)}: $status"
    }
    val mock = json.getAsJsonObject("mock")
    val detail = mock?.let {
        require(expectedMode == "mock-delivery" && it["posts"].asString == "1" && it["acknowledgements"].asString == "2" && flag(it["retrySuppressed"], true))
        "Isolated mock: 1 post, 2 ACK attempts; duplicate delivery suppressed."
    }
    val title = mock?.getAsJsonObject("presentation")?.getAsJsonArray("embeds")?.let {
        require(it.size() == 1); safeText(it[0].asJsonObject["title"], 256).also { title -> require(title.startsWith("TEST · ")) }
    }
    return RngTestReply(expectedMode, samples, detail, title)
}
