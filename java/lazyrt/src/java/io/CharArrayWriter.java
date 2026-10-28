package java.io;

/** A writer collecting chars in a growing array. */
public class CharArrayWriter extends Writer {
    protected char[] buf;
    protected int count;

    public CharArrayWriter() {
        this(32);
    }

    public CharArrayWriter(int initialSize) {
        if (initialSize < 0) {
            throw new IllegalArgumentException("Negative initial size: " + initialSize);
        }
        buf = new char[initialSize];
    }

    private void room(int more) {
        int needed = count + more;
        if (needed < 0) {
            throw new OutOfMemoryError();
        }
        if (needed > buf.length) {
            char[] bigger = new char[Math.max(needed, buf.length * 2)];
            System.arraycopy(buf, 0, bigger, 0, count);
            buf = bigger;
        }
    }

    public void write(int c) {
        synchronized (lock) {
            room(1);
            buf[count++] = (char) c;
        }
    }

    public void write(char[] c, int off, int len) {
        if (off < 0 || len < 0 || len > c.length - off) {
            throw new IndexOutOfBoundsException();
        }
        synchronized (lock) {
            room(len);
            System.arraycopy(c, off, buf, count, len);
            count += len;
        }
    }

    public void write(String str, int off, int len) {
        synchronized (lock) {
            room(Math.max(len, 0));
            str.getChars(off, off + len, buf, count);
            count += len;
        }
    }

    public void writeTo(Writer out) throws IOException {
        synchronized (lock) {
            out.write(buf, 0, count);
        }
    }

    public CharArrayWriter append(CharSequence csq) {
        String s = String.valueOf(csq);
        write(s, 0, s.length());
        return this;
    }

    public CharArrayWriter append(CharSequence csq, int start, int end) {
        String s = (csq == null ? "null" : csq).subSequence(start, end).toString();
        write(s, 0, s.length());
        return this;
    }

    public CharArrayWriter append(char c) {
        write(c);
        return this;
    }

    public void reset() {
        count = 0;
    }

    public char[] toCharArray() {
        synchronized (lock) {
            char[] copy = new char[count];
            System.arraycopy(buf, 0, copy, 0, count);
            return copy;
        }
    }

    public int size() {
        return count;
    }

    public String toString() {
        synchronized (lock) {
            return new String(buf, 0, count);
        }
    }

    public void flush() {
    }

    public void close() {
    }
}
