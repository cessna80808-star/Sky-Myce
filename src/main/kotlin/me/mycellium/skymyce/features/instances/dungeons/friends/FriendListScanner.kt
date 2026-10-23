package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import tech.thatgravyboat.skyblockapi.utils.text.TextUtils.splitLines

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
