package me.mycellium.mixin;

import me.mycellium.skymyce.features.social.CosmeticsClient;
import me.mycellium.skymyce.features.social.CosmeticProfile;
import me.mycellium.skymyce.features.social.CosmeticAvatarState;
import me.mycellium.skymyce.config.misc.CosmeticsConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarCosmeticsMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void skymyce$visualCosmetics(Avatar avatar, AvatarRenderState state, float tickDelta, CallbackInfo ci) {
        ((CosmeticAvatarState)state).skymyce$setPreview(false);
        CosmeticProfile profile = avatar instanceof Player player ? CosmeticsClient.rendering(player.getUUID()) : null;
        ((CosmeticAvatarState)state).skymyce$setCosmetics(profile);
        if (profile != null) {
            state.skin = me.mycellium.skymyce.features.social.CapeTextures.apply(state.skin, profile);
            if (CosmeticsConfig.INSTANCE.getNames() && profile.getNameStyle() != null && state.nameTag != null)
                state.nameTag = me.mycellium.skymyce.features.social.CosmeticNames.decorated(profile.getNameStyle());
        }
        if (profile != null && CosmeticsConfig.INSTANCE.getScaling()) {
            state.eyeHeight *= profile.getScaleY();
            if (state.nameTagAttachment != null) state.nameTagAttachment = state.nameTagAttachment.multiply(profile.getScaleX(), profile.getScaleY(), profile.getScaleZ());
        }
    }

    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("TAIL"))
    private void skymyce$axes(AvatarRenderState state, PoseStack pose, CallbackInfo ci) {
        CosmeticProfile profile = ((CosmeticAvatarState)state).skymyce$getCosmetics();
        // Vanilla owns push/pop and applies this pose to the player and attached layers. No entity mutation.
        if (profile != null && (CosmeticsConfig.INSTANCE.getScaling() || ((CosmeticAvatarState)state).skymyce$isPreview())) pose.scale(profile.getScaleX(), profile.getScaleY(), profile.getScaleZ());
    }
}
