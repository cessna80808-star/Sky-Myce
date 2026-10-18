package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.Options;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class SecondaryCameraOptionsMixin {
    @ModifyReturnValue(method = "getEffectiveRenderDistance", at = @At("RETURN"))
    private int skyMyce$secondaryDistance(int original) {
        // Sodium replaces terrain culling, but both renderers read this setting.
        return SecondaryWorldCamera.effectiveRenderDistance(original);
    }
}
