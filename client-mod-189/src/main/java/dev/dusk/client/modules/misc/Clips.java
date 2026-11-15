package dev.dusk.client.modules.misc;

import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.media.ScreenRecorder;
import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/**
 * Clips: the screen recorder's switches. With the replay buffer on, the last
 * stretch of play is always held and the clip key saves it as an MP4; the
 * record key starts and stops a video. The work is in {@link ScreenRecorder}.
 */
public class Clips extends Module {
    private static Clips instance;

    private final KeySetting clipKey = add(new KeySetting("save_clip", "Save clip key", Keyboard.KEY_F8), "Keybinds");
    private final KeySetting recordKey = add(new KeySetting("record_video", "Record key", Keyboard.KEY_F9), "Keybinds");

    private final BoolSetting buffer = add(new BoolSetting("buffer", "Replay buffer", true), "Replay buffer");
    private final IntSetting seconds = add(new IntSetting("seconds", "Clip length", 30, 10, 300, 5, "s"), "Replay buffer");

    private final ChoiceSetting fps = add(new ChoiceSetting("fps", "Frame rate", "60", "30", "60"), "Video");
    private final ChoiceSetting quality = add(new ChoiceSetting("quality", "Quality", "high", "standard", "high", "ultra"), "Video");
    private final ChoiceSetting resolution = add(new ChoiceSetting("resolution", "Resolution", "1440", "native", "1440", "1080", "720"), "Video");
    private final ChoiceSetting codec = add(new ChoiceSetting("codec", "Codec", "h264", "h264", "hevc"), "Video");
    private final BoolSetting hud = add(new BoolSetting("hud", "Record the HUD", true), "Video");
    private final BoolSetting indicator = add(new BoolSetting("indicator", "Show REC while recording", true), "Video");
    private final ActionSetting open = add(new ActionSetting("open", "Videos folder", "Open", ScreenRecorder::openVideosFolder), "Video");

    private boolean clipWasDown, recordWasDown;

    public Clips() {
        super("clips", "Clips", Category.MISC,
                "Records your screen: save the last moments with one key (F8), or record a video with another (F9).");
        instance = this;
        seconds.visibleWhen(buffer::on);
        quality.labelled(o -> o.substring(0, 1).toUpperCase() + o.substring(1));
        resolution.labelled(o -> o.equals("native") ? "Window" : o + "p");
        codec.labelled(o -> o.equals("hevc") ? "HEVC" : "H.264");
        for (Setting<?> s : new Setting<?>[] {buffer, seconds, fps, quality, resolution, codec}) {
            s.onChange(ScreenRecorder::settingsChanged);
        }
    }

    public static Clips instance() {
        return instance;
    }

    /** Its switches are the SETTINGS tab of Media, as on 1.21.11, so it stays out of the module list. */
    @Override
    public boolean listed() { return false; }

    /** "off", "manual" (the record key only) or "buffer" (the clip key too). */
    public String mode() {
        return !enabled() ? "off" : buffer.on() ? "buffer" : "manual";
    }

    public void setMode(String mode) {
        if (!mode.equals("off")) buffer.set(mode.equals("buffer"));
        setEnabled(!mode.equals("off"));
        ScreenRecorder.settingsChanged();
    }

    public void setSeconds(int s) { seconds.set(s); }
    public void setFps(String v) { fps.set(v); }
    public void setQuality(String v) { quality.set(v); }
    public void setResolution(String v) { resolution.set(v); }
    public void setCodec(String v) { codec.set(v); }
    public void setHud(boolean v) { hud.set(v); }
    public boolean indicator() { return indicator.on(); }
    public void setIndicator(boolean v) { indicator.set(v); }

    public String clipKeyName() { return dev.dusk.client.util.Keys.display(clipKey.get()); }
    public String recordKeyName() { return dev.dusk.client.util.Keys.display(recordKey.get()); }

    public boolean bufferOn() { return buffer.on(); }

    public int seconds() { return seconds.get(); }

    public int fps() { return fps.is("30") ? 30 : 60; }

    public String quality() { return quality.get(); }

    public String resolution() { return resolution.get(); }

    public String codec() { return codec.get(); }

    public boolean hud() { return hud.on(); }

    /** Polled every client tick; never while a screen is open, so typing F8 in chat does nothing. */
    public void tickKeys(Minecraft mc) {
        boolean clip = mc.currentScreen == null && clipKey.held();
        if (clip && !clipWasDown) ScreenRecorder.saveClip(mc);
        clipWasDown = clip;
        boolean record = mc.currentScreen == null && recordKey.held();
        if (record && !recordWasDown) ScreenRecorder.toggleRecording(mc);
        recordWasDown = record;
    }

    /** The red mark in the top right corner while a video is being recorded. */
    public void drawIndicator(Canvas canvas, HudContext ctx) {
        if (!enabled() || !indicator.on() || !ScreenRecorder.recording()) return;
        String s = "● REC " + ScreenRecorder.duration(ScreenRecorder.recordingMs());
        canvas.text(s, ctx.width() - 6 - ctx.textWidth(s), 6, 0xFFFF5555, true);
    }
}
