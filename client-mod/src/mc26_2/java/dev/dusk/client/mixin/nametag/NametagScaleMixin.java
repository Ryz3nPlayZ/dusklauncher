package dev.dusk.client.mixin.nametag;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.render.nametag.NametagHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PolyNametag's scale slider, 26.2 flavour: the pose is built in SubmitNodeCollection. */
@Mixin(SubmitNodeCollection.class)
public abstract class NametagScaleMixin {
    @WrapOperation(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void duskclient$scale(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        float scale = NametagHooks.scale();
        original.call(pose, x * scale, y * scale, z * scale);
    }
}
