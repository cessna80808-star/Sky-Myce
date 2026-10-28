package me.mycellium.skymyce.features.digest

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.DailyDigestConfig
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.validObservedItemMetadata
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.location.SkyBlockLocationEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling

/** Client-thread coordinator. All file work runs on SBAPI's scheduler; the menu reads immutable snapshots. */
object DailyDigest : SkyMyceModule() {
    override fun onDisable() { wake?.cancel(false); wake = null }
    private val gate = DigestDailyGate()
    private val profiles = linkedMapOf<String, DigestProfileState>()
    private val history = DigestRngHistory()
    private var loaded = false
    private var storageStatus = "Loading local cache…"
    private var profileKey: String? = null
    private var contextLabel = "Waiting for SkyBlock profile"
    private var savedGlobal: GlobalDigest? = null
    private var snapshot = DigestView()
    private var revision = 0L
    private var acquisitionRevision = -1L
    private var acquisitionContext: AcquisitionContext? = null
    private var nextStateChange = Long.MAX_VALUE
    private var wake: ScheduledFuture<*>? = null
    private var generation = 0L
    private var joinedAt = 0L
    private var open = false
    private var subscribed = false
    private var pendingSubscription: CompletableFuture<JsonObject?>? = null
    private var rngStatus = "Community feed is off"
    private var rngRetryAt = 0L
    private lateinit var writer: DigestStateWriter
    private val news = DigestNewsRepository(
        ::requestDigestNews,
        { MC.instance.execute(it) }, { changed() },
    )

    override fun init() {
        val store = DigestStateStore(SkyMyce.configPath.resolve("daily_digest.json"))
        writer = DigestStateWriter(store, { write -> Scheduling.schedule(500.milliseconds) {
            check(!MC.instance.isSameThread)
            write()
        } }, { error ->
            if (error != null) SkyMyce.logger.warn("[Daily Digest] Local state could not be saved; prior data retained")
            MC.instance.execute {
                if (error != null) storageStatus = "Local changes could not be saved"
                else if (storageStatus == "Local changes could not be saved") storageStatus = ""
                rebuildView()
            }
        })
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { writer.flush() }.onFailure { SkyMyce.logger.warn("[Daily Digest] Final local state could not be saved") }
        }, "Sky-Hawk-Digest-Save"))
        DigestActivities.contextReady = ::contextReady
        DigestActivities.changed = { changed() }
        DigestRng.detected = ::recordDrop
        DungeonFriendRelay.onDigestEvent = { json ->
            if (json.get("type")?.asString == "rng_removed") {
                val ids = json.getAsJsonArray("ids")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                    ?.filter { it.matches(digestRngId) }?.take(256)?.toSet().orEmpty()
                removeCommunityReports(ids)
            } else if (DailyDigestConfig.receiveRng && contextReady() && subscribed)
                receiveEvents(listOfNotNull(json.get("event")?.takeIf { it.isJsonObject }?.asJsonObject))
        }
        DungeonFriendRelay.onDigestClosed = {
            subscribed = false
            rngStatus = if (DailyDigestConfig.receiveRng) "Community feed offline · saved history retained" else "Community feed is off"
            rebuildView()
        }
        DungeonFriendRelay.onDigestReady = {
            removeCommunityReports()
            subscribed = false
            rngRetryAt = 0
            if (LocationAPI.isOnSkyBlock) {
                updateSubscription()
                if (open) refresh()
            }
        }
        // Menu removal provides a retry opportunity without a global high-frequency polling loop.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.remove(screen).register { if (LocationAPI.isOnSkyBlock) scheduleWake(300) }
        }
        Scheduling.schedule(0.seconds) {
            check(!MC.instance.isSameThread)
            val saved = store.load()
            MC.instance.execute {
                saved.lastOpened.forEach { (account, date) -> gate.opened(account, java.time.LocalDate.parse(date)) }
                // Community history is revalidated by the authoritative feed on each connection; personal history stays local.
                profiles.putAll(saved.profiles.mapValues { (_, profile) -> profile.copy(history = profile.history.filter { !it.community }) })
                saved.news.forEach { (source, state) -> NewsSource.entries.find { it.wire == source }?.let { news.restore(it, state) } }
                savedGlobal = saved.global
                saved.global?.let(DigestActivities::restoreGlobal)
                loaded = true
                storageStatus = if (store.recovered) "Recovered damaged local cache; original will be preserved" else ""
                synchronizeContext()
                changed()
                if (LocationAPI.isOnSkyBlock) scheduleWake(300)
            }
        }
    }

    @Subscription
    fun onSkyBlockJoin(event: SkyBlockLocationEvent.Join) {
        joinedAt = System.currentTimeMillis()
        gate.enter(joinedAt, DailyDigestConfig.openingDelay.coerceIn(3, 30) * 1000L)
        synchronizeContext()
        scheduleWake(DailyDigestConfig.openingDelay.coerceIn(3, 30) * 1000L)
    }

    @Subscription
    fun onProfile(event: ProfileChangeEvent) {
        preserveProfile()
        profileKey = null
        contextLabel = "Waiting for SkyBlock profile"
        resetSubscription()
        history.clear()
        DigestActivities.clearContext()
        DigestRng.clearContext()
        generation++
        if (LocationAPI.isOnSkyBlock) {
            joinedAt = System.currentTimeMillis()
            gate.enter(joinedAt, DailyDigestConfig.openingDelay.coerceIn(3, 30) * 1000L)
            scheduleWake(DailyDigestConfig.openingDelay.coerceIn(3, 30) * 1000L)
        }
        changed()
    }

    @Subscription(SkyBlockLocationEvent.Leave::class, ServerDisconnectEvent::class)
    fun onLeave() {
        preserveProfile()
        profileKey = null
        contextLabel = "Join SkyBlock to view your profile"
        history.clear()
        DigestActivities.clearContext()
        DigestRng.clearContext()
        gate.leave()
        generation++
        wake?.cancel(false)
        wake = null
        news.cancel()
        resetSubscription()
        if (MC.instance.screen is DailyDigestScreen) MC.instance.setScreen(null)
        changed()
    }

    private fun contextReady(): Boolean = isEnabled() && loaded && LocationAPI.isOnSkyBlock && MC.instance.player != null &&
        currentProfileKey() == profileKey && profileKey != null

    private fun currentProfileKey(): String? = me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository.currentContext()
        ?.let { verified -> verified.profileName?.let { DigestDailyGate.profileKey(verified.account, it, verified.server == "alpha") } }

    private fun synchronizeContext() {
        if (!loaded) return
        val key = currentProfileKey() ?: return
        if (profileKey == key) return
        preserveProfile()
        generation++
        resetSubscription()
        profileKey = key
        contextLabel = "${MC.instance.user.name} · ${ProfileAPI.profileName}${if (LocationAPI.onAlpha) " · Alpha" else ""}"
        val saved = profiles[key] ?: DigestProfileState()
        DigestActivities.restore(saved.tasks)
        history.restore(saved.history, DailyDigestConfig.retainedRngEvents, System.currentTimeMillis())
        DigestRng.contextChanged()
        DigestActivities.captureGlobal()
        updateSubscription()
        changed()
    }

    private fun scheduleWake(delay: Long) {
        wake?.cancel(false)
        if (!isEnabled() || !LocationAPI.isOnSkyBlock) return
        wake = Scheduling.schedule(delay.coerceAtLeast(100).milliseconds) { MC.instance.execute { autoOpen() } }
    }

    private fun autoOpen() {
        wake = null
        if (!isEnabled() || !LocationAPI.isOnSkyBlock || MC.instance.player == null) return
        synchronizeContext()
        val now = System.currentTimeMillis()
        if (MC.instance.screen is DailyDigestScreen) {
            gate.opened(MC.instance.user.profileId.toString(), DigestDailyGate.localDate(now))
            persist()
        }
        if (gate.shouldOpen(MC.instance.user.profileId.toString(), now, DigestDailyGate.localDate(now),
                DailyDigestConfig.automatic, loaded, contextReady(), MC.instance.screen == null)) open()
        // Briefly tolerate delayed profile packets; later profile/menu events retry without continuous polling.
        if ((!loaded || !contextReady() || now < joinedAt + DailyDigestConfig.openingDelay * 1000L) && now - joinedAt < 60000)
            scheduleWake(2000)
        else scheduleWake(DigestDailyGate.nextMidnight(now) - now + 200)
    }

    fun open() {
        if (!isEnabled()) return
        if (!LocationAPI.isOnSkyBlock || MC.instance.player == null) {
            MC.instance.player?.sendSystemMessage(Component.literal("The Daily Digest is available after joining SkyBlock."))
            return
        }
        synchronizeContext()
        rebuildView()
        me.mycellium.skymyce.ui.navigateMenu(DailyDigestScreen())
    }

    fun screenOpened() {
        open = true
        if (LocationAPI.isOnSkyBlock && MC.instance.player != null) {
            gate.opened(MC.instance.user.profileId.toString(), DigestDailyGate.localDate(System.currentTimeMillis()))
            refresh()
            persist()
        }
    }

    fun screenClosed() { open = false; news.cancel(); rebuildView() }

    fun refresh(section: String? = null) {
        if (!LocationAPI.isOnSkyBlock) return
        synchronizeContext()
        if (section == null || section == "news") news.refresh(enabledNews(), force = section == "news")
        if (section == null || section == "global" || section == "dailies") DigestActivities.captureGlobal()
        if (section == null || section == "rng") updateSubscription()
        changed()
    }

    fun settingsChanged() {
        if (!DailyDigestConfig.news) news.cancel()
        history.restore(history.entries, DailyDigestConfig.retainedRngEvents, System.currentTimeMillis())
        updateSubscription()
        refresh()
        if (LocationAPI.isOnSkyBlock) scheduleWake(300)
    }

    fun setManual(id: String, complete: Boolean) {
        if (contextReady()) { DigestActivities.manual(id, complete); changed() }
    }

    private fun enabledNews(): List<NewsSource> = if (DailyDigestConfig.news) NewsSource.entries else emptyList()

    private fun updateSubscription() {
        val wanted = DailyDigestConfig.receiveRng && contextReady()
        if (!wanted) {
            resetSubscription()
            rngStatus = if (DailyDigestConfig.receiveRng) "Waiting for SkyBlock profile" else "Community feed is off"
            return
        }
        if (subscribed || System.currentTimeMillis() < rngRetryAt) return
        if (!DungeonFriendRelay.connected || !DungeonFriendRelay.rngFeedAvailable) {
            rngStatus = if (DungeonFriendRelay.connected) "Community service not available yet" else "Community feed offline"
            return
        }
        val request = DungeonFriendRelay.digestRequest("rng_subscribe", mapOf("enabled" to true)) ?: return
        pendingSubscription = request
        subscribed = true
        rngStatus = "Connecting to community feed…"
        val token = generation
        request.whenComplete { response, _ -> MC.instance.execute {
            if (pendingSubscription !== request || token != generation || !DailyDigestConfig.receiveRng || !contextReady()) return@execute
            pendingSubscription = null
            runCatching {
                require(response != null && !response.has("error"))
                val events = parseDigestCommunityHistory(response.get("events"), System.currentTimeMillis())
                removeCommunityReports()
                receiveEntries(events)
                rngStatus = if (events.isEmpty()) "Live · no recent community reports" else "Live · community reports are unverified"
            }.onFailure {
                DungeonFriendRelay.disableRngFeed()
                subscribed = false
                rngRetryAt = System.currentTimeMillis() + 60000
                rngStatus = "Community feed unavailable; cached history retained"
            }
            changed()
        } }
    }

    private fun resetSubscription() {
        if (subscribed) DungeonFriendRelay.disableRngFeed()
        subscribed = false
        val old = pendingSubscription
        pendingSubscription = null
        old?.cancel(false)
        rngRetryAt = 0
    }

    private fun receiveEvents(events: List<JsonObject>) {
        receiveEntries(events.take(30).mapNotNull { parseDigestCommunityEvent(it, System.currentTimeMillis()) })
    }

    private fun receiveEntries(events: List<DigestRngEntry>) {
        if (!DailyDigestConfig.receiveRng || !contextReady()) return
        var added = false
        events.take(30).forEach { entry ->
            added = history.add(entry, DailyDigestConfig.retainedRngEvents, System.currentTimeMillis()) || added
        }
        if (added) { rngStatus = "Live · community reports are unverified"; changed() }
    }

    private fun removeCommunityReports(ids: Set<String>? = null) {
        history.removeCommunity(ids)
        profiles.replaceAll { _, profile -> profile.copy(history = profile.history.filter { !it.community || ids != null && it.id !in ids }) }
        if (loaded) changed()
    }

    private fun recordDrop(drop: DetectedRng) {
        if (!contextReady()) return
        val entry = DigestRngEntry(drop.id, drop.item, drop.activity, drop.occurredAt, MC.instance.user.name)
        if (!history.add(entry, DailyDigestConfig.retainedRngEvents, System.currentTimeMillis())) return
        changed()
        // Only the live detected event is eligible. Enabling sharing never uploads saved history.
        val submission = digestRngSubmission(drop, me.mycellium.skymyce.community.CommunitySharing.canShare(me.mycellium.skymyce.community.SharingCategory.RNG),
            me.mycellium.skymyce.community.CommunitySharing.showPublicName(), System.currentTimeMillis(),
            AcquisitionRepository.state.events.firstOrNull { it.id == drop.id }, AcquisitionRepository.currentContext()) ?: return
        val request = DungeonFriendRelay.digestRequest("rng_publish", submission)
        if (request == null) { rngStatus = "Drop saved locally; sharing is unavailable"; rebuildView() }
        else {
            val token = generation
            request.whenComplete { response, _ -> MC.instance.execute {
                if (token != generation || !contextReady()) return@execute
                if (response == null || response.has("error")) {
                    rngStatus = if (response?.get("error")?.asString == "rate_limited")
                        "Drop saved locally; sharing limit reached" else "Drop saved locally; sharing is unavailable"
                    rebuildView()
                }
            } }
        }
    }

    private fun preserveProfile() {
        val key = profileKey ?: return
        profiles[key] = DigestProfileState(DigestActivities.snapshot(), history.entries)
        while (profiles.size > 64) profiles.remove(profiles.keys.first())
    }

    private fun changed() { rebuildView(); persist() }
    fun diagnostics(): Map<String, Long> = mapOf("digest.profiles" to profiles.size.toLong(),
        "digest.wake" to if (wake?.isDone == false) 1L else 0L, "digest.subscription_request" to if (pendingSubscription != null) 1L else 0L,
        "digest.news_requests" to news.pendingCount.toLong(), "digest.write_task" to if (::writer.isInitialized && writer.scheduled) 1L else 0L)

    private fun persist() {
        if (!loaded) return
        preserveProfile()
        val global = DigestActivities.global().takeIf { it.updatedAt > 0 } ?: savedGlobal
        val state = DigestSavedState(lastOpened = gate.lastOpened.toMap(), profiles = profiles.toMap(),
            news = NewsSource.entries.associate { it.wire to news.state(it).copy(loading = false) }, global = global)
        writer.submit(state)
    }

    fun view(): DigestView {
        if (System.currentTimeMillis() >= nextStateChange || acquisitionRevision != AcquisitionRepository.revision ||
            acquisitionContext != AcquisitionRepository.currentContext()) rebuildView()
        return snapshot
    }

    private fun rebuildView() {
        val now = System.currentTimeMillis()
        acquisitionRevision = AcquisitionRepository.revision
        acquisitionContext = AcquisitionRepository.currentContext()
        val cards = listOf(
            runCatching { newsCard(now) }.getOrElse { failedCard("news", "Daily News") },
            runCatching { globalCard(now) }.getOrElse { failedCard("global", "Global Status") },
            runCatching { tasksCard() }.getOrElse { failedCard("dailies", "Personal Dailies") },
            runCatching { rngCard() }.getOrElse { failedCard("rng", "RNG Activity") },
        )
        nextStateChange = cards.flatMap { it.entries }.mapNotNull { it.until }.filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        snapshot = DigestView(contextLabel, storageStatus, cards, ++revision)
    }

    private fun failedCard(id: String, title: String) = DigestCardState(id, title, "Temporarily unavailable", "Other cards are still available", DigestTone.ATTENTION)

    private fun newsCard(now: Long): DigestCardState {
        val sources = enabledNews()
        val states = sources.map(news::state)
        val items = states.flatMap { it.items }.sortedByDescending { it.publishedAt }
        val failure = sources.zip(states).firstNotNullOfOrNull { (source, state) -> state.failure?.let { "${source.label}: ${it.label}" } }
        val entries = items.map { item -> DigestEntry("${item.source.wire}:${item.id}", item.title,
            item.content.replace('\n', ' ').take(160), item.content + if (item.truncated) "\n\nOpen the original update for the remaining text." else "",
            if (item.source == NewsSource.DERAILIOUS) DigestTone.SPECIAL else DigestTone.INFO,
            item.source.label + if (item.source == NewsSource.DERAILIOUS) " · YouTube video" else "", item.publishedAt,
            url = item.url, video = item.source == NewsSource.DERAILIOUS,
            links = digestNewsLinks(item.content, item.links).filter { it != item.url }) }
        return DigestCardState("news", "Daily News", items.firstOrNull()?.title ?: if (sources.isEmpty()) "News is disabled" else "No cached updates yet",
            when { sources.isEmpty() -> "Enable news in settings"; failure != null -> "$failure${if (items.isNotEmpty()) " · available updates retained" else ""}";
                states.any { it.loading } -> "Refreshing in the background"; states.any { it.fetchedAt > 0 && now - it.fetchedAt > 300000 } -> "Saved updates · refresh when connected";
                items.isEmpty() -> "Game updates and Derailious videos will appear here"; else -> "${items.size} recent updates and videos" },
            if (failure != null || items.isEmpty()) DigestTone.MUTED else DigestTone.INFO, entries,
            states.maxOfOrNull { it.fetchedAt }?.takeIf { it > 0 }, states.any { it.loading })
    }

    private fun globalCard(now: Long): DigestCardState {
        val live = DigestActivities.global()
        val global = live.takeIf { it.updatedAt > 0 } ?: savedGlobal ?: GlobalDigest(null, emptyList(), emptyList())
        val stale = now - global.updatedAt > 3600000 || !LocationAPI.isOnSkyBlock
        val events = global.events.filter { it.endsAt > now || it.endsAt == 0L && it.startsAt > now }
        val entries = global.perks.mapIndexed { index, perk -> DigestEntry("perk:$index", perk.name, perk.description,
            tone = DigestTone.INFO, meta = "Mayor perk") } + events.mapIndexed { index, event ->
            val active = event.startsAt <= now
            DigestEntry("event:$index", event.name, if (active) "Active now" else "Upcoming event",
                tone = if (active) DigestTone.POSITIVE else DigestTone.SPECIAL, meta = event.source,
                until = if (active) event.endsAt else event.startsAt)
        }
        return DigestCardState("global", "Global Status", global.mayor?.let { "Mayor $it" } ?: "Mayor not available",
            when { global.updatedAt == 0L -> "Waiting for game data"; stale -> "Saved information · may be out of date";
                events.isEmpty() -> "Open Calendar and Events in-game to detect upcoming events"; else -> "${events.size} detected events" },
            if (stale || global.mayor == null) DigestTone.MUTED else DigestTone.INFO, entries, global.updatedAt.takeIf { it > 0 })
    }

    private fun tasksCard(): DigestCardState {
        val tasks = DigestActivities.current()
        val complete = tasks.count { it.state == DailyState.COMPLETE }
        val entries = tasks.map { task -> DigestEntry(task.id, task.title, task.detail,
            "${task.source}${if (task.manual) " · manually noted, not game-verified" else ""}" +
                task.slots.joinToString("") { "\nSlot ${it.index}: ${it.item} · ${it.state.name.lowercase()}" },
            when (task.state) { DailyState.READY, DailyState.COMPLETE -> DigestTone.POSITIVE; DailyState.ACTIVE -> DigestTone.WAITING;
                DailyState.UNAVAILABLE -> DigestTone.ATTENTION; else -> DigestTone.MUTED },
            task.state.name.lowercase().replaceFirstChar(Char::uppercase) + if (task.manual) " · manual" else "",
            task.updatedAt.takeIf { it > 0 }, task.readyAt.takeIf { it > System.currentTimeMillis() },
            manualId = task.id.takeUnless { it == "forge" || it == "mayor" }, manualComplete = task.manual && task.state == DailyState.COMPLETE,
            slots = task.slots.map { slot -> DigestEntry("forge:${slot.index}", "Slot ${slot.index} · ${slot.item}",
                meta = slot.state.name.lowercase().replaceFirstChar(Char::uppercase),
                tone = when (slot.state) { DailyState.COMPLETE, DailyState.READY -> DigestTone.POSITIVE; DailyState.ACTIVE -> DigestTone.WAITING; else -> DigestTone.MUTED },
                until = slot.readyAt.takeIf { it > 0 }) }) }
        return DigestCardState("dailies", "Personal Dailies", if (contextReady()) "$complete of 6 categories complete" else "Waiting for your profile",
            "Game detections and clearly labelled manual notes", if (complete > 0) DigestTone.POSITIVE else DigestTone.INFO,
            entries, tasks.maxOfOrNull { it.updatedAt }?.takeIf { it > 0 })
    }

    private fun rngCard(): DigestCardState {
        val local = digestLocalRngHistory(AcquisitionRepository.state.events,
            history.entries.takeIf { acquisitionContext == null || currentProfileKey() == profileKey }.orEmpty(),
            acquisitionContext, System.currentTimeMillis())
        val community = history.entries.filter { it.community && DailyDigestConfig.receiveRng }.map { entry ->
            DigestEntry(entry.id, DigestRngCatalog.name(entry.item), "${entry.player} · ${entry.activity.replaceFirstChar(Char::uppercase)}",
                "Community-reported, unverified. Drop, activity and time may include observed rarity, item name, dungeon floor and an optional public name.",
                DigestTone.SPECIAL, "Community · unverified", entry.occurredAt, community = true, activity = entry.activity, item = entry.item)
        }
        val entries = (local + community).sortedWith(compareByDescending<DigestEntry> { it.timestamp }.thenBy { it.id })
        return DigestCardState("rng", "RNG Activity", entries.firstOrNull()?.title ?: "Your next rare find starts here", rngStatus,
            if (entries.isEmpty()) DigestTone.MUTED else DigestTone.SPECIAL, entries, entries.firstOrNull()?.timestamp)
    }
}

/** The digest's local day is independent of Hypixel activity reset times and profile changes. */
class DigestDailyGate {
    val lastOpened = mutableMapOf<String, String>()
    private var joined = false
    private var readyAt = Long.MAX_VALUE

    fun enter(now: Long, delayMillis: Long) {
        joined = true
        readyAt = now + delayMillis.coerceIn(2000, 30000)
    }

    fun leave() { joined = false; readyAt = Long.MAX_VALUE }

    fun shouldOpen(account: String?, now: Long, date: LocalDate, enabled: Boolean,
                   stateLoaded: Boolean, profileReady: Boolean, screenFree: Boolean): Boolean =
        joined && enabled && stateLoaded && profileReady && screenFree && account != null && now >= readyAt &&
            lastOpened[account]?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.let { day -> day >= date } } != true

    fun opened(account: String, date: LocalDate) {
        val previous = lastOpened[account]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (previous == null || date > previous) lastOpened[account] = date.toString()
        while (lastOpened.size > 64) lastOpened.remove(lastOpened.minBy { it.value }.key)
    }

    companion object {
        fun localDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun nextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            localDate(now, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        fun profileKey(account: String, profile: String, alpha: Boolean): String =
            "$account/${if (alpha) "alpha" else "main"}/${profile.trim().lowercase()}"
    }
}

data class DigestRngEntry(val id: String, val item: String, val activity: String, val occurredAt: Long,
                          val player: String, val community: Boolean = false)
data class DigestProfileState(val tasks: List<DailyTask> = emptyList(), val history: List<DigestRngEntry> = emptyList())
data class DigestSavedState(val version: Int = 1, val lastOpened: Map<String, String> = emptyMap(),
                           val profiles: Map<String, DigestProfileState> = emptyMap(),
                           val news: Map<String, DigestNewsState> = emptyMap(), val global: GlobalDigest? = null)

/** Pure file store: caller must use the existing background scheduler. A damaged original is backed up before replacement. */
class DigestStateStore(private val file: Path) {
    private val gson = Gson()
    var recovered = false
        private set

    fun load(): DigestSavedState = try {
        if (!Files.exists(file)) DigestSavedState() else {
            require(Files.size(file) <= 8 * 1024 * 1024)
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            require(json.get("version")?.asInt == 1)
            // Retired news must not invalidate otherwise valid local profiles/history or become normal news.
            json.getAsJsonObject("news")?.remove("alpha")
            json.getAsJsonObject("news")?.entrySet()?.forEach { (_, news) ->
                news.asJsonObject.getAsJsonArray("items")?.forEach { item ->
                    if (!item.asJsonObject.has("links")) item.asJsonObject.add("links", JsonArray())
                }
            }
            gson.fromJson(json, DigestSavedState::class.java).also(::validate)
        }
    } catch (_: Exception) {
        recovered = true
        DigestSavedState()
    }

    @Synchronized fun save(state: DigestSavedState) {
        validate(state)
        Files.createDirectories(file.parent)
        if (recovered && Files.exists(file)) {
            Files.copy(file, file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}"))
            recovered = false
        }
        val temporary = Files.createTempFile(file.parent, "digest-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(state, it) }
            require(Files.size(temporary) <= 8 * 1024 * 1024)
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun validate(state: DigestSavedState) {
        require(state.version == 1 && requireNotNull(state.lastOpened).size <= 64 && requireNotNull(state.profiles).size <= 64)
        state.lastOpened.forEach { (account, date) -> require(account.matches(digestAccount)); LocalDate.parse(date) }
        state.profiles.forEach { (key, profile) ->
            require(key.length in 1..128 && key.none(Char::isISOControl))
            require(requireNotNull(profile.tasks).size <= 6 && requireNotNull(profile.history).size <= 530)
            profile.tasks.forEach { task ->
                require(requireNotNull(task.id).length in 1..32 && requireNotNull(task.title).length <= 100)
                require(task.state in DailyState.entries && requireNotNull(task.detail).length <= 1000 && requireNotNull(task.source).length <= 200)
                require(task.updatedAt >= 0 && task.readyAt >= 0 && task.progress >= 0 && task.total in 0..100)
                require(requireNotNull(task.slots).size <= 7)
                task.slots.forEach { require(it.index in 0..7 && requireNotNull(it.item).length <= 160 && it.state in DailyState.entries && it.readyAt >= 0) }
            }
            profile.history.forEach { require(validDigestRng(it, Long.MAX_VALUE / 2)) }
        }
        require(requireNotNull(state.news).size <= NewsSource.entries.size)
        state.news.forEach { (source, news) ->
            require(source in NewsSource.entries.map { it.wire } && news.fetchedAt >= 0 && news.retryAt >= 0)
            require(requireNotNull(news.items).size <= 8)
            news.items.forEach { item ->
                require(item.source.wire == source && requireNotNull(item.id).length in 1..80 && requireNotNull(item.title).length in 1..160)
                require(requireNotNull(item.content).length <= 2500 && item.publishedAt > 0 && (item.url == null || safeDigestNewsUrl(item.url, item.source) != null))
                require(item.thumbnailUrl == null || item.source == NewsSource.DERAILIOUS && safeDigestVideoUrl(item.thumbnailUrl, true) != null)
                require(requireNotNull(item.links).size <= 8 && item.links.distinct().size == item.links.size)
                require(item.links.sumOf { it.length } <= 1024)
                require(item.links.all { BrowserLinks.trusted(it) != null })
            }
        }
        state.global?.let { global ->
            require(global.mayor == null || global.mayor.length <= 100)
            require(global.updatedAt >= 0 && requireNotNull(global.perks).size <= 16 && requireNotNull(global.events).size <= 32)
            global.perks.forEach { require(requireNotNull(it.name).length <= 160 && requireNotNull(it.description).length <= 2000) }
            global.events.forEach { require(requireNotNull(it.name).length <= 160 && it.startsAt >= 0 && (it.endsAt == 0L || it.endsAt >= it.startsAt) && requireNotNull(it.source).length <= 200) }
        }
    }
}

// Compiled once: validation runs per history entry on every add, restore and save.
internal val digestRngId = Regex("[a-f0-9]{32}")
private val digestRngPlayer = Regex("[A-Za-z0-9_]{1,16}")
private val digestRngActivities = setOf("dungeon", "slayer", "kuudra", "fishing")
private val digestRngFloor = Regex("E|[FM][1-7]")
private val digestAccount = Regex("[a-f0-9-]{32,36}")

internal fun validDigestRng(entry: DigestRngEntry, now: Long): Boolean = runCatching {
    entry.id.matches(digestRngId) && entry.item.length in 1..80 && entry.activity in digestRngActivities &&
        entry.occurredAt in 1..now + 60000 && entry.player.matches(digestRngPlayer) &&
        DigestRngCatalog.valid(entry.item, entry.activity)
}.getOrDefault(false)

/** Immutable bounded local history; received events never become publishable personal events. */
class DigestRngHistory {
    private val removedCommunity = linkedSetOf<String>()
    var entries: List<DigestRngEntry> = emptyList()
        private set
    fun restore(saved: List<DigestRngEntry>, limit: Int, now: Long) {
        val valid = saved.filter { validDigestRng(it, now) && (!it.community || it.id !in removedCommunity) }.distinctBy { it.id }.sortedByDescending { it.occurredAt }
        entries = (valid.filter { !it.community }.take(limit.coerceIn(20, 500)) + valid.filter { it.community }.take(30))
            .sortedByDescending { it.occurredAt }
    }
    fun add(entry: DigestRngEntry, limit: Int, now: Long): Boolean {
        if (!validDigestRng(entry, now) || entry.community && entry.id in removedCommunity || entries.any { it.id == entry.id }) return false
        restore(entries + entry, limit, now)
        return true
    }
    fun clear() { entries = emptyList() }
    fun removeCommunity(ids: Set<String>? = null) {
        if (ids != null) {
            removedCommunity.addAll(ids)
            while (removedCommunity.size > 10000) removedCommunity.remove(removedCommunity.first())
        }
        entries = entries.filter { !it.community || ids != null && it.id !in ids }
    }
}

/** One pending snapshot. submit never waits for disk; flush runs only on a background shutdown thread. */
class DigestStateWriter(private val store: DigestStateStore, private val schedule: (() -> Unit) -> Unit,
                        private val result: (Exception?) -> Unit = {}) {
    private val latest = AtomicReference<DigestSavedState?>()
    private val written = AtomicReference<DigestSavedState?>()
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val ioLock = Any()
    val scheduled get() = running.get()

    fun submit(state: DigestSavedState) {
        latest.set(state)
        start()
    }

    private fun start() {
        if (closed.get() || written.get() == latest.get() || !running.compareAndSet(false, true)) return
        schedule {
            var attempted: DigestSavedState? = null
            try {
                synchronized(ioLock) {
                    if (!closed.get()) {
                        attempted = latest.get()
                        attempted?.let { store.save(it); written.set(it) }
                    }
                }
                result(null)
            } catch (error: Exception) { result(error) }
            finally {
                running.set(false)
                // Coalesce changes arriving during a write; a failed write retries only on a later submit.
                if (latest.get() != attempted && !closed.get()) start()
            }
        }
    }

    fun flush() = synchronized(ioLock) {
        closed.set(true)
        latest.get()?.takeIf { it != written.get() }?.let { store.save(it); written.set(it) }
    }
}

internal fun digestRngSubmission(drop: DetectedRng, sharing: Boolean, showName: Boolean, now: Long,
    acquisition: AcquisitionEvent? = null, context: AcquisitionContext? = null): Map<String, Any>? {
    if (!sharing || !drop.id.matches(digestRngId) || !DigestRngCatalog.public(drop.item, drop.activity) ||
        drop.occurredAt <= 0 || drop.occurredAt <= now - 300000 || drop.occurredAt > now + 60000) return null
    // Only this live receipt can supply context. Old history and another profile's matching item are not metadata sources.
    val confirmed = acquisition?.takeIf { it.id == drop.id && it.item == drop.item && it.activity == drop.activity &&
        it.origin == "confirmed" && context?.matches(it.context) == true &&
        kotlin.math.abs(it.occurredAt - drop.occurredAt) <= 15000 }
    val metadata = observedItemMetadata(confirmed?.rarity ?: drop.rarity, confirmed?.displayName ?: drop.displayName)
    val floor = confirmed?.floor?.takeIf { drop.activity == "dungeon" && it.matches(digestRngFloor) }
    val event = buildMap<String, Any> {
        put("id", drop.id); put("item", drop.item); put("activity", drop.activity); put("occurredAt", drop.occurredAt)
        metadata?.rarity?.let { put("rarity", it) }
        metadata?.displayName?.let { put("displayName", it) }
        floor?.let { put("floor", it) }
    }
    return mapOf("event" to event, "showName" to showName)
}

internal fun parseDigestCommunityEvent(json: com.google.gson.JsonObject, now: Long): DigestRngEntry? = runCatching {
    require(json.get("unverified")?.asBoolean == true)
    require(json.keySet().all { it in setOf("id", "item", "activity", "occurredAt", "player", "unverified", "rarity", "displayName", "floor") })
    fun optionalText(key: String): String? = json[key]?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
    }
    require(validObservedItemMetadata(optionalText("rarity"), optionalText("displayName")))
    val floor = optionalText("floor")
    require(floor == null || json["activity"]?.asString == "dungeon" && floor.matches(digestRngFloor))
    DigestRngEntry(json.get("id").asString, json.get("item").asString, json.get("activity").asString,
        json.get("occurredAt").asLong, json.get("player").asString, true).also {
        require(it.occurredAt >= now - 7 * 86400000L && validDigestRng(it, now))
    }
}.getOrNull()

/** A malformed report cannot hide healthy siblings; a malformed or oversized envelope still fails closed. */
internal fun parseDigestCommunityHistory(json: com.google.gson.JsonElement?, now: Long): List<DigestRngEntry> {
    require(json?.isJsonArray == true)
    val events = requireNotNull(json).asJsonArray
    require(events.size() <= 30)
    return events.mapNotNull { event -> event.takeIf { it.isJsonObject }?.asJsonObject?.let { parseDigestCommunityEvent(it, now) } }
        .distinctBy { it.id }
}

/** Immutable presentation data. The screen only reads these snapshots; providers own all I/O. */
enum class DigestTone { POSITIVE, WAITING, ATTENTION, INFO, SPECIAL, MUTED }

data class DigestEntry(
    val id: String,
    val title: String,
    val summary: String = "",
    val detail: String = "",
    val tone: DigestTone = DigestTone.MUTED,
    val meta: String = "",
    val timestamp: Long? = null,
    val until: Long? = null,
    val url: String? = null,
    val manualId: String? = null,
    val manualComplete: Boolean = false,
    val community: Boolean = false,
    val activity: String = "",
    val slots: List<DigestEntry> = emptyList(),
    val video: Boolean = false,
    val links: List<String> = emptyList(),
    /** SkyBlock item id for an item icon (RNG drops); empty for none. */
    val item: String = "",
)

data class DigestCardState(
    val id: String,
    val title: String,
    val summary: String,
    val status: String = "",
    val tone: DigestTone = DigestTone.MUTED,
    val entries: List<DigestEntry> = emptyList(),
    val updatedAt: Long? = null,
    val loading: Boolean = false,
)

data class DigestView(
    val context: String = "Waiting for SkyBlock profile",
    val status: String = "",
    val cards: List<DigestCardState> = emptyList(),
    val revision: Long = 0,
)

/** Plain bounded report text only; never invent a public URL or dispatch a chat message here. */
fun digestShareText(entry: DigestEntry): String {
    val suffix = if (entry.community) " [unverified]" else ""
    return "[Sky-Hawk] ${entry.title} • ${entry.summary} (${entry.activity})"
        .replace(Regex("§."), "").filter { it >= ' ' && it != '\u007f' }.take(240 - suffix.length) + suffix
}

/** Logical GUI pixels, so Minecraft's selected GUI scale applies without another transform. */
data class DigestLayout(val width: Int, val height: Int, val padding: Int, val gap: Int, val columns: Int) {
    val contentWidth get() = (width - padding * 2 - 4).coerceAtLeast(1)
    val cardWidth get() = ((contentWidth - gap * (columns - 1)) / columns).coerceAtLeast(1)

    companion object {
        fun fit(width: Int, height: Int, compact: Boolean = false): DigestLayout {
            val margin = if (width < 380 || height < 240) 6 else 12
            val panelWidth = (width - margin * 2).coerceIn(1, 760)
            val dense = compact || height < 300
            return DigestLayout(panelWidth, (height - margin * 2).coerceIn(1, 490),
                if (dense || panelWidth < 400) 8 else 14, if (dense) 7 else 10,
                if (panelWidth >= if (dense) 400 else 560) 2 else 1)
        }
    }
}

fun digestText(value: String, limit: Int = 240): String = value
    .filter { it >= ' ' && it != '§' || it == '\n' }.take(limit)

fun digestTimeRemaining(until: Long, now: Long): String {
    val seconds = ((until - now).coerceAtLeast(0) + 999) / 1000
    return when {
        seconds == 0L -> "Ready now"
        seconds >= 86_400 -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h remaining"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m remaining"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s remaining"
        else -> "${seconds}s remaining"
    }
}

fun digestRelativeTime(timestamp: Long, now: Long): String {
    val seconds = ((now - timestamp).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

fun digestLink(value: String?): URI? = runCatching {
    value?.takeIf { it.length <= 2048 }?.let(::URI)?.takeIf {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && (it.port == -1 || it.port == 443)
    }
}.getOrNull()

fun digestHistory(entries: List<DigestEntry>, community: Boolean, activity: String, search: String): List<DigestEntry> {
    val query = search.trim().take(80)
    return entries.asSequence().filter { it.community == community && (activity.isEmpty() || it.activity == activity) }
        .filter { query.isEmpty() || it.title.contains(query, true) || it.summary.contains(query, true) || it.meta.contains(query, true) }
        .sortedByDescending { it.timestamp ?: 0 }.toList()
}
