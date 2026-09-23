package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.DamageTint;
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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * DamageTint: which pixel of the overlay sheet an entity samples this frame.
 * The column picks the damage type's colour and the row picks how far the
 * flash has faded. The render state carries neither the hurt timer nor the
 * damage type, so both are stashed as the state is extracted.
 */
@Mixin(LivingEntityRenderer.class)
public class DamageOverlayMixin {
    @Unique
    private static final int DUSKCLIENT$NO_OVERRIDE = Integer.MIN_VALUE;

    /** The last fade row; the sheet's red half is eight rows tall. */
    @Unique
    private static final int DUSKCLIENT$LAST_FADE_ROW = 7;

    @Unique
    private static final Map<Object, Integer> duskclient$hurtTime = Collections.synchronizedMap(new WeakHashMap<>());
    @Unique
    private static final Map<Object, Integer> duskclient$deathTime = Collections.synchronizedMap(new WeakHashMap<>());
    @Unique
    private static final Map<Object, Integer> duskclient$variant = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("HEAD"))
    private void duskclient$captureHurt(LivingEntity entity, LivingEntityRenderState state, float partialTick,
                                        CallbackInfo ci) {
        duskclient$hurtTime.put(state, entity.hurtTime);
        duskclient$deathTime.put(state, entity.deathTime);
        duskclient$variant.put(state, DamageVariants.get(entity));
    }

    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void duskclient$overlayCoords(LivingEntityRenderState state, float whiteProgress,
                                                 CallbackInfoReturnable<Integer> cir) {
        int hurtTime = duskclient$hurtTime.getOrDefault(state, 0);
        int deathTime = duskclient$deathTime.getOrDefault(state, 0);
        int variant = duskclient$variant.getOrDefault(state, OverlayTint.OTHER);

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
