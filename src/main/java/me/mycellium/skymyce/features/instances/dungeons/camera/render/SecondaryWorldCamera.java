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

/**
 * A live, client-local second world view. Extraction uses independent mutable renderer state;
 * the world pass runs after the main world and before GUI drawing. The player's Camera is never set,
 * repositioned, rotated or replaced. Scoped output routing is only active during this world pass.
 */
public final class SecondaryWorldCamera {
    public enum Status { LIVE, PREPARING, WAITING_CHUNKS, UNAVAILABLE }

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
            framesPerSecond = Math.clamp(framesPerSecond, 5, 30);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("Sky-Myce Secondary Camera");
    private static final ThreadLocal<Integer> renderDistance = new ThreadLocal<>();
    private static final ThreadLocal<Matrix4fc> projectionMatrix = new ThreadLocal<>();
    private static Pipeline pipeline;
    private static boolean requested;
    private static boolean rendering;
    private static Status status = Status.UNAVAILABLE;
    private static long retryAfter;

    private SecondaryWorldCamera() { }

    public static Status status() { return status; }

    public static int effectiveRenderDistance(int vanilla) {
        Integer distance = renderDistance.get();
        return distance == null ? vanilla : distance;
    }

    public static Object eventInvoker(Object vanilla) {
        return renderDistance.get() == null ? vanilla : SecondaryRenderEvents.invoker(vanilla);
    }

    public static Matrix4fc projectionMatrix(Matrix4fc vanilla) {
        Matrix4fc projection = projectionMatrix.get();
        return projection == null ? vanilla : projection;
    }

    static void withProjection(Matrix4fc projection, Runnable action) {
        Matrix4fc previous = projectionMatrix.get();
        projectionMatrix.set(projection);
        try { action.run(); }
        finally {
            if (previous == null) projectionMatrix.remove();
            else projectionMatrix.set(previous);
        }
    }

    static void withRenderDistance(int distance, Runnable action) {
        Integer previous = renderDistance.get();
        renderDistance.set(distance);
        try { action.run(); }
        finally {
            if (previous == null) renderDistance.remove();
            else renderDistance.set(previous);
        }
    }

    /** Called by GameRenderer before HUD extraction; hidden/removed HUDs cannot keep rendering. */
    public static void beginFrame() { requested = false; }

    public static void extract(GuiGraphicsExtractor graphics, View view, int x, int y, int width, int height, float opacity) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || width <= 0 || height <= 0 ||
            !Float.isFinite(opacity) || opacity <= 0 || System.nanoTime() < retryAfter) return;
        requested = true;
        try {
            double poseScale = Math.max(Math.hypot(graphics.pose().m00(), graphics.pose().m01()),
                Math.hypot(graphics.pose().m10(), graphics.pose().m11()));
            int[] size = CameraMath.targetSize(width, height, client.getWindow().getGuiScale() * poseScale);
            withRenderDistance(view.renderDistance(), () -> {
                if (pipeline == null || pipeline.level != client.level) {
                    retire();
                    pipeline = new Pipeline(client, view, size[0], size[1]);
                    status = Status.PREPARING;
                }
                pipeline.extract(client, view, size[0], size[1]);
            });
            int color = (Math.round(Math.clamp(opacity, 0f, 1f) * 255) << 24) | 0xFFFFFF;
            // Texture-target images have bottom-left origin. The GUI extractor preserves native pose/scissor/layering.
            ((SecondaryCameraGuiInvoker) graphics).skyMyce$cameraBlit(RenderPipelines.GUI_TEXTURED,
                pipeline.target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                x, y, x + width, y + height, 0, 1, 1, 0, color);
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    /** Hook after the main world render, while its lightmap and immutable asset resources are ready. */
    public static void render(DeltaTracker ticks) {
        if (pipeline == null) return;
        if (!requested || Minecraft.getInstance().level != pipeline.level) { stop(); return; }
        if (!pipeline.prepared) return;
        try { withRenderDistance(pipeline.renderer.distance(), () -> pipeline.render(ticks)); }
        catch (RuntimeException error) { fail(error); }
    }

    public static void stop() {
        RenderSystem.assertOnRenderThread();
        requested = false;
        status = Status.UNAVAILABLE;
        retire();
    }

    private static void retire() {
        Pipeline previous = pipeline;
        pipeline = null;
        if (previous != null) RenderSystem.queueFencedTask(previous::close);
    }

    private static void fail(RuntimeException error) {
        LOG.warn("Secondary camera unavailable; retrying after five seconds", error);
        retryAfter = System.nanoTime() + 5_000_000_000L;
        stop();
    }

    /** Only a graphics output override; never redirects main world extraction or a Camera reference. */
    public static RenderTarget outputTarget() {
        return rendering && RenderSystem.isOnRenderThread() && pipeline != null ? pipeline.target : null;
    }

    public static void sectionChanged(int x, int y, int z) {
        if (pipeline != null) pipeline.renderer.setSectionDirty(x, y, z);
    }

    public static void chunkReady(ChunkPos pos) {
        if (pipeline != null) pipeline.renderer.onChunkReadyToRender(pos);
    }

    public static void sectionNonEmpty(long section) {
        if (pipeline != null) pipeline.renderer.onSectionBecomingNonEmpty(section);
    }

    private static final class Pipeline implements AutoCloseable {
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
        private long nextFrame;
        private View previousView;
        private boolean prepared;

        private Pipeline(Minecraft client, View view, int width, int height) {
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
            boolean changed = !view.equals(previousView);
            if (resized) {
                RenderTarget old = target;
                target = new TextureTarget("Sky-Myce Secondary World", width, height, true);
                RenderSystem.queueFencedTask(old::destroyBuffers);
            }
            if (!resized && !changed && System.nanoTime() < nextFrame) return;
            nextFrame = System.nanoTime() + 1_000_000_000L / view.framesPerSecond();
            previousView = view;
            prepared = false;
            if (!level.hasChunkAt(BlockPos.containing(view.x(), view.y(), view.z()))) {
                status = Status.WAITING_CHUNKS;
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0xFF101018, target.getDepthTexture(), 1);
                return;
            }
            renderer.distance(view.renderDistance());
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
                rendering = true;
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
                status = renderer.countRenderedSections() > 0 ? Status.LIVE : Status.PREPARING;
            } finally {
                // Vanilla normally pops this itself; an interrupted world pass must unwind it too.
                if (renderer.modelViewPushed()) {
                    RenderSystem.getModelViewStack().popMatrix();
                    renderer.modelViewPushed(false);
                }
                rendering = false;
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
