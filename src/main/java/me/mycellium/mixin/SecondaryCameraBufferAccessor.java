package me.mycellium.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

@Mixin(MultiBufferSource.BufferSource.class)
public interface SecondaryCameraBufferAccessor {
    @Accessor("sharedBuffer") ByteBufferBuilder skyMyce$shared();
    @Accessor("fixedBuffers") SequencedMap<RenderType, ByteBufferBuilder> skyMyce$fixed();
}
