package me.mycellium.skymyce.features.instances.dungeons.camera

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.HealerCrushCameraConfig as Settings
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

/** Automatic activation only reads encounter events; it never sends packets or moves the player. */
object HealerCrushCamera : SkyMyceModule() {
    private val sequence = HealerCrushSequence()
    private val clockStart = System.nanoTime()
    private fun now() = (System.nanoTime() - clockStart) / 1_000_000L
    private var level: ClientLevel? = null
    @Volatile private var epoch = 0L
    private val crushMessages = StormCrushMessages()
    val stage: CrushStage? get() = sequence.stage
    val status: String get() = sequence.status
    /** True shortly before the crush view appears; see [HealerCrushSequence.warming]. */
    fun warming() = sequence.warming(now())

    override fun init() { ClientTickEvents.END_CLIENT_TICK.register { refreshContext(now()) } }
    override fun onDisable() { reset("Disabled") }

    private fun realDungeon(): Boolean {
        val address = MC.instance.currentServer?.ip?.substringBefore(':')?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return false
        return (address == "hypixel.net" || address.endsWith(".hypixel.net")) &&
            LocationAPI.isOnSkyBlock && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS
    }

    private fun refreshContext(time: Long) {
        val client = MC.instance
        if (client.level !== level) { reset("World changed"); level = client.level }
        val viewpointsReady = CrushStage.entries.all { CameraViewpoints.get(it.name.lowercase(java.util.Locale.ROOT)) != null }
        sequence.context(CrushEncounterContext(epoch, isEnabled() && Settings.enabled, DungeonAPI.dungeonFloor?.name,
            Settings.classEnabled(DungeonAPI.ownPlayer?.dungeonClass),
            client.player?.isAlive == true && DungeonAPI.ownPlayer?.dead == false,
            realDungeon() && DungeonAPI.inBoss && !DungeonAPI.completed, viewpointsReady), time)
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        val text = event.text
        val capturedEpoch = epoch
        MC.instance.execute {
            val time = now(); refreshContext(time)
            if (epoch != capturedEpoch) return@execute
            when {
                text == STORM_START -> {
                    if (!sequence.stormActive) crushMessages.reset()
                    sequence.stormStarted(time)
                }
                text in STORM_END -> reset("Storm phase ended")
                sequence.stormActive -> crushMessages.accept(text, time)?.let { sequence.crushed(it.serial, time) }
            }
        }
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun encounterChanged() { MC.instance.execute { reset("Left the encounter") } }

    private fun reset(reason: String) { epoch++; sequence.reset(reason); crushMessages.reset() }
}
