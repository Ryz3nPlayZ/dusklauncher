package dev.dusk.client.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One emote read from a Bedrock / Blockbench animation file (the format
 * Blockbench exports with File → Export → Animations, for a player-shaped
 * model). Each bone carries rotation (degrees) and position (pixels)
 * keyframes; {@link #sample} gives the pose at a time.
 *
 * <p>Only plain numbers are read. Molang expressions such as
 * {@code "math.sin(query.anim_time * 90) * 10"} count as 0 and the file is
 * flagged ({@link #usesMolang}), so the wheel can say why a move looks off.
 */
public final class EmoteAnimation {
    public enum Loop { ONCE, LOOP, HOLD }

    public enum Bone {
        HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG;

        /** The bone a Blockbench group name means, or null for one we don't drive. */
        static Bone named(String raw) {
            String n = raw.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
            return switch (n) {
                case "head" -> HEAD;
                case "body", "torso", "chest" -> BODY;
                case "rightarm", "armright" -> RIGHT_ARM;
                case "leftarm", "armleft" -> LEFT_ARM;
                case "rightleg", "legright" -> RIGHT_LEG;
                case "leftleg", "legleft" -> LEFT_LEG;
                default -> null;
            };
        }
    }

    private final String name;
    private final double length;
    private final Loop loop;
    private final boolean usesMolang;
    private final Map<Bone, Channel> rotation;
    private final Map<Bone, Channel> position;

    private EmoteAnimation(String name, double length, Loop loop, boolean usesMolang,
                           Map<Bone, Channel> rotation, Map<Bone, Channel> position) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.usesMolang = usesMolang;
        this.rotation = rotation;
        this.position = position;
    }

    public String name() { return name; }
    public double length() { return length; }
    public Loop loop() { return loop; }
    public boolean usesMolang() { return usesMolang; }

    /** True once a non-looping emote has played through. */
    public boolean finished(double seconds) {
        return loop == Loop.ONCE && seconds >= length;
    }

    /** The pose {@code seconds} after the emote started. */
    public EmotePose sample(double seconds) {
        double t = seconds;
        if (loop == Loop.LOOP && length > 0) t = seconds % length;
        else if (t > length) t = length;
        EmotePose pose = new EmotePose();
        double[] v = new double[3];
        for (var e : rotation.entrySet()) {
            e.getValue().sample(t, v);
            pose.rotate(e.getKey(), (float) v[0], (float) v[1], (float) v[2]);
        }
        for (var e : position.entrySet()) {
            e.getValue().sample(t, v);
            pose.move(e.getKey(), (float) v[0], (float) v[1], (float) v[2]);
        }
        return pose;
    }

    /* ── parsing ── */

    /** Every animation in one file's text; names that drive no player bone are skipped. */
    public static List<EmoteAnimation> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<EmoteAnimation> out = new ArrayList<>();
        if (!root.isJsonObject()) return out;
        JsonObject animations = root.getAsJsonObject().getAsJsonObject("animations");
        if (animations == null) return out;
        for (var e : animations.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            EmoteAnimation a = parseOne(e.getKey(), e.getValue().getAsJsonObject());
            if (a != null) out.add(a);
        }
        return out;
    }

    private static EmoteAnimation parseOne(String key, JsonObject anim) {
        boolean[] molang = {false};
        Map<Bone, Channel> rot = new EnumMap<>(Bone.class), pos = new EnumMap<>(Bone.class);
        JsonObject bones = anim.getAsJsonObject("bones");
        if (bones == null) return null;
        double last = 0;
        for (var b : bones.entrySet()) {
            Bone bone = Bone.named(b.getKey());
            if (bone == null || !b.getValue().isJsonObject()) continue;
            JsonObject o = b.getValue().getAsJsonObject();
            Channel r = Channel.parse(o.get("rotation"), molang);
            Channel p = Channel.parse(o.get("position"), molang);
            if (r != null) { rot.put(bone, r); last = Math.max(last, r.lastTime()); }
            if (p != null) { pos.put(bone, p); last = Math.max(last, p.lastTime()); }
        }
        if (rot.isEmpty() && pos.isEmpty()) return null;
        double length = anim.has("animation_length") ? number(anim.get("animation_length"), molang) : last;
        Loop loop = Loop.ONCE;
        JsonElement l = anim.get("loop");
        if (l != null && l.isJsonPrimitive()) {
            JsonPrimitive p = l.getAsJsonPrimitive();
            if (p.isBoolean() && p.getAsBoolean()) loop = Loop.LOOP;
            else if (p.isString() && p.getAsString().equals("hold_on_last_frame")) loop = Loop.HOLD;
            else if (p.isString() && p.getAsString().equals("true")) loop = Loop.LOOP;
        }
        return new EmoteAnimation(displayName(key), length, loop, molang[0], rot, pos);
    }

    /** "animation.player.big_wave" → "Big wave". */
    static String displayName(String key) {
        String n = key;
        if (n.startsWith("animation.")) n = n.substring("animation.".length());
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) n = n.substring(dot + 1);
        n = n.replace('_', ' ').replace('-', ' ').trim();
        if (n.isEmpty()) return key;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    static double number(JsonElement e, boolean[] molang) {
        if (e == null || !e.isJsonPrimitive()) return 0;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsDouble();
        if (p.isString()) {
            String s = p.getAsString().trim();
            if (s.isEmpty()) return 0;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ex) {
                molang[0] = true;
                return 0;
            }
        }
        return 0;
    }

    /** One bone's rotation or position track. */
    static final class Channel {
        enum Lerp { LINEAR, CATMULLROM, STEP }

        final double[] times;
        final double[][] pre, post;
        final Lerp[] lerp;

        private Channel(double[] times, double[][] pre, double[][] post, Lerp[] lerp) {
            this.times = times;
            this.pre = pre;
            this.post = post;
            this.lerp = lerp;
        }

        double lastTime() { return times[times.length - 1]; }

        static Channel parse(JsonElement e, boolean[] molang) {
            if (e == null || e.isJsonNull()) return null;
            // a constant: [x, y, z], a single number, or one molang string
            if (e.isJsonArray() || e.isJsonPrimitive()) {
                double[] v = vector(e, molang);
                return new Channel(new double[] {0}, new double[][] {v}, new double[][] {v}, new Lerp[] {Lerp.LINEAR});
            }
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            // a single keyframe written as {"vector": [...]} instead of a timeline
            if (o.has("vector") || o.has("pre") || o.has("post")) {
                TreeMap<Double, JsonElement> one = new TreeMap<>();
                one.put(0.0, o);
                return fromFrames(one, molang);
            }
            TreeMap<Double, JsonElement> frames = new TreeMap<>();
            for (var f : o.entrySet()) {
                try {
                    frames.put(Double.parseDouble(f.getKey().trim()), f.getValue());
                } catch (NumberFormatException ignored) {
                    // not a timestamp
                }
            }
            return frames.isEmpty() ? null : fromFrames(frames, molang);
        }

        private static Channel fromFrames(TreeMap<Double, JsonElement> frames, boolean[] molang) {
            int n = frames.size(), i = 0;
            double[] times = new double[n];
            double[][] pre = new double[n][], post = new double[n][];
            Lerp[] lerp = new Lerp[n];
            for (var f : frames.entrySet()) {
                times[i] = f.getKey();
                JsonElement v = f.getValue();
                Lerp mode = Lerp.LINEAR;
                if (v.isJsonObject()) {
                    JsonObject k = v.getAsJsonObject();
                    JsonElement vec = k.has("vector") ? k.get("vector") : null;
                    JsonElement a = k.has("pre") ? k.get("pre") : k.has("post") ? k.get("post") : vec;
                    JsonElement b = k.has("post") ? k.get("post") : k.has("pre") ? k.get("pre") : vec;
                    pre[i] = vector(a, molang);
                    post[i] = vector(b, molang);
                    if (k.has("lerp_mode") && k.get("lerp_mode").isJsonPrimitive()) {
                        String m = k.get("lerp_mode").getAsString();
                        if (m.equals("catmullrom")) mode = Lerp.CATMULLROM;
                        else if (m.equals("step")) mode = Lerp.STEP;
                    }
                } else {
                    pre[i] = post[i] = vector(v, molang);
                }
                lerp[i] = mode;
                i++;
            }
            return new Channel(times, pre, post, lerp);
        }

        private static double[] vector(JsonElement e, boolean[] molang) {
            if (e == null) return new double[3];
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                double[] v = new double[3];
                for (int j = 0; j < 3 && j < a.size(); j++) v[j] = number(a.get(j), molang);
                return v;
            }
            double s = number(e, molang);
            return new double[] {s, s, s};
        }

        void sample(double t, double[] out) {
            int n = times.length;
            if (n == 1 || t <= times[0]) { copy(t <= times[0] ? pre[0] : post[0], out); return; }
            if (t >= times[n - 1]) { copy(post[n - 1], out); return; }
            int i = 0;
            while (i < n - 2 && t >= times[i + 1]) i++;
            double span = times[i + 1] - times[i];
            double f = span <= 0 ? 1 : (t - times[i]) / span;
            if (lerp[i] == Lerp.STEP) { copy(post[i], out); return; }
            double[] a = post[i], b = pre[i + 1];
            if (lerp[i] == Lerp.CATMULLROM || lerp[i + 1] == Lerp.CATMULLROM) {
                double[] before = i > 0 ? post[i - 1] : a;
                double[] after = i + 2 < n ? pre[i + 2] : b;
                for (int j = 0; j < 3; j++) out[j] = catmullRom(f, before[j], a[j], b[j], after[j]);
            } else {
                for (int j = 0; j < 3; j++) out[j] = a[j] + (b[j] - a[j]) * f;
            }
        }

        private static void copy(double[] v, double[] out) {
            out[0] = v[0];
            out[1] = v[1];
            out[2] = v[2];
        }

        private static double catmullRom(double f, double p0, double p1, double p2, double p3) {
            double f2 = f * f, f3 = f2 * f;
            return 0.5 * (2 * p1 + (p2 - p0) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (3 * p1 - p0 - 3 * p2 + p3) * f3);
        }
    }
}
