package me.mycellium.skymyce.features.bazaar

import me.mycellium.mixin.BazaarAssistContainerAccessor
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.economy.BazaarAssistConfig
import me.mycellium.skymyce.features.bazaar.assist.AssistScreen
import me.mycellium.skymyce.features.bazaar.assist.AssistScreenKind
import me.mycellium.skymyce.features.bazaar.assist.AssistSlot
import me.mycellium.skymyce.features.bazaar.assist.AssistView
import me.mycellium.skymyce.features.bazaar.assist.BazaarAssist
import me.mycellium.skymyce.features.bazaar.assist.assistView
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Hypixel's Bazaar menus get a "Sky-Myce Bazaar" button that closes the menu and opens the terminal; the terminal's
 * Open Bazaar button goes the other way. Menus are recognised from their title and buttons, checked a few times a
 * second rather than every frame.
 */
object BazaarMenuButton : SkyMyceModule() {
    internal var button: Button? = null
        private set
    private var screen: AbstractContainerScreen<*>? = null
    private var ticks = 0

    override fun init() {
        ScreenEvents.AFTER_INIT.register { _, opened, _, _ ->
            if (opened !is AbstractContainerScreen<*> || !BazaarAssist.onSkyBlock || !BazaarAssistConfig.menuButton) return@register
            val menu = opened as BazaarAssistContainerAccessor
            val label = Component.literal("Sky-Myce Bazaar")
            val width = MC.instance.font.width(label) + 12
            val left = menu.`skymyce$getLeftPos`()
            val top = menu.`skymyce$getTopPos`()
            // Left of the menu's top corner, where the assistant panel only goes when the right side has less room.
            val (x, y) = if (left - width - 4 >= 2) left - width - 4 to top else left to (top - 22).coerceAtLeast(2)
            val created = Button.builder(label) { openTerminal() }.bounds(x, y, width, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Open Sky-Myce's Bazaar terminal"))).build()
            created.visible = bazaarMenu(opened)
            Screens.getWidgets(opened).add(created)
            button = created; screen = opened; ticks = 0
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            val open = screen ?: return@register
            if (MC.screen !== open) { screen = null; button = null; return@register }
            // Menu contents arrive after the screen opens and change in place as the player browses.
            if (++ticks % 5 == 0) button?.visible = isEnabled() && BazaarAssistConfig.menuButton && bazaarMenu(open)
        }
    }

    private fun bazaarMenu(open: AbstractContainerScreen<*>): Boolean {
        val slots = open.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }.take(90)
            .map { AssistSlot(it.index, it.item.hoverName.string.replace(colorCode, "").trim()) }
        return assistView(AssistScreen(AssistScreenKind.CONTAINER, open.title.string.replace(colorCode, ""), slots)) !in setOf(AssistView.OTHER, AssistView.NONE)
    }

    /** Closes Hypixel's menu the normal way, as Esc would, then opens the terminal. */
    private fun openTerminal() {
        MC.instance.player?.closeContainer()
        navigateMenu(BazaarScreen())
    }

    private val colorCode = Regex("§.")
}
