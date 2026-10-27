package java.io;

/** Reads what DataOutputStream writes (io.md D3, D4). */
public class DataInputStream extends FilterInputStream implements DataInput {
    private final byte[] scratch = new byte[8];

    public DataInputStream(InputStream in) {
        super(in);
    }

    public final int read(byte[] b) throws IOException {
        return in.read(b, 0, b.length);
    }

    public final int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    public final void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    // Spec D4: gathers across short reads; the end before `len` bytes is EOFException.
    public final void readFully(byte[] b, int off, int len) throws IOException {
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        int n = 0;
        while (n < len) {
            int k = in.read(b, off + n, len - n);
            if (k < 0) {
                throw new EOFException();
            }
            n += k;
        }
    }

    // Spec D4: as many as the stream skips; a negative count is 0.
    public final int skipBytes(int n) throws IOException {
        int total = 0;
        while (total < n) {
            long k = in.skip(n - total);
            if (k <= 0) {
                break;
            }
            total += (int) k;
        }
        return total;
    }

    private int next() throws IOException {
        int c = in.read();
        if (c < 0) {
            throw new EOFException();
        }
        return c;
    }

    private long readBits(int n) throws IOException {
        readFully(scratch, 0, n);
        return DataFormat.bits(scratch, n);
    }

    public final boolean readBoolean() throws IOException {
        return next() != 0;
    }

    public final byte readByte() throws IOException {
        return (byte) next();
    }

    public final int readUnsignedByte() throws IOException {
        return next();
    }

    public final short readShort() throws IOException {
        return (short) readBits(2);
    }

    public final int readUnsignedShort() throws IOException {
        return (int) readBits(2);
    }

    public final char readChar() throws IOException {
        return (char) readBits(2);
    }

    public final int readInt() throws IOException {
        return (int) readBits(4);
    }

    public final long readLong() throws IOException {
        return readBits(8);
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    /**
     * Spec D4: lines of Latin-1 bytes. A byte read after a '\r' that is not
     * '\n' is given back by putting a one-byte pushback stream in front of `in`.
     */
    @Deprecated
    public final String readLine() throws IOException {
        return DataFormat.readLine(new DataFormat.LineSource() {
            private int last;

            public int read() throws IOException {
                last = in.read();
                return last;
            }

            public void unread() {
                in = new Pushback(in, last);
            }
        });
    }

    public final String readUTF() throws IOException {
        return readUTF(this);
    }

    public static final String readUTF(DataInput in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b, 0, len);
        return DataFormat.fromModifiedUtf8(b, len);
    }

    /** One byte given back in front of a stream. */
    private static final class Pushback extends FilterInputStream {
        private int pending;

        Pushback(InputStream in, int b) {
            super(in);
            pending = b;
        }

        public int read() throws IOException {
            if (pending >= 0) {
                int b = pending;
                pending = -1;
                return b;
            }
            return in.read();
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (pending < 0 || len == 0) {
                return in.read(b, off, len);
            }
            if ((off | len) < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            b[off] = (byte) pending;
            pending = -1;
            return 1;
        }

        public long skip(long n) throws IOException {
            if (pending < 0 || n <= 0) {
                return in.skip(n);
            }
            pending = -1;
            return 1;
        }

        public int available() throws IOException {
            return (pending >= 0 ? 1 : 0) + in.available();
        }

        public boolean markSupported() {
            return false;
        }
    }
}
