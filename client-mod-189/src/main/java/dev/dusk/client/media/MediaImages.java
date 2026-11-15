package dev.dusk.client.media;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Pictures for the Media page: read and shrunk to {@code max} pixels on a
 * worker, uploaded as textures on the client thread, freed with the page.
 */
final class MediaImages {
    static final class Image {
        final String id;
        final int w, h;

        Image(String id, int w, int h) {
            this.id = id;
            this.w = w;
            this.h = h;
        }
    }

    private static int next;

    private final int max;
    private final ExecutorService worker;
    private final Map<String, Image> ready = new HashMap<>();
    private final Set<String> pending = new HashSet<>(), failed = new HashSet<>();
    private boolean released;

    MediaImages(int max, ExecutorService worker) {
        this.max = max;
        this.worker = worker;
    }

    /** The picture for {@code key}, read from {@code file} the first time it is asked for; null until it is up. */
    Image get(final String key, final Path file) {
        Image img = ready.get(key);
        if (img != null || failed.contains(key) || pending.contains(key)) return img;
        pending.add(key);
        worker.execute(() -> {
            final BufferedImage pic = read(file);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                pending.remove(key);
                if (released) return;
                if (pic == null) {
                    failed.add(key);
                    return;
                }
                String id = "duskclient:media/img" + (next++);
                Minecraft.getMinecraft().getTextureManager().loadTexture(new ResourceLocation(id), new DynamicTexture(pic));
                ready.put(key, new Image(id, pic.getWidth(), pic.getHeight()));
            });
        });
        return null;
    }

    boolean failed(String key) {
        return failed.contains(key);
    }

    void invalidate(String key) {
        Image img = ready.remove(key);
        if (img != null) Minecraft.getMinecraft().getTextureManager().deleteTexture(new ResourceLocation(img.id));
        failed.remove(key);
    }

    void releaseAll() {
        released = true;
        for (Image img : ready.values()) Minecraft.getMinecraft().getTextureManager().deleteTexture(new ResourceLocation(img.id));
        ready.clear();
    }

    private BufferedImage read(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) return null;
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) return null;
            int w = src.getWidth(), h = src.getHeight();
            float k = Math.min(1f, max / (float) Math.max(w, h));
            int dw = Math.max(1, Math.round(w * k)), dh = Math.max(1, Math.round(h * k));
            BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, dw, dh, null);
            g.dispose();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
