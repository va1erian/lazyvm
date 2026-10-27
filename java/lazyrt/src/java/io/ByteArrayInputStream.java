package java.io;

/** An input stream reading from a byte array, with mark and reset. */
public class ByteArrayInputStream extends InputStream {
    protected byte[] buf;
    protected int pos;
    protected int mark;
    protected int count;

    public ByteArrayInputStream(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public ByteArrayInputStream(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.pos = offset;
        this.mark = offset;
        this.count = Math.min(offset + length, buf.length);
    }

    public synchronized int read() {
        return pos < count ? (buf[pos++] & 0xff) : -1;
    }

    public synchronized int read(byte[] b, int off, int len) {
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (pos >= count) {
            return -1;
        }
        int n = Math.min(len, count - pos);
        if (n <= 0) {
            return 0;
        }
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    public synchronized long skip(long n) {
        long k = Math.min(n, (long) (count - pos));
        if (k < 0) {
            k = 0;
        }
        pos += (int) k;
        return k;
    }

    public synchronized int available() {
        return count - pos;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) {
        mark = pos;
    }

    public synchronized void reset() {
        pos = mark;
    }

    public void close() throws IOException {
    }
}
