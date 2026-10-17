package me.mycellium.skymyce.features.instances.dungeons.splits

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonCompletionFloor
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.data.MayorPerks
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.info.ScoreboardUpdateEvent
import tech.thatgravyboat.skyblockapi.api.events.info.TabListChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
import java.util.UUID

/** Relic cauldrons in the M7 Wither King arena (block x/z); placed relics stand on them (NoammAddons `M7Relics`, CC0). */
private val relicCauldrons = listOf(52 to 43, 58 to 43, 50 to 45, 60 to 45, 55 to 42)
private val serverLine = Regex("^\\s*\\d{1,2}/\\d{1,2}/\\d{2} (\\w{2,12})(?: .*)?$")
private const val RESUME_WINDOW_NANOS = 15L * 60 * 1_000_000_000

/**
 * Live dungeon splits. Each milestone is stamped with [System.nanoTime] when its event arrives and handed to a
 * [SplitTimeline], which accepts it once and in order. The run is saved when it ends or the player leaves it; leaving
 * and rejoining the same dungeon server within 15 minutes resumes the same run, with the gap left unmeasured.
 */
object DungeonSplits : SkyMyceModule() {
    internal class LiveRun(val id: String, val timeline: SplitTimeline, val recordedAt: Long, var server: String?) {
        val definition get() = timeline.definition
        val floorNumber = definition.floor.drop(1).toInt()
        /** False once any part of the run was missed; first-of-kind fallbacks then cannot be trusted. */
        var continuous = timeline.startObserved
        val terminals = TerminalSections()
        val lag = LagMeter()
        var terminalsReliable = true
        var necronArgh = false
        var defeated = false
        var score = ScoreInputs(definition.floor)
        var estimate: Int? = null
        /** [estimate] is Hypixel's own score from the boss, not an estimate. */
        var scoreExact = false
        var sawBelow300 = false
        var outcome: SplitOutcome? = null
    }

    private data class Suspended(val run: LiveRun, val at: Long)

    internal var live: LiveRun? = null
        private set
    private var suspended: Suspended? = null
    private var pendingStart: Long? = null
    private var pendingStartTicks: Long? = null
    /** Updated on the client thread, so it stays in order with chat and other packets. */
    private val serverClock = ServerTickClock()
    /** The server's tick count, or null before its game time has arrived. */
    internal val serverTicks: Long? get() = serverClock.now
    private var server: String? = null
    /** Boss bar names by id; Hypixel's withers reach the client as boss bars. */
    private val bossBars = HashMap<UUID, String>()
    private var bossBarAt = 0L
    private val bossBarHandler = object : ClientboundBossEventPacket.Handler {
        override fun add(id: UUID, name: Component, progress: Float, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay,
                         darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) { bossBars[id] = name.stripped; bossProgress(id, progress) }
        override fun updateName(id: UUID, name: Component) { bossBars[id] = name.stripped }
        override fun updateProgress(id: UUID, progress: Float) = bossProgress(id, progress)
        override fun remove(id: UUID) { bossBars.remove(id) }
    }
    var revision = 0L
        private set

    override fun init() { DungeonSplitStore.initialize() }

    private fun inDungeon() = LocationAPI.island == SkyBlockIsland.THE_CATACOMBS
    private fun active() = isEnabled() && Settings.enabled && inDungeon()

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!active()) return
        val at = System.nanoTime()
        val text = event.text
        val floor = DungeonAPI.dungeonFloor
        val signal = splitSignal(text, floor?.floorNumber ?: 0)
        when {
            signal == SplitSignal.Start -> { begin(at); return }
            dungeonCompletionFloor(text) != null -> {
                live?.takeIf { dungeonCompletionFloor(text)?.name == it.definition.floor }?.let { finish(it, at) }; return
            }
        }
        // The end of a run that was never seen is not worth a record of its own.
        val run = (if (signal != null && signal != SplitSignal.Defeated) current(at) else live) ?: return
        when (signal) {
            is SplitSignal.Milestone -> mark(run, signal.milestone, at)
            SplitSignal.WatcherLine -> if (run.continuous) mark(run, SplitMilestone.BLOOD_OPEN, at)
            SplitSignal.BossLine -> if (run.continuous) mark(run, SplitMilestone.BOSS_ENTRY, at)
            is SplitSignal.Terminal -> if (run.terminalsReliable && SplitMilestone.STORM in run.timeline.marks && SplitMilestone.S4 !in run.timeline.marks)
                run.terminals.accept(signal.done, signal.total)?.let { mark(run, it, at) }
            SplitSignal.NecronArgh -> run.necronArgh = true
            SplitSignal.Defeated -> { run.defeated = true; finish(run, at) }
            SplitSignal.Start, null -> {
                if (signal != null) return
                if (!run.score.prince && announcesPrince(text)) updateScore(run, at) { it.copy(prince = true) }
                if (!run.score.mimic && run.floorNumber >= 6 && !DungeonAPI.inBoss && (announcesMimic(text) || mimicCharm.matches(text)))
                    updateScore(run, at) { it.copy(mimic = true) }
            }
        }
    }

    /** Server packets arrive on the network thread: stamp them there, then apply on the client thread. */
    @Subscription
    fun onPacket(event: PacketReceivedEvent) {
        val packet = event.packet
        if (packet is ClientboundPingPacket || packet is ClientboundSetTimePacket) {
            if (LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return
            if (packet is ClientboundSetTimePacket) packet.gameTime.let { time ->
                val at = System.nanoTime()
                MC.instance.execute {
                    // A jump means another world; server time from before it no longer lines up.
                    if (!serverClock.time(time)) live?.timeline?.stopTicks()
                    live?.takeIf { it.outcome == null && !it.timeline.finished }?.let { run ->
                        run.timeline.elapsedTicks(serverClock.now)?.let { ticks -> run.lag.read(run.timeline.elapsed(at) - ticks * TICK_MILLIS) }
                    }
                }
            } else if ((packet as ClientboundPingPacket).id != 0) MC.instance.execute { serverClock.ping() }
            return
        }
        if (packet is ClientboundBossEventPacket) {
            if (LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return
            val at = System.nanoTime()
            MC.instance.execute { bossBarAt = at; packet.dispatch(bossBarHandler) }
            return
        }
        if (live == null) return
        if (packet is ClientboundEntityEventPacket && packet.eventId.toInt() == 3) {
            val at = System.nanoTime()
            MC.instance.execute {
                val run = live?.takeIf { it.floorNumber >= 6 && !it.score.mimic && !DungeonAPI.inBoss && active() } ?: return@execute
                val level = MC.instance.level ?: return@execute
                // A baby zombie dying before the boss is the Mimic (NoammAddons ScoreCalculation).
                if ((packet.getEntity(level) as? Zombie)?.isBaby == true) updateScore(run, at) { it.copy(mimic = true) }
            }
        }
    }

    /** Necron's bar emptying after his "ARGH!" is his death, even when boss chat is hidden (NoammAddons `F7Titles`). */
    private fun bossProgress(id: UUID, progress: Float) {
        if (progress > 0f) return
        val run = live?.takeIf { it.necronArgh && it.floorNumber == 7 && active() } ?: return
        if (bossBars[id]?.contains("Necron") == true) mark(run, SplitMilestone.NECRON, bossBarAt)
    }

    @Subscription
    fun onTabList(event: TabListChangeEvent) {
        val run = live?.takeIf { active() } ?: return
        val at = System.nanoTime()
        updateScore(run, at) { readScoreTabList(it, event.new.map { column -> column.map { line -> line.stripped } }) }
    }

    @Subscription
    fun onScoreboard(event: ScoreboardUpdateEvent) {
        if (!active()) return
        event.new.firstNotNullOfOrNull { serverLine.matchEntire(it)?.groupValues?.get(1) }?.let { server = it }
        resume()
        pendingStart?.let { at -> DungeonAPI.dungeonFloor?.name?.let(splitDefinitions::get)?.let { start(it, true, at, pendingStartTicks) } }
        val run = live ?: return
        if (run.server == null) run.server = server
        val at = System.nanoTime()
        updateScore(run, at) { readScoreSidebar(it, event.new, SplitMilestone.BLOOD_CLEAR in run.timeline.marks) }
    }

    @Subscription(TickEvent::class)
    fun onTick() {
        val run = live ?: return
        val marks = run.timeline.marks
        // Relics only exist once Necron is down; the scan does not wait for his split, so a missed line costs only that one.
        if (!active() || run.definition.floor != "M7" || SplitMilestone.GOLDOR !in marks && SplitMilestone.NECRON !in marks || SplitMilestone.RELICS in marks) return
        val level = MC.instance.level ?: return
        val placed = HashSet<Pair<Int, Int>>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")) continue
            relicCauldrons.firstOrNull { (x, z) -> (entity.x - x) * (entity.x - x) + (entity.z - z) * (entity.z - z) < 1.5 * 1.5 }?.let(placed::add)
        }
        if (placed.size == relicCauldrons.size) mark(run, SplitMilestone.RELICS, System.nanoTime())
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun onLeave() {
        pendingStart = null; pendingStartTicks = null; server = null
        serverClock.reset(); bossBars.clear()
        val run = live ?: return
        live = null; revision++
        if (run.outcome != null) return
        save(run, SplitOutcome.ABANDONED)
        // The run is saved as left early; returning to the same server resumes and re-saves it under the same id.
        suspended = Suspended(run, System.nanoTime())
    }

    private fun begin(at: Long) {
        live?.let { if (it.outcome == null) save(it, SplitOutcome.ABANDONED) }
        live = null; suspended = null
        val floor = DungeonAPI.dungeonFloor?.name
        val definition = floor?.let(splitDefinitions::get)
        if (definition == null) { pendingStart = at.takeIf { floor == null }; pendingStartTicks = serverTicks; return }
        start(definition, true, at, serverTicks)
    }

    private fun start(definition: SplitDefinition, observed: Boolean, at: Long, ticks: Long?): LiveRun =
        LiveRun(UUID.randomUUID().toString(), SplitTimeline(definition, observed, at, ticks), System.currentTimeMillis(), server).also {
            live = it; pendingStart = null; pendingStartTicks = null; revision++
            if (!observed) displayDevMessage { "Dungeon splits: joined ${definition.floor} after its start; only directly observed sections are kept" }
        }

    /** The live run, or a new one started at [at] when a split event shows a run in progress that was not seen starting. */
    private fun current(at: Long): LiveRun? {
        live?.let { return it }
        val floor = DungeonAPI.dungeonFloor?.name ?: return null
        val definition = splitDefinitions[floor] ?: return null
        pendingStart?.let { return start(definition, true, it, pendingStartTicks) }
        resume()?.let { return it }
        // Rejoining before the sidebar names the server: wait for it rather than splitting the run in two.
        if (suspended?.run?.definition?.floor == floor && server == null) return null
        return start(definition, false, at, serverTicks)
    }

    private fun resume(): LiveRun? {
        if (live != null) return live
        val held = suspended ?: return null
        val now = System.nanoTime()
        if (now - held.at > RESUME_WINDOW_NANOS || DungeonAPI.dungeonFloor?.name != held.run.definition.floor) { if (now - held.at > RESUME_WINDOW_NANOS) suspended = null; return null }
        if (server == null || held.run.server == null || server != held.run.server) return null
        suspended = null
        return held.run.also { run ->
            run.continuous = false; run.necronArgh = false; run.sawBelow300 = false
            // Ticks were not counted while away, so server time stops here; real time still covers the whole run.
            run.timeline.stopTicks()
            // A terminal section may have finished while away, so later counts could be off by one.
            if (SplitMilestone.STORM in run.timeline.marks) run.terminalsReliable = false
            live = run; revision++
            displayDevMessage { "Dungeon splits: rejoined ${run.definition.floor}; the time away is not split" }
        }
    }

    private fun mark(run: LiveRun, milestone: SplitMilestone, at: Long) {
        if (run.outcome != null && milestone != SplitMilestone.END) return
        when (run.timeline.mark(milestone, at, serverTicks)) {
            MarkResult.ACCEPTED -> revision++
            MarkResult.OUT_OF_ORDER -> displayDevMessage { "Dungeon splits: ignored ${milestone.title} out of order on ${run.definition.floor}" }
            MarkResult.DUPLICATE, MarkResult.NOT_APPLICABLE -> {}
        }
    }

    private fun finish(run: LiveRun, at: Long) {
        mark(run, SplitMilestone.END, at)
        val outcome = if (run.defeated) SplitOutcome.COMPLETED else SplitOutcome.FAILED
        if (run.outcome == outcome) return
        // The summary header can arrive just before "☠ Defeated"; the later line upgrades the same record.
        run.outcome = outcome
        save(run, outcome)
        revision++
    }

    private fun save(run: LiveRun, outcome: SplitOutcome) {
        // A late join that never saw a milestone has nothing to keep.
        if (!run.timeline.startObserved && run.timeline.marks.isEmpty()) return
        DungeonSplitStore.record(run.timeline.record(run.id, run.recordedAt, outcome))
    }

    private inline fun updateScore(run: LiveRun, at: Long, change: (ScoreInputs) -> ScoreInputs) {
        run.score = change(run.score).copy(inBoss = DungeonAPI.inBoss, paul = MayorPerks.EZPZ.active)
        val score = dungeonScore(run.score)
        if (score != run.estimate || run.score.exact != run.scoreExact) { run.estimate = score; run.scoreExact = run.score.exact; revision++ }
        if (score == null || run.outcome != null) return
        // Below 300 only counts once every input was read or Hypixel showed it, so a late join's first, partial
        // estimate is not a crossing.
        if (score < 300) { if (run.score.complete || run.score.shownScore != null) run.sawBelow300 = true; return }
        when {
            // Hypixel's score in the boss is final for the clear: the run had 300 when it entered, even if the estimate
            // missed something along the way.
            run.score.exact -> if ((run.sawBelow300 || run.timeline.startObserved) &&
                run.timeline.markAt(SplitMilestone.SCORE_300, SplitMilestone.BOSS_ENTRY) == MarkResult.ACCEPTED) revision++
            // Only a crossing seen in this session is a time; joining or returning above 300 is not.
            run.sawBelow300 -> mark(run, SplitMilestone.SCORE_300, at)
        }
    }
}
