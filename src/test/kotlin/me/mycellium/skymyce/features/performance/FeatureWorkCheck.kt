package me.mycellium.skymyce.features.performance

import com.sun.management.ThreadMXBean
import me.mycellium.skymyce.ui.AutoGraphicsController
import me.mycellium.skymyce.ui.AutoResponse
import me.mycellium.skymyce.features.instances.dungeons.friends.FriendActivity
import me.mycellium.skymyce.features.instances.dungeons.friends.OnlineDungeonFriend
import me.mycellium.skymyce.features.social.CosmeticIdentities
import me.mycellium.skymyce.features.social.CosmeticCache
import me.mycellium.skymyce.features.social.CosmeticProfile
import java.lang.management.ManagementFactory
import java.util.UUID

/** Source-operation comparisons, not Minecraft FPS/tick or a real Hypixel workload. */
fun main() {
    val names = List(256) { "Friend$it" }
    val ids = List(256) { UUID(0, it.toLong() + 1) }
    val before = PreviousCosmeticIdentities()
    val after = CosmeticIdentities()
    names.indices.forEach { before.observe(names[it], ids[it], 1); after.observe(names[it], ids[it], 1) }
    var time = 2L
    val oldIdentity = measure {
        repeat(12) { names.indices.forEach { before.observe(names[it], ids[it], time++) } }
    }
    val newIdentity = measure {
        repeat(12) { names.indices.forEach { after.observe(names[it], ids[it], time++) } }
    }
    check(before.resolved() == after.resolved())
    report("Repeated 256-player identity observations (3072 observations)", oldIdentity, newIdentity)

    val oldRendered = linkedMapOf<UUID, CosmeticProfile>()
    val rendered = CosmeticCache()
    ids.forEach { id -> CosmeticProfile(id, null, 1f, 1, 1).let { oldRendered[id] = it; rendered.apply(it, 1000) } }
    var oldScale = 0.0
    val oldTouch = measure { repeat(20) { ids.forEach { id ->
        val profile = oldRendered.getValue(id)
        oldRendered.remove(id); oldRendered[id] = profile
        oldScale += profile.scale
    } } }
    var newScale = 0.0
    val newTouch = measure { repeat(20) { ids.forEach { id -> newScale += requireNotNull(rendered.get(id, 1001, touch = true)).scale } } }
    check(oldScale == newScale && rendered.size == 256)
    report("Touch 256 rendered cosmetic records (5120 reads)", oldTouch, newTouch)

    val locations = listOf("in Dungeons", "in Dungeon Hub", "in Private Island", "in Crystal Hollows", "in Bed Wars", "Offline", "Unknown")
    val friends = List(256) { OnlineDungeonFriend(names[it], locations[it % locations.size]) }
    friends.forEach { check(it.activity == previousActivity(it.location)) }
    val original = OnlineDungeonFriend("Alice", "in Dungeons")
    check(original.activity == FriendActivity.IN_RUN)
    check(original.copy(location = "Offline").activity == FriendActivity.OFFLINE)
    check(original.copy(location = "in Dungeon Hub").activity == FriendActivity.IDLE)
    check(original.activity == FriendActivity.IN_RUN) // Copying cannot mutate a prior snapshot.
    var expected = 0L
    val oldActivity = measure { repeat(20) { friends.forEach { expected += previousActivity(it.location).ordinal } } }
    var actual = 0L
    val newActivity = measure { repeat(20) { friends.forEach { actual += it.activity.ordinal } } }
    check(expected == actual)
    report("Read 256 immutable friend activities (5120 reads)", oldActivity, newActivity)

    autoGraphicsDifferential()
    // One Auto graphics sample per client tick (20 Hz), steady state with a full 15 s Normal window (300 entries per series).
    val oldAuto = PreviousAutoGraphicsController(); val newAuto = AutoGraphicsController()
    var oldClock = 0L; var newClock = 0L
    repeat(400) { oldAuto.sample(oldClock, 60, 16.6, 60, AutoResponse.NORMAL); oldClock += 50 }
    repeat(400) { newAuto.sample(newClock, 60, 16.6, 60, AutoResponse.NORMAL); newClock += 50 }
    val oldSamples = measure { repeat(200) { oldAuto.sample(oldClock, 58 + (oldClock / 50 % 5).toInt(), 15.0 + oldClock % 7 / 3.0, 60, AutoResponse.NORMAL); oldClock += 50 } }
    val newSamples = measure { repeat(200) { newAuto.sample(newClock, 58 + (newClock / 50 % 5).toInt(), 15.0 + newClock % 7 / 3.0, 60, AutoResponse.NORMAL); newClock += 50 } }
    check(oldAuto.quality == newAuto.quality && oldAuto.reason == newAuto.reason)
    report("Auto graphics steady-state samples (200 ticks)", oldSamples, newSamples)
    if (newSamples.bytes != null) check(newSamples.bytes < 200 * 64) { "Auto graphics allocates ${newSamples.bytes} B per 200 samples" }
    // Community and Loader ticks compare the account id every client tick; the UUID only changes on an account switch.
    val account = UUID.fromString("2c1f6a9e-4b7d-4e0a-9c55-7d3a1e0f8b21")
    val compact = me.mycellium.skymyce.utils.CompactProfileId()
    var oldMatches = 0; var newMatches = 0
    val oldProfile = measure { repeat(1_000) { if (account.toString().replace("-", "") == "2c1f6a9e4b7d4e0a9c557d3a1e0f8b21") oldMatches++ } }
    val newProfile = measure { repeat(1_000) { if (compact.of(account) == "2c1f6a9e4b7d4e0a9c557d3a1e0f8b21") newMatches++ } }
    check(oldMatches == newMatches && compact.of(UUID(0, 1)) == UUID(0, 1).toString().replace("-", "") && compact.of(account) == "2c1f6a9e4b7d4e0a9c557d3a1e0f8b21")
    report("Account id comparison per client tick (1000 ticks)", oldProfile, newProfile)
    println("Feature work checks passed; warmed median JVM wall/allocation only, no live-client performance claim.")
}

private data class Measurement(val nanos: Long, val bytes: Long?)
private fun measure(work: () -> Unit): Measurement {
    val bean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }
    if (bean != null) bean.isThreadAllocatedMemoryEnabled = true
    repeat(4) { work() }
    val thread = Thread.currentThread().threadId()
    val samples = List(5) {
        val allocation = bean?.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        work()
        Measurement(System.nanoTime() - start, allocation?.let { bean!!.getThreadAllocatedBytes(thread) - it })
    }
    return Measurement(samples.map { it.nanos }.sorted()[2], samples.mapNotNull { it.bytes }.sorted().getOrNull(2))
}
private fun report(label: String, before: Measurement, after: Measurement) {
    println("$label: ${"%.3f".format(before.nanos / 1e6)} -> ${"%.3f".format(after.nanos / 1e6)} ms; " +
        "${before.bytes ?: "unavailable"} -> ${after.bytes ?: "unavailable"} allocated B")
}

/** Exact pre-edit repeated-observation path retained only as a bounded benchmark fixture. */
private class PreviousCosmeticIdentities(private val maximum: Int = 512) {
    private data class Entry(val name: String, var uuid: UUID?, var seen: Long, val byName: Boolean, var checked: Long? = null, var priorityUntil: Long = 0)
    private val entries = linkedMapOf<String, Entry>()
    fun observe(name: String, uuid: UUID?, now: Long, priority: Boolean = false) {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        val key = name.lowercase()
        val previous = entries[key]
        if (!priority && uuid != null && entries.any { it.key != key && it.value.uuid == uuid && it.value.priorityUntil > now }) return
        if (previous == null && entries.size >= maximum) {
            if (!priority) return
            entries.remove(entries.entries.firstOrNull { it.value.priorityUntil <= now }?.key ?: entries.keys.first())
        }
        entries.remove(key)
        if (uuid != null) entries.entries.removeIf { it.value.uuid == uuid }
        val entry = if (uuid == null && previous != null) previous.apply { seen = now }
            else Entry(name, uuid, now, uuid == null)
        entry.priorityUntil = maxOf(previous?.priorityUntil ?: 0, if (priority) now + 600000 else 0)
        entries[key] = entry
    }
    fun resolved() = entries.values.mapNotNull { entry -> entry.uuid?.let { it to entry.name } }.toMap()
}

/** Exact previous getter implementation. The new parser retains these classifications. */
private fun previousActivity(location: String): FriendActivity = when {
    location.contains("offline", true) -> FriendActivity.OFFLINE
    location.contains("limbo", true) -> FriendActivity.LIMBO
    location.contains("Dungeon Hub", true) -> FriendActivity.IDLE
    Regex("(?i)\\b(?:dungeons?|catacombs)\\b").containsMatchIn(location) -> FriendActivity.IN_RUN
    Regex("(?i)\\b(?:hub|private island)\\b").containsMatchIn(location) || location.equals("in Island", true) -> FriendActivity.IDLE
    location.contains("SkyBlock", true) || Regex("(?i)\\b(?:mines|hollows|garden|crimson|end|park|rift|spider)\\b").containsMatchIn(location) -> FriendActivity.SKYBLOCK
    location.isBlank() || location.equals("Unknown", true) || location.contains("offline", true) -> FriendActivity.UNKNOWN
    else -> FriendActivity.OTHER_GAME
}

/**
 * Replays seeded random measurement streams through the 2.7.29 controller and the current one, asserting identical tiers,
 * reasons, change times and summaries after every call: clock jumps backwards and forwards, pauses, invalid frame times,
 * zero FPS, every response and lowest tier, and frame caps.
 */
private fun autoGraphicsDifferential() {
    val random = java.util.Random(26_1_2)
    repeat(300) { stream ->
        val old = PreviousAutoGraphicsController(); val new = AutoGraphicsController()
        var now = random.nextLong(-1_000_000, 1_000_000_000)
        val response = AutoResponse.entries[stream % AutoResponse.entries.size]
        val lowest = AutoGraphicsController.AUTO_TIERS[random.nextInt(AutoGraphicsController.AUTO_TIERS.size)]
        var fps = 20 + random.nextInt(200); var frame = 1000.0 / fps
        repeat(2_000) {
            now += when (random.nextInt(100)) { 0 -> -random.nextLong(1, 30_000); 1 -> random.nextLong(10_000, 120_000); else -> 40L + random.nextInt(25) }
            if (random.nextInt(50) == 0) { fps = random.nextInt(300); frame = if (fps == 0) 0.0 else 1000.0 / fps }
            val sampleFps = if (random.nextInt(40) == 0) 0 else (fps + random.nextInt(11) - 5).coerceAtLeast(0)
            val sampleFrame = when (random.nextInt(60)) { 0 -> Double.NaN; 1 -> Double.POSITIVE_INFINITY; 2 -> -1.0; 3 -> 2_500.0; else -> frame * (0.5 + random.nextDouble()) }
            val target = if (random.nextInt(30) == 0) 0 else 30 + random.nextInt(200)
            if (random.nextInt(400) == 0) { old.pause(now); new.pause(now) }
            val a = old.sample(now, sampleFps, sampleFrame, target, response, lowest)
            val b = new.sample(now, sampleFps, sampleFrame, target, response, lowest)
            check(a == b && old.tier == new.tier && old.reason == new.reason && old.lastChange == new.lastChange) { "Auto graphics diverged in stream $stream" }
            check(old.summary(now) == new.summary(now)) { "Auto graphics summary diverged in stream $stream" }
        }
    }
    check(AutoGraphicsController.percentile(listOf(1.0, 2.0, 3.0, 100.0), 0.5) == PreviousAutoGraphicsController.percentile(listOf(1.0, 2.0, 3.0, 100.0), 0.5))
}
