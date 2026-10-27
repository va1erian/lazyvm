package java.io;

/** A buffering writer (io.md B8). */
public class BufferedWriter extends Writer {
    private Writer out;
    private final char[] buf;
    private int count;

    public BufferedWriter(Writer out) {
        this(out, 8192);
    }

    // Spec B1: 8192 by default; a size <= 0 is refused.
    public BufferedWriter(Writer out, int sz) {
        super(out);
        if (sz <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.out = out;
        buf = new char[sz];
    }

    private Writer sink() throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
        return out;
    }

    private void drain() throws IOException {
        if (count > 0) {
            sink().write(buf, 0, count);
            count = 0;
        }
    }

    public void write(int c) throws IOException {
        synchronized (lock) {
            sink();
            if (count >= buf.length) {
                drain();
            }
            buf[count++] = (char) c;
        }
    }

    // Spec B8: at least a buffer's worth goes straight through, after what is buffered.
    public void write(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            sink();
            if (off < 0 || len < 0 || len > cbuf.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len >= buf.length) {
                drain();
                out.write(cbuf, off, len);
                return;
            }
            int end = off + len;
            while (off < end) {
                int d = Math.min(buf.length - count, end - off);
                System.arraycopy(cbuf, off, buf, count, d);
                count += d;
                off += d;
                if (count >= buf.length) {
                    drain();
                }
            }
        }
    }

    // Spec B8: a string fills the buffer and goes out a buffer at a time.
    public void write(String s, int off, int len) throws IOException {
        synchronized (lock) {
            sink();
            int end = off + len;
            while (off < end) {
                int d = Math.min(buf.length - count, end - off);
                s.getChars(off, off + d, buf, count);
                count += d;
                off += d;
                if (count >= buf.length) {
                    drain();
                }
            }
        }
    }

    public void newLine() throws IOException {
        write(System.lineSeparator());
    }

    public void flush() throws IOException {
        synchronized (lock) {
            drain();
            sink().flush();
        }
    }

    // Spec B8: what is buffered goes out, then close, with no flush; only once.
    public void close() throws IOException {
        synchronized (lock) {
            if (out == null) {
                return;
            }
            try {
                drain();
            } finally {
                Writer w = out;
                out = null;
                w.close();
            }
        }
    }
}
