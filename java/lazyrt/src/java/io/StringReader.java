package java.io;

public class StringReader extends Reader {
    private String str;
    private int length;
    private int next;
    private int mark;

    public StringReader(String s) {
        this.str = s;
        this.length = s.length();
    }

    private void ensureOpen() throws IOException {
        if (str == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        ensureOpen();
        return next >= length ? -1 : str.charAt(next++);
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || len > cbuf.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (next >= length) {
            return -1;
        }
        int n = Math.min(length - next, len);
        str.getChars(next, next + n, cbuf, off);
        next += n;
        return n;
    }

    public long skip(long ns) throws IOException {
        ensureOpen();
        if (next >= length) {
            return 0;
        }
        long n = Math.min(length - next, ns);
        n = Math.max(-next, n);
        next += (int) n;
        return n;
    }

    public boolean ready() throws IOException {
        ensureOpen();
        return true;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if (readAheadLimit < 0) {
            throw new IllegalArgumentException("Read-ahead limit < 0");
        }
        ensureOpen();
        mark = next;
    }

    public void reset() throws IOException {
        ensureOpen();
        next = mark;
    }

    public void close() {
        str = null;
    }
}
