package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.CleanVoid;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clean Void: the client world's horizon, which only the sky renderer reads. */
@Mixin(World.class)
public abstract class WorldMixin {
    @Shadow @Final public boolean isRemote;

    @Inject(method = "getHorizon", at = @At("HEAD"), cancellable = true)
    private void dusk$cleanVoid(CallbackInfoReturnable<Double> cir) {
        if (isRemote && CleanVoid.active()) cir.setReturnValue(-10000.0D);
    }
}
