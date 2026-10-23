package me.mycellium.skymyce.features.dev

import me.mycellium.skymyce.ModuleManager
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.social.CosmeticsClient
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.ui.MenuScreen
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.loader.api.FabricLoader
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private val profilingBusy = AtomicBoolean()

internal fun profilingCommand() = literal("profile")
    .requires { FabricLoader.getInstance().isDevelopmentEnvironment || java.lang.Boolean.getBoolean("skymyce.developerProfiling") }
    .then(literal("start").executes {
        profileAction {
            if (RuntimeProfiler.start()) "Profiler started: use /sm profile report or /sm profile stop"
            else "Profiler is already running"
        }; 1
    })
    .then(literal("report").executes {
        val gauges = profilingGauges()
        profileAction { RuntimeProfiler.report(gauges).also { SkyMyce.logger.info(it) }; "Profile report written to the log" }; 1
    })
    .then(literal("stop").executes {
        val gauges = profilingGauges()
        profileAction {
            RuntimeProfiler.stop(SkyMyce.configPath.resolve("performance"), gauges)?.let { "Profile saved to $it (JFR beside it)" }
                ?: "Profiler is stopped"
        }; 1
    })

private fun profileAction(action: () -> String) {
    if (!profilingBusy.compareAndSet(false, true)) { displayMessage("Profiler operation is still running"); return }
    Scheduling.schedule(0.seconds) {
        try {
            val result = action()
            MC.instance.execute { if (MC.instance.player != null) displayMessage(result) }
        } catch (error: Exception) {
            SkyMyce.logger.warn("Developer profiler operation failed", error)
            MC.instance.execute { if (MC.instance.player != null) displayMessage("Profiler operation failed; see the log") }
        } finally { profilingBusy.set(false) }
    }
}

/** Snapshot client-owned state on the client thread; sort/dump diagnostics in the background. */
private fun profilingGauges(): Map<String, Long> = buildMap {
    val modules = ModuleManager.modules
    put("modules.retained", modules.size.toLong())
    put("modules.enabled", modules.count { it.isEnabled() }.toLong())
    put("modules.configuredSubscriptionMethods", modules.sumOf { module ->
        module.javaClass.declaredMethods.count { it.getAnnotation(Subscription::class.java) != null }
    }.toLong())
    // These are configured methods, not an invented count of Fabric/SBAPI's private global listeners.
    put("client.fps", MC.fps.toLong())
    (MC.screen as? MenuScreen)?.cacheSizes()?.forEach { (name, size) -> put("gui.$name", size) }
    putAll(AuctionAPI.diagnostics())
    putAll(DungeonFriendRelay.diagnostics())
    putAll(DungeonFriendStatsCache.diagnostics())
    putAll(DungeonFriends.diagnostics())
    putAll(CosmeticsClient.diagnostics())
    putAll(DailyDigest.diagnostics())
    putAll(DigestRng.diagnostics())
}
