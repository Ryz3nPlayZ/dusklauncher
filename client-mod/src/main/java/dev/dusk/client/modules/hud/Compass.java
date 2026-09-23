package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.player.LocalPlayer;

/**
 * Flex-HUD's compass strip: cardinal points scrolling past a marker, with
 * optional degree ticks. The map-mod and locator-bar overlays it can draw
 * on top are not ported.
 */
public class Compass extends HudElement {
    private static final int STRIP_WIDTH = 210;
    private static final int STRIP_HEIGHT = 30;
    private static final String MARKER = "▼";
    private static final String TICK = "|";
    private static final String[] POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private final BoolSetting showMarker = add(new BoolSetting("showMarker", "Show marker", true));
    private final BoolSetting showDegrees = add(new BoolSetting("showDegrees", "Show degrees", false));
    private final IntSetting degreesDecimals = add(new IntSetting("degreesDecimals", "Degree decimals", 0, 0, 14));
    private final BoolSetting showIntermediate =
            add(new BoolSetting("showIntermediate", "Show degree ticks", true));
    private final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFFFFF));

    public Compass() {
        super("compass", "Compass", "A compass strip showing which way you are facing.");
        setPosition(135, 4);
    }

    @Override
    public int width(HudContext ctx) {
        return STRIP_WIDTH;
    }

    @Override
    public int height(HudContext ctx) {
        return STRIP_HEIGHT + (showDegrees.get() ? 8 : 0);
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        LocalPlayer player = ctx.player();
        float yaw = player == null ? 180.0f : (player.getYRot() % 360 + 360) % 360;

        c.scissor(0, 0, STRIP_WIDTH, height(ctx));

        int hudY = showDegrees.get() ? 18 : 10;
        for (int i = 0; i < POINTS.length; i++) {
            drawCompassPoint(c, POINTS[i], i * 45, yaw, hudY);
        }

        if (showIntermediate.get()) {
            int tickY = hudY + 2;
            for (int i = 0; i < 8; i++) {
                drawIntermediatePoint(c, 15 * i * 3 + 15, yaw, tickY);
                drawIntermediatePoint(c, 15 * i * 3 + 30, yaw, tickY);
            }
        }

        c.unscissor();

        if (showDegrees.get()) {
            String degrees = String.format("%." + degreesDecimals.get() + "f", yaw);
            c.push();
            c.translate((STRIP_WIDTH / 2.0f) - (c.textWidth(degrees) / 2.0f) * 0.75f, 1);
            c.scale(0.75f, 0.75f);
            c.text(degrees, 0, 0, color.argb(), shadow.get());
            c.pop();
        }

        if (showMarker.get()) {
            c.push();
            c.translate((STRIP_WIDTH / 2.0f) - (c.textWidth(MARKER) / 2.0f), showDegrees.get() ? 8 : 0);
            c.scale(1.0f, 0.5f);
            c.text(MARKER, 0, 0, color.argb(), shadow.get());
            c.pop();
        }
    }

    private void drawCompassPoint(Canvas c, String label, int angle, float yaw, int y) {
        float angleDifference = (angle - yaw + 540) % 360 - 180;
        if (Math.abs(angleDifference) > 120) return;

        float scaleFactor = 1.25f;
        float positionX = positionX(angleDifference);
        float pointWidth = c.textWidth(label) * scaleFactor;

        c.push();
        c.translate(positionX - pointWidth / 2.0f, y);
        c.scale(scaleFactor, scaleFactor);
        c.text(label, 0, 0, faded(positionX), shadow.get());
        c.pop();
    }

    private void drawIntermediatePoint(Canvas c, int angle, float yaw, int y) {
        float angleDifference = (angle - yaw + 540) % 360 - 180;
        if (Math.abs(angleDifference) > 120) return;

        float positionX = positionX(angleDifference);

        c.push();
        c.translate(positionX - c.textWidth(TICK) / 2.0f, y);
        c.scale(1.0f, 0.75f);
        c.text(TICK, 0, 0, faded(positionX), shadow.get());
        c.pop();

        String angleStr = String.valueOf(angle);
        c.push();
        c.translate(positionX - c.textWidth(angleStr) / 4.0f, y + 8);
        c.scale(0.5f, 0.5f);
        c.text(angleStr, 0, 0, faded(positionX), shadow.get());
        c.pop();
    }

    private static float positionX(float angleDifference) {
        return (STRIP_WIDTH / 2.0f) + angleDifference * (STRIP_WIDTH / 180.0f);
    }

    /** Points fade out towards the ends of the strip, like Flex-HUD's. */
    private int faded(float centerX) {
        double distanceFromCenter = Math.abs(centerX - STRIP_WIDTH / 2.0);
        int alpha = 0xFF;
        if (distanceFromCenter > STRIP_WIDTH / 4.0) {
            alpha = Math.max(0xFF - (int) ((distanceFromCenter - STRIP_WIDTH / 4.0) / (STRIP_WIDTH / 4.0) * 0xFF), 0);
        }
        return (alpha << 24) | (color.argb() & 0xFFFFFF);
    }
}
