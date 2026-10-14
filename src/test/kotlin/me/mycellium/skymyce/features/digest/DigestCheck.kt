package me.mycellium.skymyce.features.digest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletionException
import java.net.http.HttpTimeoutException
import java.io.IOException
import me.mycellium.skymyce.community.CommunityFailure

/** Runs with the existing dungeonFriendsCheck task, entirely offline and without a Minecraft window. */
fun checkDigestLifecycleAndNews() {
    val account = "00000000-0000-0000-0000-000000000001"
    val other = "00000000-0000-0000-0000-000000000002"
    val day = LocalDate.of(2026, 9, 24)
    val gate = DigestDailyGate()
    fun ready(at: Long, date: LocalDate = day, id: String = account) = gate.shouldOpen(id, at, date, true, true, true, true)
    check(!ready(8000)) // First load outside SkyBlock cannot open.
    gate.enter(1000, 8000)
    check(!ready(8999) && ready(9000))
    check(!gate.shouldOpen(account, 9000, day, false, true, true, true))
    check(!gate.shouldOpen(account, 9000, day, true, false, true, true))
    check(!gate.shouldOpen(account, 9000, day, true, true, false, true))
    check(!gate.shouldOpen(account, 9000, day, true, true, true, false))
    check(!gate.shouldOpen(null, 9000, day, true, true, true, true))
    gate.opened(account, day) // A successful manual open counts too.
    check(!ready(10000) && ready(10000, id = other))
    gate.leave()
    gate.enter(20000, 8000) // Rejoin/profile change does not open again today.
    check(!ready(30000) && ready(30000, day.plusDays(1)))
    check(!ready(30000, day.minusDays(1))) // Clock rollback cannot repeat an already shown day.
    gate.lastOpened[other] = "corrupt"
    check(ready(30000, id = other))
    gate.leave()
    check(!ready(90000, day.plusDays(1)))
    repeat(65) { gate.opened("%032x".format(it), day.plusDays(it.toLong())) }
    check(gate.lastOpened.size == 64)
    val instant = Instant.parse("2026-09-23T23:30:00Z").toEpochMilli()
    check(DigestDailyGate.localDate(instant, ZoneId.of("Europe/Stockholm")) == day)
    check(DigestDailyGate.localDate(instant, ZoneId.of("America/New_York")) == day.minusDays(1))
    val spring = Instant.parse("2026-03-28T23:00:00Z").toEpochMilli()
    check(DigestDailyGate.nextMidnight(spring, ZoneId.of("Europe/Stockholm")) - spring == 23 * 3600000L)
    val autumn = Instant.parse("2026-10-24T22:00:00Z").toEpochMilli()
    check(DigestDailyGate.nextMidnight(autumn, ZoneId.of("Europe/Stockholm")) - autumn == 25 * 3600000L)
    val mainProfile = DigestDailyGate.profileKey(account, "Apple", false)
    check(mainProfile != DigestDailyGate.profileKey(account, "Banana", false))
    check(mainProfile != DigestDailyGate.profileKey(other, "Apple", false))
    check(mainProfile != DigestDailyGate.profileKey(account, "Apple", true))

    var now = 1_000_000L
    check(NewsSource.entries == listOf(NewsSource.GAME))
    val legacyNewsConfig = JsonParser.parseString("""{"General":{"Daily Digest":{"news":true,"gameUpdates":false,"alphaUpdates":true,"retainedRngEvents":100}},"unrelated":{"value":7}}""").asJsonObject
    check(!me.mycellium.skymyce.config.misc.migrateDailyNewsConfig(legacyNewsConfig, true))
    val migratedNewsConfig = legacyNewsConfig.getAsJsonObject("General").getAsJsonObject("Daily Digest")
    check(!migratedNewsConfig.has("gameUpdates") && !migratedNewsConfig.has("alphaUpdates"))
    check(migratedNewsConfig.get("retainedRngEvents").asInt == 100 && legacyNewsConfig.getAsJsonObject("unrelated").get("value").asInt == 7)
    check(!me.mycellium.skymyce.config.misc.migrateDailyNewsConfig(legacyNewsConfig, false)) // Master News=false stays off.
    val laterNewsChoice = me.mycellium.skymyce.config.mergeConfigFields(legacyNewsConfig,
        JsonParser.parseString("""{"General":{"Daily Digest":{"news":true}}}""").asJsonObject)
    check(me.mycellium.skymyce.config.misc.migrateDailyNewsConfig(laterNewsChoice, true)) // A later user toggle cannot be undone by retired keys.
    val requests = mutableListOf<Pair<NewsSource, CompletableFuture<JsonObject?>>>()
    var updates = 0
    val news = DigestNewsRepository({ source -> CompletableFuture<JsonObject?>().also { requests += source to it } },
        { it() }, { updates++ }, { now })
    fun response(source: String = "game", id: String = "123") = JsonParser.parseString("""{
      "source":"$source","status":"ready","fetchedAt":$now,"items":[
        {"id":"$id","title":"Useful update","content":"Details","publishedAt":900000,"url":"https://discord.com/channels/1/2/3"},
        {"id":"$id","title":"Repeated update","content":"Details","publishedAt":900000}]
    }""").asJsonObject
    check(news.state(NewsSource.GAME).items.isEmpty())
    news.refresh(NewsSource.entries)
    check(requests.size == 1 && news.state(NewsSource.GAME).loading)
    news.refresh(NewsSource.entries, true)
    check(requests.size == 1)
    requests[0].second.complete(response())
    check(news.state(NewsSource.GAME).items.size == 1 && !news.state(NewsSource.GAME).loading)
    check(news.state(NewsSource.GAME).failure == null)
    news.restore(NewsSource.GAME, DigestNewsState(fetchedAt = 1))
    check(news.state(NewsSource.GAME).items.size == 1) // A late disk read cannot overwrite a fresh response.
    now += 61_000
    news.refresh(listOf(NewsSource.GAME))
    check(requests.size == 1) // A warm successful cache avoids another download.
    now += 300_000
    news.refresh(listOf(NewsSource.GAME))
    check(requests.size == 2 && news.state(NewsSource.GAME).items.size == 1 && news.state(NewsSource.GAME).loading)
    requests.last().second.complete(null)
    check(news.state(NewsSource.GAME).items.size == 1 && news.state(NewsSource.GAME).failure == DigestFailure.TIMEOUT)
    now += 61_000
    news.refresh(listOf(NewsSource.GAME), true)
    requests.last().second.completeExceptionally(TimeoutException())
    check(news.state(NewsSource.GAME).items.single().source == NewsSource.GAME && news.state(NewsSource.GAME).failure == DigestFailure.TIMEOUT)
    now += 61_000
    news.refresh(listOf(NewsSource.GAME), true)
    val cancelled = requests.last().second
    news.cancel()
    check(cancelled.isCancelled && !news.state(NewsSource.GAME).loading)
    now += 61_000
    news.refresh(listOf(NewsSource.GAME), true)
    news.restore(NewsSource.GAME, news.state(NewsSource.GAME).copy(loading = false))
    check(news.state(NewsSource.GAME).loading)
    requests.last().second.complete(JsonParser.parseString("""{"error":"rate_limited","retryAt":${now + 120000}}""").asJsonObject)
    check(news.state(NewsSource.GAME).failure == DigestFailure.RATE_LIMITED && news.state(NewsSource.GAME).items.size == 1)
    val requestCount = requests.size
    now += 61_000
    news.refresh(listOf(NewsSource.GAME), true)
    check(requests.size == requestCount) // Honor the server's longer backoff.
    val malformed = response().apply { getAsJsonArray("items")[0].asJsonObject.addProperty("title", "x".repeat(161)) }
    check(parseDigestNews(malformed, NewsSource.GAME, now).size == 1) // Valid siblings survive.
    check(runCatching { parseDigestNews(response("alpha"), NewsSource.GAME, now) }.isFailure)
    val noId = response().apply { getAsJsonArray("items").forEach { it.asJsonObject.remove("id") } }
    check(parseDigestNews(noId, NewsSource.GAME, now).size == 1)
    check(safeDigestUrl("https://discord.com@evil.example/x") == null && safeDigestUrl("file:///etc/passwd") == null)
    check(safeDigestUrl("https://hypixel.net/threads/example") != null)
    check(updates > 0)
    val brokenProvider = DigestNewsRepository({ error("offline") }, { it() }, {}, { now })
    brokenProvider.refresh(listOf(NewsSource.GAME))
    check(brokenProvider.state(NewsSource.GAME).failure == DigestFailure.OFFLINE)
    var connected = false
    val connectionRequest = CompletableFuture<JsonObject?>()
    val connecting = DigestNewsRepository({ if (connected) connectionRequest else null }, { it() }, {}, { now })
    connecting.refresh(listOf(NewsSource.GAME))
    connected = true
    connecting.refresh(listOf(NewsSource.GAME))
    check(connecting.state(NewsSource.GAME).loading) // No fake cooldown for requests never transmitted.
    check(digestNewsFailure(HttpTimeoutException("timed out")) == DigestFailure.TIMEOUT)
    check(digestNewsFailure(CompletionException(IOException("offline"))) == DigestFailure.OFFLINE)
    check(digestNewsFailure(CommunityFailure("rate_limited")) == DigestFailure.RATE_LIMITED)
    check(digestNewsFailure(CommunityFailure("news_unavailable")) == DigestFailure.OFFLINE)
    check(digestNewsFailure(CommunityFailure("invalid_source")) == DigestFailure.INVALID)
    check(digestNewsFailure(IllegalArgumentException("malformed JSON")) == DigestFailure.INVALID)
    val drop = DetectedRng("a".repeat(32), "NECRON_HANDLE", "dungeon", now)
    check(digestRngSubmission(drop, false, true, now) == null)
    val privateReport = digestRngSubmission(drop, true, false, now)!!
    check(privateReport.keys == setOf("event", "showName") && privateReport["showName"] == false)
    check((privateReport["event"] as Map<*, *>).keys == setOf("id", "item", "activity", "occurredAt"))
    check(digestRngSubmission(drop.copy(item = "DIRT"), true, true, now) == null)
    check(digestRngSubmission(drop.copy(occurredAt = now - 300001), true, true, now) == null)
    val observedContext = AcquisitionContext("a".repeat(32), "b".repeat(32), "Apple")
    val receipt = AcquisitionEvent(drop.id, drop.item, now - 5000, "dungeon", observedContext,
        floor = "M7", rarity = "MYTHIC", displayName = "Ancient Necron's Handle")
    fun report(detected: DetectedRng = drop, record: AcquisitionEvent? = receipt, context: AcquisitionContext? = observedContext) =
        checkNotNull(digestRngSubmission(detected, true, false, now, record, context))["event"] as Map<*, *>
    val enrichedReport = report()
    check(enrichedReport.keys == setOf("id", "item", "activity", "occurredAt", "rarity", "displayName", "floor"))
    check(enrichedReport["rarity"] == "MYTHIC" && enrichedReport["displayName"] == receipt.displayName && enrichedReport["floor"] == "M7")
    check(enrichedReport["id"] == drop.id && enrichedReport["occurredAt"] == now) // Enrichment never changes the published receipt identity/time.
    for (mismatch in listOf(receipt.copy(id = "c".repeat(32)), receipt.copy(item = "GIANTS_SWORD"),
        receipt.copy(activity = "slayer"), receipt.copy(origin = "legacy-confirmed"), receipt.copy(occurredAt = now - 15001),
        receipt.copy(context = observedContext.copy(profileId = "c".repeat(32), profileName = "Pear")))) {
        check(report(record = mismatch).keys == setOf("id", "item", "activity", "occurredAt"))
    }
    check(report(context = null).keys == setOf("id", "item", "activity", "occurredAt"))
    val directlyObserved = drop.copy(rarity = "LEGENDARY", displayName = "Necron's Handle")
    check(report(directlyObserved, null)["rarity"] == "LEGENDARY" && report(directlyObserved, null)["floor"] == null)
    check(report(record = receipt.copy(floor = "FAKE"))["floor"] == null)
    check(report(record = receipt.copy(rarity = null, displayName = null)).keys == setOf("id", "item", "activity", "occurredAt", "floor"))
    for (unsafeName in listOf("hidden\u200Bname", "bidi\u202Ename", "bad\uD83Dname", "bad\uDE00name", "line\u2028name", "paragraph\u2029name")) {
        val safeReport = report(directlyObserved.copy(displayName = unsafeName), null)
        check(safeReport["rarity"] == "LEGENDARY" && "displayName" !in safeReport && safeReport["id"] == drop.id)
    }
    check(report(directlyObserved.copy(displayName = "Necron's Handle \uD83D\uDE00"), null)["displayName"] == "Necron's Handle \uD83D\uDE00")
    check(digestRngSubmission(directlyObserved, false, false, now, receipt, observedContext) == null)
    check(digestRngSubmission(directlyObserved.copy(occurredAt = now - 300001), true, false, now, receipt, observedContext) == null)
    val slayerDrop = drop.copy(item = "WARDEN_HEART", activity = "slayer", rarity = "LEGENDARY", displayName = "Warden Heart")
    check(report(slayerDrop, receipt.copy(item = slayerDrop.item, activity = "slayer"))["floor"] == null)
    val community = JsonParser.parseString("""{"id":"${"b".repeat(32)}","item":"WARDEN_HEART","activity":"slayer","occurredAt":$now,"player":"Anonymous","unverified":true}""").asJsonObject
    check(parseDigestCommunityEvent(community, now)?.community == true)
    check(parseDigestCommunityEvent(community.deepCopy().apply { addProperty("inventory", "private") }, now) == null)
    check(parseDigestCommunityEvent(community.deepCopy().apply { addProperty("unverified", false) }, now) == null)
    check(parseDigestCommunityEvent(community.deepCopy().apply { addProperty("player", "x".repeat(17)) }, now) == null)
    val enrichedCommunity = community.deepCopy().apply {
        addProperty("rarity", "LEGENDARY"); addProperty("displayName", "Warden Heart")
    }
    check(parseDigestCommunityEvent(enrichedCommunity, now)?.community == true)
    val dungeonCommunity = enrichedCommunity.deepCopy().apply {
        addProperty("item", "NECRON_HANDLE"); addProperty("activity", "dungeon"); addProperty("floor", "F7")
        addProperty("displayName", "Ancient Necron's Handle")
    }
    for (rarity in listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "ULTIMATE", "SPECIAL", "VERY_SPECIAL", "ADMIN")) {
        check(parseDigestCommunityEvent(dungeonCommunity.deepCopy().apply { addProperty("rarity", rarity) }, now) != null)
    }
    for (floor in listOf("E", "F1", "F7", "M1", "M7")) {
        check(parseDigestCommunityEvent(dungeonCommunity.deepCopy().apply { addProperty("floor", floor) }, now) != null)
    }
    for (malformed in listOf(dungeonCommunity.deepCopy().apply { addProperty("rarity", "FAKE") },
        dungeonCommunity.deepCopy().apply { addProperty("rarity", 2) },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "x".repeat(121)) },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "§6Styled") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "bad\nname") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "hidden\u200Bname") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "bidi\u202Ename") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "bad\uD83Dname") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "bad\uDE00name") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "line\u2028name") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", "paragraph\u2029name") },
        dungeonCommunity.deepCopy().apply { addProperty("displayName", " untrimmed ") },
        dungeonCommunity.deepCopy().apply { addProperty("floor", "F8") },
        dungeonCommunity.deepCopy().apply { addProperty("floor", "ENTRANCE") },
        dungeonCommunity.deepCopy().apply { addProperty("floor", 7) },
        enrichedCommunity.deepCopy().apply { addProperty("floor", "F7") })) {
        check(parseDigestCommunityEvent(malformed, now) == null)
    }
    val nullableMetadata = community.deepCopy().apply {
        add("rarity", com.google.gson.JsonNull.INSTANCE); add("displayName", com.google.gson.JsonNull.INSTANCE); add("floor", com.google.gson.JsonNull.INSTANCE)
    }
    check(parseDigestCommunityEvent(nullableMetadata, now) != null)
    check(parseDigestCommunityEvent(dungeonCommunity.deepCopy().apply { addProperty("displayName", "Necron's Handle \uD83D\uDE00") }, now) != null)
    val metadataHistory = com.google.gson.JsonArray().apply { add(dungeonCommunity); add(enrichedCommunity) }
    check(parseDigestCommunityHistory(metadataHistory, now).size == 1) // Extra metadata does not create a second ID.
    val mixedFeed = com.google.gson.JsonArray().apply {
        add(community); add(12); add(com.google.gson.JsonNull.INSTANCE)
        add(community.deepCopy().apply { addProperty("item", "DIRT") }); add(community)
    }
    check(parseDigestCommunityHistory(mixedFeed, now).single().id == community.get("id").asString)
    check(parseDigestCommunityHistory(com.google.gson.JsonArray(), now).isEmpty())
    check(runCatching { parseDigestCommunityHistory(community, now) }.isFailure)
    check(runCatching { parseDigestCommunityHistory(com.google.gson.JsonArray().apply { repeat(31) { add(community) } }, now) }.isFailure)

    val directory = Files.createTempDirectory("daily-digest-check")
    try {
        val file = directory.resolve("state.json")
        val store = DigestStateStore(file)
        check(store.load() == DigestSavedState() && !store.recovered)
        val entry = DigestRngEntry("a".repeat(32), "NECRON_HANDLE", "dungeon", 900000, "Player")
        val history = DigestRngHistory()
        check(history.add(entry, 100, now) && !history.add(entry, 100, now))
        check(!history.add(entry.copy(id = "b".repeat(32), item = "DIRT"), 100, now))
        check(!history.add(entry.copy(id = "b".repeat(32), player = "x".repeat(33)), 100, now))
        check(!history.add(entry.copy(id = "b".repeat(32), occurredAt = now + 60001), 100, now))
        repeat(25) { history.add(entry.copy(id = "%032x".format(it), occurredAt = 900000L + it), 20, now) }
        check(history.entries.size == 20 && history.entries.first().occurredAt == 900024L)
        repeat(40) { history.add(entry.copy(id = "%032x".format(100 + it), occurredAt = 950000L + it, community = true), 20, now) }
        check(history.entries.count { !it.community } == 20 && history.entries.count { it.community } == 30)
        val state = DigestSavedState(lastOpened = mapOf(account to day.toString()),
            profiles = mapOf(mainProfile to DigestProfileState(history = history.entries)),
            news = mapOf("game" to news.state(NewsSource.GAME)))
        store.save(state)
        check(store.load() == state)
        val legacyNews = com.google.gson.Gson().toJsonTree(state).asJsonObject
        val retiredNews = legacyNews.getAsJsonObject("news").get("game").deepCopy().asJsonObject
        retiredNews.getAsJsonArray("items").forEach { it.asJsonObject.addProperty("source", "ALPHA") }
        legacyNews.getAsJsonObject("news").add("alpha", retiredNews)
        Files.writeString(file, legacyNews.toString())
        check(store.load() == state && !store.recovered) { "Removing retired news invalidated normal news or local profile/RNG history" }
        store.save(store.load())
        check(!JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonObject("news").has("alpha"))
        Files.writeString(file, "{ damaged")
        check(store.load() == DigestSavedState() && store.recovered)
        store.save(state)
        check(store.load() == state && !store.recovered)
        check(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().startsWith("state.json.corrupt-") } })
        val queued = mutableListOf<() -> Unit>()
        val writerStore = DigestStateStore(directory.resolve("queued.json"))
        val writer = DigestStateWriter(writerStore, queued::add)
        writer.submit(state)
        val newer = state.copy(lastOpened = mapOf(account to day.plusDays(1).toString()))
        writer.submit(newer)
        check(queued.size == 1 && !Files.exists(directory.resolve("queued.json"))) // submit does no disk I/O.
        queued.removeAt(0).invoke()
        check(writerStore.load() == newer)
        val finalState = newer.copy(lastOpened = mapOf(account to day.plusDays(2).toString()))
        writer.submit(finalState)
        writer.flush() // A quick quit persists the latest queued snapshot.
        queued.removeAt(0).invoke()
        check(writerStore.load() == finalState) // A queued older writer cannot undo the shutdown save.
        history.clear()
        check(history.entries.isEmpty())
    } finally {
        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    println("Daily Digest checks passed: local day, delayed consent, profile isolation, asynchronous cache, corruption recovery and bounded history")
}
