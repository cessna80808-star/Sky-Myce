package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Destructive actions and browser links keep confirmation inside the custom menu. */
class MenuConfirmScreen(private val parent: Screen, private val heading: String, private val detail: String,
                        private val confirmLabel: String = "Confirm", private val confirmed: () -> Unit) : MenuScreen(Component.literal(heading)) {
    override val menuParent get() = parent
    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(24)); root.gap(16)
        root.child(MenuLabel(Component.literal(heading)).apply { fontSize = 17f; color(Color.ofRgb(MenuTheme.TEXT)); horizontalSizing(Sizing.fill()) })
        root.child(MenuLabel(Component.literal(detail)).apply { color(Color.ofRgb(MenuTheme.MUTED)); horizontalSizing(Sizing.fill()) })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).gap(8).apply {
            child(MenuButton(Component.literal(confirmLabel)) { MC.instance.setScreen(parent); confirmed() }.sizing(Sizing.fixed(90), Sizing.fixed(28)))
            child(MenuButton(Component.literal("Cancel")) { onClose() }.sizing(Sizing.fixed(70), Sizing.fixed(28)))
        })
    }
    override fun onClose() { MC.instance.setScreen(parent) }
}
