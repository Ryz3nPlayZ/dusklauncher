package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.DamageTint;
import dev.dusk.client.render.DamageTintState;
import dev.dusk.client.render.DamageVariants;
import dev.dusk.client.render.OverlayTint;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DamageTint: which pixel of the overlay sheet an entity samples this frame.
 * The column picks the damage type's colour and the row picks how far the
 * flash has faded. The render state carries neither the hurt timer nor the
 * damage type, so both are stashed on the state as it is extracted. With the
 * module off both hooks return before touching anything.
 */
@Mixin(LivingEntityRenderer.class)
public class DamageOverlayMixin {
    @Unique
    private static final int DUSKCLIENT$NO_OVERRIDE = Integer.MIN_VALUE;

    /** The last fade row; the sheet's red half is eight rows tall. */
    @Unique
    private static final int DUSKCLIENT$LAST_FADE_ROW = 7;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("HEAD"))
    private void duskclient$captureHurt(LivingEntity entity, LivingEntityRenderState state, float partialTick,
                                        CallbackInfo ci) {
        if (!DamageTint.active()) return;
        boolean flashing = entity.hurtTime > 0 || entity.deathTime > 0;
        ((DamageTintState) state).duskclient$setDamage(entity.hurtTime, entity.deathTime,
                flashing && DamageTint.perType() ? DamageVariants.get(entity) : OverlayTint.OTHER);
    }

    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void duskclient$overlayCoords(LivingEntityRenderState state, float whiteProgress,
                                                 CallbackInfoReturnable<Integer> cir) {
        if (!DamageTint.active()) return;
        DamageTintState damage = (DamageTintState) state;
        int hurtTime = damage.duskclient$hurtTime();
        int deathTime = damage.duskclient$deathTime();
        int variant = damage.duskclient$variant();

        boolean flashing = hurtTime > 0 || deathTime > 0;
        int coords = duskclient$coords(flashing, hurtTime, deathTime, variant, OverlayTexture.u(whiteProgress));
        if (coords != DUSKCLIENT$NO_OVERRIDE) cir.setReturnValue(coords);
    }

    /** Vanilla is left alone unless a column or a row has to change. */
    @Unique
    private static int duskclient$coords(boolean flashing, int hurtTime, int deathTime, int variant, int vanillaU) {
        boolean perType = DamageTint.perType();
        if (!DamageTint.active() || !flashing || (!DamageTint.fading() && !perType)) return DUSKCLIENT$NO_OVERRIDE;
        return OverlayTexture.pack(perType ? variant : vanillaU, duskclient$row(hurtTime, deathTime));
    }

    @Unique
    private static int duskclient$row(int hurtTime, int deathTime) {
        if (!DamageTint.fading()) return OverlayTexture.RED_OVERLAY_V;
        if (deathTime > 0) {
            return DamageTint.fadesDead() ? duskclient$fadeRow(deathTime / DamageTint.fadeDuration()) : 0;
        }
        return duskclient$fadeRow(1.0f - hurtTime / DamageTint.fadeDuration());
    }

    @Unique
    private static int duskclient$fadeRow(float progress) {
        return Math.clamp(Math.round(progress * DUSKCLIENT$LAST_FADE_ROW), 0, DUSKCLIENT$LAST_FADE_ROW);
    }
}
