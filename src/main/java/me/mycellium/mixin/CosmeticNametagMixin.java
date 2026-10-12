package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import me.mycellium.skymyce.config.misc.CosmeticsConfig;
import me.mycellium.skymyce.features.social.CosmeticAvatarState;
import me.mycellium.skymyce.features.social.CosmeticProfile;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public class CosmeticNametagMixin {
    @WrapOperation(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZIDLnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void skymyce$nameSize(SubmitNodeCollector collector, PoseStack pose, Vec3 attachment, int offset, Component name, boolean seeThrough,
                                 int light, double distance, CameraRenderState camera, Operation<Void> original, @Local(argsOnly = true) EntityRenderState state) {
        CosmeticProfile profile = state instanceof CosmeticAvatarState cosmetic ? cosmetic.skymyce$getCosmetics() : null;
        if (profile == null || !CosmeticsConfig.INSTANCE.getNames() || attachment == null) { original.call(collector, pose, attachment, offset, name, seeThrough, light, distance, camera); return; }
        float scale = profile.getNametagScale();
        pose.pushPose();
        try {
            // Scale glyphs around their attachment, preserving body size and the world-space name anchor.
            pose.translate(attachment.x, attachment.y, attachment.z); pose.scale(scale, scale, scale); pose.translate(-attachment.x, -attachment.y, -attachment.z);
            original.call(collector, pose, attachment, offset, name, seeThrough, light, distance, camera);
        } finally { pose.popPose(); }
    }
}
