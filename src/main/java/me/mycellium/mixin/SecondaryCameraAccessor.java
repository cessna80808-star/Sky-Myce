package me.mycellium.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access is used exclusively on Sky-Myce's separate Camera instance. */
@Mixin(Camera.class)
public interface SecondaryCameraAccessor {
    @Accessor("projection") Projection skyMyce$projection();
    @Accessor("initialized") void skyMyce$initialized(boolean value);
    @Accessor("detached") void skyMyce$detached(boolean value);
    @Accessor("cullFrustum") void skyMyce$frustum(Frustum value);
    @Accessor("fov") void skyMyce$fov(float value);
    @Accessor("hudFov") void skyMyce$hudFov(float value);
    @Accessor("depthFar") void skyMyce$depthFar(float value);
}
