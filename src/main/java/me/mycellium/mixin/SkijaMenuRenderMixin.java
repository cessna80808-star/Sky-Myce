package me.mycellium.mixin;

import me.mycellium.skymyce.ui.SkijaMenuTexture;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class SkijaMenuRenderMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void skyMyce$paintSkijaMenus(CallbackInfo info) {
        SkijaMenuTexture.renderPending();
    }
}
