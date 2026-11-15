package dev.dusk.client.mixin;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.modules.render.DamageTint;
import dev.dusk.client.modules.render.Nametags;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.FloatBuffer;

/**
 * Two hooks on the living renderer: the hurt flash's four combiner constants
 * (DamageTint — 1.8 has no overlay texture, the flash is these floats), and
 * the vanilla nametag draw, which the Nametags module replaces with its own
 * screen-space pass. Plus the cosmetics "upside down" flag, which turns a
 * player over the way vanilla turns Dinnerbone.
 */
@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityMixin {
    @Inject(method = "renderName", at = @At("HEAD"), cancellable = true)
    private void dusk$nametags(EntityLivingBase entity, double x, double y, double z, CallbackInfo ci) {
        if (Nametags.handling()) ci.cancel();
    }

    // setBrightness returns boolean in 1.8.9, so the callback is Returnable.
    @Inject(method = "setBrightness", at = @At("HEAD"), require = 0)
    private void dusk$tintPrepare(EntityLivingBase entity, float partialTicks, boolean combineTextures,
                                  CallbackInfoReturnable<Boolean> cir) {
        DamageTint.prepare(entity);
    }

    // setBrightness puts its hurt-branch constants first (ordinals 0-3); the
    // else-branch values follow (4-7). require = 0 so an OptiFine reorder
    // falls back to vanilla red rather than crashing.
    @Redirect(method = "setBrightness", at = @At(value = "INVOKE", ordinal = 0,
            target = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;"), require = 0)
    private FloatBuffer dusk$tintR(FloatBuffer buffer, float value) {
        return buffer.put(DamageTint.red(value));
    }

    @Redirect(method = "setBrightness", at = @At(value = "INVOKE", ordinal = 1,
            target = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;"), require = 0)
    private FloatBuffer dusk$tintG(FloatBuffer buffer, float value) {
        return buffer.put(DamageTint.green(value));
    }

    @Redirect(method = "setBrightness", at = @At(value = "INVOKE", ordinal = 2,
            target = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;"), require = 0)
    private FloatBuffer dusk$tintB(FloatBuffer buffer, float value) {
        return buffer.put(DamageTint.blue(value));
    }

    @Redirect(method = "setBrightness", at = @At(value = "INVOKE", ordinal = 3,
            target = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;"), require = 0)
    private FloatBuffer dusk$tintMix(FloatBuffer buffer, float value) {
        return buffer.put(DamageTint.mix(value));
    }

    @Inject(method = "rotateCorpse", at = @At("TAIL"))
    private void dusk$upsideDown(EntityLivingBase entity, float ageInTicks, float yaw, float partialTicks, CallbackInfo ci) {
        if (!(entity instanceof AbstractClientPlayer) || entity.deathTime > 0) return;
        if (!CosmeticsManager.get((AbstractClientPlayer) entity).upsideDown) return;
        String name = EnumChatFormatting.getTextWithoutFormattingCodes(entity.getName());
        if ("Dinnerbone".equals(name) || "Grumm".equals(name)) return; // vanilla already flipped them
        GlStateManager.translate(0f, entity.height + 0.1f, 0f);
        GlStateManager.rotate(180f, 0f, 0f, 1f);
    }
}
