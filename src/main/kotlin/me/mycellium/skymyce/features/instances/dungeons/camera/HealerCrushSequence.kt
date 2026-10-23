package me.mycellium.skymyce.features.instances.dungeons.camera

enum class CrushStage { PURPLE, YELLOW }

data class CrushEncounterContext(val world: Long, val enabled: Boolean, val floor: String?, val classEnabled: Boolean,
                                 val alive: Boolean, val inBoss: Boolean, val viewpointsReady: Boolean) {
    val eligible get() = enabled && floor in setOf("F7", "M7") && classEnabled && alive && inBoss
}

/** Times are monotonic milliseconds from the server's Storm phase entry message. */
class HealerCrushSequence {
    var stage: CrushStage? = null
        private set
    var status = "Waiting for a selected-class F7/M7 Storm encounter"
        private set
    var stormActive = false
        private set
    private var context: CrushEncounterContext? = null
    private var phaseStarted = 0L
    private var yellowAt = 0L
    private var lastCrushSerial = 0L

    fun context(value: CrushEncounterContext, now: Long) {
        if (context?.world != value.world || !value.eligible) reset(if (!value.enabled) "Disabled" else "Outside a living selected-class F7/M7 boss encounter")
        context = value
        if (!value.eligible) return
        tick(now)
    }

    fun stormStarted(now: Long) {
        if (context?.eligible != true || stormActive) return
        stormActive = true; phaseStarted = now; yellowAt = now + 38_000L; lastCrushSerial = 0L
        tick(now)
    }

    fun crushed(serial: Long, now: Long) {
        if (!stormActive || serial <= lastCrushSerial) return
        if (lastCrushSerial == 0L && now < yellowAt) yellowAt = now + 1_000L
        lastCrushSerial = serial
        tick(now)
    }

    fun tick(now: Long) {
        if (!stormActive) { stage = null; if (context?.eligible == true) status = "Waiting for Storm phase entry"; return }
        val elapsed = now - phaseStarted
        if (elapsed < 0L) { reset("Storm clock changed; waiting for the next encounter"); return }
        if (context?.viewpointsReady != true) { stage = null; status = "Save both purple and yellow viewpoints: /sm crush-camera"; return }
        if (elapsed < 26_000L) { stage = null; status = "Waiting for 26 seconds elapsed in Storm's phase"; return }
        val yellow = now >= yellowAt
        stage = if (yellow) CrushStage.YELLOW else CrushStage.PURPLE
        status = "${if (yellow) "Yellow" else "Purple"} crush view; visible until Storm dies"
    }

    /** The last seconds before the crush view appears: time to build the camera so it does not hitch on appearing. */
    fun warming(now: Long) = stormActive && stage == null && context?.viewpointsReady == true && now - phaseStarted in WARM_FROM until 26_000L

    fun reset(reason: String = "Storm encounter ended") {
        stage = null; stormActive = false; phaseStarted = 0L; yellowAt = 0L; lastCrushSerial = 0L; status = reason
    }
}

/** Storm time at which the crush camera starts building, 10 s before it is shown. */
internal const val WARM_FROM = 16_000L

internal const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
internal val STORM_END = setOf("[BOSS] Storm: I should have known that I stood no chance.",
    "[BOSS] Goldor: Who dares trespass into my domain?")
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
