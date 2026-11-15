package dev.dusk.client.render;

import dev.dusk.client.modules.render.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTPackedDepthStencil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The GPU side of {@link MotionBlur} for 1.8.9's legacy GL context, a port of
 * the 1.21.11 pipeline (natural-motionblur's maths). Each pass copies the
 * colour (and for velocity, the depth) of whatever framebuffer the world is
 * drawing into, then draws a full-screen quad with the blur shader back into
 * it. The velocity pass runs once before entities (camera blur on the
 * terrain) and once at the end of the world (blur on what moved by itself);
 * the temporal modes run at the end only. The hand and the HUD come after,
 * so they're never blurred.
 *
 * All GL state touched here is saved with glPushAttrib and put back, so
 * GlStateManager's cache stays right.
 */
public final class MotionBlurRenderer {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");

    /** Taps per pixel at most, as on 1.21.11. */
    private static final int MAX_SAMPLES = 32;
    private static final int MAX_HISTORY = 24;
    private static final int SAMPLE_LIMIT = 12;
    /** Moved further than this in a frame (a teleport, a respawn): no blur that frame. */
    private static final double TELEPORT = 8.0;

    private static boolean broken;
    private static int velocityProgram, blendProgram, maxProgram, mixProgram;
    private static boolean depthUnavailable;

    private static int width, height;
    private static int colorTex, depthTex, prevTex;
    private static int depthFormat;
    private static final int[] history = new int[MAX_HISTORY];
    private static final double[] historyTime = new double[MAX_HISTORY];
    private static int historyWrite, historyFilled;
    private static final int[] sampleIdx = new int[MAX_HISTORY];
    private static final float[] sampleWeight = new float[MAX_HISTORY];
    private static final int[] compactIdx = new int[MAX_HISTORY];
    private static final float[] compactWeight = new float[MAX_HISTORY];
    private static boolean accumHasPrevious;

    // per-frame state
    private static boolean frameStarted;
    private static long lastNano;
    private static float smoothDelta, fps, smoothedBlendFps;
    private static final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer prevModelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer prevProjection = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer mvInverse = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projInverse = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer weights = BufferUtils.createFloatBuffer(SAMPLE_LIMIT);
    private static final Matrix4f scratch = new Matrix4f();
    private static boolean havePrev;
    private static double camX, camY, camZ, prevCamX, prevCamY, prevCamZ;
    private static Entity prevEntity;

    private MotionBlurRenderer() {}

    private static boolean shouldRun(Minecraft mc) {
        MotionBlur m = MotionBlur.instance();
        if (m == null || !m.active() || broken) return false;
        if (!OpenGlHelper.shadersSupported || mc.gameSettings.anaglyph || mc.theWorld == null) return false;
        return !optifineShadersOn();
    }

    /** Before the first entity pass of the world render. */
    public static void prePass(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!shouldRun(mc)) return;
        MotionBlur m = MotionBlur.instance();
        startFrame(mc, partialTicks);
        if (m.usesVelocityBlur()) velocity(mc, m, false);
    }

    /** At the end of the world render (RenderWorldLastEvent), before the hand. */
    public static void postPass(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!shouldRun(mc)) {
            frameStarted = false;
            return;
        }
        MotionBlur m = MotionBlur.instance();
        startFrame(mc, partialTicks);
        try {
            switch (m.algorithm()) {
                case VELOCITY_BASED:
                    velocity(mc, m, true);
                    break;
                case HYBRID_BLENDING:
                    velocity(mc, m, true);
                    frameBlend(m);
                    break;
                case FRAME_BLENDING:
                    frameBlend(m);
                    break;
                case ACCUMULATION_MAX:
                    accumulate(maxProgram(), m.strength() * 7.0f);
                    break;
                case ACCUMULATION_MIX:
                    accumulate(mixProgram(), m.strength() * 5.0f);
                    break;
            }
        } finally {
            endFrame();
        }
    }

    // frame bookkeeping

    private static void startFrame(Minecraft mc, float partialTicks) {
        if (frameStarted) return;
        frameStarted = true;
        long now = System.nanoTime();
        float delta = (now - lastNano) / 1e9f;
        lastNano = now;
        if (delta > 0 && delta < 1.0f) {
            smoothDelta = smoothDelta == 0 ? delta : smoothDelta + (delta - smoothDelta) * 0.1f;
            fps = 1.0f / smoothDelta;
        } else {
            smoothDelta = 0;
            fps = 0;
        }
        if (!ensureTargets(mc)) return;

        modelView.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        projection.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        Entity e = mc.getRenderViewEntity();
        if (e != null) {
            camX = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks;
            camY = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks;
            camZ = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks;
        }
        double dx = camX - prevCamX, dy = camY - prevCamY, dz = camZ - prevCamZ;
        if (!havePrev || e != prevEntity || dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT) {
            copy(modelView, prevModelView);
            copy(projection, prevProjection);
            prevCamX = camX;
            prevCamY = camY;
            prevCamZ = camZ;
            havePrev = true;
        }
        prevEntity = e;
        invert(modelView, mvInverse);
        invert(projection, projInverse);
    }

    private static void endFrame() {
        if (!frameStarted) return;
        frameStarted = false;
        copy(modelView, prevModelView);
        copy(projection, prevProjection);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
    }

    private static int refreshRate() {
        try {
            int hz = Display.getDesktopDisplayMode().getFrequency();
            return hz > 0 ? hz : 60;
        } catch (Throwable t) {
            return 60;
        }
    }

    // velocity

    private static void velocity(Minecraft mc, MotionBlur m, boolean post) {
        if (depthUnavailable || colorTex == 0) return;
        int program = velocityProgram();
        if (program == 0) return;

        float strength;
        int samples;
        int refresh = refreshRate();
        if (m.algorithm() == MotionBlur.Algorithm.HYBRID_BLENDING) {
            strength = hybridVelocityStrength(fps, refresh, m.strength());
            samples = Math.max(100, Math.round(100.0f * strength));
        } else if (m.refreshRateScaling() && m.allowsRefreshRateScaling()) {
            float ratio = refresh > 0 ? fps / refresh : 1.0f;
            if (ratio < 1.0f) ratio = 1.0f;
            strength = m.strength() * ratio;
            samples = ratio > 1.0f ? (int) (100 * ratio) : 100;
        } else {
            strength = m.strength();
            samples = 100;
        }
        samples = Math.min(samples, MAX_SAMPLES);

        begin();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            if (GL11.glGetError() != GL11.GL_NO_ERROR && !retryDepthFormat()) {
                LOG.warn("Motion blur: couldn't read the depth buffer, so velocity blur is off");
                depthUnavailable = true;
                return;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "MainSampler"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "MainDepthSampler"), 1);
            matrix(program, "mvInverse", mvInverse);
            matrix(program, "projInverse", projInverse);
            matrix(program, "prevModelView", prevModelView);
            matrix(program, "prevProjection", prevProjection);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "cameraDelta"),
                    (float) (camX - prevCamX), (float) (camY - prevCamY), (float) (camZ - prevCamZ));
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "view_res"), width, height);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "blendFactor"), strength);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "sampleCount"), samples);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "post"), post ? 1 : 0);
            quad();
        } finally {
            end();
        }
    }

    /** The other depth format (with or without stencil); true when that one copies. */
    private static boolean retryDepthFormat() {
        depthFormat = depthFormat == EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT
                ? GL14.GL_DEPTH_COMPONENT24 : EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT;
        allocDepth();
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        return GL11.glGetError() == GL11.GL_NO_ERROR;
    }

    // frame blending (the temporal half of natural-motionblur)

    private static void updateBlendFps() {
        if (fps > 0) smoothedBlendFps = smoothedBlendFps <= 0 ? fps : smoothedBlendFps * 0.85f + fps * 0.15f;
    }

    private static float hybridVelocityStrength(float fps, int refreshRate, float strength) {
        float base = Math.max(0.0f, strength);
        if (base == 0.0f || fps <= 0.0f || refreshRate <= 0) return base;
        float referenceRate = Math.min(fps, (float) refreshRate);
        float filler = Math.min(1.0f, base * (fps / referenceRate));

        float blendFps = smoothedBlendFps <= 0.0f ? fps : smoothedBlendFps * 0.85f + fps * 0.15f;
        float blendSpan = base * (blendFps / Math.min(blendFps, (float) refreshRate));
        int count = Math.min(MAX_HISTORY, (int) Math.ceil(blendSpan - 0.000001f));
        count = Math.min(count, Math.min(MAX_HISTORY, historyFilled + 1));
        if (count > SAMPLE_LIMIT) {
            int maxGap = 1, previous = 0;
            for (int out = 1; out < SAMPLE_LIMIT; out++) {
                int source = Math.round((float) out * (count - 1) / (SAMPLE_LIMIT - 1));
                maxGap = Math.max(maxGap, source - previous);
                previous = source;
            }
            filler = Math.max(filler, maxGap);
        }
        return filler;
    }

    private static void frameBlend(MotionBlur m) {
        updateBlendFps();
        int refresh = refreshRate();
        float strength = m.strength();
        if (strength <= 0) {
            historyWrite = historyFilled = 0;
            return;
        }
        int program = blendProgram();
        if (program == 0 || colorTex == 0) return;
        ensureHistory();

        double now = System.nanoTime() * 1.0e-9;
        begin();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, history[historyWrite]);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            historyTime[historyWrite] = now;
            historyWrite = (historyWrite + 1) % MAX_HISTORY;
            if (historyFilled < MAX_HISTORY) historyFilled++;

            int count = buildSamples(now, refresh, smoothedBlendFps, strength);
            if (count <= 1) return;

            float total = 0;
            for (int i = 0; i < count; i++) total += sampleWeight[i];
            weights.clear();
            for (int i = 0; i < SAMPLE_LIMIT; i++) weights.put(i < count ? sampleWeight[i] : 0f);
            weights.flip();

            GL20.glUseProgram(program);
            int fallback = history[sampleIdx[count - 1]];
            for (int i = 0; i < SAMPLE_LIMIT; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, i < count ? history[sampleIdx[i]] : fallback);
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sample" + i), i);
            }
            GL20.glUniform1(GL20.glGetUniformLocation(program, "weights"), weights);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "invTotalWeight"), total > 0 ? 1f / total : 1f);
            quad();
        } finally {
            end();
        }
    }

    private static int buildSamples(double exposureEnd, int refreshRate, float fps, float strength) {
        Arrays.fill(sampleWeight, 0f);
        if (historyFilled <= 0) return 0;
        double referenceRate = fps > 0 ? Math.min(fps, (double) refreshRate) : refreshRate;
        double exposureStart = exposureEnd - Math.max(0.0, strength) / referenceRate;
        double frameTime = fps > 0 ? 1.0 / fps : 1.0 / refreshRate;
        double totalWeight = 0;
        int count = 0;
        int first = historyWrite - historyFilled;
        if (first < 0) first += MAX_HISTORY;
        for (int i = 0; i < historyFilled; i++) {
            int idx = (first + i) % MAX_HISTORY;
            double frameEnd = historyTime[idx];
            if (frameEnd <= 0) continue;
            double frameStart = i > 0 ? historyTime[(first + i - 1) % MAX_HISTORY] : frameEnd - frameTime;
            if (frameStart >= frameEnd) frameStart = frameEnd - frameTime;
            double overlap = Math.min(frameEnd, exposureEnd) - Math.max(frameStart, exposureStart);
            if (overlap > 0.0000001) {
                sampleIdx[count] = idx;
                sampleWeight[count] = (float) overlap;
                totalWeight += overlap;
                count++;
            }
        }
        if (count <= 0 || totalWeight <= 0.0000001) return 0;
        return count > SAMPLE_LIMIT ? compact(count) : count;
    }

    /** More frames in the window than samplers: an even spread, with the rest of the weight folded in. */
    private static int compact(int count) {
        Arrays.fill(compactWeight, 0f);
        for (int out = 0; out < SAMPLE_LIMIT; out++) {
            compactIdx[out] = sampleIdx[Math.round((float) out * (count - 1) / (SAMPLE_LIMIT - 1))];
        }
        for (int source = 0; source < count; source++) {
            int out = Math.round((float) source * (SAMPLE_LIMIT - 1) / (count - 1));
            compactWeight[Math.max(0, Math.min(SAMPLE_LIMIT - 1, out))] += sampleWeight[source];
        }
        for (int i = 0; i < SAMPLE_LIMIT; i++) {
            sampleIdx[i] = compactIdx[i];
            sampleWeight[i] = compactWeight[i];
        }
        for (int i = SAMPLE_LIMIT; i < count; i++) sampleWeight[i] = 0f;
        return SAMPLE_LIMIT;
    }

    // accumulation

    private static void accumulate(int program, float strength) {
        if (program == 0 || colorTex == 0) return;
        if (prevTex == 0) prevTex = newColorTexture();
        begin();
        try {
            if (!accumHasPrevious) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
                accumHasPrevious = true;
                return;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "MainSampler"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "PrevSampler"), 1);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "blendFactor"),
                    (float) (1.0 - Math.pow(0.5, strength / 3.0)));
            quad();
            // the blended frame is next frame's previous
            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        } finally {
            end();
        }
    }

    // GL plumbing

    private static int savedProgram;

    private static void begin() {
        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_VIEWPORT_BIT | GL11.GL_TRANSFORM_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColorMask(true, true, true, true);
        GL11.glViewport(0, 0, width, height);
    }

    private static void end() {
        GL20.glUseProgram(savedProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glPopAttrib();
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1, -1);
        GL11.glVertex2f(1, -1);
        GL11.glVertex2f(1, 1);
        GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }

    private static void matrix(int program, String name, FloatBuffer m) {
        m.rewind();
        GL20.glUniformMatrix4(GL20.glGetUniformLocation(program, name), false, m);
        m.rewind();
    }

    private static void copy(FloatBuffer from, FloatBuffer to) {
        from.rewind();
        to.clear();
        to.put(from);
        from.rewind();
        to.flip();
    }

    private static void invert(FloatBuffer from, FloatBuffer to) {
        from.rewind();
        scratch.load(from);
        from.rewind();
        if (Matrix4f.invert(scratch, scratch) == null) scratch.setIdentity();
        to.clear();
        scratch.store(to);
        to.flip();
    }

    /** The size of whatever the world is drawn into; reallocates textures when it changes. */
    private static boolean ensureTargets(Minecraft mc) {
        int w, h;
        Framebuffer fb = mc.getFramebuffer();
        if (OpenGlHelper.isFramebufferEnabled() && fb != null) {
            w = fb.framebufferWidth;
            h = fb.framebufferHeight;
        } else {
            w = mc.displayWidth;
            h = mc.displayHeight;
        }
        if (w <= 0 || h <= 0) return false;
        if (w == width && h == height && colorTex != 0) return true;
        release();
        width = w;
        height = h;
        colorTex = newColorTexture();
        depthTex = GL11.glGenTextures();
        if (depthFormat == 0) {
            depthFormat = fb != null && fb.isStencilEnabled()
                    ? EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT : GL14.GL_DEPTH_COMPONENT24;
        }
        allocDepth();
        return true;
    }

    private static void allocDepth() {
        int saved = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        params();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        boolean packed = depthFormat == EXTPackedDepthStencil.GL_DEPTH24_STENCIL8_EXT;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depthFormat, width, height, 0,
                packed ? EXTPackedDepthStencil.GL_DEPTH_STENCIL_EXT : GL11.GL_DEPTH_COMPONENT,
                packed ? EXTPackedDepthStencil.GL_UNSIGNED_INT_24_8_EXT : GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, saved);
    }

    private static int newColorTexture() {
        int saved = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        params();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, saved);
        return tex;
    }

    private static void params() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12Clamp.EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Clamp.EDGE);
    }

    private static final class GL12Clamp {
        static final int EDGE = org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
    }

    private static void ensureHistory() {
        if (history[0] != 0) return;
        for (int i = 0; i < MAX_HISTORY; i++) history[i] = newColorTexture();
        Arrays.fill(historyTime, 0);
        historyWrite = historyFilled = 0;
    }

    /** Frees every texture (the module turned off, or the window was resized). */
    public static void release() {
        if (colorTex != 0) GL11.glDeleteTextures(colorTex);
        if (depthTex != 0) GL11.glDeleteTextures(depthTex);
        if (prevTex != 0) GL11.glDeleteTextures(prevTex);
        colorTex = depthTex = prevTex = 0;
        for (int i = 0; i < MAX_HISTORY; i++) {
            if (history[i] != 0) GL11.glDeleteTextures(history[i]);
            history[i] = 0;
        }
        historyWrite = historyFilled = 0;
        accumHasPrevious = false;
        smoothedBlendFps = 0;
        width = height = 0;
        havePrev = false;
        frameStarted = false;
    }

    // shaders

    private static int velocityProgram() {
        if (velocityProgram == 0) velocityProgram = program("velocity.fsh");
        return velocityProgram;
    }

    private static int blendProgram() {
        if (blendProgram == 0) blendProgram = program("frame_blend.fsh");
        return blendProgram;
    }

    private static int maxProgram() {
        if (maxProgram == 0) maxProgram = program("accum_max.fsh");
        return maxProgram;
    }

    private static int mixProgram() {
        if (mixProgram == 0) mixProgram = program("accum_mix.fsh");
        return mixProgram;
    }

    private static int program(String fragment) {
        try {
            int vs = shader(GL20.GL_VERTEX_SHADER, "blit.vsh");
            int fs = shader(GL20.GL_FRAGMENT_SHADER, fragment);
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(p, 4096));
            }
            return p;
        } catch (Exception e) {
            LOG.error("Motion blur: the " + fragment + " shader failed, so motion blur is off", e);
            broken = true;
            return 0;
        }
    }

    private static int shader(int type, String name) throws Exception {
        InputStream in = MotionBlurRenderer.class.getResourceAsStream("/assets/duskclient/shaders/" + name);
        if (in == null) throw new IllegalStateException("missing " + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, new String(out.toByteArray(), "UTF-8"));
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(s, 4096);
            GL20.glDeleteShader(s);
            throw new IllegalStateException(name + ": " + log);
        }
        return s;
    }

    // OptiFine shader packs draw the world their own way; blur would fight them.

    private static Boolean optifinePresent;
    private static java.lang.reflect.Field shaderPackLoaded;

    private static boolean optifineShadersOn() {
        if (optifinePresent == null) {
            try {
                shaderPackLoaded = Class.forName("shadersmod.client.Shaders").getField("shaderPackLoaded");
                optifinePresent = Boolean.TRUE;
            } catch (Throwable t) {
                optifinePresent = Boolean.FALSE;
            }
        }
        if (!optifinePresent) return false;
        try {
            return shaderPackLoaded.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }
}
