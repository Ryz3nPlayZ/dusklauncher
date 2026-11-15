package dev.dusk.client.media;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

import java.nio.ByteBuffer;

/**
 * Reads frames back from the GPU without stalling the game: each grab goes
 * into a pixel buffer object and is copied out a frame or two later, once
 * the GPU says it is done, so the render thread never waits for the pixels.
 * When the video is smaller than the window the frame is shrunk on the GPU
 * first, so only video-sized frames cross to the encoder.
 *
 * Render thread only. The pack buffer binding is always left empty: vanilla
 * reads screenshots with it unbound.
 */
final class FrameGrab {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");
    private static final int RING = 3;
    private static final int NONE = 0, GL30_FBO = 1, ARB_FBO = 2;

    /** Set when shrinking on the GPU failed here; the next session lets ffmpeg shrink instead. */
    static boolean blitBroken;

    private final int srcW, srcH, w, h, bytes;
    private final int scaleMode;
    private final boolean fences, fences32;
    private final int[] pbo = new int[RING];
    private final long[] slots = new long[RING];
    private final int[] age = new int[RING];
    private final GLSync[] fence = new GLSync[RING];
    private int head, count;
    private ByteBuffer mapped;
    private int halfFbo, halfRb, halfW, halfH, outFbo, outRb;
    private boolean scaleChecked, failed, released;

    /** Whether frames can be read back here at all. */
    static boolean supported() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.OpenGL21;
    }

    /** Whether a frame can be shrunk on the GPU here. */
    static boolean canScale() {
        return !blitBroken && scaleMode(GLContext.getCapabilities()) != NONE;
    }

    private static int scaleMode(ContextCapabilities caps) {
        return caps.OpenGL30 ? GL30_FBO : caps.GL_ARB_framebuffer_object ? ARB_FBO : NONE;
    }

    /**
     * @param srcW the window, in pixels
     * @param w    the frames to hand over; smaller than the window means a GPU shrink
     */
    FrameGrab(int srcW, int srcH, int w, int h) {
        ContextCapabilities caps = GLContext.getCapabilities();
        this.srcW = srcW;
        this.srcH = srcH;
        this.w = w;
        this.h = h;
        this.bytes = w * h * 4;
        boolean scaled = w != (srcW & ~1) || h != (srcH & ~1);
        this.scaleMode = scaled ? scaleMode(caps) : NONE;
        this.failed = scaled && scaleMode == NONE;
        this.fences32 = caps.OpenGL32;
        this.fences = caps.OpenGL32 || caps.GL_ARB_sync;
    }

    /** True once this grabber can't make frames; the session is then restarted without it. */
    boolean failed() {
        return failed;
    }

    /** Starts the readback of what is on the bound framebuffer. @return false when nothing was read */
    boolean grab(long slot) {
        if (failed || released || count == RING) return false;
        int i = (head + count) % RING;
        boolean fresh = pbo[i] == 0;
        if (fresh) pbo[i] = GL15.glGenBuffers();

        int readWas = 0, drawWas = 0;
        boolean scissor = false;
        if (scaleMode != NONE) {
            readWas = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            drawWas = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (!scaleChecked) while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* someone else's */ }
            shrink(readWas);
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
        if (fresh) GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) bytes, GL15.GL_STREAM_READ);
        // BGRA in this order is what the driver holds, so the read is a plain copy
        GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        if (scaleMode != NONE) {
            bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readWas);
            bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawWas);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (!scaleChecked) {
                scaleChecked = true;
                int err = GL11.glGetError();
                if (err != GL11.GL_NO_ERROR) {
                    LOG.info("Shrinking frames on the GPU failed here (GL error 0x" + Integer.toHexString(err)
                            + "); ffmpeg will shrink them instead");
                    blitBroken = true;
                    failed = true;
                    return false;
                }
            }
        }

        if (fences) {
            fence[i] = fences32 ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                    : ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        slots[i] = slot;
        age[i] = 0;
        count++;
        return true;
    }

    /** Hands over every readback that has finished, oldest first. Once a frame. */
    void poll(Session session) {
        while (count > 0) {
            int i = head;
            age[i]++;
            if (!ready(i)) return;
            byte[] data = session.take();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            ByteBuffer map = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, (long) bytes, mapped);
            boolean got = map != null && map.capacity() >= bytes;
            if (got) {
                mapped = map;
                map.clear();
                map.get(data, 0, bytes);
            }
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            deleteFence(i);
            head = (head + 1) % RING;
            count--;
            if (got) session.deliver(data, slots[i]);
        }
    }

    private boolean ready(int i) {
        GLSync f = fence[i];
        // without fences, or with one that never signals, a readback this old is done or as good as
        if (f == null) return age[i] >= 2;
        if (age[i] >= 4) return true;
        int r = fences32 ? GL32.glClientWaitSync(f, 0, 0L) : ARBSync.glClientWaitSync(f, 0, 0L);
        return r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED;
    }

    private void deleteFence(int i) {
        GLSync f = fence[i];
        if (f == null) return;
        fence[i] = null;
        if (fences32) GL32.glDeleteSync(f);
        else ARBSync.glDeleteSync(f);
    }

    // ---- GPU shrink ----------------------------------------------------------------------

    /** Draws the bound frame into a video-sized buffer and leaves that bound for reading. */
    private void shrink(int source) {
        if (outFbo == 0) {
            // one exact halving first when there is room: each pixel then averages all four it covers
            if (srcH >= 2 * h && srcW >= 2 * w) {
                halfW = srcW / 2;
                halfH = srcH / 2;
                halfRb = renderbuffer(halfW, halfH);
                halfFbo = framebuffer(halfRb);
            }
            outRb = renderbuffer(w, h);
            outFbo = framebuffer(outRb);
        }
        int fromW = srcW, fromH = srcH;
        bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        if (halfFbo != 0) {
            bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, halfFbo);
            blit(0, 0, halfW * 2, halfH * 2, halfW, halfH);
            bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, halfFbo);
            fromW = halfW;
            fromH = halfH;
        }
        bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, outFbo);
        blit(0, 0, fromW, fromH, w, h);
        bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outFbo);
    }

    private void blit(int x, int y, int sw, int sh, int dw, int dh) {
        if (scaleMode == GL30_FBO) {
            GL30.glBlitFramebuffer(x, y, sw, sh, 0, 0, dw, dh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } else {
            ARBFramebufferObject.glBlitFramebuffer(x, y, sw, sh, 0, 0, dw, dh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        }
    }

    private void bindFramebuffer(int target, int id) {
        if (scaleMode == GL30_FBO) GL30.glBindFramebuffer(target, id);
        else ARBFramebufferObject.glBindFramebuffer(target, id);
    }

    private int renderbuffer(int width, int height) {
        boolean gl30 = scaleMode == GL30_FBO;
        int was = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int rb = gl30 ? GL30.glGenRenderbuffers() : ARBFramebufferObject.glGenRenderbuffers();
        if (gl30) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, was);
        } else {
            ARBFramebufferObject.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rb);
            ARBFramebufferObject.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
            ARBFramebufferObject.glBindRenderbuffer(GL30.GL_RENDERBUFFER, was);
        }
        return rb;
    }

    /** A framebuffer drawing into {@code rb}; the caller restores the bindings. */
    private int framebuffer(int rb) {
        boolean gl30 = scaleMode == GL30_FBO;
        int fbo = gl30 ? GL30.glGenFramebuffers() : ARBFramebufferObject.glGenFramebuffers();
        bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        if (gl30) {
            GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, rb);
        } else {
            ARBFramebufferObject.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, rb);
        }
        return fbo;
    }

    // ---- ending --------------------------------------------------------------------------

    /** Frees every GL object; frames still on the GPU are dropped. */
    void release() {
        if (released) return;
        released = true;
        for (int i = 0; i < RING; i++) {
            deleteFence(i);
            if (pbo[i] != 0) GL15.glDeleteBuffers(pbo[i]);
            pbo[i] = 0;
        }
        count = 0;
        boolean gl30 = scaleMode == GL30_FBO;
        for (int fbo : new int[] {halfFbo, outFbo}) {
            if (fbo == 0) continue;
            if (gl30) GL30.glDeleteFramebuffers(fbo);
            else ARBFramebufferObject.glDeleteFramebuffers(fbo);
        }
        for (int rb : new int[] {halfRb, outRb}) {
            if (rb == 0) continue;
            if (gl30) GL30.glDeleteRenderbuffers(rb);
            else ARBFramebufferObject.glDeleteRenderbuffers(rb);
        }
        halfFbo = outFbo = halfRb = outRb = 0;
        mapped = null;
    }
}
