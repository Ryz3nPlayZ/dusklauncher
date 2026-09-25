package dev.dusk.client.gui;

import dev.dusk.client.account.SkinLibrary;
import dev.dusk.client.cosmetics.CapeTexture;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Images a menu page shows (skin files, pack icons, cape textures), loaded
 * off the render thread on first ask, uploaded on the render thread when
 * ready and all dropped when the page closes. Anything that is not a PNG is
 * converted when Java can read it; the rest (webp) stays blank.
 */
public final class RemoteImages {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "duskclient-images");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger PAGES = new AtomicInteger();

    public record Image(String id, int w, int h) {}

    private static final class Slot {
        volatile CapeTexture tex;
        volatile int w, h;
        volatile boolean failed;
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final String prefix = "ui/" + PAGES.incrementAndGet() + "/";
    private final int maxSize;
    private volatile boolean closed;

    /** @param maxSize images wider or taller than this are scaled down (0: keep) */
    public RemoteImages(int maxSize) {
        this.maxSize = maxSize;
    }

    /** The image for {@code key}, loading it with {@code loader} on first ask; null until it is ready. Render thread. */
    @Nullable
    public Image get(String key, Callable<byte @Nullable []> loader) {
        Slot slot = slots.get(key);
        if (slot == null) {
            Slot s = new Slot();
            slots.put(key, s);
            POOL.execute(() -> load(key, s, loader));
            return null;
        }
        CapeTexture tex = slot.tex;
        if (tex == null || slot.failed) return null;
        if (!tex.isRegistered()) tex.register();
        return new Image(tex.current().toString(), slot.w, slot.h);
    }

    /** True once {@code key} has failed to load (draw a placeholder). */
    public boolean failed(String key) {
        Slot s = slots.get(key);
        return s != null && s.failed;
    }

    /** Forget {@code key} so the next {@link #get} loads it again. Render thread. */
    public void invalidate(String key) {
        Slot s = slots.remove(key);
        if (s != null && s.tex != null) s.tex.release();
    }

    /** Drop every texture. Render thread. */
    public void releaseAll() {
        closed = true;
        slots.values().forEach(s -> {
            if (s.tex != null) s.tex.release();
        });
        slots.clear();
    }

    private void load(String key, Slot slot, Callable<byte @Nullable []> loader) {
        try {
            byte[] bytes = loader.call();
            if (bytes == null) throw new IllegalStateException("no image");
            int[] size = SkinLibrary.pngSize(bytes);
            if (size == null || (maxSize > 0 && (size[0] > maxSize || size[1] > maxSize))) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) throw new IllegalStateException("unreadable image");
                if (maxSize > 0 && (img.getWidth() > maxSize || img.getHeight() > maxSize)) {
                    float k = Math.min(maxSize / (float) img.getWidth(), maxSize / (float) img.getHeight());
                    int w = Math.max(1, Math.round(img.getWidth() * k)), h = Math.max(1, Math.round(img.getHeight() * k));
                    BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    var g = small.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, 0, 0, w, h, null);
                    g.dispose();
                    img = small;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                bytes = out.toByteArray();
                size = new int[] {img.getWidth(), img.getHeight()};
            }
            CapeTexture tex = CapeTexture.prepareFrames(prefix + sanitize(key), bytes, 1, CapeTexture.FRAME_MS);
            if (tex == null) throw new IllegalStateException("undecodable image");
            slot.w = size[0];
            slot.h = size[1];
            if (closed) {
                tex.release();
                return;
            }
            slot.tex = tex;
        } catch (Exception e) {
            slot.failed = true;
        }
    }

    private static String sanitize(String key) {
        String s = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return Integer.toHexString(key.hashCode()) + "_" + (s.length() > 40 ? s.substring(0, 40) : s);
    }
}
