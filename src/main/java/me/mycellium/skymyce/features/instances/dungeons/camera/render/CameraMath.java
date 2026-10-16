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

    public static int[] previewTargetSize(int width, int height, double guiScale) {
        if (!Double.isFinite(guiScale)) throw new IllegalArgumentException("Invalid camera viewport scale");
        return targetSize(width, height, Math.min(guiScale, Math.min(320.0 / width, 180.0 / height)));
    }

    /** Leap previews may together use at most half of the frame time; each gets an equal share. */
    static final int PREVIEW_BUDGET_DIVISOR = 2;

    /**
     * When a preview may render next. Frames advance from the previous due time, so spacing stays even instead of
     * drifting with frame timing; the step slows only to keep all previews within their shared time budget.
     */
    public static long nextPreviewFrame(long due, long now, int framesPerSecond, long renderCost, int activeFeeds) {
        long interval = 1_000_000_000L / Math.clamp(framesPerSecond, 2, 60);
        long budget = Math.min(Math.max(renderCost, 0) * Math.max(activeFeeds, 1) * PREVIEW_BUDGET_DIVISOR, 1_000_000_000L);
        long step = Math.max(interval, budget);
        long next = due + step;
        // A first frame, or a stall longer than one step, restarts the cadence instead of rendering a catch-up burst.
        return due == 0 || next <= now ? now + step : next;
    }
}
