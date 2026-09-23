package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.StringDecomposer
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

val FRIEND_FLOORS = DungeonFloor.entries.filter { it != DungeonFloor.E }

/** Count completed work; discovering more friends never moves the displayed percentage backwards. */
class DungeonRefreshProgress {
    var percent = 0
        private set
    private var running = false
    private var completedAt = 0L
    fun update(completed: Long, pending: Int, pagesDone: Int, pagesLeft: Int): Int {
        if (pending == 0 && pagesLeft == 0) {
            if (running) percent = 100
            running = false
            return percent
        }
        if (!running) { running = true; percent = 0; completedAt = completed }
        val done = (completed - completedAt).coerceAtLeast(0) + pagesDone
        percent = maxOf(percent, (100 * done / (done + pending + pagesLeft)).toInt()).coerceAtMost(99)
        return percent
    }
}

private val minimumLevels = listOf(1, 3, 5, 9, 14, 19, 24, 24, 26, 28, 30, 32, 34, 36)
private val dungeonXp = listOf(
    50, 75, 110, 160, 230, 330, 470, 670, 950, 1340,
    1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000, 27600, 38000,
    52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000,
    1065000, 1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000,
    15000000, 19000000, 24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000,
)

fun dungeonLevel(experience: Double): Int {
    var remaining = experience.takeIf { it.isFinite() && it >= 0 } ?: return 0
    for ((level, required) in dungeonXp.withIndex()) {
        if (remaining < required) return level
        remaining -= required
    }
    return 50 + (remaining / 200000000.0).coerceAtMost((Int.MAX_VALUE - 50).toDouble()).toInt()
}

fun parseDungeonClass(name: String): DungeonClass? = DungeonClass.entries.firstOrNull {
    it.name.equals(name, true) || (it == DungeonClass.BERSERKER && name.equals("Berserk", true))
}

enum class StatsState(val label: String) {
    AVAILABLE("Available"), HIDDEN("API hidden"), UNAVAILABLE("API unavailable"), NO_PROFILE("No SkyBlock profile"),
}

data class DungeonFriendStats(
    val state: StatsState,
    val catacombs: Int? = null,
    val classes: Map<DungeonClass, Int> = emptyMap(),
    val completionTimes: Map<DungeonFloor, Long> = emptyMap(),
    val sPlusTimes: Map<DungeonFloor, Long> = emptyMap(),
    val completedFloors: Set<DungeonFloor> = emptySet(),
    val selectedClass: DungeonClass? = null,
) {
    val highestFloor: DungeonFloor? get() = FRIEND_FLOORS.lastOrNull { it in completedFloors }

    fun eligible(floor: DungeonFloor): Boolean = when (state) {
        StatsState.HIDDEN, StatsState.UNAVAILABLE -> true
        StatsState.NO_PROFILE -> false
        StatsState.AVAILABLE -> floor in completionTimes &&
            (catacombs ?: 0) >= minimumLevels[FRIEND_FLOORS.indexOf(floor)]
    }

    companion object {
        fun fromProfiles(response: JsonObject, uuid: String): DungeonFriendStats {
            require(response.get("success")?.asBoolean == true) { "Unsuccessful profile response" }
            val profiles = response.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
            if (profiles.isEmpty()) return DungeonFriendStats(StatsState.NO_PROFILE)
            val profile = profiles.firstOrNull { it.get("selected")?.asBoolean == true }
                ?: profiles.maxByOrNull { it.obj("members")?.obj(uuid)?.number("last_save") ?: 0.0 }!!
            val member = profile.obj("members")?.obj(uuid)
                ?: profile.obj("members")?.obj(uuid.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                ?: return DungeonFriendStats(StatsState.UNAVAILABLE)
            val dungeons = member.obj("dungeons")
                ?: return DungeonFriendStats(StatsState.HIDDEN)
            if (listOf("skills", "dungeons").any { setting ->
                member.obj("api_settings")?.get(setting)?.let { !it.isJsonNull && !it.asBoolean } == true
            }) return DungeonFriendStats(StatsState.HIDDEN)

            return fromDungeons(dungeons)
        }

        fun fromDungeons(dungeons: JsonObject): DungeonFriendStats {
            val types = dungeons.obj("dungeon_types")
            val cata = types?.obj("catacombs")
            if (cata != null && cata.number("experience") == null) return DungeonFriendStats(StatsState.HIDDEN)
            val completionTimes = mutableMapOf<DungeonFloor, Long>()
            val sPlusTimes = mutableMapOf<DungeonFloor, Long>()
            val completed = mutableSetOf<DungeonFloor>()
            for (floor in FRIEND_FLOORS) {
                val data = types?.obj(if (floor.name.startsWith("M")) "master_catacombs" else "catacombs")
                val key = floor.floorNumber.toString()
                val times = listOf("fastest_time", "fastest_time_s", "fastest_time_s_plus")
                    .mapNotNull { data?.obj(it)?.number(key)?.toLong()?.takeIf { time -> time > 0 } }
                times.minOrNull()?.let { completionTimes[floor] = it }
                data?.obj("fastest_time_s_plus")?.number(key)?.toLong()?.takeIf { it > 0 }
                    ?.let { sPlusTimes[floor] = it }
                if (times.isNotEmpty() || (data?.obj("tier_completions")?.number(key) ?: 0.0) > 0) completed += floor
            }
            val classes = dungeons.obj("player_classes")
            return DungeonFriendStats(
                state = StatsState.AVAILABLE,
                catacombs = dungeonLevel(cata?.number("experience") ?: 0.0),
                classes = DungeonClass.entries.mapNotNull { clazz ->
                    val xp = (if (clazz == DungeonClass.BERSERKER) classes?.obj("berserk") ?: classes?.obj("berserker")
                        else classes?.obj(clazz.name.lowercase()))?.number("experience")
                    xp?.let { clazz to dungeonLevel(it) }
                }.toMap(),
                completionTimes = completionTimes,
                sPlusTimes = sPlusTimes,
                completedFloors = completed,
                selectedClass = dungeons.get("selected_dungeon_class")?.takeIf { it.isJsonPrimitive }
                    ?.asString?.let(::parseDungeonClass),
            )
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }
    ?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() && it >= 0 }

data class OnlineDungeonFriend(val name: String, val location: String, val rankColor: Int? = null, val bestFriend: Boolean = false) {
    val status: FriendStatus get() = when {
        location.contains("limbo", true) -> FriendStatus.IN_RUN
        location.contains("Dungeons", true) || location.contains("Catacombs", true) -> FriendStatus.IN_RUN
        location.contains("Dungeon Hub", true) -> FriendStatus.IDLE
        location.contains("Hub", true) || location.contains("Island", true) -> FriendStatus.IDLE
        else -> FriendStatus.UNKNOWN
    }
    val badge: String get() = status.label
    val statusColor: Int get() = status.color
}

enum class FriendStatus(val label: String, val color: Int) {
    IDLE("§aIdle", 0x55FF55),
    IN_RUN("§cIn Run", 0xFF5555),
    UNKNOWN("§7Unknown", 0xAAAAAA),
}

/** Read the name's rendered style, including inherited component styles and legacy formatting codes. */
fun dungeonPlayerNameStyle(component: Component, name: String): Style? {
    val match = Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
        .find(StringDecomposer.getPlainText(component)) ?: return null
    var offset = 0
    var found: Style? = null
    StringDecomposer.iterateFormatted(component, Style.EMPTY) { _, style, codePoint ->
        if (offset == match.range.first) {
            found = Style.EMPTY.withColor(style.color).withBold(style.isBold)
            false
        } else {
            offset += Character.charCount(codePoint)
            true
        }
    }
    return found
}

fun dungeonPlayerTitle(name: String, suffix: String, rankColor: Int?): Component = Component.empty()
    .append(Component.literal(name).withStyle { it.withColor(rankColor ?: 0xFFFFFF) })
    .append(Component.literal(" $suffix").withStyle { it.withColor(0xFFFFFF) })

fun dungeonJoinedDetails(stats: DungeonFriendStats?, clazz: DungeonClass?): String =
    "§bCata ${stats?.catacombs ?: "?"} §8| " +
        (clazz?.let { "${coloredDungeonClass(it)} §f${stats?.classes?.get(it) ?: "?"}" } ?: "§7Class ?")

fun bestFriendChange(message: String): Pair<String, Boolean>? =
    Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) is (now|no longer) a best friend!$")
        .matchEntire(message.replace(Regex("§."), "").trim())?.let { it.groupValues[1] to (it.groupValues[2] == "now") }

enum class FriendSort(val label: String, val dungeonClass: DungeonClass? = null) {
    CATACOMBS("Cata"), HEALER("Healer", DungeonClass.HEALER), MAGE("Mage", DungeonClass.MAGE),
    BERSERKER("Berserk", DungeonClass.BERSERKER), ARCHER("Archer", DungeonClass.ARCHER),
    TANK("Tank", DungeonClass.TANK), PB("S+ PB"),
}

fun friendComparator(stats: Map<String, DungeonFriendStats>, floor: DungeonFloor, sort: FriendSort, ascending: Boolean = sort == FriendSort.PB): Comparator<OnlineDungeonFriend> {
    fun value(friend: OnlineDungeonFriend): Long? = stats[friend.name.lowercase()]?.let {
        when (sort) {
            FriendSort.CATACOMBS -> it.catacombs?.toLong()
            FriendSort.PB -> it.sPlusTimes[floor]
            else -> it.classes[sort.dungeonClass]?.toLong()
        }
    }
    return Comparator<OnlineDungeonFriend> { a, b ->
        val first = value(a)
        val second = value(b)
        when {
            first == null && second == null -> 0
            first == null -> 1
            second == null -> -1
            ascending -> first.compareTo(second)
            else -> second.compareTo(first)
        }
    }.thenBy { it.name.lowercase() }
}

fun matchesFriendClass(stats: DungeonFriendStats?, secondary: Set<DungeonClass>, wanted: Set<DungeonClass>): Boolean =
    wanted.isEmpty() || stats?.selectedClass in wanted || secondary.any { it in wanted } ||
        ((stats?.selectedClass == null || stats.state == StatsState.HIDDEN || stats.state == StatsState.UNAVAILABLE) && secondary.isEmpty())

fun nextMissingClass(current: DungeonClass?, occupied: Collection<DungeonClass>): DungeonClass? =
    current?.takeIf { it !in occupied } ?: DungeonClass.entries.firstOrNull { it !in occupied }

fun lfgMessage(template: String, name: String, clazz: DungeonClass?, floor: DungeonFloor): String = template
    .replace("{name}", name).replace("{class}", clazz?.displayName ?: "any class").replace("{floor}", floor.name)
    .filter { it >= ' ' && it != '\u007f' && it != '§' }.trim().take((256 - "msg $name ".length).coerceAtLeast(0))

fun formatDungeonTime(millis: Long?): String = millis?.let {
    "%d:%02d.%03d".format(it / 60000, it / 1000 % 60, it % 1000)
} ?: "Unknown"

fun newlyAddedFriend(message: String): String? = Regex(
    "^You are now friends with (?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})[!.]?$", RegexOption.IGNORE_CASE,
).matchEntire(message.replace(Regex("§."), "").trim())?.groupValues?.get(1)

enum class LfgReplyStatus(val label: String) {
    WAITING("§6Awaiting reply"), INVITED("§6Invited"), ACCEPTED("§aAccepted"), DECLINED("§cDeclined"), REPLIED("§eReplied"),
}

fun dungeonClassChange(message: String, self: String): Pair<String, DungeonClass>? {
    val text = message.replace(Regex("§."), "").trim().replace(Regex("\\s+\\(x\\d+\\)$"), "")
    val own = Regex("^You have selected the (\\w+) Dungeon Class!$").matchEntire(text)
    if (own != null) return parseDungeonClass(own.groupValues[1])?.let { self to it }
    val other = Regex("^Party Finder > (?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) set their class to (\\w+) Level \\d+!$")
        .matchEntire(text) ?: return null
    return parseDungeonClass(other.groupValues[2])?.let { other.groupValues[1] to it }
}

fun coloredDungeonClass(clazz: DungeonClass): String = when (clazz) {
    DungeonClass.MAGE -> "§b"
    DungeonClass.TANK -> "§a"
    DungeonClass.BERSERKER -> "§6"
    DungeonClass.ARCHER -> "§c"
    DungeonClass.HEALER -> "§d"
} + clazz.displayName

fun dungeonTitleDetails(floor: DungeonFloor?, classes: Collection<DungeonClass>): String =
    (floor?.let { "to play ${if (it.name.startsWith('M')) "§c" else "§a"}${it.name}§f" } ?: "to their party") +
        if (classes.isEmpty()) "" else " as ${classes.joinToString("§f/") { coloredDungeonClass(it) }}"

fun classifyLfgReply(message: String): LfgReplyStatus {
    val text = message.lowercase().replace('’', '\'').replace(',', ' ').trim().replace(Regex("\\s+"), " ")
    // ponytail: recognize explicit short English replies; leave ambiguous prose for the player to read.
    val positive = "(?:y|yes|ye|yea|yh|yep|yup|yeah|sure|ok|okay|alright|sounds good|of course|i'm (?:in|down)|im (?:in|down)|i am (?:in|down)|let'?s go|inv(?:ite)?(?: me)?|send (?:inv|invite))"
    val suffix = "(?: (?:pls|please|inv(?:ite)?(?: me)?|[0-9]+s|[0-9]+ sec(?:onds)?|give me [0-9]+s))?"
    return when {
        Regex("^$positive$suffix[!.]*$").matches(text) -> LfgReplyStatus.ACCEPTED
        Regex("^(?:no|nope|nah|can't|cannot|not now|no thanks)(?:[ ,!.].*)?$").matches(text) -> LfgReplyStatus.DECLINED
        else -> LfgReplyStatus.REPLIED
    }
}

data class LfgReply(val status: LfgReplyStatus, val text: String, val expires: Long)

class DungeonLfgReplies {
    private val replies = mutableMapOf<String, LfgReply>()
    var version = 0L
        private set
    fun get(name: String): LfgReply? = replies[name.lowercase()]
    fun sent(name: String, now: Long) {
        replies[name.lowercase()] = LfgReply(LfgReplyStatus.WAITING, "", now + 300000)
        version++
    }
    fun invited(name: String, now: Long) {
        replies[name.lowercase()] = LfgReply(LfgReplyStatus.INVITED, get(name)?.text.orEmpty(), now + 60000)
        version++
    }
    fun receive(name: String, text: String, now: Long) {
        prune(now)
        val previous = get(name) ?: return
        val result = classifyLfgReply(text)
        val status = if (result == LfgReplyStatus.REPLIED && previous.status == LfgReplyStatus.ACCEPTED) previous.status else result
        replies[name.lowercase()] = previous.copy(status = status, text = text.take(256))
        version++
    }
    fun prune(now: Long) { if (replies.entries.removeIf { it.value.expires <= now }) version++ }
    fun forget(name: String) { if (replies.remove(name.lowercase()) != null) version++ }
    fun clear() { replies.clear(); version++ }
}

/** PartyAPI supplies rosters; server announcements fill in class choices and immediate membership changes. */
class DungeonFriendParty {
    val members = mutableSetOf<String>()
    val classes = mutableMapOf<String, DungeonClass>()
    var size = 1
        private set
    var ready = false
        private set
    var neededClass: DungeonClass? = null
        private set
    val full: Boolean get() = size >= 5
    val canInvite: Boolean get() = ready && !full
    val openClasses: Set<DungeonClass> get() = DungeonClass.entries.filter { it !in classes.values }.toSet()

    fun roster(names: Collection<String>, count: Int, confirmed: Boolean = ready, completeNames: Boolean = true) {
        val normalized = names.map { it.lowercase() }.toSet() + if (completeNames) emptySet() else members
        members.clear()
        classes.keys.retainAll(normalized)
        members.addAll(normalized)
        size = maxOf(count, normalized.size, 1)
        ready = confirmed
        advance()
    }

    fun advance() { neededClass = nextMissingClass(neededClass, classes.values) }

    fun chat(message: String, self: String): Boolean {
        val leftParty = LEAVE.matches(message)
        if (leftParty) {
            members.clear()
            classes.clear()
            roster(listOf(self), 1, true)
        }
        OWN_JOIN.matchEntire(message)?.let {
            members.clear()
            classes.clear()
            // Joining only names the leader; wait for the full roster before enabling invites.
            roster(listOf(self, it.groupValues[1]), 2, false)
        }
        val joined = JOIN.matchEntire(message) ?: CLASS_JOIN.matchEntire(message)
        if (joined != null) {
            if (members.add(joined.groupValues[1].lowercase())) size++
            if (joined.groupValues.size > 2) parseDungeonClass(joined.groupValues[2])?.let {
                classes[joined.groupValues[1].lowercase()] = it
            }
        }
        LEFT.matchEntire(message)?.let {
            val name = it.groupValues[1].lowercase()
            if (members.remove(name)) size = (size - 1).coerceAtLeast(1)
            classes.remove(name)
        }
        advance()
        return leftParty
    }

    companion object {
        private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
        private val JOIN = Regex("^(?:\\[Party] )?$PLAYER joined the party\\.$")
        private val OWN_JOIN = Regex("^You have joined $PLAYER's? party!$")
        private val LEFT = Regex("^(?:\\[Party] )?$PLAYER (?:has left|has been removed from) the party\\.$")
        private val CLASS_JOIN = Regex("^Party Finder > $PLAYER joined the dungeon group! \\((\\w+) Level \\d+\\)$")
        fun joinedPlayer(message: String): String? = (JOIN.matchEntire(message) ?: CLASS_JOIN.matchEntire(message))?.groupValues?.get(1)
        private val LEAVE = Regex("^(?:You left the party\\.|You have been kicked from the party by .+|You are not (?:currently )?in a party\\.|The party was disbanded.*|(?:\\[[^]]+] )?[A-Za-z0-9_]{1,16} has disbanded the party!)$")
    }
}
