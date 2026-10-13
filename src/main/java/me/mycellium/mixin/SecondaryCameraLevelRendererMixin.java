package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryLevelRenderer;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.CameraMath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class SecondaryCameraLevelRendererMixin {
    @Redirect(method = "lambda$renderLevel$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/ARGB;colorFromFloat(FFFF)I"))
    private int skyMyce$opaqueSecondaryClear(float alpha, float red, float green, float blue) {
        int vanilla = ARGB.colorFromFloat(alpha, red, green, blue);
        // Vanilla clears a main world frame with alpha zero. A GUI-composited second frame needs its own backdrop.
        return (Object) this instanceof SecondaryLevelRenderer ? CameraMath.opaqueColor(vanilla) : vanilla;
    }

    @Redirect(method = {"allChanged", "cullTerrain", "extractLevel", "extractVisibleEntities"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int skyMyce$boundedDistance(Options options) {
        return (Object) this instanceof SecondaryLevelRenderer secondary ? secondary.distance() : options.getEffectiveRenderDistance();
    }

    @Redirect(method = "allChanged", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"))
    private Camera skyMyce$independentOrigin(GameRenderer renderer) {
        return (Object) this instanceof SecondaryLevelRenderer secondary ? secondary.camera() : renderer.getMainCamera();
    }

    @Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setViewScale(D)V"))
    private void skyMyce$preserveEntityDistance(double scale) {
        if (!((Object) this instanceof SecondaryLevelRenderer)) Entity.setViewScale(scale);
    }

    @Redirect(method = "extractVisibleEntities", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/entity/Entity;tickCount:I", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int skyMyce$preserveSpawnInterpolation(Entity entity) {
        // Vanilla initializes old-position fields here. A second view must only read the entity.
        return (Object) this instanceof SecondaryLevelRenderer ? Math.max(1, entity.tickCount) : entity.tickCount;
    }

    @Inject(method = "getTransparencyChain", at = @At("HEAD"), cancellable = true)
    private void skyMyce$independentTransparency(CallbackInfoReturnable<PostChain> result) {
        // The vanilla transparency chain depends on singleton main-view intermediates.
        // Depth-tested vanilla translucent draws are retained in this compact separate target.
        if ((Object) this instanceof SecondaryLevelRenderer) result.setReturnValue(null);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER))
    private void skyMyce$recordModelViewPush(CallbackInfo info) {
        if ((Object) this instanceof SecondaryLevelRenderer secondary) secondary.modelViewPushed(true);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER))
    private void skyMyce$recordModelViewPop(CallbackInfo info) {
        if ((Object) this instanceof SecondaryLevelRenderer secondary) secondary.modelViewPushed(false);
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("TAIL"))
    private void skyMyce$mirrorSectionChange(int x, int y, int z, boolean playerChanged, CallbackInfo info) {
        if ((Object) this == Minecraft.getInstance().levelRenderer) SecondaryWorldCamera.sectionChanged(x, y, z);
    }

    @Inject(method = "onChunkReadyToRender", at = @At("TAIL"))
    private void skyMyce$mirrorLoadedChunk(ChunkPos pos, CallbackInfo info) {
        if ((Object) this == Minecraft.getInstance().levelRenderer) SecondaryWorldCamera.chunkReady(pos);
    }

    @Inject(method = "onSectionBecomingNonEmpty", at = @At("TAIL"))
    private void skyMyce$mirrorNonEmptySection(long section, CallbackInfo info) {
        if ((Object) this == Minecraft.getInstance().levelRenderer) SecondaryWorldCamera.sectionNonEmpty(section);
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void skyMyce$cameraResourcesChanged(ResourceManager resources, CallbackInfo info) {
        if ((Object) this == Minecraft.getInstance().levelRenderer) SecondaryWorldCamera.stop();
    }
}
