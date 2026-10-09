package dev.dusk.client.modules.misc;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * Vanilla's clickable-style finder that also notes which chat line is under
 * the cursor, for {@link ChatCopy}. Chat hands every visible line through
 * here when the chat screen hit-tests a click.
 */
public class ChatLineFinder extends ActiveTextCollector.ClickableStyleFinder {
    private final Font font;
    private final float testX, testY;
    private FormattedCharSequence line;

    public ChatLineFinder(Font font, int x, int y) {
        super(font, x, y);
        this.font = font;
        this.testX = x;
        this.testY = y;
    }

    @Override
    public void accept(TextAlignment alignment, int x, int y, ActiveTextCollector.Parameters parameters, FormattedCharSequence text) {
        super.accept(alignment, x, y, parameters, text);
        if (line != null) return;
        Vector2f local = new Matrix3x2f(parameters.pose()).invert().transformPosition(testX, testY, new Vector2f());
        int left = alignment.calculateLeft(x, font, text);
        if (local.y >= y - 1 && local.y < y + font.lineHeight && local.x >= left - 2 && local.x < left + font.width(text) + 2) {
            line = text;
        }
    }

    public FormattedCharSequence line() {
        return line;
    }
}
