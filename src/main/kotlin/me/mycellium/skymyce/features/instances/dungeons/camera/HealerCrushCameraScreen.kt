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
    private lateinit var savedLabel: MenuLabel
    private lateinit var previewButton: MenuButton
    private val viewButtons = linkedMapOf<String, MenuButton>()
    private val fields = linkedMapOf<String, MenuTextBox>()

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(7)
        root.child(label("CRUSH CAMERA", MenuTheme.ACCENT))
        status = label("", MenuTheme.SECONDARY); root.child(status)
        root.child(label("F7 / M7 · a fixed live world view. Moving or looking around leaves this camera anchored.", MenuTheme.MUTED))
        val content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), content))
        content.child(label("VIEWPOINT", MenuTheme.SECONDARY))
        viewButtons.clear()
        content.child(row().apply {
            listOf("purple" to "Purple", "yellow" to "Yellow").forEach { (stage, name) ->
                child(button(if (selected == stage) "$name ✓" else name, 49) {
                    if (selected != stage) { selected = stage; updateViewpoint() }
                }.also { viewButtons[stage] = it })
            }
        })
        val saved = CameraViewpoints.get(selected)
        savedLabel = label(savedDescription(saved), MenuTheme.MUTED); content.child(savedLabel)
        content.child(row().apply {
            child(button("Use my current view", 49) {
                if (CameraViewpoints.capture(selected)) { say("Saved $selected camera position and direction. It will stay fixed."); updateViewpoint() }
                else say("Join a world before capturing a view.")
            })
            previewButton = button("Preview $selected on HUD", 49) {
                if (HealerCrushCameraHud.startPreview(selected)) navigateMenu(null)
                else say("Join a world before starting preview.")
            }
            child(previewButton)
        })
        content.child(label("Manual preview lasts up to five minutes. It does not activate or simulate dungeon mechanics.", MenuTheme.MUTED))
        val values = viewValues(saved)
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
            if (CameraViewpoints.set(selected, view)) { say("Saved $selected viewpoint."); updateViewpoint() }
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
        content.child(label("Save both viewpoints, then choose your classes in settings. The camera opens 26 seconds after Storm's phase starts, switches to yellow one second after the first crush (or at 38 seconds if its notice is missed), and stays visible until Storm dies. Automatic mode requires a living player in F7/M7 on Hypixel.", MenuTheme.MUTED))
        root.child(button("Done", 100) { onClose() })
        updateStatus()
    }

    private fun updateViewpoint() {
        val saved = CameraViewpoints.get(selected)
        viewButtons.forEach { (stage, button) ->
            val name = if (stage == "purple") "Purple" else "Yellow"
            button.message = Component.literal(if (selected == stage) "$name ✓" else name)
        }
        savedLabel.text(Component.literal(savedDescription(saved)))
        previewButton.message = Component.literal("Preview $selected on HUD")
        viewValues(saved).forEach { (name, value) -> fields[name]?.text(value?.let(::number).orEmpty()) }
    }
    private fun savedDescription(saved: SavedCameraView?) = if (saved == null) "No $selected view saved in this world/server." else
        "Saved $selected view · X ${number(saved.x)} · Y ${number(saved.y)} · Z ${number(saved.z)}"
    private fun viewValues(saved: SavedCameraView?) = mapOf("X" to saved?.x, "Y" to saved?.y, "Z" to saved?.z,
        "Yaw" to saved?.yaw?.toDouble(), "Pitch" to saved?.pitch?.toDouble())
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
