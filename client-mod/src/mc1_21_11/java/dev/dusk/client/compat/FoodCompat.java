package dev.dusk.client.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import org.jetbrains.annotations.Nullable;

/** Food values for Hunger Info. 1.21.2+ flavour: eating's effects live on the CONSUMABLE component. */
public final class FoodCompat {
    private FoodCompat() {}

    /** The food's values, or null when the item is not something to eat. */
    public static @Nullable FoodProperties food(ItemStack stack) {
        return stack.has(DataComponents.CONSUMABLE) ? stack.get(DataComponents.FOOD) : null;
    }

    /** Whether eating it gives a harmful effect, like rotten flesh's hunger. */
    public static boolean isRotten(ItemStack stack) {
        for (MobEffectInstance effect : effects(stack)) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return true;
        }
        return false;
    }

    /** Health a regeneration effect from eating it heals over its duration. */
    public static float regenerationHealth(ItemStack stack) {
        for (MobEffectInstance effect : effects(stack)) {
            if (effect.getEffect() == MobEffects.REGENERATION) {
                return (float) Math.floor(effect.getDuration() / Math.max(50 >> effect.getAmplifier(), 1));
            }
        }
        return 0;
    }

    private static java.util.List<MobEffectInstance> effects(ItemStack stack) {
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) return java.util.List.of();
        java.util.List<MobEffectInstance> out = new java.util.ArrayList<>();
        for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            if (effect instanceof ApplyStatusEffectsConsumeEffect apply) out.addAll(apply.effects());
        }
        return out;
    }
}
