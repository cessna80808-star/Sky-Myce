package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.community.CommunityClient
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import tech.thatgravyboat.skyblockapi.api.events.hypixel.PartyInfoEvent

internal val lfgButtonAction = Regex("^(yes|no) ([a-f0-9]{16})$")
internal val relayChatExit = Regex("(?i)^(?:sm|skymyce)\\s+chat$")
internal fun ircHistoryBoundary(account: java.util.UUID, endpoint: String) = "$account|$endpoint"
private val chatChannelCommand = Regex("(?i)^chat\\s+(?:a|all|p|party|g|guild|o|officer|c|co|coop|co-op)$")
internal fun userMessageError(name: String, message: String): String? = when {
    !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
    message.isBlank() || message.length > 256 -> "Messages must contain 1–256 characters."
    message.any { it.isISOControl() || it == '§' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } -> "Messages cannot contain control or formatting codes."
    else -> null
}
internal fun relayRecipients(names: List<String>, prefix: String) = names.filter {
    it.matches(Regex("[A-Za-z0-9_]{1,16}")) && it.startsWith(prefix, true)
}.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.take(30)
internal class RelayConversation {
    private var session = ""
    var replyTarget: String? = null
        private set
    var chatTarget: String? = null
        private set
    fun session(key: String) { if (key != session) { clear(); session = key } }
    fun conversation(name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) replyTarget = name }
    fun chat(name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) chatTarget = name }
    fun leaveChat() { chatTarget = null }
    fun channelCommand(command: String): Boolean {
        if (chatTarget == null || !chatChannelCommand.matches(command.trim())) return false
        leaveChat()
        return true
    }
    fun allowChat(text: String, send: (String, String) -> Unit): Boolean {
        val target = chatTarget ?: return true
        send(target, text)
        return false // Even failed delivery must never fall through to public chat.
    }
    fun relayDisconnected() { replyTarget = null }
    fun clear() { session = ""; replyTarget = null; chatTarget = null }
}

/** Ordinary private messages never enter the LFG parser and never fall back to Hypixel chat. */
object RelayMessages {
    private val irc = IrcMode()
    private val global = GlobalChatState()
    private val globalSubscription = GlobalChatSubscription()
    private val partySubscription = GlobalChatSubscription()
    internal val chatStore = IrcChatStore()
    private val partySeen = linkedSetOf<String>()
    private var partySeenBoundary = ""
    private var richBoundary = ""
    private var richSessionBoundary = ""
    private var verifiedParty: IrcPartyRoster? = null
    private var verifiedPartyAt = 0L
    private var activeParty = ""
    private var partySubscribedId = ""
    private var partySubscribedUntil = 0L
    private var nextPartySubscribe = 0L
    internal val partyId get() = livePartyRoster()?.id
    private var replyDraft: IrcKey? = null
    private val reactionRequests = IrcReactionRequests()
    private var globalAccount: java.util.UUID? = null
    private var globalNextSend = 0L
    private var nextIrcDiagnostic = Long.MIN_VALUE
    private val conversations = RelayConversation()
    private val quiet = ThreadLocal.withInitial { false }
    private var nextSend = 0L
    @JvmStatic fun suppressChatLog() = quiet.get()
    // Reconnecting the relay must not silently turn a private conversation into public chat.
    fun reset() {
        reactionRequests.clear(); IrcInlineControls.clear(); chatStore.clearPending()
        conversations.relayDisconnected(); nextSend = 0; globalSubscription.reset(); partySubscription.reset(); replyDraft = null
        partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0
        if (MC.screen is IrcMediaViewerScreen || MC.screen is IrcMessageControlsScreen) MC.instance.setScreen(null)
    }
    private fun session() {
        val account = MC.instance.user.profileId
        irc.account(account)
        conversations.session(account.toString())
    }
    fun init() {
        IrcChatRendering.init()
        ClientSendMessageEvents.ALLOW_CHAT.register { text ->
            if (suppressChatLog() || IrcChatRendering.addingMessage()) return@register false
            session()
            if (!irc.chat(text) { if (irc.party) sendParty(it) else sendGlobal(it) }) return@register false
            conversations.allowChat(text, ::send)
        }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            // This pre-dispatch exit stays available when protected command authorization expires.
            if (relayChatExit.matches(command.trim())) {
                leaveChat()
                return@register false
            }
            val wasSelected = irc.selected
            val allow = irc.command(command)
            if (!allow) {
                conversations.leaveChat()
                enableGlobal()
                globalSelected()
            } else if (wasSelected && !irc.selected) chatClosed()
            allow
        }
        ClientSendMessageEvents.COMMAND.register { command ->
            if (conversations.channelCommand(command)) chatClosed()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            conversations.clear(); nextSend = 0; irc.selected = false; globalSubscription.reset(); partySubscription.reset()
            verifiedParty = null; verifiedPartyAt = 0; activeParty = ""; partySubscribedId = ""; partySubscribedUntil = 0
            clearRichHistory(""); richSessionBoundary = ""
        }
    }
    @JvmStatic fun selectedIrcComposer(): Boolean { session(); return irc.selected }
    @JvmStatic fun allowComposerChat(text: String): Boolean {
        return irc.composer(text, suppressChatLog() || IrcChatRendering.addingMessage()) {
            if (irc.party) sendParty(it) else sendGlobal(it)
        }
    }
    fun recipients(prefix: String): List<String> = relayRecipients(FriendsAPI.friends.map { it.name } + DungeonFriends.scanner.online.values.map { it.name }, prefix)
    fun send(name: String, text: String) {
        session()
        val problem = userMessageError(name, text)
        if (problem != null) { error(problem); return }
        recipientError(name)?.let { error(it); return }
        val now = System.currentTimeMillis()
        if (now < nextSend) { error("Wait a moment before sending another message."); return }
        nextSend = now + 1000
        val account = MC.instance.user.profileId
        val accepted = DungeonFriendRelay.sendUserMessage(name, text, {
            if (MC.instance.user.profileId == account && LocationAPI.isOnSkyBlock) {
                conversations.conversation(name)
                display(Component.literal("Delivered to $name ✓").withColor(HudTheme.GREEN))
            }
        }, { reason -> if (MC.instance.user.profileId == account) error(deliveryFailure(reason)) })
        if (accepted) display(Component.literal("Me → $name: ").withColor(HudTheme.ACCENT)
            .append(Component.literal(text).withColor(HudTheme.TEXT)))
        else error("Message queue unavailable. Nothing was sent to Hypixel chat.")
    }
    private fun recipientError(name: String): String? = when {
        !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
        !LocationAPI.isOnSkyBlock || MC.instance.player == null -> "Join SkyBlock before messaging friends."
        !DungeonFriends.isRelayFriend(name) -> "That player is not in your cached friends list."
        name.equals(MC.instance.user.name, true) -> "Choose another player."
        !DungeonFriendRelay.connected -> "Relay unavailable. Reconnect and try again; nothing was sent to Hypixel chat."
        !DungeonFriendRelay.userMessagesAvailable -> "This relay does not support private messages yet."
        else -> null
    }
    fun chat(name: String) {
        session()
        recipientError(name)?.let { error(it); return }
        irc.selected = false
        conversations.chat(name)
        display(Component.literal("Relay chat → $name. Type normally to message them; /chat a or /sm chat exits.").withColor(HudTheme.ACCENT))
        val client = MC.instance
        val connection = client.connection
        val account = client.user.profileId
        // Queue after vanilla closes the command input, without opening over another menu.
        client.schedule {
            if (client.connection === connection && client.user.profileId == account && client.player != null &&
                conversations.chatTarget == name && (client.screen == null || client.screen is ChatScreen)) {
                client.setScreen(ChatScreen("", false))
            }
        }
    }
    fun leaveChat() {
        irc.selected = false
        replyDraft = null
        conversations.leaveChat()
        chatClosed()
    }
    private fun chatClosed() = display(Component.literal("Relay chat off. Normal messages use your Hypixel chat channel.").withColor(HudTheme.MUTED))

    fun selectParty() = selectIrc("party")
    fun sendParty(text: String) = sendIrc("party", text)

    fun chatHelp() = notice("/sm irc — global Minecraft/Discord chat; /sm party — Minecraft-only Party IRC. Add a message to send once. Click ↩ to reply, + to react, or ⋯ to edit/delete your own message. /sm ircstatus shows status; /sm chat exits.")

    private fun enableGlobal() {
        if (GlobalChatConfig.enabled) return
        GlobalChatConfig.enabled = true
        runCatching { me.mycellium.skymyce.SkyMyce.config.save() }.onFailure {
            me.mycellium.skymyce.SkyMyce.logger.warn("[Global chat] Could not save the receiving preference")
        }
    }

    private fun enableParty() {
        if (GlobalChatConfig.partyEnabled) return
        GlobalChatConfig.partyEnabled = true
        runCatching { me.mycellium.skymyce.SkyMyce.config.save() }.onFailure {
            me.mycellium.skymyce.SkyMyce.logger.warn("[IRC] Could not save the receiving preference")
        }
    }

    internal fun partyInfo(event: PartyInfoEvent) {
        val client = MC.instance
        if (!client.isSameThread) {
            val connection = client.connection
            val account = client.user.profileId
            client.execute { if (client.connection === connection && client.user.profileId == account) partyInfo(event) }
            return
        }
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        val leaders = event.members.entries.filter { it.value.role.name == "LEADER" }
        verifiedParty = validatedIrcPartyRoster(event.inParty, event.members.keys.toList(), leaders.singleOrNull()?.key, MC.instance.user.profileId)
        verifiedPartyAt = System.nanoTime()
        refreshPartyBoundary()
    }
    private fun livePartyRoster(): IrcPartyRoster? {
        if (!LocationAPI.onHypixel || MC.instance.player == null || MC.connection == null || System.nanoTime() - verifiedPartyAt !in 0..65_000_000_000L) return null
        val verified = verifiedParty ?: return null
        if (MC.instance.user.profileId.toString().replace("-", "") !in verified.members) return null
        if (!PartyAPI.inParty || PartyAPI.members.size != verified.members.size || PartyAPI.leader?.uuid?.toString()?.replace("-", "") != verified.leader) return null
        val members = PartyAPI.members.map { it.uuid?.toString()?.replace("-", "") ?: return null }.sorted()
        return verified.takeIf { members == it.members && PartyAPI.members.count { member -> member.role.name == "LEADER" } == 1 }
    }
    private fun refreshPartyBoundary() {
        val id = if (GlobalChatConfig.partyEnabled) partyId.orEmpty() else ""
        if (id == activeParty) return
        val old = chatStore.values().filter { it.scope == "party" }
        activeParty = id; partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0; partySubscription.reset()
        if (replyDraft?.scope == "party") replyDraft = null
        chatStore.clearScope("party"); IrcChatRendering.clearScope("party")
        if ((MC.screen as? IrcMediaViewerScreen)?.messageKey?.scope == "party" || (MC.screen as? IrcMessageControlsScreen)?.scope == "party") MC.instance.setScreen(null)
        old.flatMap { it.media }.forEach { media -> IrcMediaAssets.cancel(media, false); IrcMediaAssets.cancel(media, true) }
    }

    private fun clearRichHistory(boundary: String) {
        if (richBoundary == boundary) return
        richBoundary = boundary
        chatStore.boundary(boundary)
        replyDraft = null
        partySubscribedId = ""; partySubscribedUntil = 0; nextPartySubscribe = 0
        IrcMediaAssets.reset(boundary)
        if (MC.screen is IrcMediaViewerScreen || MC.screen is IrcMessageControlsScreen) MC.instance.setScreen(null)
        IrcChatRendering.clear()
    }

    /** Changed preferences serialize one scoped subscription; settings also refresh existing chat rows. */
    fun globalTick() {
        val account = MC.instance.user.profileId
        val endpoint = me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl
        clearRichHistory(ircHistoryBoundary(account, endpoint))
        val sessionBoundary = "$richBoundary|${CommunityClient.sessionToken}|${DungeonFriendRelay.sessionGeneration}"
        if (richSessionBoundary != sessionBoundary) { richSessionBoundary = sessionBoundary; reset() }
        refreshPartyBoundary()
        val seenBoundary = "$account|$endpoint|$activeParty"
        if (partySeenBoundary != seenBoundary) { partySeenBoundary = seenBoundary; partySeen.clear() }
        if (globalAccount != account) {
            irc.account(account); globalAccount = account; global.account(account.toString())
            globalNextSend = 0L; globalSubscription.reset(); partySubscription.reset()
            verifiedParty = null; verifiedPartyAt = 0; refreshPartyBoundary()
        }
        chatStore.expire(System.currentTimeMillis())
        IrcChatRendering.tick()
        if (!DungeonFriendRelay.connected) return
        for (scope in listOf("global", "party")) {
            if (!(if (scope == "global") DungeonFriendRelay.globalChatAvailable else DungeonFriendRelay.partyChatAvailable)) continue
            val subscription = if (scope == "global") globalSubscription else partySubscription
            val roster = if (scope == "party") livePartyRoster() else null
            val enabled = if (scope == "global") GlobalChatConfig.enabled else GlobalChatConfig.partyEnabled && roster != null
            if (scope == "party" && enabled && System.nanoTime() >= nextPartySubscribe) subscription.reset()
            val revision = subscription.take(enabled, System.nanoTime()) ?: continue
            val fields = buildMap<String, Any> {
                put("enabled", enabled)
                if (DungeonFriendRelay.richChatAvailable) put("richChatVersion", 1)
                if (scope == "party" && roster != null && enabled) { put("members", roster.members); put("leader", roster.leader) }
            }
            var grantMillis = 0L
            if (DungeonFriendRelay.ircPacket(scope, "irc_subscribe", fields, currentGlobalResult { reason ->
                if (scope == "party" && subscription.current(revision)) {
                    if (reason == null && enabled && roster?.id == activeParty) {
                        partySubscribedId = roster.id; partySubscribedUntil = System.nanoTime() + grantMillis * 1_000_000L
                        nextPartySubscribe = System.nanoTime() + ircPartyRefreshMillis(grantMillis) * 1_000_000L
                    } else { partySubscribedId = ""; partySubscribedUntil = 0 }
                }
                if (subscription.complete(revision, reason, System.nanoTime())) ircError(scope, reason, "subscription receipt")
            }, receipt = { response -> grantMillis = ircPartyGrantMillis(response, roster?.id, System.currentTimeMillis()) ?: 0L })) {
                subscription.queued(revision, enabled)
                if (scope == "party") nextPartySubscribe = System.nanoTime() + 30_000_000_000L
            }
        }
    }

    fun globalReady() { reactionRequests.clear(); IrcInlineControls.clear(); globalSubscription.reset(); partySubscription.reset(); globalTick() }

    /** Late receipts cannot affect a different login, endpoint, authorization or reconnect session. */
    private fun currentGlobalResult(action: (String?) -> Unit): (String?) -> Unit {
        val client = MC.instance
        val account = client.user.profileId
        val lease = CommunityClient.sessionToken
        val endpoint = me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl
        val generation = DungeonFriendRelay.sessionGeneration
        return { reason ->
            if (client.user.profileId == account && CommunityClient.sessionToken == lease &&
                me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings.relayUrl == endpoint &&
                DungeonFriendRelay.sessionGeneration == generation && CommunityClient.connected &&
                client.player != null && client.connection != null) action(reason)
        }
    }

    fun selectGlobal() = selectIrc("global")
    private fun selectIrc(scope: String, openComposer: Boolean = true, announce: Boolean = true) {
        session(); conversations.leaveChat()
        if (scope == "global") { irc.command("chat sky"); enableGlobal() }
        else { irc.selectParty(); enableParty(); DungeonFriends.requestIrcPartyInfo() }
        if (replyDraft?.scope != scope) replyDraft = null
        globalTick()
        if (announce) notice("${if (scope == "global") "Global chat" else "Minecraft-only Party IRC"} selected. /sm chat returns to normal chat.")
        if (!openComposer) return
        val client = MC.instance
        val connection = client.connection
        val account = client.user.profileId
        client.schedule {
            if (client.connection === connection && client.user.profileId == account && client.player != null &&
                irc.selected && (if (scope == "global") irc.global else irc.party) && (client.screen == null || client.screen is ChatScreen)) {
                val screen = client.screen as? ChatScreen
                if (screen != null) IrcInlineControls.focusComposer(screen) else client.setScreen(ChatScreen("", false))
            }
        }
    }
    private fun globalSelected() { globalTick(); notice("Global chat selected. /sm chat returns to normal chat.") }

    fun sendGlobal(text: String) = sendIrc("global", text)
    private fun sendIrc(scope: String, text: String) {
        if (suppressChatLog() || IrcChatRendering.addingMessage()) return
        val problem = ircClientMessageError(scope, text)
        if (problem != null) { notice(problem, true); return }
        if (!CommunityClient.connected) { notice("Connect Discord in Community settings to use IRC.", true); return }
        if (scope == "global") enableGlobal() else enableParty()
        if (MC.instance.player == null || MC.connection == null || !DungeonFriendRelay.currentGlobalSession()) {
            notice("IRC is unavailable. Reconnect and retry; nothing was sent to normal chat.", true); return
        }
        if (!(if (scope == "global") DungeonFriendRelay.globalChatAvailable else DungeonFriendRelay.partyChatAvailable)) {
            notice("This relay does not support this IRC channel.", true); return
        }
        val now = System.nanoTime()
        if (now < globalNextSend) { notice("Wait a moment before sending another IRC message.", true); return }
        globalTick()
        val subscription = if (scope == "global") globalSubscription else partySubscription
        if (scope == "party" && livePartyRoster() == null) {
            DungeonFriends.requestIrcPartyInfo(); notice(partyRosterError(), true); return
        }
        if (!subscription.enabled) { ircError(scope, "not_subscribed", "subscription pending"); return }
        if (scope == "party" && !currentIrcChannel(IrcKey("party", partyId.orEmpty(), ""))) {
            notice("Party IRC needs a complete current Hypixel roster and a confirmed subscription. Wait for party info, then retry.", true); return
        }
        val draft = replyDraft?.takeIf { it.scope == scope && currentIrcChannel(it) && chatStore.get(it)?.deleted == false }
        val fields = buildMap<String, Any> {
            put("text", text)
            if (draft != null && DungeonFriendRelay.richChatAvailable) put("replyTo", draft.id)
        }
        val sendPartyId = if (scope == "party") partyId else null
        if (DungeonFriendRelay.ircPacket(scope, "irc_send", fields, currentGlobalResult { reason ->
            if (scope == "party" && partyId != sendPartyId) return@currentGlobalResult
            if (reason != null) {
                if (reason == "not_subscribed") subscription.reset()
                ircError(scope, reason, "send receipt")
            }
        })) { globalNextSend = now + 500_000_000; replyDraft = null }
        else ircError(scope, null, "send queue")
        // Only the authoritative broadcast renders a send. Uncertain deliveries are never resent.
    }

    private fun ircError(scope: String, reason: String?, operation: String = "relay") {
        val now = System.nanoTime()
        if (now >= nextIrcDiagnostic) {
            nextIrcDiagnostic = now + 60_000_000_000L
            // Fixed failure descriptions keep chat bodies and arbitrary server error text out of logs.
            me.mycellium.skymyce.SkyMyce.logger.warn("[IRC] {} {} failed: {} connected={} currentSession={} writePending={}",
                scope, operation, globalDeliveryError(reason), DungeonFriendRelay.connected,
                DungeonFriendRelay.currentGlobalSession(), DungeonFriendRelay.diagnostics()["relay.writePending"])
        }
        notice(globalDeliveryError(reason).replace("Global chat", if (scope == "party") "Party IRC" else "Global chat"), true)
    }
    fun globalError(reason: String?) = ircError("global", reason)
    internal fun currentIrcChannel(key: IrcKey): Boolean = DungeonFriendRelay.currentGlobalSession() &&
        MC.instance.player != null && MC.connection != null && if (key.scope == "global") {
            key.room == "global-chat" && GlobalChatConfig.enabled && DungeonFriendRelay.globalChatAvailable && globalSubscription.enabled
        } else key.scope == "party" && key.room == partyId && key.room == partySubscribedId &&
            System.nanoTime() < partySubscribedUntil && GlobalChatConfig.partyEnabled && DungeonFriendRelay.partyChatAvailable && partySubscription.enabled

    fun receiveGlobal(json: com.google.gson.JsonObject, deliveredTo: String) {
        val client = MC.instance
        val delivery = global.receive(json, deliveredTo, client.user.profileId.toString(), GlobalChatConfig.enabled,
            DungeonFriendRelay.currentGlobalSession() && DungeonFriendRelay.globalChatAvailable && client.player != null && client.connection != null,
            GlobalChatConfig.mentionSounds) ?: return
        acceptIrc(delivery.message, delivery.ping)
    }
    fun receiveParty(json: com.google.gson.JsonObject, deliveredTo: String) {
        val roster = livePartyRoster() ?: return
        if (deliveredTo != MC.instance.user.profileId.toString() || !currentIrcChannel(IrcKey("party", roster.id, ""))) return
        val message = parsePartyIrc(json, roster.id) ?: return
        if (message.sender?.uuid !in roster.members) return
        if (!partySeen.add(message.id)) return
        while (partySeen.size > 512) partySeen.remove(partySeen.first())
        acceptIrc(message, message.mention && GlobalChatConfig.mentionSounds)
    }
    private fun acceptIrc(message: GlobalChatMessage, ping: Boolean) {
        if (message.model == 1 && !DungeonFriendRelay.richChatAvailable || !chatStore.accept(message, System.currentTimeMillis())) return
        val current = chatStore.get(message.key) ?: message
        IrcChatRendering.add(current)
        if (ping && !current.deleted) MC.instance.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.25f))
    }
    fun receiveUpdate(json: com.google.gson.JsonObject, deliveredTo: String) {
        if (deliveredTo != MC.instance.user.profileId.toString() || !DungeonFriendRelay.richChatAvailable) return
        val update = parseIrcUpdate(json) ?: return
        if (!currentIrcChannel(update.key)) return
        val previous = chatStore.get(update.key)
        if (previous == null) { chatStore.apply(update, System.currentTimeMillis()); return }
        val next = chatStore.apply(update, System.currentTimeMillis()) ?: return
        reactionRequests.observe(next)
        if (next.deleted && replyDraft == next.key) replyDraft = null
        if (previous.media != next.media || next.deleted) {
            if ((MC.screen as? IrcMediaViewerScreen)?.messageKey == next.key) MC.instance.setScreen(MC.instance.gui.chat.restoreChatScreen())
            previous.media.filter { it !in next.media }.forEach { media -> IrcMediaAssets.cancel(media, false); IrcMediaAssets.cancel(media, true) }
        }
        IrcChatRendering.update(next)
    }
    internal fun key(scope: String, id: String) = IrcKey(scope, when (scope) { "global" -> "global-chat"; "party" -> partyId.orEmpty(); else -> DungeonFriendRelay.ircRoom }, id)
    fun chatAction(command: String, mouseX: Int? = null, mouseY: Int? = null) {
        val action = parseIrcChatAction(command) ?: return
        val key = key(action.scope, action.id)
        val message = chatStore.get(key)?.takeUnless { it.deleted } ?: return
        if (!currentIrcChannel(key)) return
        if (message.model == 1 && DungeonFriendRelay.richChatAvailable) {
            if (action.kind == "reply") { prepareReply(key); return }
            if (action.kind == "react") {
                if (MC.screen !is ChatScreen) MC.instance.setScreen(ChatScreen("", false))
                IrcInlineControls.open(key, mouseX ?: 4, mouseY ?: (MC.instance.window.guiScaledHeight - 48))
                return
            }
        }
        if (MC.screen is ChatScreen) MC.instance.gui.chat.preserveCurrentChatScreen()
        if (action.kind == "media") {
            val media = message.media.getOrNull(action.mediaIndex ?: return) ?: return
            val context = message.reply?.let { "Reply to ${it.from}: ${it.text.orEmpty().replace('\n', ' ').take(96)}" }
                ?: message.text.replace('\n', ' ').take(160)
            MC.instance.setScreen(IrcMediaViewerScreen(MC.screen, media, message.from, "${ircPrefix(message.scope)} $context", key))
        } else if (action.kind == "manage" && canManage(key))
            MC.instance.setScreen(IrcMessageControlsScreen(MC.screen, key))
    }
    internal fun prepareReply(key: IrcKey) {
        if (!currentIrcChannel(key) || chatStore.get(key)?.deleted != false || !DungeonFriendRelay.richChatAvailable) return
        if (key.scope !in setOf("global", "party")) return
        selectIrc(key.scope, openComposer = false, announce = false)
        replyDraft = key
        IrcInlineControls.clear()
        val screen = MC.screen as? ChatScreen
        if (screen != null) IrcInlineControls.focusComposer(screen)
        else MC.instance.setScreen(ChatScreen("", false))
    }
    internal fun replyMessage(): GlobalChatMessage? = replyDraft?.takeIf {
        irc.selected && (if (it.scope == "global") irc.global else irc.party) && currentIrcChannel(it)
    }?.let { chatStore.get(it)?.takeUnless { message -> message.deleted } }
    fun cancelReply() { replyDraft = null; notice("Reply cancelled.") }
    internal fun canManage(key: IrcKey): Boolean = currentIrcChannel(key) && DungeonFriendRelay.richChatAvailable &&
        chatStore.manageable(key, MC.instance.user.profileId.toString().replace("-", ""), System.currentTimeMillis())
    internal fun operation(key: IrcKey, operation: String, text: String? = null, emoji: String? = null, active: Boolean? = null): Boolean {
        val message = chatStore.get(key)?.takeUnless { it.deleted } ?: return false
        if (!currentIrcChannel(key) || !DungeonFriendRelay.richChatAvailable || message.model != 1) return false
        if (operation in setOf("edit", "delete") && !canManage(key)) return false
        if (operation !in setOf("edit", "delete", "reaction") || operation == "edit" && (text == null || ircClientMessageError(key.scope, text) != null) ||
            operation == "reaction" && (emoji !in IRC_REACTIONS || active == null)) return false
        if (operation == "reaction" && !reactionRequests.start(key, emoji!!, active!!, System.currentTimeMillis())) return false
        val fields = buildMap<String, Any> {
            put("messageId", key.id); put("operation", operation)
            text?.let { put("text", it) }; emoji?.let { put("emoji", it) }; active?.let { put("active", it) }
        }
        return DungeonFriendRelay.ircPacket(key.scope, "irc_operation", fields, currentGlobalResult { reason ->
            if (reason != null && operation == "reaction") reactionRequests.finish(key, emoji!!)
            if (reason != null && currentIrcChannel(key) && chatStore.get(key) != null) notice(ircOperationError(reason), true)
        }).also { if (!it) {
            if (operation == "reaction") reactionRequests.finish(key, emoji!!)
            notice("Message control unavailable. Reconnect and try again.", true)
        } }
    }
    fun ircStatus() {
        val partyStatus = if (!GlobalChatConfig.partyEnabled) "off" else if (livePartyRoster() == null) partyRosterError()
            else if (currentIrcChannel(IrcKey("party", partyId.orEmpty(), ""))) "in-game only" else "waiting for confirmed subscription"
        notice("${DungeonFriendRelay.status}. Global: ${if (globalSubscription.enabled && GlobalChatConfig.enabled) "receiving" else "off"}; Party: $partyStatus. ${if (DungeonFriendRelay.richChatAvailable) "Media and message controls supported." else "Legacy text/link compatibility."}")
    }
    private fun partyRosterError() = ircPartyRosterError(LocationAPI.onHypixel, PartyAPI.inParty, PartyAPI.members.size,
        verifiedParty?.let { (System.nanoTime() - verifiedPartyAt) / 1_000_000L })
    internal fun notice(text: String, failed: Boolean = false) = display(IrcChatRendering.prefixComponent("room")
        .append(Component.literal(text).withColor(if (failed) HudTheme.RED else HudTheme.MUTED)), "")
    internal fun <T> quietChat(action: () -> T): T {
        val previous = quiet.get(); quiet.set(true)
        return try { action() } finally { quiet.set(previous) }
    }
    fun reply(text: String) {
        session()
        val name = conversations.replyTarget
        if (name == null) error("No current conversation. Use /sm msg <ign> <message> first.") else send(name, text)
    }
    fun receive(name: String, uuid: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock || !DungeonFriends.acceptsRelayIdentity(name, uuid) || userMessageError(name, text) != null) return false
        session(); conversations.conversation(name)
        val reply = Component.literal("  [Reply]").withColor(HudTheme.ACCENT).withStyle {
            it.withClickEvent(ClickEvent.SuggestCommand("/sm msg $name "))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Prepare a private reply to $name. Clicking does not send it.")))
        }
        display(Component.literal("$name → Me: ").withColor(HudTheme.SECONDARY)
            .append(Component.literal(text).withColor(HudTheme.TEXT)).append(reply))
        return true
    }
    fun legacy(name: String, text: String) {
        if (lfgButtonAction.matches(text)) action(name, text)
        else { display(Component.literal("/sm relaymsg is deprecated; use /sm msg.").withColor(HudTheme.MUTED)); send(name, text) }
    }
    fun action(name: String, text: String) {
        if (!lfgButtonAction.matches(text) || !DungeonFriends.relayReply(name, text)) error("Invalid or unavailable LFG action. Open a current invitation.")
    }
    private fun error(text: String) = display(Component.literal(text).withColor(HudTheme.RED))
    private fun display(message: Component, prefix: String = "[Sky-Hawk] ") {
        val previous = quiet.get(); quiet.set(true)
        try { MC.instance.gui.chat.addClientSystemMessage(Component.literal(prefix).withColor(HudTheme.ACCENT).append(message)) }
        finally { quiet.set(previous) }
    }
}

internal fun deliveryFailure(reason: String) = when (reason) {
    "offline" -> "Recipient is offline on this relay. Message was not delivered."
    "recipient_disallowed" -> "Recipient's friend/identity check rejected the message."
    "recipient_unsupported" -> "Recipient needs a version supporting private messages."
    "rate_limited" -> "Relay rate limit reached. Wait a few seconds and retry."
    "recipient_busy" -> "Recipient is busy. Try again shortly."
    "receipt_timeout" -> "No recipient acknowledgement arrived. Delivery is unconfirmed."
    else -> "Connection failed. Delivery is unconfirmed; nothing was sent to Hypixel chat."
}
