package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.HealerCrushCameraConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.Scheduling

/** Automatic activation only reads encounter events; it never sends packets or moves the player. */
object HealerCrushCamera : SkyMyceModule() {
    private val sequence = HealerCrushSequence()
    private val clockStart = System.nanoTime()
    private fun now() = (System.nanoTime() - clockStart) / 1_000_000L
    private var level: ClientLevel? = null
    @Volatile private var epoch = 0L
    private val crushMessages = StormCrushMessages()
    val stage: CrushStage? get() = sequence.stage
    val status: String get() = sequence.status
    /** True shortly before the crush view appears; see [HealerCrushSequence.warming]. */
    fun warming() = sequence.warming(now())

    override fun init() { ClientTickEvents.END_CLIENT_TICK.register { refreshContext(now()) } }
    override fun onDisable() { reset("Disabled") }

    private fun realDungeon(): Boolean {
        val address = MC.instance.currentServer?.ip?.substringBefore(':')?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return false
        return (address == "hypixel.net" || address.endsWith(".hypixel.net")) &&
            LocationAPI.isOnSkyBlock && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS
    }

    private fun refreshContext(time: Long) {
        val client = MC.instance
        if (client.level !== level) { reset("World changed"); level = client.level }
        val enabled = isEnabled() && Settings.enabled
        val context = CrushEncounterContext(epoch, enabled, DungeonAPI.dungeonFloor?.name,
            Settings.classEnabled(DungeonAPI.ownPlayer?.dungeonClass),
            client.player?.isAlive == true && DungeonAPI.ownPlayer?.dead == false,
            enabled && realDungeon() && DungeonAPI.inBoss && !DungeonAPI.completed, false)
        // Reading the saved viewpoints parses JSON every tick; only an eligible encounter uses them.
        sequence.context(if (!context.eligible) context else context.copy(viewpointsReady =
            CrushStage.entries.all { CameraViewpoints.get(it.name.lowercase(java.util.Locale.ROOT)) != null }), time)
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!isEnabled() || !Settings.enabled || LocationAPI.island != SkyBlockIsland.THE_CATACOMBS) return
        val text = event.text
        val capturedEpoch = epoch
        MC.instance.execute {
            val time = now(); refreshContext(time)
            if (epoch != capturedEpoch) return@execute
            when {
                text == STORM_START -> {
                    if (!sequence.stormActive) crushMessages.reset()
                    sequence.stormStarted(time)
                }
                text in STORM_END -> reset("Storm phase ended")
                sequence.stormActive -> crushMessages.accept(text, time)?.let { sequence.crushed(it.serial, time) }
            }
        }
    }

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun encounterChanged() { MC.instance.execute { reset("Left the encounter") } }

    private fun reset(reason: String) { epoch++; sequence.reset(reason); crushMessages.reset() }
}

enum class CrushStage { PURPLE, YELLOW }

data class CrushEncounterContext(val world: Long, val enabled: Boolean, val floor: String?, val classEnabled: Boolean,
                                 val alive: Boolean, val inBoss: Boolean, val viewpointsReady: Boolean) {
    val eligible get() = enabled && (floor == "F7" || floor == "M7") && classEnabled && alive && inBoss
}

/** Times are monotonic milliseconds from the server's Storm phase entry message. */
class HealerCrushSequence {
    var stage: CrushStage? = null
        private set
    var status = "Waiting for a selected-class F7/M7 Storm encounter"
        private set
    var stormActive = false
        private set
    private var context: CrushEncounterContext? = null
    private var phaseStarted = 0L
    private var yellowAt = 0L
    private var lastCrushSerial = 0L

    fun context(value: CrushEncounterContext, now: Long) {
        if (context?.world != value.world || !value.eligible) reset(if (!value.enabled) "Disabled" else "Outside a living selected-class F7/M7 boss encounter")
        context = value
        if (!value.eligible) return
        tick(now)
    }

    fun stormStarted(now: Long) {
        if (context?.eligible != true || stormActive) return
        stormActive = true; phaseStarted = now; yellowAt = now + 38_000L; lastCrushSerial = 0L
        tick(now)
    }

    fun crushed(serial: Long, now: Long) {
        if (!stormActive || serial <= lastCrushSerial) return
        if (lastCrushSerial == 0L && now < yellowAt) yellowAt = now + 1_000L
        lastCrushSerial = serial
        tick(now)
    }

    fun tick(now: Long) {
        if (!stormActive) { stage = null; if (context?.eligible == true) status = "Waiting for Storm phase entry"; return }
        val elapsed = now - phaseStarted
        if (elapsed < 0L) { reset("Storm clock changed; waiting for the next encounter"); return }
        if (context?.viewpointsReady != true) { stage = null; status = "Save both purple and yellow viewpoints: /sm crush-camera"; return }
        if (elapsed < 26_000L) { stage = null; status = "Waiting for 26 seconds elapsed in Storm's phase"; return }
        val yellow = now >= yellowAt
        stage = if (yellow) CrushStage.YELLOW else CrushStage.PURPLE
        status = "${if (yellow) "Yellow" else "Purple"} crush view; visible until Storm dies"
    }

    /** The last seconds before the crush view appears: time to build the camera so it does not hitch on appearing. */
    fun warming(now: Long) = stormActive && stage == null && context?.viewpointsReady == true && now - phaseStarted in WARM_FROM until 26_000L

    fun reset(reason: String = "Storm encounter ended") {
        stage = null; stormActive = false; phaseStarted = 0L; yellowAt = 0L; lastCrushSerial = 0L; status = reason
    }
}

/** Storm time at which the crush camera starts building, 10 s before it is shown. */
internal const val WARM_FROM = 16_000L

internal const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
internal val STORM_END = setOf("[BOSS] Storm: I should have known that I stood no chance.",
    "[BOSS] Goldor: Who dares trespass into my domain?")
data class StormCrushNotice(val at: Long, val serial: Long)

/** These exact normalized server lines are classified as crushes by pinned primary Noamm F7Titles source. */
class StormCrushMessages {
    private var serial = 0L
    var latest: StormCrushNotice? = null
        private set
    fun accept(text: String, at: Long): StormCrushNotice? {
        if (text !in setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!") || at < 0) return null
        if (latest?.let { at - it.at !in 501..Long.MAX_VALUE } == true) return null
        return StormCrushNotice(at, ++serial).also { latest = it }
    }
    fun reset() { latest = null }
}

/** A world-space view captured once; it never reads the moving main camera. */
data class SavedCameraView(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
    fun valid() = x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite() &&
        x in -29_999_984.0..29_999_984.0 && z in -29_999_984.0..29_999_984.0 && y in -2048.0..2048.0 &&
        yaw in -180f..180f && pitch in -90f..90f
}

internal fun normalizedCameraYaw(value: Double): Float = (((value % 360.0) + 540.0) % 360.0 - 180.0).toFloat()

internal fun isCameraStorageVersion(value: JsonElement?): Boolean =
    value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber && value.asString == "1"

internal fun parseSavedCameraView(value: JsonElement?): SavedCameraView? = runCatching {
    val row = value?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun number(key: String): Double {
        val element = row.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: error("Missing number")
        return element.asDouble.also { require(it.isFinite()) }
    }
    SavedCameraView(number("x"), number("y"), number("z"), number("yaw").toFloat(), number("pitch").toFloat()).takeIf { it.valid() }
}.getOrNull()

internal fun cameraWindowSize(width: Int, height: Int, aspect: Double?): Pair<Int, Int> {
    val locked = aspect?.takeIf { it.isFinite() && it in 1.0..2.0 }
    val w = width.coerceIn(maxOf(96, locked?.let { ceil(64 * it).toInt() } ?: 96), 400)
    val h = if (locked != null) (w / locked).roundToInt() else height
    return w to h.coerceIn(64, 300)
}

/** Local, bounded, atomic storage. Capturing in another world does not overwrite its viewpoints. */
object CameraViewpoints {
    private var saved = JsonObject()
    private var loaded = false
    private val pending = linkedMapOf<String, JsonObject>()
    private lateinit var writer: StateWriter<JsonObject>
    var revision = 0L
        private set

    fun initialize() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("crush-camera-viewpoints.json"), 32 * 1024)
        writer = StateWriter({ file.write(it) }, { action -> Scheduling.schedule(100.milliseconds) { action() } }, {
            SkyMyce.logger.warn("Could not save camera viewpoints; previous file retained")
        })
        Scheduling.schedule(0.milliseconds) {
            val source = file.read()?.takeIf { it.isJsonObject }?.asJsonObject
            val decoded = runCatching {
                if (source != null) file.preserveOriginal()
                JsonObject().apply {
                    val version = source?.get("version")
                    if (isCameraStorageVersion(version))
                        source?.get("views")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.take(32)?.forEach { (key, row) ->
                            if (validKey(key)) parseSavedCameraView(row)?.let { add(key, encode(it)) }
                        }
                }
            }.getOrElse { SkyMyce.logger.warn("Could not load camera viewpoints; no automatic view restored"); JsonObject() }
            MC.instance.execute {
                val edited = pending.isNotEmpty()
                saved = decoded
                pending.forEach { (key, row) -> saved.add(key, row) }
                pending.clear(); loaded = true; revision++
                if (edited) persist()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Camera-Save"))
    }

    fun context(): String? {
        val level = MC.instance.level ?: return null
        val server = MC.instance.currentServer?.ip ?: "singleplayer:${MC.instance.singleplayerServer?.worldData?.levelName.orEmpty()}"
        return "$server|${level.dimension().identifier()}".takeIf { it.length in 1..240 && it.none(Char::isISOControl) }
    }

    fun get(stage: String, context: String? = context()): SavedCameraView? {
        val key = key(stage, context) ?: return null
        return parseSavedCameraView(pending[key] ?: saved.get(key))
    }

    fun set(stage: String, view: SavedCameraView, context: String? = context()): Boolean {
        val key = key(stage, context) ?: return false
        if (!view.valid()) return false
        val encoded = encode(view)
        if (loaded) { saved.remove(key); saved.add(key, encoded); persist() } else {
            pending.remove(key); pending[key] = encoded
            while (pending.size > 32) pending.remove(pending.keys.first())
        }
        revision++
        return true
    }

    fun capture(stage: String): Boolean {
        val player = MC.instance.player ?: return false
        val eye = player.eyePosition
        return set(stage, SavedCameraView(eye.x, eye.y, eye.z, normalizedCameraYaw(player.yRot.toDouble()), player.xRot.coerceIn(-90f, 90f)))
    }

    private fun persist() {
        while (saved.size() > 32) saved.remove(saved.entrySet().first().key)
        writer.submit(JsonObject().apply { addProperty("version", 1); add("views", saved.deepCopy()) })
    }
    private fun key(stage: String, context: String?): String? = context?.takeIf { stage in listOf("purple", "yellow") }?.let { "$it|$stage" }?.takeIf(::validKey)
    private fun validKey(key: String) = key.length in 1..256 && key.none(Char::isISOControl)
    private fun encode(view: SavedCameraView) = JsonObject().apply {
        addProperty("x", view.x); addProperty("y", view.y); addProperty("z", view.z)
        addProperty("yaw", view.yaw); addProperty("pitch", view.pitch)
    }
}

object HealerCrushCameraHud : SkyMyceModule() {
    private var manualStage: String? = null
    private var manualLevel: ClientLevel? = null
    private var previewExpires = 0L
    private val liveViewport = CrushCameraViewport(false)
    private val editorViewport = CrushCameraViewport(true)
    val previewing get() = manualStage != null
    val widget = Widget("healer_crush_camera", "Crush Camera", 630, 8, anchor = Anchor.TOP_RIGHT,
        previewBuilder = { editorViewport.prepare() }) { if (activeStage() == null) null else liveViewport.prepare() }.apply {
        resizeHandler = { width, height ->
            val size = cameraWindowSize(width - 8, height - if (Settings.stageLabel) 20 else 8, Settings.aspect.ratio)
            Settings.width = size.first; Settings.height = size.second
        }
        resizeFinished = { SkyMyce.config.save() }
    }

    override fun init() {
        CameraViewpoints.initialize()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (manualStage != null && (client.level !== manualLevel || client.player?.isAlive != true || System.nanoTime() >= previewExpires)) stopPreview()
            val warmView = if (manualStage == null && widget.visible && HealerCrushCamera.warming()) crushView("purple") else null
            if (warmView != null) SecondaryWorldCamera.prewarm(warmView, Settings.width, Settings.height, client.window.guiScale.toDouble() * widget.scale)
            else if (activeStage() == null && client.screen !is WidgetEditorScreen || !widget.visible && client.screen !is WidgetEditorScreen) SecondaryWorldCamera.stop()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { stopPreview(); SecondaryWorldCamera.stop() } }
    }

    fun startPreview(stage: String): Boolean {
        if (stage !in listOf("purple", "yellow") || MC.instance.player?.isAlive != true) return false
        if (CameraViewpoints.get(stage) == null && !CameraViewpoints.capture(stage)) return false
        manualStage = stage; manualLevel = MC.instance.level
        previewExpires = System.nanoTime() + 300_000_000_000L
        widget.visible = true; WidgetManager.save()
        return true
    }

    fun stopPreview() { manualStage = null; manualLevel = null; previewExpires = 0L; SecondaryWorldCamera.stop() }

    /** The camera for a stage's saved viewpoint with the configured offsets; null without a saved viewpoint. */
    fun crushView(stage: String): SecondaryWorldCamera.View? {
        val saved = CameraViewpoints.get(stage) ?: return null
        return SecondaryWorldCamera.View(saved.x + Settings.offsetX, saved.y + Settings.offsetY, saved.z + Settings.offsetZ,
            normalizedCameraYaw(saved.yaw + Settings.yawOffset), (saved.pitch + Settings.pitchOffset).coerceIn(-90.0, 90.0).toFloat(),
            Settings.fieldOfView.toFloat(), Settings.renderDistance, Settings.framesPerSecond)
    }

    fun activeStage(): String? = manualStage ?: HealerCrushCamera.stage?.name?.lowercase(java.util.Locale.ROOT)

    fun resetPresentation() {
        stopPreview()
        Settings.width = 160; Settings.height = 90; Settings.aspect = me.mycellium.skymyce.config.instances.dungeons.CameraAspect.WIDE
        Settings.fieldOfView = 65.0; Settings.yawOffset = 0.0; Settings.pitchOffset = 0.0
        Settings.offsetX = 0.0; Settings.offsetY = 0.0; Settings.offsetZ = 0.0
        Settings.opacity = 100; Settings.backgroundOpacity = 75; Settings.border = true; Settings.stageLabel = true
        Settings.framesPerSecond = 15; Settings.renderDistance = 3
        widget.defaultLayout.copy().applyConfig(widget)
        SkyMyce.config.save(); WidgetManager.save()
    }

    private class CrushCameraViewport(private val editor: Boolean) : BaseUIComponent() {
        fun prepare(): CrushCameraViewport {
            val (width, height) = cameraWindowSize(Settings.width, Settings.height, Settings.aspect.ratio)
            sizing(Sizing.fixed(width + 8), Sizing.fixed(height + if (Settings.stageLabel) 20 else 8))
            return this
        }
        override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val stage = activeStage() ?: if (editor) "purple" else return
            val header = if (Settings.stageLabel) 12 else 0
            val tint = if (stage == "yellow") 0xE8BF71 else 0xC2A2F5
            graphics.fill(x(), y(), x() + width(), y() + height(), (Settings.backgroundOpacity.coerceIn(0, 100) * 255 / 100 shl 24) or 0x101018)
            if (Settings.border) {
                val color = -0x1000000 or tint
                graphics.fill(x(), y(), x() + width(), y() + 1, color)
                graphics.fill(x(), y() + height() - 1, x() + width(), y() + height(), color)
                graphics.fill(x(), y(), x() + 1, y() + height(), color)
                graphics.fill(x() + width() - 1, y(), x() + width(), y() + height(), color)
            }
            if (header > 0) graphics.text(MC.font, "${stage.replaceFirstChar { it.uppercase() }} · ${if (previewing || editor) "preview" else "Crush"}", x() + 4, y() + 3, -0x1000000 or tint, false)
            val view = crushView(stage)
            if (view == null || MC.instance.level == null) {
                graphics.text(MC.font, "Set a viewpoint: /sm crush-camera", x() + 4, y() + header + 8, -0x1000000 or tint, false)
                return
            }
            me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.CAMERA_EXTRACT) { SecondaryWorldCamera.extract(graphics, view, x() + 4, y() + header + 4, width() - 8, height() - header - 8, Settings.opacity / 100f) }
            if (SecondaryWorldCamera.status() != SecondaryWorldCamera.Status.LIVE)
                graphics.text(MC.font, SecondaryWorldCamera.status().name.replace('_', ' ').lowercase(), x() + 5, y() + header + 6, -1, false)
        }
    }
}
