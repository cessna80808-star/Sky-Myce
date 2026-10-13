package me.mycellium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class SecondaryCameraMinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void skyMyce$secondaryOutput(CallbackInfoReturnable<RenderTarget> result) {
        RenderTarget target = SecondaryWorldCamera.outputTarget();
        if (target != null) result.setReturnValue(target);
    }
}
