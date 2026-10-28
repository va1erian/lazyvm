package java.io;

/** A buffering output stream (io.md B6). */
public class BufferedOutputStream extends FilterOutputStream {
    protected byte[] buf;
    protected int count;

    public BufferedOutputStream(OutputStream out) {
        this(out, 8192);
    }

    // Spec B1: 8192 by default; a size <= 0 is refused.
    public BufferedOutputStream(OutputStream out, int size) {
        super(out);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
    }

    private void drain() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    public synchronized void write(int b) throws IOException {
        if (count >= buf.length) {
            drain();
        }
        buf[count++] = (byte) b;
    }

    // Spec B6: a write of at least the buffer's size goes straight through.
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if (len >= buf.length) {
            drain();
            out.write(b, off, len);
            return;
        }
        // Observed: no bounds check of its own; the copy below throws for a bad range.
        if (len > buf.length - count) {
            drain();
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public synchronized void flush() throws IOException {
        drain();
        out.flush();
    }
}
