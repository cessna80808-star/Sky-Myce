package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.mixin.IrcChatAccessor
import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.gui.ActiveTextCollector
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt
import org.lwjgl.glfw.GLFW

internal const val IRC_THUMBNAIL_WIDTH = 96
internal const val IRC_THUMBNAIL_HEIGHT = 72
internal data class IrcThumbnailLayout(val width: Int, val height: Int, val rows: Int) {
    fun title(fullTitle: String, textWidth: Int, index: Int) = if (textWidth <= width) fullTitle else "[${index + 1}]"
}

internal fun ircThumbnailLayout(percent: Int, availableWidth: Int, lineHeight: Int): IrcThumbnailLayout {
    val requestedWidth = (IRC_THUMBNAIL_WIDTH * percent.coerceIn(50, 200) / 100.0).roundToInt()
    val width = minOf(requestedWidth, availableWidth.coerceAtLeast(1))
    val height = (width * IRC_THUMBNAIL_HEIGHT.toDouble() / IRC_THUMBNAIL_WIDTH).roundToInt().coerceAtLeast(1)
    val rows = (height - 1) / lineHeight.coerceAtLeast(1) + 1
    return IrcThumbnailLayout(width, height, rows)
}

internal fun ircMessageHeader(message: GlobalChatMessage, highlight: Boolean): MutableComponent {
    val name = if (message.source == "discord") "@${message.from}" else message.from
    // The shared cosmetic font hook decorates the real name once, including with other chat mods installed.
    val result = Component.literal(ircReadableText(name).joinToString("") { it.text }).withColor(if (message.mention && highlight) 0xFFE18A else 0xAACBFF)
        .withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("${if (message.source == "discord") "Discord" else "Minecraft"}: ${message.from}"))) }
    message.reply?.takeUnless { message.deleted }?.let { reply ->
        result.append(Component.literal(" ↩ ${ircReadableText(reply.from).joinToString("") { it.text }}").withColor(0x969DA8).withStyle {
            it.withHoverEvent(HoverEvent.ShowText(Component.literal(reply.text ?: "Reply to ${reply.from}")))
        })
    }
    return result.append(": ")
}

/** Chat owns wrapping, scrolling and hit testing. Our tagged placeholder rows own only their thumbnails. */
object IrcChatRendering {
    private data class Texture(val texture: MenuTexture, var asset: IrcGif, var seen: Long)
    private data class Anchor(val action: IrcChatAction, val state: GuiTextRenderState, var x: Float, var y: Float)
    private val textures = linkedMapOf<String, Texture>()
    private var frame = 0L
    private var preferences = ""
    private var initialized = false
    private val taggedMessage = ThreadLocal.withInitial { false }

    @JvmStatic fun addingMessage(): Boolean = taggedMessage.get()
    internal fun <T> withTaggedMessage(action: () -> T): T {
        val previous = taggedMessage.get()
        taggedMessage.set(true)
        return try { action() } finally { taggedMessage.set(previous) }
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
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                if (click.button() == 0 && !suggestionsVisible() && IrcInlineControls.click(screen, click.x().toInt(), click.y().toInt())) return@register false
                val action = actionAt(click.x().toInt(), click.y().toInt())
                if (click.button() != 0 || click.hasShiftDown() || action == null) true
                else { RelayMessages.chatAction(action.command, click.x().toInt(), click.y().toInt()); false }
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key ->
                !(key.key() == GLFW.GLFW_KEY_ESCAPE && !suggestionsVisible() && IrcInlineControls.escape())
            }
            ScreenEvents.remove(screen).register { IrcInlineControls.clear() }
            ScreenEvents.afterExtract(screen).register { _, output, mouseX, mouseY, _ ->
                render(output)
                if (!suggestionsVisible()) IrcInlineControls.render(screen, output, mouseX, mouseY)
                val action = actionAt(mouseX, mouseY)
                val message = action?.let { RelayMessages.chatStore.get(RelayMessages.key(it.scope, it.id)) }
                if (action?.kind == "media" && message != null) {
                    val media = message.media.getOrNull(action.mediaIndex ?: -1)
                    if (media != null) {
                        val state = IrcMediaAssets.view(media, false)
                        val text = "${media.name ?: media.type.uppercase()} · Click to view" +
                            if (state.status == IrcMediaAssetStatus.FAILED || state.status == IrcMediaAssetStatus.UNSUPPORTED) "\n${state.detail.orEmpty()}" else ""
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
        val index = chat.`skymyce$getMessages`().indexOfFirst { it.tag()?.logTag() == message.key.tag }
        if (index < 0) return // Vanilla has already evicted it. Never append an update as a new message.
        val previous = chat.`skymyce$getMessages`()[index]
        chat.`skymyce$getMessages`()[index] = GuiMessage(previous.addedTime(), component(message), previous.signature(), previous.source(), previous.tag())
        refresh(chat)
        // Replacement/deletion cannot retain the old GPU image or clickable placeholder.
        textures.keys.filter { it.startsWith(message.key.tag + ":") }.forEach { textures.remove(it)?.texture?.close() }
    }
    private fun refresh(chat: IrcChatAccessor) {
        val scroll = chat.`skymyce$getScroll`()
        chat.`skymyce$refreshMessages`()
        chat.`skymyce$setScroll`(scroll.coerceAtLeast(0))
        MC.instance.gui.chat.scrollChat(0) // Native clamp after an edit or preference removes wrapped lines.
    }
    fun clear() {
        IrcInlineControls.clear()
        textures.values.forEach { it.texture.close() }; textures.clear()
        val chat = accessor()
        if (chat.`skymyce$getMessages`().removeIf { it.tag()?.logTag()?.startsWith("skymyce:irc:") == true }) refresh(chat)
    }
    internal fun clearScope(scope: String) {
        textures.keys.filter { it.startsWith("skymyce:irc:$scope:") }.forEach { textures.remove(it)?.texture?.close() }
        val chat = accessor()
        if (chat.`skymyce$getMessages`().removeIf { it.tag()?.logTag()?.startsWith("skymyce:irc:$scope:") == true }) refresh(chat)
    }
    fun tick() {
        val current = "${GlobalChatConfig.mediaPreviews}|${GlobalChatConfig.previewSize}|${GlobalChatConfig.mentionHighlight}|${DungeonFriendRelay.richChatAvailable}|${MC.instance.options.chatWidth().get()}|${MC.instance.options.chatScale().get()}|${MC.instance.options.chatLineSpacing().get()}"
        val changed = current != preferences
        preferences = current
        val known = RelayMessages.chatStore.values().associateBy { it.key.tag }
        val chat = accessor()
        var modified = false
        for (index in chat.`skymyce$getMessages`().indices) {
            val previous = chat.`skymyce$getMessages`()[index]
            val tag = previous.tag()?.logTag()?.takeIf { it.startsWith("skymyce:irc:") } ?: continue
            val message = known[tag]
            if (message != null && changed) {
                chat.`skymyce$getMessages`()[index] = GuiMessage(previous.addedTime(), component(message), previous.signature(), previous.source(), previous.tag())
                modified = true
            } else if (message == null && previous.content().style.clickEvent == null) {
                // Remove actions and media rows when their bounded metadata expires, retaining plain chat text.
                var action = false
                previous.content().visualOrderText.accept { _, style, _ ->
                    if ((style.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction) != null) action = true
                    !action
                }
                if (action) {
                    chat.`skymyce$getMessages`()[index] = GuiMessage(previous.addedTime(), Component.literal(previous.content().string), previous.signature(), previous.source(), previous.tag())
                    modified = true
                }
            }
        }
        if (modified) refresh(chat)
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
                    if (url != null) part.withStyle { it.withClickEvent(ClickEvent.OpenUrl(url)).withUnderlined(true)
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Open original link"))) }
                    else if (readable.original != null) part.withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("Emoji: ${readable.text}"))) }
                    result.append(part)
                }
            }
        }
        if (message.model == 1 && DungeonFriendRelay.richChatAvailable) {
            for (reaction in message.reactions.filter { it.count > 0 })
                result.append(actionStyle("  ${IRC_REACTION_NAMES[IRC_REACTIONS.indexOf(reaction.emoji)]} ${reaction.count}",
                    IrcChatAction("react", message.scope, message.id), "${if (reaction.mine) "Remove or change" else "Add"} reaction"))
            result.append(actionStyle("  [Reply]", IrcChatAction("reply", message.scope, message.id), "Reply in the current composer"))
            result.append(actionStyle(" [React]", IrcChatAction("react", message.scope, message.id), "Choose a reaction"))
            if (RelayMessages.canManage(message.key)) result.append(actionStyle(" [Edit/Delete]", IrcChatAction("manage", message.scope, message.id), "Manage your message"))
        }
        message.media.forEachIndexed { index, media ->
            if (GlobalChatConfig.mediaPreviews) {
                val label = when (media.type) { "gif" -> "GIF"; "video" -> "Video"; else -> "Image" }
                val layout = thumbnailLayout()
                val fullTitle = "[$label ${index + 1}]"
                val title = layout.title(fullTitle, MC.instance.font.width(fullTitle), index)
                val spaceWidth = MC.instance.font.width("\u00a0").coerceAtLeast(1)
                val width = layout.width
                val padding = "\u00a0".repeat(((width - MC.instance.font.width(title)) / spaceWidth).coerceAtLeast(0))
                val blank = "\u00a0".repeat((width / spaceWidth).coerceAtLeast(1))
                // Native rows reserve the whole image and retain its click area during wrapping and scrolling.
                val placeholder = (listOf(title + padding) + List(layout.rows - 1) { blank }).joinToString("\n")
                result.append("\n").append(actionStyle(placeholder,
                    IrcChatAction("media", message.scope, message.id, index), "View ${media.name ?: label}"))
            } else if (representedMedia.add(index)) {
                result.append(" ").append(actionStyle("[${media.type.uppercase()} ${index + 1}]", IrcChatAction("media", message.scope, message.id, index), "View media"))
            }
        }
        return result
    }

    private fun actionAt(mouseX: Int, mouseY: Int): IrcChatAction? {
        if (MC.screen !is ChatScreen || MC.instance.options.hideGui || MC.instance.player == null) return null
        if (suggestionsVisible()) return null
        val client = MC.instance
        val finder = ActiveTextCollector.ClickableStyleFinder(client.font, mouseX, mouseY)
        client.gui.chat.captureClickableText(finder, client.window.guiScaledHeight, client.gui.guiTicks, ChatComponent.DisplayMode.FOREGROUND)
        val action = (finder.result()?.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction) ?: return null
        val key = RelayMessages.key(action.scope, action.id)
        val message = RelayMessages.chatStore.get(key) ?: return null
        if (message.deleted || !RelayMessages.currentIrcChannel(key)) return null
        return action.takeIf { it.kind != "media" || message.media.getOrNull(it.mediaIndex ?: -1) != null }
    }
    /** Let ordinary HUD widgets handle every click except our current, native-chat action areas. */
    fun hasInteractionAt(mouseX: Int, mouseY: Int) = actionAt(mouseX, mouseY) != null || !suggestionsVisible() &&
        (MC.screen as? ChatScreen)?.let { IrcInlineControls.hasInteractionAt(it, mouseX, mouseY) } == true
    private fun suggestionsVisible() = (MC.screen as? IrcChatScreenAccessor)?.`skymyce$getSuggestions`()?.isVisible == true
    private fun thumbnailLayout() = ircThumbnailLayout(GlobalChatConfig.previewSize,
        (ChatComponent.getWidth(MC.instance.options.chatWidth().get()) / MC.instance.options.chatScale().get()).toInt(),
        (MC.instance.font.lineHeight * (MC.instance.options.chatLineSpacing().get() + 1)).toInt())

    private fun render(output: GuiGraphicsExtractor) {
        frame++
        if (!GlobalChatConfig.mediaPreviews || MC.instance.options.hideGui || MC.instance.player == null || suggestionsVisible()) return
        val anchors = linkedMapOf<IrcChatAction, Anchor>()
        output.guiRenderState.forEachText { state ->
            var visibleText = ""
            state.text.accept { _, _, codepoint -> visibleText += String(Character.toChars(codepoint)); visibleText.length < 80 }
            if (!visibleText.trimStart().matches(Regex("\\[(?:(Image|GIF|Video) )?[1-4]][\\s\u00a0]*"))) return@forEachText
            var candidate = false
            state.text.accept { _, style, _ ->
                val action = (style.clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction)
                if (action?.kind == "media") candidate = true
                !candidate
            }
            if (!candidate || anchors.size >= 8) return@forEachText
            state.ensurePrepared().visit(object : Font.GlyphVisitor {
                override fun acceptGlyph(glyph: TextRenderable.Styled) {
                    val action = (glyph.style().clickEvent as? ClickEvent.RunCommand)?.command()?.let(::parseIrcChatAction) ?: return
                    if (action.kind != "media" || glyph.left() == glyph.right()) return
                    val current = anchors[action]
                    if (current == null) anchors[action] = Anchor(action, state, glyph.left(), glyph.top())
                    else if (current.state === state) { current.x = minOf(current.x, glyph.left()); current.y = minOf(current.y, glyph.top()) }
                }
            })
        }
        if (anchors.isEmpty()) return
        output.nextStratum()
        for ((_, anchor) in anchors) {
            val key = RelayMessages.key(anchor.action.scope, anchor.action.id)
            val message = RelayMessages.chatStore.get(key)?.takeUnless { it.deleted } ?: continue
            if (!RelayMessages.currentIrcChannel(key)) continue
            val media = message.media.getOrNull(anchor.action.mediaIndex ?: -1) ?: continue
            // Body [GIF] links have no reserved row. Only the first glyph of a standalone placeholder may paint.
            IrcMediaAssets.request(media, false)
            val state = IrcMediaAssets.view(media, false)
            val asset = state.asset
            val scissor = anchor.state.scissor
            if (scissor != null) output.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom())
            output.pose().pushMatrix()
            output.pose().set(anchor.state.pose)
            output.pose().translate(anchor.x, anchor.y)
            val layout = thumbnailLayout()
            output.fill(0, 0, layout.width, layout.height, 0xFF17212E.toInt())
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
                texture.texture.render(output, asset.frames.first().image, width, height, 0)
                output.pose().popMatrix()
            } else output.text(MC.instance.font, when (state.status) {
                IrcMediaAssetStatus.FAILED -> "Retry…"; IrcMediaAssetStatus.UNSUPPORTED -> "Open…"; else -> "Loading…"
            }, 2, 4, 0xFFB9CDE4.toInt())
            if (media.type == "video") {
                output.fill(0, 8, minOf(12, layout.width), layout.height, 0xC0000000.toInt())
                output.text(MC.instance.font, "▶", 2, 9, 0xFFFFFFFF.toInt())
            }
            output.pose().popMatrix()
            if (scissor != null) output.disableScissor()
        }
        while (textures.size > 8) textures.remove(textures.keys.first())?.texture?.close()
    }
}
