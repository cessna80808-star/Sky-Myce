package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.digest.DigestRngCatalog
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val recentRngTime = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)

internal fun recentRngLines(events: List<AcquisitionEvent>): List<String>? {
    val confirmed = events.filter { it.origin == "confirmed" && DigestRngCatalog.valid(it.item, it.activity) }.take(3)
    if (confirmed.isEmpty()) return null
    return listOf("§b§lRecent RNG §8· personal") + confirmed.map { event ->
        "§f${DigestRngCatalog.name(event.item)}${if (event.quantity > 1) " ×${event.quantity}" else ""} §8${recentRngTime.format(Instant.ofEpochMilli(event.occurredAt))}"
    } + "§8UTC · /sm journal"
}

/** Optional compact presentation of the existing archive, with no network work or invented live statistics. */
object RecentRngWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }
    private val preview by lazy { hudPanel(padding = 6).updateTextLines(listOf(
        "§b§lRecent RNG §8· editor preview", "§fNecron's Handle §8MM-dd HH:mm", "§8UTC · /sm journal")) }
    private var revision = -1L
    private var context: AcquisitionContext? = null
    private var lines: List<String>? = null
    val widget = Widget("recent_personal_rng", "Recent Personal RNG", 10, 100, previewBuilder = { preview }) {
        val current = AcquisitionRepository.currentContext()
        if (revision != AcquisitionRepository.revision || context != current) {
            revision = AcquisitionRepository.revision; context = current
            lines = recentRngLines(AcquisitionRepository.recentPersonal(3))
        }
        lines?.let { content.updateTextLines(it) }
    }
}
