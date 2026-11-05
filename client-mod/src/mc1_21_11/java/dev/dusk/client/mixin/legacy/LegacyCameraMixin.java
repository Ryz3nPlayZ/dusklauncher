package dev.dusk.client.mixin.legacy;

import dev.dusk.client.modules.render.LegacyCombat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legacy Combat: the camera reaches the sneak height in one tick, both ways,
 * rather than easing there. Swimming, gliding and sleeping keep vanilla's
 * ease, since 1.8 had no such poses. Ported from old-animations (MIT, see NOTICE).
 */
@Mixin(Camera.class)
public class LegacyCameraMixin {
    @Shadow
    private float eyeHeight;
    @Shadow
    private Entity entity;

    /** How long the key and the pose have disagreed. */
    @Unique
    private int duskclient$disagreeTicks;

    @Inject(method = "tick", at = @At("TAIL"))
    private void duskclient$snapSneak(CallbackInfo ci) {
        if (entity == null || !LegacyCombat.oldSneak()) return;
        Pose pose = entity.getPose();
        if (pose != Pose.STANDING && pose != Pose.CROUCHING) return;
        eyeHeight = entity.getEyeHeight(duskclient$crouching(pose) ? Pose.CROUCHING : Pose.STANDING);
    }

    /**
     * Follows the key rather than the pose, because the server's echo of your
     * pose can flip it back for a tick and the snap would show that as a
     * bounce (MC-248973). A pose that stays different is real: no room to
     * stand up, for one. Shift doesn't crouch while flying or riding.
     */
    @Unique
    private boolean duskclient$crouching(Pose pose) {
        boolean posed = pose == Pose.CROUCHING;
        LocalPlayer player = Minecraft.getInstance().player;
        if (entity != player || player.getAbilities().flying || player.isPassenger()) return posed;
        boolean held = player.input.keyPresses.shift();
        if (posed == held) {
            duskclient$disagreeTicks = 0;
            return held;
        }
        return ++duskclient$disagreeTicks > 5 ? posed : held;
    }
}
