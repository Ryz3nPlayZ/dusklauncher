package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.modules.render.CustomCrosshair;

/** Flex-HUD's crosshair entry: an "Edit" button with the live 15x15 preview beside it. */
public class CrosshairWidget extends SettingRow {
    private final CustomCrosshair crosshair;
    private final Runnable onChange;
    private final PopupHost host;

    public CrosshairWidget(CustomCrosshair crosshair, Runnable onChange, PopupHost host) {
        super(crosshair.pixels().name());
        this.crosshair = crosshair;
        this.onChange = onChange;
        this.host = host;
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        boolean hover = inControl(mouseX, mouseY);
        int bw = controlWidth() - SQUARE - GAP, px = controlX() + bw + GAP, py = top();
        Vanilla.button(c, "Edit", controlX(), py, bw, SQUARE, hover, true);
        preview(c, crosshair, px, py, SQUARE, hover);
    }

    /** A dark square with the crosshair at one pixel per cell. */
    public static void preview(Canvas c, CustomCrosshair crosshair, int x, int y, int size, boolean hover) {
        c.fill(x, y, x + size, y + size, hover ? 0xFFD0D0D0 : 0xFF404040);
        c.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF1E1F22);
        int ox = x + (size - CustomCrosshair.SIZE) / 2, oy = y + (size - CustomCrosshair.SIZE) / 2;
        crosshair.forEachPixel((px, py, argb) -> c.fill(ox + px, oy + py, ox + px + 1, oy + py + 1, argb));
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !inControl(mx, my)) return false;
        host.open(new CrosshairEditorPopup(crosshair, onChange, host));
        return true;
    }
}
