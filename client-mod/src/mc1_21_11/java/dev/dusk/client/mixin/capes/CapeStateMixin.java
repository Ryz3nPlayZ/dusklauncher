package dev.dusk.client.mixin.capes;

import dev.dusk.client.render.cape.CapeShapeHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class CapeStateMixin implements CapeShapeHolder {
    @Unique
    @Nullable
    private float[] duskclient$capeShape;

    @Override
    public void duskclient$setCapeShape(@Nullable float[] shape) {
        this.duskclient$capeShape = shape;
    }

    @Override
    @Nullable
    public float[] duskclient$capeShape() {
        return this.duskclient$capeShape;
    }
}
