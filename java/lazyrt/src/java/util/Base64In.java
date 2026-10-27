package java.util;

import java.io.IOException;
import java.io.InputStream;

/** The stream Base64.Decoder.wrap returns (docs/design/util-data.md B17-B19). */
final class Base64In extends InputStream {
    private final InputStream in;
    private final boolean url;
    private final boolean mime;
    /** Decoded bytes not yet returned: a unit can give more than the caller asked for. */
    private final byte[] pending = new byte[3];
    private int pendingStart;
    private int pendingEnd;
    private boolean ended;
    private boolean closed;

    Base64In(InputStream in, boolean url, boolean mime) {
        this.in = in;
        this.url = url;
        this.mime = mime;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    /** B17: fills the request unless the data ends first. */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkOpen();
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        int done = 0;
        while (done < len) {
            if (pendingStart == pendingEnd && (ended || !nextUnit())) {
                break;
            }
            b[off + done++] = pending[pendingStart++];
        }
        return done == 0 ? -1 : done;
    }

    /** B19. */
    @Override
    public int available() throws IOException {
        checkOpen();
        return in.available();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            in.close();
        }
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    /** Reads one unit into `pending`; false at the end of the data (B17, B18). */
    private boolean nextUnit() throws IOException {
        int bits = 0;
        int p = 0;
        while (p < 4) {
            int c = in.read();
            if (c < 0) {
                ended = true;
                if (p == 1) {
                    throw new IOException("Base64 stream has one un-decoded dangling byte.");
                }
                return p > 0 && fill(bits << (6 * (4 - p)), p - 1);
            }
            if (c == '=') {
                ended = true;
                if (p < 2 || p == 2 && in.read() != '=') {
                    throw new IOException("Illegal base64 ending sequence:" + (18 - 6 * p));
                }
                return fill(bits << (6 * (4 - p)), p - 1);
            }
            int v = Base64Codec.value((byte) c, url);
            if (v < 0) {
                if (mime) {
                    continue;
                }
                throw new IOException("Illegal base64 character -1");
            }
            bits = bits << 6 | v;
            p++;
        }
        return fill(bits, 3);
    }

    private boolean fill(int bits, int count) {
        pendingStart = 0;
        pendingEnd = Base64Codec.unit(bits, count, pending, 0);
        return true;
    }
}
