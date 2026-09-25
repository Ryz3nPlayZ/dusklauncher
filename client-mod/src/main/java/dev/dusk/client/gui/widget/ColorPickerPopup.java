package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.ColorSetting;
import org.lwjgl.glfw.GLFW;

/**
 * Flex-HUD's colour picker: a saturation/value square, a hue bar, an alpha
 * bar and a hex box, on a dark panel beside the swatch that opened it.
 */
public class ColorPickerPopup implements Popup {
    private static final int PAD = 3, SV = 80, BAR = 8, FIELD_H = 16;
    private static final int W = PAD + SV + PAD + BAR + PAD + BAR + PAD, H = PAD + SV + PAD + FIELD_H + PAD;
    private static final int BG = 0xFF1E1F22;

    private final int ax, ay, as;
    private final ColorSetting setting;
    private final Runnable onChange;
    private float hue, sat, val;
    private int alpha;
    private int x, y, dragging; // 1 square, 2 hue, 3 alpha
    private boolean editing, closed;
    private String buffer;

    public ColorPickerPopup(int anchorX, int anchorY, int anchorSize, ColorSetting setting, Runnable onChange) {
        this.ax = anchorX;
        this.ay = anchorY;
        this.as = anchorSize;
        this.setting = setting;
        this.onChange = onChange;
        int argb = setting.argb();
        float[] hsv = toHsv(argb);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = argb >>> 24;
        buffer = setting.hex();
    }

    private void place(int sw, int sh) {
        x = ax + as + 4;
        y = ay;
        if (x + W > sw - 2) { // no room on the right: go under the swatch
            x = Math.max(2, ax + as - W);
            y = ay + as + 4;
            if (y + H > sh - 2) y = ay - H - 4;
        }
        y = Math.max(2, Math.min(sh - H - 2, y));
    }

    private int svX() { return x + PAD; }
    private int svY() { return y + PAD; }
    private int hueX() { return svX() + SV + PAD; }
    private int alphaX() { return hueX() + BAR + PAD; }
    private int fieldY() { return svY() + SV + PAD; }

    @Override
    public void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        place(screenW, screenH);
        c.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF000000);
        c.fill(x, y, x + W, y + H, BG);

        // saturation across, value down: each column fades its full-value colour to black
        int sx = svX(), sy = svY();
        for (int i = 0; i < SV; i++) {
            int top = 0xFF000000 | hsv(hue, i / (float) (SV - 1), 1f);
            c.fillGradient(sx + i, sy, sx + i + 1, sy + SV, top, 0xFF000000);
        }
        int cx = sx + Math.round(sat * (SV - 1)), cy = sy + Math.round((1 - val) * (SV - 1));
        c.outline(cx - 2, cy - 2, 5, 5, 0xFF000000);
        c.outline(cx - 1, cy - 1, 3, 3, 0xFFFFFFFF);

        int hx = hueX();
        for (int i = 0; i < 6; i++) {
            int y0 = sy + i * SV / 6, y1 = sy + (i + 1) * SV / 6;
            c.fillGradient(hx, y0, hx + BAR, y1, 0xFF000000 | hsv(i / 6f, 1, 1), 0xFF000000 | hsv((i + 1) / 6f, 1, 1));
        }
        marker(c, hx, sy + Math.round(hue * (SV - 1)));

        int alx = alphaX(), rgb = hsv(hue, sat, val);
        ColorWidget.checker(c, alx, sy, BAR, SV);
        c.fillGradient(alx, sy, alx + BAR, sy + SV, 0xFF000000 | rgb, rgb);
        marker(c, alx, sy + Math.round((1 - alpha / 255f) * (SV - 1)));

        int fy = fieldY(), fw = W - 2 * PAD - FIELD_H - PAD;
        Vanilla.editBox(c, sx, fy, fw, FIELD_H, editing);
        String shown = "#" + (editing ? buffer : setting.hex()) + (editing && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        c.text(shown, sx + 4, fy + (FIELD_H - 8) / 2 + 1, editing ? 0xFFE0E0E0 : Vanilla.TEXT_DIM, true);
        ColorWidget.swatch(c, sx + fw + PAD, fy, FIELD_H, setting.argb(), false);
    }

    private static void marker(Canvas c, int barX, int my) {
        c.fill(barX - 1, my - 1, barX + BAR + 1, my + 2, 0xFF000000);
        c.fill(barX - 1, my, barX + BAR + 1, my + 1, 0xFFFFFFFF);
    }

    @Override
    public void click(double mx, double my, int button) {
        if (!Vanilla.inside(mx, my, x, y, W, H)) {
            close();
            return;
        }
        if (button != 0) return;
        boolean inField = Vanilla.inside(mx, my, svX(), fieldY(), W - 2 * PAD, FIELD_H);
        if (editing && !inField) commitHex();
        editing = inField;
        if (inField) buffer = setting.hex();
        if (Vanilla.inside(mx, my, svX(), svY(), SV, SV)) dragging = 1;
        else if (Vanilla.inside(mx, my, hueX() - 1, svY(), BAR + 2, SV)) dragging = 2;
        else if (Vanilla.inside(mx, my, alphaX() - 1, svY(), BAR + 2, SV)) dragging = 3;
        drag(mx, my);
    }

    @Override
    public void drag(double mx, double my) {
        if (dragging == 0) return;
        float fx = clamp01((float) (mx - svX()) / (SV - 1)), fy = clamp01((float) (my - svY()) / (SV - 1));
        switch (dragging) {
            case 1 -> { sat = fx; val = 1 - fy; }
            case 2 -> hue = Math.min(fy, 0.999f);
            default -> alpha = Math.round((1 - fy) * 255);
        }
        apply();
    }

    @Override
    public void release() {
        dragging = 0;
    }

    private void apply() {
        int argb = alpha << 24 | hsv(hue, sat, val);
        if (argb == setting.argb()) return;
        setting.set(argb);
        onChange.run();
    }

    private void commitHex() {
        editing = false;
        if (!setting.setHex(buffer)) return;
        onChange.run();
        float[] hsv = toHsv(setting.argb());
        if (hsv[1] > 0 && hsv[2] > 0) hue = hsv[0]; // keep the hue on greys
        if (hsv[2] > 0) sat = hsv[1];
        val = hsv[2];
        alpha = setting.argb() >>> 24;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!editing) return false;
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> { if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1); }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitHex();
            case GLFW.GLFW_KEY_ESCAPE -> editing = false;
            default -> {}
        }
        return true;
    }

    @Override
    public void charTyped(char ch) {
        if (editing && buffer.length() < 8 && Character.digit(ch, 16) >= 0) buffer += Character.toUpperCase(ch);
    }

    @Override
    public void close() {
        if (editing) commitHex();
        closed = true;
    }

    @Override
    public boolean closed() { return closed; }

    private static float clamp01(float f) {
        return Math.max(0, Math.min(1, f));
    }

    /** HSV (all 0..1) to 0xRRGGBB. */
    static int hsv(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6;
        int i = (int) hh;
        float f = hh - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    static float[] toHsv(int argb) {
        float r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }
}
