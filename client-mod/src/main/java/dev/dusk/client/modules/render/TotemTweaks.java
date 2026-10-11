package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Everything about the Totem of Undying in one place: the pop animation's
 * size, position, length, spin and zoom, the totem held in first person, and
 * the particles a pop gives off. The option set follows Kairo's Totem
 * Customizer and Totem Tweaks (no source / ARR, behaviour only), the pop
 * offset follows CustomizableTotemPop (GPL-3.0, behaviour only), and the held
 * size and particle hiding follow TotemTweaker (MIT, see NOTICE). Replaces
 * the old Totem Pop module and the Totem entry of Particles.
 */
public class TotemTweaks extends Module {
    private static TotemTweaks instance;

    /** Vanilla's pop length in ticks. */
    private static final int VANILLA_TICKS = 40;
    /** The first-person projection's vertical field of view (vanilla draws hands and the pop at 70). */
    private static final float HALF_FOV_TAN = (float) Math.tan(Math.toRadians(35));
    /** Vanilla totem particle colours, at the middle of their random range. */
    private static final int VANILLA_GREEN = 0xFF338C1A, VANILLA_GOLD = 0xFFB3BF1A;

    private final BoolSetting hide = add(new BoolSetting("hide", "Hide pop", false));
    private final IntSetting size = add(new IntSetting("size", "Pop size", 100, 10, 300, 5, "%"));
    private final IntSetting offX = add(new IntSetting("offX", "Horizontal position", 0, -100, 100, 5, "%"));
    private final IntSetting offY = add(new IntSetting("offY", "Vertical position", 0, -100, 100, 5, "%"));
    private final BoolSetting centred = add(new BoolSetting("centred", "No random drift", false));

    private final IntSetting duration = add(new IntSetting("duration", "Length", 100, 25, 300, 5, "%"), "Animation");
    private final BoolSetting spin = add(new BoolSetting("spin", "Spin", true), "Animation");
    private final BoolSetting zoom = add(new BoolSetting("zoom", "Fly in and out", true), "Animation");

    private final IntSetting handSize = add(new IntSetting("handSize", "Held totem size", 100, 10, 200, 5, "%"), "Held totem");

    private final ChoiceSetting particles = add(new ChoiceSetting("particles", "Show particles", "All",
            "All", "Others' only", "None"), "Particles");
    private final IntSetting amount = add(new IntSetting("amount", "Amount", 100, 0, 500, 10, "%"), "Particles");
    private final IntSetting particleSize = add(new IntSetting("particleSize", "Size", 100, 10, 300, 5, "%"), "Particles");
    private final IntSetting speed = add(new IntSetting("speed", "Speed", 100, 0, 300, 5, "%"), "Particles");
    private final IntSetting lifetime = add(new IntSetting("lifetime", "Lifetime", 100, 10, 300, 5, "%"), "Particles");
    private final BoolSetting customColours = add(new BoolSetting("customColours", "Custom colours", false), "Particles");
    private final ColorSetting mainColour = add(new ColorSetting("mainColour", "Main colour", VANILLA_GREEN).opaque(), "Particles");
    private final ColorSetting accentColour = add(new ColorSetting("accentColour", "Accent colour (1 in 4)", VANILLA_GOLD).opaque(), "Particles");

    /** The length of the pop now playing, fixed when it starts so a change mid-pop can't make it jump. */
    private static int popTicks = VANILLA_TICKS;

    public TotemTweaks() {
        super("totemtweaks", "Totem Tweaks", Category.RENDER,
                "Resize, move, shorten or hide the totem pop, resize the held totem and restyle or hide pop particles.");
        instance = this;
        offX.visibleWhen(() -> !hide.get());
        offY.visibleWhen(() -> !hide.get());
        size.visibleWhen(() -> !hide.get());
        centred.visibleWhen(() -> !hide.get());
        spin.visibleWhen(() -> !hide.get());
        zoom.visibleWhen(() -> !hide.get());
        duration.visibleWhen(() -> !hide.get());
        for (var s : List.of(amount, particleSize, speed, lifetime)) s.visibleWhen(() -> !particles.is("None"));
        customColours.visibleWhen(() -> !particles.is("None"));
        mainColour.visibleWhen(() -> !particles.is("None") && customColours.get());
        accentColour.visibleWhen(() -> !particles.is("None") && customColours.get());
    }

    @Override
    public List<String> formerIds() {
        return List.of("totempop");
    }

    private static boolean on() {
        return instance != null && instance.enabled();
    }

    // --- pop animation ---

    public static boolean hidden() {
        return on() && instance.hide.get();
    }

    /** The factor the popping item's scale is multiplied by. */
    public static float scale() {
        return on() ? instance.size.get() / 100f : 1f;
    }

    /** True when the pop should not wander off to a random side. */
    public static boolean centred() {
        return on() && instance.centred.get();
    }

    /** Called as a pop starts: the length it will run for, in ticks. */
    public static int startPop() {
        popTicks = on() ? Math.max(1, Math.round(VANILLA_TICKS * instance.duration.get() / 100f)) : VANILLA_TICKS;
        return popTicks;
    }

    public static int popTicks() {
        return popTicks;
    }

    /** Vanilla's 900 degrees of spin over the pop, or none. */
    public static float spinDegrees(float vanilla) {
        return on() && !instance.spin.get() ? 0 : vanilla;
    }

    /**
     * The pop's translate with zoom and the set position applied. The position
     * is a fraction of the half-screen, so it lands in the same place at
     * whatever depth the zoom has the item.
     */
    public static float[] translate(float x, float y, float z) {
        if (!on()) return null;
        boolean move = instance.offX.get() != 0 || instance.offY.get() != 0;
        if (!move && instance.zoom.get()) return null;
        if (!instance.zoom.get()) z = -1f; // where vanilla's fly-in peaks
        if (move) {
            var window = Minecraft.getInstance().getWindow();
            float aspect = (float) window.getWidth() / Math.max(1, window.getHeight());
            float depth = Math.abs(z) * HALF_FOV_TAN;
            x += instance.offX.get() / 100f * depth * aspect;
            y += instance.offY.get() / 100f * depth;
        }
        return new float[] {x, y, z};
    }

    // --- held totem ---

    public static float handScale() {
        return on() ? instance.handSize.get() / 100f : 1f;
    }

    // --- particles ---

    /** True when a pop's particle emitter on this entity should not be made. */
    public static boolean hideParticles(Entity entity) {
        if (!on()) return false;
        return switch (instance.particles.get()) {
            case "None" -> true;
            case "Others' only" -> entity == Minecraft.getInstance().player;
            default -> false;
        };
    }

    /** Scales the emitter's particles per tick. */
    public static int amount(int vanilla) {
        return on() ? Math.round(vanilla * instance.amount.get() / 100f) : vanilla;
    }

    public static boolean tweaksParticles() {
        return on();
    }

    public static float particleSize() {
        return instance.particleSize.get() / 100f;
    }

    public static float particleSpeed() {
        return instance.speed.get() / 100f;
    }

    public static float particleLifetime() {
        return instance.lifetime.get() / 100f;
    }

    /** The colour for a new particle, or -1 for vanilla's; keeps vanilla's 1-in-4 accent and slight random shade. */
    public static int particleColour(float accentRoll, float shade) {
        if (!instance.customColours.get()) return -1;
        int c = (accentRoll < 0.25f ? instance.accentColour : instance.mainColour).get();
        float k = 0.85f + shade * 0.3f;
        int r = Math.min(255, Math.round((c >> 16 & 0xFF) * k));
        int g = Math.min(255, Math.round((c >> 8 & 0xFF) * k));
        int b = Math.min(255, Math.round((c & 0xFF) * k));
        return r << 16 | g << 8 | b;
    }
}
