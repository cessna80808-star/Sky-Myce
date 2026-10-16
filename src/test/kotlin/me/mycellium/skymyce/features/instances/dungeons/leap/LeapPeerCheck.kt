package me.mycellium.skymyce.features.instances.dungeons.leap

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera.Status
import java.util.UUID
import kotlin.math.abs

private val peerAlice = UUID.fromString("12345678-1234-1234-1234-123456789abc")
private val peerBob = UUID.fromString("87654321-4321-4321-4321-cba987654321")
private const val peerServerTime = 1_000_000L
private const val peerNano = 10_000_000_000L
private const val peerMillis = 1_000_000L
private val peerContext = LeapPovContext("a".repeat(32), "mini123A", "M7", false, "b".repeat(32), mapOf(peerAlice to "Alice", peerBob to "Bob"))

internal fun checkLeapPeers() {
    val identity = LeapIdentity("Alice", peerAlice)
    val zero = LeapCameraPose(0.0, 0.0, 0.0, 0f, 0f)
    val cache = freshPeerCache()
    check(cache.ready(peerNano))
    check(cache.receive(peerFrame(), peerNano))
    check(cache.pose(identity, peerNano) == zero) // Actual zero coordinates are valid, not missing values.
    check(cache.pose(identity.copy(name = "ALICE"), peerNano) == zero)
    check(cache.pose(identity.copy(name = "Bob"), peerNano) == null)
    check(cache.pose(identity.copy(uuid = peerBob), peerNano) == null)
    check(cache.pose(identity.copy(uuid = null), peerNano) == null)
    check(cache.pose(identity, peerNano + 1499 * peerMillis) == zero)
    check(cache.pose(identity, peerNano + 1500 * peerMillis) == null)
    check(!cache.receive(peerFrame(), peerNano + 1500 * peerMillis))
    check(!cache.ready(peerNano + 14_900 * peerMillis))
    check(!LeapPovCache().receive(peerFrame(), peerNano))

    checkPeerMalformedFrames()
    checkPeerClockAndConsent()
    checkPeerOrderAndRevocation()
    checkPeerInterpolation()
    checkPeerCoordinateEviction()

    check(leapPovCaption(true, false, true, Status.LIVE, shared = true) == "Shared POV")
    check(leapPovCaption(true, false, true, Status.TERRAIN_ONLY, shared = true) == "Shared POV")
    check(leapPovCaption(true, false, true, Status.WAITING_CHUNKS, shared = true) == "World not loaded")
    check(leapPovCaption(true, false, true, Status.PREPARING, shared = true) == "Loading POV…")
    check(leapPovCaption(false, false, true, Status.LIVE, shared = true) == null)
    check(leapPovCaption(true, true, true, Status.LIVE, shared = true) == null)
}

private fun checkPeerMalformedFrames() {
    val invalid = listOf(
        "type" to JsonPrimitive("other"), "epoch" to JsonPrimitive("c".repeat(32)),
        "sourceEpoch" to JsonPrimitive("invalid"), "server" to JsonPrimitive("other"),
        "floor" to JsonPrimitive("F7"), "alpha" to JsonPrimitive("false"),
        "alpha" to JsonPrimitive(true), "party" to JsonPrimitive("c".repeat(32)),
        "uuid" to JsonPrimitive(peerBob.toString().replace("-", "")), "name" to JsonPrimitive("Bob"),
        "name" to JsonPrimitive("[MVP+] Alice"), "seq" to JsonPrimitive("1"),
        "seq" to JsonPrimitive(-1), "seq" to JsonPrimitive(1.5), "seq" to JsonPrimitive(9_007_199_254_740_992L),
        "timestamp" to JsonPrimitive("1000100"), "timestamp" to JsonPrimitive(0),
        "expiresAt" to JsonPrimitive(peerServerTime + 1601), "x" to JsonPrimitive("0"),
        "x" to JsonPrimitive(30_000_000.1), "y" to JsonPrimitive(4096.1), "z" to JsonPrimitive(-30_000_000.1),
        "x" to JsonPrimitive(Double.NaN), "y" to JsonPrimitive(Double.POSITIVE_INFINITY),
        "yaw" to JsonPrimitive(180.1), "pitch" to JsonPrimitive(-90.1),
        "pitch" to JsonPrimitive(false), "x" to JsonArray(), "y" to JsonNull.INSTANCE
    )
    invalid.forEach { (key, value) ->
        val frame = peerFrame().apply { add(key, value) }
        check(!freshPeerCache().receive(frame, peerNano)) { "Malformed peer field $key was accepted: $value" }
    }
    peerFrame().keySet().forEach { key ->
        check(!freshPeerCache().receive(peerFrame().apply { remove(key) }, peerNano)) { "Missing peer field $key was accepted" }
    }
    check(freshPeerCache().receive(peerFrame(sequence = 0), peerNano))
    check(freshPeerCache().receive(peerFrame(sequence = 9_007_199_254_740_991L), peerNano))
    check(freshPeerCache().receive(peerFrame(pose = LeapCameraPose(30_000_000.0, -4096.0, -30_000_000.0, 180f, -90f)), peerNano))
    check(!freshPeerCache().receive(peerFrame(timestamp = peerServerTime + 101), peerNano)) // A future timestamp cannot gain extra TTL.
    check(!freshPeerCache().receive(peerFrame(timestamp = peerServerTime - 1400), peerNano))
}

private fun checkPeerClockAndConsent() {
    val identity = LeapIdentity("Alice", peerAlice)
    val cache = freshPeerCache()
    check(cache.receive(peerFrame(timestamp = peerServerTime - 1300), peerNano)) // Only 100 ms remain.
    check(cache.pose(identity, peerNano + 99 * peerMillis) != null)
    check(cache.acknowledge(peerContext, peerServerTime + 145, peerServerTime + 15_145,
        peerNano + 45 * peerMillis, peerNano + 50 * peerMillis))
    check(cache.pose(identity, peerNano + 100 * peerMillis) == null) // Renewing the lease never extends an old sample.

    val stalled = LeapPovCache()
    check(stalled.acknowledge(peerContext, peerServerTime, peerServerTime + 15_000, peerNano - 2000 * peerMillis, peerNano))
    check(!stalled.receive(peerFrame(), peerNano)) // A delayed ACK must not make a stale frame appear fresh.
    val fractional = LeapPovCache()
    check(fractional.acknowledge(peerContext, peerServerTime, peerServerTime + 15_000, peerNano - 1, peerNano))
    check(fractional.receive(peerFrame(timestamp = peerServerTime), peerNano))
    check(fractional.pose(identity, peerNano + 1498 * peerMillis) != null)
    check(fractional.pose(identity, peerNano + 1499 * peerMillis) == null) // Round clock uncertainty upward.

    val contexts = listOf(peerContext.copy(epoch = "invalid"), peerContext.copy(server = ""),
        peerContext.copy(floor = "M8"), peerContext.copy(allowed = mapOf(peerAlice to "Alice", peerBob to "ALICE")),
        peerContext.copy(allowed = mapOf(UUID(0, 0) to "Alice")),
        peerContext.copy(allowed = (1L..5L).associate { UUID(0, it) to "Player$it" }))
    contexts.forEach { invalid ->
        check(!LeapPovCache().acknowledge(invalid, peerServerTime, peerServerTime + 15_000, peerNano, peerNano))
    }
    listOf("E", "ENTRANCE", "F1", "M7").forEach { floor ->
        check(LeapPovCache().acknowledge(peerContext.copy(floor = floor), peerServerTime, peerServerTime + 15_000, peerNano, peerNano))
    }
    val publisherOnly = LeapPovCache()
    check(publisherOnly.acknowledge(peerContext.copy(allowed = emptyMap()), peerServerTime, peerServerTime + 15_000, peerNano, peerNano))
    check(publisherOnly.ready(peerNano) && !publisherOnly.receive(peerFrame(), peerNano))

    val changed = freshPeerCache()
    check(changed.receive(peerFrame(), peerNano))
    val next = peerContext.copy(epoch = "c".repeat(32), allowed = mapOf(peerBob to "Bob"))
    check(changed.acknowledge(next, peerServerTime, peerServerTime + 15_000, peerNano, peerNano))
    check(changed.pose(identity, peerNano) == null && !changed.receive(peerFrame(), peerNano))
    check(!changed.receive(peerFrame().apply { addProperty("epoch", next.epoch) }, peerNano))
    changed.clear()
    check(!changed.ready(peerNano) && !changed.receive(peerFrame(), peerNano))
    check(changed.pose(identity, peerNano) == null)
    val invalidClock = freshPeerCache()
    check(!invalidClock.acknowledge(peerContext, peerServerTime, peerServerTime + 15_001, peerNano, peerNano))
    check(!invalidClock.ready(peerNano))
    check(!LeapPovCache().acknowledge(peerContext, peerServerTime, peerServerTime + 15_000, peerNano + 1, peerNano))
}

private fun checkPeerOrderAndRevocation() {
    val cache = freshPeerCache()
    val identity = LeapIdentity("Alice", peerAlice)
    check(cache.receive(peerFrame(sequence = 2), peerNano))
    check(!cache.receive(peerFrame(sequence = 2), peerNano))
    check(!cache.receive(peerFrame(sequence = 1), peerNano))
    check(!cache.receive(peerFrame(sequence = 3, timestamp = peerServerTime + 99), peerNano))
    val remove = JsonObject().apply {
        addProperty("type", "leap_pov_remove"); addProperty("epoch", peerContext.epoch)
        addProperty("uuid", peerAlice.toString().replace("-", "")); addProperty("sourceEpoch", "d".repeat(32))
    }
    check(!cache.remove(remove.deepCopy().apply { addProperty("sourceEpoch", "e".repeat(32)) }))
    check(!cache.remove(remove.deepCopy().apply { addProperty("epoch", "e".repeat(32)) }))
    check(cache.pose(identity, peerNano) != null)
    check(cache.remove(remove))
    check(cache.pose(identity, peerNano) == null)
    check(!cache.receive(peerFrame(sequence = 3, timestamp = peerServerTime + 200), peerNano + 100 * peerMillis))
    check(cache.receive(peerFrame(sequence = 0, timestamp = peerServerTime + 200, sourceEpoch = "e".repeat(32)), peerNano + 100 * peerMillis))
    check(!cache.remove(remove)) // Delayed removal for the old sender context cannot erase a fresh new context.
    check(cache.pose(identity, peerNano + 100 * peerMillis) != null)
    check(!cache.receive(peerFrame(sequence = 4, timestamp = peerServerTime + 300), peerNano + 200 * peerMillis)) // Retired nonce cannot return with a newer sequence.
    val fourth = (1L..4L).associate { UUID(0, it) to "Player$it" }
    val bounded = freshPeerCache(peerContext.copy(allowed = fourth))
    fourth.forEach { (uuid, name) ->
        check(bounded.receive(peerFrame().apply { addProperty("uuid", uuid.toString().replace("-", "")); addProperty("name", name) }, peerNano))
    }
    check(!bounded.receive(peerFrame(), peerNano))
}

private fun checkPeerInterpolation() {
    val identity = LeapIdentity("Alice", peerAlice)
    val cache = freshPeerCache()
    val first = LeapCameraPose(0.0, 1.6, 0.0, 179f, -20f)
    val last = LeapCameraPose(10.0, 3.6, 2.0, -179f, 20f)
    check(cache.receive(peerFrame(pose = first), peerNano))
    check(cache.receive(peerFrame(sequence = 2, timestamp = peerServerTime + 600, pose = last), peerNano + 500 * peerMillis))
    check(cache.pose(identity, peerNano + 500 * peerMillis) == first)
    val middle = checkNotNull(cache.pose(identity, peerNano + 750 * peerMillis))
    check(abs(middle.x - 5.0) < .00001 && abs(middle.y - 2.6) < .00001 && abs(middle.z - 1.0) < .00001)
    check(middle.yaw == -180f && middle.pitch == 0f)
    check(cache.pose(identity, peerNano + 1000 * peerMillis) == last)
    check(cache.pose(identity, peerNano + 1250 * peerMillis) == last) // No extrapolation beyond the actual sample.

    val teleport = freshPeerCache()
    check(teleport.receive(peerFrame(pose = first), peerNano))
    val jump = last.copy(x = 17.0)
    check(teleport.receive(peerFrame(sequence = 2, timestamp = peerServerTime + 600, pose = jump), peerNano + 500 * peerMillis))
    check(teleport.pose(identity, peerNano + 500 * peerMillis) == jump)
    val expiredPrevious = freshPeerCache()
    check(expiredPrevious.receive(peerFrame(pose = first), peerNano))
    check(expiredPrevious.receive(peerFrame(sequence = 2, timestamp = peerServerTime + 1300, pose = last), peerNano + 1200 * peerMillis))
    check(expiredPrevious.pose(identity, peerNano + 1500 * peerMillis) == last)
    check(!expiredPrevious.receive(peerFrame(sequence = 3, timestamp = peerServerTime + 1600), peerNano + 3000 * peerMillis))
}

private fun checkPeerCoordinateEviction() {
    val cache = freshPeerCache()
    check(cache.receive(peerFrame(), peerNano))
    check(cache.receive(peerFrame(sequence = 2, timestamp = peerServerTime + 600), peerNano + 500 * peerMillis))
    fun payload(name: String): Any? {
        val field = LeapPovCache::class.java.getDeclaredField("peers").apply { isAccessible = true }
        val peer = (field.get(cache) as Map<*, *>)[peerAlice]!!
        return peer.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(peer)
    }
    check(payload("current") != null && payload("previous") != null)
    check(cache.ready(peerNano + 1500 * peerMillis))
    check(payload("previous") == null && payload("current") != null)
    check(cache.ready(peerNano + 2000 * peerMillis))
    check(payload("previous") == null && payload("current") == null) // Coordinates leave RAM even while the menu stays open.
    check(!cache.receive(peerFrame(sequence = 1, timestamp = peerServerTime + 2100), peerNano + 2000 * peerMillis))
}

private fun freshPeerCache(context: LeapPovContext = peerContext) = LeapPovCache().apply {
    check(acknowledge(context, peerServerTime, peerServerTime + 15_000, peerNano - 100 * peerMillis, peerNano))
}

private fun peerFrame(sequence: Long = 1, timestamp: Long = peerServerTime + 100,
    sourceEpoch: String = "d".repeat(32), pose: LeapCameraPose = LeapCameraPose(0.0, 0.0, 0.0, 0f, 0f)) = JsonObject().apply {
    addProperty("type", "leap_pov_pose"); addProperty("epoch", peerContext.epoch); addProperty("sourceEpoch", sourceEpoch)
    addProperty("uuid", peerAlice.toString().replace("-", "")); addProperty("name", "Alice"); addProperty("seq", sequence)
    addProperty("server", peerContext.server); addProperty("floor", peerContext.floor); addProperty("alpha", peerContext.alpha); addProperty("party", peerContext.party)
    addProperty("x", pose.x); addProperty("y", pose.y); addProperty("z", pose.z); addProperty("yaw", pose.yaw); addProperty("pitch", pose.pitch)
    addProperty("timestamp", timestamp); addProperty("expiresAt", timestamp + 1500)
}
