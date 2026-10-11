package dev.dusk.client.mixin.styles;

import dev.dusk.client.styles.StylePacks;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Styles: the picked style packs load on top of the selected resource packs, outside the vanilla list. */
@Mixin(PackRepository.class)
public abstract class StylePacksMixin {
    @Inject(method = "openAllSelected", at = @At("RETURN"), cancellable = true)
    private void duskclient$addStyles(CallbackInfoReturnable<List<PackResources>> cir) {
        cir.setReturnValue(StylePacks.withStyles((PackRepository) (Object) this, cir.getReturnValue()));
    }
}
