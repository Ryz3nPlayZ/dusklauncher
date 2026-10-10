package dev.dusk.client.mixin.totem;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.hud.TotemCounter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Totem Counter: "Name | -3" wherever the game shows a player's name (nametags, chat). */
@Mixin(Player.class)
public abstract class TotemNameMixin {
    @ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
    private Component duskclient$popsAfterName(Component name) {
        return TotemCounter.withPops((Player) (Object) this, name);
    }
}
