package dev.dusk.client.voice;

import org.jetbrains.annotations.Nullable;

import java.util.TreeMap;
import java.util.UUID;

/**
 * One voice being heard: its packets land here from the network threads and
 * the mixer takes a frame every 20 ms. Playback starts two frames behind
 * the first packet so a little jitter doesn't stutter, a missing frame is
 * rebuilt from the next packet or guessed, and the voice goes quiet once
 * packets stop coming. Where it sits (for panning and fall-off) is set from
 * the game thread by {@link Voice#tick}.
 */
final class Speaker {
    /** Frames to hold before playing. */
    private static final int LEAD = 2;
    /** Frames of nothing before the voice counts as stopped. */
    private static final int GIVE_UP = 5;
    /** A jump this far in sequence numbers is a new stream, not loss. */
    private static final int RESYNC = 25;

    final UUID channel;
    final UUID sender;
    final Voice.Source source;

    private final Opus.Decoder decoder = new Opus.Decoder();
    private final TreeMap<Long, byte[]> queue = new TreeMap<>();
    private boolean playing;
    private long next;
    private int misses;
    private boolean ended;

    /** A fixed spot the voice comes from; null to follow the sender's player. */
    @Nullable volatile double[] at;
    /** Heard the same wherever you stand (a call, a group). */
    volatile boolean flat;
    /** How far it carries, from the server; 0 for the default. */
    volatile float distance;
    volatile boolean whisper;
    /** Left and right gain, worked out on the game thread. */
    volatile float left = 1, right = 1;
    volatile long lastHeard;

    Speaker(UUID channel, UUID sender, Voice.Source source) {
        this.channel = channel;
        this.sender = sender;
        this.source = source;
    }

    /** A packet; empty data marks the end of a stretch of talking. */
    synchronized void push(long seq, byte[] opus) {
        if (opus.length == 0) {
            ended = true;
            return;
        }
        if (playing && seq < next) {
            if (next - seq < RESYNC) return; // late
            reset(); // the sender restarted its count
        }
        if (playing && seq - next > RESYNC) reset();
        ended = false;
        queue.put(seq, opus);
        while (queue.size() > 50) queue.pollFirstEntry();
        lastHeard = System.currentTimeMillis();
    }

    private void reset() {
        queue.clear();
        playing = false;
        misses = 0;
        decoder.reset();
    }

    /** The next 20 ms, or null when there is nothing to play. Mixer thread. */
    @Nullable
    synchronized short[] frame() {
        if (!playing) {
            if (queue.isEmpty() || (queue.size() < LEAD && !ended)) return null;
            playing = true;
            next = queue.firstKey();
            misses = 0;
        }
        byte[] data = queue.remove(next);
        if (data != null) {
            next++;
            misses = 0;
            return decoder.decode(data);
        }
        if (queue.isEmpty()) {
            if (ended || ++misses > GIVE_UP) {
                reset();
                ended = false;
                return null;
            }
            next++;
            return decoder.decode(null);
        }
        // a hole: rebuild it from the packet after it when that one is next
        long first = queue.firstKey();
        short[] out = first == next + 1 ? decoder.recover(queue.get(first)) : decoder.decode(null);
        next++;
        if (first > next + 5) next = first; // too much missing to fill in
        return out;
    }

    /** Talking within the last moment, for the HUD and the Friends page. */
    boolean talking() {
        return System.currentTimeMillis() - lastHeard < 250;
    }

    /** Nothing heard for long enough to drop the decoder. */
    boolean stale() {
        return System.currentTimeMillis() - lastHeard > 30_000;
    }
}
