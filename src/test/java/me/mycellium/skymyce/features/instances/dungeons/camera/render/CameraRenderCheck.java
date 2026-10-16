package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.HashMap;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;

public final class CameraRenderCheck {
    public static void main(String[] args) {
        checkDispatcherOwnership();
        checkDiagnostics();
        checkViewCulling();
        checkFeedBounds();
        checkChunkEvents();
        checkMainFrameOrder();
        checkPreviewCadence();
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
            int[] preview = CameraMath.previewTargetSize(rectangle[0], rectangle[1], 4);
            require(preview[0] <= 320 && preview[1] <= 180 && preview[0] > 0 && preview[1] > 0, "four POV textures have a smaller fixed memory bound");
        }
        SecondaryWorldCamera.View view = new SecondaryWorldCamera.View(100.5, 170, 80, 725, 100, 180, 30, 120);
        require(view.yaw() == 5 && view.pitch() == 89.9f && view.fov() == 110 && view.renderDistance() == 4 &&
            view.framesPerSecond() == 60, "camera options have safe bounded ranges");
        require(new SecondaryWorldCamera.View(0, 170, 0, 0, 0, 70, 4, 60).framesPerSecond() == 60,
            "the increased 60 FPS target reaches the camera without a hidden lower cap");
        require(new SecondaryWorldCamera.View(0, 170, 0, 0, 0, 70, 4, 0).framesPerSecond() == 2,
            "the camera retains its minimum frame-rate bound");
        reject(() -> new SecondaryWorldCamera.View(Double.NaN, 170, 0, 0, 0, 70, 3, 20));
        reject(() -> new SecondaryWorldCamera.View(30_000_001, 170, 0, 0, 0, 70, 3, 20));
        reject(() -> new SecondaryWorldCamera.View(0, 170, 0, Float.POSITIVE_INFINITY, 0, 70, 3, 20));
        reject(() -> CameraMath.projection(Float.NaN, 640, 360, 64, false));
        reject(() -> CameraMath.projection(70, 0, 360, 64, false));
        reject(() -> CameraMath.projection(70, 640, 360, Float.NaN, false));
        reject(() -> CameraMath.targetSize(100, 100, Double.POSITIVE_INFINITY));
        reject(() -> CameraMath.previewTargetSize(100, 100, Double.POSITIVE_INFINITY));
        System.out.println("Camera rendering checks passed: dispatcher/cache ownership, scoped Sodium visibility, independent frusta, entity extraction/submission diagnostics, four-feed bounds, projections and strict viewpoints. Actual GPU pixels and Storm require visual verification.");
    }

    /** Leap previews at a 144 Hz game loop: even spacing, the shared budget, and no catch-up burst after a stall. */
    private static void checkPreviewCadence() {
        long frame = 1_000_000_000L / 144;
        java.util.function.BiFunction<long[], Integer, long[]> intervals = (settings, frames) -> {
            long due = 0, last = -1;
            long[] gaps = new long[frames];
            int count = 0;
            for (int i = 1; i <= frames * 40 && count < frames; i++) {
                long now = i * frame;
                if (now < due) continue;
                if (last >= 0) gaps[count++] = now - last;
                last = now;
                due = CameraMath.nextPreviewFrame(due, now, (int) settings[0], settings[1], (int) settings[2]);
            }
            return java.util.Arrays.copyOf(gaps, count);
        };
        long[] smooth = intervals.apply(new long[] {30, 3_000_000L, 3}, 60);
        long target = 1_000_000_000L / 30;
        require(smooth.length == 60 && java.util.Arrays.stream(smooth).allMatch(gap -> Math.abs(gap - target) <= frame),
            "previews keep an even 30 FPS cadence within one game frame");
        double mean = java.util.Arrays.stream(smooth).average().orElse(0);
        require(Math.abs(mean - target) < frame / 4.0, "the cadence does not drift with frame timing");
        long[] budgeted = intervals.apply(new long[] {60, 10_000_000L, 4}, 20);
        require(java.util.Arrays.stream(budgeted).allMatch(gap -> gap >= 80_000_000L - frame && gap <= 80_000_000L + frame),
            "four 10 ms previews share half the frame time: one frame every 80 ms each");
        long stalled = CameraMath.nextPreviewFrame(1_000_000L, 5_000_000_000L, 30, 0, 1);
        require(stalled == 5_000_000_000L + target, "a stall restarts the cadence instead of rendering a burst");
        require(CameraMath.nextPreviewFrame(0, 7, 2, 2_000_000_000L, 4) == 7 + 1_000_000_000L, "a very slow preview waits at most one second");
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

    private static void checkDiagnostics() {
        var diagnostics = new CameraDiagnostics();
        diagnostics.begin();
        diagnostics.candidate(true, true, "loaded wither");
        require(diagnostics.completed(7, 320, 180) == SecondaryWorldCamera.Status.ENTITY_EXTRACTION_FAILED,
            "terrain alone cannot hide a missing visible boss extraction");
        var boss = new EntityRenderState();
        diagnostics.extracted(boss, true);
        require(diagnostics.completed(7, 320, 180) == SecondaryWorldCamera.Status.ENTITY_SUBMISSION_FAILED,
            "an extracted boss must also reach the secondary dispatcher submission");
        diagnostics.submitted(boss);
        diagnostics.submitted(boss);
        require(diagnostics.completed(7, 320, 180) == SecondaryWorldCamera.Status.LIVE,
            "successful terrain and entity submission is reported separately from visual proof");
        var snapshot = diagnostics.snapshot();
        require(snapshot.loadedBosses() == 1 && snapshot.visibleBosses() == 1 && snapshot.extractedBosses() == 1 &&
            snapshot.submittedBosses() == 1 && snapshot.submittedEntities() == 1, "boss stages count each extracted state once");
        require(snapshot.targetWidth() == 320 && snapshot.targetHeight() == 180 && snapshot.terrainSections() == 7,
            "diagnostics retain the actual secondary output dimensions and terrain submission");
        diagnostics.begin();
        diagnostics.candidate(false, true, "outside viewpoint");
        require(diagnostics.completed(7, 320, 180) == SecondaryWorldCamera.Status.TERRAIN_ONLY,
            "a terrain frame without visible entities remains distinguishable");
        require(diagnostics.completed(0, 320, 180) == SecondaryWorldCamera.Status.PREPARING,
            "a genuinely unprepared renderer is not reported as live");
        require(snapshot.bosses().equals(java.util.List.of("loaded wither")), "saved diagnostics do not change with the next extraction");
    }

    private static void checkChunkEvents() {
        long first = packedChunk(-7, 12), second = packedChunk(8, -3);
        LongSet ready = new LongOpenHashSet(new long[] {first});
        LongSet main = new LongOpenHashSet(), other = new LongOpenHashSet();
        LongSet loaded = new LongOpenHashSet(), unloaded = new LongOpenHashSet();
        SecondaryChunkEventHandler add = (x, z) -> require(loaded.add(packedChunk(x, z)), "a chunk is loaded once per update");
        SecondaryChunkEventHandler remove = (x, z) -> require(unloaded.add(packedChunk(x, z)), "a chunk is unloaded once per update");
        SecondaryLevelRenderer.synchronizeReadyChunks(main, ready, add, remove);
        require(loaded.equals(ready) && unloaded.isEmpty(), "a new POV receives existing ready chunks with signed coordinates");
        loaded.clear();
        SecondaryLevelRenderer.synchronizeReadyChunks(main, ready, add, remove);
        require(loaded.isEmpty() && unloaded.isEmpty(), "unchanged ready chunks do not rebuild repeatedly");
        ready.add(second);
        SecondaryLevelRenderer.synchronizeReadyChunks(main, ready, add, remove);
        require(loaded.equals(new LongOpenHashSet(new long[] {second})), "newly loaded chunks reach an existing POV");
        loaded.clear();
        SecondaryLevelRenderer.synchronizeReadyChunks(other, ready, add, remove);
        require(loaded.equals(ready), "another POV receives the same chunks independently");
        loaded.clear();
        ready.remove(first);
        SecondaryLevelRenderer.synchronizeReadyChunks(main, ready, add, remove);
        require(loaded.isEmpty() && unloaded.equals(new LongOpenHashSet(new long[] {first})), "unloaded terrain leaves the POV");
        require(other.size() == 2 && ready.size() == 1, "a POV delta does not consume another POV or the shared ready set");
        unloaded.clear();
        SecondaryLevelRenderer.synchronizeReadyChunks(other, ready, add, remove);
        require(unloaded.equals(new LongOpenHashSet(new long[] {first})), "each POV receives unloads independently");
    }

    /** Run inside the offline GUI client to verify the installed optional Sodium classes after Mixin transformation. */
    public static boolean checkSodiumChunkEventsCompatibility() {
        try {
            Class<?> trackerClass = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker");
            Class<?> handlerClass = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker$ChunkEventHandler");
            require(SecondaryChunkEventHandler.class.isAssignableFrom(handlerClass), "Sodium's transformed callback exposes the optional bridge");
            Object tracker = trackerClass.getConstructor().newInstance();
            var each = trackerClass.getMethod("forEachEvent", handlerClass, handlerClass);
            var added = trackerClass.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
            var removed = trackerClass.getMethod("onChunkStatusRemoved", int.class, int.class, int.class);
            LongSet loaded = new LongOpenHashSet(), unloaded = new LongOpenHashSet();
            Object load = chunkCallback(handlerClass, loaded), unload = chunkCallback(handlerClass, unloaded);
            long chunk = ChunkPos.pack(-3, 7);
            for (int x = -4; x <= -2; x++) for (int z = 6; z <= 8; z++) added.invoke(tracker, x, z, 3);
            each.invoke(tracker, load, unload);
            require(loaded.equals(new LongOpenHashSet(new long[] {chunk})) && unloaded.isEmpty(), "main Sodium receives its normal ready-chunk queue");
            loaded.clear();
            each.invoke(tracker, load, unload);
            require(loaded.isEmpty() && unloaded.isEmpty(), "main Sodium consumes its normal queue once");

            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var allocator = (sun.misc.Unsafe) unsafeField.get(null);
            var first = (SecondaryLevelRenderer) allocator.allocateInstance(SecondaryLevelRenderer.class);
            var second = (SecondaryLevelRenderer) allocator.allocateInstance(SecondaryLevelRenderer.class);
            var readyField = SecondaryLevelRenderer.class.getDeclaredField("readySodiumChunks");
            readyField.setAccessible(true);
            readyField.set(first, new LongOpenHashSet());
            readyField.set(second, new LongOpenHashSet());
            for (var secondary : new SecondaryLevelRenderer[] {first, second}) {
                loaded.clear();
                SecondaryWorldCamera.withRenderer(secondary, () -> invokeChunkEvents(each, tracker, load, unload));
                require(loaded.equals(new LongOpenHashSet(new long[] {chunk})), "each transformed Sodium POV receives chunks after the main queue was consumed");
            }
            loaded.clear();
            removed.invoke(tracker, -3, 7, 3);
            SecondaryWorldCamera.withRenderer(first, () -> invokeChunkEvents(each, tracker, load, unload));
            require(unloaded.equals(new LongOpenHashSet(new long[] {chunk})), "transformed POV receives a chunk unload");
            unloaded.clear();
            each.invoke(tracker, load, unload);
            require(unloaded.equals(new LongOpenHashSet(new long[] {chunk})), "POV updates leave the main unload queue intact");
            unloaded.clear();
            SecondaryWorldCamera.withRenderer(second, () -> invokeChunkEvents(each, tracker, load, unload));
            require(unloaded.equals(new LongOpenHashSet(new long[] {chunk})), "the other POV receives the unload after the main queue was consumed");
            return true;
        } catch (ClassNotFoundException absent) { return false; }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Optional Sodium chunk-event compatibility failed", failure); }
    }

    private static Object chunkCallback(Class<?> callback, LongSet chunks) {
        return java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback}, (proxy, method, args) -> {
            if (method.getName().equals("apply")) chunks.add(ChunkPos.pack((Integer) args[0], (Integer) args[1]));
            return null;
        });
    }

    private static long packedChunk(int x, int z) { return Integer.toUnsignedLong(x) | (long) z << 32; }

    private static void invokeChunkEvents(java.lang.reflect.Method each, Object tracker, Object loaded, Object unloaded) {
        try { each.invoke(tracker, loaded, unloaded); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Transformed Sodium chunk dispatch failed", failure); }
    }

    private static void checkViewCulling() {
        for (boolean zeroToOne : new boolean[] {false, true}) {
            var projection = CameraMath.projection(65, 320, 180, 48, zeroToOne);
            Frustum main = new Frustum(CameraMath.viewRotation(0, 0), projection);
            Frustum secondary = new Frustum(CameraMath.viewRotation(180, 0), projection);
            main.prepare(0, 1.6, 0);
            secondary.prepare(0, 1.6, 0);
            AABB behindMain = new AABB(-.5, .5, -7, .5, 2.5, -6);
            AABB inMain = new AABB(-.5, .5, 6, .5, 2.5, 7);
            require(!main.isVisible(behindMain) && secondary.isVisible(behindMain),
                "a loaded entity behind the main view remains visible from the opposite secondary view");
            require(main.isVisible(inMain) && !secondary.isVisible(inMain), "secondary frustum culling remains active");
        }
    }

    private static void checkFeedBounds() {
        var unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SecondaryWorldCamera.Feed, Boolean>());
        for (int index = 0; index < 4; index++) {
            var feed = SecondaryWorldCamera.createFeed();
            require(unique.add(feed) && feed.status() == SecondaryWorldCamera.Status.UNAVAILABLE && feed.diagnostics() == null,
                "each auxiliary handle starts independently, without allocating a world renderer or GPU target");
        }
        try { SecondaryWorldCamera.createFeed(); throw new AssertionError("unbounded secondary renderers"); }
        catch (IllegalStateException expected) { }
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
            var mainLevel = (LevelRenderer) allocator.allocateInstance(LevelRenderer.class);
            var secondaryLevel = (SecondaryLevelRenderer) allocator.allocateInstance(SecondaryLevelRenderer.class);
            var otherLevel = (SecondaryLevelRenderer) allocator.allocateInstance(SecondaryLevelRenderer.class);
            require(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel) == mainLevel, "Sodium normally retains the main renderer");
            SecondaryWorldCamera.withRenderer(secondaryLevel, () -> {
                require(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel) == secondaryLevel, "secondary entity occlusion uses its own Sodium visibility graph");
                SecondaryWorldCamera.withRenderer(otherLevel, () ->
                    require(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel) == otherLevel, "another POV owns its own visibility graph"));
                require(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel) == secondaryLevel, "nested POV restores its caller");
                var workerRenderer = new java.util.concurrent.atomic.AtomicReference<LevelRenderer>();
                Thread worker = Thread.startVirtualThread(() -> workerRenderer.set(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel)));
                try { worker.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
                require(workerRenderer.get() == mainLevel, "secondary Sodium visibility does not leak to background work");
            });
            try { SecondaryWorldCamera.withRenderer(secondaryLevel, () -> { throw new IllegalStateException("failed POV"); }); }
            catch (IllegalStateException expected) { }
            require(SecondaryWorldCamera.effectiveLevelRenderer(mainLevel) == mainLevel, "main Sodium visibility returns after secondary failure");
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
            checkReadOnlyExtraction();
        } catch (ReflectiveOperationException | java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void checkReadOnlyExtraction() throws java.io.IOException {
        int[] visibility = {0}, extraction = {0};
        try (var source = SecondaryLevelRenderer.class.getResourceAsStream("SecondaryLevelRenderer.class")) {
            new ClassReader(java.util.Objects.requireNonNull(source)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("extractEntities")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            require(!(owner.startsWith("net/minecraft/world/entity/") && (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)),
                                "secondary extraction never changes entity position, interpolation, visibility or simulation fields");
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (owner.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher") && name.equals("shouldRender")) visibility[0]++;
                            if (owner.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher") && name.equals("extractEntity")) extraction[0]++;
                            require(!(owner.equals("net/minecraft/client/renderer/LevelRenderer") && name.equals("extractEntity")),
                                "secondary extraction must not reuse EntityCulling's main-camera invisible-state substitution");
                            require(!(owner.equals("net/minecraft/world/entity/Entity") && (name.equals("setViewScale") || name.equals("tick"))),
                                "secondary extraction preserves global view scale and entity simulation");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        require(visibility[0] == 1 && extraction[0] == 1, "real dispatcher visibility and renderer selection remain in the secondary path");
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
