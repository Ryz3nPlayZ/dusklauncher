package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 26.2 version: mapping moved off the command encoder entirely, so the
 * buffers are filled through a staged {@code writeToBuffer} instead — the
 * same split natural-motionblur itself makes on its 26.2 branch
 * (UNIFORM|COPY_DST usage, stage, upload, free).
 */
public final class GpuBufferUtil {

    private static final int UBO_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
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
     * Fills a uniform buffer with std140 data via a staged upload.
     */
    public static void writeStd140(GpuBuffer buffer, int sizeBytes, Consumer<Std140Builder> writer) {
        ByteBuffer data = MemoryUtil.memCalloc(sizeBytes);
        try {
            writer.accept(Std140Builder.intoBuffer(data));
            data.position(0);
            data.limit(sizeBytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0L, sizeBytes), data);
        } finally {
            MemoryUtil.memFree(data);
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
