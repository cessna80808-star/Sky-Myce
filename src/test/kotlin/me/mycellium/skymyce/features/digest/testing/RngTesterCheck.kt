package me.mycellium.skymyce.features.digest.testing

import com.google.gson.JsonParser

fun main() {
    val now = 1_797_000_000_000L
    for (fixture in listOf("handle", "warden-heart", "duplicate")) {
        val result = localRngTest(fixture, now)
        check(result.confirmed && result.journalRecords == 1 && result.duplicateSuppressed)
    }
    check(localRngTest("handle", now).item == "NECRON_HANDLE")
    check(localRngTest("warden-heart", now).item == "WARDEN_HEART")
    check(localRngTest("malformed", now).journalRecords == 0)
    for (text in listOf("RARE REWARD! SomeoneElse found a Necron's Handle in their Bedrock Chest!",
        "RARE REWARD! TestPlayer found a Fake Handle in their Bedrock Chest!", "CRAZY RARE DROP! (Warden Heart)",
        "RARE REWARD! TestPlayer found a Necron's Handle in their Bedrock Chest!\nInjected", "x".repeat(601))) {
        check(localRngTest("handle", now, text).journalRecords == 0)
    }
    val valid = JsonParser.parseString("""{"ok":true,"version":1,"test":true,"authorized":true,"mode":"dry-run","cases":[{"label":"Fixture","status":"accepted"}],"writes":{"personal":false,"acquisitions":false,"shared":false,"leaderboards":false,"productionDiscord":false}}""").asJsonObject
    check(parseRngTestReply(valid, "dry-run").cases.single() == "Fixture: accepted")
    fun rejects(edit: (com.google.gson.JsonObject) -> Unit) = check(runCatching { parseRngTestReply(valid.deepCopy().also(edit), "dry-run") }.isFailure)
    rejects { it.addProperty("authorized", false) }; rejects { it.addProperty("authorized", "true") }
    rejects { it.addProperty("version", "1") }; rejects { it.addProperty("mode", "mock-delivery") }
    rejects { it.getAsJsonObject("writes").addProperty("productionDiscord", true) }
    rejects { it.getAsJsonObject("writes").remove("personal") }
    rejects { it.getAsJsonObject("writes").addProperty("shared", "false") }
    rejects { it.getAsJsonArray("cases")[0].asJsonObject.addProperty("status", "published") }
    val mock = valid.deepCopy().apply {
        addProperty("mode", "mock-delivery")
        add("mock", JsonParser.parseString("""{"posts":1,"acknowledgements":2,"retrySuppressed":true,"presentation":{"embeds":[{"title":"TEST · Rare drop"}]}}"""))
    }
    check(parseRngTestReply(mock, "mock-delivery").mock != null)
    check(runCatching { parseRngTestReply(mock.deepCopy().apply { getAsJsonObject("mock").addProperty("posts", 2) }, "mock-delivery") }.isFailure)
    check(runCatching { parseRngTestReply(mock.deepCopy().apply { getAsJsonObject("mock").getAsJsonObject("presentation").getAsJsonArray("embeds")[0].asJsonObject.addProperty("title", "Rare drop") }, "mock-delivery") }.isFailure)
    println("RNG tester production parser/temporary journal/duplicate and fail-closed response checks passed")
}
