package dev.dusk.client.voice;

import dev.dusk.client.DuskClient;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * The sound card side of voice chat, through Java Sound: one thread reads
 * the microphone in 20 ms frames and one mixes every voice into a single
 * stereo line, paced by that line. Both run only while some voice
 * connection wants them.
 */
final class AudioEngine {
    static final String DEFAULT = "Default";
    private static final AudioFormat MONO = new AudioFormat(Opus.RATE, 16, 1, true, false);
    private static final AudioFormat STEREO = new AudioFormat(Opus.RATE, 16, 2, true, false);

    interface MicSink {
        /** One frame from the microphone, gain applied, with its peak in dBFS. Mic thread. */
        void frame(short[] pcm, float peakDb);
    }

    private AudioEngine() {}

    /** Microphones and speakers Java Sound can open at 48 kHz, by name, with "Default" first. */
    static List<String> devices(boolean input) {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        Class<? extends Line> kind = input ? TargetDataLine.class : SourceDataLine.class;
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(info);
                if (m.isLineSupported(new DataLine.Info(kind, input ? MONO : STEREO))
                        || m.isLineSupported(new DataLine.Info(kind, STEREO))) {
                    if (!out.contains(info.getName())) out.add(info.getName());
                }
            }
        } catch (RuntimeException ignored) {
            // no sound system at all
        }
        return out;
    }

    private static <L extends Line> L open(Class<L> kind, AudioFormat format, String device) throws LineUnavailableException {
        DataLine.Info want = new DataLine.Info(kind, format);
        if (!DEFAULT.equals(device)) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (!info.getName().equals(device)) continue;
                Mixer m = AudioSystem.getMixer(info);
                if (m.isLineSupported(want)) return kind.cast(m.getLine(want));
            }
        }
        return kind.cast(AudioSystem.getLine(want));
    }

    // ---- microphone -----------------------------------------------------------

    static final class Mic implements Runnable {
        private final String device;
        private final Supplier<Float> gain;
        private final MicSink sink;
        private volatile boolean running = true;
        private volatile String error;
        private Thread thread;

        Mic(String device, Supplier<Float> gain, MicSink sink) {
            this.device = device;
            this.gain = gain;
            this.sink = sink;
        }

        void start() {
            thread = new Thread(this, "Dusk voice mic");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() { running = false; }

        @Nullable String error() { return error; }

        @Override
        public void run() {
            TargetDataLine line;
            int channels;
            try {
                try {
                    line = open(TargetDataLine.class, MONO, device);
                    line.open(MONO, Opus.FRAME * 2 * 6);
                    channels = 1;
                } catch (LineUnavailableException | IllegalArgumentException mono) {
                    line = open(TargetDataLine.class, STEREO, device);
                    line.open(STEREO, Opus.FRAME * 4 * 6);
                    channels = 2;
                }
            } catch (Exception e) {
                error = "Can't open the microphone: " + e.getMessage();
                DuskClient.LOGGER.warn("[voice] {}", error);
                return;
            }
            line.start();
            byte[] raw = new byte[Opus.FRAME * 2 * channels];
            try {
                while (running) {
                    int got = 0;
                    while (got < raw.length && running) {
                        int n = line.read(raw, got, raw.length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (!running) break;
                    if (got < raw.length) continue;
                    short[] pcm = new short[Opus.FRAME];
                    float g = gain.get();
                    int peak = 0;
                    for (int i = 0; i < Opus.FRAME; i++) {
                        int s;
                        if (channels == 1) s = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                        else {
                            int a = (short) ((raw[i * 4] & 0xFF) | (raw[i * 4 + 1] << 8));
                            int b = (short) ((raw[i * 4 + 2] & 0xFF) | (raw[i * 4 + 3] << 8));
                            s = (a + b) / 2;
                        }
                        s = Math.max(-32768, Math.min(32767, Math.round(s * g)));
                        pcm[i] = (short) s;
                        peak = Math.max(peak, Math.abs(s));
                    }
                    float db = peak == 0 ? -127 : (float) (20 * Math.log10(peak / 32768.0));
                    sink.frame(pcm, db);
                }
            } finally {
                line.stop();
                line.close();
            }
        }
    }

    // ---- speakers ---------------------------------------------------------------

    static final class Output implements Runnable {
        private final String device;
        private final Supplier<Collection<Speaker>> speakers;
        private final Supplier<Float> volume;
        private volatile boolean running = true;
        private volatile String error;

        Output(String device, Supplier<Collection<Speaker>> speakers, Supplier<Float> volume) {
            this.device = device;
            this.speakers = speakers;
            this.volume = volume;
        }

        void start() {
            Thread t = new Thread(this, "Dusk voice output");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY - 1);
            t.start();
        }

        void stop() { running = false; }

        @Nullable String error() { return error; }

        @Override
        public void run() {
            SourceDataLine line;
            try {
                line = open(SourceDataLine.class, STEREO, device);
                line.open(STEREO, Opus.FRAME * 4 * 4);
            } catch (Exception e) {
                error = "Can't open the speakers: " + e.getMessage();
                DuskClient.LOGGER.warn("[voice] {}", error);
                return;
            }
            line.start();
            int[] mix = new int[Opus.FRAME * 2];
            byte[] out = new byte[Opus.FRAME * 4];
            try {
                while (running) {
                    java.util.Arrays.fill(mix, 0);
                    float master = volume.get();
                    for (Speaker s : speakers.get()) {
                        short[] f = s.frame();
                        if (f == null) continue;
                        float l = s.left * master, r = s.right * master;
                        if (l <= 0 && r <= 0) continue;
                        for (int i = 0; i < Opus.FRAME; i++) {
                            mix[i * 2] += Math.round(f[i] * l);
                            mix[i * 2 + 1] += Math.round(f[i] * r);
                        }
                    }
                    for (int i = 0; i < mix.length; i++) {
                        int v = Math.max(-32768, Math.min(32767, mix[i]));
                        out[i * 2] = (byte) v;
                        out[i * 2 + 1] = (byte) (v >> 8);
                    }
                    line.write(out, 0, out.length); // blocks, which keeps time
                }
            } finally {
                line.stop();
                line.close();
            }
        }
    }
}
