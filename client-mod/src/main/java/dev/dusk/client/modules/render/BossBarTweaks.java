package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/** Boss bars and on-screen titles: resize, move or hide them. */
public class BossBarTweaks extends Module {
    private static BossBarTweaks instance;

    private final BoolSetting hideBoss = add(new BoolSetting("hideBossBar", "Hide boss bars", false), "Boss bar");
    private final IntSetting bossScale = add(new IntSetting("bossScale", "Boss bar size", 100, 50, 150, 5, "%"), "Boss bar");
    private final IntSetting bossY = add(new IntSetting("bossY", "Boss bar up / down", 0, 0, 200, 1, " px"), "Boss bar");
    private final BoolSetting hideTitle = add(new BoolSetting("hideTitles", "Hide titles", false), "Titles");
    private final IntSetting titleScale = add(new IntSetting("titleScale", "Title size", 100, 30, 150, 5, "%"), "Titles");
    private final IntSetting titleY = add(new IntSetting("titleY", "Title up / down", 0, -150, 150, 1, " px"), "Titles");

    public BossBarTweaks() {
        super("bossbar", "Boss Bar & Titles", Category.RENDER,
                "Resize, move or hide boss bars and the big on-screen titles.");
        instance = this;
    }

    private static BossBarTweaks on() {
        return instance != null && instance.enabled() ? instance : null;
    }

    public static boolean bossHidden() {
        BossBarTweaks m = on();
        return m != null && m.hideBoss.get();
    }

    public static float bossScale() {
        BossBarTweaks m = on();
        return m == null ? 1f : m.bossScale.get() / 100f;
    }

    public static int bossY() {
        BossBarTweaks m = on();
        return m == null ? 0 : m.bossY.get();
    }

    public static boolean titleHidden() {
        BossBarTweaks m = on();
        return m != null && m.hideTitle.get();
    }

    public static float titleScale() {
        BossBarTweaks m = on();
        return m == null ? 1f : m.titleScale.get() / 100f;
    }

    public static int titleY() {
        BossBarTweaks m = on();
        return m == null ? 0 : m.titleY.get();
    }
}
