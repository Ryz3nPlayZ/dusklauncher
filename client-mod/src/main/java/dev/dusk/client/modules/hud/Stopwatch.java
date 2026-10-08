package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Input;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.KeySetting;

import java.util.Locale;

/** A stopwatch on the HUD: one key starts and pauses it, another resets it. */
public class Stopwatch extends TextHud {
    private final KeySetting startKey = add(new KeySetting("stopwatch", "Start / pause key", Input.UNKNOWN), "Keybinds");
    private final KeySetting resetKey = add(new KeySetting("stopwatch_reset", "Reset key", Input.UNKNOWN), "Keybinds");
    private final BoolSetting hundredths = add(new BoolSetting("hundredths", "Show hundredths", false));
    private final BoolSetting hideIdle = add(new BoolSetting("hideIdle", "Hide until started", false));

    /** Time banked by earlier runs, and when the current run began (0 = paused). */
    private long bankedMs, startedAt;

    public Stopwatch() {
        super("stopwatch", "Stopwatch", "A stopwatch you start, pause and reset with keys.");
        setPosition(150, 96);
    }

    @Override
    public void tick() {
        while (startKey.mapping().consumeClick()) {
            long now = System.currentTimeMillis();
            if (startedAt == 0) {
                startedAt = now;
            } else {
                bankedMs += now - startedAt;
                startedAt = 0;
            }
        }
        while (resetKey.mapping().consumeClick()) {
            bankedMs = 0;
            startedAt = 0;
        }
    }

    private long elapsedMs() {
        return bankedMs + (startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt);
    }

    @Override
    protected String text(HudContext ctx) {
        long ms = elapsedMs();
        if (ms == 0 && startedAt == 0 && hideIdle.get()) return null;
        return format(ms);
    }

    private String format(long ms) {
        long h = ms / 3_600_000, m = ms / 60_000 % 60, s = ms / 1000 % 60;
        String frac = hundredths.get()
                ? String.format(Locale.ROOT, ".%02d", ms / 10 % 100)
                : String.format(Locale.ROOT, ".%d", ms / 100 % 10);
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d%s", h, m, s, frac)
                : String.format(Locale.ROOT, "%d:%02d%s", m, s, frac);
    }

    @Override
    protected String sample() {
        return format(83_400);
    }
}
