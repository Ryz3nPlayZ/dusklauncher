package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A single-line text box. {@code live} fires on every edit (search boxes);
 * otherwise the value is committed on Enter or when focus leaves.
 */
public class TextFieldWidget extends Widget {
    private final Supplier<String> source;
    private final Consumer<String> sink;
    private final boolean live;
    private final int maxLength;
    private String placeholder = "";
    private boolean themed, bare;
    private String buffer;

    public TextFieldWidget(Supplier<String> source, Consumer<String> sink, boolean live, int maxLength) {
        this.source = source;
        this.sink = sink;
        this.live = live;
        this.maxLength = maxLength;
        this.buffer = source.get();
    }

    /** Draw with the Dusk panel's look instead of vanilla's edit box. */
    public TextFieldWidget themed() {
        this.themed = true;
        return this;
    }

    /** Just the text, no box: the host draws the frame (the navbar's search cell). */
    public TextFieldWidget bare() {
        this.themed = true;
        this.bare = true;
        return this;
    }

    public TextFieldWidget placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public String text() { return buffer; }

    public void setText(String text) {
        buffer = text;
        if (live) sink.accept(buffer);
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        if (bare) {
            // nothing: the host's cell is the frame
        } else if (themed) {
            Theme.field(c, x, y, w, h, focused);
        } else {
            Vanilla.editBox(c, x, y, w, h, focused);
        }
        int ty = y + (h - c.lineHeight()) / 2 + 1, tx = bare ? x + 1 : x + 5;
        c.scissor(x + (bare ? 0 : 2), y + (bare ? 0 : 1), x + w - (bare ? 0 : 2), y + h - (bare ? 0 : 1));
        if (buffer.isEmpty() && !focused) {
            c.text(placeholder, tx, ty, themed ? Theme.TEXT_FAINT : 0xFF707070, !themed);
        } else if (buffer.isEmpty() && bare) {
            // focused on open: keep the hint, with the caret before it
            if ((System.currentTimeMillis() / 500) % 2 == 0) c.fill(tx, ty - 1, tx + 1, ty + 8, Theme.TEXT);
            c.text(placeholder, tx + 3, ty, Theme.TEXT_FAINT, false);
        } else {
            String caret = focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            String shown = buffer + caret;
            // keep the caret end visible when the text overflows
            int avail = w - (bare ? 2 : 10);
            int start = 0;
            while (start < shown.length() && c.textWidth(shown.substring(start)) > avail) start++;
            c.text(shown.substring(start), tx, ty, themed ? Theme.TEXT : 0xFFE0E0E0, !themed);
        }
        c.unscissor();
    }

    @Override
    public boolean click(double mx, double my, int button) {
        boolean hit = contains(mx, my);
        setFocused(hit);
        if (hit && button == 1) setText("");
        return hit;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!focused) return false;
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!buffer.isEmpty()) setText(buffer.substring(0, buffer.length() - 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            setFocused(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!live) buffer = source.get();
            super.setFocused(false);
            return true;
        }
        return true; // swallow the rest so typing never triggers keybinds
    }

    @Override
    public boolean charTyped(char ch) {
        if (!focused) return false;
        if (ch >= ' ' && buffer.length() < maxLength) setText(buffer + ch);
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (this.focused && !focused && !live) sink.accept(buffer);
        super.setFocused(focused);
    }
}
