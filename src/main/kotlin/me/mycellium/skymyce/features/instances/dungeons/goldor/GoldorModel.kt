package me.mycellium.skymyce.features.instances.dungeons.goldor

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stable identifiers for Goldor (F7/M7 phase 3) coordination. Codes are part of the party-chat protocol and the
 * strategy profiles, so existing codes must never be renamed or reused for something else.
 */
enum class GoldorRole(val code: Char, val title: String, val short: String, val color: Int) {
    HEALER('h', "Healer", "H", 0xF06AC9),
    MAGE('m', "Mage", "M", 0x55D9FF),
    BERSERKER('b', "Berserker", "B", 0xFF5555),
    ARCHER('a', "Archer", "A", 0xFFAA00),
    TANK('t', "Tank", "T", 0x55FF55);

    companion object {
        fun ofCode(code: Char) = entries.firstOrNull { it.code == code }
        /** Hypixel's class names, including "Berserk" from the tab list and party finder. */
        fun ofName(name: String?): GoldorRole? = when (name?.trim()?.lowercase()) {
            "healer" -> HEALER; "mage" -> MAGE; "berserk", "berserker" -> BERSERKER; "archer" -> ARCHER; "tank" -> TANK
            else -> null
        }
    }
}

/** The four terminal sections, then the Core run after S4. [number] is the protocol digit. */
enum class GoldorSection(val number: Int, val title: String) {
    S1(1, "S1"), S2(2, "S2"), S3(3, "S3"), S4(4, "S4"), CORE(5, "Core");

    val next get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun ofNumber(number: Int) = entries.firstOrNull { it.number == number }
    }
}

enum class ObjectiveKind { TERMINAL, DEVICE, LEVER }

/**
 * The two levers of a section, as seen walking in. Only S4's differ in height: its right lever is the top one (y 128)
 * and its left lever the bottom one (y 121), which is how the S4 lever priorities are written.
 */
enum class LeverSide(val code: Char) {
    LEFT('l'), RIGHT('r');

    fun title(section: GoldorSection) = if (section == GoldorSection.S4) (if (this == RIGHT) "top" else "bottom") else if (this == LEFT) "left" else "right"
}

/**
 * One objective of a section: a numbered terminal, the section's device, or one of its two levers.
 * [id] is stable, e.g. `s2.t4`, `s3.dev`, `s4.lr`.
 */
data class ObjectiveId(val section: GoldorSection, val kind: ObjectiveKind, val number: Int = 0, val side: LeverSide? = null) {
    val id: String get() = "s${section.number}." + when (kind) {
        ObjectiveKind.TERMINAL -> "t$number"
        ObjectiveKind.DEVICE -> "dev"
        ObjectiveKind.LEVER -> "l${side!!.code}"
    }
    val label: String get() = when (kind) {
        ObjectiveKind.TERMINAL -> "T$number"
        ObjectiveKind.DEVICE -> "Dev"
        ObjectiveKind.LEVER -> if (section == GoldorSection.S4) (if (side == LeverSide.RIGHT) "Top" else "Bot") else if (side == LeverSide.LEFT) "LL" else "RL"
    }
    override fun toString() = id

    companion object {
        fun terminal(section: GoldorSection, number: Int) = ObjectiveId(section, ObjectiveKind.TERMINAL, number)
        fun device(section: GoldorSection) = ObjectiveId(section, ObjectiveKind.DEVICE)
        fun lever(section: GoldorSection, side: LeverSide) = ObjectiveId(section, ObjectiveKind.LEVER, side = side)
    }
}

/**
 * Terminal puzzles, identified by their window titles. Codes are part of the protocol.
 * Titles as Hypixel shows them; matching ignores colour codes and surrounding spaces.
 */
enum class PuzzleType(val code: Char, val title: String) {
    MELODY('m', "Melody"),
    NUMBERS('n', "Numbers"),
    RUBIKS('r', "Rubik's"),
    STARTS_WITH('s', "Starts with"),
    SELECT_ALL('c', "Select all"),
    PANES('p', "Panes");

    companion object {
        private val colourCode = Regex("§.")
        private val startsWithTitle = Regex("^What starts with: '.'\\?$")
        private val selectAllTitle = Regex("^Select all the [A-Z0-9 _]{1,24} items!$")

        fun ofCode(code: Char) = entries.firstOrNull { it.code == code }

        /** The puzzle a terminal window shows, from its title; null for any other window. */
        fun ofTitle(rawTitle: String): PuzzleType? {
            val title = rawTitle.replace(colourCode, "").trim()
            return when {
                title == "Click the button on time!" -> MELODY
                title == "Click in order!" -> NUMBERS
                title == "Change all to same color!" -> RUBIKS
                title == "Correct all the panes!" -> PANES
                title.matches(startsWithTitle) -> STARTS_WITH
                title.matches(selectAllTitle) -> SELECT_ALL
                else -> null
            }
        }
    }
}

/** Where a piece of state came from; shown as confidence on the map. */
enum class StateSource { LOCAL, SHARED, CHAT, INFERRED, ASSIGNED }

/** How sure a prediction is. Low confidence never shows an exact countdown. */
enum class Confidence { HIGH, MEDIUM, LOW }

/** A block position in the F7/M7 boss room; identical on both floors. */
data class BossPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSq(px: Double, py: Double, pz: Double) = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz)
    fun distance(other: BossPoint) = sqrt(distanceSq(other.x, other.y, other.z))
}

/**
 * One objective on the map. [mapX]/[mapY] are pixels on the section's 96×296 map image; [points] are where the
 * objective is used in the world, for position-assisted completion matching. Devices have a single stand point.
 */
data class Station(val objective: ObjectiveId, val mapX: Int, val mapY: Int, val points: List<BossPoint>)

/**
 * The fixed Goldor geometry: terminal and lever coordinates and map positions from DungeonTerminalMap (MIT,
 * © 2026 MisterKrister), device and early-enter stand points from Devonian `PositionMessages` (verified in the
 * 2026-10-17 leap research). Terminal numbers follow the ADG convention documented in `GOLDOR-COORDINATION.md`.
 */
object GoldorLayout {
    /** Completion matching radius around a station, as in DungeonTerminalMap. */
    const val MATCH_RADIUS = 5.0
    const val MAP_WIDTH = 96
    const val MAP_HEIGHT = 296

    private fun p(x: Number, y: Number, z: Number) = BossPoint(x.toDouble(), y.toDouble(), z.toDouble())
    private fun t(section: GoldorSection, n: Int, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.terminal(section, n), mx, my, points.toList())
    private fun l(section: GoldorSection, side: LeverSide, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.lever(section, side), mx, my, points.toList())
    private fun d(section: GoldorSection, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.device(section), mx, my, points.toList())

    val stations: Map<GoldorSection, List<Station>> = mapOf(
        GoldorSection.S1 to listOf(
            t(GoldorSection.S1, 1, 6, 204, p(111, 113, 73), p(110, 113, 73)),
            t(GoldorSection.S1, 2, 6, 174, p(111, 119, 79), p(110, 119, 79)),
            t(GoldorSection.S1, 3, 85, 120, p(89, 112, 92), p(90, 112, 92)),
            t(GoldorSection.S1, 4, 85, 87, p(89, 122, 101), p(90, 122, 101)),
            // Simon Says.
            d(GoldorSection.S1, 6, 116, p(108.5, 120, 94.5)),
            l(GoldorSection.S1, LeverSide.LEFT, 23, 35, p(94, 124, 113), p(94, 125, 113)),
            l(GoldorSection.S1, LeverSide.RIGHT, 72, 35, p(106, 124, 113), p(106, 125, 113)),
        ),
        GoldorSection.S2 to listOf(
            t(GoldorSection.S2, 1, 85, 208, p(68, 109, 121), p(68, 109, 122)),
            t(GoldorSection.S2, 2, 82, 168, p(59, 120, 122), p(59, 119, 123)),
            t(GoldorSection.S2, 3, 85, 129, p(47, 109, 121), p(47, 109, 122)),
            t(GoldorSection.S2, 4, 85, 95, p(40, 124, 122), p(40, 124, 123)),
            t(GoldorSection.S2, 5, 15, 83, p(39, 108, 143), p(39, 108, 142)),
            // The lights device, reached from the EE2 high spot.
            d(GoldorSection.S2, 8, 173, p(60.5, 132, 140.5)),
            l(GoldorSection.S2, LeverSide.LEFT, 22, 18, p(23, 132, 138), p(23, 133, 138)),
            l(GoldorSection.S2, LeverSide.RIGHT, 68, 35, p(27, 124, 127), p(27, 125, 127)),
        ),
        GoldorSection.S3 to listOf(
            t(GoldorSection.S3, 1, 12, 254, p(-3, 109, 112), p(-2, 109, 112)),
            t(GoldorSection.S3, 2, 14, 175, p(-3, 119, 93), p(-2, 119, 93)),
            t(GoldorSection.S3, 3, 76, 158, p(19, 123, 93), p(18, 123, 93)),
            t(GoldorSection.S3, 4, 16, 95, p(-3, 109, 77), p(-2, 109, 77)),
            // Arrows (target practice) device.
            d(GoldorSection.S3, 16, 122, p(1.5, 120, 77.5)),
            l(GoldorSection.S3, LeverSide.LEFT, 23, 19, p(2, 122, 55), p(2, 123, 55)),
            l(GoldorSection.S3, LeverSide.RIGHT, 73, 19, p(14, 122, 55), p(14, 123, 55)),
        ),
        GoldorSection.S4 to listOf(
            t(GoldorSection.S4, 1, 10, 202, p(41, 109, 29), p(41, 109, 30)),
            t(GoldorSection.S4, 2, 10, 185, p(44, 121, 29), p(44, 121, 30)),
            t(GoldorSection.S4, 3, 14, 99, p(67, 109, 29), p(67, 109, 30)),
            t(GoldorSection.S4, 4, 76, 72, p(72, 115, 48), p(72, 114, 47)),
            // i4, the arrow device; Berserker usually does it before S1 ("pre-dev").
            d(GoldorSection.S4, 28, 110, p(63.5, 127, 35.5)),
            // The right lever is the top one (y 128); the left one is the bottom lever (y 121).
            l(GoldorSection.S4, LeverSide.LEFT, 23, 23, p(84, 121, 34), p(84, 122, 34)),
            l(GoldorSection.S4, LeverSide.RIGHT, 73, 15, p(86, 128, 46), p(86, 129, 46)),
        ),
    )

    /** Where each early-enter route stands once it is in. */
    val routePoints: Map<EarlyEnter, BossPoint> = mapOf(
        EarlyEnter.MEE2 to p(58.5, 109, 131.5),
        EarlyEnter.HEE3 to p(2.5, 109, 104.5),
        EarlyEnter.MEE4 to p(54.5, 115, 51.5),
    )
    /** How close a player must be to a route point to count as in. */
    const val ROUTE_RADIUS = 3.0

    fun station(objective: ObjectiveId): Station? = stations[objective.section]?.firstOrNull { it.objective == objective }
    fun stations(section: GoldorSection): List<Station> = stations[section].orEmpty()

    /** The count Hypixel shows as `(n/N)` for a section: every terminal, the device and both levers. */
    fun total(section: GoldorSection) = stations(section).size

    /** The section whose objective count matches a `(n/N)` total; S2 is the only section with 8. */
    fun sectionsWithTotal(total: Int) = GoldorSection.entries.filter { it != GoldorSection.CORE && total(it) == total }

    /** The open station of [kind] nearest to a position within [MATCH_RADIUS], or null. */
    fun nearest(section: GoldorSection, kind: ObjectiveKind, x: Double, y: Double, z: Double, exclude: Set<ObjectiveId>): Station? =
        stations(section).asSequence().filter { it.objective.kind == kind && it.objective !in exclude && it.points.isNotEmpty() }
            .map { station -> station to station.points.minOf { it.distanceSq(x, y, z) } }
            .filter { it.second <= MATCH_RADIUS * MATCH_RADIUS }
            .minByOrNull { it.second }?.first
}

/**
 * Sky-Myce Goldor coordination over party chat, version 1.
 *
 * A message is `smt1:<seq>:<event>[/<event>]…`, at most [MAX_LENGTH] characters and [MAX_EVENTS] events, sent with
 * `/pc`. `<seq>` is one base-36 character the sender advances per message, used only to drop exact repeats. Events:
 *
 * | Event | Meaning | Example |
 * | --- | --- | --- |
 * | `r<role>` | sender's class: h m b a t | `rb` |
 * | `p<section>` | section the sender sees as current: 1–4, 5 = Core | `p2` |
 * | `t<section><n><puzzle><q>` | sender opened terminal n; puzzle m n r s c p, or u unknown; progress in quarters 0–3 | `t24m0` |
 * | `c<section><n>` | sender completed terminal n (Hypixel's line does not say which one) | `c24` |
 * | `l<section><side>` | sender flipped the left (l) or right (r) lever; in S4 right is the top lever | `l4r` |
 * | `d<section><state>` | sender is at the device (a) or finished it (c) | `d3a` |
 * | `e<route><state>` | early-enter route 2 = MEE2, 3 = HEE3, 4 = MEE4/Core: going (g), in (i), abandoned (x) | `e3i` |
 * | `q` | ask SM clients for their current state, once | `q` |
 *
 * Messages carry state, never commands. A reply to `q` is the sender's own state and is never answered, so clients
 * cannot echo each other. Nothing here is sent anywhere but party chat.
 */
object GoldorProtocol {
    const val MARKER = "smt1"
    const val MAX_LENGTH = 40
    const val MAX_EVENTS = 5
    private val shape = Regex("^smt1:([0-9a-z]):([a-z0-9]{1,5}(?:/[a-z0-9]{1,5}){0,4})$")
    private val eventShape = Regex("^(?:r[hmbat]|p[1-5]|t[1-4][1-5][mnrscpu][0-3]|c[1-4][1-5]|l[1-4][lr]|d[1-4][ac]|e[234][gix]|q)$")

    /** True for anything that claims to be a protocol message, valid or not, so it can be hidden from chat. */
    fun looksLikeProtocol(message: String) = message.trim().startsWith("$MARKER:")

    fun encode(seq: Int, events: List<GoldorEvent>): String {
        require(events.size in 1..MAX_EVENTS)
        val text = "$MARKER:${Character.forDigit(Math.floorMod(seq, 36), 36)}:" + events.joinToString("/") { it.code }
        require(text.length <= MAX_LENGTH) { "Protocol message too long: $text" }
        return text
    }

    /** Events in [message], or null unless the whole message is a valid v1 message. Unknown or extra text is rejected. */
    fun decode(message: String): Decoded? {
        val text = message.trim()
        if (text.length > MAX_LENGTH) return null
        val match = shape.matchEntire(text) ?: return null
        val codes = match.groupValues[2].split('/')
        if (codes.any { !eventShape.matches(it) } || codes.distinct().size != codes.size) return null
        return Decoded(match.groupValues[1][0].digitToInt(36), codes.map { GoldorEvent.parse(it)!! })
    }

    data class Decoded(val seq: Int, val events: List<GoldorEvent>)
}

/** One protocol event; [code] is its wire form and [key] which earlier event it supersedes in the outbox. */
sealed class GoldorEvent(val code: String, val key: String) {
    data class Role(val role: GoldorRole) : GoldorEvent("r${role.code}", "r")
    data class Section(val section: GoldorSection) : GoldorEvent("p${section.number}", "p")
    data class Working(val terminal: ObjectiveId, val puzzle: PuzzleType?, val quarters: Int) :
        GoldorEvent("t${terminal.section.number}${terminal.number}${puzzle?.code ?: 'u'}$quarters", "t${terminal.id}")
    data class Completed(val terminal: ObjectiveId) : GoldorEvent("c${terminal.section.number}${terminal.number}", "c${terminal.id}")
    data class Lever(val lever: ObjectiveId) : GoldorEvent("l${lever.section.number}${lever.side!!.code}", "l${lever.id}")
    data class Device(val section: GoldorSection, val done: Boolean) : GoldorEvent("d${section.number}${if (done) 'c' else 'a'}", "d${section.number}")
    data class Route(val route: EarlyEnter, val state: RouteState) : GoldorEvent("e${route.code}${state.code}", "e${route.code}")
    data object SyncRequest : GoldorEvent("q", "q")

    companion object {
        /** A single validated event code; null for anything else. */
        fun parse(code: String): GoldorEvent? {
            fun section(c: Char): GoldorSection? = c.digitToIntOrNull()?.let(GoldorSection::ofNumber)
            return when (code.firstOrNull()) {
                'r' -> GoldorRole.ofCode(code.getOrNull(1) ?: return null)?.takeIf { code.length == 2 }?.let(::Role)
                'p' -> section(code.getOrNull(1) ?: return null)?.takeIf { code.length == 2 }?.let(::Section)
                't' -> {
                    if (code.length != 5) return null
                    val section = section(code[1])?.takeIf { it != GoldorSection.CORE } ?: return null
                    val number = code[2].digitToIntOrNull()?.takeIf { it in 1..5 } ?: return null
                    val puzzle = if (code[3] == 'u') null else PuzzleType.ofCode(code[3]) ?: return null
                    val quarters = code[4].digitToIntOrNull()?.takeIf { it in 0..3 } ?: return null
                    Working(ObjectiveId.terminal(section, number), puzzle, quarters)
                }
                'c' -> {
                    if (code.length != 3) return null
                    val section = section(code[1])?.takeIf { it != GoldorSection.CORE } ?: return null
                    Completed(ObjectiveId.terminal(section, code[2].digitToIntOrNull()?.takeIf { it in 1..5 } ?: return null))
                }
                'l' -> {
                    if (code.length != 3) return null
                    val section = section(code[1])?.takeIf { it != GoldorSection.CORE } ?: return null
                    Lever(ObjectiveId.lever(section, LeverSide.entries.firstOrNull { it.code == code[2] } ?: return null))
                }
                'd' -> {
                    if (code.length != 3 || code[2] !in "ac") return null
                    Device(section(code[1])?.takeIf { it != GoldorSection.CORE } ?: return null, code[2] == 'c')
                }
                'e' -> {
                    if (code.length != 3) return null
                    Route(EarlyEnter.ofCode(code[1]) ?: return null, RouteState.ofCode(code[2]) ?: return null)
                }
                'q' -> SyncRequest.takeIf { code.length == 1 }
                else -> null
            }
        }
    }
}

/** Early-enter routes, by the section the route enters early. */
enum class EarlyEnter(val code: Char, val title: String, val section: GoldorSection) {
    MEE2('2', "MEE2", GoldorSection.S2), HEE3('3', "HEE3", GoldorSection.S3), MEE4('4', "MEE4 Core", GoldorSection.CORE);

    companion object { fun ofCode(code: Char) = entries.firstOrNull { it.code == code } }
}

enum class RouteState(val code: Char) {
    PENDING('-'), GOING('g'), IN('i'), ABANDONED('x');

    companion object { fun ofCode(code: Char) = entries.firstOrNull { it.code == code && it != PENDING } }
}

/**
 * Drops repeats of the same message from the same sender (Hypixel can echo, and a resent message keeps its sequence)
 * and messages from ourselves. Entries expire after [windowMillis].
 */
class ProtocolDedupe(private val windowMillis: Long = 30_000, private val capacity: Int = 256) {
    private val seen = LinkedHashMap<String, Long>()

    fun accept(sender: String, self: String, message: String, now: Long): Boolean {
        if (sender.equals(self, ignoreCase = true)) return false
        seen.entries.removeIf { now - it.value > windowMillis }
        val key = sender.lowercase() + "\u0000" + message.trim()
        if (key in seen) return false
        seen[key] = now
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }

    fun clear() = seen.clear()
}

/**
 * Outgoing events, coalesced and rate limited. A newer event with the same [GoldorEvent.key] replaces an unsent one,
 * so progress updates never queue up. At most one message per [minIntervalMillis] and [perMinute] per minute; a
 * message packs as many pending events as fit.
 */
class ProtocolOutbox(private val minIntervalMillis: Long = 1_500, private val perMinute: Int = 12) {
    private val pending = LinkedHashMap<String, GoldorEvent>()
    private val sentAt = ArrayDeque<Long>()
    private var seq = 0
    /** The last event sent per key, so an unchanged state is never sent twice. */
    private val sent = HashMap<String, String>()

    val size get() = pending.size

    fun offer(event: GoldorEvent) {
        if (sent[event.key] == event.code && event !is GoldorEvent.SyncRequest) { pending.remove(event.key); return }
        pending.remove(event.key); pending[event.key] = event
    }

    /** Forget what was sent, e.g. to answer a sync request with the full current state again. */
    fun resendable(keys: Collection<String>) { keys.forEach(sent::remove) }

    /** The next message to send now, or null when nothing is pending or the rate limit applies. */
    fun poll(now: Long): String? {
        if (pending.isEmpty()) return null
        while (sentAt.isNotEmpty() && now - sentAt.first() >= 60_000) sentAt.removeFirst()
        if (sentAt.size >= perMinute || sentAt.isNotEmpty() && now - sentAt.last() < minIntervalMillis) return null
        val batch = ArrayList<GoldorEvent>()
        for (event in pending.values.toList()) {
            if (batch.size == GoldorProtocol.MAX_EVENTS) break
            val candidate = batch + event
            if (runCatching { GoldorProtocol.encode(seq, candidate) }.isFailure) break
            batch += event
        }
        if (batch.isEmpty()) return null
        val text = GoldorProtocol.encode(seq, batch)
        seq = (seq + 1) % 36
        batch.forEach { pending.remove(it.key); sent[it.key] = it.code }
        sentAt.addLast(now)
        return text
    }

    fun clear() { pending.clear(); sent.clear() }
}

enum class ObjectiveStatus { OPEN, ACTIVE, COMPLETE }

/**
 * What is known about one objective. [ambiguous] is set when a completion could not be pinned to this exact objective
 * (no position, no shared id) and it was chosen by assignment order; a later exact report moves it.
 */
data class ObjectiveState(
    val status: ObjectiveStatus = ObjectiveStatus.OPEN,
    val by: String? = null,
    val puzzle: PuzzleType? = null,
    val quarters: Int = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val source: StateSource = StateSource.ASSIGNED,
    val ambiguous: Boolean = false,
) {
    val work get() = TerminalWork(puzzle, quarters, startedAt, status == ObjectiveStatus.COMPLETE)
}

/** A party member as this client sees them. Positions older than [GoldorRunState.POSITION_TTL] are stale. */
data class GoldorPlayer(
    val name: String,
    val role: GoldorRole?,
    val sm: Boolean = false,
    val position: BossPoint? = null,
    val seenAt: Long? = null,
    val alive: Boolean = true,
)

/** An early-enter route's progress: the state, where it came from, and when it last changed. */
data class RouteObservation(val state: RouteState = RouteState.PENDING, val source: StateSource = StateSource.ASSIGNED, val at: Long? = null)

/**
 * The live phase-3 state for one run. Every input is a method taking the current time, so the same sequence of inputs
 * always produces the same state. It knows the profile only to resolve completions by assignment when nothing more
 * precise is available.
 */
class GoldorRunState(var profile: StrategyProfile) {
    companion object {
        const val POSITION_TTL = 3_000L
        /** How long a gate line seen before n/n still counts for that section. */
        const val GATE_EARLY_MILLIS = 3_000L
    }

    var active = false
        private set
    var section = GoldorSection.S1
        private set
    var startedAt: Long? = null
        private set
    /** When the current section's counter completed; null while work remains. */
    var sectionDoneAt: Long? = null
        private set
    /** When the current section's gate was seen opening (or the next section's first completion arrived). */
    var gateOpenedAt: Long? = null
        private set
    val objectives = HashMap<ObjectiveId, ObjectiveState>()
    val players = LinkedHashMap<String, GoldorPlayer>()
    val routes = HashMap<EarlyEnter, RouteObservation>()
    /** The latest `(n/N)` per section. */
    val counters = HashMap<GoldorSection, Int>()
    var tickOrigin: Long? = null
        private set
    var revision = 0L
        private set
    /** Conditions the player confirmed (`/sm goldor if`), for this run only. */
    val confirmations = HashMap<GuideCondition, Boolean>()
    /** When a party member last called "SS reset" / "SSRS" in party chat this run. */
    var ssResetAt: Long? = null
        private set

    private fun changed() { revision++ }

    /** The player says [condition] holds (true), does not (false), or clears it (null). */
    fun confirm(condition: GuideCondition, value: Boolean?) {
        if (value == null) confirmations.remove(condition) else confirmations[condition] = value
        changed()
    }

    /** A party member called an SS reset; only counts while the phase is on. */
    fun ssReset(now: Long) { if (active && ssResetAt == null) { ssResetAt = now; changed() } }

    fun reset() {
        active = false; section = GoldorSection.S1; startedAt = null; sectionDoneAt = null; gateOpenedAt = null
        objectives.clear(); routes.clear(); counters.clear(); tickOrigin = null
        confirmations.clear(); ssResetAt = null; pendingGateAt = null
        players.replaceAll { _, p -> p.copy(sm = false) }
        changed()
    }

    /** Goldor's opening line: phase 3 starts, S1 is open and the first tick follows at the profile's delay. */
    fun start(now: Long) {
        if (active) return
        active = true; section = GoldorSection.S1; startedAt = now; gateOpenedAt = now
        tickOrigin = now + profile.tick.firstAfterStartMillis
        changed()
    }

    /** The tick is only ever predicted from Goldor's opening line, never observed. */
    fun tick() = GoldorTick(tickOrigin, profile.tick.periodMillis, false)

    fun state(objective: ObjectiveId) = objectives[objective] ?: ObjectiveState()

    fun player(name: String) = players.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun playerWith(role: GoldorRole) = players.values.firstOrNull { it.role == role }

    fun updatePlayer(name: String, change: (GoldorPlayer) -> GoldorPlayer) {
        val key = players.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        val before = players[key] ?: GoldorPlayer(name, null)
        val after = change(before)
        if (after != before) { players[key] = after; if (after.role != before.role || after.sm != before.sm || after.alive != before.alive) changed() }
    }

    /** Drops everyone not in [names] (this run's party), so players from an earlier run or who left stop getting plans. */
    fun retainPlayers(names: Collection<String>) {
        if (players.keys.removeIf { key -> names.none { it.equals(key, ignoreCase = true) } }) changed()
    }

    /** Fresh position for [name], or null if unseen or older than [POSITION_TTL]. */
    fun position(name: String, now: Long): BossPoint? = player(name)?.takeIf { (it.seenAt ?: return@takeIf false) >= now - POSITION_TTL }?.position

    fun route(route: EarlyEnter) = routes[route] ?: RouteObservation()

    fun setRoute(route: EarlyEnter, state: RouteState, source: StateSource, now: Long) {
        val before = route(route)
        // Abandoned is final for the run; in only goes back on an explicit abandon.
        if (before.state == RouteState.ABANDONED || before.state == state) return
        if (before.state == RouteState.IN && state == RouteState.GOING) return
        routes[route] = RouteObservation(state, source, now); changed()
    }

    fun markActive(objective: ObjectiveId, by: String, puzzle: PuzzleType?, quarters: Int, source: StateSource, now: Long) {
        val before = state(objective)
        if (before.status == ObjectiveStatus.COMPLETE) return
        val after = before.copy(status = ObjectiveStatus.ACTIVE, by = by, puzzle = puzzle ?: before.puzzle,
            quarters = maxOf(before.quarters, quarters.coerceIn(0, 3)), startedAt = before.startedAt ?: now, source = source)
        if (after != before) { objectives[objective] = after; changed() }
    }

    fun markComplete(objective: ObjectiveId, by: String?, source: StateSource, now: Long, ambiguous: Boolean = false) {
        val before = state(objective)
        if (before.status == ObjectiveStatus.COMPLETE && !before.ambiguous) return
        if (before.status == ObjectiveStatus.COMPLETE && ambiguous) return
        objectives[objective] = before.copy(status = ObjectiveStatus.COMPLETE, by = by ?: before.by, completedAt = before.completedAt ?: now,
            source = source, ambiguous = ambiguous)
        changed()
    }

    /**
     * An exact completion from a player's SM client. If an assignment-order guess already marked a different objective
     * for the same player, that guess is reopened so the count stays right.
     */
    fun exactComplete(objective: ObjectiveId, by: String, now: Long) {
        val guessed = objectives.entries.firstOrNull { (id, state) ->
            id != objective && id.section == objective.section && id.kind == objective.kind && state.ambiguous && state.by.equals(by, ignoreCase = true)
        }
        if (guessed != null && state(objective).status != ObjectiveStatus.COMPLETE) objectives[guessed.key] = ObjectiveState()
        markComplete(objective, by, StateSource.SHARED, now)
    }

    /**
     * Hypixel's `<player> activated a terminal! (n/N)` (or device, lever). Chooses the objective: the one the sender's
     * SM client already named, then a station within [GoldorLayout.MATCH_RADIUS] of their position, then the next open
     * objective of that kind assigned to their role, then the first open one of that kind (ambiguous).
     * Returns the objective chosen, or null when the line does not fit the current section.
     */
    fun chatCompletion(playerName: String, kind: ObjectiveKind, done: Int, total: Int, now: Long): ObjectiveId? {
        if (!active) return null
        val section = sectionForCounter(total) ?: return null
        val role = player(playerName)?.role
        counters[section] = maxOf(counters[section] ?: 0, done)
        val chosen = when (kind) {
            ObjectiveKind.DEVICE -> deviceFor(role, section)
            else -> {
                // Their SM client said which one it was working on.
                val active = objectives.entries.firstOrNull { (id, s) -> id.section == section && id.kind == kind && s.status == ObjectiveStatus.ACTIVE && s.by.equals(playerName, true) }?.key
                active ?: position(playerName, now)?.let { GoldorLayout.nearest(section, kind, it.x, it.y, it.z, completed(section))?.objective }
            }
        }
        val exact = chosen != null
        val pick = chosen ?: role?.let { profile.plan(section, it).objectives.firstOrNull { id -> id.kind == kind && state(id).status != ObjectiveStatus.COMPLETE } }
            ?: GoldorLayout.stations(section).map { it.objective }.firstOrNull { it.kind == kind && state(it).status != ObjectiveStatus.COMPLETE }
        if (pick != null) markComplete(pick, playerName, StateSource.CHAT, now, ambiguous = !exact && kind != ObjectiveKind.DEVICE)
        if (done >= total && section == this.section) completeSection(now)
        return pick
    }

    /**
     * The device a completion belongs to. Pre-dev (Berserker's i4 during S1) and an early-enter device (MEE2's S2
     * device done from the EE2 spot during S1) count for their own sections; otherwise it is the section's device.
     */
    private fun deviceFor(role: GoldorRole?, section: GoldorSection): ObjectiveId {
        val own = role?.let { profile.plan(section, it).objectives.firstOrNull { id -> id.kind == ObjectiveKind.DEVICE && id.section != section } }
        if (own != null && state(own).status != ObjectiveStatus.COMPLETE) return own
        val next = section.next
        val early = profile.routes.entries.firstOrNull { it.value == role && it.key.section == next }?.let { ObjectiveId.device(next!!) }
        val current = ObjectiveId.device(section)
        if (early != null && profile.assignee(early) == role && state(current).status == ObjectiveStatus.COMPLETE) return early
        return current
    }

    private fun completed(section: GoldorSection) = objectives.filter { it.key.section == section && it.value.status == ObjectiveStatus.COMPLETE && !it.value.ambiguous }.keys

    /** Which section a `(n/N)` line belongs to: S2 has 8 objectives, the others 7, so 7 means the current or next one. */
    private fun sectionForCounter(total: Int): GoldorSection? {
        val candidates = GoldorLayout.sectionsWithTotal(total)
        // Once a section is full, a new counter belongs to the next one: its gate opened without us seeing it.
        val next = section.next
        if (sectionDoneAt != null && next != null && next in candidates) { advance(next); return next }
        return section.takeIf { it in candidates && sectionDoneAt == null }
    }

    private fun completeSection(now: Long) {
        if (sectionDoneAt != null) return
        sectionDoneAt = now
        GoldorLayout.stations(section).forEach { if (state(it.objective).status != ObjectiveStatus.COMPLETE) markComplete(it.objective, null, StateSource.INFERRED, now, ambiguous = true) }
        changed()
        // The gate line can land just before the last completion line; it still opens this gate.
        val early = pendingGateAt?.let { now - it <= GATE_EARLY_MILLIS } == true
        pendingGateAt = null
        if (early) gateOpened(now)
    }

    /** A gate line seen while the section was still short of n/n. */
    private var pendingGateAt: Long? = null

    private fun advance(next: GoldorSection) { section = next; sectionDoneAt = null; gateOpenedAt = null; pendingGateAt = null; changed() }

    /**
     * The gate is blown (`The gate has been destroyed!` or Goldor's line for that gate): once the section is n/n the
     * next section begins at once. A gate line before n/n is held briefly for the last completion.
     */
    fun gateOpened(now: Long) {
        if (!active) return
        // S4 has no gate; the Core line ends it.
        if (section == GoldorSection.S4 || section == GoldorSection.CORE) return
        if (sectionDoneAt == null) { pendingGateAt = now; return }
        advance(section.next!!); gateOpenedAt = now
    }

    /**
     * A finished section whose gate line was never seen (hidden by another mod, or missed) still moves on once the gate
     * must have opened by itself: the profile's gate delay after n/n, plus a second of slack.
     */
    fun gateTimeout(now: Long) {
        val done = sectionDoneAt ?: return
        if (!active || section == GoldorSection.S4 || section == GoldorSection.CORE) return
        if (now - done >= profile.objectives.gateDelayMillis + 1_000) { advance(section.next!!); gateOpenedAt = now }
    }

    /** `The Core entrance is opening!`: S4 is done and the Core run begins. */
    fun coreOpened(now: Long) {
        if (!active) return
        if (section == GoldorSection.S4) completeSection(now)
        section = GoldorSection.CORE; sectionDoneAt = null; gateOpenedAt = now; changed()
    }

    /** Debug or recovery: jump to a section without touching objectives. */
    fun setSection(target: GoldorSection) { if (target != section) { section = target; sectionDoneAt = null; changed() } }
}

/** An estimated duration. [confidence] LOW means only the order of magnitude is meaningful. */
data class Estimate(val millis: Long, val confidence: Confidence) {
    operator fun plus(other: Estimate) = Estimate(millis + other.millis, weaker(confidence, other.confidence))
    companion object { val ZERO = Estimate(0, Confidence.HIGH) }
}

/** The weaker of two confidences. */
fun weaker(a: Confidence, b: Confidence) = if (a.ordinal >= b.ordinal) a else b

/**
 * What is known about one terminal while someone works on it: its puzzle when the worker's SM client shared it, the
 * worker's progress in quarters, and when work began on it.
 */
data class TerminalWork(val puzzle: PuzzleType?, val quarters: Int = 0, val startedAt: Long? = null, val complete: Boolean = false)

/**
 * Remaining work on one terminal. A known puzzle uses its profile time; progress removes its share. Time already
 * spent counts too, but never brings a started terminal below a quarter of its time until it is complete, since a
 * slow attempt is still unfinished. An unknown puzzle uses the profile's unknown time at low confidence.
 */
fun remainingWork(work: TerminalWork, timing: PuzzleTiming, now: Long): Estimate {
    if (work.complete) return Estimate.ZERO
    val full = timing.millis(work.puzzle)
    val byProgress = full * (4 - work.quarters.coerceIn(0, 3)) / 4
    val spent = work.startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L
    val left = max(byProgress - spent, full / 4).coerceAtMost(byProgress)
    return Estimate(left, if (work.puzzle == null) Confidence.LOW else if (work.startedAt == null) Confidence.MEDIUM else Confidence.HIGH)
}

/**
 * Goldor's damage tick. [originMillis] is a tick that was observed (or derived from an observed phase line) and
 * [periodMillis] the configured interval; both come from the profile. Null origin means the tick is not known.
 */
data class GoldorTick(val originMillis: Long?, val periodMillis: Long, val observed: Boolean) {
    /** The first tick strictly after [time], or null when the tick is unknown. */
    fun nextAfter(time: Long): Long? {
        val origin = originMillis ?: return null
        if (time < origin) return origin
        return origin + ((time - origin) / periodMillis + 1) * periodMillis
    }
    /** The last tick at or before [time]. */
    fun lastAtOrBefore(time: Long): Long? {
        val origin = originMillis ?: return null
        if (time < origin) return null
        return origin + (time - origin) / periodMillis * periodMillis
    }
}

enum class LeapPhase { WAITING_TICK, PREPARE, LEAP, TRAVELLING, ARRIVED, MISSED, UNCERTAIN }

/**
 * One pre-leap sequence: the intended Goldor tick kill, the leap after it, the travel to the next terminal and arrival
 * before the next section opens. Times are absolute milliseconds on the same clock as [GoldorTick].
 */
data class LeapPlan(
    val phase: LeapPhase,
    val reason: String,
    val confidence: Confidence,
    /** The tick the leap waits for. */
    val tickAt: Long? = null,
    /** Leap no earlier than this… */
    val leapFrom: Long? = null,
    /** …and no later than this, or the travel no longer fits before [opensAt]. */
    val leapUntil: Long? = null,
    /** When the next section is expected to open. */
    val opensAt: Long? = null,
)

/**
 * Plans the pre-leap. The rule is the strategy's: wait for the Goldor tick kill, leap just after it, then travel and
 * reach the next terminal before the next section opens. The intended tick is the last one that still leaves time to
 * leap and travel before the predicted opening. Everything is recomputed whenever progress or the tick changes, so an
 * earlier or later tick, or faster work, moves the window.
 *
 * - No estimate of the remaining work, or no known tick: [LeapPhase.UNCERTAIN], with no times shown as exact.
 * - Low-confidence work estimate: the plan is computed but marked LOW so the HUD shows it as approximate.
 * - Not enough time left to leap and travel before the opening: [LeapPhase.MISSED].
 * - The fitting tick has passed: leap now only if no further tick lands before the opening, else [LeapPhase.MISSED].
 *
 * [reactionMillis] is how long the player takes to see the call and leap: every call ([LeapPlan.leapFrom], the prepare
 * warning and the latest moment) comes that much earlier, so the leap itself lands on time.
 */
fun planPreLeap(
    now: Long,
    remaining: Estimate?,
    tick: GoldorTick,
    timing: LeapTiming,
    travelMillis: Long?,
    leapt: Boolean = false,
    arrived: Boolean = false,
    reactionMillis: Long = 0,
): LeapPlan {
    if (arrived) return LeapPlan(LeapPhase.ARRIVED, "at target", Confidence.HIGH)
    if (leapt) return LeapPlan(LeapPhase.TRAVELLING, "go to target", Confidence.HIGH)
    if (remaining == null) return LeapPlan(LeapPhase.UNCERTAIN, "work unknown", Confidence.LOW)
    val travel = travelMillis ?: return LeapPlan(LeapPhase.UNCERTAIN, "target position unknown", Confidence.LOW)
    val opensAt = now + remaining.millis
    // The latest moment the leap still gets the player to the terminal with the safety margin to spare.
    val latestLeap = opensAt - timing.leapCastMillis - travel - timing.safetyMarginMillis - reactionMillis
    val confidence = weaker(remaining.confidence, if (tick.observed) Confidence.HIGH else Confidence.MEDIUM)
    if (latestLeap < now) return LeapPlan(LeapPhase.MISSED, "not enough time", weaker(confidence, Confidence.MEDIUM), opensAt = opensAt)
    if (tick.originMillis == null) return LeapPlan(LeapPhase.UNCERTAIN, "Goldor tick unknown", Confidence.LOW, leapUntil = latestLeap, opensAt = opensAt)
    // The intended tick kill: the last tick whose leap (tick + delay) still fits.
    val intended = tick.lastAtOrBefore(latestLeap + reactionMillis - timing.leapDelayAfterTickMillis)
    val leapFrom = intended?.plus(timing.leapDelayAfterTickMillis - reactionMillis)
    if (intended == null || leapFrom == null || leapFrom < now - timing.leapWindowMillis) {
        // No tick-timed leap remains. Leaping now is only safe if no tick lands before the section opens.
        val next = tick.nextAfter(now)
        if (next != null && next < opensAt) return LeapPlan(LeapPhase.MISSED, "no safe tick before opening", weaker(confidence, Confidence.MEDIUM), next, opensAt = opensAt)
        return LeapPlan(LeapPhase.LEAP, "leap now — no tick before opening", weaker(confidence, Confidence.MEDIUM), intended, now, latestLeap, opensAt)
    }
    val until = minOf(latestLeap, leapFrom + timing.leapWindowMillis)
    val phase = when {
        now >= leapFrom -> LeapPhase.LEAP
        now >= intended - timing.prepareLeadMillis - reactionMillis -> LeapPhase.PREPARE
        else -> LeapPhase.WAITING_TICK
    }
    val reason = when (phase) {
        LeapPhase.LEAP -> "leap after tick"
        LeapPhase.PREPARE -> "tick in ${seconds(intended - now)}"
        else -> "waiting for Goldor tick"
    }
    return LeapPlan(phase, reason, confidence, intended, leapFrom, until, opensAt)
}

/** Seconds to one decimal, for reasons and HUD text. */
fun seconds(millis: Long) = String.format(java.util.Locale.ROOT, "%.1fs", millis.coerceAtLeast(0) / 1000.0)

/** Walking time between two points at the profile's movement speed, plus a fixed overhead for turning and clicking. */
fun travelMillis(distanceBlocks: Double, timing: LeapTiming): Long =
    timing.travelOverheadMillis + (distanceBlocks / timing.blocksPerSecond * 1000).toLong()
