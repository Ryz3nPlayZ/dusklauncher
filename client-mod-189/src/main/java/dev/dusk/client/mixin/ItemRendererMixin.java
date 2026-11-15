package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.LowFire;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The fire overlay's vertical placement, lowered by the Low Fire module. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Redirect(method = "renderFireInFirstPerson", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V"), require = 0)
    private void dusk$lowFire(float x, float y, float z) {
        GlStateManager.translate(x, y + LowFire.offsetY() * 0.5f, z);
    }
}
