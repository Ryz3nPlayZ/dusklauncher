package dev.dusk.client.cosmetics;

import org.jetbrains.annotations.Nullable;

/** Duck interface added to {@code AvatarRenderState} by the render-state mixin. */
public interface ExtendedAvatarRenderState {
    void duskclient$setCosmetics(@Nullable PlayerCosmetics cosmetics);

    @Nullable
    PlayerCosmetics duskclient$getCosmetics();
}
