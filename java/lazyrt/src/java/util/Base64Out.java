package java.util;

import java.io.IOException;
import java.io.OutputStream;

/** The stream Base64.Encoder.wrap returns (docs/design/util-data.md B14-B16). */
final class Base64Out extends OutputStream {
    /** Groups encoded per write to the wrapped stream, so long arrays go out in big pieces. */
    private static final int CHUNK_GROUPS = 1024;

    private final OutputStream out;
    private final Base64.Encoder encoder;
    /** B14: the 1 or 2 bytes of an incomplete group wait here for more data or close(). */
    private final byte[] held = new byte[3];
    /** Reused output buffer: 4 characters and at most one separator per group. */
    private final byte[] buffer;
    /** The characters on the current output line, carried across writes (B3). */
    private final int[] column = {0};
    private int count;
    private boolean started;
    private boolean closed;

    Base64Out(OutputStream out, Base64.Encoder encoder) {
        this.out = out;
        this.encoder = encoder;
        byte[] sep = encoder.separator();
        this.buffer = new byte[CHUNK_GROUPS * (4 + (sep == null ? 0 : sep.length))];
    }

    /** B14: the low 8 bits; a completed group goes out at once. */
    @Override
    public void write(int b) throws IOException {
        checkOpen();
        held[count++] = (byte) b;
        if (count == 3) {
            emitGroups(held, 0, 1);
            count = 0;
        }
    }

    /** B15 first, then B16's bounds; whole groups are encoded straight from the caller's array. */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkOpen();
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new ArrayIndexOutOfBoundsException();
        }
        while (count > 0 && len > 0) {
            held[count++] = b[off++];
            len--;
            if (count == 3) {
                emitGroups(held, 0, 1);
                count = 0;
            }
        }
        for (int groups = len / 3; groups > 0;) {
            int n = Math.min(groups, CHUNK_GROUPS);
            emitGroups(b, off, n);
            off += 3 * n;
            len -= 3 * n;
            groups -= n;
        }
        while (len-- > 0) {
            held[count++] = b[off++];
        }
    }

    /** B14: a held-back group stays held. */
    @Override
    public void flush() throws IOException {
        if (!closed) {
            out.flush();
        }
    }

    /** B14, B15: the tail group, padded as the encoder says, then the wrapped stream closes. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (count > 0) {
            writePrefix();
            int n = Base64Codec.encodeTail(held, 0, count, buffer, 0, encoder, column);
            out.write(buffer, 0, n);
        }
        out.close();
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    private void emitGroups(byte[] src, int off, int groups) throws IOException {
        writePrefix();
        int n = Base64Codec.encodeGroups(src, off, groups, buffer, 0, encoder, column);
        out.write(buffer, 0, n);
    }

    /** B14a: a line length of 1 to 3 puts the separator before the stream's first character. */
    private void writePrefix() throws IOException {
        byte[] prefix = encoder.streamPrefix();
        if (!started && prefix != null) {
            out.write(prefix, 0, prefix.length);
        }
        started = true;
    }
}
