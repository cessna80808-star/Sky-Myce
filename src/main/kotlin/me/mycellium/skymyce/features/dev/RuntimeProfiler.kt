package me.mycellium.skymyce.features.dev

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlin.time.Duration.Companion.seconds
import me.mycellium.skymyce.ModuleManager
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.api.DungeonApi.skyblockCount
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.social.CosmeticsClient
import me.mycellium.skymyce.ui.MenuScreen
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ItemDebugTooltipEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling

/** Opt-in developer diagnostics. Disabled calls do not read clocks or allocate scopes. */
object RuntimeProfiler {
    enum class Area { COMMAND_TICK, FRIEND_TICK, COSMETIC_TICK, HUD, GUI, AUCTION_HTTP, RELAY_MESSAGE, RELAY_SEND, FRIEND_API, CHAT, DUNGEON_ROUTE,
        LOADER_TICK, COMMUNITY_TICK, ROUTING_TICK, SPLITS_TICK, GOLDOR_TICK, STATUS_BARS, NPC_DIALOGUE, CAMERA_EXTRACT,
        AUTO_GRAPHICS, CHUNK_EVENTS, IRC_MEDIA, BROWSER_PUMP }
    class Sample {
        val calls = LongAdder()
        val wall = LongAdder()
        val cpu = LongAdder()
        val allocated = LongAdder()
        val bytes = LongAdder()
    }
    class Session(val recording: Recording?, val started: Long = System.nanoTime()) {
        val samples = Array(Area.entries.size) { Sample() }
    }
    @Volatile @PublishedApi internal var session: Session? = null
    @Volatile private var stopping = false
    @PublishedApi internal val bean: ThreadMXBean? by lazy {
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.apply {
            if (isThreadCpuTimeSupported) isThreadCpuTimeEnabled = true
            if (isThreadAllocatedMemorySupported) isThreadAllocatedMemoryEnabled = true
        }
    }
    val active get() = session != null

    /** Run start/stop on the existing background scheduler; JFR/file work can block. */
    @Synchronized fun start(recordJfr: Boolean = true): Boolean {
        if (stopping || active) return false
        bean
        val recording = if (recordJfr) Recording(Configuration.getConfiguration("profile")).apply {
            name = "Sky-Hawk developer profile"
            maxAge = Duration.ofMinutes(15)
            maxSize = 64L * 1024 * 1024
            // Launch arguments/system properties/environment and exception messages
            // can contain Minecraft access tokens or private relay credentials.
            for (event in listOf("jdk.JVMInformation", "jdk.InitialSystemProperty", "jdk.InitialEnvironmentVariable",
                "jdk.SystemProcess", "jdk.ProcessStart", "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow")) disable(event)
            start()
        } else null
        session = Session(recording)
        return true
    }

    inline fun <T> measure(area: Area, block: () -> T): T {
        val current = session ?: return block()
        val meter = bean
        val thread = Thread.currentThread().threadId()
        val cpu = if (meter?.isThreadCpuTimeSupported == true) meter.currentThreadCpuTime else -1
        val allocation = if (meter?.isThreadAllocatedMemorySupported == true) meter.getThreadAllocatedBytes(thread) else -1
        val start = System.nanoTime()
        try { return block() }
        finally {
            if (session === current) {
                val sample = current.samples[area.ordinal]
                sample.wall.add(System.nanoTime() - start)
                if (cpu >= 0) sample.cpu.add((meter!!.currentThreadCpuTime - cpu).coerceAtLeast(0))
                if (allocation >= 0) sample.allocated.add((meter!!.getThreadAllocatedBytes(thread) - allocation).coerceAtLeast(0))
                sample.calls.increment()
            }
        }
    }
    fun network(area: Area, bytes: Long) { session?.samples?.get(area.ordinal)?.bytes?.add(bytes.coerceAtLeast(0)) }

    fun report(gauges: Map<String, Long> = emptyMap()): String = session?.let { summarize(it, gauges) } ?: "Profiler is stopped"
    private fun summarize(current: Session, gauges: Map<String, Long>): String = buildString {
        appendLine("Sky-Hawk developer profile · ${"%.1f".format((System.nanoTime() - current.started) / 1e9)}s")
        appendLine("Inclusive scopes; CPU and allocation belong to the calling thread, not async continuations.")
        val rows = Area.entries.filter { current.samples[it.ordinal].calls.sum() > 0 || current.samples[it.ordinal].bytes.sum() > 0 }
        for ((heading, value) in listOf<Pair<String, (Sample) -> Long>>(
            "CPU ns" to { it.cpu.sum() }, "Wall ns" to { it.wall.sum() },
            "Allocated B" to { it.allocated.sum() }, "Network B" to { it.bytes.sum() })) {
            appendLine("$heading:")
            rows.sortedByDescending { value(current.samples[it.ordinal]) }.forEach { area ->
                val sample = current.samples[area.ordinal]
                appendLine("  $area ${value(sample)} calls=${sample.calls.sum()}")
            }
        }
        val threads = ManagementFactory.getThreadMXBean()
        appendLine("JVM platform threads=${threads.threadCount} peak=${threads.peakThreadCount} heap=${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used}B")
        gauges.forEach { (name, count) -> appendLine("$name=$count") }
        appendLine("Background platform threads (JFR also captures execution/allocation stacks and socket activity):")
        threads.getThreadInfo(threads.allThreadIds).filterNotNull().filter {
            it.threadName.startsWith("Scheduling-") || it.threadName.contains("Sky-") ||
                it.threadName.contains("HttpClient") || it.threadName.contains("DefaultDispatcher")
        }.forEach { appendLine("  ${it.threadName} ${it.threadState}") }
        appendLine("Global coroutine count unavailable: shared dispatchers do not expose a reliable count. Feature-owned job gauges are reported separately.")
    }

    @Synchronized fun stop(directory: Path, gauges: Map<String, Long> = emptyMap()): Path? {
        val current = session ?: return null
        session = null
        val report = summarize(current, gauges)
        try {
            Files.createDirectories(directory)
            val stem = "profile-${System.currentTimeMillis()}"
            current.recording?.let { it.stop(); it.dump(directory.resolve("$stem.jfr")) }
            return directory.resolve("$stem.txt").also { Files.writeString(it, report) }
        } finally { current.recording?.close() }
    }
    @Synchronized fun close() { session?.recording?.close(); session = null }
    @Synchronized fun shutdown() { stopping = true; close() }
}

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

object DevTooltips : SkyMyceModule() {
    @Subscription
    fun onTooltipRender(event: ItemDebugTooltipEvent) {
        event.add(Component.literal("§8${event.item.getSkyBlockId()?.id} (x${event.item.skyblockCount()})"))
    }
}
