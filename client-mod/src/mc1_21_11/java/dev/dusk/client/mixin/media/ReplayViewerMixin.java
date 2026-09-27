package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** The recorded player shares the viewer's account, so the viewer's entity gets a UUID of its own. */
@Mixin(LocalPlayer.class)
public abstract class ReplayViewerMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$viewerUuid(CallbackInfo ci) {
        if (ReplayPlayer.active()) ((LocalPlayer) (Object) this).setUUID(UUID.randomUUID());
    }
}
