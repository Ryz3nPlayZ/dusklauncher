package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;

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

    /** Draw with the Dusk panel's field (Theme) instead of the Px settings field. */
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
            Px.field(c, x, y, w, h, focused);
        }
        boolean modern = Theme.modern();
        if (modern) {
            renderModern(c);
            return;
        }
        boolean shadow = !themed && !modern;
        int ty = y + (h - c.lineHeight()) / 2 + 1, tx = bare ? x + 1 : x + (modern ? Math.min(8, h / 2) : 5);
        c.scissor(x + (bare ? 0 : 2), y + (bare ? 0 : 1), x + w - (bare ? 0 : 2), y + h - (bare ? 0 : 1));
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (buffer.isEmpty() && !focused) {
            c.text(placeholder, tx, ty, themed || modern ? Theme.TEXT_FAINT : 0xFF707070, shadow);
        } else if (buffer.isEmpty() && (bare || modern)) {
            // focused on open: keep the hint, with the caret before it
            if (blink) c.fill(tx, ty - 1, tx + 1, ty + 8, Theme.TEXT);
            c.text(placeholder, tx + 3, ty, Theme.TEXT_FAINT, false);
        } else {
            // keep the caret end visible when the text overflows
            int avail = w - (bare ? 2 : tx - x + 5);
            if (modern) {
                int start = 0;
                while (start < buffer.length() && c.textWidth(buffer.substring(start)) + 2 > avail) start++;
                String shown = buffer.substring(start);
                c.text(shown, tx, ty, Theme.TEXT, false);
                if (focused && blink) {
                    int cx = tx + c.textWidth(shown) + 1;
                    c.fill(cx, ty - 1, cx + 1, ty + 8, Theme.TEXT);
                }
            } else {
                String caret = focused && blink ? "_" : "";
                String shown = buffer + caret;
                int start = 0;
                while (start < shown.length() && c.textWidth(shown.substring(start)) > avail) start++;
                c.text(shown.substring(start), tx, ty, themed ? Theme.TEXT : 0xFFE0E0E0, shadow);
            }
        }
        c.unscissor();
    }

    /**
     * The modern look: the sans at a control's size, the caret a hairline
     * centred on the box, and the hint kept (after the caret) while the box
     * is focused but empty.
     */
    private void renderModern(Canvas c) {
        Px.Size size = bare ? Px.Size.S14 : Px.Size.S16;
        int tx = bare ? x + 1 : x + Math.min(8, h / 2), room = x + w - (bare ? 1 : 6) - tx;
        boolean blink = focused && (System.currentTimeMillis() / 500) % 2 == 0;
        c.scissor(x + (bare ? 0 : 2), y, x + w - (bare ? 0 : 2), y + h);
        if (buffer.isEmpty()) {
            Px.sans(c, placeholder, tx + (focused ? 3 : 0) - Px.pad(), y, room + 2 * Px.pad(), h, size, Theme.TEXT_FAINT, false, true);
            if (blink) caret(c, tx);
        } else {
            // keep the caret end in view when the text overflows
            int start = 0;
            while (start < buffer.length() - 1 && Px.sansWidth(c, buffer.substring(start), size, false) + 3 > room) start++;
            String shown = buffer.substring(start);
            Px.sans(c, shown, tx - Px.pad(), y, room + 2 * Px.pad() + 4, h, size, Theme.TEXT, false, true);
            if (blink) caret(c, tx + Math.round(Px.sansWidth(c, shown, size, false)) + 1);
        }
        c.unscissor();
    }

    private void caret(Canvas c, int cx) {
        Round.begin(c);
        int mid = Round.w(y + h / 2.0), half = Round.w(4.5);
        c.fill(Round.w(cx), mid - half, Round.w(cx) + Px.line(), mid + half, Theme.TEXT);
        Round.end(c);
    }

    @Override
    public boolean carded() { return false; }

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
        if (key == InputConstants.KEY_BACKSPACE) {
            if (!buffer.isEmpty()) setText(buffer.substring(0, buffer.length() - 1));
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            setFocused(false);
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
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
