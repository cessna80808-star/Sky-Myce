package me.mycellium.skymyce.config

import com.google.gson.JsonObject
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import com.teamresourceful.resourcefulconfig.common.jsonc.JsoncObject
import com.teamresourceful.resourcefulconfig.common.loader.Writer
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import net.fabricmc.loader.api.FabricLoader
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/** Keep Resourceful's schema/loader, with recoverable migration and atomic background saves. */
fun loadSkyMyceConfig(): ResourcefulConfig {
    val directory = FabricLoader.getInstance().configDir
    var original = JsonObject()
    var recoverable = true
    // Resourceful's loader removes the old .json itself, so preserve it before registering.
    for (suffix in listOf("jsonc", "json")) {
        val path = directory.resolve("SkyMyce Config.$suffix")
        if (Files.exists(path)) {
            runCatching { AtomicJsonFile(path).preserveOriginal("before-theme-v1") }.onFailure {
                recoverable = false; SkyMyce.logger.warn("Configuration backup unavailable; configuration writes paused")
            }
            if (Files.size(path) <= 1024 * 1024) runCatching { JsoncObject.parse(Files.readString(path)) }.onSuccess { original = it }
        }
    }
    val delegate = Config.register(SkyMyce.configurator)
    val file = AtomicJsonFile(directory.resolve("${delegate.id()}.jsonc"), 1024 * 1024)
    val writer = StateWriter<JsonObject>({ file.write(it) }, { work -> Scheduling.schedule(100.milliseconds) { work() } }, {
        SkyMyce.logger.warn("Could not save configuration; previous file retained")
    })
    Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Hawk-Config-Save"))
    return SavingConfig(delegate) {
        // Capture values together, then perform serialization/disk work on the scheduler.
        val known = JsoncObject.parse(Writer.save(delegate).toString())
        if (recoverable) writer.submit(mergeConfigFields(original, known))
    }
}

internal class SavingConfig(private val delegate: ResourcefulConfig, private val saveAction: () -> Unit) : ResourcefulConfig by delegate {
    // Kotlin delegation inherits these Java defaults (empty elements/version 0) unless forwarded explicitly.
    override fun elements() = delegate.elements()
    override fun version() = delegate.version()
    override fun save() = saveAction()
}

internal fun mergeConfigFields(original: JsonObject, known: JsonObject): JsonObject = original.deepCopy().apply {
    known.entrySet().forEach { (key, value) ->
        add(key, if (value.isJsonObject && get(key)?.isJsonObject == true) mergeConfigFields(getAsJsonObject(key), value.asJsonObject) else value.deepCopy())
    }
}
