package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.mycellium.mixin.SecondaryBlockEntityDispatcherAccessor;
import me.mycellium.mixin.SecondaryCameraGuiInvoker;
import me.mycellium.mixin.SecondaryEntityDispatcherAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A live, client-local second world view. Extraction uses independent mutable renderer state;
 * the world pass runs before the main world, after its lightmap is ready. The player's Camera is never set,
 * repositioned, rotated or replaced. Scoped output routing is only active during this world pass.
 */
public final class SecondaryWorldCamera {
    public enum Status { LIVE, TERRAIN_ONLY, PREPARING, WAITING_CHUNKS, ENTITY_EXTRACTION_FAILED, ENTITY_SUBMISSION_FAILED, FAILED, UNAVAILABLE }

    public record View(double x, double y, double z, float yaw, float pitch, float fov,
                       int renderDistance, int framesPerSecond) {
        public View {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) ||
                Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 4096 ||
                !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(fov))
                throw new IllegalArgumentException("Invalid secondary camera viewpoint");
            yaw = ((yaw % 360) + 540) % 360 - 180;
            pitch = Math.clamp(pitch, -89.9f, 89.9f);
            fov = Math.clamp(fov, 20f, 110f);
            renderDistance = Math.clamp(renderDistance, 2, 4);
            framesPerSecond = Math.clamp(framesPerSecond, 2, 60);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("Sky-Myce Secondary Camera");
    private static final ThreadLocal<Integer> renderDistance = new ThreadLocal<>();
    private static final ThreadLocal<Matrix4fc> projectionMatrix = new ThreadLocal<>();
    private static final ThreadLocal<LevelRenderer> activeRenderer = new ThreadLocal<>();
    private static final Feed crush = new Feed(false);
    private static final List<Feed> feeds = new ArrayList<>(4);
    private static Feed scheduledFeed;
    private static int nextFeed;
    /** Previews shown last frame; they share one render-time budget. */
    private static int activeFeeds = 1;
    private static RenderTarget activeOutput;

    private SecondaryWorldCamera() { }

    public static Status status() { return crush.status(); }
    public static CameraDiagnostics.Snapshot diagnostics() { return crush.diagnostics(); }

    /** Four reusable, independently owned low-resolution POVs; their worlds and immutable assets are shared. */
    public static Feed createFeed() {
        if (feeds.size() == 4) throw new IllegalStateException("At most four auxiliary camera feeds are supported");
        Feed feed = new Feed(true);
        feeds.add(feed);
        return feed;
    }

    /** Sodium's singleton lookup must see the renderer whose entity visibility is being evaluated. */
    public static LevelRenderer effectiveLevelRenderer(LevelRenderer vanilla) {
        LevelRenderer secondary = activeRenderer.get();
        return secondary == null ? vanilla : secondary;
    }

    static void withRenderer(LevelRenderer renderer, Runnable action) {
        LevelRenderer previous = activeRenderer.get();
        activeRenderer.set(renderer);
        try { action.run(); }
        finally {
            if (previous == null) activeRenderer.remove();
            else activeRenderer.set(previous);
        }
    }

    /**
     * How many secondary-render scopes are open on any thread. The hooks below run on very hot paths (every Fabric
     * event dispatch, every render-distance read), so with no scope open they return at once without a ThreadLocal read.
     */
    private static volatile int openScopes;

    public static int effectiveRenderDistance(int vanilla) {
        if (openScopes == 0) return vanilla;
        Integer distance = renderDistance.get();
        return distance == null ? vanilla : distance;
    }

    public static Object eventInvoker(Object vanilla) {
        if (openScopes == 0) return vanilla;
        return renderDistance.get() == null ? vanilla : SecondaryRenderEvents.invoker(vanilla);
    }

    public static Matrix4fc projectionMatrix(Matrix4fc vanilla) {
        if (openScopes == 0) return vanilla;
        Matrix4fc projection = projectionMatrix.get();
        return projection == null ? vanilla : projection;
    }

    static void withProjection(Matrix4fc projection, Runnable action) {
        Matrix4fc previous = projectionMatrix.get();
        projectionMatrix.set(projection);
        openScopes++;
        try { action.run(); }
        finally {
            openScopes--;
            if (previous == null) projectionMatrix.remove();
            else projectionMatrix.set(previous);
        }
    }

    static void withRenderDistance(int distance, Runnable action) {
        Integer previous = renderDistance.get();
        renderDistance.set(distance);
        openScopes++;
        try { action.run(); }
        finally {
            openScopes--;
            if (previous == null) renderDistance.remove();
            else renderDistance.set(previous);
        }
    }

    /** Called by GameRenderer before HUD extraction; hidden/removed HUDs cannot keep rendering. */
    public static void beginFrame() {
        crush.requested = false;
        scheduledFeed = null;
        long now = System.nanoTime();
        for (int offset = 0; offset < feeds.size(); offset++) {
            int index = (nextFeed + offset) % feeds.size();
            Feed feed = feeds.get(index);
            if (scheduledFeed == null && feed.requested && now >= feed.nextFrame && now >= feed.retryAfter) {
                scheduledFeed = feed;
                nextFeed = (index + 1) % feeds.size();
            }
        }
        activeFeeds = Math.max(1, (int) feeds.stream().filter(feed -> feed.requested).count());
        feeds.forEach(feed -> feed.requested = false);
    }

    public static void extract(GuiGraphicsExtractor graphics, View view, int x, int y, int width, int height, float opacity) {
        crush.extract(graphics, view, x, y, width, height, opacity, null);
    }

    /**
     * Builds the crush pipeline ahead of time (renderers, buffers, target), so the costly setup happens a few seconds
     * before the camera appears instead of at that moment. Kept like a released feed until used.
     */
    public static void prewarm(View view, int width, int height, double guiScale) {
        int[] size = CameraMath.targetSize(width, height, guiScale);
        crush.prewarm(view, size[0], size[1]);
    }

    /** Whether the crush pipeline is built (warm or in use). */
    public static boolean warm() { return crush.pipeline != null; }

    /** How long an unused pipeline is kept for reuse (leaps come every few seconds in a boss). */
    static final long IDLE_RETIRE_NANOS = 20_000_000_000L;

    public static final class Feed {
        private final boolean auxiliary;
        private Pipeline pipeline;
        private boolean requested;
        private Status status = Status.UNAVAILABLE;
        private long retryAfter;
        private long nextFrame;
        private long renderCost;
        private long extractionCost;
        private long nextDiagnosticLog;
        private UUID hiddenEntity;
        /** When the feed was last released while keeping its pipeline; 0 while in use. */
        private long releasedAt;

        private Feed(boolean auxiliary) { this.auxiliary = auxiliary; }

        /**
         * Stops drawing but keeps the built pipeline for a while, so reopening (the next leap) reuses it instead of
         * rebuilding every renderer and re-meshing chunks. An idle pipeline is closed after {@link #IDLE_RETIRE_NANOS},
         * and immediately on a level change or {@link #stop()}.
         */
        void prewarm(View view, int width, int height) {
            RenderSystem.assertOnRenderThread();
            Minecraft client = Minecraft.getInstance();
            if (pipeline != null || client.level == null || width <= 0 || height <= 0 || System.nanoTime() < retryAfter) return;
            try {
                withRenderDistance(view.renderDistance(), () -> {
                    pipeline = new Pipeline(client, view, width, height, this);
                    status = Status.PREPARING;
                });
                releasedAt = System.nanoTime();
            } catch (RuntimeException error) { fail(error); }
        }

        public void release() {
            RenderSystem.assertOnRenderThread();
            requested = false;
            nextFrame = 0;
            if (pipeline != null && releasedAt == 0) releasedAt = System.nanoTime();
        }
        public Status status() { return status; }
        public CameraDiagnostics.Snapshot diagnostics() { return pipeline == null ? null : pipeline.renderer.diagnostics().snapshot(); }

        public void extract(GuiGraphicsExtractor graphics, View view, int x, int y, int width, int height, float opacity, UUID hiddenEntity) {
            RenderSystem.assertOnRenderThread();
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null || width <= 0 || height <= 0 ||
                !Float.isFinite(opacity) || opacity <= 0) { stop(); return; }
            // Only which player is hidden changes; the pipeline stays.
            this.hiddenEntity = hiddenEntity;
            releasedAt = 0;
            requested = true;
            if (System.nanoTime() < retryAfter) return;
            if (!client.level.hasChunkAt(BlockPos.containing(view.x(), view.y(), view.z()))) {
                status = Status.WAITING_CHUNKS;
                return;
            }
            try {
                double poseScale = Math.max(Math.hypot(graphics.pose().m00(), graphics.pose().m01()),
                    Math.hypot(graphics.pose().m10(), graphics.pose().m11()));
                double scale = client.getWindow().getGuiScale() * poseScale;
                int[] size = auxiliary ? CameraMath.previewTargetSize(width, height, scale) : CameraMath.targetSize(width, height, scale);
                if (pipeline != null && pipeline.level != client.level) retire();
                if (auxiliary && scheduledFeed == null && System.nanoTime() >= nextFrame) {
                    scheduledFeed = this;
                    nextFeed = (feeds.indexOf(this) + 1) % feeds.size();
                }
                if ((!auxiliary || scheduledFeed == this) && System.nanoTime() >= nextFrame) withRenderDistance(view.renderDistance(), () -> {
                    if (pipeline == null || pipeline.level != client.level) {
                        retire();
                        pipeline = new Pipeline(client, view, size[0], size[1], this);
                        status = Status.PREPARING;
                    }
                    long extractionStarted = System.nanoTime();
                    withRenderer(pipeline.renderer, () -> pipeline.extract(client, view, size[0], size[1]));
                    extractionCost = System.nanoTime() - extractionStarted;
                    nextFrame = auxiliary ? CameraMath.nextPreviewFrame(nextFrame, System.nanoTime(), view.framesPerSecond(), renderCost, activeFeeds)
                        : System.nanoTime() + 1_000_000_000L / view.framesPerSecond();
                });
                if (pipeline == null || status == Status.PREPARING) return;
                int color = (Math.round(Math.clamp(opacity, 0f, 1f) * 255) << 24) | 0xFFFFFF;
                // Texture-target images have bottom-left origin. The GUI extractor preserves native pose/scissor/layering.
                ((SecondaryCameraGuiInvoker) graphics).skyMyce$cameraBlit(RenderPipelines.GUI_TEXTURED,
                    pipeline.target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    x, y, x + width, y + height, 0, 1, 1, 0, color);
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        public void stop() {
            RenderSystem.assertOnRenderThread();
            requested = false;
            status = Status.UNAVAILABLE;
            nextFrame = 0;
            releasedAt = 0;
            retire();
        }

        private void retire() {
            Pipeline previous = pipeline;
            pipeline = null;
            if (previous != null) RenderSystem.queueFencedTask(previous::close);
        }

        private void fail(RuntimeException error) {
            LOG.warn("Secondary camera failed; retrying after five seconds. Last extraction/submission: " + diagnostics(), error);
            retryAfter = System.nanoTime() + 5_000_000_000L;
            stop();
            status = Status.FAILED;
        }

        private void render(DeltaTracker ticks) {
            if (pipeline == null) return;
            if (Minecraft.getInstance().level != pipeline.level) { stop(); return; }
            if (!requested) {
                // Not drawn this frame: keep the pipeline briefly for reuse, then close it.
                long now = System.nanoTime();
                if (releasedAt == 0) releasedAt = now;
                else if (now - releasedAt > IDLE_RETIRE_NANOS) { status = Status.UNAVAILABLE; releasedAt = 0; retire(); }
                return;
            }
            releasedAt = 0;
            if (!pipeline.prepared) return;
            long started = System.nanoTime();
            try {
                withRenderDistance(pipeline.renderer.distance(), () ->
                    withRenderer(pipeline.renderer, () -> pipeline.render(ticks)));
                if (System.nanoTime() >= nextDiagnosticLog) {
                    LOG.debug("Secondary camera {}: {}", status, diagnostics());
                    nextDiagnosticLog = System.nanoTime() + 10_000_000_000L;
                }
                long elapsed = extractionCost + System.nanoTime() - started;
                // A slowly moving average keeps one slow frame from changing the preview cadence.
                renderCost = renderCost == 0 ? elapsed : (renderCost * 7 + elapsed) / 8;
            } catch (RuntimeException error) { fail(error); }
        }
    }

    /** Render first so the main pass rebinds its state and clears any singleton overlay draws from other mods. */
    public static void render(DeltaTracker ticks) {
        crush.render(ticks);
        feeds.forEach(feed -> feed.render(ticks));
    }

    public static void stop() {
        crush.stop();
    }

    public static void stopAll() {
        crush.stop();
        feeds.forEach(Feed::stop);
    }

    /** Only a graphics output override; never redirects main world extraction or a Camera reference. */
    public static RenderTarget outputTarget() {
        RenderTarget output = activeOutput;
        return output != null && RenderSystem.isOnRenderThread() ? output : null;
    }

    public static void sectionChanged(int x, int y, int z) {
        if (crush.pipeline != null) crush.pipeline.renderer.setSectionDirty(x, y, z);
        for (Feed feed : feeds) if (feed.pipeline != null) feed.pipeline.renderer.setSectionDirty(x, y, z);
    }

    public static void chunkReady(ChunkPos pos) {
        if (crush.pipeline != null) crush.pipeline.renderer.onChunkReadyToRender(pos);
        for (Feed feed : feeds) if (feed.pipeline != null) feed.pipeline.renderer.onChunkReadyToRender(pos);
    }

    public static void sectionNonEmpty(long section) {
        if (crush.pipeline != null) crush.pipeline.renderer.onSectionBecomingNonEmpty(section);
        for (Feed feed : feeds) if (feed.pipeline != null) feed.pipeline.renderer.onSectionBecomingNonEmpty(section);
    }

    private static final class Pipeline implements AutoCloseable {
        private final Feed owner;
        private final ClientLevel level;
        private final FixedWorldCamera camera = new FixedWorldCamera();
        private final GameRenderState state = new GameRenderState();
        private final SecondaryRenderBuffers buffers = new SecondaryRenderBuffers();
        private final SubmitNodeStorage submits = new SubmitNodeStorage();
        private FeatureRenderDispatcher features;
        private SecondaryLevelRenderer renderer;
        private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Sky-Myce Secondary Projection");
        private final GlobalSettingsUniform globals = new GlobalSettingsUniform();
        private final FogRenderer fog = new FogRenderer();
        private final CrossFrameResourcePool pool = new CrossFrameResourcePool(2);
        private RenderTarget target;
        private boolean prepared;

        private Pipeline(Minecraft client, View view, int width, int height, Feed owner) {
            this.owner = owner;
            level = client.level;
            try {
            SecondaryEntityDispatcherAccessor assets = (SecondaryEntityDispatcherAccessor) client.getEntityRenderDispatcher();
            EntityRenderDispatcher entities = new SecondaryEntityRenderDispatcher(client, assets);
            BlockEntityRenderDispatcher blocks = new SecondaryBlockEntityRenderDispatcher(client, assets, entities,
                ((SecondaryBlockEntityDispatcherAccessor) client.getBlockEntityRenderDispatcher()).skyMyce$sprites());
            entities.onResourceManagerReload(client.getResourceManager());
            blocks.onResourceManagerReload(client.getResourceManager());
            features = new FeatureRenderDispatcher(submits, client.getModelManager(), buffers.bufferSource(), client.getAtlasManager(),
                buffers.outlineBufferSource(), buffers.crumblingBufferSource(), client.font, state);
            renderer = new SecondaryLevelRenderer(client, entities, blocks, buffers, state, features, camera, view.renderDistance());
            ((SecondaryEntityRenderDispatcher) entities).diagnostics(renderer.diagnostics());
            camera.configure(level, view, width, height);
            renderer.onResourceManagerReload(client.getResourceManager());
            renderer.setLevel(level);
            target = new TextureTarget("Sky-Myce Secondary World", width, height, true);
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0xFF101018, target.getDepthTexture(), 1);
            } catch (RuntimeException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private void extract(Minecraft client, View view, int width, int height) {
            boolean resized = target.width != width || target.height != height;
            if (resized) {
                RenderTarget old = target;
                target = new TextureTarget("Sky-Myce Secondary World", width, height, true);
                RenderSystem.queueFencedTask(old::destroyBuffers);
            }
            prepared = false;
            if (!level.hasChunkAt(BlockPos.containing(view.x(), view.y(), view.z()))) {
                owner.status = Status.WAITING_CHUNKS;
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0xFF101018, target.getDepthTexture(), 1);
                return;
            }
            renderer.distance(view.renderDistance());
            renderer.hiddenEntity(owner.hiddenEntity);
            copyOptions(client.gameRenderer.getGameRenderState().optionsRenderState, state.optionsRenderState, view.renderDistance());
            camera.configure(level, view, width, height);
            renderer.update(camera);
            state.levelRenderState.reset();
            float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            camera.extractRenderState(state.levelRenderState.cameraRenderState, partial);
            state.levelRenderState.cameraRenderState.fogData = fog.setupFog(camera, view.renderDistance(), client.getDeltaTracker(), partial, level);
            renderer.extractLevel(client.getDeltaTracker(), camera, partial);
            // The vanilla sky attribute is RGB-only; sky.fsh preserves that color's alpha, which is otherwise zero.
            // This is our own extracted state. Keep its RGB, making the independent sky opaque for GUI composition.
            state.levelRenderState.skyRenderState.skyColor = CameraMath.opaqueColor(state.levelRenderState.skyRenderState.skyColor);
            // Clouds add little inside a dungeon and allocate extra meshes; the sky remains vanilla.
            state.levelRenderState.cloudColor = 0;
            prepared = true;
        }

        private void render(DeltaTracker ticks) {
            GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
            ProjectionType oldProjectionType = RenderSystem.getProjectionType();
            GpuBufferSlice oldFog = RenderSystem.getShaderFog();
            GpuBufferSlice oldLights = RenderSystem.getShaderLights();
            GpuBuffer oldGlobals = RenderSystem.getGlobalSettingsUniform();
            GpuTextureView oldColor = RenderSystem.outputColorTextureOverride;
            GpuTextureView oldDepth = RenderSystem.outputDepthTextureOverride;
            ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            boolean oldScissor = scissor.enabled();
            int sx = scissor.x(), sy = scissor.y(), sw = scissor.width(), sh = scissor.height();
            try {
                activeOutput = target;
                RenderSystem.disableScissorForRenderTypeDraws();
                RenderSystem.outputColorTextureOverride = target.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
                globals.update(target.width, target.height, state.optionsRenderState.glintStrength, level.getGameTime(), ticks, 0, camera.position(), false);
                RenderSystem.setProjectionMatrix(projection.getBuffer(state.levelRenderState.cameraRenderState.projectionMatrix), ProjectionType.PERSPECTIVE);
                fog.updateBuffer(state.levelRenderState.cameraRenderState.fogData);
                GpuBufferSlice worldFog = fog.getBuffer(FogRenderer.FogMode.WORLD);
                withProjection(state.levelRenderState.cameraRenderState.projectionMatrix, () ->
                    renderer.renderLevel(pool, ticks, false, state.levelRenderState.cameraRenderState,
                        state.levelRenderState.cameraRenderState.viewRotationMatrix, worldFog,
                        state.levelRenderState.cameraRenderState.fogData.color, true, state.levelRenderState.chunkSectionsToRender));
                owner.status = renderer.diagnostics().completed(renderer.countRenderedSections(), target.width, target.height);
            } finally {
                // Vanilla normally pops this itself; an interrupted world pass must unwind it too.
                if (renderer.modelViewPushed()) {
                    RenderSystem.getModelViewStack().popMatrix();
                    renderer.modelViewPushed(false);
                }
                activeOutput = null;
                RenderSystem.outputColorTextureOverride = oldColor;
                RenderSystem.outputDepthTextureOverride = oldDepth;
                RenderSystem.setProjectionMatrix(oldProjection, oldProjectionType);
                RenderSystem.setShaderFog(oldFog);
                RenderSystem.setShaderLights(oldLights);
                RenderSystem.setGlobalSettingsUniform(oldGlobals);
                if (oldScissor) RenderSystem.enableScissorForRenderTypeDraws(sx, sy, sw, sh);
                else RenderSystem.disableScissorForRenderTypeDraws();
                prepared = false;
                features.clearSubmitNodes();
                renderer.endFrame();
                features.endFrame();
                fog.endFrame();
                pool.endFrame();
            }
        }

        @Override public void close() {
            if (renderer != null) {
                renderer.setLevel(null);
                renderer.close();
            }
            if (features != null) features.close();
            buffers.close();
            camera.reset();
            if (target != null) target.destroyBuffers();
            projection.close();
            globals.close();
            fog.close();
            pool.close();
        }
    }

    private static void copyOptions(OptionsRenderState source, OptionsRenderState dest, int distance) {
        dest.cloudRange = 0;
        dest.cloudStatus = CloudStatus.OFF;
        dest.cutoutLeaves = source.cutoutLeaves;
        dest.improvedTransparency = false;
        dest.ambientOcclusion = source.ambientOcclusion;
        dest.textureFiltering = source.textureFiltering;
        dest.maxAnisotropyValue = source.maxAnisotropyValue;
        dest.glintSpeed = source.glintSpeed;
        dest.glintStrength = source.glintStrength;
        dest.textBackgroundOpacity = source.textBackgroundOpacity;
        dest.backgroundForChatOnly = source.backgroundForChatOnly;
        dest.cameraType = source.cameraType;
        dest.renderDistance = distance;
    }
}
