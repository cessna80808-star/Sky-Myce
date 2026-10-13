package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

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
    private val removed = mutableSetOf<String>()
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
                val edited = pending.isNotEmpty() || removed.isNotEmpty()
                saved = decoded
                removed.forEach { saved.remove(it) }; removed.clear()
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
        if (key in removed) return null
        return parseSavedCameraView(pending[key] ?: saved.get(key))
    }

    fun set(stage: String, view: SavedCameraView, context: String? = context()): Boolean {
        val key = key(stage, context) ?: return false
        if (!view.valid()) return false
        val encoded = encode(view)
        removed.remove(key)
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

    fun clearCurrent() {
        for (stage in listOf("purple", "yellow")) key(stage, context())?.let { saved.remove(it); pending.remove(it); if (!loaded) removed.add(it) }
        revision++
        if (loaded) persist()
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
