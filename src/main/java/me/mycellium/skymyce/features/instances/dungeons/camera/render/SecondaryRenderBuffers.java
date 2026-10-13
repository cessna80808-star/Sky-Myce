package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import me.mycellium.mixin.SecondaryCameraBufferAccessor;
import me.mycellium.mixin.SecondaryCameraOutlineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Independent draw buffers; async chunk jobs borrow vanilla's bounded scratch pool. */
final class SecondaryRenderBuffers extends RenderBuffers implements AutoCloseable {
    SecondaryRenderBuffers() { super(1); }

    @Override public SectionBufferBuilderPool sectionBufferPool() {
        return Minecraft.getInstance().renderBuffers().sectionBufferPool();
    }

    @Override public void close() {
        // This private pool was never lent to jobs; don't close the shared pool used by them.
        SectionBufferBuilderPack unused;
        while ((unused = super.sectionBufferPool().acquire()) != null) unused.close();
        Set<ByteBufferBuilder> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) buffers.add(fixedBufferPack().buffer(layer));
        collect(bufferSource(), buffers);
        collect(crumblingBufferSource(), buffers);
        collect(((SecondaryCameraOutlineAccessor) outlineBufferSource()).skyMyce$buffer(), buffers);
        buffers.forEach(ByteBufferBuilder::close);
    }

    private static void collect(MultiBufferSource.BufferSource source, Set<ByteBufferBuilder> buffers) {
        SecondaryCameraBufferAccessor fields = (SecondaryCameraBufferAccessor) source;
        buffers.add(fields.skyMyce$shared());
        buffers.addAll(fields.skyMyce$fixed().values());
    }
}
