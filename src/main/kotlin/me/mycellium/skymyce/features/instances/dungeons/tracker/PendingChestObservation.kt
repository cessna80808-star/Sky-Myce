package me.mycellium.skymyce.features.instances.dungeons.tracker

/** Only a matching server receipt and every physical inventory delta can confirm the preview. */
internal data class PendingChestObservation(
    val id: String,
    val context: AcquisitionContext,
    val run: String,
    val contents: Map<String, Int>,
    val baseline: Map<String, Int>,
    val started: Long,
    val closed: Long? = null,
    val rewardHeader: Boolean = false,
    val received: Map<String, String> = emptyMap(),
) {
    val physical get() = contents.filterKeys { !it.startsWith("ESSENCE_") }
    fun active(now: Long, current: AcquisitionContext?, currentRun: String): Boolean =
        context.matches(current) && run == currentRun && now - started in 0..10000 &&
            (closed == null || now - closed in 0..2000)
    fun inventory(counts: Map<String, Int>, observation: String): PendingChestObservation {
        val additions = receivedChestItems(physical, baseline, counts).filterNot(received::containsKey)
            .associateWith { "$observation/$it" }
        return if (additions.isEmpty()) this else copy(received = received + additions)
    }
    val confirmed get() = contents.isNotEmpty() && rewardHeader && physical.keys.all(received::containsKey)
}

internal fun rerollInventoryConfirmed(before: Int, after: Int) = before in 1..100000 && after == before - 1
