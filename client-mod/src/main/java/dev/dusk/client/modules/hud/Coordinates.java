package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Flex-HUD's Coordinates: "X: / Y: / Z:" stacked with a direction column
 * (+/- axis signs around the heading) beside them, or one "(X: ..; Z: ..) SE" line.
 */
public class Coordinates extends TextHud {
    private static final String[] NAMES = {"South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East"};
    private static final String[] SHORT = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] AXIS_X = {"", "-", "-", "-", "", "+", "+", "+"};
    private static final String[] AXIS_Z = {"+", "+", "", "-", "-", "-", "", "+"};

    private final BoolSetting showY = add(new BoolSetting("showY", "Show Y", true));
    private final IntSetting digits = add(new IntSetting("digits", "Decimals", 0, 0, 14));
    private final BoolSetting showDirection = add(new BoolSetting("showDirection", "Show direction", true));
    private final BoolSetting abbreviate = add(new BoolSetting("directionAbbreviation", "Abbreviate direction", true));
    private final ChoiceSetting mode = add(new ChoiceSetting("displayMode", "Layout", "Vertical", "Vertical", "Horizontal"));

    private record Line(String text, int x, int y) {}

    public Coordinates() {
        super("coords", "Coordinates", "Your position and the way you are facing.");
        setPosition(5, 38);
        setEnabled(true);
    }

    private String num(double v) {
        return BigDecimal.valueOf(v).setScale(digits.get(), RoundingMode.FLOOR).toPlainString();
    }

    /** 0 = south, clockwise in 45° steps, the way Flex buckets the yaw. */
    private static int heading(float yaw) {
        float y = (yaw % 360 + 360) % 360;
        return (int) Math.floor((y + 22.5f) / 45f) % 8;
    }

    private List<Line> lines(HudContext ctx) {
        var p = ctx.player();
        double px, py, pz;
        float yaw;
        if (ctx.editing() || p == null) {
            px = 12;
            py = 73;
            pz = -48;
            yaw = 45;
        } else {
            px = p.getX();
            py = p.getY();
            pz = p.getZ();
            yaw = p.getVisualRotationYInDegrees();
        }
        int h = heading(yaw);
        String facing = abbreviate.get() ? SHORT[h] : NAMES[h];
        String x = "X: " + num(px), y = "Y: " + num(py), z = "Z: " + num(pz);
        List<Line> out = new ArrayList<>();
        if (mode.is("Horizontal")) {
            String s = "(" + x + (showY.get() ? "; " + y : "") + "; " + z + ")";
            if (showDirection.get()) s += " " + facing;
            out.add(new Line(s, 0, 0));
            return out;
        }
        int row = 0;
        out.add(new Line(x, 0, row));
        if (showY.get()) out.add(new Line(y, 0, row += 10));
        out.add(new Line(z, 0, row += 10));
        if (showDirection.get()) {
            int widest = Math.max(ctx.textWidth(x), ctx.textWidth(y));
            if (showY.get()) widest = Math.max(widest, ctx.textWidth(z));
            int dx = 24 + widest;
            out.add(new Line(AXIS_X[h], dx, 0));
            if (showY.get()) {
                out.add(new Line(facing, dx, 10));
                out.add(new Line(AXIS_Z[h], dx, 20));
            } else {
                out.add(new Line(facing, dx + 8, 5));
                out.add(new Line(AXIS_Z[h], dx, 10));
            }
        }
        return out;
    }

    @Override
    protected String text(HudContext ctx) {
        return ctx.player() == null ? null : "";
    }

    @Override
    public int width(HudContext ctx) {
        int w = 0;
        for (Line l : lines(ctx)) w = Math.max(w, l.x + ctx.textWidth(l.text));
        return w;
    }

    @Override
    public int height(HudContext ctx) {
        if (mode.is("Horizontal")) return ctx.lineHeight();
        return showY.get() ? 30 : 20;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        if (current(ctx) == null) return;
        int color = color(ctx);
        for (Line l : lines(ctx)) if (!l.text.isEmpty()) c.text(l.text, l.x, l.y, color, shadow.get());
    }
}
