package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import me.mycellium.mixin.SecondaryCameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

/** A separate camera with its own projection, frustum and environment probe. */
final class FixedWorldCamera extends Camera {
    void configure(ClientLevel level, SecondaryWorldCamera.View view, int width, int height) {
        setLevel(level);
        setEntity(Minecraft.getInstance().player);
        setPosition(view.x(), view.y(), view.z());
        setRotation(view.yaw(), view.pitch());
        SecondaryCameraAccessor fields = (SecondaryCameraAccessor) (Object) this;
        float far = view.renderDistance() * 16f;
        fields.skyMyce$projection().setupPerspective(.05f, far, view.fov(), width, height);
        fields.skyMyce$initialized(true);
        // A current-eye capture would otherwise draw the inside of the local avatar's head.
        // Detached views outside the avatar still show it normally; the main camera is untouched.
        fields.skyMyce$detached(!Minecraft.getInstance().player.getBoundingBox().inflate(.1).contains(position()));
        fields.skyMyce$fov(view.fov());
        fields.skyMyce$hudFov(view.fov());
        fields.skyMyce$depthFar(far);
        Frustum frustum = new Frustum(getViewRotationMatrix(new Matrix4f()),
            CameraMath.projection(view.fov(), width, height, far, RenderSystem.getDevice().isZZeroToOne()));
        frustum.prepare(view.x(), view.y(), view.z());
        fields.skyMyce$frustum(frustum);
        attributeProbe().tick(level, position());
    }
}
