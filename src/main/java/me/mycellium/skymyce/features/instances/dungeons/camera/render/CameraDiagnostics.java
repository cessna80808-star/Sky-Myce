package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Counts CPU extraction/submission only. A completed submission does not prove a visible GPU pixel. */
public final class CameraDiagnostics {
    public record Snapshot(int loadedEntities, int visibleEntities, int extractedEntities, int submittedEntities,
                           int loadedBosses, int visibleBosses, int extractedBosses, int submittedBosses,
                           int terrainSections, int targetWidth, int targetHeight, List<String> bosses) { }

    private int loaded, visible, extracted, submitted, loadedBosses, visibleBosses, extractedBosses, submittedBosses;
    private int terrainSections, width, height;
    private final IdentityHashMap<EntityRenderState, Boolean> states = new IdentityHashMap<>();
    private final List<String> bosses = new ArrayList<>();
    /** Boss detail strings are diagnostics only: rebuilt once a second, not on every secondary frame. */
    private long nextDetailAt;
    private boolean detailFrame;

    void begin() {
        loaded = visible = extracted = submitted = loadedBosses = visibleBosses = extractedBosses = submittedBosses = 0;
        terrainSections = width = height = 0;
        states.clear();
        long now = System.nanoTime();
        detailFrame = now >= nextDetailAt;
        if (detailFrame) { bosses.clear(); nextDetailAt = now + 1_000_000_000L; }
    }

    void candidate(boolean visible, boolean boss, java.util.function.Supplier<String> detail) {
        loaded++;
        if (visible) this.visible++;
        if (boss) {
            loadedBosses++;
            if (visible) visibleBosses++;
            if (detailFrame && bosses.size() < 8) bosses.add(detail.get());
        }
    }

    void extracted(EntityRenderState state, boolean boss) {
        states.put(state, boss);
        extracted++;
        if (boss) extractedBosses++;
    }

    void submitted(EntityRenderState state) {
        Boolean boss = states.remove(state);
        if (boss == null) return;
        submitted++;
        if (boss) submittedBosses++;
    }

    SecondaryWorldCamera.Status completed(int sections, int width, int height) {
        this.terrainSections = sections;
        this.width = width;
        this.height = height;
        if (extracted < visible || extractedBosses < visibleBosses) return SecondaryWorldCamera.Status.ENTITY_EXTRACTION_FAILED;
        if (submitted < extracted || submittedBosses < extractedBosses) return SecondaryWorldCamera.Status.ENTITY_SUBMISSION_FAILED;
        if (sections == 0) return SecondaryWorldCamera.Status.PREPARING;
        return submitted == 0 ? SecondaryWorldCamera.Status.TERRAIN_ONLY : SecondaryWorldCamera.Status.LIVE;
    }

    public Snapshot snapshot() {
        return new Snapshot(loaded, visible, extracted, submitted, loadedBosses, visibleBosses, extractedBosses,
            submittedBosses, terrainSections, width, height, List.copyOf(bosses));
    }
}
