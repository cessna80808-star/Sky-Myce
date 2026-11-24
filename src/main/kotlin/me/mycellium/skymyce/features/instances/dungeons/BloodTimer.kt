package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.StarredClearRoutingConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.splits.BLOOD_DOOR
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplits
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

internal const val BLOOD_TIMER_SECONDS = 20
private const val TICKS_PER_SECOND = 20L

/** The number to show [elapsedTicks] server ticks after Blood opened, or null outside the last [seconds] of 20 s. */
internal fun bloodCountdown(elapsedTicks: Long, seconds: Int): Int? {
    val left = BLOOD_TIMER_SECONDS * TICKS_PER_SECOND - elapsedTicks
    return if (left in 1..seconds * TICKS_PER_SECOND) ((left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND).toInt() else null
}

/** Counts down to 20 s after Blood opens on the splits' server clock, so it waits whenever the server lags. */
object BloodTimer : SkyMyceModule() {
    private var openedAt: Long? = null
    private var shown = -1
    private val label by lazy { hudLabel() }
    private val panel by lazy { hudPanel(padding = 5).apply { child(label) } }
    private val preview by lazy { hudPanel(padding = 5).apply { child(hudLabel().apply { updateText(text(3)) }) } }

    private fun text(seconds: Int) = "§c§lBlood §f§l$seconds"

    val widget = Widget("blood_timer", "Blood Timer", 480, 230, 1.5f, anchor = Anchor.CENTER, previewBuilder = { preview.takeIf { Settings.bloodTimer } }) {
        if (!isEnabled() || !Settings.bloodTimer || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return@Widget null
        val now = DungeonSplits.serverTicks ?: return@Widget null
        val seconds = bloodCountdown(now - (openedAt ?: return@Widget null), Settings.bloodTimerSeconds) ?: return@Widget null
        if (shown != seconds) { shown = seconds; label.updateText(text(seconds)) }
        panel
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (event.text == BLOOD_DOOR && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) openedAt = DungeonSplits.serverTicks
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onLeave() { openedAt = null }
}
