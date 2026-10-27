package java.util;

import java.io.IOException;
import java.io.OutputStream;

/** The stream Base64.Encoder.wrap returns (docs/design/util-data.md B14-B16). */
final class Base64Out extends OutputStream {
    private final OutputStream out;
    private final Base64.Encoder encoder;
    private final byte[] held = new byte[3];
    private final byte[] chars;
    private int count;
    private int column;
    private boolean started;
    private boolean closed;

    Base64Out(OutputStream out, Base64.Encoder encoder) {
        this.out = out;
        this.encoder = encoder;
        byte[] sep = encoder.separator();
        this.chars = new byte[4 + (sep == null ? 0 : sep.length)];
    }

    @Override
    public void write(int b) throws IOException {
        checkOpen();
        hold((byte) b);
    }

    /** B16: bad bounds are an ArrayIndexOutOfBoundsException without a message. */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkOpen();
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new ArrayIndexOutOfBoundsException();
        }
        for (int i = 0; i < len; i++) {
            hold(b[off + i]);
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
            emit(count);
        }
        out.close();
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    /** B14: each completed group goes out at once. */
    private void hold(byte b) throws IOException {
        held[count++] = b;
        if (count == 3) {
            emit(3);
        }
    }

    private void emit(int n) throws IOException {
        byte[] prefix = encoder.streamPrefix();
        if (!started && prefix != null) {
            out.write(prefix, 0, prefix.length); // B14a
        }
        started = true;
        Base64Codec.Sink sink = new Base64Codec.Sink(encoder, chars, column);
        sink.group(held[0], n > 1 ? held[1] : 0, n > 2 ? held[2] : 0, n);
        column = sink.column();
        count = 0;
        out.write(chars, 0, sink.length());
    }
}
