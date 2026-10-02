package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.loader.LoaderClient
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

internal val lfgButtonAction = Regex("^(yes|no) ([a-f0-9]{16})$")
private val chatChannelCommand = Regex("(?i)^chat\\s+(?:a|all|p|party|g|guild|o|officer|c|co|coop|co-op)$")
internal fun userMessageError(name: String, message: String): String? = when {
    !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
    message.isBlank() || message.length > 256 -> "Messages must contain 1–256 characters."
    message.any { it.isISOControl() || it == '§' } -> "Messages cannot contain control or formatting codes."
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
    private val ircSeen = linkedSetOf<String>()
    private var ircNextSend = 0L
    private val global = GlobalChatState()
    private var globalSubscription: Boolean? = null
    private var globalAccount: java.util.UUID? = null
    private var globalNextSend = 0L
    private val conversations = RelayConversation()
    private val quiet = ThreadLocal.withInitial { false }
    private var nextSend = 0L
    @JvmStatic fun suppressChatLog() = quiet.get()
    // Reconnecting the relay must not silently turn a private conversation into public chat.
    fun reset() { conversations.relayDisconnected(); nextSend = 0 }
    private fun session() { conversations.session(MC.instance.user.profileId.toString()) }
    fun init() {
        ClientSendMessageEvents.ALLOW_CHAT.register { text ->
            session()
            if (!irc.chat(text) { if (irc.global) sendGlobal(it) else sendIrc(it) }) return@register false
            conversations.allowChat(text, ::send)
        }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            val wasIrc = irc.selected && !irc.global
            val allow = irc.command(command)
            if (!allow) {
                conversations.leaveChat()
                if (irc.global) {
                    if (wasIrc) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to false))
                    globalSelected()
                } else {
                    DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to true))
                    display(Component.literal("Sky-Myce IRC selected. /chat p or /chat a exits. Messages stay private if delivery fails.").withColor(HudTheme.ACCENT))
                }
            } else if (wasIrc && !irc.selected) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to false))
            allow
        }
        ClientSendMessageEvents.COMMAND.register { command ->
            if (conversations.channelCommand(command)) chatClosed()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            conversations.clear(); nextSend = 0; irc.selected = false; ircSeen.clear(); globalSubscription = null
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
        if (irc.selected) {
            if (!irc.global) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to false))
            irc.selected = false
        }
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
        if (irc.selected) {
            if (!irc.global) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to false))
            irc.selected = false
        }
        conversations.leaveChat()
        chatClosed()
    }
    private fun chatClosed() = display(Component.literal("Relay chat off. Normal messages use your Hypixel chat channel.").withColor(HudTheme.MUTED))
    fun ircReady() { if (irc.selected && !irc.global) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to true)) }
    fun ircError() = error("Sky-Myce IRC could not deliver the message. Nothing was sent to public chat.")
    private fun sendIrc(text: String) {
        if (userMessageError("IRC", text) != null || System.nanoTime() < ircNextSend) { ircError(); return }
        ircNextSend = System.nanoTime() + 1_000_000_000
        if (!DungeonFriendRelay.ircPacket("irc_send", mapOf("text" to text))) ircError()
    }

    /** Only changed preferences serialize a subscription; incoming messages remain event-driven. */
    fun globalTick() {
        val account = MC.instance.user.profileId
        if (globalAccount != account) {
            globalAccount = account
            global.account(account.toString())
            globalNextSend = 0L
        }
        if (!DungeonFriendRelay.connected || !DungeonFriendRelay.globalChatAvailable) return
        val enabled = GlobalChatConfig.enabled
        if (globalSubscription != enabled && DungeonFriendRelay.globalPacket("irc_subscribe", mapOf("enabled" to enabled))) {
            globalSubscription = enabled
        }
    }

    fun globalReady() { globalSubscription = null; globalTick() }

    fun selectGlobal() {
        session()
        if (irc.selected && !irc.global) DungeonFriendRelay.ircPacket("irc_subscribe", mapOf("enabled" to false))
        irc.command("chat sky")
        conversations.leaveChat()
        globalSelected()
        val client = MC.instance
        val connection = client.connection
        val account = client.user.profileId
        client.schedule {
            if (client.connection === connection && client.user.profileId == account && client.player != null &&
                irc.selected && irc.global && (client.screen == null || client.screen is ChatScreen)) {
                client.setScreen(ChatScreen("", false))
            }
        }
    }

    private fun globalSelected() {
        globalTick()
        display(Component.literal(if (GlobalChatConfig.enabled)
            "Global SkyMyce chat selected. /chat a exits input; receiving stays enabled. Failed sends stay private."
        else "Global chat is disabled. Enable General → Global SkyMyce Chat; /chat a exits input.").withColor(HudTheme.ACCENT))
    }

    fun sendGlobal(text: String) {
        globalMessageError(text)?.let { error(it); return }
        if (!GlobalChatConfig.enabled) { error("Global chat is disabled. Enable it in General → Global SkyMyce Chat."); return }
        if (!LoaderClient.onlineAllowed) { error("SkyMyce online authorization is required for global chat."); return }
        if (MC.instance.player == null || MC.connection == null || !DungeonFriendRelay.connected) {
            error("Global relay unavailable. Reconnect and retry; nothing was sent to normal chat."); return
        }
        if (!DungeonFriendRelay.globalChatAvailable) { error("This relay does not support global chat yet."); return }
        val now = System.nanoTime()
        if (now < globalNextSend) { error("Wait a moment before sending another global message."); return }
        if (DungeonFriendRelay.globalPacket("irc_send", mapOf("text" to text))) globalNextSend = now + 500_000_000
        else globalError(null)
        // The authoritative broadcast includes the sender; no local echo or automatic resend.
    }

    fun globalError(reason: String?) = error(when (reason) {
        "rate_limited" -> "Global chat rate limit reached. Wait briefly and retry."
        "duplicate_message", "duplicate" -> "That global message was already sent."
        "invalid_message" -> "Global chat could not accept that message."
        "global_chat_disabled", "irc_disabled" -> "Global Discord chat is not configured on this relay."
        else -> "Global chat could not deliver the message. Nothing was sent to normal chat."
    })

    fun receiveGlobal(json: com.google.gson.JsonObject, deliveredTo: String) {
        val client = MC.instance
        val delivery = global.receive(json, deliveredTo, client.user.profileId.toString(), GlobalChatConfig.enabled,
            LoaderClient.onlineAllowed && client.player != null && client.connection != null, GlobalChatConfig.mentionSounds) ?: return
        val message = delivery.message
        message.reply?.let { reply ->
            val preview = reply.text?.let { splitGlobalChatText(it.replace('\n', ' '), 96, 1).firstOrNull() }
            display(Component.literal("↩ ${reply.from}${preview?.let { ": $it" }.orEmpty()}").withColor(HudTheme.MUTED), "[SkyMyce] ")
        }
        val name = if (message.source == "discord") "@${message.from}" else message.from
        val heading = "$name: "
        val links = globalChatSpans(message.text).mapNotNull { it.url?.toString() }.toSet()
        for (line in splitGlobalChatText(message.text, 256 - 10 - heading.length)) {
            val component = Component.literal(heading).withColor(if (message.mention) HudTheme.ACCENT else HudTheme.SECONDARY)
            for (span in globalChatSpans(line, links)) {
                val part = Component.literal(span.text).withColor(HudTheme.TEXT)
                if (span.url != null) part.withStyle {
                    it.withClickEvent(ClickEvent.OpenUrl(span.url)).withHoverEvent(HoverEvent.ShowText(Component.literal("Open link")))
                }
                component.append(part)
            }
            display(component, "[SkyMyce] ")
        }
        if (delivery.ping) client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.25f))
    }
    fun receiveIrc(json: com.google.gson.JsonObject) {
        if (!irc.selected || irc.global || !me.mycellium.skymyce.loader.LoaderClient.onlineAllowed) return
        val id = json.get("id").asString
        val source = json.get("source").asString
        val name = json.get("from").asString
        val text = json.get("text").asString
        require(id.matches(Regex("[a-f0-9-]{32,36}")) && source in setOf("minecraft", "discord"))
        require(name.length in 1..80 && name.none { it.isISOControl() || it == '§' })
        require(userMessageError("IRC", text) == null)
        if (!ircSeen.add(id)) return
        while (ircSeen.size > 256) ircSeen.remove(ircSeen.first())
        display(Component.literal("[${if (source == "discord") "Discord" else "IRC"}] $name: ").withColor(HudTheme.SECONDARY)
            .append(Component.literal(text).withColor(HudTheme.TEXT)))
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
