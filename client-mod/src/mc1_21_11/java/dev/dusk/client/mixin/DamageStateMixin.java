package dev.dusk.client.mixin;

import dev.dusk.client.render.DamageTintState;
import dev.dusk.client.render.OverlayTint;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class DamageStateMixin implements DamageTintState {
    @Unique private int duskclient$hurtTime;
    @Unique private int duskclient$deathTime;
    @Unique private int duskclient$variant = OverlayTint.OTHER;

    @Override
    public void duskclient$setDamage(int hurtTime, int deathTime, int variant) {
        this.duskclient$hurtTime = hurtTime;
        this.duskclient$deathTime = deathTime;
        this.duskclient$variant = variant;
    }

    @Override
    public int duskclient$hurtTime() { return duskclient$hurtTime; }

    @Override
    public int duskclient$deathTime() { return duskclient$deathTime; }

    @Override
    public int duskclient$variant() { return duskclient$variant; }
}
