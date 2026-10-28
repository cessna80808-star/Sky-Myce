package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.utils.MC
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.StringDecomposer
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import tech.thatgravyboat.skyblockapi.utils.text.TextUtils.splitLines

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

/** Total Catacombs experience needed to reach [level]; each level past 50 takes another 200M, as Hypixel counts them. */
fun dungeonXpForLevel(level: Int): Double =
    dungeonXp.take(level.coerceAtLeast(0)).sumOf { it.toDouble() } + (level - 50).coerceAtLeast(0) * 200_000_000.0

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
    /** Exact Catacombs experience from a profile response; shared relay reports carry only the level. */
    val catacombsXp: Double? = null,
    /** Exact class experience from a profile response; null in shared reports and records saved before 2.7.25. */
    val classXp: Map<DungeonClass, Double>? = null,
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
            val classXp = DungeonClass.entries.mapNotNull { clazz ->
                val xp = (if (clazz == DungeonClass.BERSERKER) classes?.obj("berserk") ?: classes?.obj("berserker")
                    else classes?.obj(clazz.name.lowercase()))?.number("experience")
                xp?.let { clazz to it }
            }.toMap()
            return DungeonFriendStats(
                state = StatsState.AVAILABLE,
                catacombs = dungeonLevel(cata?.number("experience") ?: 0.0),
                catacombsXp = cata?.number("experience"),
                classes = classXp.mapValues { dungeonLevel(it.value) },
                classXp = classXp,
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
    @Transient val activity: FriendActivity = friendActivity(location)
    val badge: String get() = "${activity.format}${activity.label}"
}

enum class FriendActivity(val label: String, val color: Int, val format: String) {
    OFFLINE("Offline", 0x91A2AF, "§7"),
    IN_RUN("In Run", 0xF18C8C, "§c"), LIMBO("Limbo", 0xF18C8C, "§c"),
    IDLE("Idle", 0x7DDCA0, "§a"), SKYBLOCK("SkyBlock", 0xE8BF71, "§e"),
    OTHER_GAME("Other game", 0x67CCF2, "§b"), UNKNOWN("Unknown", 0x91A2AF, "§7"),
}

private val DUNGEON_LOCATION = Regex("(?i)\\b(?:dungeons?|catacombs)\\b")
private val IDLE_LOCATION = Regex("(?i)\\b(?:hub|private island)\\b")
private val SKYBLOCK_LOCATION = Regex("(?i)\\b(?:mines|hollows|garden|crimson|end|park|rift|spider)\\b")
internal val LEGACY_FORMAT = Regex("§.")
private val BEST_FRIEND_CHANGE = Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) is (now|no longer) a best friend!$")
private val NEW_FRIEND = Regex("^You are now friends with (?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})[!.]?$", RegexOption.IGNORE_CASE)
internal val CHAT_REPEAT = Regex("\\s+\\(x\\d+\\)$")
private val OWN_CLASS_CHANGE = Regex("^You have selected the (\\w+) Dungeon Class!$")
private val OTHER_CLASS_CHANGE = Regex("^Party Finder > (?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) set their class to (\\w+) Level \\d+!$")

fun friendActivity(location: String): FriendActivity = when {
    location.contains("offline", true) -> FriendActivity.OFFLINE
    location.contains("limbo", true) -> FriendActivity.LIMBO
    location.contains("Dungeon Hub", true) -> FriendActivity.IDLE
    DUNGEON_LOCATION.containsMatchIn(location) -> FriendActivity.IN_RUN
    IDLE_LOCATION.containsMatchIn(location) || location.equals("in Island", true) -> FriendActivity.IDLE
    location.contains("SkyBlock", true) || SKYBLOCK_LOCATION.containsMatchIn(location) -> FriendActivity.SKYBLOCK
    location.isBlank() || location.equals("Unknown", true) || location.contains("offline", true) -> FriendActivity.UNKNOWN
    else -> FriendActivity.OTHER_GAME
}

fun matchesFriendName(name: String, query: String): Boolean = name.contains(query.trim(), ignoreCase = true)

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

fun dungeonJoinedDetails(stats: DungeonFriendStats?, clazz: DungeonClass?, classLevel: Int? = null): String =
    "§fCata ${coloredDungeonLevel(stats?.catacombs)} §8| " +
        (clazz?.let { "${coloredDungeonClass(it)} ${coloredDungeonLevel(classLevel ?: stats?.classes?.get(it))}" } ?: "§7Class ?")

/** Keep the announcement until its async lookup completes, including the second Party Finder chat line. */
internal class DungeonJoinedTitle(val name: String, val source: Component, private val receivedAt: Long) {
    var shownUntil = 0L
        private set

    fun subtitle(now: Long, stats: DungeonFriendStats?, clazz: DungeonClass?, classLevel: Int?, loading: Boolean): String? {
        if (shownUntil != 0L || now < receivedAt + 150) return null
        val complete = stats?.catacombs != null && clazz != null && (classLevel ?: stats.classes[clazz]) != null
        if (!complete && loading && now < receivedAt + 60000) return null
        shownUntil = now + 5000
        return dungeonJoinedDetails(stats, clazz, classLevel)
    }
}

private fun coloredDungeonLevel(level: Int?): String {
    // Match the SkyHanni dungeon level bands shown in Party Finder, including bold red at 50+.
    val color = when {
        level == null || level < 5 -> "§7"
        level < 10 -> "§f"
        level < 15 -> "§e"
        level < 20 -> "§a"
        level < 25 -> "§2"
        level < 30 -> "§b"
        level < 35 -> "§9"
        level < 40 -> "§d"
        level < 45 -> "§6"
        level < 50 -> "§c"
        else -> "§c§l"
    }
    return "$color${level ?: "?"}"
}

fun bestFriendChange(message: String): Pair<String, Boolean>? =
    BEST_FRIEND_CHANGE.matchEntire(message.replace(LEGACY_FORMAT, "").trim())?.let { it.groupValues[1] to (it.groupValues[2] == "now") }

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

fun newlyAddedFriend(message: String): String? = NEW_FRIEND.matchEntire(message.replace(LEGACY_FORMAT, "").trim())?.groupValues?.get(1)

enum class LfgReplyStatus(val label: String) {
    WAITING("§6Awaiting reply"), INVITED("§6Invited"), ACCEPTED("§aAccepted"), DECLINED("§cDeclined"), REPLIED("§eReplied"),
}

fun dungeonClassChange(message: String, self: String): Pair<String, DungeonClass>? {
    val text = message.replace(LEGACY_FORMAT, "").trim().replace(CHAT_REPEAT, "")
    val own = OWN_CLASS_CHANGE.matchEntire(text)
    if (own != null) return parseDungeonClass(own.groupValues[1])?.let { self to it }
    val other = OTHER_CLASS_CHANGE.matchEntire(text) ?: return null
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
    private val classLevels = mutableMapOf<String, Pair<DungeonClass, Int>>()
    fun classLevel(name: String, clazz: DungeonClass?): Int? = classLevels[name.lowercase()]?.takeIf { it.first == clazz }?.second
    var size = 1
        private set
    var ready = false
        private set
    var neededClass: DungeonClass? = null
        private set
    val full: Boolean get() = size >= 5
    val canInvite: Boolean get() = ready && !full
    val openClasses: Set<DungeonClass> get() = DungeonClass.entries.filter { it !in classes.values }.toSet()

    fun restoreClasses(saved: Map<String, DungeonClass>, liveClass: (String) -> DungeonClass?) {
        // A saved roster cannot undo class changes received since that party last existed.
        for (name in members) {
            (liveClass(name) ?: classes[name] ?: saved[name])?.let { classes[name] = it }
        }
        advance()
    }

    fun roster(names: Collection<String>, count: Int, confirmed: Boolean = ready, completeNames: Boolean = true) {
        val normalized = names.map { it.lowercase() }.toSet() + if (completeNames) emptySet() else members
        members.clear()
        classes.keys.retainAll(normalized)
        classLevels.keys.retainAll(normalized)
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
            classLevels.clear()
            roster(listOf(self), 1, true)
        }
        OWN_JOIN.matchEntire(message)?.let {
            members.clear()
            classes.clear()
            classLevels.clear()
            // Joining only names the leader; wait for the full roster before enabling invites.
            roster(listOf(self, it.groupValues[1]), 2, false)
        }
        val joined = JOIN.matchEntire(message) ?: CLASS_JOIN.matchEntire(message)
        if (joined != null) {
            if (members.add(joined.groupValues[1].lowercase())) size++
            if (joined.groupValues.size > 2) parseDungeonClass(joined.groupValues[2])?.let {
                classes[joined.groupValues[1].lowercase()] = it
                joined.groupValues[3].toIntOrNull()?.let { level ->
                    classLevels[joined.groupValues[1].lowercase()] = it to level
                }
            }
        }
        LEFT.matchEntire(message)?.let {
            val name = it.groupValues[1].lowercase()
            if (members.remove(name)) size = (size - 1).coerceAtLeast(1)
            classes.remove(name)
            classLevels.remove(name)
        }
        advance()
        return leftParty
    }

    companion object {
        private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
        private val JOIN = Regex("^(?:\\[Party] )?$PLAYER joined the party\\.$")
        private val OWN_JOIN = Regex("^You have joined $PLAYER's? party!$")
        private val LEFT = Regex("^(?:\\[Party] )?$PLAYER (?:has left|has been removed from) the party\\.$")
        private val CLASS_JOIN = Regex("^Party Finder > $PLAYER joined the dungeon group! \\((\\w+) Level (\\d+)\\)$")
        fun joinedPlayer(message: String): String? = (JOIN.matchEntire(message) ?: CLASS_JOIN.matchEntire(message))?.groupValues?.get(1)
        private val LEAVE = Regex("^(?:You left the party\\.|You have been kicked from the party by .+|You are not (?:currently )?in a party\\.|The party was disbanded.*|(?:\\[[^]]+] )?[A-Za-z0-9_]{1,16} has disbanded the party!)$")
    }
}

/** Optional provider integration follows DungeonProgressHud; no extra bundled mod or API key is needed. */
object DungeonFriendProfileProvider {
    private val viewerRequests = ProfileLookupCooldown()
    private val skyblockerAvailable by lazy { FabricLoader.getInstance().isModLoaded("skyblocker") }
    internal val viewerAvailable by lazy { FabricLoader.getInstance().isModLoaded("skyblockpv") }
    val available: Boolean get() = skyblockerAvailable || viewerAvailable

    fun fetch(name: String, uuid: UUID): DungeonFriendStats? {
        if (skyblockerAvailable) {
            runCatching {
                val json = fetchSkyblockerProfiles(uuid)
                DungeonFriendStats.fromProfiles(json, uuid.toString().replace("-", ""))
            }.getOrNull()?.let { return it }
        }
        if (viewerAvailable) {
            runCatching {
                val selected = fetchViewerProfile(uuid) ?: return DungeonFriendStats(StatsState.NO_PROFILE)
                val backing = getter(selected, "getBackingProfile") ?: error("No profile data")
                val future = getter(backing, "getDungeonData") as CompletableFuture<*>
                fromViewerDungeonData(future.get(30, TimeUnit.SECONDS) ?: error("No dungeon data"))
            }.getOrNull()?.let { return it }
        }
        return null // The caller can fall back to the user's Hypixel key or retry later.
    }

    private fun fetchSkyblockerProfiles(uuid: UUID): JsonObject {
        val api = Class.forName("de.hysky.skyblocker.utils.ProfileUtils")
        val future = api.getMethod("fetchFullProfileByUuid", String::class.java)
            .invoke(null, uuid.toString().replace("-", "")) as CompletableFuture<*>
        return future.get(30, TimeUnit.SECONDS) as JsonObject
    }

    internal fun fetchViewerProfile(uuid: UUID, fresh: Boolean = false): Any? =
        fetchViewerProfile(uuid, fresh, Class.forName("me.owdding.skyblockpv.api.ProfileAPI").getField("INSTANCE").get(null))

    internal fun fetchViewerProfile(uuid: UUID, fresh: Boolean, instance: Any): Any? {
        val api = instance.javaClass
        val cached = api.getMethod("getCached", Any::class.java).invoke(instance, uuid) as? List<*>
        if (fresh && cached != null) {
            // The provider has no per-player invalidation. Wait for its cache rather than clearing other mods' data.
            val ttl = (api.getMethod("getMaxCache").invoke(instance) as Number).toLong().coerceIn(1000, 300000)
            throw ProfileLookupDeferred(System.currentTimeMillis() + ttl)
        }
        val profiles = (cached ?: run {
            check(viewerRequests.start(System.currentTimeMillis())) { "Profile lookups are cooling down" }
            var success = false
            try {
                val response = CompletableFuture<List<*>>()
                // getProfiles hides failures as empty lists. Preserve errors so every caller backs off.
                val callback: (Result<List<*>>) -> Unit = { it.fold(response::complete, response::completeExceptionally) }
                api.getMethod("getDataAsync", Any::class.java, String::class.java, kotlin.Function1::class.java)
                    .invoke(instance, uuid, "skymyce", callback)
                response.get(30, TimeUnit.SECONDS).also { success = true }
            } finally {
                viewerRequests.finish(System.currentTimeMillis(), success)
            }
        }).filterNotNull()
        if (profiles.isEmpty()) return null
        return profiles.firstOrNull { getter(it, "getSelected") == true }
            ?: profiles.singleOrNull() ?: error("No selected profile")
    }

    internal fun fromViewerDungeonData(data: Any): DungeonFriendStats {
        val types = getter(data, "getDungeonTypes") as? Map<*, *> ?: error("Missing dungeon types")
        if (types["catacombs"] == null) return DungeonFriendStats(StatsState.HIDDEN)
        val json = JsonObject()
        val classes = JsonObject()
        (getter(data, "getClassExperience") as? Map<*, *>).orEmpty().forEach { (name, xp) ->
            if (name is String && xp is Number) classes.add(name, JsonObject().apply { addProperty("experience", xp) })
        }
        json.add("player_classes", classes)
        (getter(data, "getSelectedClass") as? String)?.let { json.addProperty("selected_dungeon_class", it) }
        val dungeonTypes = JsonObject()
        types.forEach { (name, type) ->
            if (name !is String || type == null) return@forEach
            val dungeon = JsonObject()
            dungeon.addProperty("experience", getter(type, "getExperience") as? Number)
            val completions = JsonObject()
            val fastest = JsonObject()
            val sPlus = JsonObject()
            (getter(type, "getFloors") as? Map<*, *>).orEmpty().forEach { (floor, value) ->
                if (value != null) {
                    val key = floor.toString()
                    completions.addProperty(key, getter(value, "getCompletions") as? Number)
                    viewerTime(value, "getFastestTime-")?.let { fastest.addProperty(key, it) }
                    viewerTime(value, "getFastestTimeSplus-")?.let { sPlus.addProperty(key, it) }
                }
            }
            dungeon.add("tier_completions", completions)
            dungeon.add("fastest_time", fastest)
            dungeon.add("fastest_time_s_plus", sPlus)
            dungeonTypes.add(name, dungeon)
        }
        json.add("dungeon_types", dungeonTypes)
        return DungeonFriendStats.fromDungeons(json)
    }

    private fun getter(instance: Any, name: String): Any? = instance.javaClass.getMethod(name).invoke(instance)

    private fun viewerTime(floor: Any, prefix: String): Long? {
        // Kotlin Duration getters return an encoded value, not milliseconds.
        val getter = floor.javaClass.methods.single { it.name.startsWith(prefix) && it.parameterCount == 0 }
        val encoded = getter.invoke(floor) as Long
        val duration = Duration::class.java.getMethod("box-impl", Long::class.javaPrimitiveType).invoke(null, encoded) as Duration
        return duration.takeIf { it.isFinite() && it.isPositive() }?.inWholeMilliseconds
    }
}

internal class ProfileLookupDeferred(val retryAt: Long) : Exception("Waiting for the provider cache to expire")

/** Both dungeon stats and wealth use the same provider budget; fallback success cannot erase a failure. */
internal class ProfileLookupCooldown {
    @Volatile var nextRequest = 0L
        private set
    private var running = false
    private var retryDelay = 60000L

    @Synchronized fun start(now: Long): Boolean {
        if (running || now < nextRequest) return false
        running = true
        return true
    }

    @Synchronized fun finish(now: Long, success: Boolean) {
        running = false
        nextRequest = now + if (success) 10000 else retryDelay
        retryDelay = if (success) 60000 else (retryDelay * 2).coerceAtMost(300000)
    }
}

/** Cache lookups run independently of the single, rate-limited provider/API request. */
object DungeonFriendStatsCache {
    private data class Request(val uuid: UUID?, val force: Boolean, val refreshExpired: Boolean)
    private data class SharedLookup(val response: CompletableFuture<JsonObject?>, val expires: Long)
    private val cache = mutableMapOf<String, CachedDungeonFriend>()
    private val liveClasses = mutableMapOf<String, Pair<String, DungeonClass>>()
    private val revisions = mutableMapOf<String, Long>()
    private val invalidProfiles = mutableMapOf<String, Long>()
    private val sharedSuppressedAt = mutableMapOf<String, Long>()
    private val pending = linkedMapOf<String, Request>()
    private val sharedLookups = mutableMapOf<String, SharedLookup>()
    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
    private var relayCredits = 20.0 // Leave room below the relay's 30-message burst limit for uploads.
    private var relayUpdated = 0L
    private var store: DungeonFriendStatsStore? = null
    private var dirty = false
    private var nextSave = 0L
    private var inFlight: String? = null
    private var generation = 0L
    private var nextRequest = 0L
    private var apiKey = ""
    private var feedback = ""
    val pendingCount: Int get() = pending.size + if (inFlight != null) 1 else 0
    var completedCount = 0L
        private set
    val status: String get() = feedback.ifEmpty {
        inFlight?.let { "Loading PBs: $it (${pending.size} queued)" }
            ?: if (pending.isNotEmpty()) "Loading PBs: ${pending.size} queued" else ""
    }
    var version = 0L
        private set

    val stats: Map<String, DungeonFriendStats> get() = cache.mapValues { it.value.stats }
    fun get(name: String): DungeonFriendStats? = cache[name.lowercase()]?.stats
    fun isPending(name: String): Boolean = name.lowercase().let { it == inFlight || it in pending }
    fun liveClass(name: String): DungeonClass? = name.lowercase().let { key ->
        liveClasses[key]?.takeIf { it.first == cache[key]?.uuid }?.second
    }
    fun verified(name: String): DungeonFriendStats? = cache[name.lowercase()]
        ?.takeIf { it.verifiedUntil > System.currentTimeMillis() }?.stats
    val canFetch: Boolean get() = DungeonFriendRelay.sharedStatsAvailable || DungeonFriendProfileProvider.available || PartyCommandsConfig.hypixelApiKey.isNotBlank()
    fun diagnostics(): Map<String, Long> = mapOf("stats.cached" to cache.size.toLong(), "stats.queued" to pending.size.toLong(),
        "stats.sharedLookups" to sharedLookups.size.toLong(), "stats.apiJob" to if (inFlight != null) 1L else 0L)

    /** Exact existing automatic policy, for event-driven queueing without delaying cache expiry. */
    fun nextAutomaticRefresh(name: String, now: Long): Long {
        val key = name.lowercase()
        if (isPending(key)) return Long.MAX_VALUE
        val record = cache[key] ?: return now
        return if (record.stats.state == StatsState.UNAVAILABLE || (record.stats.catacombs ?: 0) > 40)
            maxOf(now, record.expires) else Long.MAX_VALUE
    }

    fun updateClass(name: String, uuid: String?, clazz: DungeonClass): Boolean {
        val key = name.lowercase()
        val previous = cache[key]
        val id = uuid ?: previous?.uuid ?: return false
        if (!key.matches(Regex("[a-z0-9_]{1,16}")) || !id.matches(Regex("[a-f0-9]{32}")) ||
            (previous?.uuid != null && previous.uuid != id)) return false
        liveClasses[key] = id to clazz
        if (previous?.stats?.selectedClass == clazz && previous.uuid == id) return false
        val record = previous ?: CachedDungeonFriend(DungeonFriendStats(StatsState.UNAVAILABLE), id, 0L, 0L)
        cache[key] = record.copy(uuid = id, stats = record.stats.copy(selectedClass = clazz))
        dirty = true
        version++
        return true
    }

    fun forgetLiveClass(name: String) { liveClasses.remove(name.lowercase()) }

    fun removeSharedSubjects(uuids: Set<String>) {
        if (uuids.isEmpty()) return
        val now = System.currentTimeMillis()
        uuids.forEach { sharedSuppressedAt[it] = now }
        // A name-only lookup may already hold a removed UUID's result. Drop every outstanding
        // shared response so server/client clock differences cannot restore it after removal.
        sharedLookups.clear()
        val names = cache.filterValues { it.uuid in uuids }.keys.toSet() + pending.filterValues { it.uuid?.toString()?.replace("-", "") in uuids }.keys
        names.forEach { name -> invalidateProfile(name); pending.remove(name) }
        if (sharedSuppressedAt.size > 10000) sharedSuppressedAt.entries.sortedBy { it.value }.take(sharedSuppressedAt.size - 10000).forEach { sharedSuppressedAt.remove(it.key) }
    }

    fun invalidateProfile(name: String, now: Long = System.currentTimeMillis()) {
        val key = name.lowercase()
        forgetLiveClass(key)
        cache.remove(key)
        sharedLookups.remove(key)
        invalidProfiles[key] = now
        revisions[key] = (revisions[key] ?: 0) + 1
        dirty = true
        version++
    }

    private fun withLiveClass(name: String, record: CachedDungeonFriend): CachedDungeonFriend =
        liveClasses[name]?.takeIf { it.first == record.uuid }?.let { record.copy(stats = record.stats.copy(selectedClass = it.second)) } ?: record

    fun request(name: String, uuid: UUID? = null, force: Boolean = false, priority: Boolean = false, bypassShared: Boolean = false, refreshExpired: Boolean = false) {
        if (!name.matches(PLAYER_NAME)) return
        val key = name.lowercase()
        val existing = cache[key]
        val changedPlayer = uuid != null && existing?.uuid != null && existing.uuid != uuid.toString().replace("-", "")
        if (changedPlayer) { cache.remove(key); dirty = true; version++ }
        if (key == inFlight && !force && !changedPlayer) return
        if (!force && !changedPlayer && existing != null &&
            !(if (refreshExpired) System.currentTimeMillis() >= existing.expires else existing.shouldRefresh(System.currentTimeMillis()))) return
        if (force || changedPlayer) {
            revisions[key] = (revisions[key] ?: 0) + 1
            sharedLookups.remove(key)
        }
        val queued = pending[key]
        if (bypassShared) invalidProfiles.putIfAbsent(key, System.currentTimeMillis())
        val request = Request(uuid ?: queued?.uuid, force || changedPlayer || queued?.force == true, refreshExpired || queued?.refreshExpired == true)
        if (priority) {
            pending.remove(key)
            val rest = pending.toMap()
            pending.clear()
            pending[key] = request
            pending.putAll(rest)
        } else pending[key] = request
    }

    /** Recheck at dequeue: another request or relay report may have refreshed this player meanwhile. */
    internal fun queuedNeedsFetch(name: String, now: Long): Boolean {
        val request = pending[name] ?: return false
        val record = cache[name] ?: return true
        return request.force || if (request.refreshExpired) now >= record.expires else record.shouldRefresh(now)
    }

    fun initialize(file: Path) {
        val persistence = DungeonFriendStatsStore(file)
        try {
            cache.putAll(persistence.load())
            store = persistence
            version++
        } catch (_: Exception) {
            // Preserve unreadable data rather than silently overwriting it.
            SkyMyce.logger.warn("Could not read saved dungeon friend stats; using memory for this session")
        }
    }

    /** Periodic saves serialize the (large) cache off the render thread; a forced save (disconnect) stays synchronous. */
    private var writer: me.mycellium.skymyce.utils.StateWriter<Map<String, CachedDungeonFriend>>? = null

    fun save(force: Boolean = false) {
        val persistence = store ?: return
        val now = System.currentTimeMillis()
        if (!dirty || (!force && now < nextSave)) return
        nextSave = now + 30000
        val snapshot = cache.toMap()
        if (!force) {
            val background = writer ?: me.mycellium.skymyce.utils.StateWriter<Map<String, CachedDungeonFriend>>({ persistence.save(it) },
                { job -> Scheduling.schedule(0.seconds) { job() } },
                { MC.instance.execute { dirty = true }; SkyMyce.logger.warn("Could not save dungeon friend stats; will retry") }).also { writer = it }
            background.submit(snapshot); dirty = false
            return
        }
        try {
            persistence.save(snapshot)
            dirty = false
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend stats; will retry")
        }
    }

    fun disconnect() {
        save(true)
        liveClasses.clear()
        generation++
        pending.clear()
        sharedLookups.clear()
        feedback = ""
        version++ // Queue generation/capability changes must wake automatic scheduling.
    }

    fun clear() {
        generation++
        liveClasses.clear()
        cache.clear()
        revisions.clear()
        invalidProfiles.clear()
        pending.clear()
        sharedLookups.clear()
        feedback = ""
        version++
    }

    /** Retain displayed PBs and the server's retry deadline while requesting fresh stats. */
    fun refresh() {
        var changed = false
        cache.replaceAll { _, value -> if ((value.stats.catacombs ?: 0) > 40 && value.expires != 0L) {
            changed = true; value.copy(expires = 0L)
        } else value }
        if (changed) version++
    }

    /** Live relay reports update eligibility and visible rows without another refresh or API lookup. */
    internal fun receiveShared(json: JsonObject, name: String, uuid: String?, now: Long): Boolean {
        val key = name.lowercase()
        if (!key.matches(Regex("[a-z0-9_]{1,16}"))) return false
        val previous = cache[key]
        val record = sharedDungeonFriend(json, name, uuid ?: previous?.uuid, now)?.let { withLiveClass(key, it) } ?: return false
        if (sharedSuppressedAt[record.uuid]?.let { record.verifiedUntil - 600000 <= it } == true) return false
        if (!afterProfileChange(key, record)) return false
        if (previous != null && previous.verifiedUntil >= record.verifiedUntil) return false
        cache[key] = record
        invalidProfiles.remove(key)
        if (pending.containsKey(key)) {
            pending.remove(key)
            sharedLookups.remove(key)
            completedCount++
        }
        dirty = true
        version++
        return true
    }

    // A profile change rejects older reports, not every future relay report. Clients without a local
    // API provider must still be able to recover when another client publishes fresh selected-profile data.
    private fun afterProfileChange(name: String, record: CachedDungeonFriend): Boolean =
        invalidProfiles[name]?.let { record.verifiedUntil - 600000 > it } ?: true

    /** A forced follow-up invalidates the older response, including its eligibility and upload. */
    internal fun storeFetched(name: String, revision: Long, fetched: DungeonFriendStats, uuid: String?, started: Long, time: Long): Boolean {
        if (revision != (revisions[name] ?: 0L)) return false
        val previous = cache[name]
        if ((previous?.verifiedUntil ?: 0L) > started + 600000) return false
        val failed = fetched.state == StatsState.UNAVAILABLE
        val value = if (failed) previous?.stats ?: fetched else fetched
        cache[name] = withLiveClass(name, CachedDungeonFriend(value, uuid, time + if (failed) 60000 else 600000,
            if (failed) previous?.verifiedUntil ?: 0L else started + 600000))
        if (!failed) invalidProfiles.remove(name)
        dirty = true
        version++
        return true
    }

    /** Runs on the client thread, including while an API request or its retry deadline is pending. */
    internal fun pollShared(now: Long, lookup: (String, String?) -> CompletableFuture<JsonObject?>?) {
        relayCredits = minOf(20.0, relayCredits + (now - relayUpdated).coerceAtLeast(0) / 1000.0)
        relayUpdated = now
        sharedLookups.entries.removeIf { it.key !in pending || it.value.expires <= now }
        for ((name, query) in sharedLookups.toMap()) {
            if (!query.response.isDone) continue
            val response = runCatching { query.response.getNow(null) }.getOrNull() ?: continue
            if (response.get("error")?.takeIf { it.isJsonPrimitive }?.asString == "rate_limited") {
                relayCredits = 0.0
                sharedLookups.remove(name)
                continue
            }
            val request = pending[name] ?: continue
            val uuid = request.uuid?.toString()?.replace("-", "") ?: cache[name]?.uuid
            val record = response.get("record")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.let { sharedDungeonFriend(it, name, uuid, now) }?.let { withLiveClass(name, it) } ?: continue
            if (sharedSuppressedAt[record.uuid]?.let { record.verifiedUntil - 600000 <= it } == true) continue
            if (!afterProfileChange(name, record)) continue
            val previous = cache[name]
            // Refresh clears expires. A valid hit must restore it even if the report is unchanged.
            cache[name] = if (previous != null && previous.verifiedUntil >= record.verifiedUntil)
                previous.copy(expires = maxOf(previous.expires, previous.verifiedUntil)) else record
            invalidProfiles.remove(name)
            pending.remove(name)
            sharedLookups.remove(name)
            completedCount++
            dirty = true
            version++
        }
        // At most three prefetched misses plus the active API request: the relay keeps four upload grants.
        if (sharedLookups.size >= 3 || relayCredits < 1) return
        val request = pending.entries.firstOrNull { it.key !in sharedLookups } ?: return
        val uuid = request.value.uuid?.toString()?.replace("-", "") ?: cache[request.key]?.uuid
        val response = lookup(request.key, uuid) ?: return
        relayCredits--
        sharedLookups[request.key] = SharedLookup(response, now + 110000)
    }

    fun tick() {
        save()
        val key = PartyCommandsConfig.hypixelApiKey.trim()
        if (key != apiKey) {
            apiKey = key
            disconnect()
            cache.replaceAll { _, value -> if (value.stats.state == StatsState.UNAVAILABLE) value.copy(expires = 0L) else value }
            nextRequest = 0L
        }
        if (!canFetch) {
            feedback = "Use SkyBlockPv / SkyBlocker or add an API key"
            if (pending.isNotEmpty() || sharedLookups.isNotEmpty()) version++
            pending.clear()
            sharedLookups.clear()
            return
        }
        val now = System.currentTimeMillis()
        if (DungeonFriendRelay.sharedStatsAvailable) pollShared(now, DungeonFriendRelay::lookupStats)
        else sharedLookups.clear()
        if (inFlight != null || now < nextRequest) return
        val request = pending.entries.firstOrNull {
            !DungeonFriendRelay.sharedStatsAvailable || sharedLookups[it.key]?.response?.isDone == true
        } ?: return
        val fetch = queuedNeedsFetch(request.key, now)
        pending.remove(request.key)
        val sharedLookup = sharedLookups.remove(request.key)
        if (!fetch) { completedCount++; version++; return }
        val knownUuid = request.value.uuid?.toString()?.replace("-", "") ?: cache[request.key]?.uuid
        inFlight = request.key
        val token = generation
        val revision = revisions[request.key] ?: 0L
        feedback = ""
        Scheduling.schedule(0.seconds) {
            var resolvedUuid = knownUuid
            var result = DungeonFriendStats(StatsState.UNAVAILABLE)
            var retryAfter = 2000L
            var message = ""
            var upload: String? = null
            try {
                val response = runCatching { sharedLookup?.response?.getNow(null) }.getOrNull()
                upload = response?.get("upload")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                if (resolvedUuid == null) {
                    resolvedUuid = Http.get("https://api.mojang.com/users/profiles/minecraft/${request.key}") {
                        val body = asText()
                        check(isOk) { "Name lookup unavailable" }
                        JsonParser.parseString(body).asJsonObject.get("id").asString
                    }
                }
                require(resolvedUuid!!.matches(Regex("[a-fA-F0-9]{32}")))
                val profileUuid = UUID.fromString(resolvedUuid.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                RuntimeProfiler.measure(RuntimeProfiler.Area.FRIEND_API) {
                    val provided = DungeonFriendProfileProvider.fetch(request.key, profileUuid)
                    result = if (provided != null) provided else {
                        check(key.isNotEmpty()) { "Profile providers unavailable" }
                        Http.get(
                            "https://api.hypixel.net/v2/skyblock/profiles",
                            queries = mapOf("uuid" to resolvedUuid),
                            headers = mapOf("API-Key" to key),
                        ) {
                            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                            val reset = header("RateLimit-Reset")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
                            if (header("RateLimit-Remaining")?.toIntOrNull() == 0) retryAfter = maxOf(retryAfter, reset * 1000)
                            val body = asText() // Consume/close the response even on errors.
                            if (RuntimeProfiler.active) RuntimeProfiler.network(RuntimeProfiler.Area.FRIEND_API, body.toByteArray(Charsets.UTF_8).size.toLong())
                            when (statusCode) {
                                429 -> {
                                    retryAfter = maxOf(reset, header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60) * 1000
                                    message = "API rate limit reached; waiting before retrying"
                                    DungeonFriendStats(StatsState.UNAVAILABLE)
                                }
                                401, 403 -> {
                                    retryAfter = 300000
                                    message = "API key rejected; update it in API setup"
                                    DungeonFriendStats(StatsState.UNAVAILABLE)
                                }
                                else -> {
                                    check(isOk)
                                    DungeonFriendStats.fromProfiles(JsonParser.parseString(body).asJsonObject, resolvedUuid)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Stats temporarily unavailable; retrying later"
                retryAfter = maxOf(retryAfter, 10000)
            } finally {
                val fetched = result
                val uuid = resolvedUuid?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                val retry = retryAfter
                val requestFeedback = message
                MC.instance.execute {
                    inFlight = null
                    version++ // Even a discarded generation releases automatic request ownership.
                    if (token == generation) {
                        completedCount++
                        val time = System.currentTimeMillis()
                        val failed = fetched.state == StatsState.UNAVAILABLE
                        val stored = storeFetched(request.key, revision, fetched, uuid, now, time)
                        if (stored && !failed && uuid != null && upload != null && sharedLookup != null && time < sharedLookup.expires) {
                            DungeonFriendRelay.publishStats(request.key, uuid, cache.getValue(request.key).stats, now, upload)
                            relayCredits--
                        }
                        nextRequest = time + retry
                        feedback = requestFeedback
                    }
                }
            }
        }
    }
}

data class CachedDungeonFriend(val stats: DungeonFriendStats, val uuid: String?, val expires: Long, val verifiedUntil: Long = expires) {
    // Failed first lookups may retry; successfully checked low/hidden profiles stay cached across sessions.
    fun shouldRefresh(now: Long): Boolean = now >= expires &&
        (stats.state == StatsState.UNAVAILABLE || (stats.catacombs ?: 0) > 40)
}

private val sharedStatsGson = GsonBuilder().create()

/** Shared stats are fresh reports from verified mod users, not a signed Hypixel response. */
fun sharedDungeonFriend(json: JsonObject, name: String, uuid: String?, now: Long): CachedDungeonFriend? = runCatching {
    require(json.get("name").asString.equals(name, true))
    val id = json.get("uuid").asString
    require(id.matches(Regex("[a-f0-9]{32}")) && (uuid == null || uuid == id))
    val fetchedAt = json.get("fetchedAt").asLong
    require(fetchedAt > now - 600000 && fetchedAt <= now + 60000)
    val stats = sharedStatsGson.fromJson(json.get("stats"), DungeonFriendStats::class.java)
    validateDungeonFriendStats(stats)
    require(stats.state != StatsState.UNAVAILABLE)
    CachedDungeonFriend(stats, id, minOf(fetchedAt, now) + 600000)
}.getOrNull()

private fun validateDungeonFriendStats(stats: DungeonFriendStats) {
    require(stats.state in StatsState.entries)
    require(stats.catacombs == null || stats.catacombs in 0..10000)
    requireNotNull(stats.classes).forEach { (key, level) -> require(key in DungeonClass.entries && level in 0..10000) }
    stats.classXp?.forEach { (key, xp) -> require(key in DungeonClass.entries && xp.isFinite() && xp in 0.0..1e13) }
    listOf(requireNotNull(stats.completionTimes), requireNotNull(stats.sPlusTimes)).forEach { times ->
        times.forEach { (floor, time) -> require(floor in FRIEND_FLOORS && time in 1..86400000L) }
    }
    require(requireNotNull(stats.completedFloors).all { it in FRIEND_FLOORS })
}

/** Only normalized stats/UUIDs are persisted, never API credentials or private messages. */
class DungeonFriendStatsStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): Map<String, CachedDungeonFriend> {
        if (!Files.exists(file)) return emptyMap()
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt == 1) { "Unknown dungeon friend cache format" }
        return root.getAsJsonObject("players").entrySet().associate { (name, json) ->
            require(name.matches(Regex("[a-z0-9_]{1,16}")))
            val value = gson.fromJson(json, CachedDungeonFriend::class.java)
            require(value.uuid == null || value.uuid.matches(Regex("[a-fA-F0-9]{32}")))
            require(value.expires >= 0)
            require(value.verifiedUntil >= 0)
            validateDungeonFriendStats(requireNotNull(value.stats))
            name to value
        }
    }

    fun save(players: Map<String, CachedDungeonFriend>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "dungeon_friend_stats", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(mapOf("version" to 1, "players" to players), it) }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Complete rosters and online snapshots are saved only after a successful scan, per account. */
class FriendListStore(private val file: Path) {
    fun load(): List<OnlineDungeonFriend>? = load("online")
    fun loadAll(): List<OnlineDungeonFriend>? = load("all")

    private fun load(field: String): List<OnlineDungeonFriend>? {
        if (!Files.exists(file)) return null
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt in 1..2) { "Unknown friend list cache format" }
        val entries = root.get(field)?.takeUnless { it.isJsonNull }
            ?: return if (field == "all") null else error("Missing friend list")
        return entries.asJsonArray.map {
            val friend = it.asJsonObject
            val name = friend.get("name").asString
            val location = friend.get("location").asString
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")) && location.length <= 256)
            val color = friend.get("rankColor")?.takeUnless { it.isJsonNull }?.asInt
            require(color == null || color in 0..0xFFFFFF)
            OnlineDungeonFriend(name, location, color, friend.get("bestFriend")?.asBoolean ?: false)
        }
    }

    fun save(online: Collection<OnlineDungeonFriend>, all: Collection<OnlineDungeonFriend>? = null) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "friends", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use {
                GsonBuilder().setPrettyPrinting().create().toJson(mapOf("version" to 2, "online" to online, "all" to all), it)
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Only suppresses recognizable responses to a scan we actually requested. All times are monotonic milliseconds. */
class FriendListScanner(cached: Collection<OnlineDungeonFriend>? = null, cachedAll: Collection<OnlineDungeonFriend>? = null) {
    val online = cached.orEmpty().associateByTo(linkedMapOf()) { it.name.lowercase() }
    val all = (cachedAll ?: cached.orEmpty()).associateByTo(linkedMapOf()) { it.name.lowercase() }
    private val completed = online.toMutableMap()
    private val completedAll = all.toMutableMap()
    val savedFriends: Collection<OnlineDungeonFriend> get() = completed.values
    val savedAllFriends: Collection<OnlineDungeonFriend>? get() = completedAll.values.takeIf { hasScannedAll }
    var hasScannedAll = cachedAll != null
        private set
    var hasScanned = cached != null
        private set
    var version = 0L
        private set
    var scanning = false
        private set
    var status = if (hasScanned) "${online.size} cached friends online" else "Waiting for friend list"
        private set
    private var page = 1
    private var lastPage = 1
    var completedPages = 0
        private set
    val remainingPages: Int get() = if (scanning) (lastPage - completedPages).coerceAtLeast(1) else 0
    private var waiting = false
    private var headerSeen = false
    private var deadline = 0L
    private var nextCommand = 0L
    private var requested = !hasScanned
    private var nextAutomaticRefresh = 0L
    private var requestedAll = false
    private var scanAll = false
    private var stopAfterResponse = false
    private var offlineSeen = false
    private val seen = mutableSetOf<String>()
    private val seenAll = mutableSetOf<String>()

    fun refresh(now: Long, full: Boolean = false) {
        if (!scanning) {
            requested = true
            requestedAll = requestedAll || full
            nextCommand = maxOf(now, nextCommand)
        }
    }

    /** Party Finder opening cooldown; manual status refreshes and the initial scan also start it. */
    fun refreshOnOpen(now: Long, full: Boolean = false): Boolean {
        if (scanning || requested || now < nextAutomaticRefresh) return false
        refresh(now, full && !hasScannedAll)
        return true
    }

    fun tick(now: Long): String? {
        if (waiting && now >= deadline) {
            scanning = false
            waiting = false
            status = "Friend scan timed out; click Refresh to retry"
        }
        if (!scanning) {
            if (!requested) return null
            requested = false
            scanning = true
            nextAutomaticRefresh = now + 60000
            scanAll = requestedAll
            requestedAll = false
            page = 1
            lastPage = 1
            completedPages = 0
            stopAfterResponse = false
            offlineSeen = false
            seen.clear()
            seenAll.clear()
        }
        if (waiting || now < nextCommand) return null
        waiting = true
        headerSeen = false
        deadline = now + 10000
        nextCommand = now + 1200
        status = "Scanning friends (page $page)"
        return "friend list $page"
    }

    fun manualCommand() {
        // The response already in flight still belongs to us; suppress it before yielding to manual output.
        stopAfterResponse = waiting
        if (!waiting) scanning = false
        requested = false
        requestedAll = false
    }

    fun cancel() {
        scanning = false
        waiting = false
        requested = !hasScanned
        requestedAll = requested && (requestedAll || scanAll)
    }

    fun notification(name: String, joined: Boolean, style: Style? = null) {
        val key = name.lowercase()
        val previous = all[key]
        all[key] = OnlineDungeonFriend(name, if (joined) "Unknown" else "Offline", style?.color?.value ?: previous?.rankColor,
            previous?.bestFriend == true || style?.isBold == true)
        seenAll += key
        if (joined) {
            online[key] = all.getValue(key)
            seen += key
        } else {
            online.remove(key)
            seen.remove(key)
        }
        if (hasScanned) {
            if (joined) completed[key] = online.getValue(key) else completed.remove(key)
        }
        if (hasScannedAll) completedAll[key] = all.getValue(key)
        version++
    }

    fun remove(name: String) {
        val key = name.lowercase()
        online.remove(key); all.remove(key); completed.remove(key); completedAll.remove(key)
        seen.remove(key); seenAll.remove(key)
        version++
    }

    fun bestFriend(name: String, best: Boolean) {
        val key = name.lowercase()
        val friend = all[key] ?: return
        if (friend.bestFriend == best) return
        all[key] = friend.copy(bestFriend = best)
        online[key]?.let { online[key] = it.copy(bestFriend = best) }
        completed[key]?.let { completed[key] = it.copy(bestFriend = best) }
        completedAll[key]?.let { completedAll[key] = it.copy(bestFriend = best) }
        version++
    }

    fun receive(message: String, now: Long, component: Component = Component.literal(message)): Boolean {
        if (!waiting || now >= deadline) return false
        val lines = message.replace(LEGACY_FORMAT, "").trim().replace(CHAT_REPEAT, "")
            .lines().map { it.trim() }
        // Never swallow a mixed packet containing unrelated chat.
        if (lines.any { !recognized(it) }) return false
        if (!headerSeen && lines.none { HEADER.containsMatchIn(it) } && lines.any { ENTRY.matches(it) }) return false
        val styledLines = component.splitLines()
        for (line in lines) {
            val header = HEADER.find(line)
            if (header != null) {
                if (header.groupValues[1].toIntOrNull() != page) return false
                lastPage = header.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: return false
                headerSeen = true
            } else if (EMPTY.matches(line)) {
                lastPage = page
                scanAll = true // An explicitly empty list is a complete roster, even during an online-only scan.
                headerSeen = true
                finishPage(now)
            } else if (ERROR.matches(line)) {
                scanning = false
                waiting = false
                status = "Friend scan stopped by server; click Refresh to retry"
            } else {
                val entry = ENTRY.matchEntire(line)
                if (entry != null && headerSeen) {
                    val (name, location) = entry.destructured
                    val key = name.lowercase()
                    val source = styledLines.firstOrNull { ENTRY.matchEntire(it.string.replace(LEGACY_FORMAT, "").trim())?.groupValues?.get(1) == name }
                        ?: component
                    val style = dungeonPlayerNameStyle(source, name)
                    val color = style?.color?.value?.takeIf { it != dungeonPlayerNameStyle(source, location)?.color?.value }
                    val friend = OnlineDungeonFriend(name, location, color ?: all[key]?.rankColor,
                        style?.isBold == true || all[key]?.bestFriend == true)
                    all[key] = friend
                    seenAll += key
                    if (location.startsWith("offline", true) || location.startsWith("currently offline", true)) {
                        offlineSeen = true
                        online.remove(key)
                    } else if (!offlineSeen || scanAll) {
                        seen += key
                        online[key] = friend
                    }
                    version++
                } else if (SEPARATOR.matches(line) && headerSeen) {
                    finishPage(now)
                }
            }
        }
        return true
    }

    private fun finishPage(now: Long) {
        if (!waiting) return
        completedPages = page
        waiting = false
        if (stopAfterResponse) {
            scanning = false
            status = "Background scan paused after manual command"
            return
        }
        // Status checks stop at offline entries; only the initial cache load needs the full roster.
        if ((!scanAll && offlineSeen) || page >= lastPage) {
            online.keys.retainAll(seen)
            if (scanAll) {
                all.keys.retainAll(seenAll)
                hasScannedAll = true
            } else {
                all.replaceAll { key, friend -> if (key in online) friend else friend.copy(location = "Offline") }
            }
            completed.clear()
            completed.putAll(online)
            if (hasScannedAll) { completedAll.clear(); completedAll.putAll(all) }
            hasScanned = true
            version++
            scanning = false
            status = if (scanAll) "${all.size} friends • ${online.size} online" else "${online.size} friends online"
        } else {
            page++
            nextCommand = now + 1200
        }
    }

    private fun recognized(line: String) = line.isEmpty() || HEADER.containsMatchIn(line) ||
        ENTRY.matches(line) || SEPARATOR.matches(line) || EMPTY.matches(line) || ERROR.matches(line) ||
        NAVIGATION.matches(line)

    companion object {
        private val NAVIGATION = Regex("(?:<<|>>|<-|->|Previous Page|Next Page|Click to view.*)(?:.*)")
        private val HEADER = Regex("^(?:<<\\s*)?Friends \\(Page (\\d+) of (\\d+)\\)(?:.*)$")
        private val ENTRY = Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) is (.+)$")
        private val SEPARATOR = Regex("^[-▬─]{5,}$")
        private val EMPTY = Regex("^(?:You (?:don't|do not) have any friends(?: yet)?[!.]?|Your friends list is empty[!.]?)$")
        private val ERROR = Regex("^(?:You are sending commands too fast!.*|Please wait.*before using this command.*|You can only use this command.*|Invalid page number.*)$")
    }
}
