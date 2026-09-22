package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements ExtendedAvatarRenderState {
    @Unique
    @Nullable
    private PlayerCosmetics duskclient$cosmetics;

    @Override
    public void duskclient$setCosmetics(@Nullable PlayerCosmetics cosmetics) {
        this.duskclient$cosmetics = cosmetics;
    }

    @Override
    @Nullable
    public PlayerCosmetics duskclient$getCosmetics() {
        return this.duskclient$cosmetics;
    }
}
