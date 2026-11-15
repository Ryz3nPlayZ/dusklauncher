package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLSync;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

/**
 * Shortens the time between moving the mouse and that turn being drawn, on a
 * Mac. What adds up is frames queueing behind the GPU and motion that arrives
 * after the game's last event poll. So just before the frame reads the mouse,
 * this waits for the previous frame to finish on the GPU. The wait falls after
 * the tick, so ticking still overlaps the GPU, and it only runs while you're
 * steering the camera.
 *
 * <p>The frame-end sync with a wait at the next frame is the idea of Sodium's
 * CPU Render-Ahead Limit (LGPL; written from how it behaves, not its code).
 * On this old GL the fence needs GL 3.2 or ARB_sync, which the macOS 2.1
 * context may not have — without it the module simply does nothing.
 */
public class LowInputLag extends Module {
    /** A frame slower than this isn't worth holding the next one for. */
    private static final long MAX_WAIT_NANOS = 50_000_000L;

    private static LowInputLag instance;
    /** The previous frame's GL sync object, null for none. Main thread only. */
    private static GLSync sync;
    private static Boolean fenceSupported;

    public LowInputLag() {
        super("lowinputlag", "Low Input Lag", Category.MISC,
                "Makes the mouse feel more direct: frames stop queueing behind the GPU. Can lower FPS a little "
                        + "when the GPU is at its limit.");
        instance = this;
        setEnabled(true);
    }

    /** Only on a Mac for now, where the measuring was done. */
    public static boolean available() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean fences() {
        if (fenceSupported == null) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                fenceSupported = caps != null && (caps.OpenGL32 || caps.GL_ARB_sync);
            } catch (Exception e) {
                fenceSupported = false;
            }
        }
        return fenceSupported;
    }

    private static boolean steering() {
        LowInputLag m = instance;
        Minecraft mc = Minecraft.getMinecraft();
        return m != null && m.enabled() && mc.theWorld != null && mc.inGameHasFocus;
    }

    private static GLSync fence() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.OpenGL32 ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                : ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Right before the frame turns the camera with the accumulated mouse motion. */
    public static void beforeMouseRead() {
        if (sync == null) return;
        GLSync s = sync;
        sync = null;
        try {
            if (steering()) {
                ContextCapabilities caps = GLContext.getCapabilities();
                int flags = caps.OpenGL32 ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT
                        : ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT;
                long before = System.nanoTime();
                if (caps.OpenGL32) GL32.glClientWaitSync(s, flags, MAX_WAIT_NANOS);
                else ARBSync.glClientWaitSync(s, flags, MAX_WAIT_NANOS);
                dev.dusk.client.util.PerfLog.gpuWait(System.nanoTime() - before);
            }
        } finally {
            if (GLContext.getCapabilities().OpenGL32) GL32.glDeleteSync(s);
            else ARBSync.glDeleteSync(s);
        }
    }

    /** The end of each frame: everything it drew is now behind this sync. */
    public static void frameEnd() {
        if (sync == null && steering() && fences()) sync = fence();
    }
}
