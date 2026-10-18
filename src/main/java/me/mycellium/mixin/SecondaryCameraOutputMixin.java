package me.mycellium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OutputTarget.class)
public abstract class SecondaryCameraOutputMixin {
    @ModifyReturnValue(method = "getRenderTarget", at = @At("RETURN"))
    private RenderTarget skyMyce$secondaryOutput(RenderTarget original) {
        RenderTarget target = SecondaryWorldCamera.outputTarget();
        return target != null ? target : original;
    }
}
