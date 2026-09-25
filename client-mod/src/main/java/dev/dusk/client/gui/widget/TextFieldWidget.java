package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
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
    private String buffer;

    public TextFieldWidget(Supplier<String> source, Consumer<String> sink, boolean live, int maxLength) {
        this.source = source;
        this.sink = sink;
        this.live = live;
        this.maxLength = maxLength;
        this.buffer = source.get();
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
        Vanilla.editBox(c, x, y, w, h, focused);
        int ty = y + (h - c.lineHeight()) / 2 + 1;
        c.scissor(x + 2, y + 1, x + w - 2, y + h - 1);
        if (buffer.isEmpty() && !focused) {
            c.text(placeholder, x + 5, ty, 0xFF707070, true);
        } else {
            String caret = focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            String shown = buffer + caret;
            // keep the caret end visible when the text overflows
            int avail = w - 10;
            int start = 0;
            while (start < shown.length() && c.textWidth(shown.substring(start)) > avail) start++;
            c.text(shown.substring(start), x + 5, ty, 0xFFE0E0E0, true);
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
