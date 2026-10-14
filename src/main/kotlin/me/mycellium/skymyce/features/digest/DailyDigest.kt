package me.mycellium.skymyce.features.digest

import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.DailyDigestConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.location.SkyBlockLocationEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Client-thread coordinator. All file work runs on SBAPI's scheduler; the menu reads immutable snapshots. */
object DailyDigest : SkyMyceModule() {
    override fun onDisable() { wake?.cancel(false); wake = null }
    private val gate = DigestDailyGate()
    private val profiles = linkedMapOf<String, DigestProfileState>()
    private val history = DigestRngHistory()
    private var store: DigestStateStore? = null
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
        store = DigestStateStore(SkyMyce.configPath.resolve("daily_digest.json"))
        writer = DigestStateWriter(store!!, { write -> Scheduling.schedule(500.milliseconds) {
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
                    ?.filter { it.matches(Regex("[a-f0-9]{32}")) }?.take(256)?.toSet().orEmpty()
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
            val saved = store!!.load()
            MC.instance.execute {
                saved.lastOpened.forEach { (account, date) -> gate.opened(account, java.time.LocalDate.parse(date)) }
                // Community history is revalidated by the authoritative feed on each connection; personal history stays local.
                profiles.putAll(saved.profiles.mapValues { (_, profile) -> profile.copy(history = profile.history.filter { !it.community }) })
                saved.news.forEach { (source, state) -> NewsSource.entries.find { it.wire == source }?.let { news.restore(it, state) } }
                savedGlobal = saved.global
                saved.global?.let(DigestActivities::restoreGlobal)
                loaded = true
                storageStatus = if (store!!.recovered) "Recovered damaged local cache; original will be preserved" else ""
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

    private fun enabledNews(): List<NewsSource> = if (DailyDigestConfig.news) listOf(NewsSource.GAME) else emptyList()

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
            DigestTone.INFO, item.source.label, item.publishedAt, url = item.url) }
        return DigestCardState("news", "Daily News", items.firstOrNull()?.title ?: if (sources.isEmpty()) "News is disabled" else "No cached updates yet",
            when { sources.isEmpty() -> "Enable news in settings"; failure != null -> "$failure${if (items.isNotEmpty()) " · available updates retained" else ""}";
                states.any { it.loading } -> "Refreshing in the background"; states.any { it.fetchedAt > 0 && now - it.fetchedAt > 300000 } -> "Saved updates · refresh when connected";
                items.isEmpty() -> "Latest game updates will appear here"; else -> "${items.size} recent game updates" },
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
                DigestTone.SPECIAL, "Community · unverified", entry.occurredAt, community = true, activity = entry.activity)
        }
        val entries = (local + community).sortedWith(compareByDescending<DigestEntry> { it.timestamp }.thenBy { it.id })
        return DigestCardState("rng", "RNG Activity", entries.firstOrNull()?.title ?: "Your next rare find starts here", rngStatus,
            if (entries.isEmpty()) DigestTone.MUTED else DigestTone.SPECIAL, entries, entries.firstOrNull()?.timestamp)
    }
}
