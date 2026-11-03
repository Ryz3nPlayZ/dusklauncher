package dev.dusk.client.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

/** Enchanting-table bits that moved: 1.21.2 renamed registryOrThrow, 1.21.5 made EnchantmentInstance a record. */
public final class EnchantCompat {
    private EnchantCompat() {}

    public static Registry<Enchantment> registry(RegistryAccess access) {
        return access.lookupOrThrow(Registries.ENCHANTMENT);
    }

    public static Holder<Enchantment> enchantment(EnchantmentInstance e) {
        return e.enchantment;
    }

    public static int level(EnchantmentInstance e) {
        return e.level;
    }
}
