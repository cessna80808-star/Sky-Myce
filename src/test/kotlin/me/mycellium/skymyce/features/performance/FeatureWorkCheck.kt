package me.mycellium.skymyce.features.performance

import com.sun.management.ThreadMXBean
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
