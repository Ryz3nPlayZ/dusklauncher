package dev.dusk.client.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** A loose entity of a type, made only to be drawn (the stats screen's mob grid); never added to the level. */
public final class MobFactory {
    private MobFactory() {}

    @Nullable
    public static Entity create(EntityType<?> type, Level level) {
        return type.create(level);
    }
}
