package dev.dusk.client.mixin.hunger;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Hunger Info: exhaustion, which vanilla neither exposes nor sends to the client. */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
    @Accessor("exhaustionLevel")
    float dusk$getExhaustion();

    @Accessor("exhaustionLevel")
    void dusk$setExhaustion(float exhaustion);
}
