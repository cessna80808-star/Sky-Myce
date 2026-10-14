package me.mycellium.skymyce.community

import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun leaderboardChecks() {
    journalComparisonChecks()
    leaderboardSchedulingChecks()
    val result = JsonParser.parseString("""{"ok":true,"version":1,"metricLabel":"Recorded profit","description":"New timestamped estimates","unit":"coins","source":"Client reports","coverage":"Consenting participants only","startAt":null,"endAt":1000,"updatedAt":900,"total":3,"hasNext":false,"entries":[{"rank":1,"player":"Anonymous","value":12.5,"sampleSize":2,"updatedAt":900},{"rank":1,"player":"Shared Name","value":12.5,"sampleSize":3,"updatedAt":850},{"rank":3,"player":"Other","value":-4,"sampleSize":1,"updatedAt":800}]}""").asJsonObject
    val parsed = parseLeaderboard(result)
    check(parsed.entries.map { it.rank } == listOf(1, 1, 3) && parsed.entries.last().value == -4.0)
    check(parsed.unit == "coins" && parsed.start == null && parsed.entries.first().player == "Anonymous")
    check(formatLeaderboardValue(300001.5, "milliseconds") == "5:00.002")
    check(formatLeaderboardValue(999.5, "milliseconds") == "0:01.000")
    check(formatLeaderboardValue(3599999.5, "milliseconds") == "1:00:00.000")
    check(formatLeaderboardValue(86400000.0, "milliseconds") == "24:00:00.000")
    check(formatLeaderboardValue(12.5, "coins") == "12.5 coins")
    val timings = result.deepCopy().apply {
        addProperty("unit", "milliseconds")
        getAsJsonArray("entries").remove(2)
        getAsJsonArray("entries")[0].asJsonObject.addProperty("value", 300001.5)
    }
    check(parseLeaderboard(timings).entries.first().value == 300001.5)
    for (invalid in listOf(0.0, -1.0, 86400001.0))
        check(runCatching { parseLeaderboard(timings.deepCopy().apply { getAsJsonArray("entries")[0].asJsonObject.addProperty("value", invalid) }) }.isFailure)
    val absent = result.deepCopy().apply { addProperty("available", false); addProperty("reason", "No real publisher"); add("entries", com.google.gson.JsonArray()) }
    check(parseLeaderboard(absent).unavailable == "No real publisher")
    for (bad in listOf(result.deepCopy().apply { getAsJsonArray("entries")[0].asJsonObject.addProperty("rank", 0) },
        result.deepCopy().apply { getAsJsonArray("entries")[0].asJsonObject.addProperty("value", "NaN") },
        result.deepCopy().apply { getAsJsonArray("entries")[0].asJsonObject.addProperty("player", "Bad\nName") },
        result.deepCopy().apply { getAsJsonArray("entries")[0].asJsonObject.addProperty("sampleSize", -1) },
        result.deepCopy().apply { repeat(26) { getAsJsonArray("entries").add(result.getAsJsonArray("entries")[0].deepCopy()) } }))
        check(runCatching { parseLeaderboard(bad) }.isFailure)
    println("Leaderboard client checks passed: ties, losses, anonymous rows, source/unit labels, unavailable metrics and bounded malformed results")
}

private fun leaderboardSchedulingChecks() {
    val drains = ArrayDeque<Runnable>()
    val worker = LatestCommunityRequestWorker(Executor { drains.addLast(it) })
    val owner = Any()
    var epoch = 0
    val loaded = mutableListOf<Int>()
    val accepted = mutableListOf<Int>()
    repeat(10_000) { value ->
        epoch = value
        worker.submit(owner, { epoch == value }, { loaded += value; value }) { accepted += it.getOrThrow() }
    }
    check(drains.size == 1) { "Rapid selections queued more than one drain" }
    drains.removeFirst().run()
    check(loaded == listOf(9999) && accepted == loaded && drains.isEmpty()) { "An obsolete queued selection performed a request" }

    // A refreshed/disconnected owner becomes invalid before HTTP starts, even without a replacement request.
    var authorized = true
    worker.submit(owner, { authorized }, { error("A stale owner reached HTTP") }) { error("A stale result was delivered") }
    authorized = false; drains.removeFirst().run()
    worker.submit(owner, { true }, { error("A removed screen reached HTTP") }) { error("A removed screen received a result") }
    worker.cancel(owner); drains.removeFirst().run()

    // A failed request cannot wedge the worker or prevent the next latest request from being processed.
    var failed = false
    worker.submit(owner, { true }, { throw IllegalStateException("fixture") }) { failed = it.isFailure }
    drains.removeFirst().run(); check(failed)
    worker.submit(owner, { true }, { 10_000 }) { accepted += it.getOrThrow() }
    drains.removeFirst().run(); check(accepted.last() == 10_000)

    // Hold a running request across thousands of selections and screen removal. A transport may ignore
    // interruption until its own deadline; memory must stay bounded and its result must remain suppressed.
    val executor = Executors.newSingleThreadExecutor { Thread(it, "leaderboard scheduling fixture").apply { isDaemon = true } }
    val concurrent = LatestCommunityRequestWorker(executor)
    val oldScreen = Any(); val newScreen = Any()
    val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
    val interruptionSeen = CountDownLatch(1)
    val interrupted = AtomicBoolean(false); val selection = AtomicInteger(0)
    val requests = mutableListOf<Int>(); val results = mutableListOf<Int>()
    try {
        concurrent.submit(oldScreen, { true }, {
            requests += -1; started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (release.count != 0L) {
                try { check(release.await((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)) }
                catch (_: InterruptedException) { interrupted.set(true); interruptionSeen.countDown() }
            }
            -1
        }) { results += it.getOrThrow() }
        check(started.await(2, TimeUnit.SECONDS))
        repeat(5_000) { value ->
            selection.set(value)
            concurrent.submit(newScreen, { selection.get() == value }, { requests += value; value }) {
                results += it.getOrThrow(); finished.countDown()
            }
        }
        // Closing the old screen must interrupt it, without dropping the new screen's final request.
        concurrent.cancel(oldScreen); check(interruptionSeen.await(2, TimeUnit.SECONDS)); release.countDown()
        check(finished.await(2, TimeUnit.SECONDS))
        check(interrupted.get() && requests == listOf(-1, 4999) && results == listOf(4999)) {
            "Removal lost the latest destination, failed to cancel the old request, or delivered stale data"
        }
    } finally { release.countDown(); executor.shutdownNow() }
    println("Leaderboard scheduling checks passed: bounded latest selection, authority before request, failure recovery and screen-removal interruption/stale suppression")
}
