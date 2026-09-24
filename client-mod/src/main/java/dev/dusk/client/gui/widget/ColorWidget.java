package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.module.setting.ColorSetting;
import org.lwjgl.glfw.GLFW;

/** Label, colour swatch and an editable AARRGGBB hex field. */
public class ColorWidget extends Widget {
    private static final int FIELD_W = 62, SWATCH = 10;

    private final ColorSetting setting;
    private final Runnable onChange;
    private String buffer;

    public ColorWidget(ColorSetting setting, Runnable onChange) {
        this.setting = setting;
        this.onChange = onChange;
        this.buffer = setting.hex();
    }

    private int fieldX() { return x + w - FIELD_W - 4; }

    private boolean inField(double mx, double my) {
        return mx >= fieldX() && mx < fieldX() + FIELD_W && my >= y && my < y + h;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        if (contains(mouseX, mouseY)) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
        int ty = y + (h - c.lineHeight()) / 2 + 1;
        c.text(setting.name(), x + 4, ty, Theme.TEXT, false);
        int fx = fieldX(), fy = y + 3, fh = h - 6;
        int sx = fx - SWATCH - 4;
        c.fill(sx, fy, sx + SWATCH, fy + fh, 0xFFFFFFFF);
        c.fill(sx + 1, fy + 1, sx + SWATCH - 1, fy + fh - 1, setting.argb());
        Theme.field(c, fx, fy, FIELD_W, fh, focused);
        String shown = buffer + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        c.text(shown, fx + 3, ty, focused ? Theme.TEXT : Theme.TEXT_MUTED, false);
    }

    @Override
    public boolean click(double mx, double my, int button) {
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
            commit();
            setFocused(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            buffer = setting.hex();
            setFocused(false);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char ch) {
        if (!focused) return false;
        if (buffer.length() >= 8) return true;
        if (Character.digit(ch, 16) >= 0) buffer += Character.toUpperCase(ch);
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (this.focused && !focused) commit();
        super.setFocused(focused);
    }
}
