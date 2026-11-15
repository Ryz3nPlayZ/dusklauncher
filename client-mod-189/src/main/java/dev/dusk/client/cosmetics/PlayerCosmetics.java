package dev.dusk.client.cosmetics;

import net.minecraft.util.ResourceLocation;

/**
 * Everything the render path needs about one player. Immutable: the manager
 * swaps a whole new instance in once its textures are uploaded, so a frame
 * never sees a half-loaded state.
 */
public final class PlayerCosmetics {
    public static final PlayerCosmetics NONE = new PlayerCosmetics(null, null, false, false);

    public final CapeTexture cape;
    public final CapeTexture ears;
    public final boolean glint;
    public final boolean upsideDown;

    private PlayerCosmetics(CapeTexture cape, CapeTexture ears, boolean glint, boolean upsideDown) {
        this.cape = cape;
        this.ears = ears;
        this.glint = glint;
        this.upsideDown = upsideDown;
    }

    public static PlayerCosmetics of(CapeTexture cape, CapeTexture ears, boolean glint, boolean upsideDown) {
        if (cape == null && ears == null && !upsideDown) return NONE;
        return new PlayerCosmetics(cape, ears, glint && cape != null, upsideDown);
    }

    public boolean hasCape() {
        return cape != null && cape.isRegistered();
    }

    public boolean hasEars() {
        return ears != null && ears.isRegistered();
    }

    /** The cape frame to draw now, or null. */
    public ResourceLocation capeTexture() {
        return hasCape() ? cape.current() : null;
    }

    public ResourceLocation earsTexture() {
        return hasEars() ? ears.current() : null;
    }

    /** Upload every texture. Client thread only. */
    void register() {
        if (cape != null) cape.register();
        if (ears != null) ears.register();
    }

    /** Release GPU resources. Client thread only. */
    void release() {
        if (cape != null) cape.release();
        if (ears != null) ears.release();
    }
}
