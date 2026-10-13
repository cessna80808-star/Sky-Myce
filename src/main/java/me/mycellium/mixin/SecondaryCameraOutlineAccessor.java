package me.mycellium.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OutlineBufferSource.class)
public interface SecondaryCameraOutlineAccessor {
    @Accessor("outlineBufferSource") MultiBufferSource.BufferSource skyMyce$buffer();
}
