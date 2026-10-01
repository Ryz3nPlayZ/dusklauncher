package dev.dusk.client.mixin.shield;

import net.minecraft.client.renderer.blockentity.BannerRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** 26.x's ShieldSpecialRenderer draws the plate itself (see ShieldTintMixin); nothing to hook here. */
@Mixin(BannerRenderer.class)
public abstract class ShieldPatternTintMixin {
}
