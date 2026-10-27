package java.util;

import java.io.IOException;
import java.io.InputStream;

/** The stream Base64.Decoder.wrap returns (docs/design/util-data.md B17-B19). */
final class Base64In extends InputStream {
    private final InputStream in;
    private final int[] table;
    private final boolean mime;
    /** Decoded bytes of the last unit that the caller had no room for yet. */
    private final byte[] pending = new byte[3];
    private int pendingStart;
    private int pendingEnd;
    /** The bytes of the last unit nextUnit() decoded, high byte first. */
    private int unitBits;
    /** B18: the characters (unitP) and bits (unitAcc) of a unit an error interrupted. */
    private int unitP;
    private int unitAcc;
    private boolean ended;
    private boolean closed;
    /** Reused by read(), which would otherwise allocate per byte. */
    private final byte[] one = new byte[1];

    Base64In(InputStream in, boolean url, boolean mime) {
        this.in = in;
        this.table = Base64Codec.table(url);
        this.mime = mime;
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    /**
     * B17: fills the request unless the data ends first; whole units go straight to b. B19: once
     * the data has ended and every decoded byte is out, the answer is -1 before the arguments are
     * looked at, as the oracle does, so even an empty or out-of-bounds request gets -1.
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkOpen();
        if (ended && pendingStart == pendingEnd) {
            return -1;
        }
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        int end = off + len;
        int d = off;
        while (d < end && pendingStart < pendingEnd) {
            b[d++] = pending[pendingStart++];
        }
        while (d < end && !ended) {
            int k = nextUnit();
            int bits = unitBits;
            if (end - d >= k) {
                d = Base64Codec.unit(bits, k, b, d);
            } else {
                pendingStart = 0;
                pendingEnd = Base64Codec.unit(bits, k, pending, 0);
                while (d < end) {
                    b[d++] = pending[pendingStart++];
                }
            }
        }
        return d == off ? -1 : d - off;
    }

    /** B19: the source's own count, as the rule states; decoded bytes held in pending are not added. */
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

    /**
     * Reads one unit from `in` and returns how many bytes it gives (0 at the end of the data),
     * leaving them in unitBits. B17: `in` is read one byte at a time and never past the unit, so
     * after padding nothing more of it is consumed. B18: the errors. An error consumes the byte
     * that caused it (and the byte read after a '=' at p = 2) but not the unit begun before it:
     * that stays in unitP and unitAcc, and the next read goes on from there.
     */
    private int nextUnit() throws IOException {
        InputStream src = in;
        int[] t = table;
        int bits = unitAcc;
        int p = unitP;
        while (p < 4) {
            int c = src.read();
            if (c < 0) {
                // B19: the end of `in` finishes the stream even when it leaves a dangling byte;
                // the '=' and illegal-byte errors below do not.
                ended = true;
                keep(0, 0);
                if (p == 1) {
                    throw new IOException("Base64 stream has one un-decoded dangling byte.");
                }
                unitBits = bits << (6 * (4 - p));
                return p == 0 ? 0 : p - 1;
            }
            int v = t[c & 0xff];
            if (v >= 0) {
                bits = bits << 6 | v;
                p++;
            } else if (c == '=') {
                if (p < 2 || p == 2 && src.read() != '=') {
                    keep(p, bits);
                    throw new IOException("Illegal base64 ending sequence:" + (18 - 6 * p));
                }
                ended = true;
                keep(0, 0);
                unitBits = bits << (6 * (4 - p));
                return p - 1;
            } else if (!mime) {
                keep(p, bits);
                throw new IOException("Illegal base64 character -1");
            }
        }
        keep(0, 0);
        unitBits = bits;
        return 3;
    }

    /** The unit begun so far, kept across a thrown error (B18). */
    private void keep(int p, int bits) {
        unitP = p;
        unitAcc = bits;
    }
}
