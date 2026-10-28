package me.mycellium.skymyce.features.digest

import com.google.gson.JsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.instances.dungeons.friends.globalChatSpans
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTracker
import me.mycellium.skymyce.features.instances.dungeons.tracker.acquisitionId
import me.mycellium.skymyce.features.instances.dungeons.tracker.canonicalItemId
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.consistentItemMetadata
import me.mycellium.skymyce.utils.observedItemMetadata
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.world.entity.player.Inventory
import tech.thatgravyboat.skyblockapi.api.area.hub.ElectionAPI
import tech.thatgravyboat.skyblockapi.api.data.ElectionJson
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.info.MayorChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.ScoreboardUpdateEvent
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.PlayerInventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.extentions.cleanName
import tech.thatgravyboat.skyblockapi.utils.extentions.getRawLore

enum class DailyState { UNKNOWN, READY, ACTIVE, COMPLETE, EMPTY, UNAVAILABLE }

data class DigestForgeSlot(
    val index: Int,
    val item: String,
    val state: DailyState,
    val readyAt: Long = 0,
    val updatedAt: Long = 0,
)

data class DailyTask(
    val id: String,
    val title: String,
    val state: DailyState = DailyState.UNKNOWN,
    val detail: String = "Not detected yet",
    val updatedAt: Long = 0,
    val readyAt: Long = 0,
    val source: String = "Not detected",
    val manual: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val slots: List<DigestForgeSlot> = emptyList(),
)

data class DigestPerk(val name: String, val description: String)
data class DigestCalendarEvent(val name: String, val startsAt: Long, val endsAt: Long, val source: String)
data class GlobalDigest(
    val mayor: String? = null,
    val perks: List<DigestPerk> = emptyList(),
    val events: List<DigestCalendarEvent> = emptyList(),
    val updatedAt: Long = 0,
)

/** Pure observations; never infer completion from the absence of a message or inventory item. */
object DigestActivityParser {
    const val DAY = 86_400_000L
    val titles = linkedMapOf(
        "forge" to "Forge",
        "experiments" to "Experimentation / Superpairs",
        "puzzler" to "Puzzler",
        "fetchur" to "Fetchur",
        "dungeons" to "Dungeon daily activities",
        "mayor" to "Mayor activities & events",
    )
    private val durationPart = Regex("(\\d+)\\s*(d(?:ays?)?|h(?:ours?)?|m(?:inutes?)?|s(?:econds?)?)(?![a-z])", RegexOption.IGNORE_CASE)
    private val puzzler = Regex("Puzzler gave you .{1,100} for solving the puzzle!")
    private val charges = Regex("(?:Stored |Available )?Charges: (\\d{1,2})(?:/\\d{1,2})?", RegexOption.IGNORE_CASE)
    private val dungeonXp = Regex("\\s*\\+[\\d,.]+ Catacombs Experience(?: \\((?:Team|Daily) Bonus\\))?\\s*")

    fun duration(text: String): Long? {
        val value = text.trim().lowercase()
        if (value.length !in 1..80) return null
        val matches = durationPart.findAll(value).toList()
        if (matches.isEmpty() || durationPart.replace(value, "").any { !it.isWhitespace() }) return null
        val result = matches.sumOf { match ->
            val factor = when (match.groupValues[2].first()) { 'd' -> DAY; 'h' -> 3_600_000L; 'm' -> 60_000L; else -> 1_000L }
            (match.groupValues[1].toLongOrNull() ?: return null).takeIf { it <= 365_000 }?.times(factor) ?: return null
        }
        return result.takeIf { it in 0..365 * DAY }
    }

    fun nextReset(now: Long, offsetHours: Int = 0): Long = Instant.ofEpochMilli(now)
        .atOffset(ZoneOffset.ofHours(offsetHours)).toLocalDate().plusDays(1)
        .atStartOfDay().toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()

    fun chat(text: String, now: Long, dwarvenMines: Boolean, privateIsland: Boolean): DailyTask? = when {
        dwarvenMines && puzzler.matches(text) -> DailyTask("puzzler", titles.getValue("puzzler"), DailyState.COMPLETE,
            "Puzzle solved", now, now + DAY, "Puzzler reward message; 24-hour cooldown")
        dwarvenMines && text in setOf("[NPC] Fetchur: thanks thats probably what i needed", "[NPC] Fetchur: come back another time, maybe tmrw") ->
            DailyTask("fetchur", titles.getValue("fetchur"), DailyState.COMPLETE, "Today's request completed", now,
                nextReset(now, -5), "Fetchur response; resets at 00:00 UTC-5")
        privateIsland && text == "You claimed the Superpairs rewards!" -> DailyTask("experiments", titles.getValue("experiments"),
            DailyState.COMPLETE, "Rewards claimed; open the table to check remaining charges", now, 0,
            "Confirmed Superpairs claim; remaining charges have not been checked")
        else -> null
    }

    fun experiment(name: String, lore: List<String>, now: Long): DailyTask? {
        if (!name.contains("Superpairs", true) && !name.contains("Experiment", true)) return null
        val lines = lore.take(40).map(String::trim)
        val count = lines.firstNotNullOfOrNull { charges.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        val cooldown = lines.firstNotNullOfOrNull { line ->
            listOf("Next Charge: ", "Next charge in: ", "Next Experiment: ", "Time Remaining: ", "Cooldown: ").firstNotNullOfOrNull {
                prefix -> line.takeIf { it.startsWith(prefix, true) }?.substring(prefix.length)?.let(::duration)
            }
        }
        val state = when {
            count != null && count > 0 -> DailyState.READY
            count == 0 || "Experiments on cooldown!" in lines || cooldown != null -> DailyState.COMPLETE
            lines.any { it == "Click to play!" || it == "Click to start!" || it == "Click to experiment!" } -> DailyState.READY
            else -> return null
        }
        return DailyTask("experiments", titles.getValue("experiments"), state,
            if (state == DailyState.READY) count?.let { "$it available charge(s)" } ?: "Available in the table"
            else "No available charge detected", now, cooldown?.let { now + it } ?: 0,
            "Experimentation Table lore; bonus charges may change availability", progress = count ?: 0)
    }

    fun forge(index: Int, name: String, lore: List<String>, now: Long, color: Int? = null): DigestForgeSlot? {
        if (index !in 1..7 || name.length !in 1..120) return null
        val lines = lore.take(40).map(String::trim)
        val remaining = lines.firstOrNull { it.startsWith("Time Remaining: ") }?.removePrefix("Time Remaining: ")
        val duration = remaining?.let(::duration)
        val state = when {
            remaining == "Completed!" || "Click to collect!" in lines -> DailyState.COMPLETE
            duration != null -> DailyState.ACTIVE
            name == "Empty Slot" || name.startsWith("Slot #") && (color == 0x55FF55 || lines.any { it == "Click to forge!" || it == "Click to select!" }) -> DailyState.EMPTY
            name == "Locked Slot" || name == "Locked" || name.startsWith("Slot #") && color == 0xFF5555 -> DailyState.UNAVAILABLE
            else -> return null
        }
        return DigestForgeSlot(index, if (state in setOf(DailyState.EMPTY, DailyState.UNAVAILABLE)) "" else name,
            state, duration?.let { now + it } ?: 0, now)
    }

    fun forgeTask(slots: List<DigestForgeSlot>, now: Long): DailyTask {
        val current = (1..7).map { index ->
            val slot = slots.firstOrNull { it.index == index } ?: DigestForgeSlot(index, "", DailyState.UNKNOWN)
            if (slot.state == DailyState.ACTIVE && slot.readyAt in 1..now) slot.copy(state = DailyState.COMPLETE) else slot
        }
        val ready = current.count { it.state == DailyState.COMPLETE }
        val active = current.count { it.state == DailyState.ACTIVE }
        val empty = current.count { it.state == DailyState.EMPTY }
        val unknown = current.count { it.state == DailyState.UNKNOWN }
        return DailyTask("forge", titles.getValue("forge"), when {
            ready > 0 -> DailyState.COMPLETE
            active > 0 -> DailyState.ACTIVE
            empty > 0 -> DailyState.EMPTY
            else -> DailyState.UNKNOWN
        }, if (unknown == current.size) "Open The Forge to detect slots"
            else "$ready ready · $active forging · $empty empty" + if (unknown > 0) " · $unknown unknown" else "",
            current.maxOfOrNull { it.updatedAt } ?: 0, current.filter { it.state == DailyState.ACTIVE }.minOfOrNull { it.readyAt } ?: 0,
            "Last observed Forge inventory; undetected slots remain unknown", slots = current)
    }

    fun isDungeonCompletion(text: String): Boolean = dungeonXp.matches(text)

    fun dungeon(previous: DailyTask?, now: Long): DailyTask {
        val runs = (if (previous != null && previous.readyAt > now) previous.progress else 0).coerceIn(0, 5)
        val progress = (runs + 1).coerceAtMost(5)
        return DailyTask("dungeons", titles.getValue("dungeons"), DailyState.ACTIVE,
            "$progress run(s) observed; daily bonus completion is unverified", now, nextReset(now),
            "Catacombs XP after a started run; failed runs may also grant XP. Five daily bonuses reset at UTC midnight; use a manual note if needed.",
            progress = progress, total = 5)
    }

    fun display(task: DailyTask, now: Long): DailyTask {
        if (task.id == "forge") return forgeTask(task.slots, now)
        if (task.readyAt <= 0 || task.readyAt > now) return task
        if (task.manual) return task.copy(state = DailyState.UNKNOWN, detail = "Manual note expired; check the activity again", progress = 0)
        return when (task.id) {
            "puzzler", "fetchur", "experiments" -> task.copy(state = DailyState.READY, detail = "Detected cooldown has reset", progress = 0)
            "dungeons" -> task.copy(state = DailyState.UNKNOWN, detail = "No runs observed since the UTC reset", progress = 0)
            else -> task.copy(state = DailyState.UNKNOWN, detail = "Previous observation expired")
        }
    }

    fun calendar(name: String, lore: List<String>, now: Long): DigestCalendarEvent? {
        val clean = name.trim().removePrefix("Event: ")
        if (clean.length !in 3..80 || clean in setOf("Calendar and Events", "Close", "Go Back", "Calendar", "Event Calendar")) return null
        val starts = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Starts in: ") }?.removePrefix("Starts in: ")?.let(::duration) }
        val ends = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Ends in: ") }?.removePrefix("Ends in: ")?.let(::duration) }
        val duration = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Duration: ") }?.removePrefix("Duration: ")?.let(::duration) }
        if (starts == null && ends == null) return null
        val startAt = starts?.let { now + it } ?: now
        return DigestCalendarEvent(clean, startAt, ends?.let { now + it } ?: duration?.let { startAt + it } ?: 0,
            "Calendar and Events inventory")
    }
}

/** The module only reacts to relevant game events. Rendering reads immutable snapshots. */
object DigestActivities : SkyMyceModule() {
    var changed: (() -> Unit)? = null
    var contextReady: () -> Boolean = { false }
    private val tasks = linkedMapOf<String, DailyTask>()
    private var status = GlobalDigest()
    private var lastElectionData: ElectionJson? = null
    private var dungeonAwaitingXp = false
    private val spookyTimer = Regex("Spooky Festival (?:(\\d+):)?(\\d{1,2}):(\\d{2})")

    fun snapshot(): List<DailyTask> = tasks.values.toList()
    fun restore(state: List<DailyTask>) {
        tasks.clear()
        state.filter { it.id in DigestActivityParser.titles }.take(6).forEach { tasks[it.id] = it }
        dungeonAwaitingXp = false
    }
    fun clearContext() { tasks.clear(); dungeonAwaitingXp = false }

    fun current(now: Long = System.currentTimeMillis()): List<DailyTask> = DigestActivityParser.titles.map { (id, title) ->
        if (id == "mayor") {
            val ongoing = status.events.filter { it.startsAt <= now && it.endsAt > now }
            DailyTask(id, title, if (ongoing.isEmpty()) DailyState.UNKNOWN else DailyState.ACTIVE,
                ongoing.joinToString { it.name }.ifEmpty { "Open Calendar and Events to detect current activities" },
                status.updatedAt, source = "Observed event calendar; activity completion is not inferred")
        } else DigestActivityParser.display(tasks[id] ?: DailyTask(id, title), now)
    }

    fun global(): GlobalDigest = status
    fun restoreGlobal(state: GlobalDigest) { status = state }

    fun manual(id: String, complete: Boolean) {
        if (!contextReady() || id !in setOf("puzzler", "fetchur", "experiments", "dungeons")) return
        if (!complete) { tasks.remove(id); changed?.invoke(); return }
        val now = System.currentTimeMillis()
        val readyAt = when (id) {
            "puzzler" -> now + DigestActivityParser.DAY
            "fetchur" -> DigestActivityParser.nextReset(now, -5)
            else -> DigestActivityParser.nextReset(now)
        }
        put(DailyTask(id, DigestActivityParser.titles.getValue(id), DailyState.COMPLETE, "Marked complete by you", now,
            readyAt, "Manual check; not verified by the game", manual = true))
    }

    fun captureGlobal() {
        val mayor = ElectionAPI.mayor ?: return
        val data = ElectionAPI.rawData
        val perks = (mayor.activePerks + ElectionAPI.minister?.activePerks.orEmpty()).distinctBy { it.id }
            .take(16).map { DigestPerk(it.perkName.take(100), it.description.replace(Regex("§."), "").take(600)) }
        if (status.mayor == mayor.candidateName && status.perks == perks && data === lastElectionData) return
        lastElectionData = data
        status = status.copy(mayor = mayor.candidateName, perks = perks, updatedAt = System.currentTimeMillis())
        changed?.invoke()
    }

    @Subscription fun onMayor(event: MayorChangeEvent) = captureGlobal()

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock || !contextReady()) return
        val now = System.currentTimeMillis()
        DigestActivityParser.chat(event.text, now, LocationAPI.island == SkyBlockIsland.DWARVEN_MINES,
            LocationAPI.island == SkyBlockIsland.PRIVATE_ISLAND && !LocationAPI.isGuest)?.let(::put)
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && dungeonAwaitingXp && DigestActivityParser.isDungeonCompletion(event.text)) {
            dungeonAwaitingXp = false
            put(DigestActivityParser.dungeon(tasks["dungeons"], now))
        }
    }

    @Subscription fun onDungeonStart(event: DungeonStartEvent) {
        dungeonAwaitingXp = contextReady()
        if (!dungeonAwaitingXp) return
        val now = System.currentTimeMillis()
        val previous = tasks["dungeons"]?.let { DigestActivityParser.display(it, now) }
        if (previous?.state == DailyState.COMPLETE && previous.manual) { dungeonAwaitingXp = false; return }
        put(DailyTask("dungeons", DigestActivityParser.titles.getValue("dungeons"), DailyState.ACTIVE,
            "Run in progress; daily bonus availability is unverified", now, DigestActivityParser.nextReset(now),
            "DungeonStartEvent from the existing dungeon API", progress = previous?.progress ?: 0, total = 5))
    }

    @Subscription fun onContainer(event: ContainerInitializedEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady() || event.title !in setOf("The Forge", "Experimentation Table", "Calendar and Events")) return
        event.containerSlots.forEach { slot -> observe(event.title, slot.index, slot.item.cleanName, slot.item.getRawLore(), slot.item.hoverName.style.color?.value) }
    }

    @Subscription fun onInventory(event: InventoryChangeEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady() || event.isInPlayerInventory || event.title !in setOf("The Forge", "Experimentation Table", "Calendar and Events")) return
        observe(event.title, event.slot.index, event.item.cleanName, event.item.getRawLore(), event.item.hoverName.style.color?.value)
    }

    @Subscription fun onScoreboard(event: ScoreboardUpdateEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady()) return
        val match = event.added.firstNotNullOfOrNull { spookyTimer.matchEntire(it.trim()) } ?: return
        val now = System.currentTimeMillis()
        val seconds = (match.groupValues[1].toLongOrNull() ?: 0) * 3600 + match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()
        if (seconds !in 1..21_600) return
        updateEvent(DigestCalendarEvent("Spooky Festival", now, now + seconds * 1000, "Live scoreboard countdown"))
    }

    private fun observe(title: String, slot: Int, name: String, lore: List<String>, color: Int?) {
        val now = System.currentTimeMillis()
        when (title) {
            "The Forge" -> DigestActivityParser.forge(slot - 9, name, lore, now, color)?.let { forge ->
                val previous = tasks["forge"]?.slots.orEmpty()
                val old = previous.firstOrNull { it.index == forge.index }
                if (old != null && old.item == forge.item && old.state == forge.state && kotlin.math.abs(old.readyAt - forge.readyAt) < 3000) return
                val slots = previous.filterNot { it.index == forge.index } + forge
                put(DigestActivityParser.forgeTask(slots.sortedBy { it.index }, now))
            }
            "Experimentation Table" -> if (!LocationAPI.isGuest) DigestActivityParser.experiment(name, lore, now)?.let(::put)
            "Calendar and Events" -> DigestActivityParser.calendar(name, lore, now)?.let(::updateEvent)
        }
    }

    private fun updateEvent(event: DigestCalendarEvent) {
        val previous = status.events.firstOrNull { it.name == event.name }
        val now = System.currentTimeMillis()
        val next = if (previous != null && previous.startsAt <= now && event.startsAt <= now)
            event.copy(startsAt = previous.startsAt) else event
        // Scoreboards update each second; persist at most one correction per minute, not every countdown tick.
        if (previous != null && kotlin.math.abs(previous.endsAt - next.endsAt) < 60_000 &&
            kotlin.math.abs(previous.startsAt - next.startsAt) < 60_000) return
        status = status.copy(events = (status.events.filter { it.name != next.name && (it.endsAt == 0L && it.startsAt > now || it.endsAt > now) } + next).takeLast(24), updatedAt = now)
        changed?.invoke()
    }

    private fun put(task: DailyTask) {
        val previous = tasks[task.id]
        if (previous == task || previous?.copy(updatedAt = task.updatedAt, slots = task.slots) == task && task.slots.isEmpty()) return
        tasks[task.id] = task
        changed?.invoke()
    }
}

enum class NewsSource(val label: String, val wire: String) {
    GAME("Game Updates", "game"), DERAILIOUS("Derailious", "derailious")
}
enum class DigestFailure(val label: String) {
    OFFLINE("Offline"), TIMEOUT("Connection timed out"), INVALID("Update unavailable"),
    AUTHENTICATION("News provider permissions unavailable"), RATE_LIMITED("Waiting before retrying"),
    UNCONFIGURED("News bridge not configured"), UNSUPPORTED("Service not available yet")
}
data class DigestNewsItem(val id: String, val source: NewsSource, val title: String, val content: String,
                          val publishedAt: Long, val url: String? = null, val truncated: Boolean = false,
                          val thumbnailUrl: String? = null, val links: List<String> = emptyList())
data class DigestNewsState(val items: List<DigestNewsItem> = emptyList(), val fetchedAt: Long = 0,
                          val loading: Boolean = false, val failure: DigestFailure? = null, val retryAt: Long = 0)

/** Public announcements use the compiled HTTPS backend without transmitting a community session. */
internal fun requestDigestNews(source: NewsSource): CompletableFuture<JsonObject?> {
    val response = CompletableFuture<JsonObject?>()
    val work = Scheduling.schedule(0.milliseconds) {
        if (!response.isDone) runCatching {
            CommunityClient.exchange("/community/news?source=${source.wire}", null, "GET")
        }.onSuccess { response.complete(it) }.onFailure { response.completeExceptionally(it) }
    }
    response.whenComplete { _, _ -> if (response.isCancelled) work.cancel(true) }
    return response
}

internal fun digestNewsFailure(error: Throwable): DigestFailure {
    val cause = if (error is CompletionException) error.cause ?: error else error
    return when (cause) {
        is TimeoutException, is HttpTimeoutException -> DigestFailure.TIMEOUT
        is IOException -> DigestFailure.OFFLINE
        is CommunityFailure -> when (cause.code) {
            "rate_limited" -> DigestFailure.RATE_LIMITED
            "authentication" -> DigestFailure.AUTHENTICATION
            "invalid_source" -> DigestFailure.INVALID
            else -> DigestFailure.OFFLINE
        }
        else -> DigestFailure.INVALID
    }
}

internal fun safeDigestUrl(value: String?): String? = value?.takeIf { it.length <= 512 }?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == "discord.com" || uri.host == "hypixel.net" || uri.host.endsWith(".hypixel.net"))
    }?.toASCIIString()
}

internal fun safeDigestVideoUrl(value: String?, thumbnail: Boolean = false): String? = value?.takeIf { it.length <= 512 }?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
            if (thumbnail) uri.host in setOf("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com") &&
                uri.path.matches(Regex("/vi/[A-Za-z0-9_-]{11}/hqdefault\\.jpg")) && uri.query == null
            else uri.host in setOf("www.youtube.com", "youtube.com") &&
                (uri.path == "/watch" && uri.query?.matches(Regex("v=[A-Za-z0-9_-]{11}")) == true ||
                    uri.path.matches(Regex("/shorts/[A-Za-z0-9_-]{11}")) && uri.query == null)
    }?.toASCIIString()
}

internal fun safeDigestNewsUrl(value: String?, source: NewsSource): String? =
    if (source == NewsSource.DERAILIOUS) safeDigestVideoUrl(value) else safeDigestUrl(value)

/** Discord text stays inert; only explicitly selected, supported public links can open a page. */
internal fun digestNewsLinks(content: String, links: List<String> = emptyList()): List<String> {
    var length = 0
    return (links.asSequence() + globalChatSpans(content).asSequence().mapNotNull { it.url?.toString() })
        .mapNotNull { BrowserLinks.trusted(it)?.toASCIIString() }.distinct()
        .filter { if (length + it.length > 1024) false else { length += it.length; true } }.take(8).toList()
}

internal fun digestPlainText(value: String, limit: Int): String = value.replace(Regex("§."), "")
    .filter { it == '\n' || it == '\t' || (!it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069') }
    .take(limit).trim()

/** Strict bounded DTO parsing; Discord content is inert text, never Minecraft click events. */
internal fun parseDigestNews(json: JsonObject, source: NewsSource, now: Long): List<DigestNewsItem> {
    require(json.get("source")?.asString == source.wire)
    val array = json.getAsJsonArray("items") ?: error("Missing news items")
    require(array.size() <= 8)
    val result = array.mapNotNull { element -> runCatching {
        val entry = element.asJsonObject
        val rawContent = entry.get("content").asString
        val rawTitle = entry.get("title")?.asString.orEmpty()
        require(rawContent.length <= 2500 && rawTitle.length <= 160)
        val content = digestPlainText(rawContent, 2500)
        val title = digestPlainText(rawTitle.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }, 160)
        val time = entry.get("publishedAt").asLong
        require(title.isNotBlank() && time in 1..now + 60000)
        val id = entry.get("id")?.asString?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,80}")) }
            ?: MessageDigest.getInstance("SHA-256").digest("${source.wire}|$time|$content".toByteArray())
                .joinToString("") { "%02x".format(it) }
        val url = safeDigestNewsUrl(entry.get("url")?.takeUnless { it.isJsonNull }?.asString, source)
        require(source != NewsSource.DERAILIOUS || url != null)
        val links = entry.get("links")?.let { raw ->
            require(raw.isJsonArray && raw.asJsonArray.size() <= 8)
            raw.asJsonArray.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 2048)
                value.asString
            }
        }.orEmpty()
        DigestNewsItem(id, source, title, content, time, url, entry.get("truncated")?.asBoolean == true,
            if (source == NewsSource.DERAILIOUS) safeDigestVideoUrl(entry.get("thumbnailUrl")?.takeUnless { it.isJsonNull }?.asString, true) else null,
            digestNewsLinks(content, links))
    }.getOrNull() }
    require(array.isEmpty || result.isNotEmpty())
    return result.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(8)
}

/** One request per source, stale-while-revalidate, and no automatic retry loop. Call on the client thread. */
class DigestNewsRepository(
    private val fetch: (NewsSource) -> CompletableFuture<JsonObject?>?,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val states = NewsSource.entries.associateWith { DigestNewsState() }.toMutableMap()
    private val pending = mutableMapOf<NewsSource, CompletableFuture<JsonObject?>>()
    private val nextAttempt = mutableMapOf<NewsSource, Long>()
    private var generation = 0
    val pendingCount get() = pending.size
    fun state(source: NewsSource): DigestNewsState = states.getValue(source)

    fun restore(source: NewsSource, state: DigestNewsState) {
        if (states.getValue(source).fetchedAt > state.fetchedAt) return
        states[source] = state.copy(items = state.items.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(8), loading = source in pending)
    }

    fun refresh(sources: Collection<NewsSource>, force: Boolean = false) {
        val time = now()
        sources.forEach { source ->
            val old = state(source)
            if (source in pending || time < (nextAttempt[source] ?: 0) || time < old.retryAt ||
                (!force && old.fetchedAt > 0 && time - old.fetchedAt in 0..299999)) return@forEach
            val future = runCatching { fetch(source) }.getOrNull()
            if (future == null) { states[source] = old.copy(failure = DigestFailure.OFFLINE); changed(); return@forEach }
            nextAttempt[source] = time + 60000
            val token = generation
            pending[source] = future
            states[source] = old.copy(loading = true)
            changed()
            future.whenComplete { response, error -> dispatch {
                if (token != generation || pending[source] !== future) return@dispatch
                pending.remove(source)
                val current = state(source)
                states[source] = try {
                    if (error != null) throw error
                    if (response == null) current.copy(loading = false, failure = DigestFailure.TIMEOUT)
                    else if (!response.has("status") && response.has("error")) current.copy(loading = false,
                        failure = when (response.get("error")?.asString) {
                            "rate_limited" -> DigestFailure.RATE_LIMITED
                            "authentication" -> DigestFailure.AUTHENTICATION
                            else -> DigestFailure.INVALID
                        }, retryAt = (response.get("retryAt")?.asLong ?: now() + 60000).coerceIn(now(), now() + 86400000))
                    else {
                        val status = response.get("status")?.asString
                        require(status in setOf("ready", "stale", "unavailable"))
                        val items = parseDigestNews(response, source, now())
                        val fetchedAt = response.get("fetchedAt")?.asLong ?: 0L
                        require(fetchedAt in 0..now() + 60000 && (status != "ready" || fetchedAt > 0))
                        val retryAt = (response.get("retryAt")?.asLong ?: 0L).coerceIn(0, now() + 86400000)
                        val failure = when (response.get("error")?.asString) {
                            "not_configured" -> DigestFailure.UNCONFIGURED
                            "timeout" -> DigestFailure.TIMEOUT
                            "invalid_response" -> DigestFailure.INVALID
                            "authentication" -> DigestFailure.AUTHENTICATION
                            "rate_limited" -> DigestFailure.RATE_LIMITED
                            else -> if (status == "ready") null else DigestFailure.OFFLINE
                        }
                        if (status == "unavailable" || (status == "stale" && items.isEmpty()))
                            current.copy(loading = false, failure = failure, retryAt = retryAt)
                        else DigestNewsState(items, fetchedAt, failure = failure, retryAt = retryAt)
                    }
                } catch (error: Exception) {
                    current.copy(loading = false, failure = digestNewsFailure(error))
                }
                changed()
            } }
        }
    }

    fun cancel() {
        generation++
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        states.replaceAll { _, value -> value.copy(loading = false) }
    }
}

data class DetectedRng(val id: String, val item: String, val activity: String, val occurredAt: Long, val confirmation: String? = null,
    val rarity: String? = null, val displayName: String? = null)
internal fun confirmedRng(drop: DetectedRng) = drop.confirmation?.let { drop.copy(id = acquisitionId(it)) } ?: drop
/** [public] items may be shared to the community feed; the relay accepts only those. Others stay in local history. */
data class DigestRareItem(val name: String, val activity: String, val public: Boolean = true)

object DigestRngCatalog {
    fun valid(item: String, activity: String): Boolean = DigestRngParser.catalog[item]?.activity == activity
    fun public(item: String, activity: String): Boolean = DigestRngParser.catalog[item]?.let { it.activity == activity && it.public } == true
    fun name(item: String): String = DigestRngParser.catalog[item]?.name ?: "Unknown drop"
}

/** Presentation only: saved journal records never re-enter detection, publication or recent-history storage. */
internal fun digestLocalRngHistory(acquisitions: List<AcquisitionEvent>, recent: List<DigestRngEntry>,
    context: AcquisitionContext?, now: Long): List<DigestEntry> {
    val journal = acquisitions.filter { DigestRngCatalog.valid(it.item, it.activity) && it.occurredAt in 1..now + 60000 &&
        it.quantity in 1..100000 && (context == null || context.matches(it.context)) }.map { event ->
        DigestEntry(event.id, DigestRngCatalog.name(event.item) + if (event.quantity > 1) " ×${event.quantity}" else "",
            listOfNotNull(event.player, event.activity.replaceFirstChar(Char::uppercase), event.floor).joinToString(" · "),
            if (event.origin == "confirmed") "Saved from your local game confirmation."
            else "Saved journal record; imported and older entries retain their recorded dates.",
            DigestTone.SPECIAL, if (event.origin == "confirmed") "Your drop" else "Saved local record", event.occurredAt,
            activity = event.activity, item = event.item)
    }
    val cached = recent.filter { !it.community && validDigestRng(it, now) }.map { entry ->
        DigestEntry(entry.id, DigestRngCatalog.name(entry.item), "${entry.player} · ${entry.activity.replaceFirstChar(Char::uppercase)}",
            "Saved from your local game confirmation.", DigestTone.SPECIAL, "Your drop", entry.occurredAt, activity = entry.activity, item = entry.item)
    }
    return (journal + cached).distinctBy { it.id }.sortedWith(compareByDescending<DigestEntry> { it.timestamp }.thenBy { it.id })
}

/** A deliberately narrow catalog: common loot and reward previews can never become feed events. */
object DigestRngParser {
    val catalog = mapOf(
        "NECRON_HANDLE" to DigestRareItem("Necron's Handle", "dungeon"),
        "GIANTS_SWORD" to DigestRareItem("Giant's Sword", "dungeon"),
        "DARK_CLAYMORE" to DigestRareItem("Dark Claymore", "dungeon"),
        "SHADOW_FURY" to DigestRareItem("Shadow Fury", "dungeon"),
        "IMPLOSION_SCROLL" to DigestRareItem("Implosion", "dungeon"),
        "WITHER_SHIELD_SCROLL" to DigestRareItem("Wither Shield", "dungeon"),
        "SHADOW_WARP_SCROLL" to DigestRareItem("Shadow Warp", "dungeon"),
        // Local journal only until the relay's public allowlist includes them.
        "FIRST_MASTER_STAR" to DigestRareItem("First Master Star", "dungeon", public = false),
        "SECOND_MASTER_STAR" to DigestRareItem("Second Master Star", "dungeon", public = false),
        "THIRD_MASTER_STAR" to DigestRareItem("Third Master Star", "dungeon", public = false),
        "FOURTH_MASTER_STAR" to DigestRareItem("Fourth Master Star", "dungeon", public = false),
        "FIFTH_MASTER_STAR" to DigestRareItem("Fifth Master Star", "dungeon", public = false),
        "MASTER_SKULL_TIER_5" to DigestRareItem("Master Skull - Tier 5", "dungeon", public = false),
        "AUTO_RECOMBOBULATOR" to DigestRareItem("Auto Recombobulator", "dungeon", public = false),
        "WARDEN_HEART" to DigestRareItem("Warden Heart", "slayer"),
        "JUDGEMENT_CORE" to DigestRareItem("Judgement Core", "slayer"),
        "OVERFLUX_CAPACITOR" to DigestRareItem("Overflux Capacitor", "slayer"),
    )
    private val dungeon = Regex("RARE REWARD! (?:\\[[A-Z+]+] )?([A-Za-z0-9_]{1,16}) found an? (.{1,100}) in their (?:Obsidian|Bedrock) Chest!")
    private val dungeonDrop = Regex("RNG DROP! (?:\\[[A-Z+]+] )?([A-Za-z0-9_]{1,16}) just found an? (.{1,100})!")
    private val playerName = Regex("[A-Za-z0-9_]{1,16}")
    private val slayer = Regex("(?:CRAZY RARE DROP!|INSANE DROP!|RNGesus INCARNATE DROP!)\\s+(?:\\(([^()]{1,100})\\)|([^()]{1,100}?))(?: \\(\\+[\\d,.]+%? [^\\r\\n]{0,8}Magic Find\\))?[! ]*")

    fun detect(text: String, player: String, location: String, now: Long): DetectedRng? {
        val normalized = normalizeRngText(text) ?: return null
        if (!playerName.matches(player) || now <= 0) return null
        val dungeonMatch = dungeon.matchEntire(normalized) ?: dungeonDrop.matchEntire(normalized)
        val activity: String
        val name: String
        if (dungeonMatch != null) {
            if (location !in setOf("THE_CATACOMBS", "DUNGEON_HUB") || !dungeonMatch.groupValues[1].equals(player, true)) return null
            activity = "dungeon"
            name = dungeonMatch.groupValues[2]
        } else {
            if (location !in setOf("HUB", "SPIDERS_DEN", "THE_PARK", "THE_END", "CRIMSON_ISLE")) return null
            val match = slayer.matchEntire(normalized) ?: return null
            activity = "slayer"
            name = match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        val item = catalog.entries.firstOrNull { it.value.activity == activity && it.value.name == name.replace('’', '\'').trim() }?.key ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest("${player.lowercase()}|$activity|$item|$now".toByteArray(StandardCharsets.UTF_8))
        val id = hash.take(16).joinToString("") { "%02x".format(it) }
        return DetectedRng(id, item, activity, now)
    }
}

// Compiled once: normalization runs on every chat message.
private val rngStyle = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
private val rngSpaces = Regex("[\\s\\u00a0\\u202f]+")

/** Strip known Minecraft styling and equivalent spacing, without removing sender prefixes or combining lines. */
internal fun normalizeRngText(text: String): String? {
    if (text.length !in 1..600 || text.any { it == '\n' || it == '\r' || it.isISOControl() && it != '\t' }) return null
    val stripped = text.replace(rngStyle, "")
    if ('§' in stripped) return null
    return stripped.replace('’', '\'').replace('‘', '\'').replace(rngSpaces, " ").trim().takeIf { it.length in 1..300 }
}

/** Announcements alone may describe a preview. Require a matching aggregate inventory increase. */
class DigestDungeonAcquisition {
    private data class InventoryIncrease(val at: Long, val confirmation: String?, val metadata: ObservedItemMetadata?)
    private var counts: Map<String, Int>? = null
    private val pending = linkedMapOf<String, DetectedRng>()
    private val increased = mutableMapOf<String, InventoryIncrease>()
    private val slayerMetadata = linkedMapOf<String, DetectedRng>()

    fun seed(snapshot: Map<String, Int>) { counts = snapshot.toMap(); pending.clear(); increased.clear(); slayerMetadata.clear() }
    fun clear() { counts = null; pending.clear(); increased.clear(); slayerMetadata.clear() }

    fun announce(drop: DetectedRng, now: Long): DetectedRng? {
        if (counts == null || drop.activity != "dungeon") return null
        prune(now)
        increased.remove(drop.item)?.takeIf { now - it.at in 0..3000 }?.let {
            return drop.copy(confirmation = it.confirmation, rarity = it.metadata?.rarity, displayName = it.metadata?.displayName)
        }
        pending[drop.item] = drop
        return null
    }

    /** Slayer chat already confirms the personal drop. Correlate only its optional metadata, never an old held item. */
    fun annotateSlayer(drop: DetectedRng, now: Long): DetectedRng {
        if (counts == null || drop.activity != "slayer") return drop
        prune(now)
        increased.remove(drop.item)?.takeIf { now - it.at in 0..3000 }?.let {
            return drop.copy(rarity = it.metadata?.rarity, displayName = it.metadata?.displayName)
        }
        slayerMetadata.putIfAbsent(drop.item, drop)
        return drop
    }

    fun inventory(snapshot: Map<String, Int>, now: Long, observation: String? = null,
                  metadata: Map<String, ObservedItemMetadata> = emptyMap()): List<DetectedRng> {
        val previous = counts ?: run { seed(snapshot); return emptyList() }
        counts = snapshot.toMap()
        prune(now)
        val found = mutableListOf<DetectedRng>()
        snapshot.forEach { (item, count) ->
            if (item !in DigestRngParser.catalog || count <= (previous[item] ?: 0)) return@forEach
            val observed = metadata[item]?.let { observedItemMetadata(it.rarity, it.displayName) }
            if (DigestRngCatalog.valid(item, "slayer")) {
                val drop = slayerMetadata.remove(item)
                if (drop != null && observed != null) found += drop.copy(rarity = observed.rarity, displayName = observed.displayName)
                else increased[item] = InventoryIncrease(now, null, observed)
                return@forEach
            }
            val drop = pending.remove(item)
            val confirmation = observation?.let { "$it/$item" }
            if (drop != null) found += drop.copy(confirmation = confirmation, rarity = observed?.rarity, displayName = observed?.displayName)
            else increased[item] = InventoryIncrease(now, confirmation, observed)
        }
        return found
    }

    private fun prune(now: Long) {
        pending.entries.removeIf { now - it.value.occurredAt !in 0..15_000 }
        increased.entries.removeIf { now - it.value.at !in 0..3000 }
        slayerMetadata.entries.removeIf { now - it.value.occurredAt !in 0..15_000 }
    }
}

object DigestRng : SkyMyceModule() {
    var detected: ((DetectedRng) -> Unit)? = null
    var contextReady: () -> Boolean = { AcquisitionRepository.currentContext() != null }
    private val recent = linkedMapOf<String, Long>()
    private val confirmedReceipts = linkedSetOf<String>()
    private val acquisition = DigestDungeonAcquisition()
    private var inventoryCheck: ScheduledFuture<*>? = null
    private var context = 0L
    private var rareSlots = emptySet<Int>()
    private var inventoryContext: me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext? = null
    private var inventoryWorld: Any? = null
    fun diagnostics(): Map<String, Long> = mapOf("digest.inventory_task" to if (inventoryCheck?.isDone == false) 1L else 0L,
        "digest.recent_drops" to recent.size.toLong(), "digest.rare_slots" to rareSlots.size.toLong())

    fun clearContext() {
        recent.clear()
        acquisition.clear()
        rareSlots = emptySet()
        inventoryContext = null
        inventoryWorld = null
        context++
        inventoryCheck?.cancel(false)
        inventoryCheck = null
    }

    /** Called once after the account/profile is ready, before accepting any announcements. */
    fun contextChanged() { ensureContext() }

    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class) fun resetAcquisitionContext() { clearContext() }
    @Subscription(TickEvent::class) fun observeContext() { ensureContext() }
    private fun ensureContext(): Boolean {
        val current = AcquisitionRepository.currentContext()
        val world = MC.instance.level
        if (current == null || world == null || !contextReady()) {
            if (!LocationAPI.isOnSkyBlock && inventoryContext != null) clearContext()
            return false
        }
        if (inventoryContext?.matches(current) != true || inventoryWorld !== world) {
            clearContext()
            inventoryContext = current
            inventoryWorld = world
            acquisition.seed(inventoryCounts())
        }
        return true
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock || !ensureContext()) return
        val now = System.currentTimeMillis()
        val drop = DigestRngParser.detect(event.text, MC.instance.user.name, LocationAPI.island?.name.orEmpty(), now) ?: return
        if (drop.activity == "dungeon") acquisition.announce(drop, now)?.let(::publish)
        else publish(acquisition.annotateSlayer(drop, now))
    }

    @Subscription fun onInventory(event: PlayerInventoryChangeEvent) {
        if (!LocationAPI.isOnSkyBlock || inventoryCheck != null) return
        val digestReady = ensureContext()
        val trackerWaiting = DungeonTracker.awaitsInventory()
        if (!digestReady && !trackerWaiting) return
        if (!trackerWaiting && canonicalItemId(event.item.getSkyBlockId()?.id.orEmpty()) !in DigestRngParser.catalog && event.inventorySlot.containerSlot !in rareSlots) return
        requestInventoryObservation()
    }

    @Subscription fun onPacket(event: PacketReceivedEvent) {
        if (event.packet !is ClientboundSetPlayerInventoryPacket) return
        // This packet writes Inventory directly, without the API's Slot.set event.
        MC.instance.execute { requestInventoryObservation() }
    }

    /** Read after packet handling; a matching reward header can also request this once. */
    fun requestInventoryObservation() {
        if (!LocationAPI.isOnSkyBlock || inventoryCheck != null || !ensureContext() && !DungeonTracker.awaitsInventory()) return
        val token = context
        // Coalesce the source/destination updates of item moves before comparing total counts.
        inventoryCheck = Scheduling.schedule(200.milliseconds) { MC.instance.execute {
            if (token == context) inventoryCheck = null
            val digestCurrent = token == context && contextReady()
            if (digestCurrent || DungeonTracker.awaitsInventory()) {
                val snapshot = inventorySnapshot(); val now = System.currentTimeMillis(); val observation = java.util.UUID.randomUUID().toString()
                DungeonTracker.inventoryObserved(snapshot.counts, now, observation, snapshot.metadata)
                if (digestCurrent) acquisition.inventory(snapshot.counts, now, observation, snapshot.metadata).forEach {
                    // Late slayer metadata enriches the same archive ID, without replaying Digest or public publication.
                    if (it.activity == "slayer") AcquisitionRepository.enrichConfirmed(it) else publish(it)
                }
            }
        } }
    }

    private data class ObservedInventory(val counts: Map<String, Int>, val metadata: Map<String, ObservedItemMetadata>)
    fun inventoryCounts(): Map<String, Int> = inventorySnapshot().counts
    private fun inventorySnapshot(): ObservedInventory {
        val inventory = MC.instance.player?.inventoryMenu ?: return ObservedInventory(emptyMap(), emptyMap())
        val counts = mutableMapOf<String, Int>()
        val metadata = mutableMapOf<String, MutableList<ObservedItemMetadata?>>()
        val slots = mutableSetOf<Int>()
        for (slot in inventory.slots) {
            if (slot.container !is Inventory) continue
            val item = slot.item
            val id = item.getSkyBlockId()?.id?.let(::canonicalItemId) ?: continue
            counts[id] = (counts[id] ?: 0) + item.count
            // Container-menu indexes shift while a chest is open; the backing player inventory index stays stable.
            if (id in DigestRngParser.catalog) {
                slots += slot.containerSlot
                metadata.getOrPut(id) { mutableListOf() }.add(observedItemMetadata(item))
            }
        }
        rareSlots = slots
        return ObservedInventory(counts, metadata.mapNotNull { (id, variants) -> consistentItemMetadata(variants)?.let { id to it } }.toMap())
    }

    private fun publish(observed: DetectedRng) {
        val drop = confirmedRng(observed)
        val now = System.currentTimeMillis()
        if (drop.confirmation != null) {
            if (!confirmedReceipts.add(drop.id)) return
            if (confirmedReceipts.size > 10000) confirmedReceipts.remove(confirmedReceipts.first())
        }
        recent.entries.removeIf { it.value + 3000 < now }
        val key = drop.confirmation ?: "${drop.activity}|${drop.item}"
        if (key in recent) return
        recent[key] = now
        AcquisitionRepository.confirmed(drop)
        me.mycellium.skymyce.utils.Utils.displayDevMessage { "RNG acquisition confirmed locally: activity=${drop.activity}, inventoryReceipt=${drop.confirmation != null}" }
        detected?.invoke(drop)
    }
    /** Trusted in-process complete chest receipt; preview/control events never invoke this boundary. */
    internal fun receiptConfirmed(drop: DetectedRng) { if (AcquisitionRepository.currentContext() != null) publish(drop) }
}
