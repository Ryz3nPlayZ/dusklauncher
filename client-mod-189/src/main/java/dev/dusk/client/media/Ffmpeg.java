package dev.dusk.client.media;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The ffmpeg on this machine and the short jobs the recorder gives it:
 * listing its encoders, turning a finished MPEG-TS into an MP4 without
 * re-encoding, and pulling a thumbnail out of a video. Everything here
 * blocks, so none of it runs on the render thread.
 */
public final class Ffmpeg {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");
    private static final Map<String, String> ENCODERS = new ConcurrentHashMap<String, String>();

    private Ffmpeg() {}

    /** What a finished ffmpeg run left behind. */
    public static final class Result {
        public final int exit;
        public final boolean timedOut;
        public final String output;

        Result(int exit, boolean timedOut, String output) {
            this.exit = exit;
            this.timedOut = timedOut;
            this.output = output;
        }

        public boolean ok() {
            return !timedOut && exit == 0;
        }
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The ffmpeg to use, or null when there is none: -Ddusk.ffmpeg, then PATH, then the usual places. */
    public static Path find() {
        String name = windows() ? "ffmpeg.exe" : "ffmpeg";
        List<String> dirs = new ArrayList<String>();
        String path = System.getenv("PATH");
        if (path != null) dirs.addAll(Arrays.asList(path.split(File.pathSeparator)));
        // a launcher started from the dock doesn't get the shell's PATH
        dirs.addAll(Arrays.asList("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        List<Path> candidates = new ArrayList<Path>();
        try {
            String prop = System.getProperty("dusk.ffmpeg");
            if (prop != null && !prop.trim().isEmpty()) candidates.add(Paths.get(prop.trim()));
        } catch (InvalidPathException ignored) {
        }
        for (String d : dirs) {
            if (d.trim().isEmpty()) continue;
            try {
                candidates.add(Paths.get(d, name));
            } catch (InvalidPathException ignored) {
            }
        }
        for (Path c : candidates) if (Files.isRegularFile(c) && Files.isExecutable(c)) return c;
        return null;
    }

    /** {@code ffmpeg -encoders}, asked once per ffmpeg; empty when it couldn't be asked. */
    public static String encoders(Path exe) {
        String key = exe.toString();
        String known = ENCODERS.get(key);
        if (known != null) return known;
        Result r = run(Arrays.asList(key, "-hide_banner", "-encoders"), 5, 1 << 18);
        String list = r == null ? "" : r.output;
        if (!list.isEmpty()) ENCODERS.put(key, list);
        return list;
    }

    /**
     * Runs a command to its end, keeping the first {@code keep} bytes of what
     * it prints. Its output is read on another thread the whole time, so a
     * chatty run can't fill the pipe and hang.
     *
     * @return null when it couldn't be started
     */
    public static Result run(List<String> cmd, int timeoutSeconds, final int keep) {
        final Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            LOG.warn("Couldn't run " + cmd.get(0), e);
            return null;
        }
        final byte[] kept = new byte[keep];
        final int[] len = {0};
        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[4096];
                InputStream in = p.getInputStream();
                try {
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        int take = Math.min(n, keep - len[0]);
                        if (take > 0) {
                            System.arraycopy(chunk, 0, kept, len[0], take);
                            len[0] += take;
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "Dusk ffmpeg output");
        drain.setDaemon(true);
        drain.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        boolean done;
        try {
            done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            drain.join(2_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, true, "");
        }
        String out = new String(kept, 0, len[0], StandardCharsets.UTF_8);
        return new Result(done ? p.exitValue() : -1, !done, out);
    }

    /** MPEG-TS to MP4 without re-encoding. @return why it failed, or null */
    public static String remux(Path exe, Path in, Path out, boolean hevc) {
        // under a name the video lists skip until it is whole
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        List<String> cmd = new ArrayList<String>(Arrays.asList(exe.toString(), "-y", "-hide_banner", "-loglevel", "error",
                "-i", in.toString(), "-map", "0", "-c", "copy", "-movflags", "+faststart"));
        if (hevc) cmd.addAll(Arrays.asList("-tag:v", "hvc1"));
        cmd.addAll(Arrays.asList("-f", "mp4", tmp.toString()));
        Result r = run(cmd, 120, 8192);
        if (r == null) return "Couldn't save the video: ffmpeg didn't start.";
        if (r.timedOut) {
            deleteQuietly(tmp);
            return "Saving the video took too long.";
        }
        if (r.exit != 0 || !Files.isRegularFile(tmp)) {
            LOG.warn("Remux of " + in + " failed: " + r.output);
            deleteQuietly(tmp);
            return "Couldn't save the video (ffmpeg exit " + r.exit + ").";
        }
        try {
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return "Couldn't save the video: " + e.getMessage();
        }
    }

    /** Whether the video stream in {@code ts} is HEVC (an MP4 of it needs the hvc1 tag to play on Apple devices). */
    public static boolean probeHevc(Path exe, Path ts) {
        Result r = run(Arrays.asList(exe.toString(), "-hide_banner", "-i", ts.toString()), 10, 16384);
        return r != null && r.output.contains("Video: hevc");
    }

    /**
     * A frame a second into {@code video} as a PNG in {@code .thumbs} beside
     * it, where the launcher's video list looks for it.
     */
    public static boolean thumbnail(Path exe, Path video) {
        Path cache = video.resolveSibling(".thumbs").resolve(video.getFileName() + ".png");
        Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
        try {
            Files.createDirectories(cache.getParent());
            for (String at : new String[] {"1", "0"}) {
                Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-loglevel", "error", "-ss", at, "-i", video.toString(),
                        "-frames:v", "1", "-vf", "scale=320:-2", "-f", "image2pipe", "-vcodec", "png", "-")
                        .redirectOutput(tmp.toFile())
                        .redirectError(new File(windows() ? "NUL" : "/dev/null"))
                        .start();
                p.getOutputStream().close();
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
                if (Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
                    Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.debug("No thumbnail for " + video, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteQuietly(tmp);
        return false;
    }

    public static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
