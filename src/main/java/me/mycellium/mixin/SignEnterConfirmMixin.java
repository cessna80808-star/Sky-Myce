package me.mycellium.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enter on a matching sign does what Done does; on others it keeps moving to the next line. */
@Mixin(AbstractSignEditScreen.class)
public class SignEnterConfirmMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skymyce$enterConfirms(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (me.mycellium.skymyce.features.general.SignConfirm.INSTANCE.onKey((AbstractSignEditScreen) (Object) this, event.key())) cir.setReturnValue(true);
    }
}
