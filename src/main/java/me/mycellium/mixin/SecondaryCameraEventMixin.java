package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.fabricmc.fabric.api.event.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Event.class, remap = false)
public abstract class SecondaryCameraEventMixin {
    @Inject(method = "invoker", at = @At("RETURN"), cancellable = true)
    private void skyMyce$secondaryWorldEvents(CallbackInfoReturnable<Object> result) {
        result.setReturnValue(SecondaryWorldCamera.eventInvoker(result.getReturnValue()));
    }
}
