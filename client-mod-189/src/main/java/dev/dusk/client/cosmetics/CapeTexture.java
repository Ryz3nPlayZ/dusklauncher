package dev.dusk.client.cosmetics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * A cape (or ears) image turned into registered GPU textures, following the
 * MinecraftCapes rules so any cape that works there works here:
 * <ul>
 *   <li>static cape: padded to the smallest power-of-two multiple of 64x32
 *       that fits (64x32, 128x64, ...), pixels top-left;</li>
 *   <li>animated cape: {@code height != width/2} means a vertical strip of
 *       {@code height / (width/2)} frames;</li>
 *   <li>ears: used as they are (14x7).</li>
 * </ul>
 * The {@code prepare} methods decode on any thread; {@link #register} and
 * {@link #release} run on the client thread.
 */
public final class CapeTexture {
    public static final int FRAME_MS = 100;

    private final ResourceLocation[] frames;
    private final BufferedImage[] images; // entries null once uploaded
    private final int frameMs;
    private volatile boolean registered;
    private volatile boolean released;

    private CapeTexture(ResourceLocation[] frames, BufferedImage[] images, int frameMs) {
        this.frames = frames;
        this.images = images;
        this.frameMs = Math.max(1, frameMs);
    }

    public static CapeTexture prepareCape(String key, byte[] png) {
        return prepareCape(key, png, FRAME_MS);
    }

    /** Decode and pad or split. Null on a broken image. */
    public static CapeTexture prepareCape(String key, byte[] png, int frameMs) {
        BufferedImage src = decode(png);
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (h != w / 2) {
            int frameH = w / 2;
            if (frameH <= 0) return null;
            int total = h / frameH;
            if (total <= 0) return null;
            BufferedImage[] out = new BufferedImage[total];
            ResourceLocation[] ids = new ResourceLocation[total];
            for (int f = 0; f < total; f++) {
                out[f] = copy(src, 0, f * frameH, w, frameH, w, frameH);
                ids[f] = id("capes/" + key + "/" + f);
            }
            return new CapeTexture(ids, out, frameMs);
        }
        int tw = 64, th = 32;
        while (tw < w || th < h) {
            tw *= 2;
            th *= 2;
        }
        return new CapeTexture(new ResourceLocation[] {id("capes/" + key)},
                new BufferedImage[] {copy(src, 0, 0, w, h, tw, th)}, frameMs);
    }

    /** Ears are not padded: the 14x7 ears model maps the image 1:1. */
    public static CapeTexture prepareEars(String key, byte[] png) {
        BufferedImage img = decode(png);
        if (img == null) return null;
        return new CapeTexture(new ResourceLocation[] {id("ears/" + key)}, new BufferedImage[] {img}, FRAME_MS);
    }

    /**
     * An accessory texture: a vertical tilesheet of {@code frames} equal
     * tiles, or the whole image when {@code frames} is 1. Unpadded.
     */
    public static CapeTexture prepareFrames(String key, byte[] png, int frames, int frameMs) {
        BufferedImage src = decode(png);
        if (src == null) return null;
        if (frames <= 1 || src.getHeight() % frames != 0) {
            return new CapeTexture(new ResourceLocation[] {id("accessories/" + key)}, new BufferedImage[] {src}, frameMs);
        }
        int w = src.getWidth();
        int fh = src.getHeight() / frames;
        BufferedImage[] out = new BufferedImage[frames];
        ResourceLocation[] ids = new ResourceLocation[frames];
        for (int f = 0; f < frames; f++) {
            out[f] = copy(src, 0, f * fh, w, fh, w, fh);
            ids[f] = id("accessories/" + key + "/" + f);
        }
        return new CapeTexture(ids, out, frameMs);
    }

    /** Upload every frame. Client thread only. */
    public void register() {
        if (registered || released) return;
        TextureManager tm = Minecraft.getMinecraft().getTextureManager();
        for (int i = 0; i < frames.length; i++) {
            tm.loadTexture(frames[i], new DynamicTexture(images[i]));
            images[i] = null; // the pixels live on the GPU now
        }
        registered = true;
    }

    /** Drop the GPU textures. Client thread only; safe to call twice. */
    public void release() {
        if (released) return;
        released = true;
        if (registered) {
            TextureManager tm = Minecraft.getMinecraft().getTextureManager();
            for (ResourceLocation id : frames) tm.deleteTexture(id);
        }
        for (int i = 0; i < images.length; i++) images[i] = null;
    }

    public boolean isRegistered() {
        return registered && !released;
    }

    /** The frame to draw right now (wall-clock driven, like MinecraftCapes). */
    public ResourceLocation current() {
        if (frames.length == 1) return frames[0];
        return frames[(int) ((System.currentTimeMillis() / frameMs) % frames.length)];
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return null;
            return copy(img, 0, 0, img.getWidth(), img.getHeight(), img.getWidth(), img.getHeight());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The {@code w x h} region at (sx, sy) of {@code src}, top-left on a transparent {@code cw x ch} ARGB canvas. */
    private static BufferedImage copy(BufferedImage src, int sx, int sy, int w, int h, int cw, int ch) {
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(sx, sy + y, w, 1, row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("duskclient", path);
    }
}
