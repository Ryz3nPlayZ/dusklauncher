package dev.dusk.client.cosmetics;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Everything the render path needs to know about one player. Immutable;
 * the manager swaps a whole new instance in once its textures are
 * registered, so a frame never sees a half-loaded state.
 * 1.21.6–1.21.8 flavour: PlayerSkin holds plain ResourceLocations, so the
 * cape's current frame is resolved by the cape layer mixin each frame.
 */
public record PlayerCosmetics(
        @Nullable CapeTexture cape,
        @Nullable CapeTexture ears,
        boolean glint,
        boolean upsideDown,
        /** model accessories, drawn by {@link AccessoriesLayer} */
        List<Accessory> accessories) {

    public static final PlayerCosmetics NONE = new PlayerCosmetics(null, null, false, false, List.of());

    public static PlayerCosmetics of(@Nullable CapeTexture cape, @Nullable CapeTexture ears, boolean glint, boolean upsideDown) {
        return of(cape, ears, glint, upsideDown, List.of());
    }

    public static PlayerCosmetics of(@Nullable CapeTexture cape, @Nullable CapeTexture ears, boolean glint, boolean upsideDown,
                                     List<Accessory> accessories) {
        if (cape == null && ears == null && !glint && !upsideDown && accessories.isEmpty()) return NONE;
        return new PlayerCosmetics(cape, ears, glint, upsideDown, List.copyOf(accessories));
    }

    public boolean hasCape() {
        return cape != null && cape.isRegistered();
    }

    public boolean hasEars() {
        return ears != null && ears.isRegistered();
    }

    /** The cape frame to draw right now, or null without a custom cape. */
    @Nullable
    public ResourceLocation capeTexture() {
        return hasCape() ? cape.current() : null;
    }

    @Nullable
    public ResourceLocation earsTexture() {
        return hasEars() ? ears.current() : null;
    }

    /** Release GPU resources. Render thread only. */
    public void release() {
        if (cape != null) cape.release();
        if (ears != null) ears.release();
        for (Accessory a : accessories) a.texture().release();
    }

    /** Upload every texture. Render thread only. */
    public void register() {
        if (cape != null) cape.register();
        if (ears != null) ears.register();
        for (Accessory a : accessories) a.texture().register();
    }
}
