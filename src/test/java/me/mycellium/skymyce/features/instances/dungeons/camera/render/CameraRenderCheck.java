package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class CameraRenderCheck {
    public static void main(String[] args) {
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
        System.out.println("Camera rendering checks passed: scoped render distance and terrain projection, independent rotations, Minecraft yaw/pitch, both depth conventions, aspect, target bounds, and invalid views.");
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
