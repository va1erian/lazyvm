package java.io;

/** A buffering input stream with mark and reset (io.md B1-B5). */
public class BufferedInputStream extends FilterInputStream {
    protected volatile byte[] buf;
    protected int count;
    protected int pos;
    protected int markpos = -1;
    protected int marklimit;

    public BufferedInputStream(InputStream in) {
        this(in, 8192);
    }

    // Spec B1: 8192 by default; a size <= 0 is refused.
    public BufferedInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
    }

    // Spec B5: after close, every call is "Stream closed".
    private InputStream source() throws IOException {
        InputStream s = in;
        if (s == null || buf == null) {
            throw new IOException("Stream closed");
        }
        return s;
    }

    /**
     * Spec B4: reads more once the buffer is used up, keeping the bytes from
     * the mark while the mark is valid, and asks for the free space (B2).
     */
    private void refill() throws IOException {
        InputStream s = source();
        byte[] b = buf;
        if (markpos < 0) {
            pos = 0;
        } else if (pos >= b.length) {
            if (markpos > 0) {
                int keep = pos - markpos;
                System.arraycopy(b, markpos, b, 0, keep);
                pos = keep;
                markpos = 0;
            } else if (b.length < marklimit) {
                byte[] bigger = new byte[Math.min(2 * b.length, marklimit)];
                System.arraycopy(b, 0, bigger, 0, pos);
                buf = bigger;
                b = bigger;
            } else {
                markpos = -1;
                pos = 0;
            }
        }
        count = pos;
        int n = s.read(b, pos, b.length - pos);
        if (n > 0) {
            count = pos + n;
        }
    }

    public synchronized int read() throws IOException {
        source();
        if (pos >= count) {
            refill();
            if (pos >= count) {
                return -1;
            }
        }
        return buf[pos++] & 0xff;
    }

    /** One step of read(b, off, len) (Spec B2). */
    private int step(byte[] b, int off, int len) throws IOException {
        int have = count - pos;
        if (have <= 0) {
            if (len >= buf.length && markpos < 0) {
                return source().read(b, off, len);
            }
            refill();
            have = count - pos;
            if (have <= 0) {
                return -1;
            }
        }
        int n = Math.min(have, len);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    public synchronized int read(byte[] b, int off, int len) throws IOException {
        source();
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        int total = 0;
        while (true) {
            int n = step(b, off + total, len - total);
            if (n <= 0) {
                return total == 0 ? n : total;
            }
            total += n;
            if (total >= len) {
                return total;
            }
            InputStream s = in;
            if (s != null && s.available() <= 0) {
                return total;
            }
        }
    }

    // Spec B3: n <= 0 is 0; an empty unmarked buffer passes the skip on.
    public synchronized long skip(long n) throws IOException {
        InputStream s = source();
        if (n <= 0) {
            return 0;
        }
        long have = count - pos;
        if (have <= 0) {
            if (markpos < 0) {
                return s.skip(n);
            }
            refill();
            have = count - pos;
            if (have <= 0) {
                return 0;
            }
        }
        long k = Math.min(have, n);
        pos += (int) k;
        return k;
    }

    // Spec B3: what is buffered plus what the stream has, saturating.
    public synchronized int available() throws IOException {
        int inner = source().available();
        int have = count - pos;
        return have > Integer.MAX_VALUE - inner ? Integer.MAX_VALUE : have + inner;
    }

    public synchronized void mark(int readlimit) {
        marklimit = readlimit;
        markpos = pos;
    }

    public synchronized void reset() throws IOException {
        source();
        if (markpos < 0) {
            throw new IOException("Resetting to invalid mark");
        }
        pos = markpos;
    }

    public boolean markSupported() {
        return true;
    }

    public void close() throws IOException {
        InputStream s = in;
        if (buf == null) {
            return;
        }
        buf = null;
        in = null;
        if (s != null) {
            s.close();
        }
    }
}
