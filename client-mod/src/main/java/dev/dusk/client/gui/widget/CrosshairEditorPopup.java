package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.PixelGridSetting;
import dev.dusk.client.modules.render.CrosshairPresets;
import dev.dusk.client.modules.render.CustomCrosshair;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Flex-HUD's crosshair painter: the 15x15 grid on the left, the paint
 * colour, Clear and the preset thumbnails on the right, Done underneath.
 * Left-drag paints, right-drag erases, Ctrl/Cmd+Z undoes.
 */
public class CrosshairEditorPopup implements Popup {
    private static final int N = CustomCrosshair.SIZE, PADDING = 4, ASIDE = 60, THUMB_ROW = 32, UNDO_LIMIT = 64;

    private static final int[][][] THUMBS = new int[CrosshairPresets.NAMES.length][][];

    static {
        for (int k = 0; k < THUMBS.length; k++) THUMBS[k] = CrosshairPresets.byName(CrosshairPresets.NAMES[k]);
    }

    private final CustomCrosshair crosshair;
    private final PixelGridSetting pixels;
    private final Runnable onChange;
    private final PopupHost host;
    private final Deque<int[][]> undo = new ArrayDeque<>();
    private int ps, px, py, pw, ph, presetScroll;
    private int paintButton = -1;
    private int[][] before;
    private boolean closed;

    public CrosshairEditorPopup(CustomCrosshair crosshair, Runnable onChange, PopupHost host) {
        this.crosshair = crosshair;
        this.pixels = crosshair.pixels();
        this.onChange = onChange;
        this.host = host;
    }

    private void place(int sw, int sh) {
        ps = Math.max(3, Math.min((sh - 100) / N, (sw - 20 - 2 * PADDING - ASIDE - 4) / N));
        pw = N * ps + 2 * PADDING + ASIDE + 4;
        ph = N * ps + 48;
        px = (sw - pw) / 2;
        py = (sh - ph) / 2;
    }

    private int gx() { return px + PADDING; }
    private int gy() { return py + PADDING; }
    private int asideX() { return px + 2 * PADDING + N * ps; }
    private int swatchY() { return gy() + 11; }
    private int clearY() { return swatchY() + 24; }
    private int presetsTop() { return clearY() + 36; }
    private int presetsBottom() { return gy() + N * ps; }
    private int doneW() { return Math.min(200, N * ps); }
    private int doneX() { return gx() + (N * ps - doneW()) / 2; }
    private int doneY() { return py + ph - 30; }

    private int maxPresetScroll() {
        return Math.max(0, CrosshairPresets.NAMES.length * THUMB_ROW - (presetsBottom() - presetsTop()));
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        place(screenW, screenH);
        c.fill(0, 0, screenW, screenH, 0xA0000000);
        c.fill(px, py, px + pw, py + ph, 0xFF4A4A4A);
        c.outline(px - 1, py - 1, pw + 2, ph + 2, 0xFF000000);

        int hx = -1, hy = -1;
        if (Vanilla.inside(mouseX, mouseY, gx(), gy(), N * ps, N * ps)) {
            hx = (mouseX - gx()) / ps;
            hy = (mouseY - gy()) / ps;
        }
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int cx = gx() + i * ps, cy = gy() + j * ps, argb = pixels.pixel(i, j);
                if ((argb >>> 24) != 0) {
                    c.fill(cx, cy, cx + ps, cy + ps, argb);
                } else {
                    int in = ps / 4;
                    c.fill(cx + in, cy + in, cx + ps - in, cy + ps - in, i == N / 2 || j == N / 2 ? 0xFF424242 : 0xFF3A3A3A);
                }
                if (i == hx && j == hy) c.outline(cx, cy, ps, ps, 0xFFDF1515);
            }
        }

        int ax = asideX();
        c.text("Color", ax, gy() + 1, Vanilla.TEXT, true);
        ColorWidget.swatch(c, ax, swatchY(), 20, crosshair.pixelColor().argb(),
                Vanilla.inside(mouseX, mouseY, ax, swatchY(), 20, 20));
        Vanilla.button(c, "Clear", ax, clearY(), ASIDE, 20, Vanilla.inside(mouseX, mouseY, ax, clearY(), ASIDE, 20), true);
        c.text("Presets", ax, presetsTop() - 11, Vanilla.TEXT, true);

        int top = presetsTop(), bottom = presetsBottom();
        presetScroll = Math.max(0, Math.min(maxPresetScroll(), presetScroll));
        c.scissor(ax, top, ax + ASIDE, bottom);
        for (int k = 0; k < CrosshairPresets.NAMES.length; k++) {
            int ry = top + k * THUMB_ROW - presetScroll;
            if (ry + THUMB_ROW < top || ry > bottom) continue;
            boolean hover = mouseY >= top && mouseY < bottom && Vanilla.inside(mouseX, mouseY, ax, ry, ASIDE, THUMB_ROW - 2);
            c.fill(ax, ry, ax + ASIDE, ry + THUMB_ROW - 2, hover ? 0x50000000 : 0x10000000);
            int[][] grid = THUMBS[k];
            int tx = ax + (ASIDE - 2 * N) / 2, ty = ry + (THUMB_ROW - 2 - 2 * N) / 2;
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    if (grid != null && (grid[j][i] >>> 24) != 0) c.fill(tx + 2 * i, ty + 2 * j, tx + 2 * i + 2, ty + 2 * j + 2, grid[j][i]);
                }
            }
        }
        c.unscissor();

        Vanilla.button(c, "Done", doneX(), doneY(), doneW(), 20, Vanilla.inside(mouseX, mouseY, doneX(), doneY(), doneW(), 20), true);
    }

    @Override
    public void click(double mx, double my, int button) {
        if (!Vanilla.inside(mx, my, px, py, pw, ph)) {
            close();
            return;
        }
        if (Vanilla.inside(mx, my, gx(), gy(), N * ps, N * ps) && (button == 0 || button == 1)) {
            paintButton = button;
            before = snapshot();
            drag(mx, my);
            return;
        }
        if (button != 0) return;
        int ax = asideX();
        if (Vanilla.inside(mx, my, ax, swatchY(), 20, 20)) {
            host.open(new ColorPickerPopup(ax, swatchY(), 20, crosshair.pixelColor(), onChange));
        } else if (Vanilla.inside(mx, my, ax, clearY(), ASIDE, 20)) {
            pushUndo(snapshot());
            pixels.clear();
            crosshair.markCustom();
            onChange.run();
        } else if (Vanilla.inside(mx, my, ax, presetsTop(), ASIDE, presetsBottom() - presetsTop())) {
            int k = (int) ((my - presetsTop() + presetScroll) / THUMB_ROW);
            if (k >= 0 && k < CrosshairPresets.NAMES.length) {
                pushUndo(snapshot());
                crosshair.applyPreset(CrosshairPresets.NAMES[k]);
                onChange.run();
            }
        } else if (Vanilla.inside(mx, my, doneX(), doneY(), doneW(), 20)) {
            close();
        }
    }

    @Override
    public void drag(double mx, double my) {
        if (paintButton < 0 || !Vanilla.inside(mx, my, gx(), gy(), N * ps, N * ps)) return;
        int i = (int) ((mx - gx()) / ps), j = (int) ((my - gy()) / ps);
        int argb = paintButton == 0 ? crosshair.pixelColor().argb() : 0;
        if (pixels.pixel(i, j) == argb) return;
        pixels.setPixel(i, j, argb);
        crosshair.markCustom();
    }

    @Override
    public void release() {
        if (paintButton >= 0 && before != null && !Arrays.deepEquals(before, pixels.get())) {
            pushUndo(before);
            onChange.run();
        }
        paintButton = -1;
        before = null;
    }

    @Override
    public void scroll(double mx, double my, double amount) {
        presetScroll -= (int) Math.signum(amount) * THUMB_ROW / 2;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        boolean cmd = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (key == GLFW.GLFW_KEY_Z && cmd) {
            if (!undo.isEmpty()) {
                pixels.setGrid(undo.pop());
                crosshair.markCustom();
                onChange.run();
            }
            return true;
        }
        return false;
    }

    private int[][] snapshot() {
        int[][] g = pixels.get(), out = new int[g.length][];
        for (int j = 0; j < g.length; j++) out[j] = g[j].clone();
        return out;
    }

    private void pushUndo(int[][] grid) {
        undo.push(grid);
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
    }

    @Override
    public void close() {
        release();
        closed = true;
    }

    @Override
    public boolean closed() { return closed; }
}
