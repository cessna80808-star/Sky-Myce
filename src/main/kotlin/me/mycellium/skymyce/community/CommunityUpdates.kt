package me.mycellium.skymyce.community

import com.google.gson.JsonParser
import me.mycellium.skymyce.community.update.UpdateInstaller
import me.mycellium.skymyce.loader.LoaderRestart
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Executors
import java.lang.management.ManagementFactory
import java.util.zip.ZipFile

/** Verified staging is approved by Update Now or the user's persisted auto-update preference. */
object CommunityUpdates {
    var autoUpdate = false; private set
    var status = "Updates are checked without interrupting gameplay"; private set
    val releaseNotes get() = available?.notes ?: ""
    val canCheck get() = !busy && plan == null
    val canInstall get() = available != null && !busy && plan == null
    val canDefer get() = available != null && !restarting
    val canRestart get() = !busy && !restarting && runCatching { FabricLauncherBase.getLauncher().classPath.isNotEmpty() }.getOrDefault(false)
    val canChangePreferences get() = !restarting
    private var available: CommunityRelease? = null
    private var busy = false
    private var checked = false
    private var started = System.currentTimeMillis()
    private var deferred = ""
    private var notified = false
    private var plan: Path? = null
    private var helper: Path? = null
    private var automaticPending = false
    private var automaticAttempt = ""
    private var restarting = false
    private var restartCommitted = false
    private val selection = CommunityUpdateSelection()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Sky-Myce updates").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val directory get() = FabricLoader.getInstance().gameDir.resolve(".sky-myce-updates")
    private val preferences by lazy { CommunityUpdatePreferenceStore(directory.resolve("preferences.json")) }
    fun initialize() {
        val saved = runCatching { preferences.read() }.getOrDefault(CommunityUpdatePreferences())
        deferred = saved.deferredVersion; autoUpdate = saved.autoUpdate
        directory.resolve("result.txt").takeIf(Files::isRegularFile)?.let { result ->
            status = runCatching { Files.newInputStream(result).use { it.readNBytes(1024).toString(Charsets.UTF_8).trim() } }.getOrDefault(status)
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            if (restartCommitted) return@register
            val stagedPlan = plan; val stagedHelper = helper
            if (stagedPlan != null && stagedHelper != null) runCatching {
                startHelper(stagedHelper, stagedPlan).outputStream.close()
            }.onFailure { status = "Updater could not start. Current version remains installed; update manually." }
        }
    }
    private fun startHelper(jar: Path, updatePlan: Path?): Process {
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "javaw.exe" else "java").toString()
        val current = ProcessHandle.current()
        val arguments = if (updatePlan != null) listOf(updatePlan.toString()) else listOf("--restart", current.pid().toString(), current.info().startInstant().orElseThrow().toEpochMilli().toString())
        val builder = ProcessBuilder(listOf(executable, "-cp", jar.toString(), UpdateInstaller::class.java.name) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        LoaderRestart.clearInjectedOptions(builder)
        return builder.start()
    }
    fun restart() {
        if (!canRestart) return
        val minecraft = Minecraft.getInstance()
        val user = minecraft.user
        val account = user.profileId
        val current = linkedMapOf("username" to user.name, "uuid" to account.toString(),
            "accessToken" to user.accessToken, "clientId" to user.clientId.orElse(""), "xuid" to user.xuid.orElse(""),
            "gameDir" to FabricLoader.getInstance().gameDir.toAbsolutePath().toString())
        val original = FabricLoader.getInstance().getLaunchArguments(false)
        val arguments = mutableListOf<String>()
        var index = 0
        while (index < original.size) {
            val argument = original[index++]
            val key = argument.removePrefix("--").substringBefore('=')
            if (argument.startsWith("--") && key in current) {
                if ('=' !in argument && index < original.size) index++
            } else arguments += argument
        }
        current.forEach { (key, value) -> arguments += "--$key"; arguments += value }
        val properties = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("fabric.") || it in setOf("java.library.path", "org.lwjgl.librarypath", "java.io.tmpdir") }
            .associateWith { System.getProperty(it) }
        val classPath = FabricLauncherBase.getLauncher().classPath.toList()
        val vmArguments = ManagementFactory.getRuntimeMXBean().inputArguments.toList()
        val updatePlan = plan
        restarting = true; busy = true; status = "Preparing to restart Minecraft…"
        worker.submit {
            var process: Process? = null
            val result = runCatching {
                Files.createDirectories(directory)
                val jar = helper ?: directory.resolve("restart-helper.jar").also(UpdateInstaller::writeHelper)
                process = startHelper(jar, updatePlan)
                LoaderRestart.request(process, Path.of(System.getProperty("user.dir")), vmArguments, classPath, properties, arguments.toTypedArray())
            }
            minecraft.execute {
                val prepared = process
                if (result.isSuccess && account == minecraft.user.profileId && plan == updatePlan && prepared != null && runCatching { LoaderRestart.commit(prepared) }.isSuccess) {
                    restartCommitted = true; minecraft.stop()
                } else {
                    prepared?.destroyForcibly()
                    restarting = false; busy = false
                    val stage = LoaderRestart.failureStage(result.exceptionOrNull())
                    val step = when (stage) {
                        "RUNTIME_LIBRARIES" -> "runtime libraries"
                        "NATIVE_LIBRARIES" -> "native libraries"
                        "WRAPPER" -> "restart helper"
                        "JVM_OPTIONS" -> "Java settings"
                        "TIMEOUT" -> "preparation timeout"
                        else -> "helper connection"
                    }
                    me.mycellium.skymyce.SkyMyce.logger.warn("Community restart preparation failed at {}.", stage)
                    status = "Restart preparation failed ($step). Minecraft is still open; retry or restart from your launcher. Details are in logs/latest.log."
                }
            }
        }
    }
    fun tick() {
        if (!checked && System.currentTimeMillis() - started >= 15000) check(false)
        val release = available ?: return
        if (autoUpdate && release.version != deferred && release.version != automaticAttempt && !busy && plan == null) {
            install(automatic = true)
        }
        if (!notified && release.version != deferred && Minecraft.getInstance().player != null) {
            notified = true
            me.mycellium.skymyce.utils.Utils.displayMessage(Component.literal(if (autoUpdate)
                "Sky-Myce ${release.version} is available. Auto-update stages verified updates for installation after exit. Open /sm community for status."
            else "Sky-Myce ${release.version} is available. Open /sm community for notes, Update Now or Later."))
        }
    }
    private fun endpoint(path: String): URI {
        val backend = URI(CommunityClient.configuration.getProperty("backend"))
        require(backend.scheme == "https" && backend.userInfo == null && backend.rawQuery == null && backend.fragment == null)
        return backend.resolve(path)
    }
    fun check(manual: Boolean) {
        if (busy || plan != null) return
        busy = true; checked = true; status = "Checking for updates…"
        worker.submit {
            val result = runCatching {
                val response = http.send(HttpRequest.newBuilder(endpoint("/community/release")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = readCommunityBody(response.body(), 65536, Duration.ofSeconds(20))
                require(response.statusCode() == 200)
                verifyCommunityRelease(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject, CommunityClient.configuration)
            }
            Minecraft.getInstance().execute {
                busy = false
                result.fold({ release ->
                    available = release.takeIf { newerCommunityVersion(it.version, CommunityClient.version) }
                    status = if (available == null) "You're running the current version" else if (!manual && release.version == deferred) "Update ${release.version} deferred. This version remains available." else "Sky-Myce ${release.version} is available. Choose Update Now or Later."
                }, { status = "Update check unavailable. Keep playing and try again later." })
            }
        }
    }
    fun defer() {
        if (restarting) return
        val release = available ?: return
        if (!selection.defer(release.version)) return
        val cancelledPlan = plan; val cancelledHelper = helper
        plan = null; helper = null
        automaticPending = false
        deferred = release.version; notified = true; status = "Update ${release.version} deferred. This version remains available."
        persistPreferences()
        worker.submit {
            if (cancelledPlan != null && cancelledHelper != null) discard(cancelledPlan, cancelledHelper)
        }
    }
    fun setAutoUpdate(enabled: Boolean) {
        if (restarting) return
        if (enabled == autoUpdate) return
        autoUpdate = enabled; automaticAttempt = ""
        val cancelledPlan = plan.takeIf { automaticPending && !enabled }
        val cancelledHelper = helper.takeIf { automaticPending && !enabled }
        if (!enabled && automaticPending) {
            selection.cancel(); plan = null; helper = null; automaticPending = false
            status = "Auto-update Off. Automatic staging cancelled; Update Now remains available."
        }
        persistPreferences()
        worker.submit {
            if (cancelledPlan != null && cancelledHelper != null) discard(cancelledPlan, cancelledHelper)
        }
    }
    private fun persistPreferences() {
        // This tiny atomic write must finish before an exit, even while the download worker is busy.
        runCatching { preferences.write(CommunityUpdatePreferences(autoUpdate, deferred)) }.onFailure {
            me.mycellium.skymyce.SkyMyce.logger.warn("Could not save update preferences", it)
            status = "Update preference could not be saved. Try again before restarting."
        }
    }
    fun install() = install(automatic = false)
    private fun install(automatic: Boolean) {
        val release = available ?: return
        if (busy || plan != null) return
        val approvedSelection = selection.approve()
        automaticPending = automatic
        if (automatic) automaticAttempt = release.version
        busy = true; status = if (automatic) "Auto-update: downloading a verified release…" else "Downloading the update you selected…"
        worker.submit {
            val result = runCatching { stage(release) }
            Minecraft.getInstance().execute {
                busy = false
                result.fold({ (updatePlan, updateHelper) ->
                    if (selection.generation != approvedSelection) { worker.submit { discard(updatePlan, updateHelper) }; return@fold }
                    plan = updatePlan; helper = updateHelper; notified = true
                    status = "Update verified and ready. Restart Minecraft to install ${release.version}, or exit and launch normally."
                }, { if (selection.generation == approvedSelection) {
                    automaticPending = false
                    status = "Update failed or this installation needs a manual JAR replacement. Your current version is unchanged."
                } })
            }
        }
    }
    private fun discard(updatePlan: Path, updateHelper: Path) {
        runCatching {
            val properties = Properties(); Files.newInputStream(updatePlan).use(properties::load)
            val staging = directory.toRealPath()
            val next = Path.of(properties.getProperty("next")).toAbsolutePath().normalize()
            require(updatePlan.parent.toRealPath() == staging && updateHelper.parent.toRealPath() == staging && next.parent == staging)
            Files.deleteIfExists(next); Files.deleteIfExists(updatePlan); Files.deleteIfExists(updateHelper)
        }
    }
    private fun stage(release: CommunityRelease): Pair<Path, Path> {
        val mods = FabricLoader.getInstance().gameDir.resolve("mods").toRealPath()
        val paths = FabricLoader.getInstance().getModContainer("skymyce").orElseThrow().origin.paths
        require(paths.size == 1)
        val target = paths.single().toRealPath()
        require(target.parent == mods && target.fileName.toString().endsWith(".jar"))
        Files.createDirectories(directory)
        val staging = directory.toRealPath(); require(staging.parent == mods.parent)
        val next = staging.resolve("Sky-Myce-${release.version}-${communityNonce().take(8)}.jar")
        val response = http.send(HttpRequest.newBuilder(endpoint("/community/artifact?version=${release.version}")).timeout(Duration.ofMinutes(3)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        var downloaded = false
        try {
            require(response.statusCode() == 200)
            writeCommunityArtifactBody(response.body(), next, release.size, Duration.ofMinutes(3))
            downloaded = true
            require(UpdateInstaller.sha256(next) == release.sha256)
            ZipFile(next.toFile()).use { jar ->
                val entry = requireNotNull(jar.getEntry("fabric.mod.json")); require(entry.size in 1..65536)
                val data = jar.getInputStream(entry).reader().use { JsonParser.parseReader(it).asJsonObject }
                require(data["id"].asString == "skymyce" && data["version"].asString == release.version && data.getAsJsonObject("depends")?.has("skymyce-loader") != true)
            }
            val installer = staging.resolve("update-helper.jar")
            UpdateInstaller.writeHelper(installer)
            val updatePlan = staging.resolve("update-plan.properties")
            val properties = Properties().apply {
                setProperty("parent", ProcessHandle.current().pid().toString()); setProperty("mods", mods.toString()); setProperty("staging", staging.toString())
                setProperty("parentStarted", ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli().toString())
                setProperty("target", target.toString()); setProperty("next", next.toString())
                setProperty("backup", staging.resolve("${target.fileName}.${System.currentTimeMillis()}.backup").toString())
                setProperty("oldHash", UpdateInstaller.sha256(target)); setProperty("newHash", release.sha256); setProperty("size", release.size.toString())
            }
            Files.newOutputStream(updatePlan).use { properties.store(it, "Explicitly approved Sky-Myce update; applied after Minecraft exits") }
            return updatePlan to installer
        } catch (error: Exception) { if (downloaded) Files.deleteIfExists(next); throw error }
        finally { response.body().close() }
    }
}
