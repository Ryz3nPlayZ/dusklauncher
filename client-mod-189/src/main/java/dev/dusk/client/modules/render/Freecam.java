package dev.dusk.client.modules.render;

import com.mojang.authlib.GameProfile;
import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.util.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.FoodStats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Freecam: F4 lifts the camera off your player so you can fly it around
 * while you stand still. Movement keys steer the camera, your player gets no
 * input and can't attack or use items. The camera collides with blocks, so
 * it can't see through walls; "Fly through blocks" lifts that in
 * singleplayer only. Ends on death, world change, or F4 again.
 *
 * 1.8.9 port: the camera is a client-only player entity (never added to the
 * world) set as the render view entity, so terrain, culling, sounds and the
 * crosshair ray all follow it the way vanilla's spectator view does. The
 * HUD reads health/food/armour off the view entity, so the camera mirrors
 * the real player's. Independent implementation.
 */
public class Freecam extends Module {
    private static Freecam active;

    private final KeySetting toggleKey = add(new KeySetting("freecam", "Freecam key", Keyboard.KEY_F4), "Keybinds");
    private final IntSetting speed = add(new IntSetting("speed", "Fly speed", 10, 1, 50, 1, " b/s"), "Movement");
    private final BoolSetting followLook = add(new BoolSetting("followLook", "Fly where you look", false), "Movement");
    private final BoolSetting noclip = add(new BoolSetting("noclip", "Fly through blocks (singleplayer)", false), "Movement");

    private Camera camera;
    private World level;
    private MovementInput savedInput;
    private int savedView;
    private double vx, vy, vz;
    private float yaw, pitch;

    public Freecam() {
        super("freecam", "Freecam", Category.RENDER,
                "Press F4 to fly the camera around while your player stays put.");
        setEnabled(true);
    }

    /** The module while it owns the camera, else null. */
    public static Freecam active() { return active; }

    /** Mouse movement, in the units {@code EntityPlayerSP.setAngles} takes. */
    public void turn(float dYaw, float dPitch) {
        yaw += dYaw * 0.15F;
        pitch = MathHelper.clamp_float(pitch - dPitch * 0.15F, -90F, 90F);
        if (camera != null) {
            camera.rotationYaw = camera.prevRotationYaw = camera.rotationYawHead = camera.prevRotationYawHead = yaw;
            camera.rotationPitch = camera.prevRotationPitch = pitch;
        }
    }

    /** The toggle key and the camera's movement, every client tick. */
    public void tickKeys(Minecraft mc) {
        EntityPlayerSP player = mc.thePlayer;
        while (toggleKey.binding().isPressed()) {
            // a key shared with freelook stays freelook's
            if (player == null || mc.theWorld == null || !enabled() || Freelook.usesKey(toggleKey.binding())) continue;
            if (active == this) stop(); else start(mc, player);
            mc.ingameGUI.setRecordPlaying(active == this ? "Freecam on" : "Freecam off", false);
        }
        if (active != this) return;
        if (player == null || !enabled() || player.isDead || player.getHealth() <= 0 || mc.theWorld != level
                || mc.getRenderViewEntity() != camera) {
            stop();
            return;
        }
        mirror(player);
        move(mc);
    }

    @Override
    protected void onDisable() {
        if (active == this) stop();
    }

    private void start(Minecraft mc, EntityPlayerSP player) {
        level = mc.theWorld;
        camera = new Camera(level, player.getGameProfile());
        camera.inventory = player.inventory; // hotbar and armour bar read the view entity
        double ex = player.posX, ey = player.posY + player.getEyeHeight(), ez = player.posZ;
        yaw = player.rotationYaw;
        pitch = player.rotationPitch;
        camera.place(ex, ey, ez, true);
        turn(0, 0);
        mirror(player);
        vx = vy = vz = 0;

        savedInput = player.movementInput;
        MovementInput still = new MovementInput();
        still.sneak = savedInput != null && savedInput.sneak; // stay crouched on an edge
        player.movementInput = still;
        if (mc.playerController != null) mc.playerController.resetBlockRemoving();

        savedView = mc.gameSettings.thirdPersonView;
        mc.gameSettings.thirdPersonView = 0;
        active = this;
        mc.setRenderViewEntity(camera);
    }

    private void stop() {
        if (active != this) return;
        active = null;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player != null) {
            if (savedInput != null && player.movementInput != savedInput) player.movementInput = savedInput;
            if (mc.getRenderViewEntity() == camera) mc.setRenderViewEntity(player);
        }
        if (mc.gameSettings.thirdPersonView == 0) mc.gameSettings.thirdPersonView = savedView;
        savedInput = null;
        camera = null;
        level = null;
    }

    /** What the HUD reads off the view entity: health, absorption, air, food (via {@link Camera}). */
    private void mirror(EntityPlayerSP player) {
        camera.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(player.getMaxHealth());
        camera.setHealth(player.getHealth());
        camera.setAbsorptionAmount(player.getAbsorptionAmount());
        camera.setAir(player.getAir());
    }

    private static boolean held(KeyBinding k) {
        return Keys.isDown(k.getKeyCode());
    }

    private void move(Minecraft mc) {
        GameSettings o = mc.gameSettings;
        // physical keys: vanilla's key state can be latched by Toggle Sprint/Sneak
        boolean free = mc.currentScreen == null;
        float forward = !free ? 0 : (held(o.keyBindForward) ? 1 : 0) - (held(o.keyBindBack) ? 1 : 0);
        float left = !free ? 0 : (held(o.keyBindLeft) ? 1 : 0) - (held(o.keyBindRight) ? 1 : 0);
        float up = !free ? 0 : (held(o.keyBindJump) ? 1 : 0) - (held(o.keyBindSneak) ? 1 : 0);

        float yawRad = yaw * (float) Math.PI / 180F;
        float sin = MathHelper.sin(yawRad), cos = MathHelper.cos(yawRad);
        double ax, ay, az;
        if (followLook.get()) {
            Vec3 look = camera.getLookVec();
            ax = look.xCoord; ay = look.yCoord; az = look.zCoord;
        } else {
            ax = -sin; ay = 0; az = cos;
        }
        double wx = ax * forward + cos * left, wy = ay * forward + up, wz = az * forward + sin * left;
        double perTick = speed.get() / 20.0 * (free && held(o.keyBindSprint) ? 2.5 : 1);
        double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
        double tx = 0, ty = 0, tz = 0;
        if (len > 1e-3) {
            tx = wx / len * perTick; ty = wy / len * perTick; tz = wz / len * perTick;
        }
        vx += (tx - vx) * 0.45; vy += (ty - vy) * 0.45; vz += (tz - vz) * 0.45;
        if (vx * vx + vy * vy + vz * vz < 1e-6) vx = vy = vz = 0;

        double dx = vx, dy = vy, dz = vz;
        boolean through = noclip.get() && mc.isSingleplayer();
        if (!through && (dx != 0 || dy != 0 || dz != 0)) {
            AxisAlignedBB box = camera.box();
            List<AxisAlignedBB> hits = level.getCollidingBoundingBoxes(camera, box.addCoord(dx, dy, dz));
            for (AxisAlignedBB bb : hits) dy = bb.calculateYOffset(box, dy);
            box = box.offset(0, dy, 0);
            for (AxisAlignedBB bb : hits) dx = bb.calculateXOffset(box, dx);
            box = box.offset(dx, 0, 0);
            for (AxisAlignedBB bb : hits) dz = bb.calculateZOffset(box, dz);
            if (dx != vx) vx = 0;
            if (dy != vy) vy = 0;
            if (dz != vz) vz = 0;
        }
        camera.place(camera.posX + dx, camera.posY + Camera.EYE + dy, camera.posZ + dz, false);
    }

    /**
     * The flying camera: a 0.3-block box with its eye at the centre, never
     * added to the world (so it doesn't tick, collide, render or make sound).
     */
    static final class Camera extends EntityOtherPlayerMP {
        static final float EYE = 0.15F;

        Camera(World world, GameProfile profile) {
            super(world, profile);
            setSize(0.3F, 0.3F);
            noClip = true;
            stepHeight = 0;
        }

        /** Moves the eye to (x, y, z); {@code snap} also resets the frame interpolation. */
        void place(double x, double y, double z, boolean snap) {
            double feetY = y - EYE;
            if (snap) {
                prevPosX = lastTickPosX = x;
                prevPosY = lastTickPosY = feetY;
                prevPosZ = lastTickPosZ = z;
            } else {
                prevPosX = lastTickPosX = posX;
                prevPosY = lastTickPosY = posY;
                prevPosZ = lastTickPosZ = posZ;
            }
            setPosition(x, feetY, z);
            chunkCoordX = MathHelper.floor_double(x) >> 4;
            chunkCoordY = MathHelper.floor_double(feetY) >> 4;
            chunkCoordZ = MathHelper.floor_double(z) >> 4;
        }

        AxisAlignedBB box() {
            return getEntityBoundingBox();
        }

        @Override
        public float getEyeHeight() {
            return EYE;
        }

        @Override
        protected boolean canTriggerWalking() {
            return false;
        }

        @Override
        public FoodStats getFoodStats() {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            return p != null ? p.getFoodStats() : super.getFoodStats();
        }

        @Override
        public boolean isSpectator() {
            return false;
        }
    }
}
