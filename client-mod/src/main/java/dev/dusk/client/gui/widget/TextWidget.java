package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.TextSetting;

/** Label and a text box; Enter or clicking away saves, Esc puts the old text back, right-click clears. */
public class TextWidget extends SettingRow {
    private static final int FIELD_W = 160;

    private final TextSetting setting;
    private final Runnable onChange;
    private String buffer;

    public TextWidget(TextSetting setting, Runnable onChange) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
        this.buffer = setting.get();
    }

    @Override
    protected int controlWidth() { return FIELD_W; }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        if (!focused) buffer = setting.get(); // follow the reset button
        int fx = controlX(), fy = top();
        Px.field(c, fx, fy, FIELD_W, SQUARE, focused);
        c.scissor(fx + 2, fy + 1, fx + FIELD_W - 2, fy + SQUARE - 1);
        if (buffer.isEmpty() && !focused) {
            c.text("Empty", fx + 5, fy + 6, 0xFF707070, true);
        } else {
            String shown = buffer + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
            // keep the end in view while typing past the box
            int start = 0;
            while (start < shown.length() && c.textWidth(shown.substring(start)) > FIELD_W - 10) start++;
            c.text(shown.substring(start), fx + 5, fy + 6, focused ? 0xFFE0E0E0 : Vanilla.TEXT_DIM, true);
        }
        c.unscissor();
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        boolean hit = inControl(mx, my);
        setFocused(hit);
        if (hit && button == 1) buffer = "";
        return hit;
    }

    @Override
    public boolean click(double mx, double my, int button) {
        boolean hit = super.click(mx, my, button);
        if (!hit) setFocused(false);
        return hit;
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
            buffer = setting.get();
            super.setFocused(false);
            return true;
        }
        return true; // swallow the rest so typing never triggers keybinds
    }

    @Override
    public boolean charTyped(char ch) {
        if (!focused) return false;
        if (ch >= ' ' && buffer.length() < setting.maxLength()) buffer += ch;
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (this.focused && !focused && !buffer.equals(setting.get())) {
            setting.set(buffer);
            onChange.run();
        }
        super.setFocused(focused);
    }
}
