package dev.dusk.client.cosmetics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Java 8 has no {@code InputStream.readAllBytes}. */
final class Bytes {
    private Bytes() {}

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
