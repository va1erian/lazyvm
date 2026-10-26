package java.io;

/** An output stream collecting bytes in a growing array. */
public class ByteArrayOutputStream extends OutputStream {
    protected byte[] buf;
    protected int count;

    public ByteArrayOutputStream() {
        this(32);
    }

    public ByteArrayOutputStream(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial size: " + size);
        }
        buf = new byte[size];
    }

    private void ensure(int needed) {
        if (needed < 0) {
            throw new OutOfMemoryError();
        }
        if (needed > buf.length) {
            int grown = Math.max(needed, buf.length * 2);
            if (grown < 0) {
                grown = Integer.MAX_VALUE - 8;
            }
            byte[] bigger = new byte[grown];
            System.arraycopy(buf, 0, bigger, 0, count);
            buf = bigger;
        }
    }

    public synchronized void write(int b) {
        ensure(count + 1);
        buf[count++] = (byte) b;
    }

    public synchronized void write(byte[] b, int off, int len) {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        ensure(count + len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public synchronized void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    public synchronized void reset() {
        count = 0;
    }

    public synchronized byte[] toByteArray() {
        byte[] copy = new byte[count];
        System.arraycopy(buf, 0, copy, 0, count);
        return copy;
    }

    public synchronized int size() {
        return count;
    }

    /** Decodes the bytes as UTF-8, the platform charset of LazyVM. */
    public synchronized String toString() {
        return new String(buf, 0, count);
    }

    public synchronized String toString(String charsetName) throws UnsupportedEncodingException {
        byte[] copy = toByteArray();
        return new String(copy, charsetName);
    }

    public void close() throws IOException {
    }
}
