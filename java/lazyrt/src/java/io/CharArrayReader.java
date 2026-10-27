package java.io;

/** A reader over a char array, with mark and reset (io.md S6). */
public class CharArrayReader extends Reader {
    protected char[] buf;
    protected int pos;
    protected int markedPos;
    protected int count;

    public CharArrayReader(char[] buf) {
        this(buf, 0, buf.length);
    }

    public CharArrayReader(char[] buf, int offset, int length) {
        if (offset < 0 || offset > buf.length || length < 0 || offset + length < 0) {
            throw new IllegalArgumentException();
        }
        this.buf = buf;
        this.pos = offset;
        this.markedPos = offset;
        this.count = Math.min(offset + length, buf.length);
    }

    private void ensureOpen() throws IOException {
        if (buf == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        synchronized (lock) {
            ensureOpen();
            return pos < count ? buf[pos++] : -1;
        }
    }

    public int read(char[] b, int off, int len) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            if (pos >= count) {
                return -1;
            }
            int n = Math.min(len, count - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    // Spec S6: a negative skip skips nothing.
    public long skip(long n) throws IOException {
        synchronized (lock) {
            ensureOpen();
            long k = Math.max(0, Math.min(n, (long) (count - pos)));
            pos += (int) k;
            return k;
        }
    }

    public boolean ready() throws IOException {
        synchronized (lock) {
            ensureOpen();
            return count - pos > 0;
        }
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        synchronized (lock) {
            ensureOpen();
            markedPos = pos;
        }
    }

    public void reset() throws IOException {
        synchronized (lock) {
            ensureOpen();
            pos = markedPos;
        }
    }

    public void close() {
        buf = null;
    }
}
