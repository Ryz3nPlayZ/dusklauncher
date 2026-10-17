package dev.dusk.client.voice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Input;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Voice chat's settings. Three ways to be heard, each with its own switch:
 * the server's own voice chat (servers running Simple Voice Chat), Dusk
 * players standing near you on any server, and calls with Dusk friends.
 */
public class VoiceChat extends Module {
    private static final String FRIENDS = "Friends", EVERYONE = "Everyone", OFF = "Off";
    private static final String PTT = "Push to talk", VOICE = "Voice activation";

    @Nullable private static VoiceChat instance;

    private final BoolSetting serverVoice = add(new BoolSetting("server", "Server voice chat", true), "Who you hear");
    private final ChoiceSetting nearby = add(new ChoiceSetting("nearby", "Dusk players nearby", FRIENDS, FRIENDS, EVERYONE, OFF), "Who you hear");
    private final ChoiceSetting calls = add(new ChoiceSetting("calls", "Calls from", FRIENDS, FRIENDS, OFF), "Who you hear");

    private final ChoiceSetting mode = add(new ChoiceSetting("mode", "Talk with", PTT, PTT, VOICE), "Talking");
    private final IntSetting activation = add(new IntSetting("activation", "Activation level", -50, -90, 0, 1, " dB"), "Talking");
    private final BoolSetting muted = add(new BoolSetting("muted", "Mute microphone", false), "Talking");
    private final BoolSetting deafened = add(new BoolSetting("deafened", "Deafen", false), "Talking");

    private final KeySetting pttKey = add(new KeySetting("voice_ptt", "Push to talk key", InputConstants.KEY_V), "Keybinds");
    private final KeySetting whisperKey = add(new KeySetting("voice_whisper", "Whisper key", Input.UNKNOWN), "Keybinds");
    private final KeySetting muteKey = add(new KeySetting("voice_mute", "Mute key", Input.UNKNOWN), "Keybinds");
    private final KeySetting deafenKey = add(new KeySetting("voice_deafen", "Deafen key", Input.UNKNOWN), "Keybinds");

    private final IntSetting micVolume = add(new IntSetting("micvolume", "Microphone volume", 100, 0, 300, 5, "%"), "Devices");
    private final IntSetting voiceVolume = add(new IntSetting("volume", "Voice volume", 100, 0, 200, 5, "%"), "Devices");
    private final DeviceSetting mic = add(new DeviceSetting("mic", "Microphone", true), "Devices");
    private final DeviceSetting speaker = add(new DeviceSetting("speaker", "Speakers", false), "Devices");

    public VoiceChat() {
        super("voicechat", "Voice Chat", Category.MISC,
                "Talk on servers with Simple Voice Chat, to Dusk players near you, and in calls with friends.");
        instance = this;
        serverVoice.onChange(SvcClient::wantedChanged);
        nearby.onChange(DuskVoice::sendMode);
        calls.onChange(DuskVoice::sendMode);
        muted.onChange(SvcClient::sendState);
        deafened.onChange(SvcClient::sendState);
        activation.visibleWhen(() -> mode.is(VOICE));
        activation.format(v -> {
            float now = Voice.level();
            return v + " dB" + (Voice.active() || Voice.testing() ? "  (now " + (now <= -100 ? "silent" : Math.round(now) + " dB") + ")" : "");
        });
        add(new ActionSetting("test", "Hear yourself", "Test", Voice::toggleTest) {
            @Override
            public String button() { return Voice.testing() ? "Stop" : "Test"; }
        }, "Devices");
        pttKey.visibleWhen(() -> mode.is(PTT));
    }

    @Nullable
    static VoiceChat instance() { return instance; }

    @Override
    public String description() {
        String base = super.description();
        if (blocked()) return base;
        return base + " Now: " + Voice.status() + ".";
    }

    @Override
    protected void onEnable() {
        SvcClient.wantedChanged();
    }

    @Override
    protected void onDisable() {
        SvcClient.wantedChanged();
        if (Voice.testing()) Voice.toggleTest();
    }

    // ---- what Voice reads ---------------------------------------------------

    boolean serverVoice() { return serverVoice.get(); }

    boolean nearbyOn() { return !nearby.is(OFF); }

    String nearbyMode() { return nearby.get().toLowerCase(java.util.Locale.ROOT); }

    String callsMode() { return calls.get().toLowerCase(java.util.Locale.ROOT); }

    /** Whether the Dusk voice link is needed at all. */
    boolean duskWanted() { return nearbyOn() || !calls.is(OFF) || DuskVoice.inCall(); }

    boolean pushToTalk() { return mode.is(PTT); }

    int activationDb() { return activation.get(); }

    boolean muted() { return muted.get(); }

    boolean deafened() { return deafened.get(); }

    float micGain() { return micVolume.get() / 100f; }

    float volume() { return voiceVolume.get() / 100f; }

    String micDevice() { return mic.get(); }

    String speakerDevice() { return speaker.get(); }

    boolean pushToTalkDown() { return pttKey.mapping().isDown(); }

    boolean whisperDown() { return whisperKey.mapping().isDown(); }

    public void setMuted(boolean on) {
        muted.set(on);
        save();
    }

    public void setDeafened(boolean on) {
        deafened.set(on);
        save();
    }

    void tickKeys() {
        while (muteKey.mapping().consumeClick()) setMuted(!muted.get());
        while (deafenKey.mapping().consumeClick()) setDeafened(!deafened.get());
    }

    private static void save() {
        if (DuskClient.modules() != null) DuskClient.modules().saveConfig();
    }

    /** A microphone or speaker, from whatever the computer has plugged in. */
    private static final class DeviceSetting extends ChoiceSetting {
        private final boolean input;
        private List<String> cached = List.of(AudioEngine.DEFAULT);
        private long cachedAt;

        DeviceSetting(String id, String name, boolean input) {
            super(id, name, AudioEngine.DEFAULT, AudioEngine.DEFAULT);
            this.input = input;
        }

        @Override
        public List<String> options() {
            long now = System.currentTimeMillis();
            if (now - cachedAt > 3000) {
                cachedAt = now;
                List<String> found = AudioEngine.devices(input);
                if (!found.contains(get())) {
                    found = new java.util.ArrayList<>(found);
                    found.add(get()); // unplugged, but keep the choice
                }
                cached = found;
            }
            return cached;
        }

        @Override
        public int index() { return Math.max(0, options().indexOf(get())); }

        @Override
        public void next() {
            List<String> o = options();
            set(o.get((index() + 1) % o.size()));
        }

        @Override
        public void load(Object raw) {
            if (raw instanceof String s && !s.isBlank()) value = s;
        }
    }
}
