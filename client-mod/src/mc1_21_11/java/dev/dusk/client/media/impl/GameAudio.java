package dev.dusk.client.media.impl;

import dev.dusk.client.config.DuskConfig;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * Game sound for the screen recorder. The game's OpenAL context goes on an
 * OpenAL Soft loopback device instead of the speakers, so its mix can be
 * read back sample for sample; a thread renders that mix in small blocks,
 * hands each to the recorder and plays it on the real device through a
 * second context. Flashback opens a loopback device the same way for its
 * exports (behaviour only; it plays nothing while exporting). Anything
 * missing (no loopback, no thread-local contexts, the format refused) and
 * the game opens its device as it always does, and videos have no sound.
 */
public final class GameAudio {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    public static final int RATE = 48_000;
    /** Frames per block and blocks queued on the real device: 8 x 256 is about 43 ms. */
    private static final int BLOCK = 256, BLOCKS = 8;
    private static final int BYTES = BLOCK * 4;

    /** Takes each block of the mix: interleaved 16-bit stereo at 48 kHz, valid during the call. */
    public interface Tap {
        void samples(byte[] block, int length);
    }

    private static volatile @Nullable Tap tap;
    private static long realDevice, outContext, loopback;
    private static @Nullable Thread thread;
    private static volatile boolean running;
    /** Whether this sound engine was started routed, so a change in the settings knows to reload it. */
    private static boolean routedAtInit;

    private GameAudio() {}

    /** Whether the settings ask for the game's sound in videos. */
    public static boolean wanted() {
        DuskConfig c = DuskConfig.get();
        return !c.videoMode.equals("off") && c.videoAudio;
    }

    /** Whether game sound is going through the loopback, so videos get it. */
    public static boolean routed() {
        return loopback != 0 && running;
    }

    /** Whether the sound engine needs a reload to match the settings. */
    public static boolean needsReload() {
        return wanted() != routedAtInit;
    }

    public static void tap(@Nullable Tap t) {
        tap = t;
    }

    // ---- from the sound library's mixin ---------------------------------------------

    /**
     * The game just opened {@code device}: when routing, keep it as the real
     * output and give the game a loopback device in its place.
     */
    public static long opened(long device) {
        routedAtInit = wanted();
        if (!routedAtInit || device == 0) return device;
        try {
            if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")
                    || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_thread_local_context")) {
                LOG.info("OpenAL has no loopback here; videos will be silent");
                return device;
            }
            long lb = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (lb == 0) return device;
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(lb, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                ALC10.alcCloseDevice(lb);
                return device;
            }
            long ctx;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer attrs = stack.ints(ALC10.ALC_FREQUENCY, RATE, 0);
                ctx = ALC10.alcCreateContext(device, attrs);
            }
            if (ctx == 0) {
                ALC10.alcCloseDevice(lb);
                return device;
            }
            realDevice = device;
            outContext = ctx;
            loopback = lb;
            LOG.info("Game sound goes through a loopback device, so videos can have it");
            return lb;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Couldn't set up the loopback device", e);
            return device;
        }
    }

    /** The context attributes for {@code device}: the loopback needs its render format named. */
    public static @Nullable IntBuffer attributes(long device, MemoryStack stack) {
        if (device == 0 || device != loopback) return null;
        return stack.ints(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
                ALC10.ALC_FREQUENCY, RATE, 0);
    }

    /** The game's context exists: start pumping its mix to the speakers. */
    public static void contextCreated(long device, long context) {
        if (device == 0 || device != loopback) return;
        if (context == 0) {
            shutdown();
            return;
        }
        running = true;
        Thread t = new Thread(GameAudio::pump, "Dusk game audio");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        thread = t;
    }

    /** The real device's name, for the game's checks on which device it is using. */
    public static @Nullable String realName(long device) {
        if (device == 0 || device != loopback || realDevice == 0) return null;
        String name = ALC10.alcGetString(realDevice, 4115);
        if (name == null) name = ALC10.alcGetString(realDevice, ALC10.ALC_DEVICE_SPECIFIER);
        return name;
    }

    /** Whether the speakers went away (the game checks the device it holds, which can't). */
    public static boolean realDisconnected() {
        return realDevice != 0 && ALC10.alcIsExtensionPresent(realDevice, "ALC_EXT_disconnect")
                && ALC11.alcGetInteger(realDevice, 787) == 0;
    }

    /** Before the game closes its context and the loopback. */
    public static void shutdown() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (outContext != 0) ALC10.alcDestroyContext(outContext);
        if (realDevice != 0) ALC10.alcCloseDevice(realDevice);
        outContext = 0;
        realDevice = 0;
        loopback = 0;
    }

    // ---- the pump ---------------------------------------------------------------------

    private static void pump() {
        long lb = loopback;
        ByteBuffer block = MemoryUtil.memAlloc(BYTES);
        byte[] copy = new byte[BYTES];
        int source = 0;
        int[] buffers = new int[BLOCKS];
        try {
            EXTThreadLocalContext.alcSetThreadContext(outContext);
            // its own capabilities: the game may not have made its own yet
            AL.setCurrentThread(AL.createCapabilities(ALC.createCapabilities(realDevice)));
            source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alGenBuffers(buffers);
            for (int b : buffers) {
                render(lb, block, copy);
                AL10.alBufferData(b, AL10.AL_FORMAT_STEREO16, block, RATE);
                AL10.alSourceQueueBuffers(source, b);
            }
            AL10.alSourcePlay(source);
            while (running) {
                int done = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < done; i++) {
                    int b = AL10.alSourceUnqueueBuffers(source);
                    render(lb, block, copy);
                    AL10.alBufferData(b, AL10.AL_FORMAT_STEREO16, block, RATE);
                    AL10.alSourceQueueBuffers(source, b);
                }
                // ran dry (a long pause somewhere): start again from what's queued
                if (done > 0 && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source);
                LockSupport.parkNanos(1_000_000L);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Game audio stopped", e);
        } finally {
            try {
                if (source != 0) {
                    AL10.alSourceStop(source);
                    AL10.alDeleteSources(source);
                }
                AL10.alDeleteBuffers(buffers);
                EXTThreadLocalContext.alcSetThreadContext(0L);
                AL.setCurrentThread(null);
            } catch (RuntimeException | LinkageError ignored) {
            }
            MemoryUtil.memFree(block);
        }
    }

    private static void render(long lb, ByteBuffer block, byte[] copy) {
        block.clear();
        SOFTLoopback.nalcRenderSamplesSOFT(lb, MemoryUtil.memAddress(block), BLOCK);
        Tap t = tap;
        if (t != null) {
            block.get(0, copy, 0, BYTES);
            t.samples(copy, BYTES);
        }
    }
}
