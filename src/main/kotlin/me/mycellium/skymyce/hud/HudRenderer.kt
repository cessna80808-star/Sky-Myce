package me.mycellium.skymyce.hud

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import io.wispforest.owo.ui.core.CursorStyle
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.resources.Identifier

object HudRenderer : SkyMyceModule() {
    private var pressedWidget: Widget? = null
    private val interactive get() = isEnabled() && MC.screen is ChatScreen && !MC.instance.options.hideGui && MC.instance.player != null

    override fun init() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(SkyMyce.MOD_ID, "hud_renderer")
        ) { context, ticks -> render(context, ticks.getGameTimeDeltaPartialTick(false)) }

        // Chat releases the mouse without letting HUD controls intercept inventory or editor input.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is ChatScreen) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                val widget = hovered(click.x.toInt(), click.y.toInt())
                val handled = widget?.adapter?.mouseClicked(widget.localClick(click), false) == true
                pressedWidget = widget.takeIf { handled }
                !handled
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { _, click ->
                val widget = pressedWidget
                pressedWidget = null
                if (widget != null) widget.adapter?.mouseReleased(widget.localClick(click))
                widget == null
            }
            ScreenMouseEvents.allowMouseDrag(screen).register { _, click, dx, dy ->
                val widget = pressedWidget?.takeIf { interactive && it.isRenderable }
                if (widget != null) widget.adapter?.mouseDragged(widget.localClick(click), dx / widget.scale, dy / widget.scale)
                widget == null
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, horizontal, vertical ->
                val widget = hovered(mouseX.toInt(), mouseY.toInt())
                widget?.adapter?.mouseScrolled(widget.localX(mouseX), widget.localY(mouseY), horizontal, vertical) != true
            }
            ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, partialTicks ->
                hovered(mouseX, mouseY)?.drawTooltip(graphics, mouseX, mouseY, partialTicks)
            }
            ScreenEvents.remove(screen).register {
                pressedWidget = null
                WidgetManager.widgets.forEach { it.adapter?.cursorAdapter?.applyStyle(CursorStyle.NONE) }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { dispose() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { dispose() }
    }

    private fun hovered(mouseX: Int, mouseY: Int): Widget? = if (!interactive) null else
        WidgetManager.widgets.asReversed().firstOrNull { it.isRenderable && it.inBounds(mouseX, mouseY) }

    fun render(context: GuiGraphicsExtractor, partialTicks: Float) {
        if (!isEnabled() || MC.instance.options.hideGui || MC.instance.player == null || MC.screen is WidgetEditorScreen) return
        val window = MC.instance.window
        val mouseX = if (interactive) (MC.instance.mouseHandler.xpos() * window.guiScaledWidth / window.screenWidth).toInt() else -1000000
        val mouseY = if (interactive) (MC.instance.mouseHandler.ypos() * window.guiScaledHeight / window.screenHeight).toInt() else -1000000
        WidgetManager.widgets.forEach { it.render(context, mouseX, mouseY, partialTicks) }
    }

    private fun dispose() {
        pressedWidget = null
        WidgetManager.widgets.forEach { it.dispose() }
    }
}
