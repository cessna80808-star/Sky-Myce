package me.mycellium.skymyce.features.instances.dungeons.leap

import com.google.gson.JsonObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class LeapPovContext(val epoch: String, val server: String, val floor: String, val alpha: Boolean,
    val party: String, val allowed: Map<UUID, String>)

/** Four consenting peers, held only in memory and bounded by the relay's clock rather than the computer's wall clock. */
class LeapPovCache {
    private data class Clock(val upperMillis: Long, val receivedNanos: Long, val leaseExpiresNanos: Long)
    private data class TimedPose(val pose: LeapCameraPose, val timestamp: Long, val receivedNanos: Long, val expiresNanos: Long)
    private data class Peer(val sourceEpoch: String, val sequence: Long, val timestamp: Long, val expiresNanos: Long,
        val previous: TimedPose?, val current: TimedPose?, val retired: Map<String, Long>, val revoked: Boolean = false)
    private data class Frame(val uuid: UUID, val sourceEpoch: String, val sequence: Long,
        val timestamp: Long, val expiresAt: Long, val pose: LeapCameraPose)

    private var context: LeapPovContext? = null
    private var clock: Clock? = null
    private val peers = mutableMapOf<UUID, Peer>()

    fun acknowledge(next: LeapPovContext, serverTime: Long, leaseExpiresAt: Long, sentNanos: Long, receivedNanos: Long): Boolean {
        val elapsed = receivedNanos - sentNanos
        if (!validContext(next) || serverTime !in 1..MAX_INTEGER - LEASE_MILLIS ||
            leaseExpiresAt - serverTime !in 1..LEASE_MILLIS || elapsed !in 0..LEASE_MILLIS * NANOS_PER_MILLI) {
            clear(); return false
        }
        val upper = serverTime + ceilMillis(elapsed)
        val remaining = leaseExpiresAt - upper
        if (remaining <= 0) { clear(); return false }
        if (context != next) peers.clear()
        context = next.copy(allowed = next.allowed.toMap())
        clock = Clock(upper, receivedNanos, receivedNanos + remaining * NANOS_PER_MILLI)
        return true
    }

    fun ready(nowNanos: Long): Boolean {
        val valid = clock?.let { nowNanos - it.receivedNanos >= 0 && it.leaseExpiresNanos - nowNanos > 0 } == true
        peers.entries.forEach { entry ->
            val peer = entry.value
            val current = peer.current?.takeIf { valid && it.expiresNanos - nowNanos > 0 }
            val previous = peer.previous?.takeIf { valid && it.expiresNanos - nowNanos > 0 }
            if (current !== peer.current || previous !== peer.previous) entry.setValue(peer.copy(current = current, previous = previous))
        }
        return valid
    }

    fun receive(json: JsonObject, nowNanos: Long): Boolean {
        val currentContext = context ?: return false
        val upperNow = upperServerNow(nowNanos) ?: return false
        val frame = decode(json, currentContext) ?: return false
        val remaining = frame.expiresAt - upperNow
        if (remaining !in 1..POSE_MILLIS) return false
        val prior = peers[frame.uuid]
        var retired = prior?.retired?.filterValues { it - nowNanos > 0 }.orEmpty()
        if (frame.sourceEpoch in retired) return false
        if (prior != null && (frame.timestamp < prior.timestamp ||
                frame.sourceEpoch == prior.sourceEpoch && (frame.sequence <= prior.sequence || prior.revoked) ||
                frame.sourceEpoch != prior.sourceEpoch && frame.timestamp <= prior.timestamp)) return false
        if (prior != null && frame.sourceEpoch != prior.sourceEpoch && prior.expiresNanos - nowNanos > 0) {
            if (retired.size >= 8) return false
            retired = retired + (prior.sourceEpoch to prior.expiresNanos)
        }
        val previous = prior?.takeIf { it.sourceEpoch == frame.sourceEpoch }?.current?.takeIf {
            it.expiresNanos - nowNanos > 0 && distanceSquared(it.pose, frame.pose) < 16.0 * 16.0
        }
        val pose = TimedPose(frame.pose, frame.timestamp, nowNanos, nowNanos + remaining * NANOS_PER_MILLI)
        peers[frame.uuid] = Peer(frame.sourceEpoch, frame.sequence, frame.timestamp, pose.expiresNanos, previous, pose, retired)
        return true
    }

    fun remove(json: JsonObject): Boolean {
        val currentContext = context ?: return false
        if (string(json, "type") != "leap_pov_remove" || string(json, "epoch") != currentContext.epoch) return false
        val uuid = uuid(string(json, "uuid")) ?: return false
        val sourceEpoch = string(json, "sourceEpoch") ?: return false
        val peer = peers[uuid]?.takeIf { it.sourceEpoch == sourceEpoch } ?: return false
        peers[uuid] = peer.copy(previous = null, current = null, revoked = true)
        return true
    }

    fun pose(identity: LeapIdentity, nowNanos: Long): LeapCameraPose? {
        if (!ready(nowNanos)) return null
        val uuid = identity.uuid ?: return null
        val expected = context?.allowed?.get(uuid) ?: return null
        if (normalizedLeapName(expected) != identity.key) return null
        val peer = peers[uuid] ?: return null
        val current = peer.current?.takeIf { it.expiresNanos - nowNanos > 0 && nowNanos - it.receivedNanos >= 0 } ?: return null
        val previous = peer.previous?.takeIf { it.expiresNanos - nowNanos > 0 } ?: return current.pose
        val duration = (current.timestamp - previous.timestamp).coerceAtMost(500)
        if (duration <= 0) return current.pose
        val progress = ((nowNanos - current.receivedNanos).toDouble() / (duration * NANOS_PER_MILLI)).coerceIn(0.0, 1.0)
        val yawDelta = ((current.pose.yaw.toDouble() - previous.pose.yaw) % 360 + 540) % 360 - 180
        return LeapCameraPose(previous.pose.x + (current.pose.x - previous.pose.x) * progress,
            previous.pose.y + (current.pose.y - previous.pose.y) * progress,
            previous.pose.z + (current.pose.z - previous.pose.z) * progress,
            (((previous.pose.yaw + yawDelta * progress) % 360 + 540) % 360 - 180).toFloat(),
            (previous.pose.pitch + (current.pose.pitch - previous.pose.pitch) * progress).toFloat())
    }

    fun clear() { context = null; clock = null; peers.clear() }

    private fun upperServerNow(nowNanos: Long): Long? {
        if (!ready(nowNanos)) return null
        val current = clock ?: return null
        return current.upperMillis + ceilMillis(nowNanos - current.receivedNanos)
    }

    private fun decode(json: JsonObject, current: LeapPovContext): Frame? {
        if (string(json, "type") != "leap_pov_pose" || string(json, "epoch") != current.epoch ||
            string(json, "server") != current.server || string(json, "floor") != current.floor ||
            boolean(json, "alpha") != current.alpha || string(json, "party") != current.party) return null
        val uuid = uuid(string(json, "uuid")) ?: return null
        val expected = current.allowed[uuid] ?: return null
        val name = string(json, "name")?.takeIf { NAME.matches(it) } ?: return null
        if (!name.equals(expected, ignoreCase = true)) return null
        val sourceEpoch = string(json, "sourceEpoch")?.takeIf { HEX.matches(it) } ?: return null
        val sequence = integer(json, "seq")?.takeIf { it in 0..MAX_INTEGER } ?: return null
        val timestamp = integer(json, "timestamp")?.takeIf { it in 1..MAX_INTEGER - POSE_MILLIS } ?: return null
        val expiresAt = integer(json, "expiresAt")?.takeIf { it == timestamp + POSE_MILLIS } ?: return null
        val x = number(json, "x")?.takeIf { abs(it) <= 30_000_000 } ?: return null
        val y = number(json, "y")?.takeIf { abs(it) <= 4096 } ?: return null
        val z = number(json, "z")?.takeIf { abs(it) <= 30_000_000 } ?: return null
        val yaw = number(json, "yaw")?.takeIf { it in -180.0..180.0 } ?: return null
        val pitch = number(json, "pitch")?.takeIf { it in -90.0..90.0 } ?: return null
        return Frame(uuid, sourceEpoch, sequence, timestamp, expiresAt, LeapCameraPose(x, y, z, yaw.toFloat(), pitch.toFloat()))
    }

    private companion object {
        const val POSE_MILLIS = 1500L
        const val LEASE_MILLIS = 15_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_INTEGER = 9_007_199_254_740_991L
        val HEX = Regex("[a-f0-9]{32}")
        val NAME = Regex("[A-Za-z0-9_]{1,16}")
        val SERVER = Regex("[A-Za-z0-9_-]{1,64}")
        val FLOOR = Regex("E|ENTRANCE|[FM][1-7]")

        fun validContext(value: LeapPovContext) = HEX.matches(value.epoch) && HEX.matches(value.party) &&
            SERVER.matches(value.server) && FLOOR.matches(value.floor) && value.allowed.size <= 4 &&
            value.allowed.all { (uuid, name) -> uuid != UUID(0, 0) && NAME.matches(name) } &&
            value.allowed.values.map { it.lowercase(Locale.ROOT) }.distinct().size == value.allowed.size

        fun ceilMillis(nanos: Long) = nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI == 0L) 0 else 1
        fun distanceSquared(first: LeapCameraPose, second: LeapCameraPose) =
            (first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y) + (first.z - second.z) * (first.z - second.z)

        fun string(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun boolean(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        fun number(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asDouble.takeIf(Double::isFinite) }.getOrNull() }
        fun integer(json: JsonObject, key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() }
        fun uuid(value: String?): UUID? = value?.takeIf { HEX.matches(it) }?.let {
            UUID.fromString("${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}")
        }
    }
}
