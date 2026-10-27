package java.io;

/**
 * A buffering reader with lines and marks (io.md B7, L1-L3). After a line
 * that ended in '\r', a following '\n' is still unread; `swallowLF` says
 * the next read, skip or readLine drops it.
 */
public class BufferedReader extends Reader {
    private static final int UNMARKED = -1;
    private static final int INVALID = -2;

    private Reader in;
    private char[] buf;
    private int pos;
    private int end;
    private int markAt = UNMARKED;
    private int markLimit;
    private boolean swallowLF;
    private boolean markSwallowLF;

    // Spec B1: 8192 by default; a size <= 0 is refused.
    public BufferedReader(Reader in, int sz) {
        super(in);
        if (sz <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.in = in;
        buf = new char[sz];
    }

    public BufferedReader(Reader in) {
        this(in, 8192);
    }

    private Reader source() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
        return in;
    }

    /**
     * Reads more into the free space. Spec L3: a mark is kept (moved to the
     * front, the buffer grown up to its limit) unless `markLimit` chars have
     * been read since it, which invalidates it.
     */
    private void refill() throws IOException {
        int dst = 0;
        if (markAt >= 0) {
            int kept = pos - markAt;
            if (kept >= markLimit) {
                markAt = INVALID;
                markLimit = 0;
            } else {
                char[] target = markLimit <= buf.length ? buf : new char[markLimit];
                System.arraycopy(buf, markAt, target, 0, kept);
                buf = target;
                markAt = 0;
                dst = kept;
            }
        }
        pos = dst;
        end = dst;
        int n;
        do {
            n = source().read(buf, dst, buf.length - dst);
        } while (n == 0);
        if (n > 0) {
            end = dst + n;
        }
    }

    /** Spec L2: drops a '\n' owed to an earlier '\r' line end; false at the end of input. */
    private boolean available() throws IOException {
        if (pos >= end) {
            refill();
            if (pos >= end) {
                return false;
            }
        }
        if (swallowLF) {
            swallowLF = false;
            if (buf[pos] == '\n') {
                pos++;
                return available();
            }
        }
        return true;
    }

    public int read() throws IOException {
        synchronized (lock) {
            source();
            return available() ? buf[pos++] : -1;
        }
    }

    /** One step of read(c, off, len) (Spec B7). */
    private int step(char[] c, int off, int len) throws IOException {
        if (pos >= end && len >= buf.length && markAt < 0 && !swallowLF) {
            return in.read(c, off, len);
        }
        if (!available()) {
            return -1;
        }
        int n = Math.min(len, end - pos);
        System.arraycopy(buf, pos, c, off, n);
        pos += n;
        return n;
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            source();
            if (off < 0 || len < 0 || len > cbuf.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            int n = step(cbuf, off, len);
            if (n <= 0) {
                return n;
            }
            while (n < len && in.ready()) {
                int more = step(cbuf, off + n, len - n);
                if (more <= 0) {
                    break;
                }
                n += more;
            }
            return n;
        }
    }

    // Spec L1: '\n', '\r' and "\r\n" end a line, even across refills; null at the end.
    public String readLine() throws IOException {
        synchronized (lock) {
            source();
            StringBuilder sb = null;
            while (available()) {
                int start = pos;
                while (pos < end && buf[pos] != '\n' && buf[pos] != '\r') {
                    pos++;
                }
                if (pos < end) {
                    String line = sb == null ? new String(buf, start, pos - start)
                            : sb.append(buf, start, pos - start).toString();
                    swallowLF = buf[pos++] == '\r';
                    return line;
                }
                if (sb == null) {
                    sb = new StringBuilder(80);
                }
                sb.append(buf, start, pos - start);
            }
            return sb == null ? null : sb.toString();
        }
    }

    public long skip(long n) throws IOException {
        if (n < 0L) {
            throw new IllegalArgumentException("skip value is negative");
        }
        synchronized (lock) {
            source();
            long left = n;
            while (left > 0 && available()) {
                int d = (int) Math.min(left, end - pos);
                pos += d;
                left -= d;
            }
            return n - left;
        }
    }

    // Spec B7, L2: an empty buffer asks the reader; a pending '\n' is dropped first.
    public boolean ready() throws IOException {
        synchronized (lock) {
            source();
            if (swallowLF) {
                if (pos >= end && in.ready()) {
                    refill();
                }
                if (pos < end) {
                    swallowLF = false;
                    if (buf[pos] == '\n') {
                        pos++;
                    }
                }
            }
            return pos < end || in.ready();
        }
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if (readAheadLimit < 0) {
            throw new IllegalArgumentException("Read-ahead limit < 0");
        }
        synchronized (lock) {
            source();
            markLimit = readAheadLimit;
            markAt = pos;
            markSwallowLF = swallowLF;
        }
    }

    public void reset() throws IOException {
        synchronized (lock) {
            source();
            if (markAt < 0) {
                throw new IOException(markAt == INVALID ? "Mark invalid" : "Stream not marked");
            }
            pos = markAt;
            swallowLF = markSwallowLF;
        }
    }

    public void close() throws IOException {
        synchronized (lock) {
            if (in == null) {
                return;
            }
            try {
                in.close();
            } finally {
                in = null;
                buf = null;
            }
        }
    }
}
