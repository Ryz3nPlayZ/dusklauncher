package dev.dusk.client.modules.movement;

import dev.dusk.client.mixin.KeyBindingAccessor;
import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.util.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * 1.8.9 has no toggle sprint or sneak option, so this adds the 1.21 ones: a tap
 * of the key latches it on, the next tap lets go, and holding it works as
 * normal. The key itself is held down for the game, so everything that reads
 * it (sprinting, sneaking at edges) behaves as with the key held.
 * Behaviour after ToggleSprint by celestialfault (Zlib).
 */
public class ToggleSprint extends Module {
    private final BoolSetting sprint = add(new BoolSetting("toggleSprint", "Toggle sprint", true));
    private final BoolSetting sneak = add(new BoolSetting("toggleSneak", "Toggle sneak", false));

    private final Latch sprintLatch = new Latch();
    private final Latch sneakLatch = new Latch();

    public ToggleSprint() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT,
                "Tap sprint or sneak to keep it on, like the 1.21 options. Holding the key still works.");
        setEnabled(true);
    }

    /** Whether sprint is latched on (rather than held). */
    public boolean sprintToggled() { return enabled() && sprint.get() && sprintLatch.on; }

    public boolean sneakToggled() { return enabled() && sneak.get() && sneakLatch.on; }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        sprintLatch.release(mc.gameSettings.keyBindSprint);
        sneakLatch.release(mc.gameSettings.keyBindSneak);
    }

    /** Start of every client tick, before the player reads its keys. */
    public void preTick(Minecraft mc) {
        if (!enabled()) return;
        boolean playing = mc.thePlayer != null && mc.currentScreen == null;
        if (sprint.get()) sprintLatch.update(mc.gameSettings.keyBindSprint, playing);
        else sprintLatch.release(mc.gameSettings.keyBindSprint);
        if (sneak.get()) sneakLatch.update(mc.gameSettings.keyBindSneak, playing);
        else sneakLatch.release(mc.gameSettings.keyBindSneak);
        if (mc.thePlayer == null) {
            sprintLatch.on = false;
            sneakLatch.on = false;
        }
    }

    /** After each key event: a release of a latched key mustn't let go of it for a tick. */
    public void reassert(Minecraft mc) {
        if (!enabled() || mc.thePlayer == null || mc.currentScreen != null) return;
        if (sprint.get() && sprintLatch.on) ((KeyBindingAccessor) mc.gameSettings.keyBindSprint).dusk$setPressed(true);
        if (sneak.get() && sneakLatch.on) ((KeyBindingAccessor) mc.gameSettings.keyBindSneak).dusk$setPressed(true);
    }

    private static final class Latch {
        boolean on;
        private boolean wasDown;

        void update(KeyBinding key, boolean playing) {
            boolean down = playing && Keys.isDown(key.getKeyCode());
            if (down && !wasDown) on = !on;
            wasDown = down;
            if (on && playing) ((KeyBindingAccessor) key).dusk$setPressed(true);
        }

        void release(KeyBinding key) {
            if (on && !Keys.isDown(key.getKeyCode())) ((KeyBindingAccessor) key).dusk$setPressed(false);
            on = false;
            wasDown = false;
        }
    }
}
