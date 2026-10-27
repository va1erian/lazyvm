package java.io;

public abstract class Writer implements Appendable, Closeable, Flushable {
    protected Object lock;
    /** Spec S5: write(int) and short strings go through this one reused array. */
    private char[] small;

    protected Writer() {
        this.lock = this;
    }

    protected Writer(Object lock) {
        if (lock == null) {
            throw new NullPointerException();
        }
        this.lock = lock;
    }

    private char[] small() {
        if (small == null) {
            small = new char[1024];
        }
        return small;
    }

    public void write(int c) throws IOException {
        synchronized (lock) {
            char[] buf = small();
            buf[0] = (char) c;
            write(buf, 0, 1);
        }
    }

    public void write(char[] cbuf) throws IOException {
        write(cbuf, 0, cbuf.length);
    }

    public abstract void write(char[] cbuf, int off, int len) throws IOException;

    public void write(String str) throws IOException {
        write(str, 0, str.length());
    }

    // Spec S5: up to 1024 chars through the reused array, longer ones in an array of exactly len.
    public void write(String str, int off, int len) throws IOException {
        synchronized (lock) {
            // Bounds first: a negative len is an index error, not an array size.
            char[] buf = len <= 1024 ? small() : new char[len];
            str.getChars(off, off + len, buf, 0);
            write(buf, 0, len);
        }
    }

    public Writer append(CharSequence csq) throws IOException {
        write(String.valueOf(csq));
        return this;
    }

    public Writer append(CharSequence csq, int start, int end) throws IOException {
        CharSequence cs = csq == null ? "null" : csq;
        write(cs.subSequence(start, end).toString());
        return this;
    }

    public Writer append(char c) throws IOException {
        write(c);
        return this;
    }

    public abstract void flush() throws IOException;

    public abstract void close() throws IOException;
}
