package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/** WASD + mouse buttons + space bar, lit while held. */
public class Keystrokes extends HudElement {
    private static final int KEY = 20, GAP = 2;

    private final BoolSetting showMouse = add(new BoolSetting("showMouse", "Show mouse buttons", true));
    private final BoolSetting showSpace = add(new BoolSetting("showSpace", "Show space bar", true));
    private final BoolSetting showCps = add(new BoolSetting("showCps", "CPS on mouse buttons", true));
    private final ColorSetting keyColor = add(new ColorSetting("keyColor", "Key colour", 0x80000000));
    private final ColorSetting pressedColor = add(new ColorSetting("pressedColor", "Pressed colour", 0xC0FFFFFF));
    private final ColorSetting textColor = add(new ColorSetting("textColor", "Text colour", 0xFFFFFFFF));
    private final ColorSetting pressedTextColor = add(new ColorSetting("pressedTextColor", "Pressed text colour", 0xFF000000));

    public Keystrokes() {
        super("keystrokes", "Keystrokes", "Shows which movement keys and mouse buttons are held.");
        setPosition(5, 125);
        setEnabled(true);
    }

    @Override
    public int width(HudContext ctx) {
        return KEY * 3 + GAP * 2;
    }

    @Override
    public int height(HudContext ctx) {
        int rows = 2 + (showMouse.get() ? 1 : 0);
        int h = rows * KEY + (rows - 1) * GAP;
        if (showSpace.get()) h += GAP + KEY / 2;
        return h;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        Options o = ctx.mc().options;
        int row = 0;
        key(c, KEY + GAP, 0, KEY, KEY, "W", down(o.keyUp));
        row += KEY + GAP;
        key(c, 0, row, KEY, KEY, "A", down(o.keyLeft));
        key(c, KEY + GAP, row, KEY, KEY, "S", down(o.keyDown));
        key(c, (KEY + GAP) * 2, row, KEY, KEY, "D", down(o.keyRight));
        row += KEY + GAP;
        if (showMouse.get()) {
            int w = (KEY * 3 + GAP * 2 - GAP) / 2;
            String l = showCps.get() ? "LMB " + ClickTracker.leftCps() : "LMB";
            String r = showCps.get() ? "RMB " + ClickTracker.rightCps() : "RMB";
            key(c, 0, row, w, KEY, l, down(o.keyAttack));
            key(c, w + GAP, row, w, KEY, r, down(o.keyUse));
            row += KEY + GAP;
        }
        if (showSpace.get()) {
            int w = KEY * 3 + GAP * 2;
            boolean pressed = down(o.keyJump);
            c.fill(0, row, w, row + KEY / 2, pressed ? pressedColor.argb() : keyColor.argb());
            int barColor = pressed ? pressedTextColor.argb() : textColor.argb();
            c.fill(w / 2 - 8, row + KEY / 4 - 1, w / 2 + 8, row + KEY / 4 + 1, barColor);
        }
    }

    private static boolean down(KeyMapping k) {
        return k.isDown();
    }

    private void key(Canvas c, int x, int y, int w, int h, String label, boolean pressed) {
        c.fill(x, y, x + w, y + h, pressed ? pressedColor.argb() : keyColor.argb());
        c.centeredText(label, x + w / 2, y + (h - c.lineHeight()) / 2 + 1,
                pressed ? pressedTextColor.argb() : textColor.argb(), false);
    }
}
