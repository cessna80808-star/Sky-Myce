package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Path

internal data class CommunityUpdatePreferences(val autoUpdate: Boolean = false, val deferredVersion: String = "")

/** Old installations retain explicit Update Now behavior until the user turns this on. */
internal class CommunityUpdatePreferenceStore(path: Path) {
    private val file = AtomicJsonFile(path, 4096)
    fun read(): CommunityUpdatePreferences {
        val json = file.read()?.takeIf { it.isJsonObject }?.asJsonObject ?: return CommunityUpdatePreferences()
        return CommunityUpdatePreferences(
            json["autoUpdate"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false,
            json["deferredVersion"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.take(128) ?: "",
        )
    }
    fun write(value: CommunityUpdatePreferences) {
        val json = file.read()?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        json.addProperty("autoUpdate", value.autoUpdate)
        json.addProperty("deferredVersion", value.deferredVersion)
        file.write(json)
    }
}
