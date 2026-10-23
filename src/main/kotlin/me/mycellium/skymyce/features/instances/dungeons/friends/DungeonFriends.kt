package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.api.events.PlayerMessageEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.config.instances.dungeons.LeapMenuConfig
import me.mycellium.skymyce.config.instances.dungeons.PartyListing
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import me.mycellium.skymyce.utils.Utils.displayTitle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.hypixel.PartyInfoEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import tech.thatgravyboat.skyblockapi.utils.text.Text.send
import java.util.UUID

object DungeonFriends : SkyMyceModule() {
    val replies = DungeonLfgReplies()
    val joining = DungeonFriendJoining()
    private val partyNotices = DungeonPartyNotices()
    private val partyBorders = DungeonPartyBorders()
    var scanner = FriendListScanner()
        private set
    private var scannerAccount: UUID? = null
    private var scannerStore: FriendListStore? = null
    private var savedScannerVersion = 0L
    private var nextScannerSave = 0L
    private var party = DungeonFriendParty()
    private var previousRoster: Pair<Set<String>, Int>? = null
    private var observedMembers = emptyList<Pair<String?, UUID?>>()
    private var observedPartySize = -1
    private var observedInParty = false
    private var observedSelf: String? = null
    private var observedStatsVersion = -1L
    private var observedScannerVersion = -1L
    private var observedScanner: FriendListScanner? = null
    private var observedFriends = emptyList<Any>()
    private var friendUuids = emptyMap<String, UUID?>()
    private var socialVersion = 0L
    private var rosterVersion = 0L
    private var statsScanner: FriendListScanner? = null
    private var statsScannerVersion = -1L
    private var statsRosterVersion = -1L
    private var statsCacheVersion = -1L
    private var statsSocialVersion = -1L
    private var nextStatsRefresh = 0L
    private var statsActive = false
    private var publishedPolicy: DungeonJoinPolicy? = null
    private var nextPartyRequest = 0L
    private var partyRequestAllowedAt = 0L
    private var nextAction = 0L
    private var sendingScan = false
    private var sendingPartyAction = false
    private var selectedListing: Pair<PartyListing, Long>? = null
    private var awaitingRoster = false
    private var restoredParty = false
    private var currentLeader: String? = null
    private val fallbackMessages = ArrayDeque<Triple<String, String, Boolean>>()
    private val sentOffers = mutableMapOf<String, DungeonLfgOffer>()
    private val receivedOffers = mutableMapOf<String, DungeonLfgOffer>()
    private var lastJoinStatus = ""
    private var nextJoinStatsCheck = 0L
    private val joinedTitles = mutableMapOf<String, DungeonJoinedTitle>()
    var partyFloor: DungeonFloor? = null
        private set
    val hostingFloor: DungeonFloor get() = partyFloor ?: DungeonFriendsSettings.availability.floor
    var partyRevision = 0L
        private set
    val neededClass: DungeonClass? get() = party.neededClass

    val partySize: Int get() = maxOf(party.size, PartyAPI.size, 1)
    val partyFull: Boolean get() = partySize >= 5
    val actionUnavailable: String? get() = when {
        !LocationAPI.onHypixel -> "Join Hypixel to invite friends"
        !party.ready -> "Checking party size..."
        partyFull -> "Your party is full"
        !canInvite -> "Only the party leader or a moderator can invite; enable All Invite to allow members"
        now() < nextAction -> "Wait a moment before another invitation"
        else -> null
    }
    val canAct: Boolean get() = actionUnavailable == null
    private val canInvite: Boolean get() = MC.instance.player?.let { self ->
        dungeonCanInvite(PartyAPI.inParty, PartyAPI.allInvite,
            PartyAPI.leader?.uuid == self.uuid || PartyAPI.leader?.name.equals(self.name.string, true),
            PartyAPI.members.any { it.uuid == self.uuid && it.role.name == "MOD" })
    } == true
    val openClasses: Set<DungeonClass> get() = party.openClasses
    val partyStatus: String get() = if (!party.ready) "Checking party size..." else "Party $partySize/5"
    private val solo get() = party.ready && !PartyAPI.inParty && partySize == 1

    fun selectFloor(floor: DungeonFloor) {
        if (!DungeonFriendsSettings.save(available = DungeonFriendsSettings.availability.copy(floor = floor))) {
            Component.literal("§c" + (DungeonFriendsSettings.error ?: "Could not save the dungeon floor. Try again.")).send()
            return
        }
        if (partyFloor != null) partyFloor = floor
        joining.clear()
        partyRevision++
    }

    fun joinUnavailable(friend: OnlineDungeonFriend, floor: DungeonFloor): String? {
        if (!LocationAPI.isOnSkyBlock || !solo) return "You must be solo in SkyBlock to use Join"
        if (now() < nextAction || joining.busy(now())) return "Waiting for your current party request"
        if (!DungeonFriendRelay.connected) return "Party requests are currently unavailable"
        val target = DungeonFriendRelay.partyPolicy(friend.name) ?: return "Waiting for ${friend.name}'s party details; both players need the updated mod"
        val uuid = FriendsAPI.getFriend(friend.name)?.uuid?.toString()?.replace("-", "")
        if (uuid != null && uuid != target.uuid) return "Player identity changed; refresh your friends"
        val policy = target.policy
        if (!policy.open) return "${friend.name}'s party is not accepting Join requests"
        if (floor != policy.floor) return "${friend.name} is hosting ${policy.floor.name}"
        val stats = DungeonFriendStatsCache.verified(MC.player.name.string)
        if (policy.maxPbMillis != null && stats == null && now() >= nextJoinStatsCheck) {
            nextJoinStatsCheck = now() + 60000
            checkJoinStats(MC.player.name.string)
        }
        if (!policy.canRequest(stats, floor)) return "Your ${floor.name} S+ PB must be ${formatDungeonTime(policy.maxPbMillis)} or faster"
        return null
    }

    override fun init() {
        DungeonFriendsSettings.load()
        RelayMessages.init()
        DungeonFriendStatsCache.initialize(SkyMyce.configPath.resolve("dungeon_friend_stats.json"))
        ClientLifecycleEvents.CLIENT_STOPPING.register { saveFriends(true); DungeonFriendStatsCache.save(true); DungeonFriendRelay.disconnect() }
        ClientTickEvents.END_CLIENT_TICK.register { RuntimeProfiler.measure(RuntimeProfiler.Area.FRIEND_TICK) { tick() } }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (!isEnabled()) return@register true
            if (!sendingPartyAction) partyNotices.command(command, automatic = false, now())
            if (!sendingScan && command.matches(Regex("(?i)(?:f|friend) list(?: \\d+)?"))) scanner.manualCommand()
            val reply = Regex("(?i)^(?:msg|w|tell|whisper) ([A-Za-z0-9_]{1,16}) (.+)$").matchEntire(command)
            val offer = reply?.groupValues?.get(1)?.lowercase()?.let(receivedOffers::get)
            if (offer != null && offer.expires > now() && classifyLfgReply(reply.groupValues[2]) == LfgReplyStatus.ACCEPTED) {
                !relayReply(reply.groupValues[1], reply.groupValues[2])
            } else true
        }
    }

    fun refreshPartyFinder(automatic: Boolean = false) {
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        loadFriends()
        observeFriends()
        if (automatic) {
            if (!scanner.refreshOnOpen(now(), full = true)) return
        } else scanner.refresh(now(), full = !scanner.hasScannedAll)
        if (!automatic) DungeonFriendStatsCache.refresh()
    }

    override fun tick() {
        val sharing = LeapMenuConfig.enabled && LeapMenuConfig.partyPovSharing
        val global = GlobalChatConfig.enabled || GlobalChatConfig.partyEnabled || sharing
        DungeonFriendRelay.tick(relayActive(MC.instance.player != null && MC.connection != null,
            global, isEnabled() && LocationAPI.onHypixel, DungeonFriendRelay.connected))
        RelayMessages.globalTick()
        if ((isEnabled() || GlobalChatConfig.partyEnabled || sharing) && LocationAPI.onHypixel && MC.instance.player != null &&
            partyInfoRequestDue(now(), nextPartyRequest, partyRequestAllowedAt)) {
            val requestAt = now()
            val sent = HypixelModAPI.getInstance().sendPacket(ServerboundPartyInfoPacket())
            partyRequestAllowedAt = requestAt + if (sent) PARTY_INFO_REQUEST_MILLIS else 5000
            nextPartyRequest = partyRequestAllowedAt
        }
        if (!isEnabled()) { publishClosedPolicy(); return }
        if (!LocationAPI.isOnSkyBlock) { fallbackMessages.clear(); publishClosedPolicy() }
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        loadFriends()
        observeFriends()
        partyBorders.tick(now()) { it.send() }
        replies.prune(now())
        sentOffers.entries.removeIf { it.value.expires <= now() }
        receivedOffers.entries.removeIf { it.value.expires <= now() }
        joining.prune(now())
        party.members.forEach { replies.forget(it) }
        if (partyObservationChanged()) syncParty()
        val active = DungeonFriendsConfig.enabled && LocationAPI.isOnSkyBlock
        val statusChecker = active || MC.screen is DungeonFriendsScreen
        if (statusChecker) {
            scanner.tick(now())?.let { command ->
                sendingScan = true
                try { sendCommand(command) } finally { sendingScan = false }
            }
        } else if (scanner.scanning) scanner.manualCommand()
        queueVisibleStats(statusChecker || MC.screen is FriendsSocialScreen)
        saveFriends()
        joinedTitles.entries.removeIf { (name, title) ->
            !DungeonFriendsSettings.titleNotifications || name !in party.members || (title.shownUntil != 0L && title.shownUntil <= now())
        }
        for ((name, title) in joinedTitles) {
            val stats = DungeonFriendStatsCache.get(name)
            val clazz = party.classes[name] ?: stats?.selectedClass ?: joining.classFor(name)
            title.subtitle(now(), stats, clazz, party.classLevel(name, clazz), DungeonFriendStatsCache.isPending(name))?.let {
                showPartyTitle(title.name, "joined your party", it, title.source)
            }
        }
        if (LocationAPI.isOnSkyBlock && now() >= nextAction && MC.connection != null) {
            val self = MC.player
            val availability = DungeonFriendsSettings.availability
            val floor = hostingFloor
            val limit = availability.maxPbMillis.takeIf { availability.floor == floor }
            val invite = canAct
            val policy = publishedPolicy?.takeIf { it.floor == floor && it.maxPbMillis == limit && it.open == (invite && partySize < 5) }
                ?: DungeonJoinPolicy(floor, limit, invite && partySize < 5).also { publishedPolicy = it }
            DungeonFriendRelay.publishPolicy(policy, DungeonFriendStatsCache.get(self.name.string)?.selectedClass)
            if (joining.hasCommandWork) {
                val context = JoinPartyContext(availability, solo, invite, partySize, floor, openClasses, party.members.toSet())
                joining.nextCommand(context, now(), DungeonFriendStatsCache::verified)?.let {
                    sendJoinAction(it)
                    nextAction = now() + 1000
                }
            }
            if (joining.status != lastJoinStatus) {
                lastJoinStatus = joining.status
                if (lastJoinStatus.contains("does not meet") || lastJoinStatus.endsWith("expired")) notify(lastJoinStatus, joining.statusPlayer)
            }
            if (now() >= nextAction && fallbackMessages.isNotEmpty()) {
                val (name, text, reply) = fallbackMessages.removeFirst()
                if (isFriend(name) && (reply || canAct)) {
                    sendCommand("msg $name $text")
                    nextAction = now() + 1000
                }
            }
        }
    }

    private fun observeFriends() {
        // SBAPI updates immutable friend records inside a mutable list. Compare that
        // list once, then reuse the name index instead of n linear getFriend calls.
        val friends = FriendsAPI.friends
        if (observedFriends != friends) {
            observedFriends = friends.toList()
            friendUuids = friends.associate { it.name.lowercase() to it.uuid }
            socialVersion++
        }
        if (observedScanner !== scanner || observedScannerVersion != scanner.version) socialVersion++
    }

    /** Native APIs own the roster. The tick only compares existing fields; rebuild on an actual change. */
    private fun partyObservationChanged(): Boolean {
        val members = PartyAPI.members
        val self = MC.player.name.string
        var changed = observedSelf != self || observedPartySize != PartyAPI.size || observedInParty != PartyAPI.inParty ||
            PartyAPI.leader?.name?.let { it != currentLeader } == true || observedMembers.size != members.size ||
            observedStatsVersion != DungeonFriendStatsCache.version || observedScanner !== scanner || observedScannerVersion != scanner.version
        var index = 0
        for (member in members) {
            val old = observedMembers.getOrNull(index++)
            if (member.name == null || old?.first != member.name || old?.second != member.uuid) changed = true
        }
        if (!changed && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            for (player in DungeonAPI.teammates) {
                val clazz = player.dungeonClass ?: continue
                val name = player.name.lowercase()
                if (name in party.members && party.classes[name] != clazz) { changed = true; break }
            }
            DungeonAPI.ownPlayer?.let { player ->
                if (player.dungeonClass != null && party.classes[player.name.lowercase()] != player.dungeonClass) changed = true
            }
        }
        if (changed) {
            observedMembers = members.map { it.name to it.uuid }
            observedSelf = self; observedPartySize = PartyAPI.size; observedInParty = PartyAPI.inParty
            observedStatsVersion = DungeonFriendStatsCache.version; observedScannerVersion = scanner.version
            observedScanner = scanner
            rosterVersion++
        }
        return changed
    }

    private fun queueVisibleStats(active: Boolean) {
        if (!active || !DungeonFriendStatsCache.canFetch) { statsActive = false; return }
        val time = System.currentTimeMillis()
        if (statsActive && statsScanner === scanner && statsScannerVersion == scanner.version && statsRosterVersion == rosterVersion &&
            statsCacheVersion == DungeonFriendStatsCache.version &&
            statsSocialVersion == socialVersion && time < nextStatsRefresh) return
        nextStatsRefresh = Long.MAX_VALUE
        fun request(name: String, uuid: UUID?) {
            DungeonFriendStatsCache.request(name, uuid)
            nextStatsRefresh = minOf(nextStatsRefresh, DungeonFriendStatsCache.nextAutomaticRefresh(name, time))
        }
        request(MC.player.name.string, MC.player.uuid)
        scanner.online.values.forEach { friend ->
            request(friend.name, friendUuids[friend.name.lowercase()])
        }
        PartyAPI.members.forEach { member -> member.name?.let { request(it, member.uuid) } }
        statsActive = true; statsScanner = scanner; statsScannerVersion = scanner.version
        statsRosterVersion = rosterVersion; statsCacheVersion = DungeonFriendStatsCache.version
        statsSocialVersion = socialVersion
    }

    private fun syncParty() {
        val self = MC.player.name.string.lowercase()
        val names = PartyAPI.members.mapNotNull { member ->
            member.name ?: FriendsAPI.friends.firstOrNull { it.uuid == member.uuid && member.uuid != null }?.name
        }.map { it.lowercase() }.toSet() + self
        val roster = names to PartyAPI.size.coerceAtLeast(1)
        if (roster != previousRoster) {
            party.roster(names, roster.second, completeNames = !awaitingRoster && names.size >= roster.second)
            previousRoster = roster
        }
        currentLeader = PartyAPI.leader?.name ?: currentLeader
        if (!restoredParty && party.ready && names.size >= roster.second && PartyAPI.inParty) {
            DungeonFriendsSettings.lastParty?.takeIf { it.matches(currentLeader, names, System.currentTimeMillis()) }?.let {
                party.restoreClasses(it.classes, DungeonFriendStatsCache::liveClass)
                partyFloor = it.floor
                partyRevision++
            }
            restoredParty = true
        }
        for (name in party.members) {
            replies.forget(name)
            // Last selected class is a fallback, never a replacement for a class observed in the current party.
            // A suggested Join role does not switch the player's selected class in Hypixel.
            if (name !in party.classes) (DungeonFriendStatsCache.get(name)?.selectedClass ?: joining.classFor(name))?.let { party.classes[name] = it }
        }
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            (DungeonAPI.teammates + listOfNotNull(DungeonAPI.ownPlayer)).forEach { player ->
                if (player.name.lowercase() in party.members) player.dungeonClass?.let { party.classes[player.name.lowercase()] = it }
            }
        }
        party.advance()
        saveParty()
    }

    private fun saveParty() {
        val leader = currentLeader?.lowercase()?.takeIf { it in party.members } ?: return
        if (!PartyAPI.inParty || !party.ready || !restoredParty || awaitingRoster || party.members.size !in 2..5) return
        val saved = SavedDungeonParty(leader, party.members.toSet(), party.classes.filterKeys { it in party.members },
            partyFloor, System.currentTimeMillis())
        if (saved.copy(savedAt = 0) != DungeonFriendsSettings.lastParty?.copy(savedAt = 0)) DungeonFriendsSettings.save(savedParty = saved)
    }

    fun onListingSelected(listing: PartyListing) {
        selectedListing = listing to now()
    }

    fun onListings(listings: List<PartyListing>) {
        if (MC.instance.player == null) return
        val self = MC.player.name.string
        // Browsing another party's listing must never change our own composition.
        val own = listings.firstOrNull { it.memberUsername.any { name -> name.equals(self, true) } } ?: return
        party.roster(own.memberUsername, own.memberUsername.size, true)
        party.classes.clear()
        party.classes.putAll(own.memberClasses.mapKeys { it.key.lowercase() })
        currentLeader = own.leaderName
        partyFloor = own.floor
        restoredParty = true
        party.advance()
        saveParty()
    }

    @Subscription(priority = Subscription.LOW, receiveCancelled = true)
    fun onPartyInfo(event: PartyInfoEvent) {
        if (MC.instance.player == null) return
        partyRequestAllowedAt = now() + PARTY_INFO_REQUEST_MILLIS
        nextPartyRequest = partyRequestAllowedAt
        RelayMessages.partyInfo(event)
        if (!event.inParty) {
            if (party.members.size > 1) partyRevision++
            party = DungeonFriendParty()
            joinedTitles.clear()
            currentLeader = null
            partyFloor = null
            restoredParty = false
        }
        awaitingRoster = false
        previousRoster = null
        observedMembers = emptyList(); observedPartySize = -1; observedSelf = null; observedStatsVersion = -1L
        observedScannerVersion = -1L; statsActive = false; statsScanner = null; publishedPolicy = null
        observedScanner = null; observedFriends = emptyList(); friendUuids = emptyMap()
        syncParty()
        party.roster(party.members.toList(), if (event.inParty) event.members.size else 1, true)
        syncParty()
    }

    /** IRC reuses this module's throttled party-info request; it never sends a duplicate packet. */
    internal fun requestIrcPartyInfo() {
        if (LocationAPI.onHypixel && MC.instance.player != null) nextPartyRequest = minOf(nextPartyRequest, now())
    }

    @Subscription(priority = Subscription.LOW, receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        loadFriends()
        val message = event.text
        val normalized = message.lines().joinToString("\n") { line ->
            val nickname = NICKNAME.find(line.trim())?.groupValues?.get(1)
            val friend = nickname?.let { nick -> FriendsAPI.friends.firstOrNull { it.nickname == nick } }
            if (friend != null) line.replaceFirst("$nickname*", friend.name) else line
        }
        if (scanner.receive(normalized, now(), event.component)) event.cancel()
        FRIEND_NOTICE.matchEntire(message)?.let {
            scanner.notification(it.groupValues[1], it.groupValues[2] == "joined", dungeonPlayerNameStyle(event.component, it.groupValues[1]))
        }
        message.lines().mapNotNull(::bestFriendChange).forEach { (name, best) -> scanner.bestFriend(name, best) }
        message.lines().mapNotNull(::newlyAddedFriend).forEach { name ->
            scanner.notification(name, true, dungeonPlayerNameStyle(event.component, name))
            DungeonFriendStatsCache.request(name, FriendsAPI.getFriend(name)?.uuid, force = true, bypassShared = true)
        }
        if (message.startsWith("You removed ") && message.endsWith(" from your friends list!")) {
            val name = message.substringAfter("You removed ").substringBefore(" from your friends list!").substringAfterLast(' ')
            scanner.remove(name)
        }
        syncParty()
        if (party.chat(message, MC.player.name.string)) {
            currentLeader = null
            partyFloor = null
            awaitingRoster = false
            restoredParty = false
            joining.clear()
            partyNotices.clear()
            joinedTitles.clear()
            partyRevision++
            nextPartyRequest = 0
        }
        dungeonClassChange(message, MC.player.name.string)?.let { (name, clazz) ->
            val uuid = if (name.equals(MC.player.name.string, true)) MC.player.uuid else
                PartyAPI.members.firstOrNull { it.name.equals(name, true) }?.uuid ?: FriendsAPI.getFriend(name)?.uuid
            if (name.equals(MC.player.name.string, true) || name.lowercase() in party.members) {
                onClassChange(name, uuid?.toString()?.replace("-", ""), clazz)
            }
        }
        DungeonFriendParty.joinedPlayer(message)?.let { name ->
            if (DungeonFriendsSettings.titleNotifications && !name.equals(MC.player.name.string, true) && name.lowercase() !in joinedTitles) {
                joinedTitles[name.lowercase()] = DungeonJoinedTitle(name, event.component, now())
                val stats = DungeonFriendStatsCache.get(name)
                val clazz = party.classes[name.lowercase()] ?: stats?.selectedClass ?: joining.classFor(name)
                val missing = stats?.catacombs == null || clazz == null ||
                    (party.classLevel(name, clazz) ?: stats.classes[clazz]) == null
                val uuid = PartyAPI.members.firstOrNull { it.name.equals(name, true) }?.uuid
                    ?: FriendsAPI.getFriend(name)?.uuid ?: MC.connection?.getPlayerInfo(name)?.profile?.id
                DungeonFriendStatsCache.request(name, uuid, force = stats != null && missing, priority = true)
            }
        }
        joinedPartyLeader(message)?.let { leader ->
            joinedTitles.clear()
            currentLeader = leader
            awaitingRoster = true
            restoredParty = true
            val accepted = joining.accepted?.second
            val listing = selectedListing?.takeIf { now() - it.second <= 60000 && it.first.leaderName.equals(leader, true) }?.first
            if (listing != null) {
                val members = listing.memberUsername.map { it.lowercase() }.toSet() + MC.player.name.string.lowercase()
                party.roster(members, members.size, false)
                party.classes.putAll(listing.memberClasses.mapKeys { it.key.lowercase() })
            }
            partyFloor = listing?.floor ?: accepted?.first
            (DungeonFriendStatsCache.get(MC.player.name.string)?.selectedClass ?: accepted?.second)?.let {
                party.classes[MC.player.name.string.lowercase()] = it
            }
            party.advance()
            selectedListing = null
            joining.clear()
            partyRevision++
            nextPartyRequest = minOf(nextPartyRequest, now() + 2000)
        }
        var replacement: Component? = null
        var noticePlayer = ""
        var hideInvitation = false
        serverPartyInviter(event.component)?.let { inviter ->
            hideInvitation = LocationAPI.isOnSkyBlock && solo && joining.invited(inviter, now())
            if (!hideInvitation && DungeonFriendsSettings.titleNotifications) {
                val details = joining.inviteDetails(inviter, now())
                showPartyTitle(inviter, "has invited you", dungeonTitleDetails(details?.first, listOfNotNull(details?.second)), event.component)
            }
        }
        partyNotices.compact(message, MC.player.name.string, now())?.let { notice ->
            noticePlayer = partyNotices.player
            replacement = Component.literal("§b[Party] §f$notice")
        }
        if (!event.isCancelled || replacement != null || hideInvitation) {
            val border = partyBorders.filter(event.component, partyNotices.pending(now()) || joining.busy(now()),
                replacement != null || hideInvitation, now()) {
                it.send()
            }
            if (border || replacement != null || hideInvitation) event.cancel()
        }
        replacement?.let { partyMessage(noticePlayer, it) }
    }

    @Subscription
    fun onPrivateReply(event: PlayerMessageEvent) {
        if (!LocationAPI.onHypixel || event.type != ChatChannel.PRIVATE) return
        replies.receive(event.player, event.message, now())
        acceptLfgReply(event.player, event.message)
    }

    private fun isFriend(name: String) = name.matches(Regex("[A-Za-z0-9_]{1,16}")) &&
        (FriendsAPI.getFriend(name) != null || name.lowercase() in scanner.online)

    fun isRelayFriend(name: String) = isFriend(name)
    fun acceptsRelayIdentity(name: String, uuid: String): Boolean {
        if (!isFriend(name) || !uuid.matches(Regex("[a-f0-9]{32}"))) return false
        val known = FriendsAPI.getFriend(name)?.uuid?.toString()?.replace("-", "")
        return known == null || known == uuid
    }

    fun onRelayMessage(name: String, uuid: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock) return rejectRelay(name, "outside_skyblock")
        if (!isFriend(name)) return rejectRelay(name, "unknown_friend")
        val knownUuid = FriendsAPI.getFriend(name)?.uuid?.toString()?.replace("-", "")
        if (knownUuid != null && knownUuid != uuid) return rejectRelay(name, "friend_identity_mismatch")
        if (text == "[SkyMyce Connection Test]") return false // Ignore diagnostics from older clients.
        val offer = DungeonLfgOffer.parse(text, now())
        val request = DungeonJoinRequest.parse(text)
        if (offer != null) {
            if (receivedOffers.size >= 16 && name.lowercase() !in receivedOffers) return rejectRelay(name, "offer_queue_full")
            receivedOffers[name.lowercase()] = offer
            partyMessage(name, offer.chatMessage(name))
            if (DungeonFriendsSettings.titleNotifications) {
                showPartyTitle(name, "has invited you", dungeonTitleDetails(offer.request.floor, offer.request.classes))
            }
        } else if (request != null) {
            val manual = sentOffers[name.lowercase()]?.takeIf { it.expires > now() && it.request == request } != null
            if (joining.receiveRequest(name, request, now(), manual)) {
                if (manual) replies.receive(name, "yes", now())
                sentOffers.remove(name.lowercase())
                notify("$name has requested to join", name)
                if (!manual) checkJoinStats(name)
            } else return rejectRelay(name, "join_request_rejected")
        } else if (text.startsWith("Inviting you for ") && text.contains("[SkyMyce Ready ")) {
            if (!joining.receiveOffer(name, text, now())) return rejectRelay(name, "join_offer_rejected")
        } else {
            val lfgReply = sentOffers[name.lowercase()]?.expires?.let { it > now() } == true
            replies.receive(name, text, now())
            acceptLfgReply(name, text)
            val message = Component.literal("§b$name §8» §f$text")
            if (lfgReply) partyMessage(name, message) else message.send()
        }
        return true
    }

    private fun rejectRelay(name: String, reason: String): Boolean {
        SkyMyce.logger.info("[Dungeon relay] Ignored message from {}: {}", name, reason)
        return false
    }

    private fun partyMessage(name: String, message: Component) {
        if (MC.instance.player != null) message.send("skymyce:party:${name.lowercase()}")
    }

    private fun showPartyTitle(name: String, suffix: String, subtitle: String, source: Component? = null) {
        val color = source?.let { dungeonPlayerNameStyle(it, name)?.color?.value }
            ?: scanner.online[name.lowercase()]?.rankColor
            ?: MC.connection?.getPlayerInfo(name)?.tabListDisplayName?.let { dungeonPlayerNameStyle(it, name)?.color?.value }
        displayTitle(dungeonPlayerTitle(name, suffix, color), Component.literal(subtitle))
    }

    fun onClassChange(name: String, uuid: String?, clazz: DungeonClass) {
        val self = MC.instance.player ?: return
        val friend = FriendsAPI.getFriend(name)
        val member = PartyAPI.members.firstOrNull { it.name.equals(name, true) }
        val own = name.equals(self.name.string, true)
        val knownUuid = (if (own) self.uuid else friend?.uuid ?: member?.uuid)?.toString()?.replace("-", "")
        if (uuid != null && knownUuid != null && uuid != knownUuid) return
        if (!own && friend == null && name.lowercase() !in party.members && DungeonFriendStatsCache.get(name) == null) return
        DungeonFriendStatsCache.updateClass(name, uuid ?: knownUuid, clazz)
        if (name.lowercase() in party.members && party.classes[name.lowercase()] != clazz) {
            party.classes[name.lowercase()] = clazz
            party.advance()
            saveParty()
        }
    }

    private fun notify(text: String, name: String) {
        partyMessage(name, Component.literal("§b[Party] §f$text"))
    }

    private fun acceptLfgReply(name: String, text: String) {
        val offer = sentOffers[name.lowercase()]?.takeIf { it.expires > now() } ?: return
        if (classifyLfgReply(text) == LfgReplyStatus.ACCEPTED && isFriend(name)) {
            joining.receiveAcceptedReply(name, offer.request, now())
            sentOffers.remove(name.lowercase())
        }
    }

    private fun sendJoinAction(command: String): Boolean {
        if (!command.startsWith("msg ")) {
            if (command.startsWith("party invite ")) {
                val name = command.substringAfterLast(' ')
                notify("Inviting ${FriendsAPI.getFriend(name)?.name ?: scanner.online[name]?.name ?: name}...", name)
                replies.invited(name, now())
            }
            sendPartyAction(command)
            return true
        }
        val parts = command.split(' ', limit = 3)
        val name = parts[1]
        val token = parts[2].substringAfterLast(' ').removeSuffix("]")
        val failed = { if (joining.failed(name, token)) notify("Could not reach $name", name) }
        val sent = DungeonFriendRelay.send(name, parts[2], { joining.acknowledged(name, token) }, failed)
        if (!sent) failed()
        return sent
    }

    private fun checkJoinStats(name: String) {
        if (DungeonFriendStatsCache.verified(name) == null) {
            DungeonFriendStatsCache.request(name, FriendsAPI.getFriend(name)?.uuid, force = true, priority = true)
        }
    }

    fun join(friend: OnlineDungeonFriend, floor: DungeonFloor) {
        if (joinUnavailable(friend, floor) != null || MC.connection == null || friend.name.lowercase() !in scanner.online) return
        if (!friend.name.matches(Regex("[A-Za-z0-9_]{1,16}")) || friend.name.equals(MC.player.name.string, true)) return
        val classes = DungeonFriendsSettings.availability.classes.ifEmpty {
            DungeonFriendStatsCache.get(MC.player.name.string)?.selectedClass?.let(::setOf) ?: DungeonClass.entries.toSet()
        }
        val request = DungeonJoinRequest(floor, classes, UUID.randomUUID().toString().replace("-", "").take(16))
        if (sendJoinAction(joining.request(friend.name, request, now(), manual = true))) {
            notify("Requested to join ${friend.name}", friend.name)
        }
        nextAction = now() + 1000
    }

    fun invite(friend: OnlineDungeonFriend) {
        if (action(friend, "p ${friend.name}")) replies.invited(friend.name, now())
    }

    fun message(friend: OnlineDungeonFriend, floor: DungeonFloor, clazz: DungeonClass?) {
        val text = lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, clazz, floor)
        if (text.isEmpty() || !canAct || MC.connection == null || friend.name.lowercase() !in scanner.online || !isFriend(friend.name)) return
        replies.sent(friend.name, now())
        val classes = clazz?.let(::setOf) ?: DungeonClass.entries.toSet()
        val offer = DungeonLfgOffer(DungeonJoinRequest(floor, classes, UUID.randomUUID().toString().replace("-", "").take(16)), text, now() + 60000)
        sentOffers[friend.name.lowercase()] = offer
        sendLfg(friend.name, text, false, offer.message())
    }

    /** True means sent or explicitly explained to the player, so the command can be suppressed safely. */
    fun relayReply(name: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock || !isFriend(name) || text.length !in 1..220 || text.any { it < ' ' || it == '§' || it == '\u007f' }) return false
        val offer = receivedOffers[name.lowercase()]?.takeIf { it.expires > now() }
        val buttonReply = lfgButtonAction.matchEntire(text)
        if (buttonReply != null && buttonReply.groupValues[2] != offer?.request?.token) { notify("That request has expired", name); return true }
        val reply = buttonReply?.groupValues?.get(1) ?: text
        if (offer != null && classifyLfgReply(reply) == LfgReplyStatus.ACCEPTED) {
            lfgAcceptanceUnavailable(solo, joining.busy(now()), now() < nextAction)?.let { notify(it, name); return true }
            receivedOffers.remove(name.lowercase())
            if (sendJoinAction(joining.request(name, offer.request, now(), manual = true))) {
                notify("Waiting for $name's invite...", name)
            }
            nextAction = now() + 1000
            return true
        }
        if (now() < nextAction) { notify("Wait a moment, then reply again", name); return true }
        receivedOffers.remove(name.lowercase())
        sendLfg(name, reply, true)
        if (offer != null) notify("Replied to $name: $reply", name)
        return true
    }

    private fun sendLfg(name: String, text: String, reply: Boolean, relayText: String = text) {
        nextAction = now() + 1000
        val fallback = {
            if (LocationAPI.isOnSkyBlock && fallbackMessages.size < 16) fallbackMessages.addLast(Triple(name, text, reply))
            Unit
        }
        if (!DungeonFriendRelay.send(name, relayText, {}, fallback)) fallback()
    }

    private fun action(friend: OnlineDungeonFriend, command: String): Boolean {
        // Recheck at the send boundary, including clicks from rows rendered before the fifth member joined.
        if (!canAct || MC.connection == null || friend.name.lowercase() !in scanner.online || !friend.name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return false
        nextAction = now() + 1000
        sendPartyAction(command)
        return true
    }

    private fun sendPartyAction(command: String) {
        partyNotices.command(command, automatic = true, now())
        sendingPartyAction = true
        try { sendCommand(command) } finally { sendingPartyAction = false }
    }

    @Subscription
    fun onDisconnect(event: ServerDisconnectEvent) { resetConnection() }
    override fun onDisable() { resetConnection(disconnectRelay = false) }
    private fun publishClosedPolicy() {
        if (!DungeonFriendRelay.connected) return
        val availability = DungeonFriendsSettings.availability
        val policy = publishedPolicy?.takeIf { it.floor == availability.floor &&
            it.maxPbMillis == availability.maxPbMillis && !it.open }
            ?: DungeonJoinPolicy(availability.floor, availability.maxPbMillis, open = false).also { publishedPolicy = it }
        DungeonFriendRelay.publishPolicy(policy,
            MC.instance.player?.let { DungeonFriendStatsCache.get(it.name.string)?.selectedClass })
    }
    private fun resetConnection(disconnectRelay: Boolean = true) {
        scanner.cancel()
        saveFriends(true)
        party = DungeonFriendParty()
        previousRoster = null
        observedMembers = emptyList(); observedPartySize = -1; observedSelf = null; observedStatsVersion = -1L
        observedScannerVersion = -1L; statsActive = false; statsScanner = null; publishedPolicy = null
        observedScanner = null; observedFriends = emptyList(); friendUuids = emptyMap()
        nextPartyRequest = 0
        if (disconnectRelay) partyRequestAllowedAt = 0
        nextAction = 0
        replies.clear()
        joinedTitles.clear()
        sentOffers.clear()
        receivedOffers.clear()
        lastJoinStatus = ""
        nextJoinStatsCheck = 0L
        fallbackMessages.clear()
        if (disconnectRelay) DungeonFriendRelay.disconnect()
        joining.clear()
        partyNotices.clear()
        partyBorders.clear()
        selectedListing = null
        awaitingRoster = false
        restoredParty = false
        currentLeader = null
        partyFloor = null
        partyRevision++
        DungeonFriendStatsCache.disconnect()
    }

    @Subscription
    fun onProfileChange(event: ProfileChangeEvent) {
        MC.instance.player?.let {
            DungeonFriendStatsCache.invalidateProfile(it.name.string)
            DungeonFriendStatsCache.request(it.name.string, it.uuid, force = true, bypassShared = true)
        }
    }

    private fun loadFriends() {
        val account = MC.player.uuid
        if (scannerAccount == account) return
        saveFriends(true)
        scannerAccount = account
        val store = FriendListStore(SkyMyce.configPath.resolve("friend_lists/$account.json"))
        scannerStore = store
        scanner = try {
            FriendListScanner(store.load(), store.loadAll()).also {
                if (!it.hasScannedAll) it.refresh(now(), full = true)
            }
        } catch (_: Exception) {
            scannerStore = null // Preserve an unreadable cache instead of overwriting it or starting another scan.
            SkyMyce.logger.warn("Could not read saved friend list; refresh Party Finder to scan for this session")
            FriendListScanner(emptyList())
        }
        savedScannerVersion = scanner.version
        scanner.all.values.toList().filter { FriendsAPI.isBestFriend(it.name) }.forEach { scanner.bestFriend(it.name, true) }
        nextScannerSave = 0L
    }

    private fun saveFriends(force: Boolean = false) {
        val store = scannerStore ?: return
        if (!scanner.hasScanned || scanner.version == savedScannerVersion ||
            (!force && (scanner.scanning || now() < nextScannerSave))) return
        nextScannerSave = now() + 5000
        // Snapshot here (the scanner keeps changing); serialize and write off the render thread unless forced.
        val online = scanner.savedFriends.toList(); val all = scanner.savedAllFriends?.toList()
        if (!force) {
            val version = scanner.version
            val writer = friendWriter ?: me.mycellium.skymyce.utils.StateWriter<Pair<List<OnlineDungeonFriend>, List<OnlineDungeonFriend>?>>(
                { (o, a) -> store.save(o, a) }, { job -> tech.thatgravyboat.skyblockapi.utils.Scheduling.schedule(kotlin.time.Duration.ZERO) { job() } },
                { MC.instance.execute { savedScannerVersion = -1 }; SkyMyce.logger.warn("Could not save friend list; will retry saving") }).also { friendWriter = it }
            writer.submit(online to all); savedScannerVersion = version
            return
        }
        try {
            store.save(online, all)
            savedScannerVersion = scanner.version
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not save friend list; will retry saving")
        }
    }
    private var friendWriter: me.mycellium.skymyce.utils.StateWriter<Pair<List<OnlineDungeonFriend>, List<OnlineDungeonFriend>?>>? = null

    fun now(): Long = System.nanoTime() / 1000000
    fun diagnostics(): Map<String, Long> = mapOf("friends.cached" to scanner.all.size.toLong(), "friends.online" to scanner.online.size.toLong(),
        "friends.scan" to if (scanner.scanning) 1L else 0L, "friends.join_work" to joining.pendingCount.toLong(),
        "friends.fallback" to fallbackMessages.size.toLong(), "friends.offers" to (sentOffers.size + receivedOffers.size).toLong(),
        "friends.titles" to joinedTitles.size.toLong())

    private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
    private val FRIEND_NOTICE = Regex("^Friend > $PLAYER (joined|left)(?:\\.| the (?:server|network)[!.]?)?$")
    private val NICKNAME = Regex("^(\\S+)\\* is ")
}
