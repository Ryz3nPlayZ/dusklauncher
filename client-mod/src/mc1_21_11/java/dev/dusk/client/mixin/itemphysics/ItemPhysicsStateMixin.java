package dev.dusk.client.mixin.itemphysics;

import dev.dusk.client.render.ItemPhysicsState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntityRenderState.class)
public class ItemPhysicsStateMixin implements ItemPhysicsState {
    @Unique private boolean duskclient$resting;

    @Override
    public void duskclient$setResting(boolean resting) {
        this.duskclient$resting = resting;
    }

    @Override
    public boolean duskclient$resting() {
        return duskclient$resting;
    }
}
