package me.mycellium.skymyce.features.instances.dungeons.friends

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import me.mycellium.mixin.IrcChatAccessor
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.browser.InGameBrowser
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuButtonIntent
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuLabel
import me.mycellium.skymyce.ui.MenuScreen
import me.mycellium.skymyce.ui.MenuTextBox
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.ActiveTextCollector
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.TextAlignment
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.entity.player.ChatVisiblity
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW

internal const val IRC_THUMBNAIL_WIDTH = 96
internal const val IRC_THUMBNAIL_HEIGHT = 72
private val ircThumbnailPlaceholder = Regex("\\[(?:(Image|GIF|Video) )?[1-4]][\\s\u00a0]*")
internal data class IrcThumbnailLayout(val width: Int, val height: Int, val rows: Int) {
    fun title(fullTitle: String, textWidth: Int, index: Int) = if (textWidth <= width) fullTitle else "[${index + 1}]"
}

/**
 * The preview box: at most the 4:3 thumbnail size, shrunk to the media's own aspect ratio when its dimensions are known,
 * so wide or tall images reserve no empty rows. Sized from metadata only, so the reserved rows match what is painted.
 */
internal fun ircThumbnailLayout(percent: Int, availableWidth: Int, lineHeight: Int, mediaWidth: Int? = null, mediaHeight: Int? = null): IrcThumbnailLayout {
    val requestedWidth = (IRC_THUMBNAIL_WIDTH * percent.coerceIn(50, 200) / 100.0).roundToInt()
    val maxWidth = minOf(requestedWidth, availableWidth.coerceAtLeast(1))
    val maxHeight = (maxWidth * IRC_THUMBNAIL_HEIGHT.toDouble() / IRC_THUMBNAIL_WIDTH).roundToInt().coerceAtLeast(1)
    val aspect = if (mediaWidth != null && mediaHeight != null && mediaWidth > 0 && mediaHeight > 0) mediaWidth.toDouble() / mediaHeight else null
    val width = if (aspect == null) maxWidth else minOf(maxWidth, (maxHeight * aspect).roundToInt()).coerceAtLeast(minOf(16, maxWidth))
    val height = if (aspect == null) maxHeight else minOf(maxHeight, (width / aspect).roundToInt()).coerceAtLeast(1)
    val rows = (height - 1) / lineHeight.coerceAtLeast(1) + 1
    return IrcThumbnailLayout(width, height, rows)
}

internal data class IrcThumbnailAnchor(val action: IrcChatAction, val parameters: ActiveTextCollector.Parameters,
    val x: Int, val top: Int, var visibleTop: Int, var visibleBottom: Int) {
    fun rectangle(width: Int): ScreenRectangle {
        val area = ScreenRectangle(x, visibleTop, width, visibleBottom - visibleTop).transformAxisAligned(parameters.pose())
        val scissor = parameters.scissor() ?: return area
        return area.intersection(scissor) ?: ScreenRectangle.empty()
    }
}

/** Native chat capture includes empty reserved rows that GUI glyph extraction discards. */
internal class IrcThumbnailCollector(private val lineHeight: Int, lineSpacing: Double) : ActiveTextCollector {
    val anchors = linkedMapOf<IrcChatAction, IrcThumbnailAnchor>()
    private var defaults = ActiveTextCollector.Parameters(Matrix3x2f())
    private val rowOffset = lineHeight - (8 * (lineSpacing + 1) - 4 * lineSpacing).roundToInt()
    override fun defaultParameters() = defaults
    override fun defaultParameters(value: ActiveTextCollector.Parameters) { defaults = value }
    override fun accept(alignment: TextAlignment, x: Int, y: Int, parameters: ActiveTextCollector.Parameters,
        text: FormattedCharSequence) {
        val shown = StringBuilder()
        var action: IrcChatAction? = null
        text.accept { _, style, codepoint ->
            shown.appendCodePoint(codepoint)
            if (action == null) action = (style.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction)
            shown.length < 80
        }
        val mediaAction = action?.takeIf { it.kind == "media" } ?: return
        val row = mediaAction.previewRow ?: return
        if (!(shown.all { it == '\u00a0' || it.isWhitespace() } || shown.toString().trimStart().matches(ircThumbnailPlaceholder))) return
        val attachmentAction = mediaAction.copy(previewRow = null)
        val current = anchors[attachmentAction]
        val visibleTop = y - rowOffset
        val top = visibleTop - row * lineHeight
        if (current == null) {
            if (anchors.size < 8) anchors[attachmentAction] = IrcThumbnailAnchor(attachmentAction, parameters, x, top, visibleTop, visibleTop + lineHeight)
        } else {
            current.visibleTop = minOf(current.visibleTop, visibleTop)
            current.visibleBottom = maxOf(current.visibleBottom, visibleTop + lineHeight)
        }
    }
    override fun acceptScrolling(text: Component, centerX: Int, left: Int, right: Int, y: Int, maxY: Int,
        parameters: ActiveTextCollector.Parameters) = Unit
}

private val ircEmojiFont = FontDescription.Resource(Identifier.fromNamespaceAndPath("skymyce", "irc_emoji"))
internal fun ircPassiveText(source: Component): Component = Component.empty().apply {
    source.visit(net.minecraft.network.chat.FormattedText.StyledContentConsumer<Unit> { style, text ->
        append(Component.literal(text).setStyle(style.withClickEvent(null).withHoverEvent(null).withInsertion(null)))
        java.util.Optional.empty()
    }, net.minecraft.network.chat.Style.EMPTY)
}
internal fun ircDisplayText(text: String, color: Int): MutableComponent {
    val parts = ircReadableText(text)
    if (parts.none { it.emoji }) return Component.literal(parts.joinToString("") { it.text }).withColor(color)
    return Component.empty().apply {
        for (part in parts) append(Component.literal(part.text).withColor(color).also { component ->
            if (part.emoji) component.withStyle { it.withFont(ircEmojiFont).withColor(0xFFFFFF).withBold(false).withItalic(false) }
        })
    }
}

internal fun ircReactionText(reaction: IrcReaction): MutableComponent =
    ircDisplayText("${reaction.emoji} ${reaction.count}", 0x68B9F5)

internal fun ircMessageHeader(message: GlobalChatMessage, highlight: Boolean): MutableComponent {
    val name = if (message.source == "discord") "@${message.from}" else message.from
    // The shared cosmetic font hook decorates the real name once, including with other chat mods installed.
    val result = ircDisplayText(name, if (message.mention && highlight) 0xFFE18A else 0xAACBFF)
        .withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("${if (message.source == "discord") "Discord" else "Minecraft"}: ${message.from}"))) }
    message.reply?.takeUnless { message.deleted }?.let { reply ->
        result.append(Component.literal(" ↩ ").withColor(0x969DA8).append(ircDisplayText(reply.from, 0x969DA8)).withStyle {
            it.withHoverEvent(HoverEvent.ShowText(Component.literal(reply.text ?: "Reply to ${reply.from}")))
        })
    }
    return result.append(": ")
}
internal fun ircMessageActions(message: GlobalChatMessage, manageable: Boolean): Component = Component.empty().apply {
    for ((kind, label, hint) in listOf(
        Triple("reply", " [↩]", "Reply in the current composer"),
        Triple("react", " [+]", "Choose a reaction"),
        Triple("manage", " [⋯]", "Edit or delete your message"))) {
        if (kind == "manage" && !manageable) continue
        append(Component.literal(label).withColor(0x68B9F5).withStyle {
            it.withClickEvent(ClickEvent.RunCommand(IrcChatAction(kind, message.scope, message.id).command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hint)))
        })
    }
}

/** Rendering may reenter chat hooks. Apply only to the same surviving history records after rendering finishes. */
internal fun updateIrcHistory(messages: MutableList<GuiMessage>, transform: (GuiMessage) -> GuiMessage?): Boolean {
    val updates = IdentityHashMap<GuiMessage, GuiMessage?>()
    for (previous in ArrayList(messages)) {
        val next = transform(previous)
        if (next !== previous) updates[previous] = next
    }
    if (updates.isEmpty()) return false
    var modified = false
    val iterator = messages.listIterator()
    while (iterator.hasNext()) {
        val previous = iterator.next()
        if (!updates.containsKey(previous)) continue
        val next = updates[previous]
        if (next == null) iterator.remove() else iterator.set(next)
        modified = true
    }
    return modified
}

/** Chat owns wrapping, scrolling and hit testing. Our tagged placeholder rows own only their thumbnails. */
object IrcChatRendering {
    private data class Texture(val texture: MenuTexture, var asset: IrcGif, var seen: Long,
        var elapsedNanos: Long = 0, var lastNanos: Long = System.nanoTime())
    private val textures = linkedMapOf<String, Texture>()
    private val compactMediaLayouts = linkedMapOf<String, Set<Int>>()
    private var frame = 0L
    private var preferences = ""
    private var nextHistoryPrune = 0L
    private var prunedRevision = -1
    private var mediaRevision = -1L
    private var initialized = false
    private val taggedMessage = ThreadLocal.withInitial { false }
    private val refreshingMessages = ThreadLocal.withInitial { false }

    @JvmStatic fun addingMessage(): Boolean = taggedMessage.get()
    internal fun <T> withTaggedMessage(action: () -> T): T {
        val previous = taggedMessage.get()
        taggedMessage.set(true)
        return try { action() } finally { taggedMessage.set(previous) }
    }

    @JvmStatic fun snapshotForRefresh(messages: List<GuiMessage>): List<GuiMessage> =
        if (refreshingMessages.get()) ArrayList(messages) else messages

    internal fun <T> withRefreshMessages(action: () -> T): T {
        val previous = refreshingMessages.get()
        refreshingMessages.set(true)
        return try { action() } finally { refreshingMessages.set(previous) }
    }

    fun init() {
        if (initialized) return
        initialized = true
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "irc_media")) { output, _ ->
            if (MC.screen !is ChatScreen && MC.screen !is IrcMediaViewerScreen && MC.screen !is IrcMessageControlsScreen) render(output)
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is ChatScreen) return@register
            IrcEmojiPicker.layout(screen)
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                if (click.button() == 0 && IrcEmojiPicker.click(screen, click.x().toInt(), click.y().toInt())) return@register false
                if (click.button() == 0 && !suggestionsVisible() && IrcInlineControls.click(screen, click.x().toInt(), click.y().toInt())) return@register false
                if (click.button() == 0 && !click.hasShiftDown() && openSupportedLink(screen, click.x().toInt(), click.y().toInt())) return@register false
                val action = actionAt(click.x().toInt(), click.y().toInt())
                if (click.button() != 0 || click.hasShiftDown() || action == null) true
                else { RelayMessages.chatAction(action.command, click.x().toInt(), click.y().toInt()); false }
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key ->
                !(key.key() == GLFW.GLFW_KEY_ESCAPE && !suggestionsVisible() && IrcInlineControls.escape() ||
                    !suggestionsVisible() && IrcEmojiPicker.key(screen, key))
            }
            ScreenKeyboardEvents.allowCharType(screen).register { _, character -> !IrcEmojiPicker.charTyped(character) }
            ScreenKeyboardEvents.afterCharType(screen).register { _, character -> IrcEmojiPicker.afterCharTyped(screen, character) }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, _, vertical ->
                !IrcEmojiPicker.scroll(screen, mouseX.toInt(), mouseY.toInt(), vertical)
            }
            ScreenEvents.remove(screen).register { IrcInlineControls.clear(); IrcEmojiPicker.clear() }
            ScreenEvents.afterExtract(screen).register { _, output, mouseX, mouseY, delta ->
                render(output)
                if (!suggestionsVisible()) IrcInlineControls.render(screen, output, mouseX, mouseY)
                IrcEmojiPicker.render(screen, output, mouseX, mouseY, delta, suggestionsVisible())
                val action = actionAt(mouseX, mouseY)
                val message = action?.let { RelayMessages.chatStore.get(RelayMessages.key(it.scope, it.id)) }
                if (action?.kind == "media" && message != null) {
                    val media = message.media.getOrNull(action.mediaIndex ?: -1)
                    if (media != null) {
                        val state = IrcMediaAssets.view(media, false)
                        val text = "${media.name ?: media.type.uppercase()} · Click to view" +
                            if (compactIrcMedia(state.status)) "\n${state.detail.orEmpty()}" else ""
                        output.setTooltipForNextFrame(Component.literal(text), mouseX, mouseY)
                    }
                }
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { clear() }
    }

    private fun accessor() = MC.instance.gui.chat as IrcChatAccessor
    internal fun add(message: GlobalChatMessage) {
        val tag = GuiMessageTag(0x4A8EFF, null, Component.literal(ircPrefix(message.scope)), message.key.tag)
        withTaggedMessage {
            RelayMessages.quietChat { accessor().`skymyce$addMessage`(component(message), null, GuiMessageSource.SYSTEM_CLIENT, tag) }
        }
    }
    internal fun update(message: GlobalChatMessage) {
        val chat = accessor()
        var found = false
        val modified = updateIrcHistory(chat.`skymyce$getMessages`()) { previous ->
            if (found || previous.tag()?.logTag() != message.key.tag) previous else {
                found = true
                GuiMessage(previous.addedTime(), component(message), previous.signature(), previous.source(), previous.tag())
            }
        }
        if (modified) refresh(chat) // Evicted/replaced records stay gone; never append an update as a new message.
        // Replacement/deletion cannot retain the old GPU image or clickable placeholder.
        textures.keys.filter { it.startsWith(message.key.tag + ":") }.forEach { textures.remove(it)?.texture?.close() }
    }
    /** Rewrites vanilla chat lines in place (another feature's clickable list); [transform] returns the line unchanged to keep it. */
    internal fun rewriteChat(transform: (GuiMessage) -> GuiMessage) {
        val chat = accessor()
        if (updateIrcHistory(chat.`skymyce$getMessages`(), transform)) refresh(chat)
    }
    private fun refresh(chat: IrcChatAccessor) {
        val scroll = chat.`skymyce$getScroll`()
        withRefreshMessages { chat.`skymyce$refreshMessages`() }
        chat.`skymyce$setScroll`(scroll.coerceAtLeast(0))
        MC.instance.gui.chat.scrollChat(0) // Native clamp after an edit or preference removes wrapped lines.
    }
    fun clear() {
        IrcInlineControls.clear()
        textures.values.forEach { it.texture.close() }; textures.clear()
        compactMediaLayouts.clear()
        val chat = accessor()
        if (chat.`skymyce$getMessages`().removeIf { it.tag()?.logTag()?.startsWith("skymyce:irc:") == true }) refresh(chat)
    }
    internal fun clearScope(scope: String) {
        compactMediaLayouts.keys.removeIf { it.startsWith("skymyce:irc:$scope:") }
        textures.keys.filter { it.startsWith("skymyce:irc:$scope:") }.forEach { textures.remove(it)?.texture?.close() }
        val chat = accessor()
        if (chat.`skymyce$getMessages`().removeIf { it.tag()?.logTag()?.startsWith("skymyce:irc:$scope:") == true }) refresh(chat)
    }
    fun tick() {
        val current = "${GlobalChatConfig.mediaPreviews}|${GlobalChatConfig.previewSize}|${GlobalChatConfig.mentionHighlight}|${DungeonFriendRelay.richChatAvailable}|${MC.instance.options.chatWidth().get()}|${MC.instance.options.chatScale().get()}|${MC.instance.options.chatLineSpacing().get()}"
        val changed = current != preferences
        preferences = current
        val currentMediaRevision = IrcMediaAssets.revision
        val assetsChanged = currentMediaRevision != mediaRevision
        val chat = accessor()
        val now = System.nanoTime()
        // An empty store that has not changed since the last pass has nothing to prune or rewrite; skip copying the history.
        val store = RelayMessages.chatStore
        val historyPruned = now >= nextHistoryPrune && !(store.isEmpty && store.revision == prunedRevision)
        if (now >= nextHistoryPrune) nextHistoryPrune = now + 1_000_000_000L
        if (historyPruned) {
            val tags = ArrayList(chat.`skymyce$getMessages`()).mapNotNull { it.tag()?.logTag()?.takeIf { tag -> tag.startsWith("skymyce:irc:") } }.toSet()
            RelayMessages.chatStore.retain(tags, System.currentTimeMillis())
        }
        if (changed || historyPruned) {
            mediaRevision = currentMediaRevision
            val known = RelayMessages.chatStore.values().associateBy { it.key.tag }
            val modified = updateIrcHistory(chat.`skymyce$getMessages`()) { previous ->
                val tag = previous.tag()?.logTag()?.takeIf { it.startsWith("skymyce:irc:") } ?: return@updateIrcHistory previous
                val message = known[tag]
                val mediaChanged = message != null && GlobalChatConfig.mediaPreviews && assetsChanged &&
                    compactMediaIndices(message) != compactMediaLayouts[tag].orEmpty()
                if (message != null && (changed || mediaChanged)) {
                    GuiMessage(previous.addedTime(), component(message), previous.signature(), previous.source(), previous.tag())
                } else if (message == null && previous.content().style.clickEvent == null) {
                    // Evicted metadata removes interactions while retaining text and bitmap emoji styles.
                    var action = false
                    previous.content().visualOrderText.accept { _, style, _ ->
                        if ((style.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction) != null) action = true
                        !action
                    }
                    if (action) {
                        GuiMessage(previous.addedTime(), ircPassiveText(previous.content()), previous.signature(), previous.source(), previous.tag())
                    } else previous
                } else previous
            }
            if (modified) refresh(chat)
            if (historyPruned) prunedRevision = store.revision
        }
        if (!GlobalChatConfig.mediaPreviews) { textures.values.forEach { it.texture.close() }; textures.clear() }
        textures.entries.removeIf { (_, entry) -> if (frame - entry.seen > 2) { entry.texture.close(); true } else false }
    }

    internal fun prefixComponent(scope: String): MutableComponent {
        val text = ircPrefix(scope)
        val result = Component.empty()
        val colors = ircPrefixColors(text.length)
        text.forEachIndexed { index, char -> result.append(Component.literal(char.toString()).withColor(colors[index])) }
        return result.append(" ")
    }
    private fun actionStyle(text: String, action: IrcChatAction, hint: String): MutableComponent = Component.literal(text).withColor(0x68B9F5).withStyle {
        it.withClickEvent(ClickEvent.RunCommand(action.command)).withHoverEvent(HoverEvent.ShowText(Component.literal(hint)))
    }
    private fun component(message: GlobalChatMessage): Component {
        val result = prefixComponent(message.scope)
        result.append(ircMessageHeader(message, GlobalChatConfig.mentionHighlight))
        if (message.deleted) return result.append(Component.literal("Message deleted").withColor(0x969DA8).withStyle { it.withItalic(true) })
        val compactMedia = if (GlobalChatConfig.mediaPreviews) compactMediaIndices(message) else emptySet()
        if (message.media.isNotEmpty()) {
            compactMediaLayouts[message.key.tag] = compactMedia
            while (compactMediaLayouts.size > 1024) compactMediaLayouts.remove(compactMediaLayouts.keys.first())
        }
        val representedMedia = hashSetOf<Int>()
        for (span in ircBodySpans(message.text, message.media, GlobalChatConfig.mediaPreviews)) {
            val url = span.url
            val mediaIndex = span.mediaIndex ?: -1
            if (url != null && mediaIndex >= 0) {
                if (!GlobalChatConfig.mediaPreviews && representedMedia.add(mediaIndex))
                    result.append(actionStyle("[${message.media[mediaIndex].type.uppercase()}]", IrcChatAction("media", message.scope, message.id, mediaIndex), "View media"))
            } else {
                val text = if (url != null && trustedIrcGifUrl(url)) "[GIF]" else span.text
                for (readable in ircReadableText(text)) {
                    val part = Component.literal(readable.text).withColor(if (url == null) 0xE2E7EF else 0x68B9F5)
                    if (readable.emoji) part.withStyle { it.withFont(ircEmojiFont)
                        .withColor(0xFFFFFF).withBold(false).withItalic(false) }
                    if (url != null) part.withStyle { it.withClickEvent(ClickEvent.OpenUrl(url)).withUnderlined(true)
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Open original link"))) }
                    else if (readable.original != null) part.withStyle { it.withHoverEvent(HoverEvent.ShowText(
                        if (readable.emoji) Component.literal("Emoji: ").append(ircDisplayText(readable.original, 0xFFFFFF))
                        else Component.literal("Emoji: ${readable.text}"))) }
                    result.append(part)
                }
            }
        }
        if (message.model == 1 && DungeonFriendRelay.richChatAvailable) {
            for (reaction in message.reactions.filter { it.count > 0 })
                result.append("  ").append(ircReactionText(reaction).withStyle {
                    it.withClickEvent(ClickEvent.RunCommand(IrcChatAction("react", message.scope, message.id).command))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("${if (reaction.mine) "Remove or change" else "Add"} reaction")))
                })
            result.append(ircMessageActions(message, RelayMessages.canManage(message.key)))
        }
        message.media.forEachIndexed { index, media ->
            if (GlobalChatConfig.mediaPreviews) {
                val label = when (media.type) { "gif" -> "GIF"; "video" -> "Video"; else -> "Image" }
                if (index in compactMedia) {
                    result.append("\n").append(actionStyle("[$label preview unavailable]", IrcChatAction("media", message.scope, message.id, index),
                        IrcMediaAssets.view(media, false).detail ?: "Open the media viewer for details"))
                    return@forEachIndexed
                }
                val layout = thumbnailLayout(media)
                val fullTitle = "[$label ${index + 1}]"
                val title = layout.title(fullTitle, MC.instance.font.width(fullTitle), index)
                val spaceWidth = MC.instance.font.width("\u00a0").coerceAtLeast(1)
                val width = layout.width
                val padding = "\u00a0".repeat(((width - MC.instance.font.width(title)) / spaceWidth).coerceAtLeast(0))
                val blank = "\u00a0".repeat((width / spaceWidth).coerceAtLeast(1))
                // Native rows reserve the whole image and retain its click area during wrapping and scrolling.
                for (row in 0 until layout.rows) result.append("\n").append(actionStyle(if (row == 0) title + padding else blank,
                    IrcChatAction("media", message.scope, message.id, index, row), "View ${media.name ?: label}"))
            } else if (representedMedia.add(index)) {
                result.append(" ").append(actionStyle("[${media.type.uppercase()} ${index + 1}]", IrcChatAction("media", message.scope, message.id, index), "View media"))
            }
        }
        return result
    }
    private fun compactMediaIndices(message: GlobalChatMessage): Set<Int> = message.media.indices.filterTo(linkedSetOf()) {
        compactIrcMedia(IrcMediaAssets.view(message.media[it], false).status)
    }

    private fun actionAt(mouseX: Int, mouseY: Int): IrcChatAction? {
        val action = (clickStyleAt(mouseX, mouseY)?.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction) ?: return null
        val key = RelayMessages.key(action.scope, action.id)
        val message = RelayMessages.chatStore.get(key) ?: return null
        if (message.deleted || !RelayMessages.currentIrcChannel(key)) return null
        return action.takeIf { it.kind != "media" || message.media.getOrNull(it.mediaIndex ?: -1) != null }
    }
    private fun clickStyleAt(mouseX: Int, mouseY: Int): net.minecraft.network.chat.Style? {
        if (MC.screen !is ChatScreen || MC.instance.options.hideGui || MC.instance.player == null) return null
        if (suggestionsVisible()) return null
        val client = MC.instance
        val finder = ActiveTextCollector.ClickableStyleFinder(client.font, mouseX, mouseY)
        client.gui.chat.captureClickableText(finder, client.window.guiScaledHeight, client.gui.guiTicks, ChatComponent.DisplayMode.FOREGROUND)
        return finder.result()
    }
    private fun supportedLinkAt(mouseX: Int, mouseY: Int) =
        BrowserLinks.supported((clickStyleAt(mouseX, mouseY)?.clickEvent as? ClickEvent.OpenUrl)?.uri()?.toString())
    private fun openSupportedLink(screen: ChatScreen, mouseX: Int, mouseY: Int): Boolean {
        val uri = supportedLinkAt(mouseX, mouseY) ?: return false
        val client = MC.instance
        if (!client.options.chatLinks().get()) return true
        val generation = IrcMediaAssets.generation
        client.gui.chat.preserveCurrentChatScreen()
        if (client.options.chatLinksPrompt().get()) client.setScreen(ConfirmLinkScreen({ confirmed ->
            if (generation != IrcMediaAssets.generation) client.setScreen(null)
            else if (confirmed && client.options.chatLinks().get()) InGameBrowser.open(screen, uri.toString())
            else client.setScreen(screen)
        }, uri.toString(), false))
        else InGameBrowser.open(screen, uri.toString())
        return true
    }
    /** Let ordinary HUD widgets handle every click except our current, native-chat action areas. */
    fun hasInteractionAt(mouseX: Int, mouseY: Int) = actionAt(mouseX, mouseY) != null || supportedLinkAt(mouseX, mouseY) != null || !suggestionsVisible() &&
        (MC.screen as? ChatScreen)?.let { IrcInlineControls.hasInteractionAt(it, mouseX, mouseY) } == true ||
        (MC.screen as? ChatScreen)?.let { IrcEmojiPicker.hasInteractionAt(it, mouseX, mouseY) } == true
    private fun suggestionsVisible() = (MC.screen as? IrcChatScreenAccessor)?.`skymyce$getSuggestions`()?.isVisible == true
    private fun thumbnailLayout(media: IrcMedia) = ircThumbnailLayout(GlobalChatConfig.previewSize,
        (ChatComponent.getWidth(MC.instance.options.chatWidth().get()) / MC.instance.options.chatScale().get()).toInt(),
        (MC.instance.font.lineHeight * (MC.instance.options.chatLineSpacing().get() + 1)).toInt(), media.width, media.height)

    private fun render(output: GuiGraphicsExtractor) {
        frame++
        if (!GlobalChatConfig.mediaPreviews || MC.instance.options.hideGui || MC.instance.options.chatVisibility().get() == ChatVisiblity.HIDDEN ||
            MC.instance.player == null || suggestionsVisible()) return
        // Walking every visible chat line for attachment anchors is wasted while no stored message has one.
        if (!RelayMessages.chatStore.hasMedia()) return
        val lineSpacing = MC.instance.options.chatLineSpacing().get()
        val lineHeight = (MC.instance.font.lineHeight * (lineSpacing + 1)).toInt()
        val collector = IrcThumbnailCollector(lineHeight, lineSpacing)
        MC.instance.gui.chat.captureClickableText(collector, MC.instance.window.guiScaledHeight,
            MC.instance.gui.guiTicks, if (MC.screen is ChatScreen) ChatComponent.DisplayMode.FOREGROUND else ChatComponent.DisplayMode.BACKGROUND)
        val anchors = collector.anchors
        if (anchors.isEmpty()) return
        output.nextStratum()
        for ((_, anchor) in anchors) {
            val key = RelayMessages.key(anchor.action.scope, anchor.action.id)
            val message = RelayMessages.chatStore.get(key)?.takeUnless { it.deleted } ?: continue
            if (!RelayMessages.currentIrcChannel(key)) continue
            val media = message.media.getOrNull(anchor.action.mediaIndex ?: -1) ?: continue
            // Paint only the visible native rows reserved for this attachment, including during scrolling.
            IrcMediaAssets.request(media, false)
            val state = IrcMediaAssets.view(media, false)
            val asset = state.asset
            if (asset == null && compactIrcMedia(state.status)) continue
            val layout = thumbnailLayout(media)
            val scissor = anchor.rectangle(layout.width)
            if (scissor.width() <= 0 || scissor.height() <= 0) continue
            output.scissorStack.push(scissor)
            output.pose().pushMatrix()
            output.pose().set(anchor.parameters.pose())
            output.pose().translate(anchor.x.toFloat(), anchor.top.toFloat())
            // Only a pending or failed preview gets a backdrop; a loaded image fills its own aspect-sized box.
            if (asset == null) output.fill(0, 0, layout.width, layout.height, 0xFF17212E.toInt())
            if (asset != null) {
                val textureKey = key.tag + ":" + media.id
                var texture = textures[textureKey]
                if (texture?.asset !== asset) {
                    texture?.texture?.close()
                    texture = Texture(MenuTexture(), asset, frame); textures[textureKey] = texture
                }
                texture.seen = frame
                val scale = minOf(layout.width.toDouble() / asset.width, layout.height.toDouble() / asset.height)
                val width = (asset.width * scale).roundToInt().coerceAtLeast(1)
                val height = (asset.height * scale).roundToInt().coerceAtLeast(1)
                output.pose().pushMatrix()
                output.pose().translate((layout.width - width) / 2f, (layout.height - height) / 2f)
                val now = System.nanoTime()
                if (GlobalChatConfig.animateGifs) texture.elapsedNanos += (now - texture.lastNanos).coerceIn(0, 250_000_000)
                texture.lastNanos = now
                val index = asset.frameIndexAt(texture.elapsedNanos / 1_000_000)
                texture.texture.render(output, asset.frames[index].image, width, height, index.toLong())
                output.pose().popMatrix()
            } else output.text(MC.instance.font, when (state.status) {
                IrcMediaAssetStatus.FAILED -> "Retry…"; IrcMediaAssetStatus.UNSUPPORTED -> "Open…"; else -> "Loading…"
            }, 2, 4, 0xFFB9CDE4.toInt())
            if (media.type == "video" && asset?.frames?.size.let { it == null || it <= 1 }) {
                output.fill(0, 8, minOf(12, layout.width), layout.height, 0xC0000000.toInt())
                output.text(MC.instance.font, "▶", 2, 9, 0xFFFFFFFF.toInt())
            }
            output.pose().popMatrix()
            output.disableScissor()
        }
        while (textures.size > 8) textures.remove(textures.keys.first())?.texture?.close()
    }
}

internal data class IrcControlBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Int, py: Int) = px >= x && py >= y && px < x + width && py < y + height
}
internal fun ircPickerBounds(x: Int, y: Int, width: Int, height: Int): IrcControlBounds {
    val size = minOf(246, (width - 8).coerceAtLeast(1))
    val maxX = (width - size - 4).coerceAtLeast(0)
    return IrcControlBounds(x.coerceIn(minOf(4, maxX), maxX),
        (y - 68).coerceIn(0, (height - 96).coerceAtLeast(0)), size, 64)
}
internal fun ircReactionCell(bounds: IrcControlBounds, index: Int): IrcControlBounds {
    require(index in IRC_REACTIONS.indices)
    val column = index % 3
    val left = bounds.x + column * bounds.width / 3
    val right = bounds.x + (column + 1) * bounds.width / 3
    return IrcControlBounds(left, bounds.y + 20 + index / 3 * 20, right - left, 20)
}
internal fun ircReactionAt(bounds: IrcControlBounds, x: Int, y: Int): Int? =
    IRC_REACTIONS.indices.firstOrNull { ircReactionCell(bounds, it).contains(x, y) }

/** Overlay controls belong to the existing native composer, with no separate reply/reaction screen. */
internal object IrcInlineControls {
    private data class Picker(val screen: ChatScreen, val key: IrcKey, val x: Int, val y: Int)
    private var picker: Picker? = null
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    fun open(key: IrcKey, x: Int, y: Int) {
        val screen = MC.screen as? ChatScreen ?: return
        picker = Picker(screen, key, x, y)
    }
    fun clear() { picker = null; canvas.close(); texture.close() }
    fun escape(): Boolean = if (picker != null) { clear(); true } else false
    private fun current(screen: ChatScreen): Picker? = picker?.takeIf {
        it.screen === screen && RelayMessages.currentIrcChannel(it.key) &&
            RelayMessages.chatStore.get(it.key)?.deleted == false && DungeonFriendRelay.richChatAvailable
    }.also { if (it == null) picker = null }
    private fun cancelBounds(screen: ChatScreen) = IrcControlBounds((screen.width - 54).coerceAtLeast(4), screen.height - 40, 50, 18)
    fun hasInteractionAt(screen: ChatScreen, x: Int, y: Int): Boolean =
        current(screen)?.let { ircPickerBounds(it.x, it.y, screen.width, screen.height).contains(x, y) } == true ||
            RelayMessages.replyMessage() != null && cancelBounds(screen).contains(x, y)
    fun click(screen: ChatScreen, x: Int, y: Int): Boolean {
        val selected = current(screen)
        if (selected != null) {
            val bounds = ircPickerBounds(selected.x, selected.y, screen.width, screen.height)
            if (bounds.contains(x, y)) {
                ircReactionAt(bounds, x, y)?.let { index ->
                    val emoji = IRC_REACTIONS[index]
                    val message = RelayMessages.chatStore.get(selected.key) ?: return true
                    val mine = message.reactions.firstOrNull { it.emoji == emoji }?.mine == true
                    if (RelayMessages.operation(selected.key, "reaction", emoji = emoji, active = !mine)) clear()
                }
                return true
            }
            clear() // A click outside closes the picker and retains ordinary native chat behavior.
        }
        if (RelayMessages.replyMessage() != null && cancelBounds(screen).contains(x, y)) {
            RelayMessages.cancelReply(); return true
        }
        return false
    }
    fun render(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val selected = current(screen)
        val reply = RelayMessages.replyMessage()
        if (selected == null && reply == null) { canvas.close(); texture.close(); return }
        if (texture.prepareFrame()) canvas.close()
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX, mouseY, screen.width, screen.height)
        val font = MC.instance.font
        val emojiLabels = arrayListOf<Pair<Component, IrcControlBounds>>()
        reply?.let { message ->
            val cancel = cancelBounds(screen)
            val y = screen.height - 40
            MenuTheme.box(graphics, 2, y, screen.width - 4, 18, MenuTheme.SIDEBAR)
            val preview = ircReadableText(message.text.replace('\n', ' ')).joinToString("") { it.text }
                .ifBlank { message.media.firstOrNull()?.name ?: "Media" }
            val from = ircReadableText(message.from).joinToString("") { it.text }
            graphics.text(font, fit("Reply to $from: $preview", cancel.x - 12), 7, y + 4, MenuTheme.alpha(MenuTheme.TEXT), false)
            if (cancel.contains(mouseX, mouseY)) MenuTheme.rounded(graphics, cancel.x, cancel.y, cancel.width, cancel.height, MenuTheme.CARD)
            graphics.text(font, "Cancel", cancel.x + 7, y + 4, MenuTheme.alpha(MenuTheme.ACCENT), false)
            if (cancel.contains(mouseX, mouseY)) output.setTooltipForNextFrame(Component.literal("Cancel this reply; keep your message draft"), mouseX, mouseY)
        }
        selected?.let { picker ->
            RelayMessages.chatStore.get(picker.key)?.let { message ->
                val bounds = ircPickerBounds(picker.x, picker.y, screen.width, screen.height)
                MenuTheme.box(graphics, bounds.x, bounds.y, bounds.width, bounds.height, MenuTheme.SIDEBAR)
                val from = ircReadableText(message.from).joinToString("") { it.text }
                graphics.text(font, fit("React · $from", bounds.width - 12), bounds.x + 6, bounds.y + 4, MenuTheme.alpha(MenuTheme.MUTED), false)
                IRC_REACTIONS.forEachIndexed { index, emoji ->
                    val cell = ircReactionCell(bounds, index)
                    val reaction = message.reactions.firstOrNull { it.emoji == emoji }
                    if (cell.contains(mouseX, mouseY)) {
                        MenuTheme.rounded(graphics, cell.x + 1, cell.y, cell.width - 2, cell.height, MenuTheme.CARD, 3)
                        output.setTooltipForNextFrame(Component.literal("${if (reaction?.mine == true) "Remove" else "Add"} ${IRC_REACTION_NAMES[index]} reaction"), mouseX, mouseY)
                    }
                    val color = MenuTheme.alpha(if (reaction?.mine == true) MenuTheme.ACCENT else MenuTheme.TEXT)
                    val text = ircDisplayText("${if (reaction?.mine == true) "✓ " else ""}$emoji${reaction?.count?.takeIf { it > 0 }?.let { " $it" }.orEmpty()}", color)
                    emojiLabels += text to cell
                }
            }
        }
        output.nextStratum()
        val scale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / screen.width.coerceAtLeast(1), 1440.0 / screen.height.coerceAtLeast(1))
        texture.render(output, canvas, screen.width, screen.height, scale)
        if (emojiLabels.isNotEmpty()) output.nextStratum()
        for ((text, cell) in emojiLabels) {
            output.enableScissor(cell.x, cell.y, cell.x + cell.width, cell.y + cell.height)
            output.text(font, text, cell.x + 6, cell.y + 5, 0xFFFFFFFF.toInt(), false)
            output.disableScissor()
        }
    }
    private fun fit(text: String, width: Int): String {
        var end = 0
        var measured = 0.0
        while (end < text.length) {
            val codepoint = text.codePointAt(end)
            measured += MenuCanvas.textWidth(codepoint)
            if (measured > width.coerceAtLeast(1)) break
            end += Character.charCount(codepoint)
        }
        return text.substring(0, end)
    }
    fun focusComposer(screen: ChatScreen) {
        val input = (screen as IrcChatScreenAccessor).`skymyce$getInput`()
        screen.setFocused(input)
        input.setFocused(true)
    }
}

/** Own-message editing only; Reply and React remain directly inside native chat. */
internal class IrcMessageControlsScreen(private val previous: Screen?, private val key: IrcKey) : MenuScreen(Component.literal("Edit your IRC message")) {
    internal val scope get() = key.scope
    override val modal = true
    override val menuParent get() = previous
    private lateinit var edit: MenuTextBox
    private lateinit var save: MenuButton
    private lateinit var delete: MenuButton
    private lateinit var feedback: MenuLabel
    private var confirmDelete = false
    private var requestAfter = 0L
    private var seenRevision = -1
    private var status = ""

    override fun buildContent(root: FlowLayout) {
        confirmDelete = false
        val draft = if (::edit.isInitialized) edit.value else null
        val message = RelayMessages.chatStore.get(key)
        root.padding(Insets.of(16)); root.gap(8)
        root.child(label("EDIT MESSAGE", MenuTheme.ACCENT))
        root.child(label("${ircPrefix(key.scope)} ${message?.from.orEmpty()}", MenuTheme.MUTED))
        edit = MenuTextBox(Sizing.fill()).apply { verticalSizing(Sizing.fixed(24)); setHint(Component.literal("Edit your message")) }
        root.child(edit)
        val maximum = if (key.scope == "room") 256 else 1024
        edit.setMaxLength(maximum)
        edit.value = draft ?: message?.text?.replace('\n', ' ').orEmpty().take(maximum)
        edit.setEditable(RelayMessages.canManage(key))
        save = button("Save edit") {
            if (RelayMessages.operation(key, "edit", text = edit.value)) queued("Edit requested; waiting for chat update.")
        }.apply { horizontalSizing(Sizing.fill(48)); intent = MenuButtonIntent.PRIMARY }
        delete = button("Delete") {
            if (!confirmDelete) { confirmDelete = true; delete.message = Component.literal("Confirm delete") }
            else if (RelayMessages.operation(key, "delete")) queued("Deletion requested; waiting for chat update.")
        }.apply { horizontalSizing(Sizing.fill(48)); intent = MenuButtonIntent.DESTRUCTIVE }
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).gap(6).apply { child(save); child(delete) })
        root.child(label("Own-message edits and deletion are available for 15 minutes.", MenuTheme.MUTED))
        feedback = label(status, MenuTheme.MUTED); root.child(feedback)
        root.child(button("Back to chat") { onClose() })
        updateControls()
    }
    private fun queued(text: String) {
        // A queued operation is not a Discord HTTP delivery receipt. Never optimistically alter the message.
        status = text; requestAfter = System.nanoTime() + 1_000_000_000L
    }
    private fun updateControls() {
        val message = RelayMessages.chatStore.get(key)
        val available = message != null && !message.deleted && message.model == 1 && RelayMessages.currentIrcChannel(key) && DungeonFriendRelay.richChatAvailable
        val waiting = System.nanoTime() < requestAfter
        val own = available && RelayMessages.canManage(key)
        save.active = own && !waiting && ircClientMessageError(key.scope, edit.value) == null
        delete.active = own && !waiting
        edit.setEditable(own)
        if (message != null && seenRevision >= 0 && message.revision != seenRevision) {
            status = if (message.deleted) "Message deleted." else "Chat updated."
            requestAfter = 0
        }
        seenRevision = message?.revision ?: -1
        if (!available) status = "This message is no longer available. Return to chat."
        if (feedback.text().string != status) feedback.text(Component.literal(status))
    }
    override fun tick() { super.tick(); if (::edit.isInitialized && !menuClosing) updateControls() }
    override fun onClose() { closeMenuTo(if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
