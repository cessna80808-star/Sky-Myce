package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

/** Hypixel's chat limit; vanilla's chat box already enforces it, so emoji names may only fill the remaining room. */
internal const val SERVER_CHAT_LIMIT = 256

/** Received server chat with exact `:name:` tokens painted as emoji; null leaves the original component untouched. */
internal fun ircShortcodeComponent(component: Component, glyph: (String) -> String?): Component? {
    val output = Component.empty()
    var changed = false
    component.visit({ style: Style, text: String ->
        val runs = ircShortcodeRuns(text, glyph)
        if (runs == null) output.append(Component.literal(text).withStyle(style))
        else {
            changed = true
            // Click and hover events stay on the glyph; only the font and tint change so the emoji keeps its colours.
            for ((run, emoji) in runs) output.append(Component.literal(run).withStyle(if (emoji) style.withFont(ircEmojiStyle.font)
                .withColor(0xFFFFFF).withBold(false).withItalic(false).withObfuscated(false) else style))
        }
        Optional.empty<Unit>()
    }, Style.EMPTY)
    return if (changed) output else null
}

/** Shared emoji names and recents; normal server chat sends readable `:names:` and shows received ones as emoji. */
object IrcEmojiChat : SkyMyceModule() {
    @Volatile internal var catalog: IrcEmojiCatalog? = null
        private set
    /** Fully qualified emoji text, newest first. */
    internal var recent = emptyList<String>()
        private set
    internal var revision = 0
        private set
    private var loaded = false
    private var edited = false
    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("irc_emoji_recent.json"), 4096) }
    private val writer by lazy {
        StateWriter<List<String>>({ value ->
            file.write(JsonObject().apply { addProperty("version", 1); add("recent", JsonArray().apply { value.forEach(::add) }) })
        }, { work -> Scheduling.schedule(500.milliseconds) { work() } },
            { SkyMyce.logger.warn("Could not save recent emoji; previous file retained") }).also { writer ->
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Recent-Emoji"))
        }
    }

    override fun init() {
        // Character names for ~4000 sequences: built once, off the render thread.
        Scheduling.schedule(0.milliseconds) {
            val built = runCatching { IrcEmojiCatalog(ircEmojiKeys) }
                .onFailure { SkyMyce.logger.warn("Emoji names unavailable; :shortcodes: and the emoji picker are disabled", it) }.getOrNull()
            val saved = runCatching { parseIrcRecentEmoji(file.read()) }.getOrElse { file.preserveOriginal(); emptyList() }
            MC.instance.execute {
                catalog = built
                if (!edited) recent = saved.filter { built?.text(it) != null }
                loaded = true
                revision++
                if (edited) writer.submit(recent)
            }
        }
        // IRC and relay messages are consumed before these modify hooks, so they keep real Unicode.
        ClientSendMessageEvents.MODIFY_CHAT.register { serverText(it, SERVER_CHAT_LIMIT) }
        ClientSendMessageEvents.MODIFY_COMMAND.register { command ->
            // The command arrives without "/", which still counts toward the chat box limit.
            if (ircFreeTextCommand(command)) serverText(command, SERVER_CHAT_LIMIT - 1) else command
        }
    }

    internal fun pick(text: String) {
        recent = ircRecentEmoji(recent, text)
        revision++
        edited = true
        if (loaded) writer.submit(recent)
    }

    /** IRC keeps real Unicode: pasted or untouched known `:names:` become the emoji when sent. */
    internal fun ircText(text: String): String = catalog?.let { ircReplaceShortcodes(text, it::shortcode) } ?: text

    private fun serverText(text: String, limit: Int): String {
        val names = catalog ?: return text
        return if (GlobalChatConfig.emojiInNormalChat) ircEmojiToShortcodes(text, limit, names::serverShortcode) else text
    }

    @Subscription
    fun onChat(event: ChatReceivedEvent.Post) {
        // IRC history is added through the same chat path but already paints real emoji itself.
        if (!GlobalChatConfig.emojiInNormalChat || IrcChatRendering.addingMessage() || ':' !in event.text) return
        val names = catalog ?: return
        ircShortcodeComponent(event.component, names::exactGlyph)?.let { event.component = it }
    }
}
