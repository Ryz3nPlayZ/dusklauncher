package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Uniform-buffer allocation. {@code createBuffer} moved around between
 * snapshots, so natural-motionblur looks it up reflectively; we keep that
 * so one jar covers a whole game line.
 */
public final class GpuBufferUtil {

    /**
     * natural-motionblur's raw usage value (UNIFORM|MAP_WRITE): the buffers
     * are filled through a mapped view, never staged. 26.2 removed mapping
     * from the command encoder, so that game line ships its own copy of
     * this class that stages through {@code writeToBuffer} instead.
     */
    private static final int UBO_USAGE = 130;
    private static Method createBufferMethod = null;

    private GpuBufferUtil() {}

    public static GpuBuffer createUBO(String debugName, int sizeBytes) {
        Object device = RenderSystem.getDevice();
        Supplier<String> label = () -> "duskclient:" + debugName;
        try {
            if (createBufferMethod == null) {
                createBufferMethod = device.getClass().getMethod("createBuffer", Supplier.class, int.class, long.class);
            }
            return (GpuBuffer) createBufferMethod.invoke(device, label, UBO_USAGE, (long) sizeBytes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("[DuskClient] no compatible createBuffer on " + device.getClass(), e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[DuskClient] motion blur UBO allocation failed", e);
        }
    }

    /**
     * Fills a uniform buffer with std140 data, exactly like
     * natural-motionblur: through a mapped view that stays alive for the
     * whole write. A stage-and-free pattern does not work here — on
     * backends with deferred command execution the upload races the free
     * and every velocity pass reads garbage matrices (full-screen smear
     * that never settles).
     */
    public static void writeStd140(GpuBuffer buffer, int sizeBytes, Consumer<Std140Builder> writer) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, false, true)) {
            ByteBuffer data = view.data();
            data.clear();
            writer.accept(Std140Builder.intoBuffer(data));
        }
    }

    public static void closeQuietly(GpuBuffer buffer) {
        if (buffer == null) return;
        try {
            buffer.close();
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isClosedBufferException(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("closed");
    }
}
