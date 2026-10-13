package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonParser
import me.mycellium.skymyce.hud.widget.hudEditorResizeAt
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files

fun main() {
    val valid = """{"x":162.25,"y":110.62,"z":-1,"yaw":-180,"pitch":32.5}"""
    val view = parseSavedCameraView(JsonParser.parseString(valid))!!
    check(isCameraStorageVersion(JsonParser.parseString("1")))
    for (version in listOf("null", "\"1\"", "1.5", "1e100", "4294967297", "[]"))
        check(!isCameraStorageVersion(JsonParser.parseString(version)))
    check(view == SavedCameraView(162.25, 110.62, -1.0, -180f, 32.5f))
    for (bad in listOf("null", "[]", "{}", valid.replace("162.25", "\"162.25\""), valid.replace("110.62", "4096"),
        valid.replace("-180", "181"), valid.replace("32.5", "91"), valid.replace("162.25", "3e100"))) {
        check(parseSavedCameraView(JsonParser.parseString(bad)) == null)
    }
    check(normalizedCameraYaw(540.0) == -180f)
    check(normalizedCameraYaw(-720.0) == 0f)
    check(cameraWindowSize(160, 90, 16.0 / 9.0) == (160 to 90))
    check(cameraWindowSize(96, 90, 16.0 / 9.0) == (114 to 64))
    check(cameraWindowSize(160, 90, 4.0 / 3.0) == (160 to 120))
    check(cameraWindowSize(220, 180, null) == (220 to 180))
    check(cameraWindowSize(Int.MAX_VALUE, Int.MIN_VALUE, null) == (400 to 64))
    check(cameraWindowSize(200, 100, Double.NaN) == (200 to 100))
    // Actual scaled HUD editor hit bounds: only the lower-right handle resizes.
    check(hudEditorResizeAt(257.0, 151.0, 10, 8, 250, 150))
    check(!hudEditorResizeAt(40.0, 20.0, 10, 8, 250, 150))
    check(!hudEditorResizeAt(260.0, 158.0, 10, 8, 250, 150))
    check(!hudEditorResizeAt(Double.NaN, 150.0, 10, 8, 250, 150))
    val directory = Files.createTempDirectory("sky-myce-camera-view-test")
    val path = directory.resolve("view.json")
    try {
        val file = AtomicJsonFile(path)
        file.write(JsonParser.parseString(valid))
        check(parseSavedCameraView(AtomicJsonFile(path).read()) == view)
        Files.writeString(path, "{malformed")
        check(AtomicJsonFile(path).read() == null)
    } finally {
        Files.deleteIfExists(path); Files.deleteIfExists(directory)
    }
    println("Camera viewpoint checks passed: strict persisted views, aspect bounds and scaled editor resize handle")
}
