package dev.dusk.client.voice;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.DuskClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice chat's middle: one microphone and one output shared by the two
 * ways voices travel. {@link SvcClient} carries them on servers running
 * Simple Voice Chat; {@link DuskVoice} carries them between Dusk players
 * through the Dusk service, both nearby (on the same server, by distance)
 * and in calls between friends. The settings live on {@link VoiceChat}.
 */
public final class Voice {
    public enum Source { SERVER, NEARBY, CALL, TEST }

    /** Hold the mic open this many frames after the voice drops under the activation level. */
    private static final int HANGOVER = 25;

    private static final Map<String, Speaker> speakers = new ConcurrentHashMap<>();
    /** How far voices carry on the Simple Voice Chat server we're on. */
    static volatile double distance = 48;

    @Nullable private static AudioEngine.Mic mic;
    @Nullable private static AudioEngine.Output output;
    private static String micDevice = "", outDevice = "";
    @Nullable private static Opus.Encoder encoder;
    private static long seq;
    private static boolean transmitting;
    private static int hangover;
    private static volatile boolean pttDown, whisperDown, testing, talking;
    private static volatile float level = -127;
    @Nullable private static volatile UUID self;

    private static final Map<UUID, Integer> volumes = new ConcurrentHashMap<>();
    private static boolean volumesLoaded;

    private Voice() {}

    public static void init() {
        SvcClient.init();
    }

    @Nullable
    static VoiceChat settings() {
        return VoiceChat.instance();
    }

    private static boolean on() {
        VoiceChat m = settings();
        return m != null && m.enabled();
    }

    static boolean serverWanted() {
        VoiceChat m = settings();
        return m != null && m.enabled() && m.serverVoice();
    }

    public static boolean muted() {
        VoiceChat m = settings();
        return m != null && m.muted();
    }

    public static boolean deafened() {
        VoiceChat m = settings();
        return m != null && m.deafened();
    }

    /** You're sending your voice right now. */
    public static boolean talking() { return talking && !testing; }

    /** The microphone's last level, in dBFS (-127 when it is closed). */
    public static float level() { return mic == null ? -127 : level; }

    public static boolean testing() { return testing; }

    /** Plays the microphone back to you, through the codec, until called again. */
    public static void toggleTest() {
        testing = !testing;
    }

    /** Whether any voice connection is up, so the mic and speakers are in use. */
    public static boolean active() {
        return SvcClient.connected() || DuskVoice.active();
    }

    /** A line for the settings page saying what voice is doing. */
    public static String status() {
        if (!on()) return "Voice chat is off";
        List<String> parts = new ArrayList<>();
        String svc = SvcClient.status();
        if (svc != null) parts.add(svc);
        String dusk = DuskVoice.status();
        if (dusk != null) parts.add(dusk);
        AudioEngine.Mic m = mic;
        if (m != null && m.error() != null) parts.add(m.error());
        AudioEngine.Output o = output;
        if (o != null && o.error() != null) parts.add(o.error());
        return parts.isEmpty() ? "Not on a server with voice chat" : String.join(" · ", parts);
    }

    // ---- the game thread ---------------------------------------------------------

    /** Every client tick: connections, devices, keys and where each voice sits. */
    public static void tick(Minecraft mc) {
        VoiceChat m = settings();
        boolean on = m != null && m.enabled();
        if (mc.getUser() != null) self = mc.getUser().getProfileId();
        if (on) m.tickKeys();
        DuskVoice.tick(mc, on && m.duskWanted());
        if (!on) testing = false;

        boolean audio = on && (active() || testing);
        boolean hear = audio && (!m.deafened() || testing);
        boolean speak = audio && ((!m.muted() && !m.deafened()) || testing);
        String wantOut = m == null ? AudioEngine.DEFAULT : m.speakerDevice();
        String wantMic = m == null ? AudioEngine.DEFAULT : m.micDevice();
        if (output != null && (!hear || !wantOut.equals(outDevice))) {
            output.stop();
            output = null;
        }
        if (hear && output == null) {
            outDevice = wantOut;
            output = new AudioEngine.Output(wantOut, Voice::playing, () -> {
                VoiceChat s = settings();
                return s == null ? 1f : s.volume();
            });
            output.start();
        }
        if (mic != null && (!speak || !wantMic.equals(micDevice))) {
            mic.stop();
            mic = null;
            level = -127;
        }
        if (speak && mic == null) {
            micDevice = wantMic;
            mic = new AudioEngine.Mic(wantMic, () -> {
                VoiceChat s = settings();
                return s == null ? 1f : s.micGain();
            }, Voice::micFrame);
            mic.start();
        }

        boolean inGame = mc.player != null && dev.dusk.client.compat.Compat.currentScreen(mc) == null;
        pttDown = on && inGame && m.pushToTalkDown();
        whisperDown = on && inGame && m.whisperDown();

        place(mc);
        speakers.values().removeIf(Speaker::stale);
    }

    /** Works out each voice's left and right gain from where it is. */
    private static void place(Minecraft mc) {
        LocalPlayer me = mc.player;
        for (Speaker s : speakers.values()) {
            float gain = s.source == Source.TEST ? 1 : volume(s.sender) / 100f;
            if (s.source == Source.TEST || s.flat || me == null || mc.level == null) {
                s.left = s.right = gain;
                continue;
            }
            double x, y, z;
            double[] at = s.at;
            if (at != null) {
                x = at[0];
                y = at[1];
                z = at[2];
            } else {
                Player p = mc.level.getPlayerByUUID(s.sender);
                if (p == null) {
                    // the server says they're in range, but they aren't loaded here
                    s.left = s.right = gain * 0.5f;
                    continue;
                }
                x = p.getX();
                y = p.getEyeY();
                z = p.getZ();
            }
            double dx = x - me.getX(), dy = y - me.getEyeY(), dz = z - me.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double max = s.distance > 0 ? s.distance : s.source == Source.SERVER ? distance : DuskVoice.RANGE;
            float fall = (float) Math.max(0, Math.min(1, 1 - dist / Math.max(1, max)));
            // which ear: the direction against the way you face (yaw 0 looks south, +Z)
            double flat = Math.sqrt(dx * dx + dz * dz);
            float pan = 0;
            if (flat > 0.5) {
                double yaw = Math.toRadians(me.getYRot());
                double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                pan = (float) ((dx * rx + dz * rz) / flat);
            }
            s.left = gain * fall * (1 - Math.max(0, pan) * 0.7f);
            s.right = gain * fall * (1 + Math.min(0, pan) * 0.7f);
        }
    }

    // ---- audio threads -----------------------------------------------------------

    private static Collection<Speaker> playing() {
        return speakers.values();
    }

    private static void micFrame(short[] pcm, float db) {
        level = db;
        VoiceChat m = settings();
        if (m == null) return;
        boolean talk;
        if (m.pushToTalk()) talk = pttDown || whisperDown || testing;
        else {
            if (db >= m.activationDb()) hangover = HANGOVER;
            talk = hangover > 0;
            if (hangover > 0) hangover--;
        }
        if (!testing && (m.muted() || m.deafened())) talk = false;
        Opus.Encoder enc = encoder;
        if (enc == null) encoder = enc = new Opus.Encoder();
        UUID me = self != null ? self : new UUID(0, 0);
        if (talk) {
            byte[] opus = enc.encode(pcm);
            long n = ++seq;
            if (testing) heard(Source.TEST, me, me, n, opus, null, true, 0, false);
            else {
                boolean whisper = whisperDown && !pttDown;
                SvcClient.sendMic(opus, n, whisper);
                DuskVoice.sendMic(opus, n, whisper);
            }
            transmitting = true;
        } else if (transmitting) {
            transmitting = false;
            long n = ++seq;
            if (testing) heard(Source.TEST, me, me, n, new byte[0], null, true, 0, false);
            else {
                SvcClient.sendMic(new byte[0], n, false);
                DuskVoice.sendMic(new byte[0], n, false);
            }
            enc.reset();
        }
        talking = talk;
    }

    /** A voice packet from either connection. Network threads. */
    static void heard(Source source, UUID channel, UUID sender, long seq, byte[] opus,
                      @Nullable double[] at, boolean flat, float distance, boolean whisper) {
        if (source != Source.TEST && (deafened() || volume(sender) <= 0)) return;
        // on a Simple Voice Chat server its voices win; Dusk's own nearby would double them
        if (source == Source.NEARBY && SvcClient.connected()) return;
        Speaker s = speakers.computeIfAbsent(source.name() + channel, k -> new Speaker(channel, sender, source));
        s.at = at;
        s.flat = flat;
        s.distance = distance;
        s.whisper = whisper;
        s.push(seq, opus);
    }

    /** Who is talking now, for the HUD. */
    public static List<Talker> talkers() {
        List<Talker> out = new ArrayList<>();
        for (Speaker s : speakers.values()) {
            if (s.source != Source.TEST && s.talking()) out.add(new Talker(s.sender, name(s.sender), s.source, s.whisper));
        }
        out.sort(java.util.Comparator.comparing(Talker::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public record Talker(UUID uuid, String name, Source source, boolean whisper) {}

    /** Whether {@code uuid} has been heard in the last moment (the Friends page marks them). */
    public static boolean isTalking(UUID uuid) {
        for (Speaker s : speakers.values()) if (s.sender.equals(uuid) && s.talking()) return true;
        return false;
    }

    static String name(UUID uuid) {
        String n = DuskVoice.name(uuid);
        if (n != null) return n;
        SvcClient.Peer peer = SvcClient.peers.get(uuid);
        if (peer != null) return peer.name();
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.level == null ? null : mc.level.getPlayerByUUID(uuid);
        if (p != null) return p.getName().getString();
        return uuid.toString().substring(0, 8);
    }

    /** Leaving a server: drop its voices and its Simple Voice Chat link. */
    public static void leave() {
        SvcClient.leave();
        speakers.values().removeIf(s -> s.source == Source.SERVER || s.source == Source.NEARBY);
    }

    public static void join() {
        DuskVoice.refresh();
    }

    // ---- per-player volume ----------------------------------------------------

    private static Path volumesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("duskclient-voice.json");
    }

    /** A player's voice volume, 0 to 200 percent; 0 is muted. */
    public static int volume(UUID uuid) {
        loadVolumes();
        return volumes.getOrDefault(uuid, 100);
    }

    public static void setVolume(UUID uuid, int percent) {
        loadVolumes();
        percent = Math.max(0, Math.min(200, percent));
        if (percent == 100) volumes.remove(uuid);
        else volumes.put(uuid, percent);
        try {
            JsonObject root = new JsonObject();
            JsonObject v = new JsonObject();
            volumes.forEach((k, p) -> v.addProperty(k.toString(), p));
            root.add("volumes", v);
            Files.writeString(volumesFile(), new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DuskClient.LOGGER.warn("[voice] couldn't save player volumes", e);
        }
    }

    private static synchronized void loadVolumes() {
        if (volumesLoaded) return;
        volumesLoaded = true;
        try {
            Path f = volumesFile();
            if (!Files.exists(f)) return;
            JsonObject v = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("volumes");
            if (v == null) return;
            for (var e : v.entrySet()) {
                try {
                    volumes.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
                } catch (RuntimeException ignored) {
                    // a bad line
                }
            }
        } catch (Exception e) {
            DuskClient.LOGGER.warn("[voice] couldn't read player volumes", e);
        }
    }
}
