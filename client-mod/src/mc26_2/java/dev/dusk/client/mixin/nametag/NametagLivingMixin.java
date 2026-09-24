package dev.dusk.client.mixin.nametag;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.render.nametag.NametagHooks;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PolyNametag's own-nametag, sneaking and F1 rules in LivingEntityRenderer.shouldShowName. */
@Mixin(LivingEntityRenderer.class)
public abstract class NametagLivingMixin {
    @ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
    private boolean duskclient$showOwn(boolean original, @Local(argsOnly = true) LivingEntity entity) {
        return NametagHooks.showOwn(entity, original);
    }

    @WrapOperation(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isDiscrete()Z"))
    private boolean duskclient$sneaking(LivingEntity instance, Operation<Boolean> original) {
        return NametagHooks.discrete(original.call(instance));
    }

    @WrapOperation(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;isHidden()Z"))
    private boolean duskclient$hiddenHud(Hud hud, Operation<Boolean> original, @Local(argsOnly = true) LivingEntity entity) {
        return !NametagHooks.renderNamesUnderF1(entity, !original.call(hud));
    }
}
