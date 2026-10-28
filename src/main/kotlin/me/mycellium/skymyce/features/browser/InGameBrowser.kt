package me.mycellium.skymyce.features.browser

import com.mojang.blaze3d.systems.RenderSystem
import de.keksuccino.rinku.OSPlatform
import de.keksuccino.rinku.Rinku
import de.keksuccino.rinku.RinkuBrowser
import de.keksuccino.rinku.RinkuClient
import de.keksuccino.rinku.RinkuRenderCoordinator
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener
import de.keksuccino.rinku.binarydownload.RinkuDownloader
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.Vector
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
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
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.*
import org.cef.callback.CefCommandLine
import org.cef.handler.*
import org.cef.handler.CefAppHandlerAdapter
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.handler.CefRequestHandler.TerminationStatus
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.lwjgl.glfw.GLFW

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
                SkyMyce.logger.warn("Could not open the in-game browser page", failure)
            } catch (failure: LinkageError) {
                failed = true
                status = "Chromium could not start on this platform. Use Open externally."
                SkyMyce.logger.warn("Could not open the in-game browser page", failure)
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
            me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.BROWSER_PUMP) { RinkuRenderCoordinator.pumpOnRenderThread() }
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

/** Any public HTTPS website may open; navigation never admits local files, custom protocols, IP literals or local hosts. */
object BrowserLinks {
    private val numericHost = Regex("[0-9.]+")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val videoPath = Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    fun supported(value: String?): URI? = https(value)?.takeIf { publicHost(it.host) }

    /** Hypixel pages and YouTube videos only: the sources Daily Digest news may link to. */
    fun trusted(value: String?): URI? {
        val uri = https(value) ?: return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (host == "hypixel.net" || host.endsWith(".hypixel.net")) return uri
        if (host !in youtubeHosts) return null
        val path = uri.path.orEmpty()
        val video = if (host == "youtu.be") path.removePrefix("/").takeIf(videoId::matches)
        else if (path == "/watch") uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
            .singleOrNull()?.substringAfter('=')?.takeIf(videoId::matches)
        else videoPath.matchEntire(path)?.groupValues?.get(1)
        return uri.takeIf { video != null && uri.rawQuery.orEmpty().split('&').none { part ->
            part.substringBefore('=').lowercase(Locale.ROOT) in setOf("url", "redirect", "redirect_url", "q", "u")
        } }
    }

    fun navigationAllowed(value: String?): Boolean = value == "about:blank" || supported(value) != null

    private fun publicHost(value: String): Boolean {
        val host = value.lowercase(Locale.ROOT)
        return '.' in host && ':' !in host && !host.matches(numericHost) &&
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")
    }

    private fun https(value: String?, maximumLength: Int = 2048): URI? = value?.takeIf { it.length in 1..maximumLength && it.none(Char::isISOControl) }?.let {
        runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
        }
    }

    fun resourceAllowed(value: String?): Boolean {
        if (value == "about:blank" || value?.startsWith("data:") == true || value?.startsWith("chrome-extension://") == true) return true
        if (value?.startsWith("blob:") == true) return navigationAllowed(value.removePrefix("blob:"))
        return https(value, 8192)?.let { publicHost(it.host) } == true
    }
}

internal object BrowserRuntime {
    private var preparation: CompletableFuture<Unit>? = null
    private var extension: Path? = null
    private var sessionRoot: Path? = null
    private var lifecycleRegistered = false
    private var initialized = false
    @Volatile private var stopping = false
    @Volatile private var stage = "Preparing browser"

    @Synchronized fun prepare(): CompletableFuture<Unit> {
        if (stopping) return CompletableFuture.failedFuture(CancellationException("Minecraft is stopping"))
        preparation?.takeUnless { it.isCompletedExceptionally }?.let { return it }
        if (!lifecycleRegistered) {
            lifecycleRegistered = true
            ClientLifecycleEvents.CLIENT_STOPPING.register {
                synchronized(this) { stopping = true }
                try { RinkuRenderCoordinator.shutdownOnRenderThread() }
                finally {
                    try { Rinku.shutdown() }
                    finally { sessionRoot?.let { cleanupSession(it) } }
                }
            }
        }
        val gameDirectory = FabricLoader.getInstance().gameDir
        stage = "Preparing ad blocker"
        return CompletableFuture.supplyAsync { withBrowserClassLoader {
            extension = BrowserExtension.prepare(gameDirectory.resolve("sky-myce-browser/extensions"))
            if (stopping) throw CancellationException("Minecraft is stopping")
            val libraries = gameDirectory.resolve("rinku-libraries")
            Files.createDirectories(libraries)
            System.setProperty("rinku.libraries.path", libraries.toRealPath().toString())
            stage = "Checking Chromium"
            val progress = RinkuDownloadListener.INSTANCE
            progress.setTask(Component.literal("Checking Chromium"))
            val installer = RinkuDownloader(RinkuDownloader.OFFICIAL_MIRROR, OSPlatform.getPlatform(), RinkuDownloader.DownloadPolicy.defaults())
            val installed = installer.installOrUpdate(false)
            if (stopping) throw CancellationException("Minecraft is stopping")
            System.setProperty("jcef.path", installed.installationDirectory().toRealPath().toString())
            synchronized(this) {
                if (stopping) throw CancellationException("Minecraft is stopping")
                val sessions = gameDirectory.resolve("sky-myce-browser/sessions")
                Files.createDirectories(sessions)
                // Shutdown cleanup fails while Chromium still holds its files (~150 MB each); older sessions are unlocked now.
                // The age check leaves a session that another running game sharing this directory may still be using.
                val stale = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
                Files.newDirectoryStream(sessions.toRealPath(), "cef-*").use { old -> old.toList() }
                    .filter { Files.isDirectory(it, NOFOLLOW_LINKS) && Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() < stale }
                    .forEach(::cleanupSession)
                sessionRoot = Files.createTempDirectory(sessions.toRealPath(), "cef-")
            }
            stage = "Starting browser"
            Unit
        } }.whenComplete { _, failure ->
            if (failure != null && !stopping) {
                stage = "Browser unavailable: ${(failure.cause ?: failure).message.orEmpty().take(180)}"
                SkyMyce.logger.warn("Could not prepare the in-game browser", failure)
            }
        }.also { preparation = it }
    }

    @Synchronized fun initialize(): Boolean {
        RenderSystem.assertOnRenderThread()
        if (stopping) return false
        if (initialized) return true
        if (preparation?.let { it.isDone && !it.isCompletedExceptionally } != true) return false
        if (Rinku.isInitialized()) {
            stage = "Browser runtime was started by another mod before its security settings were applied"
            return false
        }
        return runCatching { withBrowserClassLoader {
            Rinku.getSettings().apply {
                setDisableWebSecurity(false)
                setUseCache(false)
                setEnableWidevineCdm(false)
                setBrowserPreloadEnabled(false)
            }
            val extensionPath = checkNotNull(extension).toString()
            val dataPath = checkNotNull(sessionRoot).toString()
            CefApp.addAppHandler(object : CefAppHandlerAdapter(null) {
                override fun onBeforeCommandLineProcessing(processType: String, commandLine: CefCommandLine) {
                    if (processType.isEmpty()) {
                        commandLine.removeSwitch("disable-web-security")
                        commandLine.appendSwitchWithValue("load-extension", extensionPath)
                        // CEF's default root is shared across applications even with its profile cache disabled.
                        commandLine.appendSwitchWithValue("user-data-dir", dataPath)
                        commandLine.appendSwitchWithValue("autoplay-policy", "document-user-activation-required")
                        if (java.lang.Boolean.getBoolean("skymyce.browserMuted")) commandLine.appendSwitch("mute-audio")
                    }
                }
            })
            Rinku.initialize().also { success ->
                initialized = success
                stage = if (success) "Browser ready" else "Chromium could not start; use Open externally"
            }
        } }.getOrElse { failure ->
            stage = "Chromium could not start; use Open externally"
            SkyMyce.logger.warn("Could not start the in-game browser", failure)
            false
        }
    }

    fun status(): String {
        if (stage != "Checking Chromium") return stage
        val progress = RinkuDownloadListener.INSTANCE
        val task = progress.task.string.ifBlank { stage }
        return if (progress.progress > 0f) "$task ${(progress.progress * 100).toInt().coerceIn(0, 100)}%" else task
    }

    private fun <T> withBrowserClassLoader(action: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        // Fabric's SPI providers and interfaces must resolve through the same mod class loader.
        thread.contextClassLoader = javaClass.classLoader
        try { return action() }
        finally { thread.contextClassLoader = previous }
    }

    private fun cleanupSession(directory: Path) {
        runCatching {
            val parent = FabricLoader.getInstance().gameDir.resolve("sky-myce-browser/sessions").toRealPath()
            check(directory.parent == parent && directory.fileName.toString().startsWith("cef-"))
            if (!Files.exists(directory, NOFOLLOW_LINKS)) return
            val paths = Files.walk(directory, 12).use { it.limit(8193).toList() }
            if (paths.size > 8192 || paths.any { Files.isDirectory(it, NOFOLLOW_LINKS) && directory.relativize(it).nameCount >= 12 })
                throw IOException("Browser session cleanup exceeded its file limit")
            paths.sortedDescending().forEach { Files.deleteIfExists(it) }
        }.onFailure { SkyMyce.logger.warn("Could not remove the temporary in-game browser session", it) }
    }
}

/** A dedicated client owns the page's handlers; web content receives no Minecraft or community API bridge. */
internal class BrowserSession(url: String, width: Int, height: Int, private val muted: () -> Boolean,
                              private val status: (String) -> Unit) : AutoCloseable {
    private val handle = Rinku.getApp().handle.createClient()
    private val client: RinkuClient
    val browser: RinkuBrowser
    @Volatile var ready = false; private set
    @Volatile private var closed = false

    init {
        var opened: RinkuBrowser? = null
        try {
            require(BrowserLinks.navigationAllowed(url))
            client = RinkuClient(handle)
            browser = RinkuBrowser(client, "about:blank", false).also { opened = it }
            handle.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, redirect: Boolean): Boolean {
                    val allowed = if (frame.isMain) BrowserLinks.navigationAllowed(request.url) else BrowserLinks.resourceAllowed(request.url)
                    if (!allowed && frame.isMain) status("Only public HTTPS websites open here. Use Open externally.")
                    return !allowed
                }
                override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, target: String, userGesture: Boolean): Boolean {
                    if (userGesture && BrowserLinks.navigationAllowed(target)) browser.loadURL(target)
                    return true
                }
                override fun getResourceRequestHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest,
                    navigation: Boolean, download: Boolean, initiator: String?, disableDefaultHandling: BoolRef): CefResourceRequestHandler =
                    object : CefResourceRequestHandlerAdapter() {
                        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest) =
                            !BrowserLinks.resourceAllowed(request.url)
                    }
                override fun onRenderProcessTerminated(browser: CefBrowser, termination: TerminationStatus, errorCode: Int, errorText: String) {
                    status("The web page stopped. Reload it or reopen the browser.")
                }
            })
            handle.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) {
                    if (closed) { browser.close(true); return }
                    browser.setAudioMuted(muted())
                    browser.setWindowlessFrameRate(30)
                    ready = true
                    browser.loadURL(url)
                }
                override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, target: String, name: String): Boolean {
                    if (BrowserLinks.navigationAllowed(target)) browser.loadURL(target)
                    return true
                }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transition: CefRequest.TransitionType) {
                    if (frame.isMain) status("Loading page…")
                }
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, code: Int) {
                    if (frame.isMain) status(if (code >= 400) "The website returned HTTP $code." else "uBlock Origin Lite · Esc to return")
                }
                override fun onLoadError(browser: CefBrowser, frame: CefFrame, error: ErrorCode, text: String, failedUrl: String) {
                    if (frame.isMain && error != ErrorCode.ERR_ABORTED) status("Page unavailable (${error.name}).")
                }
            })
            client.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(browser: CefBrowser, frame: CefFrame, params: CefContextMenuParams, model: CefMenuModel) { model.clear() }
            })
            handle.removeDownloadHandler()
            handle.addDownloadHandler(object : CefDownloadHandlerAdapter() {
                override fun canDownload(browser: CefBrowser, url: String, method: String) = false
            })
            handle.addPermissionHandler(object : CefPermissionHandlerAdapter() {
                override fun onRequestMediaAccessPermission(browser: CefBrowser, frame: CefFrame, origin: String, permissions: Int, callback: CefMediaAccessCallback): Boolean {
                    callback.Cancel(); return true
                }
                override fun onShowPermissionPrompt(browser: CefBrowser, id: Long, origin: String, permissions: Int, callback: CefPermissionPromptCallback): Boolean {
                    callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY); return true
                }
            })
            handle.addDialogHandler(object : CefDialogHandler {
                override fun onFileDialog(browser: CefBrowser, mode: CefDialogHandler.FileDialogMode, title: String, defaultPath: String,
                    filters: Vector<String>, extensions: Vector<String>, descriptions: Vector<String>, callback: CefFileDialogCallback): Boolean {
                    callback.Cancel(); return true
                }
            })
            browser.resize(width, height)
            browser.setCloseAllowed()
            browser.createImmediately()
        } catch (failure: Throwable) {
            runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { handle.dispose() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (ready) browser.setAudioMuted(true)
            browser.close()
        }
        finally { ready = false; handle.dispose() }
    }
}

internal object BrowserExtension {
    const val VERSION = "2026.1012.1545"
    const val SHA256 = "73909c8f26d608b00a99f446192630868bea4f947ba293c29bbd18ae948d903b"

    fun prepare(cache: Path): Path {
        Files.createDirectories(cache)
        val root = cache.toRealPath()
        val target = root.resolve("ubol-$VERSION")
        if (complete(target)) return target
        if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Ad blocker cache is incomplete: $target")
        val bytes = javaClass.getResourceAsStream("/browser/ubol-$VERSION.zip")?.use { it.readNBytes(12 * 1024 * 1024 + 1) }
            ?: throw IOException("Bundled ad blocker is missing")
        if (bytes.size > 12 * 1024 * 1024 || HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) != SHA256)
            throw IOException("Bundled ad blocker checksum does not match")
        val staging = Files.createTempDirectory(root, ".ubol-")
        try {
            extractBrowserExtension(bytes.inputStream(), staging)
            if (!Files.isRegularFile(staging.resolve("manifest.json"), NOFOLLOW_LINKS)) throw IOException("Ad blocker manifest is missing")
            Files.writeString(staging.resolve(".complete"), SHA256, CREATE_NEW)
            try {
                try { Files.move(staging, target, ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(staging, target) }
            }
            catch (failure: IOException) { if (!complete(target)) throw failure }
            return target
        } finally {
            // Staging is a new, direct child of this cache; never traverse links during cleanup.
            check(staging.parent == root && staging.fileName.toString().startsWith(".ubol-"))
            if (Files.exists(staging, NOFOLLOW_LINKS)) runCatching {
                Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun complete(directory: Path): Boolean = Files.isDirectory(directory, NOFOLLOW_LINKS) &&
        Files.isRegularFile(directory.resolve("manifest.json"), NOFOLLOW_LINKS) &&
        Files.isRegularFile(directory.resolve(".complete"), NOFOLLOW_LINKS) &&
        runCatching { Files.size(directory.resolve(".complete")) == SHA256.length.toLong() && Files.readString(directory.resolve(".complete")) == SHA256 }.getOrDefault(false)
}

internal fun extractBrowserExtension(archive: InputStream, destination: Path) {
    val root = destination.toAbsolutePath().normalize()
    val seen = HashSet<String>()
    var total = 0L
    ZipInputStream(archive).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (name.isBlank() || name.length > 256 || '\\' in name || ':' in name || name.startsWith('/') ||
                name.trimEnd('/').split('/').any { it == "." || it == ".." || it.isEmpty() } ||
                !seen.add(name.trimEnd('/').lowercase(Locale.ROOT)) || seen.size > 2048)
                throw IOException("Unsafe or duplicate ad blocker archive entry")
            val output = root.resolve(name).normalize()
            if (output == root || !output.startsWith(root)) throw IOException("Ad blocker archive path escapes its cache")
            if (entry.isDirectory) {
                if (zip.read() != -1) throw IOException("Ad blocker archive directory contains data")
                Files.createDirectories(output)
            } else {
                Files.createDirectories(output.parent)
                var size = 0L
                Files.newOutputStream(output, CREATE_NEW).use { file ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        size += count; total += count
                        if (size > 8L * 1024 * 1024 || total > 64L * 1024 * 1024) throw IOException("Ad blocker archive exceeds its size limit")
                        file.write(buffer, 0, count)
                    }
                }
            }
            zip.closeEntry()
        }
    }
}
