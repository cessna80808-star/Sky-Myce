package me.mycellium.skymyce.features.instances.dungeons.camera.render;

/** Optional Sodium chunk callbacks, without making Sodium a required dependency. */
@FunctionalInterface
public interface SecondaryChunkEventHandler {
    void apply(int x, int z);
}
