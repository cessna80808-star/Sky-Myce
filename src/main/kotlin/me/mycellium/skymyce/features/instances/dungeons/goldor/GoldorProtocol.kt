package me.mycellium.skymyce.features.instances.dungeons.goldor

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
