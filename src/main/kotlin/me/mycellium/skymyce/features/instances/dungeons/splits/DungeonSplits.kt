package me.mycellium.skymyce.features.instances.dungeons.splits

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonSplitsConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonCompletionFloor
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ClientboundPingPacket
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
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped

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

    private var relicTicks = 0

    @Subscription(TickEvent::class)
    fun onTick() = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.SPLITS_TICK) { tickSplits() }

    private fun tickSplits() {
        val run = live ?: return
        val marks = run.timeline.marks
        // Relics only exist once Necron is down; the scan does not wait for his split, so a missed line costs only that one.
        if (!active() || run.definition.floor != "M7" || SplitMilestone.GOLDOR !in marks && SplitMilestone.NECRON !in marks || SplitMilestone.RELICS in marks) return
        // Four times a second is plenty for a relic placement; position before the item name, which builds a string.
        if (++relicTicks % 5 != 0) return
        val level = MC.instance.level ?: return
        val placed = HashSet<Pair<Int, Int>>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            val cauldron = relicCauldrons.firstOrNull { (x, z) -> (entity.x - x) * (entity.x - x) + (entity.z - z) * (entity.z - z) < 1.5 * 1.5 } ?: continue
            if (entity.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")) placed += cauldron
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
        val first = run.outcome == null
        // The summary header can arrive just before "☠ Defeated"; the later line upgrades the same record.
        run.outcome = outcome
        save(run, outcome)
        if (first && Settings.chatSummary) splitRunSummary(run.timeline.record(run.id, run.recordedAt, outcome))?.let(::displayModMessage)
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

/**
 * Every milestone the splitter understands. Boss-phase lines are exact server messages from the NoammAddons
 * `runSplits.json` data (CC0 1.0, data branch 3c7b32c4); how each one is detected is documented on [splitTrigger].
 * Saved records refer to these by enum name, so existing names must not be renamed.
 */
enum class SplitMilestone(val title: String) {
    START("Start"),
    BLOOD_OPEN("Blood Open"),
    BLOOD_CLEAR("Watcher Clear"),
    SCORE_300("300 Score"),
    BOSS_ENTRY("Boss Entry"),
    BONZO_FIRST("Bonzo 1"),
    SCARF_UNDEADS("Undeads"),
    GUARDIANS("Guardians"),
    PROFESSOR("Professor"),
    THORN_DIALOGUE("Dialogue"),
    LIVID_DIALOGUE("Dialogue"),
    LIVID_KILL("Livid"),
    TERRACOTTAS("Terracottas"),
    GIANTS("Giants"),
    MAXOR("Maxor"),
    STORM("Storm"),
    S1("S1"),
    S2("S2"),
    S3("S3"),
    /** Ends when the Core entrance opens. Saved as `CORE` by 2.7.12, which is read back as this milestone. */
    S4("S4"),
    GOLDOR("Goldor"),
    NECRON("Necron"),
    RELICS("Relics"),
    END("End"),
}

/**
 * One floor's split layout. [chain] runs from START to END; each later entry ends one section that starts at the entry
 * before it, so section durations always add up to the run time. [markers] are timed from the run start but are not
 * sections, because they can happen before or after other milestones (300 score may come before or after boss entry).
 * [checkpoints] are chain milestones that are also timed from the start, such as boss entry; the section ending there
 * keeps its own name (Portal) so the two rows read apart.
 * Increase [version] whenever a boundary changes so records with different boundaries are never combined.
 */
data class SplitDefinition(
    val floor: String,
    val version: Int,
    val chain: List<SplitMilestone>,
    val markers: List<SplitMilestone> = listOf(SplitMilestone.SCORE_300),
    private val titles: Map<SplitMilestone, String> = emptyMap(),
    /** Totals over consecutive sections, such as all terminals; shown and tracked, never added to the theoretical PB. */
    val groups: List<SplitGroup> = emptyList(),
    val checkpoints: List<SplitMilestone> = listOf(SplitMilestone.BOSS_ENTRY),
    private val sectionTitles: Map<SplitMilestone, String> = mapOf(SplitMilestone.BOSS_ENTRY to "Portal"),
) {
    init {
        require(chain.first() == SplitMilestone.START && chain.last() == SplitMilestone.END && chain.distinct().size == chain.size)
        require(markers.none { it in chain })
        require(groups.all { chain.indexOf(it.from) in 0 until chain.indexOf(it.to) })
        require(checkpoints.all { it in chain && it != SplitMilestone.START })
    }
    val sections: List<SplitSection> = chain.zipWithNext { from, to -> SplitSection(to, from) }
    fun title(milestone: SplitMilestone) = titles[milestone] ?: milestone.title
    /** The name of the section ending at [milestone]; differs from [title] only where a checkpoint shares the milestone. */
    fun sectionTitle(milestone: SplitMilestone) = sectionTitles[milestone] ?: title(milestone)
    val applicable: Set<SplitMilestone> = (chain + markers).toSet()
}

/** The sections from just after [from] up to and including [to], timed as one block. */
data class SplitGroup(val title: String, val from: SplitMilestone, val to: SplitMilestone)

private val terms = SplitGroup("Terms", SplitMilestone.STORM, SplitMilestone.S4)

private val clear = listOf(SplitMilestone.START, SplitMilestone.BLOOD_OPEN, SplitMilestone.BLOOD_CLEAR, SplitMilestone.BOSS_ENTRY)

private fun floors(number: Int, boss: List<SplitMilestone>, endTitle: String, extra: Map<SplitMilestone, String> = emptyMap()) =
    listOf("F$number", "M$number").map { SplitDefinition(it, 1, clear + boss + SplitMilestone.END, titles = extra + (SplitMilestone.END to endTitle)) }

private val f7Boss = listOf(SplitMilestone.MAXOR, SplitMilestone.STORM, SplitMilestone.S1, SplitMilestone.S2, SplitMilestone.S3,
    SplitMilestone.S4, SplitMilestone.GOLDOR, SplitMilestone.NECRON)

/** Floor name (F1–F7, M1–M7) to its definition; the Entrance has no boss phases and is not split. */
val splitDefinitions: Map<String, SplitDefinition> = (
    floors(1, listOf(SplitMilestone.BONZO_FIRST), "Bonzo 2") +
        floors(2, listOf(SplitMilestone.SCARF_UNDEADS), "Scarf") +
        floors(3, listOf(SplitMilestone.GUARDIANS, SplitMilestone.PROFESSOR), "Guardifessor") +
        floors(4, listOf(SplitMilestone.THORN_DIALOGUE), "Thorn") +
        floors(5, listOf(SplitMilestone.LIVID_DIALOGUE, SplitMilestone.LIVID_KILL), "End Dialogue") +
        floors(6, listOf(SplitMilestone.TERRACOTTAS, SplitMilestone.GIANTS), "Sadan") +
        SplitDefinition("F7", 1, clear + f7Boss + SplitMilestone.END, titles = mapOf(SplitMilestone.END to "End Dialogue"), groups = listOf(terms)) +
        // Only Master Mode continues into the Wither King phase: relics, then dragons until the run ends.
        SplitDefinition("M7", 1, clear + f7Boss + SplitMilestone.RELICS + SplitMilestone.END, titles = mapOf(SplitMilestone.END to "Drags"),
            groups = listOf(terms))
    ).associateBy { it.floor }

/** The boss's opening line on each floor number, sent when the first player enters the boss room. */
internal val bossEntryLines = mapOf(
    1 to "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.",
    2 to "[BOSS] Scarf: This is where the journey ends for you, Adventurers.",
    3 to "[BOSS] The Professor: I was burdened with terrible news recently...",
    4 to "[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!",
    5 to "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.",
    6 to "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!",
    7 to "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
)
/** The boss name in `[BOSS] Name:` lines, used only as a fallback when the opening line changes. */
internal val bossChatNames = mapOf(1 to "Bonzo", 2 to "Scarf", 3 to "The Professor", 4 to "Thorn", 5 to "Livid", 6 to "Sadan", 7 to "Maxor")

/** Exact boss-phase lines (stripped chat text). The first of these ends the section named by its milestone. */
internal val bossPhaseLines: Map<String, SplitMilestone> = mapOf(
    "[BOSS] Bonzo: Oh noes, you got me.. what ever will I do?!" to SplitMilestone.BONZO_FIRST,
    "[BOSS] Scarf: Those toys are not strong enough I see." to SplitMilestone.SCARF_UNDEADS,
    "[BOSS] The Professor: Oh? You found my Guardians' one weakness?" to SplitMilestone.GUARDIANS,
    "[BOSS] The Professor: I see. You have forced me to use my ultimate technique." to SplitMilestone.PROFESSOR,
    "[BOSS] Thorn: Dance! Dance with my Spirit animals! And may you perish in a delightful way!" to SplitMilestone.THORN_DIALOGUE,
    "[BOSS] Livid: I respect you for making it to here, but I'll be your undoing." to SplitMilestone.LIVID_DIALOGUE,
    "[BOSS] Sadan: ENOUGH!" to SplitMilestone.TERRACOTTAS,
    "[BOSS] Sadan: You did it. I understand now, you have earned my respect." to SplitMilestone.GIANTS,
    "[BOSS] Storm: Pathetic Maxor, just like expected." to SplitMilestone.MAXOR,
    "[BOSS] Goldor: Who dares trespass into my domain?" to SplitMilestone.STORM,
    "The Core entrance is opening!" to SplitMilestone.S4,
    "[BOSS] Necron: You went further than any human before, congratulations." to SplitMilestone.GOLDOR,
    // Necron's death line (Devonian `Stages`). Mods that hide boss chat can drop it, so his boss bar emptying after
    // "ARGH!" also ends the split (NoammAddons `F7Titles`); M7 continues into the Wither King phase.
    "[BOSS] Necron: All this, for nothing..." to SplitMilestone.NECRON,
)

internal const val MORT_START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
internal const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
internal const val WATCHER_CLEAR = "[BOSS] The Watcher: You have proven yourself. You may pass."
internal const val NECRON_ARGH = "[BOSS] Necron: ARGH!"
internal val watcherLine = Regex("^\\[BOSS] The Watcher: .+$")
/** Hypixel prefixes the real Livid's name with a colour word, e.g. `[BOSS] Hockey Livid:`. */
internal val lividKill = Regex("^\\[BOSS] \\w+ Livid: Impossible! How did you figure out which one I was\\?!$")
/** System lines only: a player name is a plain identifier, so a relayed player chat line cannot match. */
// Other mods may append timings, e.g. "(7/7) (24.116s | 24.116s)"; anything after the counter is ignored.
internal val terminalProgress = Regex("^(\\w{1,16}) (?:activated|completed) a (?:terminal|device|lever)! \\((\\d)/(\\d)\\)(?: .*)?$")
internal val bossDefeated = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")

/** Pure chat classification shared by the live splitter and the executable check. */
sealed interface SplitSignal {
    data class Milestone(val milestone: SplitMilestone) : SplitSignal
    data class Terminal(val done: Int, val total: Int) : SplitSignal
    data object Start : SplitSignal
    /** Any Watcher line: blood open only when the run has been watched continuously since its start. */
    data object WatcherLine : SplitSignal
    /** Any line from the floor's boss: boss entry only when the run has been watched continuously since its start. */
    data object BossLine : SplitSignal
    /** Necron's last phase has begun: his boss bar reaching zero from here on is his death. */
    data object NecronArgh : SplitSignal
    data object Defeated : SplitSignal
}

fun splitSignal(text: String, floorNumber: Int): SplitSignal? {
    if (text.length > 160) return null
    if (text == MORT_START) return SplitSignal.Start
    if (text == BLOOD_DOOR) return SplitSignal.Milestone(SplitMilestone.BLOOD_OPEN)
    if (text == WATCHER_CLEAR) return SplitSignal.Milestone(SplitMilestone.BLOOD_CLEAR)
    if (watcherLine.matches(text)) return SplitSignal.WatcherLine
    if (bossEntryLines[floorNumber] == text) return SplitSignal.Milestone(SplitMilestone.BOSS_ENTRY)
    bossPhaseLines[text]?.let { return SplitSignal.Milestone(it) }
    if (lividKill.matches(text)) return SplitSignal.Milestone(SplitMilestone.LIVID_KILL)
    if (text == NECRON_ARGH) return SplitSignal.NecronArgh
    bossChatNames[floorNumber]?.let { if (text.startsWith("[BOSS] $it: ")) return SplitSignal.BossLine }
    terminalProgress.matchEntire(text)?.let {
        val done = it.groupValues[2].toInt(); val total = it.groupValues[3].toInt()
        return if (total in 1..9 && done in 0..total) SplitSignal.Terminal(done, total) else null
    }
    if (bossDefeated.matches(text)) return SplitSignal.Defeated
    return null
}

/**
 * Counts F7/M7 terminal sections from the server's `(n/n)` progress lines. A section completes on its first full
 * count; a repeated full count is ignored until a partial count shows the next section has begun.
 */
class TerminalSections {
    private var completed = 0
    private var armed = true
    fun accept(done: Int, total: Int): SplitMilestone? {
        if (done < total) { armed = true; return null }
        // The fourth section ends on the Core entrance line instead.
        if (!armed || completed >= 3) return null
        armed = false
        return listOf(SplitMilestone.S1, SplitMilestone.S2, SplitMilestone.S3)[completed++]
    }
}

enum class MarkResult { ACCEPTED, DUPLICATE, OUT_OF_ORDER, NOT_APPLICABLE }

/** Milliseconds per server tick; server time is ticks × 50 ms, so lag never adds to it. */
const val TICK_MILLIS = 50L

/**
 * Lag lost while a run is live, read once per server time update. A late packet can only make lag look larger, and a
 * server never runs extra ticks to catch up, so the lowest of the last few readings is the honest one and the shown
 * value only ever rises. This keeps the HUD steady instead of swinging with packet jitter.
 */
class LagMeter(private val window: Int = 3) {
    private val readings = ArrayDeque<Long>()
    /** Null until a first reading. */
    var shown: Long? = null
        private set

    fun read(lagMillis: Long) {
        readings.addLast(lagMillis)
        while (readings.size > window) readings.removeFirst()
        val floor = readings.min().coerceAtLeast(0)
        shown = maxOf(shown ?: 0L, floor)
    }
}

/**
 * The server's tick count. The server sends its game time about once a second (every 20 ticks), as tick timers in
 * NoammAddons and Devonian read it; between those updates, each per-tick ping advances it, at most to the next update.
 * Hypixel sends more pings than ticks (about 4% more in M7), so pings are never added up on their own.
 */
class ServerTickClock {
    private var gameTime: Long? = null
    private var pings = 0
    // Never reported lower than before, even when an update lands a tick behind the pings.
    private var floor = 0L

    /** Null until the first game time arrives. */
    val now: Long? get() = gameTime?.let { maxOf(it + pings, floor) }

    /** False when the clock jumped (another world, or a gap of minutes), so timing from before it is not comparable. */
    fun time(value: Long): Boolean {
        val previous = now
        val continuous = previous == null || value >= previous - UPDATE_TICKS && value - previous <= MAX_GAP_TICKS
        gameTime = value; pings = 0; floor = if (continuous) previous ?: value else value
        return continuous
    }

    fun ping() { if (gameTime != null && pings < UPDATE_TICKS) pings++ }

    fun reset() { gameTime = null; pings = 0; floor = 0L }

    private companion object {
        const val UPDATE_TICKS = 20
        const val MAX_GAP_TICKS = 20L * 60 * 5
    }
}

/**
 * Live, single-run split state. Times are monotonic nanoseconds from the caller; they are stored as milliseconds from
 * the run's origin. Each milestone is accepted once, chain milestones only in chain order, and nothing is back-filled.
 *
 * Alongside real time, each mark keeps the server tick count since the origin (NoammAddons `DualTime`): real time is
 * what the clock measured, server time is how many ticks the server ran, and their difference is time lost to lag.
 */
class SplitTimeline(val definition: SplitDefinition, val startObserved: Boolean, private val originNanos: Long,
                    originTicks: Long? = null) {
    private val marked = LinkedHashMap<SplitMilestone, Long>()
    private val tickMarks = LinkedHashMap<SplitMilestone, Long>()
    private var tickOrigin = originTicks
    private var lastChainIndex = -1
    var conflicts = 0
        private set

    init {
        if (startObserved) {
            marked[SplitMilestone.START] = 0L; lastChainIndex = 0
            if (tickOrigin != null) tickMarks[SplitMilestone.START] = 0L
        }
    }

    val marks: Map<SplitMilestone, Long> get() = marked
    val ticks: Map<SplitMilestone, Long> get() = tickMarks
    val finished get() = SplitMilestone.END in marked
    /** False once ticks were missed (a reconnect) or never arrived; earlier tick marks stay valid. */
    val ticksAvailable get() = tickOrigin != null

    fun elapsed(nowNanos: Long): Long = ((nowNanos - originNanos) / 1_000_000L).coerceAtLeast(0)
    fun elapsedTicks(nowTicks: Long?): Long? = tickOrigin?.let { origin -> nowTicks?.let { (it - origin).coerceAtLeast(0) } }

    /** Stop server timing for the rest of the run, e.g. after ticks were missed while disconnected. */
    fun stopTicks() { tickOrigin = null }

    fun mark(milestone: SplitMilestone, atNanos: Long, atTicks: Long? = null): MarkResult {
        if (milestone == SplitMilestone.START) return if (startObserved) MarkResult.DUPLICATE else MarkResult.NOT_APPLICABLE
        if (milestone in marked) return MarkResult.DUPLICATE
        if (finished) return MarkResult.OUT_OF_ORDER
        val at = elapsed(atNanos)
        val result = if (milestone in definition.markers) MarkResult.ACCEPTED else {
            val index = definition.chain.indexOf(milestone)
            when {
                index < 0 -> MarkResult.NOT_APPLICABLE
                // A later phase has already been seen: this event is late or contradictory, never a reason to rewrite history.
                index <= lastChainIndex -> { conflicts++; MarkResult.OUT_OF_ORDER }
                at < (marked.values.maxOrNull() ?: 0L) -> { conflicts++; MarkResult.OUT_OF_ORDER }
                else -> { lastChainIndex = index; MarkResult.ACCEPTED }
            }
        }
        if (result != MarkResult.ACCEPTED) return result
        marked[milestone] = at
        markTicks(milestone, at, atTicks)
        return result
    }

    /** Marks [marker] at the moment [same] was marked, on both clocks: a score confirmed later but reached by then. */
    fun markAt(marker: SplitMilestone, same: SplitMilestone): MarkResult {
        if (marker !in definition.markers) return MarkResult.NOT_APPLICABLE
        if (marker in marked) return MarkResult.DUPLICATE
        if (finished) return MarkResult.OUT_OF_ORDER
        val at = marked[same] ?: return MarkResult.NOT_APPLICABLE
        marked[marker] = at
        tickMarks[same]?.let { tickMarks[marker] = it }
        return MarkResult.ACCEPTED
    }

    private fun markTicks(milestone: SplitMilestone, at: Long, atTicks: Long?) {
        val ticks = elapsedTicks(atTicks) ?: return
        // No game time for a while, or more server time than real time: the tick source is not trustworthy for this run.
        if (at > 10_000 && ticks == 0L || ticks * TICK_MILLIS > at + at / 20 + 1_000) { stopTicks(); return }
        tickMarks[milestone] = ticks
    }

    /** The section currently running: the next chain milestone after the latest one observed. */
    fun current(): SplitMilestone? = if (finished) null else definition.chain.drop(lastChainIndex + 1).firstOrNull()
        ?.takeIf { lastChainIndex >= 0 }

    fun record(id: String, recordedAt: Long, outcome: SplitOutcome) =
        SplitRunRecord(id, definition.floor, definition.version, recordedAt, startObserved, LinkedHashMap(marked), outcome, conflicts,
            LinkedHashMap(tickMarks))
}

/**
 * The inputs Hypixel shows during a run. Null means not yet read; the estimate then counts it as NoammAddons does
 * (nothing found, 36 rooms, no time spent), and [complete] says whether the estimate rests on what the run shows.
 */
data class ScoreInputs(
    val floor: String,
    val secretPercent: Double? = null,
    val clearedPercent: Int? = null,
    val completedRooms: Int? = null,
    val crypts: Int = 0,
    val deaths: Int = 0,
    /** Puzzles not yet completed (unopened, discovered or failed). */
    val openPuzzles: Int = 0,
    val elapsedSeconds: Int? = null,
    val bloodDone: Boolean = false,
    val inBoss: Boolean = false,
    val mimic: Boolean = false,
    val prince: Boolean = false,
    val paul: Boolean = false,
    /** Hypixel's own score, shown on the sidebar after the cleared percentage: `Cleared: 100% (301)`. */
    val shownScore: Int? = null,
) {
    val complete get() = secretPercent != null && clearedPercent != null && completedRooms != null && elapsedSeconds != null
    /** In the boss with Hypixel's score read: nothing from the clear can still change it, so it is the score, not an estimate. */
    val exact get() = inBoss && shownScore != null
}

private val requiredSecrets = mapOf("F1" to 0.3, "F2" to 0.4, "F3" to 0.5, "F4" to 0.6, "F5" to 0.7, "F6" to 0.85, "F7" to 1.0)
private val timeLimits = mapOf("F1" to 600, "F2" to 600, "F3" to 600, "F4" to 720, "F5" to 600, "F6" to 720, "F7" to 840,
    "M1" to 480, "M2" to 480, "M3" to 480, "M4" to 480, "M5" to 480, "M6" to 600, "M7" to 840)

internal fun speedDeduction(percentOver: Double): Double {
    var over = percentOver
    var deduction = 0.0
    for ((cap, divisor) in listOf(20.0 to 2.0, 20.0 to 3.5, 10.0 to 4.0, 10.0 to 5.0)) {
        if (over <= 0) return deduction
        deduction += over.coerceAtMost(cap) / divisor
        over -= cap
    }
    return if (over > 0) deduction + over / 6.0 else deduction
}

/**
 * The run's score if the team finishes it now: NoammAddons `ScoreCalculation.kt` (CC0), the calculator its map shows.
 * Rooms still to be completed by blood and boss are counted, as Hypixel does at the end. Null only for unknown floors.
 */
fun estimateDungeonScore(input: ScoreInputs): Int? {
    val secrets = input.secretPercent ?: 0.0
    val cleared = input.clearedPercent ?: 0
    val completed = input.completedRooms ?: 0
    val elapsed = input.elapsedSeconds ?: 0
    val number = input.floor.drop(1).toIntOrNull() ?: return null
    val required = if (input.floor.startsWith("M")) 1.0 else requiredSecrets[input.floor] ?: return null
    val limit = timeLimits[input.floor] ?: return null
    val totalRooms = if (completed > 0 && cleared > 0) floor(completed / (cleared / 100.0) + 0.4) else 36.0
    val effective = completed + (if (input.bloodDone) 0 else 1) + (if (input.inBoss) 0 else 1)
    val secretScore = floor(secrets / required / 100.0 * 40.0).coerceIn(0.0, 40.0).toInt()
    val roomScore = (effective / totalRooms * 60.0).coerceIn(0.0, 60.0).toInt()
    val skillRooms = floor(effective / totalRooms * 80.0).coerceIn(0.0, 80.0).toInt()
    val deathPenalty = (input.deaths * 2 - 1).coerceAtLeast(0)
    val skill = (20 + skillRooms - input.openPuzzles * 10 - deathPenalty).coerceIn(20, 100)
    val bonus = input.crypts.coerceIn(0, 5) + (if (input.mimic && number > 5) 2 else 0) + (if (input.prince) 1 else 0) +
        (if (input.paul) 10 else 0)
    val speed = if (elapsed <= limit) 100 else (100 - speedDeduction((elapsed - limit) * 100.0 / limit)).toInt().coerceAtLeast(0)
    return secretScore + roomScore + skill + bonus + speed
}

/**
 * The run's score: Hypixel's own once in the boss, where it is final; during the clear the higher of it and the
 * estimate, since the estimate also counts rooms still to come but can miss a bonus such as a mimic killed out of sight.
 */
fun dungeonScore(input: ScoreInputs): Int? {
    val estimate = estimateDungeonScore(input)
    val shown = input.shownScore ?: return estimate
    return if (input.inBoss) shown else maxOf(shown, estimate ?: shown)
}

private val secretPercentLine = Regex("^\\s*Secrets Found: ([\\d.]+)%$")
private val cryptsLine = Regex("^\\s*Crypts: (\\d+)$")
private val completedRoomsLine = Regex("^\\s*Completed Rooms: (\\d+)$")
private val deathsLine = Regex("^\\s*Team Deaths: (\\d+)$")
private val puzzlesHeader = Regex("^\\s*Puzzles: \\((\\d+)\\)$")
private val puzzleLine = Regex("^\\s*.+: \\[([✦✔✖])].*$")
private val clearedLine = Regex("^\\s*Cleared: (\\d+)%(?: \\((\\d+)\\))?.*$")
private val elapsedLine = Regex("^\\s*Time Elapsed: (?:(\\d+)h )?(?:(\\d+)m )?(?:(\\d+)s)?$")

/** Reads the tab list (stripped lines in column order) into [input]; lines that are absent leave values unchanged. */
fun readScoreTabList(input: ScoreInputs, columns: List<List<String>>): ScoreInputs {
    var result = input
    for (column in columns) {
        var puzzleCount: Int? = null
        var puzzlesDone = 0
        var puzzleLines = 0
        for (line in column) {
            secretPercentLine.matchEntire(line)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }?.let { result = result.copy(secretPercent = it) }
            }
            cryptsLine.matchEntire(line)?.let { result = result.copy(crypts = it.groupValues[1].toInt().coerceIn(0, 1000)) }
            completedRoomsLine.matchEntire(line)?.let { result = result.copy(completedRooms = it.groupValues[1].toInt().coerceIn(0, 100)) }
            deathsLine.matchEntire(line)?.let { result = result.copy(deaths = it.groupValues[1].toInt().coerceIn(0, 1000)) }
            puzzlesHeader.matchEntire(line)?.let { puzzleCount = it.groupValues[1].toInt().coerceIn(0, 10); puzzlesDone = 0; puzzleLines = 0; continue }
            if (puzzleCount != null && puzzleLines < puzzleCount) puzzleLine.matchEntire(line)?.let {
                puzzleLines++
                if (it.groupValues[1] == "✔") puzzlesDone++
            }
        }
        puzzleCount?.let { result = result.copy(openPuzzles = (it - puzzlesDone).coerceAtLeast(0)) }
    }
    return result
}

/** Reads the sidebar; `Cleared` changing after the Watcher is done means the blood room now counts as completed. */
fun readScoreSidebar(input: ScoreInputs, lines: List<String>, watcherCleared: Boolean): ScoreInputs {
    var result = input
    for (line in lines) {
        clearedLine.matchEntire(line)?.let { match ->
            val cleared = match.groupValues[1].toInt().coerceIn(0, 100)
            if (watcherCleared && result.clearedPercent != null && cleared != result.clearedPercent) result = result.copy(bloodDone = true)
            result = result.copy(clearedPercent = cleared)
            match.groupValues[2].toIntOrNull()?.takeIf { it in 0..1000 }?.let { result = result.copy(shownScore = it) }
        }
        elapsedLine.matchEntire(line)?.takeIf { match -> match.groupValues.drop(1).any { it.isNotEmpty() } }?.let { match ->
            val (h, m, s) = match.destructured
            result = result.copy(elapsedSeconds = (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0))
        }
    }
    return result
}

/**
 * Mimic and Prince kills: Hypixel's own Prince line, and what teammates' mods announce in party chat, so a kill out of
 * sight still counts (NoammAddons `ScoreCalculation`). Matched case-insensitively anywhere in a chat line, as there.
 */
private val mimicMessages = setOf("mimic dead!", "mimic dead", "mimic killed!", "mimic killed", "\$skytils-dungeon-score-mimic\$",
    "child destroyed!", "mimic obliterated!", "mimic exorcised!", "mimic destroyed!", "mimic annhilated!", "breefing killed", "breefing dead")
private val princeMessages = setOf("prince dead", "prince dead!", "\$skytils-dungeon-score-prince\$", "prince killed", "prince slain",
    "prince killed!", "a prince falls. +1 bonus score")

internal fun announcesMimic(text: String) = text.lowercase().let { line -> mimicMessages.any { it in line } }
internal fun announcesPrince(text: String) = text.lowercase().let { line -> princeMessages.any { it in line } }
internal val mimicCharm = Regex("^CHARM You charmed a Mimic and captured (?:\\d+ shards from it|its shard)\\.$", RegexOption.IGNORE_CASE)

/**
 * Which clock a time is measured on. [REAL] is the stopwatch and includes lag; [SERVER] counts server ticks × 50 ms,
 * so lag does not add to it. The difference between them is the time lost to lag.
 */
enum class SplitClock(val title: String) {
    REAL("Real time"), SERVER("Server time");
    val other get() = if (this == REAL) SERVER else REAL
    override fun toString() = title
}

/** What live splits are compared with: the best, the average over the chosen runs, or the worst completed time. */
enum class SplitComparison(val title: String) {
    BEST("Best"), AVERAGE("Average"), WORST("Worst");
    override fun toString() = title
}

/**
 * How a saved run ended. Only [COMPLETED] runs with an observed start can supply an actual PB, a theoretical PB section
 * or an overall average; other runs keep their directly observed sections for section averages only.
 */
enum class SplitOutcome(val title: String) { COMPLETED("Completed"), FAILED("Failed"), ABANDONED("Left early") }

/**
 * One saved run. [marks] are milliseconds since the run's start when [startObserved], otherwise since the first
 * milestone observed after joining late; values are never filled in for milestones that were not seen.
 */
data class SplitRunRecord(
    val id: String,
    val floor: String,
    val definition: Int,
    val recordedAt: Long,
    val startObserved: Boolean,
    val marks: Map<SplitMilestone, Long>,
    val outcome: SplitOutcome,
    /** Conflicting or out-of-order events that were rejected; the run stays saved but is never eligible for PBs. */
    val conflicts: Int = 0,
    /** Server ticks since the same origin, for milestones where ticks were being counted. */
    val ticks: Map<SplitMilestone, Long> = emptyMap(),
) {
    val total: Long? get() = total(SplitClock.REAL)
    fun times(clock: SplitClock): Map<SplitMilestone, Long> =
        if (clock == SplitClock.REAL) marks else ticks.mapValues { it.value * TICK_MILLIS }
    fun total(clock: SplitClock): Long? {
        if (!startObserved || outcome != SplitOutcome.COMPLETED || conflicts != 0) return null
        val times = times(clock)
        return times[SplitMilestone.END]?.takeIf { clock == SplitClock.REAL || times[SplitMilestone.START] == 0L }
    }
    /** Real minus server time for the whole run: how much the run was slowed by lag. */
    val lagLost: Long? get() = total(SplitClock.SERVER)?.let { server -> total!! - server }
}

/** A section ending at [milestone], measured from the preceding milestone of the floor's chain. */
data class SplitSection(val milestone: SplitMilestone, val from: SplitMilestone)

/** Duration of every section both of whose boundaries were observed, in the order of the floor's chain. */
fun sectionDurations(record: SplitRunRecord, definition: SplitDefinition, clock: SplitClock = SplitClock.REAL): Map<SplitMilestone, Long> {
    if (record.definition != definition.version || record.floor != definition.floor) return emptyMap()
    val result = LinkedHashMap<SplitMilestone, Long>()
    for (section in definition.sections) spanDuration(record, section.from, section.milestone, clock)?.let { result[section.milestone] = it }
    return result
}

/** A run that may supply section PBs and the theoretical PB: completed, started in view, conflict-free and fully split. */
fun splitRunEligible(record: SplitRunRecord, definition: SplitDefinition, clock: SplitClock = SplitClock.REAL): Boolean =
    record.total(clock) != null && record.definition == definition.version && record.floor == definition.floor &&
        sectionDurations(record, definition, clock).size == definition.sections.size

enum class SplitWindow(val title: String, val runs: Int?) {
    ALL("All time", null), LAST_10("Last 10", 10), LAST_25("Last 25", 25), LAST_50("Last 50", 50);
    override fun toString() = title
}

data class SectionBest(val duration: Long, val run: String, val recordedAt: Long)

/** Best, average and worst of one timed span; [worst] comes from the same runs as [best]. */
interface SpanStats {
    val best: SectionBest?
    val average: Double?
    val worst: SectionBest?
    /** The time [comparison] measures against, or null when there is none yet. */
    fun reference(comparison: SplitComparison): Long? = when (comparison) {
        SplitComparison.BEST -> best?.duration
        SplitComparison.AVERAGE -> average?.toLong()
        SplitComparison.WORST -> worst?.duration
    }
}

data class SectionStats(
    val section: SplitSection,
    override val best: SectionBest?,
    override val average: Double?,
    val samples: Int,
    override val worst: SectionBest? = null,
) : SpanStats

/** A marker such as 300 score, or a checkpoint such as boss entry, timed from the run start and kept out of the theoretical total. */
data class MarkerStats(val milestone: SplitMilestone, override val best: SectionBest?, override val average: Double?, val samples: Int,
                       override val worst: SectionBest? = null) : SpanStats

/** A block of sections (Terms) timed within single runs; its best is a real run's time, not a sum of section bests. */
data class GroupStats(val group: SplitGroup, override val best: SectionBest?, override val average: Double?, val samples: Int,
                      override val worst: SectionBest? = null) : SpanStats

data class FloorSplitStats(
    val definition: SplitDefinition,
    val clock: SplitClock,
    val markers: List<MarkerStats>,
    val actualPb: SectionBest?,
    /**
     * The best checkpoint (boss entry) plus the best of each section after it; null when any of those has no best, as a
     * missing section is never counted as zero.
     */
    val theoreticalPb: Long?,
    val sections: List<SectionStats>,
    val groups: List<GroupStats>,
    val averageTotal: Double?,
    val averageSamples: Int,
    val completedRuns: Int,
    val savedRuns: Int,
    /** Average real-minus-server time over the window's completed runs that have both clocks. */
    val averageLag: Double?,
    /** Checkpoints such as boss entry, timed from the start. */
    val checkpoints: List<MarkerStats> = emptyList(),
    /** The slowest completed run, from the same runs as [actualPb]. */
    val worstTotal: SectionBest? = null,
) {
    /** The whole run's time [comparison] measures against. */
    fun totalReference(comparison: SplitComparison): Long? = when (comparison) {
        SplitComparison.BEST -> actualPb?.duration
        SplitComparison.AVERAGE -> averageTotal?.toLong()
        SplitComparison.WORST -> worstTotal?.duration
    }
}

/** Time from [from] to [to] in one run on [clock], when both were observed. */
internal fun spanDuration(record: SplitRunRecord, from: SplitMilestone, to: SplitMilestone, clock: SplitClock): Long? {
    val times = record.times(clock)
    val start = if (from == SplitMilestone.START) times[from]?.takeIf { record.startObserved && it == 0L } else times[from]
    val end = times[to]
    return if (start == null || end == null || end < start) null else end - start
}

/**
 * Actual PB, per-section PBs with their source runs, the theoretical PB and windowed averages for one floor, all on
 * one [clock]. [runs] may contain any floor and definition; only matching records are used.
 */
fun floorSplitStats(runs: List<SplitRunRecord>, definition: SplitDefinition, window: SplitWindow,
                    clock: SplitClock = SplitClock.REAL): FloorSplitStats {
    val floorRuns = runs.filter { it.floor == definition.floor && it.definition == definition.version }.sortedByDescending { it.recordedAt }
    val completed = floorRuns.filter { it.total(clock) != null }
    val eligible = floorRuns.filter { splitRunEligible(it, definition, clock) }
    val actual = completed.minWithOrNull(compareBy<SplitRunRecord> { it.total(clock)!! }.thenBy { it.recordedAt })
        ?.let { SectionBest(it.total(clock)!!, it.id, it.recordedAt) }
    val worstRun = completed.maxWithOrNull(compareBy<SplitRunRecord> { it.total(clock)!! }.thenBy { -it.recordedAt })
        ?.let { SectionBest(it.total(clock)!!, it.id, it.recordedAt) }
    val durations = floorRuns.associate { it.id to sectionDurations(it, definition, clock) }
    fun windowed(values: List<Long>) = window.runs?.let { values.take(it) } ?: values
    fun fastest(values: List<SectionBest>) = values.minWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { it.recordedAt })
    // The earliest of equally slow times, as for the fastest.
    fun slowest(values: List<SectionBest>) = values.maxWithOrNull(compareBy<SectionBest> { it.duration }.thenBy { -it.recordedAt })
    val sections = definition.sections.map { section ->
        val timed = eligible.mapNotNull { run -> durations.getValue(run.id)[section.milestone]?.let { SectionBest(it, run.id, run.recordedAt) } }
        // Conflicting runs never contribute; partial runs only contribute sections they observed directly.
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { durations.getValue(it.id)[section.milestone] })
        SectionStats(section, fastest(timed), observed.takeIf { it.isNotEmpty() }?.average(), observed.size, slowest(timed))
    }
    val groups = definition.groups.map { group ->
        val timed = eligible.mapNotNull { run -> spanDuration(run, group.from, group.to, clock)?.let { SectionBest(it, run.id, run.recordedAt) } }
        val observed = windowed(floorRuns.filter { it.conflicts == 0 }.mapNotNull { spanDuration(it, group.from, group.to, clock) })
        GroupStats(group, fastest(timed), observed.takeIf { it.isNotEmpty() }?.average(), observed.size, slowest(timed))
    }
    val totals = windowed(completed.map { it.total(clock)!! })
    // Time from the observed start; the run need not finish for the moment it reached 300 to be real.
    fun fromStart(milestone: SplitMilestone): MarkerStats {
        val observed = floorRuns.filter { it.startObserved && it.conflicts == 0 }
            .mapNotNull { run -> spanDuration(run, SplitMilestone.START, milestone, clock)?.let { SectionBest(it, run.id, run.recordedAt) } }
        val window = windowed(observed.map { it.duration })
        return MarkerStats(milestone, fastest(observed), window.takeIf { it.isNotEmpty() }?.average(), window.size, slowest(observed))
    }
    val markers = definition.markers.map(::fromStart)
    val checkpoints = definition.checkpoints.map(::fromStart)
    // The clear's fastest sections rarely happen in one run, so the best boss entry, a real run's time, stands in for
    // every section before it.
    val cut = checkpoints.filter { it.best != null }.maxByOrNull { definition.chain.indexOf(it.milestone) }
    val after = cut?.let { c -> sections.filter { definition.chain.indexOf(it.section.milestone) > definition.chain.indexOf(c.milestone) } } ?: sections
    val theoretical = after.takeIf { list -> (cut != null || list.isNotEmpty()) && list.all { it.best != null } }
        ?.sumOf { it.best!!.duration }?.plus(cut?.best?.duration ?: 0L)
    val lag = windowed(floorRuns.mapNotNull { it.lagLost })
    return FloorSplitStats(definition, clock, markers, actual, theoretical, sections, groups, totals.takeIf { it.isNotEmpty() }?.average(),
        totals.size, completed.size, floorRuns.size, lag.takeIf { it.isNotEmpty() }?.average(), checkpoints, worstRun)
}

/** The chat line for an ended run: real time, server time and the lag between them; null when the start was missed. */
internal fun splitRunSummary(record: SplitRunRecord): String? {
    if (!record.startObserved) return null
    val real = record.marks[SplitMilestone.END] ?: return null
    val ticks = record.times(SplitClock.SERVER)
    val server = ticks[SplitMilestone.END]?.takeIf { ticks[SplitMilestone.START] == 0L }
        ?: return "§b${record.floor} §7run §f${formatSplitTime(real)} §8· §7server time unavailable"
    val lag = (real - server).coerceAtLeast(0)
    return "§b${record.floor} §7run §f${formatSplitTime(real)} §8· §7server §b${formatSplitTime(server)} §8· §7lag ${if (lag >= 1_000) "§c" else "§7"}+${formatSplitTime(lag)}"
}

fun formatSplitTime(millis: Long?, precise: Boolean = true): String {
    if (millis == null || millis < 0) return "—"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (millis % 1000) / 100
    val hundredths = (millis % 1000) / 10
    return when {
        minutes > 0 && precise -> String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths)
        minutes > 0 -> String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        precise -> String.format(Locale.ROOT, "%d.%02d", seconds, hundredths)
        else -> "${seconds}s"
    }
}

fun formatSplitDelta(millis: Long?): String {
    if (millis == null) return ""
    val sign = if (millis < 0) "-" else "+"
    val magnitude = kotlin.math.abs(millis)
    return sign + if (magnitude >= 60_000) formatSplitTime(magnitude) else String.format(Locale.ROOT, "%d.%02d", magnitude / 1000, (magnitude % 1000) / 10)
}

internal const val SPLIT_STORE_VERSION = 1
/** Newest runs are kept; older ones are dropped once this many are saved across all floors. */
internal const val SPLIT_STORE_LIMIT = 4000
private const val MAX_MARK = 6 * 60 * 60 * 1000L
private val SAVED_INTEGER = Regex("-?[0-9]{1,15}")

internal fun encodeSplitRun(run: SplitRunRecord) = JsonObject().apply {
    addProperty("id", run.id); addProperty("floor", run.floor); addProperty("definition", run.definition)
    addProperty("recordedAt", run.recordedAt); addProperty("startObserved", run.startObserved)
    addProperty("outcome", run.outcome.name)
    if (run.conflicts > 0) addProperty("conflicts", run.conflicts)
    add("marks", JsonObject().apply { run.marks.forEach { (milestone, at) -> addProperty(milestone.name, at) } })
    if (run.ticks.isNotEmpty()) add("serverTicks", JsonObject().apply { run.ticks.forEach { (milestone, at) -> addProperty(milestone.name, at) } })
}

/** 2.7.12 saved the fourth terminal section as `CORE`; it has the same boundary as [SplitMilestone.S4]. */
private fun savedMilestone(name: String) = if (name == "CORE") SplitMilestone.S4 else SplitMilestone.entries.firstOrNull { it.name == name }

/** Rejects malformed rows instead of repairing them, so a damaged record can never produce a better time. */
internal fun parseSplitRun(element: JsonElement?): SplitRunRecord? = runCatching {
    val row = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun string(key: String) = row.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun long(value: JsonElement?) = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
        ?.takeIf { it.matches(SAVED_INTEGER) }?.toLong()
    val id = string("id")?.takeIf { it.length in 1..64 } ?: return null
    val floor = string("floor")?.takeIf { it in splitDefinitions } ?: return null
    val definition = long(row.get("definition"))?.toInt() ?: return null
    val recordedAt = long(row.get("recordedAt"))?.takeIf { it > 0 } ?: return null
    val start = row.get("startObserved")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
    val outcome = SplitOutcome.entries.firstOrNull { it.name == string("outcome") } ?: return null
    val conflicts = row.get("conflicts")?.let { long(it)?.toInt() ?: return null } ?: 0
    fun times(key: String, limit: Long): Map<SplitMilestone, Long>? {
        val source = row.get(key) ?: return emptyMap()
        if (!source.isJsonObject) return null
        val parsed = LinkedHashMap<SplitMilestone, Long>()
        for ((name, value) in source.asJsonObject.entrySet()) {
            // A milestone from a newer definition is unknown here; the record keeps only what this version understands.
            val milestone = savedMilestone(name) ?: continue
            parsed[milestone] = long(value)?.takeIf { it in 0..limit } ?: return null
        }
        return parsed
    }
    val parsed = row.get("marks")?.takeIf { it.isJsonObject }?.let { times("marks", MAX_MARK) } ?: return null
    // Server ticks may be missing (older saves, no game time); a damaged tick row drops only the server times. 2.7.13
    // saved counted pings as "ticks", which run fast on Hypixel, so those are not read as server time.
    val ticks = times("serverTicks", MAX_MARK / TICK_MILLIS)?.filterKeys { it in parsed } ?: emptyMap()
    if (start && parsed[SplitMilestone.START]?.let { it != 0L } == true) return null
    SplitRunRecord(id, floor, definition, recordedAt, start, parsed, outcome, conflicts.coerceAtLeast(0),
        ticks.takeIf { it[SplitMilestone.START]?.let { first -> first == 0L } ?: true } ?: emptyMap())
}.getOrNull()

internal fun encodeSplitStore(runs: List<SplitRunRecord>) = JsonObject().apply {
    addProperty("version", SPLIT_STORE_VERSION)
    add("runs", JsonArray().apply { runs.forEach { add(encodeSplitRun(it)) } })
}

internal fun parseSplitStore(root: JsonElement?): List<SplitRunRecord> {
    val source = root?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    if (source.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt != SPLIT_STORE_VERSION) return emptyList()
    val rows = source.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return rows.asSequence().mapNotNull(::parseSplitRun).distinctBy { it.id }.sortedBy { it.recordedAt }.toList().takeLast(SPLIT_STORE_LIMIT)
}

/** Local history in config/skymyce/dungeon-splits.json; written atomically off the render thread. */
object DungeonSplitStore {
    private var saved: List<SplitRunRecord> = emptyList()
    private val pending = mutableListOf<SplitRunRecord>()
    private var loaded = false
    private var initialized = false
    private lateinit var writer: StateWriter<JsonObject>
    var status = "Loading saved splits…"
        private set
    var revision = 0L
        private set
    val runs: List<SplitRunRecord> get() = saved

    fun initialize() {
        if (initialized) return
        initialized = true
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("dungeon-splits.json"), 8 * 1024 * 1024)
        writer = StateWriter({ file.write(it) }, { action -> Scheduling.schedule(250.milliseconds) { action() } }, {
            SkyMyce.logger.warn("Could not save dungeon splits; previous file retained")
        })
        Scheduling.schedule(0.milliseconds) {
            val decoded = runCatching { parseSplitStore(file.read()) }
                .onFailure { SkyMyce.logger.warn("Could not read dungeon splits; starting with an empty history", it) }.getOrDefault(emptyList())
            val damaged = file.damaged
            MC.instance.execute {
                saved = (decoded + pending).distinctBy { it.id }.takeLast(SPLIT_STORE_LIMIT)
                val edited = pending.isNotEmpty()
                pending.clear(); loaded = true; revision++
                status = if (damaged) "Saved splits were unreadable; the original file is kept" else "${saved.size} saved runs"
                if (edited) persist()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Dungeon-Splits-Save"))
    }

    fun record(run: SplitRunRecord) {
        if (!loaded) { pending.removeAll { it.id == run.id }; pending += run; return }
        saved = (saved.filter { it.id != run.id } + run).takeLast(SPLIT_STORE_LIMIT)
        revision++
        status = "${saved.size} saved runs"
        persist()
    }

    fun clear(floor: String) {
        if (!loaded) return
        saved = saved.filter { it.floor != floor }
        revision++; status = "${saved.size} saved runs"; persist()
    }

    private fun persist() { if (::writer.isInitialized) writer.submit(encodeSplitStore(saved)) }
}
