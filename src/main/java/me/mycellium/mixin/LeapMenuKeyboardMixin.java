package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapMenu;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class LeapMenuKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void skyMyce$queueEarlyLeapKey(long window, int action, KeyEvent key, CallbackInfo callback) {
        if (window == Minecraft.getInstance().getWindow().handle() && LeapMenu.earlyKey(key.key(), action)) callback.cancel();
    }

    @WrapOperation(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
    private void skyMyce$rememberManualUse(InputConstants.Key key, Operation<Void> original, long window, int action, KeyEvent event) {
        if (action == GLFW.GLFW_PRESS) LeapMenu.manualInput(key);
        original.call(key);
    }
}
