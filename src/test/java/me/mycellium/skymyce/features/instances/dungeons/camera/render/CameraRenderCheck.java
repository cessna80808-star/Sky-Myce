package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class CameraRenderCheck {
    public static void main(String[] args) {
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
        System.out.println("Camera rendering checks passed: independent rotations, Minecraft yaw/pitch, both depth conventions, aspect, target bounds, and invalid views.");
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
