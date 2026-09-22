package dev.dusk.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A cape (or ears) image turned into registered GPU textures, following the
 * MinecraftCapes rules exactly so any cape that works there works here:
 *
 * <ul>
 *   <li>static cape: padded to the smallest power-of-two multiple of 64×32
 *       that fits the source (64×32, 128×64, 256×128, …), pixels top-left;</li>
 *   <li>animated cape: {@code height != width/2} ⇒ a vertical strip of
 *       {@code height / (width/2)} frames, 100 ms per frame;</li>
 *   <li>ears: used as-is (14×7).</li>
 * </ul>
 *
 * {@link #prepare} does the CPU work on any thread; {@link #register} must
 * run on the render thread. {@link #current()} is what the render layers
 * read every frame.
 */
public final class CapeTexture {
    public static final int FRAME_MS = 100;

    private final Identifier[] frames;
    private final NativeImage[] images; // null once registered
    private final int frameMs;
    private volatile boolean registered;
    private volatile boolean released;

    private CapeTexture(Identifier[] frames, NativeImage[] images, int frameMs) {
        this.frames = frames;
        this.images = images;
        this.frameMs = Math.max(1, frameMs);
    }

    /** Decode + pad/split at the MinecraftCapes speed. Any thread. */
    public static CapeTexture prepareCape(String key, byte[] png) {
        return prepareCape(key, png, FRAME_MS);
    }

    /** Decode + pad/split. Any thread. Returns null on a broken image. */
    public static CapeTexture prepareCape(String key, byte[] png, int frameMs) {
        NativeImage src;
        try {
            src = NativeImage.read(png);
        } catch (IOException e) {
            return null;
        }
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            if (h != w / 2) {
                int frameH = w / 2;
                if (frameH <= 0) return null;
                int total = h / frameH;
                if (total <= 0) return null;
                List<NativeImage> out = new ArrayList<>(total);
                for (int f = 0; f < total; f++) {
                    NativeImage frame = new NativeImage(w, frameH, true);
                    for (int x = 0; x < w; x++) {
                        for (int y = 0; y < frameH; y++) {
                            frame.setPixel(x, y, src.getPixel(x, y + f * frameH));
                        }
                    }
                    out.add(frame);
                }
                Identifier[] ids = new Identifier[total];
                for (int f = 0; f < total; f++) ids[f] = id("capes/" + key + "/" + f);
                return new CapeTexture(ids, out.toArray(new NativeImage[0]), frameMs);
            }
            int tw = 64, th = 32;
            while (tw < w || th < h) {
                tw *= 2;
                th *= 2;
            }
            NativeImage padded = new NativeImage(tw, th, true);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    padded.setPixel(x, y, src.getPixel(x, y));
                }
            }
            return new CapeTexture(new Identifier[] {id("capes/" + key)}, new NativeImage[] {padded}, frameMs);
        } finally {
            src.close();
        }
    }

    /** Ears are not padded — the 14×7 ears model UV maps the image 1:1. */
    public static CapeTexture prepareEars(String key, byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            return new CapeTexture(new Identifier[] {id("ears/" + key)}, new NativeImage[] {img}, FRAME_MS);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * An accessory texture: a vertical tilesheet of {@code frames} equal
     * tiles (Cosmetica's animated-texture layout), or the whole image when
     * {@code frames} is 1. Used unpadded — the model UVs span the image.
     */
    public static CapeTexture prepareFrames(String key, byte[] png, int frames, int frameMs) {
        NativeImage src;
        try {
            src = NativeImage.read(png);
        } catch (IOException e) {
            return null;
        }
        if (frames <= 1 || src.getHeight() % frames != 0) {
            return new CapeTexture(new Identifier[] {id("accessories/" + key)}, new NativeImage[] {src}, frameMs);
        }
        try {
            int w = src.getWidth();
            int fh = src.getHeight() / frames;
            NativeImage[] out = new NativeImage[frames];
            Identifier[] ids = new Identifier[frames];
            for (int f = 0; f < frames; f++) {
                NativeImage frame = new NativeImage(w, fh, true);
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < fh; y++) {
                        frame.setPixel(x, y, src.getPixel(x, y + f * fh));
                    }
                }
                out[f] = frame;
                ids[f] = id("accessories/" + key + "/" + f);
            }
            return new CapeTexture(ids, out, frameMs);
        } finally {
            src.close();
        }
    }

    /** Upload every frame. Render thread only. */
    public void register() {
        if (registered || released) return;
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        for (int i = 0; i < frames.length; i++) {
            final Identifier id = frames[i];
            tm.register(id, new DynamicTexture(id::toString, images[i]));
            images[i] = null; // owned by the DynamicTexture now
        }
        registered = true;
    }

    /** Drop the GPU textures. Render thread only. Safe to call twice. */
    public void release() {
        if (released) return;
        released = true;
        if (registered) {
            TextureManager tm = Minecraft.getInstance().getTextureManager();
            for (Identifier id : frames) tm.release(id);
        } else {
            for (NativeImage img : images) if (img != null) img.close();
        }
    }

    public boolean isRegistered() {
        return registered && !released;
    }

    public int frameCount() {
        return frames.length;
    }

    /** The frame to draw right now (wall-clock driven, like MinecraftCapes). */
    public Identifier current() {
        if (frames.length == 1) return frames[0];
        int f = (int) ((System.currentTimeMillis() / frameMs) % frames.length);
        return frames[f];
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("duskclient", path);
    }
}
