package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import java.util.UUID;

/** Own visibility graph, section meshes, extracted states and dispatcher; no main-view reuse. */
public final class SecondaryLevelRenderer extends LevelRenderer {
    private final Camera camera;
    private final Minecraft client;
    private final EntityRenderDispatcher entities;
    private final CameraDiagnostics diagnostics = new CameraDiagnostics();
    private final LongSet readySodiumChunks = new LongOpenHashSet();
    private UUID hiddenEntity;
    private int distance;
    private boolean modelViewPushed;

    SecondaryLevelRenderer(Minecraft client, EntityRenderDispatcher entities, BlockEntityRenderDispatcher blocks,
        RenderBuffers buffers, GameRenderState state, FeatureRenderDispatcher features, Camera camera, int distance) {
        super(client, entities, blocks, buffers, state, features);
        this.client = client;
        this.entities = entities;
        this.camera = camera;
        this.distance = distance;
    }

    public Camera camera() { return camera; }
    public CameraDiagnostics diagnostics() { return diagnostics; }
    void hiddenEntity(UUID value) { hiddenEntity = value; }
    public int distance() { return distance; }
    void distance(int value) { if (distance != value) { distance = value; allChanged(); } }
    public void modelViewPushed(boolean value) { modelViewPushed = value; }
    boolean modelViewPushed() { return modelViewPushed; }

    public void synchronizeReadyChunks(LongSet ready, SecondaryChunkEventHandler added, SecondaryChunkEventHandler removed) {
        // Sodium also seeds new/reloaded managers itself; its existing-section guard makes repeated initial adds harmless.
        synchronizeReadyChunks(readySodiumChunks, ready, added, removed);
    }

    static void synchronizeReadyChunks(LongSet previous, LongSet ready, SecondaryChunkEventHandler added, SecondaryChunkEventHandler removed) {
        for (long chunk : previous) if (!ready.contains(chunk)) removed.apply((int) chunk, (int) (chunk >> 32));
        for (long chunk : ready) if (!previous.contains(chunk)) added.apply((int) chunk, (int) (chunk >> 32));
        previous.clear();
        previous.addAll(ready);
    }

    /** Vanilla visibility rules, without singleton occlusion hooks or writes to shared entity interpolation. */
    public void extractEntities(Camera view, Frustum frustum, DeltaTracker ticks, LevelRenderState output) {
        diagnostics.begin();
        var level = client.level;
        var position = view.position();
        for (Entity entity : level.entitiesForRendering()) {
            boolean inView = entities.shouldRender(entity, frustum, position.x, position.y, position.z);
            boolean sectionReady = level.isOutsideBuildHeight(entity.blockPosition().getY()) || isSectionCompiledAndVisible(entity.blockPosition());
            boolean visible = !entity.getUUID().equals(hiddenEntity) &&
                (inView || entity.hasIndirectPassenger(client.player)) && sectionReady &&
                (entity != view.entity() || view.isDetached() || entity instanceof LivingEntity living && living.isSleeping()) &&
                (!(entity instanceof LocalPlayer) || view.entity() == entity);
            boolean boss = entity instanceof WitherBoss;
            // Built only on the once-a-second diagnostics frame.
            diagnostics.candidate(visible, boss, () -> entity.getId() + " " + entity.getType() + " " + entity.getName().getString() +
                " pos=" + entity.position() + " distance=" + Math.sqrt(position.distanceToSqr(entity.position())) +
                " mainFrustum=" + client.gameRenderer.getMainCamera().getCullFrustum().isVisible(entity.getBoundingBox().inflate(.5)) +
                " secondaryVisible=" + inView + " sectionReady=" + sectionReady +
                " renderer=" + entities.getRenderer(entity).getClass().getName());
            if (!visible) continue;
            float partial = entity.tickCount == 0 ? 1f : ticks.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            var state = entities.extractEntity(entity, partial);
            output.entityRenderStates.add(state);
            diagnostics.extracted(state, boss);
        }
        output.lastEntityRenderStateCount = output.entityRenderStates.size();
    }

    @Override public void initOutline() { /* No extra full-screen outline target or player selection. */ }
    @Override protected boolean shouldShowEntityOutlines() { return false; }
}
