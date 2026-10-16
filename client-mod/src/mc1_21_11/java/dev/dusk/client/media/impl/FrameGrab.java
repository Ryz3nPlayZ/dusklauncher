package dev.dusk.client.media.impl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Reads the main target back for the screen recorder without stalling the
 * frame: the copy goes into a buffer the CPU can map and the sink runs once
 * the GPU is done with it, a frame or two later, on the render thread. A few
 * copies can be in flight; the buffers are kept for the next frames.
 */
final class FrameGrab {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    private static final int MAX_IN_FLIGHT = 3;
    private static final ArrayDeque<GpuBuffer> FREE = new ArrayDeque<>();
    private static long freeSize;
    private static int inFlight;

    private FrameGrab() {}

    /** Gets the frame's RGBA rows, bottom row first; {@code rgba} is only valid during the call. */
    interface Sink {
        void frame(ByteBuffer rgba, int width, int height);
    }

    /** @return false when the frame was skipped (too many copies in flight, or a format it can't read) */
    static boolean grab(RenderTarget target, Sink sink) {
        GpuTexture tex = target.getColorTexture();
        if (tex == null || tex.getFormat().pixelSize() != 4 || inFlight >= MAX_IN_FLIGHT) return false;
        int w = target.width, h = target.height;
        long size = (long) w * h * 4;
        if (size != freeSize) release(size);
        GpuBuffer buf = FREE.poll();
        if (buf == null) buf = RenderSystem.getDevice().createBuffer(() -> "Dusk recorder frame", 9, size);
        GpuBuffer b = buf;
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        inFlight++;
        enc.copyTextureToBuffer(tex, b, 0L, () -> {
            inFlight--;
            try (GpuBuffer.MappedView view = enc.mapBuffer(b, true, false)) {
                sink.frame(view.data(), w, h);
            } catch (RuntimeException e) {
                LOG.warn("Couldn't read a recorder frame back", e);
            }
            if (b.size() == freeSize && FREE.size() < MAX_IN_FLIGHT) FREE.push(b);
            else b.close();
        }, 0);
        return true;
    }

    /** Drops the kept buffers (the window changed size, or recording stopped). */
    static void release(long newSize) {
        for (GpuBuffer b : FREE) b.close();
        FREE.clear();
        freeSize = newSize;
    }
}
