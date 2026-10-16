package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapMenu;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class LeapMenuMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"))
    private void skyMyce$cancelChoiceOnScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
        var client = Minecraft.getInstance();
        if (window == client.getWindow().handle() && client.screen == null && (horizontal != 0 || vertical != 0)) LeapMenu.cancelEarlyChoice();
    }

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
    private void skyMyce$rememberManualUse(InputConstants.Key key, Operation<Void> original) {
        LeapMenu.manualInput(key);
        original.call(key);
    }
}
