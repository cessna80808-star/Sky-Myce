package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;

/** Own visibility graph, section meshes, extracted states and dispatcher; no main-view reuse. */
public final class SecondaryLevelRenderer extends LevelRenderer {
    private final Camera camera;
    private int distance;
    private boolean modelViewPushed;

    SecondaryLevelRenderer(Minecraft client, EntityRenderDispatcher entities, BlockEntityRenderDispatcher blocks,
        RenderBuffers buffers, GameRenderState state, FeatureRenderDispatcher features, Camera camera, int distance) {
        super(client, entities, blocks, buffers, state, features);
        this.camera = camera;
        this.distance = distance;
    }

    public Camera camera() { return camera; }
    public int distance() { return distance; }
    void distance(int value) { if (distance != value) { distance = value; allChanged(); } }
    public void modelViewPushed(boolean value) { modelViewPushed = value; }
    boolean modelViewPushed() { return modelViewPushed; }

    @Override public void initOutline() { /* No extra full-screen outline target or player selection. */ }
    @Override protected boolean shouldShowEntityOutlines() { return false; }
}
