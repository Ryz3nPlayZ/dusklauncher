package dev.dusk.client.voice;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;

import java.util.ArrayList;
import java.util.List;

/** Who is talking, and whether your own microphone is live, muted or deafened. */
public class VoiceHud extends HudElement {
    private static final int TALK = 0xFF55FF7F, WHISPER = 0xFF7FB2FF, OFF = 0xFFFF5555, IDLE = 0xFFAAAAAA, NAME = 0xFFFFFFFF;
    private static final int ICON = 9;

    private final BoolSetting self = add(new BoolSetting("self", "Show your microphone", true));

    private record Row(String text, int icon, int color) {}

    public VoiceHud() {
        super("voicehud", "Voice Chat HUD", "Shows who is talking in voice chat.");
        setPosition(4, 60);
    }

    private List<Row> rows(HudContext ctx) {
        List<Row> out = new ArrayList<>();
        if (ctx.editing() && !Voice.active()) {
            out.add(new Row("You", TALK, NAME));
            out.add(new Row("Steve", TALK, NAME));
            out.add(new Row("Alex", WHISPER, NAME));
            return out;
        }
        if (!Voice.active()) return out;
        if (self.get()) {
            if (Voice.deafened()) out.add(new Row("Deafened", OFF, OFF));
            else if (Voice.muted()) out.add(new Row("Muted", OFF, OFF));
            else out.add(new Row("You", Voice.talking() ? TALK : IDLE, Voice.talking() ? NAME : IDLE));
        }
        for (Voice.Talker t : Voice.talkers()) {
            String where = t.source() == Voice.Source.CALL ? " (call)" : "";
            out.add(new Row(t.name() + where, t.whisper() ? WHISPER : TALK, NAME));
        }
        return out;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && !rows(ctx).isEmpty();
    }

    @Override
    public int width(HudContext ctx) {
        int w = 0;
        for (Row r : rows(ctx)) w = Math.max(w, ICON + ctx.textWidth(r.text()));
        return Math.max(w, ICON);
    }

    @Override
    public int height(HudContext ctx) {
        return Math.max(1, rows(ctx).size()) * (ctx.lineHeight() + 1) - 1;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        int y = 0;
        int lh = ctx.lineHeight() + 1;
        for (Row r : rows(ctx)) {
            Icons.MIC.draw(c, 0, y + 1, r.icon());
            c.text(r.text(), ICON, y, r.color(), true);
            y += lh;
        }
    }
}
