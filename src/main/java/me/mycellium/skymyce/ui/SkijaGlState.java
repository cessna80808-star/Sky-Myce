package me.mycellium.skymyce.ui;

import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL21C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL33C.*;

/** Establish Skia's pixel-transfer baseline, then restore real state and Minecraft's matching caches. */
public final class SkijaGlState implements AutoCloseable {
    private static org.lwjgl.opengl.GLCapabilities extensionCaps;
    private static boolean packInvertSupported;
    private static final int[] ENABLES = { GL_BLEND, GL_CULL_FACE, GL_DEPTH_TEST, GL_STENCIL_TEST,
        GL_SCISSOR_TEST, GL_DITHER, GL_MULTISAMPLE, GL_SAMPLE_ALPHA_TO_COVERAGE,
        GL_SAMPLE_ALPHA_TO_ONE, GL_SAMPLE_COVERAGE, GL_FRAMEBUFFER_SRGB, GL_COLOR_LOGIC_OP,
        GL_POLYGON_OFFSET_FILL, GL_POLYGON_OFFSET_LINE, GL_POLYGON_OFFSET_POINT,
        GL_RASTERIZER_DISCARD, GL_PRIMITIVE_RESTART, GL_SAMPLE_MASK, GL_DEPTH_CLAMP };
    private static final int[] PIXEL_STORES = { GL_PACK_ALIGNMENT, GL_UNPACK_ALIGNMENT,
        GL_PACK_ROW_LENGTH, GL_UNPACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_UNPACK_SKIP_PIXELS,
        GL_PACK_SKIP_ROWS, GL_UNPACK_SKIP_ROWS, GL_PACK_IMAGE_HEIGHT, GL_UNPACK_IMAGE_HEIGHT,
        GL_PACK_SKIP_IMAGES, GL_UNPACK_SKIP_IMAGES, GL_PACK_SWAP_BYTES, GL_UNPACK_SWAP_BYTES,
        GL_PACK_LSB_FIRST, GL_UNPACK_LSB_FIRST };
    private final boolean[] enabled = new boolean[ENABLES.length];
    private final int[] pixelStores = new int[PIXEL_STORES.length];
    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
    private final int elementBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
    private final int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
    private final int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int copyReadBuffer = glGetInteger(GL_COPY_READ_BUFFER);
    private final int copyWriteBuffer = glGetInteger(GL_COPY_WRITE_BUFFER);
    private final int indirectBuffer = GL.getCapabilities().OpenGL40 ? glGetInteger(0x8F43) : -1;
    private final int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    // Ganesh allocates/binds units [0, fMaxFragmentSamplers), including its scratch unit.
    private final int[] textures = new int[glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)];
    private final int[] rectangleTextures = new int[textures.length];
    private final int[] samplers = new int[textures.length];
    private final int[] viewport = integers(GL_VIEWPORT, 4);
    private final int[] scissor = integers(GL_SCISSOR_BOX, 4);
    private final boolean[] colorMask = booleans(GL_COLOR_WRITEMASK, 4);
    private final int blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
    private final int blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
    private final int blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int blendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
    private final int blendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final float[] blendColor = floats(GL_BLEND_COLOR, 4);
    private final float[] clearColor = floats(GL_COLOR_CLEAR_VALUE, 4);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
    private final double[] depthRange = doubles(GL_DEPTH_RANGE, 2);
    private final double clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
    private final int clearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE);
    private final int[] frontStencil = stencil(false);
    private final int[] backStencil = stencil(true);
    private final int cullFace = glGetInteger(GL_CULL_FACE_MODE);
    private final int frontFace = glGetInteger(GL_FRONT_FACE);
    private final int polygonMode = integers(GL_POLYGON_MODE, 2)[0];
    private final float polygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR);
    private final float polygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
    private final float lineWidth = glGetFloat(GL_LINE_WIDTH);
    private final boolean sampleInvert = glGetBoolean(GL_SAMPLE_COVERAGE_INVERT);
    private final float sampleCoverage = glGetFloat(GL_SAMPLE_COVERAGE_VALUE);
    private final int logicOp = glGetInteger(GL_LOGIC_OP_MODE);
    private final int primitiveRestartIndex = glGetInteger(GL_PRIMITIVE_RESTART_INDEX);
    private final int[] sampleMasks = new int[glGetInteger(GL_MAX_SAMPLE_MASK_WORDS)];
    private final boolean indexedBlending = GL.getCapabilities().OpenGL40 || GL.getCapabilities().GL_ARB_draw_buffers_blend;
    private final int[][] drawBuffers = new int[glGetInteger(GL_MAX_DRAW_BUFFERS)][];
    private final int invertedPack = packInvertSupported() ? glGetInteger(0x8758) : -1;
    private boolean closed;

    public SkijaGlState() {
        for (int index = 0; index < ENABLES.length; index++) enabled[index] = glIsEnabled(ENABLES[index]);
        for (int index = 0; index < PIXEL_STORES.length; index++) pixelStores[index] = glGetInteger(PIXEL_STORES[index]);
        for (int index = 0; index < sampleMasks.length; index++) sampleMasks[index] = glGetIntegeri(GL_SAMPLE_MASK_VALUE, index);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            for (int index = 0; index < drawBuffers.length; index++) {
                glGetBooleani_v(GL_COLOR_WRITEMASK, index, mask);
                drawBuffers[index] = new int[] { glIsEnabledi(GL_BLEND, index) ? 1 : 0,
                    mask.get(0), mask.get(1), mask.get(2), mask.get(3),
                    indexedBlending ? glGetIntegeri(GL_BLEND_SRC_RGB, index) : blendSrcRgb,
                    indexedBlending ? glGetIntegeri(GL_BLEND_DST_RGB, index) : blendDstRgb,
                    indexedBlending ? glGetIntegeri(GL_BLEND_SRC_ALPHA, index) : blendSrcAlpha,
                    indexedBlending ? glGetIntegeri(GL_BLEND_DST_ALPHA, index) : blendDstAlpha,
                    indexedBlending ? glGetIntegeri(GL_BLEND_EQUATION_RGB, index) : blendEquationRgb,
                    indexedBlending ? glGetIntegeri(GL_BLEND_EQUATION_ALPHA, index) : blendEquationAlpha };
            }
        }
        for (int unit = 0; unit < textures.length; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
            rectangleTextures[unit] = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
            samplers[unit] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(activeTexture);
        // Minecraft sub-image uploads leave source skips set. Ganesh resets row length, not skips;
        // without this baseline glyph-atlas uploads read displaced bytes and scramble every letter.
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        for (int name : PIXEL_STORES) {
            glPixelStorei(name, name == GL_PACK_ALIGNMENT || name == GL_UNPACK_ALIGNMENT ? 1 : 0);
        }
        if (invertedPack >= 0) glPixelStorei(0x8758, 0);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        if (vao != 0) glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        glBindBuffer(GL_COPY_READ_BUFFER, copyReadBuffer);
        glBindBuffer(GL_COPY_WRITE_BUFFER, copyWriteBuffer);
        if (indirectBuffer >= 0) glBindBuffer(0x8F3F, indirectBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        for (int unit = 0; unit < textures.length; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, textures[unit]);
            glBindTexture(GL_TEXTURE_RECTANGLE, rectangleTextures[unit]);
            glBindSampler(unit, samplers[unit]);
        }
        glActiveTexture(activeTexture);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
        glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        glDepthMask(depthMask);
        glDepthFunc(depthFunc);
        glDepthRange(depthRange[0], depthRange[1]);
        glClearDepth(clearDepth);
        glClearStencil(clearStencil);
        restoreStencil(GL_FRONT, frontStencil);
        restoreStencil(GL_BACK, backStencil);
        glCullFace(cullFace);
        glFrontFace(frontFace);
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode);
        glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
        glLineWidth(lineWidth);
        glSampleCoverage(sampleCoverage, sampleInvert);
        glLogicOp(logicOp);
        glPrimitiveRestartIndex(primitiveRestartIndex);
        for (int index = 0; index < sampleMasks.length; index++) glSampleMaski(index, sampleMasks[index]);
        for (int index = 0; index < PIXEL_STORES.length; index++) glPixelStorei(PIXEL_STORES[index], pixelStores[index]);
        if (invertedPack >= 0) glPixelStorei(0x8758, invertedPack);
        for (int index = 0; index < ENABLES.length; index++) {
            if (ENABLES[index] == GL_BLEND) continue;
            if (enabled[index]) glEnable(ENABLES[index]); else glDisable(ENABLES[index]);
        }
        for (int index = 0; index < drawBuffers.length; index++) {
            int[] value = drawBuffers[index];
            if (value[0] != 0) glEnablei(GL_BLEND, index); else glDisablei(GL_BLEND, index);
            glColorMaski(index, value[1] != 0, value[2] != 0, value[3] != 0, value[4] != 0);
            if (GL.getCapabilities().OpenGL40) {
                org.lwjgl.opengl.GL40C.glBlendFuncSeparatei(index, value[5], value[6], value[7], value[8]);
                org.lwjgl.opengl.GL40C.glBlendEquationSeparatei(index, value[9], value[10]);
            } else if (indexedBlending) {
                org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB(index, value[5], value[6], value[7], value[8]);
                org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendEquationSeparateiARB(index, value[9], value[10]);
            }
        }
    }

    private static int[] stencil(boolean back) {
        return new int[] { glGetInteger(back ? GL_STENCIL_BACK_FUNC : GL_STENCIL_FUNC),
            glGetInteger(back ? GL_STENCIL_BACK_REF : GL_STENCIL_REF),
            glGetInteger(back ? GL_STENCIL_BACK_VALUE_MASK : GL_STENCIL_VALUE_MASK),
            glGetInteger(back ? GL_STENCIL_BACK_WRITEMASK : GL_STENCIL_WRITEMASK),
            glGetInteger(back ? GL_STENCIL_BACK_FAIL : GL_STENCIL_FAIL),
            glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_FAIL : GL_STENCIL_PASS_DEPTH_FAIL),
            glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_PASS : GL_STENCIL_PASS_DEPTH_PASS) };
    }
    private static void restoreStencil(int face, int[] value) {
        glStencilFuncSeparate(face, value[0], value[1], value[2]);
        glStencilMaskSeparate(face, value[3]);
        glStencilOpSeparate(face, value[4], value[5], value[6]);
    }
    private static int[] integers(int name, int count) {
        int[] values = new int[count]; glGetIntegerv(name, values); return values;
    }
    private static float[] floats(int name, int count) {
        float[] values = new float[count]; glGetFloatv(name, values); return values;
    }
    private static double[] doubles(int name, int count) {
        double[] values = new double[count]; glGetDoublev(name, values); return values;
    }
    private static boolean[] booleans(int name, int count) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer values = stack.calloc(count);
            glGetBooleanv(name, values);
            boolean[] result = new boolean[count];
            for (int index = 0; index < count; index++) result[index] = values.get(index) != 0;
            return result;
        }
    }
    private static boolean packInvertSupported() {
        var capabilities = GL.getCapabilities();
        if (extensionCaps != capabilities) {
            extensionCaps = capabilities; packInvertSupported = false;
            for (int index = 0, count = glGetInteger(GL_NUM_EXTENSIONS); index < count; index++) {
                if ("GL_MESA_pack_invert".equals(glGetStringi(GL_EXTENSIONS, index))) {
                    packInvertSupported = true; break;
                }
            }
        }
        return packInvertSupported;
    }
}
