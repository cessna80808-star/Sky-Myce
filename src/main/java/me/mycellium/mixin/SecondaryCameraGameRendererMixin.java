package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class SecondaryCameraGameRendererMixin {
    @ModifyReturnValue(method = "sodium$getProjectionMatrix", at = @At("RETURN"), require = 0, remap = false)
    private Matrix4fc skyMyce$secondaryTerrainProjection(Matrix4fc original) {
        // Sodium caches the main projection separately from RenderSystem's active GPU projection.
        return SecondaryWorldCamera.projectionMatrix(original);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void skyMyce$beginCameraFrame(DeltaTracker ticks, boolean renderLevel, CallbackInfo info) {
        SecondaryWorldCamera.beginFrame();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void skyMyce$drawSecondaryWorld(DeltaTracker ticks, CallbackInfo info) {
        SecondaryWorldCamera.render(ticks);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void skyMyce$cameraWorldChanged(ClientLevel level, CallbackInfo info) {
        SecondaryWorldCamera.stopAll();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void skyMyce$closeCamera(CallbackInfo info) {
        SecondaryWorldCamera.stopAll();
    }
}
