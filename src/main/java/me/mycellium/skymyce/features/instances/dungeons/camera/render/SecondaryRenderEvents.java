package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.world.phys.HitResult;

// Other mods' overlays own a single main-view lifecycle and must not run twice in one frame.
final class SecondaryRenderEvents implements LevelRenderEvents.AfterBlockOutlineExtraction, LevelRenderEvents.EndExtraction,
    LevelRenderEvents.StartMain, LevelRenderEvents.AfterOpaqueTerrain, LevelRenderEvents.CollectSubmits,
    LevelRenderEvents.AfterSolidFeatures, LevelRenderEvents.AfterTranslucentFeatures, LevelRenderEvents.BeforeBlockOutline,
    LevelRenderEvents.BeforeGizmos, LevelRenderEvents.BeforeTranslucentTerrain, LevelRenderEvents.AfterTranslucentTerrain,
    LevelRenderEvents.EndMain {
    private static final SecondaryRenderEvents NONE = new SecondaryRenderEvents();

    static Object invoker(Object original) {
        return switch (original) {
            case LevelRenderEvents.AfterBlockOutlineExtraction ignored -> NONE;
            case LevelRenderEvents.EndExtraction ignored -> NONE;
            case LevelRenderEvents.StartMain ignored -> NONE;
            case LevelRenderEvents.AfterOpaqueTerrain ignored -> NONE;
            case LevelRenderEvents.CollectSubmits ignored -> NONE;
            case LevelRenderEvents.AfterSolidFeatures ignored -> NONE;
            case LevelRenderEvents.AfterTranslucentFeatures ignored -> NONE;
            case LevelRenderEvents.BeforeBlockOutline ignored -> NONE;
            case LevelRenderEvents.BeforeGizmos ignored -> NONE;
            case LevelRenderEvents.BeforeTranslucentTerrain ignored -> NONE;
            case LevelRenderEvents.AfterTranslucentTerrain ignored -> NONE;
            case LevelRenderEvents.EndMain ignored -> NONE;
            default -> original;
        };
    }

    @Override public void afterBlockOutlineExtraction(LevelExtractionContext context, HitResult hit) { }
    @Override public void endExtraction(LevelExtractionContext context) { }
    @Override public void startMain(LevelTerrainRenderContext context) { }
    @Override public void afterOpaqueTerrain(LevelTerrainRenderContext context) { }
    @Override public void collectSubmits(LevelRenderContext context) { }
    @Override public void afterSolidFeatures(LevelRenderContext context) { }
    @Override public void afterTranslucentFeatures(LevelRenderContext context) { }
    @Override public boolean beforeBlockOutline(LevelRenderContext context, BlockOutlineRenderState outline) { return true; }
    @Override public void beforeGizmos(LevelRenderContext context) { }
    @Override public void beforeTranslucentTerrain(LevelRenderContext context) { }
    @Override public void afterTranslucentTerrain(LevelRenderContext context) { }
    @Override public void endMain(LevelRenderContext context) { }
}
