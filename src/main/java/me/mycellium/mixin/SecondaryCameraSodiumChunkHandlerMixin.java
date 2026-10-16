package me.mycellium.mixin;

import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryChunkEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker$ChunkEventHandler", remap = false)
public interface SecondaryCameraSodiumChunkHandlerMixin extends SecondaryChunkEventHandler { }
