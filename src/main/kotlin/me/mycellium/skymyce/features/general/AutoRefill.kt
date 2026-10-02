package me.mycellium.skymyce.features.general

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GeneralConfig
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.RightClickEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object AutoRefill : SkyMyceModule() {
    private var command: String? = null
    private var fillTimestamp: Duration = 0.milliseconds
    private var lastRefill: Duration = 0.milliseconds

    @Subscription
    fun useItem(event: RightClickEvent) {
        if (GeneralConfig.autoRefill.isEmpty()) return
        val id = event.stack.getData(DataTypes.ID) ?: return
        val type = Types.entries.firstOrNull { it.name == id } ?: return

        if (!GeneralConfig.autoRefill.contains(type)) return

        val now = System.currentTimeMillis().milliseconds
        if (event.stack.count <= 5) {
            val count = (event.stack.maxStackSize - event.stack.count) + 1
            if (command == null) fillTimestamp = now
            command = "gfs $id $count"
        }
        if (event.stack.count <= 1) event.cancel()
    }

    @Subscription
    fun tick(event: TickEvent) {
        val cmd = command ?: return
        val now = System.currentTimeMillis().milliseconds
        if ((now - fillTimestamp) > 500.milliseconds && (now - lastRefill) > 1.seconds) {
            sendCommand(cmd, true)
            command = null
            lastRefill = now
        }
    }

    @Subscription(ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun clearPending() { command = null; fillTimestamp = 0.milliseconds; lastRefill = 0.milliseconds }
    override fun onDisable() { clearPending() }

    enum class Types {
        ENDER_PEARL,
        SUPERBOOM_TNT,
        SPIRIT_LEAP,
    }
}
