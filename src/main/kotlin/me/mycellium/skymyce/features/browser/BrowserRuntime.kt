package me.mycellium.skymyce.features.browser

import com.mojang.blaze3d.systems.RenderSystem
import de.keksuccino.rinku.OSPlatform
import de.keksuccino.rinku.Rinku
import de.keksuccino.rinku.RinkuRenderCoordinator
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener
import de.keksuccino.rinku.binarydownload.RinkuDownloader
import me.mycellium.skymyce.SkyMyce
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import org.cef.CefApp
import org.cef.callback.CefCommandLine
import org.cef.handler.CefAppHandlerAdapter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

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
