package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.minecraft.network.chat.Component

/** Home choices reference the existing tool inventory and never duplicate a tool's controls. */
internal class MenuHomeShortcutsScreen : MenuScreen(Component.literal("Customize Home")) {
    override val menuParent get() = MenuHomeScreen()

    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(14)); root.gap(9)
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Customize Home", MenuTheme.TEXT, 16f).horizontalSizing(Sizing.expand()))
            child(MenuButton(Component.literal("Back")) { onClose() }.apply { sizing(Sizing.fixed(48), Sizing.fixed(24)) })
        })
        root.child(label("Choose the six tools that Home opens. Choosing a tool already on Home swaps its slots.", MenuTheme.MUTED))
        val choices = MenuDirectory.catalog.features.filter(::canUseMenuHomeTool)
        val slots = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(9); padding(Insets.right(4)) }
        MenuShortcuts.homeTools.forEachIndexed { index, id ->
            val feature = checkNotNull(MenuDirectory.catalog.findFeature(id))
            slots.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(5); padding(Insets.of(10)); surface(MenuTheme.panel(true))
                child(label("Tool ${index + 1} · ${feature.category.title}", MenuTheme.ACCENT, 9f))
                child(MenuButton(Component.literal(feature.title)) { anchor ->
                    openMenuDropdown(anchor) { dropdown -> choices.forEach { choice ->
                        dropdown.button(Component.literal(choice.title)) {
                            if (MenuShortcuts.replaceHomeTool(index, choice.id)) rebuildMenu()
                        }
                    } }
                }.apply { sizing(Sizing.fill(), Sizing.fixed(26)); tooltip(Component.literal("Choose the tool for Home slot ${index + 1}")) })
                child(label(feature.description, MenuTheme.MUTED, 9f))
            })
        }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), slots))
        root.child(MenuButton(Component.literal("Reset defaults")) {
            if (MenuShortcuts.resetHomeTools()) rebuildMenu()
        }.apply { sizing(Sizing.fixed(112), Sizing.fixed(24)) })
    }

    private fun label(text: String, color: Int, size: Float = 10f) = MenuLabel(Component.literal(text)).apply {
        color(Color.ofRgb(color)); fontSize = size; horizontalSizing(Sizing.fill())
    }
}
