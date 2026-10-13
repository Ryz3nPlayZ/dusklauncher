package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import java.util.HashMap;
import java.util.Map;

/**
 * Flex-HUD's keystrokes: WASD, a jump bar and LMB/RMB with CPS, each key a
 * tile that fades to the pressed colours (100 ms in, 400 ms out).
 */
public class Keystrokes extends TextHud {
    private static final int KEY = 22;
    private static final long FADE_IN = 100, FADE_OUT = 400;

    private final BoolSetting chromaPressed = add(new BoolSetting("chromaPressed", "Chroma when pressed", false));
    private final ColorSetting colorPressed = add(new ColorSetting("colorPressed", "Pressed text colour", 0xFF323232));
    private final BoolSetting backgroundPressed = add(new BoolSetting("backgroundPressed", "Pressed background", true));
    private final ColorSetting backgroundColorPressed = add(new ColorSetting("backgroundColorPressed", "Pressed background colour", 0xFFFFFFFF));
    private final BoolSetting showBorder = add(new BoolSetting("showBorder", "Border", false));
    private final ColorSetting borderColor = add(new ColorSetting("borderColor", "Border colour", 0xFFFFFFFF));
    private final BoolSetting displayCps = add(new BoolSetting("displayCps", "Mouse buttons with CPS", true));
    private final BoolSetting useArrow = add(new BoolSetting("useArrow", "Arrows instead of keys", false));

    /** When each key last went up or down, for the fade. */
    private final Map<KeyMapping, long[]> changedAt = new HashMap<>();
    private final Map<KeyMapping, Boolean> wasDown = new HashMap<>();

    public Keystrokes() {
        super("keystrokes", "Keystrokes", "Shows which movement keys and mouse buttons are held.", 0xFFFFFFFF, true);
        setPosition(5, 125);
        setEnabled(true);
    }

    /** Each key draws its own background tile, so the box behind the element stays off. */
    @Override
    public boolean background() {
        return false;
    }

    @Override
    protected String text(HudContext ctx) {
        return "";
    }

    /** Space between the tiles: the modern look's rounded tiles get a little more room. */
    private static int gap() {
        return Theme.modern() ? 2 : 1;
    }

    @Override
    public int width(HudContext ctx) {
        return KEY * 3 + gap() * 4;
    }

    @Override
    public int height(HudContext ctx) {
        int gap = gap();
        return displayCps.get() ? (int) (KEY * 3.5 + gap * 5) : (int) (KEY * 2.5 + gap * 4);
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        int gap = gap();
        Options o = ctx.mc().options;
        boolean arrows = useArrow.get();
        movementKey(c, ctx, KEY + gap * 2, gap, o.keyUp, arrows ? "▲" : label(o.keyUp));
        movementKey(c, ctx, KEY + gap * 2, KEY + gap * 2, o.keyDown, arrows ? "▼" : label(o.keyDown));
        movementKey(c, ctx, KEY * 2 + gap * 3, KEY + gap * 2, o.keyRight, arrows ? "▶" : label(o.keyRight));
        movementKey(c, ctx, gap, KEY + gap * 2, o.keyLeft, arrows ? "◀" : label(o.keyLeft));
        jumpKey(c, gap, KEY * 2 + gap * 3, KEY * 3 + gap * 2, KEY / 2, o.keyJump);
        if (displayCps.get()) {
            int y = (int) (KEY * 2.5) + gap * 4, w = (int) (KEY * 1.5) + gap / 2, x2 = (int) (KEY * 1.5 + gap * 2.5) + 1;
            if (gap > 1) {
                w = (KEY * 3 + gap) / 2;
                x2 = gap * 2 + w;
            }
            mouseKey(c, ctx, gap, y, w, o.keyAttack, ClickTracker.leftCps(), "LMB");
            mouseKey(c, ctx, x2, y, w, o.keyUse, ClickTracker.rightCps(), "RMB");
        }
    }

    private static String label(KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }

    /** Draws the tile and returns how far it has faded towards pressed (0..1). */
    private float key(Canvas c, int x, int y, int w, int h, KeyMapping k) {
        boolean down = k.isDown();
        long now = System.currentTimeMillis();
        long[] at = changedAt.computeIfAbsent(k, m -> new long[] {-1});
        if (wasDown.getOrDefault(k, down) != down) at[0] = now;
        wasDown.put(k, down);
        float elapsed = now - at[0];
        float fade = down ? Math.min(1f, elapsed / FADE_IN) : 1f - Math.min(1f, elapsed / FADE_OUT);

        int pressed = (int) (fade / 2 * 255) << 24 | backgroundColorPressed.argb() & 0xFFFFFF;
        if (Theme.modern()) {
            int r = Round.w(3);
            if (background.get()) Round.fill(c, x, y, w, h, r, backgroundColor.argb());
            if (backgroundPressed.get()) Round.fill(c, x, y, w, h, r, pressed);
            if (showBorder.get()) Round.ring(c, x, y, w, h, r, 0xFF000000 | borderColor.argb());
            return fade;
        }
        if (background.get()) c.fill(x, y, x + w, y + h, backgroundColor.argb());
        if (backgroundPressed.get()) c.fill(x, y, x + w, y + h, pressed);
        if (showBorder.get()) c.outline(x - 1, y - 1, w + 2, h + 2, 0xFF000000 | borderColor.argb());
        return fade;
    }

    private void movementKey(Canvas c, HudContext ctx, int x, int y, KeyMapping k, String label) {
        float fade = key(c, x, y, KEY, KEY, k);
        c.push();
        c.translate(x + (KEY - ctx.textWidth(label)) / 2f, y + (KEY - ctx.lineHeight()) / 2f);
        c.text(label, 0, 0, color(fade), shadow.get());
        c.pop();
    }

    private void jumpKey(Canvas c, int x, int y, int w, int h, KeyMapping k) {
        int color = color(key(c, x, y, w, h, k));
        int x1 = x + w / 4, y1 = y + h / 2 - 2, x2 = (int) (x + w * 0.75), y2 = y + h / 2;
        int shadowColor = (color & 0xFCFCFC) >> 2 | color & 0xFF000000;
        c.fill(x1 + 1, y1 + 1, x2 + 1, y2 + 1, shadowColor);
        c.fill(x1, y1, x2, y2, color);
    }

    private void mouseKey(Canvas c, HudContext ctx, int x, int y, int w, KeyMapping k, int cps, String label) {
        int color = color(key(c, x, y, w, KEY, k));
        c.push();
        c.translate(x + (w - ctx.textWidth(label)) / 2f, y + KEY / 2f - ctx.lineHeight() + 2);
        c.text(label, 0, 0, color, shadow.get());
        c.pop();
        String cpsLabel = cps + " CPS";
        // the smooth font stays sharp small, so it can sit a little larger and softer
        float small = Theme.sansOn() ? 0.75f : 0.7f;
        int cpsColor = Theme.sansOn() ? (color >>> 24) * 3 / 4 << 24 | color & 0xFFFFFF : color;
        c.push();
        c.translate(x + (w - ctx.textWidth(cpsLabel) * small) / 2f, y + KEY / 2f + 3);
        c.scale(small, small);
        c.text(cpsLabel, 0, 0, cpsColor, shadow.get());
        c.pop();
    }

    /** Text colour at a given fade, from the normal colour to the pressed one. */
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
