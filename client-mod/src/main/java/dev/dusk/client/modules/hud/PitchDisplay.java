package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;

/** Flex-HUD's pitch gauge: a vertical strip of angle ticks around your pitch. */
public class PitchDisplay extends HudElement {
    private static final int STRIP_HEIGHT = 150;
    private static final String MARKER = "▶";
    private static final String TICK = "|";

    private final BoolSetting elytraOnly =
            add(new BoolSetting("elytraOnly", "Only with an elytra", false));
    private final BoolSetting showMarker = add(new BoolSetting("showMarker", "Show marker", true));
    private final BoolSetting showDegrees = add(new BoolSetting("showDegrees", "Show degrees", false));
    private final IntSetting degreesDecimals = add(new IntSetting("degreesDecimals", "Degree decimals", 0, 0, 14));
    private final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFFFFF));

    public PitchDisplay() {
        super("pitchdisplay", "Pitch Display", "A vertical pitch gauge, like an aircraft's ladder.");
        setPosition(380, 40);
    }

    private String pitchText(HudContext ctx) {
        LocalPlayer player = ctx.player();
        float pitch = player == null ? 0.0f : -player.getXRot();
        return String.format("%." + degreesDecimals.get() + "f", pitch);
    }

    @Override
    public boolean visible(HudContext ctx) {
        LocalPlayer player = ctx.player();
        if (player == null) return false;
        return !elytraOnly.get() || player.getInventory().getItem(38).is(Items.ELYTRA);
    }

    @Override
    public int width(HudContext ctx) {
        double w = 34;
        if (showDegrees.get()) w += ctx.textWidth(pitchText(ctx)) * 0.75f + 2;
        if (showMarker.get()) w += ctx.textWidth(MARKER) / 2.0 + 5;
        return (int) w;
    }

    @Override
    public int height(HudContext ctx) {
        return STRIP_HEIGHT;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        LocalPlayer player = ctx.player();
        float pitch = player == null ? 0.0f : (player.getXRot() % 360 + 360) % 360;

        c.scissor(0, 0, width(ctx), STRIP_HEIGHT);

        float hudX = 0;
        if (showDegrees.get()) {
            String text = pitchText(ctx);
            c.push();
            c.translate(hudX, (STRIP_HEIGHT - c.lineHeight()) / 2.0f);
            c.scale(0.75f, 0.75f);
            c.text(text, 0, 0, color.argb(), shadow.get());
            c.pop();
            hudX += c.textWidth(text) * 0.75f + 2;
        }

        if (showMarker.get()) {
            c.push();
            c.translate(hudX, (STRIP_HEIGHT - c.lineHeight()) / 2.0f);
            c.scale(0.5f, 1.0f);
            c.text(MARKER, 0, 0, color.argb(), shadow.get());
            c.pop();
            hudX += c.textWidth(MARKER) / 2.0f + 5;
        }

        drawIntermediate(c, -165, pitch, hudX);
        drawIntermediate(c, -150, pitch, hudX);
        for (int angle = -135; angle <= 135; angle += 45) {
            drawPitchPoint(c, angle, pitch, hudX);
            drawIntermediate(c, angle + 15, pitch, hudX);
            drawIntermediate(c, angle + 30, pitch, hudX);
        }

        c.unscissor();
    }

    private void drawPitchPoint(Canvas c, int angle, float pitch, float x) {
        String angleStr = String.valueOf(angle);
        float angleDifference = ((-angle) - pitch + 540) % 360 - 180;
        if (Math.abs(angleDifference) > 120) return;

        float scaleFactor = 1.25f;
        float angleScale = 0.75f;
        float positionY = (STRIP_HEIGHT / 2.0f) + angleDifference * (STRIP_HEIGHT / 180.0f);
        float pointWidth = c.textWidth(TICK) * scaleFactor;
        float angleHeight = c.lineHeight() * angleScale;

        c.push();
        c.translate(x + 14, positionY - angleHeight / 2.0f);
        c.scale(angleScale, angleScale);
        c.text(angleStr, 0, 0, faded(positionY), shadow.get());
        c.pop();

        c.push();
        c.translate(x + 9, positionY - pointWidth / 2.0f);
        c.scale(scaleFactor, scaleFactor);
        c.rotate((float) Math.toRadians(90));
        c.text(TICK, 0, 0, faded(positionY), shadow.get());
        c.pop();
    }

    private void drawIntermediate(Canvas c, int angle, float pitch, float x) {
        float angleDifference = ((-angle) - pitch + 540) % 360 - 180;
        if (Math.abs(angleDifference) > 120) return;

        float scaleFactor = 0.75f;
        float positionY = (STRIP_HEIGHT / 2.0f) + angleDifference * (STRIP_HEIGHT / 180.0f);
        float pointWidth = c.textWidth(TICK) * scaleFactor;

        c.push();
        c.translate(x + 5.6f, positionY - pointWidth / 2.0f);
        c.scale(scaleFactor, scaleFactor);
        c.rotate((float) Math.toRadians(90));
        c.text(TICK, 0, 0, faded(positionY), shadow.get());
        c.pop();
    }

    /** Ticks fade out towards the ends of the strip, like Flex-HUD's. */
    private int faded(float centerY) {
        double distanceFromCenter = Math.abs(centerY - STRIP_HEIGHT / 2.0);
        int alpha = 0xFF;
        if (distanceFromCenter > STRIP_HEIGHT / 4.0) {
            alpha = Math.max(0xFF - (int) ((distanceFromCenter - STRIP_HEIGHT / 4.0) / (STRIP_HEIGHT / 4.0) * 0xFF), 0);
        }
        return (alpha << 24) | (color.argb() & 0xFFFFFF);
    }
}
