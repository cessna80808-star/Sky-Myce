package me.mycellium.skymyce.features.instances.dungeons.camera

import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.HealerCrushCameraConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component

object HealerCrushCameraHud : SkyMyceModule() {
    private var manualStage: String? = null
    private var manualLevel: ClientLevel? = null
    private var previewExpires = 0L
    private val liveViewport = CrushCameraViewport(false)
    private val editorViewport = CrushCameraViewport(true)
    val previewing get() = manualStage != null
    val widget = Widget("healer_crush_camera", "Healer Crush Camera", 630, 8, anchor = Anchor.TOP_RIGHT,
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
            if (activeStage() == null && client.screen !is WidgetEditorScreen || !widget.visible && client.screen !is WidgetEditorScreen) SecondaryWorldCamera.stop()
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
            if (header > 0) graphics.text(MC.font, "${stage.replaceFirstChar { it.uppercase() }} · ${if (previewing || editor) "preview" else "Healer crush"}", x() + 4, y() + 3, -0x1000000 or tint, false)
            val saved = CameraViewpoints.get(stage)
            if (saved == null || MC.instance.level == null) {
                graphics.text(MC.font, "Set a viewpoint: /sm crush-camera", x() + 4, y() + header + 8, -0x1000000 or tint, false)
                return
            }
            val view = SecondaryWorldCamera.View(saved.x + Settings.offsetX, saved.y + Settings.offsetY, saved.z + Settings.offsetZ,
                normalizedCameraYaw(saved.yaw + Settings.yawOffset), (saved.pitch + Settings.pitchOffset).coerceIn(-90.0, 90.0).toFloat(),
                Settings.fieldOfView.toFloat(), Settings.renderDistance, Settings.framesPerSecond)
            SecondaryWorldCamera.extract(graphics, view, x() + 4, y() + header + 4, width() - 8, height() - header - 8, Settings.opacity / 100f)
            if (SecondaryWorldCamera.status() != SecondaryWorldCamera.Status.LIVE)
                graphics.text(MC.font, SecondaryWorldCamera.status().name.replace('_', ' ').lowercase(), x() + 5, y() + header + 6, -1, false)
        }
    }
}
