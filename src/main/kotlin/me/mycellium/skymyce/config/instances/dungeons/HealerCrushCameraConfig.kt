package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

/** F7 and M7 share one presentation; captured viewpoints are stored separately. */
object HealerCrushCameraConfig : CategoryKt("Healer Crush Camera") {
    var enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show a fixed secondary view during the Healer purple-to-yellow Storm crush sequence. Requires calibrated dungeon viewpoints.")
    }
    var width by int(160) {
        name = TranslatableValue.literal("Window width")
        description = TranslatableValue.literal("HUD width in Minecraft GUI pixels. Drag the lower-right corner in the HUD editor to resize.")
        range = 96..400; slider = true
    }
    var height by int(90) {
        name = TranslatableValue.literal("Window height")
        description = TranslatableValue.literal("HUD height in Minecraft GUI pixels when the aspect ratio is Free.")
        range = 64..300; slider = true
    }
    var aspect by enum(CameraAspect.WIDE) {
        name = TranslatableValue.literal("Aspect ratio")
        description = TranslatableValue.literal("16:9, 4:3, or an independently resizable window.")
    }
    var fieldOfView by double(65.0) {
        name = TranslatableValue.literal("Field of view")
        description = TranslatableValue.literal("Lower values zoom in. This does not change the player's main camera.")
        range = 20.0..110.0; slider = true
    }
    var yawOffset by double(0.0) {
        name = TranslatableValue.literal("Yaw adjustment")
        description = TranslatableValue.literal("Horizontal angle relative to the saved viewpoint.")
        range = -180.0..180.0; slider = true
    }
    var pitchOffset by double(0.0) {
        name = TranslatableValue.literal("Pitch adjustment")
        description = TranslatableValue.literal("Vertical angle relative to the saved viewpoint.")
        range = -60.0..60.0; slider = true
    }
    var offsetX by double(0.0) {
        name = TranslatableValue.literal("World X offset")
        range = -32.0..32.0; slider = true
    }
    var offsetY by double(0.0) {
        name = TranslatableValue.literal("Height offset")
        range = -32.0..32.0; slider = true
    }
    var offsetZ by double(0.0) {
        name = TranslatableValue.literal("World Z offset")
        range = -32.0..32.0; slider = true
    }
    var opacity by int(100) {
        name = TranslatableValue.literal("View opacity")
        range = 20..100; slider = true
    }
    var backgroundOpacity by int(75) {
        name = TranslatableValue.literal("Background opacity")
        range = 0..100; slider = true
    }
    var border by boolean(true) {
        name = TranslatableValue.literal("Show border")
    }
    var stageLabel by boolean(true) {
        name = TranslatableValue.literal("Show stage label")
    }
    var framesPerSecond by int(15) {
        name = TranslatableValue.literal("Camera frame limit")
        description = TranslatableValue.literal("Limit secondary rendering work independently from the main view.")
        range = 5..30; slider = true
    }
    var renderDistance by int(3) {
        name = TranslatableValue.literal("Camera render distance")
        description = TranslatableValue.literal("Nearby loaded chunks only; the camera never requests server chunks.")
        range = 2..4; slider = true
    }
}

enum class CameraAspect(val label: String, val ratio: Double?) {
    WIDE("16:9", 16.0 / 9.0), CLASSIC("4:3", 4.0 / 3.0), FREE("Free", null)
}
