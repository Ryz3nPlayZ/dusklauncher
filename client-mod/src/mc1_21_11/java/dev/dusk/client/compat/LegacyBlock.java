package dev.dusk.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dusk.client.modules.render.LegacyCombat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Whether Legacy Combat is holding your sword up, and how it is held. Worked
 * out from the use key rather than from using an item, because on most
 * servers a sword can't be used at all. Ported from old-animations (MIT, see
 * NOTICE); there is no fade, the pose is on or off as it was in 1.8.
 */
public final class LegacyBlock {
    public static final boolean SUPPORTED = true;

    private static boolean blocking;
    /** The click opened or placed something, so holding it down isn't a block. */
    private static boolean useConsumed;

    private LegacyBlock() {}

    public static boolean blocking() {
        return blocking;
    }

    public static void useConsumed() {
        useConsumed = true;
    }

    public static void reset() {
        blocking = false;
        useConsumed = false;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean held = mc.options.keyUse.isDown();
        if (!held) useConsumed = false;
        blocking = held && !useConsumed && canBlock(mc);
    }

    private static boolean canBlock(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || Compat.currentScreen(mc) != null) return false;
        if (player.isSpectator() || player.isAutoSpinAttack()) return false;
        // something usable in the off hand (a shield, food) takes the right click
        if (player.getOffhandItem().getUseAnimation() != ItemUseAnimation.NONE) return false;
        ItemStack main = player.getMainHandItem();
        ItemUseAnimation use = main.getUseAnimation();
        return main.is(ItemTags.SWORDS) && (use == ItemUseAnimation.NONE || use == ItemUseAnimation.BLOCK);
    }

    /**
     * The 1.8 block, on top of the normal held-item transform. These constants
     * are that game's translate and three rotations folded together for the
     * modern item pipeline.
     */
    public static void applyPose(PoseStack pose, HumanoidArm arm, float attack) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        if (attack > 0f && LegacyCombat.blockHit()) {
            float swing = Mth.sin(attack * attack * (float) Math.PI);
            float lift = Mth.sin(Mth.sqrt(attack) * (float) Math.PI);
            turn(pose, Axis.YP.rotationDegrees(side * 45f));
            turn(pose, Axis.YP.rotationDegrees(side * -swing * 20f));
            turn(pose, Axis.ZP.rotationDegrees(side * -lift * 20f));
            turn(pose, Axis.XP.rotationDegrees(-lift * 80f));
            turn(pose, Axis.YP.rotationDegrees(side * -45f));
        }
        pose.translate(side * -0.14142136f, 0.08f, 0.14142136f);
        turn(pose, new Quaternionf()
                .rotateY((float) Math.toRadians(side * 75f))
                .rotateX((float) Math.toRadians(-80f))
                .rotateY((float) Math.toRadians(side * 15f)));
    }

    /** 26.3 took away mulPose(quaternion); the matrix form is on every target. */
    private static void turn(PoseStack pose, Quaternionf rotation) {
        pose.mulPose(new Matrix4f().rotation(rotation));
    }
}
