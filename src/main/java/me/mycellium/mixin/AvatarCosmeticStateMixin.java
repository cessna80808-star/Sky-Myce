package me.mycellium.mixin;

import me.mycellium.skymyce.features.social.CosmeticProfile;
import me.mycellium.skymyce.features.social.CosmeticAvatarState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class AvatarCosmeticStateMixin implements CosmeticAvatarState {
    @Unique private CosmeticProfile skymyce$cosmetics;
    @Unique private boolean skymyce$preview;
    public CosmeticProfile skymyce$getCosmetics() { return skymyce$cosmetics; }
    public void skymyce$setCosmetics(CosmeticProfile profile) { skymyce$cosmetics = profile; }
    public boolean skymyce$isPreview() { return skymyce$preview; }
    public void skymyce$setPreview(boolean preview) { skymyce$preview = preview; }
}
