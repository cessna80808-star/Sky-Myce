package me.mycellium.skymyce.features.instances.dungeons.camera

import kotlin.math.abs

enum class CrushStage { PURPLE, YELLOW }

data class CrushPoint(val x: Double, val y: Double, val z: Double) {
    fun valid() = x.isFinite() && y.isFinite() && z.isFinite() &&
        x in -29_999_984.0..29_999_984.0 && z in -29_999_984.0..29_999_984.0 && y in -2048.0..2048.0
    fun distanceSquared(other: CrushPoint) = (x - other.x) * (x - other.x) +
        (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
}

data class CrushBounds(val minX: Double, val minY: Double, val minZ: Double,
                       val maxX: Double, val maxY: Double, val maxZ: Double) {
    fun valid() = CrushPoint(minX, minY, minZ).valid() && CrushPoint(maxX, maxY, maxZ).valid() &&
        minX < maxX && minY < maxY && minZ < maxZ
    fun intersects(other: CrushBounds) = valid() && other.valid() && minX < other.maxX && maxX > other.minX &&
        minY < other.maxY && maxY > other.minY && minZ < other.maxZ && maxZ > other.minZ
}

data class CrushEncounterContext(val world: Long, val enabled: Boolean, val floor: String?, val healer: Boolean,
                                 val alive: Boolean, val inBoss: Boolean, val calibrated: Boolean) {
    val eligible get() = enabled && floor in setOf("F7", "M7") && healer && alive && inBoss
}

data class LeapDestination(val name: String, val uuid: String, val berserker: Boolean, val alive: Boolean, val point: CrushPoint)

/** Times are monotonic milliseconds. Item usage, elapsed time, and ordinary position samples cannot activate it. */
class HealerCrushSequence {
    var stage: CrushStage? = null
        private set
    var status = "Outside a Healer F7/M7 encounter"
        private set
    var stormActive = false
        private set
    private var context: CrushEncounterContext? = null
    private var phaseStarted = 0L
    private var opened = 0L
    private var consumed = false
    private var leap: Pair<String, Long>? = null
    private data class Correction(val id: Int, val from: CrushPoint, val to: CrushPoint, val at: Long)
    private var correction: Correction? = null
    private var verifiedLeapAt: Long? = null
    private val corrections = LinkedHashSet<Int>()
    private var lastConfirmation: String? = null

    fun context(value: CrushEncounterContext, now: Long) {
        if (context?.world != value.world || !value.eligible) reset(if (!value.enabled) "Disabled" else "Outside a living Healer F7/M7 boss encounter")
        context = value
        if (!value.eligible) return
        if (!value.calibrated) {
            stage = null; verifiedLeapAt = null; leap = null; correction = null
            status = "Set and validate both hanging pillars and camera viewpoints in a real Storm encounter"
        } else if (!stormActive) status = "Waiting for verified Storm phase entry"
        else if (!consumed && stage == null && verifiedLeapAt == null) status = "Waiting for a successful leap to a living Berserker"
        tick(now)
    }

    fun stormStarted(now: Long) {
        if (context?.eligible != true || stormActive) return
        stormActive = true; phaseStarted = now; consumed = false; lastConfirmation = null
        leap = null; correction = null; verifiedLeapAt = null; corrections.clear()
        status = if (context?.calibrated == true) "Waiting for a successful leap to a living Berserker" else
            "Storm observed; both hanging pillars and camera viewpoints need calibration"
    }

    fun leapSucceeded(name: String, now: Long, destination: LeapDestination?) {
        if (!ready() || consumed || stage != null || !name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        // A valid server success message to another role also invalidates the older pending success.
        leap = if (destination?.name == name && destination.berserker && destination.alive && destination.point.valid()) name to now else null
        if (leap == null) { verifiedLeapAt = null; status = "Leap destination is not a verified living Berserker"; return }
        verify(now, destination)
    }

    fun positionCorrected(id: Int, from: CrushPoint, to: CrushPoint, now: Long, destination: LeapDestination?) {
        if (!ready() || consumed || stage != null || !from.valid() || !to.valid() || !corrections.add(id)) return
        while (corrections.size > 32) corrections.remove(corrections.first())
        correction = Correction(id, from, to, now)
        verify(now, destination)
    }

    private fun verify(now: Long, destination: LeapDestination?) {
        val success = leap ?: return
        val move = correction ?: return
        if (abs(success.second - move.at) > 2_000L || now - success.second !in 0..2_000L || now - move.at !in 0..2_000L) return
        if (destination == null || destination.name != success.first || !destination.berserker || !destination.alive || !destination.point.valid()) return
        // Conservative proof: a real server correction displaced us, and its actual result is at this exact teammate.
        if (move.from.distanceSquared(move.to) < 16.0 || move.to.distanceSquared(destination.point) > 9.0) return
        verifiedLeapAt = now; leap = null; correction = null
        status = "Leap verified; waiting for observed purple pillar descent at Storm"
    }

    fun underway(value: CrushStage, now: Long) {
        if (!ready() || consumed || value != CrushStage.PURPLE || stage != null) return
        val leapAt = verifiedLeapAt ?: return
        if (now - leapAt !in 0..30_000L) return
        stage = CrushStage.PURPLE; opened = now; status = "Purple: waiting for observed pillar collision and server crush confirmation"
    }

    fun confirmed(value: CrushStage, observationId: String, now: Long) {
        if (!ready() || consumed || value != stage || now <= opened || observationId == lastConfirmation) return
        lastConfirmation = observationId
        if (value == CrushStage.PURPLE) {
            stage = CrushStage.YELLOW; opened = now; status = "Yellow: waiting for observed pillar collision and server crush confirmation"
        } else {
            stage = null; consumed = true; verifiedLeapAt = null; status = "Purple and yellow observed; sequence complete"
        }
    }

    fun evidenceUnavailable(reason: String) {
        if (stormActive && !consumed) status = reason
    }

    fun cancelCurrent(reason: String) {
        stage = null; consumed = true; verifiedLeapAt = null; leap = null; correction = null; status = reason
    }

    fun tick(now: Long) {
        if (!stormActive) return
        if (now - phaseStarted !in 0..240_000L || stage != null && now - opened !in 0..60_000L) {
            stage = null; consumed = true; verifiedLeapAt = null; leap = null; correction = null
            status = "Encounter evidence timed out; waiting for the next Storm phase"
        }
        if (stage == null && verifiedLeapAt?.let { now - it > 30_000L } == true) {
            verifiedLeapAt = null; status = "Verified leap expired; waiting for a new successful Berserker leap"
        }
    }

    fun reset(reason: String = "Encounter ended") {
        stage = null; stormActive = false; phaseStarted = 0L; opened = 0L; consumed = false
        leap = null; correction = null; verifiedLeapAt = null; corrections.clear(); lastConfirmation = null; status = reason
    }
    private fun ready() = context?.eligible == true && context?.calibrated == true && stormActive
}

internal const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
internal val STORM_END = setOf("[BOSS] Storm: I should have known that I stood no chance.",
    "[BOSS] Storm: FINALLY! This took way too long.", "[BOSS] Goldor: Who dares trespass into my domain?")
internal fun successfulLeapName(message: String): String? = Regex("^You have teleported to ([A-Za-z0-9_]{1,16})!$")
    .matchEntire(message)?.groupValues?.get(1)

data class StormCrushNotice(val at: Long, val serial: Long)

/** These exact normalized server lines are classified as crushes by pinned primary Noamm F7Titles source. */
class StormCrushMessages {
    private var serial = 0L
    var latest: StormCrushNotice? = null
        private set
    fun accept(text: String, at: Long): StormCrushNotice? {
        if (text !in setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!") || at < 0) return null
        if (latest?.let { at - it.at !in 501..Long.MAX_VALUE } == true) return null
        return StormCrushNotice(at, ++serial).also { latest = it }
    }
    fun reset() { latest = null }
}
