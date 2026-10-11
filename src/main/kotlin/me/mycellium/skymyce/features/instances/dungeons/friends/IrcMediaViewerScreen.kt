package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.ui.MenuTexture
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.pow
import kotlin.math.roundToInt

/** One explicit viewer owns one GPU texture; chat thumbnails use the shared decoded asset cache. */
internal class IrcMediaViewerScreen(private val previous: Screen?, private val media: IrcMedia,
    private val sender: String, private val context: String, internal val messageKey: IrcKey? = null) : Screen(Component.literal("IRC media")) {
    private var texture = MenuTexture()
    private var paintedAsset: IrcGif? = null
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var elapsedNanos = 0L
    private var lastTickNanos = System.nanoTime()
    private var paused = !GlobalChatConfig.animateGifs
    private var preference = GlobalChatConfig.animateGifs
    private val generation = IrcMediaAssets.generation
    private var browserDisabled = false
    private lateinit var pauseButton: Button
    private lateinit var fitButton: Button
    private lateinit var minusButton: Button
    private lateinit var plusButton: Button
    private lateinit var retryButton: Button
    private val imageTop get() = 58
    private val imageBottom get() = (height - 76).coerceAtLeast(imageTop + 1)

    override fun init() {
        if (!current()) return
        fun button(label: String, x: Int, y: Int, size: Int, action: () -> Unit): Button =
            addRenderableWidget(Button.builder(Component.literal(label)) { action() }.bounds(x, y, size, 20).build())
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
        if (minecraft.options.chatLinksPrompt().get()) {
            minecraft.setScreen(ConfirmLinkScreen({ confirmed ->
                if (!current()) closeExpired()
                else {
                    if (confirmed) Util.getPlatform().openUri(media.url)
                    minecraft.setScreen(this)
                }
            }, media.url.toString(), false))
        } else Util.getPlatform().openUri(media.url)
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
        output.fill(0, 0, width, height, 0xf2111419.toInt())
        if (!current()) {
            discardTexture()
            output.centeredText(font, "This media is no longer available.", width / 2, height / 2, 0xffbcc4d0.toInt())
            return
        }
        output.centeredText(font, font.plainSubstrByWidth("${media.name ?: media.type.uppercase()} · $sender", width - 24), width / 2, 12, 0xffffffff.toInt())
        output.centeredText(font, font.plainSubstrByWidth(context.replace('\n', ' '), width - 24), width / 2, 27, 0xffbcc4d0.toInt())
        output.centeredText(font, media.url.host.orEmpty(), width / 2, 42, 0xff8f9eb1.toInt())
        output.fill(16, imageTop, width - 16, imageBottom, 0xff080a0d.toInt())
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
            val scale = minOf((width - 32).coerceAtLeast(1).toDouble() / asset.width,
                (imageBottom - imageTop).toDouble() / asset.height) * zoom
            val drawWidth = (asset.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (asset.height * scale).roundToInt().coerceAtLeast(1)
            val x = ((width - drawWidth) / 2.0 + panX).toFloat()
            val y = (imageTop + (imageBottom - imageTop - drawHeight) / 2.0 + panY).toFloat()
            val index = asset.frameIndexAt(elapsedNanos / 1_000_000)
            output.enableScissor(16, imageTop, width - 16, imageBottom)
            output.pose().pushMatrix()
            output.pose().translate(x, y)
            texture.render(output, asset.frames[index].image, drawWidth, drawHeight, index.toLong())
            output.pose().popMatrix()
            output.disableScissor()
        } else {
            val detail = when (state.status) {
                IrcMediaAssetStatus.IDLE -> "Media session changed. Return to chat and open it again."
                IrcMediaAssetStatus.LOADING -> "Loading media…"
                else -> state.detail ?: "Media is unavailable. Open it in your browser."
            }
            val lines = font.split(Component.literal(detail), (width - 64).coerceAtLeast(1))
            val y = imageTop + (imageBottom - imageTop - lines.size * font.lineHeight) / 2
            lines.forEachIndexed { index, line ->
                output.text(font, line, (width - font.width(line)) / 2, y + index * font.lineHeight, 0xffd2d9e3.toInt())
            }
        }
        val animated = asset?.frames?.size?.let { it > 1 } == true && media.type != "video"
        pauseButton.active = animated
        pauseButton.message = Component.literal(if (paused) "Play GIF" else "Pause GIF")
        fitButton.active = asset != null; minusButton.active = asset != null && zoom > 0.25; plusButton.active = asset != null && zoom < 16
        retryButton.active = state.status == IrcMediaAssetStatus.FAILED
        val status = if (browserDisabled) "Enable Chat Links in Minecraft settings to open the browser." else if (media.type == "video") "Video · Open browser to play" else if (asset != null)
            "${asset.width} × ${asset.height} · ${(zoom * 100).roundToInt()}% of fit · Scroll to zoom, drag to pan"
            else if (state.status == IrcMediaAssetStatus.LOADING) "Downloading safely…" else "Open browser for the original"
        output.centeredText(font, font.plainSubstrByWidth(status, width - 24), width / 2, height - 69, 0xffa7b5c8.toInt())
        super.extractRenderState(output, mouseX, mouseY, partialTicks)
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
        if (generation == IrcMediaAssets.generation) IrcMediaAssets.cancel(media, true)
    }
    private fun closeExpired() {
        if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen()
        minecraft.setScreen(null)
    }
    override fun tick() { if (!current()) closeExpired() else super.tick() }
    override fun onClose() {
        if (!current()) closeExpired()
        else minecraft.setScreen(if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous)
    }
    override fun isPauseScreen() = false
}
