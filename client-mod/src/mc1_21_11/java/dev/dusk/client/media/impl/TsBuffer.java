package dev.dusk.client.media.impl;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The replay buffer: ffmpeg's MPEG-TS output cut at each keyframe into
 * small files on disk, the old ones dropped as time passes, so the last
 * stretch is always there to save without re-encoding. Each piece starts
 * with the stream's tables and a keyframe, so any run of pieces in a row is
 * a stream that plays on its own. A recording takes the packets too, into
 * its own file, from the start of the piece it began in.
 */
final class TsBuffer {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    static final int PACKET = 188;
    private static final int VIDEO_PID = 0x100, PMT_PID = 0x1000;
    private static final long CLOCK = 90_000;

    /** One piece: a keyframe and what follows until the next. */
    static final class Unit {
        final Path file;
        final OutputStream out;
        /** When its first frame shows, on the stream's 90 kHz clock; -1 when unknown. */
        final long pts;
        long size;
        int pins;
        boolean dropped;

        Unit(Path file, long pts) throws IOException {
            this.file = file;
            this.pts = pts;
            this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        }
    }

    /** The pieces a save covers, held until they are copied out. */
    record Span(List<Unit> units, List<Long> sizes, double seconds) {}

    private final Path dir;
    private final ArrayDeque<Unit> units = new ArrayDeque<>();
    private final byte[] pat = new byte[PACKET], pmt = new byte[PACKET];
    private boolean havePat, havePmt;
    private long lastPts = -1;
    private int next;
    private volatile long keepTicks;
    private @Nullable OutputStream recording;
    private long recordingSize;
    private volatile @Nullable String failure;

    TsBuffer(Path dir, int keepSeconds) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        clear(dir);
        keep(keepSeconds);
    }

    void keep(int seconds) {
        keepTicks = (seconds + 2L) * CLOCK;
    }

    @Nullable String failure() {
        return failure;
    }

    // ---- reading ffmpeg ----------------------------------------------------------------

    /** Reads the stream until it ends; on its own thread. */
    void pump(InputStream in) {
        byte[] p = new byte[PACKET];
        try (in) {
            while (true) {
                if (!readPacket(in, p)) break;
                accept(p);
            }
        } catch (IOException e) {
            if (failure == null) failure = "The encoder's output stopped: " + e.getMessage();
        }
        synchronized (this) {
            Unit u = units.peekLast();
            if (u != null) flush(u);
        }
    }

    private static boolean readPacket(InputStream in, byte[] p) throws IOException {
        int n = in.readNBytes(p, 0, PACKET);
        if (n == 0) return false;
        if (n < PACKET) throw new EOFException("cut short");
        if (p[0] != 0x47) {
            // lost sync: find the next sync byte and read the rest of that packet
            int at = 1;
            while (at < PACKET && p[at] != 0x47) at++;
            if (at == PACKET) return true;
            System.arraycopy(p, at, p, 0, PACKET - at);
            int more = in.readNBytes(p, PACKET - at, at);
            if (more < at) return false;
        }
        return true;
    }

    private synchronized void accept(byte[] p) throws IOException {
        if (p[0] != 0x47) return;
        int pid = (p[1] & 0x1F) << 8 | p[2] & 0xFF;
        boolean start = (p[1] & 0x40) != 0;
        if (pid == 0) {
            System.arraycopy(p, 0, pat, 0, PACKET);
            havePat = true;
            return;
        }
        if (pid == PMT_PID) {
            System.arraycopy(p, 0, pmt, 0, PACKET);
            havePmt = true;
            return;
        }
        if (pid == VIDEO_PID && start && randomAccess(p) && havePat && havePmt) {
            long pts = pts(p);
            if (pts >= 0) lastPts = pts;
            begin(pts);
        }
        Unit u = units.peekLast();
        if (u == null) return;
        u.out.write(p);
        u.size += PACKET;
        OutputStream r = recording;
        if (r != null) {
            r.write(p);
            recordingSize += PACKET;
        }
    }

    private static boolean randomAccess(byte[] p) {
        int afc = p[3] >> 4 & 3;
        return (afc == 2 || afc == 3) && (p[4] & 0xFF) > 0 && (p[5] & 0x40) != 0;
    }

    /** The PES header's presentation time, or -1. */
    private static long pts(byte[] p) {
        int afc = p[3] >> 4 & 3;
        int i = 4;
        if (afc == 2 || afc == 3) i += 1 + (p[4] & 0xFF);
        if (afc == 2 || i + 14 > PACKET) return -1;
        if (p[i] != 0 || p[i + 1] != 0 || p[i + 2] != 1) return -1;
        if ((p[i + 7] & 0x80) == 0) return -1;
        int b = i + 9;
        return ((long) (p[b] & 0x0E) << 29) | ((long) (p[b + 1] & 0xFF) << 22) | ((long) (p[b + 2] & 0xFE) << 14)
                | ((long) (p[b + 3] & 0xFF) << 7) | ((p[b + 4] & 0xFE) >> 1);
    }

    private void begin(long pts) throws IOException {
        Unit prev = units.peekLast();
        if (prev != null) flush(prev);
        Unit u = new Unit(dir.resolve("unit_" + next++ + ".ts"), pts);
        u.out.write(pat);
        u.out.write(pmt);
        u.size = PACKET * 2L;
        units.addLast(u);
        prune();
    }

    private void prune() {
        if (lastPts < 0) {
            // no clock in the stream: about one piece a second
            while (units.size() > keepTicks / CLOCK) drop(units.pollFirst());
            return;
        }
        while (units.size() > 2) {
            Unit second = units.stream().skip(1).findFirst().orElseThrow();
            if (second.pts < 0 || lastPts - second.pts < keepTicks) break;
            drop(units.pollFirst());
        }
    }

    private void drop(@Nullable Unit u) {
        if (u == null) return;
        u.dropped = true;
        close(u);
        if (u.pins == 0) delete(u.file);
    }

    private static void flush(Unit u) {
        try {
            u.out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void close(Unit u) {
        try {
            u.out.close();
        } catch (IOException ignored) {
        }
    }

    // ---- saving ------------------------------------------------------------------------

    /** Holds the pieces covering the last {@code seconds}, or null when there are none yet. */
    synchronized @Nullable Span hold(int seconds) {
        if (units.isEmpty()) return null;
        Unit last = units.peekLast();
        flush(last);
        List<Unit> picked = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long want = seconds * CLOCK;
        Unit[] all = units.toArray(new Unit[0]);
        int from = all.length - 1;
        if (lastPts >= 0) {
            while (from > 0 && all[from].pts >= 0 && lastPts - all[from].pts < want) from--;
        } else {
            from = Math.max(0, all.length - seconds);
        }
        for (int i = from; i < all.length; i++) {
            Unit u = all[i];
            u.pins++;
            picked.add(u);
            sizes.add(u.size);
        }
        long first = picked.get(0).pts;
        double covered = first >= 0 && lastPts >= 0 ? (lastPts - first) / (double) CLOCK + 1 : picked.size();
        return new Span(picked, sizes, covered);
    }

    /** Copies a held span into {@code out}, then lets its pieces go. Off the render thread. */
    void write(Span span, Path out) throws IOException {
        try (FileChannel dst = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int i = 0; i < span.units.size(); i++) {
                try (FileChannel src = FileChannel.open(span.units.get(i).file, StandardOpenOption.READ)) {
                    long size = span.sizes.get(i), at = 0;
                    while (at < size) {
                        long n = src.transferTo(at, size - at, dst);
                        if (n <= 0) break;
                        at += n;
                    }
                }
            }
        } finally {
            release(span);
        }
    }

    synchronized void release(Span span) {
        for (Unit u : span.units) {
            u.pins--;
            if (u.pins == 0 && u.dropped) delete(u.file);
        }
    }

    // ---- recording ---------------------------------------------------------------------

    /** Starts copying into {@code out}, from the start of the current piece. */
    synchronized void startRecording(Path out) throws IOException {
        stopRecording();
        OutputStream r = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20);
        recordingSize = 0;
        Unit u = units.peekLast();
        if (u != null) {
            flush(u);
            try (InputStream in = Files.newInputStream(u.file)) {
                long n = in.transferTo(r);
                recordingSize += n - n % PACKET;
            }
        } else if (havePat && havePmt) {
            r.write(pat);
            r.write(pmt);
        }
        recording = r;
    }

    /** @return bytes written to the recording, 0 when none was running */
    synchronized long stopRecording() {
        OutputStream r = recording;
        recording = null;
        if (r == null) return 0;
        try {
            r.close();
        } catch (IOException e) {
            LOG.warn("Couldn't finish the recording file", e);
        }
        return recordingSize;
    }

    synchronized boolean recordingActive() {
        return recording != null;
    }

    synchronized long recordingBytes() {
        return recordingSize;
    }

    // ---- ending ------------------------------------------------------------------------

    /** Closes every piece and deletes the ones nothing holds. */
    synchronized void close() {
        stopRecording();
        while (!units.isEmpty()) drop(units.pollFirst());
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    static void clear(Path dir) {
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".ts")).forEach(TsBuffer::delete);
        } catch (IOException ignored) {
        }
    }
}
