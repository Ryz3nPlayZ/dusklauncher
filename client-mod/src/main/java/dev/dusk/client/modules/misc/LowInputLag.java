package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.FramePoll;
import dev.dusk.client.module.Module;
import dev.dusk.client.perf.Perf;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;

/**
 * Shortens the time between moving the mouse and that turn being drawn, on a
 * Mac. Measured, macOS hands motion to the game with no delay of its own; what
 * adds up is frames queueing behind the GPU (Sodium allows three by default)
 * and motion that arrives after the game's last event poll. So just before the
 * frame reads the mouse, this waits for the previous frame to finish on the
 * GPU and polls events once more. The wait falls after the tick, so ticking
 * still overlaps the GPU, and it only runs while you're steering the camera.
 *
 * The frame-end sync with a wait at the next frame is the idea of Sodium's
 * CPU Render-Ahead Limit (LGPL; written from how it behaves, not its code),
 * and the late poll that of Input Optimizer's sampling (LGPL, same).
 */
public class LowInputLag extends Module {
    /** A frame slower than this isn't worth holding the next one for. */
    private static final long MAX_WAIT_NANOS = 50_000_000L;

    private static LowInputLag instance;
    /** The previous frame's GL sync object, 0 for none. Main thread only. */
    private static long sync;
    private static Boolean openGl;

    public LowInputLag() {
        super("lowinputlag", "Low Input Lag", Category.MISC,
                "Makes the mouse feel more direct: frames stop queueing behind the GPU, and the camera uses the "
                        + "newest mouse motion. Can lower FPS a little when the GPU is at its limit.");
        instance = this;
        setEnabled(true);
    }

    /** Only on a Mac for now, where the measuring was done, and on targets with the hooks. */
    public static boolean available() {
        return FramePoll.SUPPORTED && System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean steering(LowInputLag m) {
        if (m == null || !m.enabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.mouseHandler.isMouseGrabbed();
    }

    /** No GL context on this thread means a Vulkan backend, which has no sync objects to use. */
    private static boolean openGl() {
        if (openGl == null) {
            try {
                GL.getCapabilities();
                openGl = true;
            } catch (IllegalStateException e) {
                openGl = false;
            }
        }
        return openGl;
    }

    /** Right before the frame turns the camera with the accumulated mouse motion. */
    public static void beforeMouseRead() {
        boolean steering = steering(instance);
        long s = sync;
        if (s != 0) {
            sync = 0;
            if (steering) {
                long t = System.nanoTime();
                GL32C.glClientWaitSync(s, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, MAX_WAIT_NANOS);
                Perf.gpuWait(System.nanoTime() - t);
            }
            GL32C.glDeleteSync(s);
        }
        if (steering) FramePoll.poll();
    }

    /** The end of each frame: everything it drew is now behind this sync. */
    public static void frameEnd() {
        if (sync == 0 && steering(instance) && openGl()) {
            sync = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
    }
}
