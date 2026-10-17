package dev.dusk.client.voice;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Opus through Concentus (a pure Java port, BSD, see NOTICE), so voice needs
 * no native library on any platform. Everything is 48 kHz mono in 20 ms
 * frames, which is what Simple Voice Chat servers relay too.
 */
final class Opus {
    static final int RATE = 48000;
    static final int FRAME = 960;
    /** The largest packet Opus makes for one frame. */
    static final int MAX_PACKET = 1275;

    private Opus() {}

    static final class Encoder {
        private final OpusEncoder enc;
        private final byte[] out = new byte[MAX_PACKET];

        Encoder() {
            try {
                enc = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
            enc.setBitrate(32000);
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            // a lost packet can be rebuilt from the next one's spare copy
            enc.setUseInbandFEC(true);
            enc.setPacketLossPercent(5);
        }

        byte[] encode(short[] pcm) {
            try {
                int n = enc.encode(pcm, 0, FRAME, out, 0, out.length);
                return java.util.Arrays.copyOf(out, n);
            } catch (OpusException e) {
                return new byte[0];
            }
        }

        void reset() { enc.resetState(); }
    }

    static final class Decoder {
        private final OpusDecoder dec;

        Decoder() {
            try {
                dec = new OpusDecoder(RATE, 1);
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
        }

        /** One frame of audio, or a guess at the missing one when {@code data} is null. */
        short[] decode(byte[] data) {
            short[] pcm = new short[FRAME];
            try {
                if (data == null) dec.decode(null, 0, 0, pcm, 0, FRAME, false);
                else dec.decode(data, 0, data.length, pcm, 0, FRAME, false);
            } catch (OpusException | RuntimeException e) {
                java.util.Arrays.fill(pcm, (short) 0);
            }
            return pcm;
        }

        /** Rebuilds a lost frame from the spare copy carried in {@code next}. */
        short[] recover(byte[] next) {
            short[] pcm = new short[FRAME];
            try {
                dec.decode(next, 0, next.length, pcm, 0, FRAME, true);
            } catch (OpusException | RuntimeException e) {
                return decode(null);
            }
            return pcm;
        }

        void reset() { dec.resetState(); }
    }
}
