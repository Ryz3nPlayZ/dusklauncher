package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.modules.render.CustomCrosshair;

/** The crosshair entry: an "Edit" PxButton with the live 15x15 preview beside it. */
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
        Px.button(c, controlX(), py, bw, SQUARE, hover, true);
        Px.label(c, "Edit", controlX(), py, bw, SQUARE, hover, false, 0xFF);
        preview(c, crosshair, px, py, SQUARE, hover);
    }

    /** A panel square with the crosshair at one pixel per cell. */
    public static void preview(Canvas c, CustomCrosshair crosshair, int x, int y, int size, boolean hover) {
        Px.panel(c, x, y, size, size);
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
