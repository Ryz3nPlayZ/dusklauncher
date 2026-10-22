package dev.dusk.client.mixin.thirdperson;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.render.thirdperson.FancyCamera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fancy Third Person: the block you break or use is the one under the crosshair. */
@Mixin(Entity.class)
public class FancyEntityPickMixin {
    @WrapOperation(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 dusk$fancyPick(Entity entity, float pt, Operation<Vec3> original) {
        Vec3 d = FancyCamera.pickDirection(entity, pt);
        return d != null ? d : original.call(entity, pt);
    }
}
