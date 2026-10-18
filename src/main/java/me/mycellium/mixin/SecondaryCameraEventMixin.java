package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import net.fabricmc.fabric.api.event.Event;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Event.class, remap = false)
public abstract class SecondaryCameraEventMixin {
    @ModifyReturnValue(method = "invoker", at = @At("RETURN"))
    private Object skyMyce$secondaryWorldEvents(Object original) {
        return SecondaryWorldCamera.eventInvoker(original);
    }
}
