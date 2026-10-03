package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/** The sidebar scoreboard: hide the red score numbers, resize it, move it, or hide it. */
public class ScoreboardTweaks extends Module {
    private static ScoreboardTweaks instance;
    /** True while the sidebar is being drawn, so its score numbers can be blanked. */
    private static boolean drawing;

    private final BoolSetting hideNumbers = add(new BoolSetting("hideNumbers", "Hide red numbers", true));
    private final BoolSetting hide = add(new BoolSetting("hide", "Hide the scoreboard", false));
    private final IntSetting scale = add(new IntSetting("scale", "Size", 100, 50, 150, 5, "%"), "Layout");
    private final IntSetting offsetX = add(new IntSetting("offsetX", "Move left / right", 0, -400, 0, 1, " px"), "Layout");
    private final IntSetting offsetY = add(new IntSetting("offsetY", "Move up / down", 0, -200, 200, 1, " px"), "Layout");

    public ScoreboardTweaks() {
        super("scoreboard", "Scoreboard", Category.RENDER,
                "Hide the red numbers, resize, move or hide the sidebar scoreboard.");
        instance = this;
    }

    private static ScoreboardTweaks on() {
        return instance != null && instance.enabled() ? instance : null;
    }

    public static boolean hidden() {
        ScoreboardTweaks m = on();
        return m != null && m.hide.get();
    }

    /** Size as a factor, 1 when off. */
    public static float scale() {
        ScoreboardTweaks m = on();
        return m == null ? 1f : m.scale.get() / 100f;
    }

    public static int offsetX() {
        ScoreboardTweaks m = on();
        return m == null ? 0 : m.offsetX.get();
    }

    public static int offsetY() {
        ScoreboardTweaks m = on();
        return m == null ? 0 : m.offsetY.get();
    }

    public static void setDrawing(boolean value) {
        drawing = value;
    }

    /** Whether a score being formatted right now should come out blank. */
    public static boolean blankScore() {
        ScoreboardTweaks m = on();
        return drawing && m != null && m.hideNumbers.get();
    }
}
