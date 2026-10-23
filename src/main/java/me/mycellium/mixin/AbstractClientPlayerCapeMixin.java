package me.mycellium.mixin;

import me.mycellium.skymyce.features.social.CapeTextures;
import me.mycellium.skymyce.features.social.CosmeticProfile;
import me.mycellium.skymyce.features.social.CosmeticsClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cape renderers that read the entity's skin instead of the render state (e.g. WaveyCapes) still get the Sky-Myce cape. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerCapeMixin {
    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true)
    private void skymyce$cosmeticCape(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CapeTextures.readingNative) return;
        CosmeticProfile profile = CosmeticsClient.rendering(((AbstractClientPlayer) (Object) this).getUUID());
        if (profile != null) cir.setReturnValue(CapeTextures.apply(cir.getReturnValue(), profile));
    }
}
