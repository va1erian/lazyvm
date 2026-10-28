package java.io;

public abstract class Reader implements Readable, Closeable {
    protected Object lock;

    protected Reader() {
        this.lock = this;
    }

    protected Reader(Object lock) {
        if (lock == null) {
            throw new NullPointerException();
        }
        this.lock = lock;
    }

    // Spec S4: one read of t.remaining() chars into a new array.
    public int read(java.nio.CharBuffer target) throws IOException {
        char[] tmp = new char[target.remaining()];
        int n = read(tmp, 0, tmp.length);
        if (n > 0) {
            target.put(tmp, 0, n);
        }
        return n;
    }

    public int read() throws IOException {
        char[] one = new char[1];
        return read(one, 0, 1) == -1 ? -1 : one[0];
    }

    public int read(char[] cbuf) throws IOException {
        return read(cbuf, 0, cbuf.length);
    }

    public abstract int read(char[] cbuf, int off, int len) throws IOException;

    public long skip(long n) throws IOException {
        if (n < 0L) {
            throw new IllegalArgumentException("skip value is negative");
        }
        // Spec S4: a buffer of min(n, 8192), reads of min(left, 8192).
        int chunk = (int) Math.min(n, 8192);
        char[] skipBuffer = new char[chunk];
        long remaining = n;
        while (remaining > 0) {
            int got = read(skipBuffer, 0, (int) Math.min(remaining, chunk));
            if (got == -1) {
                break;
            }
            remaining -= got;
        }
        return n - remaining;
    }

    public boolean ready() throws IOException {
        return false;
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark() not supported");
    }

    public void reset() throws IOException {
        throw new IOException("reset() not supported");
    }

    public abstract void close() throws IOException;
}
