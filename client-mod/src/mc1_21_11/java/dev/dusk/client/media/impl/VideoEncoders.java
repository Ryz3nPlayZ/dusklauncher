package dev.dusk.client.media.impl;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Picks the screen recorder's video encoder the way OBS does: the GPU's own
 * encoder when there is one (VideoToolbox on macOS; NVENC, AMF, Quick Sync
 * or Media Foundation on Windows; NVENC on Linux), x264 otherwise. Each
 * candidate is tried on a few black frames first, since an ffmpeg build
 * lists encoders the machine has no hardware for.
 */
final class VideoEncoders {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    /** The encoder that worked per ffmpeg, codec, size and rate. */
    private static final Map<String, String> PICKED = new ConcurrentHashMap<>();

    private VideoEncoders() {}

    /** One way to encode: the ffmpeg arguments and the pixel format it wants. */
    record Choice(String name, List<String> args, String pixFmt, boolean hevc) {}

    /**
     * The encoder for this ffmpeg and stream, with its arguments; blocking
     * (the first call per size runs trial encodes), so off the render thread.
     */
    static Choice pick(Path exe, String codec, String quality, int width, int height, int fps) {
        boolean wantHevc = codec.equals("hevc");
        String key = exe + "|" + codec + "|" + width + "x" + height + "@" + fps;
        String encoders = VideoExporter.encoders(exe);
        long bitrate = bitrate(quality, width, height, fps);
        List<Choice> candidates = new ArrayList<>();
        if (wantHevc) candidates.addAll(hardware(true, bitrate, fps));
        candidates.addAll(hardware(false, bitrate, fps));
        candidates.add(x264(quality, bitrate));
        String done = PICKED.get(key);
        for (Choice c : candidates) {
            if (done != null) {
                if (c.name.equals(done)) return c;
                continue;
            }
            if (!encoders.isEmpty() && !encoders.contains(" " + c.name + " ")) continue;
            if (c.name.equals("libx264") || trial(exe, c, width, height, fps)) {
                PICKED.put(key, c.name);
                return c;
            }
        }
        // no x264 in this build: MPEG-4, which every build has
        Choice fallback = new Choice("mpeg4", List.of("-c:v", "mpeg4", "-q:v", "3"), "yuv420p", false);
        PICKED.put(key, fallback.name);
        return fallback;
    }

    /** Bits per second: a bits-per-pixel rate for each quality, as streaming guides size them. */
    static long bitrate(String quality, int width, int height, int fps) {
        double bpp = switch (quality) {
            case "standard" -> 0.06;
            case "ultra" -> 0.16;
            default -> 0.10;
        };
        return Math.max(2_000_000L, Math.round((double) width * height * fps * bpp));
    }

    private static List<Choice> hardware(boolean hevc, long bitrate, int fps) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        long b = hevc ? Math.round(bitrate * 0.65) : bitrate;
        String c = hevc ? "hevc" : "h264";
        List<String> rate = List.of("-b:v", String.valueOf(b), "-maxrate", String.valueOf(b * 3 / 2), "-bufsize", String.valueOf(b * 2));
        List<Choice> out = new ArrayList<>();
        if (os.contains("mac")) {
            out.add(choice(c + "_videotoolbox", hevc, "nv12", rate, "-realtime", "1", "-allow_sw", "0"));
        } else if (os.startsWith("windows")) {
            out.add(choice(c + "_nvenc", hevc, "yuv420p", rate, "-preset", "p4", "-tune", "ll", "-rc", "vbr", "-forced-idr", "1"));
            out.add(choice(c + "_amf", hevc, "yuv420p", rate, "-usage", "lowlatency", "-quality", "speed"));
            out.add(choice(c + "_qsv", hevc, "nv12", rate, "-preset", "veryfast"));
            out.add(choice(c + "_mf", hevc, "nv12", rate, "-hw_encoding", "1"));
        } else {
            out.add(choice(c + "_nvenc", hevc, "yuv420p", rate, "-preset", "p4", "-tune", "ll", "-rc", "vbr", "-forced-idr", "1"));
        }
        return out;
    }

    private static Choice choice(String name, boolean hevc, String pixFmt, List<String> rate, String... extra) {
        List<String> args = new ArrayList<>(List.of("-c:v", name));
        args.addAll(List.of(extra));
        args.addAll(rate);
        return new Choice(name, List.copyOf(args), pixFmt, hevc);
    }

    private static Choice x264(String quality, long bitrate) {
        String crf = switch (quality) {
            case "standard" -> "23";
            case "ultra" -> "17";
            default -> "20";
        };
        // capped, so a busy scene can't outrun the disk or the buffer's size
        return new Choice("libx264", List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                "-maxrate", String.valueOf(bitrate * 2), "-bufsize", String.valueOf(bitrate * 2)), "yuv420p", false);
    }

    /** A few frames through the encoder: true when it made them. */
    private static boolean trial(Path exe, Choice c, int width, int height, int fps) {
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=black:s=" + width + "x" + height + ":r=" + fps,
                "-frames:v", "3", "-pix_fmt", c.pixFmt));
        cmd.addAll(c.args);
        cmd.addAll(List.of("-f", "null", "-"));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readNBytes(4096);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOG.info("{} took too long to try; skipping it", c.name);
                return false;
            }
            boolean ok = p.exitValue() == 0;
            if (!ok) LOG.info("{} can't encode here: {}", c.name, firstLine(out));
            else LOG.info("Recording with {}", c.name);
            return ok;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String firstLine(byte @Nullable [] out) {
        if (out == null) return "";
        String s = new String(out, java.nio.charset.StandardCharsets.UTF_8).strip();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** "VideoToolbox" style names for the settings page. */
    static String label(String encoder) {
        if (encoder.contains("videotoolbox")) return "VideoToolbox (GPU)";
        if (encoder.contains("nvenc")) return "NVENC (GPU)";
        if (encoder.contains("amf")) return "AMF (GPU)";
        if (encoder.contains("qsv")) return "Quick Sync (GPU)";
        if (encoder.endsWith("_mf")) return "Media Foundation";
        if (encoder.equals("libx264")) return "x264 (CPU)";
        return encoder;
    }
}
