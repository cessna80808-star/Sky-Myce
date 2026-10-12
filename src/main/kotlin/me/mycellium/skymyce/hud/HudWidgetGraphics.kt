package me.mycellium.skymyce.hud

import io.wispforest.owo.mixin.ui.access.GuiGraphicsExtractorAccessor
import io.wispforest.owo.ui.core.OwoUIGraphics
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Submit to Minecraft's existing GUI state while isolating mutable matrix/clipping stacks.
 * owo's default wrapper shares both stacks and its adapter does not unwind clipping if a draw throws.
 * Current inherited clip rectangles are already transformed; push the rectangle directly rather than transform it twice.
 */
internal class HudWidgetGraphics(parent: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) :
    OwoUIGraphics(MC.instance, parent.guiRenderState, mouseX, mouseY,
        { drawer -> (parent as GuiGraphicsExtractorAccessor).`owo$setDeferredTooltip`(drawer) }) {
    init {
        pose().set(parent.pose())
        parent.scissorStack.peek()?.let { scissorStack.push(it) }
    }
}
