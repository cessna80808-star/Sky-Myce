package me.mycellium.skymyce.hud;

import me.mycellium.skymyce.ui.MenuCanvas;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Isolated Java2D fixture: excludes native layout, actual GPU upload and game scheduling. */
public final class MenuCanvasBenchmark {
    private static final int SAMPLES = 121;
    private static final String[] titles = new String[60];
    private static final String[] details = new String[60];
    private static final String status = "Enabled · custom UI ✓ 30–50";
    private static volatile double sink;
    private static final int[] copyPixels = new int[1920 * 1080];
    private static final IntBuffer nativePixels = ByteBuffer.allocateDirect(copyPixels.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private static long uploadedRevision = -1;
    private static void copy(BufferedImage image, long revision, boolean bulk) {
        if (uploadedRevision == revision) return;
        int[] source;
        if (bulk) {
            source = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < source.length; i++) {
                int argb = source[i];
                copyPixels[i] = (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >>> 16) | ((argb & 255) << 16);
            }
            nativePixels.clear(); nativePixels.put(copyPixels); nativePixels.flip();
        } else {
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), copyPixels, 0, image.getWidth());
            for (int i = 0; i < copyPixels.length; i++) {
                int argb = copyPixels[i];
                nativePixels.put(i, (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >>> 16) | ((argb & 255) << 16));
            }
        }
        uploadedRevision = revision;
    }
    static {
        for (int i = 0; i < titles.length; i++) {
            titles[i] = new String[] {"Dungeon Friends", "Class matching", "Party Commands", "Missing roles", "Friends & loans", "Recent parties"}[i % 6] + " " + i;
            details[i] = "Remember party filters and keep friends visible " + i;
        }
    }
    private static void layout() {
        double total = 0;
        for (int i = 0; i < titles.length; i++) {
            total += MenuCanvas.Companion.textWidth(titles[i], true, false, 10);
            total += MenuCanvas.Companion.textWidth(details[i], false, false, 10);
            total += MenuCanvas.Companion.textWidth(status, false, true, 10);
            total += MenuCanvas.Companion.lineHeight(10);
        }
        sink = total;
    }
    private static BufferedImage frame(MenuCanvas canvas, int tick, int width, int height, double scale) {
        canvas.begin();
        canvas.fill(0, 0, width, height, 0xA5090608);
        canvas.fill(8, 8, width - 16, height - 16, 0xFF201116);
        canvas.fill(8, 8, 165, height - 16, 0xFF10090C);
        canvas.text(List.of(new MenuCanvas.Run("SKY-MYCE", 0xFFF2E9EC, true, false, false)), 22, 25, 14);
        String[] nav = {"Daily Digest", "Party Finder", "Friends & loans", "Dungeons", "Auctions", "Cosmetics", "Settings"};
        for (int i = 0; i < nav.length; i++) canvas.text(List.of(new MenuCanvas.Run(nav[i], 0xFFAF929C, false, false, false)), 22, 65 + i * 32, 10);
        canvas.setClip(new MenuCanvas.Clip(182, 62, width - 192, height - 72));
        double cardWidth = (width - 202) / 2.0;
        for (int i = 0; i < 60; i++) {
            double x = 182 + (i % 2) * (cardWidth + 8), y = 62 + (i / 2) * 62;
            canvas.fill(x, y, cardWidth, 56, i == tick % 14 ? 0xFF382028 : 0xFF29171D);
            canvas.text(List.of(new MenuCanvas.Run(titles[i], 0xFFF2E9EC, true, false, false)), x + 8, y + 5, 10);
            canvas.text(List.of(new MenuCanvas.Run(details[i], 0xFFAF929C, false, false, false)), x + 8, y + 21, 10);
            canvas.text(List.of(new MenuCanvas.Run(status, 0xFFE9A5B4, false, true, true)), x + 8, y + 37, 10);
            canvas.arc(x + cardWidth - 15, y + 13, 5, 0, 360, 0xFFDF8196, 0);
        }
        canvas.setClip(null);
        canvas.fill(186, height - 9, (tick % 100) * 5, 3, 0xFFDF8196);
        return canvas.finish(width, height, scale);
    }
    private static void parity(String label, File directory) throws Exception {
        MenuCanvas canvas = new MenuCanvas();
        ImageIO.write(frame(canvas, 17, 960, 540, 2), "png", new File(directory, label + "-menu.png"));
        ImageIO.write(frame(canvas, 19, 640, 400, 1.5), "png", new File(directory, label + "-resize.png"));
        canvas.begin();
        String[] samples = {"Wi AV office café gjQ", "Русский 中文 日本語 ✓ 🗿 😀", "a\u0301 e\u0301 Ж ✓ 💰", "0123456789 /.,_-"};
        double y = 6;
        for (double size : new double[] {9, 10, 12, 17.25}) for (int style = 0; style < 4; style++) {
            String sample = samples[style];
            canvas.text(List.of(new MenuCanvas.Run(sample, 0xC0F2E9EC, (style & 1) != 0, (style & 2) != 0, true)), 7.25, y, size);
            y += MenuCanvas.Companion.lineHeight(size) + 3;
        }
        canvas.setTransform(new MenuCanvas.Transform(1.05, .07, -.02, .96, 17, 1));
        canvas.setClip(new MenuCanvas.Clip(15, 0, 410, 400));
        canvas.text(List.of(new MenuCanvas.Run("Scale/clip: ✓ Wi 😀", 0x88E9A5B4, true, true, true)), 160.25, 13.5, 12.5);
        ImageIO.write(canvas.finish(460, 430, 2), "png", new File(directory, label + "-glyphs.png"));
    }
    private static double median(long[] values) { Arrays.sort(values); return values[values.length / 2] / 1e6; }
    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "current";
        File directory = new File(args.length > 1 ? args[1] : ".");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create " + directory);
        MenuCanvas canvas = new MenuCanvas();
        for (int i = 0; i < 50; i++) { layout(); frame(canvas, i, 960, 540, 2); }
        long[] measure = new long[SAMPLES], steady = new long[SAMPLES], repaint = new long[SAMPLES];
        frame(canvas, 1, 960, 540, 2);
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime(); layout(); measure[i] = System.nanoTime() - start;
            start = System.nanoTime(); frame(canvas, 1, 960, 540, 2); steady[i] = System.nanoTime() - start;
        }
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime(); frame(canvas, i + 3, 960, 540, 2); repaint[i] = System.nanoTime() - start;
        }
        parity(label, directory);
        System.out.printf(Locale.ROOT, "%s 60 cards 960x540@2, warm median ms: measure %.3f; steady %.3f; changing %.3f; measure+changing %.3f%n", label, median(measure), median(steady), median(repaint), median(measure) + median(repaint));
        System.out.println("Width checksum " + sink);
        if (args.length > 2) {
            boolean bulk = args[2].equals("bulk");
            uploadedRevision = -1;
            MenuCanvas combinedCanvas = new MenuCanvas();
            for (int i = 0; i < 50; i++) {
                BufferedImage image = frame(combinedCanvas, i, 960, 540, 2);
                copy(image, combinedCanvas.getRevision(), bulk);
            }
            long[] combinedSteady = new long[SAMPLES], combinedChanging = new long[SAMPLES];
            copy(frame(combinedCanvas, 1, 960, 540, 2), combinedCanvas.getRevision(), bulk);
            for (int i = 0; i < SAMPLES; i++) {
                long start = System.nanoTime();
                BufferedImage image = frame(combinedCanvas, 1, 960, 540, 2);
                copy(image, combinedCanvas.getRevision(), bulk);
                combinedSteady[i] = System.nanoTime() - start;
            }
            for (int i = 0; i < SAMPLES; i++) {
                long start = System.nanoTime();
                BufferedImage image = frame(combinedCanvas, i + 3, 960, 540, 2);
                copy(image, combinedCanvas.getRevision(), bulk);
                combinedChanging[i] = System.nanoTime() - start;
            }
            System.out.printf(Locale.ROOT, "%s combined raster+ARGB conversion+direct-buffer copy median ms: steady %.3f; changing %.3f%n", label, median(combinedSteady), median(combinedChanging));
        }
    }
}
