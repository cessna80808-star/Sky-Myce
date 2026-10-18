package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.mixin.IrcChatAccessor
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.browser.InGameBrowser
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.ui.SkijaMenuFonts
import me.mycellium.skymyce.ui.menuTextLines
import me.mycellium.skymyce.ui.menuTextRuns
import me.mycellium.skymyce.ui.menuTextWidth
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shared menu chrome surrounds native media composition; the existing decoded asset cache owns GIF frames. */
internal class IrcMediaViewerScreen(private val previous: Screen?, private val media: IrcMedia,
    private val sender: String, private val context: String, internal val messageKey: IrcKey? = null) : Screen(Component.literal("IRC media")) {
    private var texture = MenuTexture()
    private val chrome = MenuCanvas()
    private val chromeTexture = MenuTexture()
    private data class Header(val source: String, val available: Int, val color: Int, val size: Double,
                              val native: Boolean, val runs: List<MenuCanvas.Run>, val width: Double)
    private val headers = arrayOfNulls<Header>(5)
    private var detailText: String? = null
    private var detailWidth = -1
    private var detailColor = 0
    private var detailNative = false
    private var detailRuns = emptyList<List<MenuCanvas.Run>>()
    private var detailWidths = emptyList<Double>()
    private var paintedAsset: IrcGif? = null
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var elapsedNanos = 0L
    private var lastTickNanos = System.nanoTime()
    private var paused = !GlobalChatConfig.animateGifs
    private val previousChatScroll = if (previous is ChatScreen) (MC.instance.gui.chat as IrcChatAccessor).`skymyce$getScroll`() else null
    private var preference = GlobalChatConfig.animateGifs
    private val generation = IrcMediaAssets.generation
    private var browserDisabled = false
    private lateinit var pauseButton: MenuButton
    private lateinit var fitButton: MenuButton
    private lateinit var minusButton: MenuButton
    private lateinit var plusButton: MenuButton
    private lateinit var retryButton: MenuButton
    private val imageTop get() = 58
    private val imageBottom get() = (height - 76).coerceAtLeast(imageTop + 1)

    override fun init() {
        if (chromeTexture.prepareFrame()) chrome.close()
        if (!current()) return
        fun button(label: String, x: Int, y: Int, size: Int, action: () -> Unit): MenuButton =
            addRenderableWidget(MenuButton(Component.literal(label)) { action() }.apply { setRectangle(size, 20, x, y) })
        val start = (width - 250) / 2
        val row = height - 54
        fitButton = button("Fit", start, row, 52) { fit() }
        minusButton = button("−", start + 56, row, 32) { changeZoom(1 / 1.25) }
        plusButton = button("+", start + 92, row, 32) { changeZoom(1.25) }
        pauseButton = button(if (paused) "Play GIF" else "Pause GIF", start + 128, row, 66) {
            paused = !paused; lastTickNanos = System.nanoTime()
        }
        retryButton = button("Retry", start + 198, row, 52) {
            if (current()) { discardTexture(); elapsedNanos = 0; IrcMediaAssets.retry(media, true) }
        }
        button("Back", (width - 172) / 2, height - 28, 58) { onClose() }
        button("Open browser", (width - 172) / 2 + 62, height - 28, 110) { openBrowser() }
        lastTickNanos = System.nanoTime()
        if (current()) IrcMediaAssets.request(media, true)
    }

    private fun current(): Boolean = ircMediaViewerCurrent(generation, IrcMediaAssets.generation, media, messageKey,
        messageKey?.let(RelayMessages.chatStore::get), messageKey?.let(RelayMessages::currentIrcChannel) ?: true)

    private fun openBrowser() {
        if (!current()) return
        if (!minecraft.options.chatLinks().get()) { browserDisabled = true; return }
        browserDisabled = false
        val original = media.sourceUrl ?: media.url
        fun open() {
            if (BrowserLinks.supported(original.toString()) != null) InGameBrowser.open(this, original.toString())
            else {
                Util.getPlatform().openUri(original)
                if (minecraft.screen !== this) minecraft.setScreen(this)
            }
        }
        if (minecraft.options.chatLinksPrompt().get()) {
            minecraft.setScreen(ConfirmLinkScreen({ confirmed ->
                if (!current()) closeExpired()
                else {
                    if (confirmed && minecraft.options.chatLinks().get()) open()
                    else minecraft.setScreen(this)
                }
            }, original.toString(), false))
        } else open()
    }

    private fun fit() { zoom = 1.0; panX = 0.0; panY = 0.0 }
    private fun changeZoom(factor: Double) { zoom = (zoom * factor).coerceIn(0.25, 16.0); clampPan() }
    private fun clampPan() {
        val asset = paintedAsset ?: return
        val scale = minOf((width - 32).coerceAtLeast(1).toDouble() / asset.width,
            (imageBottom - imageTop).toDouble() / asset.height) * zoom
        val edgeX = ((asset.width * scale - (width - 32)) / 2).coerceAtLeast(0.0)
        val edgeY = ((asset.height * scale - (imageBottom - imageTop)) / 2).coerceAtLeast(0.0)
        panX = panX.coerceIn(-edgeX, edgeX); panY = panY.coerceIn(-edgeY, edgeY)
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (chromeTexture.prepareFrame()) chrome.close()
        chrome.begin()
        val graphics = MenuGraphics(chrome, mouseX, mouseY, width, height)
        graphics.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.PANEL, 95))
        if (!current()) {
            discardTexture()
            centerLine(graphics, 4, "This media is no longer available.", height / 2, MenuTheme.alpha(MenuTheme.MUTED))
            renderChrome(output)
            return
        }
        centerLine(graphics, 0, "${media.name ?: media.type.uppercase()} · $sender", 10, MenuTheme.alpha(MenuTheme.TEXT), 12.0)
        centerLine(graphics, 1, context.replace('\n', ' '), 27, MenuTheme.alpha(MenuTheme.MUTED), 9.0)
        centerLine(graphics, 2, media.url.host.orEmpty(), 42, MenuTheme.alpha(MenuTheme.ACCENT), 9.0)
        MenuTheme.box(graphics, 15, imageTop - 1, (width - 30).coerceAtLeast(1), imageBottom - imageTop + 2,
            MenuTheme.CARD)
        val state = IrcMediaAssets.view(media, true)
        val asset = state.asset
        if (asset !== paintedAsset) {
            discardTexture()
            paintedAsset = asset
            elapsedNanos = 0
            fit()
        }
        val now = System.nanoTime()
        if (GlobalChatConfig.animateGifs != preference) {
            preference = GlobalChatConfig.animateGifs
            paused = !preference
        }
        if (!paused && asset != null) elapsedNanos += (now - lastTickNanos).coerceIn(0, 250_000_000)
        lastTickNanos = now
        if (asset != null) {
            clampPan()
        } else {
            val detail = when (state.status) {
                IrcMediaAssetStatus.IDLE -> "Media session changed. Return to chat and open it again."
                IrcMediaAssetStatus.LOADING -> "Loading media…"
                else -> state.detail ?: "Media is unavailable. Open it in your browser."
            }
            drawDetail(graphics, detail)
        }
        val animated = asset?.frames?.size?.let { it > 1 } == true
        pauseButton.active = animated
        pauseButton.message = Component.literal(if (paused) "Play GIF" else "Pause GIF")
        fitButton.active = asset != null; minusButton.active = asset != null && zoom > 0.25; plusButton.active = asset != null && zoom < 16
        retryButton.active = state.status == IrcMediaAssetStatus.FAILED
        val status = if (browserDisabled) "Enable Chat Links in Minecraft settings to open the browser." else if (media.type == "video" && !animated) "Video · Open browser to play" else if (asset != null)
            "${asset.width} × ${asset.height} · ${(zoom * 100).roundToInt()}% of fit · Scroll to zoom, drag to pan"
            else if (state.status == IrcMediaAssetStatus.LOADING) "Downloading safely…" else "Open browser for the original"
        centerLine(graphics, 3, status, height - 69, MenuTheme.alpha(MenuTheme.MUTED), 9.0)
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks)
        renderChrome(output)
        if (asset != null) {
            val scale = minOf((width - 32).coerceAtLeast(1).toDouble() / asset.width,
                (imageBottom - imageTop).toDouble() / asset.height) * zoom
            val drawWidth = (asset.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (asset.height * scale).roundToInt().coerceAtLeast(1)
            val x = ((width - drawWidth) / 2.0 + panX).toFloat()
            val y = (imageTop + (imageBottom - imageTop - drawHeight) / 2.0 + panY).toFloat()
            val index = asset.frameIndexAt(elapsedNanos / 1_000_000)
            output.enableScissor(16, imageTop, width - 16, imageBottom)
            output.pose().pushMatrix()
            try {
                output.pose().translate(x, y)
                texture.render(output, asset.frames[index].image, drawWidth, drawHeight, index.toLong())
            } finally { output.pose().popMatrix(); output.disableScissor() }
        }
    }

    private fun renderChrome(output: GuiGraphicsExtractor) {
        val scale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        chromeTexture.render(output, chrome, width, height, scale)
    }
    private fun centerLine(graphics: MenuGraphics, slot: Int, text: String, y: Int, color: Int, size: Double = 10.0) {
        val available = (width - 24).coerceAtLeast(1)
        val native = SkijaMenuFonts.active
        var header = headers[slot]
        if (header == null || header.source != text || header.available != available || header.color != color ||
            header.size != size || header.native != native) {
            val shown = ircMediaTextEllipsis(text, available.toDouble(), size)
            header = Header(text, available, color, size, native, listOf(MenuCanvas.Run(shown, color)), MenuCanvas.textWidth(shown, size = size))
            headers[slot] = header
        }
        graphics.styledText(header.runs, (width - header.width) / 2, y.toDouble(), size)
    }
    private fun drawDetail(graphics: MenuGraphics, text: String) {
        val available = (width - 64).coerceAtLeast(1)
        val color = MenuTheme.alpha(MenuTheme.TEXT)
        if (detailText != text || detailWidth != available || detailColor != color || detailNative != SkijaMenuFonts.active) {
            detailText = text; detailWidth = available; detailColor = color; detailNative = SkijaMenuFonts.active
            val lines = menuTextLines(Component.literal(text), available)
            detailRuns = lines.map { menuTextRuns(it, color) }; detailWidths = lines.map { menuTextWidth(it) }
        }
        val lineHeight = MenuCanvas.lineHeight()
        val top = imageTop + (imageBottom - imageTop - detailRuns.size * lineHeight) / 2
        detailRuns.forEachIndexed { index, runs ->
            graphics.styledText(runs, (width - detailWidths[index]) / 2, (top + index * lineHeight).toDouble())
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (event.button() == 0 && event.y() in imageTop.toDouble()..imageBottom.toDouble() && paintedAsset != null) {
            panX += dx; panY += dy; clampPan(); return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
        if (y in imageTop.toDouble()..imageBottom.toDouble() && paintedAsset != null && vertical != 0.0) {
            changeZoom(1.2.pow(vertical.coerceIn(-4.0, 4.0))); return true
        }
        return super.mouseScrolled(x, y, horizontal, vertical)
    }

    private fun discardTexture() { texture.close(); texture = MenuTexture(); paintedAsset = null }
    override fun removed() {
        texture.close(); paintedAsset = null
        chrome.close(); chromeTexture.close()
        headers.fill(null); detailText = null; detailRuns = emptyList(); detailWidths = emptyList()
        if (generation == IrcMediaAssets.generation) IrcMediaAssets.cancel(media, true)
        super.removed()
    }
    private fun closeExpired() {
        if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen()
        minecraft.setScreen(null)
    }
    override fun tick() { if (!current()) closeExpired() else super.tick() }
    override fun onClose() {
        if (!current()) closeExpired()
        else {
            val returned = if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous
            minecraft.setScreen(returned)
            if (returned is ChatScreen && previousChatScroll != null) {
                // ChatScreen.init resets scrolling; restore the captured position after reopening it.
                (minecraft.gui.chat as IrcChatAccessor).`skymyce$setScroll`(previousChatScroll)
                minecraft.gui.chat.scrollChat(0)
            }
        }
    }
    override fun isPauseScreen() = false
}

/** Truncate using the same glyph advances as the shared renderer, without splitting surrogate pairs. */
internal fun ircMediaTextEllipsis(text: String, available: Double, size: Double = 10.0): String {
    if (MenuCanvas.textWidth(text, size = size) <= available) return text
    val suffix = "…"
    val limit = available - MenuCanvas.textWidth(suffix, size = size)
    if (limit < 0) return ""
    var width = 0.0
    var end = 0
    while (end < text.length) {
        val codepoint = text.codePointAt(end)
        val advance = MenuCanvas.textWidth(codepoint, false, false, size)
        if (width + advance > limit) break
        width += advance; end += Character.charCount(codepoint)
    }
    return text.substring(0, end).trimEnd() + suffix
}
