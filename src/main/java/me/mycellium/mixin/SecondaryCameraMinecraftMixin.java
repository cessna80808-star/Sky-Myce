package me.mycellium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.Minecraft;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class SecondaryCameraMinecraftMixin {
    @ModifyReturnValue(method = "getMainRenderTarget", at = @At("RETURN"))
    private RenderTarget skyMyce$secondaryOutput(RenderTarget original) {
        RenderTarget target = SecondaryWorldCamera.outputTarget();
        return target != null ? target : original;
    }
}
