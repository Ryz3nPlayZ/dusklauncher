package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.util.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** How far away the last entity you hit was, back to 0 five seconds later. */
public class Reach extends TextHud {
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 2, 0, 4));

    private static double reach;
    private static long lastHitMs = -1;

    public Reach() {
        super("reach", "Reach", "Distance to the last entity you attacked.");
        setPosition(150, 93);
    }

    /**
     * Every attack the game sends (from Forge's AttackEntityEvent), measured
     * where it hit: the crosshair's spot on the target, or else where the look
     * line enters its box, or its nearest point.
     */
    public static void onAttack(EntityPlayer player, Entity target) {
        if (!(target instanceof EntityLivingBase)) return;
        Minecraft mc = Minecraft.getMinecraft();
        Vec3 eye = player.getPositionEyes(1);
        AxisAlignedBB box = target.getEntityBoundingBox().expand(target.getCollisionBorderSize(),
                target.getCollisionBorderSize(), target.getCollisionBorderSize());
        Vec3 at = null;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && hit.entityHit == target) {
            at = hit.hitVec;
        } else if (!box.isVecInside(eye)) {
            Vec3 look = player.getLook(1);
            MovingObjectPosition clip = box.calculateIntercept(eye, eye.addVector(look.xCoord * 8, look.yCoord * 8, look.zCoord * 8));
            if (clip != null) at = clip.hitVec;
        }
        if (at == null) {
            at = new Vec3(clamp(eye.xCoord, box.minX, box.maxX), clamp(eye.yCoord, box.minY, box.maxY),
                    clamp(eye.zCoord, box.minZ, box.maxZ));
        }
        reach = at.distanceTo(eye);
        lastHitMs = System.currentTimeMillis();
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        double shown = lastHitMs == -1 || System.currentTimeMillis() - lastHitMs > 5000 ? 0 : reach;
        return Fmt.fixed(shown, digits.get()) + " blocks";
    }

    @Override
    protected String sample() { return Fmt.fixed(0.0, digits.get()) + " blocks"; }
}
