package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.HashMap;
import java.util.Map;

public final class CameraRenderCheck {
    public static void main(String[] args) {
        checkDispatcherOwnership();
        checkMainFrameOrder();
        int[] overlayCalls = {0};
        Object[] overlays = {
            (LevelRenderEvents.AfterBlockOutlineExtraction) (context, hit) -> overlayCalls[0]++,
            (LevelRenderEvents.EndExtraction) context -> overlayCalls[0]++,
            (LevelRenderEvents.StartMain) context -> overlayCalls[0]++,
            (LevelRenderEvents.AfterOpaqueTerrain) context -> overlayCalls[0]++,
            (LevelRenderEvents.CollectSubmits) context -> overlayCalls[0]++,
            (LevelRenderEvents.AfterSolidFeatures) context -> overlayCalls[0]++,
            (LevelRenderEvents.AfterTranslucentFeatures) context -> overlayCalls[0]++,
            (LevelRenderEvents.BeforeBlockOutline) (context, outline) -> { overlayCalls[0]++; return false; },
            (LevelRenderEvents.BeforeGizmos) context -> overlayCalls[0]++,
            (LevelRenderEvents.BeforeTranslucentTerrain) context -> overlayCalls[0]++,
            (LevelRenderEvents.AfterTranslucentTerrain) context -> overlayCalls[0]++,
            (LevelRenderEvents.EndMain) context -> overlayCalls[0]++
        };
        for (Object overlay : overlays) {
            require(SecondaryWorldCamera.eventInvoker(overlay) == overlay, "main-view overlay callbacks are unchanged");
            invokeOverlay(overlay.getClass().getInterfaces()[0], overlay);
        }
        SecondaryWorldCamera.withRenderDistance(3, () -> {
            for (Object overlay : overlays) {
                Object isolated = SecondaryWorldCamera.eventInvoker(overlay);
                require(isolated != overlay, "secondary passes isolate every world-render lifecycle event");
                invokeOverlay(overlay.getClass().getInterfaces()[0], isolated);
            }
            Runnable unrelated = () -> { };
            require(SecondaryWorldCamera.eventInvoker(unrelated) == unrelated, "unrelated Fabric events are preserved");
        });
        require(overlayCalls[0] == overlays.length, "third-party overlays run only for the main view");
        require(SecondaryWorldCamera.eventInvoker(overlays[0]) == overlays[0], "main-view callbacks resume after preview");
        require(SecondaryWorldCamera.effectiveRenderDistance(12) == 12, "main view retains its distance");
        SecondaryWorldCamera.withRenderDistance(3, () -> {
            require(SecondaryWorldCamera.effectiveRenderDistance(12) == 3, "secondary view bounds either renderer");
            SecondaryWorldCamera.withRenderDistance(2, () ->
                require(SecondaryWorldCamera.effectiveRenderDistance(12) == 2, "nested secondary distance"));
            require(SecondaryWorldCamera.effectiveRenderDistance(12) == 3, "nested scope restores its caller");
            var workerDistance = new java.util.concurrent.atomic.AtomicInteger();
            Thread worker = Thread.startVirtualThread(() ->
                workerDistance.set(SecondaryWorldCamera.effectiveRenderDistance(12)));
            try { worker.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
            require(workerDistance.get() == 12, "secondary settings do not leak into background work");
        });
        var failure = new IllegalStateException("secondary renderer failed");
        try {
            SecondaryWorldCamera.withRenderDistance(4, () -> { throw failure; });
            throw new AssertionError("Renderer failure was swallowed");
        } catch (IllegalStateException error) {
            require(error == failure, "renderer failure is preserved");
        }
        require(SecondaryWorldCamera.effectiveRenderDistance(12) == 12, "failed secondary view restores main settings");
        Matrix4f mainProjection = CameraMath.projection(90, 3840, 2160, 512, false);
        Matrix4f cameraProjection = CameraMath.projection(60, 640, 360, 48, false);
        Matrix4f retainedProjection = new Matrix4f(mainProjection);
        require(SecondaryWorldCamera.projectionMatrix(mainProjection) == mainProjection, "main terrain retains its projection");
        SecondaryWorldCamera.withProjection(cameraProjection, () -> {
            require(SecondaryWorldCamera.projectionMatrix(mainProjection) == cameraProjection, "Sodium terrain and entities use the same camera projection");
            for (Vector3f point : new Vector3f[] {new Vector3f(0, -1.62f, -6), new Vector3f(0, .18f, -6), new Vector3f(1, 0, -40)}) {
                Vector4f terrain = SecondaryWorldCamera.projectionMatrix(mainProjection).transform(new Vector4f(point, 1));
                Vector4f entity = cameraProjection.transform(new Vector4f(point, 1));
                require(terrain.equals(entity), "terrain and player share both screen position and depth");
            }
            SecondaryWorldCamera.withProjection(retainedProjection, () ->
                require(SecondaryWorldCamera.projectionMatrix(mainProjection) == retainedProjection, "nested projection scope"));
            require(SecondaryWorldCamera.projectionMatrix(mainProjection) == cameraProjection, "nested projection restores camera");
            var workerProjection = new java.util.concurrent.atomic.AtomicReference<org.joml.Matrix4fc>();
            Thread worker = Thread.startVirtualThread(() -> workerProjection.set(SecondaryWorldCamera.projectionMatrix(mainProjection)));
            try { worker.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
            require(workerProjection.get() == mainProjection, "camera projection is local to its render thread");
        });
        try {
            SecondaryWorldCamera.withProjection(cameraProjection, () -> { throw failure; });
            throw new AssertionError("Projection failure was swallowed");
        } catch (IllegalStateException error) {
            require(error == failure, "projection failure is preserved");
        }
        require(SecondaryWorldCamera.projectionMatrix(mainProjection) == mainProjection && mainProjection.equals(retainedProjection),
            "camera restores the original unmodified projection after drawing or failure");
        // Vanilla's RGB sky and alpha-zero fog clear must not reveal a different main-camera silhouette.
        for (int source : new int[] {0x0078A7FF, 0x00101018, 0x774488AA, 0xFF123456}) {
            int frame = CameraMath.opaqueColor(source);
            require((frame & 0xFFFFFF) == (source & 0xFFFFFF), "opaque camera backdrop preserves fog/sky RGB");
            float textureAlpha = (frame >>> 24) / 255f;
            for (int mainView : new int[] {0x00202020, 0x00BBDDEE, 0x00336622}) {
                for (int shift : new int[] {0, 8, 16}) {
                    float composite = ((frame >>> shift) & 255) * textureAlpha + ((mainView >>> shift) & 255) * (1 - textureAlpha);
                    require(composite == ((source >>> shift) & 255), "100% camera opacity hides all main-view colors even in sky/fog pixels");
                }
            }
        }
        for (float yaw : new float[] {0, 90, -90, 180, -35}) {
            for (float pitch : new float[] {0, 30, -60, 89}) {
                double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
                Vector3f forward = new Vector3f((float) (-Math.sin(yr) * Math.cos(pr)),
                    (float) -Math.sin(pr), (float) (Math.cos(yr) * Math.cos(pr)));
                Vector3f inView = CameraMath.viewRotation(yaw, pitch).transformDirection(forward);
                near(inView.x, 0, "camera forward x");
                near(inView.y, 0, "camera forward y");
                near(inView.z, -1, "camera forward depth");
            }
        }
        Matrix4f primary = CameraMath.viewRotation(25, -10);
        Matrix4f retained = new Matrix4f(primary);
        Matrix4f secondary = CameraMath.viewRotation(-90, 45);
        secondary.rotateZ(.2f);
        require(primary.equals(retained), "A different viewpoint must not mutate another view matrix");

        for (boolean zeroToOne : new boolean[] {false, true}) {
            Matrix4f projection = CameraMath.projection(60, 640, 360, 64, zeroToOne);
            Vector4f nearPoint = projection.transform(new Vector4f(0, 0, -.05f, 1));
            Vector4f farPoint = projection.transform(new Vector4f(0, 0, -64, 1));
            near(nearPoint.z / nearPoint.w, zeroToOne ? 0 : -1, "near clipping plane");
            near(farPoint.z / farPoint.w, 1, "far clipping plane");
            near(projection.m11() / projection.m00(), 640f / 360, "viewport aspect ratio");
        }
        for (int[] rectangle : new int[][] {{240, 135}, {2000, 600}, {320, 1200}, {1, 1}}) {
            int[] target = CameraMath.targetSize(rectangle[0], rectangle[1], 4);
            require(target[0] <= 1024 && target[1] <= 768 && target[0] > 0 && target[1] > 0, "bounded GPU target");
            near((float) target[0] / target[1], (float) rectangle[0] / rectangle[1], .015f, "bounded target preserves aspect");
        }
        SecondaryWorldCamera.View view = new SecondaryWorldCamera.View(100.5, 170, 80, 725, 100, 180, 30, 120);
        require(view.yaw() == 5 && view.pitch() == 89.9f && view.fov() == 110 && view.renderDistance() == 4 &&
            view.framesPerSecond() == 30, "camera options have safe bounded ranges");
        reject(() -> new SecondaryWorldCamera.View(Double.NaN, 170, 0, 0, 0, 70, 3, 20));
        reject(() -> new SecondaryWorldCamera.View(30_000_001, 170, 0, 0, 0, 70, 3, 20));
        reject(() -> new SecondaryWorldCamera.View(0, 170, 0, Float.POSITIVE_INFINITY, 0, 70, 3, 20));
        reject(() -> CameraMath.projection(Float.NaN, 640, 360, 64, false));
        reject(() -> CameraMath.projection(70, 0, 360, 64, false));
        reject(() -> CameraMath.projection(70, 640, 360, Float.NaN, false));
        reject(() -> CameraMath.targetSize(100, 100, Double.POSITIVE_INFINITY));
        System.out.println("Camera rendering checks passed: dispatcher/cache ownership after teardown, avatar selection, scoped render distance and terrain projection, independent rotations, Minecraft yaw/pitch, both depth conventions, aspect, target bounds, and invalid views.");
    }

    private static void checkMainFrameOrder() {
        try {
            boolean[] headHook = {false};
            int[] secondaryDraws = {0};
            try (var source = CameraRenderCheck.class.getResourceAsStream("/me/mycellium/mixin/SecondaryCameraGameRendererMixin.class")) {
                new ClassReader(java.util.Objects.requireNonNull(source)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!name.equals("skyMyce$drawSecondaryWorld")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                if (!descriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override public AnnotationVisitor visitArray(String name) {
                                        if (!name.equals("at")) return null;
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                                return new AnnotationVisitor(Opcodes.ASM9) {
                                                    @Override public void visit(String name, Object value) {
                                                        if (name.equals("value")) headHook[0] = value.equals("HEAD");
                                                    }
                                                };
                                            }
                                        };
                                    }
                                };
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                                if (owner.equals("me/mycellium/skymyce/features/instances/dungeons/camera/render/SecondaryWorldCamera") && name.equals("render")) secondaryDraws[0]++;
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            require(headHook[0] && secondaryDraws[0] == 1, "secondary world renders before the main frame, never over the completed world and hand");
            boolean[] lightmapReady = {false}, mainWorld = {false}, projectionReady = {false}, fogReady = {false}, handAfterWorld = {false};
            try (var source = CameraRenderCheck.class.getResourceAsStream("/net/minecraft/client/renderer/GameRenderer.class")) {
                new ClassReader(java.util.Objects.requireNonNull(source)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        if (!method.equals("render") && !method.equals("renderLevel")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                                if (method.equals("render")) {
                                    if (owner.equals("net/minecraft/client/renderer/Lightmap") && name.equals("render")) lightmapReady[0] = true;
                                    if (owner.equals("net/minecraft/client/renderer/GameRenderer") && name.equals("renderLevel"))
                                        require(lightmapReady[0], "the main lightmap is ready before the secondary render hook");
                                } else {
                                    if (owner.equals("com/mojang/blaze3d/systems/RenderSystem") && name.equals("setProjectionMatrix")) projectionReady[0] = true;
                                    if (owner.equals("net/minecraft/client/renderer/fog/FogRenderer") && name.equals("updateBuffer")) fogReady[0] = true;
                                    if (owner.equals("net/minecraft/client/renderer/LevelRenderer") && name.equals("renderLevel")) {
                                        require(projectionReady[0] && fogReady[0], "the main projection and fog are rebound after the secondary hook");
                                        mainWorld[0] = true;
                                    }
                                    if (name.equals("renderItemInHand")) handAfterWorld[0] = mainWorld[0];
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            require(mainWorld[0] && handAfterWorld[0], "the main world and hand draw after the auxiliary passes");
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void checkDispatcherOwnership() {
        try {
            // Construct only the dispatch/lookup objects: no Minecraft instance, registries, assets or GL context.
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var allocator = (sun.misc.Unsafe) field.get(null);
            var main = (EntityRenderDispatcher) allocator.allocateInstance(EntityRenderDispatcher.class);
            var secondary = (SecondaryEntityRenderDispatcher) allocator.allocateInstance(SecondaryEntityRenderDispatcher.class);
            var mainRenderer = (LookupRenderer) allocator.allocateInstance(LookupRenderer.class);
            var localRenderer = (LookupRenderer) allocator.allocateInstance(LookupRenderer.class);
            putMap(EntityRenderDispatcher.class, main, "renderers", mainRenderer);
            // A singleton-oriented superclass cache must never be the secondary view's lookup source.
            putMap(EntityRenderDispatcher.class, secondary, "renderers", mainRenderer);
            putMap(SecondaryEntityRenderDispatcher.class, secondary, "localRenderers", localRenderer);
            var mainCamera = (Camera) allocator.allocateInstance(Camera.class);
            var secondaryCamera = (Camera) allocator.allocateInstance(Camera.class);
            main.prepare(mainCamera, null);
            secondary.prepare(secondaryCamera, null);
            var state = new EntityRenderState();
            require(main.getRenderer(state) == mainRenderer && secondary.getRenderer(state) == localRenderer,
                "main and secondary extracted entities use distinct dispatcher-local renderers");
            secondary.resetCamera();
            require(main.camera == mainCamera && secondary.camera == null && main.getRenderer(state) == mainRenderer,
                "secondary teardown cannot clear the main renderer's camera or replace its renderer");
            require(secondary.getRenderer(state) == localRenderer, "teardown does not redirect secondary lookup to a shared cache");

            var mainBlocks = (BlockEntityRenderDispatcher) allocator.allocateInstance(BlockEntityRenderDispatcher.class);
            var secondaryBlocks = (SecondaryBlockEntityRenderDispatcher) allocator.allocateInstance(SecondaryBlockEntityRenderDispatcher.class);
            var mainBlock = new LookupBlockRenderer();
            var localBlock = new LookupBlockRenderer();
            putMap(BlockEntityRenderDispatcher.class, mainBlocks, "renderers", mainBlock);
            putMap(BlockEntityRenderDispatcher.class, secondaryBlocks, "renderers", mainBlock);
            putMap(SecondaryBlockEntityRenderDispatcher.class, secondaryBlocks, "localRenderers", localBlock);
            var blockState = (BlockEntityRenderState) allocator.allocateInstance(BlockEntityRenderState.class);
            require(mainBlocks.getRenderer(blockState) == mainBlock && secondaryBlocks.getRenderer(blockState) == localBlock,
                "block-entity state lookup also ignores the main view's cache");

            Object wide = new Object(), slim = new Object();
            var avatars = Map.of(PlayerModelType.WIDE, wide, PlayerModelType.SLIM, slim);
            require(SecondaryEntityRenderDispatcher.avatarRenderer(avatars, PlayerModelType.WIDE) == wide &&
                SecondaryEntityRenderDispatcher.avatarRenderer(avatars, PlayerModelType.SLIM) == slim,
                "both player and mannequin maps retain the selected vanilla skin model");
            require(SecondaryEntityRenderDispatcher.avatarRenderer(Map.of(PlayerModelType.WIDE, wide), PlayerModelType.SLIM) == wide,
                "a missing skin model retains vanilla's wide fallback");
            require(SecondaryEntityRenderDispatcher.avatarRenderer(Map.of(), PlayerModelType.WIDE) == null,
                "an unavailable avatar renderer remains unavailable");
            checkOwnedDispatcherBytecode(SecondaryEntityRenderDispatcher.class, 3, 3);
            checkOwnedDispatcherBytecode(SecondaryBlockEntityRenderDispatcher.class, 1, 2);
        } catch (ReflectiveOperationException | java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void putMap(Class<?> owner, Object dispatcher, String name, Object renderer) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        var map = new HashMap<>();
        map.put(null, renderer); // A synthetic type key avoids initializing the game registry in this lookup fixture.
        field.set(dispatcher, map);
    }

    private static void checkOwnedDispatcherBytecode(Class<?> owner, int factoryCalls, int lookups) throws java.io.IOException {
        int[] factories = {0}, methods = {0};
        try (var source = owner.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
            new ClassReader(java.util.Objects.requireNonNull(source)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("onResourceManagerReload") && !name.equals("getRenderer") && !name.equals("getPlayerRenderer")) return null;
                    if (name.equals("getRenderer") || name.equals("getPlayerRenderer")) methods[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String target, String method, String desc, boolean itf) {
                            require(!(opcode == Opcodes.INVOKESPECIAL && target.equals(owner.getSuperclass().getName().replace('.', '/'))),
                                "owned reload/lookup must not invoke singleton-oriented superclass hooks");
                            if (name.equals("onResourceManagerReload") && (method.equals("createEntityRenderers") || method.equals("createAvatarRenderers"))) factories[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        require(factories[0] == factoryCalls && methods[0] == lookups,
            "all entity/state/avatar lookups are owned, and reload keeps public registered-renderer factories");
    }

    private static final class LookupRenderer extends EntityRenderer<Entity, EntityRenderState> {
        private LookupRenderer() { super(null); }
        @Override public EntityRenderState createRenderState() { return new EntityRenderState(); }
    }

    private static final class LookupBlockRenderer implements BlockEntityRenderer<BlockEntity, BlockEntityRenderState> {
        @Override public BlockEntityRenderState createRenderState() { return null; }
        @Override public void submit(BlockEntityRenderState state, PoseStack pose, SubmitNodeCollector submits, CameraRenderState camera) { }
    }

    private static void invokeOverlay(Class<?> event, Object invoker) {
        var method = event.getDeclaredMethods()[0];
        try { method.invoke(invoker, new Object[method.getParameterCount()]); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void reject(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid camera input accepted");
    }
    private static void near(float actual, float expected, String reason) { near(actual, expected, .0002f, reason); }
    private static void near(float actual, float expected, float tolerance, String reason) {
        require(Float.isFinite(actual) && Math.abs(actual - expected) <= tolerance, reason + ": " + actual + " != " + expected);
    }
    private static void require(boolean passed, String reason) { if (!passed) throw new AssertionError(reason); }
}
