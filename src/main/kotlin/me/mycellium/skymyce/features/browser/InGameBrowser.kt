package me.mycellium.skymyce.features.browser

import de.keksuccino.rinku.RinkuRenderCoordinator
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

object InGameBrowser {
    fun open(parent: Screen, url: String) {
        val target = BrowserLinks.supported(url) ?: return
        Minecraft.getInstance().setScreen(InGameBrowserScreen(parent, target.toASCIIString()))
    }
}

internal data class BrowserViewport(val x: Int, val y: Int, val width: Int, val height: Int, val pixelsWide: Int, val pixelsHigh: Int) {
    fun contains(x: Double, y: Double) = x >= this.x && x < this.x + width && y >= this.y && y < this.y + height
    fun mouseX(x: Double) = ((x - this.x) * pixelsWide / width).roundToInt()
    fun mouseY(y: Double) = ((y - this.y) * pixelsHigh / height).roundToInt()
}

internal fun browserViewport(width: Int, height: Int, guiScale: Double): BrowserViewport {
    require(width > 0 && height > 0 && guiScale.isFinite() && guiScale > 0)
    val logicalWidth = (width - 16).coerceAtLeast(1)
    val logicalHeight = (height - 82).coerceAtLeast(1)
    val scale = minOf(guiScale, 1920.0 / logicalWidth, 1080.0 / logicalHeight)
    return BrowserViewport(8, 58, logicalWidth, logicalHeight, (logicalWidth * scale).roundToInt().coerceAtLeast(1),
        (logicalHeight * scale).roundToInt().coerceAtLeast(1))
}

/** The native browser renders only this screen; returning to the previous screen closes its page and audio. */
internal class InGameBrowserScreen(private val parent: Screen, private val initialUrl: String) : Screen(Component.literal("Web browser")) {
    internal var session: BrowserSession? = null; private set
    private var preparation: CompletableFuture<Unit>? = null
    private var failed = false
    private var webFocused = false
    private var pressedMouse: Int? = null
    @Volatile private var status = "Preparing browser…"
    @Volatile private var muted = Minecraft.getInstance().options.getSoundSourceOptionInstance(SoundSource.MASTER).get() == 0.0
    private val chrome = MenuCanvas()
    private var texture = MenuTexture()
    private lateinit var viewport: BrowserViewport
    private lateinit var back: MenuButton
    private lateinit var forward: MenuButton
    private lateinit var reload: MenuButton
    private lateinit var sound: MenuButton

    init { require(BrowserLinks.navigationAllowed(initialUrl)) }

    override fun init() {
        super.init()
        viewport = browserViewport(width, height, minecraft.window.guiScale.toDouble())
        fun button(label: String, x: Int, size: Int, action: () -> Unit) = addRenderableWidget(MenuButton(Component.literal(label)) { action() }
            .apply { setRectangle(size, 20, x, 28) })
        back = button("Back", 8, 42) { session?.browser?.goBack() }
        forward = button("Next", 54, 42) { session?.browser?.goForward() }
        reload = button("Reload", 100, 48) { session?.browser?.reload() }
        sound = button(if (muted) "Unmute" else "Mute", 152, 48) { muted = !muted; updateAudio() }
        button("Close", width - 54, 46) { onClose() }
        if (width >= 340) button("Open externally", width - 158, 100) {
            val target = session?.browser?.url ?: initialUrl
            if (BrowserLinks.supported(target) != null) Util.getPlatform().openUri(target)
        }
        session?.browser?.resize(viewport.pixelsWide, viewport.pixelsHigh)
        if (preparation == null && !failed) preparation = BrowserRuntime.prepare()
    }

    override fun tick() {
        super.tick()
        val pending = preparation
        if (!failed && session == null && pending?.isDone == true) {
            try {
                pending.join()
                check(BrowserRuntime.initialize()) { BrowserRuntime.status() }
                session = BrowserSession(initialUrl, viewport.pixelsWide, viewport.pixelsHigh,
                    { muted || masterMuted() }, { status = it })
                status = "Loading page…"
            } catch (failure: Exception) {
                failed = true
                status = "Browser unavailable. Reopen it to retry setup, or use Open externally."
            } catch (failure: LinkageError) {
                failed = true
                status = "Chromium could not start on this platform. Use Open externally."
            }
        }
        val browser = session?.browser
        back.active = session?.ready == true && browser?.canGoBack() == true
        forward.active = session?.ready == true && browser?.canGoForward() == true
        reload.active = session?.ready == true
        sound.active = session?.ready == true
        sound.message = Component.literal(if (muted || masterMuted()) "Unmute" else "Mute")
        updateAudio()
    }

    private fun masterMuted() = minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).get() == 0.0
    private var lastMute: Boolean? = null
    private fun updateAudio() {
        val quiet = muted || masterMuted()
        if (session?.ready == true && quiet != lastMute) { session?.browser?.setAudioMuted(quiet); lastMute = quiet }
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (texture.prepareFrame()) chrome.close()
        chrome.begin()
        val graphics = MenuGraphics(chrome, mouseX, mouseY, width, height)
        graphics.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.PANEL, 100))
        val browser = session?.browser
        val address = browser?.url?.takeUnless { it == "about:blank" } ?: initialUrl
        graphics.text(font, font.plainSubstrByWidth(address, (width - 20).coerceAtLeast(1)), 8, 10, MenuTheme.alpha(MenuTheme.ACCENT), false)
        val message = if (session == null && !failed) BrowserRuntime.status() else status
        graphics.text(font, font.plainSubstrByWidth(message, (width - 20).coerceAtLeast(1)), 8, height - 16, MenuTheme.alpha(MenuTheme.MUTED), false)
        if (session == null || !session!!.ready) graphics.centeredText(font, "${if (failed) "Browser unavailable" else "Preparing Chromium…"}", width / 2, height / 2, -1)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        texture.render(output, chrome, width, height, minOf(minecraft.window.guiScale.toDouble(), 2.0))
        if (session != null) {
            RinkuRenderCoordinator.pumpOnRenderThread()
            browser?.textureIdentifier?.let { id -> output.blit(RenderPipelines.GUI_TEXTURED, id, viewport.x, viewport.y, 0f, 0f,
                viewport.width, viewport.height, viewport.width, viewport.height) }
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (viewport.contains(event.x(), event.y()) && session?.ready == true) {
            webFocused = true; focused = null; pressedMouse = event.button()
            session!!.browser.sendMousePress(viewport.mouseX(event.x()), viewport.mouseY(event.y()), event.button())
            session!!.browser.setFocus(true)
            return true
        }
        webFocused = false; session?.browser?.setFocus(false)
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (pressedMouse != null && session?.ready == true) {
            session!!.browser.sendMouseRelease(viewport.mouseX(event.x()), viewport.mouseY(event.y()), event.button())
            pressedMouse = null; return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseMoved(x: Double, y: Double) {
        if ((viewport.contains(x, y) || pressedMouse != null) && session?.ready == true)
            session!!.browser.sendMouseMove(viewport.mouseX(x), viewport.mouseY(y))
        super.mouseMoved(x, y)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (pressedMouse != null && session?.ready == true) {
            session!!.browser.sendMouseMove(viewport.mouseX(event.x()), viewport.mouseY(event.y())); return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
        if (viewport.contains(x, y) && session?.ready == true) {
            session!!.browser.sendMouseWheel(viewport.mouseX(x), viewport.mouseY(y), vertical, 0); return true
        }
        return super.mouseScrolled(x, y, horizontal, vertical)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        if (webFocused && session?.ready == true) {
            session!!.browser.sendKeyPress(event.key(), event.scancode().toLong(), event.modifiers()); return true
        }
        return super.keyPressed(event)
    }
    override fun keyReleased(event: KeyEvent): Boolean {
        if (webFocused && session?.ready == true) {
            session!!.browser.sendKeyRelease(event.key(), event.scancode().toLong(), event.modifiers()); return true
        }
        return super.keyReleased(event)
    }
    override fun charTyped(event: CharacterEvent): Boolean {
        if (webFocused && session?.ready == true) {
            Character.toChars(event.codepoint()).forEach { session!!.browser.sendKeyTyped(it, 0) }; return true
        }
        return super.charTyped(event)
    }

    override fun onClose() { minecraft.setScreen(parent) }
    override fun isPauseScreen() = false
    override fun removed() {
        val previous = session
        session = null; preparation = null; pressedMouse = null; webFocused = false; lastMute = null
        runCatching { previous?.close() }.onFailure { SkyMyce.logger.warn("Could not close browser page cleanly", it) }
        try { chrome.close() }
        finally { texture.close(); texture = MenuTexture(); super.removed() }
    }
}
