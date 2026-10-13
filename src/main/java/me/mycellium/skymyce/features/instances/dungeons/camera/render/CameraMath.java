package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Pure camera math, matching Minecraft's yaw/pitch convention without touching a player. */
public final class CameraMath {
    private CameraMath() { }

    /** Independent world frames carry opaque pixels; only the HUD's explicit opacity controls blending. */
    public static int opaqueColor(int argb) { return argb | 0xFF000000; }

    public static Matrix4f viewRotation(float yaw, float pitch) {
        return new Matrix4f().rotation(new Quaternionf().rotationYXZ(
            (float) Math.PI - (float) Math.toRadians(yaw), -(float) Math.toRadians(pitch), 0).conjugate());
    }

    public static Matrix4f projection(float fov, int width, int height, float far, boolean zeroToOne) {
        if (!Float.isFinite(fov) || fov < 20 || fov > 110 || width < 1 || height < 1 || !Float.isFinite(far) || far <= .05f)
            throw new IllegalArgumentException("Invalid secondary camera projection");
        return new Matrix4f().perspective((float) Math.toRadians(fov), (float) width / height, .05f, far, zeroToOne);
    }

    public static int[] targetSize(int width, int height, double guiScale) {
        if (width < 1 || height < 1 || !Double.isFinite(guiScale) || guiScale <= 0)
            throw new IllegalArgumentException("Invalid camera viewport dimensions");
        double scale = Math.min(guiScale, Math.min(1024.0 / width, 768.0 / height));
        return new int[] {Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }
}
