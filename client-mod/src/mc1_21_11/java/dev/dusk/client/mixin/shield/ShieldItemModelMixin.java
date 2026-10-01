package dev.dusk.client.mixin.shield;

import dev.dusk.client.modules.render.ShieldStatuses;
import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shield Statuses: picks whose state a shield shows while its model is
 * resolved, where the holder is known. Third-person shields show their
 * holder; first-person, GUI and holder-less ones show yours, as in the
 * original. The tint joins the model identity so the GUI's item atlas redraws
 * the icon when it changes (the original marks shield icons animated).
 */
@Mixin(SpecialModelWrapper.class)
public abstract class ShieldItemModelMixin {
    @Shadow @Final private SpecialModelRenderer<?> specialRenderer;

    @Inject(method = "update", at = @At("TAIL"))
    private void dusk$shieldTint(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                                 ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed, CallbackInfo ci) {
        if (!ShieldStatuses.active() || !(specialRenderer instanceof ShieldSpecialRenderer)) return;
        LivingEntity holder = owner == null ? null : owner.asLivingEntity();
        Player player;
        if (holder == null) player = Minecraft.getInstance().player;
        else if (holder instanceof Player p) player = p;
        else return; // armour stands and mobs have no shield state
        int tint = ShieldStatuses.colorFor(player);
        if (tint == ShieldTint.NONE) return;
        ((ShieldTint.Holder) output).dusk$setShieldTint(tint);
        output.appendModelIdentityElement(tint);
    }
}
