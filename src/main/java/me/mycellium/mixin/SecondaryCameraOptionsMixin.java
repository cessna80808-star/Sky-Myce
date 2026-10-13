package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class SecondaryCameraOptionsMixin {
    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void skyMyce$secondaryDistance(CallbackInfoReturnable<Integer> result) {
        // Sodium replaces terrain culling, but both renderers read this setting.
        result.setReturnValue(SecondaryWorldCamera.effectiveRenderDistance(result.getReturnValue()));
    }
}
