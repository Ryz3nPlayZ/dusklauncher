package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.ColorSetting;
import org.lwjgl.glfw.GLFW;

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
        Vanilla.editBox(c, fx, fy, FIELD_W, SQUARE, focused);
        String shown = "#" + buffer + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        c.text(shown, fx + 5, fy + 6, focused ? 0xFFE0E0E0 : Vanilla.TEXT_DIM, true);
        swatch(c, swatchX(), fy, SQUARE, setting.argb(), inSwatch(mouseX, mouseY));
    }

    /** Flex's colour square: grey outline (light on hover) around the colour over a checkerboard. */
    public static void swatch(Canvas c, int x, int y, int size, int argb, boolean hover) {
        c.fill(x, y, x + size, y + size, hover ? 0xFFD0D0D0 : 0xFF404040);
        checker(c, x + 1, y + 1, size - 2, size - 2);
        c.fill(x + 1, y + 1, x + size - 1, y + size - 1, argb);
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
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            setFocused(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
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
