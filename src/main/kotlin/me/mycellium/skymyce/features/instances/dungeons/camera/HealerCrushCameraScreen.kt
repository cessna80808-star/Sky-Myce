package me.mycellium.skymyce.features.instances.dungeons.camera

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import java.util.Locale

/** Explicit developer preview/calibration controls, separate from automatic encounter events. */
class HealerCrushCameraScreen : MenuScreen(Component.literal("Crush Camera"), MenuPage.CRUSH_CAMERA) {
    override val menuCategory get() = MenuCategory.DUNGEONS
    private var selected = "purple"
    private var feedback = "Use your current view or enter coordinates. No dungeon viewpoint is guessed."
    private lateinit var status: MenuLabel
    private lateinit var feedbackLabel: MenuLabel
    private val fields = linkedMapOf<String, MenuTextBox>()

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(7)
        root.child(label("CRUSH CAMERA", MenuTheme.ACCENT))
        status = label("", MenuTheme.SECONDARY); root.child(status)
        root.child(label("F7 / M7 · a fixed live world view. Moving or looking around leaves this camera anchored.", MenuTheme.MUTED))
        val content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), content))
        content.child(label("VIEWPOINT", MenuTheme.SECONDARY))
        content.child(row().apply {
            child(button(if (selected == "purple") "Purple ✓" else "Purple", 49) { selected = "purple"; rebuildMenu() })
            child(button(if (selected == "yellow") "Yellow ✓" else "Yellow", 49) { selected = "yellow"; rebuildMenu() })
        })
        val saved = CameraViewpoints.get(selected)
        content.child(label(if (saved == null) "No $selected view saved in this world/server." else
            "Saved $selected view · X ${number(saved.x)} · Y ${number(saved.y)} · Z ${number(saved.z)}", MenuTheme.MUTED))
        content.child(row().apply {
            child(button("Use my current view", 49) {
                if (CameraViewpoints.capture(selected)) { feedback = "Saved $selected camera position and direction. It will stay fixed."; rebuildMenu() }
                else say("Join a world before capturing a view.")
            })
            child(button("Preview $selected on HUD", 49) {
                if (HealerCrushCameraHud.startPreview(selected)) navigateMenu(null)
                else say("Join a world before starting preview.")
            })
        })
        content.child(label("Manual preview lasts up to five minutes. It does not activate or simulate dungeon mechanics.", MenuTheme.MUTED))
        val values = mapOf("X" to saved?.x, "Y" to saved?.y, "Z" to saved?.z, "Yaw" to saved?.yaw?.toDouble(), "Pitch" to saved?.pitch?.toDouble())
        fields.clear()
        content.child(row().apply {
            values.forEach { (name, value) ->
                val column = UIContainers.verticalFlow(Sizing.fill(19), Sizing.content()).apply { gap(3) }
                column.child(label(name, MenuTheme.MUTED))
                val field = MenuTextBox(Sizing.fill()).apply { setMaxLength(24); text(value?.let(::number).orEmpty()); setHint(Component.literal(name)) }
                fields[name] = field; column.child(field); child(column)
            }
        })
        content.child(button("Save entered viewpoint", 100) {
            val values = fields.mapValues { it.value.value.trim().toDoubleOrNull() }
            if (values.values.any { it == null || !it.isFinite() }) { say("Enter finite numbers for X, Y, Z, yaw and pitch."); return@button }
            val yaw = values.getValue("Yaw")!!
            val view = SavedCameraView(values.getValue("X")!!, values.getValue("Y")!!, values.getValue("Z")!!,
                normalizedCameraYaw(yaw), values.getValue("Pitch")!!.toFloat())
            if (CameraViewpoints.set(selected, view)) { feedback = "Saved $selected viewpoint."; rebuildMenu() }
            else say("Join a world and use coordinates within world bounds, Y −2048…2048 and pitch −90…90.")
        })
        feedbackLabel = label(feedback, MenuTheme.MUTED); content.child(feedbackLabel)
        content.child(label("WINDOW & CAMERA", MenuTheme.SECONDARY))
        content.child(row().apply {
            child(button("Classes, size & appearance", 49) { navigateMenu(SettingsScreen.forFeature(this@HealerCrushCameraScreen, "healer-crush-camera")) })
            child(button("Move / resize HUD", 49) { navigateMenu(WidgetEditorScreen(this@HealerCrushCameraScreen)) })
        })
        content.child(label("In the HUD editor: drag to move, drag the lower-right square to resize, scroll to scale, V to hide. Aspect ratio locks height unless Free is selected.", MenuTheme.MUTED))
        content.child(row().apply {
            child(button("Stop preview", 49) { HealerCrushCameraHud.stopPreview(); say("Manual preview stopped.") })
            child(button("Reset presentation", 49) { HealerCrushCameraHud.resetPresentation(); say("Window, orientation adjustments and appearance reset. Saved viewpoints remain.") })
        })
        content.child(label("AUTOMATIC ACTIVATION", MenuTheme.SECONDARY))
        content.child(label("Save both viewpoints, then choose your classes in settings. The camera opens 26 seconds after Storm's phase starts, switches to yellow after the first Storm crush, and stays visible until Storm dies. Automatic mode requires a living player in F7/M7 on Hypixel.", MenuTheme.MUTED))
        root.child(button("Done", 100) { onClose() })
        updateStatus()
    }

    private fun say(value: String) { feedback = value; feedbackLabel.text(Component.literal(value)) }
    private fun updateStatus() {
        status.text(Component.literal(if (HealerCrushCameraHud.previewing) "Manual HUD preview active · ${HealerCrushCameraHud.activeStage()}" else HealerCrushCamera.status))
    }
    override fun tick() { super.tick(); updateStatus() }
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }
    private fun label(text: String, color: Int) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, percent: Int, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(percent), Sizing.fixed(26)) }
    private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}
