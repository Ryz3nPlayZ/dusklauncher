package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.ColorSetting;

/** Label, an editable AARRGGBB hex box and a swatch that opens the colour picker. */
public class ColorWidget extends SettingRow {
    private static final int FIELD_W = CONTROL_W - SQUARE - GAP;

    private final ColorSetting setting;
    private final Runnable onChange;
    private final PopupHost host;
    private String buffer;

    public ColorWidget(ColorSetting setting, Runnable onChange, PopupHost host) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
        this.host = host;
        this.buffer = setting.hex();
    }

    private int swatchX() { return controlX() + FIELD_W + GAP; }

    private boolean inField(double mx, double my) {
        return Vanilla.inside(mx, my, controlX(), top(), FIELD_W, SQUARE);
    }

    private boolean inSwatch(double mx, double my) {
        return Vanilla.inside(mx, my, swatchX(), top(), SQUARE, SQUARE);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        if (!focused) buffer = setting.hex(); // follow the picker and the reset button
        int fx = controlX(), fy = top();
        Px.field(c, fx, fy, FIELD_W, SQUARE, focused);
        if (Theme.modern()) {
            String hex = "#" + buffer;
            Px.sans(c, hex, fx + 8 - Px.pad(), fy, FIELD_W - 8 + Px.pad(), SQUARE, Px.Size.S16, Theme.TEXT, false, true);
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = fx + 8 + Math.round(Px.sansWidth(c, hex, Px.Size.S16, false)) + 1;
                Round.begin(c);
                int mid = Round.w(fy + SQUARE / 2.0), half = Round.w(4.5);
                c.fill(Round.w(cx), mid - half, Round.w(cx) + Px.line(), mid + half, Theme.TEXT);
                Round.end(c);
            }
            swatch(c, swatchX(), fy, SQUARE, setting.argb(), inSwatch(mouseX, mouseY));
            return;
        }
        String shown = "#" + buffer + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? Theme.modern() ? "|" : "_" : "");
        c.text(shown, fx + TextWidget.inset(), fy + 6, Theme.modern() ? Theme.TEXT : focused ? 0xFFE0E0E0 : Vanilla.TEXT_DIM,
                !Theme.modern());
        swatch(c, swatchX(), fy, SQUARE, setting.argb(), inSwatch(mouseX, mouseY));
    }

    /** Flex's colour square: grey outline (light on hover) around the colour over a checkerboard. */
    public static void swatch(Canvas c, int x, int y, int size, int argb, boolean hover) {
        if (Theme.modern()) {
            // a round chip: the colour laid over light grey so see-through colours still read
            Round.dot(c, x, y, size, hover ? Theme.LINE_3 : Theme.LINE_2);
            Round.dot(c, x + 2, y + 2, size - 4, over(argb, 0xFFC8C8C8));
            return;
        }
        c.fill(x, y, x + size, y + size, hover ? 0xFFD0D0D0 : 0xFF404040);
        checker(c, x + 1, y + 1, size - 2, size - 2);
        c.fill(x + 1, y + 1, x + size - 1, y + size - 1, argb);
    }

    private static int over(int argb, int base) {
        int a = argb >>> 24, r = 0xFF000000;
        for (int sh = 0; sh <= 16; sh += 8) {
            int f = argb >>> sh & 0xFF, b = base >>> sh & 0xFF;
            r |= (f * a + b * (255 - a)) / 255 << sh;
        }
        return r;
    }

    /** The transparency checkerboard, 3px squares. */
    public static void checker(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0xFFFFFFFF);
        for (int cy = 0; cy < h; cy += 3) {
            for (int cx = (cy / 3) % 2 * 3; cx < w; cx += 6) {
                c.fill(x + cx, y + cy, x + Math.min(w, cx + 3), y + Math.min(h, cy + 3), 0xFFBFBFBF);
            }
        }
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (inSwatch(mx, my) && button == 0) {
            setFocused(false);
            host.open(new ColorPickerPopup(swatchX(), top(), SQUARE, setting, onChange));
            return true;
        }
        boolean hit = inField(mx, my);
        setFocused(hit);
        if (hit && button == 1) buffer = "";
        return hit;
    }

    private void commit() {
        if (setting.setHex(buffer)) onChange.run();
        buffer = setting.hex();
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!focused) return false;
        if (key == InputConstants.KEY_BACKSPACE) {
            if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1);
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            setFocused(false);
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            buffer = setting.hex();
            super.setFocused(false);
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(char ch) {
        if (!focused) return false;
        if (buffer.length() < 8 && Character.digit(ch, 16) >= 0) buffer += Character.toUpperCase(ch);
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (this.focused && !focused) commit();
        super.setFocused(focused);
    }
}
