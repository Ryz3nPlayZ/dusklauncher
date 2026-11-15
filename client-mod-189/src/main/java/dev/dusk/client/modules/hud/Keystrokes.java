package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.util.ClickTracker;
import dev.dusk.client.util.Keys;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import java.util.HashMap;
import java.util.Map;

/** WASD, a jump bar and LMB/RMB with CPS, each key a tile that fades to the pressed colours (100 ms in, 400 ms out). */
public class Keystrokes extends TextHud {
    private static final int KEY = 22, GAP = 1;
    private static final long FADE_IN = 100, FADE_OUT = 400;

    private final BoolSetting chromaPressed = add(new BoolSetting("chromaPressed", "Chroma when pressed", false));
    private final ColorSetting colorPressed = add(new ColorSetting("colorPressed", "Pressed text colour", 0xFF323232));
    private final BoolSetting backgroundPressed = add(new BoolSetting("backgroundPressed", "Pressed background", true));
    private final ColorSetting backgroundColorPressed = add(new ColorSetting("backgroundColorPressed", "Pressed background colour", 0xFFFFFFFF));
    private final BoolSetting showBorder = add(new BoolSetting("showBorder", "Border", false));
    private final ColorSetting borderColor = add(new ColorSetting("borderColor", "Border colour", 0xFFFFFFFF));
    private final BoolSetting displayCps = add(new BoolSetting("displayCps", "Mouse buttons with CPS", true));
    private final BoolSetting useArrow = add(new BoolSetting("useArrow", "Arrows instead of keys", false));

    private final Map<KeyBinding, long[]> changedAt = new HashMap<KeyBinding, long[]>();
    private final Map<KeyBinding, Boolean> wasDown = new HashMap<KeyBinding, Boolean>();

    public Keystrokes() {
        super("keystrokes", "Keystrokes", "Shows which movement keys and mouse buttons are held.", 0xFFFFFFFF, true);
        setPosition(5, 125);
        setEnabled(true);
    }

    /** Each key draws its own background tile, so the box behind the element stays off. */
    @Override
    public boolean background() { return false; }

    @Override
    protected String text(HudContext ctx) { return ""; }

    @Override
    public int width(HudContext ctx) { return KEY * 3 + GAP * 4; }

    @Override
    public int height(HudContext ctx) {
        return displayCps.get() ? (int) (KEY * 3.5 + GAP * 5) : (int) (KEY * 2.5 + GAP * 4);
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        GameSettings o = ctx.mc().gameSettings;
        boolean arrows = useArrow.get();
        movementKey(c, ctx, KEY + GAP * 2, GAP, o.keyBindForward, arrows ? "▲" : label(o.keyBindForward));
        movementKey(c, ctx, KEY + GAP * 2, KEY + GAP * 2, o.keyBindBack, arrows ? "▼" : label(o.keyBindBack));
        movementKey(c, ctx, KEY * 2 + GAP * 3, KEY + GAP * 2, o.keyBindRight, arrows ? "▶" : label(o.keyBindRight));
        movementKey(c, ctx, GAP, KEY + GAP * 2, o.keyBindLeft, arrows ? "◀" : label(o.keyBindLeft));
        jumpKey(c, GAP, KEY * 2 + GAP * 3, KEY * 3 + GAP * 2, KEY / 2, o.keyBindJump);
        if (displayCps.get()) {
            int y = (int) (KEY * 2.5) + GAP * 4, w = (int) (KEY * 1.5) + GAP / 2, x2 = (int) (KEY * 1.5 + GAP * 2.5) + 1;
            mouseKey(c, ctx, GAP, y, w, o.keyBindAttack, ClickTracker.leftCps(), "LMB");
            mouseKey(c, ctx, x2, y, w, o.keyBindUseItem, ClickTracker.rightCps(), "RMB");
        }
    }

    private static String label(KeyBinding k) { return Keys.display(k.getKeyCode()); }

    /** Held, from the physical key, so toggle sprint/sneak latching doesn't show as pressed. */
    private static boolean down(KeyBinding k) { return Keys.isDown(k.getKeyCode()); }

    private float key(Canvas c, int x, int y, int w, int h, KeyBinding k) {
        boolean down = down(k);
        long now = System.currentTimeMillis();
        long[] at = changedAt.get(k);
        if (at == null) changedAt.put(k, at = new long[] {-1});
        Boolean was = wasDown.get(k);
        if (was != null && was != down) at[0] = now;
        wasDown.put(k, down);
        float elapsed = now - at[0];
        float fade = down ? Math.min(1f, elapsed / FADE_IN) : 1f - Math.min(1f, elapsed / FADE_OUT);
        if (at[0] < 0) fade = down ? 1 : 0;
        int pressed = (int) (fade / 2 * 255) << 24 | backgroundColorPressed.argb() & 0xFFFFFF;
        if (background.get()) c.fill(x, y, x + w, y + h, backgroundColor.argb());
        if (backgroundPressed.get()) c.fill(x, y, x + w, y + h, pressed);
        if (showBorder.get()) c.outline(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000 | borderColor.argb());
        return fade;
    }

    private void movementKey(Canvas c, HudContext ctx, int x, int y, KeyBinding k, String label) {
        float fade = key(c, x, y, KEY, KEY, k);
        c.text(label, x + (KEY - ctx.textWidth(label)) / 2f, y + (KEY - ctx.lineHeight()) / 2f + 1, color(fade), shadow.get());
    }

    private void jumpKey(Canvas c, int x, int y, int w, int h, KeyBinding k) {
        int color = color(key(c, x, y, w, h, k));
        int x1 = x + w / 4, y1 = y + h / 2 - 2, x2 = (int) (x + w * 0.75), y2 = y + h / 2;
        int shadowColor = (color & 0xFCFCFC) >> 2 | color & 0xFF000000;
        c.fill(x1 + 1, y1 + 1, x2 + 1, y2 + 1, shadowColor);
        c.fill(x1, y1, x2, y2, color);
    }

    private void mouseKey(Canvas c, HudContext ctx, int x, int y, int w, KeyBinding k, int cps, String label) {
        int color = color(key(c, x, y, w, KEY, k));
        c.text(label, x + (w - ctx.textWidth(label)) / 2f, y + KEY / 2f - ctx.lineHeight() + 2, color, shadow.get());
        String cpsLabel = cps + " CPS";
        float small = 0.7f;
        c.push();
        c.translate(x + (w - ctx.textWidth(cpsLabel) * small) / 2f, y + KEY / 2f + 3);
        c.scale(small, small);
        c.text(cpsLabel, 0, 0, color, shadow.get());
        c.pop();
    }

    private int color(float fade) {
        int from = textColor();
        int to = chromaPressed.get() ? chromaColor() : 0xFF000000 | colorPressed.argb();
        int out = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            int a = from >> shift & 0xFF, b = to >> shift & 0xFF;
            out |= Math.round(a + (b - a) * fade) << shift;
        }
        return out;
    }
}
