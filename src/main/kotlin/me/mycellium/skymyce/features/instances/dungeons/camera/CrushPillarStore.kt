package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

internal fun encodeCrushPillar(value: CrushPillarCalibration) = JsonObject().apply {
    addProperty("stage", value.stage.name); addProperty("environment", value.environment)
    addProperty("minX", value.minX); addProperty("maxX", value.maxX); addProperty("minZ", value.minZ); addProperty("maxZ", value.maxZ)
    addProperty("bottom", value.bottom); addProperty("top", value.top); addProperty("blockId", value.blockId)
    add("footprint", com.google.gson.JsonArray().apply { value.footprint.forEach { cell ->
        add(com.google.gson.JsonArray().apply { add(cell.x); add(cell.z) })
    } })
}

internal fun parseCrushPillar(element: JsonElement?): CrushPillarCalibration? = runCatching {
    val row = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    require(row.keySet() == setOf("stage", "environment", "minX", "maxX", "minZ", "maxZ", "bottom", "top", "blockId", "footprint"))
    fun string(key: String) = row.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
    fun integer(key: String): Int = row.get(key).also {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("-?[0-9]{1,9}")))
    }.asInt
    val footprint = row.get("footprint").also { require(it.isJsonArray && it.asJsonArray.size() in 4..81) }.asJsonArray.map {
        require(it.isJsonArray && it.asJsonArray.size() == 2)
        val pair = it.asJsonArray.map { coordinate ->
            require(coordinate.isJsonPrimitive && coordinate.asJsonPrimitive.isNumber && coordinate.asString.matches(Regex("-?[0-9]{1,9}")))
            coordinate.asInt
        }
        CrushFootprintCell(pair[0], pair[1])
    }
    CrushPillarCalibration(CrushStage.valueOf(string("stage")), string("environment"), integer("minX"), integer("maxX"),
        integer("minZ"), integer("maxZ"), integer("bottom"), integer("top"), string("blockId"), footprint).takeIf { it.valid() }
}.getOrNull()

/** Saved geometry is revalidated in each loaded encounter; a stored row by itself never activates the camera. */
object CrushPillarStore {
    private val saved = linkedMapOf<String, CrushPillarCalibration>()
    private val pending = linkedMapOf<String, CrushPillarCalibration>()
    private var loaded = false
    private var initialized = false
    private lateinit var writer: StateWriter<JsonObject>
    var revision = 0L
        private set

    fun initialize() {
        if (initialized) return
        initialized = true
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("crush-camera-pillars.json"), 32 * 1024)
        writer = StateWriter({ file.write(it) }, { action -> Scheduling.schedule(100.milliseconds) { action() } }, {
            SkyMyce.logger.warn("Could not save crush pillar calibration; previous file retained")
        })
        Scheduling.schedule(0.milliseconds) {
            val decoded = runCatching {
                val source = file.read()?.asJsonObject
                val version = source?.get("version")
                if (!isCameraStorageVersion(version)) emptyList()
                else source?.get("pillars")?.takeIf { it.isJsonArray }?.asJsonArray?.take(32)?.mapNotNull(::parseCrushPillar).orEmpty()
            }.getOrElse { SkyMyce.logger.warn("Could not load crush pillar calibration; automatic mode remains unavailable"); emptyList() }
            MC.instance.execute {
                decoded.forEach { saved[key(it.environment, it.stage)] = it }
                val edited = pending.isNotEmpty()
                saved.putAll(pending); pending.clear(); loaded = true; revision++
                if (edited) persist()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Myce-Crush-Pillar-Save"))
    }

    fun get(environment: String?, stage: CrushStage): CrushPillarCalibration? = environment?.let {
        pending[key(it, stage)] ?: saved[key(it, stage)]
    }?.takeIf { it.valid() }

    fun put(value: CrushPillarCalibration): Boolean {
        if (!value.valid()) return false
        if (loaded) { saved[key(value.environment, value.stage)] = value; persist() }
        else pending[key(value.environment, value.stage)] = value
        revision++
        return true
    }

    private fun key(environment: String, stage: CrushStage) = "$environment|${stage.name}"
    private fun persist() {
        while (saved.size > 32) saved.remove(saved.keys.first())
        writer.submit(JsonObject().apply {
            addProperty("version", 1)
            add("pillars", com.google.gson.JsonArray().apply { saved.values.forEach { add(encodeCrushPillar(it)) } })
        })
    }
}
