package dev.dusk.client.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Food values for Hunger Info. 1.21.1 flavour: eating's effects live on the FOOD component. */
public final class FoodCompat {
    private FoodCompat() {}

    /** The food's values, or null when the item is not something to eat. */
    public static @Nullable FoodProperties food(ItemStack stack) {
        return stack.get(DataComponents.FOOD);
    }

    /** Whether eating it gives a harmful effect, like rotten flesh's hunger. */
    public static boolean isRotten(ItemStack stack) {
        FoodProperties food = food(stack);
        if (food == null) return false;
        for (FoodProperties.PossibleEffect effect : food.effects()) {
            if (effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return true;
        }
        return false;
    }

    /** Health a regeneration effect from eating it heals over its duration. */
    public static float regenerationHealth(ItemStack stack) {
        FoodProperties food = food(stack);
        if (food == null) return 0;
        for (FoodProperties.PossibleEffect possible : food.effects()) {
            MobEffectInstance effect = possible.effect();
            if (effect.getEffect() == MobEffects.REGENERATION) {
                return (float) Math.floor(effect.getDuration() / Math.max(50 >> effect.getAmplifier(), 1));
            }
        }
        return 0;
    }
}
