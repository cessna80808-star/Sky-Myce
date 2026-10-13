package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SecondaryCameraGameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void skyMyce$beginCameraFrame(DeltaTracker ticks, boolean renderLevel, CallbackInfo info) {
        SecondaryWorldCamera.beginFrame();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void skyMyce$drawSecondaryWorld(DeltaTracker ticks, CallbackInfo info) {
        SecondaryWorldCamera.render(ticks);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void skyMyce$cameraWorldChanged(ClientLevel level, CallbackInfo info) {
        SecondaryWorldCamera.stop();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void skyMyce$closeCamera(CallbackInfo info) {
        SecondaryWorldCamera.stop();
    }
}
