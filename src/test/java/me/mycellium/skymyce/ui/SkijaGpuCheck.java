package me.mycellium.skymyce.ui;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.Rect;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.*;

/** Explicit GPU check, outside ordinary headless check: never touches an existing Minecraft process. */
public final class SkijaGpuCheck {
    public static void main(String[] args) throws Exception {
        try (var error = GLFWErrorCallback.createPrint(System.err).set()) {
            check(glfwInit(), "GLFW initialization");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            long window = glfwCreateWindow(64, 64, "Sky-Myce GPU verification", 0, 0);
            check(window != 0, "Hidden OpenGL context creation");
            try {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                verify(Arrays.asList(args).contains("--benchmark"));
            } finally {
                GL.setCapabilities(null);
                glfwDestroyWindow(window);
                glfwTerminate();
            }
        }
    }

    private static void verify(boolean benchmark) throws Exception {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        int stencil = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, stencil);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 64, 64);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencil);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Owned framebuffer");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int buffer = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        int sampler = glGenSamplers();
        glBindSampler(3, sampler);
        glActiveTexture(GL_TEXTURE0 + 3);
        glBindTexture(GL_TEXTURE_2D, texture);
        glEnable(GL_BLEND); glBlendFuncSeparate(GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA, GL_ONE, GL_ZERO);
        glBlendEquationSeparate(GL_FUNC_SUBTRACT, GL_FUNC_REVERSE_SUBTRACT);
        glEnable(GL_DEPTH_TEST); glDepthMask(false); glDepthFunc(GL_GREATER); glDepthRange(.2, .8);
        glEnable(GL_CULL_FACE); glCullFace(GL_FRONT); glFrontFace(GL_CW);
        glEnable(GL_SCISSOR_TEST); glScissor(2, 3, 4, 5); glViewport(3, 4, 45, 46);
        glColorMask(true, false, true, false);
        glColorMaski(1, false, true, false, true); glDisablei(GL_BLEND, 0); glEnablei(GL_BLEND, 1);
        if (GL.getCapabilities().GL_ARB_draw_buffers_blend) {
            org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB(1, GL_ZERO, GL_ONE, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 8); glPixelStorei(GL_UNPACK_ROW_LENGTH, 7);
        Map<String, String> expected = state();
        DirectContext context;
        try (var restore = new SkijaGlState()) { context = DirectContext.makeGL(); }
        checkState(expected, "Context initialization restoration");
        try (context; var target = BackendRenderTarget.makeGL(64, 64, 0, 8, fbo, GL_RGBA8);
             var colorSpace = ColorSpace.getSRGB();
             var paint = new Paint().setColor(0x80FF0000)) {
            Surface created;
            try (var restore = new SkijaGlState()) {
                created = Surface.wrapBackendRenderTarget(context, target, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, colorSpace);
            }
            checkState(expected, "Surface initialization restoration");
            try (var surface = created) {
            for (int repeat = 0; repeat < 10; repeat++) {
                try (var restore = new SkijaGlState()) {
                    context.resetGLAll();
                    surface.getCanvas().clear(0);
                    surface.getCanvas().drawRect(Rect.makeXYWH(0, 0, 32, 16), paint);
                    context.flushAndSubmit(surface, true);
                }
                checkState(expected, "Drawing restores GL state on repeat " + repeat);
            }
            try {
                try (var restore = new SkijaGlState()) {
                    context.resetGLAll();
                    glViewport(0, 0, 64, 64); glDisable(GL_DEPTH_TEST);
                    throw new IllegalStateException("Expected simulated paint failure");
                }
            } catch (IllegalStateException failure) {
                check("Expected simulated paint failure".equals(failure.getMessage()), "Expected paint failure");
            }
            checkState(expected, "Failed draw restores GL state");
            try (var restore = new SkijaGlState(); var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                var pixel = stack.malloc(4);
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                check(Math.abs((pixel.get(0) & 255) - 128) <= 1 && pixel.get(1) == 0 && pixel.get(2) == 0 && (pixel.get(3) & 255) == 128,
                    "Premultiplied red at texture V=0 matches Minecraft GUI orientation");
                glReadPixels(4, 60, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                check(pixel.get(3) == 0, "Bottom of texture remains transparent");
            }
            checkState(expected, "Readback restores GL state");
            check(glGetError() == GL_NO_ERROR, "No GL errors");
            System.out.println("Skija GPU checks passed: native OpenGL, premultiplied alpha/orientation, repeated drawing and exception-safe full state restoration; " + glGetString(GL_RENDERER));
            poisonedTransfers(fbo, expected);
            productionPainter(context, surface, fbo, expected);
            if (benchmark) benchmark(context);
            }
        } finally {
            glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0);
            glDeleteBuffers(buffer); glDeleteVertexArrays(vao); glDeleteSamplers(sampler);
            glDeleteFramebuffers(fbo); glDeleteRenderbuffers(stencil); glDeleteTextures(texture);
        }
    }

    private static void poisonedTransfers(int fbo, Map<String, String> expected) throws Exception {
        SkijaMenuFonts.INSTANCE.setActive(true);
        try {
            byte[] clean = textPixels(fbo, false);
            byte[] poisoned = textPixels(fbo, true);
            int changed = 0, visible = 0;
            for (int index = 0; index < clean.length; index += 4) {
                if (clean[index] != 0) visible++;
                if (clean[index] != poisoned[index]) changed++;
            }
            check(visible > 20, "Reference native text contains visible glyph pixels");
            check(changed == 0, "Native glyph atlas ignores incoming pixel-transfer offsets: " + changed + " altered pixels");
            checkState(expected, "Poisoned pixel-transfer regression restores caller GL state");
            System.out.println("Skija glyph-atlas transfer check passed: clean and poisoned skips/row lengths/image heights/PBOs produce identical text and restore caller state");
        } finally {
            try (var restore = new SkijaGlState()) { SkijaMenuPainter.INSTANCE.close(); }
        }
    }

    /** A new native context ensures the second draw uploads glyphs instead of reusing an existing atlas. */
    private static byte[] textPixels(int fbo, boolean poison) throws Exception {
        MenuCanvas canvas = new MenuCanvas();
        try (var restore = new SkijaGlState()) {
            int unpack = glGenBuffers(), pack = glGenBuffers();
            try (var context = DirectContext.makeGL();
                 var backend = BackendRenderTarget.makeGL(64, 64, 0, 8, fbo, GL_RGBA8);
                 var colorSpace = ColorSpace.getSRGB();
                 var surface = Surface.wrapBackendRenderTarget(context, backend, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, colorSpace)) {
                if (poison) {
                    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack); glBufferData(GL_PIXEL_UNPACK_BUFFER, 1 << 20, GL_STREAM_DRAW);
                    glBindBuffer(GL_PIXEL_PACK_BUFFER, pack); glBufferData(GL_PIXEL_PACK_BUFFER, 1 << 20, GL_STREAM_READ);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 8); glPixelStorei(GL_UNPACK_ROW_LENGTH, 256);
                    glPixelStorei(GL_UNPACK_SKIP_ROWS, 1); glPixelStorei(GL_UNPACK_SKIP_PIXELS, 2);
                    glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 512); glPixelStorei(GL_UNPACK_SKIP_IMAGES, 3);
                    glPixelStorei(GL_UNPACK_SWAP_BYTES, 1); glPixelStorei(GL_UNPACK_LSB_FIRST, 1);
                    glPixelStorei(GL_PACK_ALIGNMENT, 8); glPixelStorei(GL_PACK_ROW_LENGTH, 256);
                    glPixelStorei(GL_PACK_SKIP_ROWS, 2); glPixelStorei(GL_PACK_SKIP_PIXELS, 3);
                    glPixelStorei(GL_PACK_IMAGE_HEIGHT, 512); glPixelStorei(GL_PACK_SKIP_IMAGES, 1);
                    glPixelStorei(GL_PACK_SWAP_BYTES, 1); glPixelStorei(GL_PACK_LSB_FIRST, 1);
                }
                Map<String, String> incoming = state();
                canvas.begin(); canvas.fill(0, 0, 64, 64, 0xFF000000);
                canvas.text(java.util.List.of(new MenuCanvas.Run("Sky-Myce", 0xFFFFFFFF, false, false, false)), 2, 2, 12);
                canvas.text(java.util.List.of(new MenuCanvas.Run("Glyphs 0123", 0xFFFFFFFF, true, false, false)), 2, 25, 9);
                drawBenchmark(context, surface, canvas.finishFrame(64, 64, 1));
                try (var drawRestore = new SkijaGlState()) { context.flushAndSubmit(true); }
                checkState(incoming, "Native text preserves poisoned transfer state");
                try (var readRestore = new SkijaGlState()) {
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo); glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                    glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_PACK_ROW_LENGTH, 0);
                    glPixelStorei(GL_PACK_SKIP_ROWS, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
                    var pixels = org.lwjgl.BufferUtils.createByteBuffer(64 * 64 * 4);
                    glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    byte[] bytes = new byte[pixels.capacity()]; pixels.get(bytes); return bytes;
                }
            } finally {
                canvas.close(); glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                glDeleteBuffers(pack); glDeleteBuffers(unpack);
            }
        }
    }

    /** Keep the owned context explicit: Surface.getRecordingContext creates an owning wrapper for a borrowed pointer. */
    private static void productionPainter(DirectContext context, Surface surface, int fbo, Map<String, String> expected) throws Exception {
        MenuCanvas canvas = new MenuCanvas();
        MenuCanvas.Snapshot snapshot = null;
        SkijaMenuFonts.INSTANCE.setActive(true);
        try {
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xFFFFFF00);
            canvas.begin();
            canvas.fill(0, 0, 64, 64, 0x806080A0);
            canvas.gradient(4, 4, 24, 24, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF);
            canvas.shadow(34, 4, 24, 24, 6, 2, 0x40000000);
            canvas.roundedRect(34, 4, 24, 24, 6, 0xFFFFFFFF);
            canvas.roundedBorder(34, 4, 24, 24, 6, 1, 0xFF000000);
            canvas.text(java.util.List.of(new MenuCanvas.Run("A \u2713", 0xFFFFFFFF, true, true, true)), 4, 30, 8);
            canvas.picture(image, 4, 44, 12, 12);
            drawBenchmark(context, surface, canvas.finishFrame(64, 64, 1));
            try (var restore = new SkijaGlState()) { context.flushAndSubmit(true); }
            checkState(expected, "Production primitives restore GL state");
            int[] gradientStart = pixel(fbo, 7, 7), gradientEnd = pixel(fbo, 25, 25);
            check(gradientStart[3] == 255 && gradientEnd[3] == 255 && !Arrays.equals(gradientStart, gradientEnd),
                "Four-corner gradient executes its GPU shader");
            check(pixel(fbo, 45, 15)[3] == 255 && pixel(fbo, 34, 4)[3] < 240,
                "Rounded shape keeps its opaque interior and rounded corner");
            check(Arrays.equals(pixel(fbo, 8, 48), new int[] {255, 255, 0, 255}), "Uploaded image draws without cropping or channel swaps");

            canvas.begin();
            canvas.fill(0, 0, 64, 64, 0x804070A0);
            canvas.text(java.util.List.of(new MenuCanvas.Run("A \u2713", 0xFFFFFFFF, false, false, false)), 4, 4, 8);
            drawBenchmark(context, surface, canvas.finishFrame(64, 64, 1));
            snapshot = canvas.snapshot(null);
            check(snapshot != null, "Initial native snapshot");
            try (var restore = new SkijaGlState()) {
                context.resetGLAll(); SkijaMenuPainter.INSTANCE.prepareSnapshots(context); context.flushAndSubmit(true);
            }
            for (int iteration = 0; iteration < 20; iteration++) {
                final int color = (iteration & 1) == 0 ? 0x8090A080 : 0x804070A0;
                canvas.begin();
                canvas.transition(snapshot, .35, new MenuCanvas.Clip(0, 0, 64, 64), 0, () -> {
                    canvas.fill(0, 0, 64, 64, color);
                    canvas.text(java.util.List.of(new MenuCanvas.Run("A \u2713", 0xFFFFFFFF, false, false, false)), 4, 4, 8);
                    return kotlin.Unit.INSTANCE;
                });
                MenuCanvas.Frame frame = canvas.finishFrame(64, 64, 1);
                snapshot.close(); // Retired resources must survive this already-extracted frame.
                drawBenchmark(context, surface, frame);
                snapshot = canvas.snapshot(null);
                check(snapshot != null, "Interrupted native snapshot " + iteration);
                try (var restore = new SkijaGlState()) {
                    context.resetGLAll();
                    SkijaMenuPainter.INSTANCE.prepareSnapshots(context); // Flatten before the old image retires.
                    context.flushAndSubmit(true); SkijaMenuPainter.INSTANCE.releaseRetired();
                }
                System.gc(); // Exercise native wrapper finalization without ever retrieving a borrowed context wrapper.
                checkState(expected, "Interrupted native transition restores GL state " + iteration);
                check(Math.abs(pixel(fbo, 60, 60)[3] - 128) <= 2, "Interrupted transitions retain panel alpha " + iteration);
                var sizes = SkijaMenuPainter.INSTANCE.cacheSizes();
                check(sizes.get("snapshots.pending") == 0 && sizes.get("snapshots.live") == 1,
                    "Interrupted transitions retain one flattened native snapshot " + iteration);
                check(sizes.get("images") <= 64 && sizes.get("gradients") <= 32 && sizes.get("blurs") <= 8,
                    "Production painter resource caches stay bounded " + iteration);
                check(glGetError() == GL_NO_ERROR, "Production primitives leave no GL errors " + iteration);
            }
            System.out.println("Skija production GPU checks passed: gradients, rounded shapes/shadows, images/text and 20 interrupted snapshots with forced GC, constant alpha, bounded resources and restored GL state");
        } finally {
            if (snapshot != null) snapshot.close();
            canvas.close();
            try (var restore = new SkijaGlState()) {
                context.flushAndSubmit(true); SkijaMenuPainter.INSTANCE.releaseRetired(); SkijaMenuPainter.INSTANCE.close();
            }
            SkijaMenuFonts.INSTANCE.setActive(false);
        }
    }

    private static int[] pixel(int fbo, int x, int y) {
        try (var restore = new SkijaGlState(); var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo); glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0); glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            var value = stack.malloc(4); glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, value);
            return new int[] {value.get(0) & 255, value.get(1) & 255, value.get(2) & 255, value.get(3) & 255};
        }
    }

    private static void benchmark(DirectContext context) {
        int texture, fbo, stencil;
        Surface created;
        BackendRenderTarget backend;
        try (var restore = new SkijaGlState()) {
            texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1920, 1080, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            fbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            stencil = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, stencil);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 1920, 1080);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencil);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Benchmark framebuffer");
            backend = BackendRenderTarget.makeGL(1920, 1080, 0, 8, fbo, GL_RGBA8);
            try (var colorSpace = ColorSpace.getSRGB()) {
                created = Surface.wrapBackendRenderTarget(context, backend, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, colorSpace);
            }
        }
        MenuCanvas canvas = new MenuCanvas();
        SkijaMenuFonts.INSTANCE.setActive(true);
        try (var target = backend; var surface = created) {
            for (int warmup = 0; warmup < 50; warmup++) drawBenchmark(context, surface, scene(canvas, warmup));
            long[] changing = new long[121], idle = new long[121], gpu = new long[121];
            int[] queries = new int[121];
            for (int index = 0; index < changing.length; index++) {
                queries[index] = glGenQueries();
                long start = System.nanoTime();
                glBeginQuery(GL_TIME_ELAPSED, queries[index]);
                drawBenchmark(context, surface, scene(canvas, index + 3));
                glEndQuery(GL_TIME_ELAPSED);
                changing[index] = System.nanoTime() - start;
            }
            context.flushAndSubmit(true);
            for (int index = 0; index < queries.length; index++) {
                gpu[index] = glGetQueryObjectui64(queries[index], GL_QUERY_RESULT); glDeleteQueries(queries[index]);
            }
            MenuCanvas.Frame last = scene(canvas, 1);
            drawBenchmark(context, surface, last);
            for (int index = 0; index < idle.length; index++) {
                long start = System.nanoTime();
                MenuCanvas.Frame frame = scene(canvas, 1);
                check(frame == last, "Unchanged native frame reuses retained commands");
                idle[index] = System.nanoTime() - start;
            }
            report("Skija 60 cards 960x540@2 CPU including commands/state/draw/submit", changing);
            report("Skija isolated GPU elapsed (texture paint only)", gpu);
            report("Skija retained idle CPU (no repaint/submit)", idle);
        } finally {
            canvas.close(); SkijaMenuPainter.INSTANCE.close(); SkijaMenuFonts.INSTANCE.setActive(false);
            glDeleteFramebuffers(fbo); glDeleteRenderbuffers(stencil); glDeleteTextures(texture);
        }
    }

    private static void drawBenchmark(DirectContext context, Surface surface, MenuCanvas.Frame frame) {
        try (var restore = new SkijaGlState()) {
            context.resetGLAll();
            surface.getCanvas().clear(0);
            SkijaMenuPainter.INSTANCE.draw(surface.getCanvas(), frame, context);
            context.flushAndSubmit(surface, false);
        }
    }

    private static MenuCanvas.Frame scene(MenuCanvas canvas, int tick) {
        canvas.begin();
        canvas.fill(0, 0, 960, 540, 0xA5090608); canvas.fill(8, 8, 944, 524, 0xFF201116);
        canvas.fill(8, 8, 165, 524, 0xFF10090C);
        canvas.text(java.util.List.of(new MenuCanvas.Run("SKY-MYCE", 0xFFF2E9EC, true, false, false)), 22, 25, 14);
        String[] nav = {"Daily Digest", "Party Finder", "Friends & loans", "Dungeons", "Auctions", "Cosmetics", "Settings"};
        for (int index = 0; index < nav.length; index++)
            canvas.text(java.util.List.of(new MenuCanvas.Run(nav[index], 0xFFAF929C, false, false, false)), 22, 65 + index * 32, 10);
        canvas.setClip(new MenuCanvas.Clip(182, 62, 768, 468));
        String[] titles = {"Dungeon Friends", "Class matching", "Party Commands", "Missing roles", "Friends & loans", "Recent parties"};
        for (int index = 0; index < 60; index++) {
            double x = 182 + (index % 2) * 387, y = 62 + (index / 2) * 62;
            canvas.fill(x, y, 379, 56, index == tick % 14 ? 0xFF382028 : 0xFF29171D);
            canvas.text(java.util.List.of(new MenuCanvas.Run(titles[index % 6] + " " + index, 0xFFF2E9EC, true, false, false)), x + 8, y + 5, 10);
            canvas.text(java.util.List.of(new MenuCanvas.Run("Remember party filters and keep friends visible " + index, 0xFFAF929C, false, false, false)), x + 8, y + 21, 10);
            canvas.text(java.util.List.of(new MenuCanvas.Run("Enabled · custom UI ✓ 30–50", 0xFFE9A5B4, false, true, true)), x + 8, y + 37, 10);
            canvas.arc(x + 364, y + 13, 5, 0, 360, 0xFFDF8196, 0);
        }
        canvas.setClip(null); canvas.fill(186, 531, (tick % 100) * 5, 3, 0xFFDF8196);
        return canvas.finishFrame(960, 540, 2);
    }

    private static void report(String label, long[] times) {
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT, "%s median %.3f ms; p95 %.3f ms%n", label, times[times.length / 2] / 1e6,
            times[(int) (times.length * .95)] / 1e6);
    }

    private static Map<String, String> state() throws Exception {
        try (var captured = new SkijaGlState()) {
            Map<String, String> state = new LinkedHashMap<>();
            for (Field field : SkijaGlState.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getName().equals("closed")) continue;
                field.setAccessible(true);
                Object value = field.get(captured);
                String text = value instanceof int[] array ? Arrays.toString(array) :
                    value instanceof boolean[] array ? Arrays.toString(array) :
                    value instanceof float[] array ? Arrays.toString(array) :
                    value instanceof double[] array ? Arrays.toString(array) :
                    value instanceof Object[] array ? Arrays.deepToString(array) : String.valueOf(value);
                state.put(field.getName(), text);
            }
            return state;
        }
    }

    private static void checkState(Map<String, String> expected, String label) throws Exception {
        Map<String, String> actual = state();
        for (var entry : expected.entrySet()) check(entry.getValue().equals(actual.get(entry.getKey())),
            label + ": " + entry.getKey() + " expected " + entry.getValue() + " actual " + actual.get(entry.getKey()));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
