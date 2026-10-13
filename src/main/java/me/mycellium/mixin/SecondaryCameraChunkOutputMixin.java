package me.mycellium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSectionLayerGroup.class)
public abstract class SecondaryCameraChunkOutputMixin {
    @Inject(method = "outputTarget", at = @At("HEAD"), cancellable = true)
    private void skyMyce$secondaryOutput(CallbackInfoReturnable<RenderTarget> result) {
        RenderTarget target = SecondaryWorldCamera.outputTarget();
        if (target != null) result.setReturnValue(target);
    }
}
