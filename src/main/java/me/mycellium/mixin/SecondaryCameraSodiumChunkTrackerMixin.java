package me.mycellium.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryChunkEventHandler;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryLevelRenderer;
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker", remap = false)
public abstract class SecondaryCameraSodiumChunkTrackerMixin {
    @Shadow @Final private LongOpenHashSet chunkReady;

    @Inject(method = "forEachEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private void skyMyce$independentChunkEvents(@Coerce Object loaded, @Coerce Object unloaded, CallbackInfo info) {
        // Sodium's normal event queues are consumed once by the main renderer.
        if (SecondaryWorldCamera.effectiveLevelRenderer(null) instanceof SecondaryLevelRenderer secondary &&
            loaded instanceof SecondaryChunkEventHandler added && unloaded instanceof SecondaryChunkEventHandler removed) {
            secondary.synchronizeReadyChunks(chunkReady, added, removed);
            info.cancel();
        }
    }
}
