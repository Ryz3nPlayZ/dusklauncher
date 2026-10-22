package dev.dusk.client.modules.render;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Fancy Third Person: an over-the-shoulder F5 view, after Leawind's Third
 * Person (MIT). The mouse turns the camera rather than the player; the
 * player walks the way you press relative to the camera, turns to face the
 * crosshair when you hit, use or aim, and the crosshair shows in third person.
 * Only the back view is changed, and it steps aside for Freecam, Freelook and for
 * spectating another entity. The camera itself lives in the 1.21.11+ layers
 * (render/thirdperson), so this is only registered there.
 */
public class FancyThirdPerson extends Module {
    public static final String RIGHT = "Right", LEFT = "Left", CENTER = "Center";
    public static final String LOOK = "Look at crosshair", STAY = "Stay put";

    private static FancyThirdPerson instance;

    private final ChoiceSetting shoulder = add(new ChoiceSetting("shoulder", "Shoulder", RIGHT, RIGHT, LEFT, CENTER), "Camera");
    private final IntSetting distance = add(new IntSetting("distance", "Distance", 160, 50, 500, 5, " blocks").decimals(2), "Camera");
    private final IntSetting side = add(new IntSetting("side", "Side offset", 15, 0, 50, 1, "%"), "Camera");
    private final IntSetting height = add(new IntSetting("height", "Height offset", 12, 0, 40, 1, "%"), "Camera");
    private final BoolSetting smooth = add(new BoolSetting("smooth", "Smooth follow", true), "Camera");
    private final BoolSetting aimZoom = add(new BoolSetting("aimZoom", "Move in while aiming", true), "Camera");
    private final ChoiceSetting idle = add(new ChoiceSetting("idle", "Standing still", LOOK, LOOK, STAY), "Player");
    private final BoolSetting faceTarget = add(new BoolSetting("faceTarget", "Face the crosshair to hit and use", true), "Player");
    private final BoolSetting crosshair = add(new BoolSetting("crosshair", "Show crosshair", true), "Other");
    private final BoolSetting skipFront = add(new BoolSetting("skipFront", "F5 skips the front view", true), "Other");
    private final KeySetting swapKey = add(new KeySetting("thirdperson_swap", "Swap shoulder key", Input.UNKNOWN), "Keybinds");

    private boolean swapDown;

    public FancyThirdPerson() {
        super("fancythirdperson", "Fancy Third Person", Category.RENDER,
                "Over-the-shoulder F5: walk where the camera points, face what you hit or aim at.");
        instance = this;
    }

    public static FancyThirdPerson instance() { return instance; }

    /** The module while it drives the camera: enabled, back view, your own player, no Freecam or Freelook. */
    public static FancyThirdPerson active() {
        FancyThirdPerson m = instance;
        if (m == null || !m.enabled() || Freecam.active() != null || Freelook.looking() != null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return null;
        return mc.getCameraEntity() == mc.player ? m : null;
    }

    /** True when the crosshair should draw although the view is third person. */
    public static boolean showCrosshair() {
        FancyThirdPerson m = active();
        return m != null && m.crosshair.get();
    }

    /** True when F5 should go from the back view straight to first person. */
    public static boolean skipsFrontView() {
        FancyThirdPerson m = instance;
        return m != null && m.enabled() && m.skipFront.get();
    }

    /** -1 for the left shoulder, 1 for the right, 0 centred. */
    public int shoulderSign() { return shoulder.is(CENTER) ? 0 : shoulder.is(LEFT) ? -1 : 1; }

    public double distance() { return distance.asFloat(); }

    /** How far the player sits from the screen centre, as a fraction of half the screen. */
    public double sideOffset() { return side.get() / 100.0; }

    public double heightOffset() { return height.get() / 100.0; }

    public boolean smooth() { return smooth.get(); }

    public boolean aimZoom() { return aimZoom.get(); }

    public boolean lookWhenIdle() { return idle.is(LOOK); }

    public boolean faceTarget() { return faceTarget.get(); }

    @Override
    public void tick() {
        boolean down = swapKey.mapping().isDown();
        if (down && !swapDown && Compat.currentScreen(Minecraft.getInstance()) == null) {
            shoulder.set(shoulder.is(LEFT) ? RIGHT : LEFT);
        }
        swapDown = down;
    }
}
