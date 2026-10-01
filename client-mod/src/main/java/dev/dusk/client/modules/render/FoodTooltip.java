package dev.dusk.client.modules.render;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.TooltipImage;
import net.minecraft.client.gui.Font;
import net.minecraft.world.food.FoodProperties;

/**
 * AppleSkin's tooltip food values (Unlicense, squeek502 — see NOTICE): a row
 * of shanks for the hunger a food restores and a row of saturation icons
 * under it, each with an "x12" count once there are too many to draw. Drawn
 * by the per-version TooltipImageComponent.
 */
public final class FoodTooltip implements TooltipImage {
    private final FoodProperties food;
    private final boolean rotten;

    private int hungerBars;
    private String hungerBarsText;
    private int saturationBars;
    private String saturationBarsText;

    FoodTooltip(FoodProperties food, boolean rotten) {
        this.food = food;
        this.rotten = rotten;

        int hunger = food.nutrition();
        float saturation = food.saturation();

        hungerBars = (int) Math.ceil(Math.abs(hunger) / 2f);
        if (hungerBars > 10) {
            hungerBarsText = "x" + ((hunger < 0 ? -1 : 1) * hungerBars);
            hungerBars = 1;
        }

        saturationBars = (int) Math.ceil(Math.abs(saturation) / 2f);
        if (saturationBars > 10 || saturationBars == 0) {
            saturationBarsText = "x" + ((saturation < 0 ? -1 : 1) * saturationBars);
            saturationBars = 1;
        }
    }

    boolean shouldRenderHungerBars() {
        return hungerBars > 0;
    }

    @Override
    public int height() {
        // hunger + spacing + saturation + the 3 extra that looks best
        return 9 + 1 + 7 + 3;
    }

    @Override
    public int width(Font font) {
        int hungerBarLength = hungerBars * 9;
        if (hungerBarsText != null) hungerBarLength += font.width(hungerBarsText);
        int saturationBarLength = saturationBars * 7;
        if (saturationBarsText != null) saturationBarLength += font.width(saturationBarsText);
        return Math.max(hungerBarLength, saturationBarLength);
    }

    @Override
    public void draw(Canvas c, int tooltipX, int tooltipY) {
        int hunger = food.nutrition();
        int x = tooltipX;
        int y = tooltipY;

        // right to left, so the icons 'face' the right way
        x += (hungerBars - 1) * 9;
        for (int i = 0; i < hungerBars * 2; i += 2) {
            c.blit(HungerInfo.foodSprite(false, "empty"), x, y, 0, 0, 9, 9, 9, 9);

            boolean isDefaultHalf = hunger - 1 == i;
            c.blit(HungerInfo.foodSprite(rotten, isDefaultHalf ? "half" : "full"), x, y, 0, 0, 9, 9, 9, 9,
                    HungerInfo.argb(1F, 1F, 1F, 0.25F));
            if (hunger > i) {
                c.blit(HungerInfo.foodSprite(rotten, isDefaultHalf ? "half" : "full"), x, y, 0, 0, 9, 9, 9, 9);
            }
            x -= 9;
        }
        if (hungerBarsText != null) {
            x += 18;
            drawCount(c, hungerBarsText, x, y, 2);
        }

        x = tooltipX;
        y += 10;

        float saturation = food.saturation();
        float absSaturation = Math.abs(saturation);

        x += (saturationBars - 1) * 7;
        for (int i = 0; i < saturationBars * 2; i += 2) {
            float effectiveSaturationOfBar = (absSaturation - i) / 2f;
            boolean faded = absSaturation <= i;
            int u = effectiveSaturationOfBar >= 1 ? 21 : effectiveSaturationOfBar > 0.5 ? 14
                    : effectiveSaturationOfBar > 0.25 ? 7 : effectiveSaturationOfBar > 0 ? 0 : 28;
            c.blit(HungerInfo.ICONS, x, y, u, saturation >= 0 ? 27 : 34, 7, 7, 256, 256,
                    HungerInfo.argb(1F, 1F, 1F, faded ? 0.5F : 1F));
            x -= 7;
        }
        if (saturationBarsText != null) {
            x += 14;
            drawCount(c, saturationBarsText, x, y, 1);
        }
    }

    private static void drawCount(Canvas c, String text, int x, int y, int dy) {
        c.push();
        c.translate(x, y);
        c.scale(0.75f, 0.75f);
        c.text(text, 2, dy, 0xFFAAAAAA, true);
        c.pop();
    }
}
