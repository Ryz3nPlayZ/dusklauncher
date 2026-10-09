package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Input;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Lunar's Scrollable Tooltips and the Scrollable Tooltips / SimpleScrollToolTips
 * mods: a tooltip too tall (or wide) for the screen can be scrolled with
 * Ctrl and the mouse wheel, so long book, shulker or modded-item tooltips can
 * be read to the end. Ctrl keeps the plain wheel for Scroll Transfer.
 * Written for Dusk; no code from those mods.
 */
public class ScrollableTooltips extends Module {
    /** a tooltip not drawn for this long is gone, and the next one starts unscrolled */
    private static final long GONE_MS = 150;
    private static final int MARGIN = 4;
    private static ScrollableTooltips instance;

    private final IntSetting speed = add(new IntSetting("speed", "Pixels per notch", 12, 4, 40, 2, " px"));
    private final BoolSetting noKey = add(new BoolSetting("nokey", "Scroll without Ctrl (takes the wheel from Scroll Transfer)", false));

    private int offX, offY;
    /** the last tooltip's size, to tell a new tooltip from the same one */
    private int lastW, lastH;
    private long lastDrawn;
    private boolean overflows;

    public ScrollableTooltips() {
        super("scrolltooltips", "Scrollable Tooltips", Category.MISC,
                "Hold Ctrl and scroll to read a tooltip that doesn't fit on the screen.");
        instance = this;
    }

    /**
     * Where to draw a {@code w}×{@code h} tooltip the game placed at
     * ({@code x}, {@code y}): moved by the scroll so far, kept so it can't
     * leave the screen entirely. {@code out[0..1]} gets the position.
     */
    public static void place(int screenW, int screenH, int x, int y, int w, int h, int[] out) {
        ScrollableTooltips m = instance;
        out[0] = x;
        out[1] = y;
        if (m == null || !m.enabled()) return;
        long now = System.currentTimeMillis();
        if (now - m.lastDrawn > GONE_MS || w != m.lastW || h != m.lastH) {
            m.offX = m.offY = 0;
            m.lastW = w;
            m.lastH = h;
        }
        m.lastDrawn = now;
        m.overflows = y + h > screenH - MARGIN || y < MARGIN || x + w > screenW - MARGIN || x < MARGIN;
        // only as far as it takes to bring each edge on screen
        m.offY = clamp(m.offY, Math.min(0, screenH - MARGIN - (y + h)), Math.max(0, MARGIN - y));
        m.offX = clamp(m.offX, Math.min(0, screenW - MARGIN - (x + w)), Math.max(0, MARGIN - x));
        out[0] = x + m.offX;
        out[1] = y + m.offY;
    }

    /** One wheel event; true when it moved a tooltip and the wheel is spent. */
    public static boolean scroll(double horizontal, double vertical) {
        ScrollableTooltips m = instance;
        if (m == null || !m.enabled() || !m.overflows || System.currentTimeMillis() - m.lastDrawn > GONE_MS) return false;
        if (!m.noKey.get() && !(Input.keyDown(InputConstants.KEY_LCONTROL) || Input.keyDown(InputConstants.KEY_RCONTROL))) return false;
        int step = m.speed.get();
        // wheel down reads further down, so the tooltip moves up
        m.offY += (int) Math.round(vertical * step);
        m.offX += (int) Math.round(horizontal * step);
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
