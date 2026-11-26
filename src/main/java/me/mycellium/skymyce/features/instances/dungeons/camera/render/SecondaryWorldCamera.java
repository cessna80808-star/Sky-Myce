package me.mycellium.skymyce.features.instances.dungeons.camera.render;

import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Minecraft 26.2 rebuilt the world renderer the 26.1.2 secondary camera hooks into, so on 26.2 the
 * camera is not built and every view reports {@link Status#UNAVAILABLE}. Same API as src/main's camera.
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

    private SecondaryWorldCamera() { }

    public static Status status() { return Status.UNAVAILABLE; }
    public static Feed createFeed() { return new Feed(); }
    public static void extract(GuiGraphicsExtractor graphics, View view, int x, int y, int width, int height, float opacity) { }
    public static void prewarm(View view, int width, int height, double guiScale) { }
    public static void stop() { }
    public static void stopAll() { }

    public static final class Feed {
        public void release() { }
        public Status status() { return Status.UNAVAILABLE; }
        public void extract(GuiGraphicsExtractor graphics, View view, int x, int y, int width, int height, float opacity, UUID hiddenEntity) { }
        public void stop() { }
    }
}
