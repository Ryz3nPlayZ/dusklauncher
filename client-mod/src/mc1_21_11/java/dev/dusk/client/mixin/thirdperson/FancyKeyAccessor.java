package dev.dusk.client.mixin.thirdperson;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pending presses, so a quick click counts even when the key is already up again. */
@Mixin(KeyMapping.class)
public interface FancyKeyAccessor {
    @Accessor("clickCount")
    int dusk$clickCount();
}
