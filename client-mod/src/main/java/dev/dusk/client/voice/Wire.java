package dev.dusk.client.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bytes in Minecraft's buffer layout (big-endian numbers, VarInt lengths,
 * UUIDs as two longs), without a Netty buffer, for voice packets that are
 * built and read off the game thread.
 */
final class Wire {
    private Wire() {}

    static final class Out {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        Out b(int v) { out.write(v); return this; }

        Out bool(boolean v) { return b(v ? 1 : 0); }

        Out i(int v) { return b(v >>> 24).b(v >>> 16).b(v >>> 8).b(v); }

        Out l(long v) { return i((int) (v >>> 32)).i((int) v); }

        Out uuid(UUID u) { return l(u.getMostSignificantBits()).l(u.getLeastSignificantBits()); }

        Out varInt(int v) {
            while ((v & ~0x7F) != 0) {
                b((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            return b(v);
        }

        Out raw(byte[] bytes) { out.write(bytes, 0, bytes.length); return this; }

        Out bytes(byte[] bytes) { return varInt(bytes.length).raw(bytes); }

        byte[] done() { return out.toByteArray(); }
    }

    static final class In {
        private final ByteBuffer buf;

        In(byte[] data) { this(data, 0, data.length); }

        In(byte[] data, int off, int len) { buf = ByteBuffer.wrap(data, off, len); }

        int b() { return buf.get(); }

        boolean bool() { return buf.get() != 0; }

        int i() { return buf.getInt(); }

        long l() { return buf.getLong(); }

        float f() { return buf.getFloat(); }

        double d() { return buf.getDouble(); }

        UUID uuid() { return new UUID(buf.getLong(), buf.getLong()); }

        int varInt() {
            int v = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = buf.get();
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("VarInt too long");
        }

        byte[] bytes(int max) {
            int n = varInt();
            if (n < 0 || n > max) throw new IllegalArgumentException("array of " + n);
            return raw(n);
        }

        byte[] raw(int n) {
            byte[] out = new byte[n];
            buf.get(out);
            return out;
        }

        String utf(int maxChars) {
            int n = varInt();
            if (n < 0 || n > maxChars * 3) throw new IllegalArgumentException("string of " + n);
            return new String(raw(n), StandardCharsets.UTF_8);
        }

        byte[] rest() { return raw(buf.remaining()); }

        int remaining() { return buf.remaining(); }
    }
}
