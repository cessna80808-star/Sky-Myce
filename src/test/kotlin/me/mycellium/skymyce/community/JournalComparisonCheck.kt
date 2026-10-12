package me.mycellium.skymyce.community

import com.google.gson.JsonParser
import me.mycellium.skymyce.features.instances.dungeons.tracker.*
import java.time.Instant

internal fun journalComparisonChecks() {
    val monday = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(); val now = monday + 3600000
    val raw = JsonParser.parseString("""{"ok":true,"version":1,"metric":"rng_drops","period":"weekly","startAt":$monday,"endAt":$now,"participantCount":2,"totalDrops":5,"meanPerIdentity":2.5,"ownSharedCount":2,"ownAvailable":true,"updatedAt":$monday,"source":"Shared client reports","coverage":"Consenting participants"}""").asJsonObject
    val comparison = parseJournalCommunityComparison(raw, "weekly")
    check(comparison.mean == 2.5 && comparison.ownSharedCount == 2L)
    val owner = "a".repeat(32); val context = AcquisitionContext(owner, "b".repeat(32), "Apple")
    val first = AcquisitionEvent("1".repeat(32), "NECRON_HANDLE", monday, "dungeon", context, quantity = 2)
    val archive = AcquisitionArchive(events = listOf(first, first.copy(id = "2".repeat(32), context = context.copy(profileId = "c".repeat(32))),
        first.copy(id = "3".repeat(32), context = context.copy(account = "d".repeat(32)), quantity = 9),
        first.copy(id = "4".repeat(32), occurredAt = monday - 1), first.copy(id = "5".repeat(32), origin = "legacy-confirmed"),
        first.copy(id = "6".repeat(32), occurredAt = now + 1)))
    check(journalComparableLocalCount(archive, owner, comparison) == 2L) { "Shared comparison counts acquisition reports, not receipt item quantities" }
    val periods = journalPeriodCounts(archive, JournalFilter(), context, now)
    check(periods[JournalPeriod.ALL_TIME] == 4L && periods[JournalPeriod.WEEK] == 2L && periods[JournalPeriod.MONTH] == 4L)
    for (filter in listOf(JournalFilter(activity = "dungeon"), JournalFilter(floor = "F7"), JournalFilter(query = "handle"),
        JournalFilter(includeLegacy = true), JournalFilter(period = JournalPeriod.CUSTOM), JournalFilter(scope = TrackerScope.UNSCOPED_LEGACY)))
        check(journalCommunityPeriod(filter) == null)
    check(journalCommunityPeriod(JournalFilter(period = JournalPeriod.MONTH)) == "monthly")
    val optedOut = raw.deepCopy().apply { addProperty("ownAvailable", false); add("ownSharedCount", com.google.gson.JsonNull.INSTANCE) }
    check(parseJournalCommunityComparison(optedOut, "weekly").ownSharedCount == null)
    val empty = raw.deepCopy().apply { addProperty("participantCount", 0); addProperty("totalDrops", 0); addProperty("ownSharedCount", 0); add("meanPerIdentity", com.google.gson.JsonNull.INSTANCE); addProperty("updatedAt", 0) }
    check(parseJournalCommunityComparison(empty, "weekly").mean == null)
    for (invalid in listOf(raw.deepCopy().apply { addProperty("meanPerIdentity", "NaN") }, raw.deepCopy().apply { addProperty("totalDrops", -1) },
        raw.deepCopy().apply { addProperty("participantCount", 2.5) }, raw.deepCopy().apply { addProperty("ownAvailable", false) },
        raw.deepCopy().apply { addProperty("meanPerIdentity", 5) }, raw.deepCopy().apply { addProperty("startAt", monday - 1) }))
        check(runCatching { parseJournalCommunityComparison(invalid, "weekly") }.isFailure)
    check(runCatching { parseJournalCommunityComparison(raw, "monthly") }.isFailure)
    println("Journal comparison checks passed: compatible UTC periods, one Minecraft identity across profiles, alternate/legacy/future exclusion, empty/opt-out and strict response integrity")
}
